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

/** Cycle-accurate reference model of ISA mvp-0, mvp-1 and mvp-2 (docs/ISA.md, "実行意味論", "mvp-1: MAC"
  * and "mvp-2: W").
  *
  * State is the architectural RF and scratchpad, a queue of issued operations carrying their
  * computed values and commit boundaries, and for each operation the cycle from which its unit
  * accepts a new issue. Each cycle reads sources from the visible state, enqueues results for
  * E_(s+L), and then applies everything due at E_(s+1). Runtime checks stand in for RTL assertions:
  * the first violated legality rule stops the run. The model does not use the verifier (ADR-0002).
  *
  * In mvp-2 an operation of W moves two values: its two lanes, computed modulo 2^32 each, or the
  * words `a` and `a+1` of LD.W and ST.W. It enters the queue as one entry per register or word, all
  * carrying the operation's id, so that the hazards are per register and per word while the
  * write-back port is used once per operation.
  */
object ReferenceModel {
  private val Mask = 0xffffffffL

  /** One register or word that operation `op` writes at E_`commitAt`, with its value. */
  private final case class InFlight(
      issue: Int,
      commitAt: Int,
      target: Either[Int, Int], // Left(register) or Right(address)
      port: Option[Slot],
      value: Long,
      op: Int
  )

  /** The operand fields that bundle `b` reads in its issue cycle, each with the registers it reads.
    * ADD, SUB, MUL, VADD, VSUB and VMUL have two fields, MAC and VMAC three, ST and ST.W one. A
    * pair field reads r(p) and r(p+1), and a broadcast rs1 one register.
    */
  private[sim] def sourceFields(b: Bundle): Vector[Vector[Int]] = {
    def one(r: Int) = Vector(r)
    def two(p: Int) = Vector(p, p + 1)
    def multiplicand(r: Int, broadcast: Boolean) = if (broadcast) one(r) else two(r)
    val compute = b.compute match {
      case ComputeOp.Add(_, x, y)         => Vector(one(x), one(y))
      case ComputeOp.Sub(_, x, y)         => Vector(one(x), one(y))
      case ComputeOp.Mul(_, x, y)         => Vector(one(x), one(y))
      case ComputeOp.Mac(_, x, y, z)      => Vector(one(x), one(y), one(z))
      case ComputeOp.VAdd(_, x, y)        => Vector(two(x), two(y))
      case ComputeOp.VSub(_, x, y)        => Vector(two(x), two(y))
      case ComputeOp.VMul(_, x, y, bc)    => Vector(multiplicand(x, bc), two(y))
      case ComputeOp.VMac(_, x, y, z, bc) => Vector(multiplicand(x, bc), two(y), two(z))
      case ComputeOp.Nop | ComputeOp.Halt => Vector.empty
    }
    val memory = b.memory match {
      case MemoryOp.St(_, r)                                     => Vector(one(r))
      case MemoryOp.StW(_, p)                                    => Vector(two(p))
      case MemoryOp.Ld(_, _) | MemoryOp.LdW(_, _) | MemoryOp.Nop => Vector.empty
    }
    compute ++ memory
  }

  /** Registers that bundle `b` reads in its issue cycle, one entry per operand field (the field's
    * first register). Rule L11 limits their number to the read ports.
    */
  private[sim] def sourceRegisters(b: Bundle): Vector[Int] = sourceFields(b).map(_.head)

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
    // The two slots each issue at most one operation in cycle s.
    val (cid, mid) = (2 * s, 2 * s + 1)

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
      for { _ <- unit(mnemonic); x <- readReg(a); y <- readReg(b) } yield Vector(
        InFlight(s, s + latency(mnemonic), Left(d), Some(Slot.Compute), f(x, y) & Mask, cid)
      )

    // W (mvp-2). A pair field reads r(p) as lane 0 and r(p+1) as lane 1; a broadcast rs1 reads one
    // register for both lanes.
    def pair(p: Int): Either[Violation, (Long, Long)] =
      for { lane0 <- readReg(p); lane1 <- readReg(p + 1) } yield (lane0, lane1)
    def multiplicand(r: Int, broadcast: Boolean): Either[Violation, (Long, Long)] =
      if (broadcast) readReg(r).map(v => (v, v)) else pair(r)

    /** A vector operation writing the pair d:d+1 through the Compute port, as one operation. */
    def lanes(d: Int, mnemonic: String)(values: Either[Violation, (Long, Long)]) =
      for { _ <- unit(mnemonic); v <- values } yield {
        val at = s + latency(mnemonic)
        Vector(
          InFlight(s, at, Left(d), Some(Slot.Compute), v._1 & Mask, cid),
          InFlight(s, at, Left(d + 1), Some(Slot.Compute), v._2 & Mask, cid)
        )
      }

    /** The word at `a` for a LD or LD.W in cycle s: the scratchpad seen in cycle s (L7, L10, L8).
      */
    def loadWord(a: Int): Either[Violation, Long] =
      for {
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
      } yield v

    val bundle = bundles(s)

    // The bundle reads no more registers than there are read ports (L11).
    val reads = sourceRegisters(bundle).size
    val ports: Either[Violation, Unit] =
      if (reads <= machine.readPorts) Right(())
      else fail(Rule.ReadPorts, s"$reads register reads exceed the ${machine.readPorts} read ports")

