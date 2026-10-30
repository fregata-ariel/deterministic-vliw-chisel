package vliw.compiler.modulo

import vliw.compiler.KernelCompiler
import vliw.compiler.dag.LaneReason
import vliw.compiler.dsl.MemoryLayout
import vliw.compiler.loop._
import vliw.compiler.regalloc.RegisterAllocator
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

/** The loop vectorization decision on a machine with LD.W (ADR-0023; docs/COMPILER.md "ループのベクトル化と
  * Modulo Scheduling（W-full）"): information, never a fallback. `notDoubled` is every reason not to
  * double (empty when doubled); the MIIs compared leave out the third read port (`scalarMII` of G,
  * `doubledMII` of the doubled graph when it was built); `body` is the body packing of a loop that
  * is not doubled (`LoopPack`: its pairs, and each other store's lane reason; None when doubled,
  * and from [[ModuloCompiler.decide]], which decides the doubling only).
  */
final case class LoopDecision(
    notDoubled: Vector[NotDoubled],
    scalarMII: Int,
    doubledMII: Option[Int],
    body: Option[LoopBody]
) {
  def doubled: Boolean = notDoubled.isEmpty

  /** Not doubled, and its body packs at least one store pair. */
  def packed: Boolean = body.exists(!_.nothingToPack)

  private def miis = doubledMII.fold("")(d => s" (MII $d vs 2 × $scalarMII)")

  /** The body is described when a pair packs or a rule rejected a store (not for a loop whose
    * stores all have no neighbour).
    */
  private def bodyText = body
    .filter(b => !b.nothingToPack || b.scalarLanes.exists(_.reason != LaneReason.NoNeighbour))
    .fold("")(b => s"; ${b.describe}")
  def describe: String =
    if (doubled) s"doubled$miis"
    else s"not doubled: ${notDoubled.map(_.describe).mkString(", ")}$miis$bodyText"
}

/** Why a loop runs as its scalar loop G (ADR-0023's loop fallback). G's code is the mvp-1 code on a
  * machine whose scalar latencies are mvp-1's; `mvp2_vii` stores with latency 2 and ends one cycle
  * later.
  */
sealed trait LoopFallback extends Product with Serializable {
  def describe: String
}
object LoopFallback {

  /** Not doubled, and no store pair of the body packs (`LoopPack`): no rule-1 candidate, or every
    * candidate rejected.
    */
  case object NothingToPack extends LoopFallback {
    def describe: String = "NothingToPack"
  }

  /** The W graph fits no II from its MII to the span of one iteration; every II's outcome. */
  final case class NoIiFits(attempts: Vector[Attempt]) extends LoopFallback {
    def describe: String =
      "NoIiFits: " + attempts.map(a => s"II ${a.ii}: ${a.outcome}").mkString("; ")
  }

  /** A part of the W path that is not implemented: W-reg ([[WReg]]). */
  final case class NotImplemented(what: String) extends LoopFallback {
    def describe: String = s"NotImplemented: $what"
  }

  /** W-reg (no LD.W): #78's question 8 (c) (ADR-0023 既定; #110 第5段の Q1 (a): オーナーが 2026-11-06 に採った).
    */
  val WReg: String = "W-reg Modulo Scheduling (#78 Q8 (c))"
}

/** What a modulo-scheduled loop does with W (ISA mvp-2, ADR-0023). */
sealed trait LoopSimd extends Product with Serializable
object LoopSimd {

  /** An mvp-0 or mvp-1 machine: no W. */
  case object Scalar extends LoopSimd

  /** The W graph was compiled: the doubled graph (`decision.doubled`), or the packed body
    * (`decision.packed`).
    */
  final case class Vectorized(decision: LoopDecision) extends LoopSimd

  /** The scalar loop G was compiled instead, for `reason`; the code uses no W operation. The
    * decision is None on W-reg, which is not vectorized ([[LoopFallback.WReg]]).
    */
  final case class Fallback(reason: LoopFallback, decision: Option[LoopDecision]) extends LoopSimd
}

