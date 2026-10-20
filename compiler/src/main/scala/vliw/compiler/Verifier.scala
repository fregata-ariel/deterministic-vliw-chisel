package vliw.compiler

import vliw.isa._

/** One violated legality rule. `cycle` is the bundle index, None for whole-program findings. */
final case class Finding(rule: Rule, cycle: Option[Int], message: String) {
  override def toString: String =
    s"${rule.id} (${rule.title})${cycle.fold("")(c => s" at cycle $c")}: $message"
}

/** Static schedule verifier for the legality rules L1-L11 (docs/VERIFIER.md).
  *
  * It never computes values. The program is summarised as register write intervals `[s, s+L)`,
  * write-back port reservations per commit boundary, register and memory reads with their cycles,
  * and the issue cycles of each operation; each rule is then a check over these tables. All
  * violations are reported. The verifier does not use the reference model (ADR-0002).
  *
  * ISA mvp-2 (W) comes with #110 stage 2: until then [[verify]] refuses mvp-2 machines.
  */
object Verifier {

  /** The W operations of mvp-2, which no check reaches: [[verify]] refuses mvp-2 machines first. */
  private def notYet(op: Product): Nothing =
    throw new UnsupportedOperationException(
      s"$op: ISA mvp-2 is not implemented in the verifier yet (#110 stage 2)"
    )

  /** Declared inputs: only which locations the host sets matters, not their values. */
  final case class Declaration(registers: Set[Int], memory: Set[Int])
  object Declaration {
    def of(inputs: Inputs): Declaration = Declaration(inputs.registers.keySet, inputs.memory.keySet)
  }

  def verify(
      machine: MachineDescription,
      words: Vector[Long],
      declared: Declaration
  ): Vector[Finding] = {
    IsaVersion.requireImplemented(machine, "the verifier", "stage 2")
    val codec = new BundleCodec(machine)
    val decoded = words.map(codec.decode)
    val encoding = decoded.zipWithIndex.collect { case (Left(e), i) =>
      Finding(Rule.Encoding, Some(i), e)
    }
    val size = sizeFindings(machine, words.size)
    if (encoding.nonEmpty) size ++ encoding // Without every bundle decoded, nothing else is known.
    else {
      val bundles = decoded.collect { case Right(b) => b }
      val summary = Summary(machine, bundles)
      (size ++ haltPlacement(bundles) ++ inFlightReads(summary) ++ overlappingWrites(summary) ++
        writebackPorts(summary) ++ haltCompletion(bundles, summary) ++ addressRange(
          machine,
          summary
        ) ++
        undefinedReads(summary, declared) ++ issueIntervals(machine, summary) ++ inFlightStoreReads(
          summary
        ) ++ readPorts(machine, summary)).sortBy(f => (f.cycle.getOrElse(-1), f.rule.id))
    }
  }

  /** Register write `[issue, issue + latency)`; the value is visible from `issue + latency`. */
  private final case class Write(
      reg: Int,
      issue: Int,
      latency: Int,
      port: Option[Slot],
      what: String
  ) {
    def end: Int = issue + latency
  }
  private final case class Read(reg: Int, cycle: Int, what: String)
  private final case class Access(addr: Int, cycle: Int, store: Boolean, latency: Int)
  private final case class Issue(mnemonic: String, cycle: Int, what: String)

