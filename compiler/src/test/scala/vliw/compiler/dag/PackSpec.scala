package vliw.compiler.dag

import java.nio.file.Paths

import scala.util.Random

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.KernelCompiler
import vliw.compiler.dsl._
import vliw.compiler.dsl.LoweredKernel.{Bin, Read, Value, Write}
import vliw.isa.MachineDescription

/** Packing into W pairs (ISA mvp-2, #110 stage 4; ADR-0023, docs/COMPILER.md "ISA mvp-2（W）"). The
  * expected node lists are worked out by hand from the rules; the meaning of the packed DAG is
  * checked by running it (`DagRun`, written from the node definitions, not from `Pack`).
  */
class PackSpec extends AnyFunSuite {
  private def load(path: String*) = MachineDescription
    .load(Paths.get(path.head, path.tail: _*))
    .fold(es => fail(es.mkString("\n")), identity)
  private val full = load("config", "machine-mvp2.json")
  private val mw1 = load("tests", "machines", "mvp2_mw1.json")

  private def r(a: Int): Value = Read(a)
  private def add(a: Value, b: Value): Value = Bin(Op.Add, a, b)
  private def mul(a: Value, b: Value): Value = Bin(Op.Mul, a, b)
  private def sub(a: Value, b: Value): Value = Bin(Op.Sub, a, b)
  private def pack(m: MachineDescription, ws: Write*) =
    Pack.pack(Fusion.fuse(Dag.build(m, ws.toVector)))
  private def prepared(k: Kernel, m: MachineDescription) =
    KernelCompiler.prepare(k, m).fold(e => fail(e), identity)
  private def packed(k: Kernel, m: MachineDescription) =
    prepared(k, m).packing.getOrElse(fail(s"${m.machineId} packs nothing"))
  private def edgeSet(d: Dag) = d.edges.map(e => (e.from, e.to, e.distance, e.kind)).toSet

  /** Data edges follow the operands with the producer's latency, ids are topological, the outputs
    * are partitioned into pairs and scalar lanes, and the stores write the words of the fused DAG.
    */
  private def checkPacking(p: Packing, label: String): Unit = {
    val d = p.dag
    val data = d.nodes.indices.flatMap { i =>
      d.nodes(i).operands.distinct.map(o => (o, i, d.latency(o), EdgeKind.Data: EdgeKind))
    }
    assert(edgeSet(d).filter(_._4 == EdgeKind.Data) == data.toSet, label)
    assert(d.edges.size == d.edges.distinct.size && d.edges.forall(e => e.from < e.to), label)
    assert(DagRun.storedWords(d) == DagRun.storedWords(p.scalar), label)
    val words = p.scalar.nodes.collect { case Node.Store(a, _) => a }.distinct.sorted
    val inPairs = p.pairs.flatMap(x => Seq(x.addr, x.addr + 1))
    assert((inPairs ++ p.scalarLanes.map(_.addr)).sorted == words, label)
    assert(p.pairs.map(_.addr) == p.pairs.map(_.addr).sorted, label)
    // Every field reads what its kind needs: a pair field a pair node; a gather two different
    // scalar values; a broadcast one scalar value, as rs1 of VMUL or VMAC only (rule 2). A scalar
    // operation reads scalar values, and a lane ST lane 0 or 1 of a pair node. W-reg has no LD.W
    // and no ST.W.
    def scalarValue(o: Int) = !d.nodes(o).isPair && !d.nodes(o).isStore
    d.nodes.zipWithIndex.foreach { case (n, i) =>
      val at = s"$label: node $i $n"
      val multiply = n match {
        case Node.VCompute(Op.Mul, _, _) | _: Node.VMac => true
        case _                                          => false
      }
      n.sources.zipWithIndex.foreach {
        case (Src.Pair(o), _)        => assert(d.nodes(o).isPair, at)
        case (Src.Gather(lo, hi), _) => assert(lo != hi && scalarValue(lo) && scalarValue(hi), at)
        case (Src.Broadcast(o), k)   => assert(scalarValue(o) && multiply && k == 0, at)
      }
      n match {
        case Node.StoreLane(_, pair, lane) =>
          assert(d.nodes(pair).isPair && lane >= 0 && lane < 2, at)
        case _: Node.Compute | _: Node.Mac | _: Node.Store =>
          assert(n.operands.forall(scalarValue), at)
        case _ => ()
      }
    }
    if (!d.machine.hasWideMemory)
      assert(!d.nodes.exists(n => n.mnemonic == "LD.W" || n.mnemonic == "ST.W"), label)
    // One group per value: every node but the stores is in exactly one (Dag requires the gathers
    // to give one home per node).
    val definers = d.groups.flatMap(_.definers).sorted
    assert(definers == d.nodes.indices.filterNot(d.nodes(_).isStore), label)
  }

