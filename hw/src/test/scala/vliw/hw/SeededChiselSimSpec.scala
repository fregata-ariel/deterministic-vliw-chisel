package vliw.hw

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import chisel3._
import org.scalatest.funsuite.AnyFunSuite

class SeededChiselSimSpec extends AnyFunSuite with SeededChiselSim {
  test("the seed is rendered in the only form that Verilator accepts") {
    assert(SimSeed.plusArg.simulatorFlags == s"+verilator+seed+${SimSeed.value}")
    assert(SimSeed.value >= 1)
  }

  test("every simulation records its seed and replays with it") {
    simulate(new RegisterFile(16)) { dut =>
      dut.io.write.foreach(_.valid.poke(false.B))
      dut.clock.step()
    }
    val dir = implementation.getDirectory
    def read(name: String) =
      new String(Files.readAllBytes(dir.resolve(name)), StandardCharsets.UTF_8)
    assert(read("seed.txt") == SimSeed.describe + "\n")
    // The `replay` target of the Makefile runs the simulation with the same arguments as this run.
    assert(read("workdir-verilator/Makefile").contains(SimSeed.plusArg.simulatorFlags))
  }
}
