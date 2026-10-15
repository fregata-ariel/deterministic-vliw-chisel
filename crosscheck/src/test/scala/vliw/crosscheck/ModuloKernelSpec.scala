package vliw.crosscheck

import scala.util.Random

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.{KernelCompiler, Verifier}
import vliw.compiler.dsl._
import vliw.compiler.loop._
import vliw.compiler.loop.Loop.{Bin, Read, Write}
import vliw.compiler.modulo.{MapEntry, ModuloCompiled, ModuloCompiler, ModuloScheduler}
import vliw.hw.{Core, CoreDriver, CoreParams, VliwChiselSim}
import vliw.isa._
import vliw.sim.ReferenceModel

/** Modulo-scheduled loops (#68) on the reference model and the RTL: outputs from the DSL reference
  * implementation, the scheduled cycles, and the trace check of the II (`ModuloTrace`).
  */
class ModuloKernelSpec extends AnyFunSuite with VliwChiselSim {
  private def machineFile(name: String) = MachineDescription
    .load(java.nio.file.Paths.get("tests", "machines", name))
    .fold(es => fail(es.mkString("\n")), identity)
  private val machine = MachineDescription.load().fold(es => fail(es.mkString("\n")), identity)
  private val machines =
    Seq(machine, machineFile("slow_units.json"), machineFile("ii_units.json"))

  private def word(rng: Random) = rng.nextLong() & 0xffffffffL
  private def values(k: Kernel, rng: Random) = Values(
    k.inputs.map(a => a.name -> Vector.fill(a.length)(word(rng))).toMap,
    k.scalars.map(_ -> word(rng)).toMap
  )

  /** The compiled loops of a machine; matmul3 over m does not fit slow_units (ModuloSchedulerSpec).
    */
  private def compiled(m: MachineDescription): Seq[ModuloCompiled] =
    LoopBenchmarks.all.flatMap { k =>
      ModuloCompiler.compile(k, m) match {
        case Right(c)                                                  => Some(c)
        case Left(e) if m.machineId.endsWith("slow") && k.index == "m" => None
        case Left(e)                                                   => fail(e)
      }
    }

  private def rows(trace: Vector[ujson.Obj]): Vector[ujson.Value] = trace

  test("on the reference model: DSL results, the scheduled cycles, and the II in the trace") {
    val rng = new Random(68)
    // With three registers only saxpy8 (at II 6) and product8 fit (ModuloSchedulerSpec).
    val regs3 = machineFile("regs3.json")
    val few = Seq(LoopBenchmarks.saxpy(8), LoopBenchmarks.product(8))
      .map(ModuloCompiler.compile(_, regs3).fold(e => fail(e), identity))
    for (c <- machines.flatMap(compiled) ++ few) {
      val m = c.machine
      val where = s"${c.kernel.name} (${c.kernel.index}) on ${m.machineId}"
      (0 until 3).foreach { _ =>
        val in = values(c.kernel.unroll, rng)
        val run = ReferenceModel
          .run(m, c.words, c.layout.inputs(in).fold(e => fail(e), identity))
          .fold(v => fail(s"$where: $v"), identity)
        assert(run.cycles == c.cycles, where)
        val out = c.layout.outputs(a => run.memory(a).getOrElse(fail(s"$where: [$a] undefined")))
        assert(out == Interpreter.run(c.kernel.unroll, in).fold(e => fail(e), identity), where)
        val trace = run.trace.cycles.map(_.toJson: ujson.Value)
        assert(ModuloTrace.check(c.graph, c.result.map, trace, c.ii) == Right(Some(c.ii)), where)
      }
    }
  }

  machines.tail.foreach { m =>
    ignore(s"on the RTL, ${m.machineId}: outside the RTL's range") {}
  }

  test("on the RTL (default machine): DSL results, the scheduled cycles, and the II in the trace") {
    assert(CoreParams.check(machine).isRight && machines.tail.forall(CoreParams.check(_).isLeft))
    val rng = new Random(6868)
    simulate(new Core(CoreParams(machine))) { dut =>
      for (c <- compiled(machine)) {
        val outputs =
          c.kernel.outputs.flatMap(o => (0 until o.length).map(c.layout.elem(o.name, _)))
        val in = values(c.kernel.unroll, rng)
        val run = CoreDriver.run(
          dut,
          machine,
          c.words,
          c.layout.inputs(in).fold(e => fail(e), identity),
          outputs.toSet
        )
        assert(run.violations.isEmpty && run.cycles == c.cycles, c.kernel.name)
        val out = c.layout.outputs(a => run.memory(a).toLong)
        assert(
          out == Interpreter.run(c.kernel.unroll, in).fold(e => fail(e), identity),
          c.kernel.name
        )
        assert(ModuloTrace.check(c.graph, c.result.map, rows(run.trace), c.ii) == Right(Some(c.ii)))
      }
    }
  }

