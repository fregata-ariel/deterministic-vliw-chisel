package vliw.compiler.dsl

import vliw.isa.{Inputs, MachineDescription}

/** Scratchpad addresses of a kernel's data. The DSL names no address; the layout decides them.
  *
  * Every word the kernel reads (input elements, scalars, constants) is initialised memory, so the
  * host sets it before start and it is declared in M_in. Registers start undefined (R_in is empty).
  */
final case class MemoryLayout(
    kernel: Kernel,
    arrays: Map[String, Int],
    scalars: Map[String, Int],
    constants: Map[Long, Int],
    words: Int
) {
  def elem(array: String, index: Int): Int = arrays(array) + index
  def scalar(name: String): Int = scalars(name)
  def constant(value: Long): Int = constants(value)

  /** Addresses the host initialises before start (M_in): input elements, scalars and constants. */
  def initialized: Set[Int] =
    (kernel.inputs.flatMap(a => (0 until a.length).map(elem(a.name, _))) ++
      kernel.scalars.map(scalar) ++ constants.values).toSet

  /** Addresses of the output elements. */
  def outputAddresses: Set[Int] =
    kernel.outputs.flatMap(o => (0 until o.length).map(elem(o.name, _))).toSet

  /** Initial scratchpad contents for `in`: inputs, scalars and constants. */
  def memoryImage(in: Values): Either[String, Map[Int, Long]] =
    Interpreter.check(kernel, in).map { _ =>
      val elems = for {
        a <- kernel.inputs
        i <- 0 until a.length
      } yield elem(a.name, i) -> Word(in.arrays(a.name)(i))
      (elems ++ kernel.scalars.map(s => scalar(s) -> Word(in.scalars(s))) ++
        constants.map { case (v, addr) => addr -> v }).toMap
    }

  /** The declaration for a test case or a run: M_in is the memory image, R_in is empty. */
  def inputs(in: Values): Either[String, Inputs] = memoryImage(in).map(Inputs(Map.empty, _))

  /** Reads the outputs back from final scratchpad contents. */
  def outputs(memory: Int => Long): Values =
    Values(
      kernel.outputs
        .map(o => o.name -> Vector.tabulate(o.length)(i => memory(elem(o.name, i))))
        .toMap
    )
}

object MemoryLayout {

  /** Contiguous from address 0, in a fixed order: inputs and scalars in declaration order, then
    * constants in order of first appearance, then outputs in declaration order.
    */
  def fixedOrder(k: Kernel, machine: MachineDescription): Either[String, MemoryLayout] = {
    var next = 0
    def take(n: Int): Int = { val a = next; next += n; a }
    val arrays = k.inputs.map(a => a.name -> take(a.length)).toMap
    val scalars = k.scalars.map(s => s -> take(1)).toMap
    val constants = k.constants.map(v => v -> take(1)).toMap
    val outputs = k.outputs.map(o => o.name -> take(o.length)).toMap
    if (next > machine.scratchpadWords)
      Left(
        s"kernel ${k.name} needs $next words; the scratchpad of ${machine.machineId} has ${machine.scratchpadWords}"
      )
    else Right(MemoryLayout(k, arrays ++ outputs, scalars, constants, next))
  }
}