/** The SIMD candidate of a loop in the comparisons (ADR-0023): never silently a scalar value. */
sealed trait LoopCandidate extends Product with Serializable
object LoopCandidate {

  /** The W graph compiled; the code passed the verifier. */
  final case class Packed(compiled: ModuloCompiled) extends LoopCandidate

  /** The W graph fits no II: infeasible, with every II's outcome. */
  final case class Infeasible(decision: LoopDecision, attempts: Vector[Attempt])
      extends LoopCandidate

  /** Nothing to pack: no SIMD candidate; the scalar loop is measured, with the decision. */
  final case class NothingToPack(decision: LoopDecision, scalar: ModuloCompiled)
      extends LoopCandidate

  /** Not measured, for `reason`: W-reg (#78 Q8 (c)). */
  final case class NotMeasured(reason: String) extends LoopCandidate
}

/** A modulo-scheduled loop kernel and the program the verifier accepted. `fusion` says whether the
  * loop was fused into MAC (None on a machine without MAC); `simd` what it does with W.
  */
final case class ModuloCompiled(
    kernel: LoopKernel,
    machine: MachineDescription,
    layout: MemoryLayout,
    result: ModuloSchedule,
    bundles: Vector[Bundle],
    words: Vector[Long],
    fusion: Option[FusionChoice] = None,
    simd: LoopSimd = LoopSimd.Scalar
) {
  def graph: LoopGraph = result.graph
  def ii: Int = result.ii

  /** The cycles the schedule predicts: `h + 1` with HALT at h. */
  def cycles: Int = result.cycles

  /** MinAvg at the MII with ASAP times, the #78 estimate's bound (docs/COMPILER.md, "記録の定義"). */
  lazy val minAvgAtMii: Int = ModuloScheduler.minAvgAtMii(graph)

  /** MinAvg of the found schedule at its II. */
  lazy val minAvgAtIi: Int = ModuloScheduler.minAvg(graph, ii, result.times)

  /** The most registers live in one cycle of the unrolled program (a pair counts 2). */
  lazy val maxLive: Int = RegisterAllocator.maxLive(result.schedule)
}

/** Loop kernel to program by Modulo Scheduling (docs/COMPILER.md, "Modulo Scheduling", #68). */
object ModuloCompiler {

  /** The input of the II search: the layout of `kernel`, the fused scalar loop graph G (`scalar`,
    * fused into MAC when `fusion` says so; None on a machine without MAC), and the graph to
    * schedule first (`graph`): on a machine with LD.W, the doubled graph when the loop doubles
    * (`simd` is `Vectorized` and `doubled`), or the packed graph of its body when a store pair
    * packs (`Vectorized` and `packed`), otherwise G with the reason it runs as G.
    */
  final case class Prepared(
      kernel: LoopKernel,
      layout: MemoryLayout,
      graph: LoopGraph,
      fusion: Option[FusionChoice],
      scalar: LoopGraph,
      simd: LoopSimd
  )

  /** The doubling decision of G (docs/COMPILER.md): the reasons decided on G; without one, the
    * doubled graph, kept when its MII without the third read port is at most twice G's. The body of
    * a loop that is not doubled is packed by [[prepare]], so `body` is None here.
    */
  def decide(g: LoopGraph): (LoopDecision, Option[LoopGraph]) = {
    val scalarMII = Mii.of(g).withoutReadPort.mii
    LoopVector.reasons(g) match {
      case Vector() =>
        val v = LoopVector.double(g)
        val doubledMII = Mii.of(v).withoutReadPort.mii
        if (doubledMII <= 2 * scalarMII)
          (LoopDecision(Vector.empty, scalarMII, Some(doubledMII), None), Some(v))
        else
          (
            LoopDecision(Vector(NotDoubled.MiiIncrease), scalarMII, Some(doubledMII), None),
            None
          )
      case reasons => (LoopDecision(reasons, scalarMII, None, None), None)
    }
  }

