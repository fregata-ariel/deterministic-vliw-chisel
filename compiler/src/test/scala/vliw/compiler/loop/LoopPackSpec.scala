package vliw.compiler.loop

import java.nio.file.Paths

import scala.util.Random

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.{IsaExecutor, KernelCompiler}
import vliw.compiler.dag.{Dag, DagRun, Edge, EdgeKind, LaneReason, Node, Pack}
import vliw.compiler.dsl.{Interpreter, Op}
import vliw.compiler.loop.Loop.{Bin, Read, StateRef, Update, Write}
import vliw.compiler.modulo.{FusionChoice, LoopSimd, Mii, ModuloCompiler, ModuloScheduler}
import vliw.isa.MachineDescription

import LaneReason._

/** Body packing of a loop (ISA mvp-2, #110 stage 5b; docs/COMPILER.md "ループのベクトル化と Modulo
  * Scheduling（W-full）"): `LoopPack`, the loop port of `dag.Pack`. The packed graphs of matmul3,
  * their values against the loop's, one minimal loop per rule and lane reason, vector states, LD.W
  * chains, and the one-iteration oracle against `Pack`.
  */
class LoopPackSpec extends AnyFunSuite {
  private def load(path: String*) = MachineDescription
    .load(Paths.get(path.head, path.tail: _*))
    .fold(es => fail(es.mkString("\n")), identity)
  private val full = load("config", "machine-mvp2.json")
  private val mw1 = load("tests", "machines", "mvp2_mw1.json")

  /** G: the fused scalar loop graph of `k` on W-full, with its lowering. */
  private def scalar(k: LoopKernel) = {
    val layout = LoopLowering.layout(k, full).fold(e => fail(e), identity)
    val lowered = LoopLowering.lower(k, layout)
    (FusionChoice.of(LoopGraph.of(lowered, full))._1, lowered)
  }

  /** The packed graph's unrolled DAG, run in `orders` random orders that respect its edges
    * (`DagRun`), gives the memory of `LoopLowering.evaluate`.
    */
  private def sameValues(v: LoopGraph, loop: Loop, words: Int, rng: Random, orders: Int = 3) = {
    val memory = (0 until words).map(_ -> (rng.nextLong() & 0xffffffffL)).toMap
    val want = LoopLowering.evaluate(loop, memory)
    val dag = v.expanded.dag
    (0 until orders).foreach { _ =>
      assert(DagRun.execute(dag, DagRun.randomOrder(dag, rng), memory) == want)
    }
  }
  private def sameValues(k: LoopKernel, rng: Random): LoopPacking = {
    val (g, lowered) = scalar(k)
    val p = LoopPack.pack(g)
    sameValues(p.graph, lowered.loop, lowered.layout.words, rng)
    p
  }

  /** `k` compiled by `ModuloCompiler` on W-full gives the DSL's outputs (`IsaExecutor`). */
  private def correct(k: LoopKernel): Unit = {
    val c = ModuloCompiler.compile(k, full).fold(e => fail(e), identity)
    (1L to 2L).foreach { s =>
      val in = KernelCompiler.sampleInputs(c.kernel.unroll, s)
      val image = c.layout.memoryImage(in).fold(e => fail(e), identity)
      assert(
        c.layout.outputs(IsaExecutor.run(c.machine, c.bundles, image)) ==
          Interpreter.run(c.kernel.unroll, in).fold(e => fail(e), identity),
        k.name
      )
    }
  }

  private def lanes(b: LoopBody) = b.scalarLanes.map(l => (l.addr.base, l.reason, l.initialPair))
  import Place.{After, Before, Each}

  test(
    "matmul3 (i): the pair (c(i,0), c(i,1)), a(3i+m) broadcast, LD.W of b's rows before the loop"
  ) {
    val p = sameValues(LoopBenchmarks.matmulRows(3), new Random(1))
    // a 0-8, b 9-17, c 18-26 (COMPILER.md); c(i,2) stays scalar (NoNeighbour).
    assert(
      p.body == LoopBody(
        Vector(LoopPair(Each, Address(18, 3))),
        Vector(LoopLane(Each, Address(20, 3), NoNeighbour)),
        0
      )
    )
    def r(n: Int) = Ref(n)
    assert(
      p.graph.nodes == Vector(
        LoopNode.Load(Address(2, 3)),
        LoopNode.LoadW(Address(15, 0)),
        LoopNode.Load(Address(1, 3)),
        LoopNode.LoadW(Address(12, 0)),
        LoopNode.Load(Address(0, 3)),
        LoopNode.LoadW(Address(9, 0)),
        LoopNode.VCompute(Op.Mul, VRef.Broadcast(r(4)), VRef.Pair(r(5))),
        LoopNode.VMac(VRef.Broadcast(r(2)), VRef.Pair(r(3)), VRef.Pair(r(6))),
        LoopNode.VMac(VRef.Broadcast(r(0)), VRef.Pair(r(1)), VRef.Pair(r(7))),
        LoopNode.StoreW(Address(18, 3), VRef.Pair(r(8))),
        LoopNode.Load(Address(17, 0)),
        LoopNode.Load(Address(14, 0)),
        LoopNode.Load(Address(11, 0)),
        LoopNode.Compute(Op.Mul, r(4), r(12)),
        LoopNode.Mac(r(2), r(11), r(13)),
        LoopNode.Mac(r(0), r(10), r(14)),
        LoopNode.Store(Address(20, 3), r(15))
      )
    )
    assert(
      p.graph.places == Vector(Each, Before, Each, Before, Each, Before) ++
        Vector.fill(4)(Each) ++ Vector.fill(3)(Before) ++ Vector.fill(4)(Each)
    )
  }

