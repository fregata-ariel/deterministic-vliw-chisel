package vliw.hw

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.sys.process.{Process, ProcessLogger}
import scala.util.Try

/** The toolchain of the tests (ADR-0020, #121): the versions pinned in
  * `project/toolchain.properties`, the Verilator on the PATH (which ChiselSim runs), and the
  * version that the tests expect of it.
  *
  * The file has one strict format, which `scripts/toolchain.sh` reads too: every line is empty, a
  * comment starting with `#`, or `key=value` with a key of [[Keys]], each exactly once; CR, tabs,
  * trailing spaces and NUL are errors. `ToolchainSpec` checks that both readers accept the same
  * files and read the same values.
  */
object Toolchain {
  val File: Path = Paths.get("project", "toolchain.properties")
  val Keys: Seq[String] = Seq("verilator.version", "verilator.apt", "verilator.commit", "jdk.major")

  /** Overrides the expected Verilator version where [[overrideAllowed]], to try a candidate. */
  val ExpectedEnvVar = "VLIW_VERILATOR_EXPECTED"

  /** In `GITHUB_WORKFLOW_REF`: the workflow that tries candidate versions, the only one on GitHub
    * Actions that may set [[ExpectedEnvVar]].
    */
  val CandidatesWorkflow = "/.github/workflows/toolchain-candidates.yml@"

  private val KeyValue = "([a-z][a-z0-9.]*)=([A-Za-z0-9.+-]*)".r
  private val VersionForm = "[0-9]+\\.[0-9]{3}".r
  private val CommitForm = "[0-9a-f]{40}".r
  private val Digits = "[0-9]+".r
  private val versionForm = "digits, a dot and three digits"

  /** Whether `v` has the form of a Verilator version: digits, a dot and three digits. */
  def isVersion(v: String): Boolean = VersionForm.matches(v)

  /** The values of a file in the format of `project/toolchain.properties`, or its errors. */
  def parse(text: String): Either[Seq[String], Map[String, String]] = {
    val pieces = text.split("\n", -1).toSeq
    // A final newline ends the last line rather than starting another.
    val lines = if (pieces.last.isEmpty) pieces.init else pieces
    val errors = Seq.newBuilder[String]
    // bash's read, in scripts/toolchain.sh, drops NUL bytes: both readers refuse them.
    if (text.contains(0.toChar)) errors += "NUL byte"
    val values = scala.collection.mutable.LinkedHashMap.empty[String, (Int, String)]
    lines.zip(LazyList.from(1)).foreach { case (line, n) =>
      if (line.contains('\r')) errors += s"line $n: CR"
      else if (line.contains('\t')) errors += s"line $n: tab"
      else if (line.endsWith(" ")) errors += s"line $n: trailing space"
      else if (line.nonEmpty && !line.startsWith("#")) line match {
        case KeyValue(k, _) if !Keys.contains(k)  => errors += s"line $n: unknown key $k"
        case KeyValue(k, _) if values.contains(k) =>
          errors += s"line $n: duplicate key $k (also line ${values(k)._1})"
        case KeyValue(k, v) => values(k) = (n, v)
        case _              =>
          errors += s"line $n: expected an empty line, a comment starting with # or key=value"
      }
    }
    Keys.filterNot(values.contains).foreach(k => errors += s"missing key $k")
    val structure = errors.result()
    if (structure.nonEmpty) Left(structure)
    else {
      val v = values.map { case (k, (_, value)) => k -> value }.toMap
      val (version, apt, commit, jdk) =
        (v("verilator.version"), v("verilator.apt"), v("verilator.commit"), v("jdk.major"))
      // An apt package of the version: <version>-<revision>.
      val aptOfVersion = apt.startsWith(s"$version-") && apt.length > version.length + 1
      val wrong = Seq(
        Option.when(!isVersion(version))(s"verilator.version=$version: expected $versionForm"),
        Option.when(apt.isEmpty == commit.isEmpty)(
          "exactly one of verilator.apt and verilator.commit must be set"
        ),
        Option.when(apt.nonEmpty && !aptOfVersion)(
          s"verilator.apt=$apt: expected the package of verilator.version, $version-<revision>"
        ),
        Option.when(commit.nonEmpty && !CommitForm.matches(commit))(
          s"verilator.commit=$commit: expected 40 lowercase hexadecimal digits"
        ),
        Option.when(!Digits.matches(jdk))(s"jdk.major=$jdk: expected digits")
      ).flatten
      Either.cond(wrong.isEmpty, v, wrong)
    }
  }

