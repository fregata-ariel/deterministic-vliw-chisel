package vliw.compiler.loop

import java.nio.file.Paths

import scala.util.Random

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.dag.{Dag, EdgeKind, Fusion, Node}
import vliw.compiler.dsl._
import vliw.compiler.loop.Loop.{Bin, Final, Read, StateRef, Update, Write}
import vliw.isa.MachineDescription

import LoopFusionSpec.Hand
import Place.{After, Before, Each}

/** Fusion of MUL + ADD into MAC on the loop graph (ADR-0019, #77). */
class LoopFusionSpec extends AnyFunSuite {
  private def load(path: String*) = MachineDescription
    .load(Paths.get(path.head, path.tail: _*))
    .fold(es => fail(es.mkString("\n")), identity)
  private val mvp0 = MachineDescription.load().fold(es => fail(es.mkString("\n")), identity)
  private val mvp1 = load("config", "machine-mvp1.json")
  private val mvp1s = Seq(
    mvp1,
    load("tests", "machines", "mvp1_rp4.json"),
    load("tests", "machines", "mvp1_mac3.json")
  )

  private def graph(k: LoopKernel, m: MachineDescription): (MemoryLayout, LoopGraph) = {
    val layout = LoopLowering.layout(k, m).fold(e => fail(e), identity)
    (layout, LoopGraph.of(LoopLowering.lower(k, layout), m))
  }

  /** Runs a DAG in `order`, which must respect its edges; fails when an operand has not run yet.
    * Returns the memory afterwards.
    */
  private def execute(d: Dag, order: Seq[Int], memory: Map[Int, Long]): Map[Int, Long] = {
    val result = new Array[Long](d.nodes.size)
    val done = new Array[Boolean](d.nodes.size)
    def operand(i: Int, j: Int) = {
      assert(done(j), s"node $i runs before its operand $j"); result(j)
    }
    order.foldLeft(memory) { (mem, i) =>
      val after = d.nodes(i) match {
        case Node.Load(a)           => result(i) = mem(a); mem
        case Node.Compute(op, a, b) => result(i) = op(operand(i, a), operand(i, b)); mem
        case Node.Mac(a, b, c)      =>
          result(i) = Op.Add(Op.Mul(operand(i, a), operand(i, b)), operand(i, c)); mem
        case Node.Store(a, v) => mem.updated(a, operand(i, v))
        case other            => fail(s"$other: not a node of a loop's DAG")
      }
      done(i) = true
      after
    }
  }

  /** A random order that respects every edge of `d` (Kahn's algorithm, random choice). */
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

  /** The DAG with nodes named by what they compute instead of their ids. */
  private def canonical(d: Dag) = {
    val key = d.nodes.foldLeft(Vector.empty[Any]) { (keys, n) =>
      keys :+ (n match {
        case Node.Load(a)           => ("LD", a)
        case Node.Compute(op, a, b) => (op, keys(a), keys(b))
        case Node.Mac(a, b, c)      => ("MAC", keys(a), keys(b), keys(c))
        case Node.Store(a, v)       => ("ST", a, keys(v))
        case other                  => fail(s"$other: not a node of a loop's DAG")
      })
    }
    def count[A](xs: Seq[A]) = xs.groupBy(identity).view.mapValues(_.size).toMap
    (count(key), count(d.edges.map(e => (key(e.from), key(e.to), e.distance, e.kind))))
  }

  /** The fused graph computes what the loop does: its unrolled DAG, run in node order and in random
    * orders that respect its edges, leaves the memory of `LoopLowering.evaluate`.
    */
  private def computes(f: LoopGraph, loop: Loop, memory: Map[Int, Long], rng: Random): Boolean = {
    val want = LoopLowering.evaluate(loop, memory)
    val dag = f.expanded.dag
    (dag.nodes.indices.toVector +: Seq.fill(3)(randomOrder(dag, rng)))
      .forall(order => execute(dag, order, memory) == want)
  }

