package vliw.sim

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.util.Random

import org.scalatest.funsuite.AnyFunSuite

import vliw.isa._

class ReferenceModelSpec extends AnyFunSuite {
  private val machine = MachineDescription.load().fold(es => fail(es.mkString("\n")), identity)

  private def words(src: String, m: MachineDescription = machine): Vector[Long] =
    Assembler
      .assemble(src, m)
      .flatMap(p => new BundleCodec(m).encodeAll(p.bundles).left.map(List(_)))
      .fold(es => fail(es.mkString("\n")), identity)

  private def run(src: String, regs: Map[Int, Long], mem: Map[Int, Long]) =
    ReferenceModel.run(machine, words(src), Inputs(regs, mem))

  private def ok(src: String, regs: Map[Int, Long], mem: Map[Int, Long] = Map.empty) =
    run(src, regs, mem).fold(v => fail(v.toString), identity)

  private def rule(src: String, regs: Map[Int, Long], mem: Map[Int, Long] = Map.empty) =
    run(src, regs, mem).left.map(_.rule).left.getOrElse(fail(s"expected a violation:\n$src"))

  private def canonicalLines(text: String): Vector[String] =
    text.linesIterator
      .filter(_.nonEmpty)
      .map(l => Json.parse(l).flatMap(Json.canonical).fold(e => fail(e.message), identity))
      .toVector

  private def readText(p: Path) = new String(Files.readAllBytes(p), StandardCharsets.UTF_8)

  private val isaCase =
    TestCase
      .load(Paths.get("tests", "cases", "isa_example.json"))
      .fold(es => fail(es.mkString), identity)
  private def isaRun = ReferenceModel
    .run(isaCase.machine, isaCase.words, isaCase.inputs)
    .fold(v => fail(v.toString), identity)

  test("ISA example: memory[1] = 28 in 7 cycles, trace equals the hand-written golden trace") {
    val r = isaRun
    assert(r.cycles == 7)
    assert(r.memory(1).contains(28L))
    assert(r.registers.take(5) == Vector(None, Some(7L), Some(3L), Some(21L), Some(28L)))
    val golden = canonicalLines(readText(Paths.get("tests", "golden", "isa_example.trace.jsonl")))
    assert(canonicalLines(r.trace.bodyLines.mkString("\n")) == golden)
  }

  test("trace header identifies the ISA and machine description") {
    val lines = isaRun.trace.jsonLines.linesIterator.toVector
    assert(lines.size == 9)
    val header = ujson.read(lines.head)
    assert(header("trace").str == "vliw-trace/1" && header("isa").str == "mvp-0")
    assert(header("machine_hash").str == machine.hash)
  }

  test("every test case in tests/cases agrees with the reference model") {
    val cases = TestCase.loadAll().fold(es => fail(es.mkString("\n")), identity)
    cases.foreach { c =>
      val result = ReferenceModel.run(c.machine, c.words, c.inputs)
      c.expect.verdict match {
        case Verdict.Legal =>
          val r = result.fold(v => fail(s"${c.name}: unexpected violation $v"), identity)
          c.expect.cycles.foreach(n => assert(r.cycles == n, c.name))
          c.expect.registers.foreach { case (reg, v) =>
            assert(r.registers(reg).contains(v), s"${c.name}: r$reg")
          }
          c.expect.memory.foreach { case (a, v) =>
            assert(r.memory(a).contains(v), s"${c.name}: [$a]")
          }
          c.expect.trace.foreach { t =>
            assert(
              canonicalLines(r.trace.bodyLines.mkString("\n")) == canonicalLines(readText(t)),
              c.name
            )
          }
        case Verdict.Illegal(rules) =>
          val v = result.left.getOrElse(
            fail(s"${c.name}: expected one of ${rules.map(_.id)}, but it ran")
          )
          assert(
            rules.contains(v.rule),
            s"${c.name}: model reported $v, expected one of ${rules.map(_.id)}"
          )
      }
    }
  }

