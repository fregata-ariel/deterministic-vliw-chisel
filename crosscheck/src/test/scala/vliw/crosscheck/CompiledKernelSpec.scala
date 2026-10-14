package vliw.crosscheck

import scala.util.Random

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.dag.Dag
import vliw.compiler.dsl._
import vliw.compiler.regalloc.RegisterAllocator
import vliw.hw.{Core, CoreDriver, CoreParams}
import vliw.isa._
import vliw.sim.ReferenceModel

private final case class Compiled(layout: MemoryLayout, words: Vector[Long], cycles: Int)

/** The benchmark kernels, scheduled and register-allocated, run on the reference model and the RTL:
  * the outputs must equal the DSL reference implementation, in the scheduled cycles.
  */
class CompiledKernelSpec extends AnyFunSuite with ChiselSim {
  private val machine = MachineDescription.load().fold(es => fail(es.mkString("\n")), identity)

  private def compile(k: Kernel, m: MachineDescription): Compiled = {
    val layout = MemoryLayout.fixedOrder(k, m).fold(e => fail(e), identity)
    val a = RegisterAllocator
      .scheduleAndAllocate(Dag.of(Lowering.lower(layout), m))
      .fold(e => fail(s"${k.name} on ${m.machineId}: $e"), identity)
    val words = new BundleCodec(m).encodeAll(a.allocation.bundles).fold(e => fail(e), identity)
    Compiled(layout, words, a.schedule.cycles)
  }

  private def values(k: Kernel, rng: Random) = Values(
    k.inputs.map(a => a.name -> Vector.fill(a.length)(rng.nextLong() & 0xffffffffL)).toMap,
    k.scalars.map(_ -> (rng.nextLong() & 0xffffffffL)).toMap
  )

  test("on the reference model, compiled kernels compute the DSL results in the scheduled cycles") {
    val rng = new Random(50L)
    val machines = Seq(machine) ++ Seq("slow_units.json", "ii_units.json").map { f =>
      MachineDescription
        .load(java.nio.file.Paths.get("tests", "machines", f))
        .fold(es => fail(es.mkString), identity)
    }
    for (m <- machines; k <- Benchmarks.all) {
      val c = compile(k, m)
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

  test("on the RTL, compiled kernels compute the DSL results in the scheduled cycles") {
    val rng = new Random(5050L)
    simulate(new Core(CoreParams(machine))) { dut =>
      for (k <- Benchmarks.all) {
        val c = compile(k, machine)
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
