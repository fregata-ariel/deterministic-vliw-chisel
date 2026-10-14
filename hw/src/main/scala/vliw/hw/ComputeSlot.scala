package vliw.hw

import chisel3._
import chisel3.util._

/** Write request with the latency it was issued with. */
class ScheduledWrite(regBits: Int, val latencyBits: Int) extends RegWrite(regBits) {
  val latency = UInt(latencyBits.W)
}

class WritebackScheduleIO(val regBits: Int, val maxLatency: Int) extends Bundle {
  val latencyBits: Int = log2Ceil(maxLatency + 1)

  /** Write issued this cycle with latency >= 1; it must commit at E_(s+latency). */
  val issue = Flipped(Valid(new ScheduledWrite(regBits, latencyBits)))

  /** Write performed at the end of this cycle (visible from the next cycle). */
  val commit = Valid(new RegWrite(regBits))

  /** The issued write wants a boundary that already has a write on this port (rule L5). */
  val conflict = Output(Bool())

  /** Some earlier write has not been performed yet (used by rule L6 at HALT). */
  val pending = Output(Bool())
}

/** Commit schedule of one register write-back port.
  *
  * `slot(k)` holds the write that an earlier cycle scheduled for the end of cycle now+k. A write
  * issued now with latency L is performed at the end of cycle now+L-1, which is boundary E_(s+L):
  * immediately for L = 1, otherwise from `slot(L-1)` after shifting. The schedule never delays a
  * write: a conflict is only reported, for an assertion.
  */
class WritebackSchedule(regBits: Int, maxLatency: Int) extends Module {
  require(maxLatency >= 1)
  val io = IO(new WritebackScheduleIO(regBits, maxLatency))

  private val empty = 0.U.asTypeOf(Valid(new RegWrite(regBits)))
  private val slot = RegInit(VecInit(Seq.fill(maxLatency)(empty)))

  private val issued = Wire(new RegWrite(regBits))
  issued.reg := io.issue.bits.reg
  issued.data := io.issue.bits.data

  private val now = io.issue.valid && io.issue.bits.latency === 1.U
  io.commit.valid := slot(0).valid || now
  io.commit.bits := Mux(now, issued, slot(0).bits)

  private val later = io.issue.valid && io.issue.bits.latency > 1.U
  private val target = (io.issue.bits.latency - 1.U)(log2Ceil(maxLatency).max(1) - 1, 0)
  io.conflict := (now && slot(0).valid) || (later && slot(target).valid)
  io.pending := slot.map(_.valid).reduce(_ || _)

  for (k <- 0 until maxLatency) {
    val shifted = if (k + 1 < maxLatency) slot(k + 1) else empty
    val insert = later && target === (k + 1).U
    slot(k).valid := shifted.valid || insert
    slot(k).bits := Mux(insert, issued, shifted.bits)
  }
}

object ComputeKind extends ChiselEnum {
  val Add, Sub, Mul = Value
}

/** Decoded compute operation (NOP and HALT are not operations of the slot). */
class ComputeOperation(val regBits: Int) extends Bundle {
  val kind = ComputeKind()
  val rd = UInt(regBits.W)
}

class ComputeSlotIO(val p: CoreParams) extends Bundle {
  val op = Flipped(Valid(new ComputeOperation(p.regBits)))

  /** Source values read from the register file in the issue cycle. */
  val a = Input(UInt(32.W))
  val b = Input(UInt(32.W))

  /** Latency of the issued operation, for the write reservations. */
  val latency = Output(UInt(log2Ceil(p.maxLatency + 1).W))

  val commit = Valid(new RegWrite(p.regBits))
  val conflict = Output(Bool())
  val pending = Output(Bool())
}

/** Compute slot: ADD/SUB/MUL modulo 2^32 with the latencies of the machine description, II = 1.
  *
  * The result is computed in the issue cycle and carried by the write-back schedule until E_(s+L).
  * Functionally this equals an L-stage pipeline; synthesis may retime the multiplier into the
  * schedule registers.
  */
class ComputeSlot(p: CoreParams) extends Module {
  val io = IO(new ComputeSlotIO(p))

  private val schedule = Module(new WritebackSchedule(p.regBits, p.maxComputeLatency))
  private val kind = io.op.bits.kind

  private val result = MuxLookup(kind, 0.U(32.W))(
    Seq(
      ComputeKind.Add -> (io.a + io.b),
      ComputeKind.Sub -> (io.a - io.b),
      ComputeKind.Mul -> (io.a * io.b)(31, 0)
    )
  )
  io.latency := MuxLookup(kind, 1.U)(
    Seq(
      ComputeKind.Add -> p.latency("ADD").U,
      ComputeKind.Sub -> p.latency("SUB").U,
      ComputeKind.Mul -> p.latency("MUL").U
    )
  )

  schedule.io.issue.valid := io.op.valid
  schedule.io.issue.bits.reg := io.op.bits.rd
  schedule.io.issue.bits.data := result
  schedule.io.issue.bits.latency := io.latency
  io.commit := schedule.io.commit
  io.conflict := schedule.io.conflict
  io.pending := schedule.io.pending
}