    // 1. Read sources and compute results from the state visible in cycle s.
    val computeOp: Either[Violation, Vector[InFlight]] = bundle.compute match {
      case ComputeOp.Nop | ComputeOp.Halt => Right(Vector.empty)
      case ComputeOp.Add(d, a, b)         => binary(d, a, b, "ADD")(_ + _)
      case ComputeOp.Sub(d, a, b)         => binary(d, a, b, "SUB")(_ - _)
      case ComputeOp.Mul(d, a, b)         => binary(d, a, b, "MUL")(_ * _)
      case ComputeOp.Mac(d, a, b, c)      =>
        for { _ <- unit("MAC"); x <- readReg(a); y <- readReg(b); z <- readReg(c) } yield Vector(
          InFlight(s, s + latency("MAC"), Left(d), Some(Slot.Compute), (x * y + z) & Mask, cid)
        )
      case ComputeOp.VAdd(d, a, b) =>
        lanes(d, "VADD")(for { x <- pair(a); y <- pair(b) } yield (x._1 + y._1, x._2 + y._2))
      case ComputeOp.VSub(d, a, b) =>
        lanes(d, "VSUB")(for { x <- pair(a); y <- pair(b) } yield (x._1 - y._1, x._2 - y._2))
      case ComputeOp.VMul(d, a, b, bc) =>
        lanes(d, "VMUL")(for {
          x <- multiplicand(a, bc); y <- pair(b)
        } yield (x._1 * y._1, x._2 * y._2))
      case ComputeOp.VMac(d, a, b, c, bc) =>
        lanes(d, "VMAC")(for {
          x <- multiplicand(a, bc); y <- pair(b); z <- pair(c)
        } yield (x._1 * y._1 + z._1, x._2 * y._2 + z._2))
    }
    val memoryOp: Either[Violation, Vector[InFlight]] = bundle.memory match {
      case MemoryOp.Nop      => Right(Vector.empty)
      case MemoryOp.Ld(d, a) =>
        for { _ <- unit("LD"); v <- loadWord(a) } yield Vector(
          InFlight(s, s + latency("LD"), Left(d), Some(Slot.Memory), v, mid)
        )
      case MemoryOp.St(a, r) =>
        for { _ <- unit("ST"); addr <- address(a); v <- readReg(r) } yield Vector(
          InFlight(s, s + latency("ST"), Right(addr), None, v, mid)
        )
      // LD.W and ST.W: lane 0 is word a and lane 1 word a + 1 (which may be in the next row).
      case MemoryOp.LdW(d, a) =>
        for { _ <- unit("LD.W"); lane0 <- loadWord(a); lane1 <- loadWord(a + 1) } yield {
          val at = s + latency("LD.W")
          Vector(
            InFlight(s, at, Left(d), Some(Slot.Memory), lane0, mid),
            InFlight(s, at, Left(d + 1), Some(Slot.Memory), lane1, mid)
          )
        }
      case MemoryOp.StW(a, r) =>
        for {
          _ <- unit("ST.W"); lo <- address(a); hi <- address(a + 1); v <- pair(r)
        } yield {
          val at = s + latency("ST.W")
          Vector(
            InFlight(s, at, Right(lo), None, v._1, mid),
            InFlight(s, at, Right(hi), None, v._2, mid)
          )
        }
    }

    // 2. Reserve: no overlapping writes to a register (L4), one commit per port per boundary (L5).
    // L5 counts operations: the two entries of a pair are one use of the port.
    def reserve(ops: Vector[InFlight]): Either[Violation, Unit] =
      ops.zipWithIndex.foldLeft[Either[Violation, Unit]](Right(())) { case (acc, (op, i)) =>
        acc.flatMap { _ =>
          val others = state.inFlight.toVector ++ ops.patch(i, Nil, 1)
          val overlap = op.target match {
            case Left(r)  => others.find(_.target == Left(r)).map(o => (r, o))
            case Right(_) => None
          }
          val portClash = op.port.filter(p =>
            others.exists(o => o.op != op.op && o.port.contains(p) && o.commitAt == op.commitAt)
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
      ops = c ++ m
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
        case ComputeOp.VAdd(_, _, _)        => occupy("VADD")
        case ComputeOp.VSub(_, _, _)        => occupy("VSUB")
        case ComputeOp.VMul(_, _, _, _)     => occupy("VMUL")
        case ComputeOp.VMac(_, _, _, _, _)  => occupy("VMAC")
        case ComputeOp.Nop | ComputeOp.Halt => ()
      }
      bundle.memory match {
        case MemoryOp.Ld(_, _)  => occupy("LD")
        case MemoryOp.St(_, _)  => occupy("ST")
        case MemoryOp.LdW(_, _) => occupy("LD.W")
        case MemoryOp.StW(_, _) => occupy("ST.W")
        case MemoryOp.Nop       => ()
      }
      halted
    }
  }

  /** Applies every operation due at boundary `e`; returns the commits in trace order
    * (docs/TRACE.md: the Compute port, then the Memory port, each by register; then the words by
    * address).
    */
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
