package vliw.compiler.loop

import java.nio.file.Paths

import scala.util.Random

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.KernelCompiler
import vliw.compiler.dag.{DagRun, Node}
import vliw.compiler.dsl.{Benchmarks, Op}
import vliw.compiler.loop.Loop.{Bin, Read, Write}
import vliw.compiler.modulo.{FusionChoice, ModuloCompiler, ModuloScheduler}
import vliw.isa.MachineDescription

import NotDoubled._

/** The loop vectorization by 2 (ISA mvp-2, #110 stage 5a; docs/COMPILER.md "ループのベクトル化と Modulo
  * Scheduling（W-full）"): the doubled graphs, their values against the loop's, and the reasons not
  * to double. The rule-1 candidates are body packing's (`LoopPackSpec`).
  */
class LoopVectorSpec extends AnyFunSuite {
  private def load(path: String*) = MachineDescription
    .load(Paths.get(path.head, path.tail: _*))
    .fold(es => fail(es.mkString("\n")), identity)
  private val full = load("config", "machine-mvp2.json")
  private val mw1 = load("tests", "machines", "mvp2_mw1.json")
  private val mvp1 = load("config", "machine-mvp1.json")
  private val mvp0 = MachineDescription.load().fold(es => fail(es.mkString("\n")), identity)

  /** G: the fused scalar loop graph of `k` on `m`, with its lowering. */
  private def scalar(k: LoopKernel, m: MachineDescription) = {
    val layout = LoopLowering.layout(k, m).fold(e => fail(e), identity)
    val lowered = LoopLowering.lower(k, layout)
    (FusionChoice.of(LoopGraph.of(lowered, m))._1, lowered)
  }
  private def fir(n: Int) = LoopBenchmarks.fir(Seq(1L, 2L, 3L, 4L), n)

  /** The doubled graph's unrolled DAG run in `orders` random orders that respect its edges
    * (`DagRun`, from the definitions of the nodes) gives the memory of `LoopLowering.evaluate`.
    */
  private def sameValues(v: LoopGraph, lowered: LoweredLoop, rng: Random, orders: Int = 3) = {
    val memory = (0 until lowered.layout.words).map(_ -> (rng.nextLong() & 0xffffffffL)).toMap
    val want = LoopLowering.evaluate(lowered.loop, memory)
    val dag = v.expanded.dag
    (0 until orders).foreach { _ =>
      assert(DagRun.execute(dag, DagRun.randomOrder(dag, rng), memory) == want, lowered.layout)
    }
  }

  test(
    "saxpy8 doubles into LD.W of x and y, a VMAC with `a` broadcast, and ST.W at the odd word 17"
  ) {
    val (g, lowered) = scalar(LoopBenchmarks.saxpy(8), full)
    val v = LoopVector.double(g)
    // x 0-7, y 8-15, a 16, out 17-24 (COMPILER.md).
    assert(v.trips == 4 && v.states.isEmpty)
    assert(v.places == Vector(Place.Before) ++ Vector.fill(4)(Place.Each))
    assert(
      v.nodes == Vector(
        LoopNode.Load(Address(16, 0)),
        LoopNode.LoadW(Address(0, 2)),
        LoopNode.LoadW(Address(8, 2)),
        LoopNode.VMac(VRef.Broadcast(Ref(0)), VRef.Pair(Ref(1)), VRef.Pair(Ref(2))),
        LoopNode.StoreW(Address(17, 2), VRef.Pair(Ref(3)))
      )
    )
    sameValues(v, lowered, new Random(5))
  }

  test(
    "fir4x8 doubles with LD.W chains of stride 2: taps 0 and 1 read the leaders 2 and 3 back 1"
  ) {
    val k = fir(8)
    val (g, lowered) = scalar(k, full)
    val v = LoopVector.double(g)
    val layout = lowered.layout
    val x = layout.elem("x", 0)
    def h(t: Int) = LoopNode.Load(Address(layout.constant(t + 1L), 0))
    import Place.{Before, Each}
    // Post-order from the ST: the last MAC (tap 3) first, its fields in order.
    assert(v.trips == 4)
    assert(
      v.places == Vector(
        Before,
        Each,
        Before,
        Each,
        Before,
        Before,
        Before,
        Before,
        Each,
        Each,
        Each,
        Each,
        Each
      )
    )
    assert(
      v.nodes == Vector(
        h(3),
        LoopNode.LoadW(Address(x + 3, 2)),
        h(2),
        LoopNode.LoadW(Address(x + 2, 2)),
        h(1),
        LoopNode.LoadW(Address(x + 1, 0)),
        h(0),
        LoopNode.LoadW(Address(x, 0)),
        LoopNode.VCompute(Op.Mul, VRef.Broadcast(Ref(6)), VRef.Pair(Ref(3, 1, Vector(7)))),
        LoopNode.VMac(VRef.Broadcast(Ref(4)), VRef.Pair(Ref(1, 1, Vector(5))), VRef.Pair(Ref(8))),
        LoopNode.VMac(VRef.Broadcast(Ref(2)), VRef.Pair(Ref(3)), VRef.Pair(Ref(9))),
        LoopNode.VMac(VRef.Broadcast(Ref(0)), VRef.Pair(Ref(1)), VRef.Pair(Ref(10))),
        LoopNode.StoreW(Address(layout.elem("out", 0), 2), VRef.Pair(Ref(11)))
      )
    )
    sameValues(v, lowered, new Random(6))
    Seq(16, 32).foreach(n =>
      sameValues(LoopVector.double(scalar(fir(n), full)._1), scalar(fir(n), full)._2, new Random(n))
    )
  }

