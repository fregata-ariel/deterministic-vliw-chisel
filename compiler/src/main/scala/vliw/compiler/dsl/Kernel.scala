package vliw.compiler.dsl

import scala.collection.mutable

/** Binary operation of the DSL; each one maps to the ISA operation of the same mnemonic. */
sealed abstract class Op(val mnemonic: String) extends Product with Serializable {
  def apply(a: Long, b: Long): Long
}
object Op {
  case object Add extends Op("ADD") { def apply(a: Long, b: Long): Long = (a + b) & Word.Mask }
  case object Sub extends Op("SUB") { def apply(a: Long, b: Long): Long = (a - b) & Word.Mask }
  case object Mul extends Op("MUL") { def apply(a: Long, b: Long): Long = (a * b) & Word.Mask }
}

/** 32-bit words are held as Long in 0 until 2^32. */
object Word {
  val Mask: Long = 0xffffffffL
  def apply(v: Long): Long = v & Mask
}

/** A 32-bit value computed by the kernel (docs/DSL.md). It names no register, cycle or address:
  * register allocation, scheduling and memory layout are later steps.
  */
sealed trait Expr extends Product with Serializable {
  def +(that: Expr): Expr = Expr.Bin(Op.Add, this, that)
  def -(that: Expr): Expr = Expr.Bin(Op.Sub, this, that)
  def *(that: Expr): Expr = Expr.Bin(Op.Mul, this, that)
}
object Expr {

  /** Element `index` of input array `array`. */
  final case class Elem(array: String, index: Int) extends Expr

  /** Scalar input `name`. */
  final case class Scalar(name: String) extends Expr

  /** Constant, normalised to a 32-bit word. */
  final case class Const(value: Long) extends Expr

  final case class Bin(op: Op, a: Expr, b: Expr) extends Expr

  /** A set of expressions by reference, to visit a shared subtree once (a loop kernel unrolled by
    * `loop.LoopKernel` shares the states' values between iterations).
    */
  def identitySet(): java.util.Set[Expr] =
    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap[Expr, java.lang.Boolean])
}

final case class ArrayDecl(name: String, length: Int)

/** `output(index) = value`. */
final case class Store(output: String, index: Int, value: Expr)

/** A fixed-size integer kernel: inputs, scalar inputs, outputs, and one store per output element.
  * Inputs are read-only and outputs write-only, so no two memory operations of a kernel alias.
  */
final case class Kernel(
    name: String,
    inputs: Vector[ArrayDecl],
    scalars: Vector[String],
    outputs: Vector[ArrayDecl],
    stores: Vector[Store]
) {

  /** Distinct constants in order of first appearance (stores in order, operands left to right). A
    * subtree shared by reference is visited once, which does not change the order.
    */
  def constants: Vector[Long] = {
    val seen = Expr.identitySet()
    val found = Vector.newBuilder[Long]
    def walk(e: Expr): Unit = if (seen.add(e)) e match {
      case Expr.Const(v)     => found += v; ()
      case Expr.Bin(_, a, b) => walk(a); walk(b)
      case _                 => ()
    }
    stores.foreach(s => walk(s.value))
    found.result().distinct
  }
}

object Kernel {

  /** Builds a kernel; `body` declares arrays and assigns outputs, usually inside Scala `for` loops.
    * Throws IllegalArgumentException for a malformed kernel (docs/DSL.md, "制約").
    */
  def apply(name: String)(body: Builder => Unit): Kernel = {
    val b = new Builder
    body(b)
    b.result(name)
  }

  final class InputArray private[Kernel] (val name: String, val length: Int) {
    def apply(i: Int): Expr = {
      require(0 <= i && i < length, s"$name($i) is outside 0 until $length")
      Expr.Elem(name, i)
    }
  }

  final class OutputArray private[Kernel] (
      val name: String,
      val length: Int,
      builder: Builder
  ) {

    /** `out(i) = value`. */
    def update(i: Int, value: Expr): Unit = {
      require(0 <= i && i < length, s"$name($i) is outside 0 until $length")
      builder.store(Store(name, i, value))
    }
  }

  final class Builder private[Kernel] {
    private val names = mutable.LinkedHashSet.empty[String]
    private val inputs = Vector.newBuilder[ArrayDecl]
    private val scalars = Vector.newBuilder[String]
    private val outputs = mutable.ArrayBuffer.empty[ArrayDecl]
    private val stores = mutable.ArrayBuffer.empty[Store]

    private def declare(name: String): Unit = {
      require(name.matches("[a-z][a-z0-9_]*"), s"invalid name: $name")
      require(names.add(name), s"$name is declared twice")
    }

    def input(name: String, length: Int): InputArray = {
      declare(name)
      require(length >= 1, s"$name has length $length")
      inputs += ArrayDecl(name, length)
      new InputArray(name, length)
    }

    def scalar(name: String): Expr = {
      declare(name)
      scalars += name
      Expr.Scalar(name)
    }

    def output(name: String, length: Int): OutputArray = {
      declare(name)
      require(length >= 1, s"$name has length $length")
      outputs += ArrayDecl(name, length)
      new OutputArray(name, length, this)
    }

    def const(value: Long): Expr = Expr.Const(Word(value))

    private[Kernel] def store(s: Store): Unit = {
      require(
        !stores.exists(o => o.output == s.output && o.index == s.index),
        s"${s.output}(${s.index}) is assigned twice"
      )
      stores += s
    }

    private[Kernel] def result(name: String): Kernel = {
      require(name.matches("[a-z][a-z0-9_]*"), s"invalid kernel name: $name")
      outputs.foreach { o =>
        val missing =
          (0 until o.length).filterNot(i => stores.exists(s => s.output == o.name && s.index == i))
        require(missing.isEmpty, s"${o.name} is not assigned at ${missing.mkString(", ")}")
      }
      Kernel(name, inputs.result(), scalars.result(), outputs.toVector, stores.toVector)
    }
  }
}
