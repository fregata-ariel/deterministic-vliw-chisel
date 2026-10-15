package vliw.hw

import chisel3._
import org.scalatest.funsuite.AnyFunSuite

import vliw.isa.MachineDescription

class MemorySlotSpec extends AnyFunSuite with SeededChiselSim {
  private val machine = MachineDescription.load().fold(es => fail(es.mkString), identity)
  private val params = CoreParams(machine)

  private def init(dut: MemorySlot): Unit = {
    dut.reset.poke(true.B)
    idle(dut)
    dut.clock.step()
    dut.reset.poke(false.B)
  }
  private def idle(dut: MemorySlot): Unit = {
    dut.io.op.valid.poke(false.B)
    dut.io.hostWrite.valid.poke(false.B)
  }
  private def hostWrite(dut: MemorySlot, addr: Int, value: BigInt): Unit = {
    dut.io.hostWrite.valid.poke(true.B)
    dut.io.hostWrite.bits.addr.poke(addr.U)
    dut.io.hostWrite.bits.data.poke(value.U(32.W))
    dut.clock.step()
    dut.io.hostWrite.valid.poke(false.B)
  }
  private def hostRead(dut: MemorySlot, addr: Int): BigInt = {
    dut.io.hostReadAddr.poke(addr.U)
    dut.clock.step()
    dut.io.hostReadData.peek().litValue
  }
  private def op(
      dut: MemorySlot,
      kind: MemoryKind.Type,
      reg: Int,
      addr: Int,
      storeData: BigInt = 0
  ): Unit = {
    dut.io.op.valid.poke(true.B)
    dut.io.op.bits.kind.poke(kind)
    dut.io.op.bits.reg.poke(reg.U)
    dut.io.op.bits.addr.poke(addr.U)
    dut.io.storeData.poke(storeData.U(32.W))
  }

  test("LD(s) writes the register at E_(s+2): the commit appears in cycle s+1, exactly once") {
    simulate(new MemorySlot(params)) { dut =>
      init(dut)
      hostWrite(dut, 7, 0xcafef00dL)
      op(dut, MemoryKind.Ld, 3, 7) // cycle s
      dut.io.commit.valid.expect(false.B)
      dut.clock.step()
      idle(dut) // cycle s+1
      dut.io.pending.expect(true.B, "the write happens at the end of s+1, after a HALT in s+1")
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.bits.reg.expect(3.U)
      dut.io.commit.bits.data.expect(0xcafef00dL.U)
      dut.clock.step()
      dut.io.commit.valid.expect(false.B) // never again, whatever the memory output holds
      dut.io.pending.expect(false.B)
    }
  }

  test("ST(s) updates the scratchpad at E_(s+1), so LD(s+1) reads the new value") {
    simulate(new MemorySlot(params)) { dut =>
      init(dut)
      op(dut, MemoryKind.St, 2, 300, 1234)
      dut.io.storeCommit.valid.expect(true.B)
      dut.io.storeCommit.bits.addr.expect(300.U)
      dut.clock.step()
      op(dut, MemoryKind.Ld, 5, 300)
      dut.clock.step()
      idle(dut)
      dut.io.commit.bits.data.expect(1234.U)
      assert(hostRead(dut, 300) == 1234)
    }
  }

  test("every word of both banks is distinct and reachable") {
    simulate(new MemorySlot(params)) { dut =>
      init(dut)
      val addrs = Seq(0, 1, 2, 3, 254, 255, 256, 257, 510, 511)
      addrs.foreach(a => hostWrite(dut, a, 1000 + a))
      addrs.foreach(a => assert(hostRead(dut, a) == 1000 + a, s"address $a"))
    }
  }

  test("a load at s is pending in cycle s+1 when its latency is 3") {
    val v = ujson.read(machine.canonicalJson)
    v("operations")("LD")("latency") = 3
    val slow =
      CoreParams(MachineDescription.parse(ujson.write(v)).fold(es => fail(es.mkString), identity))
    simulate(new MemorySlot(slow)) { dut =>
      init(dut)
      hostWrite(dut, 1, 77)
      op(dut, MemoryKind.Ld, 4, 1)
      dut.clock.step()
      idle(dut)
      dut.io.pending.expect(true.B)
      dut.io.commit.valid.expect(false.B)
      dut.clock.step()
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.bits.data.expect(77.U)
      dut.clock.step()
      dut.io.pending.expect(false.B)
    }
  }

  test("addresses beyond a smaller scratchpad are flagged (L7) and not accessed") {
    val v = ujson.read(machine.canonicalJson)
    v("scratchpad")("words_per_bank") = 4
    val small =
      CoreParams(MachineDescription.parse(ujson.write(v)).fold(es => fail(es.mkString), identity))
    simulate(new MemorySlot(small)) { dut =>
      init(dut)
      op(dut, MemoryKind.St, 0, 8, 1)
      dut.io.outOfRange.expect(true.B)
      dut.io.storeCommit.valid.expect(false.B)
      op(dut, MemoryKind.Ld, 0, 7)
      dut.io.outOfRange.expect(false.B)
    }
  }
}