  test("the doubled saxpy8 and fir4x8 load as many words as the list-scheduled W-full DAG") {
    def counts(nodes: Seq[Node]) =
      (nodes.count(_.isInstanceOf[Node.Load]), nodes.count(_.isInstanceOf[Node.LoadW]))
    for (
      (loop, kernel, want) <- Seq(
        (LoopBenchmarks.saxpy(8), Benchmarks.saxpy(8), (1, 8)),
        (fir(8), Benchmarks.fir(Seq(1L, 2L, 3L, 4L), 8), (4, 10))
      )
    ) {
      val v = LoopVector.double(scalar(loop, full)._1)
      val packed = KernelCompiler.prepare(kernel, full).fold(e => fail(e), identity).packing.get
      assert(counts(v.expanded.dag.nodes) == want && counts(packed.dag.nodes) == want, loop.name)
    }
  }

  test("odd and negative strides: gathers with LDs of their own, lane STs, and copies") {
    // x(2i) and x(2i+1) read with stride 2, y reversed, a reversed store and w(2i), w(2i+1).
    val k = LoopKernel("strides", "i", 6) { b =>
      val x = b.input("x", 16)
      val y = b.input("y", 8)
      val a = b.scalar("a")
      val out = b.output("out", 6)
      val w = b.output("w", 12)
      b.body { i =>
        out(i * -1 + 5) = a * x(i * 2) + y(i * -1 + 6)
        w(i * 2) = x(i * 2 + 1) - y(i)
        w(i * 2 + 1) = x(i * 2)
      }
    }
    val (g, lowered) = scalar(k, full)
    val v = LoopVector.double(g)
    val gathers = v.nodes.flatMap(LoopNode.fields).collect { case x: VRef.Gather => x }.distinct
    // x(2i): stride 4 lanes; y(6-i): stride -2; x(2i+1): stride 4. The copy w(2i+1) = x(2i)
    // stores the lanes of x(2i)'s gather with two STs.
    assert(gathers.size == 3)
    val lanes = gathers.flatMap(x => Seq(x.lo, x.hi))
    assert(lanes.forall(r => r.back == 0 && r.initial.isEmpty))
    assert(lanes.map(_.node).distinct.size == 6) // one LD pair per gather
    def addr(r: Ref) = v.nodes(r.node).asInstanceOf[LoopNode.Load].addr
    val xb = lowered.layout.elem("x", 0)
    val yb = lowered.layout.elem("y", 0)
    assert(
      gathers.map(x => (addr(x.lo), addr(x.hi))).toSet == Set(
        (Address(xb, 4), Address(xb + 2, 4)),
        (Address(yb + 6, -2), Address(yb + 5, -2)),
        (Address(xb + 1, 4), Address(xb + 3, 4))
      )
    )
    // No LD of a gather is shared across iterations, and no LD.W reads x or y with stride 2.
    assert(v.nodes.forall(_.operands.forall(r => r.back == 0)))
    val ob = lowered.layout.elem("out", 0)
    val wb = lowered.layout.elem("w", 0)
    val stores = v.nodes.collect {
      case LoopNode.StoreLane(a, _, lane) => ("lane", a, lane)
      case LoopNode.StoreW(a, _)          => ("ST.W", a, 0)
      case LoopNode.Store(a, _)           => ("ST", a, 0)
    }
    assert(
      stores.toSet == Set(
        ("lane", Address(ob + 5, -2), 0),
        ("lane", Address(ob + 4, -2), 1),
        ("lane", Address(wb, 4), 0),
        ("lane", Address(wb + 2, 4), 1),
        ("ST", Address(wb + 1, 4), 0),
        ("ST", Address(wb + 3, 4), 0)
      )
    )
    sameValues(v, lowered, new Random(7), orders = 5)
    // W-reg doubles with gathers and lane STs only.
    val (gr, loweredReg) = scalar(LoopBenchmarks.saxpy(8), mw1)
    val vr = LoopVector.double(gr)
    assert(!vr.nodes.exists(n => n.isInstanceOf[LoopNode.LoadW] || n.isInstanceOf[LoopNode.StoreW]))
    assert(vr.nodes.count(_.isInstanceOf[LoopNode.StoreLane]) == 2)
    sameValues(vr, loweredReg, new Random(8))
  }