  test(
    "matmul3 (m): three pairs of finals, vector states with VMUL initial pairs before the loop"
  ) {
    val p = sameValues(LoopBenchmarks.matmulAcc(3), new Random(2))
    assert(p.body.pairs == Vector(18, 21, 24).map(w => LoopPair(After, Address(w, 0))))
    assert(lanes(p.body) == Vector(20, 23, 26).map(w => (w, NoNeighbour, false)))
    assert(p.body.vectorStates == 3 && p.graph.states.size == 3)
    val g = p.graph
    // The vector states: VMAC(a(m) broadcast, LD.W b(m)'s row, itself one iteration back), whose
    // value in iteration 0 is VMUL(a(0) broadcast, LD.W [9]) before the loop.
    val states = g.nodes.indices.flatMap { n =>
      g.nodes(n) match {
        case LoopNode.VMac(VRef.Broadcast(a), VRef.Pair(b), VRef.Pair(Ref(`n`, 1, Vector(i)))) =>
          Some((n, a, b, i))
        case _ => None
      }
    }
    assert(states.size == 3)
    states.foreach { case (n, a, b, i) =>
      assert(g.places(n) == Each && g.nodes(b.node) == LoopNode.LoadW(Address(12, 3)))
      assert(g.places(a.node) == Each && g.nodes(a.node).isInstanceOf[LoopNode.Load])
      assert(g.places(i) == Before && (g.nodes(i) match {
        case LoopNode.VCompute(Op.Mul, VRef.Broadcast(_), VRef.Pair(w)) =>
          g.nodes(w.node) == LoopNode.LoadW(Address(9, 0)) && g.places(w.node) == Before
        case _ => false
      }))
    }
    // Before: LD 4, LD.W 1, MUL 3, VMUL 3 (the initial pairs); each: LD 4, LD.W 1, MAC 3, VMAC 3;
    // after: ST 3, ST.W 3.
    def mnemonics(p: Place) = g.ids(p).map(n => g.nodes(n).mnemonic).groupBy(identity).map {
      case (m, ns) => m -> ns.size
    }
    assert(mnemonics(Before) == Map("LD" -> 4, "LD.W" -> 1, "MUL" -> 3, "VMUL" -> 3))
    assert(mnemonics(Each) == Map("LD" -> 4, "LD.W" -> 1, "MAC" -> 3, "VMAC" -> 3))
    assert(mnemonics(After) == Map("ST" -> 3, "ST.W" -> 3))
    // As the list W-full DAG (#78's row pairs): LD 12 and LD.W 3 in the program.
    val dag = g.expanded.dag
    assert(dag.nodes.count(_.mnemonic == "LD") == 12 && dag.nodes.count(_.mnemonic == "LD.W") == 3)
    val i = LoopPack.pack(scalar(LoopBenchmarks.matmulRows(3))._1).graph.expanded.dag
    assert(i.nodes.count(_.mnemonic == "LD") == 12 && i.nodes.count(_.mnemonic == "LD.W") == 3)
  }

  test("rule 1: the candidates by address, and the two passes of Pack.storePairs") {
    def candidates(k: LoopKernel) = LoopPack.byAddress(scalar(k)._1)
    Seq(LoopBenchmarks.saxpy(7), LoopBenchmarks.fir(Seq(1L, 2L, 3L, 4L), 7))
      .foreach(k => assert(candidates(k).isEmpty, k.name))
    Seq(LoopBenchmarks.product(8), LoopBenchmarks.dot(8))
      .foreach(k => assert(candidates(k).isEmpty, k.name))
    // matmul3 (i): c(3i) and c(3i+1), c(3i+1) and c(3i+2), stride 3; (m): the finals c(0) to c(8).
    assert(
      candidates(LoopBenchmarks.matmulRows(3)).map(c => (c.place, c.addr)) ==
        Vector((Each, Address(18, 3)), (Each, Address(19, 3)))
    )
    assert(candidates(LoopBenchmarks.matmulAcc(3)).map(_.addr.base) == (18 to 25))
    // A body store next to a final is no candidate; two body stores with other strides neither.
    assert(candidates(LoopKernel("bf", "i", 1) { b =>
      val x = b.input("x", 1); val o = b.output("o", 2)
      val s = b.state("s", b.const(0)); b.body { i => s := s.value + x(i); o(i) = x(i) }
      o(1) = s
    }).isEmpty)
    assert(candidates(LoopKernel("st", "i", 2) { b =>
      val x = b.input("x", 2); val o = b.output("o", 4)
      b.body { i => o(i) = x(i); o(i * -1 + 3) = x(i) }
    }).isEmpty)
    // Pass 1 (gather-free) and pass 2 (all), as Pack.storePairs: matmul3 (m) tries the row pairs
    // (c(i,0), c(i,1)) first; by address, (c(0,2), c(1,0)) would come before (c(1,0), c(1,1)).
    val m = scalar(LoopBenchmarks.matmulAcc(3))._1
    val free = LoopPack.byAddress(m).filter(LoopPack.gatherFree(m, _))
    assert(free.map(_.addr.base) == Vector(18, 19, 21, 22, 24, 25))
    for (k <- LoopBenchmarks.all ++ RandomLoops.packable(5, 60)) {
      val (g, _) = scalar(k)
      val all = LoopPack.byAddress(g)
      assert(LoopPack.storePairs(g) == all.filter(LoopPack.gatherFree(g, _)) ++ all, k.name)
    }
    // A store of a state value is tried in pass 2 only (in iteration 0 it stores the initial
    // values), and so is a pair of a value before the loop and one of the iteration.
    val st = scalar(stateStore(finals = false))._1
    assert(
      LoopPack.byAddress(st).nonEmpty && LoopPack.byAddress(st).forall(!LoopPack.gatherFree(st, _))
    )
    intercept[IllegalArgumentException](
      LoopPack.pack(
        LoopGraph.of(
          LoopLowering.lower(
            LoopBenchmarks.matmulAcc(3),
            LoopLowering.layout(LoopBenchmarks.matmulAcc(3), mw1).fold(e => fail(e), identity)
          ),
          mw1
        )
      )
    )
  }

