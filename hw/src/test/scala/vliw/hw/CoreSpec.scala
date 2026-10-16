package vliw.hw

import java.nio.file.Paths

import chisel3._
import chisel3.simulator.Exceptions.AssertionFailed
import org.scalatest.funsuite.AnyFunSuite

import vliw.isa._

class CoreSpec extends AnyFunSuite with VliwChiselSim {
  private def load(name: String) =
    TestCase
      .load(Paths.get("tests", "cases", s"$name.json"))
      .fold(es => fail(es.mkString), identity)

  private def machine(path: java.nio.file.Path) =
    MachineDescription.load(path).fold(es => fail(es.mkString), identity)
  private val mvp1 = machine(MachineDescription.Mvp1Path)
  private val mac3 = machine(Paths.get("tests", "machines", "mvp1_mac3.json"))

  private def words(m: MachineDescription, lines: String*): Vector[Long] =
    Assembler
      .assemble(lines.mkString("\n"), m)
      .flatMap(p => new BundleCodec(m).encodeAll(p.bundles).left.map(List(_)))
      .fold(es => fail(es.mkString("\n")), identity)

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

  test("mvp-1: read port 2 reads MAC's rs3, and the ST source in every other bundle") {
    val in = Inputs(Map(1 -> 2L, 2 -> 3L, 3 -> 4L, 5 -> 9L), Map.empty)
    val program = words(
      mvp1,
      "mac r4, r1, r2, r3            ; 2 * 3 + 4 = 10, rs3 through port 2",
      "mul r6, r1, r2 | st [0], r5   ; port 2: r5",
      "nop            | st [1], r3   ; port 2: r3",
      "halt"
    )
    simulate(new Core(CoreParams(mvp1))) { dut =>
      val r = CoreDriver.run(dut, mvp1, program, in, Set(0, 1))
      assert(r.violations.isEmpty, r.violations)
      assert(r.cycles == 4)
      assert(r.registers(4) == 10 && r.registers(6) == 6)
      assert(r.memory == Map(0 -> BigInt(9), 1 -> BigInt(4)))
      assert(r.trace.head("issue")("compute").str == "mac r4, r1, r2, r3")
    }
  }

  test("mvp-1: MAC and ST in one bundle read 4 registers with 3 ports: L11 and its assertion") {
    val in = Inputs(Map(1 -> 2L, 2 -> 3L, 3 -> 4L, 5 -> 9L), Map.empty)
    val program = words(mvp1, "mac r4, r1, r2, r3 | st [0], r5", "nop", "halt")
    val e = intercept[AssertionFailed] {
      simulate(new Core(CoreParams(mvp1))) { dut =>
        CoreDriver.start(dut, mvp1, program, in)
        dut.io.violation.readPorts.get.expect(true.B)
        dut.io.violation.inFlightRead.expect(false.B)
        // Port 2 reads rs3 (r3 = 4) for the MAC, so the ST does not get its source r5.
        dut.io.observe.memCommit.valid.expect(true.B)
        dut.io.observe.memCommit.bits.data.expect(4.U)
        dut.clock.step()
      }
    }
    assert(e.getMessage.contains("L11 (more register reads than read ports) at pc"), e.getMessage)
  }

  /** MUL, LD, MAC, ST and ADD; MAC with latency `l`. Hand-derived: r3 = 3 * 5 = 15, r4 = [0] = 7,
    * r5 = [1] = 0x10001, r6 = 15 * 7 + 3 = 108, r7 = low32(0x10001^2) + 108 = 0x20001 + 108 =
    * 131181, r8 = 131181 + 15 = 131196, r9 = 131199; [2] = 15, [3] = 131196. HALT at 4 + 2l.
    */
  private def macProgram(m: MachineDescription, l: Int) = words(
    m,
    Seq(
      Seq("mul r3, r1, r2     | ld r4, [0]", "nop                | ld r5, [1]"),
      Seq("mac r6, r3, r4, r1 ; r6 from 2 + l", "nop                | st [2], r3"),
      Seq.fill(l - 2)("nop"),
      Seq("mac r7, r5, r5, r6 ; at 2 + l, r7 from 2 + 2l"),
      Seq.fill(l - 1)("nop"),
      Seq("add r8, r7, r3", "add r9, r8, r1     | st [3], r8", "halt")
    ).flatten: _*
  )

  Seq(mvp1 -> 2, mac3 -> 3).foreach { case (m, l) =>
    test(s"mvp-1 with MAC latency $l: MUL, LD, MAC, ST and ADD give the hand-derived state") {
      val program = macProgram(m, l)
      assert(program.size == 5 + 2 * l)
      val in = Inputs(Map(1 -> 3L, 2 -> 5L), Map(0 -> 7L, 1 -> 0x10001L))
      simulate(new Core(CoreParams(m))) { dut =>
        val r = CoreDriver.run(dut, m, program, in, Set(0, 1, 2, 3))
        assert(r.violations.isEmpty, r.violations)
        assert(r.cycles == 5 + 2 * l)
        assert(
          r.registers.slice(3, 10) == Vector(15, 7, 0x10001, 108, 131181, 131196, 131199)
            .map(BigInt(_))
        )
        assert(r.memory == Map(0 -> 7, 1 -> 0x10001, 2 -> 15, 3 -> 131196).map { case (a, v) =>
          a -> BigInt(v)
        })
        // MAC commits on the compute port at E_(s+l): the trace line of cycle s+l shows it.
        val macCommits = r.trace.zipWithIndex.collect {
          case (line, c) if line("rf_commit").arr.exists(w => Set(6, 7)(w("reg").num.toInt)) => c
        }
        assert(macCommits == Seq(2 + l, 2 + 2 * l))
      }
    }
  }
}