  test("random vectorizable loops: the doubled graph computes the loop, on W-full and W-reg") {
    val rng = new Random(110)
    var (doubled, gathered) = (0, 0)
    for (k <- RandomLoops.vectorizable(110, 300); m <- Seq(full, mw1)) {
      val (g, lowered) = scalar(k, m)
      if (LoopVector.reasons(g).isEmpty) {
        val v = LoopVector.double(g)
        if (m == full) {
          doubled += 1
          if (v.nodes.flatMap(LoopNode.fields).exists(_.isInstanceOf[VRef.Gather])) gathered += 1
        }
        sameValues(v, lowered, rng, orders = 2)
      }
    }
    // Measured: 130 loops have no reason not to double (all of them double on W-full), 77 of
    // them with a gather (WModuloSpec).
    assert(doubled == 130 && gathered == 77, (doubled, gathered))
  }

  test("each reason on a minimal loop; the set of all that apply") {
    def reasons(build: LoopKernel.Builder => Unit, trips: Int = 4) =
      LoopVector.reasons(scalar(LoopKernel("r", "i", trips)(build), full)._1)
    // x(i) + a: `a` outside a multiplication's rs1; x(i) * a moves `a` to rs1.
    assert(reasons { b =>
      val x = b.input("x", 4); val a = b.scalar("a"); val o = b.output("o", 4)
      b.body(i => o(i) = x(i) + a)
    } == Vector(SameValue("rs2 of ADD")))
    assert(reasons { b =>
      val x = b.input("x", 4); val a = b.scalar("a"); val o = b.output("o", 4)
      b.body(i => o(i) = x(i) * a)
    }.isEmpty)
    // MAC's addend, and a store of a value before the loop.
    assert(reasons { b =>
      val x = b.input("x", 4); val a = b.scalar("a"); val o = b.output("o", 4)
      b.body(i => o(i) = x(i) * x(i) + a)
    } == Vector(SameValue("rs3 of MAC")))
    assert(reasons { b =>
      val x = b.input("x", 4); val a = b.scalar("a"); val o = b.output("o", 4)
      val p = b.output("p", 4)
      b.body { i => o(i) = x(i) * x(i); p(i) = a * a }
    } == Vector(SameValue("the store source")))
    // Odd trips and a state together.
    assert(
      reasons(
        { b =>
          val x = b.input("x", 3); val o = b.output("o", 1)
          val s = b.state("s", b.const(0)); b.body(i => s := s.value + x(i)); o(0) = s
        },
        trips = 3
      ) == Vector(OddTripCount, AssociationOrder)
    )
    // ZeroStoreStride on a hand-made loop: [9] is written in every iteration.
    val zero = LoopGraph.build(
      full,
      Loop(
        2,
        Vector(),
        Vector(Write(Address(9, 0), Bin(Op.Add, Read(Address(0, 1)), Read(Address(4, 1))))),
        Vector()
      )
    )
    assert(LoopVector.reasons(zero) == Vector(ZeroStoreStride))
    // MiiIncrease on W-reg counts: fir4x8 doubles to MII 10 > 2 x 4 (#78's W-reg memory slot).
    val (d, none) = ModuloCompiler.decide(scalar(fir(8), mw1)._1)
    assert(d.notDoubled == Vector(MiiIncrease) && d.scalarMII == 4 && d.doubledMII.contains(10))
    assert(none.isEmpty && !d.doubled)
    val (dFull, someV) = ModuloCompiler.decide(scalar(fir(8), full)._1)
    assert(dFull.doubled && dFull.doubledMII.contains(4) && someV.nonEmpty)
  }

