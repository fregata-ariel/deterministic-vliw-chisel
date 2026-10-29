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

  /** What the decoder made of the issued bundle: the operations the slots actually execute. */
  val decoded = new DecodedBundle(p)

  /** Compute port, memory port. On ISA mvp-2 each can be a pair (one operation, two registers). */
  val rfCommit = Vec(2, Valid(new RegWrite(p.regBits, p.hasVector)))

  /** The word a store writes; for ST.W (W-full) word `a`, and `memCommit1` word `a + 1`. */
  val memCommit = Valid(new MemWrite)
  val memCommit1 = Option.when(p.hasWideMemory)(Valid(new MemWrite))
}

/** Signals of rule violations in this cycle; #28 turns them into assertions. */
class ViolationIO(val p: CoreParams) extends Bundle {
  val encoding = Bool() // L1
  val programShape = Bool() // L2
  val inFlightRead = Bool() // L3
  val overlappingWrites = Bool() // L4
  val writebackPort = Bool() // L5
  val haltCompletion = Bool() // L6
  val addressRange = Bool() // L7

  /** L11, on mvp-1 and mvp-2: MAC or VMAC with ST or ST.W in one bundle reads 4 operand fields (an
    * mvp-0 bundle reads 3).
    */
  val readPorts = Option.when(p.hasMac)(Bool())
}

class CoreIO(val p: CoreParams) extends Bundle {
  val host = new HostIO(p)
  val busy = Output(Bool())

  /** High for exactly one cycle, the cycle after HALT (h+1). */
  val done = Output(Bool())
  val observe = Output(new ObserveIO(p))
  val violation = Output(new ViolationIO(p))
}

/** The core of ISA mvp-0, mvp-1 and mvp-2: one compute and one memory slot per bundle, fixed
  * latencies, no stalls.
  *
  * Start is accepted at E_0 and bundle s executes in cycle s (PC = s; there are no branches). HALT
  * issued in cycle h clears busy and raises done for one cycle at E_(h+1), so the run takes h+1
  * cycles (docs/ISA.md, "開始・終了"). On mvp-1 the compute slot also issues MAC, which reads rs3
  * through read port 2, the port of the ST source (ADR-0018).
  *
  * On mvp-2 (W, docs/ISA.md "mvp-2: W") the register file has two banks ([[BankedRegisterFile]]),
  * and each operand field k reads port k of its bank (a scalar) or of both banks (a pair); port 2
  * reads the rs3 of MAC and VMAC or the source of ST and ST.W. A broadcast rs1 feeds both lanes
  * from one register. Pair writes and pair claims cover both registers; control is unchanged.
  */
class Core(p: CoreParams) extends Module {
  val io = IO(new CoreIO(p))

  private val imem = Mem(p.imemBundles, UInt(64.W))
  private val rf = Module(
    if (p.hasVector) new BankedRegisterFile(p.registerCount) else new RegisterFile(p.registerCount)
  )

  /** Queried registers: rs1, rs2, ST source, compute rd, LD rd, and MAC's rs3 on mvp-1. On mvp-2
    * each query can be a pair.
    */
  private val reservations =
    Module(
      new WriteReservations(p.registerCount, p.maxLatency, if (p.hasMac) 6 else 5, p.hasVector)
    )
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

  // Register reads in the issue cycle: rs1, rs2, and on port 2 the ST source, or MAC's rs3 on
  // mvp-1 (a bundle with both breaks L11); the host reads through port 0. On mvp-2 the same, for
  // VMAC and ST.W too: VMAC has the kind Mac, ST.W the kind St.
  private val isMac =
    Option.when(p.hasMac)(d.compute.valid && d.compute.bits.kind === ComputeKind.Mac)
  rf.io.read(0).addr := Mux(busy, d.rs1, io.host.rfReadAddr)
  rf.io.read(1).addr := d.rs2
  rf.io.read(2).addr := d.rs3.zip(isMac).fold(d.memory.bits.reg) { case (rs3, mac) =>
    Mux(mac, rs3, d.memory.bits.reg)
  }
  io.host.rfReadData := rf.io.read(0).data

  compute.io.op.valid := issue && d.compute.valid
  compute.io.op.bits := d.compute.bits
  compute.io.a := rf.io.read(0).data
  compute.io.b := rf.io.read(1).data
  compute.io.c.foreach(_ := rf.io.read(2).data)
  // mvp-2, lane 1: r(p + 1) of each pair field; a broadcast rs1 gives both lanes r(rs1).
  compute.io.a1.foreach(
    _ := Mux(d.compute.bits.broadcast.get, rf.io.read(0).data, rf.io.read(0).pairData.get)
  )
  compute.io.b1.foreach(_ := rf.io.read(1).pairData.get)
  compute.io.c1.foreach(_ := rf.io.read(2).pairData.get)

