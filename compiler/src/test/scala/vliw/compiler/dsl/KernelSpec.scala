package vliw.compiler.dsl

import scala.util.Random

import org.scalatest.funsuite.AnyFunSuite

import vliw.isa.MachineDescription

class KernelSpec extends AnyFunSuite {
  private val machine = MachineDescription.load().fold(es => fail(es.mkString("\n")), identity)

  private def words(rng: Random, n: Int) = Vector.fill(n)(rng.nextLong() & 0xffffffffL)

  /** Random inputs for `k`, with full 32-bit values so that results wrap. */
  private def values(k: Kernel, rng: Random) =
    Values(
      k.inputs.map(a => a.name -> words(rng, a.length)).toMap,
      k.scalars.map(_ -> (rng.nextLong() & 0xffffffffL)).toMap
    )

  private def run(k: Kernel, in: Values) = Interpreter.run(k, in).fold(e => fail(e), identity)

  test("hand-computed results: SAXPY, FIR and matrix product of small size") {
    val s = run(
      Benchmarks.saxpy(2),
      Values(Map("x" -> Vector(1L, 2L), "y" -> Vector(10L, 20L)), Map("a" -> 3L))
    )
    assert(s.arrays("out") == Vector(13L, 26L))
    // taps (1, 2): out(0) = 1*5 + 2*6, out(1) = 1*6 + 2*7
    val f = run(Benchmarks.fir(Seq(1L, 2L), 2), Values(Map("x" -> Vector(5L, 6L, 7L))))
    assert(f.arrays("out") == Vector(17L, 20L))
    // [1 2; 3 4] x [5 6; 7 8] = [19 22; 43 50]
    val m = run(
      Benchmarks.matmul(2),
      Values(Map("a" -> Vector(1L, 2L, 3L, 4L), "b" -> Vector(5L, 6L, 7L, 8L)))
    )
    assert(m.arrays("c") == Vector(19L, 22L, 43L, 50L))
  }

  test("arithmetic wraps modulo 2^32 and constants are normalised") {
    val k = Kernel("wrap") { k =>
      val x = k.input("x", 1)
      val out = k.output("out", 3)
      out(0) = x(0) + k.const(1)
      out(1) = k.const(0) - k.const(1)
      out(2) = x(0) * x(0)
    }
    val r = run(k, Values(Map("x" -> Vector(0xffffffffL))))
    assert(r.arrays("out") == Vector(0L, 0xffffffffL, 1L))
    assert(Kernel("neg")(k => k.output("out", 1)(0) = k.const(-1)).constants == Vector(0xffffffffL))
  }

  test("the benchmark kernels agree with the plain Scala implementations on random inputs") {
    val rng = new Random(47L)
    val taps = Seq(1L, 2L, 3L, 4L)
    (0 until 50).foreach { _ =>
      val s = Benchmarks.saxpy(8)
      val sv = values(s, rng)
      assert(
        run(s, sv).arrays("out") ==
          ReferenceKernels.saxpy(sv.scalars("a"), sv.arrays("x"), sv.arrays("y"))
      )
      val f = Benchmarks.fir(taps, 8)
      val fv = values(f, rng)
      assert(run(f, fv).arrays("out") == ReferenceKernels.fir(taps, fv.arrays("x"), 8))
      val m = Benchmarks.matmul(3)
      val mv = values(m, rng)
      assert(run(m, mv).arrays("c") == ReferenceKernels.matmul(3, mv.arrays("a"), mv.arrays("b")))
    }
  }

  test("malformed kernels are rejected when they are built") {
    def rejected(body: Kernel.Builder => Unit) =
      intercept[IllegalArgumentException](Kernel("bad")(body)).getMessage
    assert(rejected { k => k.input("x", 1); k.output("x", 1) }.contains("declared twice"))
    assert(rejected { k =>
      val x = k.input("x", 2); k.output("out", 1)(0) = x(2)
    }.contains("outside"))
    assert(rejected { k => k.output("out", 1)(1) = k.const(0) }.contains("outside"))
    assert(rejected { k =>
      val o = k.output("out", 1); o(0) = k.const(0); o(0) = k.const(1)
    }.contains("twice"))
    assert(rejected { k =>
      val o = k.output("out", 2); o(0) = k.const(0)
    }.contains("not assigned at 1"))
    assert(rejected(k => k.input("x", 0)).contains("length 0"))
    assert(rejected(k => k.input("X", 1)).contains("invalid name"))
  }