  test("fused, each benchmark loop unrolls to the fused Phase 2 DAG of the unrolled kernel") {
    // (pairs of the loop graph, MAC of the unrolled loop): one pair per MUL + ADD of the body.
    val expected = Map(
      ("saxpy8", "i") -> ((1, 8)),
      ("fir4x8", "i") -> ((3, 24)),
      ("matmul3", "i") -> ((6, 18)),
      ("matmul3", "m") -> ((9, 18)),
      ("product8", "i") -> ((0, 0)),
      ("dot8", "i") -> ((1, 7))
    )
    for (k <- LoopBenchmarks.all; m <- mvp1s) {
      val (layout, g) = graph(k, m)
      val f = LoopFusion.fuse(g)
      val phase2 = Fusion.fuse(Dag.of(Lowering.lower(layout), m))
      val (pairs, macs) = expected((k.name, k.index))
      val label = s"${k.name} (${k.index}) on ${m.machineId}"
      assert(LoopFusion.pairs(g).size == pairs, label)
      assert(f.expanded.dag.macs == macs && phase2.macs == macs, label)
      assert(canonical(f.expanded.dag) == canonical(phase2), label)
      // Nothing is left to fuse.
      assert(LoopFusion.pairs(f).isEmpty, label)
    }
  }

  test("fused benchmark loops: operations per iteration, and dependences across iterations") {
    // Per loop: LD and MUL before the loop; LD, MUL, MAC, ADD, ST per iteration; ST after the loop;
    // the dependences across iterations (from, to, cycles, iterations) with their number. `mac` is
    // the latency of MAC.
    def expected(mac: Int) = Map(
      ("saxpy8", "i") -> ((
        1,
        0,
        Seq(2, 0, 1, 0, 1),
        0,
        Map.empty[(String, String, Int, Int), Int]
      )),
      ("fir4x8", "i") -> ((
        7,
        0,
        Seq(1, 1, 3, 0, 1),
        0,
        // x(i + 3), loaded once, read by the MUL of x(i) and the MACs of x(i + 1) and x(i + 2).
        Map(("LD", "MUL", 2, 3) -> 1, ("LD", "MAC", 2, 2) -> 1, ("LD", "MAC", 2, 1) -> 1)
      )),
      ("matmul3", "i") -> ((
        9,
        0,
        Seq(3, 3, 6, 0, 3),
        0,
        Map.empty[(String, String, Int, Int), Int]
      )),
      // The accumulations: each state is a MAC that reads its own value of the iteration before.
      ("matmul3", "m") -> ((6, 9, Seq(6, 0, 9, 0, 0), 9, Map(("MAC", "MAC", mac, 1) -> 9))),
      ("product8", "i") -> ((1, 0, Seq(1, 1, 0, 0, 0), 1, Map(("MUL", "MUL", 2, 1) -> 1))),
      ("dot8", "i") -> ((2, 1, Seq(2, 0, 1, 0, 0), 1, Map(("MAC", "MAC", mac, 1) -> 1)))
    )
    for (k <- LoopBenchmarks.all; m <- mvp1s) {
      val f = LoopFusion.fuse(graph(k, m)._2)
      def count(p: Place, mn: String) = f.ids(p).count(i => f.nodes(i).mnemonic == mn)
      val carried = f.edges
        .filter(_.iterations > 0)
        .map(e => (f.nodes(e.from).mnemonic, f.nodes(e.to).mnemonic, e.cycles, e.iterations))
        .groupBy(identity)
        .view
        .mapValues(_.size)
        .toMap
      val got = (
        count(Place.Before, "LD"),
        count(Place.Before, "MUL"),
        Seq("LD", "MUL", "MAC", "ADD", "ST").map(count(Place.Each, _)),
        count(Place.After, "ST"),
        carried
      )
      assert(
        got == expected(m.operation("MAC").latency)((k.name, k.index)),
        s"${k.name} on ${m.machineId}"
      )
      // The states now end in a MAC where they ended in an ADD.
      assert(
        f.states.forall(s => f.places(s.init) == Place.Before && f.places(s.next) == Place.Each)
      )
    }
  }