  test("#110 第4段の Q1 (a), one pass by address: matmul3 (m) would pair across rows and fall back") {
    // (a) pairs (18,19), (20,21), (22,23) and (24,25): (c(0,2), c(1,0)) crosses rows, so a(m) and
    // a(m+3), and b(3m+2) and b(3m), are gathered. MII 10 against G's 9; no W II fits on W-full.
    val (m, _) = scalar(LoopBenchmarks.matmulAcc(3))
    val a = LoopPack.packInOrder(m, LoopPack.byAddress(m))
    assert(a.body.pairs.map(_.addr.base) == Vector(18, 20, 22, 24))
    assert(Mii.of(a.graph).mii == 10 && Mii.of(m).mii == 9)
    assert(ModuloScheduler.schedule(a.graph).isLeft)
    // (i): its only gather-free candidate is also the lowest, so (a) is (b).
    val (i, _) = scalar(LoopBenchmarks.matmulRows(3))
    assert(LoopPack.packInOrder(i, LoopPack.byAddress(i)) == LoopPack.pack(i))
    // packable(5, 300): the loops that are not doubled where (a) and (b) pack differently, by
    // whether each packed graph schedules on W-full, and where both do, by (b)'s cycles against
    // (a)'s.
    val counts = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    for (k <- RandomLoops.packable(5, 300)) {
      val p = ModuloCompiler.prepare(k, full).fold(e => fail(e), identity)
      val doubled = p.simd match {
        case LoopSimd.Vectorized(d) => d.doubled
        case _                      => false
      }
      if (!doubled) {
        val (pa, pb) =
          (LoopPack.packInOrder(p.scalar, LoopPack.byAddress(p.scalar)), LoopPack.pack(p.scalar))
        if (pa != pb) {
          counts("differ") += 1
          def run(x: LoopPacking) =
            Option.when(!x.body.nothingToPack)(ModuloScheduler.schedule(x.graph).toOption).flatten
          (run(pa), run(pb)) match {
            case (Some(sa), Some(sb)) =>
              counts(s"both, (b) ${sb.cycles.compare(sa.cycles).sign}") += 1
            case (Some(_), None) => counts("(a) only") += 1
            case (None, Some(_)) => counts("(b) only") += 1
            case (None, None)    => counts("neither") += 1
          }
        }
      }
    }
    assert(
      counts.toMap == Map(
        "differ" -> 18,
        "both, (b) -1" -> 7,
        "both, (b) 1" -> 1,
        "(a) only" -> 1,
        "neither" -> 9
      )
    )
  }

  /** `o(2i) = s0`, `o(2i+1) = s1` with s0 and s1 the sums of x(2i) and x(2i+1) from y(0) and y(1)
    * (adjacent: a LD.W before the loop), with or without the finals `f(0) = s0`, `f(1) = s1`.
    */
  private def stateStore(finals: Boolean) = LoopKernel("ss", "i", 3) { b =>
    val x = b.input("x", 6); val y = b.input("y", 2); val o = b.output("o", 6)
    val f = Option.when(finals)(b.output("f", 2))
    val s0 = b.state("s0", y(0)); val s1 = b.state("s1", y(1))
    b.body { i =>
      o(i * 2) = s0.value; o(i * 2 + 1) = s1.value
      s0 := s0.value + x(i * 2); s1 := s1.value + x(i * 2 + 1)
    }
    f.foreach { o => o(0) = s0; o(1) = s1 }
  }

