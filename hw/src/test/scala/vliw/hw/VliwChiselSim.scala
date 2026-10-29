package vliw.hw

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.security.SecureRandom

import chisel3.Module
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.TestSuite
import svsim.{
  Backend,
  BackendSettingsModifications,
  CommonCompilationSettings,
  CommonSettingsModifications,
  PlusArg
}
import svsim.verilator.Backend.CompilationSettings.Parallelism

/** The seed of Verilator's random numbers for every simulation in this test JVM (#74).
  *
  * ChiselSim builds with `RANDOMIZE_REG_INIT`, `RANDOMIZE_MEM_INIT` and `RANDOM=$urandom`, so every
  * register and memory starts from Verilator's random numbers ([[InitMode.Urandom]]; a test can
  * choose all zeros or all ones instead, [[VliwChiselSim.simulateInit]]). Registers with a reset
  * get their reset value at the first reset edge. The state without a reset keeps its initial value
  * until written: the registers of the register file (`RegisterFile.regs`, on ISA mvp-2
  * `BankedRegisterFile.bank0` and `bank1`), the instruction memory (`Core.imem`), the scratchpad
  * banks (`MemorySlot.banks`, with the registers of their read ports that firtool adds, `_RW0_*`),
  * the bank of the last read (`MemorySlot.readBank`) and the value registers of a fixed pipeline
  * (`FixedPipeline.regs`). The first reset edge itself can write the register file: the write-back
  * schedules and `loadStage` still hold their initial values at that edge, and register writes are
  * not stopped by reset (with all ones, both ports write the register whose index bits are all 1,
  * on mvp-2 its pair; ADR-0021). The host therefore loads its inputs after reset
  * ([[CoreDriver.start]]).
  *
  * Where the random numbers start without `+verilator+seed+` depends on the Verilator version, and
  * the same seed can give other numbers in another version. The seed is therefore passed explicitly
  * and recorded together with the version of the Verilator that ran (ADR-0020).
  *
  * `VLIW_SIM_SEED` selects it: unset for [[Default]], a number from 1 to 2147483647 (the range that
  * Verilator accepts), or `random` for a new seed per test JVM.
  */
object SimSeed {
  val EnvVar = "VLIW_SIM_SEED"
  val Default: Int = 1

  private val raw = sys.env.get(EnvVar).map(_.trim).filter(_.nonEmpty)

  lazy val value: Int = raw match {
    case None           => Default
    case Some("random") => 1 + new SecureRandom().nextInt(Int.MaxValue - 1)
    case Some(s)        =>
      s.toIntOption
        .filter(_ >= 1)
        .getOrElse(
          throw new IllegalArgumentException(
            s"$EnvVar=$s: expected 1 to ${Int.MaxValue}, or random"
          )
        )
  }

  def source: String = raw.fold("default")(r => if (r == "random") "random" else "env")

  /** The seed, where it comes from and the Verilator on the PATH, e.g. `VLIW_SIM_SEED=1 (default)
    * verilator=5.020`: the line in `seed.txt` and after `[sim-seed]`.
    */
  def describe: String = s"$EnvVar=$value ($source) verilator=${Toolchain.installedVerilator}"

  /** Rendered as `+verilator+seed+<n>`. `new PlusArg("verilator+seed", Some(n))` would render as
    * `+verilator+seed=<n>`, which Verilator rejects at startup.
    */
  def plusArg: PlusArg = new PlusArg(s"verilator+seed+$value", None)

  /** Printed once per test JVM, so that the sbt log names the seed. */
  lazy val announce: Unit = println(s"[sim-seed] $describe")
}

