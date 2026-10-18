package vliw.hw

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.sys.process.{Process, ProcessLogger}
import scala.util.Try

import org.scalatest.funsuite.AnyFunSuite

/** `project/toolchain.properties` and its two readers (ADR-0020): [[Toolchain]] and
  * `scripts/toolchain.sh`.
  */
class ToolchainSpec extends AnyFunSuite {
  import Toolchain.Expected

  /** A Verilator version, built so that no Verilator version is written in the Scala sources
    * (#121).
    */
  private def version(major: Int, minor: Int): String = f"$major%d.$minor%03d"
  private val v = version(4, 321)
  private val other = version(4, 322)
  private val commit = "0123456789abcdef0123456789abcdef01234567"

  private def file(lines: String*): String = lines.mkString("", "\n", "\n")
  private val validLines =
    Seq(
      "# comment",
      s"verilator.version=$v",
      s"verilator.apt=$v-1",
      "verilator.commit=",
      "jdk.major=17"
    )
  private val valid = file(validLines: _*)
  private val validValues = Map(
    "verilator.version" -> v,
    "verilator.apt" -> s"$v-1",
    "verilator.commit" -> "",
    "jdk.major" -> "17"
  )
  private def replace(key: String, line: String) =
    file(validLines.map(l => if (l.startsWith(s"$key=")) line else l): _*)
  private def without(key: String) = file(validLines.filterNot(_.startsWith(s"$key=")): _*)
  private val nul = 0.toChar.toString

  private val accepted: Seq[(String, String, Map[String, String])] = Seq(
    ("the minimal file", valid, validValues),
    ("no final newline", valid.stripSuffix("\n"), validValues),
    (
      "blank lines, comments with any text, another order",
      file("", "#", "# ADR。x=y # z", "jdk.major=17", "", s"verilator.apt=$v-1+b2") +
        file(s"verilator.version=$v", "verilator.commit="),
      validValues + ("verilator.apt" -> s"$v-1+b2")
    ),
    (
      "a commit instead of an apt package",
      replace("verilator.apt", "verilator.apt=")
        .replace("verilator.commit=", s"verilator.commit=$commit"),
      validValues + ("verilator.apt" -> "") + ("verilator.commit" -> commit)
    )
  )

  private val rejected: Seq[(String, String)] = Seq(
    "an empty file" -> "",
    "CR" -> valid.replace("\n", "\r\n"),
    "a tab in a comment" -> valid.replace("# comment", "#\tcomment"),
    "a trailing space after a value" -> replace("jdk.major", "jdk.major=17 "),
    "a trailing space after a comment" -> valid.replace("# comment", "# comment "),
    "a NUL in a value" -> replace("jdk.major", s"jdk.major=1${nul}7"),
    "a NUL in a comment" -> valid.replace("# comment", s"# com${nul}ment"),
    "a line of spaces" -> (valid + " \n"),
    "spaces around =" -> replace("jdk.major", "jdk.major = 17"),
    "a leading space" -> replace("jdk.major", " jdk.major=17"),
    "an indented comment" -> valid.replace("# comment", " # comment"),
    "a comment after a value" -> replace("jdk.major", "jdk.major=17#x"),
    "a line without =" -> (valid + "jdk.major\n"),
    "an uppercase key" -> replace("jdk.major", "JDK.major=17"),
    "a key starting with a digit" -> (valid + "1jdk=17\n"),
    "another character in a value" -> replace("verilator.apt", s"verilator.apt=$v-1~b"),
    "= in a value" -> replace("jdk.major", "jdk.major=17=1"),
    "an unknown key" -> (valid + "verilator.jobs=verilate-1\n"),
    "a duplicate key" -> (valid + "jdk.major=17\n"),
    "a missing key" -> without("verilator.commit"),
    "a byte order mark" -> (0xfeff.toChar.toString + valid.stripPrefix("# comment\n")),
    "a version with two decimals" -> replace("verilator.version", "verilator.version=4.32")
      .replace(s"$v-1", "4.32-1"),
    "a version with four decimals" -> replace("verilator.version", s"verilator.version=${v}0")
      .replace(s"$v-1", s"${v}0-1"),
    "a version with a v" -> replace("verilator.version", s"verilator.version=v$v")
      .replace(s"$v-1", s"v$v-1"),
    "an empty version" -> replace("verilator.version", "verilator.version="),
    "both an apt package and a commit" -> replace("verilator.commit", s"verilator.commit=$commit"),
    "neither an apt package nor a commit" -> replace("verilator.apt", "verilator.apt="),
    "the apt package of another version" -> replace("verilator.apt", s"verilator.apt=$other-1"),
    "an apt package without a revision" -> replace("verilator.apt", s"verilator.apt=$v"),
    "an apt package with an empty revision" -> replace("verilator.apt", s"verilator.apt=$v-"),
    "a commit in uppercase" -> replace("verilator.apt", "verilator.apt=")
      .replace("verilator.commit=", s"verilator.commit=${commit.toUpperCase}"),
    "a commit of 39 digits" -> replace("verilator.apt", "verilator.apt=")
      .replace("verilator.commit=", s"verilator.commit=${commit.tail}"),
    "a JDK with a minor version" -> replace("jdk.major", "jdk.major=17.0"),
    "an empty JDK" -> replace("jdk.major", "jdk.major=")
  )

