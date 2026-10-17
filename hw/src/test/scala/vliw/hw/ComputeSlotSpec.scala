package vliw.hw

import chisel3._
import org.scalatest.funsuite.AnyFunSuite

import vliw.isa.MachineDescription

class ComputeSlotSpec extends AnyFunSuite with VliwChiselSim {
  private def load(path: java.nio.file.Path) =
    CoreParams(MachineDescription.load(path).fold(es => fail(es.mkString), identity))
  private val params = load(MachineDescription.DefaultPath)
  private val mvp1 = load(MachineDescription.Mvp1Path) // MAC latency 2
  private val mac3 = load(java.nio.file.Paths.get("tests", "machines", "mvp1_mac3.json"))

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
      b: BigInt,
      c: BigInt = 0
  ): Unit = {
    dut.io.op.valid.poke(true.B)
    dut.io.op.bits.kind.poke(kind)
    dut.io.op.bits.rd.poke(rd.U)
    dut.io.a.poke(a.U(32.W))
    dut.io.b.poke(b.U(32.W))
    dut.io.c.foreach(_.poke(c.U(32.W)))
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

  test("MAC computes low32(a * b + c), modulo 2^32, and is written at E_(s+2)") {
    simulate(new ComputeSlot(mvp1)) { dut =>
      init(dut)
      val max = 0xffffffffL
      // (a, b, c) -> low 32 bits: plain, (2^32-1)^2 + (2^32-1) = 2^32 (2^32-1), and a carry out of
      // the low 32 bits of the product plus c.
      val cases = Seq(
        (3L, 5L, 7L) -> 22L,
        (max, max, max) -> 0L,
        (0x10000L, 0x10000L, max) -> max,
        (0x10001L, 0x10001L, 0xfffdffffL) -> 0L, // 0x20001 + 0xfffdffff = 2^32
        (max, 2L, 3L) -> 1L
      )
      cases.zipWithIndex.foreach { case (((a, b, c), value), i) =>
        issue(dut, ComputeKind.Mac, i + 1, a, b, c)
        dut.io.latency.expect(2.U)
        if (i > 0) commits(dut, i, cases(i - 1)._2)
        else dut.io.commit.valid.expect(false.B)
        dut.io.conflict.expect(false.B)
        dut.clock.step()
        if (i == cases.size - 1) {
          nop(dut)
          dut.io.pending.expect(true.B)
          commits(dut, i + 1, value)
          dut.clock.step()
          dut.io.commit.valid.expect(false.B)
          dut.io.pending.expect(false.B)
        }
      }
    }
  }

  test("MAC latency 3: written at the end of s+2, i.e. visible from s+3") {
    simulate(new ComputeSlot(mac3)) { dut =>
      init(dut)
      issue(dut, ComputeKind.Mac, 4, 2, 3, 4)
      dut.io.latency.expect(3.U)
      dut.io.commit.valid.expect(false.B)
      dut.clock.step()
      nop(dut)
      dut.io.commit.valid.expect(false.B)
      dut.io.pending.expect(true.B)
      dut.clock.step()
      commits(dut, 4, 10)
      dut.clock.step()
      dut.io.commit.valid.expect(false.B)
      dut.io.pending.expect(false.B)
    }
  }

  test("MAC shares the write-back port: MAC(s) meets ADD(s+1), not MUL(s+1), at latency 2 (L5)") {
    simulate(new ComputeSlot(mvp1)) { dut =>
      init(dut)
      issue(dut, ComputeKind.Mac, 1, 2, 3, 4)
      dut.clock.step()
      issue(dut, ComputeKind.Mul, 2, 2, 3) // E_3, after MAC's E_2
      dut.io.conflict.expect(false.B)
      commits(dut, 1, 10)
      dut.clock.step()
      issue(dut, ComputeKind.Mac, 3, 1, 1, 1) // E_4
      dut.io.conflict.expect(false.B)
      commits(dut, 2, 6)
      dut.clock.step()
      issue(dut, ComputeKind.Add, 4, 2, 3) // E_4 as well
      dut.io.conflict.expect(true.B)
    }
    simulate(new ComputeSlot(mvp1)) { dut =>
      init(dut)
      issue(dut, ComputeKind.Mul, 1, 2, 3)
      dut.clock.step()
      issue(dut, ComputeKind.Mac, 2, 2, 3, 4) // MUL(s) and MAC(s+1): E_2 and E_3
      dut.io.conflict.expect(false.B)
      dut.clock.step()
      issue(dut, ComputeKind.Sub, 3, 2, 3) // E_3, with MAC
      dut.io.conflict.expect(true.B)
    }
  }

  test("MAC latency 3: MAC(s) meets MUL(s+1) and ADD(s+2) on the write-back port (L5)") {
    simulate(new ComputeSlot(mac3)) { dut =>
      init(dut)
      issue(dut, ComputeKind.Mac, 1, 2, 3, 4) // E_3
      dut.clock.step()
      issue(dut, ComputeKind.Mul, 2, 2, 3) // E_3
      dut.io.conflict.expect(true.B)
    }
    simulate(new ComputeSlot(mac3)) { dut =>
      init(dut)
      issue(dut, ComputeKind.Mac, 1, 2, 3, 4) // E_3
      dut.clock.step()
      issue(dut, ComputeKind.Add, 2, 2, 3) // E_2
      dut.io.conflict.expect(false.B)
      dut.clock.step()
      issue(dut, ComputeKind.Add, 3, 2, 3) // E_3
      dut.io.conflict.expect(true.B)
    }
  }
}
