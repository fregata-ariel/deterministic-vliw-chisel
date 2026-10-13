package vliw.sim

import vliw.isa.{Disassembler, IsaVersion, MachineDescription}

/** Per-cycle record of a run (format: docs/TRACE.md). */
final case class Trace(machine: MachineDescription, cycles: Vector[Trace.Cycle]) {

  /** Header line identifying the ISA and machine description. */
  def header: ujson.Obj = ujson.Obj(
    "trace" -> Trace.Format,
    "isa" -> IsaVersion.Current,
    "machine" -> machine.machineId,
    "machine_revision" -> machine.revision,
    "machine_hash" -> machine.hash
  )

  /** Body lines, one per cycle, without the header. */
  def bodyLines: Vector[String] = cycles.map(c => ujson.write(c.toJson))

  def jsonLines: String = (ujson.write(header) +: bodyLines).map(_ + "\n").mkString
}

object Trace {
  val Format: String = "vliw-trace/1"

  /** Cycle `cycle`: the bundle issued in it and the commits made visible at its start (E_cycle).
    * `pc` and `issue` are None after HALT.
    */
  final case class Cycle(
      cycle: Int,
      pc: Option[Int],
      issue: Option[vliw.isa.Bundle],
      commits: Vector[Commit],
      busy: Boolean,
      done: Boolean
  ) {
    def toJson: ujson.Obj = ujson.Obj(
      "cycle" -> cycle,
      "pc" -> pc.fold[ujson.Value](ujson.Null)(ujson.Num(_)),
      "issue" -> issue.fold[ujson.Value](ujson.Null) { b =>
        ujson.Obj(
          "compute" -> Disassembler.compute(b.compute),
          "memory" -> Disassembler.memory(b.memory)
        )
      },
      "rf_commit" -> ujson.Arr.from(commits.collect { case Commit.Register(r, v, p) =>
        ujson.Obj("reg" -> r, "value" -> v.toDouble, "port" -> p.name)
      }),
      "mem_commit" -> ujson.Arr.from(commits.collect { case Commit.Memory(a, v) =>
        ujson.Obj("addr" -> a, "value" -> v.toDouble)
      }),
      "busy" -> busy,
      "done" -> done
    )
  }
}
