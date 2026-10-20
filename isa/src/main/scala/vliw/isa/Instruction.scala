package vliw.isa

/** Compute slot operation. Register operands are indices r0..r15.
  *
  * The vector operations of ISA mvp-2 (docs/ISA.md, "mvp-2: W") work on register pairs: a pair
  * field holds the even register p and names r(p) (lane 0) and r(p+1) (lane 1). A broadcast rs1
  * holds one register, read once for both lanes.
  */
sealed trait ComputeOp extends Product with Serializable
object ComputeOp {
  case object Nop extends ComputeOp
  final case class Add(rd: Int, rs1: Int, rs2: Int) extends ComputeOp
  final case class Sub(rd: Int, rs1: Int, rs2: Int) extends ComputeOp
  final case class Mul(rd: Int, rs1: Int, rs2: Int) extends ComputeOp

  /** rd = low32(rs1 * rs2 + rs3). ISA mvp-1 and mvp-2 (docs/ISA.md, "mvp-1: MAC"). */
  final case class Mac(rd: Int, rs1: Int, rs2: Int, rs3: Int) extends ComputeOp

  /** r(rd+i) = r(rs1+i) + r(rs2+i) for lanes i = 0, 1; every field is a pair. ISA mvp-2. */
  final case class VAdd(rd: Int, rs1: Int, rs2: Int) extends ComputeOp

  /** r(rd+i) = r(rs1+i) - r(rs2+i); every field is a pair. ISA mvp-2. */
  final case class VSub(rd: Int, rs1: Int, rs2: Int) extends ComputeOp

  /** r(rd+i) = low32(A_i * r(rs2+i)), where A_i is r(rs1) when `broadcast` (rs1 is one register)
    * and r(rs1+i) otherwise (rs1 is a pair). ISA mvp-2.
    */
  final case class VMul(rd: Int, rs1: Int, rs2: Int, broadcast: Boolean) extends ComputeOp

  /** r(rd+i) = low32(A_i * r(rs2+i) + r(rs3+i)), with A_i as for [[VMul]]. ISA mvp-2. */
  final case class VMac(rd: Int, rs1: Int, rs2: Int, rs3: Int, broadcast: Boolean) extends ComputeOp
  case object Halt extends ComputeOp
}

/** Memory slot operation. Addresses are word indices into the scratchpad. */
sealed trait MemoryOp extends Product with Serializable
object MemoryOp {
  case object Nop extends MemoryOp
  final case class Ld(rd: Int, addr: Int) extends MemoryOp
  final case class St(addr: Int, rs: Int) extends MemoryOp

  /** r(rd) = M(addr), r(rd+1) = M(addr+1); rd is a pair and `addr` may be odd. ISA mvp-2 with
    * `scratchpad.max_words_per_access` 2 only.
    */
  final case class LdW(rd: Int, addr: Int) extends MemoryOp

  /** M(addr) = r(rs), M(addr+1) = r(rs+1); rs is a pair. As for [[LdW]]. */
  final case class StW(addr: Int, rs: Int) extends MemoryOp
}

/** One 64-bit instruction bundle, issued in a single cycle. */
final case class Bundle(compute: ComputeOp, memory: MemoryOp)
object Bundle {
  val Nop: Bundle = Bundle(ComputeOp.Nop, MemoryOp.Nop)
  def apply(compute: ComputeOp): Bundle = Bundle(compute, MemoryOp.Nop)
  def apply(memory: MemoryOp): Bundle = Bundle(ComputeOp.Nop, memory)
}
