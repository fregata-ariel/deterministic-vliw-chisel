package vliw.hw

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters._
import scala.util.Using
import scala.util.matching.Regex

import chisel3.Module
import chisel3.simulator.{Randomization, Settings}
import svsim.CommonCompilationSettings.VerilogPreprocessorDefine

/** What every register and memory of a simulation starts from: the value of firtool's `RANDOM`
  * macro (#129, ADR-0021). The generated SystemVerilog fills every register and memory word from
  * `` `RANDOM `` in its init blocks ([[SvInitCheck]]); one build per mode.
  *
  *   - [[InitMode.Zeros]] and [[InitMode.Ones]]: every bit 0 or every bit 1, for every seed and
  *     Verilator version (the build calls no random function). They are sized 32-bit literals: an
  *     unsized `0` or `'1` fails the build (WIDTHCONCAT and WIDTHEXPAND in the memories' init
  *     blocks).
  *   - [[InitMode.Urandom]]: Verilator's random numbers from the seed of [[SimSeed]]. ChiselSim's
  *     default, and the mode of every test that calls `simulate`.
  */
sealed abstract class InitMode(val name: String, val value: String)
    extends Product
    with Serializable

object InitMode {
  case object Zeros extends InitMode("zeros", "32'h0")
  case object Ones extends InitMode("ones", "32'hFFFFFFFF")
  case object Urandom extends InitMode("urandom", "$urandom")

  val all: Seq[InitMode] = Seq(Zeros, Ones, Urandom)

  /** ChiselSim's default settings with `RANDOM` set to the value of `mode`. */
  def settings[T <: Module](mode: InitMode): Settings[T] =
    Settings
      .default[T]
      .copy(randomization = Randomization.random.copy(randomValue = Some(mode.value)))

  /** The mode that the defines of a build select: exactly one `RANDOMIZE_REG_INIT`, one
    * `RANDOMIZE_MEM_INIT` and one `RANDOM`, whose value is that of a mode, and no `SYNTHESIS`
    * (which leaves out the init blocks). Otherwise the reasons.
    */
  def of(defines: Seq[VerilogPreprocessorDefine]): Either[String, InitMode] = {
    def count(name: String) = defines.count(_.name == name)
    val problems = Seq("RANDOMIZE_REG_INIT", "RANDOMIZE_MEM_INIT", "RANDOM").collect {
      case n if count(n) != 1 => s"$n is defined ${count(n)} times, not once"
    } ++ Option.when(count("SYNTHESIS") > 0)("SYNTHESIS is defined, which leaves out the init")
    if (problems.nonEmpty) Left(problems.mkString("; "))
    else {
      val value = defines.find(_.name == "RANDOM").flatMap(_.value)
      all
        .find(m => value.contains(m.value))
        .toRight(s"RANDOM=${value.getOrElse("")} is not one of ${all.map(_.value).mkString(", ")}")
    }
  }
}

/** A static check of the SystemVerilog that firtool generated for a simulation (#129): every
  * register and memory starts from `` `RANDOM ``, so that an [[InitMode]] reaches all the state of
  * the design. In a `*.sv` file of the primary sources with any `reg`:
  *   - every `reg` is declared on its own line, in the form `reg [w] name;` or `reg [w] name[d];`
  *     (a memory);
  *   - the init blocks (`initial begin ... end // initial`) count only inside
  *     `` `ifdef ENABLE_INITIAL_REG_ `` or `` `ifdef ENABLE_INITIAL_MEM_ ``, and the file defines
  *     each of these macros once, inside `` `ifndef SYNTHESIS ``;
  *   - every register (other than firtool's `_RANDOM*` temporaries) is set in an init block under
  *     `` `ifdef RANDOMIZE_REG_INIT ``, and every assignment to it there is from `_RANDOM` words
  *     (`_RANDOM[...]` or `{_RANDOM[...], ...}`);
  *   - every memory is set under `` `ifdef RANDOMIZE_MEM_INIT ``, and every assignment to it there
  *     is `_RANDOM_MEM`;
  *   - every `_RANDOM*` is filled only from `` `RANDOM `` (`` `RANDOM ``, `` {`RANDOM} `` or
  *     `` {`RANDOM, `RANDOM} ``).
  *
  * The rules follow the output of firtool 1.158.0. If an upgrade of firtool (ADR-0004) writes the
  * init blocks in another way, every simulation fails here for that one reason, and the upgrade
  * adjusts these rules in the same change.
  */
object SvInitCheck {

  /** What was checked, and the problems found (empty when the state starts from `RANDOM`). */
  final case class Result(files: Int, registers: Int, memories: Int, problems: Seq[String]) {
    def ++(other: Result): Result = Result(
      files + other.files,
      registers + other.registers,
      memories + other.memories,
      problems ++ other.problems
    )
  }