  /** Layout and lowering as `loop.LoopLowering`, then the loop graph, fused by `FusionChoice` (G).
    * On ISA mvp-2 with LD.W, the loop vectorization decision ([[decide]]); the body of a loop that
    * is not doubled, for any reason (`MiiIncrease` too), is packed by `LoopPack` (ADR-0023's
    * "1反復の本体を同じ SLP の規則で詰めてコンパイルする"). With a store pair, the packed graph is scheduled; without
    * one, G runs with `NothingToPack`. The doubling and the body packing are never combined, and
    * body packing has no MII test (#110 第5段の Q4: オーナーが異を唱えなければ既定とする推奨で、決定ではない). On W-reg, G with
    * `NotImplemented` (#78 Q8 (c)).
    */
  def prepare(k: LoopKernel, machine: MachineDescription): Either[String, Prepared] =
    LoopLowering.layout(k, machine).map { layout =>
      val (g, fusion) = FusionChoice.of(LoopGraph.of(LoopLowering.lower(k, layout), machine))
      def scalar(simd: LoopSimd) = Prepared(k, layout, g, fusion, g, simd)
      if (!machine.hasVector) scalar(LoopSimd.Scalar)
      else if (!machine.hasWideMemory)
        scalar(LoopSimd.Fallback(LoopFallback.NotImplemented(LoopFallback.WReg), None))
      else
        decide(g) match {
          case (d, Some(v)) => Prepared(k, layout, v, fusion, g, LoopSimd.Vectorized(d))
          case (d, None)    =>
            val packing = LoopPack.pack(g)
            val packed = d.copy(body = Some(packing.body))
            if (packing.body.nothingToPack)
              scalar(LoopSimd.Fallback(LoopFallback.NothingToPack, Some(packed)))
            else Prepared(k, layout, packing.graph, fusion, g, LoopSimd.Vectorized(packed))
        }
    }

  /** [[prepare]], then the II search; the program passes the final verifier check
    * (`KernelCompiler.checked`) or it is a compiler error. A doubled or packed loop that fits no II
    * falls back to G (`NoIiFits`; a doubled loop does not try body packing, and a packed one tries
    * nothing else); when G fits no II either, the error gives both.
    */
  def compile(k: LoopKernel, machine: MachineDescription): Either[String, ModuloCompiled] =
    prepare(k, machine).flatMap { p =>
      p.simd match {
        case LoopSimd.Vectorized(d) =>
          ModuloScheduler.schedule(p.graph) match {
            case Right(s) => finish(p, s, p.simd)
            case Left(no) =>
              val what = if (d.doubled) "doubled" else "packed"
              scalar(p, LoopSimd.Fallback(LoopFallback.NoIiFits(no.attempts), Some(d))).left.map {
                e =>
                  if (e.startsWith("compiler error")) e
                  else s"$e; the $what loop fits no II either (${no.message})"
              }
          }
        case other => scalar(p, other)
      }
    }

  /** The SIMD candidate of `k` on the ISA mvp-2 machine `machine`, for the comparisons (ADR-0023):
    * the doubled or packed graph compiled, or infeasible with every II's outcome, or no candidate
    * when there is nothing to pack (then the scalar loop), or not measured (W-reg). Left on a
    * machine without W, when the scalar loop of a loop with nothing to pack fits no II, and on a
    * compiler error.
    */
  def candidate(k: LoopKernel, machine: MachineDescription): Either[String, LoopCandidate] =
    if (!machine.hasVector)
      Left(
        s"${machine.machineId} is ISA ${machine.isaVersion}, without W: no SIMD candidate for ${k.name} (${k.index})"
      )
    else
      prepare(k, machine).flatMap { p =>
        p.simd match {
          case LoopSimd.Vectorized(d) =>
            ModuloScheduler.schedule(p.graph) match {
              case Right(s) => finish(p, s, p.simd).map(LoopCandidate.Packed(_))
              case Left(no) => Right(LoopCandidate.Infeasible(d, no.attempts))
            }
          case LoopSimd.Fallback(LoopFallback.NotImplemented(what), _) =>
            Right(LoopCandidate.NotMeasured(what))
          case LoopSimd.Fallback(LoopFallback.NothingToPack, Some(d)) =>
            scalar(p, p.simd).map(LoopCandidate.NothingToPack(d, _))
          case other => Left(s"compiler error: ${k.name} (${k.index}) prepared as $other")
        }
      }