  /** Per row: the issued mnemonics (compute, memory), the ports of the register commits, and
    * whether a word is stored.
    */
  private def shape(trace: Vector[ujson.Value]) = trace.filter(!_("issue").isNull).map { r =>
    def m(s: String) = r("issue")(s).str.takeWhile(_ != ' ')
    (
      r("cycle").num.toInt,
      m("compute"),
      m("memory"),
      r("rf_commit").arr.map(_("port").str).toVector,
      r("mem_commit").arr.nonEmpty
    )
  }

  private def traceOf(c: ModuloCompiled) = {
    val in = KernelCompiler.sampleInputs(c.kernel.unroll, 1)
    ReferenceModel
      .run(c.machine, c.words, c.layout.inputs(in).fold(e => fail(e), identity))
      .fold(v => fail(v.toString), identity)
  }

  test("hand-computed: product(4), issue and commits of the prologue, steady state and epilogue") {
    val c = ModuloCompiler.compile(LoopBenchmarks.product(4), machine).fold(e => fail(e), identity)
    val run = traceOf(c)
    // LD x(0) at 0 and LD x(k+1) at 1 + 2k commit on the memory port 2 cycles later; MUL(k) at
    // 3 + 2k commits at 5 + 2k; the ST at 9 writes the word at E_10, the HALT row.
    val (n, mem, cmp) = (Vector.empty[String], Vector("memory"), Vector("compute"))
    assert(
      shape(run.trace.cycles.map(_.toJson)) == Vector(
        (0, "nop", "ld", n, false),
        (1, "nop", "ld", n, false),
        (2, "nop", "nop", mem, false),
        (3, "mul", "ld", mem, false),
        (4, "nop", "nop", n, false),
        (5, "mul", "ld", cmp ++ mem, false),
        (6, "nop", "nop", n, false),
        (7, "mul", "nop", cmp ++ mem, false),
        (8, "nop", "nop", n, false),
        (9, "nop", "st", cmp, false),
        (10, "halt", "nop", n, true)
      )
    )
    assert(run.cycles == 11 && c.cycles == 11)
  }

  test("hand-computed: dot(4), issue and commits with II 3") {
    val c = ModuloCompiler.compile(LoopBenchmarks.dot(4), machine).fold(e => fail(e), identity)
    val run = traceOf(c)
    val got = shape(run.trace.cycles.map(_.toJson))
    // Before: LD a0 at 0, LD b0 at 1, their MUL at 4 (commits at 6). Iteration k: LD a at 2 + 3k,
    // LD b at 3 + 3k (commit 2 later), MUL at 5 + 3k (commit 7 + 3k), ADD at 7 + 3k (commit
    // 8 + 3k). ST at 14, HALT at 15.
    def at(c: Int) = got.find(_._1 == c).getOrElse(fail(s"row $c"))
    val compute = Map(
      4 -> "mul",
      5 -> "mul",
      7 -> "add",
      8 -> "mul",
      10 -> "add",
      11 -> "mul",
      13 -> "add",
      15 -> "halt"
    )
    val memory = Map(
      0 -> "ld",
      1 -> "ld",
      2 -> "ld",
      3 -> "ld",
      5 -> "ld",
      6 -> "ld",
      8 -> "ld",
      9 -> "ld",
      14 -> "st"
    )
    (0 to 15).foreach { r =>
      assert(at(r)._2 == compute.getOrElse(r, "nop") && at(r)._3 == memory.getOrElse(r, "nop"), r)
    }
    val computeCommits = got.filter(_._4.contains("compute")).map(_._1)
    val memoryCommits = got.filter(_._4.contains("memory")).map(_._1)
    assert(computeCommits == Vector(6, 7, 8, 10, 11, 13, 14))
    assert(memoryCommits == Vector(2, 3, 4, 5, 7, 8, 10, 11))
    assert(got.filter(_._5).map(_._1) == Vector(15) && run.cycles == 16)
  }

