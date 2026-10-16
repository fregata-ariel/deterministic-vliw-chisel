package vliw.sim

import vliw.isa._

/** Performance and configuration record of one run (format: docs/METRICS.md, vliw-metrics/1).
  *
  * Kept separate from the trace: the trace is for checking correctness cycle by cycle, this record
  * is for comparing programs and machine configurations.
  */
object Metrics {
  val Format: String = "vliw-metrics/1"

  private def rate(n: Long, d: Long): ujson.Value =
    if (d == 0) ujson.Null else ujson.Num(math.round(n.toDouble / d * 1e6) / 1e6)

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

    val computeOps = bundles.map(_.compute).collect {
      case _: ComputeOp.Add => "ADD"
      case _: ComputeOp.Sub => "SUB"
      case _: ComputeOp.Mul => "MUL"
    }
    val halts = bundles.count(_.compute == ComputeOp.Halt).toLong
    val memoryOps = bundles.map(_.memory).collect {
      case _: MemoryOp.Ld => "LD"
      case _: MemoryOp.St => "ST"
    }
    val byOperation = Seq("ADD", "SUB", "MUL", "LD", "ST", "HALT").map { m =>
      m -> ujson.Num(
        if (m == "HALT") halts.toDouble else (computeOps ++ memoryOps).count(_ == m).toDouble
      )
    }

    def slot(ops: Long, extra: Long) = ujson.Obj(
      "operations" -> ops.toDouble,
      "nop" -> (n - ops - extra).toDouble,
      "nop_rate" -> rate(n - ops - extra, n)
    )

    val commits = run.trace.cycles.flatMap(_.commits)
    def port(p: Slot) = {
      val c = commits.count { case Commit.Register(_, _, q) => q == p; case _ => false }.toLong
      ujson.Obj("commits" -> c.toDouble, "utilization" -> rate(c, cycles))
    }

    val loads = bundles.collect { case Bundle(_, MemoryOp.Ld(_, a)) => a }
    val stores = bundles.collect { case Bundle(_, MemoryOp.St(a, _)) => a }
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

    ujson.Obj(
      "metrics" -> Format,
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
      "issue" -> ujson.Obj(
        "compute" -> slot(computeOps.size.toLong, halts),
        "memory" -> slot(memoryOps.size.toLong, 0),
        "by_operation" -> ujson.Obj.from(byOperation)
      ),
      "writeback" -> ujson.Obj("compute" -> port(Slot.Compute), "memory" -> port(Slot.Memory)),
      "scratchpad" -> ujson.Obj(
        "reads" -> loads.size.toDouble,
        "writes" -> stores.size.toDouble,
        "accesses_per_cycle" -> rate((loads.size + stores.size).toLong, cycles),
        "banks" -> ujson.Arr.from(banks)
      ),
      "ii" -> ii.fold[ujson.Value](ujson.Null)(ujson.Num(_)),
      "rtl" -> ujson.Null
    )
  }
}
