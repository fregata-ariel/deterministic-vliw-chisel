package vliw.compiler.dag

import java.nio.file.Paths

import scala.util.Random

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.KernelCompiler
import vliw.compiler.dsl._
import vliw.compiler.dsl.LoweredKernel.{Bin, Read, Value, Write}
import vliw.isa.MachineDescription

/** Fusion of MUL + ADD into MAC on ISA mvp-1 (ADR-0019, #77). */
class FusionSpec extends AnyFunSuite {
  private def load(path: String*) = MachineDescription
    .load(Paths.get(path.head, path.tail: _*))
    .fold(es => fail(es.mkString("\n")), identity)
  private val mvp0 = MachineDescription.load().fold(es => fail(es.mkString("\n")), identity)
  private val mvp1 = load("config", "machine-mvp1.json")
  private val rp4 = load("tests", "machines", "mvp1_rp4.json")
  private val mac3 = load("tests", "machines", "mvp1_mac3.json")

  private def mul(a: Value, b: Value): Value = Bin(Op.Mul, a, b)
  private def add(a: Value, b: Value): Value = Bin(Op.Add, a, b)
  private def r(a: Int): Value = Read(a)
  private def edgeSet(d: Dag) = d.edges.map(e => (e.from, e.to, e.distance, e.kind)).toSet
  private def fused(m: MachineDescription, ws: Write*) = Fusion.fuse(Dag.build(m, ws.toVector))

  /** Executes the DAG in `order`, which must respect its edges; fails on an operand not run yet. */
  private def execute(d: Dag, order: Seq[Int], memory: Map[Int, Long]): Map[Int, Long] = {
    val values = new Array[Long](d.nodes.size)
    val done = new Array[Boolean](d.nodes.size)
    def v(i: Int, o: Int) = { assert(done(o), s"node $i runs before its operand $o"); values(o) }
    order.foldLeft(memory) { (mem, i) =>
      val after = d.nodes(i) match {
        case Node.Load(a)           => values(i) = mem(a); mem
        case Node.Compute(op, a, b) => values(i) = op(v(i, a), v(i, b)); mem
        case Node.Mac(a, b, c)      => values(i) = Op.Add(Op.Mul(v(i, a), v(i, b)), v(i, c)); mem
        case Node.Store(a, x)       => mem.updated(a, v(i, x))
      }
      done(i) = true
      after
    }
  }

  /** A random order that respects every edge of `d`. */
  private def randomOrder(d: Dag, rng: Random): Vector[Int] = {
    val indegree = Array.fill(d.nodes.size)(0)
    d.edges.foreach(e => indegree(e.to) += 1)
    val ready = scala.collection.mutable.ArrayBuffer.from(d.nodes.indices.filter(indegree(_) == 0))
    val order = Vector.newBuilder[Int]
    while (ready.nonEmpty) {
      val n = ready.remove(rng.nextInt(ready.size))
      order += n
      d.successors(n).foreach { e =>
        indegree(e.to) -= 1
        if (indegree(e.to) == 0) ready += e.to
      }
    }
    order.result()
  }

  /** The memory-order edges of the nodes in id order, by the rule of docs/COMPILER.md ("辺"). */
  private def memoryOrder(d: Dag): Set[(Int, Int, Int, EdgeKind)] = {
    val lastStore = scala.collection.mutable.Map.empty[Int, Int]
    val loads = scala.collection.mutable.Map.empty[Int, Vector[Int]].withDefaultValue(Vector.empty)
    val st = d.machine.operation("ST").latency
    d.nodes.zipWithIndex.flatMap {
      case (Node.Load(a), i) =>
        loads(a) :+= i
        lastStore.get(a).map(s => (s, i, st, EdgeKind.StoreToLoad: EdgeKind)).toSeq
      case (Node.Store(a, _), i) =>
        val e = loads(a).map(l => (l, i, 0, EdgeKind.LoadToStore: EdgeKind)) ++
          lastStore.get(a).map(s => (s, i, 1, EdgeKind.StoreToStore: EdgeKind))
        lastStore(a) = i
        loads(a) = Vector.empty
        e
      case _ => Nil
    }.toSet
  }

  /** The rule of ADR-0019, stated on the nodes before fusion: which ADD fuses which MUL. Readers
    * are counted from the operands of every node, not from the edges.
    */
  private def expectedPairs(d: Dag): Vector[(Int, Int)] = {
    def readers(m: Int) = d.nodes.indices.count(j => d.nodes(j).operands.contains(m))
    def fusable(m: Int) = d.nodes(m).mnemonic == "MUL" && readers(m) == 1
    d.nodes.zipWithIndex.flatMap {
      case (Node.Compute(Op.Add, a, b), i) if a != b =>
        Seq(a, b).filter(fusable).maxOption.map(_ -> i)
      case _ => None
    }
  }