  test("saxpy8 on W-full: LD a, 8 LD.W, 4 VMAC broadcasting a, 4 ST.W at odd words") {
    val p = packed(Benchmarks.saxpy(8), full)
    // x at 0..7, y at 8..15, a at 16, out at 17..24 (fixedOrder).
    val expected = Node.Load(16) +: (0 until 4).flatMap { k =>
      Seq(
        Node.LoadW(2 * k),
        Node.LoadW(8 + 2 * k),
        Node.VMac(Src.Broadcast(0), Src.Pair(1 + 4 * k), Src.Pair(2 + 4 * k)),
        Node.StoreW(17 + 2 * k, Src.Pair(3 + 4 * k))
      )
    }
    assert(p.dag.nodes == expected.toVector)
    assert(p.pairs == (0 until 4).map(k => PackedPair(17 + 2 * k, Src.Pair(3 + 4 * k))).toVector)
    assert(p.scalarLanes.isEmpty && p.copies == 0 && !p.nothingToPack)
    val edges = (0 until 4).flatMap { k =>
      val v = 3 + 4 * k
      Seq((0, v), (v - 2, v), (v - 1, v), (v, v + 1)).map { case (f, t) =>
        (f, t, 2, EdgeKind.Data: EdgeKind)
      }
    }
    assert(edgeSet(p.dag) == edges.toSet)
    // LD 0, VMAC 2, ST.W 4, done at 5; 13 memory operations and HALT.
    assert(p.dag.cyclesLowerBound == 6 && p.dag.resourceLowerBound == 14)
    assert(p.count("VMAC") == 4 && p.count("LD.W") == 8 && p.count("ST.W") == 4)
    assert(p.broadcasts == 1 && p.gathers == 0 && p.dag.loads == 9)
    checkPacking(p, "saxpy8")
    // Packing is deterministic.
    assert(Pack.pack(p.scalar) == p)
  }

  test("saxpy8 on W-reg: 17 LD (a and 8 gathers), 4 VMAC, 8 lane STs") {
    val p = packed(Benchmarks.saxpy(8), mw1)
    val expected = Node.Load(16) +: (0 until 4).flatMap { k =>
      val b = 1 + 7 * k
      Seq(
        Node.Load(2 * k),
        Node.Load(8 + 2 * k),
        Node.Load(2 * k + 1),
        Node.Load(9 + 2 * k),
        Node.VMac(Src.Broadcast(0), Src.Gather(b, b + 2), Src.Gather(b + 1, b + 3)),
        Node.StoreLane(17 + 2 * k, b + 4, 0),
        Node.StoreLane(18 + 2 * k, b + 4, 1)
      )
    }
    assert(p.dag.nodes == expected.toVector)
    assert(p.pairs == (0 until 4).map(k => PackedPair(17 + 2 * k, Src.Pair(5 + 7 * k))).toVector)
    assert(p.dag.resourceLowerBound == 26 && p.dag.cyclesLowerBound == 6)
    assert(p.gathers == 8 && p.laneStores == 8 && p.copies == 0 && p.dag.loads == 17)
    checkPacking(p, "saxpy8 W-reg")
    assert(Pack.pack(p.scalar) == p)
  }

