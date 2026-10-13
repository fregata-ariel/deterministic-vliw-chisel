package vliw.isa

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import scala.util.Random

import org.scalatest.funsuite.AnyFunSuite

class AssemblerSpec extends AnyFunSuite {
  private val machine = MachineDescription.load().fold(es => fail(es.mkString("\n")), identity)
  private val codec = new BundleCodec(machine)

  private def asm(src: String): Vector[Bundle] =
    Assembler.assemble(src, machine).fold(es => fail(es.mkString("\n")), _.bundles)
  private def errors(src: String): List[String] =
    Assembler.assemble(src, machine).left.getOrElse(fail(s"should not assemble: $src"))

  private val isaExample =
    """; docs/ISA.md 最初の命令例
      |@0: nop            | ld r1, [0]
      |@1: nop            | nop
      |@2: mul r3, r1, r2
      |@3: nop
      |
      |@4: ADD R4, R3, R1     ; mnemonics and registers are case-insensitive
      |@5:                | st [0x1], r4
      |@6: halt
      |""".stripMargin

  test("the ISA example assembles to tests/golden/isa_example.hex") {
    val golden = new String(
      Files.readAllBytes(Paths.get("tests", "golden", "isa_example.hex")),
      StandardCharsets.US_ASCII
    )
    val words = codec.encodeAll(asm(isaExample)).fold(e => fail(e), identity)
    assert(ProgramFile.toHex(words) == golden)
  }

  test("source lines are recorded for each bundle") {
    val program = Assembler.assemble(isaExample, machine).toOption.get
    assert(program.lines == Vector(2, 3, 4, 5, 7, 8, 9))
  }

  test("disassembly is canonical and assembles back to the same bundles") {
    val bundles = asm(isaExample)
    val text = Disassembler.program(bundles)
    assert(text.linesIterator.next() == "@0: nop             | ld r1, [0]")
    assert(text.linesIterator.toSeq(4) == "@4: add r4, r3, r1  | nop")
    assert(asm(text) == bundles)
    assert(Disassembler.program(asm(text)) == text)
  }

  test("asm -> bin -> asm round-trips for random programs") {
    val rng = new Random(7L)
    def r = rng.nextInt(16)
    def compute: ComputeOp = rng.nextInt(5) match {
      case 0 => ComputeOp.Nop
      case 1 => ComputeOp.Add(r, r, r)
      case 2 => ComputeOp.Sub(r, r, r)
      case 3 => ComputeOp.Mul(r, r, r)
      case _ => ComputeOp.Halt
    }
    def memory: MemoryOp = rng.nextInt(3) match {
      case 0 => MemoryOp.Nop
      case 1 => MemoryOp.Ld(r, rng.nextInt(512))
      case _ => MemoryOp.St(rng.nextInt(512), r)
    }
    (0 until 200).foreach { _ =>
      val bundles = Vector.fill(1 + rng.nextInt(40)) {
        val c = compute
        Bundle(c, if (c == ComputeOp.Halt) MemoryOp.Nop else memory)
      }
      val words = codec.encodeAll(bundles).toOption.get
      val text = Disassembler.program(codec.decodeAll(words).toOption.get)
      assert(codec.encodeAll(asm(text)).toOption.get == words)
    }
  }

  test("a bundle may omit either side") {
    assert(asm("add r1, r2, r3") == Vector(Bundle(ComputeOp.Add(1, 2, 3))))
    assert(asm("| ld r1, [5]") == Vector(Bundle(MemoryOp.Ld(1, 5))))
    assert(asm("|") == Vector(Bundle.Nop))
    assert(asm("nop | st [511], r15") == Vector(Bundle(MemoryOp.St(511, 15))))
  }

  test("errors carry the source line and are all reported") {
    val es = errors("nop\nfoo r1\nadd r1, r2\n| ld r1, [512]\nhalt | st [0], r1\nadd r16, r0, r0\n")
    assert(es.map(_.takeWhile(_ != ':')) == List("line 2", "line 3", "line 4", "line 5", "line 6"))
    assert(es(0).contains("unknown compute operation"))
    assert(es(1).contains("add takes 3 operand(s), got 2"))
    assert(es(2).contains("does not fit in the 9-bit immediate"))
    assert(es(3).contains("halt must be paired with a memory nop"))
    assert(es(4).contains("r16 is outside r0..r15"))
  }

  test("misplaced operations, bad labels and malformed operands are rejected") {
    assert(errors("ld r1, [0]").head.contains("is a memory operation"))
    assert(errors("| add r1, r1, r1").head.contains("is a compute operation"))
    assert(errors("@1: nop").head.contains("cycle label @1 does not match bundle index 0"))
    assert(errors("nop | nop | nop").head.contains("more than one '|'"))
    assert(errors("| ld r1, 0").head.contains("expected an address"))
    assert(errors("| st r1, [0]").head.contains("expected an address"))
    assert(errors("add x1, r2, r3").head.contains("expected a register"))
  }
}
