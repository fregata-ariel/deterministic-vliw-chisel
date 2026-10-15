package vliw.hw

import chisel3._
import org.scalatest.funsuite.AnyFunSuite

class RegisterFileSpec extends AnyFunSuite with SeededChiselSim {
  private def write(dut: RegisterFile, port: Int, reg: Int, value: BigInt): Unit = {
    dut.io.write(port).valid.poke(true.B)
    dut.io.write(port).bits.reg.poke(reg.U)
    dut.io.write(port).bits.data.poke(value.U(32.W))
  }
  private def idle(dut: RegisterFile): Unit = dut.io.write.foreach(_.valid.poke(false.B))

  test("a write in cycle s is visible from cycle s+1; reads in cycle s see the old value") {
    simulate(new RegisterFile(16)) { dut =>
      idle(dut)
      write(dut, 0, 3, 11)
      dut.clock.step()
      write(dut, 0, 3, 22)
      dut.io.read(0).addr.poke(3.U)
      dut.io.read(0).data.expect(11.U) // cycle s+1: the first write, not the one requested now
      dut.clock.step()
      idle(dut)
      dut.io.read(0).data.expect(22.U)
    }
  }

  test("both write ports and all three read ports work in the same cycle") {
    simulate(new RegisterFile(16)) { dut =>
      idle(dut)
      write(dut, 0, 1, 0xdeadbeefL)
      write(dut, 1, 15, 0xffffffffL)
      dut.clock.step()
      idle(dut)
      Seq(1 -> 0xdeadbeefL, 15 -> 0xffffffffL, 1 -> 0xdeadbeefL).zipWithIndex.foreach {
        case ((r, v), p) =>
          dut.io.read(p).addr.poke(r.U)
          dut.io.read(p).data.expect(v.U(32.W))
      }
    }
  }
}

class WriteReservationsSpec extends AnyFunSuite with SeededChiselSim {
  private def claim(dut: WriteReservations, port: Int, reg: Int, latency: Int): Unit = {
    dut.io.claim(port).valid.poke(true.B)
    dut.io.claim(port).bits.reg.poke(reg.U)
    dut.io.claim(port).bits.latency.poke(latency.U)
  }
  private def noClaims(dut: WriteReservations): Unit = dut.io.claim.foreach(_.valid.poke(false.B))
  private def inFlight(dut: WriteReservations, reg: Int): Boolean = {
    dut.io.query(0).poke(reg.U)
    dut.io.inFlight(0).peek().litToBoolean
  }

  test("a claim with latency L marks the register in cycles s+1 .. s+L-1 only") {
    simulate(new WriteReservations(16, 3, 1)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      noClaims(dut)
      claim(dut, 0, 4, 3) // cycle s
      assert(!inFlight(dut, 4), "not marked in the issue cycle")
      dut.clock.step()
      noClaims(dut)
      assert(inFlight(dut, 4)) // s+1
      assert(!inFlight(dut, 5))
      dut.io.anyInFlight.expect(true.B)
      dut.clock.step()
      assert(inFlight(dut, 4)) // s+2
      dut.clock.step()
      assert(!inFlight(dut, 4)) // s+3: visible
      dut.io.anyInFlight.expect(false.B)
    }
  }

  test("latency 1 never marks the register; both ports claim independently") {
    simulate(new WriteReservations(16, 2, 2)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      claim(dut, 0, 1, 1)
      claim(dut, 1, 2, 2)
      dut.clock.step()
      noClaims(dut)
      dut.io.query(0).poke(1.U)
      dut.io.query(1).poke(2.U)
      dut.io.inFlight(0).expect(false.B)
      dut.io.inFlight(1).expect(true.B)
      dut.clock.step()
      dut.io.inFlight(1).expect(false.B)
    }
  }
}