  test("hand-written loops through memory give the memory of LoopLowering.evaluate") {
    val loops = Seq(
      Loop(
        6,
        Vector(),
        Vector(Write(Address(1, 1), Bin(Op.Add, Read(Address(0, 1)), Read(Address(10, 0))))),
        Vector()
      ),
      Loop(
        6,
        Vector(),
        Vector(Write(Address(1, 1), Bin(Op.Mul, Read(Address(0, 1)), Read(Address(10, 0))))),
        Vector()
      ),
      Loop(
        6,
        Vector(),
        Vector(Write(Address(5, 0), Bin(Op.Add, Read(Address(5, 0)), Read(Address(0, 1))))),
        Vector()
      ),
      Loop(
        4,
        Vector(),
        Vector(
          Write(Address(0, 1), Read(Address(10, 1))),
          Write(Address(20, 1), Read(Address(0, 1)))
        ),
        Vector()
      )
    )
    val rng = new Random(7)
    val words = (0 until 64).toSet
    for (l <- loops; m <- machines.take(2)) {
      val g = LoopGraph.build(m, l)
      val s = ModuloScheduler.schedule(g).fold(e => fail(e.message), identity)
      val program = KernelCompiler
        .verified(m, "loop", Verifier.Declaration(Set.empty, words), s.allocation.bundles)
        .fold(e => fail(e), identity)
      val memory = words.map(_ -> word(rng)).toMap
      val run = ReferenceModel
        .run(m, program, Inputs(Map.empty, memory))
        .fold(v => fail(v.toString), identity)
      val want = LoopLowering.evaluate(l, memory)
      assert(words.forall(a => run.memory(a).contains(want(a))), l)
      assert(run.cycles == s.cycles)
      val trace = run.trace.cycles.map(_.toJson: ujson.Value)
      assert(ModuloTrace.check(g, s.map, trace, s.ii) == Right(Some(s.ii)), l)
    }
  }

  test("the trace check finds a wrong table, address, producer, commit or II") {
    def setup(k: LoopKernel) = {
      val c = ModuloCompiler.compile(k, machine).fold(e => fail(e), identity)
      (c, traceOf(c).trace.cycles.map(_.toJson: ujson.Value))
    }
    def fails(c: ModuloCompiled, map: Vector[MapEntry], trace: Vector[ujson.Value], ii: Int) =
      assert(ModuloTrace.check(c.graph, map, trace, ii).isLeft)
    def edit(trace: Vector[ujson.Value], row: Int)(f: ujson.Value => Unit) = {
      val copy = trace.map(r => ujson.read(ujson.write(r)))
      f(copy(row))
      copy
    }
    val (p, pt) = setup(LoopBenchmarks.product(4))
    assert(ModuloTrace.check(p.graph, p.result.map, pt, p.ii) == Right(Some(2)))
    // The recorded II off by one.
    fails(p, p.result.map, pt, 3)
    // Iterations 0 and 1 of the MUL swapped in the table.
    val swapped = p.result.map.map { e =>
      e.instance match {
        case Instance(n, Some(k)) if p.graph.nodes(n).mnemonic == "MUL" && k < 2 =>
          e.copy(instance = Instance(n, Some(1 - k)))
        case _ => e
      }
    }
    fails(p, swapped, pt, 2)
    // MUL(0) reading its state from LD(0) instead of x(0) before the loop: the graph's initial value.
    val mul = p.graph.ids(Place.Each).find(p.graph.nodes(_).mnemonic == "MUL").get
    val wrongInit = p.graph.copy(nodes =
      p.graph.nodes.updated(
        mul,
        p.graph.nodes(mul) match {
          case LoopNode.Compute(op, a, b) =>
            LoopNode.Compute(op, a.copy(initial = Vector(b.node)), b)
          case other => other
        }
      )
    )
    assert(ModuloTrace.check(wrongInit, p.result.map, pt, 2).isLeft)
    // One LD address one word off.
    fails(
      p,
      p.result.map,
      edit(pt, 1)(r => r("issue")("memory") = r("issue")("memory").str.replace("[1]", "[2]")),
      2
    )
    // A commit one row later.
    fails(
      p,
      p.result.map, {
        val c2 = edit(pt, 5)(r => r("rf_commit") = ujson.Arr())
        edit(c2, 6)(r => r("rf_commit") = pt(5)("rf_commit"))
      },
      2
    )
    // Sources swapped on a MUL with two different operands (dot: a(i) * b(i)).
    val (d, dt) = setup(LoopBenchmarks.dot(4))
    val row = d.result.map
      .find(e =>
        d.graph.nodes(e.instance.node).mnemonic == "MUL" && e.instance.iteration.contains(1)
      )
      .get
      .bundle
    fails(
      d,
      d.result.map,
      edit(dt, row) { r =>
        r("issue")("compute") = r("issue")("compute").str match {
          case s"mul r$x, r$a, r$b" => s"mul r$x, r$b, r$a"
          case other                => other
        }
      },
      3
    )
    // The ADD of iteration 1 reading the ADD of the same iteration: a state off by one in the table.
    val add = d.graph.ids(Place.Each).find(d.graph.nodes(_).mnemonic == "ADD").get
    val selfRead = d.graph.copy(nodes =
      d.graph.nodes.updated(
        add,
        d.graph.nodes(add) match {
          case LoopNode.Compute(op, a, b) => LoopNode.Compute(op, a.copy(back = 0), b)
          case other                      => other
        }
      )
    )
    assert(ModuloTrace.check(selfRead, d.result.map, dt, 3).isLeft)
  }
}
