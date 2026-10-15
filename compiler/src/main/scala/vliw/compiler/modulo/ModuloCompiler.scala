package vliw.compiler.modulo

import vliw.compiler.KernelCompiler
import vliw.compiler.dsl.MemoryLayout
import vliw.compiler.loop.{LoopGraph, LoopKernel, LoopLowering}
import vliw.isa.{Bundle, MachineDescription}

/** A modulo-scheduled loop kernel and the program the verifier accepted. */
final case class ModuloCompiled(
    kernel: LoopKernel,
    machine: MachineDescription,
    layout: MemoryLayout,
    result: ModuloSchedule,
    bundles: Vector[Bundle],
    words: Vector[Long]
) {
  def graph: LoopGraph = result.graph
  def ii: Int = result.ii

  /** The cycles the schedule predicts: `h + 1` with HALT at h. */
  def cycles: Int = result.cycles
}

/** Loop kernel to program by Modulo Scheduling (docs/COMPILER.md, "Modulo Scheduling", #68). */
object ModuloCompiler {

  /** Layout and lowering as `loop.LoopLowering`, the loop graph, then the II search; the program
    * passes the final verifier check (`KernelCompiler.checked`) or it is a compiler error.
    */
  def compile(k: LoopKernel, machine: MachineDescription): Either[String, ModuloCompiled] =
    for {
      layout <- LoopLowering.layout(k, machine)
      graph = LoopGraph.of(LoopLowering.lower(k, layout), machine)
      result <- ModuloScheduler
        .schedule(graph)
        .left
        .map(no => s"${k.name}: ${k.trips} iterations of the loop over ${k.index}: ${no.message}")
      bundles = result.allocation.bundles
      words <- KernelCompiler.checked(machine, layout, bundles)
    } yield ModuloCompiled(k, machine, layout, result, bundles, words)
}
