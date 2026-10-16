package vliw.compiler

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.dsl._
import vliw.isa._

class KernelCompilerSpec extends AnyFunSuite {
  private val machine = MachineDescription.load().fold(es => fail(es.mkString("\n")), identity)

  private def compiled(k: Kernel) = KernelCompiler.compile(k, machine).fold(e => fail(e), identity)

  test("the benchmarks compile; the program is the verified encoding of the allocated bundles") {
    Benchmarks.all.foreach { k =>
      val c = compiled(k)
      assert(c.words == new BundleCodec(machine).encodeAll(c.bundles).toOption.get)
      assert(Verifier.verify(machine, c.words, KernelCompiler.declaration(c.layout)).isEmpty)
      assert(c.words.size == c.cycles) // one bundle per cycle, HALT last
      assert(c.bundles.last == Bundle(ComputeOp.Halt))
    }
  }

  test("code that the verifier rejects is stopped as a compiler error") {
    val c = compiled(Benchmarks.saxpy(8))
    // Dropping the bundle after the first MUL moves its consumers one cycle too early.
    val firstMul = c.bundles.indexWhere(_.compute.isInstanceOf[ComputeOp.Mul])
    val broken = c.bundles.patch(firstMul + 1, Nil, 1)
    val e = KernelCompiler.checked(machine, c.layout, broken).left.getOrElse(fail("accepted"))
    assert(e.startsWith("compiler error: the verifier rejects the code generated for saxpy8"))
    assert(e.contains("L3") || e.contains("L5") || e.contains("L6"), e)
  }

  test("a kernel that does not fit is an input error, reported with the reason") {
    val v = ujson.read(machine.canonicalJson)
    // 3 registers: matmul3 does not fit even with LDs issued again (#70; 4 registers fit).
    v("register_file")("count") = 3
    val tiny = MachineDescription.parse(ujson.write(v)).fold(es => fail(es.mkString), identity)
    assert(
      KernelCompiler
        .compile(Benchmarks.matmul(3), tiny)
        .left
        .exists(_.contains("no schedule fits 3 registers"))
    )
    v("register_file")("count") = 16
    v("scratchpad")("words_per_bank") = 8
    val small = MachineDescription.parse(ujson.write(v)).fold(es => fail(es.mkString), identity)
    assert(
      KernelCompiler.compile(Benchmarks.saxpy(8), small).left.exists(_.contains("needs 25 words"))
    )
  }

  test(
    "the generated test case loads, and its expectations come from the DSL reference implementation"
  ) {
    Benchmarks.all.foreach { k =>
      val c = compiled(k)
      val in = KernelCompiler.sampleInputs(k, 7L)
      val json = KernelCompiler.testCase(c, in, None).fold(e => fail(e), identity)
      val tc = TestCase.parse(ujson.write(json)).fold(es => fail(es.mkString("\n")), identity)
      assert(tc.words == c.words && tc.machine.hash == machine.hash)
      assert(
        tc.inputs.registers.isEmpty && tc.inputs.memory == c.layout.memoryImage(in).toOption.get
      )
      assert(tc.expect.verdict == Verdict.Legal && tc.expect.cycles.contains(c.cycles))
      val out = Interpreter.run(k, in).fold(e => fail(e), identity)
      val expected = for {
        o <- k.outputs
        i <- 0 until o.length
      } yield c.layout.elem(o.name, i) -> out.arrays(o.name)(i)
      assert(tc.expect.memory == expected.toMap)
      assert(Verifier.verify(machine, tc.words, Verifier.Declaration.of(tc.inputs)).isEmpty)
    }
  }

  test("sample inputs are deterministic and cover the 32-bit range") {
    val k = Benchmarks.matmul(3)
    assert(KernelCompiler.sampleInputs(k, 1L) == KernelCompiler.sampleInputs(k, 1L))
    assert(KernelCompiler.sampleInputs(k, 1L) != KernelCompiler.sampleInputs(k, 2L))
    assert(KernelCompiler.sampleInputs(k, 1L).arrays.values.flatten.exists(_ > 0x7fffffffL))
  }
}
