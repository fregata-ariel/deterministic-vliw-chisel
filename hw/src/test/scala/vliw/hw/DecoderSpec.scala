package vliw.hw

import chisel3._
import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

import vliw.isa.MachineDescription

/** MAC in the decoder: fields, reserved bits, and no MAC on mvp-0 (docs/ISA.md, "mvp-1: MAC"). */
class DecoderSpec extends AnyFunSuite with VliwChiselSim {
  private val mvp0 = MachineDescription.load().fold(es => fail(es.mkString), identity)
  private val mvp1 =
    MachineDescription.load(MachineDescription.Mvp1Path).fold(es => fail(es.mkString), identity)

  /** `mac rd, rs1, rs2, rs3` with opcode 4 (docs/ISA.md); the memory word is NOP. */
  private def mac(rd: Int, rs1: Int, rs2: Int, rs3: Int, low: Int = 0): BigInt =
    BigInt((4L << 28) | (rd.toLong << 24) | (rs1.toLong << 20) | (rs2 << 16) | (rs3 << 12) | low)

  private def decodes(dut: Decoder, word: BigInt): Unit = dut.io.word.poke(word.U(64.W))

  test("MAC: rd, rs1, rs2 and rs3 from bits 27:12, kind Mac, on mvp-1") {
    simulate(new Decoder(CoreParams(mvp1))) { dut =>
      val out = dut.io.out
      decodes(dut, 0x45123000L) // the example of docs/ISA.md: mac r5, r1, r2, r3
      out.illegal.expect(false.B)
      out.compute.valid.expect(true.B)
      out.compute.bits.kind.expect(ComputeKind.Mac)
      out.compute.bits.rd.expect(5.U)
      out.rs1.expect(1.U)
      out.rs2.expect(2.U)
      out.rs3.get.expect(3.U)
      out.halt.expect(false.B)
      out.memory.valid.expect(false.B)
      decodes(dut, mac(15, 14, 13, 12) | (BigInt(0x10000000L | (7 << 24) | (9 << 15)) << 32))
      out.illegal.expect(false.B) // with ST [9], r7 the bundle decodes; L11 is the core's
      out.compute.bits.kind.expect(ComputeKind.Mac)
      out.rs3.get.expect(12.U)
      out.memory.valid.expect(true.B)
      out.memory.bits.kind.expect(MemoryKind.Ld)
      decodes(dut, mac(1, 2, 3, 4) | (BigInt(0x20000000L | (7 << 24) | (9 << 15)) << 32))
      out.memory.bits.kind.expect(MemoryKind.St)
      out.memory.bits.reg.expect(7.U)
    }
  }

  test("MAC: bits 11:0 are reserved; ADD/SUB/MUL still reserve bits 15:0") {
    simulate(new Decoder(CoreParams(mvp1))) { dut =>
      val out = dut.io.out
      Seq(1, 0x800, 0xfff).foreach { low =>
        decodes(dut, mac(5, 1, 2, 3, low))
        out.illegal.expect(true.B, f"reserved bits 0x$low%03x")
        out.compute.valid.expect(false.B)
      }
      Seq(1, 2, 3).foreach { opcode => // rs3's field in an ADD, SUB or MUL word
        decodes(dut, BigInt((opcode.toLong << 28) | (5 << 24) | (1 << 20) | (2 << 16) | (3 << 12)))
        out.illegal.expect(true.B, s"opcode $opcode with bits 15:12 set")
      }
    }
  }

  test("MAC: rs3 outside the register file is illegal") {
    val v = ujson.read(mvp1.canonicalJson)
    v("register_file")("count") = 8
    val small = MachineDescription.parse(ujson.write(v)).fold(es => fail(es.mkString), identity)
    simulate(new Decoder(CoreParams(small))) { dut =>
      decodes(dut, mac(5, 1, 2, 7))
      dut.io.out.illegal.expect(false.B)
      dut.io.out.rs3.get.expect(7.U)
      decodes(dut, mac(5, 1, 2, 8))
      dut.io.out.illegal.expect(true.B)
    }
  }

  test("mvp-0 has no MAC: opcode 4 is unknown (L1), and the decoder has no rs3 output") {
    simulate(new Decoder(CoreParams(mvp0))) { dut =>
      assert(dut.io.out.rs3.isEmpty)
      decodes(dut, 0x45123000L)
      dut.io.out.illegal.expect(true.B)
      dut.io.out.compute.valid.expect(false.B)
      decodes(dut, 0x45120000L) // no rs3 either
      dut.io.out.illegal.expect(true.B)
    }
    val sv = ChiselStage.emitSystemVerilog(
      new Decoder(CoreParams(mvp0)),
      firtoolOpts = Emit.FirtoolOptions
    )
    assert(!sv.contains("rs3") && !sv.contains("4'h4"), sv)
  }
}
