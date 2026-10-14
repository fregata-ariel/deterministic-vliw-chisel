package vliw.isa

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.jdk.CollectionConverters._
import scala.util.Try

import vliw.isa.JsonReader.{Errors, Obj, kind}

/** Expected judgement of a program under the MVP legality rules. */
sealed trait Verdict extends Product with Serializable
object Verdict {
  case object Legal extends Verdict

  /** Illegal; `rules` is the exact set of rules the verifier must report. */
  final case class Illegal(rules: Set[Rule]) extends Verdict
}

/** Values the host places before start (the input declaration R_in / M_in of rule L8). */
final case class Inputs(registers: Map[Int, Long], memory: Map[Int, Long])

/** Hand-written expectations. Final state maps are partial: unlisted locations are not checked.
  * `trace` names a golden trace body (docs/TRACE.md) to compare cycle by cycle.
  */
final case class Expectation(
    verdict: Verdict,
    cycles: Option[Int],
    registers: Map[Int, Long],
    memory: Map[Int, Long],
    trace: Option[Path] = None
)

/** A test case shared by the verifier, the reference model and the RTL (docs/TESTCASE.md). */
final case class TestCase(
    name: String,
    description: String,
    machine: MachineDescription,
    words: Vector[Long],
    source: Option[Assembler.Program],
    inputs: Inputs,
    expect: Expectation
)

object TestCase {
  val Format: String = "vliw-testcase/1"
  val DefaultDirectory: Path = Paths.get("tests", "cases")

  def load(path: Path): Either[List[String], TestCase] =
    Try(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).toEither.left
      .map(e => List(s"cannot read: ${e.getMessage}"))
      .flatMap(parse)
      .left
      .map(_.map(e => s"$path: $e"))

  /** All `*.json` files in `dir`, sorted by file name. */
  def loadAll(dir: Path = DefaultDirectory): Either[List[String], Vector[TestCase]] = {
    val files = Files.list(dir).iterator().asScala.filter(_.toString.endsWith(".json")).toVector
    val results = files.sortBy(_.getFileName.toString).map(load)
    val errors = results.collect { case Left(es) => es }.flatten
    if (errors.nonEmpty) Left(errors.toList) else Right(results.collect { case Right(c) => c })
  }

  def parse(text: String): Either[List[String], TestCase] =
    Json.parse(text).left.map(e => List(e.message)).flatMap {
      case ujson.Obj(fields) =>
        val errors = new Errors
        val root = new Obj("$", fields, errors)
        val result = decode(root, errors)
        root.finish()
        if (errors.isEmpty) result.toRight(List("$: invalid test case")) else Left(errors.toList)
      case other => Left(List(s"$$: must be an object, got ${kind(other)}"))
    }

