package vliw.crosscheck

import scala.util.Random

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.dag.Dag
import vliw.compiler.dsl._
import vliw.compiler.schedule.{ListScheduler, NaiveCodegen, Priority}
import vliw.hw.{Core, CoreDriver, CoreParams}
import vliw.isa._
import vliw.sim.ReferenceModel

private final case class Compiled(
    kernel: Kernel,
    layout: MemoryLayout,
    words: Vector[Long],
    cycles: Int
)

/** Small DSL kernels, list-scheduled and given one register per value (no register allocation yet,
  * #50), run on the reference model and the RTL: the outputs must equal the DSL reference
  * implementation, and cycles must equal the schedule's.
  */
class ScheduledKernelSpec extends AnyFunSuite with ChiselSim {
  private val machine = MachineDescription.load().fold(es => fail(es.mkString("\n")), identity)
  private val small = Seq(Benchmarks.saxpy(2), Benchmarks.saxpy(3), Benchmarks.fir(Seq(1L, 2L), 2))

  private def compile(k: Kernel, m: MachineDescription, p: Priority): Compiled = {
    val layout = MemoryLayout.fixedOrder(k, m).fold(e => fail(e), identity)
    val s = ListScheduler.schedule(Dag.of(Lowering.lower(layout), m), p)
    val bundles = NaiveCodegen.bundles(s).getOrElse(fail(s"${k.name}: too many values"))
    Compiled(
      k,
      layout,
      new BundleCodec(m).encodeAll(bundles).fold(e => fail(e), identity),
      s.cycles
    )
  }

  private def values(k: Kernel, rng: Random) = Values(
    k.inputs.map(a => a.name -> Vector.fill(a.length)(rng.nextLong() & 0xffffffffL)).toMap,
    k.scalars.map(_ -> (rng.nextLong() & 0xffffffffL)).toMap
  )

  test(
    "on the reference model, scheduled kernels compute the DSL results in the scheduled cycles"
  ) {
    val rng = new Random(49L)
    val machines = Seq(machine) ++ Seq("slow_units.json", "ii_units.json").map { f =>
      MachineDescription
        .load(java.nio.file.Paths.get("tests", "machines", f))
        .fold(es => fail(es.mkString), identity)
    }
    for (m <- machines; k <- small; p <- Priority.all) {
      val c = compile(k, m, p)
      (0 until 10).foreach { _ =>
        val in = values(k, rng)
        val inputs = c.layout.inputs(in).fold(e => fail(e), identity)
        val run = ReferenceModel
          .run(m, c.words, inputs)
          .fold(v => fail(s"${k.name} on ${m.machineId}: $v"), identity)
        assert(run.cycles == c.cycles)
        val out = c.layout.outputs(a => run.memory(a).getOrElse(fail(s"[$a] undefined")))
        assert(
          out == Interpreter.run(k, in).fold(e => fail(e), identity),
          s"${k.name} on ${m.machineId}"
        )
      }
    }
  }

  test("on the RTL, scheduled kernels compute the DSL results in the scheduled cycles") {
    val rng = new Random(4949L)
    simulate(new Core(CoreParams(machine))) { dut =>
      for (k <- small; p <- Priority.all) {
        val c = compile(k, machine, p)
        val outAddrs =
          k.outputs.flatMap(o => (0 until o.length).map(c.layout.elem(o.name, _))).toSet
        (0 until 5).foreach { _ =>
          val in = values(k, rng)
          val inputs = c.layout.inputs(in).fold(e => fail(e), identity)
          val run = CoreDriver.run(dut, machine, c.words, inputs, outAddrs)
          assert(run.violations.isEmpty && run.cycles == c.cycles, k.name)
          val out = c.layout.outputs(a => run.memory(a).toLong)
          assert(out == Interpreter.run(k, in).fold(e => fail(e), identity), k.name)
        }
      }
    }
  }
}