  private def add(a: Loop.Value, b: Loop.Value) = Bin(Op.Add, a, b)
  private def mul(a: Loop.Value, b: Loop.Value) = Bin(Op.Mul, a, b)
  private def rd(base: Int, stride: Int) = Read(Address(base, stride))

  // Hand-made loops: the pairs (MUL, ADD) by the ids before fusion, and the nodes and places after
  // it (None when nothing fuses, and the same graph comes back).
  private val hand: Seq[Hand] = Seq(
    Hand(
      "fused: [20 + k] = [k] * [10 + k] + [30]",
      Loop(
        3,
        Vector(),
        Vector(Write(Address(20, 1), add(mul(rd(0, 1), rd(10, 1)), rd(30, 0)))),
        Vector()
      ),
      Vector((2, 4)),
      Some(
        Vector(
          Each -> LoopNode.Load(Address(0, 1)),
          Each -> LoopNode.Load(Address(10, 1)),
          Before -> LoopNode.Load(Address(30, 0)),
          Each -> LoopNode.Mac(Ref(0), Ref(1), Ref(2)),
          Each -> LoopNode.Store(Address(20, 1), Ref(3))
        )
      )
    ),
    Hand(
      "fused, with an operand of the iteration before: [20 + k] = [k] * [1 + k] + [30]",
      Loop(
        4,
        Vector(),
        Vector(Write(Address(20, 1), add(mul(rd(0, 1), rd(1, 1)), rd(30, 0)))),
        Vector()
      ),
      Vector((2, 4)),
      // x(k) is the LD of x(k + 1) one iteration back, or the LD of x(0) before the loop.
      Some(
        Vector(
          Each -> LoopNode.Load(Address(1, 1)),
          Before -> LoopNode.Load(Address(0, 0)),
          Before -> LoopNode.Load(Address(30, 0)),
          Each -> LoopNode.Mac(Ref(0, 1, Vector(1)), Ref(0), Ref(2)),
          Each -> LoopNode.Store(Address(20, 1), Ref(3))
        )
      )
    ),
    Hand(
      "ADD(MUL, MUL) fuses the later MUL: [20 + k] = [k] * [10 + k] + [10 + k] * [30 + k]",
      Loop(
        3,
        Vector(),
        Vector(Write(Address(20, 1), add(mul(rd(0, 1), rd(10, 1)), mul(rd(10, 1), rd(30, 1))))),
        Vector()
      ),
      Vector((4, 5)),
      Some(
        Vector(
          Each -> LoopNode.Load(Address(0, 1)),
          Each -> LoopNode.Load(Address(10, 1)),
          Each -> LoopNode.Compute(Op.Mul, Ref(0), Ref(1)),
          Each -> LoopNode.Load(Address(30, 1)),
          Each -> LoopNode.Mac(Ref(1), Ref(3), Ref(2)),
          Each -> LoopNode.Store(Address(20, 1), Ref(4))
        )
      )
    ),
    Hand(
      "before the loop: the initial value [0] * [1] + [2] becomes a MAC there; s += [10 + k]",
      Loop(
        3,
        Vector(add(mul(rd(0, 0), rd(1, 0)), rd(2, 0))),
        Vector(Update(0, add(StateRef(0), rd(10, 1)))),
        Vector(Final(40, 0))
      ),
      Vector((2, 4)),
      Some(
        Vector(
          Before -> LoopNode.Load(Address(0, 0)),
          Before -> LoopNode.Load(Address(1, 0)),
          Before -> LoopNode.Load(Address(2, 0)),
          Before -> LoopNode.Mac(Ref(0), Ref(1), Ref(2)),
          Each -> LoopNode.Load(Address(10, 1)),
          Each -> LoopNode.Compute(Op.Add, Ref(5, 1, Vector(3)), Ref(4)),
          After -> LoopNode.Store(Address(40, 0), Ref(5))
        )
      )
    ),
    Hand(
      "a MUL that the next iteration reads (through a state) is not fused: s = [1 + k] * [10 + k]; [20 + k] = s + [30]",
      Loop(
        3,
        Vector(rd(0, 0)),
        Vector(
          Update(0, mul(rd(1, 1), rd(10, 1))),
          Write(Address(20, 1), add(StateRef(0), rd(30, 0)))
        ),
        Vector(Final(40, 0))
      ),
      Vector(),
      None
    ),
    Hand(
      "a MUL that its iteration and the next read: s = [1 + k] * [10 + k]; [20 + k] = [1 + k] * [10 + k] + s",
      Loop(
        3,
        Vector(rd(0, 0)),
        Vector(
          Update(0, mul(rd(1, 1), rd(10, 1))),
          Write(Address(20, 1), add(mul(rd(1, 1), rd(10, 1)), StateRef(0)))
        ),
        Vector()
      ),
      Vector(),
      None
    ),
    Hand(
      "a MUL that is the next value of a state that nothing reads: s = [1 + k] * [10 + k]; [20 + k] = [1 + k] * [10 + k] + [30]",
      Loop(
        3,
        Vector(rd(0, 0)),
        Vector(
          Update(0, mul(rd(1, 1), rd(10, 1))),
          Write(Address(20, 1), add(mul(rd(1, 1), rd(10, 1)), rd(30, 0)))
        ),
        Vector()
      ),
      Vector(),
      None
    ),
    Hand(
      "a MUL that the store after the loop and the next iteration read: s = s * [1 + k]; [20 + k] = s * [1 + k] + [30]",
      Loop(
        3,
        Vector(rd(0, 0)),
        Vector(
          Update(0, mul(StateRef(0), rd(1, 1))),
          Write(Address(20, 1), add(mul(StateRef(0), rd(1, 1)), rd(30, 0)))
        ),
        Vector(Final(40, 0))
      ),
      Vector(),
      None
    ),
    Hand(
      "a MUL that is the initial value of a state: s0 = [0] * [1]; s1 = [0] * [1] + [2]; s0 = [10 + k] + [11 + k]; s1 += [11 + k]",
      Loop(
        2,
        Vector(mul(rd(0, 0), rd(1, 0)), add(mul(rd(0, 0), rd(1, 0)), rd(2, 0))),
        Vector(Update(0, add(rd(10, 1), rd(11, 1))), Update(1, add(StateRef(1), rd(11, 1)))),
        Vector(Final(40, 1))
      ),
      Vector(),
      None
    ),
    Hand(
      "a MUL before the loop does not fuse into the iterations: [20 + k] = [0] * [1] + [10 + k]",
      Loop(
        3,
        Vector(),
        Vector(Write(Address(20, 1), add(mul(rd(0, 0), rd(1, 0)), rd(10, 1)))),
        Vector()
      ),
      Vector(),
      None
    ),
    Hand(
      "ADD(m, m): [20 + k] = [k] * [10 + k] + [k] * [10 + k]",
      Loop(
        3,
        Vector(),
        Vector(Write(Address(20, 1), add(mul(rd(0, 1), rd(10, 1)), mul(rd(0, 1), rd(10, 1))))),
        Vector()
      ),
      Vector(),
      None
    ),
    Hand(
      "SUB: [20 + k] = [k] * [10 + k] - [30]",
      Loop(
        3,
        Vector(),
        Vector(Write(Address(20, 1), Bin(Op.Sub, mul(rd(0, 1), rd(10, 1)), rd(30, 0)))),
        Vector()
      ),
      Vector(),
      None
    ),
    Hand(
      "a MUL that a ST reads too: [20 + k] = [k] * [10 + k] + [30]; [40 + k] = [k] * [10 + k]",
      Loop(
        3,
        Vector(),
        Vector(
          Write(Address(20, 1), add(mul(rd(0, 1), rd(10, 1)), rd(30, 0))),
          Write(Address(40, 1), mul(rd(0, 1), rd(10, 1)))
        ),
        Vector()
      ),
      Vector(),
      None
    )
  )

