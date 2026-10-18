package vliw.hw

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardOpenOption}
import java.security.SecureRandom

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
  * ChiselSim builds with `RANDOMIZE_REG_INIT`, `RANDOMIZE_MEM_INIT` and `RANDOM=$urandom`, so
  * registers and memories start from Verilator's random numbers. Registers with a reset lose them
  * at reset; the register file (no reset) and the memories keep them until written. Where the
  * random numbers start without `+verilator+seed+` depends on the Verilator version, and the same
  * seed can give other numbers in another version. The seed is therefore passed explicitly and
  * recorded together with the version of the Verilator that ran (ADR-0020).
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
  *   - the Verilator jobs of [[SimVerilator]];
  *   - the `[toolchain]` line of [[Toolchain]] in the sbt log, once per test JVM.
  *
  * ChiselSim specs mix in this trait instead of ChiselSim (`scripts/check.sh static` checks it).
  */
trait VliwChiselSim extends ChiselSim { this: TestSuite =>
  override implicit def commonSettingsModifications: CommonSettingsModifications = {
    val inherited = super.commonSettingsModifications
    (original: CommonCompilationSettings) => {
      val settings = inherited(original)
      val dir = implementation.getDirectory
      SimSeed.announce
      Toolchain.announce
      Files.createDirectories(dir)
      Files.write(
        dir.resolve("seed.txt"),
        (SimSeed.describe + "\n").getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE
      )
      settings.copy(simulationSettings =
        settings.simulationSettings.copy(plusArgs =
          settings.simulationSettings.plusArgs :+ SimSeed.plusArg
        )
      )
    }
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
