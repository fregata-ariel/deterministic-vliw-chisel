package vliw.hw

import chisel3._
import chisel3.util._

/** A register write: destination and value. */
class RegWrite(val regBits: Int) extends Bundle {
  val reg = UInt(regBits.W)
  val data = UInt(32.W)
}

/** Combinational read port. */
class RfReadPort(val regBits: Int) extends Bundle {
  val addr = Input(UInt(regBits.W))
  val data = Output(UInt(32.W))
}

class RegisterFileIO(val count: Int) extends Bundle {
  val regBits: Int = log2Ceil(count).max(1)

  /** Read ports: compute rs1, compute rs2, store source or MAC's rs3 on mvp-1 (docs/ISA.md). */
  val read = Vec(3, new RfReadPort(regBits))

  /** Write ports: index 0 is the compute port, index 1 the memory port. */
  val write = Vec(2, Flipped(Valid(new RegWrite(regBits))))
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
class RegisterFile(count: Int) extends Module {
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

/** Claim of a destination register by an operation issued this cycle. */
class WriteClaim(val regBits: Int, val latencyBits: Int) extends Bundle {
  val reg = UInt(regBits.W)
  val latency = UInt(latencyBits.W)
}

class WriteReservationsIO(val count: Int, val maxLatency: Int, val queries: Int) extends Bundle {
  val regBits: Int = log2Ceil(count).max(1)
  val latencyBits: Int = log2Ceil(maxLatency + 1)

  /** Destinations claimed by the operations issued this cycle (compute, memory). */
  val claim = Vec(2, Flipped(Valid(new WriteClaim(regBits, latencyBits))))

  /** Registers read or written this cycle, and whether each is still being written. */
  val query = Input(Vec(queries, UInt(regBits.W)))
  val inFlight = Output(Vec(queries, Bool()))

  /** Some register write has not committed yet. */
  val anyInFlight = Output(Bool())
}

/** Tracks the write interval [s, s+L) of every register (docs/ISA.md, rules L3, L4, L6).
  *
  * A claim with latency L made in cycle s marks the register as being written in cycles s+1 to
  * s+L-1. In the issue cycle itself the register is not marked, so the issuing bundle reads the old
  * value. The counters are only used for assertions; they never stall execution.
  */
class WriteReservations(count: Int, maxLatency: Int, queries: Int) extends Module {
  val io = IO(new WriteReservationsIO(count, maxLatency, queries))

  /** Cycles until the pending write of each register becomes visible (0 = none pending). */
  private val remaining = RegInit(VecInit(Seq.fill(count)(0.U(io.latencyBits.W))))

  for (r <- 0 until count) {
    val claimed = io.claim.map(c => c.valid && c.bits.reg === r.U && c.bits.latency > 1.U)
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
  io.anyInFlight := remaining.map(_ =/= 0.U).reduce(_ || _)
}
