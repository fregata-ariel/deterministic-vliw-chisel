package vliw.isa

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import org.scalatest.funsuite.AnyFunSuite

/** `isa/run asm` and `isa/run disasm` with and without `--machine` (docs/ASM.md「コマンド」). */
class CliSpec extends AnyFunSuite {
  private val mvp1 = "config/machine-mvp1.json"
  private val dir = Files.createTempDirectory("isa-cli")
  private def file(name: String, text: String): Path =
    Files.write(dir.resolve(name), text.getBytes(StandardCharsets.UTF_8))
  private def errors(args: String*): List[String] =
    Cli.run(args.toList).left.getOrElse(fail(s"expected errors from ${args.mkString(" ")}"))

  test("asm assembles mac with --machine config/machine-mvp1.json only") {
    val src = file("mac.asm", "mac r5, r1, r2, r3\n").toString
    val out = dir.resolve("mac.hex")
    assert(Cli.run(List("asm", src, out.toString, "--machine", mvp1)) == Right(s"$out: 1 bundles"))
    assert(ProgramFile.read(out) == Right(Vector(0x0000000045123000L)))
    // --machine may come first, as in compiler/run.
    val bin = dir.resolve("mac.bin")
    assert(Cli.run(List("asm", "--machine", mvp1, src, bin.toString)).isRight)
    assert(ProgramFile.read(bin) == Right(Vector(0x0000000045123000L)))
    // The default machine is mvp-0, which has no MAC.
    val rejected = errors("asm", src, dir.resolve("mvp0.hex").toString)
    assert(rejected.exists(_.contains("mac is not an operation of vliw2-i32-sp2 (ISA mvp-0)")))
    assert(!Files.exists(dir.resolve("mvp0.hex")))
  }

  test("disasm decodes an mvp-1 word with --machine config/machine-mvp1.json only") {
    val hex = file("mvp1.hex", ProgramFile.toHex(Vector(0x0000000045123000L, 0xf0000000L))).toString
    assert(
      Cli.run(List("disasm", hex, "--machine", mvp1)) ==
        Right("@0: mac r5, r1, r2, r3 | nop\n@1: halt            | nop")
    )
    assert(errors("disasm", hex).exists(_.contains("unknown opcode 4")))
    // mvp-0 words read the same on both machines.
    val golden = "tests/golden/isa_example.hex"
    assert(Cli.run(List("disasm", golden, "--machine", mvp1)) == Cli.run(List("disasm", golden)))
  }

  test("machine, asm and disasm handle ISA mvp-2 with --machine config/machine-mvp2.json") {
    val shown =
      Cli.run(List("machine", "config/machine-mvp2.json")).fold(e => fail(e.mkString), s => s)
    assert(shown.contains("machine: vliw2-i32-sp2-mac-w2 revision 1 (proposed)"))
    assert(shown.contains("isa:     mvp-2"))
    val mvp2 = "config/machine-mvp2.json"
    val src = file("w.asm", "vmac r4:r5, r2, r6:r7, r8:r9 | ld.w r10:r11, [17]\nhalt\n").toString
    val out = dir.resolve("w.hex")
    assert(Cli.run(List("asm", src, out.toString, "--machine", mvp2)) == Right(s"$out: 2 bundles"))
    assert(ProgramFile.read(out) == Right(Vector(0x3a08800084268800L, 0xf0000000L)))
    assert(
      Cli.run(List("disasm", out.toString, "--machine", mvp2)) ==
        Right("@0: vmac r4:r5, r2, r6:r7, r8:r9 | ld.w r10:r11, [17]\n@1: halt            | nop")
    )
    // mvp-1 has no W operations.
    assert(
      errors("asm", src, dir.resolve("w1.hex").toString, "--machine", mvp1).exists(
        _.contains("vmac is not an operation of vliw2-i32-sp2-mac (ISA mvp-1)")
      )
    )
    assert(errors("disasm", out.toString, "--machine", mvp1).exists(_.contains("unknown opcode 8")))
  }

  test("a missing operand, an unknown option or a bad machine path is reported") {
    assert(errors("asm", "--machine", mvp1, "in.asm") == List(Cli.usage))
    assert(errors("disasm", "in.hex", "--machines", mvp1) == List(Cli.usage))
    assert(errors("disasm", "in.hex", "--machine") == List(Cli.usage))
    assert(errors("disasm", "tests/golden/isa_example.hex", "--machine", "no/such.json").nonEmpty)
  }
}
