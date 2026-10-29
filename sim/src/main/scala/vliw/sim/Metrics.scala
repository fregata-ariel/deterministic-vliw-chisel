package vliw.sim

import vliw.isa._

/** Performance and configuration record of one run (format: docs/METRICS.md). Records of ISA mvp-0
  * are vliw-metrics/1, records of mvp-1 are vliw-metrics/2, which adds MAC and register reads, and
  * records of mvp-2 are vliw-metrics/3, which counts operations, register words and transfer words
  * separately (an operation of W moves two registers or two words).
  *
  * Kept separate from the trace: the trace is for checking correctness cycle by cycle, this record
  * is for comparing programs and machine configurations.
  */
object Metrics {
  val Format: String = "vliw-metrics/1"
  val FormatMvp1: String = "vliw-metrics/2"
  val FormatMvp2: String = "vliw-metrics/3"

  /** The record format for runs on `machine`. */
  def format(machine: MachineDescription): String = machine.isaVersion match {
    case IsaVersion.Mvp0 => Format
    case IsaVersion.Mvp1 => FormatMvp1
    case IsaVersion.Mvp2 => FormatMvp2
    case other           => throw new IllegalArgumentException(s"unknown ISA version $other")
  }

  private def rate(n: Long, d: Long): ujson.Value =
    if (d == 0) ujson.Null else ujson.Num(math.round(n.toDouble / d * 1e6) / 1e6)

  /** The operations of each format's `issue.by_operation`, in the order they are written. */
  private def mnemonics(format: String): Seq[String] = format match {
    case Format     => Seq("ADD", "SUB", "MUL", "LD", "ST", "HALT")
    case FormatMvp1 => Seq("ADD", "SUB", "MUL", "MAC", "LD", "ST", "HALT")
    case _          =>
      Seq("ADD", "SUB", "MUL", "MAC", "VADD", "VSUB", "VMUL", "VMAC") ++
        Seq("LD", "ST", "LD.W", "ST.W", "HALT")
  }

