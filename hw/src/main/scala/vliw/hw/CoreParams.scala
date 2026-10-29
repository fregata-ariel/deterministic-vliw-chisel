package vliw.hw

import chisel3.util.log2Ceil

import vliw.isa.{MachineDescription, Slot}

/** Elaboration parameters taken from the machine description.
  *
  * The RTL implements a subset of what the loader accepts (docs/MACHINE.md, "実装ごとの対応範囲"):
  *   - every II is 1 (fully pipelined units; issue every cycle);
  *   - LD and LD.W latency >= 2, because the scratchpad is a synchronous-read memory;
  *   - ST and ST.W latency 1, so that a load and a store never meet at the same clock edge;
  *   - 3 register read ports (per RF bank on ISA mvp-2): port 2 reads the rs3 of MAC and VMAC or
  *     the source of ST and ST.W (ADR-0018, ADR-0022).
  *
  * The ranges are checked over every operation of the machine, not over a fixed list. Use
  * [[CoreParams.check]] to get every reason a machine is outside that subset.
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

  /** ISA mvp-2 (W, docs/ISA.md "mvp-2: W"): register pairs, the vector operations VADD, VSUB, VMUL
    * and VMAC, and a register file of two banks. On mvp-0 and mvp-1 the RTL has no W logic at all.
    */
  val hasVector: Boolean = machine.hasVector

  /** W-full (`scratchpad.max_words_per_access` 2): the memory slot also issues LD.W and ST.W. */
  val hasWideMemory: Boolean = machine.hasWideMemory

  /** The vector operation of each compute kind, by mnemonic. */
  val vectorMnemonics: Seq[String] = Seq("VADD", "VSUB", "VMUL", "VMAC")

  /** The latency of LD.W (W-full only). */
  val wideLoadLatency: Option[Int] = Option.when(hasWideMemory)(latency("LD.W"))

  /** The latency of every operation that writes a register through the compute port. */
  val computeLatencies: Seq[Int] =
    machine.operations.values.filter(_.writeback.contains(Slot.Compute)).map(_.latency).toSeq
  val maxComputeLatency: Int = computeLatencies.max
  val maxLatency: Int = machine.operations.values.map(_.latency).max

  /** The longest load latency: LD, or LD.W on W-full. */
  val maxLoadLatency: Int = (latency("LD") +: wideLoadLatency.toSeq).max

  require(
    CoreParams.unsupported(machine).isEmpty,
    CoreParams.unsupported(machine).mkString("; ")
  )
}

object CoreParams {

  /** The operations whose II and latency the RTL checks, in the order of the reasons. */
  private val Order =
    Seq("ADD", "SUB", "MUL", "MAC", "VADD", "VSUB", "VMUL", "VMAC", "LD", "ST", "LD.W", "ST.W")

  /** Why the RTL cannot implement `machine`, one reason per setting; empty when it can. */
  def unsupported(machine: MachineDescription): Seq[String] = {
    def op(m: String) = machine.operation(m)
    // Every operation of the machine (HALT is not one of `operations`), so that a machine with an
    // operation outside this list cannot pass unchecked.
    val units = Order.filter(machine.operations.contains) ++
      machine.operations.keys.filterNot(Order.contains).toSeq.sorted
    val ii = units.filter(op(_).ii != 1).map { m =>
      s"$m has II ${op(m).ii}; the RTL issues every unit every cycle and supports only II 1"
    }
    val loads = Seq("LD", "LD.W").filter(machine.operations.contains)
    val ld = loads.filter(op(_).latency < 2).map { m =>
      s"$m has latency ${op(m).latency}; the RTL needs at least 2 (synchronous-read scratchpad)"
    }
    val stores = Seq("ST", "ST.W").filter(machine.operations.contains)
    val st = stores.filter(op(_).latency != 1).map { m =>
      s"$m has latency ${op(m).latency}; the RTL supports only 1 (stores write at the issue edge)"
    }
    val readPorts = Option.when(machine.readPorts != 3)(
      if (machine.hasVector)
        s"read_ports is ${machine.readPorts}; the RTL has 3 register read ports per RF bank, port 2 shared by the rs3 of MAC and VMAC and the Memory source of ST and ST.W (4 read ports come in #57, ADR-0018)"
      else
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