  test("saxpy7: three pairs and the scalar lane out(6), rule 1; saxpy1 has nothing to pack") {
    // x at 0..6, y at 7..13, a at 14, out at 15..21: LD.W at the odd words 7, 9 and 11.
    val f = packed(Benchmarks.saxpy(7), full)
    assert(f.pairs.map(_.addr) == Vector(15, 17, 19))
    assert(f.scalarLanes == Vector(ScalarLane(21, LaneReason.NoNeighbour)))
    assert(f.dag.nodes.collect { case Node.LoadW(a) => a } == Vector(0, 7, 2, 9, 4, 11))
    assert(
      f.dag.nodes.takeRight(4) == Vector(
        Node.Load(6),
        Node.Load(13),
        Node.Mac(0, 13, 14),
        Node.Store(21, 15)
      )
    )
    assert(f.count("VMAC") == 3 && f.count("MAC") == 1 && f.dag.resourceLowerBound == 14)
    val w = packed(Benchmarks.saxpy(7), mw1)
    assert(w.scalarLanes == Vector(ScalarLane(21, LaneReason.NoNeighbour)))
    assert(w.count("LD") == 15 && w.count("ST") == 7 && w.dag.resourceLowerBound == 23)
    Seq(f, w).foreach(checkPacking(_, "saxpy7"))
    for (m <- Seq(full, mw1)) {
      val one = packed(Benchmarks.saxpy(1), m)
      assert(one.nothingToPack && one.scalarLanes == Vector(ScalarLane(3, LaneReason.NoNeighbour)))
      // Nothing packed: the identity order.
      assert(one.dag.nodes == one.scalar.nodes && edgeSet(one.dag) == edgeSet(one.scalar))
    }
  }

  test("saxpy(1..9) and fir4x(1..9): n / 2 pairs, the last word scalar for odd n; FIR windows") {
    for (m <- Seq(full, mw1); n <- 1 to 9) {
      for (k <- Seq(Benchmarks.saxpy(n), Benchmarks.fir(Seq(1L, 2L, 3L, 4L), n))) {
        val prep = prepared(k, m)
        val p = prep.packing.get
        val out0 = prep.layout.elem("out", 0)
        val label = s"${k.name} on ${m.machineId}"
        // Every candidate is gather-free: pass 1 tries them all by address (and pairs as one pass).
        val candidates = (out0 until out0 + n - 1).toVector
        assert(Pack.storePairs(p.scalar) == candidates ++ candidates, label)
        assert(p.pairs.map(_.addr) == (0 until n / 2).map(i => out0 + 2 * i).toVector, label)
        val odd = Option.when(n % 2 == 1)(ScalarLane(out0 + n - 1, LaneReason.NoNeighbour))
        assert(p.scalarLanes == odd.toVector, label)
        checkPacking(p, label)
      }
      // The window (x(j), x(j+1)) of every output pair, shared by value numbering (rule 6): one
      // LD.W on W-full, one gather on W-reg, where a word in two windows is copied for one.
      val fir = packed(Benchmarks.fir(Seq(1L, 2L, 3L, 4L), n), m)
      val starts = (for (i <- 0 until n / 2; t <- 0 until 4) yield 2 * i + t).toSet
      if (m.hasWideMemory) assert(fir.count("LD.W") == starts.size && fir.gathers == 0, n)
      else {
        assert(fir.gathers == starts.size && fir.count("LD.W") == 0, n)
        assert(fir.copies == starts.count(j => starts(j - 1)), n)
      }
    }
    // fir4x8: VMUL 4 and VMAC 12 broadcasting h; 10 windows; W-reg: 20 window LDs, bound 33.
    val f = packed(Benchmarks.fir(Seq(1L, 2L, 3L, 4L), 8), full)
    assert(f.count("VMUL") == 4 && f.count("VMAC") == 12 && f.broadcasts == 4)
    assert(f.count("LD.W") == 10 && f.dag.resourceLowerBound == 19)
    val w = packed(Benchmarks.fir(Seq(1L, 2L, 3L, 4L), 8), mw1)
    assert(w.gathers == 10 && w.copies == 9 && w.count("LD") == 24)
    assert(w.dag.resourceLowerBound == 33)
  }

