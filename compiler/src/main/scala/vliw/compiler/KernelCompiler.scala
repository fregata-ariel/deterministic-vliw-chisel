package vliw.compiler

import scala.util.Random

import vliw.compiler.dag.{Dag, Fusion, Pack, Packing}
import vliw.compiler.dsl._
import vliw.compiler.regalloc.{Allocated, Attempt, NoFit, RegisterAllocator}
import vliw.compiler.schedule.Priority
import vliw.isa._

/** What a compiled kernel does with W (ISA mvp-2, ADR-0023; docs/COMPILER.md "ISA mvp-2（W）"). */
sealed trait Simd extends Product with Serializable
object Simd {

  /** An mvp-0 or mvp-1 machine: no W. */
  case object Scalar extends Simd

  /** The packed DAG was compiled; `packing.scalarLanes` are the lane fallbacks. */
  final case class Packed(packing: Packing) extends Simd

  /** The kernel fallback: the fused scalar DAG (`packing.scalar`) was compiled instead, for
    * `reason`. The code uses no W operation.
    */
  final case class Fallback(reason: FallbackReason, packing: Packing) extends Simd
}

/** Why a kernel fell back to its scalar DAG (ADR-0023). */
sealed trait FallbackReason extends Product with Serializable {
  def describe: String
}
object FallbackReason {

  /** No store pair packs (ADR-0023's name). */
  case object NothingToPack extends FallbackReason {
    def describe: String = "NothingToPack"
  }

  /** Every attempt of the packed DAG failed (`NoAlignedPair`, `TooManyLive`, `Stuck`). */
  final case class NoAttemptFits(failures: Vector[(Attempt, NoFit)]) extends FallbackReason {
    def describe: String =
      s"every attempt of the packed DAG failed (${KernelCompiler.failureCounts(failures)})"
  }
}

/** The SIMD candidate of a kernel in the comparisons (ADR-0023): never silently replaced by the
  * scalar code.
  */
sealed trait SimdCandidate extends Product with Serializable
object SimdCandidate {

  /** The packed DAG compiled; the code passed the verifier. */
  final case class Packed(compiled: CompiledKernel) extends SimdCandidate

  /** Every attempt of the packed DAG failed: the candidate is infeasible, with each failure. */
  final case class Infeasible(packing: Packing, failures: Vector[(Attempt, NoFit)])
      extends SimdCandidate

  /** No store pair packs, so there is no SIMD candidate: the scalar code is measured, with the
    * reason.
    */
  final case class NothingToPack(packing: Packing, scalar: CompiledKernel) extends SimdCandidate
}

/** A compiled kernel: every intermediate result, and the program the verifier accepted. `dag` is
  * the DAG scheduled (before the LD copies of rematerialization): on ISA mvp-2 the packed DAG when
  * `simd` is `Packed`, the fused scalar DAG on a fallback.
  */
final case class CompiledKernel(
    kernel: Kernel,
    machine: MachineDescription,
    layout: MemoryLayout,
    dag: Dag,
    allocated: Allocated,
    bundles: Vector[Bundle],
    words: Vector[Long],
    simd: Simd = Simd.Scalar
) {

  /** The cycles the scheduler predicts: `h + 1` with HALT at h. */
  def cycles: Int = allocated.schedule.cycles
}

/** DSL kernel to program (docs/COMPILER.md, "コード生成と最終検証"). */
object KernelCompiler {

  /** The declaration of a compiled kernel: M_in is the layout's initialised words, R_in is empty.
    */
  def declaration(layout: MemoryLayout): Verifier.Declaration =
    Verifier.Declaration(Set.empty, layout.initialized)

  /** The input of scheduling: the layout of `kernel` and its DAG, with MUL + ADD fused into MAC
    * when the machine has MAC (`dag.macs` pairs). On ISA mvp-2, `packing` is the DAG packed into W
    * pairs (`Pack`); `dag` stays the fused scalar DAG.
    */
  final case class Prepared(
      kernel: Kernel,
      machine: MachineDescription,
      layout: MemoryLayout,
      dag: Dag,
      packing: Option[Packing] = None
  )

  def prepare(kernel: Kernel, machine: MachineDescription): Either[String, Prepared] =
    MemoryLayout.fixedOrder(kernel, machine).map { layout =>
      val dag = Fusion.fuse(Dag.of(Lowering.lower(layout), machine))
      Prepared(kernel, machine, layout, dag, Option.when(machine.hasVector)(Pack.pack(dag)))
    }

  /** Encodes and verifies the code of `allocated`, a schedule of `dag` (`checked`). */
  def finish(
      p: Prepared,
      allocated: Allocated,
      dag: Dag,
      simd: Simd
  ): Either[String, CompiledKernel] = {
    val bundles = allocated.allocation.bundles
    checked(p.machine, p.layout, bundles).map(words =>
      CompiledKernel(p.kernel, p.machine, p.layout, dag, allocated, bundles, words, simd)
    )
  }

  /** [[finish]] of the fused scalar DAG of `p`. */
  def finish(p: Prepared, allocated: Allocated): Either[String, CompiledKernel] =
    finish(p, allocated, p.dag, Simd.Scalar)