  /** Static tables extracted from the program. */
  private final case class Summary(
      writes: Vector[Write],
      reads: Vector[Read],
      accesses: Vector[Access],
      issues: Vector[Issue]
  )
  private object Summary {
    def apply(machine: MachineDescription, bundles: Vector[Bundle]): Summary = {
      def lat(m: String) = machine.operation(m).latency
      val writes = Vector.newBuilder[Write]
      val reads = Vector.newBuilder[Read]
      val accesses = Vector.newBuilder[Access]
      val issues = Vector.newBuilder[Issue]
      bundles.zipWithIndex.foreach { case (b, s) =>
        val text = Disassembler.compute(b.compute)
        def rrr(d: Int, x: Int, y: Int, m: String): Unit = {
          reads += Read(x, s, text); reads += Read(y, s, text)
          writes += Write(d, s, lat(m), Some(Slot.Compute), text)
          issues += Issue(m, s, text)
        }
        b.compute match {
          case ComputeOp.Add(d, x, y)         => rrr(d, x, y, "ADD")
          case ComputeOp.Sub(d, x, y)         => rrr(d, x, y, "SUB")
          case ComputeOp.Mul(d, x, y)         => rrr(d, x, y, "MUL")
          case ComputeOp.Mac(d, x, y, z)      => rrr(d, x, y, "MAC"); reads += Read(z, s, text)
          case ComputeOp.Nop | ComputeOp.Halt => ()
          case op: ComputeOp.VAdd             => notYet(op)
          case op: ComputeOp.VSub             => notYet(op)
          case op: ComputeOp.VMul             => notYet(op)
          case op: ComputeOp.VMac             => notYet(op)
        }
        val mtext = Disassembler.memory(b.memory)
        b.memory match {
          case MemoryOp.Ld(d, a) =>
            writes += Write(d, s, lat("LD"), Some(Slot.Memory), mtext)
            accesses += Access(a, s, store = false, lat("LD"))
            issues += Issue("LD", s, mtext)
          case MemoryOp.St(a, r) =>
            reads += Read(r, s, mtext)
            accesses += Access(a, s, store = true, lat("ST"))
            issues += Issue("ST", s, mtext)
          case MemoryOp.Nop     => ()
          case op: MemoryOp.LdW => notYet(op)
          case op: MemoryOp.StW => notYet(op)
        }
      }
      Summary(writes.result(), reads.result(), accesses.result(), issues.result())
    }
  }

  private def sizeFindings(machine: MachineDescription, n: Int): Vector[Finding] =
    if (n == 0) Vector(Finding(Rule.ProgramShape, None, "the program is empty"))
    else if (n > machine.instructionMemoryBundles)
      Vector(
        Finding(
          Rule.ProgramShape,
          None,
          s"$n bundles exceed the instruction memory (${machine.instructionMemoryBundles})"
        )
      )
    else Vector.empty

  // L2: exactly one HALT, in the last bundle.
  private def haltPlacement(bundles: Vector[Bundle]): Vector[Finding] = {
    val halts = bundles.indices.filter(i => bundles(i).compute == ComputeOp.Halt)
    if (bundles.isEmpty) Vector.empty
    else if (halts.isEmpty) Vector(Finding(Rule.ProgramShape, None, "the program has no HALT"))
    else
      halts.filter(_ != bundles.size - 1).toVector.map { i =>
        Finding(Rule.ProgramShape, Some(i), s"HALT must be the last bundle (${bundles.size - 1})")
      }
  }

  // L3: no read of r strictly inside a write interval [s, s+L) of r.
  private def inFlightReads(sum: Summary): Vector[Finding] =
    for {
      r <- sum.reads
      w <- sum.writes
      if w.reg == r.reg && w.issue < r.cycle && r.cycle < w.end
    } yield Finding(
      Rule.InFlightRead,
      Some(r.cycle),
      s"`${r.what}` reads r${r.reg} while `${w.what}` (cycle ${w.issue}) writes it; visible from cycle ${w.end}"
    )

  // L4: write intervals of the same register are disjoint.
  private def overlappingWrites(sum: Summary): Vector[Finding] =
    sum.writes.groupBy(_.reg).toVector.sortBy(_._1).flatMap { case (reg, ws) =>
      val sorted = ws.sortBy(w => (w.issue, w.end))
      for {
        i <- sorted.indices.toVector
        j <- (i + 1) until sorted.size
        a = sorted(i)
        b = sorted(j)
        if b.issue < a.end && a.issue < b.end
      } yield Finding(
        Rule.OverlappingWrites,
        Some(b.issue),
        s"writes to r$reg overlap: `${a.what}` [${a.issue}, ${a.end}) and `${b.what}` [${b.issue}, ${b.end})"
      )
    }

  // L5: at most one commit per write-back port per boundary.
  private def writebackPorts(sum: Summary): Vector[Finding] =
    sum.writes
      .collect { case w @ Write(_, _, _, Some(port), _) => (port, w.end) -> w }
      .groupBy(_._1)
      .toVector
      .sortBy { case ((port, end), _) => (end, port.name) }
      .collect {
        case ((port, end), ws) if ws.size > 1 =>
          val users = ws.map(_._2).sortBy(_.issue)
          Finding(
            Rule.WritebackPort,
            Some(users.last.issue),
            s"the ${port.name} write-back port is used ${users.size} times at E_$end: " +
              users.map(w => s"`${w.what}` (cycle ${w.issue})").mkString(", ")
          )
      }

