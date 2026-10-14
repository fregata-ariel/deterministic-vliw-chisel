package vliw.compiler.dsl

/** A kernel after memory layout: every leaf is a read of an initialised scratchpad word, and every
  * store writes one address. Values are still virtual: no register, cycle or operation order is
  * chosen. This is the input of DAG construction (#48).
  */
final case class LoweredKernel(layout: MemoryLayout, writes: Vector[LoweredKernel.Write])

object LoweredKernel {
  sealed trait Value extends Product with Serializable
  final case class Read(addr: Int) extends Value
  final case class Bin(op: Op, a: Value, b: Value) extends Value

  /** `[addr] = value`. */
  final case class Write(addr: Int, value: Value)
}

object Lowering {
  import LoweredKernel._

  /** Replaces input elements, scalars and constants with reads of their layout addresses. */
  def lower(layout: MemoryLayout): LoweredKernel = {
    def value(e: Expr): Value = e match {
      case Expr.Elem(a, i)    => Read(layout.elem(a, i))
      case Expr.Scalar(s)     => Read(layout.scalar(s))
      case Expr.Const(v)      => Read(layout.constant(v))
      case Expr.Bin(op, a, b) => Bin(op, value(a), value(b))
    }
    LoweredKernel(
      layout,
      layout.kernel.stores.map(s => Write(layout.elem(s.output, s.index), value(s.value)))
    )
  }

  /** Evaluates the lowered kernel on a scratchpad image; returns the image after all writes. */
  def evaluate(k: LoweredKernel, memory: Map[Int, Long]): Map[Int, Long] = {
    def eval(v: Value, mem: Map[Int, Long]): Long = v match {
      case Read(a)       => mem(a)
      case Bin(op, a, b) => op(eval(a, mem), eval(b, mem))
    }
    k.writes.foldLeft(memory)((mem, w) => mem.updated(w.addr, eval(w.value, mem)))
  }
}