  /** Compiles with every attempt of `priorities` and keeps the one with the fewest cycles. On ISA
    * mvp-2 (normal use, ADR-0023), the packed DAG; when nothing packs or every attempt of it fails,
    * the fused scalar DAG instead, with the fallback and its reason in `simd`. A kernel that fits
    * as a scalar kernel therefore always compiles.
    */
  def compile(
      kernel: Kernel,
      machine: MachineDescription,
      priorities: Seq[Priority] = Priority.all,
      singleIssue: Boolean = false
  ): Either[String, CompiledKernel] =
    prepare(kernel, machine).flatMap { p =>
      p.packing match {
        case None          => scalar(p, priorities, singleIssue, Simd.Scalar)
        case Some(packing) =>
          simdCandidate(p, packing, priorities, singleIssue).flatMap {
            case SimdCandidate.Packed(c)               => Right(c)
            case SimdCandidate.NothingToPack(_, c)     => Right(c)
            case SimdCandidate.Infeasible(_, failures) =>
              val reason = FallbackReason.NoAttemptFits(failures)
              scalar(p, priorities, singleIssue, Simd.Fallback(reason, packing)).left.map { e =>
                if (e.startsWith("compiler error")) e
                else s"$e; the packed DAG fits no attempt either (${failureCounts(failures)})"
              }
          }
      }
    }

  /** The SIMD candidate of `kernel` on the ISA mvp-2 machine `machine`, for the comparisons
    * (ADR-0023): the packed DAG compiled, or infeasible with the failure of every attempt, or no
    * candidate when nothing packs (then the scalar code). Left on a machine without W, when the
    * scalar code of a kernel with nothing to pack does not fit, and on a compiler error.
    */
  def candidate(
      kernel: Kernel,
      machine: MachineDescription,
      priorities: Seq[Priority] = Priority.all,
      singleIssue: Boolean = false
  ): Either[String, SimdCandidate] =
    if (!machine.hasVector)
      Left(
        s"${machine.machineId} is ISA ${machine.isaVersion}, without W: no SIMD candidate for ${kernel.name}"
      )
    else
      prepare(kernel, machine).flatMap(p =>
        simdCandidate(p, p.packing.getOrElse(Pack.pack(p.dag)), priorities, singleIssue)
      )

  private def simdCandidate(
      p: Prepared,
      packing: Packing,
      priorities: Seq[Priority],
      singleIssue: Boolean
  ): Either[String, SimdCandidate] =
    if (packing.nothingToPack)
      scalar(p, priorities, singleIssue, Simd.Fallback(FallbackReason.NothingToPack, packing))
        .map(SimdCandidate.NothingToPack(packing, _))
    else {
      val tried = RegisterAllocator.allAttempts(packing.dag, priorities, singleIssue)
      RegisterAllocator.best(tried) match {
        case Some(a) =>
          finish(p, a, packing.dag, Simd.Packed(packing)).map(SimdCandidate.Packed(_))
        case None =>
          Right(
            SimdCandidate.Infeasible(packing, tried.collect { case (at, Left(no)) => at -> no })
          )
      }
    }

  /** The fused scalar DAG of `p` compiled, marked `simd`. */
  private def scalar(
      p: Prepared,
      priorities: Seq[Priority],
      singleIssue: Boolean,
      simd: Simd
  ): Either[String, CompiledKernel] =
    for {
      allocated <- RegisterAllocator
        .scheduleAndAllocate(p.dag, priorities, singleIssue)
        .left
        .map(e => s"${p.kernel.name}: $e")
      c <- finish(p, allocated, p.dag, simd)
    } yield c

  /** The failures by name, in the order of `NoFit`'s reasons: "TooManyLive 2, Stuck 6". */
  def failureCounts(failures: Seq[(Attempt, NoFit)]): String = {
    val counts = failures.groupMapReduce(_._2.name)(_ => 1)(_ + _)
    Seq("TooManyLive", "NoAlignedPair", "Stuck")
      .filter(counts.contains)
      .map(n => s"$n ${counts(n)}")
      .mkString(", ")
  }

