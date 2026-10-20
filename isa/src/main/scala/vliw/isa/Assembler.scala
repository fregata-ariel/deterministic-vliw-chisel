package vliw.isa

/** Text syntax of programs (docs/ASM.md): one line per bundle, `compute | memory`. */
object Assembler {

  /** A program and, for each bundle, the 1-based source line it came from. */
  final case class Program(bundles: Vector[Bundle], lines: Vector[Int])

  private val CycleLabel = """@(\d+)\s*:(.*)""".r
  private val Mnemonic = """([A-Za-z]+(?:\.[A-Za-z]+)?)(?:\s+(.*))?""".r
  private val Register = """[rR](\d+)""".r
  private val Pair = """([rR]\d+):([rR]\d+)""".r
  private val Address = """\[\s*(0[xX][0-9a-fA-F]+|\d+)\s*\]""".r

  /** Assembles `source`. Errors are `line N: message`, in source order. */
  def assemble(source: String, machine: MachineDescription): Either[List[String], Program] = {
    val errors = List.newBuilder[String]
    val bundles = Vector.newBuilder[Bundle]
    val lines = Vector.newBuilder[Int]
    var index = 0
    source.split("\n", -1).iterator.zipWithIndex.foreach { case (rawLine, i) =>
      val lineNo = i + 1
      val text = rawLine.takeWhile(_ != ';').trim
      if (text.nonEmpty) {
        parseLine(text, index, machine) match {
          case Right(b) => bundles += b; lines += lineNo
          case Left(e)  => errors += s"line $lineNo: $e"
        }
        index += 1
      }
    }
    val errs = errors.result()
    if (errs.nonEmpty) Left(errs) else Right(Program(bundles.result(), lines.result()))
  }

  private def parseLine(
      text: String,
      index: Int,
      machine: MachineDescription
  ): Either[String, Bundle] = {
    val body = text match {
      case CycleLabel(n, rest) =>
        if (BigInt(n) != index) Left(s"cycle label @$n does not match bundle index $index")
        else Right(rest.trim)
      case _ => Right(text)
    }
    body.flatMap { b =>
      b.split("\\|", -1) match {
        case Array(c)    => parseCompute(c.trim, machine).map(Bundle(_, MemoryOp.Nop))
        case Array(c, m) =>
          for {
            compute <- parseCompute(c.trim, machine)
            memory <- parseMemory(m.trim, machine)
            _ <- Either.cond(
              compute != ComputeOp.Halt || memory == MemoryOp.Nop,
              (),
              "halt must be paired with a memory nop"
            )
          } yield Bundle(compute, memory)
        case _ => Left("more than one '|' in a bundle")
      }
    }
  }

  private def split(text: String): (String, Vector[String]) = text match {
    case Mnemonic(m, null) => (m.toLowerCase, Vector.empty)
    case Mnemonic(m, rest) => (m.toLowerCase, rest.split(",", -1).map(_.trim).toVector)
    case _                 => ("", Vector(text))
  }

  private def register(token: String, machine: MachineDescription): Either[String, Int] =
    token match {
      case Register(n) if n.length <= 2 && n.toInt < machine.registerCount => Right(n.toInt)
      case Register(_) => Left(s"register $token is outside r0..r${machine.registerCount - 1}")
      case _           => Left(s"expected a register, got \"$token\"")
    }

  /** A register pair `rP:rQ` (ISA mvp-2): Q is P+1 and P is even. Returns P, the value of the pair
    * field. An odd pair is refused here, as the encoder would refuse it (rule L1).
    */
  private def pair(token: String, machine: MachineDescription): Either[String, Int] =
    token match {
      case Pair(a, b) =>
        for {
          p <- register(a, machine)
          q <- register(b, machine)
          _ <- Either.cond(
            q == p + 1,
            (),
            s"a pair names two consecutive registers rP:rP+1, got $token"
          )
          _ <- Either.cond(
            p % 2 == 0,
            (),
            s"pair $token starts at an odd register (P must be even)"
          )
        } yield p
      case _ => Left(s"expected a register pair like r4:r5, got \"$token\"")
    }

