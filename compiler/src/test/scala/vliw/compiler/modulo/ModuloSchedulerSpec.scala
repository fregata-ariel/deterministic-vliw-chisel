package vliw.compiler.modulo

import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable

import vliw.compiler.{KernelCompiler, Verifier}
import vliw.compiler.dag.Node
import vliw.compiler.dsl.{Benchmarks, Interpreter, Op}
import vliw.compiler.loop._
import vliw.compiler.loop.Loop.{Bin, Final, Read, StateRef, Update, Write}
import vliw.compiler.regalloc.RegisterAllocator
import vliw.compiler.schedule.Schedule
import vliw.isa.{Bundle, BundleCodec, ComputeOp, MachineDescription, MemoryOp, Rule, Slot}

import ModuloSchedulerSpec.{Expect, Fused}

class ModuloSchedulerSpec extends AnyFunSuite {
  private def machineFile(name: String) = MachineDescription
    .load(java.nio.file.Paths.get("tests", "machines", name))
    .fold(es => fail(es.mkString("\n")), identity)
  private val machine = MachineDescription.load().fold(es => fail(es.mkString("\n")), identity)
  private val slow = machineFile("slow_units.json")
  private val iiUnits = machineFile("ii_units.json")
  private val regs3 = machineFile("regs3.json")
  private val mvp1 = MachineDescription
    .load(MachineDescription.Mvp1Path)
    .fold(es => fail(es.mkString("\n")), identity)
  private val rp4 = machineFile("mvp1_rp4.json")
  private val mac3 = machineFile("mvp1_mac3.json")

  private def compile(k: LoopKernel, m: MachineDescription) =
    ModuloCompiler
      .compile(k, m)
      .fold(e => fail(s"${k.name} (${k.index}) on ${m.machineId}: $e"), identity)

  private val slotPort = "no reservation table (slot + write-back port (L5))"
  private val slotUnitPort = "no reservation table (slot + unit (L9) + write-back port (L5))"
  private def live(n: Int, r: Int = 16) = s"$n values live at once > $r registers"

  private val all1 = Seq(
    "compute slot",
    "memory slot",
    "compute write-back port",
    "memory write-back port",
    "LD unit",
    "MUL unit"
  )
  private val compute = Seq("compute slot", "compute write-back port")
  private val dot1 =
    Seq(
      "compute slot",
      "memory slot",
      "compute write-back port",
      "memory write-back port",
      "LD unit"
    )
  private val expected: Seq[(String, MachineDescription, Expect)] = Seq(
    ("saxpy8 i", machine, Expect(3, Seq("memory slot"), 0, 3, 6, 1, 29)),
    ("fir4x8 i", machine, Expect(7, compute, 0, 8, 11, 4, 76, Seq(7 -> slotPort))),
    (
      "matmul3 i",
      machine,
      Expect(
        15,
        compute,
        0,
        20,
        24,
        2,
        87,
        Seq(15 -> slotPort, 16 -> live(22), 17 -> live(20), 18 -> live(19), 19 -> live(17))
      )
    ),
    (
      "matmul3 m",
      machine,
      Expect(
        18,
        compute,
        1,
        27,
        30,
        9,
        68,
        (18 -> slotPort) +: (19 to 26).zip(Seq(23, 22, 21, 20, 19, 18, 17, 17).map(live(_)))
      )
    ),
    ("product8 i", machine, Expect(1, all1, 2, 2, 4, 1, 19)),
    ("dot8 i", machine, Expect(2, dot1, 1, 3, 6, 2, 28, Seq(2 -> slotPort))),
    ("saxpy8 i", slow, Expect(3, Seq("memory slot"), 0, 3, 9, 1, 33)),
    ("fir4x8 i", slow, Expect(7, compute, 0, 8, 11, 5, 74, Seq(7 -> slotPort))),
    (
      "matmul3 i",
      slow,
      Expect(
        15,
        compute,
        0,
        19,
        24,
        2,
        65,
        Seq(15 -> slotPort, 16 -> live(18), 17 -> live(17), 18 -> live(17))
      )
    ),
    ("product8 i", slow, Expect(1, all1, 3, 3, 6, 1, 28)),
    ("dot8 i", slow, Expect(2, dot1, 1, 2, 8, 2, 25)),
    ("saxpy8 i", iiUnits, Expect(6, Seq("LD unit"), 0, 6, 7, 3, 54)),
    (
      "fir4x8 i",
      iiUnits,
      Expect(8, Seq("MUL unit"), 0, 11, 12, 15, 106, (8 to 10).map(_ -> slotUnitPort))
    ),
    (
      "matmul3 i",
      iiUnits,
      Expect(18, Seq("MUL unit"), 0, 24, 28, 12, 91, (18 to 23).map(_ -> slotUnitPort))
    ),
    (
      "matmul3 m",
      iiUnits,
      Expect(
        18,
        Seq("compute slot", "compute write-back port", "ADD unit", "LD unit", "MUL unit"),
        1,
        27,
        33,
        21,
        82,
        (18 -> slotPort) +: (19 to 26).map(_ -> slotUnitPort)
      )
    ),
    ("product8 i", iiUnits, Expect(3, Seq("LD unit"), 2, 3, 4, 3, 27)),
    ("dot8 i", iiUnits, Expect(6, Seq("LD unit"), 1, 6, 9, 6, 52))
  )
  private def loop(label: String) =
    LoopBenchmarks.all.find(k => s"${k.name} ${k.index}" == label).getOrElse(fail(label))

