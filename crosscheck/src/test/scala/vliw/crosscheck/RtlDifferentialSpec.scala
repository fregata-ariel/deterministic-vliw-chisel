package vliw.crosscheck

import scala.util.Random

import org.scalatest.funsuite.AnyFunSuite

import vliw.hw.{Core, CoreDriver, CoreParams, VliwChiselSim}
import vliw.isa._

import RtlPrograms.{canonical, compareOn, legalPrograms}

/** Random legal programs on the RTL and on the reference model: the traces must be identical cycle
  * by cycle, and so must every location that the program defines ([[RtlPrograms]]).
  */
class RtlDifferentialSpec extends AnyFunSuite with VliwChiselSim {
  private val machine = MachineDescription.load().fold(es => fail(es.mkString("\n")), identity)

  private val legalOnDefault = legalPrograms(machine, 314159L, 400)

  /** Runs every program on one RTL core for `m` and on the reference model, and compares. */
  private def compare(m: MachineDescription, programs: Vector[(Vector[Long], Inputs)]): Unit =
    simulate(new Core(CoreParams(m)))(compareOn(_, m, programs))

  test(
    "RTL and reference model produce identical traces and final states for random legal programs"
  ) {
    compare(machine, legalOnDefault)
  }

  RtlPrograms.variants(machine).zipWithIndex.foreach { case ((label, m), k) =>
    test(s"RTL and reference model agree on another configuration the RTL implements: $label") {
      assert(CoreParams.check(m).isRight)
      compare(m, legalPrograms(m, 1000L + k, 150))
    }
  }

  /** Runs each program on the core `dut` for `m` again with other input values: the cycles and the
    * commit timing stay the same.
    */
  private def sameTimingOn(
      dut: Core,
      m: MachineDescription,
      programs: Vector[(Vector[Long], Inputs)],
      rng: Random
  ): Unit = {
    def timing(t: Vector[ujson.Obj]) = t.map { line =>
      val l = ujson.read(ujson.write(line))
      l("rf_commit").arr.foreach(_.obj.remove("value"))
      l("mem_commit").arr.foreach(_.obj.remove("value"))
      canonical(l)
    }
    programs.foreach { case (words, in) =>
      val reference = CoreDriver.run(dut, m, words, in, Set.empty)
      (0 until 3).foreach { _ =>
        val other = Inputs(
          in.registers.map { case (k, _) => k -> (rng.nextLong() & 0xffffffffL) },
          in.memory.map { case (k, _) =>
            k -> (rng.nextLong() & 0xffffffffL)
          }
        )
        val run = CoreDriver.run(dut, m, words, other, Set.empty)
        assert(run.cycles == reference.cycles)
        assert(timing(run.trace) == timing(reference.trace))
      }
    }
  }

  test("on the RTL, cycles and commit timing do not depend on input values") {
    val rng = new Random(2718L)
    simulate(new Core(CoreParams(machine)))(sameTimingOn(_, machine, legalOnDefault.take(60), rng))
  }

  // ISA mvp-1 (#77): the default machine and MAC latency 3. Four read ports are outside the RTL
  // (#57); hw/AssertionSpec runs the L11 case (mac_store_read_ports) on the RTL.
  private def machineFile(path: java.nio.file.Path) =
    MachineDescription.load(path).fold(es => fail(es.mkString("\n")), identity)
  private val mvp1 = machineFile(MachineDescription.Mvp1Path)
  private val mac3 = machineFile(java.nio.file.Paths.get("tests", "machines", "mvp1_mac3.json"))
  private val rp4 = machineFile(java.nio.file.Paths.get("tests", "machines", "mvp1_rp4.json"))

  private def macs(words: Vector[Long], m: MachineDescription): Vector[ComputeOp.Mac] =
    new BundleCodec(m).decodeAll(words).toOption.get.collect { case Bundle(op: ComputeOp.Mac, _) =>
      op
    }

  Seq(mvp1, mac3).zipWithIndex.foreach { case (m, k) =>
    test(
      s"ISA mvp-1, ${m.machineId}: identical traces and final states for random legal programs with MAC, and timing independent of input values"
    ) {
      assert(CoreParams.check(m).isRight && CoreParams.check(rp4).isLeft)
      val programs = legalPrograms(m, 7700L + k, 400, mac = true)
      val withMac = programs.filter(p => macs(p._1, m).nonEmpty)
      val accumulating = withMac.count(p => macs(p._1, m).exists(op => op.rd == op.rs3))
      assert(
        withMac.size > 100 && accumulating > 40,
        s"${withMac.size} with MAC, $accumulating with rd = rs3"
      )
      simulate(new Core(CoreParams(m))) { dut =>
        compareOn(dut, m, programs)
        sameTimingOn(dut, m, withMac.take(40), new Random(27180L + k))
      }
    }
  }

  ignore(s"ISA mvp-1, ${rp4.machineId}: outside the RTL's range (read_ports 4, #57)") {}
}