  test("matmul3, rule 1 in two passes (Q1 (b)): the row pairs (c(i,0), c(i,1)), c(i,2) scalar") {
    // a at 0..8, b at 9..17, c at 18..26. Pass 1 tries the gather-free candidates: in each row
    // (c(i,0), c(i,1)) and (c(i,1), c(i,2)), which broadcast a(i,m) and read b(m,j), b(m,j+1).
    // The lower one pairs, so (c(i,1), c(i,2)) is skipped. The cross-row candidates (c(i,2),
    // c(i+1,0)) need gathers of a(i,m), a(i+1,m) and b(m,2), b(m,0), and pass 2 skips them: c(i+1,0)
    // is already paired. Rule 1 as written (one pass, Q1 (a)) pairs (c0,c1) .. (c6,c7) instead,
    // with 6 gathers on W-full (26 Memory operations, slot bound 27) and c8 scalar.
    val f = packed(Benchmarks.matmul(3), full)
    assert(Pack.storePairs(f.scalar) == Vector(18, 19, 21, 22, 24, 25) ++ (18 to 25))
    val lanes = Vector(20, 23, 26).map(ScalarLane(_, LaneReason.NoNeighbour))
    assert(f.pairs.map(_.addr) == Vector(18, 21, 24) && f.scalarLanes == lanes)
    assert(f.dag.nodes.collect { case Node.LoadW(a) => a }.sorted == Vector(9, 12, 15))
    assert(f.dag.nodes.collect { case Node.StoreW(a, _) => a }.sorted == Vector(18, 21, 24))
    assert(f.dag.nodes.collect { case Node.Store(a, _) => a }.sorted == Vector(20, 23, 26))
    assert(f.count("LD") == 12 && f.count("LD.W") == 3 && f.gathers == 0 && f.broadcasts == 9)
    assert(f.count("VMUL") == 3 && f.count("VMAC") == 6 && f.count("MUL") == 3)
    assert(f.count("MAC") == 6 && f.count("ST.W") == 3 && f.count("ST") == 3)
    assert(f.dag.resourceLowerBound == 22 && f.dag.cyclesLowerBound == 10)
    // W-reg pairs the same rows. Each gather (b(m,0), b(m,1)) is read by the 3 rows (rule 6).
    val w = packed(Benchmarks.matmul(3), mw1)
    assert(w.pairs.map(_.addr) == Vector(18, 21, 24) && w.scalarLanes == lanes)
    val laneStores = w.dag.nodes.collect { case Node.StoreLane(a, _, _) => a }.sorted
    assert(laneStores == Vector(18, 19, 21, 22, 24, 25))
    assert(w.count("LD") == 18 && w.count("ST") == 9 && w.laneStores == 6 && w.copies == 0)
    assert(w.count("VMUL") == 3 && w.count("VMAC") == 6 && w.count("MUL") == 3)
    assert(w.count("MAC") == 6 && w.dag.resourceLowerBound == 28)
    assert(
      w.dag.gathers.map(g => (w.dag.nodes(g.lo), w.dag.nodes(g.hi))).toSet ==
        (0 until 3).map(m => (Node.Load(9 + 3 * m), Node.Load(10 + 3 * m))).toSet
    )
    assert(w.gathers == 3)
    w.dag.gathers.foreach(g => assert(w.dag.nodes.count(_.sources.contains(g)) == 3, g))
    Seq(f, w).foreach(checkPacking(_, "matmul3"))
  }