  // ISA mvp-1 (#77): as `expected`, with ResMII without the third read port and the fusion choice.
  private val thirdPort = "third read port"
  private val computePort = compute :+ thirdPort
  private val dotFused = Seq("memory slot", "memory write-back port", "LD unit")
  private val mvp1Expected: Seq[(String, MachineDescription, Expect, Int, Fused)] = Seq(
    ("saxpy8 i", mvp1, Expect(3, Seq("memory slot"), 0, 3, 6, 1, 29), 3, Fused(1, 3, 3, true)),
    ("saxpy8 i", rp4, Expect(3, Seq("memory slot"), 0, 3, 6, 1, 29), 3, Fused(1, 3, 3, true)),
    ("saxpy8 i", mac3, Expect(3, Seq("memory slot"), 0, 3, 7, 1, 32), 3, Fused(1, 3, 3, true)),
    ("fir4x8 i", mvp1, Expect(4, computePort, 0, 4, 9, 3, 46), 4, Fused(3, 7, 4, true)),
    ("fir4x8 i", rp4, Expect(4, compute, 0, 4, 9, 4, 43), 4, Fused(3, 7, 4, true)),
    (
      "fir4x8 i",
      mac3,
      Expect(4, computePort, 0, 5, 12, 4, 60, Seq(4 -> slotPort)),
      4,
      Fused(3, 7, 4, true)
    ),
    ("matmul3 i", mvp1, Expect(9, computePort, 0, 9, 15, 4, 38), 9, Fused(6, 15, 9, true)),
    ("matmul3 i", rp4, Expect(9, compute, 0, 9, 15, 3, 45), 9, Fused(6, 15, 9, true)),
    (
      "matmul3 i",
      mac3,
      Expect(9, computePort, 0, 10, 19, 3, 39, Seq(9 -> slotPort)),
      9,
      Fused(6, 15, 9, true)
    ),
    (
      "matmul3 m",
      mvp1,
      Expect(9, computePort :+ "MAC unit", 2, 9, 13, 9, 40),
      9,
      Fused(9, 18, 9, true)
    ),
    (
      "matmul3 m",
      rp4,
      Expect(9, compute :+ "MAC unit", 2, 9, 13, 9, 34),
      9,
      Fused(9, 18, 9, true)
    ),
    (
      "matmul3 m",
      mac3,
      Expect(9, computePort :+ "MAC unit", 3, 9, 14, 9, 40),
      9,
      Fused(9, 18, 9, true)
    ),
    ("product8 i", mvp1, Expect(1, all1, 2, 2, 4, 1, 19), 1, Fused(0, 2, 2, false)),
    ("product8 i", rp4, Expect(1, all1, 2, 2, 4, 1, 19), 1, Fused(0, 2, 2, false)),
    ("product8 i", mac3, Expect(1, all1, 2, 2, 4, 1, 19), 1, Fused(0, 2, 2, false)),
    ("dot8 i", mvp1, Expect(2, dotFused, 2, 2, 5, 2, 21), 2, Fused(1, 2, 2, true)),
    ("dot8 i", rp4, Expect(2, dotFused, 2, 2, 5, 2, 21), 2, Fused(1, 2, 2, true)),
    ("dot8 i", mac3, Expect(2, dot1, 1, 3, 6, 2, 28, Seq(2 -> slotPort)), 2, Fused(1, 2, 3, false))
  )

  test("MII, II, the attempts before it, and the cycles of the recorded loops on three machines") {
    for ((label, m, e) <- expected) {
      val c = compile(loop(label), m)
      val s = c.result
      val got = Expect(
        s.mii.resMII,
        s.mii.bounding,
        s.mii.recMII,
        s.ii,
        s.iiMax,
        s.start,
        c.cycles,
        s.attempts.init.map(a => a.ii -> a.outcome.toString)
      )
      assert(got == e, s"$label on ${m.machineId}")
      assert(s.attempts.last == Attempt(s.ii, Outcome.Fits))
    }
  }

  test("matmul3 accumulated over m does not fit slow_units: 17 values stay live at every II") {
    val e = ModuloCompiler.compile(loop("matmul3 m"), slow).swap.getOrElse(fail("compiled"))
    assert(e.startsWith("matmul3: 2 iterations of the loop over m: no II from 18 to 24"), e)
    (18 to 24).foreach(ii => assert(e.contains(s"II $ii: ${live(17)}"), e))
    val layout = LoopLowering.layout(loop("matmul3 m"), slow).fold(e => fail(e), identity)
    val no = ModuloScheduler
      .schedule(LoopGraph.of(LoopLowering.lower(loop("matmul3 m"), layout), slow))
      .swap
      .getOrElse(fail("scheduled"))
    assert((no.mii.resMII, no.mii.bounding, no.mii.recMII, no.iiMax) == ((18, compute, 1, 24)))
  }

