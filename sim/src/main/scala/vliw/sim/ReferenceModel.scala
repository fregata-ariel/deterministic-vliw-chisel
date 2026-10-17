package vliw.sim

import scala.collection.mutable

import vliw.isa._

/** Architectural update that becomes visible at the start of `Trace.Cycle.cycle`. */
sealed trait Commit extends Product with Serializable
object Commit {
  final case class Register(reg: Int, value: Long, port: Slot) extends Commit
  final case class Memory(addr: Int, value: Long) extends Commit
}

/** A legality rule violated during execution; `cycle` is None for whole-program checks. */
final case class Violation(rule: Rule, cycle: Option[Int], message: String) {
  override def toString: String =
    s"${rule.id} (${rule.title})${cycle.fold("")(c => s" at cycle $c")}: $message"
}

/** Completed execution: the trace, `cycles = h + 1`, and the final state (None = undefined). */
final case class Execution(
    trace: Trace,
    cycles: Int,
    registers: Vector[Option[Long]],
    memory: Vector[Option[Long]]
)

/** Cycle-accurate reference model of mvp-0 and mvp-1 (docs/ISA.md, "実行意味論" and "mvp-1: MAC").
  *
  * State is the architectural RF and scratchpad, a queue of issued operations carrying their
  * computed values and commit boundaries, and for each operation the cycle from which its unit
  * accepts a new issue. Each cycle reads sources from the visible state, enqueues results for
  * E_(s+L), and then applies everything due at E_(s+1). Runtime checks stand in for RTL assertions:
  * the first violated legality rule stops the run. The model does not use the verifier (ADR-0002).
  */
object ReferenceModel {
  private val Mask = 0xffffffffL

  private final case class InFlight(
      issue: Int,
      commitAt: Int,
      target: Either[Int, Int], // Left(register) or Right(address)
      port: Option[Slot],
      value: Long
  )

  /** Registers that bundle `b` reads in its issue cycle, one entry per operand field: ADD, SUB and
    * MUL read 2, MAC 3, ST 1. Rule L11 limits their number to the read ports.
    */
  private[sim] def sourceRegisters(b: Bundle): Vector[Int] = {
    val compute = b.compute match {
      case ComputeOp.Add(_, x, y)         => Vector(x, y)
      case ComputeOp.Sub(_, x, y)         => Vector(x, y)
      case ComputeOp.Mul(_, x, y)         => Vector(x, y)
      case ComputeOp.Mac(_, x, y, z)      => Vector(x, y, z)
      case ComputeOp.Nop | ComputeOp.Halt => Vector.empty
    }
    val memory = b.memory match {
      case MemoryOp.St(_, r)                => Vector(r)
      case MemoryOp.Ld(_, _) | MemoryOp.Nop => Vector.empty
    }
    compute ++ memory
  }

  def run(
      machine: MachineDescription,
      words: Vector[Long],
      inputs: Inputs
  ): Either[Violation, Execution] = {
    val codec = new BundleCodec(machine)
    for {
      bundles <- codec.decodeAll(words).left.map(e => Violation(Rule.Encoding, None, e))
      _ <- checkShape(machine, bundles)
      _ <- checkInputs(machine, inputs)
      result <- execute(machine, bundles, inputs)
    } yield result
  }

  private def checkShape(
      machine: MachineDescription,
      bundles: Vector[Bundle]
  ): Either[Violation, Unit] =
    if (bundles.isEmpty) Left(Violation(Rule.ProgramShape, None, "the program is empty"))
    else if (bundles.size > machine.instructionMemoryBundles)
      Left(
        Violation(
          Rule.ProgramShape,
          None,
          s"${bundles.size} bundles exceed the instruction memory (${machine.instructionMemoryBundles})"
        )
      )
    else Right(())

  private def checkInputs(machine: MachineDescription, inputs: Inputs): Either[Violation, Unit] =
    inputs.registers.keys.find(_ >= machine.registerCount) match {
      case Some(r) =>
        Left(Violation(Rule.UndefinedRead, None, s"input r$r is outside the register file"))
      case None =>
        inputs.memory.keys.find(_ >= machine.scratchpadWords) match {
          case Some(a) =>
            Left(Violation(Rule.AddressRange, None, s"input address $a is outside the scratchpad"))
          case None => Right(())
        }
    }

  /** Mutable machine state during one run. */
  private final class State(machine: MachineDescription, inputs: Inputs) {
    val rf: Array[Option[Long]] = Array.fill(machine.registerCount)(None)
    val mem: Array[Option[Long]] = Array.fill(machine.scratchpadWords)(None)
    val inFlight: mutable.ArrayBuffer[InFlight] = mutable.ArrayBuffer.empty

