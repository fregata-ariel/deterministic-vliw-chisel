package vliw.compiler

import org.scalatest.funsuite.AnyFunSuite

import vliw.isa._

class VerifierSpec extends AnyFunSuite {
  private val machine = MachineDescription.load().fold(es => fail(es.mkString("\n")), identity)

  private def words(src: String, m: MachineDescription = machine): Vector[Long] =
    Assembler
      .assemble(src, m)
      .flatMap(p => new BundleCodec(m).encodeAll(p.bundles).left.map(List(_)))
      .fold(es => fail(es.mkString("\n")), identity)

  /** Declares r0..r15 and [0..15] unless given, so that tests focus on the rule at hand. */
  private val all = Verifier.Declaration((0 until 16).toSet, (0 until 16).toSet)

  private def findings(src: String, declared: Verifier.Declaration) =
    Verifier.verify(machine, words(src), declared)
  private def rules(src: String, declared: Verifier.Declaration = all): Set[String] =
    findings(src, declared).map(_.rule.id).toSet

  test("the ISA example is legal with its input declaration") {
    val c =
      TestCase.load(java.nio.file.Paths.get("tests", "cases", "isa_example.json")).toOption.get
    assert(Verifier.verify(c.machine, c.words, Verifier.Declaration.of(c.inputs)).isEmpty)
  }

  test("every test case in tests/cases gets exactly the expected rules") {
    val cases = TestCase.loadAll().fold(es => fail(es.mkString("\n")), identity)
    cases.foreach { c =>
      val got =
        Verifier.verify(c.machine, c.words, Verifier.Declaration.of(c.inputs)).map(_.rule).toSet
      val expected = c.expect.verdict match {
        case Verdict.Legal       => Set.empty[Rule]
        case Verdict.Illegal(rs) => rs
      }
      assert(got == expected, s"${c.name}: got ${got.map(_.id)}, expected ${expected.map(_.id)}")
    }
  }

  // docs/ISA.md, "MVPの合法性規則" / "例" (a trailing HALT is added where the row omits it).
  test("the examples table of docs/ISA.md") {
    assert(rules("mul r3, r1, r2\nnop | st [0], r3\nnop\nhalt") == Set("L3"))
    assert(rules("mul r3, r1, r2\nadd r4, r3, r1\nnop\nhalt") == Set("L3", "L5"))
    assert(rules("mul r3, r1, r2\nnop\nadd r4, r3, r1\nhalt") == Set.empty)
    assert(rules("mul r3, r1, r2\nadd r5, r1, r2\nnop\nhalt") == Set("L5"))
    assert(rules("add r1, r1, r2\nhalt") == Set.empty)
    assert(rules("add r1, r2, r3 | ld r1, [0]\nnop\nhalt") == Set("L4"))
    assert(rules("mul r1, r2, r3\nnop | ld r1, [0]\nnop\nhalt") == Set("L4"))
    assert(rules("nop | st [1], r4\nhalt") == Set.empty)
    assert(rules("nop | ld r1, [0]\nhalt") == Set("L6"))
  }

  test("L2: program shape") {
    assert(rules("nop") == Set("L2"))
    assert(rules("halt\nnop\nhalt") == Set("L2"))
    assert(Verifier.verify(machine, Vector.empty, all).map(_.rule) == Vector(Rule.ProgramShape))
    val tooLong = Vector.fill(machine.instructionMemoryBundles)(0L) :+ 0x00000000f0000000L
    assert(Verifier.verify(machine, tooLong, all).map(_.rule.id).toSet == Set("L2"))
  }

  test("L3 applies to every source, including the ST source, but not to the issue cycle") {
    assert(rules("| ld r1, [0]\nnop | st [1], r1\nhalt") == Set("L3"))
    assert(rules("| ld r1, [0]\nadd r2, r3, r1\nnop\nhalt") == Set("L3"))
    assert(rules("mul r1, r2, r3 | st [0], r1\nnop\nhalt") == Set.empty)
  }

