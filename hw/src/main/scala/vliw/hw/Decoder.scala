package vliw.hw

import chisel3._
import chisel3.util._

class DecodedBundle(val p: CoreParams) extends Bundle {
  val compute = Valid(new ComputeOperation(p.regBits))
  val rs1 = UInt(p.regBits.W)
  val rs2 = UInt(p.regBits.W)
  val halt = Bool()
  val memory = Valid(new MemoryOperation(p.regBits))

  /** The word is not a valid bundle (rule L1): unknown opcode, non-zero reserved or operand bits, a
    * register outside the register file, or HALT with a memory operation.
    */
  val illegal = Bool()
}

class DecoderIO(val p: CoreParams) extends Bundle {
  val word = Input(UInt(64.W))
  val out = Output(new DecodedBundle(p))
}

/** Combinational decoder of a 64-bit bundle (docs/ISA.md, "64bit bundleのエンコード案").
  *
  * Written independently of `vliw.isa.BundleCodec`; opcode values come from the machine
  * description. The compute word is bits 31:0 and the memory word bits 63:32.
  */
class Decoder(p: CoreParams) extends Module {
  val io = IO(new DecoderIO(p))

  private val c = io.word(31, 0)
  private val m = io.word(63, 32)
  private def reg(field: UInt): (UInt, Bool) = (field(p.regBits - 1, 0), field < p.registerCount.U)

  // Compute word.
  private val cOp = c(31, 28)
  private val (rd, rdOk) = reg(c(27, 24))
  private val (rs1, rs1Ok) = reg(c(23, 20))
  private val (rs2, rs2Ok) = reg(c(19, 16))
  private val kinds = Seq(
    p.opcode("ADD") -> ComputeKind.Add,
    p.opcode("SUB") -> ComputeKind.Sub,
    p.opcode("MUL") -> ComputeKind.Mul
  )
  private val cIsArith = kinds.map { case (code, _) => cOp === code.U }.reduce(_ || _)
  private val cIsHalt = cOp === p.machine.haltOpcode.U
  private val cNoOperands = c(27, 0) === 0.U
  private val cIllegal = Mux(
    cIsArith,
    c(15, 0) =/= 0.U || !rdOk || !rs1Ok || !rs2Ok,
    Mux(cOp === p.machine.nopOpcode.U || cIsHalt, !cNoOperands, true.B)
  )

  io.out.compute.valid := cIsArith && !cIllegal
  io.out.compute.bits.kind := MuxCase(
    ComputeKind.Add,
    kinds.map { case (code, k) => (cOp === code.U) -> k }
  )
  io.out.compute.bits.rd := rd
  io.out.rs1 := rs1
  io.out.rs2 := rs2
  io.out.halt := cIsHalt && !cIllegal

  // Memory word.
  private val mOp = m(31, 28)
  private val (mReg, mRegOk) = reg(m(27, 24))
  private val mIsLd = mOp === p.opcode("LD").U
  private val mIsSt = mOp === p.opcode("ST").U
  private val mIsNop = mOp === p.machine.nopOpcode.U
  private val mIllegal = Mux(
    mIsLd || mIsSt,
    m(14, 0) =/= 0.U || !mRegOk,
    Mux(mIsNop, m(27, 0) =/= 0.U, true.B)
  )

  io.out.memory.valid := (mIsLd || mIsSt) && !mIllegal
  io.out.memory.bits.kind := Mux(mIsLd, MemoryKind.Ld, MemoryKind.St)
  io.out.memory.bits.reg := mReg
  io.out.memory.bits.addr := m(23, 15)

  io.out.illegal := cIllegal || mIllegal || (cIsHalt && !mIsNop)
}
