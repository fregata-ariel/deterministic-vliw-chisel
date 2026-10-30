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
    // Every gather and pair field is aligned to its kind; W-reg has no LD.W and no ST.W.
    d.nodes.foreach(n => n.sources.foreach(_.nodes.foreach(o => assert(o < d.nodes.size, label))))
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

  test("matmul3 under rule 1 as written: (c0,c1) .. (c6,c7) with gathers, c8 scalar (Q1)") {
    val p = packed(Benchmarks.matmul(3), full)
    // a at 0..8, b at 9..17, c at 18..26. (c2, c3) = (c(0,2), c(1,0)) gathers a(0,m), a(1,m)
    // and b(m,2), b(m,0); the other pairs broadcast a(i,m) and read b(m,j), b(m,j+1) by LD.W.
    assert(p.pairs.map(_.addr) == Vector(18, 20, 22, 24))
    assert(p.scalarLanes == Vector(ScalarLane(26, LaneReason.NoNeighbour)))
    assert(p.count("LD.W") == 6 && p.count("LD") == 15 && p.count("ST.W") == 4)
    assert(p.count("ST") == 1 && p.gathers == 6 && p.dag.resourceLowerBound == 27)
    assert(p.count("VMUL") == 4 && p.count("VMAC") == 8 && p.count("MUL") == 1)
    assert(p.count("MAC") == 2)
    checkPacking(p, "matmul3")
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