  test("L5 is per port: compute and memory commits may share a boundary") {
    assert(rules("mul r2, r1, r1 | ld r3, [0]\nnop\nhalt") == Set.empty)
  }

  test("L6 covers stores and every slot") {
    assert(rules("nop | st [0], r1\nhalt") == Set.empty) // ST completes at E_1 = h
    assert(rules("mul r2, r1, r1\nhalt") == Set("L6"))
    assert(rules("nop | st [0], r1\nnop\nhalt") == Set.empty)
  }

  test("L7 uses the machine's scratchpad capacity") {
    val v = ujson.read(machine.canonicalJson)
    v("scratchpad")("words_per_bank") = 4
    val small = MachineDescription.parse(ujson.write(v)).fold(es => fail(es.mkString), identity)
    val f =
      Verifier.verify(small, words("nop | st [8], r1\nnop | ld r2, [7]\nnop\nhalt", small), all)
    assert(f.map(_.rule.id) == Vector("L7"))
    assert(f.head.cycle.contains(0))
  }

  test("L8: undeclared inputs and loads before any store") {
    val none = Verifier.Declaration(Set.empty, Set.empty)
    assert(rules("add r1, r2, r3\nhalt", none) == Set("L8"))
    assert(findings("add r1, r2, r3\nhalt", none).size == 2)
    assert(
      rules(
        "add r1, r2, r2\nadd r3, r1, r1\nhalt",
        Verifier.Declaration(Set(2), Set.empty)
      ) == Set.empty
    )
    assert(rules("| ld r1, [5]\nnop\nhalt", none) == Set("L8"))
    assert(
      rules(
        "| st [5], r2\n| ld r1, [5]\nnop\nhalt",
        Verifier.Declaration(Set(2), Set.empty)
      ) == Set.empty
    )
  }

  test("a read during an in-flight write of an undeclared register violates both L3 and L8") {
    val none = Verifier.Declaration(Set(2), Set.empty)
    assert(rules("mul r1, r2, r2\nnop | st [0], r1\nnop\nhalt", none) == Set("L3", "L8"))
  }

  test("L1: undecodable bundles are reported and stop further analysis") {
    val good = words("mul r3, r1, r2\nadd r4, r3, r1\nhalt")
    val f = Verifier.verify(machine, good.updated(1, 0x40000000L), all)
    assert(f.map(_.rule) == Vector(Rule.Encoding))
    assert(f.head.cycle.contains(1))
  }

  test("all violations are reported, ordered by cycle") {
    // Cycle 1: r3 is read in flight (L3) and has no value yet (L8); MUL and ADD share E_2 (L5).
    // Cycle 3: the MUL issued at cycle 2 completes at E_4, after HALT (L6).
    val f = findings(
      "mul r3, r1, r2\nadd r4, r3, r1\nmul r5, r1, r1\nhalt",
      Verifier.Declaration(Set(1, 2), Set.empty)
    )
    assert(
      f.map(f => (f.rule.id, f.cycle.get)) == Vector(("L3", 1), ("L5", 1), ("L8", 1), ("L6", 3))
    )
    assert(f.map(_.cycle.get) == f.map(_.cycle.get).sorted)
  }

  private def machineFile(name: String) =
    MachineDescription
      .load(java.nio.file.Paths.get("tests", "machines", name))
      .fold(es => fail(es.mkString("\n")), identity)