  test("rule 1's two passes: pass 1 pairs a gather-free candidate first; pairs by address") {
    // [10] = [0] + [2]; [11] = [3] + [4]; [12] = [4] + [5]: only (11, 12) is gather-free, so
    // pass 1 pairs it and (10, 11) is skipped. Rule 1 as written would pair (10, 11), with two
    // gathers, and leave 12 scalar.
    val differ = Seq(
      Write(10, add(r(0), r(2))),
      Write(11, add(r(3), r(4))),
      Write(12, add(r(4), r(5)))
    )
    for (m <- Seq(full, mw1)) {
      val p = pack(m, differ: _*)
      assert(Pack.storePairs(p.scalar) == Vector(11, 10, 11), m.machineId)
      assert(p.pairs.map(_.addr) == Vector(11), m.machineId)
      assert(p.scalarLanes == Vector(ScalarLane(10, LaneReason.NoNeighbour)), m.machineId)
      checkPacking(p, s"passes differ on ${m.machineId}")
    }
    // W-full: LD.W [3] and LD.W [4]. W-reg: the gathers (ld 3, ld 4) and (ld 4, ld 5), with a copy
    // of ld 4 for the second.
    val f = pack(full, differ: _*)
    assert(f.dag.nodes.collect { case Node.LoadW(a) => a }.sorted == Vector(3, 4))
    assert(f.gathers == 0)
    val w = pack(mw1, differ: _*)
    assert(w.gathers == 2 && w.copies == 1 && w.dag.nodes.count(_ == Node.Load(4)) == 2)
    // [10] = [0] + [5]; [11] = [3] + [6]; [12] = [1] * [2]: no candidate is gather-free, so pass
    // 1 is empty and the pairs are those of rule 1 as written: (10, 11), with a gather.
    val empty = Seq(
      Write(10, add(r(0), r(5))),
      Write(11, add(r(3), r(6))),
      Write(12, mul(r(1), r(2)))
    )
    for (m <- Seq(full, mw1)) {
      val p = pack(m, empty: _*)
      assert(Pack.storePairs(p.scalar) == Vector(10, 11), m.machineId)
      assert(p.pairs.map(_.addr) == Vector(10) && p.gathers >= 1, m.machineId)
      assert(p.scalarLanes == Vector(ScalarLane(12, LaneReason.NoNeighbour)), m.machineId)
      checkPacking(p, s"pass 1 empty on ${m.machineId}")
    }
    // [10] = [0] + [5]; [11] = [3] + [6]; [12] = [1] + [8]; [13] = [2] + [9]: pass 1 accepts
    // (12, 13), then pass 2 accepts (10, 11). `pairs` is by address, as rule 1 as written gives.
    val late = Seq(
      Write(10, add(r(0), r(5))),
      Write(11, add(r(3), r(6))),
      Write(12, add(r(1), r(8))),
      Write(13, add(r(2), r(9)))
    )
    for (m <- Seq(full, mw1)) {
      val p = pack(m, late: _*)
      assert(Pack.storePairs(p.scalar) == Vector(12, 10, 11, 12), m.machineId)
      assert(p.pairs.map(_.addr) == Vector(10, 12) && p.scalarLanes.isEmpty, m.machineId)
      checkPacking(p, s"pairs by address on ${m.machineId}")
    }
  }

  test(
    "Pack.gatherFree: the same operation at the root, broadcasts at rs1, adjacent unwritten LDs"
  ) {
    def free(ws: Write*) = Pack.gatherFree(pack(full, ws: _*).scalar, 10)
    // saxpy8 (out at 17..24): a broadcast at the MAC's rs1, x and y adjacent.
    val saxpy = prepared(Benchmarks.saxpy(8), full).dag
    assert((17 to 23).forall(Pack.gatherFree(saxpy, _)))
    // [10] = [0] * [5]; [11] = [1] * [5]: the multiplicand equal in both lanes moves to rs1.
    assert(free(Write(10, mul(r(0), r(5))), Write(11, mul(r(1), r(5)))))
    // Not gather-free: a copy of two LDs (no operation at the root); the same value outside a
    // multiplication's rs1; adjacent LDs of a word that is written; LDs in the other lane order;
    // different operations; a word stored twice.
    assert(!free(Write(10, r(0)), Write(11, r(1))))
    assert(!free(Write(10, add(r(0), r(5))), Write(11, add(r(1), r(5)))))
    assert(!free(Write(10, add(r(0), r(2))), Write(11, add(r(1), r(3))), Write(0, r(4))))
    assert(!free(Write(10, add(r(1), r(2))), Write(11, add(r(0), r(3)))))
    assert(!free(Write(10, add(r(0), r(2))), Write(11, sub(r(1), r(3)))))
    assert(!free(Write(10, add(r(0), r(2))), Write(10, r(4)), Write(11, add(r(1), r(3)))))
  }