  test("rule 2: SameValue outside a multiplication's rs1, and at a vector state's initial value") {
    val k = LoopKernel("sv", "i", 3) { b =>
      val x = b.input("x", 3); val y = b.input("y", 3); val a = b.scalar("a")
      val o = b.output("o", 6)
      b.body { i => o(i * 2) = x(i) + a; o(i * 2 + 1) = y(i) + a }
    }
    val (g, lowered) = scalar(k)
    val p = LoopPack.pack(g)
    val o = lowered.layout.elem("o", 0)
    assert(
      p.body.nothingToPack && lanes(p.body) == Vector(o, o + 1).map(w =>
        (w, SameValue("rs2 of ADD"), false)
      )
    )
    assert(p.graph == g)
    // Equal initial values: the loop IR gives a state's iteration-0 value as one node, so the
    // initial pair must be one pair node (`initialPair`; Pack would broadcast it at a MUL's rs1).
    for (product <- Seq(false, true)) {
      val (g1, l1) = scalar(LoopKernel("init", "i", 3) { b =>
        val x = b.input("x", 6); val f = b.output("f", 2)
        val s0 = b.state("s0", b.const(1)); val s1 = b.state("s1", b.const(1))
        b.body { i =>
          if (product) { s0 := s0.value * x(i * 2); s1 := s1.value * x(i * 2 + 1) }
          else { s0 := s0.value + x(i * 2); s1 := s1.value + x(i * 2 + 1) }
        }
        f(0) = s0; f(1) = s1
      })
      val f = l1.layout.elem("f", 0)
      assert(
        lanes(LoopPack.pack(g1).body) == Vector(f, f + 1).map(w =>
          (w, SameValue("the initial value of a state"), true)
        )
      )
    }
  }

  test(
    "rule 4: NeededElsewhere for a shared lane, a computed value before the loop, a gathered initial pair, crossed states, a state next to another value"
  ) {
    def body(build: LoopKernel.Builder => Unit, trips: Int = 3) = {
      val (g, lowered) = scalar(LoopKernel("r4", "i", trips)(build))
      (g, LoopPack.pack(g).body, lowered.layout)
    }
    // u = x(i) * a is lane 0 of VMUL and also stored by p(i): NeededElsewhere(u).
    val (g1, b1, l1) = body { b =>
      val x = b.input("x", 3); val y = b.input("y", 3); val z = b.input("z", 3)
      val w = b.input("w", 3); val a = b.scalar("a")
      val o = b.output("o", 6); val p = b.output("p", 3)
      b.body { i =>
        val u = x(i) * a
        o(i * 2) = u - y(i); o(i * 2 + 1) = z(i) * a - w(i); p(i) = u
      }
    }
    val u = g1.nodes.indices
      .find(n =>
        g1.nodes(n) match {
          case LoopNode.Compute(Op.Mul, x, _) =>
            g1.nodes(x.node) == LoopNode.Load(Address(l1.elem("x", 0), 1))
          case _ => false
        }
      )
      .get
    val o1 = l1.elem("o", 0)
    assert(
      lanes(b1) == Vector(
        (o1, NeededElsewhere(u), false),
        (o1 + 1, NeededElsewhere(u), false),
        (l1.elem("p", 0), NoNeighbour, false)
      )
    )
    // a * a before the loop next to y(i): a lane of every iteration's gather (the loop-only case
    // of rule 4); Pack's walk of one iteration gathers it.
    val (g2, b2, l2) = body { b =>
      val x = b.input("x", 3); val y = b.input("y", 3); val z = b.input("z", 3)
      val a = b.scalar("a"); val o = b.output("o", 6)
      b.body { i => o(i * 2) = a * a - x(i); o(i * 2 + 1) = y(i) - z(i) }
    }
    val aa = g2.nodes.indices.find(n => g2.places(n) == Before && g2.nodes(n).mnemonic == "MUL").get
    assert(lanes(b2) == Vector(0, 1).map(d => (l2.elem("o", 0) + d, NeededElsewhere(aa), false)))
    assert(LoopPack.packOneIteration(g2).pairs.size == 1)
    // A gathered initial pair, x(0) and x(2): the loop IR's limit (initialPair).
    val (g3, b3, l3) = body { b =>
      val x = b.input("x", 3); val y = b.input("y", 6); val f = b.output("f", 2)
      val s0 = b.state("s0", x(0)); val s1 = b.state("s1", x(2))
      b.body { i => s0 := s0.value + y(i * 2); s1 := s1.value + y(i * 2 + 1) }
      f(0) = s0; f(1) = s1
    }
    val x0 = g3.nodes.indexOf(LoopNode.Load(Address(l3.elem("x", 0), 0)))
    assert(lanes(b3) == Vector(0, 1).map(d => (l3.elem("f", 0) + d, NeededElsewhere(x0), true)))
    // Crossed states: s0 takes s1's value and s1 takes s0's; the lanes would swap.
    val (g4, b4, l4) = body { b =>
      val x = b.input("x", 6); val y = b.input("y", 2); val f = b.output("f", 2)
      val s0 = b.state("s0", y(0)); val s1 = b.state("s1", y(1))
      b.body { i => s0 := s1.value + x(i * 2); s1 := s0.value + x(i * 2 + 1) }
      f(0) = s0; f(1) = s1
    }
    val n1 = g4.states(1).next
    assert(lanes(b4) == Vector(0, 1).map(d => (l4.elem("f", 0) + d, NeededElsewhere(n1), false)))
    // A state value next to a value of the iteration: the state stays in its own register.
    val (g5, b5, l5) = body { b =>
      val x = b.input("x", 3); val z = b.input("z", 3); val w = b.input("w", 3)
      val y = b.input("y", 3); val o = b.output("o", 6); val f = b.output("f", 1)
      val s = b.state("s", b.const(0))
      b.body { i => s := s.value + x(i); o(i * 2) = s.value - y(i); o(i * 2 + 1) = z(i) - w(i) }
      f(0) = s
    }
    val n5 = g5.states(0).next
    assert(
      lanes(b5) == Vector(
        (l5.elem("o", 0), NeededElsewhere(n5), false),
        (l5.elem("o", 0) + 1, NeededElsewhere(n5), false),
        (l5.elem("f", 0), NoNeighbour, false)
      )
    )
    // Two finals of different operations (s0 + x(i), s1 · x(i)): a gather after the loop of the
    // last iteration's values, as Pack gathers them (W-full: the ST.W of a gathered root).
    val fin = LoopKernel("fin", "i", 3) { b =>
      val x = b.input("x", 3); val f = b.output("f", 2)
      val s0 = b.state("s0", b.const(0)); val s1 = b.state("s1", b.const(1))
      b.body { i => s0 := s0.value + x(i); s1 := s1.value * x(i) }
      f(0) = s0; f(1) = s1
    }
    val (g6, l6) = scalar(fin)
    val p6 = sameValues(fin, new Random(7))
    assert(
      p6.body == LoopBody(Vector(LoopPair(After, Address(l6.layout.elem("f", 0), 0))), Vector(), 0)
    )
    val (n60, n61) = (g6.states(0).next, g6.states(1).next)
    assert(
      p6.graph.ids(After).map(p6.graph.nodes) == Vector(
        LoopNode.StoreW(
          Address(l6.layout.elem("f", 0), 0),
          VRef.Gather(Ref(p6.graph.states(0).next), Ref(p6.graph.states(1).next))
        )
      )
    )
    assert(Seq(n60, n61).map(g6.nodes(_).mnemonic) == Seq("ADD", "MUL"))
    correct(fin)
  }

