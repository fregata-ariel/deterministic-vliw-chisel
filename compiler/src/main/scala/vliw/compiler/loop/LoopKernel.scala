package vliw.compiler.loop

import scala.collection.mutable

import vliw.compiler.dsl.{ArrayDecl, Expr, Kernel, Op, Word}

/** `stride * k + offset` for the iteration number k (docs/COMPILER.md, "ループ IR"). Stride 0 is the
  * same element in every iteration.
  */
final case class Index(stride: Int, offset: Int) {
  def at(k: Int): Int = stride * k + offset
  def +(c: Int): Index = Index(stride, offset + c)
  def -(c: Int): Index = Index(stride, offset - c)
  def *(c: Int): Index = Index(stride * c, offset * c)
}

/** A 32-bit value of a loop: like `dsl.Expr`, with affine indices and the loop's states. */
sealed trait LoopExpr extends Product with Serializable {
  def +(that: LoopExpr): LoopExpr = LoopExpr.Bin(Op.Add, this, that)
  def -(that: LoopExpr): LoopExpr = LoopExpr.Bin(Op.Sub, this, that)
  def *(that: LoopExpr): LoopExpr = LoopExpr.Bin(Op.Mul, this, that)
}
object LoopExpr {

  /** Element `index.at(k)` of input array `array`. */
  final case class Elem(array: String, index: Index) extends LoopExpr

  final case class Scalar(name: String) extends LoopExpr

  /** Constant, normalised to a 32-bit word. */
  final case class Const(value: Long) extends LoopExpr

  /** The value of state `name` at the start of the iteration. */
  final case class State(name: String) extends LoopExpr

  final case class Bin(op: Op, a: LoopExpr, b: LoopExpr) extends LoopExpr

  /** The same value in every iteration: no state and no element whose index changes. */
  def invariant(e: LoopExpr): Boolean = e match {
    case Elem(_, i)   => i.stride == 0
    case State(_)     => false
    case Bin(_, a, b) => invariant(a) && invariant(b)
    case _: Scalar    => true
    case _: Const     => true
  }
}

/** A value passed from each iteration to the next: `init` before the first iteration, then the
  * value of `next` in the iteration before.
  */
final case class LoopState(name: String, init: LoopExpr, next: LoopExpr)

/** `output(index.at(k)) = value` in every iteration k. */
final case class LoopStore(output: String, index: Index, value: LoopExpr)

/** `output(index) = ` the final value of `state`, after the last iteration. */
final case class FinalStore(output: String, index: Int, state: String)

/** A kernel with one counted loop (docs/COMPILER.md, "ループ IR"): iterations k = 0 until `trips`,
  * each one evaluating `stores` and the next values of `states`; then `finals` store the states'
  * values after the last iteration. `index` names the loop of the kernel that it stands for (for
  * example "i", the output loop, or "m", an accumulation). Inputs are read-only and outputs
  * write-only, as in `dsl.Kernel` (ADR-0010), so the order of the stores does not matter.
  */
