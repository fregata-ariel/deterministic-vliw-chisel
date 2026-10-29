package vliw.hw

import chisel3._
import chisel3.util._

/** A register write: destination and value.
  *
  * With `pairs` (ISA mvp-2) it can also be a pair write, one operation's write of two registers
  * through one port: `data` to r(reg) and `data1` to r(reg + 1), where `reg` is even.
  */
class RegWrite(val regBits: Int, val pairs: Boolean = false) extends Bundle {
  val reg = UInt(regBits.W)
  val data = UInt(32.W)
  val pair = Option.when(pairs)(Bool())
  val data1 = Option.when(pairs)(UInt(32.W))
}

object RegWrite {

  /** `w` as a write of a register file with pairs: `w` itself if it can carry a pair or `pairs` is
    * false, otherwise a scalar write (`pair` false).
    */
  def withPairs(w: RegWrite, pairs: Boolean): RegWrite =
    if (!pairs || w.pairs) w
    else {
      val out = Wire(new RegWrite(w.regBits, pairs = true))
      out.reg := w.reg
      out.data := w.data
      out.pair.get := false.B
      out.data1.get := 0.U
      out
    }

  /** [[withPairs]] for a valid write. */
  def withPairs(w: Valid[RegWrite], pairs: Boolean): Valid[RegWrite] =
    if (!pairs || w.bits.pairs) w
    else {
      val out = Wire(Valid(new RegWrite(w.bits.regBits, pairs = true)))
      out.valid := w.valid
      out.bits := withPairs(w.bits, pairs)
      out
    }
}

/** Combinational read port. With `pairs` (the banked register file of ISA mvp-2) it also returns
  * `pairData`, r(addr | 1): the odd register of the pair that holds r(addr).
  */
class RfReadPort(val regBits: Int, val pairs: Boolean = false) extends Bundle {
  val addr = Input(UInt(regBits.W))
  val data = Output(UInt(32.W))
  val pairData = Option.when(pairs)(Output(UInt(32.W)))
}

class RegisterFileIO(val count: Int, val pairs: Boolean = false) extends Bundle {
  val regBits: Int = log2Ceil(count).max(1)

  /** Read ports: compute rs1, compute rs2, store source or the rs3 of MAC (mvp-1) and VMAC (mvp-2)
    * (docs/ISA.md).
    */
  val read = Vec(3, new RfReadPort(regBits, pairs))

  /** Write ports: index 0 is the compute port, index 1 the memory port. */
  val write = Vec(2, Flipped(Valid(new RegWrite(regBits, pairs))))
}

/** The register file of a [[Core]]: [[RegisterFile]] for ISA mvp-0 and mvp-1,
  * [[BankedRegisterFile]] for mvp-2.
  */
abstract class RegisterFileModule extends Module {
  val io: RegisterFileIO
}

/** Register file with 3 combinational read ports and 2 write ports.
  *
  * A write requested in cycle s happens at the clock edge E_(s+1) and is visible to reads from
  * cycle s+1 on; reads in cycle s still see the old value. The register file has no reset: the host
  * sets every input before start, and legal programs never read other registers (rule L8). Two
  * writes to the same register in one cycle are illegal (rule L4); the memory port is connected
  * last and would win.
  *
  * Built from flip-flops as an initial model; 3R/2W is not a standard SRAM macro (docs/DESIGN.md).
  */
class RegisterFile(count: Int) extends RegisterFileModule {
  require(count >= 1 && count <= 16, s"register count $count is outside 1..16")
  val io = IO(new RegisterFileIO(count))

  private val regs = Reg(Vec(count, UInt(32.W)))

  /** With a single register the index has no bits; a dynamic index would be too wide. */
  private def reg(i: UInt) = if (count == 1) regs(0) else regs(i)

  io.read.foreach(p => p.data := reg(p.addr))
  io.write.foreach { w =>
    when(w.valid) {
      reg(w.bits.reg) := w.bits.data
    }
  }
}

/** The register file of ISA mvp-2 (docs/ISA.md, "mvp-2: W"): two banks, bank 0 with the even and
  * bank 1 with the odd registers (r(2i + b) in row i of bank b), each with 3 read ports and 2 write
  * ports (3R/2W per bank, 6R/4W in all; ADR-0022 records it for #57).
  *
  * Read port k of both banks reads the row `addr >> 1` of field k: `data` is r(addr), from the port
  * of addr's own bank (a scalar field, or lane 0 of a pair), and `pairData` is r(addr | 1), the
  * same port of bank 1 (lane 1 of a pair). This is the datapath of form S of docs/ISA.md: a scalar
  * field uses its own bank's port, a pair field the same port of both banks; the core reads a
  * broadcast rs1 through `data` only. L11 in form F (fields) makes it sufficient.
  *
  * Write port k writes bank `reg(0)` for a scalar, and row `reg >> 1` of both banks for a pair.
  * Timing, the missing reset and the port order are those of [[RegisterFile]].
  */
