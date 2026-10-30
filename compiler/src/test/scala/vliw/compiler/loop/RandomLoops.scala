package vliw.compiler.loop

import scala.util.Random

import vliw.compiler.dsl.Op

/** Random loop kernels, for the loop graph and its fusion into MAC (`LoopGraphSpec`,
  * `LoopFusionSpec`).
  */
object RandomLoops {
  private def word(rng: Random) = rng.nextLong() & 0xffffffffL

  /** Random loop kernels: strides -2 to 2, 1 to 6 iterations, up to two states (each next value
    * reads its own state and may read the other), stores in the body and finals. Returns whether
    * each array is read with one stride, so that the loop loads each word once.
    */
  def kernel(rng: Random, id: Int): (LoopKernel, Boolean) = {
    val trips = 1 + rng.nextInt(6)
    val length = 2 * trips + 4
    def some(from: Seq[Int]) = Seq.fill(1 + rng.nextInt(2))(from(rng.nextInt(from.size))).distinct
    val strides = Map("x" -> some(Seq(-2, -1, 1, 2)), "y" -> some(Seq(-2, -1, 0, 1, 2)))
    val states = Seq("s0", "s1").take(rng.nextInt(3))
    val ops = Seq(Op.Add, Op.Sub, Op.Mul)
    val k = LoopKernel(s"rand$id", "i", trips) { b =>
      val in = strides.keys.toSeq.sorted.map(a => a -> b.input(a, length)).toMap
      val a = b.scalar("a")
      def elem(name: String, s: Int): LoopExpr = {
        val lo = if (s < 0) -s * (trips - 1) else 0
        val hi = if (s > 0) length - 1 - s * (trips - 1) else length - 1
        in(name)(Index(s, lo + rng.nextInt(hi - lo + 1)))
      }
      def leaf(invariant: Boolean): LoopExpr = {
        val elems =
          for ((n, ss) <- strides.toSeq.sortBy(_._1); s <- ss if !invariant || s == 0)
            yield (() => elem(n, s))
        val all =
          elems ++ Seq(() => a, () => b.const(word(rng)), () => b.const(rng.nextInt(4).toLong))
        all(rng.nextInt(all.size))()
      }
      def expr(depth: Int, invariant: Boolean): LoopExpr =
        if (depth == 0 || rng.nextInt(3) == 0) leaf(invariant)
        else
          LoopExpr.Bin(ops(rng.nextInt(3)), expr(depth - 1, invariant), expr(depth - 1, invariant))
      def varying(depth: Int): LoopExpr =
        LoopExpr.Bin(
          ops(rng.nextInt(3)),
          elem("x", strides("x").head),
          expr(depth, invariant = false)
        )
      def withState(e: LoopExpr): LoopExpr =
        if (states.isEmpty || rng.nextBoolean()) e
        else {
          val s = states(rng.nextInt(states.size))
          if (rng.nextBoolean()) LoopExpr.Bin(ops(rng.nextInt(3)), LoopExpr.State(s), e)
          else LoopExpr.Bin(ops(rng.nextInt(3)), e, LoopExpr.State(s))
        }
      val vars = states.map(s => b.state(s, expr(2, invariant = true)))
      val out = if (states.isEmpty || rng.nextBoolean()) Some(b.output("out", trips)) else None
      val w = if (rng.nextBoolean()) Some(b.output("w", 2 * trips)) else None
      val fin = if (states.nonEmpty) Some(b.output("fin", states.size)) else None
      b.body { i =>
        vars.foreach(v => v := LoopExpr.Bin(ops(rng.nextInt(3)), v.value, withState(varying(2))))
        out.foreach { o =>
          if (rng.nextBoolean()) o(i) = withState(expr(3, invariant = false))
          else o(i * -1 + (trips - 1)) = withState(expr(3, invariant = false))
        }
        w.foreach { o =>
          o(i * 2) = withState(expr(2, invariant = false))
          o(i * 2 + 1) = withState(expr(2, invariant = false))
        }
      }
      fin.foreach(o => vars.zipWithIndex.foreach { case (v, j) => o(j) = v })
    }
    (k, strides.values.forall(_.size == 1))
  }

  /** `n` kernels from the seed. */
  def kernels(seed: Long, n: Int): Vector[(LoopKernel, Boolean)] = {
    val rng = new Random(seed)
    Vector.tabulate(n)(kernel(rng, _))
  }

  /** A random loop that tends to double (ISA mvp-2, #110 stage 5): no state, `trips` iterations,
    * reads of two inputs with strides mostly 1 (and −1, 2), values before the loop mostly at a
    * multiplication, `out(i)` or reversed, and with probability 1/3 also `w(2i)` and `w(2i+1)`.
    */
  def vectorizableKernel(rng: Random, id: Int, trips: Int): LoopKernel = {
    val n = 2 * trips + 4
    val ops = Seq(Op.Add, Op.Sub, Op.Mul)
    LoopKernel(s"vec$id", "i", trips) { b =>
      val arrays = Seq(b.input("x", n), b.input("y", n))
      val a = b.scalar("a")
      def elem(): LoopExpr = {
        val s = Seq(1, 1, 1, 1, -1, 2)(rng.nextInt(6))
        val lo = if (s < 0) -s * (trips - 1) else 0
        val hi = if (s > 0) n - 1 - s * (trips - 1) else n - 1
        arrays(rng.nextInt(2))(Index(s, lo + rng.nextInt(hi - lo + 1)))
      }
      def invariant(): LoopExpr = if (rng.nextBoolean()) a else b.const(rng.nextInt(5).toLong)
      def expr(d: Int): LoopExpr =
        if (d == 0 || rng.nextInt(4) == 0) elem()
        else
          rng.nextInt(8) match {
            case 0 | 1 => invariant() * expr(d - 1)
            case 2     => expr(d - 1) * invariant()
            case 3     =>
              if (rng.nextInt(3) == 0) expr(d - 1) + invariant() else expr(d - 1) - expr(d - 1)
            case _ => LoopExpr.Bin(ops(rng.nextInt(3)), expr(d - 1), expr(d - 1))
          }
      val out = b.output("out", trips)
      val w = Option.when(rng.nextInt(3) == 0)(b.output("w", 2 * trips))
      b.body { i =>
        if (rng.nextInt(4) > 0) out(i) = expr(3) else out(i * -1 + (trips - 1)) = expr(3)
        w.foreach { o => o(i * 2) = expr(2); o(i * 2 + 1) = expr(2) }
      }
    }
  }

  /** `n` loops of [[vectorizableKernel]] from the seed, with 1 to 8 iterations. */
  def vectorizable(seed: Long, n: Int): Vector[LoopKernel] = {
    val rng = new Random(seed)
    Vector.tabulate(n) { i =>
      val trips = 1 + rng.nextInt(8)
      vectorizableKernel(rng, i, trips)
    }
  }

  /** `n` long loops of [[vectorizableKernel]] from the seed, with an even trip count of 10 to 24,
    * so that doubled loops with LD.W chains and gathers reach a steady state.
    */
  def longVectorizable(seed: Long, n: Int): Vector[LoopKernel] = {
    val rng = new Random(seed)
    Vector.tabulate(n) { i =>
      val trips = 10 + 2 * rng.nextInt(8)
      vectorizableKernel(rng, i, trips)
    }
  }
}