  test("cycle count and commit timing do not depend on input values") {
    def timing(trace: Trace) = trace.cycles.map { c =>
      val where = c.commits.map {
        case Commit.Register(r, _, p) => s"r$r/${p.name}"
        case Commit.Memory(a, _)      => s"[$a]"
      }
      (c.cycle, c.pc, c.issue, where, c.busy, c.done)
    }
    val reference = timing(isaRun.trace)
    val rng = new Random(42L)
    (0 until 500).foreach { _ =>
      val r2 = rng.nextLong() & 0xffffffffL
      val m0 = rng.nextLong() & 0xffffffffL
      val r = ReferenceModel
        .run(machine, isaCase.words, Inputs(Map(2 -> r2), Map(0 -> m0)))
        .fold(v => fail(v.toString), identity)
      assert(r.cycles == 7)
      assert(timing(r.trace) == reference)
      // Expected values, derived by hand from the program: r1 = m0, r3 = r1 * r2, r4 = r3 + r1.
      val r3 = (m0 * r2) & 0xffffffffL
      val r4 = (r3 + m0) & 0xffffffffL
      assert(
        r.registers(1).contains(m0) && r.registers(3).contains(r3) && r.registers(4).contains(r4)
      )
      assert(r.memory(1).contains(r4))
    }
  }

  test("arithmetic is modulo 2^32") {
    val r = ok(
      """add r3, r1, r2
        |sub r4, r0, r2
        |mul r5, r1, r1
        |nop
        |halt""".stripMargin,
      Map(0 -> 0L, 1 -> 0xffffffffL, 2 -> 1L)
    )
    assert(r.registers(3).contains(0L))
    assert(r.registers(4).contains(0xffffffffL))
    assert(r.registers(5).contains(1L)) // (2^32 - 1)^2 mod 2^32 = 1
  }

  test("results of both slots commit at s + L through their own ports") {
    val r = ok("mul r2, r1, r1 | ld r3, [0]\nnop\nhalt", Map(1 -> 3L), Map(0 -> 5L))
    val atTwo = r.trace.cycles(2).commits
    assert(atTwo == Vector(Commit.Register(2, 9, Slot.Compute), Commit.Register(3, 5, Slot.Memory)))
    assert(r.trace.cycles(1).commits.isEmpty)
    assert(r.cycles == 3)
  }

  test("reading in the issue cycle sees the old value") {
    val r = ok("add r1, r1, r2 | st [0], r1\nhalt", Map(1 -> 10L, 2 -> 5L))
    assert(r.registers(1).contains(15L) && r.memory(0).contains(10L))
  }

  test("a load sees a store issued in an earlier cycle") {
    val r = ok("| st [3], r1\n| ld r2, [3]\nnop\nhalt", Map(1 -> 99L))
    assert(r.registers(2).contains(99L))
  }

  test("runtime checks report the violated rule") {
    val in = Map(1 -> 1L, 2 -> 2L)
    assert(rule("mul r3, r1, r2\nadd r4, r3, r1\nhalt", in) == Rule.InFlightRead)
    assert(
      rule("add r1, r2, r2 | ld r1, [0]\nnop\nhalt", in, Map(0 -> 0L)) == Rule.OverlappingWrites
    )
    assert(
      rule("mul r1, r2, r2\n| ld r1, [0]\nnop\nhalt", in, Map(0 -> 0L)) == Rule.OverlappingWrites
    )
    assert(rule("mul r3, r1, r2\nadd r4, r1, r2\nnop\nhalt", in) == Rule.WritebackPort)
    assert(rule("| ld r3, [0]\nhalt", in, Map(0 -> 0L)) == Rule.HaltCompletion)
    assert(rule("nop\nnop", in) == Rule.ProgramShape)
    assert(rule("halt\nnop", in) == Rule.ProgramShape)
    assert(rule("add r3, r1, r9\nhalt", in) == Rule.UndefinedRead)
    assert(rule("| ld r3, [4]\nnop\nhalt", in) == Rule.UndefinedRead)
    val none = Inputs(Map.empty, Map.empty)
    assert(
      ReferenceModel.run(machine, Vector.empty, none).left.map(_.rule) == Left(Rule.ProgramShape)
    )
    assert(
      ReferenceModel.run(machine, Vector(0x40000000L), none).left.map(_.rule) == Left(Rule.Encoding)
    )
    val tooLong = Vector.fill(machine.instructionMemoryBundles)(0L) :+ 0x00000000f0000000L
    assert(ReferenceModel.run(machine, tooLong, none).left.map(_.rule) == Left(Rule.ProgramShape))
  }

  test("addresses beyond a smaller scratchpad violate L7") {
    val v = ujson.read(machine.canonicalJson)
    v("scratchpad")("words_per_bank") = 4
    val small = MachineDescription.parse(ujson.write(v)).fold(es => fail(es.mkString), identity)
    val result = ReferenceModel.run(
      small,
      words("nop | st [8], r1\nhalt", small),
      Inputs(Map(1 -> 1L), Map.empty)
    )
    assert(result.left.map(_.rule) == Left(Rule.AddressRange))
  }
}