  /** G of `p` scheduled and compiled, marked `simd`. */
  private def scalar(p: Prepared, simd: LoopSimd): Either[String, ModuloCompiled] =
    ModuloScheduler
      .schedule(p.scalar)
      .left
      .map(no =>
        s"${p.kernel.name}: ${p.kernel.trips} iterations of the loop over ${p.kernel.index}: ${no.message}"
      )
      .flatMap(finish(p, _, simd))

  private def finish(
      p: Prepared,
      result: ModuloSchedule,
      simd: LoopSimd
  ): Either[String, ModuloCompiled] = {
    val bundles = result.allocation.bundles
    val machine = p.scalar.machine
    KernelCompiler
      .checked(machine, p.layout, bundles)
      .map(ModuloCompiled(p.kernel, machine, p.layout, result, bundles, _, p.fusion, simd))
  }

  /** The W outcome of `c` for messages and test cases (ISA mvp-2; "" on mvp-0 and mvp-1). The II
    * per original iteration is given for a doubled loop only: one iteration of a packed loop is one
    * original iteration.
    */
  def describeW(c: ModuloCompiled): String = {
    val name = s"${c.kernel.name} (${c.kernel.index})"
    c.simd match {
      case LoopSimd.Scalar        => ""
      case LoopSimd.Vectorized(d) =>
        val g = c.graph
        val each = g.ids(Place.Each)
        def count(p: LoopNode => Boolean) = each.count(n => p(g.nodes(n)))
        val lanes = count(_.isInstanceOf[LoopNode.StoreLane])
        def mnemonic(m: String) = count(_.mnemonic == m) - (if (m == "ST") lanes else 0)
        val ops = Seq("LD", "LD.W", "ADD", "SUB", "MUL", "MAC")
          .++(Seq("VADD", "VSUB", "VMUL", "VMAC", "ST.W", "ST"))
          .filter(mnemonic(_) > 0)
          .map(m => s"${mnemonic(m)} $m") ++ Option.when(lanes > 0)(s"$lanes lane ST")
        val fields = each.flatMap(n => LoopNode.fields(g.nodes(n)))
        val broadcasts = fields.count(_.isInstanceOf[VRef.Broadcast])
        val gathers = fields.collect { case x: VRef.Gather => x }.distinct.size
        val perIteration =
          if (!d.doubled) ""
          else if (c.ii % 2 == 0) s" (${c.ii / 2} per original iteration)"
          else s" (${c.ii / 2}.5 per original iteration)"
        val shape = if (d.doubled) s"${d.describe}," else s"${d.describe};"
        s"$name: W: $shape ${g.trips} iterations of II ${c.ii}$perIteration, " +
          s"per iteration ${ops.mkString(", ")}, $broadcasts broadcast" +
          s"${if (broadcasts == 1) "" else "s"}, $gathers gather${if (gathers == 1) "" else "s"}; " +
          s"MinAvg ${c.minAvgAtMii} at MII, ${c.minAvgAtIi} at II; MaxLive ${c.maxLive}"
      case LoopSimd.Fallback(r, d) =>
        s"$name: W: scalar fallback (${r.describe}${d.fold("")(x => s"; ${x.describe}")})"
    }
  }
}