  test("L9: issues of one operation are at least its II apart; each operation has its own unit") {
    val ii = machineFile("ii_units.json") // ADD, SUB, MUL II 2; LD II 3
    def on(src: String) = Verifier.verify(ii, words(src, ii), all).map(_.rule.id).toSet
    assert(on("mul r3, r1, r2\nmul r4, r1, r2\nnop\nhalt") == Set("L9"))
    assert(on("mul r3, r1, r2\nnop\nmul r4, r1, r2\nnop\nhalt") == Set.empty)
    assert(on("add r3, r1, r2\nsub r4, r1, r2\nadd r5, r1, r2\nhalt") == Set.empty)
    assert(on("| ld r3, [0]\nnop\n| ld r4, [1]\nnop\nhalt") == Set("L9"))
    assert(on("| ld r3, [0]\nnop\nnop\n| ld r4, [1]\nnop\nhalt") == Set.empty)
    // Each issue is checked against the previous one: three MULs one cycle apart give two findings.
    val f = Verifier.verify(
      ii,
      words("mul r3, r1, r2\nmul r4, r1, r2\nmul r5, r1, r2\nnop\nhalt", ii),
      all
    )
    assert(f.map(f => (f.rule.id, f.cycle.get)) == Vector(("L9", 1), ("L9", 2)))
    // With II 1 (mvp-0), back-to-back issue is legal.
    assert(rules("mul r3, r1, r2\nmul r4, r1, r2\nnop\nhalt") == Set.empty)
  }

  test("L10: no load of a word while a store to it is in flight") {
    val slow = machineFile("slow_units.json") // ST latency 2, LD latency 3
    def on(src: String) = Verifier.verify(slow, words(src, slow), all).map(_.rule.id).toSet
    assert(on("| st [2], r1\n| ld r2, [2]\nnop\nnop\nhalt") == Set("L10"))
    assert(on("| st [2], r1\n| ld r2, [0]\nnop\nnop\nhalt") == Set.empty) // same bank, other word
    assert(on("| st [2], r1\nnop\n| ld r2, [2]\nnop\nnop\nhalt") == Set.empty) // visible at s+2
    // With ST latency 1 (mvp-0), the store is visible in the next cycle.
    assert(rules("| st [2], r1\n| ld r2, [2]\nnop\nhalt") == Set.empty)
  }

  private val mvp1 = MachineDescription
    .load(java.nio.file.Paths.get("config", "machine-mvp1.json"))
    .fold(es => fail(es.mkString("\n")), identity)
  private def on(m: MachineDescription, src: String): Set[String] =
    Verifier.verify(m, words(src, m), all).map(_.rule.id).toSet

  test("the mvp-1 examples table of docs/ISA.md (read_ports 3)") {
    def r(src: String) = on(mvp1, src)
    assert(r("mul r3, r1, r2\nmac r4, r1, r2, r3\nnop\nhalt") == Set("L3"))
    assert(r("mul r3, r1, r2\nnop\nmac r4, r1, r2, r3\nnop\nhalt") == Set.empty)
    assert(r("mac r1, r2, r3, r1\nnop\nmac r1, r2, r3, r1\nnop\nhalt") == Set.empty)
    assert(r("mac r1, r2, r3, r1\nmac r1, r4, r5, r1\nnop\nhalt") == Set("L3", "L4"))
    assert(r("mac r4, r1, r2, r3\nadd r5, r1, r2\nhalt") == Set("L5"))
    assert(r("mac r4, r1, r2, r3\nmul r5, r1, r2\nnop\nhalt") == Set.empty)
    assert(r("mac r4, r1, r2, r3 | ld r4, [0]\nnop\nhalt") == Set("L4"))
    assert(r("mac r4, r1, r2, r3\nhalt") == Set("L6"))
    assert(r("mac r4, r1, r2, r3 | st [0], r5\nnop\nhalt") == Set("L11"))
    assert(r("mul r4, r1, r2 | st [0], r5\nnop\nhalt") == Set.empty)
  }

