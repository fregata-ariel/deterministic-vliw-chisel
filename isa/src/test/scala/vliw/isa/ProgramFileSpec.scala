package vliw.isa

import java.nio.file.Files

import org.scalatest.funsuite.AnyFunSuite

class ProgramFileSpec extends AnyFunSuite {
  private val words = Vector(0x1100000000000000L, 0L, 0x00000000f0000000L, -1L)

  test(".bin is 8 bytes per bundle, little-endian") {
    val bytes = ProgramFile.toBin(words)
    assert(bytes.length == 32)
    assert(bytes.take(8).toSeq == Seq[Byte](0, 0, 0, 0, 0, 0, 0, 0x11))
    assert(bytes.slice(16, 24).toSeq == Seq[Byte](0, 0, 0, 0xf0.toByte, 0, 0, 0, 0))
    assert(ProgramFile.fromBin(bytes) == Right(words))
    assert(ProgramFile.fromBin(new Array[Byte](12)).isLeft)
  }

  test(".hex is one 16-digit lower-case word per line") {
    val hex = ProgramFile.toHex(words)
    assert(hex.linesIterator.toSeq.head == "1100000000000000")
    assert(hex.linesIterator.toSeq.last == "ffffffffffffffff")
    assert(ProgramFile.fromHex(hex) == Right(words))
    assert(ProgramFile.fromHex("").contains(Vector.empty))
    assert(ProgramFile.fromHex("12\n").left.toOption.get.startsWith("line 1:"))
    assert(
      ProgramFile
        .fromHex("1100000000000000\nABCDEF0000000000\n")
        .left
        .toOption
        .get
        .startsWith("line 2:")
    )
  }

  test("files round-trip by extension") {
    val dir = Files.createTempDirectory("program-file")
    Seq("p.bin", "p.hex").foreach { name =>
      val path = dir.resolve(name)
      assert(ProgramFile.write(path, words) == Right(()))
      assert(ProgramFile.read(path) == Right(words))
    }
    assert(ProgramFile.write(dir.resolve("p.txt"), words).isLeft)
  }
}
