package vliw.hw

import chisel3._
import chisel3.util._

object MemoryKind extends ChiselEnum {
  val Ld, St = Value
}

/** Decoded memory operation: LD reg <- [addr] or ST [addr] <- reg (NOP is not an operation).
  *
  * With `hasWide` (W-full, ISA mvp-2): `wide` marks LD.W and ST.W, which move the words `addr` and
  * `addr + 1` to or from the pair `reg` (even) and `reg + 1`.
  */
class MemoryOperation(val regBits: Int, val hasWide: Boolean = false) extends Bundle {
  val kind = MemoryKind()
  val reg = UInt(regBits.W)
  val addr = UInt(9.W)
  val wide = Option.when(hasWide)(Bool())
}

/** A scratchpad word write. */
class MemWrite extends Bundle {
  val addr = UInt(9.W)
  val data = UInt(32.W)
}

class MemorySlotIO(val p: CoreParams) extends Bundle {
  val op = Flipped(Valid(new MemoryOperation(p.regBits, p.hasWideMemory)))

  /** ST source value, read from the register file in the issue cycle. */
  val storeData = Input(UInt(32.W))

  /** W-full: lane 1 of the ST.W source, r(reg + 1), read in the issue cycle. */
  val storeData1 = Option.when(p.hasWideMemory)(Input(UInt(32.W)))

  /** Host access while the core is idle; reads return data in the next cycle. */
  val hostWrite = Flipped(Valid(new MemWrite))
  val hostReadAddr = Input(UInt(9.W))
  val hostReadData = Output(UInt(32.W))

  /** LD result written to the register file at the end of this cycle (memory write-back port); a
    * pair for LD.W (W-full).
    */
  val commit = Valid(new RegWrite(p.regBits, p.hasWideMemory))

  /** Scratchpad word written at the end of this cycle by a store (word `addr` of ST.W). */
  val storeCommit = Valid(new MemWrite)

  /** W-full: the word `addr + 1` written at the end of this cycle by ST.W. */
  val storeCommit1 = Option.when(p.hasWideMemory)(Valid(new MemWrite))

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
  *
  * W-full (ISA mvp-2, docs/ISA.md "LD.W・ST.W の語・番地・bank"): LD.W and ST.W access word `a` in lane 0
  * and word `a+1` in lane 1, at any `a`. With 2 or more banks the two words are in different banks,
  * so every bank still sees at most one access per cycle (1RW). The bank of word `a+1` takes its
  * row and lane 1's store data; with 2 banks an odd `a` swaps the lanes and bank 0 takes the next
  * row. Lane 1's read data comes from bank `(readBank + 1) mod banks`, derived from the existing
  * `readBank`. A wide access with a word outside the scratchpad accesses neither word (L7). LD.W
  * enters the schedule as one pair entry with its own latency.
  */
class MemorySlot(p: CoreParams) extends Module {
  val io = IO(new MemorySlotIO(p))

  private val banks = Seq.fill(p.banks)(SyncReadMem(p.wordsPerBank, UInt(32.W)))
  private val rowBits = log2Ceil(p.wordsPerBank).max(1)
  private val bankBits = log2Ceil(p.banks).max(1)

  private def bankOf(addr: UInt): UInt =
    if (p.banks == 1) 0.U else (addr % p.banks.U)(bankBits - 1, 0)
  private def rowOf(addr: UInt): UInt = (addr / p.banks.U)(rowBits - 1, 0)

  // W-full: LD.W and ST.W, and their second word a+1 (10 bits: word 512 is in no scratchpad).
  private val wide = io.op.bits.wide.map(w => (io.op.valid && w, io.op.bits.addr +& 1.U))

  private val inRange = wide.fold(io.op.bits.addr < p.scratchpadWords.U) { case (w, addr1) =>
    io.op.bits.addr < p.scratchpadWords.U && (!w || addr1 < p.scratchpadWords.U)
  }
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
    wide.fold(mem.readWrite(rowOf(addr), wdata, enable && bankOf(addr) === b.U, isWrite)) {
      case (w, addr1) =>
        // The bank of word a+1 serves lane 1 of a wide access: its row and its store data.
        val lane1 = w && bankOf(addr1) === b.U
        mem.readWrite(
          Mux(lane1, rowOf(addr1), rowOf(addr)),
          Mux(lane1, io.storeData1.get, wdata),
          enable && (bankOf(addr) === b.U || lane1),
          isWrite
        )
    }
  }
  private val readBank = RegNext(bankOf(addr))
  private val readValue = VecInit(readData)(readBank)
  io.hostReadData := readValue

  // W-full: lane 1 reads the bank after readBank (no register of its own).
  private val readValue1 = Option.when(p.hasWideMemory) {
    val next = Mux(readBank === (p.banks - 1).U, 0.U, (readBank +& 1.U)(bankBits - 1, 0))
    VecInit(readData)(next)
  }

  io.storeCommit.valid := store
  io.storeCommit.bits.addr := io.op.bits.addr
  io.storeCommit.bits.data := io.storeData
  io.storeCommit1.zip(wide).foreach { case (c, (w, addr1)) =>
    c.valid := store && w
    c.bits.addr := addr1(8, 0)
    c.bits.data := io.storeData1.get
  }

  // The load's destination waits one cycle for the read data, then enters the schedule with the
  // remaining latency L-1 (>= 1), so the register is written at E_(s+L).
  private val loadStage = RegInit(0.U.asTypeOf(Valid(UInt(p.regBits.W))))
  loadStage.valid := load
  loadStage.bits := io.op.bits.reg

  // W-full: whether the load in loadStage is LD.W (a pair, with LD.W's latency); reset as loadStage.
  private val loadWide = wide.map { case (w, _) =>
    val r = RegInit(false.B)
    r := load && w
    r
  }

  private val schedule =
    Module(new WritebackSchedule(p.regBits, p.maxLoadLatency - 1, p.hasWideMemory))
  schedule.io.issue.valid := loadStage.valid
  schedule.io.issue.bits.reg := loadStage.bits
  schedule.io.issue.bits.data := readValue
  schedule.io.issue.bits.latency := (p.latency("LD") - 1).U
  loadWide.foreach { w =>
    schedule.io.issue.bits.pair.get := w
    schedule.io.issue.bits.data1.get := readValue1.get
    when(w) {
      schedule.io.issue.bits.latency := (p.latency("LD.W") - 1).U
    }
  }
  io.commit := schedule.io.commit
  io.conflict := schedule.io.conflict
  io.pending := loadStage.valid || schedule.io.pending
}