  test("L11: reads are counted per operand field, against the machine's read ports") {
    val rp4 = machineFile("mvp1_rp4.json")
    // The same register in every field still takes four reads.
    assert(on(mvp1, "mac r1, r2, r2, r2 | st [0], r2\nnop\nhalt") == Set("L11"))
    assert(on(rp4, "mac r1, r2, r2, r2 | st [0], r2\nnop\nhalt") == Set.empty)
    assert(on(rp4, "mac r4, r1, r2, r3 | st [0], r5\nnop\nhalt") == Set.empty)
    // MAC alone, MAC with LD, and every mvp-0 bundle read at most three registers.
    assert(on(mvp1, "mac r4, r1, r2, r3 | ld r5, [0]\nnop\nhalt") == Set.empty)
    assert(on(mvp1, "add r4, r1, r2 | st [0], r5\nhalt") == Set.empty)
    assert(rules("mul r4, r1, r2 | st [0], r5\nnop\nhalt") == Set.empty)
    val f = Verifier.verify(
      mvp1,
      words(
        "nop\nmac r4, r1, r2, r3 | st [0], r5\nnop\nmac r6, r1, r2, r3 | st [1], r1\nnop\nhalt",
        mvp1
      ),
      all
    )
    assert(f.map(f => (f.rule.id, f.cycle.get)) == Vector(("L11", 1), ("L11", 3)))
    assert(f.head.message.contains("reads 4 registers (r1, r2, r3, r5)"), f.head.message)
  }

  test("MAC reads rs3 in its issue cycle: L3 and L8 apply to it") {
    val none = Verifier.Declaration(Set(1, 2), Set.empty)
    assert(
      Verifier
        .verify(mvp1, words("mac r4, r1, r2, r3\nnop\nhalt", mvp1), none)
        .map(_.rule.id)
        .toSet == Set("L8")
    )
    assert(on(mvp1, "add r3, r1, r2\nmac r4, r1, r2, r3\nnop\nhalt") == Set.empty)
    // rd = rs3 reads the old value: the accumulator form is legal.
    assert(on(mvp1, "mac r3, r1, r2, r3\nnop\nhalt") == Set.empty)
  }

  test("MAC uses its own latency and II from the machine description") {
    val mac3 = machineFile("mvp1_mac3.json")
    assert(on(mac3, "mac r4, r1, r2, r3\nnop\nhalt") == Set("L6"))
    assert(on(mac3, "mac r4, r1, r2, r3\nnop\nnop\nhalt") == Set.empty)
    // MAC(s) and MUL(s+1) share E_(s+3) when MAC has latency 3; ADD(s+2) too.
    assert(on(mac3, "mac r4, r1, r2, r3\nmul r5, r1, r2\nnop\nnop\nhalt") == Set("L5"))
    assert(on(mac3, "mac r4, r1, r2, r3\nnop\nadd r5, r1, r2\nnop\nhalt") == Set("L5"))
    assert(
      on(mac3, "mac r4, r1, r2, r4\nnop\nmac r4, r1, r2, r4\nnop\nnop\nhalt") == Set("L3", "L4")
    )
    val v = ujson.read(mvp1.canonicalJson)
    v("operations")("MAC")("ii") = 2
    val ii2 = MachineDescription.parse(ujson.write(v)).fold(es => fail(es.mkString), identity)
    assert(on(ii2, "mac r4, r1, r2, r3\nmac r5, r1, r2, r3\nnop\nhalt") == Set("L9"))
    // MUL and MAC are different operations, each with its own unit.
    assert(on(ii2, "mac r4, r1, r2, r3\nmul r5, r1, r2\nnop\nhalt") == Set.empty)
  }

  test("ISA mvp-2 is refused until #110 stage 2, not verified as mvp-1") {
    val mvp2 = MachineDescription
      .load(MachineDescription.Mvp2Path)
      .fold(es => fail(es.mkString("\n")), identity)
    val e = intercept[UnsupportedOperationException] {
      Verifier.verify(mvp2, Vector(0xf0000000L), Verifier.Declaration(Set.empty, Set.empty))
    }
    assert(
      e.getMessage == "ISA mvp-2 is not implemented in the verifier yet (#110 stage 2); " +
        "machine vliw2-i32-sp2-mac-w2"
    )
  }
}
