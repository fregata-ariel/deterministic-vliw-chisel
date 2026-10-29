package vliw.hw

import chisel3._
import chisel3.util._

/** Write request with the latency it was issued with. */
class ScheduledWrite(regBits: Int, val latencyBits: Int, pairs: Boolean = false)
    extends RegWrite(regBits, pairs) {
  val latency = UInt(latencyBits.W)
}

class WritebackScheduleIO(val regBits: Int, val maxLatency: Int, val pairs: Boolean = false)
    extends Bundle {
  val latencyBits: Int = log2Ceil(maxLatency + 1)

  /** Write issued this cycle with latency >= 1; it must commit at E_(s+latency). */
  val issue = Flipped(Valid(new ScheduledWrite(regBits, latencyBits, pairs)))

  /** Write performed at the end of this cycle (visible from the next cycle). */
  val commit = Valid(new RegWrite(regBits, pairs))

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
  *
  * With `pairs` (ISA mvp-2) an entry can carry a pair: one operation, one use of the port, two
  * registers ([[RegWrite]]). L5 counts entries, so it counts operations (docs/ISA.md, "mvp-2: W").
  */
class WritebackSchedule(regBits: Int, maxLatency: Int, pairs: Boolean = false) extends Module {
  require(maxLatency >= 1)
  val io = IO(new WritebackScheduleIO(regBits, maxLatency, pairs))

  private val empty = 0.U.asTypeOf(Valid(new RegWrite(regBits, pairs)))
  private val slot = RegInit(VecInit(Seq.fill(maxLatency)(empty)))

  private val issued = Wire(new RegWrite(regBits, pairs))
  issued.reg := io.issue.bits.reg
  issued.data := io.issue.bits.data
  issued.pair.zip(io.issue.bits.pair).foreach { case (o, i) => o := i }
  issued.data1.zip(io.issue.bits.data1).foreach { case (o, i) => o := i }

  private val now = io.issue.valid && io.issue.bits.latency === 1.U
  io.commit.valid := slot(0).valid || now
  io.commit.bits := Mux(now, issued, slot(0).bits)

  private val later = io.issue.valid && io.issue.bits.latency > 1.U
  private val target = io.issue.bits.latency - 1.U
  private val laterTaken =
    if (maxLatency == 1) false.B else slot(target(log2Ceil(maxLatency) - 1, 0)).valid
  io.conflict := (now && slot(0).valid) || (later && laterTaken)
  io.pending := slot.map(_.valid).reduce(_ || _)

  for (k <- 0 until maxLatency) {
    val shifted = if (k + 1 < maxLatency) slot(k + 1) else empty
    val insert = later && target === (k + 1).U
    slot(k).valid := shifted.valid || insert
    slot(k).bits := Mux(insert, issued, shifted.bits)
  }
}

/** The compute operations. Mac exists only on ISA mvp-1 and mvp-2 machines; on mvp-0 nothing
  * decodes it. The vector operations of mvp-2 are these kinds with `vector` set.
  */
object ComputeKind extends ChiselEnum {
  val Add, Sub, Mul, Mac = Value
}

/** Decoded compute operation (NOP and HALT are not operations of the slot).
  *
  * With `w` (ISA mvp-2): `vector` marks VADD, VSUB, VMUL and VMAC (the kinds Add, Sub, Mul and Mac
  * on register pairs, `rd` even), and `broadcast` the B bit of VMUL and VMAC.
  */
class ComputeOperation(val regBits: Int, val w: Boolean = false) extends Bundle {
  val kind = ComputeKind()
  val rd = UInt(regBits.W)
  val vector = Option.when(w)(Bool())
  val broadcast = Option.when(w)(Bool())
}

class ComputeSlotIO(val p: CoreParams) extends Bundle {
  val op = Flipped(Valid(new ComputeOperation(p.regBits, p.hasVector)))

  /** Source values read from the register file in the issue cycle. */
  val a = Input(UInt(32.W))
  val b = Input(UInt(32.W))

  /** The rs3 of MAC and VMAC, read in the issue cycle (ISA mvp-1 and mvp-2). */
  val c = Option.when(p.hasMac)(Input(UInt(32.W)))

  /** Lane 1 of a vector operation (ISA mvp-2): r(rs1 + 1) or the broadcast r(rs1), r(rs2 + 1),
    * r(rs3 + 1). `a`, `b` and `c` are lane 0.
    */
  val a1 = Option.when(p.hasVector)(Input(UInt(32.W)))
  val b1 = Option.when(p.hasVector)(Input(UInt(32.W)))
  val c1 = Option.when(p.hasVector)(Input(UInt(32.W)))

  /** Latency of the issued operation, for the write reservations. */
  val latency = Output(UInt(log2Ceil(p.maxLatency + 1).W))

  val commit = Valid(new RegWrite(p.regBits, p.hasVector))
  val conflict = Output(Bool())
  val pending = Output(Bool())
}

class VectorLaneIO extends Bundle {
  val kind = Input(ComputeKind())
  val a = Input(UInt(32.W))
  val b = Input(UInt(32.W))
  val c = Input(UInt(32.W))
  val result = Output(UInt(32.W))
}

/** Lane 1 of the vector operations (ISA mvp-2): Add, Sub, Mul and Mac on lane 1's operands, with
  * the second 32x32 multiplier. A module of its own, so that #57 can report the area of the second
  * lane apart from the rest (ADR-0022).
  */
class VectorLane extends Module {
  val io = IO(new VectorLaneIO)
  private val product = (io.a * io.b)(31, 0)
  io.result := MuxLookup(io.kind, 0.U(32.W))(
    Seq(
      ComputeKind.Add -> (io.a + io.b),
      ComputeKind.Sub -> (io.a - io.b),
      ComputeKind.Mul -> product,
      ComputeKind.Mac -> (product + io.c)
    )
  )
}

/** Compute slot: ADD/SUB/MUL, MAC on ISA mvp-1, and VADD/VSUB/VMUL/VMAC on ISA mvp-2, modulo 2^32
  * with the latencies of the machine description, II = 1.
  *
  * The result is computed in the issue cycle and carried by the write-back schedule until E_(s+L).
  * Functionally this equals an L-stage pipeline; synthesis may retime the multiplier into the
  * schedule registers. MAC computes low32(a * b + c) from the values read in the issue cycle and
  * commits through the same schedule, so it meets ADD/SUB/MUL on the write-back port as rule L5
  * says. Its product is the same expression as MUL's; the slot issues one operation per cycle, so
  * sharing the multiplier is invisible at II 1 (docs/ISA.md, "mvp-1: MAC").
  *
  * A vector operation (mvp-2) computes lane 0 on the same datapath (and multiplier) as its scalar
  * kind, and lane 1 in [[VectorLane]]; it takes its own latency from the machine description and
  * enters the schedule as one pair entry, so L5 counts it once (docs/ISA.md, "mvp-2: W").
  */
class ComputeSlot(p: CoreParams) extends Module {
  val io = IO(new ComputeSlotIO(p))

  private val schedule =
    Module(new WritebackSchedule(p.regBits, p.maxComputeLatency, p.hasVector))
  private val kind = io.op.bits.kind

  private val result = MuxLookup(kind, 0.U(32.W))(
    Seq(
      ComputeKind.Add -> (io.a + io.b),
      ComputeKind.Sub -> (io.a - io.b),
      ComputeKind.Mul -> (io.a * io.b)(31, 0)
    ) ++ io.c.map(c => ComputeKind.Mac -> ((io.a * io.b)(31, 0) + c))
  )
  io.latency := MuxLookup(kind, 1.U)(
    Seq(
      ComputeKind.Add -> p.latency("ADD").U,
      ComputeKind.Sub -> p.latency("SUB").U,
      ComputeKind.Mul -> p.latency("MUL").U
    ) ++ p.macLatency.map(l => ComputeKind.Mac -> l.U)
  )

  // ISA mvp-2: a vector operation takes its own latency, and lane 1 its own datapath.
  io.op.bits.vector.foreach { vector =>
    when(vector) {
      io.latency := MuxLookup(kind, 1.U)(
        Seq(ComputeKind.Add, ComputeKind.Sub, ComputeKind.Mul, ComputeKind.Mac)
          .zip(p.vectorMnemonics)
          .map { case (k, m) => k -> p.latency(m).U }
      )
    }
  }
  private val lane1 = Option.when(p.hasVector)(Module(new VectorLane))
  lane1.foreach { l =>
    l.io.kind := kind
    l.io.a := io.a1.get
    l.io.b := io.b1.get
    l.io.c := io.c1.get
  }

  schedule.io.issue.valid := io.op.valid
  schedule.io.issue.bits.reg := io.op.bits.rd
  schedule.io.issue.bits.data := result
  schedule.io.issue.bits.latency := io.latency
  schedule.io.issue.bits.pair.foreach(_ := io.op.bits.vector.get)
  schedule.io.issue.bits.data1.foreach(_ := lane1.get.io.result)
  io.commit := schedule.io.commit
  io.conflict := schedule.io.conflict
  io.pending := schedule.io.pending
}