    /** First cycle in which each operation's unit accepts a new issue (L9). */
    val unitFreeAt: mutable.Map[String, Int] = mutable.Map.empty.withDefaultValue(0)
    inputs.registers.foreach { case (r, v) => rf(r) = Some(v & Mask) }
    inputs.memory.foreach { case (a, v) => mem(a) = Some(v & Mask) }
  }

  private def execute(
      machine: MachineDescription,
      bundles: Vector[Bundle],
      inputs: Inputs
  ): Either[Violation, Execution] = {
    val state = new State(machine, inputs)
    val cycles = Vector.newBuilder[Trace.Cycle]

    @annotation.tailrec
    def loop(s: Int, commitsAtStart: Vector[Commit]): Either[Violation, Int] =
      if (s >= bundles.size)
        Left(
          Violation(Rule.ProgramShape, Some(s), "execution ran past the last bundle without HALT")
        )
      else
        issue(machine, state, bundles, s) match {
          case Left(v)       => Left(v)
          case Right(halted) =>
            cycles += Trace.Cycle(
              s,
              Some(s),
              Some(bundles(s)),
              commitsAtStart,
              busy = true,
              done = false
            )
            val next = commit(state, s + 1)
            if (halted) {
              cycles += Trace.Cycle(s + 1, None, None, next, busy = false, done = true)
              Right(s + 1)
            } else loop(s + 1, next)
        }

    loop(0, Vector.empty).map { n =>
      Execution(Trace(machine, cycles.result()), n, state.rf.toVector, state.mem.toVector)
    }
  }