  /** rs1 of VMUL and VMAC: a pair, or one register that both lanes read (the broadcast bit). */
  private def pairOrBroadcast(
      token: String,
      machine: MachineDescription
  ): Either[String, (Int, Boolean)] =
    if (token.contains(':')) pair(token, machine).map(_ -> false)
    else register(token, machine).map(_ -> true)

  /** Operations that only some machines have: MAC from mvp-1, the W operations in mvp-2. */
  private def available(mnemonic: String, machine: MachineDescription): Either[String, Unit] =
    Either.cond(
      machine.operations.contains(mnemonic.toUpperCase),
      (),
      s"$mnemonic is not an operation of ${machine.machineId} (ISA ${machine.isaVersion})"
    )

  private def address(token: String): Either[String, Int] = token match {
    case Address(n) =>
      val value =
        if (n.startsWith("0x") || n.startsWith("0X")) BigInt(n.drop(2), 16) else BigInt(n)
      if (value <= BundleCodec.AddressMask) Right(value.toInt)
      else
        Left(s"address $value does not fit in the 9-bit immediate (0..${BundleCodec.AddressMask})")
    case _ => Left(s"expected an address like [12], got \"$token\"")
  }

  private def arity(mnemonic: String, operands: Vector[String], n: Int): Either[String, Unit] =
    Either.cond(operands.size == n, (), s"$mnemonic takes $n operand(s), got ${operands.size}")

  private def parseCompute(text: String, machine: MachineDescription): Either[String, ComputeOp] =
    if (text.isEmpty) Right(ComputeOp.Nop)
    else {
      val (mnemonic, ops) = split(text)
      def rrr(build: (Int, Int, Int) => ComputeOp) =
        for {
          _ <- arity(mnemonic, ops, 3)
          rd <- register(ops(0), machine)
          rs1 <- register(ops(1), machine)
          rs2 <- register(ops(2), machine)
        } yield build(rd, rs1, rs2)
      mnemonic match {
        case "nop"  => arity(mnemonic, ops, 0).map(_ => ComputeOp.Nop)
        case "halt" => arity(mnemonic, ops, 0).map(_ => ComputeOp.Halt)
        case "add"  => rrr(ComputeOp.Add.apply)
        case "sub"  => rrr(ComputeOp.Sub.apply)
        case "mul"  => rrr(ComputeOp.Mul.apply)
        case "mac"  =>
          for {
            _ <- Either.cond(
              machine.hasMac,
              (),
              s"mac is not an operation of ${machine.machineId} (ISA ${machine.isaVersion})"
            )
            _ <- arity(mnemonic, ops, 4)
            rd <- register(ops(0), machine)
            rs1 <- register(ops(1), machine)
            rs2 <- register(ops(2), machine)
            rs3 <- register(ops(3), machine)
          } yield ComputeOp.Mac(rd, rs1, rs2, rs3)
        case "vadd" | "vsub" =>
          for {
            _ <- available(mnemonic, machine)
            _ <- arity(mnemonic, ops, 3)
            rd <- pair(ops(0), machine)
            rs1 <-
              if (ops(1).contains(':')) pair(ops(1), machine)
              else Left(s"$mnemonic has no broadcast: rs1 must be a pair like r2:r3, got ${ops(1)}")
            rs2 <- pair(ops(2), machine)
          } yield
            if (mnemonic == "vadd") ComputeOp.VAdd(rd, rs1, rs2) else ComputeOp.VSub(rd, rs1, rs2)
        case "vmul" =>
          for {
            _ <- available(mnemonic, machine)
            _ <- arity(mnemonic, ops, 3)
            rd <- pair(ops(0), machine)
            rs1 <- pairOrBroadcast(ops(1), machine)
            rs2 <- pair(ops(2), machine)
          } yield ComputeOp.VMul(rd, rs1._1, rs2, broadcast = rs1._2)
        case "vmac" =>
          for {
            _ <- available(mnemonic, machine)
            _ <- arity(mnemonic, ops, 4)
            rd <- pair(ops(0), machine)
            rs1 <- pairOrBroadcast(ops(1), machine)
            rs2 <- pair(ops(2), machine)
            rs3 <- pair(ops(3), machine)
          } yield ComputeOp.VMac(rd, rs1._1, rs2, rs3, broadcast = rs1._2)
        case "ld" | "st" | "ld.w" | "st.w" =>
          Left(s"$mnemonic is a memory operation; write it after '|'")
        case _ => Left(s"unknown compute operation \"$text\"")
      }
    }