  /** Data edges follow the operands, memory-order edges follow the rule, ids are topological. */
  private def checkEdges(d: Dag, label: String): Unit = {
    val data = d.nodes.indices.flatMap { i =>
      d.nodes(i).operands.distinct.map(o => (o, i, d.latency(o), EdgeKind.Data: EdgeKind))
    }
    assert(d.edges.size == d.edges.distinct.size, label)
    assert(edgeSet(d).filter(_._4 == EdgeKind.Data) == data.toSet, label)
    assert(edgeSet(d).filter(_._4 != EdgeKind.Data) == memoryOrder(d), label)
    assert(d.edges.forall(e => e.from < e.to), label)
  }

  test("a MUL read only by an ADD becomes a MAC in the ADD's place; the MUL is removed") {
    // [10] = [0] * [1] + [2]: LD 0, LD 1, MUL 2, LD 3, ADD 4, ST 5 before fusion.
    val w = Write(10, add(mul(r(0), r(1)), r(2)))
    assert(Fusion.pairs(Dag.build(mvp1, Vector(w))) == Vector((2, 4)))
    val d = fused(mvp1, w)
    assert(
      d.nodes == Vector(
        Node.Load(0),
        Node.Load(1),
        Node.Load(2),
        Node.Mac(0, 1, 2),
        Node.Store(10, 3)
      )
    )
    assert(
      edgeSet(d) == Set(
        (0, 3, 2, EdgeKind.Data),
        (1, 3, 2, EdgeKind.Data),
        (2, 3, 2, EdgeKind.Data),
        (3, 4, 2, EdgeKind.Data) // the MAC's latency, not the ADD's
      )
    )
    // LD 0, MAC 2, ST 4, done at 5; the chain without MAC takes 7 cycles (docs/ISA.md).
    assert(d.earliest == Vector(0, 0, 0, 2, 4) && d.cyclesLowerBound == 6)
    assert(d.height == Vector(5, 5, 5, 3, 1) && d.macs == 1)
    // The MAC's latency comes from the machine.
    val slow = fused(mac3, w)
    assert(slow.nodes == d.nodes && slow.edges.last.distance == 3 && slow.cyclesLowerBound == 7)
    // The MUL may be either operand of the ADD; the addend is the other one.
    assert(fused(mvp1, Write(10, add(r(2), mul(r(0), r(1))))).nodes(3) == Node.Mac(1, 2, 0))
    // Without MAC in the machine, nothing fuses.
    val plain = Dag.build(mvp0, Vector(w))
    assert(Fusion.pairs(plain).isEmpty && (Fusion.fuse(plain) eq plain))
  }

  test("a MUL that anything else reads too is not fused: an ADD, a ST, a SUB or a MUL") {
    val m = mul(r(0), r(1))
    for (other <- Seq(add(m, r(3)), m, Bin(Op.Sub, m, r(3)), mul(m, r(3)), add(r(3), m))) {
      val d = Dag.build(mvp1, Vector(Write(10, add(m, r(2))), Write(11, other)))
      assert(Fusion.pairs(d).filter(_._1 == 2).isEmpty, other)
      assert(Fusion.fuse(d).nodes.count(_.mnemonic == "MUL") >= 1, other)
    }
    // The ST of [11] reads the MUL: it stays, and the ADD stays an ADD.
    val st = fused(mvp1, Write(10, add(m, r(2))), Write(11, m))
    assert(st.nodes.map(_.mnemonic) == Vector("LD", "LD", "MUL", "LD", "ADD", "ST", "ST"))
  }

  test("SUB does not fuse, nor ADD(m, m)") {
    val m = mul(r(0), r(1))
    for (v <- Seq(Bin(Op.Sub, m, r(2)), Bin(Op.Sub, r(2), m), add(m, m))) {
      val d = Dag.build(mvp1, Vector(Write(10, v)))
      assert(Fusion.pairs(d).isEmpty && (Fusion.fuse(d) eq d), v)
    }
  }

  test("ADD(MUL, MUL) fuses the later MUL and keeps the earlier one as the addend") {
    // [10] = [0] * [1] + [2] * [3]: LD 0, LD 1, MUL 2, LD 3, LD 4, MUL 5, ADD 6, ST 7.
    val w = Write(10, add(mul(r(0), r(1)), mul(r(2), r(3))))
    assert(Fusion.pairs(Dag.build(mvp1, Vector(w))) == Vector((5, 6)))
    assert(
      fused(mvp1, w).nodes == Vector(
        Node.Load(0),
        Node.Load(1),
        Node.Compute(Op.Mul, 0, 1),
        Node.Load(2),
        Node.Load(3),
        Node.Mac(3, 4, 2),
        Node.Store(10, 5)
      )
    )
    // When the later MUL has another reader (the ST of [11]), the earlier one fuses.
    val shared = Vector(w, Write(11, mul(r(2), r(3))))
    assert(Fusion.pairs(Dag.build(mvp1, shared)) == Vector((2, 6)))
    assert(
      fused(mvp1, shared: _*).nodes == Vector(
        Node.Load(0),
        Node.Load(1),
        Node.Load(2),
        Node.Load(3),
        Node.Compute(Op.Mul, 2, 3),
        Node.Mac(0, 1, 4),
        Node.Store(10, 5),
        Node.Store(11, 4)
      )
    )
  }

