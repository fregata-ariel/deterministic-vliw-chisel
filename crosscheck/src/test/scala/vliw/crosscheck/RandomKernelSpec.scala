package vliw.crosscheck

import scala.util.Random

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.{CompiledKernel, KernelCompiler}
import vliw.compiler.dag.Dag
import vliw.compiler.dsl._
import vliw.hw.{Core, CoreDriver, CoreParams, VliwChiselSim}
import vliw.isa._
import vliw.sim.ReferenceModel

/** Many random DSL kernels through the whole compiler: every one passes the final verification
  * (KernelCompiler stops otherwise), and the generated code computes the DSL reference results in
  * the predicted cycles, on the reference model and on the RTL.
  */
class RandomKernelSpec extends AnyFunSuite with VliwChiselSim {
  private val machine = MachineDescription.load().fold(es => fail(es.mkString("\n")), identity)

  /** 400 kernels of `RandomKernels` (one or two inputs of 1 to 6 words, maybe a scalar, one or two
    * outputs of 1 to 4 words, and expression trees of depth up to 3 over ADD, SUB and MUL with
    * elements, scalars and constants), compiled for `m`.
    */
  private def compileAll(m: MachineDescription): Vector[CompiledKernel] = {
    val rng = new Random(51L)
    (0 until 400)
      .map(i =>
        KernelCompiler.compile(RandomKernels.kernel(rng, i), m).fold(e => fail(e), identity)
      )
      .toVector
  }

  private lazy val compiled: Vector[CompiledKernel] = compileAll(machine)

  /** Runs each kernel `runs` times on the reference model: the DSL results in the predicted cycles.
    */
  private def onModel(code: Vector[CompiledKernel], rng: Random, runs: Int): Unit =
    code.foreach { c =>
      (0 until runs).foreach { _ =>
        val in = KernelCompiler.sampleInputs(c.kernel, rng.nextLong())
        val run = ReferenceModel
          .run(c.machine, c.words, c.layout.inputs(in).fold(e => fail(e), identity))
          .fold(v => fail(s"${c.kernel.name}: $v\n${Disassembler.program(c.bundles)}"), identity)
        assert(run.cycles == c.cycles, c.kernel.name)
        val out = c.layout.outputs(a => run.memory(a).getOrElse(fail(s"[$a] undefined")))
        assert(out == Interpreter.run(c.kernel, in).fold(e => fail(e), identity), c.kernel.name)
      }
    }

  /** The test case of each kernel (`machinePath` names its machine) holds on the reference model.
    */
  private def testCasesHold(code: Vector[CompiledKernel], machinePath: Option[String]): Unit =
    code.foreach { c =>
      val json = KernelCompiler
        .testCase(c, KernelCompiler.sampleInputs(c.kernel, 3L), machinePath)
        .fold(e => fail(e), identity)
      val tc = TestCase.parse(ujson.write(json)).fold(es => fail(es.mkString("\n")), identity)
      assert(tc.machine == c.machine, c.kernel.name)
      val run =
        ReferenceModel.run(tc.machine, tc.words, tc.inputs).fold(v => fail(v.toString), identity)
      assert(tc.expect.cycles.contains(run.cycles))
      tc.expect.memory.foreach { case (a, v) =>
        assert(run.memory(a).contains(v), s"${c.kernel.name} [$a]")
      }
    }

  /** Runs each kernel once on the core `dut` built for `m`: the DSL results in the predicted
    * cycles.
    */
  private def onRtl(dut: Core, m: MachineDescription, code: Vector[CompiledKernel], rng: Random) =
    code.foreach { c =>
      val in = KernelCompiler.sampleInputs(c.kernel, rng.nextLong())
      val run = CoreDriver.run(
        dut,
        m,
        c.words,
        c.layout.inputs(in).fold(e => fail(e), identity),
        c.layout.outputAddresses
      )
      assert(run.violations.isEmpty && run.cycles == c.cycles, c.kernel.name)
      val out = c.layout.outputs(a => run.memory(a).toLong)
      assert(out == Interpreter.run(c.kernel, in).fold(e => fail(e), identity), c.kernel.name)
    }

  test("400 random kernels compile and compute the DSL results on the reference model") {
    onModel(compiled, new Random(5151L), 3)
  }

  test("the generated test cases hold on the reference model") {
    testCasesHold(compiled.take(100), None)
  }

  test("60 random kernels compute the DSL results on the RTL in the predicted cycles") {
    val rng = new Random(515151L)
    simulate(new Core(CoreParams(machine)))(onRtl(_, machine, compiled.take(60), rng))
  }

  // ISA mvp-1 (#77): the same kernels with MUL + ADD fused into MAC, on three read ports and on four
  // (the reference model only).
  private val mvp1Path = MachineDescription.Mvp1Path.toString
  private val rp4Path = java.nio.file.Paths.get("tests", "machines", "mvp1_rp4.json").toString
  private def load(path: String) = MachineDescription
    .load(java.nio.file.Paths.get(path))
    .fold(es => fail(es.mkString("\n")), identity)
  private val mvp1 = load(mvp1Path)
  private val rp4 = load(rp4Path)
  private lazy val onMvp1 = compileAll(mvp1)
  private lazy val onRp4 = compileAll(rp4)

  private def macs(c: CompiledKernel) = c.bundles.count(_.compute.isInstanceOf[ComputeOp.Mac])
  private def macStore(b: Bundle) =
    b.compute.isInstanceOf[ComputeOp.Mac] && b.memory.isInstanceOf[MemoryOp.St]

  test(
    "ISA mvp-1: 400 random kernels compile with MAC and compute the DSL results on the reference model, on 3 and 4 read ports"
  ) {
    val rng = new Random(7751L)
    for (code <- Seq(onMvp1, onRp4)) {
      val m = code.head.machine
      // One MAC instruction per fused pair, and a MUL instruction for every other MUL of the
      // unfused DAG: one with several readers stays a MUL, also when an ADD reads it.
      val shared = code.count { c =>
        val unfused = Dag.of(Lowering.lower(c.layout), m)
        val muls = unfused.nodes.indices.filter(unfused.nodes(_).mnemonic == "MUL")
        val mulCode = c.bundles.count(_.compute.isInstanceOf[ComputeOp.Mul])
        assert(macs(c) == c.dag.macs && mulCode == muls.size - c.dag.macs, c.kernel.name)
        val readers = muls.map(unfused.readers)
        readers.exists(r => r.size > 1 && r.exists(unfused.nodes(_).mnemonic == "ADD"))
      }
      assert(code.count(macs(_) > 0) > 100 && shared > 5, s"${m.machineId}: $shared")
      val together = code.count(_.bundles.exists(macStore))
      assert(if (m.readPorts == 3) together == 0 else together > 0, m.machineId)
      onModel(code, rng, 3)
    }
  }

  test("ISA mvp-1: the generated test cases hold on the reference model") {
    testCasesHold(onMvp1.take(100), Some(mvp1Path))
    testCasesHold(onRp4.take(100), Some(rp4Path))
  }

  test("ISA mvp-1: 60 random kernels with MAC compute the DSL results on the RTL (machine-mvp1)") {
    assert(CoreParams.check(mvp1).isRight && CoreParams.check(rp4).isLeft)
    val rng = new Random(77515151L)
    val withMac = onMvp1.filter(macs(_) > 0).take(60)
    assert(withMac.size == 60)
    simulate(new Core(CoreParams(mvp1)))(onRtl(_, mvp1, withMac, rng))
  }
}