/** The Verilator jobs of every ChiselSim build (#74): Verilation in one thread, the C++ build on
  * all cores (`--verilate-jobs 1 --build-jobs 0`).
  *
  * svsim's default is `-j 0`, which also verilates with one thread per core. Verilator 5.020 (the
  * pinned version when #74 found this) then aborts now and then at exit, after a successful build,
  * with `%Error: Internal Error: attempted to destroy locked Thread Pool` (`make ... Error 255`):
  * the destructor of its thread pool tries the pool's lock once while a worker can still hold it
  * (Verilator issue 4931, mitigated in 5.024 and 5.026, removed in 5.030). With one verilation
  * thread there are no workers and the lock is disabled.
  */
object SimVerilator {
  val parallelism: Parallelism.Type = Parallelism.Different.default
    .withBuild(Some(0))
    .withVerilate(Some(1))
}

/** ChiselSim with the project's settings for every simulation:
  *   - the seed of [[SimSeed]], also written to `seed.txt` in the simulation's directory under
  *     `build/chiselsim/` (next to the logs that ChiselSim writes there; the Makefile `replay`
  *     target in that directory uses the same seed);
  *   - the initial state: the build must define `RANDOMIZE_REG_INIT`, `RANDOMIZE_MEM_INIT` and
  *     `RANDOM` once each, `RANDOM` as one of the [[InitMode]]s, and not `SYNTHESIS`; every
  *     register and memory of the generated SystemVerilog must start from `RANDOM`
  *     ([[SvInitCheck]]); the mode is written to `init.txt` next to `seed.txt` (`init=<mode>
  *     RANDOM=<value>`; the Makefile holds the same define, so `replay` keeps the mode). Otherwise
  *     the simulation fails before the build, naming its directory;
  *   - the Verilator jobs of [[SimVerilator]];
  *   - the `[toolchain]` line of [[Toolchain]] in the sbt log, once per test JVM.
  *
  * ChiselSim specs mix in this trait instead of ChiselSim (`scripts/check.sh static` checks it).
  */
trait VliwChiselSim extends ChiselSim { this: TestSuite =>

  /** `simulate` with every register and memory starting from `mode` (#129). `simulate` itself
    * starts from [[InitMode.Urandom]].
    */
  def simulateInit[T <: Module](mode: InitMode)(module: => T)(body: T => Unit): Unit =
    simulate(module, settings = InitMode.settings[T](mode))(body)

  override implicit def commonSettingsModifications: CommonSettingsModifications = {
    val inherited = super.commonSettingsModifications
    (original: CommonCompilationSettings) => {
      val settings = inherited(original)
      val dir = implementation.getDirectory
      // The mode that the test chose wins. A JVM-wide mode added later (for example an environment
      // variable for whole-suite runs) may only replace the default, urandom.
      val mode = InitMode
        .of(settings.verilogPreprocessorDefines)
        .fold(e => throw new IllegalArgumentException(s"$dir: randomization: $e"), identity)
      // Elaboration has written the primary sources before the compilation settings are made.
      val sv = SvInitCheck.directory(dir.resolve("primary-sources"))
      if (sv.problems.nonEmpty)
        throw new IllegalStateException(
          s"$dir: initial state: ${sv.problems.mkString("; ")}"
        )
      SimSeed.announce
      Toolchain.announce
      Files.createDirectories(dir)
      write(dir.resolve("seed.txt"), SimSeed.describe + "\n")
      write(dir.resolve("init.txt"), s"init=${mode.name} RANDOM=${mode.value}\n")
      settings.copy(simulationSettings =
        settings.simulationSettings.copy(plusArgs =
          settings.simulationSettings.plusArgs :+ SimSeed.plusArg
        )
      )
    }
  }

  private def write(file: Path, text: String): Unit = {
    Files.write(
      file,
      text.getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.WRITE
    )
  }

  override implicit def backendSettingsModifications: BackendSettingsModifications = {
    val inherited = super.backendSettingsModifications
    (original: Backend.Settings) =>
      inherited(original) match {
        case verilator: svsim.verilator.Backend.CompilationSettings =>
          verilator.withParallelism(Some(SimVerilator.parallelism))
        case other => other
      }
  }
}
