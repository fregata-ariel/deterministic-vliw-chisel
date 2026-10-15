package vliw.crosscheck

import scala.util.Random

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.{CompiledKernel, KernelCompiler}
import vliw.compiler.dsl._
import vliw.hw.{Core, CoreDriver, CoreParams, VliwChiselSim}
import vliw.isa._
import vliw.sim.ReferenceModel

/** Many random DSL kernels through the whole compiler: every one passes the final verification
  * (KernelCompiler stops otherwise), and the generated code computes the DSL reference results in
  * the predicted cycles, on the reference model and on the RTL.
  */
class RandomKernelSpec extends AnyFunSuite with VliwChiselSim {
  private val machine = MachineDescription.load().fold(es => fail(es.mkString("\n")), identity)

  /** One or two inputs of 1 to 6 words, maybe a scalar, one or two outputs of 1 to 4 words, and
    * expression trees of depth up to 3 over ADD, SUB and MUL with elements, scalars and constants.
    */
  private def kernel(rng: Random, id: Int): Kernel = Kernel(s"random$id") { k =>
    val ins = (0 until 1 + rng.nextInt(2)).map(i => k.input(s"in$i", 1 + rng.nextInt(6)))
    val scalar = Option.when(rng.nextBoolean())(k.scalar("s"))
    val outs = (0 until 1 + rng.nextInt(2)).map(i => k.output(s"out$i", 1 + rng.nextInt(4)))
    def leaf(): Expr = rng.nextInt(6) match {
      case 0 => k.const(Seq(0L, 1L, 3L, 0xffffffffL)(rng.nextInt(4)))
      case 1 => scalar.getOrElse(k.const(2L))
      case _ =>
        val a = ins(rng.nextInt(ins.size))
        a(rng.nextInt(a.length))
    }
    def expr(depth: Int): Expr =
      if (depth == 0 || rng.nextInt(4) == 0) leaf()
      else
        rng.nextInt(3) match {
          case 0 => expr(depth - 1) + expr(depth - 1)
          case 1 => expr(depth - 1) - expr(depth - 1)
          case _ => expr(depth - 1) * expr(depth - 1)
        }
    for (o <- outs; i <- 0 until o.length) o(i) = expr(3)
  }

  private lazy val compiled: Vector[CompiledKernel] = {
    val rng = new Random(51L)
    (0 until 400)
      .map(i => KernelCompiler.compile(kernel(rng, i), machine).fold(e => fail(e), identity))
      .toVector
  }

  test("400 random kernels compile and compute the DSL results on the reference model") {
    val rng = new Random(5151L)
    compiled.foreach { c =>
      (0 until 3).foreach { _ =>
        val in = KernelCompiler.sampleInputs(c.kernel, rng.nextLong())
        val run = ReferenceModel
          .run(machine, c.words, c.layout.inputs(in).fold(e => fail(e), identity))
          .fold(v => fail(s"${c.kernel.name}: $v\n${Disassembler.program(c.bundles)}"), identity)
        assert(run.cycles == c.cycles, c.kernel.name)
        val out = c.layout.outputs(a => run.memory(a).getOrElse(fail(s"[$a] undefined")))
        assert(out == Interpreter.run(c.kernel, in).fold(e => fail(e), identity), c.kernel.name)
      }
    }
  }

  test("the generated test cases hold on the reference model") {
    compiled.take(100).foreach { c =>
      val json = KernelCompiler
        .testCase(c, KernelCompiler.sampleInputs(c.kernel, 3L), None)
        .fold(e => fail(e), identity)
      val tc = TestCase.parse(ujson.write(json)).fold(es => fail(es.mkString("\n")), identity)
      val run =
        ReferenceModel.run(tc.machine, tc.words, tc.inputs).fold(v => fail(v.toString), identity)
      assert(tc.expect.cycles.contains(run.cycles))
      tc.expect.memory.foreach { case (a, v) =>
        assert(run.memory(a).contains(v), s"${c.kernel.name} [$a]")
      }
    }
  }

  test("60 random kernels compute the DSL results on the RTL in the predicted cycles") {
    val rng = new Random(515151L)
    simulate(new Core(CoreParams(machine))) { dut =>
      compiled.take(60).foreach { c =>
        val in = KernelCompiler.sampleInputs(c.kernel, rng.nextLong())
        val run = CoreDriver.run(
          dut,
          machine,
          c.words,
          c.layout.inputs(in).fold(e => fail(e), identity),
          c.layout.outputAddresses
        )
        assert(run.violations.isEmpty && run.cycles == c.cycles, c.kernel.name)
        val out = c.layout.outputs(a => run.memory(a).toLong)
        assert(out == Interpreter.run(c.kernel, in).fold(e => fail(e), identity), c.kernel.name)
      }
    }
  }
}