  /** Checks a schedule against the machine, independently of the scheduler: dependences of the
    * unrolled loop, one operation per slot and cycle, unit II (L9), one commit per port and
    * boundary (L5), HALT after every completion (L6), the modulo times, and the map.
    */
  private def checkSchedule(s: ModuloSchedule): Unit = {
    val g = s.graph
    val m = g.machine
    val dag = g.expanded.dag
    val origin = g.expanded.origin
    val issue = s.schedule.issue
    dag.edges.foreach(e => assert(issue(e.to) >= issue(e.from) + e.distance, e))
    def op(i: Int) = m.operation(dag.nodes(i).mnemonic)
    dag.nodes.indices.groupBy(i => (issue(i), op(i).slot)).foreach { case (k, is) =>
      assert(is.size == 1, s"two operations in $k")
    }
    dag.nodes.indices.groupBy(op(_).mnemonic).foreach { case (mn, is) =>
      val cs = is.map(issue).sorted
      cs.zip(cs.drop(1)).foreach { case (a, b) =>
        assert(b - a >= m.operation(mn).ii, s"$mn at $a, $b")
      }
    }
    dag.nodes.indices
      .filter(op(_).writeback.isDefined)
      .groupBy(i => (op(i).writeback, issue(i) + op(i).latency))
      .foreach { case (k, is) => assert(is.size == 1, s"two commits at $k") }
    // L11: the registers each bundle reads (ADD/SUB/MUL 2, MAC 3, ST 1, LD 0) fit the read ports.
    val reads = Map("ADD" -> 2, "SUB" -> 2, "MUL" -> 2, "MAC" -> 3, "ST" -> 1, "LD" -> 0)
    dag.nodes.indices.groupBy(issue).foreach { case (c, is) =>
      assert(is.map(i => reads(dag.nodes(i).mnemonic)).sum <= m.readPorts, s"reads at $c")
    }
    assert(s.schedule.halt == dag.nodes.indices.map(i => issue(i) + op(i).latency).max)
    origin.indices.foreach { i =>
      origin(i).iteration.foreach(k =>
        assert(issue(i) == s.start + s.times(origin(i).node) + k * s.ii)
      )
    }
    val ii = s.ii
    g.edges.foreach(e => assert(s.times(e.to) + ii * e.iterations >= s.times(e.from) + e.cycles, e))
    assert(s.map.map(_.instance).toSet == origin.toSet && s.map.size == origin.size)
    assert(s.map.map(e => (e.bundle, e.slot)).distinct.size == s.map.size)
    assert(s.map.forall(e => e.slot == m.operation(g.nodes(e.instance.node).mnemonic).slot))
  }

  test("every schedule respects the dependences and the resources, checked independently") {
    for ((label, m, _) <- expected) checkSchedule(compile(loop(label), m).result)
    checkSchedule(compile(LoopBenchmarks.saxpy(8), regs3).result)
    for ((label, m, _, _, _) <- mvp1Expected) checkSchedule(compile(loop(label), m).result)
  }

  /** The issued operations as (bundle, slot, place, mnemonic, iteration). */
  private def table(c: ModuloCompiled) = c.result.map.map { e =>
    val n = e.instance.node
    (e.bundle, e.slot, c.graph.places(n), c.graph.nodes(n).mnemonic, e.instance.iteration)
  }

  test("hand-computed: product(4), three iterations through MUL, II 2 = RecMII") {
    // LD x(0) before the loop; iteration k: LD x(k+1) at 1 + 2k, MUL at 3 + 2k (reads the MUL of
    // k - 1, or x(0) for k = 0); ST after the loop at 9 (the last MUL commits at E_9); HALT at 10.
    val c = compile(LoopBenchmarks.product(4), machine)
    val (b, e, a) = (Place.Before, Place.Each, Place.After)
    val (mem, cmp) = (Slot.Memory, Slot.Compute)
    assert(
      table(c) == Vector(
        (0, mem, b, "LD", None),
        (1, mem, e, "LD", Some(0)),
        (3, cmp, e, "MUL", Some(0)),
        (3, mem, e, "LD", Some(1)),
        (5, cmp, e, "MUL", Some(1)),
        (5, mem, e, "LD", Some(2)),
        (7, cmp, e, "MUL", Some(2)),
        (9, mem, a, "ST", None)
      )
    )
    assert((c.ii, c.result.start, c.result.schedule.halt, c.cycles) == ((2, 1, 10, 11)))
  }

  test("hand-computed: dot(4), MUL and ADD per iteration, II 3 > MII 2 (slot and port)") {
    // II 2 has no table: with one compute slot, MUL(s) and ADD(t) commit at s + 2 and t + 1, and
    // both rows mod 2 are taken. Before: LD a0, LD b0, then their MUL; iteration k: LD a at 2 + 3k,
    // LD b at 3 + 3k, MUL at 5 + 3k, ADD at 7 + 3k; ST at 14, HALT at 15.
    val c = compile(LoopBenchmarks.dot(4), machine)
    val (b, e, a) = (Place.Before, Place.Each, Place.After)
    val (mem, cmp) = (Slot.Memory, Slot.Compute)
    val iteration = (0 to 2).flatMap { k =>
      Seq(
        (2 + 3 * k, mem, e, "LD", Some(k)),
        (3 + 3 * k, mem, e, "LD", Some(k)),
        (5 + 3 * k, cmp, e, "MUL", Some(k)),
        (7 + 3 * k, cmp, e, "ADD", Some(k))
      )
    }
    val want = Seq((0, mem, b, "LD", None), (1, mem, b, "LD", None), (4, cmp, b, "MUL", None)) ++
      iteration :+ ((14, mem, a, "ST", None))
    assert(table(c) == want.sortBy(r => (r._1, r._2.name)).toVector)
    assert((c.ii, c.result.start, c.result.schedule.halt, c.cycles) == ((3, 2, 15, 16)))
    assert(c.result.attempts.head == Attempt(2, Outcome.NoMrt("slot + write-back port (L5)")))
  }

