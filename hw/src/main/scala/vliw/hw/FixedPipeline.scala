package vliw.hw

import chisel3._
import chisel3.util._

import vliw.isa.{MachineDescription, Slot}

/** Elaboration parameters of a fixed pipeline: the machine (the same range as the Core, see
  * [[CoreParams.check]]), the static schedule and the storage policy of its values.
  */
final case class FixedParams(
    machine: MachineDescription,
    plan: FixedPlan,
    policy: StoragePolicy = StoragePolicy.ByLifetime
) {
  require(
    FixedParams.unsupported(machine, plan, policy).isEmpty,
    FixedParams.unsupported(machine, plan, policy).mkString("; ")
  )

  val core: CoreParams = CoreParams(machine)

  /** The boundary E_T at which the last write lands: the cycle in which a legal HALT could issue at
    * the earliest (L6).
    */
  val completion: Int = FixedPlan.completion(machine, plan)

  /** The run takes `cycles` = T + 1 cycles, counted from E_0 like the Core's: busy is high in
    * cycles 0..T and done in cycle T + 1, the cycle after the HALT a VLIW program would issue at T.
    * The same schedule therefore takes the same cycles as on the Core.
    */
  val cycles: Int = completion + 1
  val counterBits: Int = log2Ceil(completion + 1).max(1)

  val storage: Vector[Option[Storage]] = FixedPlan.bind(machine, plan, policy)
  val registers: Int = storage.flatten.map(_.register).maxOption.fold(0)(_ + 1)
  def registersOf(port: Slot): Int =
    storage.flatten.filter(_.port == port).map(_.register).distinct.size

  /** The compute operations the plan uses. The [[ComputeSlot]] holds all three units (and MAC on an
    * mvp-1 machine, which no plan uses); the kind decode never selects the others.
    */
  val units: Seq[String] =
    FixedPlan.ComputeMnemonics.filter(m => plan.ops.exists(_.mnemonic == m))
}

object FixedParams {

  /** Why no fixed pipeline is built for `plan` on `machine`: the machines outside the Core's range
    * (the same reasons as [[CoreParams.unsupported]]), then the plan's violations.
    */
  def unsupported(
      machine: MachineDescription,
      plan: FixedPlan,
      policy: StoragePolicy = StoragePolicy.ByLifetime
  ): Seq[String] =
    CoreParams.unsupported(machine) match {
      case Seq() =>
        FixedPlan.violations(machine, plan) match {
          case Seq() =>
            FixedPlan.storageViolations(machine, plan, FixedPlan.bind(machine, plan, policy))
          case reasons => reasons
        }
      case reasons => reasons
    }

  def check(
      machine: MachineDescription,
      plan: FixedPlan,
      policy: StoragePolicy = StoragePolicy.ByLifetime
  ): Either[Seq[String], FixedParams] =
    unsupported(machine, plan, policy) match {
      case Seq()   => Right(FixedParams(machine, plan, policy))
      case reasons => Left(reasons)
    }
}

/** Host side of a fixed pipeline: start, and the scratchpad while idle (as in the Core). */
class FixedHostIO extends Bundle {

  /** Accepted only while idle; the first operations issue in the cycle after the accepting edge. */
  val start = Input(Bool())
  val memWrite = Flipped(Valid(new MemWrite))
  val memReadAddr = Input(UInt(9.W))

  /** Data of the address presented in the previous cycle. */
  val memReadData = Output(UInt(32.W))
}

/** What happened in this cycle; writes take effect at the next edge. */
class FixedObserveIO(val p: FixedParams) extends Bundle {
  val cycle = UInt(p.counterBits.W)
  val computeIssue = Bool()
  val memoryIssue = Bool()

  /** Values on the two write-back ports, written to a register at the end of this cycle. */
  val computeCommit = Valid(UInt(32.W))
  val memoryCommit = Valid(UInt(32.W))
  val memCommit = Valid(new MemWrite)
}

class FixedPipelineIO(val p: FixedParams) extends Bundle {
  val host = new FixedHostIO
  val busy = Output(Bool())

  /** High for exactly one cycle, cycle T + 1, where E_T is the boundary of the last write. */
  val done = Output(Bool())
  val observe = Output(new FixedObserveIO(p))
}

/** A fixed pipeline (#75): one kernel's static schedule as a circuit without instruction memory,
  * instruction control or register file.
  *
  * The datapath is the Core's: the same [[ComputeSlot]] (ADD/SUB/MUL, one issue per cycle, one
  * commit per boundary) and [[MemorySlot]] (banked 1RW scratchpad, one access per cycle). Control
  * is a cycle counter: every select, enable and address is a constant of the cycle, decoded from
  * the counter. Values live in registers chosen by [[FixedPlan.bind]] and are written at the
  * boundary E_(s+L) of their producer, from the producer's write-back port (a register of both
  * ports, possible only with [[StoragePolicy.Assigned]], takes a 2:1 write multiplexer); operands
  * come from multiplexers over only the registers that the port reads.
  *
  * Start is accepted at E_0 while idle; operation s of the plan issues in cycle `issue(s)`; the
  * last write lands at E_T. Cycle T plays the HALT of the Core: E_(T+1) clears busy and raises done
  * for one cycle, so a run takes T + 1 cycles, as the same schedule takes on the Core.
  */
class FixedPipeline(p: FixedParams) extends Module {
  val io = IO(new FixedPipelineIO(p))

  private val ops = p.plan.ops
  private val compute = Module(new ComputeSlot(p.core))
  private val memory = Module(new MemorySlot(p.core))