  private def parseMemory(text: String, machine: MachineDescription): Either[String, MemoryOp] =
    if (text.isEmpty) Right(MemoryOp.Nop)
    else {
      val (mnemonic, ops) = split(text)
      mnemonic match {
        case "nop" => arity(mnemonic, ops, 0).map(_ => MemoryOp.Nop)
        case "ld"  =>
          for {
            _ <- arity(mnemonic, ops, 2)
            rd <- register(ops(0), machine)
            addr <- address(ops(1))
          } yield MemoryOp.Ld(rd, addr)
        case "st" =>
          for {
            _ <- arity(mnemonic, ops, 2)
            addr <- address(ops(0))
            rs <- register(ops(1), machine)
          } yield MemoryOp.St(addr, rs)
        case "ld.w" =>
          for {
            _ <- available(mnemonic, machine)
            _ <- arity(mnemonic, ops, 2)
            rd <- pair(ops(0), machine)
            addr <- address(ops(1))
          } yield MemoryOp.LdW(rd, addr)
        case "st.w" =>
          for {
            _ <- available(mnemonic, machine)
            _ <- arity(mnemonic, ops, 2)
            addr <- address(ops(0))
            rs <- pair(ops(1), machine)
          } yield MemoryOp.StW(addr, rs)
        case "add" | "sub" | "mul" | "mac" | "vadd" | "vsub" | "vmul" | "vmac" | "halt" =>
          Left(s"$mnemonic is a compute operation; write it before '|'")
        case _ => Left(s"unknown memory operation \"$text\"")
      }
    }
}

/** Canonical text form of bundles, accepted by [[Assembler]]. */
object Disassembler {
  def compute(op: ComputeOp): String = op match {
    case ComputeOp.Nop                      => "nop"
    case ComputeOp.Halt                     => "halt"
    case ComputeOp.Add(d, a, b)             => s"add r$d, r$a, r$b"
    case ComputeOp.Sub(d, a, b)             => s"sub r$d, r$a, r$b"
    case ComputeOp.Mul(d, a, b)             => s"mul r$d, r$a, r$b"
    case ComputeOp.Mac(d, a, b, c)          => s"mac r$d, r$a, r$b, r$c"
    case ComputeOp.VAdd(d, a, b)            => s"vadd ${pair(d)}, ${pair(a)}, ${pair(b)}"
    case ComputeOp.VSub(d, a, b)            => s"vsub ${pair(d)}, ${pair(a)}, ${pair(b)}"
    case ComputeOp.VMul(d, a, b, broadcast) =>
      s"vmul ${pair(d)}, ${rs1(a, broadcast)}, ${pair(b)}"
    case ComputeOp.VMac(d, a, b, c, broadcast) =>
      s"vmac ${pair(d)}, ${rs1(a, broadcast)}, ${pair(b)}, ${pair(c)}"
  }

  /** A pair field holding p is written rP:rP+1; a broadcast rs1 is one register. */
  private def pair(p: Int): String = s"r$p:r${p + 1}"
  private def rs1(r: Int, broadcast: Boolean): String = if (broadcast) s"r$r" else pair(r)

  def memory(op: MemoryOp): String = op match {
    case MemoryOp.Nop          => "nop"
    case MemoryOp.Ld(d, addr)  => s"ld r$d, [$addr]"
    case MemoryOp.St(addr, s)  => s"st [$addr], r$s"
    case MemoryOp.LdW(d, addr) => s"ld.w ${pair(d)}, [$addr]"
    case MemoryOp.StW(addr, s) => s"st.w [$addr], ${pair(s)}"
  }

  def bundle(b: Bundle): String = f"${compute(b.compute)}%-15s | ${memory(b.memory)}"

  /** One line per bundle, each prefixed with its cycle label. */
  def program(bundles: Seq[Bundle]): String = {
    val width = s"@${(bundles.size - 1).max(0)}:".length
    bundles.zipWithIndex.map { case (b, i) =>
      s"@$i:".padTo(width, ' ') + " " + bundle(b) + "\n"
    }.mkString
  }

}
