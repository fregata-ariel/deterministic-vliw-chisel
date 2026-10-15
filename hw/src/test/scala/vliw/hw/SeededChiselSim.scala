package vliw.hw

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardOpenOption}
import java.security.SecureRandom

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.TestSuite
import svsim.{CommonCompilationSettings, CommonSettingsModifications, PlusArg}

/** The seed of Verilator's random numbers for every simulation in this test JVM (#74).
  *
  * ChiselSim builds with `RANDOMIZE_REG_INIT`, `RANDOMIZE_MEM_INIT` and `RANDOM=$urandom`, so
  * registers and memories start from Verilator's random numbers until reset. Without
  * `+verilator+seed+`, Verilator 5.020 always starts from the same state, which depends on its
  * version. The seed is therefore passed explicitly and recorded.
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

  def describe: String = s"$EnvVar=$value ($source)"

  /** Rendered as `+verilator+seed+<n>`. `new PlusArg("verilator+seed", Some(n))` would render as
    * `+verilator+seed=<n>`, which Verilator rejects at startup.
    */
  def plusArg: PlusArg = new PlusArg(s"verilator+seed+$value", None)

  /** Printed once per test JVM, so that the sbt log names the seed. */
  lazy val announce: Unit = println(s"[sim-seed] $describe")
}

/** ChiselSim with the seed of [[SimSeed]]. Every simulation also gets `seed.txt` in its directory
  * under `build/chiselsim/`, next to the logs that ChiselSim writes there. The Makefile `replay`
  * target in that directory runs the simulation with the same seed.
  */
trait SeededChiselSim extends ChiselSim { this: TestSuite =>
  override implicit def commonSettingsModifications: CommonSettingsModifications = {
    val inherited = super.commonSettingsModifications
    (original: CommonCompilationSettings) => {
      val settings = inherited(original)
      val dir = implementation.getDirectory
      SimSeed.announce
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
}