  /** Builds the record for a completed run of `words` on `machine`. `ii` is the steady-state
    * initiation interval of a modulo-scheduled loop, as the caller has confirmed it (#68); None for
    * straight-line code.
    */
  def of(
      machine: MachineDescription,
      words: Vector[Long],
      run: Execution,
      name: String,
      ii: Option[Int] = None
  ): ujson.Obj = {
    val bundles = new BundleCodec(machine).decodeAll(words).fold(e => sys.error(e), identity)
    val n = bundles.size.toLong
    val cycles = run.cycles.toLong
    val version = format(machine)
    val mvp1 = version == FormatMvp1
    val mvp2 = version == FormatMvp2

    val computeOps = bundles.map(_.compute).collect {
      case _: ComputeOp.Add  => "ADD"
      case _: ComputeOp.Sub  => "SUB"
      case _: ComputeOp.Mul  => "MUL"
      case _: ComputeOp.Mac  => "MAC"
      case _: ComputeOp.VAdd => "VADD"
      case _: ComputeOp.VSub => "VSUB"
      case _: ComputeOp.VMul => "VMUL"
      case _: ComputeOp.VMac => "VMAC"
    }
    val halts = bundles.count(_.compute == ComputeOp.Halt).toLong
    val memoryOps = bundles.map(_.memory).collect {
      case _: MemoryOp.Ld  => "LD"
      case _: MemoryOp.St  => "ST"
      case _: MemoryOp.LdW => "LD.W"
      case _: MemoryOp.StW => "ST.W"
    }
    val byOperation = mnemonics(version).map { m =>
      m -> ujson.Num(
        if (m == "HALT") halts.toDouble else (computeOps ++ memoryOps).count(_ == m).toDouble
      )
    }

    def slot(ops: Long, extra: Long) = ujson.Obj(
      "operations" -> ops.toDouble,
      "nop" -> (n - ops - extra).toDouble,
      "nop_rate" -> rate(n - ops - extra, n)
    )

    // In /3 a commit is an operation that uses the port: rule L5 lets at most one operation commit
    // on a port at each boundary, so in a completed run the registers that one trace row commits
    // through a port are one operation's (docs/TRACE.md). `registers` counts each register.
    def port(p: Slot) = {
      val rows = run.trace.cycles.map(
        _.commits
          .count {
            case Commit.Register(_, _, q) => q == p
            case _                        => false
          }
          .toLong
      )
      val registers = rows.sum
      if (mvp2) {
        val operations = rows.count(_ > 0).toLong
        ujson.Obj(
          "commits" -> operations.toDouble,
          "registers" -> registers.toDouble,
          "utilization" -> rate(operations, cycles)
        )
      } else ujson.Obj("commits" -> registers.toDouble, "utilization" -> rate(registers, cycles))
    }

    // Words read and written; LD.W and ST.W access a and a + 1.
    val loads = bundles.flatMap {
      case Bundle(_, MemoryOp.Ld(_, a))  => Seq(a)
      case Bundle(_, MemoryOp.LdW(_, a)) => Seq(a, a + 1)
      case _                             => Nil
    }
    val stores = bundles.flatMap {
      case Bundle(_, MemoryOp.St(a, _))  => Seq(a)
      case Bundle(_, MemoryOp.StW(a, _)) => Seq(a, a + 1)
      case _                             => Nil
    }
    val banks = (0 until machine.banks).map { b =>
      val r = loads.count(_ % machine.banks == b).toLong
      val w = stores.count(_ % machine.banks == b).toLong
      ujson.Obj(
        "bank" -> b,
        "reads" -> r.toDouble,
        "writes" -> w.toDouble,
        "accesses_per_cycle" -> rate(r + w, cycles)
      )
    }

    val issue = ujson.Obj(
      "compute" -> slot(computeOps.size.toLong, halts),
      "memory" -> slot(memoryOps.size.toLong, 0),
      "by_operation" -> ujson.Obj.from(byOperation)
    )
    if (mvp1 || mvp2) {
      // Reads are counted by operand field, as rule L11 counts them; keys run from 0 to read_ports.
      val reads = bundles.map(ReferenceModel.sourceRegisters(_).size)
      val byReads = ujson.Obj.from((0 to machine.readPorts).map { k =>
        k.toString -> ujson.Num(reads.count(_ == k).toDouble)
      })
      if (mvp1)
        issue("register_reads") = ujson.Obj(
          "read_ports" -> machine.readPorts,
          "reads" -> reads.sum.toDouble,
          "bundles_by_reads" -> byReads
        )
      else {
        // The 32-bit registers the fields read (a pair 2, a broadcast 1), and per RF bank (r mod 2).
        val registers = bundles.flatMap(ReferenceModel.sourceFields(_).flatten)
        issue("register_reads") = ujson.Obj(
          "read_ports" -> machine.readPorts,
          "reads" -> reads.sum.toDouble,
          "registers" -> registers.size.toDouble,
          "banks" -> ujson.Arr.from((0 until 2).map { b =>
            ujson.Obj("bank" -> b, "registers" -> registers.count(_ % 2 == b).toDouble)
          }),
          "bundles_by_reads" -> byReads
        )
      }
    }
    if (mvp1)
      issue("mac_with_st") = bundles.count {
        case Bundle(_: ComputeOp.Mac, _: MemoryOp.St) => true
        case _                                        => false
      }.toDouble
    if (mvp2)
      issue("rs3_with_store") = bundles.count { b =>
        val rs3 = b.compute.isInstanceOf[ComputeOp.Mac] || b.compute.isInstanceOf[ComputeOp.VMac]
        rs3 && (b.memory.isInstanceOf[MemoryOp.St] || b.memory.isInstanceOf[MemoryOp.StW])
      }.toDouble

    val scratchpad = ujson.Obj()
    if (mvp2) {
      scratchpad("read_operations") = memoryOps.count(m => m == "LD" || m == "LD.W").toDouble
      scratchpad("write_operations") = memoryOps.count(m => m == "ST" || m == "ST.W").toDouble
    }
    scratchpad("reads") = loads.size.toDouble
    scratchpad("writes") = stores.size.toDouble
    scratchpad("accesses_per_cycle") = rate((loads.size + stores.size).toLong, cycles)
    scratchpad("banks") = ujson.Arr.from(banks)

    ujson.Obj(
      "metrics" -> version,
      "isa" -> machine.isaVersion,
      "machine" -> ujson
        .Obj("id" -> machine.machineId, "revision" -> machine.revision, "hash" -> machine.hash),
      "program" -> ujson.Obj(
        "name" -> name,
        "hash" -> Json.sha256(ProgramFile.toHex(words)),
        "bundles" -> n.toDouble
      ),
      "generator" -> ujson.Obj(
        "model" -> "vliw.sim.ReferenceModel",
        "scala" -> scala.util.Properties.versionNumberString,
        "java" -> System.getProperty("java.version")
      ),
      "cycles" -> cycles.toDouble,
      "issue" -> issue,
      "writeback" -> ujson.Obj("compute" -> port(Slot.Compute), "memory" -> port(Slot.Memory)),
      "scratchpad" -> scratchpad,
      "ii" -> ii.fold[ujson.Value](ujson.Null)(ujson.Num(_)),
      "rtl" -> ujson.Null
    )
  }
}