class BankedRegisterFile(count: Int) extends RegisterFileModule {
  require(
    count >= 2 && count <= 16 && count % 2 == 0,
    s"register count $count is not even in 2..16"
  )
  val io = IO(new RegisterFileIO(count, pairs = true))

  private val rows = count / 2
  private val bank0 = Reg(Vec(rows, UInt(32.W)))
  private val bank1 = Reg(Vec(rows, UInt(32.W)))

  /** The row of a register; with one row per bank the index has no bits. */
  private def row(bank: Vec[UInt], reg: UInt) = if (rows == 1) bank(0) else bank(reg >> 1)

  io.read.foreach { p =>
    p.data := Mux(p.addr(0), row(bank1, p.addr), row(bank0, p.addr))
    p.pairData.get := row(bank1, p.addr)
  }
  io.write.foreach { w =>
    when(w.valid) {
      when(w.bits.pair.get) {
        row(bank0, w.bits.reg) := w.bits.data
        row(bank1, w.bits.reg) := w.bits.data1.get
      }.elsewhen(w.bits.reg(0)) {
        row(bank1, w.bits.reg) := w.bits.data
      }.otherwise {
        row(bank0, w.bits.reg) := w.bits.data
      }
    }
  }
}

/** Claim of a destination register by an operation issued this cycle. With `pairs` (mvp-2) the
  * claim can be a pair: `reg` (even) and `reg + 1`.
  */
class WriteClaim(val regBits: Int, val latencyBits: Int, val pairs: Boolean = false)
    extends Bundle {
  val reg = UInt(regBits.W)
  val latency = UInt(latencyBits.W)
  val pair = Option.when(pairs)(Bool())
}

class WriteReservationsIO(
    val count: Int,
    val maxLatency: Int,
    val queries: Int,
    val pairs: Boolean = false
) extends Bundle {
  val regBits: Int = log2Ceil(count).max(1)
  val latencyBits: Int = log2Ceil(maxLatency + 1)

  /** Destinations claimed by the operations issued this cycle (compute, memory). */
  val claim = Vec(2, Flipped(Valid(new WriteClaim(regBits, latencyBits, pairs))))

  /** Registers read or written this cycle, and whether each is still being written. */
  val query = Input(Vec(queries, UInt(regBits.W)))

  /** mvp-2: query k is the pair `query(k)` (even) and `query(k) + 1`; in flight if either is. */
  val queryPair = Option.when(pairs)(Input(Vec(queries, Bool())))
  val inFlight = Output(Vec(queries, Bool()))

  /** Some register write has not committed yet. */
  val anyInFlight = Output(Bool())
}

/** Tracks the write interval [s, s+L) of every register (docs/ISA.md, rules L3, L4, L6).
  *
  * A claim with latency L made in cycle s marks the register as being written in cycles s+1 to
  * s+L-1. In the issue cycle itself the register is not marked, so the issuing bundle reads the old
  * value. The counters are only used for assertions; they never stall execution. With `pairs`
  * (mvp-2), a pair claim marks both registers and a pair query asks for both: the hazards stay per
  * register (L3, L4).
  */
class WriteReservations(count: Int, maxLatency: Int, queries: Int, pairs: Boolean = false)
    extends Module {
  val io = IO(new WriteReservationsIO(count, maxLatency, queries, pairs))

  /** Cycles until the pending write of each register becomes visible (0 = none pending). */
  private val remaining = RegInit(VecInit(Seq.fill(count)(0.U(io.latencyBits.W))))

  for (r <- 0 until count) {
    val claimed = io.claim.map(c =>
      c.valid && c.bits.pair.fold(c.bits.reg === r.U)(pair =>
        c.bits.reg === r.U || (pair && c.bits.reg === (r & ~1).U)
      ) && c.bits.latency > 1.U
    )
    val claimLatency = Mux1H(claimed, io.claim.map(_.bits.latency))
    remaining(r) := Mux(
      claimed.reduce(_ || _),
      claimLatency - 1.U,
      Mux(remaining(r) =/= 0.U, remaining(r) - 1.U, 0.U)
    )
  }

  io.query.zip(io.inFlight).foreach { case (q, f) =>
    f := (if (count == 1) remaining(0) else remaining(q)) =/= 0.U
  }
  io.queryPair.foreach(_.zip(io.query).zip(io.inFlight).foreach { case ((pair, q), f) =>
    f := remaining(q) =/= 0.U || (pair && remaining(q | 1.U) =/= 0.U)
  })
  io.anyInFlight := remaining.map(_ =/= 0.U).reduce(_ || _)
}