  /** The W outcome of `c` for messages and test cases (ISA mvp-2; "" on mvp-0 and mvp-1). Output
    * words are named by their array and index.
    */
  def describeW(c: CompiledKernel): String = {
    def word(a: Int) = c.kernel.outputs
      .collectFirst {
        case o if a >= c.layout.elem(o.name, 0) && a < c.layout.elem(o.name, 0) + o.length =>
          s"${o.name}(${a - c.layout.elem(o.name, 0)}) [$a]"
      }
      .getOrElse(s"[$a]")
    def lanes(p: Packing) =
      if (p.scalarLanes.isEmpty) "no scalar lanes"
      else
        p.scalarLanes
          .map(l => s"scalar lane ${word(l.addr)} (${l.reason.describe})")
          .mkString("; ")
    c.simd match {
      case Simd.Scalar    => ""
      case Simd.Packed(p) =>
        val wide = c.machine.hasWideMemory
        val ops =
          Seq("VADD", "VSUB", "VMUL", "VMAC").filter(p.count(_) > 0).map(m => s"${p.count(m)} $m")
        val memory =
          if (wide) Seq(s"${p.count("LD.W")} LD.W", s"${p.count("ST.W")} ST.W")
          else Seq(s"${p.laneStores} lane STs")
        val shapes = Seq(
          s"${p.broadcasts} broadcast${if (p.broadcasts == 1) "" else "s"}",
          s"${p.gathers} gather${if (p.gathers == 1) "" else "s"}"
        ) ++ Option.when(p.copies > 0)(s"${p.copies} LD copies")
        s"W: ${p.pairs.size} output pairs packed (${(ops ++ memory ++ shapes).mkString(", ")}), ${lanes(p)}"
      case Simd.Fallback(r, p) =>
        val why = r match {
          case FallbackReason.NothingToPack => s"(NothingToPack: ${lanes(p)})"
          case other                        => other.describe
        }
        s"W: scalar fallback, $why"
    }
  }

  /** Encodes `bundles` and runs the independent verifier on them. Generated code that the verifier
    * rejects is a compiler error: nothing is returned for it.
    */
  def checked(
      machine: MachineDescription,
      layout: MemoryLayout,
      bundles: Vector[Bundle]
  ): Either[String, Vector[Long]] =
    verified(machine, layout.kernel.name, declaration(layout), bundles)

  /** [[checked]] with the declaration given: for code whose inputs are not a kernel's layout. */
  def verified(
      machine: MachineDescription,
      name: String,
      declaration: Verifier.Declaration,
      bundles: Vector[Bundle]
  ): Either[String, Vector[Long]] =
    new BundleCodec(machine)
      .encodeAll(bundles)
      .left
      .map(e => s"compiler error: $name does not encode: $e")
      .flatMap { words =>
        val findings = Verifier.verify(machine, words, declaration)
        Either.cond(
          findings.isEmpty,
          words,
          (s"compiler error: the verifier rejects the code generated for $name" +:
            findings.map(f => s"  $f")).mkString("\n")
        )
      }

  /** Deterministic inputs with full 32-bit values, for test cases and examples. */
  def sampleInputs(kernel: Kernel, seed: Long): Values = {
    val rng = new Random(seed)
    Values(
      kernel.inputs.map(a => a.name -> Vector.fill(a.length)(rng.nextLong() & Word.Mask)).toMap,
      kernel.scalars.map(_ -> (rng.nextLong() & Word.Mask)).toMap
    )
  }

  /** A `vliw-testcase/1` test case (docs/TESTCASE.md) for `c` with inputs `in`. The expected
    * outputs come from the DSL reference implementation, not from the generated code, and the
    * expected cycles are the scheduler's prediction. `machinePath` is written when given; `name`
    * defaults to the kernel's. On ISA mvp-2 the description also gives the W outcome.
    */
  def testCase(
      c: CompiledKernel,
      in: Values,
      machinePath: Option[String],
      name: Option[String] = None
  ): Either[String, ujson.Obj] =
    testCaseOf(
      c.kernel,
      c.layout,
      c.bundles,
      c.cycles,
      s"generated from the DSL kernel ${c.kernel.name}; expected outputs from the DSL reference implementation, cycles predicted by the scheduler (${c.allocated.attempt})" +
        Option(describeW(c)).filter(_.nonEmpty).fold("")(w => s"; $w"),
      in,
      machinePath,
      name.getOrElse(c.kernel.name)
    )

  /** The test case of a program for `kernel` in `layout` that the scheduler predicts to take
    * `cycles`; the expected outputs come from the DSL reference implementation.
    */
  def testCaseOf(
      kernel: Kernel,
      layout: MemoryLayout,
      bundles: Vector[Bundle],
      cycles: Int,
      description: String,
      in: Values,
      machinePath: Option[String],
      name: String
  ): Either[String, ujson.Obj] =
    for {
      image <- layout.memoryImage(in)
      out <- Interpreter.run(kernel, in)
    } yield {
      val expectedMemory = for {
        o <- kernel.outputs
        i <- 0 until o.length
      } yield layout.elem(o.name, i).toString -> ujson.Num(out.arrays(o.name)(i).toDouble)
      val doc = ujson.Obj(
        "format" -> TestCase.Format,
        "name" -> ujson.Str(name),
        "description" -> description,
        "program" -> ujson.Arr.from(
          Disassembler.program(bundles).linesIterator.map(ujson.Str(_))
        ),
        "inputs" -> ujson.Obj(
          "registers" -> ujson.Obj(),
          "memory" -> ujson.Obj.from(image.toSeq.sortBy(_._1).map { case (a, v) =>
            a.toString -> ujson.Num(v.toDouble)
          })
        ),
        "expect" -> ujson.Obj(
          "verdict" -> "legal",
          "cycles" -> cycles,
          "memory" -> ujson.Obj.from(expectedMemory)
        )
      )
      machinePath.foreach(p => doc("machine") = p)
      doc
    }
}