  test("sums of products become chains of MACs; the product of a MAC fuses too") {
    // ([0] * [1] + [2] * [3]) + [4] * [5], as fir4x8 sums its taps: the first MUL stays.
    val sum = fused(mvp1, Write(10, add(add(mul(r(0), r(1)), mul(r(2), r(3))), mul(r(4), r(5)))))
    assert(
      sum.nodes
        .map(_.mnemonic) == Vector("LD", "LD", "MUL", "LD", "LD", "MAC", "LD", "LD", "MAC", "ST")
    )
    assert(sum.nodes(5) == Node.Mac(3, 4, 2) && sum.nodes(8) == Node.Mac(6, 7, 5))
    // ([0] * [1] + [2]) * [3] + [4]: LD 0, LD 1, MUL 2, LD 3, ADD 4, LD 5, MUL 6, LD 7, ADD 8, ST 9.
    val w = Write(10, add(mul(add(mul(r(0), r(1)), r(2)), r(3)), r(4)))
    assert(Fusion.pairs(Dag.build(mvp1, Vector(w))) == Vector((2, 4), (6, 8)))
    val d = fused(mvp1, w)
    assert(
      d.nodes == Vector(
        Node.Load(0),
        Node.Load(1),
        Node.Load(2),
        Node.Mac(0, 1, 2),
        Node.Load(3),
        Node.Load(4),
        Node.Mac(3, 4, 5),
        Node.Store(10, 6)
      )
    )
    assert(edgeSet(d).contains((3, 6, 2, EdgeKind.Data)))
    // A fused DAG has nothing left to fuse.
    assert(Fusion.pairs(d).isEmpty && (Fusion.fuse(d) eq d))
  }

  test("memory-order edges keep their ends when the MULs before them are removed") {
    val ws = Vector(
      Write(5, add(mul(r(0), r(1)), r(2))), // 0 LD [0], 1 LD [1], 2 MUL, 3 LD [2], 4 ADD, 5 ST [5]
      Write(0, add(mul(r(5), r(3)), r(5))), // 6 LD [5] after 5, 7 LD [3], 8 MUL, 9 ADD, 10 ST [0]
      Write(5, add(r(0), mul(r(1), r(4)))) //  11 LD [0] after 10, 12 LD [4], 13 MUL, 14 ADD, 15 ST
    )
    val before = Dag.build(mvp1, ws)
    assert(Fusion.pairs(before) == Vector((2, 4), (8, 9), (13, 14)))
    assert(
      edgeSet(before).filter(_._4 != EdgeKind.Data) == Set(
        (5, 6, 1, EdgeKind.StoreToLoad),
        (0, 10, 0, EdgeKind.LoadToStore),
        (10, 11, 1, EdgeKind.StoreToLoad),
        (6, 15, 0, EdgeKind.LoadToStore),
        (5, 15, 1, EdgeKind.StoreToStore)
      )
    )
    val d = Fusion.fuse(before)
    assert(
      d.nodes == Vector(
        Node.Load(0),
        Node.Load(1),
        Node.Load(2),
        Node.Mac(0, 1, 2),
        Node.Store(5, 3),
        Node.Load(5),
        Node.Load(3),
        Node.Mac(5, 6, 5), // [5] * [3] + [5]: one LD read by two fields
        Node.Store(0, 7),
        Node.Load(0),
        Node.Load(4),
        Node.Mac(1, 10, 9),
        Node.Store(5, 11)
      )
    )
    assert(
      edgeSet(d).filter(_._4 != EdgeKind.Data) == Set(
        (4, 5, 1, EdgeKind.StoreToLoad),
        (0, 8, 0, EdgeKind.LoadToStore),
        (8, 9, 1, EdgeKind.StoreToLoad),
        (5, 12, 0, EdgeKind.LoadToStore),
        (4, 12, 1, EdgeKind.StoreToStore)
      )
    )
    checkEdges(d, "hand-made")
    // [5] = 2 * 3 + 4 = 10; [0] = 10 * 5 + 10 = 60; [5] = 60 + 3 * 6 = 78.
    val memory = Map(0 -> 2L, 1 -> 3L, 2 -> 4L, 3 -> 5L, 4 -> 6L, 5 -> 0L)
    val expected = Map(0 -> 60L, 1 -> 3L, 2 -> 4L, 3 -> 5L, 4 -> 6L, 5 -> 78L)
    assert(execute(before, before.nodes.indices, memory) == expected)
    assert(execute(d, d.nodes.indices, memory) == expected)
  }

