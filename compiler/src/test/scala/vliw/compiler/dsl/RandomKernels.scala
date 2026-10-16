package vliw.compiler.dsl

import scala.util.Random

/** Random DSL kernels, made as crosscheck/RandomKernelSpec makes them: one or two inputs of 1 to 6
  * words, maybe a scalar, one or two outputs of 1 to 4 words, and expression trees of depth up to 3
  * over ADD, SUB and MUL with elements, scalars and constants.
  */
object RandomKernels {
  def kernel(rng: Random, id: Int): Kernel = Kernel(s"random$id") { k =>
    val ins = (0 until 1 + rng.nextInt(2)).map(i => k.input(s"in$i", 1 + rng.nextInt(6)))
    val scalar = Option.when(rng.nextBoolean())(k.scalar("s"))
    val outs = (0 until 1 + rng.nextInt(2)).map(i => k.output(s"out$i", 1 + rng.nextInt(4)))
    def leaf(): Expr = rng.nextInt(6) match {
      case 0 => k.const(Seq(0L, 1L, 3L, 0xffffffffL)(rng.nextInt(4)))
      case 1 => scalar.getOrElse(k.const(2L))
      case _ =>
        val a = ins(rng.nextInt(ins.size))
        a(rng.nextInt(a.length))
    }
    def expr(depth: Int): Expr =
      if (depth == 0 || rng.nextInt(4) == 0) leaf()
      else
        rng.nextInt(3) match {
          case 0 => expr(depth - 1) + expr(depth - 1)
          case 1 => expr(depth - 1) - expr(depth - 1)
          case _ => expr(depth - 1) * expr(depth - 1)
        }
    for (o <- outs; i <- 0 until o.length) o(i) = expr(3)
  }

  /** `n` kernels from the seed. */
  def kernels(seed: Long, n: Int): Vector[Kernel] = {
    val rng = new Random(seed)
    Vector.tabulate(n)(kernel(rng, _))
  }

  /** Inputs with full 32-bit values, so that results wrap. */
  def values(k: Kernel, rng: Random): Values =
    Values(
      k.inputs.map(a => a.name -> Vector.fill(a.length)(rng.nextLong() & Word.Mask)).toMap,
      k.scalars.map(_ -> (rng.nextLong() & Word.Mask)).toMap
    )
}
