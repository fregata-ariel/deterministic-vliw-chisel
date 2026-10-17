package vliw.compiler.modulo

import vliw.compiler.KernelCompiler
import vliw.compiler.dsl.MemoryLayout
import vliw.compiler.loop.{LoopFusion, LoopGraph, LoopKernel, LoopLowering}
import vliw.isa.{Bundle, MachineDescription}

/** Whether a loop is fused into MAC on a machine with MAC (ADR-0019, docs/COMPILER.md "Modulo
  * Scheduling"): the MUL + ADD pairs of `LoopFusion`, and the MII of the loop without and with
  * them. The loop is fused when it has a pair and the MII does not grow. Both MIIs leave out the
  * third read port (`Mii.withoutReadPort`), so that three and four read ports fuse the same loops.
  */
final case class FusionChoice(pairs: Int, unfused: Mii, fused: Mii) {
  def unfusedMII: Int = unfused.withoutReadPort.mii
  def fusedMII: Int = fused.withoutReadPort.mii
  def fuses: Boolean = pairs > 0 && fusedMII <= unfusedMII
}

object FusionChoice {

  /** The graph to schedule: `g` fused when the choice fuses it. None on a machine without MAC. */
  def of(g: LoopGraph): (LoopGraph, Option[FusionChoice]) =
    if (!g.machine.hasMac) (g, None)
    else {
      val fused = LoopFusion.fuse(g)
      val c = FusionChoice(LoopFusion.pairs(g).size, Mii.of(g), Mii.of(fused))
      (if (c.fuses) fused else g, Some(c))
    }
}

/** A modulo-scheduled loop kernel and the program the verifier accepted. `fusion` says whether the
  * loop was fused into MAC (None on a machine without MAC).
  */
final case class ModuloCompiled(
    kernel: LoopKernel,
    machine: MachineDescription,
    layout: MemoryLayout,
    result: ModuloSchedule,
    bundles: Vector[Bundle],
    words: Vector[Long],
    fusion: Option[FusionChoice] = None
) {
  def graph: LoopGraph = result.graph
  def ii: Int = result.ii

  /** The cycles the schedule predicts: `h + 1` with HALT at h. */
  def cycles: Int = result.cycles
}

/** Loop kernel to program by Modulo Scheduling (docs/COMPILER.md, "Modulo Scheduling", #68). */
object ModuloCompiler {

  /** The input of the II search: the layout of `kernel` and its loop graph, fused into MAC when
    * `fusion` says so (None on a machine without MAC).
    */
  final case class Prepared(
      kernel: LoopKernel,
      layout: MemoryLayout,
      graph: LoopGraph,
      fusion: Option[FusionChoice]
  )

  /** Layout and lowering as `loop.LoopLowering`, then the loop graph, fused by `FusionChoice`. */
  def prepare(k: LoopKernel, machine: MachineDescription): Either[String, Prepared] =
    LoopLowering.layout(k, machine).map { layout =>
      val (g, fusion) = FusionChoice.of(LoopGraph.of(LoopLowering.lower(k, layout), machine))
      Prepared(k, layout, g, fusion)
    }

  /** [[prepare]], then the II search; the program passes the final verifier check
    * (`KernelCompiler.checked`) or it is a compiler error.
    */
  def compile(k: LoopKernel, machine: MachineDescription): Either[String, ModuloCompiled] =
    for {
      p <- prepare(k, machine)
      result <- ModuloScheduler
        .schedule(p.graph)
        .left
        .map(no => s"${k.name}: ${k.trips} iterations of the loop over ${k.index}: ${no.message}")
      bundles = result.allocation.bundles
      words <- KernelCompiler.checked(machine, p.layout, bundles)
    } yield ModuloCompiled(k, machine, p.layout, result, bundles, words, p.fusion)
}
