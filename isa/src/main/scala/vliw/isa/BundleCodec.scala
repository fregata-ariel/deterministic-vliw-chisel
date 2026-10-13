package vliw.isa

/** Bit-level encoding of bundles (docs/ISA.md, "64bit bundleのエンコード案").
  *
  * The compute word is bits 31:0 and the memory word bits 63:32. Opcodes come from the machine
  * description. Decoding rejects unknown opcodes, non-zero reserved bits, non-zero operand bits of
  * NOP/HALT, register indices outside the register file and HALT with a non-NOP memory word
  * (legality rule L1).
  */
final class BundleCodec(machine: MachineDescription) {
  import BundleCodec._

  private val opAdd = machine.operation("ADD").opcode
  private val opSub = machine.operation("SUB").opcode
  private val opMul = machine.operation("MUL").opcode
  private val opLd = machine.operation("LD").opcode
  private val opSt = machine.operation("ST").opcode
  private val opHalt = machine.haltOpcode
  private val opNop = machine.nopOpcode

  def encode(bundle: Bundle): Either[String, Long] =
    for {
      c <- encodeCompute(bundle.compute)
      m <- encodeMemory(bundle.memory)
      _ <- Either.cond(
        bundle.compute != ComputeOp.Halt || bundle.memory == MemoryOp.Nop,
        (),
        "HALT requires a NOP memory word"
      )
    } yield (m.toLong << 32) | (c.toLong & 0xffffffffL)

  def decode(word: Long): Either[String, Bundle] =
    for {
      c <- decodeCompute((word & 0xffffffffL).toInt)
      m <- decodeMemory((word >>> 32).toInt)
      _ <- Either.cond(
        c != ComputeOp.Halt || m == MemoryOp.Nop,
        (),
        "HALT requires a NOP memory word"
      )
    } yield Bundle(c, m)

  /** Encodes a program; errors carry the bundle index. */
  def encodeAll(bundles: Seq[Bundle]): Either[String, Vector[Long]] =
    traverse(bundles.toVector)(encode)

  /** Decodes a program; errors carry the bundle index. */
  def decodeAll(words: Seq[Long]): Either[String, Vector[Bundle]] =
    traverse(words.toVector)(decode)

  private def reg(name: String, r: Int): Either[String, Int] =
    Either.cond(
      r >= 0 && r < machine.registerCount,
      r,
      s"$name r$r is outside r0..r${machine.registerCount - 1}"
    )

  private def encodeCompute(op: ComputeOp): Either[String, Int] = {
    def rrr(opcode: Int, rd: Int, rs1: Int, rs2: Int) =
      for {
        d <- reg("rd", rd); a <- reg("rs1", rs1); b <- reg("rs2", rs2)
      } yield (opcode << 28) | (d << 24) | (a << 20) | (b << 16)
    op match {
      case ComputeOp.Nop               => Right(opNop << 28)
      case ComputeOp.Halt              => Right(opHalt << 28)
      case ComputeOp.Add(rd, rs1, rs2) => rrr(opAdd, rd, rs1, rs2)
      case ComputeOp.Sub(rd, rs1, rs2) => rrr(opSub, rd, rs1, rs2)
      case ComputeOp.Mul(rd, rs1, rs2) => rrr(opMul, rd, rs1, rs2)
    }
  }

  private def address(addr: Int): Either[String, Int] =
    Either.cond(addr >= 0 && addr <= AddressMask, addr, s"address $addr does not fit in 9 bits")

  private def encodeMemory(op: MemoryOp): Either[String, Int] =
    op match {
      case MemoryOp.Nop          => Right(opNop << 28)
      case MemoryOp.Ld(rd, addr) =>
        for { d <- reg("rd", rd); a <- address(addr) } yield (opLd << 28) | (d << 24) | (a << 15)
      case MemoryOp.St(addr, rs) =>
        for { s <- reg("rs", rs); a <- address(addr) } yield (opSt << 28) | (s << 24) | (a << 15)
    }

  private def decodeCompute(w: Int): Either[String, ComputeOp] = {
    val opcode = w >>> 28
    val rd = (w >>> 24) & 0xf
    val rs1 = (w >>> 20) & 0xf
    val rs2 = (w >>> 16) & 0xf
    val reserved = w & 0xffff
    def rrr(build: (Int, Int, Int) => ComputeOp) =
      if (reserved != 0) Left(f"compute word 0x$w%08x: reserved bits 15:0 are not zero")
      else
        for { d <- reg("rd", rd); a <- reg("rs1", rs1); b <- reg("rs2", rs2) } yield build(d, a, b)
    opcode match {
      case `opNop` | `opHalt` if (w & 0x0fffffff) != 0 =>
        Left(
          f"compute word 0x$w%08x: ${if (opcode == opNop) "NOP" else "HALT"} must have all operand bits zero"
        )
      case `opNop`  => Right(ComputeOp.Nop)
      case `opHalt` => Right(ComputeOp.Halt)
      case `opAdd`  => rrr(ComputeOp.Add.apply)
      case `opSub`  => rrr(ComputeOp.Sub.apply)
      case `opMul`  => rrr(ComputeOp.Mul.apply)
      case _        => Left(f"compute word 0x$w%08x: unknown opcode $opcode")
    }
  }

  private def decodeMemory(w: Int): Either[String, MemoryOp] = {
    val opcode = w >>> 28
    val r = (w >>> 24) & 0xf
    val addr = (w >>> 15) & AddressMask
    val reserved = w & 0x7fff
    def check(build: Int => MemoryOp) =
      if (reserved != 0) Left(f"memory word 0x$w%08x: reserved bits 14:0 are not zero")
      else reg("register", r).map(build)
    opcode match {
      case `opNop` if (w & 0x0fffffff) != 0 =>
        Left(f"memory word 0x$w%08x: NOP must have all operand bits zero")
      case `opNop` => Right(MemoryOp.Nop)
      case `opLd`  => check(MemoryOp.Ld(_, addr))
      case `opSt`  => check(MemoryOp.St(addr, _))
      case _       => Left(f"memory word 0x$w%08x: unknown opcode $opcode")
    }
  }
}

object BundleCodec {
  val AddressMask: Int = (1 << MachineDescription.AddressBits) - 1

  private def traverse[A, B](
      items: Vector[A]
  )(f: A => Either[String, B]): Either[String, Vector[B]] = {
    val out = Vector.newBuilder[B]
    var i = 0
    while (i < items.length) {
      f(items(i)) match {
        case Right(b) => out += b
        case Left(e)  => return Left(s"bundle $i: $e")
      }
      i += 1
    }
    Right(out.result())
  }
}
