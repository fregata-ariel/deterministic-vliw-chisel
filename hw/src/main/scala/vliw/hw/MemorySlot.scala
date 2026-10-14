package vliw.hw

import chisel3._
import chisel3.util._

object MemoryKind extends ChiselEnum {
  val Ld, St = Value
}

/** Decoded memory operation: LD reg <- [addr] or ST [addr] <- reg (NOP is not an operation). */
class MemoryOperation(val regBits: Int) extends Bundle {
  val kind = MemoryKind()
  val reg = UInt(regBits.W)
  val addr = UInt(9.W)
}

/** A scratchpad word write. */
class MemWrite extends Bundle {
  val addr = UInt(9.W)
  val data = UInt(32.W)
}

class MemorySlotIO(val p: CoreParams) extends Bundle {
  val op = Flipped(Valid(new MemoryOperation(p.regBits)))

  /** ST source value, read from the register file in the issue cycle. */
  val storeData = Input(UInt(32.W))

  /** Host access while the core is idle; reads return data in the next cycle. */
  val hostWrite = Flipped(Valid(new MemWrite))
  val hostReadAddr = Input(UInt(9.W))
  val hostReadData = Output(UInt(32.W))

  /** LD result written to the register file at the end of this cycle (memory write-back port). */
  val commit = Valid(new RegWrite(p.regBits))

  /** Scratchpad word written at the end of this cycle by a store. */
  val storeCommit = Valid(new MemWrite)

  val conflict = Output(Bool()) // rule L5 on the memory write-back port
  val pending = Output(Bool()) // a load has not been written back yet (rule L6)
  val outOfRange = Output(Bool()) // the issued operation's address is not in the scratchpad (L7)
}

/** Memory slot with a banked scratchpad (docs/ISA.md, "実行意味論").
  *
  * Every bank is a synchronous-read memory with one read/write port: `bank = addr % banks`,
  * `row = addr / banks`. LD issued in cycle s presents its address, the bank accepts it at E_(s+1),
  * and the read data of cycle s+1 is captured exactly once into the write-back schedule, which
  * writes the register at E_(s+L). ST writes the bank at E_(s+1). With one memory slot and ST
  * latency 1 a bank never sees a read and a write at the same edge.
  */
class MemorySlot(p: CoreParams) extends Module {
  val io = IO(new MemorySlotIO(p))

  private val banks = Seq.fill(p.banks)(SyncReadMem(p.wordsPerBank, UInt(32.W)))
  private val rowBits = log2Ceil(p.wordsPerBank).max(1)
  private val bankBits = log2Ceil(p.banks).max(1)

  private def bankOf(addr: UInt): UInt =
    if (p.banks == 1) 0.U else (addr % p.banks.U)(bankBits - 1, 0)
  private def rowOf(addr: UInt): UInt = (addr / p.banks.U)(rowBits - 1, 0)

  private val inRange = io.op.bits.addr < p.scratchpadWords.U
  io.outOfRange := io.op.valid && !inRange

  private val load = io.op.valid && io.op.bits.kind === MemoryKind.Ld && inRange
  private val store = io.op.valid && io.op.bits.kind === MemoryKind.St && inRange

  // One access per cycle: the core's operation, otherwise the host's write or read.
  private val addr = Mux(
    io.op.valid,
    io.op.bits.addr,
    Mux(io.hostWrite.valid, io.hostWrite.bits.addr, io.hostReadAddr)
  )
  private val isWrite = store || (!io.op.valid && io.hostWrite.valid)
  private val wdata = Mux(io.op.valid, io.storeData, io.hostWrite.bits.data)
  private val enable = load || store || !io.op.valid

  private val readData = banks.zipWithIndex.map { case (mem, b) =>
    mem.readWrite(rowOf(addr), wdata, enable && bankOf(addr) === b.U, isWrite)
  }
  private val readBank = RegNext(bankOf(addr))
  private val readValue = VecInit(readData)(readBank)
  io.hostReadData := readValue

  io.storeCommit.valid := store
  io.storeCommit.bits.addr := io.op.bits.addr
  io.storeCommit.bits.data := io.storeData

  // The load's destination waits one cycle for the read data, then enters the schedule with the
  // remaining latency L-1 (>= 1), so the register is written at E_(s+L).
  private val loadStage = RegInit(0.U.asTypeOf(Valid(UInt(p.regBits.W))))
  loadStage.valid := load
  loadStage.bits := io.op.bits.reg

  private val schedule = Module(new WritebackSchedule(p.regBits, p.latency("LD") - 1))
  schedule.io.issue.valid := loadStage.valid
  schedule.io.issue.bits.reg := loadStage.bits
  schedule.io.issue.bits.data := readValue
  schedule.io.issue.bits.latency := (p.latency("LD") - 1).U
  io.commit := schedule.io.commit
  io.conflict := schedule.io.conflict
  io.pending := loadStage.valid || schedule.io.pending
}
