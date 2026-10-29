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
  *      mode reached the design. On ISA mvp-2 (W) also a VADD that reads both RF banks, a VMUL that
  *      broadcasts an undefined register, and an LD.W at an odd address, whose second word is in
  *      the next row of bank 0;
  *   1. 50 random legal programs of [[RtlDifferentialSpec]] (of [[RtlMvp2Spec]] on mvp-2) give the
  *      reference model's traces, cycles and defined final values;
  *   1. every word and register that nothing wrote still holds the pattern. The register whose
  *      index bits are all 1 can be written at the first reset edge (ADR-0021), and on mvp-2 the
  *      pair it belongs to (the reset value of the write-back schedules' pair flag is not there yet
  *      either), so these only have to keep the value they had after the first run.
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

  /** A core to check: its machine, the seed of its random legal programs, and whether they are the
    * programs of ISA mvp-1 (MAC) or mvp-2 (W).
    */
  private case class Design(
      label: String,
      m: MachineDescription,
      seed: Long,
      mac: Boolean,
      w: Boolean = false
  ) {
    def programs: Vector[(Vector[Long], Inputs)] =
      if (w) WPrograms.legalPrograms(m, seed, 50) else RtlPrograms.legalPrograms(m, seed, 50, mac)
    def usedWords: Set[Int] = if (w) WPrograms.usedWords else RtlPrograms.usedWords

    /** The programs use r0 up to this register (exclusive). */
    def usedRegisters: Int = if (w) 6 else 4

    /** The registers the first reset edge can write: r(n-1), and on mvp-2 its pair. */
    def resetEdge: Seq[Int] = (m.registerCount - (if (w) 2 else 1) until m.registerCount)
  }

  // The machines and program seeds of RtlDifferentialSpec, and of RtlMvp2Spec for W-full.
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
  } :+ (Design(
    "mvp-2 W-full",
    machineFile(MachineDescription.Mvp2Path),
    110L,
    mac = true,
    w = true
  ) -> InitMode.Ones)

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
    * an undeclared word `a` of each bank commits p. On mvp-2, `vadd r0:r1, r2:r3, r4:r5` with only
    * r2 = r3 = 1 declared commits 1 + p to both registers (r4 and r5 are in the two RF banks),
    * `vmul r0:r1, r7, r2:r3` broadcasting the undeclared r7 (no canary writes it) with r2 = r3 = 1
    * commits p twice, and on W-full `ld.w r0:r1, [a]` at the odd `a` = 509 (bank 1, then the next
    * row of bank 0) commits p twice. The verifier rejects each by L8 alone.
    */
  private def canaries(dut: Core, m: MachineDescription, p: Long): Unit = {
    def canary(what: String, first: Bundle, waits: String, in: Inputs, expected: (Int, Long)*) = {
      val nops = Vector.fill(m.operation(waits).latency - 1)(Bundle.Nop)
      val words = encode(m, (first +: nops) :+ Bundle(ComputeOp.Halt))
      val rules = Verifier.verify(m, words, Verifier.Declaration.of(in)).map(_.rule).toSet
      assert(rules == Set(Rule.UndefinedRead), s"$what: the verifier reports $rules")
      val run = CoreDriver.run(dut, m, words, in, Set.empty)
      assert(run.violations.isEmpty, what)
      assert(commits(run.trace) == expected.toVector, what)
    }
    canary(
      "add r1, r2, r3 (r3 undefined)",
      Bundle(ComputeOp.Add(1, 2, 3)),
      "ADD",
      Inputs(Map(2 -> 1L), Map.empty),
      1 -> ((1L + p) & Mask)
    )
    lastRow(m).foreach { a =>
      val what = s"ld r1, [$a] (bank ${a % m.banks}, undefined)"
      canary(what, Bundle(MemoryOp.Ld(1, a)), "LD", NoInputs, 1 -> p)
    }
    if (m.hasVector) {
      canary(
        "vadd r0:r1, r2:r3, r4:r5 (r4 and r5 undefined)",
        Bundle(ComputeOp.VAdd(0, 2, 4)),
        "VADD",
        Inputs(Map(2 -> 1L, 3 -> 1L), Map.empty),
        0 -> ((1L + p) & Mask),
        1 -> ((1L + p) & Mask)
      )
      canary(
        "vmul r0:r1, r7, r2:r3 (the broadcast r7 undefined)",
        Bundle(ComputeOp.VMul(0, 7, 2, broadcast = true)),
        "VMUL",
        Inputs(Map(2 -> 1L, 3 -> 1L), Map.empty),
        0 -> p,
        1 -> p
      )
    }
    if (m.hasWideMemory) {
      val a = m.scratchpadWords - 3
      assert(a % 2 == 1 && m.banks == 2)
      canary(
        s"ld.w r0:r1, [$a] (undefined)",
        Bundle(MemoryOp.LdW(0, a)),
        "LD.W",
        NoInputs,
        0 -> p,
        1 -> p
      )
    }
  }

  /** Every word other than those of the programs, and every register from r(usedRegisters) up to
    * the ones of the first reset edge, still hold p; those still hold `kept`, their values after
    * the first run, which are p in zeros. Returns the words checked.
    */
  private def unwritten(dut: Core, d: Design, p: Long, kept: Seq[BigInt]): Int = {
    val words = (0 until d.m.scratchpadWords).toSet -- d.usedWords
    val run = halt(dut, d.m, words)
    words.toSeq.sorted.foreach(a =>
      assert(run.memory(a) == BigInt(p), s"[$a] does not keep the pattern")
    )
    (d.usedRegisters until d.m.registerCount)
      .filterNot(d.resetEdge.contains)
      .foreach(r => assert(run.registers(r) == BigInt(p), s"r$r does not keep the pattern"))
    d.resetEdge.zip(kept).foreach { case (r, k) =>
      assert(run.registers(r) == k, s"r$r was written after the first run")
      if (p == 0L) assert(k == 0, s"r$r was written at the first reset edge in zeros")
    }
    words.size
  }

  runs.foreach { case (d, mode) =>
    test(
      s"${d.label}, ${mode.name}: L8 canaries read the pattern, legal programs run as on the model, unwritten state keeps it"
    ) {
      assert(CoreParams.check(d.m).isRight)
      val p = pattern(mode)
      val programs = d.programs
      var kept = Seq.empty[BigInt]
      var words = 0
      simulateInit(mode)(new Core(CoreParams(d.m))) { dut =>
        canaries(dut, d.m, p)
        kept = d.resetEdge.map(halt(dut, d.m, Set.empty).registers)
        RtlPrograms.compareOn(dut, d.m, programs, d.usedWords)
        words = unwritten(dut, d, p, kept)
      }
      val sv = SvInitCheck.directory(implementation.getDirectory.resolve("primary-sources"))
      val edge = d.resetEdge.zip(kept).map { case (r, k) => s"r$r ${k.toString(16)}" }
      info(
        s"${programs.size} programs; $words words and r${d.usedRegisters}..r${d.resetEdge.head - 1} " +
          s"keep ${p.toHexString}; after the first run: ${edge.mkString(", ")}; the SV check covered " +
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