  test("inputs of the wrong shape are reported, not evaluated") {
    val s = Benchmarks.saxpy(2)
    assert(
      Interpreter
        .run(s, Values(Map("x" -> Vector(1L), "y" -> Vector(1L, 2L)), Map("a" -> 1L)))
        .isLeft
    )
    assert(Interpreter.run(s, Values(Map("x" -> Vector(1L, 2L), "y" -> Vector(1L, 2L)))).isLeft)
  }

  test("fixed-order layout: inputs, scalars, constants, then outputs, from address 0") {
    val k = Kernel("order") { k =>
      val x = k.input("x", 3)
      val a = k.scalar("a")
      val out = k.output("out", 2)
      out(0) = x(0) * k.const(7) + a
      out(1) = x(2) - k.const(9) + k.const(7)
    }
    val l = MemoryLayout.fixedOrder(k, machine).fold(e => fail(e), identity)
    assert((0 until 3).map(l.elem("x", _)) == Seq(0, 1, 2))
    assert(l.scalar("a") == 3)
    assert(l.constant(7L) == 4 && l.constant(9L) == 5)
    assert(l.elem("out", 0) == 6 && l.elem("out", 1) == 7 && l.words == 8)
    val image =
      l.memoryImage(Values(Map("x" -> Vector(10L, 11L, 12L)), Map("a" -> 1L))).toOption.get
    assert(image == Map(0 -> 10L, 1 -> 11L, 2 -> 12L, 3 -> 1L, 4 -> 7L, 5 -> 9L))
    val in = l.inputs(Values(Map("x" -> Vector(10L, 11L, 12L)), Map("a" -> 1L))).toOption.get
    assert(in.registers.isEmpty && in.memory == image)
  }

  test("a kernel that does not fit the scratchpad is rejected by the layout") {
    val v = ujson.read(machine.canonicalJson)
    v("scratchpad")("words_per_bank") = 4 // 8 words
    val small = MachineDescription.parse(ujson.write(v)).fold(es => fail(es.mkString), identity)
    assert(MemoryLayout.fixedOrder(Benchmarks.saxpy(2), small).isRight) // a, x, y, out: 7 words
    assert(
      MemoryLayout.fixedOrder(Benchmarks.saxpy(3), small).left.exists(_.contains("needs 10 words"))
    )
  }

  test("lowering reads only initialised words, writes each output once, and keeps the meaning") {
    val rng = new Random(4747L)
    Benchmarks.all.foreach { k =>
      val layout = MemoryLayout.fixedOrder(k, machine).fold(e => fail(e), identity)
      val lowered = Lowering.lower(layout)
      def reads(v: LoweredKernel.Value): Set[Int] = v match {
        case LoweredKernel.Read(a)      => Set(a)
        case LoweredKernel.Bin(_, a, b) => reads(a) ++ reads(b)
      }
      val outputAddrs = k.outputs.flatMap(o => (0 until o.length).map(layout.elem(o.name, _)))
      assert(lowered.writes.map(_.addr).sorted == outputAddrs.sorted, k.name)
      (0 until 20).foreach { _ =>
        val in = values(k, rng)
        val image = layout.memoryImage(in).fold(e => fail(e), identity)
        assert(lowered.writes.flatMap(w => reads(w.value)).toSet.subsetOf(image.keySet), k.name)
        val after = Lowering.evaluate(lowered, image)
        assert(layout.outputs(after) == run(k, in), k.name)
      }
    }
  }

  test("the benchmark sizes fit the default machine") {
    Benchmarks.all.foreach(k => assert(MemoryLayout.fixedOrder(k, machine).isRight, k.name))
  }
}