  test("hand-made loops: what fuses, the fused graph, and its result") {
    val rng = new Random(77)
    for (h <- hand) {
      val g = LoopGraph.build(mvp1, h.loop)
      assert(LoopFusion.pairs(g) == h.pairs, h.name)
      val f = LoopFusion.fuse(g)
      h.fused match {
        case Some(nodes) => assert(f.places.zip(f.nodes) == nodes, h.name)
        case None        => assert(f eq g, h.name)
      }
      for (_ <- 0 until 5) {
        val memory = (0 until 48).map(_ -> (rng.nextLong() & 0xffffffffL)).toMap
        assert(computes(f, h.loop, memory, rng), h.name)
      }
      // Without MAC in the machine, nothing fuses.
      val plain = LoopGraph.build(mvp0, h.loop)
      assert(LoopFusion.pairs(plain).isEmpty && (LoopFusion.fuse(plain) eq plain), h.name)
    }
    // The states point to the fused values: the initial value, a MAC, of the loop before.
    val before = LoopFusion.fuse(LoopGraph.build(mvp1, hand(3).loop))
    assert(before.states == Vector(StateNodes(3, 5)))
  }

  /** The rule of ADR-0019 on the loop graph, stated on the nodes: the uses of each node are the
    * operands and initial values that name it, and the states that start or end with it.
    */
  private def expectedPairs(g: LoopGraph): Vector[(Int, Int)] = {
    def uses(n: Int) =
      g.nodes
        .map(_.operands.map(r => (if (r.node == n) 1 else 0) + r.initial.count(_ == n)).sum)
        .sum +
        g.states.count(_.init == n) + g.states.count(_.next == n)
    def fusable(add: Int, r: Ref) =
      r.back == 0 && g.places(r.node) == g.places(add) && g.nodes(r.node).mnemonic == "MUL" &&
        uses(r.node) == 1
    g.nodes.indices.flatMap { i =>
      g.nodes(i) match {
        case LoopNode.Compute(Op.Add, x, y) =>
          Seq(x, y).filter(fusable(i, _)).map(_.node).maxOption.map(_ -> i)
        case _ => None
      }
    }.toVector
  }

