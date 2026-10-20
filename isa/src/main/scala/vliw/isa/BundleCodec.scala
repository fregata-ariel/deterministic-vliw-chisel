package vliw.isa

/** Bit-level encoding of bundles (docs/ISA.md, "64bit bundleのエンコード案").
  *
  * The compute word is bits 31:0 and the memory word bits 63:32. Opcodes come from the machine
  * description. Decoding rejects unknown opcodes, non-zero reserved bits, non-zero operand bits of
  * NOP/HALT, register indices outside the register file and HALT with a non-NOP memory word
  * (legality rule L1). MAC exists from ISA mvp-1 and puts rs3 in bits 15:12; on an mvp-0 machine
  * its opcode is unknown. The W operations of mvp-2 (docs/ISA.md, "mvp-2: W") have even pair fields
  * and, on VMUL and VMAC only, the broadcast bit 11; on a machine without them their opcodes are
  * unknown. Encoding refuses what decoding rejects, so no illegal word is ever produced.
  */
final class BundleCodec(machine: MachineDescription) {
  import BundleCodec._

  private val opAdd = machine.operation("ADD").opcode
  private val opSub = machine.operation("SUB").opcode
  private val opMul = machine.operation("MUL").opcode
  private val opMac = machine.operations.get("MAC").map(_.opcode)
  private val opVadd = machine.operations.get("VADD").map(_.opcode)
  private val opVsub = machine.operations.get("VSUB").map(_.opcode)
  private val opVmul = machine.operations.get("VMUL").map(_.opcode)
  private val opVmac = machine.operations.get("VMAC").map(_.opcode)
  private val opLd = machine.operation("LD").opcode
  private val opSt = machine.operation("ST").opcode
  private val opLdw = machine.operations.get("LD.W").map(_.opcode)
  private val opStw = machine.operations.get("ST.W").map(_.opcode)
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

  /** A pair field holds the even register p of the pair r(p), r(p+1). The register count of an
    * mvp-2 machine is even, so r(p+1) exists whenever r(p) does.
    */
  private def pair(name: String, r: Int): Either[String, Int] =
    reg(name, r).flatMap { p =>
      Either.cond(p % 2 == 0, p, s"$name r$p is odd; a pair field holds an even register")
    }

  /** The opcode of an operation that only some machines have (the W operations of mvp-2). */
  private def opcodeOf(mnemonic: String, op: Option[Int]): Either[String, Int] =
    op.toRight(s"$mnemonic is not an operation of ${machine.machineId} (ISA ${machine.isaVersion})")