  test("rule 2: the same value in both lanes is a broadcast only at a multiplication's rs1") {
    // [10] = [0] * [2] + [5]; [11] = [1] * [3] + [5]: the same addend at the MAC's rs3.
    val rs3 =
      pack(full, Write(10, add(mul(r(0), r(2)), r(5))), Write(11, add(mul(r(1), r(3)), r(5))))
    assert(rs3.nothingToPack)
    assert(rs3.scalarLanes.map(_.reason).distinct == Vector(LaneReason.SameValue("rs3 of MAC")))
    // [10] = [11] = [0] + [1]: the same node at both store sources.
    val st = pack(full, Write(10, add(r(0), r(1))), Write(11, add(r(0), r(1))))
    assert(
      st.scalarLanes.map(_.reason).distinct == Vector(LaneReason.SameValue("the store source"))
    )
    // [10] = [0] * [5]; [11] = [1] * [5]: the multiplicand equal in both lanes moves to rs1.
    val swap = pack(full, Write(10, mul(r(0), r(5))), Write(11, mul(r(1), r(5))))
    val ld5 = swap.dag.nodes.indexOf(Node.Load(5))
    val ldw = swap.dag.nodes.indexOf(Node.LoadW(0))
    assert(swap.dag.nodes.contains(Node.VCompute(Op.Mul, Src.Broadcast(ld5), Src.Pair(ldw))))
    // [10] = [0] + [5]; [11] = [1] + [5]: no broadcast for ADD.
    val noAdd = pack(full, Write(10, add(r(0), r(5))), Write(11, add(r(1), r(5))))
    assert(noAdd.scalarLanes.map(_.reason).distinct == Vector(LaneReason.SameValue("rs2 of ADD")))
    Seq(rs3, st, swap, noAdd).foreach(checkPacking(_, "rule 2"))
  }

  test("rules 3 and 4: LD.W for adjacent unwritten words, gathers otherwise; LD copies") {
    // [10] = [0] + [2]; [11] = [1] + [3]; [0] = [4]: word 0 is written, so (ld 0, ld 1) is a
    // gather of the LD nodes (with the LD -> ST edge kept), and (ld 2, ld 3) a LD.W.
    val written =
      pack(full, Write(10, add(r(0), r(2))), Write(11, add(r(1), r(3))), Write(0, r(4)))
    val d = written.dag
    val (ld0, ld1) = (d.nodes.indexOf(Node.Load(0)), d.nodes.indexOf(Node.Load(1)))
    val v = d.nodes.indexWhere(_.isInstanceOf[Node.VCompute])
    assert(
      d.nodes(v) == Node.VCompute(
        Op.Add,
        Src.Gather(ld0, ld1),
        Src.Pair(d.nodes.indexOf(Node.LoadW(2)))
      )
    )
    val st0 = d.nodes.indexWhere { case Node.Store(0, _) => true; case _ => false }
    assert(d.edges.contains(Edge(ld0, st0, 0, EdgeKind.LoadToStore)))
    assert(written.pairs.map(_.addr) == Vector(10))
    // [10] = [0] + [5]; [11] = [3] + [6]: non-adjacent words are gathered.
    val far = pack(full, Write(10, add(r(0), r(5))), Write(11, add(r(3), r(6))))
    assert(far.gathers == 1 && far.count("LD.W") == 1)
    // [10] = [0] + [1]; [11] = [2] + [0]: ld 0 is lane 0 of one gather and lane 1 of the other,
    // so the second gets a copy of it.
    val copy = pack(full, Write(10, add(r(0), r(1))), Write(11, add(r(2), r(0))))
    assert(copy.copies == 1 && copy.gathers == 2 && copy.dag.nodes.count(_ == Node.Load(0)) == 2)
    // A computed node in two gathers is not packed: [10] = m + [2]; [11] = [3] + m, m = [0] * [1].
    val twice =
      pack(full, Write(10, add(mul(r(0), r(1)), r(2))), Write(11, add(r(3), mul(r(0), r(1)))))
    val m = twice.scalar.nodes.indexOf(Node.Compute(Op.Mul, 0, 1))
    assert(twice.nothingToPack)
    assert(twice.scalarLanes.map(_.reason).distinct == Vector(LaneReason.NeededElsewhere(m)))
    Seq(written, far, copy, twice).foreach(checkPacking(_, "rules 3 and 4"))
  }