  memory.io.op.valid := issue && d.memory.valid
  memory.io.op.bits := d.memory.bits
  memory.io.storeData := rf.io.read(2).data
  memory.io.storeData1.foreach(_ := rf.io.read(2).pairData.get)
  memory.io.hostWrite.valid := !busy && io.host.memWrite.valid
  memory.io.hostWrite.bits := io.host.memWrite.bits
  memory.io.hostReadAddr := io.host.memReadAddr
  io.host.memReadData := memory.io.hostReadData

  // Write-back: the compute port also serves host writes while idle.
  rf.io.write(0).valid := compute.io.commit.valid || (!busy && io.host.rfWrite.valid)
  rf.io.write(0).bits := Mux(
    compute.io.commit.valid,
    compute.io.commit.bits,
    RegWrite.withPairs(io.host.rfWrite.bits, p.hasVector)
  )
  rf.io.write(1) := RegWrite.withPairs(memory.io.commit, p.hasVector)

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
    Seq(d.rs1, d.rs2, d.memory.bits.reg, d.compute.bits.rd, d.memory.bits.reg) ++ d.rs3
  )
  // mvp-2: vector operations and LD.W claim pairs, and the fields that read or write pairs ask
  // for both registers (a broadcast rs1 reads one). LD.W claims with its own latency.
  private def wide: Bool = d.memory.bits.wide.getOrElse(false.B)
  d.compute.bits.vector.foreach { vector =>
    reservations.io.claim(0).bits.pair.get := vector
    reservations.io.claim(1).bits.pair.get := wide
    reservations.io.queryPair.get := VecInit(
      Seq(vector && !d.compute.bits.broadcast.get, vector, wide, vector, wide, vector)
    )
  }
  d.memory.bits.wide.foreach { w =>
    when(w) {
      reservations.io.claim(1).bits.latency := p.latency("LD.W").U
    }
  }

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
  io.observe.decoded := d
  io.observe.rfCommit(0) := compute.io.commit
  io.observe.rfCommit(1) := RegWrite.withPairs(memory.io.commit, p.hasVector)
  io.observe.memCommit := memory.io.storeCommit
  io.observe.memCommit1.zip(memory.io.storeCommit1).foreach { case (o, c) => o := c }

  /** The compute rd and the LD rd share a register (L4 within a bundle). On mvp-2 either can be a
    * pair: the same row of the banks, and a pair or the same bank.
    */
  private def sharedDestination: Bool =
    d.compute.bits.vector.fold(d.compute.bits.rd === d.memory.bits.reg) { vector =>
      val (c, m) = (d.compute.bits.rd, d.memory.bits.reg)
      (c >> 1) === (m >> 1) && (vector || wide || c(0) === m(0))
    }

  // Rule violations (docs/ISA.md, "MVPの合法性規則"); never used to stall.
  private val inFlight = reservations.io.inFlight
  private val readsRs = issue && d.compute.valid
  private val readsSt = issue && d.memory.valid && d.memory.bits.kind === MemoryKind.St
  private val readsRs3 = isMac.map(issue && _)
  io.violation.encoding := inProgram && d.illegal
  io.violation.programShape := busy && !inProgram || (halt && pc =/= length - 1.U)
  io.violation.inFlightRead := (
    Seq(readsRs && (inFlight(0) || inFlight(1)), readsSt && inFlight(2)) ++
      readsRs3.map(_ && inFlight(5))
  ).reduce(_ || _)
  io.violation.overlappingWrites := (compute.io.op.valid && inFlight(3)) || (isLoad && inFlight(
    4
  )) ||
    (compute.io.op.valid && isLoad && sharedDestination)
  io.violation.writebackPort := compute.io.conflict || memory.io.conflict
  io.violation.haltCompletion := halt && (reservations.io.anyInFlight || compute.io.pending || memory.io.pending)
  io.violation.addressRange := issue && memory.io.outOfRange
  // MAC reads 3 registers and ST 1, with 3 read ports (read_ports 4 is outside CoreParams). On
  // mvp-2 this is (MAC or VMAC) and (ST or ST.W): 4 operand fields (L11 in form F).
  io.violation.readPorts.zip(readsRs3).foreach { case (l11, mac) => l11 := mac && readsSt }

  // Assertions (simulation): a legal program never fires them; nothing stalls to hide a violation.
  private val v = io.violation
  private val rules = Seq(
    v.encoding -> "L1 (encoding)",
    v.programShape -> "L2 (program shape)",
    v.inFlightRead -> "L3 (read of a register being written)",
    v.overlappingWrites -> "L4 (overlapping writes to a register)",
    v.writebackPort -> "L5 (write-back port conflict)",
    v.haltCompletion -> "L6 (operation pending at HALT)",
    v.addressRange -> "L7 (scratchpad address out of range)"
  ) ++ v.readPorts.map(_ -> "L11 (more register reads than read ports)")
  rules.foreach { case (violated, rule) =>
    assert(!violated, cf"$rule at pc $pc")
  }
  assert(
    !(busy && (io.host.imemWrite.valid || io.host.rfWrite.valid || io.host.memWrite.valid)),
    cf"HOST (the host changed state while the core is busy) at pc $pc"
  )
}
