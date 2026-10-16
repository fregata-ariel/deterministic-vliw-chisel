package vliw.isa

/** Compute slot operation. Register operands are indices r0..r15. */
sealed trait ComputeOp extends Product with Serializable
object ComputeOp {
  case object Nop extends ComputeOp
  final case class Add(rd: Int, rs1: Int, rs2: Int) extends ComputeOp
  final case class Sub(rd: Int, rs1: Int, rs2: Int) extends ComputeOp
  final case class Mul(rd: Int, rs1: Int, rs2: Int) extends ComputeOp

  /** rd = low32(rs1 * rs2 + rs3). ISA mvp-1 only (docs/ISA.md, "mvp-1: MAC"). */
  final case class Mac(rd: Int, rs1: Int, rs2: Int, rs3: Int) extends ComputeOp
  case object Halt extends ComputeOp
}

/** Memory slot operation. Addresses are word indices into the scratchpad. */
sealed trait MemoryOp extends Product with Serializable
object MemoryOp {
  case object Nop extends MemoryOp
  final case class Ld(rd: Int, addr: Int) extends MemoryOp
  final case class St(addr: Int, rs: Int) extends MemoryOp
}

/** One 64-bit instruction bundle, issued in a single cycle. */
final case class Bundle(compute: ComputeOp, memory: MemoryOp)
object Bundle {
  val Nop: Bundle = Bundle(ComputeOp.Nop, MemoryOp.Nop)
  def apply(compute: ComputeOp): Bundle = Bundle(compute, MemoryOp.Nop)
  def apply(memory: MemoryOp): Bundle = Bundle(ComputeOp.Nop, memory)
}
