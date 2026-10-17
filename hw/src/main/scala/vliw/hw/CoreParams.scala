package vliw.hw

import chisel3.util.log2Ceil

import vliw.isa.MachineDescription

/** Elaboration parameters taken from the machine description.
  *
  * The RTL implements a subset of what the loader accepts (docs/MACHINE.md, "実装ごとの対応範囲"):
  *   - every II is 1 (fully pipelined units; issue every cycle);
  *   - LD latency >= 2, because the scratchpad is a synchronous-read memory;
  *   - ST latency 1, so that a load and a store never meet at the same clock edge;
  *   - 3 register read ports: on ISA mvp-1, port 2 reads MAC's rs3 or the ST source (ADR-0018).
  *
  * Use [[CoreParams.check]] to get every reason a machine is outside that subset.
  */
final case class CoreParams(machine: MachineDescription) {
  val registerCount: Int = machine.registerCount
  val regBits: Int = log2Ceil(registerCount).max(1)
  val banks: Int = machine.banks
  val wordsPerBank: Int = machine.wordsPerBank
  val scratchpadWords: Int = machine.scratchpadWords
  val imemBundles: Int = machine.instructionMemoryBundles
  val pcBits: Int = log2Ceil(imemBundles + 1)

  def latency(mnemonic: String): Int = machine.operation(mnemonic).latency
  def opcode(mnemonic: String): Int = machine.operation(mnemonic).opcode

  /** ISA mvp-1: the compute slot also issues MAC (docs/ISA.md, "mvp-1: MAC"). On mvp-0 the RTL has
    * no MAC logic at all.
    */
  val hasMac: Boolean = machine.hasMac
  val macOpcode: Option[Int] = Option.when(hasMac)(opcode("MAC"))
  val macLatency: Option[Int] = Option.when(hasMac)(latency("MAC"))

  val computeLatencies: Seq[Int] = Seq("ADD", "SUB", "MUL").map(latency) ++ macLatency
  val maxComputeLatency: Int = computeLatencies.max
  val maxLatency: Int = (computeLatencies :+ latency("LD") :+ latency("ST")).max

  require(
    CoreParams.unsupported(machine).isEmpty,
    CoreParams.unsupported(machine).mkString("; ")
  )
}

object CoreParams {

  /** Why the RTL cannot implement `machine`, one reason per setting; empty when it can. */
  def unsupported(machine: MachineDescription): Seq[String] = {
    def op(m: String) = machine.operation(m)
    val units = Seq("ADD", "SUB", "MUL", "MAC", "LD", "ST").filter(machine.operations.contains)
    val ii = units.filter(op(_).ii != 1).map { m =>
      s"$m has II ${op(m).ii}; the RTL issues every unit every cycle and supports only II 1"
    }
    val ld = Option.when(op("LD").latency < 2)(
      s"LD has latency ${op("LD").latency}; the RTL needs at least 2 (synchronous-read scratchpad)"
    )
    val st = Option.when(op("ST").latency != 1)(
      s"ST has latency ${op("ST").latency}; the RTL supports only 1 (stores write at the issue edge)"
    )
    val readPorts = Option.when(machine.readPorts != 3)(
      s"read_ports is ${machine.readPorts}; the RTL has 3 register read ports, port 2 shared by MAC's rs3 and the ST source (4 read ports come in #57)"
    )
    ii ++ ld ++ st ++ readPorts
  }

  /** The parameters for `machine`, or every reason the RTL cannot implement it. */
  def check(machine: MachineDescription): Either[Seq[String], CoreParams] =
    unsupported(machine) match {
      case Seq()   => Right(CoreParams(machine))
      case reasons => Left(reasons)
    }
}
