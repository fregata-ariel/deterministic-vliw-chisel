package vliw.isa

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import scala.util.Random

import org.scalatest.funsuite.AnyFunSuite

class BundleCodecSpec extends AnyFunSuite {
  private val machine = MachineDescription.load().fold(es => fail(es.mkString("\n")), identity)
  private val codec = new BundleCodec(machine)

  private def enc(b: Bundle): Long = codec.encode(b).fold(e => fail(e), identity)
  private def decErr(w: Long): String =
    codec.decode(w).left.getOrElse(fail(f"0x$w%016x should not decode"))

  /** docs/ISA.md "最初の命令例". Expected words are written by hand from the bit layout table. */
  private val isaExample = Vector(
    Bundle(MemoryOp.Ld(1, 0)) -> 0x1100000000000000L,
    Bundle.Nop -> 0x0L,
    Bundle(ComputeOp.Mul(3, 1, 2)) -> 0x0000000033120000L,
    Bundle.Nop -> 0x0L,
    Bundle(ComputeOp.Add(4, 3, 1)) -> 0x0000000014310000L,
    Bundle(MemoryOp.St(1, 4)) -> 0x2400800000000000L,
    Bundle(ComputeOp.Halt) -> 0x00000000f0000000L
  )

  test("the ISA example encodes to the hand-computed words") {
    isaExample.foreach { case (bundle, word) =>
      assert(enc(bundle) == word, f"$bundle: got 0x${enc(bundle)}%016x, expected 0x$word%016x")
    }
  }

  test("the ISA example matches tests/golden/isa_example.hex") {
    val golden = new String(
      Files.readAllBytes(Paths.get("tests", "golden", "isa_example.hex")),
      StandardCharsets.US_ASCII
    )
    assert(ProgramFile.fromHex(golden) == Right(isaExample.map(_._2)))
    assert(ProgramFile.toHex(isaExample.map(_._2)) == golden)
  }

  test("field placement: each operand lands in its documented bit range") {
    assert(enc(Bundle(ComputeOp.Sub(0xf, 0, 0))) == 0x2f000000L)
    assert(enc(Bundle(ComputeOp.Sub(0, 0xf, 0))) == 0x20f00000L)
    assert(enc(Bundle(ComputeOp.Sub(0, 0, 0xf))) == 0x200f0000L)
    assert(enc(Bundle(MemoryOp.Ld(0xf, 0))) == 0x1f000000L << 32)
    assert(enc(Bundle(MemoryOp.St(511, 0))) == 0x20ff8000L << 32)
  }

  test("every valid compute and memory operation round-trips (exhaustive)") {
    val regs = 0 until 16
    val computes: Seq[ComputeOp] = Seq(ComputeOp.Nop, ComputeOp.Halt) ++
      (for (d <- regs; a <- regs; b <- regs)
        yield Seq(ComputeOp.Add(d, a, b), ComputeOp.Sub(d, a, b), ComputeOp.Mul(d, a, b))).flatten
    val memories: Seq[MemoryOp] = MemoryOp.Nop +:
      (for (r <- regs; addr <- 0 to 511)
        yield Seq(MemoryOp.Ld(r, addr), MemoryOp.St(addr, r))).flatten
    computes.foreach(c => assert(codec.decode(enc(Bundle(c))) == Right(Bundle(c))))
    memories.foreach(m => assert(codec.decode(enc(Bundle(m))) == Right(Bundle(m))))
    val words = (computes.map(c => enc(Bundle(c))) ++ memories.map(m => enc(Bundle(m)))).toSet
    // Bundle.Nop appears in both lists and encodes to 0 once.
    assert(words.size == computes.size + memories.size - 1, "encoding must be injective")
  }

  test("random words: whatever decodes re-encodes to the same word") {
    val rng = new Random(20261014L)
    // Mostly plausible words (known opcodes, zero reserved bits), sometimes arbitrary bits.
    def word32(opcodes: Seq[Int], reservedMask: Int): Int = {
      val opcode = if (rng.nextInt(8) == 0) rng.nextInt(16) else opcodes(rng.nextInt(opcodes.size))
      val operands = if (rng.nextInt(4) == 0) 0 else rng.nextInt() & 0x0fffffff
      val reserved = if (rng.nextInt(8) == 0) rng.nextInt() & reservedMask else 0
      (opcode << 28) | (operands & ~reservedMask & 0x0fffffff) | reserved
    }
    var decoded = 0
    (0 until 200000).foreach { _ =>
      val c = word32(Seq(0, 1, 2, 3, 15), 0xffff)
      val m = word32(Seq(0, 1, 2), 0x7fff)
      val word = (m.toLong << 32) | (c.toLong & 0xffffffffL)
      codec.decode(word).foreach { b =>
        decoded += 1
        assert(enc(b) == word, f"0x$word%016x decoded to $b")
      }
    }
    assert(decoded > 50000, s"only $decoded valid words generated")
  }

  test("pairs of slots combine independently") {
    val b = Bundle(ComputeOp.Mul(7, 8, 9), MemoryOp.Ld(10, 300))
    assert(enc(b) == ((enc(Bundle(b.memory)) & 0xffffffff00000000L) | enc(Bundle(b.compute))))
    assert(codec.decode(enc(b)) == Right(b))
  }

  test("invalid words are rejected with a reason") {
    assert(decErr(0x0000000040000000L).contains("unknown opcode 4"))
    assert(decErr(0x3000000000000000L).contains("unknown opcode 3"))
    assert(decErr(0x0000000010000001L).contains("reserved bits 15:0"))
    assert(decErr(0x1000000100000000L).contains("reserved bits 14:0"))
    assert(decErr(0x0000000000010000L).contains("NOP must have all operand bits zero"))
    assert(decErr(0x00000000f1000000L).contains("HALT must have all operand bits zero"))
    assert(decErr(0x0000800000000000L).contains("NOP must have all operand bits zero"))
    assert(decErr(0x11000000f0000000L).contains("HALT requires a NOP memory word"))
  }

  test("encoding rejects operands that do not fit their fields") {
    assert(codec.encode(Bundle(ComputeOp.Add(16, 0, 0))).isLeft)
    assert(codec.encode(Bundle(ComputeOp.Add(0, -1, 0))).isLeft)
    assert(codec.encode(Bundle(MemoryOp.Ld(0, 512))).isLeft)
    assert(codec.encode(Bundle(ComputeOp.Halt, MemoryOp.St(0, 0))).isLeft)
  }

  test("register indices beyond a smaller register file are rejected") {
    val v = ujson.read(machine.canonicalJson)
    v("register_file")("count") = 8
    val small = MachineDescription.parse(ujson.write(v)).fold(es => fail(es.mkString), identity)
    val smallCodec = new BundleCodec(small)
    assert(smallCodec.encode(Bundle(ComputeOp.Add(8, 0, 0))).left.toOption.get.contains("r0..r7"))
    assert(smallCodec.decode(enc(Bundle(ComputeOp.Add(0, 0, 8)))).isLeft)
    assert(smallCodec.decode(enc(Bundle(ComputeOp.Add(7, 7, 7)))).isRight)
  }

  test("program errors carry the bundle index") {
    assert(codec.decodeAll(Seq(0L, 0L, 0x40000000L)).left.toOption.get.startsWith("bundle 2:"))
    assert(
      codec
        .encodeAll(Seq(Bundle.Nop, Bundle(MemoryOp.Ld(0, 999))))
        .left
        .toOption
        .get
        .startsWith("bundle 1:")
    )
  }
}