  test(
    "Cycle and StoredTwice on hand-made loops; a pair across a ST -> LD edge keeps the memory order"
  ) {
    // [21 + 2k] = LD [20 + 2k] - ..., where [20 + 2k] is stored before it in the iteration: the
    // ST.W would come before and after that LD.
    val cyclic = LoopGraph.build(
      full,
      Loop(
        2,
        Vector(),
        Vector(
          Write(Address(20, 2), Bin(Op.Sub, Read(Address(0, 2)), Read(Address(1, 2)))),
          Write(Address(21, 2), Bin(Op.Sub, Read(Address(20, 2)), Read(Address(3, 2))))
        ),
        Vector()
      )
    )
    assert(lanes(LoopPack.pack(cyclic).body) == Vector((20, Cycle, false), (21, Cycle, false)))
    // [21 + 2k] is stored twice.
    val twice = LoopGraph.build(
      full,
      Loop(
        2,
        Vector(),
        Vector(
          Write(Address(20, 2), Bin(Op.Sub, Read(Address(0, 2)), Read(Address(1, 2)))),
          Write(Address(21, 2), Bin(Op.Sub, Read(Address(4, 2)), Read(Address(5, 2)))),
          Write(Address(21, 2), Bin(Op.Add, Read(Address(4, 2)), Read(Address(5, 2))))
        ),
        Vector()
      )
    )
    assert(lanes(LoopPack.pack(twice).body).map(_._2).distinct == Vector(StoredTwice))
    // o(2k) = x(k) + c0; y(k) = e; o(2k + 1) = y(k) + c1: the pair is accepted, and its ST.W's
    // tree reads y(k) after y(k) is stored (the packed graph keeps G's memory order).
    val loop = Loop(
      3,
      Vector(),
      Vector(
        Write(Address(30, 2), Bin(Op.Add, Read(Address(0, 1)), Read(Address(10, 0)))),
        Write(Address(20, 1), Bin(Op.Sub, Read(Address(3, 1)), Read(Address(6, 1)))),
        Write(Address(31, 2), Bin(Op.Add, Read(Address(20, 1)), Read(Address(11, 0))))
      ),
      Vector()
    )
    val p = LoopPack.pack(LoopGraph.build(full, loop))
    assert(p.body.pairs == Vector(LoopPair(Each, Address(30, 2))))
    val v = p.graph
    val st = v.nodes.indexWhere {
      case LoopNode.Store(Address(20, 1), _) => true
      case _                                 => false
    }
    assert(st >= 0 && st < v.nodes.indexOf(LoopNode.Load(Address(20, 1))))
    sameValues(v, loop, 40, new Random(3))
  }

