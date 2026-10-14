package vliw.isa

import java.nio.file.Paths

import org.scalatest.funsuite.AnyFunSuite

class TestCaseSpec extends AnyFunSuite {
  private def base: ujson.Value = ujson.Obj(
    "format" -> TestCase.Format,
    "name" -> "t",
    "description" -> "d",
    "program" -> ujson.Arr("add r1, r2, r3", "halt"),
    "inputs" -> ujson
      .Obj("registers" -> ujson.Obj("r2" -> 1, "r3" -> "0xFFFFFFFF"), "memory" -> ujson.Obj()),
    "expect" -> ujson.Obj("verdict" -> "legal", "cycles" -> 2, "registers" -> ujson.Obj("r1" -> 0))
  )

  private def parse(v: ujson.Value) = TestCase.parse(ujson.write(v))
  private def errorsAfter(edit: ujson.Value => Unit): List[String] = {
    val v = base
    edit(v)
    parse(v).left.getOrElse(fail("expected the edited case to be rejected"))
  }

  test("a minimal case parses") {
    val c = parse(base).fold(es => fail(es.mkString("\n")), identity)
    assert(c.words.size == 2)
    assert(c.inputs.registers == Map(2 -> 1L, 3 -> 0xffffffffL))
    assert(c.expect == Expectation(Verdict.Legal, Some(2), Map(1 -> 0L), Map.empty))
    assert(c.source.map(_.lines) == Some(Vector(1, 2)))
  }

  test("tests/cases/isa_example.json describes the ISA example") {
    val c = TestCase
      .load(Paths.get("tests", "cases", "isa_example.json"))
      .fold(es => fail(es.mkString("\n")), identity)
    assert(
      ProgramFile.toHex(c.words) == new String(
        java.nio.file.Files.readAllBytes(Paths.get("tests", "golden", "isa_example.hex"))
      )
    )
    assert(c.inputs == Inputs(Map(2 -> 3L), Map(0 -> 7L)))
    assert(c.expect.cycles.contains(7))
    assert(c.expect.memory == Map(0 -> 7L, 1 -> 28L))
  }

  test("all cases in tests/cases load") {
    val cases = TestCase.loadAll().fold(es => fail(es.mkString("\n")), identity)
    assert(cases.nonEmpty)
    assert(cases.map(_.name).distinct.size == cases.size, "case names must be unique")
  }

  test("illegal verdicts name rules and forbid final-state expectations") {
    val v = base
    v("expect") = ujson.Obj("verdict" -> ujson.Obj("illegal" -> ujson.Arr("L3", "L5")))
    assert(
      parse(v).map(_.expect.verdict) == Right(
        Verdict.Illegal(Set(Rule.InFlightRead, Rule.WritebackPort))
      )
    )
    assert(
      errorsAfter(_("expect") = ujson.Obj("verdict" -> ujson.Obj("illegal" -> ujson.Arr("L9"))))
        .exists(_.contains("unknown rule"))
    )
    assert(
      errorsAfter(_("expect") =
        ujson.Obj("verdict" -> ujson.Obj("illegal" -> ujson.Arr("L3")), "cycles" -> 2)
      )
        .exists(_.contains("cycles: is only allowed for legal programs"))
    )
  }

  test("raw programs are given as hex words") {
    val v = base
    v.obj.remove("program")
    v("program_hex") = ujson.Arr("0000000040000000")
    v("expect") = ujson.Obj("verdict" -> ujson.Obj("illegal" -> ujson.Arr("L1")))
    val c = parse(v).fold(es => fail(es.mkString), identity)
    assert(c.words == Vector(0x40000000L) && c.source.isEmpty)
    assert(errorsAfter(_("program_hex") = ujson.Arr("xyz")).exists(_.contains("program")))
  }

  test("malformed cases are rejected with paths") {
    assert(errorsAfter(_("format") = "x").exists(_.startsWith("$.format")))
    assert(errorsAfter(_("extra") = 1).exists(_.contains("unknown key \"extra\"")))
    assert(errorsAfter(_("program_hex") = ujson.Arr()).exists(_.contains("exactly one of")))
    assert(
      errorsAfter(_("program") = ujson.Arr("bogus"))
        .exists(_.contains("line 1: unknown compute operation"))
    )
    assert(
      errorsAfter(_("inputs")("registers")("r16") = 1).exists(_.contains("register names r0..r15"))
    )
    assert(
      errorsAfter(_("inputs")("memory")("512") = 1).exists(_.contains("word addresses 0..511"))
    )
    assert(errorsAfter(_("inputs")("memory")("07") = 1).exists(_.contains("word addresses")))
    assert(
      errorsAfter(_("inputs")("registers")("r1") = 4294967296L)
        .exists(_.contains("32-bit unsigned"))
    )
    assert(errorsAfter(_("inputs")("registers")("r1") = -1).exists(_.contains("32-bit unsigned")))
    assert(errorsAfter(_("name") = "Bad").exists(_.contains("must match")))
    assert(errorsAfter(_("machine") = "nope.json").exists(_.startsWith("$.machine")))
    assert(errorsAfter { v =>
      v("machine") = "tests/machines/small_scratchpad.json"
      v("inputs")("memory")("8") = 1
    }.exists(_.contains("address 8 does not exist on vliw2-i32-sp8 (0..7)")))
  }
}