  test(
    "the look-ahead keeps rows for the rest; a write-back port of the next iteration moves an ADD"
  ) {
    val g = compile(LoopBenchmarks.fir(Seq(1L, 2L, 3L, 4L), 8), machine).graph
    // Without the look-ahead, II 8 fails although a table exists.
    assert(ModuloScheduler.place(g, 8, lookahead = false).isLeft)
    val times = ModuloScheduler.place(g, 8, lookahead = true).getOrElse(fail("II 8"))
    // ADD node 11 (earliest 8): at 10 its commit E_11 is the boundary of MUL node 7 (t = 1) of the
    // next iteration (1 + 8 + 2), so it goes to 12.
    assert(g.nodes(11).mnemonic == "ADD" && g.nodes(7).mnemonic == "MUL")
    assert(times(7) == 1 && times(11) == 12)
    // Forcing it to 10 gives a program that the verifier rejects for the write-back port (L5).
    val c = compile(LoopBenchmarks.fir(Seq(1L, 2L, 3L, 4L), 8), machine)
    val origin = g.expanded.origin
    val moved = c.result.schedule.issue.indices.map { i =>
      val issue = c.result.schedule.issue(i)
      if (origin(i).node == 11) issue - 2 else issue
    }.toVector
    val s = Schedule(g.expanded.dag, moved, c.result.schedule.halt)
    val bundles = RegisterAllocator.allocate(s).fold(e => fail(e), _.bundles)
    val words = new BundleCodec(machine).encodeAll(bundles).fold(e => fail(e), identity)
    val findings = Verifier.verify(machine, words, KernelCompiler.declaration(c.layout))
    assert(findings.map(_.rule).contains(Rule.WritebackPort), findings)
  }

  test("the span of one iteration bounds the II: there it is a modulo schedule") {
    for ((label, m, e) <- expected) {
      val g = compile(loop(label), m).graph
      val one = ModuloScheduler.oneIteration(g)
      assert(ModuloScheduler.span(g, one) == e.iiMax, s"$label on ${m.machineId}")
      g.edges.foreach(x => assert(one(x.to) + e.iiMax * x.iterations >= one(x.from) + x.cycles))
    }
  }

  test(
    "too few registers: the II goes up, and a loop that fits no II is an error with the reasons"
  ) {
    // Three registers: saxpy8 keeps 4 values live at II 3 to 5 and fits at 6.
    val s = compile(LoopBenchmarks.saxpy(8), regs3)
    assert((s.ii, s.cycles) == ((6, 50)))
    assert(s.result.attempts.init == (3 to 5).map(Attempt(_, Outcome.TooManyLive(4, 3))))
    // dot8 fits no II up to its span 6.
    val e = ModuloCompiler.compile(LoopBenchmarks.dot(8), regs3).swap.getOrElse(fail("compiled"))
    assert(e.startsWith("dot8: 7 iterations of the loop over i: no II from 2 to 6"), e)
    assert(e.contains(s"II 2: $slotPort"), e)
    (3 to 6).foreach(ii => assert(e.contains(s"II $ii: ${live(4, 3)}"), e))
  }

  test(
    "the instruction memory: saxpy(83) fits 256 bundles, saxpy(84) is an error with no program"
  ) {
    val c = compile(LoopBenchmarks.saxpy(83), machine)
    assert((c.ii, c.cycles, c.words.size) == ((3, 254, 254)))
    val e =
      ModuloCompiler.compile(LoopBenchmarks.saxpy(84), machine).swap.getOrElse(fail("compiled"))
    assert(e.startsWith("saxpy84: 84 iterations of the loop over i"), e)
    assert(e.contains("II 3: 257 bundles; the instruction memory holds 256"), e)
    // The series of #68: II 3, 3n + 5 bundles and cycles.
    for (n <- Seq(8, 16, 32, 64)) {
      val k = LoopBenchmarks.saxpy(n)
      assert(k.unroll == Benchmarks.saxpy(n))
      val c = compile(k, machine)
      assert((c.ii, c.cycles, c.words.size) == ((3, 3 * n + 5, 3 * n + 5)), n)
    }
  }

  test("the recorded loops load as many words as the list-scheduled kernel") {
    for (
      (label, want) <- Seq("saxpy8 i" -> 17, "fir4x8 i" -> 15, "matmul3 i" -> 18, "matmul3 m" -> 18)
    ) {
      val dag = compile(loop(label), machine).graph.expanded.dag
      assert(dag.nodes.count(_.isInstanceOf[Node.Load]) == want, label)
    }
  }