  test(
    "the build makes a value read with back > 0 after its root: a recurrence, a state that loads a stored word, one VMUL per vector state"
  ) {
    val rng = new Random(6)
    // s := s * 3 + z(i) next to an unrelated pair: p(i) = s * 3 reads s's next value with back 1,
    // and that next value reads s * 3 with back 0.
    val rec = LoopKernel("rec", "i", 4) { b =>
      val x = b.input("x", 8); val y = b.input("y", 8); val z = b.input("z", 4)
      val o = b.output("o", 8); val p = b.output("p", 4); val f = b.output("f", 1)
      val s = b.state("s", b.const(1))
      b.body { i =>
        o(i * 2) = x(i * 2) + y(i * 2); o(i * 2 + 1) = x(i * 2 + 1) + y(i * 2 + 1)
        p(i) = s.value * b.const(3)
        s := s.value * b.const(3) + z(i)
      }
      f(0) = s
    }
    assert(sameValues(rec, rng).body.pairs.size == 1)
    val c = ModuloCompiler.compile(rec, full).fold(e => fail(e), identity)
    assert(c.simd.isInstanceOf[LoopSimd.Vectorized] && (c.ii, c.cycles) == ((6, 29)))
    correct(rec)
    // The pair's tree reads a state (a broadcast, or a vector state) whose next value loads a word
    // that the pair stores: the ST.W comes before that LD, as the ST does in G.
    // Lane l: [20 + l + 2k] = s0 · [l + 2k] (s0 broadcast) or s_l · [52] (a vector state), with
    // s_l := s_l + [20 + l + 2k].
    def loop(vectorState: Boolean) = {
      def value(l: Int) =
        if (vectorState) Bin(Op.Mul, StateRef(l), Read(Address(52, 0)))
        else Bin(Op.Mul, StateRef(0), Read(Address(l, 2)))
      Loop(
        3,
        Vector(Read(Address(50, 0)), Read(Address(51, 0))),
        Vector(
          Write(Address(20, 2), value(0)),
          Write(Address(21, 2), value(1)),
          Update(0, Bin(Op.Add, StateRef(0), Read(Address(20, 2)))),
          Update(1, Bin(Op.Add, StateRef(1), Read(Address(21, 2))))
        ),
        Vector()
      )
    }
    for (vectorState <- Seq(false, true)) {
      val l = loop(vectorState)
      val p = LoopPack.pack(LoopGraph.build(full, l))
      assert(p.body.pairs == Vector(LoopPair(Each, Address(20, 2))), vectorState)
      assert(p.body.vectorStates == (if (vectorState) 1 else 0))
      val stw = p.graph.nodes.indexWhere(_.isInstanceOf[LoopNode.StoreW])
      assert(stw >= 0 && stw < p.graph.nodes.indexOf(LoopNode.Load(Address(20, 2))), vectorState)
      sameValues(p.graph, l, 64, rng)
    }
    // Two first-order IIR filters, o(2i + l) = a · s_l and s_l := a · s_l + x(2i + l): one VMUL,
    // stored by the ST.W and read by the next value.
    val iir = LoopKernel("iir2", "i", 4) { b =>
      val x = b.input("x", 8); val y = b.input("y", 2); val a = b.scalar("a")
      val o = b.output("o", 8)
      val s0 = b.state("s0", y(0)); val s1 = b.state("s1", y(1))
      b.body { i =>
        o(i * 2) = a * s0.value; o(i * 2 + 1) = a * s1.value
        s0 := a * s0.value + x(i * 2); s1 := a * s1.value + x(i * 2 + 1)
      }
    }
    val q = sameValues(iir, rng)
    assert(q.body.vectorStates == 1 && q.graph.nodes.count(_.mnemonic == "VMUL") == 1)
    val ci = ModuloCompiler.compile(iir, full).fold(e => fail(e), identity)
    assert((ci.ii, ci.cycles) == ((3, 16)))
    correct(iir)
  }

  test("vector states: a store of a state pair, a state in a pair's tree, an LD.W initial pair") {
    val rng = new Random(4)
    // Without finals, o(2i) = s0, o(2i+1) = s1 packs: ST.W of the vector state one iteration back.
    val k = stateStore(finals = false)
    val p = sameValues(k, rng)
    val (g, lowered) = scalar(k)
    assert(p.body.pairs == Vector(LoopPair(Each, Address(lowered.layout.elem("o", 0), 2))))
    assert(p.body.vectorStates == 1 && p.graph.states.isEmpty)
    val v = p.graph
    val vec = v.nodes.indexWhere(_.isInstanceOf[LoopNode.VCompute])
    val initial = v.nodes.indexOf(LoopNode.LoadW(Address(lowered.layout.elem("y", 0), 0)))
    assert(
      v.nodes(vec) == LoopNode.VCompute(
        Op.Add,
        VRef.Pair(Ref(vec, 1, Vector(initial))),
        VRef.Pair(Ref(v.nodes.indexOf(LoopNode.LoadW(Address(lowered.layout.elem("x", 0), 2)))))
      )
    )
    assert(
      v.nodes.contains(
        LoopNode.StoreW(
          Address(lowered.layout.elem("o", 0), 2),
          VRef.Pair(Ref(vec, 1, Vector(initial)))
        )
      )
    )
    assert(v.places(initial) == Before)
    correct(k)
    // With the finals too, each pair reads the state that the other one stores: neither packs (a
    // reader not walked yet is a scalar, #110 第4段の Q3).
    val (gf, lf) = scalar(stateStore(finals = true))
    val n0 = gf.states(0).next
    assert(lanes(LoopPack.pack(gf).body).map(_._2).distinct == Vector(NeededElsewhere(n0)))
    assert(
      LoopPack.pack(gf).body.scalarLanes.map(_.addr.base).toSet ==
        Set("o", "f").flatMap(a => Set(0, 1).map(lf.layout.elem(a, _)))
    )
    // A state value in a pair's tree: VSUB(the vector state, LD.W x).
    val tree = LoopKernel("tree", "i", 3) { b =>
      val x = b.input("x", 6); val y = b.input("y", 2); val z = b.input("z", 6)
      val o = b.output("o", 6)
      val s0 = b.state("s0", y(0)); val s1 = b.state("s1", y(1))
      b.body { i =>
        o(i * 2) = s0.value - z(i * 2); o(i * 2 + 1) = s1.value - z(i * 2 + 1)
        s0 := s0.value * x(i * 2); s1 := s1.value * x(i * 2 + 1)
      }
    }
    val t = sameValues(tree, rng)
    assert(t.body.pairs.size == 1 && t.body.vectorStates == 1)
    assert(t.graph.nodes.exists {
      case LoopNode.VCompute(Op.Sub, VRef.Pair(Ref(n, 1, _)), _) =>
        t.graph.nodes(n).isInstanceOf[LoopNode.VCompute]
      case _ => false
    })
    correct(tree)
  }

