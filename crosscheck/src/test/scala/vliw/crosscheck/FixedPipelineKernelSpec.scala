package vliw.crosscheck

import scala.util.Random

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.KernelCompiler
import vliw.compiler.dsl.{Benchmarks, Interpreter, Kernel, MemoryLayout, Values, Word}
import vliw.hw.{FixedDriver, FixedOp, FixedPipeline, FixedPlan, VliwChiselSim}
import vliw.isa.MemoryOp

/** The fixed pipelines of the benchmark kernels (#75) under ChiselSim: the outputs of the DSL
  * reference implementation for random inputs, in the planned cycles, with the planned issue and
  * commit cycles; FIR's coefficients are words of the scratchpad, not part of the circuit.
  *
  * Two plans per kernel: the list scheduler's without a register limit, and the kernel's VLIW
  * program (its schedule and registers, without instruction memory, decoder and register file).
  */
class FixedPipelineKernelSpec extends AnyFunSuite with VliwChiselSim {
  private val machine = BenchmarkRecords.machine
  private val vliw =
    Benchmarks.all.map(k => KernelCompiler.compile(k, machine).fold(e => fail(e), identity))
  private val planned =
    Benchmarks.all.map(k => FixedPipelines.planned(k, machine).fold(e => fail(e), identity))
  private val burntIn = vliw.map(FixedPipelines.burntIn)
  private val Runs = 8

  /** Random full 32-bit inputs, then all zeros and all ones. */
  private def inputs(k: Kernel): Seq[Values] = {
    def filled(v: Long) = Values(
      k.inputs.map(a => a.name -> Vector.fill(a.length)(v)).toMap,
      k.scalars.map(_ -> v).toMap
    )
    (0 until Runs).map(i => KernelCompiler.sampleInputs(k, 1000L + i)) ++
      Seq(filled(0L), filled(Word.Mask))
  }

  test("every plan is legal and touches the same words as the kernel's VLIW program") {
    (planned ++ burntIn).foreach { p =>
      val name = s"${p.kernel.name} (${p.strategy})"
      assert(FixedPlan.violations(machine, p.plan).isEmpty, name)
      val layout = p.prepared.layout
      val loads = p.plan.ops.collect { case FixedOp.Load(_, a) => a }
      val stores = p.plan.ops.collect { case FixedOp.Store(_, a, _) => a }
      // Every value the kernel reads (inputs, scalars, constants) is read from its layout address.
      assert(loads.toSet == layout.initialized, name)
      assert(stores.toSet == layout.outputAddresses, name)
      val ops = vliw.find(_.kernel == p.kernel).get.bundles.map(_.memory)
      assert(ops.collect { case MemoryOp.Ld(_, a) => a }.toSet == loads.toSet, name)
      assert(ops.collect { case MemoryOp.St(a, _) => a }.toSet == stores.toSet, name)
      // Cycle T of the last write plays the HALT: the same schedule takes the same cycles.
      assert(p.params.completion == p.schedule.halt && p.cycles == p.schedule.cycles, name)
    }
    vliw.zip(burntIn).foreach { case (c, b) =>
      assert(b.cycles == c.cycles, c.kernel.name)
      assert(b.params.registers <= machine.registerCount, c.kernel.name)
    }
    planned.zip(burntIn).foreach { case (p, b) => assert(p.cycles <= b.cycles, p.kernel.name) }
  }

  (planned ++ burntIn).foreach { p =>
    val k = p.kernel
    val layout = p.prepared.layout
    test(s"${k.name}: DSL reference outputs in ${p.cycles} cycles (${p.strategy})") {
      simulate(new FixedPipeline(p.params)) { dut =>
        def check(image: Map[Int, Long], expected: Values, first: Boolean, what: String): Unit = {
          val run = FixedDriver.run(dut, image, layout.outputAddresses, reset = first, extra = 2)
          val problems = FixedPipelines.mismatches(p, run, expected)
          assert(problems.isEmpty, s"$what: ${problems.mkString("; ")}")
        }
        inputs(k).zipWithIndex.foreach { case (in, i) =>
          val image = layout.memoryImage(in).fold(e => fail(e), identity)
          check(image, Interpreter.run(k, in).fold(e => fail(e), identity), i == 0, s"input $i")
        }
        if (k.name == "fir4x8") coefficients(layout, check)
      }
    }
  }

