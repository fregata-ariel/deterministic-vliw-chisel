package vliw.compiler

import scala.util.Random

import vliw.compiler.dag.Dag
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

  def compile(
      kernel: Kernel,
      machine: MachineDescription,
      priorities: Seq[Priority] = Priority.all
  ): Either[String, CompiledKernel] =
    for {
      layout <- MemoryLayout.fixedOrder(kernel, machine)
      dag = Dag.of(Lowering.lower(layout), machine)
      allocated <- RegisterAllocator
        .scheduleAndAllocate(dag, priorities)
        .left
        .map(e => s"${kernel.name}: $e")
      bundles = allocated.allocation.bundles
      words <- checked(machine, layout, bundles)
    } yield CompiledKernel(kernel, machine, layout, dag, allocated, bundles, words)

  /** Encodes `bundles` and runs the independent verifier on them. Generated code that the verifier
    * rejects is a compiler error: nothing is returned for it.
    */
  def checked(
      machine: MachineDescription,
      layout: MemoryLayout,
      bundles: Vector[Bundle]
  ): Either[String, Vector[Long]] =
    new BundleCodec(machine)
      .encodeAll(bundles)
      .left
      .map(e => s"compiler error: ${layout.kernel.name} does not encode: $e")
      .flatMap { words =>
        val findings = Verifier.verify(machine, words, declaration(layout))
        Either.cond(
          findings.isEmpty,
          words,
          (s"compiler error: the verifier rejects the code generated for ${layout.kernel.name}" +:
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
    * expected cycles are the scheduler's prediction. `machinePath` is written when given.
    */
  def testCase(
      c: CompiledKernel,
      in: Values,
      machinePath: Option[String]
  ): Either[String, ujson.Obj] =
    for {
      image <- c.layout.memoryImage(in)
      out <- Interpreter.run(c.kernel, in)
    } yield {
      val expectedMemory = for {
        o <- c.kernel.outputs
        i <- 0 until o.length
      } yield c.layout.elem(o.name, i).toString -> ujson.Num(out.arrays(o.name)(i).toDouble)
      val doc = ujson.Obj(
        "format" -> TestCase.Format,
        "name" -> c.kernel.name,
        "description" -> s"generated from the DSL kernel ${c.kernel.name}; expected outputs from the DSL reference implementation, cycles predicted by the scheduler (${c.allocated.attempt})",
        "program" -> ujson.Arr.from(
          Disassembler.program(c.bundles).linesIterator.map(ujson.Str(_))
        ),
        "inputs" -> ujson.Obj(
          "registers" -> ujson.Obj(),
          "memory" -> ujson.Obj.from(image.toSeq.sortBy(_._1).map { case (a, v) =>
            a.toString -> ujson.Num(v.toDouble)
          })
        ),
        "expect" -> ujson.Obj(
          "verdict" -> "legal",
          "cycles" -> c.cycles,
          "memory" -> ujson.Obj.from(expectedMemory)
        )
      )
      machinePath.foreach(p => doc("machine") = p)
      doc
    }
}