  test(
    "LD.W chains of stride 1, -1 and 3; gathers before the loop; a LD before the loop gathered in the body"
  ) {
    val rng = new Random(5)
    // Lane l reads x(s·i + b + l) and x(s·i + b + s + l): two LD.W of stride s, one chain whose
    // leader reads each word first (the highest base for s > 0, the lowest for s < 0); the other
    // read takes it one iteration back, and from the LD.W before the loop in iteration 0.
    for ((s, base, leader, other) <- Seq((1, 0, 1, 0), (-1, 4, 3, 4), (3, 0, 3, 0))) {
      val k = LoopKernel(s"chain${if (s < 0) "m" else ""}${s.abs}", "i", 3) { b =>
        val x = b.input("x", 12); val o = b.output("o", 6)
        b.body { i =>
          o(i * 2) = x(i * s + base) + x(i * s + base + s)
          o(i * 2 + 1) = x(i * s + base + 1) + x(i * s + base + s + 1)
        }
      }
      val p = sameValues(k, rng)
      val (_, lowered) = scalar(k)
      val x = lowered.layout.elem("x", 0)
      val v = p.graph
      val lead = v.nodes.indexOf(LoopNode.LoadW(Address(x + leader, s)))
      val init = v.nodes.indexOf(LoopNode.LoadW(Address(x + other, 0)))
      assert(lead >= 0 && init >= 0 && v.places(init) == Before, s)
      assert(
        v.nodes.exists {
          case LoopNode.VCompute(Op.Add, a, b) =>
            Set(a, b) == Set(VRef.Pair(Ref(lead)), VRef.Pair(Ref(lead, 1, Vector(init))))
          case _ => false
        },
        s
      )
      correct(k)
    }
    // (a, c) before the loop: a gather before the loop, read by every iteration; and z(i) next to
    // `a`: a fresh LD of `a`'s word in every iteration (rule 4's LD copy, an exception to
    // ADR-0014).
    val k = LoopKernel("before", "i", 3) { b =>
      val x = b.input("x", 3); val y = b.input("y", 3); val z = b.input("z", 3)
      val a = b.scalar("a"); val m = b.scalar("m"); val c = b.scalar("c")
      val o = b.output("o", 6); val p = b.output("p", 3); val q = b.output("q", 6)
      b.body { i =>
        o(i * 2) = x(i) - a; o(i * 2 + 1) = y(i) - c; p(i) = m * x(i)
        q(i * 2) = a - y(i); q(i * 2 + 1) = z(i) - x(i)
      }
    }
    val p = sameValues(k, rng)
    val (_, lowered) = scalar(k)
    val v = p.graph
    val (wa, wc) = (lowered.layout.scalar("a"), lowered.layout.scalar("c"))
    assert(p.body.pairs.size == 2)
    assert(
      v.nodes.indices.exists(n =>
        v.nodes(n) match {
          case LoopNode.VCompute(Op.Sub, _, VRef.Gather(lo, hi)) =>
            v.places(lo.node) == Before && v.places(hi.node) == Before &&
            v.nodes(lo.node) == LoopNode.Load(Address(wa, 0)) && v.nodes(hi.node) == LoopNode.Load(
              Address(wc, 0)
            )
          case _ => false
        }
      )
    )
    assert(v.ids(Each).exists(n => v.nodes(n) == LoopNode.Load(Address(wa, 0))))
    correct(k)
  }

