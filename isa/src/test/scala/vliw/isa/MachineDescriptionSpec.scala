package vliw.isa

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import org.scalatest.funsuite.AnyFunSuite

class MachineDescriptionSpec extends AnyFunSuite {
  private val text =
    new String(Files.readAllBytes(MachineDescription.DefaultPath), StandardCharsets.UTF_8)
  private def baseValue: ujson.Value = ujson.read(text)

  private def loadOk(t: String): MachineDescription =
    MachineDescription.parse(t).fold(es => fail(es.mkString("\n")), identity)

  /** Applies `edit` to a copy of the repository machine.json and returns the errors. */
  private def errorsAfter(edit: ujson.Value => Unit): List[String] = {
    val v = baseValue
    edit(v)
    MachineDescription.parse(ujson.write(v)) match {
      case Left(errors) => errors
      case Right(_)     => fail("expected the edited machine description to be rejected")
    }
  }

  private def assertRejected(expected: String)(edit: ujson.Value => Unit): Unit = {
    val errors = errorsAfter(edit)
    assert(errors.exists(_.contains(expected)), errors.mkString("\n"))
  }

  test("config/machine.json is a valid mvp-0 machine description") {
    val m = loadOk(text)
    assert(m.schemaVersion == 1)
    assert(m.machineId == "vliw2-i32-sp2")
    assert(m.registerCount == 16)
    assert((m.banks, m.wordsPerBank, m.scratchpadWords) == ((2, 256, 512)))
    assert(m.instructionMemoryBundles == 256)
    assert(m.nopOpcode == 0 && m.haltOpcode == 15)
    val latencies = m.operations.view.mapValues(op => (op.opcode, op.latency, op.ii)).toMap
    assert(
      latencies == Map(
        "ADD" -> ((1, 1, 1)),
        "SUB" -> ((2, 1, 1)),
        "MUL" -> ((3, 2, 1)),
        "LD" -> ((1, 2, 1)),
        "ST" -> ((2, 1, 1))
      )
    )
    assert(m.operation("ST").writeback.isEmpty)
    assert(m.operation("LD").writeback.contains(Slot.Memory))
  }

  test("hash depends only on content, not on key order or whitespace") {
    val m = loadOk(text)
    val reordered = ujson.Obj.from(baseValue.obj.toSeq.reverse)
    val m2 = loadOk(ujson.write(reordered, indent = 7))
    assert(m.hash == m2.hash)
    assert(m.hash.matches("sha256:[0-9a-f]{64}"))
    assert(m.canonicalJson == Json.canonical(baseValue).toOption.get)
  }

  test("hash changes when any value changes") {
    val v = baseValue
    v("revision") = v("revision").num + 1
    assert(loadOk(ujson.write(v)).hash != loadOk(text).hash)
  }

  test("unknown and missing keys are rejected") {
    assertRejected("""unknown key "extra"""")(_("extra") = 1)
    assertRejected("""$.operations.MUL: unknown key "lanes"""")(_("operations")("MUL")("lanes") = 2)
    assertRejected("""$.operations: missing key "SUB"""")(_("operations").obj.remove("SUB"))
    assertRejected("""unknown key "DIV"""")(v => v("operations")("DIV") = v("operations")("ADD"))
  }

  test("values out of range or of the wrong type are rejected") {
    assertRejected("$.operations.MUL.latency: must be in 1..8, got 0")(
      _("operations")("MUL")("latency") = 0
    )
    assertRejected("$.operations.LD.ii: must be an integer, got string")(
      _("operations")("LD")("ii") = "1"
    )
    // Non-integer numbers never reach the field decoder: they have no canonical form.
    assertRejected("non-integer number: 1.5")(_("operations")("ADD")("latency") = 1.5)
    assertRejected("$.register_file.count: must be in 1..16")(_("register_file")("count") = 32)
    assertRejected("$.schema_version")(_("schema_version") = 0)
    assertRejected("$.isa_version")(_("isa_version") = "mvp-1")
    assertRejected("$.machine_id: must match")(_("machine_id") = "Bad ID")
  }

  test("operations must keep their mvp-0 slot and write-back port") {
    assertRejected("""$.operations.LD.slot: must be "memory"""")(_("operations")("LD")("slot") =
      "compute"
    )
    assertRejected("$.operations.ST.writeback: must be null")(
      _("operations")("ST")("writeback") = "memory"
    )
  }

  test("opcodes must be unique within a slot and differ from NOP") {
    assertRejected("opcode 1 is used more than once in the compute slot: ADD, SUB")(
      _("operations")("SUB")("opcode") = 1
    )
    assertRejected("$.operations.ADD.opcode: must be in 1..15, got 0")(
      _("operations")("ADD")("opcode") = 0
    )
    // The same opcode in different slots is fine (ADD and LD are both 1 in mvp-0).
    assert(loadOk(text).operation("ADD").opcode == loadOk(text).operation("LD").opcode)
  }

  test("scratchpad must fit the 9-bit address and match the bank mapping") {
    assertRejected("banks * words_per_bank must be <= 512")(_("scratchpad")("words_per_bank") = 512)
    assertRejected("""$.scratchpad.bank_mapping: must be "word_address_mod_4"""")(
      _("scratchpad")("banks") = 4
    )
    val v = baseValue
    v("scratchpad")("words_per_bank") = 64
    assert(loadOk(ujson.write(v)).scratchpadWords == 128)
  }

  test("documents that are not machine descriptions are rejected") {
    assert(MachineDescription.parse("[]").left.toOption.get.head.contains("must be an object"))
    assert(MachineDescription.parse("""{"a": 1, "a": 1}""").isLeft)
    assert(MachineDescription.parse("{").isLeft)
  }
}
