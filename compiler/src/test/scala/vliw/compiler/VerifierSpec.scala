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

  // ISA mvp-2 (#110 stage 2, docs/ISA.md "mvp-2: W").

  private val mvp2 = MachineDescription
    .load(MachineDescription.Mvp2Path)
    .fold(es => fail(es.mkString("\n")), identity)

  /** Declares r0..r15 and every word of a 512-word scratchpad. */
  private val every = Verifier.Declaration((0 until 16).toSet, (0 until 512).toSet)
  private def findingsOn(
      m: MachineDescription,
      src: String,
      declared: Verifier.Declaration = every
  ) =
    Verifier.verify(m, words(src, m), declared)
  private def onW(m: MachineDescription, src: String): Set[String] =
    findingsOn(m, src).map(_.rule.id).toSet

  test("the mvp-2 examples table of docs/ISA.md (read_ports 3, L11 form F)") {
    def r(src: String) = onW(mvp2, src)
    assert(r("vmul r4:r5, r1, r2:r3\nadd r6, r1, r2\nhalt") == Set("L5"))
    assert(r("vmul r4:r5, r1, r2:r3\nvmul r6:r7, r1, r2:r3\nnop\nhalt") == Set.empty)
    assert(r("mul r1, r2, r3\nadd r4, r5, r6\nhalt") == Set("L5"))
    assert(r("vmul r4:r5, r1, r2:r3\nmul r8, r5, r1\nnop\nhalt") == Set("L3"))
    assert(
      r("vmul r4:r5, r1, r2:r3\nnop\nadd r5, r1, r2\nvadd r6:r7, r4:r5, r8:r9\nhalt") == Set.empty
    )
    assert(r("vmac r4:r5, r2, r6:r7, r8:r9 | ld r5, [0]\nnop\nhalt") == Set("L4"))
    assert(r("vmac r4:r5, r2, r6:r7, r8:r9 | st [0], r1\nnop\nhalt") == Set("L11"))
    assert(
      onW(
        machineFile("mvp2_rp4.json"),
        "vmac r4:r5, r2, r6:r7, r8:r9 | st [0], r1\nnop\nhalt"
      ).isEmpty
    )
    assert(r("mac r10, r0, r2, r4 | st [0], r3\nnop\nhalt") == Set("L11"))
    assert(r("vmul r4:r5, r1, r2:r3 | st.w [0], r6:r7\nnop\nhalt") == Set.empty)
    assert(r("nop | ld.w r2:r3, [510]\nnop\nhalt") == Set.empty)
    // Word 512 is outside the scratchpad: no input can name it, and no store here writes it (L8).
    assert(r("nop | ld.w r2:r3, [511]\nnop\nhalt") == Set("L7", "L8"))
    val m8 = Verifier.Declaration((0 until 16).toSet, Set(8))
    assert(findingsOn(mvp2, "nop | ld.w r2:r3, [8]\nnop\nhalt", m8).map(_.rule.id) == Vector("L8"))
    assert(r("vmac r4:r5, r2, r6:r7, r8:r9\nhalt") == Set("L6"))
  }

  test("mvp-2 L5 counts operations: a pair commits through one use of its port") {
    // One vector operation and one LD.W at the same boundary, each with two registers.
    assert(onW(mvp2, "vmul r4:r5, r1, r2:r3 | ld.w r6:r7, [0]\nnop\nhalt").isEmpty)
    // Two operations at E_2 on the compute port; the message names each operation once.
    val f = findingsOn(mvp2, "vmul r4:r5, r1, r2:r3\nvadd r6:r7, r2:r3, r2:r3\nhalt")
    assert(f.map(f => (f.rule.id, f.cycle.get)) == Vector(("L5", 1)))
    assert(
      f.head.message == "the compute write-back port is used 2 times at E_2: " +
        "`vmul r4:r5, r1, r2:r3` (cycle 0), `vadd r6:r7, r2:r3, r2:r3` (cycle 1)",
      f.head.message
    )
    // LD.W at latency 3 and LD one cycle later share E_3 on the memory port.
    val vlat = machineFile("mvp2_vlat.json")
    assert(onW(vlat, "nop | ld.w r2:r3, [0]\nnop | ld r4, [2]\nnop\nhalt") == Set("L5"))
    assert(onW(vlat, "vmul r4:r5, r1, r2:r3\nmul r6, r1, r2\nnop\nhalt") == Set("L5"))
    assert(onW(vlat, "vadd r4:r5, r2:r3, r2:r3\nadd r6, r1, r2\nhalt") == Set("L5"))
  }

  test("mvp-2 L3, L4 and L8 are per register: both registers of a pair, one of a broadcast") {
    val none = Verifier.Declaration(Set(1, 6, 7), Set.empty)
    // A broadcast rs1 reads r1 only, so r2 needs no value.
    assert(findingsOn(mvp2, "vmul r4:r5, r1, r6:r7\nnop\nhalt", none).isEmpty)
    // A pair field reads r6 and r7, and r7 has no value.
    val f = findingsOn(
      mvp2,
      "vmul r4:r5, r1, r6:r7\nnop\nhalt",
      Verifier.Declaration(Set(1, 6), Set.empty)
    )
    assert(f.map(_.message) == Vector("`vmul r4:r5, r1, r6:r7` reads r7, which has no value yet"))
    // The second register of a pair is being written: by a vector operation, and by LD.W.
    assert(
      onW(mvp2, "vadd r4:r5, r2:r3, r2:r3 | ld.w r6:r7, [0]\n| st.w [2], r6:r7\nnop\nhalt") == Set(
        "L3"
      )
    )
    assert(onW(mvp2, "vmul r4:r5, r1, r2:r3\nnop | st [0], r5\nnop\nhalt") == Set("L3"))
    // A pair write overlaps a scalar write to either register, in flight or in the same bundle.
    assert(onW(mvp2, "vmul r4:r5, r1, r2:r3\nnop | ld r5, [0]\nnop\nhalt") == Set("L4"))
    assert(onW(mvp2, "add r5, r1, r2 | ld.w r4:r5, [0]\nnop\nhalt") == Set("L4"))
    // The accumulator form reads the old pair; a broadcast rs1 may be r(rd + 1).
    assert(onW(mvp2, "vmac r4:r5, r5, r6:r7, r4:r5\nnop\nhalt").isEmpty)
  }

  test("mvp-2 L7, L8 and L10 are per word for LD.W and ST.W, whatever bank and row a word is in") {
    val vii = machineFile("mvp2_vii.json") // ST and ST.W latency 2; the new operations II 2
    def onVii(src: String, declared: Verifier.Declaration = every) =
      Verifier.verify(vii, words(src, vii), declared)
    // ld.w [17]: word 18 is in bank 0, the row after word 17's; a store to it is in flight.
    val f = onVii("| st [18], r1\n| ld.w r2:r3, [17]\nnop\nhalt")
    assert(f.map(f => (f.rule.id, f.cycle.get)) == Vector(("L10", 1)))
    assert(
      f.head.message == "ld.w [17] (word 18) reads the word while `st [18]` (cycle 0) writes it; " +
        "visible from cycle 2"
    )
    // Word 16 is in the same bank as word 18 but is not read.
    assert(onVii("| st [16], r1\n| ld.w r2:r3, [17]\nnop\nhalt").isEmpty)
    // The second word of ST.W, read while it is being written.
    val g = onVii("| st.w [4], r2:r3\n| ld r6, [5]\nnop\nhalt")
    assert(
      g.map(_.message) == Vector(
        "ld [5] reads the word while `st.w [4] (word 5)` (cycle 0) writes it; visible from cycle 2"
      )
    )
    // L8: word 9 has no value, and words written by ST.W are visible from s + L.
    val m8 = Verifier.Declaration((0 until 16).toSet, Set(8))
    assert(
      findingsOn(mvp2, "nop | ld.w r2:r3, [8]\nnop\nhalt", m8).map(_.message) ==
        Vector("ld.w [8] (word 9) reads a word that has no value yet")
    )
    val noMemory = Verifier.Declaration((0 until 16).toSet, Set.empty)
    assert(
      findingsOn(mvp2, "nop | st.w [8], r2:r3\nnop | ld.w r4:r5, [8]\nnop\nhalt", noMemory).isEmpty
    )
    assert(
      onVii("nop | st.w [8], r2:r3\nnop | ld r4, [9]\nnop\nhalt", noMemory).map(_.rule.id).toSet ==
        Set("L8", "L10")
    )
    // L7: ST.W [511] writes word 512.
    val l7 = findingsOn(mvp2, "nop | st.w [511], r2:r3\nhalt")
    assert(
      l7.map(_.message) == Vector("`st.w [511]`: address 512 is outside the scratchpad (0..511)")
    )
    // L8 does not tell stores by range: an earlier ST.W [511] (itself L7) gives word 512 a value,
    // so the later LD.W [511] is L7 and not L8 (docs/VERIFIER.md, L8).
    assert(
      findingsOn(mvp2, "nop | st.w [511], r2:r3\nnop | ld.w r4:r5, [511]\nnop\nhalt")
        .map(f => (f.rule.id, f.cycle)) == Vector(("L7", Some(0)), ("L7", Some(1)))
    )
  }

  test("mvp-2 L6 reports an operation once, though it writes two registers or two words") {
    val f = findingsOn(mvp2, "vmac r4:r5, r2, r6:r7, r8:r9 | ld.w r10:r11, [0]\nhalt")
    assert(
      f.map(_.message) == Vector(
        "`vmac r4:r5, r2, r6:r7, r8:r9` (cycle 0) completes at E_2, after HALT at cycle 1",
        "`ld.w r10:r11, [0]` (cycle 0) completes at E_2, after HALT at cycle 1"
      )
    )
    val vii = machineFile("mvp2_vii.json")
    assert(
      findingsOn(vii, "nop | st.w [0], r2:r3\nhalt").map(_.message) ==
        Vector("`st.w [0]` (cycle 0) completes at E_2, after HALT at cycle 1")
    )
  }

  test("mvp-2 L9: each new operation has its own unit and II") {
    val vii = machineFile("mvp2_vii.json")
    def onVii(src: String) = onW(vii, src)
    assert(onVii("vmul r4:r5, r1, r2:r3\nvmul r6:r7, r1, r2:r3\nnop\nhalt") == Set("L9"))
    assert(onVii("vadd r4:r5, r2:r3, r2:r3\nvsub r6:r7, r2:r3, r2:r3\nhalt").isEmpty)
    assert(onVii("vmul r4:r5, r1, r2:r3\nmul r6, r1, r2\nnop\nhalt").isEmpty)
    assert(onVii("| ld.w r2:r3, [0]\n| ld.w r4:r5, [2]\nnop\nhalt") == Set("L9"))
    assert(onVii("| st.w [0], r2:r3\n| st.w [2], r4:r5\nnop\nhalt") == Set("L9"))
    assert(onVii("| ld.w r2:r3, [0]\n| ld r4, [2]\nnop\nhalt").isEmpty)
  }

  test("mvp-2 L11 counts operand fields: a pair or a broadcast is one field") {
    // VMAC with pairs reads six registers in three fields, with LD.W it is still three.
    assert(onW(mvp2, "vmac r4:r5, r2:r3, r6:r7, r8:r9 | ld.w r10:r11, [0]\nnop\nhalt").isEmpty)
    val f = findingsOn(mvp2, "vmac r4:r5, r2, r6:r7, r8:r9 | st.w [0], r10:r11\nnop\nhalt")
    assert(
      f.map(_.message) == Vector(
        "the bundle reads 4 operand fields (r2, r6:r7, r8:r9, r10:r11); the register file has 3 read ports"
      )
    )
    // Form F counts fields whatever RF bank they read: r4 (bank 0) and r3 (bank 1).
    assert(
      findingsOn(mvp2, "mac r10, r0, r2, r4 | st [0], r3\nnop\nhalt").map(_.message) == Vector(
        "the bundle reads 4 registers (r0, r2, r4, r3); the register file has 3 read ports"
      )
    )
  }

  test("mvp-2 L1: odd pairs, the broadcast bit outside VMUL and VMAC, LD.W on W-reg") {
    val halt = 0x00000000f0000000L
    def l1(m: MachineDescription, word: Long) =
      Verifier.verify(m, Vector(word, halt), every).map(f => (f.rule.id, f.cycle))
    Seq(0x0000000085268800L, 0x0000000074360000L, 0x0000000054260800L, 0x3b08800000000000L)
      .foreach(w => assert(l1(mvp2, w) == Vector(("L1", Some(0))), f"$w%016x"))
    assert(l1(machineFile("mvp2_mw1.json"), 0x3a08800000000000L) == Vector(("L1", Some(0))))
    assert(l1(mvp1, 0x0000000084268800L) == Vector(("L1", Some(0))))
    // The same words with even pairs and B on VMAC decode (vmac r4:r5, r2, r6:r7, r8:r9).
    assert(Verifier.verify(mvp2, Vector(0x0000000084268800L, 0L, halt), every).isEmpty)
  }
}