  /** The values of `project/toolchain.properties`. An error in the file fails every test that needs
    * them.
    */
  lazy val pinned: Map[String, String] =
    parse(new String(Files.readAllBytes(File), StandardCharsets.UTF_8)).fold(
      es => throw new IllegalStateException(es.map(e => s"$File: $e").mkString("\n")),
      identity
    )

  def pinnedVerilator: String = pinned("verilator.version")
  def jdkMajor: String = pinned("jdk.major")

  /** The Verilator version that the tests expect, and where it comes from: `pinned` or `env`. */
  final case class Expected(version: String, source: String)

  /** Whether [[ExpectedEnvVar]] may override the pinned version: outside GitHub Actions
    * (`GITHUB_ACTIONS` unset or empty: a local run) or in the candidates workflow. The required
    * checks therefore always expect the pinned version. `env` looks up an environment variable.
    */
  def overrideAllowed(env: String => Option[String]): Boolean =
    env("GITHUB_ACTIONS").forall(_.isEmpty) ||
      env("GITHUB_WORKFLOW_REF").exists(_.contains(CandidatesWorkflow))

  /** The expected version: that of [[ExpectedEnvVar]] where it is allowed, else `pinned`. Set where
    * it is not allowed, empty or not a version, it is an error (as in `scripts/toolchain.sh`).
    */
  def expected(pinned: String, env: String => Option[String]): Either[String, Expected] =
    env(ExpectedEnvVar) match {
      case None     => Right(Expected(pinned, "pinned"))
      case Some("") =>
        Left(s"$ExpectedEnvVar is empty; unset it to expect the pinned Verilator $pinned")
      case Some(v) if !overrideAllowed(env) =>
        Left(
          s"$ExpectedEnvVar=$v is set on GitHub Actions outside" +
            " .github/workflows/toolchain-candidates.yml" +
            s" (GITHUB_WORKFLOW_REF=${env("GITHUB_WORKFLOW_REF").getOrElse("")});" +
            s" the required checks run with the pinned Verilator $pinned only (ADR-0020)"
        )
      case Some(v) if !isVersion(v) => Left(s"$ExpectedEnvVar=$v: expected $versionForm")
      case Some(v)                  => Right(Expected(v, "env"))
    }

  lazy val expectedVerilator: Expected =
    expected(pinnedVerilator, sys.env.get).fold(e => throw new IllegalStateException(e), identity)

  /** The version of the Verilator on the PATH, which ChiselSim runs (see [[versionOf]]). */
  lazy val installedVerilator: String =
    versionOf(Try(Process(Seq("verilator", "--version")).!!(ProcessLogger(_ => ()))).toOption)

  /** The version in the output of `verilator --version`, read as `scripts/toolchain.sh` reads it:
    * the second word of the first line, "5.020" from "Verilator 5.020 2024-01-01 rev (Debian
    * 5.020-1)". `not-found` when the command does not run or fails, `unreadable` when the line has
    * another form.
    */
  def versionOf(output: Option[String]): String = output match {
    case None       => "not-found"
    case Some(text) =>
      text.linesIterator.nextOption().getOrElse("").split(" ", -1).toSeq match {
        case Seq("Verilator", v, _*) if isVersion(v) => v
        case _                                       => "unreadable"
      }
  }

  /** `verilator=<installed> expected=<version> (<source>)`. */
  def describe: String = {
    val e = expectedVerilator
    s"verilator=$installedVerilator expected=${e.version} (${e.source})"
  }

  /** Printed once per test JVM that simulates, so that the sbt log names the toolchain. CI checks
    * that every such line of the required checks is `(pinned)`.
    */
  lazy val announce: Unit = println(s"[toolchain] $describe")
}