final case class LoopKernel(
    name: String,
    index: String,
    trips: Int,
    inputs: Vector[ArrayDecl],
    scalars: Vector[String],
    outputs: Vector[ArrayDecl],
    states: Vector[LoopState],
    stores: Vector[LoopStore],
    finals: Vector[FinalStore]
) {

  /** `stateValues(k)(s)`: the value of state `s` at the start of iteration k, for k = 0 to trips
    * (the last one after the loop). A state's value is shared by reference with the values built
    * from it, so the trees grow linearly with the trips when a next value uses its states once.
    */
  lazy val stateValues: Vector[Vector[Expr]] = {
    val names = states.map(_.name)
    def values(row: Vector[Expr]): Map[String, Expr] = names.zip(row).toMap
    val first = states.map(s => LoopKernel.expr(s.init, 0, Map.empty))
    (0 until trips)
      .scanLeft(first)((row, k) => states.map(s => LoopKernel.expr(s.next, k, values(row))))
      .toVector
  }

  /** Distinct constants in order of first appearance: initial values, next values, stores. */
  def constants: Vector[Long] = {
    def walk(e: LoopExpr): Vector[Long] = e match {
      case LoopExpr.Const(v)     => Vector(v)
      case LoopExpr.Bin(_, a, b) => walk(a) ++ walk(b)
      case _                     => Vector.empty
    }
    (states.flatMap(s => walk(s.init) ++ walk(s.next)) ++ stores.flatMap(s =>
      walk(s.value)
    )).distinct
  }

  /** The kernel fully unrolled: the stores of every iteration in order, then the finals. Built with
    * the `dsl.Kernel` builder, so its checks apply (each output element exactly once).
    */
  lazy val unroll: Kernel = Kernel(name) { b =>
    inputs.foreach(a => b.input(a.name, a.length))
    scalars.foreach(b.scalar)
    val out = outputs.map(o => o.name -> b.output(o.name, o.length)).toMap
    val lengths = inputs.map(a => a.name -> a.length).toMap
    val seen = Expr.identitySet()
    def check(e: Expr): Unit = if (seen.add(e)) e match {
      case Expr.Elem(a, i) =>
        require(lengths.get(a).exists(n => 0 <= i && i < n), s"$a($i) is not an input element")
      case Expr.Bin(_, x, y) => check(x); check(y)
      case _                 => ()
    }
    val names = states.map(_.name)
    def assign(o: String, i: Int, e: Expr): Unit = { check(e); out(o)(i) = e }
    for (k <- 0 until trips; s <- stores)
      assign(s.output, s.index.at(k), LoopKernel.expr(s.value, k, names.zip(stateValues(k)).toMap))
    finals.foreach { f =>
      require(names.contains(f.state), s"unknown state ${f.state}")
      assign(f.output, f.index, stateValues(trips)(names.indexOf(f.state)))
    }
  }
}

object LoopKernel {

  /** `e` in iteration `k`, with `states` the states' values at the start of the iteration. */
  private def expr(e: LoopExpr, k: Int, states: Map[String, Expr]): Expr = e match {
    case LoopExpr.Elem(a, i)    => Expr.Elem(a, i.at(k))
    case LoopExpr.Scalar(s)     => Expr.Scalar(s)
    case LoopExpr.Const(v)      => Expr.Const(v)
    case LoopExpr.State(s)      => states(s)
    case LoopExpr.Bin(op, a, b) => Expr.Bin(op, expr(a, k, states), expr(b, k, states))
  }

  /** Builds a loop kernel of `trips` iterations. `build` declares the arrays, scalars and states,
    * gives the loop body once with `k.body`, and stores the final states outside the body. Throws
    * IllegalArgumentException for a malformed kernel (docs/DSL.md, "ループ IR").
    */
  def apply(name: String, index: String, trips: Int)(build: Builder => Unit): LoopKernel = {
    require(trips >= 1, s"$name has $trips iterations")
    val b = new Builder(trips)
    build(b)
    b.result(name, index)
  }

  final class InputArray private[LoopKernel] (val name: String, val length: Int, trips: Int) {
    def apply(i: Index): LoopExpr = {
      Seq(0, trips - 1).foreach { k =>
        require(
          0 <= i.at(k) && i.at(k) < length,
          s"$name(${i.at(k)}) in iteration $k is outside 0 until $length"
        )
      }
      LoopExpr.Elem(name, i)
    }
    def apply(i: Int): LoopExpr = apply(Index(0, i))
  }

  final class OutputArray private[LoopKernel] (
      val name: String,
      val length: Int,
      builder: Builder
  ) {

    /** In the body: `out(i) = value` in every iteration. */
    def update(i: Index, value: LoopExpr): Unit = builder.store(name, length, i, value)
    def update(i: Int, value: LoopExpr): Unit = update(Index(0, i), value)

    /** Outside the body: `out(i) = ` the final value of `state`. */
    def update(i: Int, state: StateVar): Unit = builder.storeFinal(name, length, i, state)
  }

  /** A state of the loop. `value` is its value at the start of the iteration; `:=` gives its next
    * value, once, in the body.
    */
  final class StateVar private[LoopKernel] (val name: String, builder: Builder) {
    val value: LoopExpr = LoopExpr.State(name)
    def :=(next: LoopExpr): Unit = builder.update(name, next)
  }

  final class Builder private[LoopKernel] (trips: Int) {
    private val names = mutable.LinkedHashSet.empty[String]
    private val inputs = Vector.newBuilder[ArrayDecl]
    private val scalars = Vector.newBuilder[String]
    private val outputs = Vector.newBuilder[ArrayDecl]
    private val states = mutable.LinkedHashMap.empty[String, LoopExpr]
    private val nexts = mutable.LinkedHashMap.empty[String, LoopExpr]
    private val stores = Vector.newBuilder[LoopStore]
    private val finals = Vector.newBuilder[FinalStore]
    private var inBody = false
    private var bodies = 0

