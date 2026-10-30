package vliw.compiler

import java.nio.file.Paths

import scala.util.Random

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.dag.Node
import vliw.compiler.dsl._
import vliw.isa._

/** The compiler on ISA mvp-1 (#77): MUL + ADD fused into MAC, rule L11, MAC in the code. */
class MacCompilerSpec extends AnyFunSuite {
  private def load(path: String*) = MachineDescription
    .load(Paths.get(path.head, path.tail: _*))
    .fold(es => fail(es.mkString("\n")), identity)
  private val mvp0 = MachineDescription.load().fold(es => fail(es.mkString("\n")), identity)
  private val mvp1 = load("config", "machine-mvp1.json")
  private val rp4 = load("tests", "machines", "mvp1_rp4.json")
  private val mac3 = load("tests", "machines", "mvp1_mac3.json")
  private def withRegisters(m: MachineDescription, n: Int) = {
    val v = ujson.read(m.canonicalJson)
    v("register_file")("count") = n
    MachineDescription.parse(ujson.write(v)).fold(es => fail(es.mkString), identity)
  }

  private def compiled(k: Kernel, m: MachineDescription) =
    KernelCompiler.compile(k, m).fold(e => fail(s"${k.name} on ${m.machineId}: $e"), identity)

  /** The outputs of the compiled program on `in` equal the DSL reference implementation's. The
    * program runs on `IsaExecutor` (docs/ISA.md, independently of the compiler); mvp-1 code has no
    * W operation.
    */
  private def computes(c: CompiledKernel, in: Values): Boolean = {
    val image = c.layout.memoryImage(in).fold(e => fail(e), identity)
    assert(!c.bundles.exists(b => WCompilerSpec.isW(b)), c.kernel.name)
    val mem = IsaExecutor.run(c.machine, c.bundles, image)
    c.layout.outputs(mem) == Interpreter.run(c.kernel, in).fold(e => fail(e), identity)
  }

  private def macStore(b: Bundle) =
    b.compute.isInstanceOf[ComputeOp.Mac] && b.memory.isInstanceOf[MemoryOp.St]

  test("the benchmarks compile on the mvp-1 machines at 16, 12 and 8 registers") {
    val rng = new Random(77L)
    for (base <- Seq(mvp1, rp4, mac3); r <- Seq(16, 12, 8); k <- Benchmarks.all) {
      val m = withRegisters(base, r)
      val c = compiled(k, m)
      val label = s"${k.name} on ${m.machineId} with $r registers (${c.allocated.attempt})"
      assert(Verifier.verify(m, c.words, KernelCompiler.declaration(c.layout)).isEmpty, label)
      // One MAC instruction per MAC node, with its registers; none next to a ST on 3 read ports.
      assert(c.bundles.count(_.compute.isInstanceOf[ComputeOp.Mac]) == c.dag.macs, label)
      val reg = c.allocated.allocation.register
      c.allocated.dag.nodes.zipWithIndex.foreach {
        case (Node.Mac(a, b, x), i) =>
          val at = c.allocated.schedule.issue(i)
          assert(c.bundles(at).compute == ComputeOp.Mac(reg(i), reg(a), reg(b), reg(x)), label)
        case _ => ()
      }
      if (m.readPorts == 3) assert(!c.bundles.exists(macStore), label)
      (0 until 3).foreach(_ => assert(computes(c, RandomKernels.values(k, rng)), label))
      // Compiling again gives the same program.
      assert(compiled(k, m).words == c.words, label)
    }
  }

