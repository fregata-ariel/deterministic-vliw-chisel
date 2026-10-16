package vliw.crosscheck

import scala.util.Random

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.{CompiledKernel, KernelCompiler}
import vliw.compiler.dag.{Dag, Fusion}
import vliw.compiler.dsl._
import vliw.hw.{Core, CoreDriver, CoreParams, VliwChiselSim}
import vliw.isa._
import vliw.sim.ReferenceModel

/** The benchmark kernels, scheduled and register-allocated, run on the reference model and the RTL:
  * the outputs must equal the DSL reference implementation, in the scheduled cycles.
  */
class CompiledKernelSpec extends AnyFunSuite with VliwChiselSim {
  private val machine = MachineDescription.load().fold(es => fail(es.mkString("\n")), identity)
  private def machineFile(f: String) = MachineDescription
    .load(java.nio.file.Paths.get("tests", "machines", f))
    .fold(es => fail(es.mkString), identity)

  /** 8 and 12 registers, the default latencies (#70): the RTL implements them. */
  private val fewerRegisters = Seq("regs8.json", "regs12.json").map(machineFile)

  /** `KernelCompiler.compile`: the DAG of `KernelCompiler.prepare` (MUL + ADD fused into MAC on ISA
    * mvp-1), list scheduling and register allocation, and the final verifier check.
    */
  private def compile(k: Kernel, m: MachineDescription): CompiledKernel =
    KernelCompiler.compile(k, m).fold(e => fail(s"${k.name} on ${m.machineId}: $e"), identity)

  private def values(k: Kernel, rng: Random) = Values(
    k.inputs.map(a => a.name -> Vector.fill(a.length)(rng.nextLong() & 0xffffffffL)).toMap,
    k.scalars.map(_ -> (rng.nextLong() & 0xffffffffL)).toMap
  )

  /** Runs `c` on the reference model with random inputs: the DSL results in the scheduled cycles.
    */
  private def onModel(c: CompiledKernel, rng: Random, runs: Int): Unit =
    (0 until runs).foreach { _ =>
      val (k, m) = (c.kernel, c.machine)
      val in = values(k, rng)
      val inputs = c.layout.inputs(in).fold(e => fail(e), identity)
      val run = ReferenceModel
        .run(m, c.words, inputs)
        .fold(v => fail(s"${k.name} on ${m.machineId}: $v"), identity)
      assert(run.cycles == c.cycles)
      val out = c.layout.outputs(a => run.memory(a).getOrElse(fail(s"[$a] undefined")))
      assert(
        out == Interpreter.run(k, in).fold(e => fail(e), identity),
        s"${k.name} on ${m.machineId}"
      )
    }

  /** Runs `c` on the core `dut` built for `m` with random inputs: the DSL results in the scheduled
    * cycles.
    */
  private def onRtl(dut: Core, m: MachineDescription, c: CompiledKernel, rng: Random, runs: Int) = {
    val k = c.kernel
    val outAddrs = k.outputs.flatMap(o => (0 until o.length).map(c.layout.elem(o.name, _))).toSet
    (0 until runs).foreach { _ =>
      val in = values(k, rng)
      val inputs = c.layout.inputs(in).fold(e => fail(e), identity)
      val run = CoreDriver.run(dut, m, c.words, inputs, outAddrs)
      assert(run.violations.isEmpty && run.cycles == c.cycles, k.name)
      val out = c.layout.outputs(a => run.memory(a).toLong)
      assert(out == Interpreter.run(k, in).fold(e => fail(e), identity), k.name)
    }
  }

  test("on the reference model, compiled kernels compute the DSL results in the scheduled cycles") {
    val rng = new Random(50L)
    val machines =
      Seq(machine) ++ Seq("slow_units.json", "ii_units.json").map(machineFile) ++ fewerRegisters
    for (m <- machines; k <- Benchmarks.all) onModel(compile(k, m), rng, 10)
  }

  // One test per machine: each simulation builds its own core (as in RtlDifferentialSpec).
  (machine +: fewerRegisters).zipWithIndex.foreach { case (m, i) =>
    test(
      s"on the RTL, compiled kernels compute the DSL results in the scheduled cycles: ${m.machineId}"
    ) {
      val rng = new Random(5050L + i)
      simulate(new Core(CoreParams(m))) { dut =>
        for (k <- Benchmarks.all) onRtl(dut, m, compile(k, m), rng, 5)
      }
    }
  }

  // ISA mvp-1 (#77): three read ports, four (the reference model only) and MAC latency 3, each at
  // 16, 12 and 8 registers.
  private val mvp1 =
    MachineDescription.load(MachineDescription.Mvp1Path).fold(es => fail(es.mkString), identity)
  private val rp4 = machineFile("mvp1_rp4.json")
  private val mac3 = machineFile("mvp1_mac3.json")
  private def withRegisters(m: MachineDescription, n: Int) = {
    val v = ujson.read(m.canonicalJson)
    v("register_file")("count") = n
    MachineDescription.parse(ujson.write(v)).fold(es => fail(es.mkString), identity)
  }
  private def macStore(b: Bundle) =
    b.compute.isInstanceOf[ComputeOp.Mac] && b.memory.isInstanceOf[MemoryOp.St]

  /** The kernels compiled for `base` at 16, 12 and 8 registers. Each has one MAC instruction per
    * MUL + ADD pair of its unfused DAG (`Fusion.pairs`), and no MAC next to a ST on three read
    * ports (L11).
    */
  private def compiledMvp1(base: MachineDescription): Seq[CompiledKernel] =
    for (r <- Seq(16, 12, 8); k <- Benchmarks.all) yield {
      val m = withRegisters(base, r)
      val c = compile(k, m)
      val where = s"${k.name} on ${m.machineId} with $r registers (${c.allocated.attempt})"
      val pairs = Fusion.pairs(Dag.of(Lowering.lower(c.layout), m)).size
      assert(pairs > 0 && c.bundles.count(_.compute.isInstanceOf[ComputeOp.Mac]) == pairs, where)
      assert(m.readPorts == 4 || !c.bundles.exists(macStore), where)
      c
    }

  test(
    "ISA mvp-1: compiled kernels with MAC compute the DSL results in the scheduled cycles on the reference model, at 16, 12 and 8 registers"
  ) {
    val rng = new Random(77L)
    for (base <- Seq(mvp1, rp4, mac3)) {
      val code = compiledMvp1(base)
      code.foreach(onModel(_, rng, 10))
      // Four read ports let MAC and ST share a bundle.
      if (base.readPorts == 4) assert(code.exists(_.bundles.exists(macStore)))
    }
  }

  // One core per machine, with 16 registers: the code for 12 and 8 registers reads and writes only
  // r0..r11 and r0..r7, so it runs there with the same timing.
  Seq(mvp1, mac3).zipWithIndex.foreach { case (m, i) =>
    test(
      s"ISA mvp-1, on the RTL, compiled kernels with MAC compute the DSL results in the scheduled cycles: ${m.machineId} (code for 16, 12 and 8 registers)"
    ) {
      assert(CoreParams.check(m).isRight && CoreParams.check(rp4).isLeft)
      val rng = new Random(7777L + i)
      val code = compiledMvp1(m)
      simulate(new Core(CoreParams(m)))(dut => code.foreach(onRtl(dut, m, _, rng, 3)))
    }
  }
}
