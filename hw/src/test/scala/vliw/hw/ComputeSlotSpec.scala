package vliw.hw

import chisel3._
import org.scalatest.funsuite.AnyFunSuite

import vliw.isa.MachineDescription

class ComputeSlotSpec extends AnyFunSuite with SeededChiselSim {
  private val params = CoreParams(MachineDescription.load().fold(es => fail(es.mkString), identity))

  private def init(dut: ComputeSlot): Unit = {
    dut.reset.poke(true.B)
    dut.io.op.valid.poke(false.B)
    dut.clock.step()
    dut.reset.poke(false.B)
  }
  private def issue(
      dut: ComputeSlot,
      kind: ComputeKind.Type,
      rd: Int,
      a: BigInt,
      b: BigInt
  ): Unit = {
    dut.io.op.valid.poke(true.B)
    dut.io.op.bits.kind.poke(kind)
    dut.io.op.bits.rd.poke(rd.U)
    dut.io.a.poke(a.U(32.W))
    dut.io.b.poke(b.U(32.W))
  }
  private def nop(dut: ComputeSlot): Unit = dut.io.op.valid.poke(false.B)
  private def commits(dut: ComputeSlot, rd: Int, value: BigInt): Unit = {
    dut.io.commit.valid.expect(true.B)
    dut.io.commit.bits.reg.expect(rd.U)
    dut.io.commit.bits.data.expect(value.U(32.W))
  }

  test("ADD and SUB (L=1) are written at the end of the issue cycle, modulo 2^32") {
    simulate(new ComputeSlot(params)) { dut =>
      init(dut)
      issue(dut, ComputeKind.Add, 3, 0xffffffffL, 1)
      commits(dut, 3, 0)
      dut.clock.step()
      issue(dut, ComputeKind.Sub, 4, 0, 1)
      commits(dut, 4, 0xffffffffL)
      dut.io.latency.expect(1.U)
    }
  }

  test("MUL (L=2) is written at the end of s+1, i.e. visible from s+2") {
    simulate(new ComputeSlot(params)) { dut =>
      init(dut)
      issue(dut, ComputeKind.Mul, 5, 0x10001L, 0x10001L)
      dut.io.latency.expect(2.U)
      dut.io.commit.valid.expect(false.B)
      dut.clock.step()
      nop(dut)
      dut.io.pending.expect(true.B)
      commits(dut, 5, 0x20001L) // (2^16 + 1)^2 = 2^32 + 2^17 + 1 -> low 32 bits
      dut.clock.step()
      dut.io.commit.valid.expect(false.B)
      dut.io.pending.expect(false.B)
    }
  }

  test("back-to-back MUL (II=1) commits one result per cycle") {
    simulate(new ComputeSlot(params)) { dut =>
      init(dut)
      (1 to 4).foreach { i =>
        issue(dut, ComputeKind.Mul, i, i, 10)
        if (i > 1) commits(dut, i - 1, (i - 1) * 10)
        dut.io.conflict.expect(false.B)
        dut.clock.step()
      }
      nop(dut)
      commits(dut, 4, 40)
    }
  }

  test("MUL(s) followed by ADD(s+1) is reported as a write-back port conflict (L5)") {
    simulate(new ComputeSlot(params)) { dut =>
      init(dut)
      issue(dut, ComputeKind.Mul, 1, 2, 3)
      dut.clock.step()
      issue(dut, ComputeKind.Add, 2, 2, 3)
      dut.io.conflict.expect(true.B)
    }
  }
}