  /** Executes bundle `s` up to its reservations; returns whether it was HALT. */
  private def issue(
      machine: MachineDescription,
      state: State,
      bundles: Vector[Bundle],
      s: Int
  ): Either[Violation, Boolean] = {
    def fail(rule: Rule, message: String) = Left(Violation(rule, Some(s), message))
    def latency(mnemonic: String) = machine.operation(mnemonic).latency

    def readReg(r: Int): Either[Violation, Long] =
      state.inFlight.find(op => op.target == Left(r) && op.issue < s) match {
        case Some(op) =>
          fail(
            Rule.InFlightRead,
            s"r$r is read while being written (issued at cycle ${op.issue}, visible from ${op.commitAt})"
          )
        case None =>
          state
            .rf(r)
            .toRight(Violation(Rule.UndefinedRead, Some(s), s"r$r is read before it has a value"))
      }

    def address(a: Int): Either[Violation, Int] =
      if (a < machine.scratchpadWords) Right(a)
      else
        fail(
          Rule.AddressRange,
          s"address $a is outside the scratchpad (0..${machine.scratchpadWords - 1})"
        )

    def unit(mnemonic: String): Either[Violation, Unit] = {
      val free = state.unitFreeAt(mnemonic)
      if (s < free)
        fail(
          Rule.IssueInterval,
          s"$mnemonic is issued before its unit accepts a new operation (from cycle $free)"
        )
      else Right(())
    }

    def binary(d: Int, a: Int, b: Int, mnemonic: String)(f: (Long, Long) => Long) =
      for { _ <- unit(mnemonic); x <- readReg(a); y <- readReg(b) } yield Some(
        InFlight(s, s + latency(mnemonic), Left(d), Some(Slot.Compute), f(x, y) & Mask)
      )

    val bundle = bundles(s)

    // The bundle reads no more registers than there are read ports (L11).
    val reads = sourceRegisters(bundle).size
    val ports: Either[Violation, Unit] =
      if (reads <= machine.readPorts) Right(())
      else fail(Rule.ReadPorts, s"$reads register reads exceed the ${machine.readPorts} read ports")

    // 1. Read sources and compute results from the state visible in cycle s.
    val computeOp: Either[Violation, Option[InFlight]] = bundle.compute match {
      case ComputeOp.Nop | ComputeOp.Halt => Right(None)
      case ComputeOp.Add(d, a, b)         => binary(d, a, b, "ADD")(_ + _)
      case ComputeOp.Sub(d, a, b)         => binary(d, a, b, "SUB")(_ - _)
      case ComputeOp.Mul(d, a, b)         => binary(d, a, b, "MUL")(_ * _)
      case ComputeOp.Mac(d, a, b, c)      =>
        for { _ <- unit("MAC"); x <- readReg(a); y <- readReg(b); z <- readReg(c) } yield Some(
          InFlight(s, s + latency("MAC"), Left(d), Some(Slot.Compute), (x * y + z) & Mask)
        )
    }
    val memoryOp: Either[Violation, Option[InFlight]] = bundle.memory match {
      case MemoryOp.Nop      => Right(None)
      case MemoryOp.Ld(d, a) =>
        for {
          _ <- unit("LD")
          addr <- address(a)
          _ <- state.inFlight.find(op => op.target == Right(addr) && op.issue < s) match {
            case Some(op) =>
              fail(
                Rule.InFlightStoreRead,
                s"[$addr] is loaded while being stored (issued at cycle ${op.issue}, visible from ${op.commitAt})"
              )
            case None => Right(())
          }
          v <- state
            .mem(addr)
            .toRight(
              Violation(Rule.UndefinedRead, Some(s), s"[$addr] is loaded before it has a value")
            )
        } yield Some(InFlight(s, s + latency("LD"), Left(d), Some(Slot.Memory), v))
      case MemoryOp.St(a, r) =>
        for { _ <- unit("ST"); addr <- address(a); v <- readReg(r) } yield Some(
          InFlight(s, s + latency("ST"), Right(addr), None, v)
        )
    }

    // 2. Reserve: no overlapping writes to a register (L4), one commit per port per boundary (L5).
    def reserve(ops: Vector[InFlight]): Either[Violation, Unit] =
      ops.zipWithIndex.foldLeft[Either[Violation, Unit]](Right(())) { case (acc, (op, i)) =>
        acc.flatMap { _ =>
          val others = state.inFlight.toVector ++ ops.patch(i, Nil, 1)
          val overlap = op.target match {
            case Left(r)  => others.find(_.target == Left(r)).map(o => (r, o))
            case Right(_) => None
          }
          val portClash = op.port.filter(p =>
            state.inFlight.exists(o => o.port.contains(p) && o.commitAt == op.commitAt)
          )
          (overlap, portClash) match {
            case (Some((r, o)), _) =>
              fail(
                Rule.OverlappingWrites,
                s"r$r already has a write in flight (issued at cycle ${o.issue})"
              )
            case (_, Some(p)) =>
              fail(
                Rule.WritebackPort,
                s"the ${p.name} write-back port is already used at E_${op.commitAt}"
              )
            case _ => Right(())
          }
        }
      }

    def halt(): Either[Violation, Boolean] =
      if (bundle.compute != ComputeOp.Halt) Right(false)
      else if (s != bundles.size - 1) fail(Rule.ProgramShape, "HALT is not the last bundle")
      else
        state.inFlight.headOption match {
          case Some(op) =>
            fail(
              Rule.HaltCompletion,
              s"an operation issued at cycle ${op.issue} completes at E_${op.commitAt}, after HALT"
            )
          case None => Right(true)
        }

    for {
      _ <- ports
      c <- computeOp
      m <- memoryOp
      ops = c.toVector ++ m.toVector
      _ <- reserve(ops)
      halted <- halt()
    } yield {
      state.inFlight ++= ops
      def occupy(m: String) = state.unitFreeAt(m) = s + machine.operation(m).ii
      bundle.compute match {
        case ComputeOp.Add(_, _, _)         => occupy("ADD")
        case ComputeOp.Sub(_, _, _)         => occupy("SUB")
        case ComputeOp.Mul(_, _, _)         => occupy("MUL")
        case ComputeOp.Mac(_, _, _, _)      => occupy("MAC")
        case ComputeOp.Nop | ComputeOp.Halt => ()
      }
      bundle.memory match {
        case MemoryOp.Ld(_, _) => occupy("LD")
        case MemoryOp.St(_, _) => occupy("ST")
        case MemoryOp.Nop      => ()
      }
      halted
    }
  }

  /** Applies every operation due at boundary `e`; returns the commits in trace order. */
  private def commit(state: State, e: Int): Vector[Commit] = {
    val due = state.inFlight.filter(_.commitAt == e).toVector
    state.inFlight --= due
    due
      .map { op =>
        op.target match {
          case Left(r)  => state.rf(r) = Some(op.value); Commit.Register(r, op.value, op.port.get)
          case Right(a) => state.mem(a) = Some(op.value); Commit.Memory(a, op.value)
        }
      }
      .sortBy {
        case Commit.Register(r, _, p) => (0, Slot.all.indexOf(p), r)
        case Commit.Memory(a, _)      => (1, 0, a)
      }
  }
}
