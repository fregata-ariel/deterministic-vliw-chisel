package vliw.crosscheck

import java.nio.file.Paths

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.{KernelCompiler, Verifier}
import vliw.compiler.dsl.{Benchmarks, Interpreter}
import vliw.hw.{
  Core,
  CoreDriver,
  CoreParams,
  CoreRun,
  FixedDriver,
  FixedPipeline,
  InitMode,
  SvInitCheck,
  VliwChiselSim
}
import vliw.isa._

/** The RTL does not depend on its initial state (#129, ADR-0021). Every register and memory starts
  * from all zeros or all ones (firtool's `RANDOM`, [[vliw.hw.InitMode]]); the random initial state
  * is that of every other test, at `VLIW_SIM_SEED`. On one core per design and mode:
  *   1. canaries, programs that are illegal only by L8, read an undefined register and an undefined
  *      word of each bank, and commit the pattern (the add canary 1 + the pattern): in ones, the
  *      mode reached the design;
  *   1. 50 random legal programs of [[RtlDifferentialSpec]] give the reference model's traces,
  *      cycles and defined final values;
  *   1. every word and register that nothing wrote still holds the pattern. The register whose
  *      index bits are all 1 can be written at the first reset edge (ADR-0021), so it only has to
  *      keep the value it had after the first run.
  *
  * In zeros, the canaries and the unwritten values cannot show that the mode reached the design:
  * the build passes no `+verilator+rand+reset+`, so Verilator's default initialisation also starts
  * every variable at 0. For zeros that rests on the define check, [[vliw.hw.SvInitCheck]] and the
  * ones test of the same design, which takes the same path.
  *
  * Zeros runs on the default Core only: there every control register (busy, done, pc, length, the
  * write-back schedules, loadStage) starts at its reset value, so zeros tests only the state
  * without a reset, and the other machines build the same `Core.scala`. The fixed pipeline runs in
  * ones only: in zeros busy, done and the counter start at their reset values, and its value
  * registers are never read before they are written.
  */
class InitStateSpec extends AnyFunSuite with VliwChiselSim {
  private def machineFile(path: java.nio.file.Path) =
    MachineDescription.load(path).fold(es => fail(es.mkString("\n")), identity)
  private val machine = MachineDescription.load().fold(es => fail(es.mkString("\n")), identity)
  private val variants = RtlPrograms.variants(machine)

  private case class Design(label: String, m: MachineDescription, seed: Long, mac: Boolean)

  // The machines and program seeds of RtlDifferentialSpec.
  private val mvp0 = Design("mvp-0 default", machine, 314159L, mac = false)
  private val runs = Seq(
    mvp0 -> InitMode.Zeros,
    mvp0 -> InitMode.Ones,
    Design("mvp-1 default", machineFile(MachineDescription.Mvp1Path), 7700L, mac = true) ->
      InitMode.Ones,
    Design(
      "mvp-1 MAC L=3",
      machineFile(Paths.get("tests", "machines", "mvp1_mac3.json")),
      7701L,
      mac = true
    ) -> InitMode.Ones
  ) ++ variants.zipWithIndex.map { case ((label, m), k) =>
    Design(label, m, 1000L + k, mac = false) -> InitMode.Ones
  }

  private val NoInputs = Inputs(Map.empty, Map.empty)
  private val Mask = 0xffffffffL

  private def pattern(mode: InitMode): Long = mode match {
    case InitMode.Zeros => 0L
    case InitMode.Ones  => Mask
    case other          => fail(s"$other has no fixed pattern")
  }

  /** One word of each bank: the last row. */
  private def lastRow(m: MachineDescription): Seq[Int] =
    m.scratchpadWords - m.banks until m.scratchpadWords

  private def encode(m: MachineDescription, bundles: Vector[Bundle]) =
    new BundleCodec(m).encodeAll(bundles).fold(e => fail(e), identity)

  private def commits(trace: Vector[ujson.Obj]): Vector[(Int, Long)] =
    trace.flatMap(_("rf_commit").arr).map(c => (c("reg").num.toInt, c("value").num.toLong))

  private def halt(dut: Core, m: MachineDescription, readBack: Set[Int]): CoreRun =
    CoreDriver.run(dut, m, encode(m, Vector(Bundle(ComputeOp.Halt))), NoInputs, readBack)

