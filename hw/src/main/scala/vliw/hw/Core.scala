package vliw.hw

import chisel3._
import chisel3.util._

class ImemWrite(val addrBits: Int) extends Bundle {
  val addr = UInt(addrBits.W)
  val data = UInt(64.W)
}

/** Host side of the core. Writes and reads are only allowed while the core is idle. */
class HostIO(val p: CoreParams) extends Bundle {
  val imemAddrBits: Int = log2Ceil(p.imemBundles).max(1)

  /** Accepted only while idle; the first bundle executes in the cycle after the accepting edge. */
  val start = Input(Bool())

  /** Number of bundles of the program, sampled when start is accepted. */
  val length = Input(UInt(p.pcBits.W))

  val imemWrite = Flipped(Valid(new ImemWrite(imemAddrBits)))
  val rfWrite = Flipped(Valid(new RegWrite(p.regBits)))
  val rfReadAddr = Input(UInt(p.regBits.W))
  val rfReadData = Output(UInt(32.W))
  val memWrite = Flipped(Valid(new MemWrite))
  val memReadAddr = Input(UInt(9.W))

  /** Data of the address presented in the previous cycle. */
  val memReadData = Output(UInt(32.W))
}

/** What happened in this cycle, for traces (docs/TRACE.md). Writes take effect at the next edge. */
class ObserveIO(val p: CoreParams) extends Bundle {
  val issue = Bool()
  val pc = UInt(p.pcBits.W)
  val word = UInt(64.W)
  val rfCommit = Vec(2, Valid(new RegWrite(p.regBits))) // compute port, memory port
  val memCommit = Valid(new MemWrite)
}

/** Signals of rule violations in this cycle; #28 turns them into assertions. */
class ViolationIO extends Bundle {
  val encoding = Bool() // L1
  val programShape = Bool() // L2
  val inFlightRead = Bool() // L3
  val overlappingWrites = Bool() // L4
  val writebackPort = Bool() // L5
  val haltCompletion = Bool() // L6
  val addressRange = Bool() // L7
}

class CoreIO(val p: CoreParams) extends Bundle {
  val host = new HostIO(p)
  val busy = Output(Bool())

  /** High for exactly one cycle, the cycle after HALT (h+1). */
  val done = Output(Bool())
  val observe = Output(new ObserveIO(p))
  val violation = Output(new ViolationIO)
}

/** The mvp-0 core: one compute and one memory slot per bundle, fixed latencies, no stalls.
  *
  * Start is accepted at E_0 and bundle s executes in cycle s (PC = s; there are no branches). HALT
  * issued in cycle h clears busy and raises done for one cycle at E_(h+1), so the run takes h+1
  * cycles (docs/ISA.md, "開始・終了").
  */
class Core(p: CoreParams) extends Module {
  val io = IO(new CoreIO(p))

  private val imem = Mem(p.imemBundles, UInt(64.W))
  private val rf = Module(new RegisterFile(p.registerCount))
  private val reservations = Module(new WriteReservations(p.registerCount, p.maxLatency, 5))
  private val decoder = Module(new Decoder(p))
  private val compute = Module(new ComputeSlot(p))
  private val memory = Module(new MemorySlot(p))

  private val busy = RegInit(false.B)
  private val done = RegInit(false.B)
  private val pc = RegInit(0.U(p.pcBits.W))
  private val length = RegInit(0.U(p.pcBits.W))

  // Fetch and decode. A PC beyond the program or the instruction memory issues nothing.
  private val inProgram = busy && pc < length && pc < p.imemBundles.U
  private val word = Mux(inProgram, imem(pc(io.host.imemAddrBits - 1, 0)), 0.U)
  decoder.io.word := word
  private val d = decoder.io.out
  private val issue = inProgram && !d.illegal

  // Register reads in the issue cycle: rs1, rs2, ST source; the host reads through port 0.
  rf.io.read(0).addr := Mux(busy, d.rs1, io.host.rfReadAddr)
  rf.io.read(1).addr := d.rs2
  rf.io.read(2).addr := d.memory.bits.reg
  io.host.rfReadData := rf.io.read(0).data

  compute.io.op.valid := issue && d.compute.valid
  compute.io.op.bits := d.compute.bits
  compute.io.a := rf.io.read(0).data
  compute.io.b := rf.io.read(1).data