  // Hand-written loops: five with dependences through memory (a LoopKernel has none), one of them at
  // a distance of 2 iterations, and two without one whose loop state a later node reads. RecMII,
  // the attempts and the cycles; the program passes the verifier.
  private val memoryLoops
      : Seq[(String, Loop, Seq[(MachineDescription, Int, Int, Seq[(Int, String)], Int)])] = Seq(
    (
      "[k + 1] = [k] + [10]",
      Loop(
        6,
        Vector(),
        Vector(Write(Address(1, 1), Bin(Op.Add, Read(Address(0, 1)), Read(Address(10, 0))))),
        Vector()
      ),
      Seq((machine, 4, 4, Seq(), 26), (slow, 6, 6, Seq(), 38))
    ),
    (
      "[k + 1] = [k] * [10]",
      Loop(
        6,
        Vector(),
        Vector(Write(Address(1, 1), Bin(Op.Mul, Read(Address(0, 1)), Read(Address(10, 0))))),
        Vector()
      ),
      Seq((machine, 5, 5, Seq(), 32), (slow, 8, 8, Seq(), 50))
    ),
    (
      "[5] = [5] + [k]",
      Loop(
        6,
        Vector(),
        Vector(Write(Address(5, 0), Bin(Op.Add, Read(Address(5, 0)), Read(Address(0, 1))))),
        Vector()
      ),
      Seq(
        (machine, 4, 5, Seq(4 -> "node 3 finds no cycle in 4..3"), 31),
        (slow, 6, 7, Seq(6 -> "node 3 finds no cycle in 5..4"), 43)
      )
    ),
    (
      "[k] = [10 + k]; [20 + k] = [k]",
      Loop(
        4,
        Vector(),
        Vector(
          Write(Address(0, 1), Read(Address(10, 1))),
          Write(Address(20, 1), Read(Address(0, 1)))
        ),
        Vector()
      ),
      Seq((machine, 0, 4, Seq(), 19))
    ),
    (
      "[k + 2] = [k] + [10]: a dependence over 2 iterations",
      Loop(
        6,
        Vector(),
        Vector(Write(Address(2, 1), Bin(Op.Add, Read(Address(0, 1)), Read(Address(10, 0))))),
        Vector()
      ),
      Seq((machine, 2, 2, Seq(), 16), (slow, 3, 3, Seq(), 23))
    ),
    (
      "s = [0]; s = s * ([1 + k] * [40 + k]); [20 + k] = s * [10]: a state read by a later node",
      Loop(
        6,
        Vector(Read(Address(0, 0))),
        Vector(
          Update(
            0,
            Bin(Op.Mul, StateRef(0), Bin(Op.Mul, Read(Address(1, 1)), Read(Address(40, 1))))
          ),
          Write(Address(20, 1), Bin(Op.Mul, StateRef(0), Read(Address(10, 0))))
        ),
        Vector(Final(30, 0))
      ),
      Seq((machine, 2, 3, Seq(), 26), (slow, 3, 3, Seq(), 32))
    ),
    (
      "s = [0]; s = s * [1 + k]; [20 + k] = s + [10]: a carried edge before the edge it feeds",
      Loop(
        6,
        Vector(Read(Address(0, 0))),
        Vector(
          Update(0, Bin(Op.Mul, StateRef(0), Read(Address(1, 1)))),
          Write(Address(20, 1), Bin(Op.Add, StateRef(0), Read(Address(10, 0))))
        ),
        Vector(Final(30, 0))
      ),
      Seq((machine, 2, 3, Seq(2 -> slotPort), 23), (slow, 3, 3, Seq(), 25))
    )
  )

  test("hand-written loops: dependences through memory, and states read by a later node") {
    val declaration = Verifier.Declaration(Set.empty, (0 until 64).toSet)
    for ((name, l, cases) <- memoryLoops; (m, recMII, ii, before, cycles) <- cases) {
      val s = ModuloScheduler.schedule(LoopGraph.build(m, l)).fold(e => fail(e.message), identity)
      val got = (s.mii.recMII, s.ii, s.attempts.init.map(a => a.ii -> a.outcome.toString), s.cycles)
      assert(got == ((recMII, ii, before, cycles)), s"$name on ${m.machineId}")
      checkSchedule(s)
      KernelCompiler
        .verified(m, name, declaration, s.allocation.bundles)
        .fold(e => fail(e), identity)
    }
  }

  test("the instruction memory: a program of exactly its size fits, and every II is tried") {
    // dot(84) takes exactly 256 bundles at II 3; dot(85) takes 259.
    assert(compile(LoopBenchmarks.dot(84), machine).words.size == 256)
    val e = ModuloCompiler.compile(LoopBenchmarks.dot(85), machine).swap.getOrElse(fail("compiled"))
    assert(e.contains("II 3: 259 bundles; the instruction memory holds 256"), e)
    // With 80 bundles, matmul3 over i overflows at II 20 (87) and fits at II 21 (70): cycles are not
    // monotone in the II, so the search goes on after an overflow.
    val c = compile(LoopBenchmarks.matmulRows(3), machine.copy(instructionMemoryBundles = 80))
    assert((c.ii, c.words.size) == ((21, 70)))
    assert(c.result.attempts.find(_.ii == 20).contains(Attempt(20, Outcome.Overflow(87, 80))))
  }

  test("SUB counts for the compute slot and the write-back port") {
    val k = LoopKernel("subs", "i", 4) { b =>
      val x = b.input("x", 4)
      val (p, q, r) = (b.scalar("p"), b.scalar("q"), b.scalar("r"))
      val out = b.output("out", 4)
      b.body(i => out(i) = ((x(i) - p) - q) - r)
    }
    val c = compile(k, machine)
    assert(c.result.mii.resMII == 3)
    assert(c.result.mii.bounding == Seq("compute slot", "compute write-back port", "SUB unit"))
    assert((c.ii, c.cycles) == ((3, 17)))
  }

  test("at the span of one iteration the search falls back to the one-iteration schedule") {
    val g = compile(LoopBenchmarks.saxpy(8), machine).graph
    val never = (_: LoopGraph, _: Int, _: Boolean) => Left(Outcome.NoTime(0, 0, 0))
    val s = ModuloScheduler.search(g, never).fold(e => fail(e.message), identity)
    assert((s.ii, s.iiMax, s.times) == ((6, 6, ModuloScheduler.oneIteration(g))))
    assert(s.attempts.init == (3 to 5).map(Attempt(_, Outcome.NoTime(0, 0, 0))))
    checkSchedule(s)
    KernelCompiler
      .verified(
        machine,
        "saxpy8",
        Verifier.Declaration(Set.empty, (0 until 64).toSet),
        s.allocation.bundles
      )
      .fold(e => fail(e), identity)
  }

