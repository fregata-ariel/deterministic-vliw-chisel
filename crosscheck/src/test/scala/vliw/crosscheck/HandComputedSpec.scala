package vliw.crosscheck

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.KernelCompiler
import vliw.compiler.dsl._
import vliw.hw.{Core, CoreDriver, CoreParams, SeededChiselSim}
import vliw.isa._
import vliw.sim.ReferenceModel

/** The benchmark kernels at their recorded sizes, compiled in both issue modes, on inputs whose
  * outputs are computed by hand below (not by any implementation): the reference model and the RTL
  * must produce exactly these values.
  */
class HandComputedSpec extends AnyFunSuite with SeededChiselSim {
  private val machine = MachineDescription.load().fold(es => fail(es.mkString("\n")), identity)

  // saxpy8, a = 3, x = 1..8, y = 10, 20, ..., 80: out(i) = 3(i+1) + 10(i+1) = 13(i+1).
  // fir4x8, h = (1, 2, 3, 4), x(j) = j + 1: out(i) = Σ_t (t+1)(i+t+1) = 10(i+1) + 20.
  // matmul3, a = 1..9 and b = 9..1 row-major: c = [30 24 18; 84 69 54; 138 114 90].
  private val cases: Seq[(Kernel, Values, String, Vector[Long])] = Seq(
    (
      Benchmarks.saxpy(8),
      Values(
        Map("x" -> (1L to 8L).toVector, "y" -> (1L to 8L).map(_ * 10).toVector),
        Map("a" -> 3L)
      ),
      "out",
      Vector(13L, 26L, 39L, 52L, 65L, 78L, 91L, 104L)
    ),
    (
      Benchmarks.fir(Seq(1L, 2L, 3L, 4L), 8),
      Values(Map("x" -> (1L to 11L).toVector)),
      "out",
      Vector(30L, 40L, 50L, 60L, 70L, 80L, 90L, 100L)
    ),
    (
      Benchmarks.matmul(3),
      Values(Map("a" -> (1L to 9L).toVector, "b" -> (9L to 1L by -1).toVector)),
      "c",
      Vector(30L, 24L, 18L, 84L, 69L, 54L, 138L, 114L, 90L)
    )
  )

  private def compiled = for {
    (k, in, out, expected) <- cases
    single <- Seq(false, true)
  } yield (
    KernelCompiler.compile(k, machine, singleIssue = single).fold(e => fail(e), identity),
    in,
    out,
    expected
  )

  test("hand-computed outputs on the reference model, both issue modes") {
    compiled.foreach { case (c, in, out, expected) =>
      val run = ReferenceModel
        .run(machine, c.words, c.layout.inputs(in).fold(e => fail(e), identity))
        .fold(v => fail(v.toString), identity)
      assert(run.cycles == c.cycles)
      val got =
        expected.indices.map(i => run.memory(c.layout.elem(out, i)).getOrElse(fail("undefined")))
      assert(got == expected, c.kernel.name)
    }
  }

  test("hand-computed outputs on the RTL, both issue modes") {
    simulate(new Core(CoreParams(machine))) { dut =>
      compiled.foreach { case (c, in, out, expected) =>
        val inputs = c.layout.inputs(in).fold(e => fail(e), identity)
        val run = CoreDriver.run(dut, machine, c.words, inputs, c.layout.outputAddresses)
        assert(run.violations.isEmpty && run.cycles == c.cycles, c.kernel.name)
        assert(
          expected.indices.map(i => run.memory(c.layout.elem(out, i)).toLong) == expected,
          c.kernel.name
        )
      }
    }
  }
}
