package vliw.compiler.dsl

/** Values of a kernel's inputs or outputs: arrays by name, and scalars by name. */
final case class Values(arrays: Map[String, Vector[Long]], scalars: Map[String, Long] = Map.empty)

/** Reference implementation of the DSL: evaluates a kernel directly, without memory layout or
  * instructions (docs/DSL.md, "意味"). Expected outputs of generated code come from here.
  */
object Interpreter {

  /** Checks that `in` gives every input and scalar of `k`, with the declared lengths. */
  def check(k: Kernel, in: Values): Either[String, Unit] = {
    val arrays = k.inputs.collectFirst {
      case a if !in.arrays.get(a.name).exists(_.size == a.length) =>
        s"input ${a.name} needs ${a.length} values"
    }
    val scalars = k.scalars.collectFirst {
      case s if !in.scalars.contains(s) => s"scalar $s has no value"
    }
    arrays.orElse(scalars).toLeft(())
  }

  def run(k: Kernel, in: Values): Either[String, Values] = check(k, in).map { _ =>
    def eval(e: Expr): Long = e match {
      case Expr.Elem(a, i)    => Word(in.arrays(a)(i))
      case Expr.Scalar(s)     => Word(in.scalars(s))
      case Expr.Const(v)      => v
      case Expr.Bin(op, a, b) => op(eval(a), eval(b))
    }
    val written = k.stores.map(s => (s.output, s.index) -> eval(s.value)).toMap
    Values(k.outputs.map(o => o.name -> Vector.tabulate(o.length)(i => written((o.name, i)))).toMap)
  }
}