  private val busy = RegInit(false.B)
  private val done = RegInit(false.B)
  private val cycle = RegInit(0.U(p.counterBits.W))

  // One decoded enable per cycle that some signal needs.
  private val at = scala.collection.mutable.Map.empty[Int, Bool]
  private def in(cycles: Iterable[Int]): Bool = {
    val cs = cycles.toSeq.distinct.sorted
    if (cs.isEmpty) false.B
    else cs.map(c => at.getOrElseUpdate(c, busy && cycle === c.U)).reduce(_ || _)
  }

  // Value registers (no reset, like the register file).
  private val regs = Seq.fill(p.registers)(Reg(UInt(32.W)))

  /** `regs` read in the cycles of `reads` (cycle, value), one-hot by cycle. */
  private def operand(reads: Seq[(Int, Int)]): UInt =
    if (reads.isEmpty) 0.U(32.W)
    else {
      val byRegister = reads.groupBy { case (_, v) => p.storage(v).get.register }.toSeq.sortBy(_._1)
      Mux1H(byRegister.map { case (r, rs) => in(rs.map(_._1)) -> regs(r) })
    }

  // Compute slot: operands and kind of the operation issued in each cycle.
  private val computes = ops.collect { case c: FixedOp.Compute => c }
  compute.io.op.valid := in(computes.map(_.issue))
  compute.io.op.bits.rd := 0.U
  private def kindIn(m: String) = in(computes.filter(_.mnemonic == m).map(_.issue))
  compute.io.op.bits.kind := Mux(
    kindIn("MUL"),
    ComputeKind.Mul,
    Mux(kindIn("SUB"), ComputeKind.Sub, ComputeKind.Add)
  )
  compute.io.a := operand(computes.map(c => c.issue -> c.a))
  compute.io.b := operand(computes.map(c => c.issue -> c.b))
  // MAC's rs3 on an mvp-1 machine: tied off, plans have no MAC (FixedPlan.ComputeMnemonics).
  compute.io.c.foreach(_ := 0.U)

  // Memory slot: one LD or ST per cycle, at a constant address of the layout.
  private val loads = ops.collect { case l: FixedOp.Load => l }
  private val stores = ops.collect { case s: FixedOp.Store => s }
  private val memoryOps = loads.map(l => l.issue -> l.addr) ++ stores.map(s => s.issue -> s.addr)
  memory.io.op.valid := in(memoryOps.map(_._1))
  memory.io.op.bits.reg := 0.U
  memory.io.op.bits.kind := Mux(in(stores.map(_.issue)), MemoryKind.St, MemoryKind.Ld)
  memory.io.op.bits.addr :=
    (if (memoryOps.isEmpty) 0.U
     else Mux1H(memoryOps.sortBy(_._1).map { case (c, a) => in(Seq(c)) -> a.U(9.W) }))
  memory.io.storeData := operand(stores.map(s => s.issue -> s.value))
  memory.io.hostWrite.valid := !busy && io.host.memWrite.valid
  memory.io.hostWrite.bits := io.host.memWrite.bits
  memory.io.hostReadAddr := io.host.memReadAddr
  io.host.memReadData := memory.io.hostReadData

  // Write-back: each register takes the port of its values at their commit cycles.
  private val commits = ops.indices.filter(p.plan.produces).map { v =>
    (p.storage(v).get, FixedPlan.commitCycle(p.machine, ops(v)))
  }
  regs.indices.foreach { r =>
    val mine = commits.filter(_._1.register == r)
    def commitsOf(port: Slot) = in(mine.filter(_._1.port == port).map(_._2))
    val (fromCompute, fromMemory) = (commitsOf(Slot.Compute), commitsOf(Slot.Memory))
    val ports = mine.map(_._1.port).distinct
    // A register of one port takes that port; one of both ports (an assigned storage) a 2:1 mux.
    val data =
      if (ports == Seq(Slot.Compute)) compute.io.commit.bits.data
      else if (ports == Seq(Slot.Memory)) memory.io.commit.bits.data
      else Mux(fromMemory, memory.io.commit.bits.data, compute.io.commit.bits.data)
    when(fromCompute || fromMemory)(regs(r) := data)
    // The slots' own write-back schedules commit in exactly the decoded cycles.
    assert(
      !fromCompute || compute.io.commit.valid,
      cf"FIXED (register $r without a compute commit) at $cycle"
    )
    assert(
      !fromMemory || memory.io.commit.valid,
      cf"FIXED (register $r without a memory commit) at $cycle"
    )
  }

  // Control.
  done := false.B
  when(!busy && io.host.start) {
    busy := true.B
    cycle := 0.U
  }.elsewhen(busy) {
    cycle := cycle + 1.U
    when(cycle === p.completion.U) {
      busy := false.B
      done := true.B
    }
  }
  io.busy := busy
  io.done := done

  io.observe.cycle := cycle
  io.observe.computeIssue := compute.io.op.valid
  io.observe.memoryIssue := memory.io.op.valid
  io.observe.computeCommit.valid := compute.io.commit.valid
  io.observe.computeCommit.bits := compute.io.commit.bits.data
  io.observe.memoryCommit.valid := memory.io.commit.valid
  io.observe.memoryCommit.bits := memory.io.commit.bits.data
  io.observe.memCommit := memory.io.storeCommit

  assert(!compute.io.conflict && !memory.io.conflict, cf"L5 (write-back port conflict) at $cycle")
  assert(!(busy && memory.io.outOfRange), cf"L7 (scratchpad address out of range) at $cycle")
  assert(
    !(busy && io.host.memWrite.valid),
    cf"HOST (the host changed state while the pipeline is busy) at $cycle"
  )
}
