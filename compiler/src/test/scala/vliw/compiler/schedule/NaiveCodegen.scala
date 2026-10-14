package vliw.compiler.schedule

import vliw.compiler.dag.Node
import vliw.compiler.dsl.Op
import vliw.isa._

/** Test-only code generation without register allocation: every value gets its own register, so it
  * works only while the kernel has at most as many values as the machine has registers. It lets
  * schedules be checked by the verifier and the reference model before #50 exists.
  */
object NaiveCodegen {
  def bundles(s: Schedule): Option[Vector[Bundle]] = {
    val values = s.dag.nodes.indices.filter(i => !s.dag.nodes(i).isInstanceOf[Node.Store])
    Option.when(values.size <= s.dag.machine.registerCount) {
      val reg = values.zipWithIndex.toMap
      def compute(i: Int): ComputeOp = s.dag.nodes(i) match {
        case Node.Compute(Op.Add, a, b) => ComputeOp.Add(reg(i), reg(a), reg(b))
        case Node.Compute(Op.Sub, a, b) => ComputeOp.Sub(reg(i), reg(a), reg(b))
        case Node.Compute(Op.Mul, a, b) => ComputeOp.Mul(reg(i), reg(a), reg(b))
        case other                      => sys.error(s"not a compute node: $other")
      }
      def memory(i: Int): MemoryOp = s.dag.nodes(i) match {
        case Node.Load(addr)     => MemoryOp.Ld(reg(i), addr)
        case Node.Store(addr, v) => MemoryOp.St(addr, reg(v))
        case other               => sys.error(s"not a memory node: $other")
      }
      s.bundles.map(b =>
        Bundle(
          b.compute.fold[ComputeOp](ComputeOp.Nop)(compute),
          b.memory.fold[MemoryOp](MemoryOp.Nop)(memory)
        )
      ) :+ Bundle(ComputeOp.Halt)
    }
  }
}
