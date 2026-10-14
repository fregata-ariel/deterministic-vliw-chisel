package vliw.compiler.dsl

/** The Phase 2 benchmark kernels (docs/DSL.md, benchmarks/README.md). Loops are Scala `for` loops,
  * so every kernel is fully unrolled when it is built.
  */
object Benchmarks {

  /** `out(i) = a * x(i) + y(i)` for i in 0 until n. */
  def saxpy(n: Int): Kernel = Kernel(s"saxpy$n") { k =>
    val a = k.scalar("a")
    val x = k.input("x", n)
    val y = k.input("y", n)
    val out = k.output("out", n)
    for (i <- 0 until n) out(i) = a * x(i) + y(i)
  }

  /** `out(i) = sum over t of h(t) * x(i + t)` for i in 0 until n, with constant taps `h`. */
  def fir(taps: Seq[Long], n: Int): Kernel = Kernel(s"fir${taps.size}x$n") { k =>
    require(taps.nonEmpty, "FIR needs at least one tap")
    val x = k.input("x", n + taps.size - 1)
    val out = k.output("out", n)
    for (i <- 0 until n)
      out(i) = taps.indices.map(t => k.const(taps(t)) * x(i + t)).reduce(_ + _)
  }

  /** `c = a * b` for n x n matrices stored row-major:
    * `c(i*n + j) = sum over m of a(i*n + m) * b(m*n + j)`.
    */
  def matmul(n: Int): Kernel = Kernel(s"matmul$n") { k =>
    val a = k.input("a", n * n)
    val b = k.input("b", n * n)
    val c = k.output("c", n * n)
    for (i <- 0 until n; j <- 0 until n)
      c(i * n + j) = (0 until n).map(m => a(i * n + m) * b(m * n + j)).reduce(_ + _)
  }

  /** The sizes recorded for Phase 2 (#52). */
  val all: Seq[Kernel] = Seq(saxpy(8), fir(Seq(1L, 2L, 3L, 4L), 8), matmul(3))
}
