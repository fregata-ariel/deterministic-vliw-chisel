package vliw.compiler.loop

/** The benchmark kernels as loops (docs/COMPILER.md, "ループ IR"). The first four unroll to
  * `dsl.Benchmarks.saxpy`, `fir` and `matmul` exactly, so they share the Phase 2 memory layout and
  * DAG. `product` and `dot` are the loops through a state that #68 measures.
  */
object LoopBenchmarks {

  /** `out(i) = a * x(i) + y(i)`: the loop over i, n iterations. */
  def saxpy(n: Int): LoopKernel = LoopKernel(s"saxpy$n", "i", n) { k =>
    val a = k.scalar("a")
    val x = k.input("x", n)
    val y = k.input("y", n)
    val out = k.output("out", n)
    k.body(i => out(i) = a * x(i) + y(i))
  }

  /** `out(i) = sum over t of h(t) * x(i + t)`: the output loop over i, n iterations, with the taps
    * unrolled in the body.
    */
  def fir(taps: Seq[Long], n: Int): LoopKernel = LoopKernel(s"fir${taps.size}x$n", "i", n) { k =>
    require(taps.nonEmpty, "FIR needs at least one tap")
    val x = k.input("x", n + taps.size - 1)
    val out = k.output("out", n)
    k.body(i => out(i) = taps.indices.map(t => k.const(taps(t)) * x(i + t)).reduce(_ + _))
  }

  /** `c = a * b` for n x n matrices: the loop over the rows i, n iterations, with j and m unrolled
    * in the body.
    */
  def matmulRows(n: Int): LoopKernel = LoopKernel(s"matmul$n", "i", n) { k =>
    val a = k.input("a", n * n)
    val b = k.input("b", n * n)
    val c = k.output("c", n * n)
    k.body { i =>
      for (j <- 0 until n)
        c(i * n + j) = (0 until n).map(m => a(i * n + m) * b(m * n + j)).reduce(_ + _)
    }
  }

  /** `c = a * b` for n x n matrices: the accumulation over m as the loop, with a state per element
    * of `c`. m = 0 is the states' initial value, so iteration k is m = k + 1 (n - 1 iterations) and
    * the sums are added in the order of `dsl.Benchmarks.matmul`.
    */
  def matmulAcc(n: Int): LoopKernel = {
    require(n >= 2, s"matmulAcc needs n >= 2, not $n")
    LoopKernel(s"matmul$n", "m", n - 1) { k =>
      val a = k.input("a", n * n)
      val b = k.input("b", n * n)
      val c = k.output("c", n * n)
      val acc = for (i <- 0 until n; j <- 0 until n) yield k.state(s"acc_${i}_$j", a(i * n) * b(j))
      k.body { t =>
        val m = t + 1
        for (i <- 0 until n; j <- 0 until n) {
          val s = acc(i * n + j)
          s := s.value + a(m + i * n) * b(m * n + j)
        }
      }
      for (i <- 0 until n * n) c(i) = acc(i)
    }
  }

  /** `out(0) = x(0) * x(1) * ... * x(n - 1)`: a state through MUL, `p := p * x(i)` for i = 1 until
    * n (iteration k is i = k + 1).
    */
  def product(n: Int): LoopKernel = {
    require(n >= 2, s"product needs n >= 2, not $n")
    LoopKernel(s"product$n", "i", n - 1) { k =>
      val x = k.input("x", n)
      val out = k.output("out", 1)
      val p = k.state("p", x(0))
      k.body(t => p := p.value * x(t + 1))
      out(0) = p
    }
  }

  /** `out(0) = sum over i of a(i) * b(i)`: one MUL and one ADD per iteration, `acc := acc + a(i) *
    * b(i)` for i = 1 until n (iteration k is i = k + 1).
    */
  def dot(n: Int): LoopKernel = {
    require(n >= 2, s"dot needs n >= 2, not $n")
    LoopKernel(s"dot$n", "i", n - 1) { k =>
      val a = k.input("a", n)
      val b = k.input("b", n)
      val out = k.output("out", 1)
      val acc = k.state("acc", a(0) * b(0))
      k.body(t => acc := acc.value + a(t + 1) * b(t + 1))
      out(0) = acc
    }
  }

  /** The loops recorded in docs/COMPILER.md. */
  val all: Seq[LoopKernel] =
    Seq(saxpy(8), fir(Seq(1L, 2L, 3L, 4L), 8), matmulRows(3), matmulAcc(3), product(8), dot(8))
}