    private def declare(name: String): Unit = {
      require(name.matches("[a-z][a-z0-9_]*"), s"invalid name: $name")
      require(names.add(name), s"$name is declared twice")
    }

    def input(name: String, length: Int): InputArray = {
      declare(name)
      require(length >= 1, s"$name has length $length")
      inputs += ArrayDecl(name, length)
      new InputArray(name, length, trips)
    }

    def scalar(name: String): LoopExpr = {
      declare(name)
      scalars += name
      LoopExpr.Scalar(name)
    }

    def output(name: String, length: Int): OutputArray = {
      declare(name)
      require(length >= 1, s"$name has length $length")
      outputs += ArrayDecl(name, length)
      new OutputArray(name, length, this)
    }

    def const(value: Long): LoopExpr = LoopExpr.Const(Word(value))

    /** A state whose value before the first iteration is `init`, which must be invariant. */
    def state(name: String, init: LoopExpr): StateVar = {
      require(!inBody, s"state $name is declared in the body")
      declare(name)
      require(
        LoopExpr.invariant(init),
        s"the initial value of state $name changes with the iteration"
      )
      states(name) = init
      new StateVar(name, this)
    }

    /** The loop body, given once. `i` is the iteration number as an index: `x(i + 1)`, `a(i * 3)`.
      */
    def body(f: Index => Unit): Unit = {
      require(bodies == 0, "the body is given twice")
      bodies += 1
      inBody = true
      try f(Index(1, 0))
      finally inBody = false
    }

    private[LoopKernel] def store(out: String, length: Int, i: Index, value: LoopExpr): Unit = {
      require(inBody, s"$out: outside the body, only a final state can be stored")
      Seq(0, trips - 1).foreach { k =>
        require(
          0 <= i.at(k) && i.at(k) < length,
          s"$out(${i.at(k)}) in iteration $k is outside 0 until $length"
        )
      }
      stores += LoopStore(out, i, value)
    }

    private[LoopKernel] def storeFinal(out: String, length: Int, i: Int, s: StateVar): Unit = {
      require(!inBody, s"$out: a final state is stored outside the body")
      require(0 <= i && i < length, s"$out($i) is outside 0 until $length")
      finals += FinalStore(out, i, s.name)
    }

    private[LoopKernel] def update(name: String, next: LoopExpr): Unit = {
      require(inBody, s"state $name is updated outside the body")
      require(!nexts.contains(name), s"state $name is updated twice")
      // An operation: so the next value is computed in each iteration and has a node of its own.
      require(
        next.isInstanceOf[LoopExpr.Bin] && !LoopExpr.invariant(next),
        s"the next value of state $name must be an operation that changes with the iteration"
      )
      nexts(name) = next
    }

    private[LoopKernel] def result(name: String, index: String): LoopKernel = {
      require(index.matches("[a-z][a-z0-9_]*"), s"invalid loop index: $index")
      require(bodies == 1, s"$name has no body")
      val missing = states.keys.filterNot(nexts.contains)
      require(missing.isEmpty, s"state ${missing.mkString(", ")} is not updated in the body")
      val known = states.keySet.toSet
      def refs(e: LoopExpr): Set[String] = e match {
        case LoopExpr.State(s)     => Set(s)
        case LoopExpr.Bin(_, a, b) => refs(a) ++ refs(b)
        case _                     => Set.empty
      }
      val read = (nexts.values ++ stores.result().map(_.value)).flatMap(refs).toSet
      require((read -- known).isEmpty, s"unknown state ${(read -- known).mkString(", ")}")
      // Otherwise the state passes nothing between iterations, and its initial value is unused.
      val unread = known -- read
      require(unread.isEmpty, s"state ${unread.mkString(", ")} is never read in the body")
      val k = LoopKernel(
        name,
        index,
        trips,
        inputs.result(),
        scalars.result(),
        outputs.result(),
        states.map { case (s, init) => LoopState(s, init, nexts(s)) }.toVector,
        stores.result(),
        finals.result()
      )
      k.unroll // the checks of dsl.Kernel: every output element exactly once
      k
    }
  }
}
