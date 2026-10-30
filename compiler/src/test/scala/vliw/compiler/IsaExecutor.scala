package vliw.compiler

import scala.collection.mutable

import vliw.compiler.dsl.Op
import vliw.isa._

/** Runs a legal program as docs/ISA.md defines it, independently of the compiler (the compiler's
  * tests cannot use the reference model): each bundle reads its sources in its cycle, and each
  * result commits `latency` cycles later. ISA mvp-2 (docs/ISA.md "mvp-2: W"): each lane of a vector
  * operation computes as the scalar operation, a broadcast rs1 gives one register to both lanes,
  * and LD.W and ST.W access the words `a` and `a + 1`.
  */
object IsaExecutor {

  /** The memory after running `bundles` on `image`; a read of an undefined word throws. */
  def run(m: MachineDescription, bundles: Vector[Bundle], image: Map[Int, Long]): Map[Int, Long] = {
    val reg = Array.fill(m.registerCount)(0L)
    val mem = mutable.Map.from(image)
    val pending = mutable.ArrayBuffer.empty[(Int, () => Unit)]
    bundles.indices.foreach { s =>
      pending.filter(_._1 <= s).foreach(_._2())
      pending.filterInPlace(_._1 > s)
      def later(op: String)(commit: () => Unit) = pending += ((s + m.operation(op).latency, commit))
      def pair(r: Int) = (reg(r), reg(r + 1))
      def a(x: Int, broadcast: Boolean) = if (broadcast) (reg(x), reg(x)) else pair(x)
      def write(d: Int, v: (Long, Long)) = { reg(d) = v._1; reg(d + 1) = v._2 }
      bundles(s).compute match {
        case ComputeOp.Add(d, x, y) =>
          val v = Op.Add(reg(x), reg(y)); later("ADD")(() => reg(d) = v)
        case ComputeOp.Sub(d, x, y) =>
          val v = Op.Sub(reg(x), reg(y)); later("SUB")(() => reg(d) = v)
        case ComputeOp.Mul(d, x, y) =>
          val v = Op.Mul(reg(x), reg(y)); later("MUL")(() => reg(d) = v)
        case ComputeOp.Mac(d, x, y, z) =>
          val v = Op.Add(Op.Mul(reg(x), reg(y)), reg(z)); later("MAC")(() => reg(d) = v)
        case ComputeOp.VAdd(d, x, y) =>
          val (p, q) = (pair(x), pair(y))
          val v = (Op.Add(p._1, q._1), Op.Add(p._2, q._2)); later("VADD")(() => write(d, v))
        case ComputeOp.VSub(d, x, y) =>
          val (p, q) = (pair(x), pair(y))
          val v = (Op.Sub(p._1, q._1), Op.Sub(p._2, q._2)); later("VSUB")(() => write(d, v))
        case ComputeOp.VMul(d, x, y, b) =>
          val (p, q) = (a(x, b), pair(y))
          val v = (Op.Mul(p._1, q._1), Op.Mul(p._2, q._2)); later("VMUL")(() => write(d, v))
        case ComputeOp.VMac(d, x, y, z, b) =>
          val (p, q, r) = (a(x, b), pair(y), pair(z))
          val v = (Op.Add(Op.Mul(p._1, q._1), r._1), Op.Add(Op.Mul(p._2, q._2), r._2))
          later("VMAC")(() => write(d, v))
        case ComputeOp.Nop | ComputeOp.Halt => ()
      }
      bundles(s).memory match {
        case MemoryOp.Ld(d, x)  => val v = mem(x); later("LD")(() => reg(d) = v)
        case MemoryOp.St(x, r)  => val v = reg(r); later("ST")(() => mem(x) = v)
        case MemoryOp.LdW(d, x) =>
          val v = (mem(x), mem(x + 1)); later("LD.W")(() => write(d, v))
        case MemoryOp.StW(x, r) =>
          val v = pair(r); later("ST.W")(() => { mem(x) = v._1; mem(x + 1) = v._2 })
        case MemoryOp.Nop => ()
      }
    }
    pending.foreach(_._2())
    mem.toMap
  }
}