  test("random packable loops: the packed graph computes the loop, LD.W chains of every stride") {
    val rng = new Random(110)
    // packable(13, 60) with `chains`: the packed loops with a LD.W read one iteration back or more
    // (`LoopVector.chains`), by stride.
    val chained = scala.collection.mutable.Map.empty[Int, Int].withDefaultValue(0)
    val sets = Seq(
      RandomLoops.packable(5, 100) -> false,
      RandomLoops.packable(7, 60, stateStores = true) -> false,
      RandomLoops.packable(13, 60, chains = true) -> true
    )
    for ((ks, chains) <- sets; k <- ks) {
      val (g, lowered) = scalar(k)
      if (LoopVector.reasons(g).nonEmpty) {
        val p = LoopPack.pack(g)
        if (!p.body.nothingToPack) {
          sameValues(p.graph, lowered.loop, lowered.layout.words, rng, 1)
          val v = p.graph
          if (chains)
            v.ids(Each)
              .flatMap(n => v.nodes(n).operands)
              .collect { case Ref(n, back, _) if back > 0 && v.places(n) == Each => v.nodes(n) }
              .collect { case LoopNode.LoadW(a) => a.stride }
              .distinct
              .foreach(chained(_) += 1)
        }
      }
    }
    assert(chained.toMap == Map(1 -> 3, 2 -> 2, -1 -> 1, 3 -> 2))
  }

  /** `Pack` on the straight-line DAG of one iteration of the state-free G: every read without
    * ADR-0014's sharing is a LD of a synthetic word, and every store a ST of one, each (read or
    * store, place, stride) class in a range of its own, so that "adjacent" means LoopPack's rule
    *   3. The pairs and lane reasons, by (place, address). None when G has states.
    */
  private def onePack(
      g: LoopGraph
  ): Option[(Set[(Place, Address)], Map[(Place, Address), String])] =
    Option.when(g.states.isEmpty && g.ids(After).isEmpty) {
      val classes = scala.collection.mutable.LinkedHashMap.empty[(Boolean, Place, Int), Int]
      def word(store: Boolean, p: Place, a: Address) =
        100000 * (classes.getOrElseUpdate((store, p, a.stride), classes.size) + 1) + 50000 + a.base
      val stored = scala.collection.mutable.Map.empty[Int, (Place, Address)]
      val nodes = scala.collection.mutable.ArrayBuffer.empty[Node]
      val edges = scala.collection.mutable.ArrayBuffer.empty[Edge]
      val loads = scala.collection.mutable.Map.empty[Int, Int]
      val ids = scala.collection.mutable.Map.empty[Int, Int]
      def operand(r: Ref): Int = LoopVector.unshared(g, r) match {
        case Some((p, a)) =>
          loads.getOrElseUpdate(
            word(store = false, p, a), {
              nodes += Node.Load(word(store = false, p, a)); nodes.size - 1
            }
          )
        case None => assert(r.back == 0); ids(r.node)
      }
      def add(n: Int, node: Node): Unit = {
        nodes += node
        ids(n) = nodes.size - 1
        node.operands.distinct.foreach(f =>
          edges += Edge(f, nodes.size - 1, full.operation(nodes(f).mnemonic).latency, EdgeKind.Data)
        )
      }
      g.nodes.indices.foreach { n =>
        g.nodes(n) match {
          case _: LoopNode.Load           => ()
          case LoopNode.Compute(op, a, b) => add(n, Node.Compute(op, operand(a), operand(b)))
          case LoopNode.Mac(a, b, c)      => add(n, Node.Mac(operand(a), operand(b), operand(c)))
          case LoopNode.Store(a, v)       =>
            val w = word(store = true, g.places(n), a)
            stored(w) = (g.places(n), a)
            add(n, Node.Store(w, operand(v)))
          case other => fail(s"$other")
        }
      }
      val packed = Pack.pack(Dag(full, nodes.toVector, edges.toVector))
      (
        packed.pairs.map(x => stored(x.addr)).toSet,
        packed.scalarLanes.map(l => stored(l.addr) -> name(l.reason)).toMap
      )
    }
  private def name(r: LaneReason) = r match {
    case SameValue(p) => s"SameValue($p)"
    case other        => other.name
  }
  private def decisions(b: LoopBody) = (
    b.pairs.map(x => (x.place, x.addr)).toSet,
    b.scalarLanes.map(l => (l.place, l.addr) -> name(l.reason)).toMap
  )

  test(
    "the one-iteration oracle: LoopPack's walk of one iteration is Pack's on 570 state-free loops"
  ) {
    // The state-free G of the three generators, doubled or not (#110 第5段の計画 8.4). The loop
    // differs from that walk only where a computed value before the loop would be a lane next to a
    // value of the iteration (the loop-only case of rule 4).
    val sets = Seq(
      RandomLoops.kernels(76, 300).map(_._1) -> ((80, 4)),
      RandomLoops.vectorizable(110, 300) -> ((300, 0)),
      RandomLoops.packable(5, 300) -> ((190, 11))
    )
    for ((ks, (free, loopOnly)) <- sets) {
      var (n, differ) = (0, 0)
      ks.foreach { k =>
        val g = scalar(k)._1
        onePack(g).foreach { pack =>
          n += 1
          assert(decisions(LoopPack.packOneIteration(g)) == pack, k.name)
          if (decisions(LoopPack.pack(g).body) != pack) differ += 1
        }
      }
      assert((n, differ) == ((free, loopOnly)))
    }
  }
}
