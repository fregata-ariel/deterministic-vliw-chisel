package vliw.crosscheck

import scala.util.Random

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.{CompiledKernel, FallbackReason, KernelCompiler, Simd, SimdCandidate}
import vliw.compiler.WCompilerSpec
import vliw.compiler.dag.{Dag, Src}
import vliw.compiler.dsl._
import vliw.compiler.regalloc.{NoFit, RegisterAllocator}
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

  // ISA mvp-2 (W, #110 stage 4; ADR-0023): the same kernels packed into W pairs, on W-full, W-reg
  // and a W-full variant with 6 registers (fragmentation and kernel fallbacks), on the reference
  // model. The RTL runs compiled W programs in the builds of RtlMvp2Spec.
  private val mw1Path = java.nio.file.Paths.get("tests", "machines", "mvp2_mw1.json").toString
  private val full = load(MachineDescription.Mvp2Path.toString)
  private val mw1 = load(mw1Path)
  private def withRegisters(m: MachineDescription, n: Int) = {
    val v = ujson.read(m.canonicalJson)
    v("register_file")("count") = n
    MachineDescription.parse(ujson.write(v)).fold(es => fail(es.mkString), identity)
  }
  private val six = withRegisters(full, 6)
  private val mvp1six = withRegisters(mvp1, 6)
  private lazy val kernels = RandomKernels.kernels(51L, 400)
  private lazy val onFull = compileAll(full)
  private lazy val onMw1 = compileAll(mw1)
  // Six registers: a kernel may not fit, but only when it does not fit mvp-1 with 6 registers either.
  private lazy val onSix: Vector[(Kernel, Option[CompiledKernel])] = kernels.map { k =>
    val c = KernelCompiler.compile(k, six)
    c.left.foreach { e =>
      assert(!e.startsWith("compiler error") && e.contains("packed DAG"), s"${k.name}: $e")
      assert(KernelCompiler.compile(k, mvp1six).isLeft, s"${k.name} fits mvp-1 with 6 registers")
    }
    k -> c.toOption
  }

  private def packing(c: CompiledKernel) = c.simd match {
    case Simd.Packed(p)      => Some(p)
    case Simd.Fallback(_, p) => Some(p)
    case Simd.Scalar         => None
  }

  test(
    "ISA mvp-2: 400 random kernels compile packed or with a fallback and compute the DSL results on the reference model (W-full, W-reg, 6 registers)"
  ) {
    val rng = new Random(110L)
    val scalarCycles = Map(
      full.hash -> onMvp1.map(_.cycles),
      mw1.hash -> onMvp1.map(_.cycles),
      six.hash -> kernels.map(k => KernelCompiler.compile(k, mvp1six).fold(_ => -1, _.cycles))
    )
    val counts = collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    for ((m, code) <- Seq(full -> onFull, mw1 -> onMw1, six -> onSix.flatMap(_._2))) {
      val tag = m.machineId.stripPrefix("vliw2-i32-sp2-mac-") + s"/${m.registerCount}"
      def count(what: String) = counts(s"$tag $what") += 1
      code.foreach { c =>
        val label = s"${c.kernel.name} on $tag"
        c.simd match {
          case Simd.Packed(p) =>
            count("packed")
            if (p.vectorOperations > 0) count("vector")
            if (p.scalarLanes.nonEmpty) count("lane fallback")
            if (p.broadcasts > 0) count("broadcast")
            if (p.gathers > 0) count("gather")
            if (p.copies > 0) count("LD copy")
            if (m.hasWideMemory && p.pairs.exists(_.root.isInstanceOf[Src.Gather]))
              count("root gather ST.W")
            if (
              c.bundles.exists(b =>
                b.memory match { case MemoryOp.LdW(_, a) => a % 2 == 1; case _ => false }
              )
            )
              count("odd LD.W")
            if (
              c.bundles.exists(b =>
                b.memory match { case MemoryOp.StW(a, _) => a % 2 == 1; case _ => false }
              )
            )
              count("odd ST.W")
            if (c.allocated.dag.nodes.count(_.mnemonic == "LD.W") > p.count("LD.W"))
              count("LD.W copied")
            assert(c.dag == p.dag && c.bundles.exists(b => WCompilerSpec.isW(b)), label)
            val scalar = scalarCycles(m.hash)(c.kernel.name.stripPrefix("random").toInt)
            if (scalar >= 0 && c.cycles > scalar) count("slower than scalar")
          case Simd.Fallback(r, p) =>
            count(r match {
              case FallbackReason.NothingToPack    => "NothingToPack"
              case FallbackReason.NoAttemptFits(_) => "NoAttemptFits"
            })
            assert(c.dag == p.scalar && !c.bundles.exists(b => WCompilerSpec.isW(b)), label)
          case Simd.Scalar => fail(s"$label: not marked as an mvp-2 compile")
        }
        if (!m.hasWideMemory) assert(!c.bundles.exists(b => WCompilerSpec.wideMemory(b)), label)
        packing(c).foreach { p =>
          p.scalarLanes.foreach(l => count(l.reason.name))
          // Fragmentation on 6 registers: attempts of the packed DAG that fail at allocation.
          if (m == six && !p.nothingToPack)
            RegisterAllocator.allAttempts(p.dag).foreach {
              case (_, Left(_: NoFit.NoAlignedPair)) => count("NoAlignedPair attempt")
              case _                                 => ()
            }
        }
      }
      counts(s"$tag fits") = code.size
      onModel(code, rng, 3)
    }
    info(counts.toSeq.sorted.map { case (k, v) => s"$k $v" }.mkString(", "))
    // About half of the counts measured when they were set (#110 stage 4); the seeds are fixed.
    // The random kernels rarely broadcast or copy a LD.W (no two lanes share a multiplicand, few
    // windows); the benchmarks do both (WCompilerSpec, RematerializationSpec). On W-full most
    // pairs are ST.W of a root gather (ADR-0023 rules 4 and 5, Q2).
    val minimum = Map(
      "w2/16 packed" -> 176,
      "w2/16 vector" -> 39,
      "w2/16 lane fallback" -> 80,
      "w2/16 NothingToPack" -> 24,
      "w2/16 NoNeighbour" -> 100,
      "w2/16 SameValue" -> 3,
      "w2/16 NeededElsewhere" -> 1,
      "w2/16 gather" -> 175,
      "w2/16 LD copy" -> 19,
      "w2/16 root gather ST.W" -> 167,
      "w2/16 odd LD.W" -> 2,
      "w2/16 odd ST.W" -> 88,
      "w2-mw1/16 packed" -> 61,
      "w2-mw1/16 vector" -> 61,
      "w2-mw1/16 lane fallback" -> 57,
      "w2-mw1/16 NothingToPack" -> 138,
      "w2-mw1/16 RootDiffers" -> 583,
      "w2-mw1/16 NoNeighbour" -> 38,
      "w2-mw1/16 SameValue" -> 8,
      "w2-mw1/16 NeededElsewhere" -> 4,
      "w2-mw1/16 LD copy" -> 10,
      "w2/6 fits" -> 189,
      "w2/6 packed" -> 79,
      "w2/6 vector" -> 4,
      "w2/6 NoAttemptFits" -> 86,
      "w2/6 NothingToPack" -> 24,
      "w2/6 NoAlignedPair attempt" -> 48
    )
    minimum.foreach { case (k, n) => assert(counts(k) >= n, s"$k: ${counts(k)}") }
  }

  test("ISA mvp-2: the comparison candidate and normal use agree (the first 100 kernels)") {
    for ((m, code) <- Seq(full -> onFull, mw1 -> onMw1); c <- code.take(100)) {
      val label = s"${c.kernel.name} on ${m.machineId}"
      (KernelCompiler.candidate(c.kernel, m).fold(e => fail(e), identity), c.simd) match {
        case (SimdCandidate.Packed(p), Simd.Packed(_)) => assert(p.words == c.words, label)
        case (SimdCandidate.Infeasible(_, f), Simd.Fallback(FallbackReason.NoAttemptFits(g), _)) =>
          assert(f == g, label)
        case (SimdCandidate.NothingToPack(_, s), Simd.Fallback(FallbackReason.NothingToPack, _)) =>
          val index = c.kernel.name.stripPrefix("random").toInt
          assert(s.words == c.words && c.bundles == onMvp1(index).bundles, label)
        case (candidate, simd) => fail(s"$label: $candidate but $simd")
      }
    }
  }

  test("ISA mvp-2: the generated test cases hold on the reference model") {
    testCasesHold(onFull.take(100), Some(MachineDescription.Mvp2Path.toString))
    testCasesHold(onMw1.take(100), Some(mw1Path))
  }
}
