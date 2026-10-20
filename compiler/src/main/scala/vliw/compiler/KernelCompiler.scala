package vliw.compiler

import scala.util.Random

import vliw.compiler.dag.{Dag, Fusion}
import vliw.compiler.dsl._
import vliw.compiler.regalloc.{Allocated, RegisterAllocator}
import vliw.compiler.schedule.Priority
import vliw.isa._

/** A compiled kernel: every intermediate result, and the program the verifier accepted. */
final case class CompiledKernel(
    kernel: Kernel,
    machine: MachineDescription,
    layout: MemoryLayout,
    dag: Dag,
    allocated: Allocated,
    bundles: Vector[Bundle],
    words: Vector[Long]
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
    * when the machine has MAC (`dag.macs` pairs).
    */
  final case class Prepared(
      kernel: Kernel,
      machine: MachineDescription,
      layout: MemoryLayout,
      dag: Dag
  )

  def prepare(kernel: Kernel, machine: MachineDescription): Either[String, Prepared] =
    for {
      _ <- notImplemented(machine)
      layout <- MemoryLayout.fixedOrder(kernel, machine)
    } yield Prepared(kernel, machine, layout, Fusion.fuse(Dag.of(Lowering.lower(layout), machine)))

  /** ISA mvp-2 (W) comes to the compiler in #110 stages 4 and 5. Until then the compiler refuses
    * mvp-2 machines, rather than generate mvp-1 code under an mvp-2 machine's name.
    */
  def notImplemented(machine: MachineDescription): Either[String, Unit] =
    IsaVersion.notImplemented(machine, "the compiler", "stages 4-5").toLeft(())

  /** Encodes and verifies the code of `allocated` (`checked`). */
  def finish(p: Prepared, allocated: Allocated): Either[String, CompiledKernel] = {
    val bundles = allocated.allocation.bundles
    checked(p.machine, p.layout, bundles).map(words =>
      CompiledKernel(p.kernel, p.machine, p.layout, p.dag, allocated, bundles, words)
    )
  }

  /** Compiles with every attempt of `priorities` and keeps the one with the fewest cycles. */
  def compile(
      kernel: Kernel,
      machine: MachineDescription,
      priorities: Seq[Priority] = Priority.all,
      singleIssue: Boolean = false
  ): Either[String, CompiledKernel] =
    for {
      p <- prepare(kernel, machine)
      allocated <- RegisterAllocator
        .scheduleAndAllocate(p.dag, priorities, singleIssue)
        .left
        .map(e => s"${kernel.name}: $e")
      c <- finish(p, allocated)
    } yield c

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
    * defaults to the kernel's.
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
      s"generated from the DSL kernel ${c.kernel.name}; expected outputs from the DSL reference implementation, cycles predicted by the scheduler (${c.allocated.attempt})",
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