  test("rule 4: a computed lane with a scalar reader is not packed; W-full stores a root gather") {
    // [10] = [0] + [1]; [11] = [2] + [3]; [12] = ([0] + [1]) * [4]. (10, 11) would make [0] + [1]
    // a vector lane, but [12] reads it (not walked yet: a scalar). (11, 12) differ at the root:
    // W-full stores the gather of the two values by ST.W, W-reg cannot.
    val ws = Seq(
      Write(10, add(r(0), r(1))),
      Write(11, add(r(2), r(3))),
      Write(12, mul(add(r(0), r(1)), r(4)))
    )
    val f = pack(full, ws: _*)
    val sum = f.scalar.nodes.indexOf(Node.Compute(Op.Add, 0, 1))
    assert(f.scalarLanes == Vector(ScalarLane(10, LaneReason.NeededElsewhere(sum))))
    assert(f.pairs.map(_.addr) == Vector(11) && f.pairs.head.root.isInstanceOf[Src.Gather])
    assert(f.count("ST.W") == 1 && f.vectorOperations == 0)
    val w = pack(mw1, ws: _*)
    assert(
      w.scalarLanes == Vector(
        ScalarLane(10, LaneReason.NeededElsewhere(sum)),
        ScalarLane(11, LaneReason.NeededElsewhere(sum)),
        ScalarLane(12, LaneReason.RootDiffers)
      )
    )
    assert(w.nothingToPack)
    // [10] = [0] + [1]; [11] = [2] * [3]: different operations at the root.
    val root = Seq(Write(10, add(r(0), r(1))), Write(11, mul(r(2), r(3))))
    assert(pack(full, root: _*).dag.nodes.exists(_.isInstanceOf[Node.StoreW]))
    assert(pack(mw1, root: _*).scalarLanes.map(_.reason).distinct == Vector(LaneReason.RootDiffers))
    Seq(f, w, pack(full, root: _*)).foreach(checkPacking(_, "rule 4"))
  }

  test("rule 6 across store pairs: two pairs that would share a computed vector both stay scalar") {
    // [10] = ([0] + [2]) - [4]; [11] = ([1] + [3]) - [5]; [12] = ([0] + [2]) * [6];
    // [13] = ([1] + [3]) * [7] (no MUL + ADD to fuse). Rule 6 alone would share one VADD between
    // the pairs (10, 11) and (12, 13). (10, 11) is not packed: [12]'s MUL reads [0] + [2] and is not
    // walked yet (a scalar). (12, 13) is not packed either: [10]'s SUB is now a scalar reader.
    // Both are gather-free, so pass 1 tries (10, 11) and then (12, 13), and their words take the
    // reason of that first rejection. Pass 2 tries (10, 11) and (11, 12), and on W-reg (12, 13).
    val ws = Seq(
      Write(10, sub(add(r(0), r(2)), r(4))),
      Write(11, sub(add(r(1), r(3)), r(5))),
      Write(12, mul(add(r(0), r(2)), r(6))),
      Write(13, mul(add(r(1), r(3)), r(7)))
    )
    val w = pack(mw1, ws: _*)
    assert(w.scalar.nodes.count(_.mnemonic == "ADD") == 2) // value numbering
    def ld(a: Int) = w.scalar.nodes.indexOf(Node.Load(a))
    val sum = w.scalar.nodes.indexOf(Node.Compute(Op.Add, ld(0), ld(2)))
    assert(sum >= 0)
    val elsewhere = LaneReason.NeededElsewhere(sum)
    assert(Pack.storePairs(w.scalar) == Vector(10, 12, 10, 11, 12))
    // W-reg: (11, 12) would be `RootDiffers` in pass 2, but its words were rejected in pass 1.
    assert(w.scalarLanes == (10 to 13).map(ScalarLane(_, elsewhere)).toVector)
    assert(w.nothingToPack)
    // W-full packs only (11, 12) in pass 2, as the ST.W of a root gather: no vector operation.
    val f = pack(full, ws: _*)
    assert(f.pairs.map(_.addr) == Vector(11) && f.pairs.head.root.isInstanceOf[Src.Gather])
    assert(f.scalarLanes == Vector(ScalarLane(10, elsewhere), ScalarLane(13, elsewhere)))
    assert(f.vectorOperations == 0)
    Seq(w, f).foreach(checkPacking(_, "rule 6 across store pairs"))
  }