  test("a long chain with mixed latencies ends: undecided tables are not (a)") {
    // SUB latency 5, MUL latency 8: whether a table of 36 to 42 rows exists is not decided within the
    // budget, so those IIs are not recorded as (a); the search goes on and finds II 44.
    val mixed = machine.copy(operations =
      machine.operations
        .updated("SUB", machine.operation("SUB").copy(latency = 5))
        .updated("MUL", machine.operation("MUL").copy(latency = 8))
    )
    val chain = LoopKernel("chain12", "i", 2) { b =>
      val x = b.input("x", 2)
      val out = b.output("out", 2)
      b.body { i =>
        out(i) = (0 until 36).foldLeft(x(i)) { (e, j) =>
          j % 3 match {
            case 0 => e + x(i)
            case 1 => e - x(i)
            case _ => e * x(i)
          }
        }
      }
    }
    val start = System.nanoTime
    val c = compile(chain, mixed)
    assert((System.nanoTime - start) / 1000000000L < 60, "took a minute or more")
    assert((c.ii, c.cycles) == ((44, 255)))
    assert(
      c.result.attempts.exists(a =>
        a.outcome match {
          case o: Outcome.NoTime => !o.tableExists
          case _                 => false
        }
      )
    )
    assert(!c.result.attempts.exists(_.outcome.isInstanceOf[Outcome.NoMrt]))
    checkSchedule(c.result)
  }

  test("mvp-1: MII, II, the fusion choice and the cycles of the recorded loops on three machines") {
    for ((label, m, e, withoutPort, fused) <- mvp1Expected) {
      val c = compile(loop(label), m)
      val s = c.result
      val got = Expect(
        s.mii.resMII,
        s.mii.bounding,
        s.mii.recMII,
        s.ii,
        s.iiMax,
        s.start,
        c.cycles,
        s.attempts.init.map(a => a.ii -> a.outcome.toString)
      )
      assert(got == e, s"$label on ${m.machineId}")
      assert(s.mii.withoutReadPort.resMII == withoutPort, s"$label on ${m.machineId}")
      val f = c.fusion.getOrElse(fail(s"$label on ${m.machineId}: no fusion choice"))
      assert(
        Fused(f.pairs, f.unfusedMII, f.fusedMII, f.fuses) == fused,
        s"$label on ${m.machineId}"
      )
      // The loop scheduled is the fused one exactly when the choice fuses it.
      assert((c.graph.expanded.dag.macs > 0) == f.fuses, s"$label on ${m.machineId}")
    }
    // Without MAC in the machine there is no choice.
    assert(LoopBenchmarks.all.forall(k => compile(k, machine).fusion.isEmpty))
  }

  test("mvp-1, fusion by the MII: dot8 fuses at MAC latency 2, and not at latency 3") {
    // Latency 2: LD 2 and MAC 1 per iteration. MII 2: ResMII 2 (memory slot) and RecMII 2 (MAC ->
    // its MAC of the next iteration), the same as without fusion (ResMII 2, RecMII 1), so it fuses.
    // II 2, where mvp-0 has no table for MUL and ADD and takes II 3.
    val l2 = compile(LoopBenchmarks.dot(8), mvp1)
    assert(l2.fusion.map(f => (f.unfusedMII, f.fusedMII, f.fuses)).contains((2, 2, true)))
    assert((l2.ii, compile(LoopBenchmarks.dot(8), machine).ii) == ((2, 3)))
    assert(l2.bundles.count(_.compute.isInstanceOf[ComputeOp.Mac]) == 7)
    // Latency 3: RecMII 3 > 2, so the loop keeps MUL and ADD and takes II 3, as on mvp-0.
    val l3 = compile(LoopBenchmarks.dot(8), mac3)
    assert(l3.fusion.map(f => (f.unfusedMII, f.fusedMII, f.fuses)).contains((2, 3, false)))
    assert(l3.fusion.map(_.fused.recMII).contains(3) && l3.ii == 3)
    assert(!l3.bundles.exists(_.compute.isInstanceOf[ComputeOp.Mac]))
    assert(l3.graph == LoopGraph.of(LoopLowering.lower(l3.kernel, l3.layout), mac3))
  }

  test("mvp-1: MAC and ST use the third read port once each with three read ports, not with four") {
    // Per iteration: saxpy8 MAC 1 + ST 1; fir4x8 (i) MAC 3 + ST 1; matmul3 (i) MAC 6 + ST 3;
    // matmul3 (m) MAC 9 (its ST is after the loop); dot8 MAC 1 (likewise).
    val uses =
      Seq("saxpy8 i" -> 2, "fir4x8 i" -> 4, "matmul3 i" -> 9, "matmul3 m" -> 9, "dot8 i" -> 1)
    for ((label, n) <- uses) {
      val g = compile(loop(label), mvp1).graph
      assert(Mii.of(g).resources.find(_.resource == Mii.ReadPort).map(_.uses).contains(n), label)
      val four = compile(loop(label), rp4).graph
      assert(four.nodes == g.nodes, label) // the same loop is fused
      assert(Mii.of(four).resources.forall(_.resource != Mii.ReadPort), label)
    }
    assert(Mii.readPortUsers(mvp1) == Set("MAC", "ST"))
    assert(Mii.readPortUsers(rp4).isEmpty && Mii.readPortUsers(machine).isEmpty)
  }