  /** What `scripts/toolchain.sh get` reads from `text` for every key, or None if it refuses the
    * file.
    */
  private def shellReads(text: String): Option[Map[String, String]] = {
    val path = Files.createTempFile("toolchain", ".properties")
    try {
      Files.write(path, text.getBytes(StandardCharsets.UTF_8))
      def get(key: String) = Try(
        Process(Seq("scripts/toolchain.sh", "--file", path.toString, "get", key))
          .!!(ProcessLogger(_ => ()))
          .stripSuffix("\n")
      ).toOption
      // A refused file is refused for every key: stop at the first.
      get(Toolchain.Keys.head)
        .map(first =>
          (Toolchain.Keys.head -> first) +: Toolchain.Keys.tail
            .map(k => k -> get(k).getOrElse(fail(s"toolchain.sh refuses $k only")))
        )
        .map(_.toMap)
    } finally Files.delete(path)
  }

  test("project/toolchain.properties is valid, and both readers read the same values from it") {
    val shell = Toolchain.Keys.map { k =>
      k -> Process(Seq("scripts/toolchain.sh", "get", k)).!!.stripSuffix("\n")
    }.toMap
    assert(Toolchain.pinned == shell)
    assert(Toolchain.isVersion(Toolchain.pinnedVerilator))
    assert(Toolchain.jdkMajor.nonEmpty && Toolchain.jdkMajor.forall(_.isDigit))
  }

  test("both readers accept the strict format and read the same values") {
    accepted.foreach { case (name, text, values) =>
      assert(Toolchain.parse(text) == Right(values), name)
      assert(shellReads(text).contains(values), name)
    }
  }

  // Most cases are the minimal file with one change.
  test("both readers refuse everything else") {
    rejected.foreach { case (name, text) =>
      assert(Toolchain.parse(text).isLeft, name)
      assert(shellReads(text).isEmpty, name)
    }
  }

  private def env(vars: (String, String)*): String => Option[String] = vars.toMap.get
  private val local = Seq.empty[(String, String)]
  private def actions(workflow: String) =
    Seq(
      "GITHUB_ACTIONS" -> "true",
      "GITHUB_WORKFLOW_REF" -> s"o/r/.github/workflows/$workflow@refs/pull/1/merge"
    )

  test("VLIW_VERILATOR_EXPECTED overrides the pinned version only locally or for candidates") {
    val set = Toolchain.ExpectedEnvVar -> other
    def expected(vars: Seq[(String, String)]) = Toolchain.expected(v, env(vars: _*))
    assert(expected(local) == Right(Expected(v, "pinned")))
    assert(expected(actions("ci.yml")) == Right(Expected(v, "pinned")))
    assert(expected(local :+ set) == Right(Expected(other, "env")))
    assert(expected(Seq("GITHUB_ACTIONS" -> "", set)) == Right(Expected(other, "env")))
    assert(expected(actions("toolchain-candidates.yml") :+ set) == Right(Expected(other, "env")))
    // Anywhere else on GitHub Actions it is an error, so that the required checks are never green
    // with another version.
    Seq(
      actions("ci.yml"),
      actions("mutation.yml"),
      actions("my-toolchain-candidates.yml"),
      actions("toolchain-candidates.yml.bak"),
      Seq("GITHUB_ACTIONS" -> "true")
    ).foreach(vars => assert(expected(vars :+ set).isLeft, vars))
    // So are an empty value and one that is not a version.
    Seq("", " ", s" $other", s"v$other", "4.32", s"${other}0", "candidate").foreach { s =>
      assert(expected(Seq(Toolchain.ExpectedEnvVar -> s)).isLeft, s)
    }
  }

  test("the installed version is the second word of the first line of verilator --version") {
    assert(Toolchain.versionOf(Some(s"Verilator $v 2024-01-01 rev (Debian $v-1)\n")) == v)
    assert(Toolchain.versionOf(Some(s"Verilator $v")) == v)
    assert(Toolchain.versionOf(None) == "not-found")
    Seq("", s"verilator $v", s"Verilator ${v}x", s"Verilator  $v", s"\nVerilator $v", "Verilator")
      .foreach(out => assert(Toolchain.versionOf(Some(out)) == "unreadable", out))
  }

  test("the [toolchain] line names the installed and the expected version") {
    val e = Toolchain.expectedVerilator
    val installed = Toolchain.installedVerilator
    assert(Toolchain.describe == s"verilator=$installed expected=${e.version} (${e.source})")
    assert(Set("pinned", "env").contains(e.source))
  }
}