  memory.io.op.valid := issue && d.memory.valid
  memory.io.op.bits := d.memory.bits
  memory.io.storeData := rf.io.read(2).data
  memory.io.hostWrite.valid := !busy && io.host.memWrite.valid
  memory.io.hostWrite.bits := io.host.memWrite.bits
  memory.io.hostReadAddr := io.host.memReadAddr
  io.host.memReadData := memory.io.hostReadData

  // Write-back: the compute port also serves host writes while idle.
  rf.io.write(0).valid := compute.io.commit.valid || (!busy && io.host.rfWrite.valid)
  rf.io.write(0).bits := Mux(compute.io.commit.valid, compute.io.commit.bits, io.host.rfWrite.bits)
  rf.io.write(1) := memory.io.commit

  when(!busy && io.host.imemWrite.valid) {
    imem(io.host.imemWrite.bits.addr) := io.host.imemWrite.bits.data
  }

  // Write reservations of the destinations issued this cycle.
  private val isLoad = issue && d.memory.valid && d.memory.bits.kind === MemoryKind.Ld
  reservations.io.claim(0).valid := compute.io.op.valid
  reservations.io.claim(0).bits.reg := d.compute.bits.rd
  reservations.io.claim(0).bits.latency := compute.io.latency
  reservations.io.claim(1).valid := isLoad
  reservations.io.claim(1).bits.reg := d.memory.bits.reg
  reservations.io.claim(1).bits.latency := p.latency("LD").U
  reservations.io.query := VecInit(
    d.rs1,
    d.rs2,
    d.memory.bits.reg,
    d.compute.bits.rd,
    d.memory.bits.reg
  )

  // Control.
  private val halt = issue && d.halt
  done := false.B
  when(!busy && io.host.start) {
    busy := true.B
    pc := 0.U
    length := io.host.length
  }.elsewhen(busy) {
    when(halt) {
      busy := false.B
      done := true.B
    }.otherwise {
      pc := pc + 1.U
    }
  }
  io.busy := busy
  io.done := done

  io.observe.issue := busy
  io.observe.pc := pc
  io.observe.word := word
  io.observe.rfCommit(0) := compute.io.commit
  io.observe.rfCommit(1) := memory.io.commit
  io.observe.memCommit := memory.io.storeCommit

  // Rule violations (docs/ISA.md, "MVPの合法性規則"); never used to stall.
  private val inFlight = reservations.io.inFlight
  private val readsRs = issue && d.compute.valid
  private val readsSt = issue && d.memory.valid && d.memory.bits.kind === MemoryKind.St
  io.violation.encoding := inProgram && d.illegal
  io.violation.programShape := busy && !inProgram || (halt && pc =/= length - 1.U)
  io.violation.inFlightRead := (readsRs && (inFlight(0) || inFlight(1))) || (readsSt && inFlight(2))
  io.violation.overlappingWrites := (compute.io.op.valid && inFlight(3)) || (isLoad && inFlight(
    4
  )) ||
    (compute.io.op.valid && isLoad && d.compute.bits.rd === d.memory.bits.reg)
  io.violation.writebackPort := compute.io.conflict || memory.io.conflict
  io.violation.haltCompletion := halt && (reservations.io.anyInFlight || compute.io.pending || memory.io.pending)
  io.violation.addressRange := issue && memory.io.outOfRange

  // Assertions (simulation): a legal program never fires them; nothing stalls to hide a violation.
  private val v = io.violation
  Seq(
    v.encoding -> "L1 (encoding)",
    v.programShape -> "L2 (program shape)",
    v.inFlightRead -> "L3 (read of a register being written)",
    v.overlappingWrites -> "L4 (overlapping writes to a register)",
    v.writebackPort -> "L5 (write-back port conflict)",
    v.haltCompletion -> "L6 (operation pending at HALT)",
    v.addressRange -> "L7 (scratchpad address out of range)"
  ).foreach { case (violated, rule) =>
    assert(!violated, cf"$rule at pc $pc")
  }
  assert(
    !(busy && (io.host.imemWrite.valid || io.host.rfWrite.valid || io.host.memWrite.valid)),
    cf"HOST (the host changed state while the core is busy) at pc $pc"
  )
}