  private def decode(root: Obj, errors: Errors): Option[TestCase] = {
    root.const("format", ujson.Str(Format))
    val name = root.str("name").filter { n =>
      val ok = n.matches("[a-z0-9][a-z0-9_]*")
      if (!ok) root.report("name", "must match [a-z0-9][a-z0-9_]*")
      ok
    }
    val description = root.str("description")
    val machine = (if (root.has("machine")) root.str("machine") else Some("config/machine.json"))
      .flatMap { p =>
        MachineDescription.load(Paths.get(p)) match {
          case Right(m) => Some(m)
          case Left(es) => es.foreach(e => root.report("machine", e)); None
        }
      }

    val program: Option[(Vector[Long], Option[Assembler.Program])] =
      (root.has("program"), root.has("program_hex")) match {
        case (true, false) =>
          for {
            lines <- root.strings("program")
            m <- machine
            asm <- Assembler.assemble(lines.mkString("\n"), m) match {
              case Right(p) => Some(p)
              case Left(es) => es.foreach(e => root.report("program", e)); None
            }
            words <- new BundleCodec(m).encodeAll(asm.bundles) match {
              case Right(ws) => Some(ws)
              case Left(e)   => root.report("program", e); None
            }
          } yield (words, Some(asm))
        case (false, true) =>
          root.strings("program_hex").flatMap { lines =>
            ProgramFile.fromHex(lines.map(_ + "\n").mkString) match {
              case Right(ws) => Some((ws, None))
              case Left(e)   => root.report("program_hex", e); None
            }
          }
        case _ =>
          root.report("program", "exactly one of \"program\" and \"program_hex\" is required")
          None
      }

    val inputs = root
      .obj("inputs") { o =>
        for {
          regs <- registers(o, "registers")
          mem <- memory(o, "memory")
        } yield Inputs(regs, mem)
      }
      .flatten

    val expect = root
      .obj("expect") { o =>
        val verdict: Option[Verdict] = o.raw("verdict").flatMap {
          case ujson.Str("legal")                         => Some(Verdict.Legal)
          case ujson.Obj(v) if v.keySet == Set("illegal") =>
            v("illegal") match {
              case ujson.Arr(ids) if ids.nonEmpty =>
                val rules = ids.toVector.map {
                  case ujson.Str(id) if Rule.byId.contains(id) => Some(Rule.byId(id))
                  case other                                   =>
                    o.report("verdict", s"unknown rule ${ujson.write(other)}"); None
                }
                if (rules.forall(_.isDefined)) Some(Verdict.Illegal(rules.flatten.toSet)) else None
              case _ => o.report("verdict", "illegal must be a non-empty array of rule ids"); None
            }
          case _ => o.report("verdict", """must be "legal" or {"illegal": ["L1", ...]}"""); None
        }
        val legal = verdict.contains(Verdict.Legal)
        def onlyIfLegal(key: String): Boolean = {
          val present = o.has(key)
          if (present && !legal) o.report(key, "is only allowed for legal programs")
          present
        }
        val cycles =
          if (onlyIfLegal("cycles")) o.int("cycles", 1, Int.MaxValue).map(Some(_)) else Some(None)
        val regs =
          if (onlyIfLegal("registers")) registers(o, "registers") else Some(Map.empty[Int, Long])
        val mem = if (onlyIfLegal("memory")) memory(o, "memory") else Some(Map.empty[Int, Long])
        val trace =
          if (onlyIfLegal("trace"))
            o.str("trace").flatMap { p =>
              val path = Paths.get(p)
              if (Files.isRegularFile(path)) Some(Some(path))
              else { o.report("trace", s"no such file: $p"); None }
            }
          else Some(None)
        for (v <- verdict; c <- cycles; r <- regs; m <- mem; t <- trace)
          yield Expectation(v, c, r, m, t)
      }
      .flatten

    for {
      n <- name
      d <- description
      m <- machine
      (words, source) <- program
      in <- inputs.filter(in => inputsFit(root, m, in))
      ex <- expect
    } yield TestCase(n, d, m, words, source, in, ex)
  }

  /** Declared inputs must name locations that exist on the case's machine. */
  private def inputsFit(root: Obj, m: MachineDescription, in: Inputs): Boolean = {
    val regs = in.registers.keys.filter(_ >= m.registerCount).toSeq.sorted
    val mem = in.memory.keys.filter(_ >= m.scratchpadWords).toSeq.sorted
    regs.foreach(r =>
      root.report("inputs", s"r$r does not exist on ${m.machineId} (r0..r${m.registerCount - 1})")
    )
    mem.foreach(a =>
      root.report(
        "inputs",
        s"address $a does not exist on ${m.machineId} (0..${m.scratchpadWords - 1})"
      )
    )
    regs.isEmpty && mem.isEmpty
  }

  private val RegisterKey = "r([0-9]|1[0-5])".r
  private val AddressKey = "(0|[1-9][0-9]{0,2})".r

  private def registers(o: Obj, key: String): Option[Map[Int, Long]] =
    locations(o, key) {
      case RegisterKey(n) => Some(n.toInt)
      case _              => None
    }("register names r0..r15")

  private def memory(o: Obj, key: String): Option[Map[Int, Long]] =
    locations(o, key) {
      case AddressKey(n) if n.toInt <= BundleCodec.AddressMask => Some(n.toInt)
      case _                                                   => None
    }("decimal word addresses 0..511")

  private def locations(o: Obj, key: String)(
      index: String => Option[Int]
  )(what: String): Option[Map[Int, Long]] =
    o.entries(key).flatMap { entries =>
      val parsed = entries.map { case (k, v) =>
        (index(k), word(v)) match {
          case (Some(i), Some(w)) => Some(i -> w)
          case (None, _)          => o.report(key, s"keys must be $what, got \"$k\""); None
          case (_, None)          =>
            o.report(key, s"value of \"$k\" must be a 32-bit unsigned integer or \"0x...\" string");
            None
        }
      }
      if (parsed.forall(_.isDefined)) Some(parsed.flatten.toMap) else None
    }

  private val HexWord = "0x([0-9a-fA-F]{1,8})".r

  private def word(v: ujson.Value): Option[Long] = v match {
    case ujson.Num(d) if d.isWhole && d >= 0 && d <= 0xffffffffL.toDouble => Some(d.toLong)
    case ujson.Str(HexWord(h)) => Some(java.lang.Long.parseLong(h, 16))
    case _                     => None
  }
}
