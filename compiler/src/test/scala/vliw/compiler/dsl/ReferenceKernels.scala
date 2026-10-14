package vliw.compiler.dsl

/** The benchmark kernels written directly in Scala, without the DSL, as an independent check of how
  * the kernels are written in Benchmarks. Arithmetic is modulo 2^32.
  */
object ReferenceKernels {
  private def w(v: Long): Long = v & 0xffffffffL

  def saxpy(a: Long, x: Vector[Long], y: Vector[Long]): Vector[Long] =
    x.indices.map(i => w(w(a * x(i)) + y(i))).toVector

  def fir(taps: Seq[Long], x: Vector[Long], n: Int): Vector[Long] =
    (0 until n).map { i =>
      var acc = 0L
      for (t <- taps.indices) acc = w(acc + w(taps(t) * x(i + t)))
      acc
    }.toVector

  def matmul(n: Int, a: Vector[Long], b: Vector[Long]): Vector[Long] =
    (for (i <- 0 until n; j <- 0 until n) yield {
      var acc = 0L
      for (m <- 0 until n) acc = w(acc + w(a(i * n + m) * b(m * n + j)))
      acc
    }).toVector
}