  test("random loops: the rule, every instance of a fused MUL read only by its ADD, the result") {
    val rng = new Random(78)
    val loops = RandomLoops.kernels(76, 300).map(_._1)
    val fused = loops.map { k =>
      val (layout, g) = graph(k, mvp1)
      val pairs = LoopFusion.pairs(g)
      assert(pairs == expectedPairs(g), k.name)
      // In the loop unrolled without fusion, each instance of a fused MUL has one reader: the
      // instance of its ADD in the same iteration (or before the loop, for both before it).
      val (dag, origin) = (g.expanded.dag, g.expanded.origin)
      val index = origin.zipWithIndex.toMap
      for ((m, add) <- pairs; (inst, i) <- origin.zipWithIndex if inst.node == m) {
        val readers = dag.successors(i).filter(_.kind == EdgeKind.Data).map(_.to)
        assert(readers == Vector(index(Instance(add, inst.iteration))), s"${k.name}: $m, $add")
      }
      val f = LoopFusion.fuse(g)
      assert(LoopFusion.pairs(f).isEmpty, k.name)
      val each = pairs.count { case (m, _) => g.places(m) == Place.Each }
      assert(f.expanded.dag.macs == each * g.trips + (pairs.size - each), k.name)
      assert(
        f.states.size == g.states.size && f.states.forall(s =>
          f.places(s.init) == Place.Before && f.places(s.next) == Place.Each
        ),
        k.name
      )
      val in = RandomKernels.values(k.unroll, rng)
      val memory = layout.memoryImage(in).fold(e => fail(e), identity)
      assert(computes(f, LoopLowering.lower(k, layout).loop, memory, rng), k.name)
      pairs.size
    }
    // Enough of the loops fuse something.
    assert(fused.count(_ > 0) >= 100, fused.count(_ > 0))
  }
}

object LoopFusionSpec {

  /** A hand-made loop, the pairs that fuse, and the fused graph as (place, node) by id. */
  final case class Hand(
      name: String,
      loop: Loop,
      pairs: Vector[(Int, Int)],
      fused: Option[Vector[(Place, LoopNode)]]
  )
}