  test("AssociationOrder: every state is a reduction; LoopState otherwise") {
    def kind(build: LoopKernel.Builder => Unit) =
      LoopVector.reasons(scalar(LoopKernel("s", "i", 4)(build), full)._1)
    def loop(next: (LoopExpr, LoopExpr) => LoopExpr) = kind { b =>
      val x = b.input("x", 4); val o = b.output("o", 1)
      val s = b.state("s", b.const(1)); b.body(i => s := next(s.value, x(i))); o(0) = s
    }
    assert(loop(_ + _) == Vector(AssociationOrder)) // sum
    assert(loop(_ * _) == Vector(AssociationOrder)) // product
    assert(loop((s, x) => x + s) == Vector(AssociationOrder)) // the state as rs2
    assert(loop((s, x) => s + x * x) == Vector(AssociationOrder)) // MAC: the state as the addend
    assert(loop(_ - _) == Vector(LoopState)) // SUB does not reassociate
    assert(loop((s, x) => s + s * x) == Vector(LoopState)) // the state read twice
    assert(loop((s, x) => (s + x) * x) == Vector(LoopState)) // a recurrence
    // Two independent reductions.
    assert(kind { b =>
      val x = b.input("x", 4); val o = b.output("o", 2)
      val s = b.state("s", b.const(0)); val t = b.state("t", b.const(1))
      b.body { i => s := s.value + x(i); t := t.value * x(i) }
      o(0) = s; o(1) = t
    } == Vector(AssociationOrder))
    // A scan stores the state's value; a store of the next value; two states that read each other.
    assert(kind { b =>
      val x = b.input("x", 4); val o = b.output("o", 4)
      val s = b.state("s", b.const(0)); b.body { i => s := s.value + x(i); o(i) = s.value }
    } == Vector(LoopState))
    assert(kind { b =>
      val x = b.input("x", 4); val o = b.output("o", 4)
      val s = b.state("s", b.const(0)); b.body { i => s := s.value + x(i); o(i) = s.value + x(i) }
    } == Vector(LoopState))
    assert(kind { b =>
      val x = b.input("x", 4); val o = b.output("o", 2)
      val s = b.state("s", b.const(0)); val t = b.state("t", b.const(1))
      b.body { i => s := s.value + x(i) * t.value; t := t.value + x(i) * s.value }
      o(0) = s; o(1) = t
    } == Vector(LoopState))
    // The benchmark loops: matmul3 (m), product8 and dot8 are pure reductions.
    assert(
      LoopVector.reasons(scalar(LoopBenchmarks.matmulAcc(3), full)._1) == Vector(AssociationOrder)
    )
    assert(
      LoopVector.reasons(scalar(LoopBenchmarks.product(8), full)._1) == Vector(
        OddTripCount,
        AssociationOrder
      )
    )
    assert(
      LoopVector.reasons(scalar(LoopBenchmarks.dot(8), full)._1) == Vector(
        OddTripCount,
        AssociationOrder
      )
    )
    assert(LoopVector.reasons(scalar(LoopBenchmarks.dot(9), full)._1) == Vector(AssociationOrder))
    assert(
      LoopVector.reasons(scalar(LoopBenchmarks.matmulRows(3), full)._1) == Vector(OddTripCount)
    )
  }

  test("MinAvg counts a gather 2 from its first lane to its last reader") {
    // LD lo at 3, LD hi at 0, LD.W at 2, VADD(gather, pair) at 4, ST.W at 5; II 3.
    val g = LoopGraph(
      full,
      2,
      Vector.fill(5)(Place.Each),
      Vector(
        LoopNode.Load(Address(0, 2)),
        LoopNode.Load(Address(1, 2)),
        LoopNode.LoadW(Address(4, 2)),
        LoopNode.VCompute(Op.Add, VRef.Gather(Ref(0), Ref(1)), VRef.Pair(Ref(2))),
        LoopNode.StoreW(Address(8, 2), VRef.Pair(Ref(3)))
      ),
      Vector.empty
    )
    val t = Map(0 -> 3, 1 -> 0, 2 -> 2, 3 -> 4, 4 -> 5)
    // The gather: 2 x (4 - 0); the LD.W 2 x 2; the VADD 2 x 1. ceil(14 / 3) = 5. Each lane by
    // itself (1 x 1 + 1 x 4) would give ceil(11 / 3) = 4.
    assert(ModuloScheduler.minAvg(g, 3, t) == 5)
    assert(ModuloScheduler.minAvg(g, 1, t) == 14 + 0)
  }

  test("no W node on ISA mvp-0, mvp-1 or W-reg") {
    for (m <- Seq(mvp0, mvp1, mw1); k <- LoopBenchmarks.all) {
      val c = ModuloCompiler.compile(k, m).fold(e => fail(e), identity)
      assert(
        c.graph.nodes.forall {
          case _: LoopNode.Load | _: LoopNode.Compute | _: LoopNode.Mac | _: LoopNode.Store => true
          case _                                                                            => false
        },
        s"${k.name} on ${m.machineId}"
      )
    }
  }
}