  /** On a fresh core: `add r1, r2, r3` with only r2 = 1 declared commits 1 + p; `ld r1, [a]` from
    * an undeclared word `a` of each bank commits p. The verifier rejects each by L8 alone.
    */
  private def canaries(dut: Core, m: MachineDescription, p: Long): Unit = {
    def canary(what: String, first: Bundle, waits: String, in: Inputs, expected: Long) = {
      val nops = Vector.fill(m.operation(waits).latency - 1)(Bundle.Nop)
      val words = encode(m, (first +: nops) :+ Bundle(ComputeOp.Halt))
      val rules = Verifier.verify(m, words, Verifier.Declaration.of(in)).map(_.rule).toSet
      assert(rules == Set(Rule.UndefinedRead), s"$what: the verifier reports $rules")
      val run = CoreDriver.run(dut, m, words, in, Set.empty)
      assert(run.violations.isEmpty, what)
      assert(commits(run.trace) == Vector(1 -> expected), what)
    }
    canary(
      "add r1, r2, r3 (r3 undefined)",
      Bundle(ComputeOp.Add(1, 2, 3)),
      "ADD",
      Inputs(Map(2 -> 1L), Map.empty),
      (1L + p) & Mask
    )
    lastRow(m).foreach { a =>
      val what = s"ld r1, [$a] (bank ${a % m.banks}, undefined)"
      canary(what, Bundle(MemoryOp.Ld(1, a)), "LD", NoInputs, p)
    }
  }

  /** Every word other than those of the programs, and r4 to r(n-2), still hold p; r(n-1) still
    * holds `kept`, its value after the first run, which is p in zeros. Returns the words checked.
    */
  private def unwritten(dut: Core, m: MachineDescription, p: Long, kept: BigInt): Int = {
    val words = (0 until m.scratchpadWords).toSet -- RtlPrograms.usedWords
    val run = halt(dut, m, words)
    words.toSeq.sorted.foreach(a =>
      assert(run.memory(a) == BigInt(p), s"[$a] does not keep the pattern")
    )
    val top = m.registerCount - 1
    (4 until top).foreach(r =>
      assert(run.registers(r) == BigInt(p), s"r$r does not keep the pattern")
    )
    assert(run.registers(top) == kept, s"r$top was written after the first run")
    if (p == 0L) assert(kept == 0, s"r$top was written at the first reset edge in zeros")
    words.size
  }

  runs.foreach { case (d, mode) =>
    test(
      s"${d.label}, ${mode.name}: L8 canaries read the pattern, legal programs run as on the model, unwritten state keeps it"
    ) {
      assert(CoreParams.check(d.m).isRight)
      val p = pattern(mode)
      val programs = RtlPrograms.legalPrograms(d.m, d.seed, 50, d.mac)
      val top = d.m.registerCount - 1
      var kept = BigInt(-1)
      var words = 0
      simulateInit(mode)(new Core(CoreParams(d.m))) { dut =>
        canaries(dut, d.m, p)
        kept = halt(dut, d.m, Set.empty).registers(top)
        RtlPrograms.compareOn(dut, d.m, programs)
        words = unwritten(dut, d.m, p, kept)
      }
      val sv = SvInitCheck.directory(implementation.getDirectory.resolve("primary-sources"))
      info(
        s"${programs.size} programs; $words words and r4..r${top - 1} keep ${p.toHexString}; " +
          s"r$top after the first run: ${kept.toString(16)}; the SV check covered " +
          s"${sv.registers} registers and ${sv.memories} memories in ${sv.files} files"
      )
    }
  }

  test(
    "saxpy8 fixed pipeline, ones: outputs, cycles, issue and commit cycles as planned, unwritten words keep the pattern"
  ) {
    val p = pattern(InitMode.Ones)
    val m = BenchmarkRecords.machine
    val k = Benchmarks.saxpy(8)
    val planned = FixedPipelines.planned(k, m).fold(e => fail(e), identity)
    val layout = planned.prepared.layout
    // A busy without a reset starts at 1 here. If the counter's initial value (all ones) were the
    // completion cycle, busy would clear itself at the first edge and this test could not see it.
    assert(planned.params.completion != (1 << planned.params.counterBits) - 1)
    val canary = lastRow(m).toSet
    assert(canary.intersect(layout.initialized ++ layout.outputAddresses).isEmpty)
    simulateInit(InitMode.Ones)(new FixedPipeline(planned.params)) { dut =>
      (0 until 4).foreach { i =>
        val in = KernelCompiler.sampleInputs(k, 1000L + i)
        val image = layout.memoryImage(in).fold(e => fail(e), identity)
        val readBack = layout.outputAddresses ++ canary
        val run = FixedDriver.run(dut, image, readBack, reset = i == 0, extra = 2)
        canary.foreach(a =>
          assert(run.memory(a) == BigInt(p), s"input $i: [$a] does not keep the pattern")
        )
        val expected = Interpreter.run(k, in).fold(e => fail(e), identity)
        val problems = FixedPipelines.mismatches(planned, run, expected)
        assert(problems.isEmpty, s"input $i: ${problems.mkString("; ")}")
      }
    }
  }
}
