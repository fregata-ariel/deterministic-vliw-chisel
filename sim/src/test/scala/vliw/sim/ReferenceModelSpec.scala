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

  private def machineFile(name: String) =
    MachineDescription
      .load(Paths.get("tests", "machines", name))
      .fold(es => fail(es.mkString), identity)

  test("L9: an operation's unit accepts a new issue II cycles after the previous one") {
    val ii = machineFile("ii_units.json") // ADD, SUB, MUL II 2; LD II 3
    def on(src: String) =
      ReferenceModel.run(ii, words(src, ii), Inputs(Map(1 -> 3L, 2 -> 4L), Map(0 -> 5L, 1 -> 6L)))
    assert(
      on("mul r3, r1, r2\nmul r4, r1, r2\nnop\nhalt").left.map(_.rule) == Left(Rule.IssueInterval)
    )
    assert(
      on("| ld r3, [0]\nnop\n| ld r4, [1]\nnop\nhalt").left.map(_.rule) == Left(Rule.IssueInterval)
    )
    val r = on("add r3, r1, r2\nsub r4, r1, r2\nadd r5, r3, r4\nhalt").fold(
      v => fail(v.toString),
      identity
    )
    assert(r.registers(5).contains(6L)) // (3 + 4) + (3 - 4) modulo 2^32
  }

  test("L10: a load of a word being stored stops the run, even if the old value is defined") {
    val slow = machineFile("slow_units.json") // ST latency 2
    def on(src: String) =
      ReferenceModel.run(slow, words(src, slow), Inputs(Map(1 -> 9L), Map(0 -> 4L, 2 -> 5L)))
    val v = on("| st [2], r1\n| ld r2, [2]\nnop\nnop\nhalt").left.toOption.get
    assert(v.rule == Rule.InFlightStoreRead && v.cycle.contains(1))
    val r = on("| st [2], r1\n| ld r2, [0]\n| ld r3, [2]\nnop\nnop\nhalt").fold(
      v => fail(v.toString),
      identity
    )
    assert(r.registers(2).contains(4L) && r.registers(3).contains(9L))
  }

  private val mvp1 = MachineDescription
    .load(MachineDescription.Mvp1Path)
    .fold(es => fail(es.mkString), identity)
  private val rp4 = machineFile("mvp1_rp4.json") // mvp-1 with 4 read ports

  private def runOn(
      m: MachineDescription,
      src: String,
      regs: Map[Int, Long],
      mem: Map[Int, Long] = Map.empty
  ) = ReferenceModel.run(m, words(src, m), Inputs(regs, mem))

  private def okOn(
      m: MachineDescription,
      src: String,
      regs: Map[Int, Long],
      mem: Map[Int, Long] = Map.empty
  ) = runOn(m, src, regs, mem).fold(v => fail(v.toString), identity)

  private def violationOn(m: MachineDescription, src: String, regs: Map[Int, Long]) =
    runOn(m, src, regs).left.getOrElse(fail(s"expected a violation:\n$src"))

  test("MAC is modulo 2^32 and commits at s + L through the compute port") {
    val r = okOn(mvp1, "mac r2, r1, r1, r1\nnop\nhalt", Map(1 -> 0xffffffffL))
    // (2^32 - 1) * (2^32 - 1) + (2^32 - 1) = 2^32 * (2^32 - 1), which is 0 modulo 2^32.
    assert(r.registers(2).contains(0L))
    assert(r.trace.cycles(1).commits.isEmpty)
    assert(r.trace.cycles(2).commits == Vector(Commit.Register(2, 0, Slot.Compute)))
    assert(r.cycles == 3)
  }

  test("MAC reads rs3 in the issue cycle: with rd = rs3 it reads the old value") {
    val in = Map(1 -> 1L, 2 -> 4L, 3 -> 5L)
    val acc = okOn(mvp1, "mac r1, r2, r3, r1\nnop\nmac r1, r2, r3, r1\nnop\nhalt", in)
    assert(acc.registers(1).contains(41L)) // 1 + 4 * 5 = 21, then 21 + 4 * 5
    // A ST in the same bundle (4 read ports) also reads the old r1.
    val same = okOn(rp4, "mac r1, r2, r3, r1 | st [0], r1\nnop\nhalt", in)
    assert(same.registers(1).contains(21L) && same.memory(0).contains(1L))
    // In the next cycle r1 is being written.
    val v = violationOn(mvp1, "mac r1, r2, r3, r1\nnop | st [0], r1\nhalt", in)
    assert(v.rule == Rule.InFlightRead && v.cycle.contains(1))
  }

  test("MAC latency 3: results from s + 3, and MAC(s) clashes with MUL(s + 1) at E_(s+3) (L5)") {
    val mac3 = machineFile("mvp1_mac3.json")
    val in = Map(1 -> 2L, 2 -> 3L, 3 -> 4L)
    // MAC(0) commits at E_3 and ADD(1) at E_2, so they do not clash as they do with latency 2.
    val r = okOn(mac3, "mac r4, r1, r2, r3\nadd r5, r1, r2\nnop\nhalt", in)
    assert(r.trace.cycles(2).commits == Vector(Commit.Register(5, 5, Slot.Compute)))
    assert(r.trace.cycles(3).commits == Vector(Commit.Register(4, 10, Slot.Compute)))
    assert(
      violationOn(mvp1, "mac r4, r1, r2, r3\nadd r5, r1, r2\nnop\nhalt", in).rule ==
        Rule.WritebackPort
    )
    // s + 2 is inside the write interval [0, 3).
    val early = violationOn(mac3, "mac r4, r1, r2, r3\nnop\nadd r5, r4, r1\nnop\nhalt", in)
    assert(early.rule == Rule.InFlightRead && early.cycle.contains(2))
    val clash = violationOn(mac3, "mac r4, r1, r2, r3\nmul r5, r1, r2\nnop\nnop\nhalt", in)
    assert(clash.rule == Rule.WritebackPort && clash.cycle.contains(1))
    assert(okOn(mvp1, "mac r4, r1, r2, r3\nmul r5, r1, r2\nnop\nhalt", in).cycles == 4)
    assert(violationOn(mac3, "mac r4, r1, r2, r3\nnop\nhalt", in).rule == Rule.HaltCompletion)
  }

  test("L4: MAC's rd overlaps a LD of the same bundle and a MAC in flight") {
    val in = Map(1 -> 2L, 2 -> 3L, 3 -> 4L)
    // MAC and LD both commit r4 at E_2, through different write-back ports.
    val same = runOn(mvp1, "mac r4, r1, r2, r3 | ld r4, [0]\nnop\nhalt", in, Map(0 -> 0L))
    assert(
      same.left.toOption.map(v => (v.rule, v.cycle)) == Some((Rule.OverlappingWrites, Some(0)))
    )
    // [0, 2) and [1, 3).
    val next = violationOn(mvp1, "mac r4, r1, r2, r3\nmac r4, r1, r2, r3\nnop\nhalt", in)
    assert((next.rule, next.cycle) == ((Rule.OverlappingWrites, Some(1))))
    // [0, 2) and [2, 4) do not overlap: 2 * 3 + 4 = 10, then 2 * 3 + 10.
    val after = okOn(mvp1, "mac r4, r1, r2, r3\nnop\nmac r4, r1, r2, r4\nnop\nhalt", in)
    assert(after.registers(4).contains(16L))
  }

  test("L9: MAC has its own unit with operations.MAC.ii, independent of MUL") {
    val v = ujson.read(mvp1.canonicalJson)
    v("operations")("MAC")("ii") = 2
    val ii = MachineDescription.parse(ujson.write(v)).fold(es => fail(es.mkString), identity)
    val in = Map(1 -> 2L, 2 -> 3L, 3 -> 4L)
    val back = violationOn(ii, "mac r4, r1, r2, r3\nmac r5, r1, r2, r3\nnop\nhalt", in)
    assert(back.rule == Rule.IssueInterval && back.cycle.contains(1))
    // MUL right after MAC, and the next MAC exactly II after the first.
    val r = okOn(
      ii,
      "mac r4, r1, r2, r3\nmul r5, r1, r2\nmac r6, r1, r2, r3\nmul r7, r1, r2\nnop\nhalt",
      in
    )
    assert(r.registers.slice(4, 8) == Vector(Some(10L), Some(6L), Some(10L), Some(6L)))
  }

  test("L11: MAC and ST in one bundle stop the run with 3 read ports, not with 4") {
    val in = Map(1 -> 2L, 2 -> 3L, 3 -> 4L, 5 -> 9L)
    val src = "add r6, r1, r2 | st [1], r5\nmac r4, r1, r2, r3 | st [0], r5\nnop\nhalt"
    val v = violationOn(mvp1, src, in)
    assert(v.rule == Rule.ReadPorts && v.cycle.contains(1))
    val r = okOn(rp4, src, in)
    assert(r.registers(4).contains(10L) && r.memory(0).contains(9L) && r.memory(1).contains(9L))
    // L11 is checked before the bundle's reads: r9 is also undefined (L8).
    val first = violationOn(mvp1, "mac r4, r1, r2, r9 | st [0], r9\nnop\nhalt", in)
    assert(first.rule == Rule.ReadPorts)
  }

  test("L11 counts reads by operand field, so one register in four fields is 4 reads") {
    val in = Map(2 -> 3L)
    val src = "mac r1, r2, r2, r2 | st [0], r2\nnop\nhalt"
    val v = violationOn(mvp1, src, in)
    assert(v.rule == Rule.ReadPorts && v.cycle.contains(0))
    val r = okOn(rp4, src, in)
    assert(r.registers(1).contains(12L) && r.memory(0).contains(3L)) // 3 * 3 + 3
    assert(okOn(mvp1, "mul r1, r2, r2 | st [0], r2\nnop\nhalt", in).registers(1).contains(9L))
  }

  test("MAC is an encoding error (L1) on an mvp-0 machine") {
    // mac r5, r1, r2, r3 with a NOP memory word (docs/ISA.md, "mvp-1: MAC"), NOP, HALT.
    val program = Vector(0x0000000045123000L, 0L, 0x00000000f0000000L)
    val in = Inputs(Map(1 -> 2L, 2 -> 3L, 3 -> 4L), Map.empty)
    assert(ReferenceModel.run(machine, program, in).left.map(_.rule) == Left(Rule.Encoding))
    val r = ReferenceModel.run(mvp1, program, in).fold(v => fail(v.toString), identity)
    assert(r.registers(5).contains(10L))
  }

  test("MAC in the trace: its issue text, and its commit through the compute port") {
    val r = okOn(
      mvp1,
      "mac r4, r1, r2, r3 | ld r5, [0]\nnop\nmac r6, r4, r5, r4\nnop | st [1], r4\nhalt",
      Map(1 -> 2L, 2 -> 3L, 3 -> 4L),
      Map(0 -> 7L)
    )
    // Derived by hand: 2 * 3 + 4 = 10 and [0] = 7 at E_2, 10 * 7 + 10 = 80 and [1] = 10 at E_4.
    val expected =
      """{"cycle":0,"pc":0,"issue":{"compute":"mac r4, r1, r2, r3","memory":"ld r5, [0]"},"rf_commit":[],"mem_commit":[],"busy":true,"done":false}
        |{"cycle":1,"pc":1,"issue":{"compute":"nop","memory":"nop"},"rf_commit":[],"mem_commit":[],"busy":true,"done":false}
        |{"cycle":2,"pc":2,"issue":{"compute":"mac r6, r4, r5, r4","memory":"nop"},"rf_commit":[{"reg":4,"value":10,"port":"compute"},{"reg":5,"value":7,"port":"memory"}],"mem_commit":[],"busy":true,"done":false}
        |{"cycle":3,"pc":3,"issue":{"compute":"nop","memory":"st [1], r4"},"rf_commit":[],"mem_commit":[],"busy":true,"done":false}
        |{"cycle":4,"pc":4,"issue":{"compute":"halt","memory":"nop"},"rf_commit":[{"reg":6,"value":80,"port":"compute"}],"mem_commit":[{"addr":1,"value":10}],"busy":true,"done":false}
        |{"cycle":5,"pc":null,"issue":null,"rf_commit":[],"mem_commit":[],"busy":false,"done":true}""".stripMargin
    assert(canonicalLines(r.trace.bodyLines.mkString("\n")) == canonicalLines(expected))
    val header = r.trace.header
    assert(header("isa").str == "mvp-1" && header("machine").str == "vliw2-i32-sp2-mac")
    assert(header("machine_hash").str == mvp1.hash)
  }

  // ISA mvp-2 (#110 stage 2, docs/ISA.md "mvp-2: W").

  private val mvp2 = MachineDescription
    .load(MachineDescription.Mvp2Path)
    .fold(es => fail(es.mkString), identity)

  test("mvp-2 trace: a pair commits as two rf_commit items of one port, ST.W as two mem_commit") {
    // docs/METRICS.md, the vliw-metrics/3 example: M[0] = 3, M[17] = 5, M[18] = 2^32 - 1.
    val r = okOn(
      mvp2,
      "nop | ld.w r2:r3, [17]\nnop | ld r1, [0]\nnop\nvmul r4:r5, r1, r2:r3\nnop\n" +
        "nop | st.w [20], r4:r5\nhalt",
      Map.empty,
      Map(0 -> 3L, 17 -> 5L, 18 -> 0xffffffffL)
    )
    // Derived by hand: lane 0 of ld.w is word 17 (bank 1), lane 1 word 18 (bank 0, the next row);
    // vmul broadcasts r1 = 3: 3 * 5 = 15 and 3 * (2^32 - 1) = 2^32 - 3.
    val expected =
      """{"cycle":0,"pc":0,"issue":{"compute":"nop","memory":"ld.w r2:r3, [17]"},"rf_commit":[],"mem_commit":[],"busy":true,"done":false}
        |{"cycle":1,"pc":1,"issue":{"compute":"nop","memory":"ld r1, [0]"},"rf_commit":[],"mem_commit":[],"busy":true,"done":false}
        |{"cycle":2,"pc":2,"issue":{"compute":"nop","memory":"nop"},"rf_commit":[{"reg":2,"value":5,"port":"memory"},{"reg":3,"value":4294967295,"port":"memory"}],"mem_commit":[],"busy":true,"done":false}
        |{"cycle":3,"pc":3,"issue":{"compute":"vmul r4:r5, r1, r2:r3","memory":"nop"},"rf_commit":[{"reg":1,"value":3,"port":"memory"}],"mem_commit":[],"busy":true,"done":false}
        |{"cycle":4,"pc":4,"issue":{"compute":"nop","memory":"nop"},"rf_commit":[],"mem_commit":[],"busy":true,"done":false}
        |{"cycle":5,"pc":5,"issue":{"compute":"nop","memory":"st.w [20], r4:r5"},"rf_commit":[{"reg":4,"value":15,"port":"compute"},{"reg":5,"value":4294967293,"port":"compute"}],"mem_commit":[],"busy":true,"done":false}
        |{"cycle":6,"pc":6,"issue":{"compute":"halt","memory":"nop"},"rf_commit":[],"mem_commit":[{"addr":20,"value":15},{"addr":21,"value":4294967293}],"busy":true,"done":false}
        |{"cycle":7,"pc":null,"issue":null,"rf_commit":[],"mem_commit":[],"busy":false,"done":true}""".stripMargin
    assert(canonicalLines(r.trace.bodyLines.mkString("\n")) == canonicalLines(expected))
    val header = r.trace.header
    assert(header("trace").str == "vliw-trace/1" && header("isa").str == "mvp-2")
    assert(
      header("machine").str == "vliw2-i32-sp2-mac-w2" && header("machine_hash").str == mvp2.hash
    )
  }

  test("mvp-2 trace order: the compute port before the memory port, each by register number") {
    // vmul reads the old r1 in its issue cycle, while ld.w writes r0:r1; both commit at E_2.
    val r = okOn(
      mvp2,
      "vmul r6:r7, r1, r2:r3 | ld.w r0:r1, [0]\nnop\nhalt",
      Map(1 -> 2L, 2 -> 3L, 3 -> 4L),
      Map(0 -> 10L, 1 -> 11L)
    )
    assert(
      r.trace.cycles(2).commits == Vector(
        Commit.Register(6, 6, Slot.Compute),
        Commit.Register(7, 8, Slot.Compute),
        Commit.Register(0, 10, Slot.Memory),
        Commit.Register(1, 11, Slot.Memory)
      )
    )
  }

  test("mvp-2 lanes are modulo 2^32 each, and a broadcast rs1 feeds both lanes") {
    val m = 0xffffffffL
    val r = okOn(
      mvp2,
      "vadd r4:r5, r0:r1, r2:r3\nvsub r6:r7, r2:r3, r0:r1\nvmul r8:r9, r1, r2:r3\nnop\n" +
        "vmac r10:r11, r0:r1, r2:r3, r6:r7\nnop\nhalt",
      Map(0 -> m, 1 -> 1L, 2 -> 1L, 3 -> m)
    )
    // (2^32 - 1) + 1 and 1 + (2^32 - 1) are 0 in each lane: no carry from lane 0 into lane 1.
    assert(r.registers.slice(4, 6) == Vector(Some(0L), Some(0L)))
    // 1 - (2^32 - 1) = 2 and (2^32 - 1) - 1.
    assert(r.registers.slice(6, 8) == Vector(Some(2L), Some(m - 1)))
    // r1 = 1 times r2:r3.
    assert(r.registers.slice(8, 10) == Vector(Some(1L), Some(m)))
    // (2^32 - 1) * 1 + 2 = 2^32 + 1 and 1 * (2^32 - 1) + (2^32 - 2) = 2^33 - 3.
    assert(r.registers.slice(10, 12) == Vector(Some(1L), Some(m - 2)))
  }

  test("mvp-2 LD.W reads words a and a + 1 as seen in its issue cycle, odd a included") {
    // ST latency 1: the word 18 stored at cycle 0 is visible to the ld.w at cycle 1, word 17 is not
    // written. Lane 0 is word 17 (bank 1, row 8) and lane 1 word 18 (bank 0, row 9).
    val r = okOn(
      mvp2,
      "nop | st [18], r1\nnop | ld.w r2:r3, [17]\nnop\nhalt",
      Map(1 -> 9L),
      Map(17 -> 4L)
    )
    assert(r.registers.slice(2, 4) == Vector(Some(4L), Some(9L)))
    val w = okOn(mvp2, "nop | st.w [7], r2:r3\nhalt", Map(2 -> 5L, 3 -> 6L))
    assert(w.memory.slice(7, 9) == Vector(Some(5L), Some(6L)) && w.memory(6).isEmpty)
  }

  test("mvp-2 runtime checks: per register, per word, and L5 per operation") {
    val in = Map((0 until 16).map(r => r -> r.toLong): _*)
    def v(m: MachineDescription, src: String, mem: Map[Int, Long] = Map.empty) =
      runOn(m, src, in, mem).left.toOption.map(x => (x.rule, x.cycle))
    // L5: two operations commit through the compute port at E_2; one pair alone is legal.
    assert(
      v(mvp2, "vmul r4:r5, r1, r2:r3\nadd r6, r1, r2\nhalt") == Some((Rule.WritebackPort, Some(1)))
    )
    assert(
      v(mvp2, "vmul r4:r5, r1, r2:r3 | ld.w r6:r7, [0]\nnop\nhalt", Map(0 -> 1L, 1 -> 2L)).isEmpty
    )
    val vlat = machineFile("mvp2_vlat.json")
    assert(
      v(
        vlat,
        "nop | ld.w r2:r3, [0]\nnop | ld r4, [2]\nnop\nhalt",
        Map(0 -> 1L, 1 -> 2L, 2 -> 3L)
      ) ==
        Some((Rule.WritebackPort, Some(1)))
    )
    // L3 on the second register of a pair, and L4 between a pair and a scalar write.
    assert(
      v(mvp2, "vmul r4:r5, r1, r2:r3\nnop | st [0], r5\nnop\nhalt") == Some(
        (Rule.InFlightRead, Some(1))
      )
    )
    assert(
      v(mvp2, "vmac r4:r5, r2, r6:r7, r8:r9 | ld r5, [0]\nnop\nhalt", Map(0 -> 1L)) ==
        Some((Rule.OverlappingWrites, Some(0)))
    )
    // A broadcast rs1 reads one register: r2 has no value, and it is not read.
    val broadcast = runOn(mvp2, "vmul r4:r5, r1, r6:r7\nnop\nhalt", Map(1 -> 2L, 6 -> 3L, 7 -> 4L))
    assert(broadcast.map(_.registers.slice(4, 6)) == Right(Vector(Some(6L), Some(8L))))
    val lane = runOn(mvp2, "vadd r4:r5, r2:r3, r6:r7\nhalt", Map(2 -> 1L, 6 -> 1L, 7 -> 1L))
    assert(lane.left.toOption.map(x => (x.rule, x.cycle)) == Some((Rule.UndefinedRead, Some(0))))
    // L7 and L8 on the second word.
    assert(
      v(mvp2, "nop | ld.w r2:r3, [511]\nnop\nhalt", Map(511 -> 1L)) == Some(
        (Rule.AddressRange, Some(0))
      )
    )
    assert(v(mvp2, "nop | st.w [511], r2:r3\nhalt") == Some((Rule.AddressRange, Some(0))))
    assert(
      v(mvp2, "nop | ld.w r2:r3, [8]\nnop\nhalt", Map(8 -> 1L)) == Some(
        (Rule.UndefinedRead, Some(0))
      )
    )
    // L10 per word, on a machine whose ST and ST.W take 2 cycles.
    val vii = machineFile("mvp2_vii.json")
    val mem = Map(4 -> 1L, 5 -> 2L, 16 -> 3L, 17 -> 4L, 18 -> 5L)
    assert(
      v(vii, "nop | st [18], r1\nnop | ld.w r2:r3, [17]\nnop\nhalt", mem) ==
        Some((Rule.InFlightStoreRead, Some(1)))
    )
    assert(v(vii, "nop | st [16], r1\nnop | ld.w r2:r3, [17]\nnop\nhalt", mem).isEmpty)
    assert(
      v(vii, "nop | st.w [4], r2:r3\nnop | ld r6, [5]\nnop\nhalt", mem) ==
        Some((Rule.InFlightStoreRead, Some(1)))
    )
    // L9: each new operation has its own unit; L6 and L11 as for the scalar operations.
    assert(
      v(vii, "vmul r4:r5, r1, r2:r3\nvmul r6:r7, r1, r2:r3\nnop\nhalt") == Some(
        (Rule.IssueInterval, Some(1))
      )
    )
    assert(v(vii, "vmul r4:r5, r1, r2:r3\nmul r6, r1, r2\nnop\nhalt").isEmpty)
    assert(v(vii, "vadd r4:r5, r2:r3, r2:r3\nvsub r6:r7, r2:r3, r2:r3\nhalt").isEmpty)
    assert(v(mvp2, "vmac r4:r5, r2, r6:r7, r8:r9\nhalt") == Some((Rule.HaltCompletion, Some(1))))
    assert(
      v(mvp2, "vmac r4:r5, r2, r6:r7, r8:r9 | st.w [0], r10:r11\nnop\nhalt") == Some(
        (Rule.ReadPorts, Some(0))
      )
    )
    assert(
      v(
        machineFile("mvp2_rp4.json"),
        "vmac r4:r5, r2, r6:r7, r8:r9 | st.w [0], r10:r11\nnop\nhalt"
      ).isEmpty
    )
  }
}