  private val RegLine = """^\s*reg\b.*""".r
  private val Declaration =
    """^\s*reg\s+(?:\[[^\]]+\]\s*)?(\w+)\s*(\[[^\]]+\])?\s*;\s*(?://.*)?$""".r
  private val Guarded = """(?s)`ifdef (ENABLE_INITIAL_(?:REG|MEM)_)\b(.*?)`endif // \1""".r
  private val Initial = """(?s)initial begin\b(.*?)end // initial""".r
  // Indices may nest: `_RANDOM[i[3:0]]`, `Memory[i[7:0]]`, `_RANDOM_MEM[j[5:0] +: 32]`.
  private val Assignment = """(?m)^\s*(\w+)\s*(\[[^=;]*\])?\s*=\s*([^;]*);""".r
  private val FromRandom =
    """_RANDOM\b[^;,{}]*|\{\s*_RANDOM\b[^;,{}]*(?:,\s*_RANDOM\b[^;,{}]*)*\}""".r
  private val RandomFill = """`RANDOM|\{\s*`RANDOM(?:\s*,\s*`RANDOM)*\s*\}""".r

  private def section(body: String, macroName: String): String =
    new Regex(s"(?s)`ifdef $macroName\\b(.*?)`endif // $macroName\\b")
      .findAllMatchIn(body)
      .map(_.group(1))
      .mkString("\n")

  /** `macroName` is defined once in `text`, inside `` `ifndef SYNTHESIS ``. */
  private def behindSynthesis(text: String, macroName: String): Boolean =
    new Regex(
      s"`ifndef SYNTHESIS\\s*`ifndef $macroName\\s*`define $macroName\\s*" +
        s"`endif // not def $macroName\\s*`endif // not def SYNTHESIS"
    ).unanchored.matches(text) &&
      new Regex(s"`define $macroName\\b").findAllMatchIn(text).size == 1

  /** The check of one file; `name` labels its problems. */
  def file(name: String, text: String): Result = {
    val lines = text.linesIterator.filter(RegLine.matches).toSeq
    val declared = lines.map(l => l -> Declaration.findFirstMatchIn(l))
    val unrecognized = declared.collect { case (l, None) =>
      s"$name: unrecognized declaration '${l.trim}'"
    }
    val state = declared
      .flatMap(_._2)
      .map(m => (m.group(1), m.group(2) != null))
      .filterNot(_._1.startsWith("_RANDOM"))
    val (memories, registers) = state.partition(_._2)
    if (state.isEmpty) Result(1, 0, 0, unrecognized)
    else {
      val guarded = Guarded.findAllMatchIn(text).toSeq
      val unguarded = guarded.map(_.group(1)).distinct.filterNot(behindSynthesis(text, _)).map {
        m => s"$name: $m is not defined once inside `ifndef SYNTHESIS"
      }
      val body = guarded.flatMap(g => Initial.findAllMatchIn(g.group(2)).map(_.group(1)))
      val assignments = Assignment.findAllMatchIn(body.mkString("\n")).toSeq
      def underMacro(macroName: String) =
        Assignment.findAllMatchIn(section(body.mkString("\n"), macroName)).toSeq
      val (regInit, memInit) = (underMacro("RANDOMIZE_REG_INIT"), underMacro("RANDOMIZE_MEM_INIT"))
      def to(as: Seq[Regex.Match], target: String, memory: Boolean) =
        as.filter(a => a.group(1) == target && (a.group(2) != null) == memory).map(_.group(3).trim)
      val registerProblems = registers.map(_._1).flatMap { r =>
        if (to(regInit, r, memory = false).isEmpty)
          Some(s"$name: reg $r is not set under RANDOMIZE_REG_INIT")
        else
          to(assignments, r, memory = false).find(!FromRandom.matches(_)).map { v =>
            s"$name: reg $r is set from '$v', not from _RANDOM"
          }
      }
      val memoryProblems = memories.map(_._1).flatMap { r =>
        if (to(memInit, r, memory = true).isEmpty)
          Some(s"$name: memory $r is not set under RANDOMIZE_MEM_INIT")
        else
          to(assignments, r, memory = true).find(_ != "_RANDOM_MEM").map { v =>
            s"$name: memory $r is set from '$v', not from _RANDOM_MEM"
          }
      }
      val fills = assignments.filter(_.group(1).startsWith("_RANDOM"))
      val fillProblems = fills.collect {
        case f if !RandomFill.matches(f.group(3).trim) =>
          s"$name: ${f.group(1)} is filled from '${f.group(3).trim}', not from `RANDOM"
      } ++ Option.when(fills.isEmpty)(s"$name: nothing is filled from `RANDOM")
      Result(
        1,
        registers.size,
        memories.size,
        unrecognized ++ unguarded ++ registerProblems ++ memoryProblems ++ fillProblems
      )
    }
  }

  /** The check of every `*.sv` file directly in `dir`, the primary sources of a simulation (the
    * assertions in `verification/` hold no state).
    */
  def directory(dir: Path): Result =
    if (!Files.isDirectory(dir)) Result(0, 0, 0, Seq(s"$dir: no such directory"))
    else {
      val files = Using.resource(Files.list(dir)) {
        _.iterator().asScala.filter(_.getFileName.toString.endsWith(".sv")).toVector.sorted
      }
      if (files.isEmpty) Result(0, 0, 0, Seq(s"$dir: no .sv file"))
      else
        files
          .map(f =>
            file(f.getFileName.toString, new String(Files.readAllBytes(f), StandardCharsets.UTF_8))
          )
          .reduce(_ ++ _)
    }
}