  test("a VLIW program that copies LDs (matmul3, 8 registers) burnt in: its own DAG and cycles") {
    val m = RegisterRecords.machineOf(8)
    val c = KernelCompiler.compile(Benchmarks.matmul(3), m).fold(e => fail(e), identity)
    assert(c.allocated.dag.loads > c.dag.loads, "the chosen attempt copies LDs")
    val b = FixedPipelines.burntIn(c)
    assert(b.plan.ops.count(_.isInstanceOf[FixedOp.Load]) == c.allocated.dag.loads)
    assert(b.cycles == c.cycles && b.params.registers <= m.registerCount)
    simulate(new FixedPipeline(b.params)) { dut =>
      inputs(c.kernel).take(3).zipWithIndex.foreach { case (in, i) =>
        val image = c.layout.memoryImage(in).fold(e => fail(e), identity)
        val run = FixedDriver.run(dut, image, c.layout.outputAddresses, reset = i == 0)
        assert(run.cycles == c.cycles, s"input $i")
        assert(
          c.layout.outputs(a => run.memory(a).toLong) ==
            Interpreter.run(c.kernel, in).fold(e => fail(e), identity),
          s"input $i"
        )
      }
    }
  }

  /** The FIR circuit with other coefficient words at the layout's coefficient addresses. */
  private def coefficients(
      layout: MemoryLayout,
      check: (Map[Int, Long], Values, Boolean, String) => Unit
  ): Unit = {
    val taps = Seq(1L, 2L, 3L, 4L)
    val built =
      KernelCompiler.prepare(Benchmarks.fir(taps, 8), machine).fold(e => fail(e), identity)
    val rng = new Random(75L)
    val distinct = Seq(Seq(5L, 6L, 7L, 8L), Seq(Word.Mask, 0x80000000L, 0L, 7L)) ++
      Seq.fill(3)(
        Iterator.continually(Seq.fill(4)(rng.nextLong() & Word.Mask)).find(_.distinct.size == 4).get
      )
    val x = KernelCompiler.sampleInputs(built.kernel, 2026L)
    distinct.foreach { h =>
      val other = KernelCompiler.prepare(Benchmarks.fir(h, 8), machine).fold(e => fail(e), identity)
      // Distinct taps: the same addresses and the same DAG, so the same plan and circuit.
      assert(other.layout.arrays == layout.arrays && other.layout.words == layout.words)
      assert(h.map(other.layout.constant) == taps.map(layout.constant))
      assert(other.dag.nodes == built.dag.nodes && other.dag.edges == built.dag.edges)
      val image = other.layout.memoryImage(x).fold(e => fail(e), identity)
      check(image, Interpreter.run(other.kernel, x).fold(e => fail(e), identity), false, s"taps $h")
    }
    // Equal taps share one word in their own layout, which moves the outputs: another layout and
    // another DAG. This circuit still computes them from four words at its own addresses.
    val equal = Seq(2L, 2L, 3L, 3L)
    val merged =
      KernelCompiler.prepare(Benchmarks.fir(equal, 8), machine).fold(e => fail(e), identity)
    assert(merged.layout.constants.size == 2 && merged.layout.words == layout.words - 2)
    assert(merged.dag.loads == built.dag.loads - 2)
    val image = layout.memoryImage(x).fold(e => fail(e), identity) ++
      taps.zip(equal).map { case (t, v) => layout.constant(t) -> v }
    check(
      image,
      Interpreter.run(merged.kernel, x).fold(e => fail(e), identity),
      false,
      s"taps $equal"
    )
  }
}