  test("a pair that would make a cycle, and a word stored twice, are not packed") {
    // [10] = [0] + [1]; [11] = [10] + [2]: lane 1 reads word 10 after its ST, so ST.W [10] (or the
    // lane ST of W-reg) would come before and after the vector operation.
    for (m <- Seq(full, mw1)) {
      val c = pack(m, Write(10, add(r(0), r(1))), Write(11, add(r(10), r(2))))
      assert(c.scalarLanes.map(_.reason).distinct == Vector(LaneReason.Cycle), m.machineId)
      checkPacking(c, "cycle")
    }
    // [10] = [0]; [10] = [1]; [11] = [2]: word 10 is stored twice.
    val twice = pack(full, Write(10, r(0)), Write(10, r(1)), Write(11, r(2)))
    assert(
      twice.scalarLanes == Vector(
        ScalarLane(10, LaneReason.StoredTwice),
        ScalarLane(11, LaneReason.StoredTwice)
      )
    )
    assert(twice.dag.edges.exists(_.kind == EdgeKind.StoreToStore))
    checkPacking(twice, "stored twice")
  }

  /** Random writes that read and write the same 6 words (as FusionSpec), so that adjacent words are
    * stored and read back.
    */
  private def randomWrites(rng: Random): Vector[Write] = {
    def value(depth: Int): Value =
      if (depth == 0 || rng.nextInt(4) == 0) r(rng.nextInt(6))
      else
        rng.nextInt(5) match {
          case 0 | 1 => mul(value(depth - 1), value(depth - 1))
          case 2 | 3 => add(value(depth - 1), value(depth - 1))
          case _     => Bin(Op.Sub, value(depth - 1), value(depth - 1))
        }
    Vector.fill(1 + rng.nextInt(5))(Write(rng.nextInt(6), value(1 + rng.nextInt(3))))
  }

  test("random writes with aliasing: the packed DAG gives the same memory in any order") {
    val rng = new Random(110L)
    val seen = collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    for (t <- 0 until 500; m <- Seq(full, mw1)) {
      val ws = randomWrites(rng)
      val p = pack(m, ws: _*)
      val label = s"$ws on ${m.machineId}"
      checkPacking(p, label)
      p.scalarLanes.foreach(l => seen(l.reason.name) += 1)
      seen("pairs") += p.pairs.size
      (0 until 3).foreach { _ =>
        val memory = (0 until 6).map(_ -> (rng.nextLong() & Word.Mask)).toMap
        val expected = ws.foldLeft(memory) { (mem, w) =>
          def eval(v: Value): Long = v match {
            case Read(a)       => mem(a)
            case Bin(op, a, b) => op(eval(a), eval(b))
          }
          mem.updated(w.addr, eval(w.value))
        }
        assert(DagRun.execute(p.dag, DagRun.randomOrder(p.dag, rng), memory) == expected, label)
      }
      assert(t >= 0)
    }
    info(seen.toSeq.sorted.map { case (k, v) => s"$k $v" }.mkString(", "))
    Seq(
      "pairs",
      "NoNeighbour",
      "SameValue",
      "NeededElsewhere",
      "Cycle",
      "StoredTwice",
      "RootDiffers"
    ).foreach(k => assert(seen(k) > 0, k))
  }

  test("random DSL kernels and the benchmarks: the packed DAG computes the kernel") {
    val rng = new Random(4878L)
    val kernels = Benchmarks.all ++ RandomKernels.kernels(51L, 400)
    var pairs = 0
    for (m <- Seq(full, mw1); k <- kernels) {
      val prep = prepared(k, m)
      val p = prep.packing.get
      val label = s"${k.name} on ${m.machineId}"
      assert(p.scalar == prep.dag, label)
      checkPacking(p, label)
      pairs += p.pairs.size
      val lowered = Lowering.lower(prep.layout)
      val image = prep.layout.memoryImage(RandomKernels.values(k, rng)).fold(e => fail(e), identity)
      assert(
        DagRun.execute(p.dag, DagRun.randomOrder(p.dag, rng), image) ==
          Lowering.evaluate(lowered, image),
        label
      )
    }
    assert(pairs > 300, pairs)
  }

  test("mvp-0 and mvp-1 machines are not packed") {
    for (m <- Seq(MachineDescription.load().toOption.get, load("config", "machine-mvp1.json")))
      assert(Benchmarks.all.forall(k => prepared(k, m).packing.isEmpty))
  }
}
