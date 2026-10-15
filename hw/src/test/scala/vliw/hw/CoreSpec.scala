package vliw.hw

import java.nio.file.Paths

import chisel3._
import org.scalatest.funsuite.AnyFunSuite

import vliw.isa._

class CoreSpec extends AnyFunSuite with VliwChiselSim {
  private def load(name: String) =
    TestCase
      .load(Paths.get("tests", "cases", s"$name.json"))
      .fold(es => fail(es.mkString), identity)

  test("ISA example: memory[1] = 28 in 7 cycles, done for one cycle, no violation") {
    val c = load("isa_example")
    simulate(new Core(CoreParams(c.machine))) { dut =>
      val r = CoreDriver.run(dut, c.machine, c.words, c.inputs, Set(0, 1))
      assert(r.cycles == 7)
      assert(r.memory == Map(0 -> BigInt(7), 1 -> BigInt(28)))
      assert(r.registers.slice(1, 5) == Vector(7, 3, 21, 28).map(BigInt(_)))
      assert(r.violations.isEmpty, r.violations)
      assert(r.trace.count(_("done").bool) == 1)
      assert(r.trace.last("busy").bool == false && r.trace.last("done").bool)
      dut.clock.step()
      dut.io.done.expect(false.B)
      dut.io.busy.expect(false.B)
    }
  }

  test("HALT alone takes one cycle") {
    val c = load("halt_only")
    simulate(new Core(CoreParams(c.machine))) { dut =>
      assert(CoreDriver.run(dut, c.machine, c.words, c.inputs, Set.empty).cycles == 1)
    }
  }

  test("start is ignored while busy; the core can run again after done") {
    val c = load("isa_example")
    simulate(new Core(CoreParams(c.machine))) { dut =>
      CoreDriver.run(dut, c.machine, c.words, c.inputs, Set.empty)
      // Restart with a shorter program and pulse start again during the run.
      dut.io.host.length.poke(c.words.size.U)
      dut.io.host.start.poke(true.B)
      dut.clock.step()
      dut.io.busy.expect(true.B)
      dut.io.observe.pc.expect(0.U)
      dut.clock.step() // start is still high: must not restart the PC
      dut.io.observe.pc.expect(1.U)
      dut.io.host.start.poke(false.B)
      (2 to 6).foreach(_ => dut.clock.step()) // cycle 6 issues HALT
      dut.io.done.expect(false.B)
      dut.clock.step() // cycle 7 = h + 1
      dut.io.done.expect(true.B)
    }
  }
}
