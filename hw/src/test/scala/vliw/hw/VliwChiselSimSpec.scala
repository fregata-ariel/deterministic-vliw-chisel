package vliw.hw

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import chisel3._
import org.scalatest.funsuite.AnyFunSuite

class VliwChiselSimSpec extends AnyFunSuite with VliwChiselSim {
  test("the seed is rendered in the only form that Verilator accepts") {
    assert(SimSeed.plusArg.simulatorFlags == s"+verilator+seed+${SimSeed.value}")
    assert(SimSeed.value >= 1)
  }

  test("Verilator verilates in one thread and builds on all cores") {
    assert(
      SimVerilator.parallelism.toCompileFlags == Seq("--build-jobs", "0", "--verilate-jobs", "1")
    )
  }

  test("every simulation records its seed and is built and replayed with the project's settings") {
    simulate(new RegisterFile(16)) { dut =>
      dut.io.write.foreach(_.valid.poke(false.B))
      dut.clock.step()
    }
    val dir = implementation.getDirectory
    def read(name: String) =
      new String(Files.readAllBytes(dir.resolve(name)), StandardCharsets.UTF_8)
    assert(read("seed.txt") == SimSeed.describe + "\n")
    // The seed with the version of the Verilator that ran (ADR-0020, R3).
    assert(
      read("seed.txt") == s"VLIW_SIM_SEED=${SimSeed.value} (${SimSeed.source}) verilator=" +
        s"${Toolchain.installedVerilator}\n"
    )

    // The Makefile in the work directory holds the commands that svsim ran: `simulation` builds,
    // `replay` runs the simulation again with the same arguments as this run.
    val makefile = read("workdir-verilator/Makefile")
    assert(makefile.contains(SimSeed.plusArg.simulatorFlags))
    val verilator = makefile.linesIterator
      .dropWhile(!_.startsWith("simulation:"))
      .drop(1)
      .takeWhile(_.trim.nonEmpty)
      .flatMap(line => "'([^']*)'".r.findAllMatchIn(line).map(_.group(1)))
      .toSeq
    assert(verilator.contains("--build"), verilator)
    assert(verilator.containsSlice(Seq("--build-jobs", "0")), verilator)
    assert(verilator.containsSlice(Seq("--verilate-jobs", "1")), verilator)
    assert(!verilator.contains("-j"), verilator) // -j would also set the verilation threads
  }
}