  test("best cycles and LDs per kernel next to mvp-0, at 16, 12 and 8 registers") {
    val got = for {
      m <- Seq(mvp0, mvp1, rp4, mac3); r <- Seq(16, 12, 8); k <- Benchmarks.all
    } yield {
      val c = compiled(k, withRegisters(m, r))
      (m.machineId, r, k.name) -> (c.cycles, c.allocated.dag.loads)
    }
    // Recorded so that changes show up (docs/COMPILER.md). ADR-0018 estimated fir4x8 65 -> 40 and
    // matmul3 56 -> 39 (three read ports) or 33 (four); saxpy8 26 -> 27 (three) or 26 (four).
    // With four read ports, fir4x8 gets 38 from an attempt that copies LDs.
    val (v0, v1, v4, v3) = (mvp0.machineId, mvp1.machineId, rp4.machineId, mac3.machineId)
    assert(
      got.toMap == Map(
        (v0, 16, "saxpy8") -> (26, 17),
        (v0, 16, "fir4x8") -> (65, 15),
        (v0, 16, "matmul3") -> (56, 18),
        (v0, 12, "saxpy8") -> (26, 17),
        (v0, 12, "fir4x8") -> (66, 45),
        (v0, 12, "matmul3") -> (55, 37),
        (v0, 8, "saxpy8") -> (28, 17),
        (v0, 8, "fir4x8") -> (70, 55),
        (v0, 8, "matmul3") -> (61, 44),
        (v1, 16, "saxpy8") -> (27, 17),
        (v1, 16, "fir4x8") -> (40, 15),
        (v1, 16, "matmul3") -> (39, 18),
        (v1, 12, "saxpy8") -> (27, 17),
        (v1, 12, "fir4x8") -> (40, 15),
        (v1, 12, "matmul3") -> (39, 24),
        (v1, 8, "saxpy8") -> (27, 17),
        (v1, 8, "fir4x8") -> (40, 29),
        (v1, 8, "matmul3") -> (49, 32),
        (v4, 16, "saxpy8") -> (26, 17),
        (v4, 16, "fir4x8") -> (38, 27),
        (v4, 16, "matmul3") -> (33, 18),
        (v4, 12, "saxpy8") -> (26, 17),
        (v4, 12, "fir4x8") -> (40, 15),
        (v4, 12, "matmul3") -> (38, 24),
        (v4, 8, "saxpy8") -> (26, 17),
        (v4, 8, "fir4x8") -> (40, 29),
        (v4, 8, "matmul3") -> (49, 32),
        (v3, 16, "saxpy8") -> (27, 17),
        (v3, 16, "fir4x8") -> (44, 27),
        (v3, 16, "matmul3") -> (40, 18),
        (v3, 12, "saxpy8") -> (27, 17),
        (v3, 12, "fir4x8") -> (44, 32),
        (v3, 12, "matmul3") -> (44, 32),
        (v3, 8, "saxpy8") -> (27, 17),
        (v3, 8, "fir4x8") -> (53, 15),
        (v3, 8, "matmul3") -> (55, 38)
      )
    )
  }

  test("four read ports put MAC and ST in one bundle; three read ports reject that code (L11)") {
    val c = compiled(Benchmarks.matmul(3), rp4)
    val both = c.bundles.indices.filter(i => macStore(c.bundles(i)))
    assert(both.nonEmpty)
    // The same words on the machine with three read ports: one L11 finding per such bundle.
    val findings = Verifier.verify(mvp1, c.words, KernelCompiler.declaration(c.layout))
    assert(findings.map(_.rule) == both.map(_ => Rule.ReadPorts))
    assert(findings.flatMap(_.cycle) == both)
  }

  test("random kernels compile on the mvp-1 machines and compute the DSL results") {
    val rng = new Random(7777L)
    val kernels = RandomKernels.kernels(51L, 300)
    for (m <- Seq(mvp1, rp4, mac3); k <- kernels) {
      val c = compiled(k, m)
      if (m.readPorts == 3) assert(!c.bundles.exists(macStore), k.name)
      assert(computes(c, RandomKernels.values(k, rng)), s"${k.name} on ${m.machineId}")
    }
    // The same kernels on mvp-0, as a check of the execution above.
    kernels.take(50).foreach(k => assert(computes(compiled(k, mvp0), RandomKernels.values(k, rng))))
  }
}
