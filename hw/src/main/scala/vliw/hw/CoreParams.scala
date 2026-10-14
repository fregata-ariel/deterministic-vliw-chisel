package vliw.hw

import chisel3.util.log2Ceil

import vliw.isa.MachineDescription

/** Elaboration parameters taken from the machine description.
  *
  * The RTL supports a subset of what the loader accepts. These limits are part of the configuration
  * contract to settle in #35:
  *   - every II is 1 (fully pipelined units; issue every cycle);
  *   - LD latency >= 2, because the scratchpad is a synchronous-read memory;
  *   - ST latency 1, so that a load and a store never meet at the same clock edge.
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

  val computeLatencies: Seq[Int] = Seq("ADD", "SUB", "MUL").map(latency)
  val maxComputeLatency: Int = computeLatencies.max
  val maxLatency: Int = (computeLatencies :+ latency("LD") :+ latency("ST")).max

  require(
    machine.operations.values.forall(_.ii == 1),
    "the RTL issues every unit every cycle and supports only II = 1 (#35)"
  )
  require(latency("LD") >= 2, "the RTL needs LD latency >= 2 (synchronous-read scratchpad)")
  require(latency("ST") == 1, "the RTL supports only ST latency 1 (#35)")
}