  private def encodeCompute(op: ComputeOp): Either[String, Int] = {
    def rrr(opcode: Int, rd: Int, rs1: Int, rs2: Int) =
      for {
        d <- reg("rd", rd); a <- reg("rs1", rs1); b <- reg("rs2", rs2)
      } yield (opcode << 28) | (d << 24) | (a << 20) | (b << 16)
    // W: rd and rs2 are pairs; rs1 is a pair, or with the broadcast bit one register.
    def vector(mnemonic: String, known: Option[Int], rd: Int, rs1: Int, rs2: Int, bc: Boolean) =
      for {
        o <- opcodeOf(mnemonic, known)
        _ <- pair("rd", rd)
        _ <- if (bc) reg("rs1", rs1) else pair("rs1", rs1)
        _ <- pair("rs2", rs2)
        word <- rrr(o, rd, rs1, rs2)
      } yield if (bc) word | BroadcastBit else word
    op match {
      case ComputeOp.Nop                    => Right(opNop << 28)
      case ComputeOp.Halt                   => Right(opHalt << 28)
      case ComputeOp.Add(rd, rs1, rs2)      => rrr(opAdd, rd, rs1, rs2)
      case ComputeOp.Sub(rd, rs1, rs2)      => rrr(opSub, rd, rs1, rs2)
      case ComputeOp.Mul(rd, rs1, rs2)      => rrr(opMul, rd, rs1, rs2)
      case ComputeOp.Mac(rd, rs1, rs2, rs3) =>
        for {
          opcode <- opMac.toRight(
            s"MAC is not an operation of ${machine.machineId} (ISA ${machine.isaVersion})"
          )
          word <- rrr(opcode, rd, rs1, rs2)
          c <- reg("rs3", rs3)
        } yield word | (c << 12)
      case ComputeOp.VAdd(rd, rs1, rs2)         => vector("VADD", opVadd, rd, rs1, rs2, bc = false)
      case ComputeOp.VSub(rd, rs1, rs2)         => vector("VSUB", opVsub, rd, rs1, rs2, bc = false)
      case ComputeOp.VMul(rd, rs1, rs2, b)      => vector("VMUL", opVmul, rd, rs1, rs2, b)
      case ComputeOp.VMac(rd, rs1, rs2, rs3, b) =>
        for {
          v <- vector("VMAC", opVmac, rd, rs1, rs2, b)
          p <- pair("rs3", rs3)
        } yield v | (p << Rs3Shift)
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
      case MemoryOp.LdW(rd, addr) =>
        for { o <- opcodeOf("LD.W", opLdw); d <- pair("rd", rd); a <- address(addr) } yield (
          (o << 28) | (d << 24) | (a << 15)
        )
      case MemoryOp.StW(addr, rs) =>
        for { o <- opcodeOf("ST.W", opStw); s <- pair("rs", rs); a <- address(addr) } yield (
          (o << 28) | (s << 24) | (a << 15)
        )
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
    // W: the reserved bits of each operation (`mask`), then the pair fields. The broadcast bit
    // exists for VMUL and VMAC only; on VADD and VSUB it is one of the reserved bits 15:0.
    val bc = (w & BroadcastBit) != 0
    def vector(mask: Int, bits: String, broadcast: Boolean)(build: => ComputeOp) =
      if ((w & mask) != 0) {
        val note = if (bc && !broadcast) " (bit 11 broadcasts on VMUL and VMAC only)" else ""
        Left(f"compute word 0x$w%08x: reserved bits $bits are not zero$note")
      } else
        for {
          _ <- pair("rd", rd)
          _ <- if (broadcast && bc) reg("rs1", rs1) else pair("rs1", rs1)
          _ <- pair("rs2", rs2)
        } yield build
    opcode match {
      case `opNop` | `opHalt` if (w & 0x0fffffff) != 0 =>
        Left(
          f"compute word 0x$w%08x: ${if (opcode == opNop) "NOP" else "HALT"} must have all operand bits zero"
        )
      case `opNop`                  => Right(ComputeOp.Nop)
      case `opHalt`                 => Right(ComputeOp.Halt)
      case `opAdd`                  => rrr(ComputeOp.Add.apply)
      case `opSub`                  => rrr(ComputeOp.Sub.apply)
      case `opMul`                  => rrr(ComputeOp.Mul.apply)
      case op if opMac.contains(op) =>
        if ((w & 0xfff) != 0) Left(f"compute word 0x$w%08x: reserved bits 11:0 are not zero")
        else
          for {
            d <- reg("rd", rd); a <- reg("rs1", rs1); b <- reg("rs2", rs2)
            c <- reg("rs3", (w >>> 12) & 0xf)
          } yield ComputeOp.Mac(d, a, b, c)
      case op if opVadd.contains(op) =>
        vector(0xffff, "15:0", broadcast = false)(ComputeOp.VAdd(rd, rs1, rs2))
      case op if opVsub.contains(op) =>
        vector(0xffff, "15:0", broadcast = false)(ComputeOp.VSub(rd, rs1, rs2))
      case op if opVmul.contains(op) =>
        vector(0xffff & ~BroadcastBit, "15:12 and 10:0", broadcast = true)(
          ComputeOp.VMul(rd, rs1, rs2, bc)
        )
      case op if opVmac.contains(op) =>
        val rs3 = (w >>> Rs3Shift) & 0xf
        vector(0xfff & ~BroadcastBit, "10:0", broadcast = true)(
          ComputeOp.VMac(rd, rs1, rs2, rs3, bc)
        )
          .flatMap(v => pair("rs3", rs3).map(_ => v))
      case _ => Left(f"compute word 0x$w%08x: unknown opcode $opcode")
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
    def evenPair(op: MemoryOp) = pair("register", r).map(_ => op)
    opcode match {
      case `opNop` if (w & 0x0fffffff) != 0 =>
        Left(f"memory word 0x$w%08x: NOP must have all operand bits zero")
      case `opNop` => Right(MemoryOp.Nop)
      case `opLd`  => check(MemoryOp.Ld(_, addr))
      case `opSt`  => check(MemoryOp.St(addr, _))
      // LD.W and ST.W: a pair in bits 27:24; the address may be odd.
      case op if opLdw.contains(op) => check(MemoryOp.LdW(_, addr)).flatMap(evenPair)
      case op if opStw.contains(op) => check(MemoryOp.StW(addr, _)).flatMap(evenPair)
      case _                        => Left(f"memory word 0x$w%08x: unknown opcode $opcode")
    }
  }
}

object BundleCodec {
  val AddressMask: Int = (1 << MachineDescription.AddressBits) - 1

  /** Compute word bit 11 of VMUL and VMAC: rs1 is one register, read for both lanes (mvp-2). */
  val BroadcastBit: Int = 1 << 11

  /** The low bit of rs3 in the compute word (MAC and VMAC). */
  val Rs3Shift: Int = 12

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