  test("mvp-1: the look-ahead decides MAC and ST together (fir4x8 at II 4, three read ports)") {
    val g = compile(LoopBenchmarks.fir(Seq(1L, 2L, 3L, 4L), 8), mvp1).graph
    // A table exists: the ST takes the row of the MUL, the three MACs the other rows.
    assert(ModuloScheduler.table(g, 4) == Right(true))
    // In node order without the look-ahead, the LD and the MUL take row 0, so the ST, which must
    // avoid the rows of the MACs, finds no row.
    assert(g.nodes(12).mnemonic == "ST")
    assert(
      ModuloScheduler.place(g, 4, lookahead = false) == Left(Outcome.NoTime(12, 9, Int.MaxValue))
    )
    val times = ModuloScheduler.place(g, 4, lookahead = true).getOrElse(fail("II 4"))
    val stRow = Math.floorMod(times(12), 4)
    assert(
      g.ids(Place.Each)
        .filter(g.nodes(_).mnemonic == "MAC")
        .forall(n => Math.floorMod(times(n), 4) != stRow)
    )
    // With four read ports the ST may share a row with a MAC, and node order fits at once.
    val four = compile(LoopBenchmarks.fir(Seq(1L, 2L, 3L, 4L), 8), rp4).graph
    assert(ModuloScheduler.place(four, 4, lookahead = false).isRight)
  }

  test("mvp-1: a table that only the third read port rules out is (a), with the port named") {
    // s = [0]; s = s * s + s; [20 + k] = s: one MAC (s * s + s) and one ST per iteration.
    val l = Loop(
      4,
      Vector(Read(Address(0, 0))),
      Vector(
        Update(0, Bin(Op.Add, Bin(Op.Mul, StateRef(0), StateRef(0)), StateRef(0))),
        Write(Address(20, 1), StateRef(0))
      ),
      Vector()
    )
    for (m <- Seq(mvp1, rp4)) {
      val (g, choice) = FusionChoice.of(LoopGraph.build(m, l))
      // Fused: RecMII 2 (MAC -> MAC) against 3 (ADD -> MUL -> ADD).
      assert(choice.map(c => (c.unfusedMII, c.fusedMII)).contains((3, 2)))
      assert(g.ids(Place.Each).map(g.nodes(_).mnemonic) == Vector("MAC", "ST"))
      // One row: each slot class fits, but the MAC and the ST cannot share the third read port.
      val one = ModuloScheduler.table(g, 1)
      if (m == mvp1) assert(one == Left(Outcome.NoMrt("slot + third read port (L11)")))
      else assert(one == Right(true))
      val s = ModuloScheduler.schedule(g).fold(e => fail(e.message), identity)
      assert(s.ii == 2)
      checkSchedule(s)
      KernelCompiler
        .verified(
          m,
          "s * s + s",
          Verifier.Declaration(Set.empty, (0 until 64).toSet),
          s.allocation.bundles
        )
        .fold(e => fail(e), identity)
    }
  }

  // [20 + k] = [k] * [10] + [11]; [30 + k] = [k] * [12] + [13]; [40 + k] = [k]. Per iteration
  // without fusion: LD 1 + ST 3 in the memory slot, MUL 2 + ADD 2 in the compute slot, MII 4.
  // Fused, the two MACs and the three STs take the third read port 5 times with three read ports.
  private val macsAndStores = {
    def term(h: Int) =
      Bin(Op.Add, Bin(Op.Mul, Read(Address(0, 1)), Read(Address(h, 0))), Read(Address(h + 1, 0)))
    Loop(
      8,
      Vector(),
      Vector(
        Write(Address(20, 1), term(10)),
        Write(Address(30, 1), term(12)),
        Write(Address(40, 1), Read(Address(0, 1)))
      ),
      Vector()
    )
  }

  test("mvp-1: the fusion choice leaves out the third read port, so 3 and 4 ports fuse the same") {
    // Without the port the fused MII stays 4, so the loop fuses on both machines (ADR-0019).
    for ((m, mii) <- Seq(mvp1 -> 5, rp4 -> 4)) {
      val (g, choice) = FusionChoice.of(LoopGraph.build(m, macsAndStores))
      val c = choice.getOrElse(fail(m.machineId))
      assert(Fused(c.pairs, c.unfusedMII, c.fusedMII, c.fuses) == Fused(2, 4, 4, true), m.machineId)
      assert(g.ids(Place.Each).map(g.nodes(_).mnemonic).count(_ == "MAC") == 2, m.machineId)
      // With three read ports the fused loop has MII 5: the cost of the constraint alone.
      assert(c.fused.mii == mii && Mii.of(g).mii == mii, m.machineId)
    }
    // Random loops: the same loops fuse with three and four read ports.
    for ((k, _) <- RandomLoops.kernels(77, 300)) {
      def graph(m: MachineDescription) =
        ModuloCompiler.prepare(k, m).fold(e => fail(s"${k.name}: $e"), _.graph)
      assert(graph(mvp1).nodes == graph(rp4).nodes, k.name)
    }
  }

  test("mvp-1: the look-ahead after the last MAC checks the rows left for the STs") {
    val (g, _) = FusionChoice.of(LoopGraph.build(mvp1, macsAndStores))
    // II 5: the five MACs and STs take the five rows of the third read port, and the LD a MAC row.
    assert(ModuloScheduler.table(g, 5) == Right(true))
    // In node order the LD takes row 0, the first MAC row 2 and its ST row 4. The second MAC must
    // then take row 0, which holds the LD but no ST: at row 3 the two STs left would have row 1
    // only. Without the look-ahead it takes row 3, and the last ST finds no row.
    assert(ModuloScheduler.place(g, 5, lookahead = false).isLeft)
    assert(ModuloScheduler.place(g, 5, lookahead = true).isRight)
    for ((m, ii) <- Seq(mvp1 -> 5, rp4 -> 4)) {
      val s = ModuloScheduler
        .schedule(FusionChoice.of(LoopGraph.build(m, macsAndStores))._1)
        .fold(e => fail(e.message), identity)
      assert(s.ii == ii && s.attempts.size == 1, m.machineId)
      checkSchedule(s)
    }
  }