  // L6: everything issued before the final HALT at h completes by E_h.
  private def haltCompletion(bundles: Vector[Bundle], sum: Summary): Vector[Finding] =
    bundles.lastOption.filter(_.compute == ComputeOp.Halt).toVector.flatMap { _ =>
      val h = bundles.size - 1
      val late = sum.writes.map(w => (w.issue, w.end, w.what)) ++
        sum.accesses.filter(_.store).map(a => (a.cycle, a.cycle + a.latency, s"st [${a.addr}]"))
      late.filter(_._2 > h).sortBy(_._1).map { case (issue, end, what) =>
        Finding(
          Rule.HaltCompletion,
          Some(h),
          s"`$what` (cycle $issue) completes at E_$end, after HALT at cycle $h"
        )
      }
    }

  // L7: scratchpad addresses within the machine's capacity.
  private def addressRange(machine: MachineDescription, sum: Summary): Vector[Finding] =
    sum.accesses.filter(_.addr >= machine.scratchpadWords).map { a =>
      Finding(
        Rule.AddressRange,
        Some(a.cycle),
        s"address ${a.addr} is outside the scratchpad (0..${machine.scratchpadWords - 1})"
      )
    }

  // L8: every read has a declared input or a write (register or store) that is visible by then.
  private def undefinedReads(sum: Summary, declared: Declaration): Vector[Finding] = {
    val regs = sum.reads
      .filterNot { r =>
        declared.registers.contains(r.reg) || sum.writes
          .exists(w => w.reg == r.reg && w.end <= r.cycle)
      }
      .map(r =>
        Finding(
          Rule.UndefinedRead,
          Some(r.cycle),
          s"`${r.what}` reads r${r.reg}, which has no value yet"
        )
      )
    val loads = sum.accesses
      .filter(!_.store)
      .filterNot { l =>
        declared.memory.contains(l.addr) || sum.accesses
          .exists(st => st.store && st.addr == l.addr && st.cycle + st.latency <= l.cycle)
      }
      .map(l =>
        Finding(
          Rule.UndefinedRead,
          Some(l.cycle),
          s"ld [${l.addr}] reads a word that has no value yet"
        )
      )
    regs ++ loads
  }

  // L9: two issues of the same operation are at least its II apart.
  private def issueIntervals(machine: MachineDescription, sum: Summary): Vector[Finding] =
    sum.issues.groupBy(_.mnemonic).toVector.sortBy(_._1).flatMap { case (m, is) =>
      val ii = machine.operation(m).ii
      is.sortBy(_.cycle).sliding(2).collect {
        case Vector(a, b) if b.cycle - a.cycle < ii =>
          Finding(
            Rule.IssueInterval,
            Some(b.cycle),
            s"`${b.what}` is issued ${b.cycle - a.cycle} cycle(s) after `${a.what}` (cycle ${a.cycle}); $m has II $ii"
          )
      }
    }

  // L11: the operand fields of a bundle read at most `read_ports` registers. Reads are counted per
  // field, so one register read by two fields counts twice.
  private def readPorts(machine: MachineDescription, sum: Summary): Vector[Finding] =
    sum.reads.groupBy(_.cycle).toVector.sortBy(_._1).collect {
      case (cycle, rs) if rs.size > machine.readPorts =>
        Finding(
          Rule.ReadPorts,
          Some(cycle),
          s"the bundle reads ${rs.size} registers (${rs.map(r => s"r${r.reg}").mkString(", ")}); " +
            s"the register file has ${machine.readPorts} read ports"
        )
    }

  // L10: no load of a word strictly inside the interval [s, s+L) of a store to it.
  private def inFlightStoreReads(sum: Summary): Vector[Finding] =
    for {
      l <- sum.accesses
      if !l.store
      st <- sum.accesses
      if st.store && st.addr == l.addr && st.cycle < l.cycle && l.cycle < st.cycle + st.latency
    } yield Finding(
      Rule.InFlightStoreRead,
      Some(l.cycle),
      s"ld [${l.addr}] reads the word while `st [${st.addr}]` (cycle ${st.cycle}) writes it; visible from cycle ${st.cycle + st.latency}"
    )
}
