package vliw.hw

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class SmokeSpec extends AnyFunSuite with ChiselSim {
  test("the sum is registered and wraps at 32 bits") {
    simulate(new Smoke) { dut =>
      dut.io.a.poke("hffffffff".U)
      dut.io.b.poke(2.U)
      dut.clock.step()
      dut.io.sum.expect(1.U)
    }
  }
}