  /** Random writes over 6 words, reading words that earlier writes stored: every kind of edge. */
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

  test("random writes with aliasing: the rule, the edges, and the same memory in any order") {
    val rng = new Random(77L)
    var pairs = 0
    (0 until 500).foreach { t =>
      val ws = randomWrites(rng)
      val m = Seq(mvp1, rp4, mac3)(t % 3)
      val before = Dag.build(m, ws)
      val d = Fusion.fuse(before)
      val label = s"$ws on ${m.machineId}"
      assert(Fusion.pairs(before) == expectedPairs(before), label)
      assert(d.macs == Fusion.pairs(before).size, label)
      assert(d.nodes.size == before.nodes.size - d.macs, label)
      assert(Fusion.pairs(d).isEmpty, label)
      checkEdges(d, label)
      pairs += d.macs
      (0 until 3).foreach { _ =>
        val memory = (0 until 6).map(_ -> (rng.nextLong() & Word.Mask)).toMap
        val expected = ws.foldLeft(memory) { (mem, w) =>
          def eval(v: Value): Long = v match {
            case Read(a)       => mem(a)
            case Bin(op, a, b) => op(eval(a), eval(b))
          }
          mem.updated(w.addr, eval(w.value))
        }
        assert(execute(before, before.nodes.indices, memory) == expected, label)
        assert(execute(d, randomOrder(d, rng), memory) == expected, label)
      }
    }
    assert(pairs > 200) // the writes fuse often
  }

  test("benchmarks and random DSL kernels: fused by the rule, with the kernel's meaning") {
    val rng = new Random(4877L)
    val kernels = Benchmarks.all ++ RandomKernels.kernels(51L, 400)
    for (m <- Seq(mvp1, rp4, mac3); k <- kernels) {
      val p = KernelCompiler.prepare(k, m).fold(e => fail(e), identity)
      val lowered = Lowering.lower(p.layout)
      val before = Dag.of(lowered, m)
      val label = s"${k.name} on ${m.machineId}"
      assert(p.dag == Fusion.fuse(before), label)
      assert(Fusion.pairs(before) == expectedPairs(before), label)
      assert(p.dag.macs == Fusion.pairs(before).size, label)
      checkEdges(p.dag, label)
      val image = p.layout.memoryImage(RandomKernels.values(k, rng)).fold(e => fail(e), identity)
      assert(
        execute(p.dag, randomOrder(p.dag, rng), image) == Lowering.evaluate(lowered, image),
        label
      )
    }
  }

  test("benchmark DAGs on mvp-1: every MUL + ADD pair fuses; node counts and lower bounds") {
    def dag(k: Kernel, m: MachineDescription) =
      KernelCompiler.prepare(k, m).fold(e => fail(e), identity).dag
    def counts(d: Dag) = d.nodes.groupBy(_.mnemonic).map { case (n, ns) => n -> ns.size }
    // ADR-0019: saxpy8 8, fir4x8 24 and matmul3 18 pairs. Each fir4x8 output keeps its first MUL,
    // and each matmul3 element too.
    val (saxpy, fir, matmul) = (Benchmarks.all(0), Benchmarks.all(1), Benchmarks.all(2))
    for (m <- Seq(mvp1, rp4, mac3)) {
      assert(counts(dag(saxpy, m)) == Map("LD" -> 17, "MAC" -> 8, "ST" -> 8))
      assert(counts(dag(fir, m)) == Map("LD" -> 15, "MUL" -> 8, "MAC" -> 24, "ST" -> 8))
      assert(counts(dag(matmul, m)) == Map("LD" -> 18, "MUL" -> 9, "MAC" -> 18, "ST" -> 9))
      assert(Benchmarks.all.map(dag(_, m).resourceLowerBound) == Seq(26, 33, 28))
    }
    // Dependences: LD 0, then MUL 2 and the MACs every L_MAC, ST, done one cycle later.
    assert(Benchmarks.all.map(dag(_, mvp1).cyclesLowerBound) == Seq(6, 12, 10))
    assert(Benchmarks.all.map(dag(_, rp4).cyclesLowerBound) == Seq(6, 12, 10))
    assert(Benchmarks.all.map(dag(_, mac3).cyclesLowerBound) == Seq(7, 15, 12))
    // mvp-0 keeps its DAGs.
    assert(Benchmarks.all.forall(k => dag(k, mvp0).macs == 0))
  }
}