  /** Runs a legal program as docs/ISA.md defines it, independently of the compiler: each bundle
    * reads its sources in its cycle, and each result commits `latency` cycles later.
    */
  private def execute(m: MachineDescription, bundles: Vector[Bundle], image: Map[Int, Long]) = {
    val reg = Array.fill(m.registerCount)(0L)
    val mem = mutable.Map.from(image)
    val pending = mutable.ArrayBuffer.empty[(Int, () => Unit)]
    bundles.indices.foreach { s =>
      pending.filter(_._1 <= s).foreach(_._2())
      pending.filterInPlace(_._1 > s)
      def later(op: String)(commit: () => Unit) = pending += ((s + m.operation(op).latency, commit))
      bundles(s).compute match {
        case ComputeOp.Add(d, x, y) =>
          val v = Op.Add(reg(x), reg(y)); later("ADD")(() => reg(d) = v)
        case ComputeOp.Sub(d, x, y) =>
          val v = Op.Sub(reg(x), reg(y)); later("SUB")(() => reg(d) = v)
        case ComputeOp.Mul(d, x, y) =>
          val v = Op.Mul(reg(x), reg(y)); later("MUL")(() => reg(d) = v)
        case ComputeOp.Mac(d, x, y, z) =>
          val v = Op.Add(Op.Mul(reg(x), reg(y)), reg(z)); later("MAC")(() => reg(d) = v)
        case ComputeOp.Nop | ComputeOp.Halt => ()
        case op @ (_: ComputeOp.VAdd | _: ComputeOp.VSub | _: ComputeOp.VMul | _: ComputeOp.VMac) =>
          fail(s"$op: the compiler generates W operations from #110 stage 4")
      }
      bundles(s).memory match {
        case MemoryOp.Ld(d, a) => val v = mem(a); later("LD")(() => reg(d) = v)
        case MemoryOp.St(a, x) => val v = reg(x); later("ST")(() => mem(a) = v)
        case MemoryOp.Nop      => ()
        case op @ (_: MemoryOp.LdW | _: MemoryOp.StW) =>
          fail(s"$op: the compiler generates W operations from #110 stage 4")
      }
    }
    pending.foreach(_._2())
    mem.toMap
  }

  test("mvp-1: the recorded loops and random loops give the outputs of the DSL reference") {
    def macStore(b: Bundle) =
      b.compute.isInstanceOf[ComputeOp.Mac] && b.memory.isInstanceOf[MemoryOp.St]
    for ((label, m, _, _, _) <- mvp1Expected; seed <- 1 to 3) {
      val c = compile(loop(label), m)
      val in = KernelCompiler.sampleInputs(c.kernel.unroll, seed.toLong)
      val image = c.layout.memoryImage(in).fold(e => fail(e), identity)
      val want = Interpreter.run(c.kernel.unroll, in).fold(e => fail(e), identity)
      assert(c.layout.outputs(execute(m, c.bundles, image)) == want, s"$label on ${m.machineId}")
      // One mac instruction per MAC of the unrolled loop; with three read ports, never with a ST.
      val macs = c.bundles.count(_.compute.isInstanceOf[ComputeOp.Mac])
      assert(macs == c.graph.expanded.dag.macs, s"$label on ${m.machineId}")
      assert(m.readPorts == 4 || !c.bundles.exists(macStore), s"$label on ${m.machineId}")
    }
    // Random loops (as LoopGraphSpec makes them) pass the verifier and compute the reference too.
    // The loops that fit no II on mvp-0 (too many values live at once) fit none on mvp-1 either.
    val random = RandomLoops.kernels(76, 300).map(_._1)
    val tooLarge = random.filter(ModuloCompiler.compile(_, machine).isLeft).map(_.name)
    assert(tooLarge.size == 4)
    for (m <- Seq(mvp1, rp4, mac3)) {
      val fused = random.flatMap { k =>
        ModuloCompiler.compile(k, m) match {
          case Left(e) =>
            assert(tooLarge.contains(k.name) && e.contains("no II from"), e)
            None
          case Right(c) =>
            checkSchedule(c.result)
            val in = KernelCompiler.sampleInputs(k.unroll, 1L)
            val image = c.layout.memoryImage(in).fold(e => fail(e), identity)
            val want = Interpreter.run(k.unroll, in).fold(e => fail(e), identity)
            assert(
              c.layout.outputs(execute(m, c.bundles, image)) == want,
              s"${k.name} on ${m.machineId}"
            )
            Some(c.fusion.exists(_.fuses))
        }
      }
      assert(fused.size == random.size - tooLarge.size, m.machineId)
      assert(fused.count(identity) >= 100, s"${fused.count(identity)} on ${m.machineId}")
    }
    // With four read ports, MAC and ST share bundles.
    assert(
      Seq("saxpy8 i", "fir4x8 i", "matmul3 i").exists(l =>
        compile(loop(l), rp4).bundles.exists(macStore)
      )
    )
  }
}

object ModuloSchedulerSpec {

  /** The fusion choice of a loop on a machine with MAC: the pairs, the MII without and with them
    * (both without the third read port), and whether the loop is fused.
    */
  final case class Fused(pairs: Int, unfused: Int, fused: Int, fuses: Boolean)

  /** Per loop and machine: ResMII and the resources that set it, RecMII, II, the span of one
    * iteration (the largest II tried), the start of iteration 0, the cycles, and the attempts
    * before the II that fits.
    */
  final case class Expect(
      resMII: Int,
      bounding: Seq[String],
      recMII: Int,
      ii: Int,
      iiMax: Int,
      start: Int,
      cycles: Int,
      before: Seq[(Int, String)] = Seq.empty
  )
}
