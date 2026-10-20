package vliw.isa

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.security.MessageDigest

import scala.util.Random

import org.scalatest.funsuite.AnyFunSuite

/** ISA mvp-2 (W): the schema 3 machine descriptions, the encoding of the W operations and their
  * assembly syntax (docs/ISA.md "mvp-2: W", docs/MACHINE.md "mvp-2（schema_version 3）", ADR-0022).
  * Expected words are computed by hand from the bit layout of docs/ISA.md.
  */
class Mvp2Spec extends AnyFunSuite {
  private def bytes(path: String): Array[Byte] = Files.readAllBytes(Paths.get(path))
  private def read(path: String): String = new String(bytes(path), StandardCharsets.UTF_8)
  private def loadOk(t: String): MachineDescription =
    MachineDescription.parse(t).fold(es => fail(es.mkString("\n")), identity)
  private def edited(base: String)(edit: ujson.Value => Unit): MachineDescription = {
    val v = ujson.read(base)
    edit(v)
    loadOk(ujson.write(v))
  }

  private val text = read("config/machine-mvp2.json")
  private val machine = loadOk(text) // W-full
  private val mvp1Text = read("config/machine-mvp1.json")
  private val mvp1 = loadOk(mvp1Text)
  private val mvp0Text = read("config/machine.json")
  private val mvp0 = loadOk(mvp0Text)
  private val wregText = read("tests/machines/mvp2_mw1.json")
  private val wreg = loadOk(wregText)
  private val codec = new BundleCodec(machine)
  private val wregCodec = new BundleCodec(wreg)
  private val mvp1Codec = new BundleCodec(mvp1)
  private val mvp0Codec = new BundleCodec(mvp0)

  private def errorsAfter(base: String)(edit: ujson.Value => Unit): List[String] = {
    val v = ujson.read(base)
    edit(v)
    MachineDescription.parse(ujson.write(v)).left.getOrElse(fail("expected a rejection"))
  }
  private def assertRejected(base: String, expected: String)(edit: ujson.Value => Unit): Unit = {
    val errors = errorsAfter(base)(edit)
    assert(errors.exists(_.contains(expected)), errors.mkString("\n"))
  }
  private def enc(b: Bundle, c: BundleCodec = codec): Long =
    c.encode(b).fold(e => fail(e), identity)
  private def encErr(b: Bundle, c: BundleCodec = codec): String =
    c.encode(b).left.getOrElse(fail(s"$b should not encode"))
  private def decErr(w: Long, c: BundleCodec = codec): String =
    c.decode(w).left.getOrElse(fail(f"0x$w%016x should not decode"))

  private val W = Set("VADD", "VSUB", "VMUL", "VMAC", "LD.W", "ST.W")

  // Machine descriptions (docs/MACHINE.md "mvp-2（schema_version 3）").

  test("config/machine-mvp2.json is mvp-1 plus the W operations (W-full, proposed)") {
    assert((machine.schemaVersion, machine.isaVersion) == ((3, IsaVersion.Mvp2)))
    assert(
      (machine.machineId, machine.revision, machine.status) == ((
        "vliw2-i32-sp2-mac-w2",
        1,
        "proposed"
      ))
    )
    assert(machine.hasMac && machine.hasVector && machine.hasWideMemory)
    assert(machine.maxWordsPerAccess == 2 && machine.readPorts == 3)
    def spec(m: String, slot: Slot, opcode: Int, latency: Int, wb: Option[Slot]) =
      m -> OperationSpec(m, slot, opcode, latency, 1, wb)
    assert(
      machine.operations.filter(op => W(op._1)) == Map(
        spec("VADD", Slot.Compute, 5, 1, Some(Slot.Compute)),
        spec("VSUB", Slot.Compute, 6, 1, Some(Slot.Compute)),
        spec("VMUL", Slot.Compute, 7, 2, Some(Slot.Compute)),
        spec("VMAC", Slot.Compute, 8, 2, Some(Slot.Compute)),
        spec("LD.W", Slot.Memory, 3, 2, Some(Slot.Memory)),
        spec("ST.W", Slot.Memory, 4, 1, None)
      )
    )
    // Every other key is that of config/machine-mvp1.json.
    assert(machine.operations -- W == mvp1.operations)
    assert(
      machine.copy(
        schemaVersion = 2,
        isaVersion = IsaVersion.Mvp1,
        status = "frozen",
        machineId = "vliw2-i32-sp2-mac",
        maxWordsPerAccess = 1,
        operations = machine.operations -- W,
        canonicalJson = "",
        hash = ""
      ) == mvp1.copy(canonicalJson = "", hash = "")
    )
    assert(MachineDescription.load(MachineDescription.Mvp2Path).map(_.hash) == Right(machine.hash))
  }

  test("the frozen mvp-0 and mvp-1 machine descriptions are unchanged, byte for byte") {
    def sha256(path: String) =
      MessageDigest.getInstance("SHA-256").digest(bytes(path)).map(b => f"$b%02x").mkString
    assert(
      sha256(
        "config/machine.json"
      ) == "70624941ca2ac0932a49e874619e4c58f7360185f45efaf2eace272bf872b6fd"
    )
    assert(
      sha256("config/machine-mvp1.json") ==
        "8e1a5671e53f94651c4b183870b63f68ea0db105530b746ec8a955e8a4b80c67"
    )
    assert(mvp0.hash == "sha256:a4af6064e049415dc9822614264a01f06b6e1be39c86bc41c5fcdda3e7d89e21")
    assert(mvp1.hash == "sha256:90f6a9667a8b9fa7be448117438c04ff40a1af6495f13c0b8d8d1ccd95f89162")
    // config/machine-mvp1.json reads as before #110; the new field is 1 on schema 1 and 2.
    def spec(m: String, slot: Slot, opcode: Int, latency: Int, wb: Option[Slot]) =
      m -> OperationSpec(m, slot, opcode, latency, 1, wb)
    assert(
      mvp1.copy(canonicalJson = "", hash = "") == MachineDescription(
        schemaVersion = 2,
        isaVersion = IsaVersion.Mvp1,
        revision = 1,
        status = "frozen",
        machineId = "vliw2-i32-sp2-mac",
        registerCount = 16,
        readPorts = 3,
        banks = 2,
        wordsPerBank = 256,
        maxWordsPerAccess = 1,
        operations = Map(
          spec("ADD", Slot.Compute, 1, 1, Some(Slot.Compute)),
          spec("SUB", Slot.Compute, 2, 1, Some(Slot.Compute)),
          spec("MUL", Slot.Compute, 3, 2, Some(Slot.Compute)),
          spec("MAC", Slot.Compute, 4, 2, Some(Slot.Compute)),
          spec("LD", Slot.Memory, 1, 2, Some(Slot.Memory)),
          spec("ST", Slot.Memory, 2, 1, None)
        ),
        haltOpcode = 15,
        nopOpcode = 0,
        instructionMemoryBundles = 256,
        canonicalJson = "",
        hash = ""
      )
    )
    assert(mvp0.maxWordsPerAccess == 1 && !mvp0.hasVector && !mvp0.hasWideMemory)
    assert(!mvp1.hasVector && !mvp1.hasWideMemory)
  }

  test("the mvp-2 variants differ from W-full only in the documented keys") {
    val full = ujson.read(text)
    // Each variant, with its documented keys set back to the W-full values, is W-full.
    def check(file: String, id: String)(undo: ujson.Value => Unit): MachineDescription = {
      val t = read(s"tests/machines/$file")
      val m = loadOk(t)
      assert((m.machineId, m.status, m.schemaVersion) == ((id, "proposed", 3)), file)
      val v = ujson.read(t)
      v("machine_id") = full("machine_id")
      v("status") = full("status")
      undo(v)
      assert(v == full, file)
      m
    }
    val mw1 = check("mvp2_mw1.json", "vliw2-i32-sp2-mac-w2-mw1") { v =>
      assert(v("scratchpad")("max_words_per_access") == ujson.Num(1))
      v("scratchpad")("max_words_per_access") = 2
      Seq("LD.W", "ST.W").foreach { op =>
        assert(!v("operations").obj.contains(op))
        v("operations")(op) = full("operations")(op)
      }
    }
    assert(mw1.hasVector && !mw1.hasWideMemory && mw1.maxWordsPerAccess == 1)
    val rp4 = check("mvp2_rp4.json", "vliw2-i32-sp2-mac-w2-rp4") { v =>
      v("register_file")("read_ports") = 3
    }
    assert(rp4.readPorts == 4 && rp4.hasWideMemory)
    val vlat = check("mvp2_vlat.json", "vliw2-i32-sp2-mac-w2-vlat") { v =>
      Seq("VADD", "VSUB", "VMUL", "VMAC", "LD.W").foreach { op =>
        v("operations")(op)("latency") = full("operations")(op)("latency")
      }
    }
    assert(
      Seq("VADD", "VSUB", "VMUL", "VMAC", "LD.W").map(vlat.operation(_).latency) ==
        Seq(2, 2, 3, 3, 3)
    )
    assert((vlat.operations -- W) == (machine.operations -- W), "scalar operations unchanged")
    // ST.W must keep ST's latency, so mvp2_vii raises both to 2 (per-word L10 for ST and ST.W).
    val vii = check("mvp2_vii.json", "vliw2-i32-sp2-mac-w2-vii") { v =>
      W.foreach(op => v("operations")(op)("ii") = 1)
      Seq("ST", "ST.W").foreach(op => v("operations")(op)("latency") = 1)
    }
    assert(W.toSeq.map(vii.operation(_).ii).toSet == Set(2))
    assert(Seq("ST", "ST.W").map(vii.operation(_).latency) == Seq(2, 2))
    val rest = W + "ST"
    assert(
      (vii.operations -- rest) == (machine.operations -- rest),
      "other scalar operations unchanged"
    )
  }

  test("schema 3 is for mvp-2 only") {
    assertRejected(text, "$.isa_version: schema_version 3 is for mvp-2, got mvp-1")(
      _("isa_version") = "mvp-1"
    )
    assertRejected(mvp1Text, "$.isa_version: schema_version 2 is for mvp-1, got mvp-2")(
      _("isa_version") = "mvp-2"
    )
    assertRejected(mvp0Text, "$.isa_version: schema_version 3 is for mvp-2, got mvp-0")(
      _("schema_version") = 3
    )
    assertRejected(text, "$.schema_version: must be in 1..3, got 4")(_("schema_version") = 4)
    assertRejected(text, "$.isa_version: must be one of mvp-0, mvp-1, mvp-2, got mvp-3")(
      _("isa_version") = "mvp-3"
    )
    assert(IsaVersion.all == Seq("mvp-0", "mvp-1", "mvp-2"))
    assert((1 to 3).map(IsaVersion.ofSchema) == IsaVersion.all)
    assertThrows[IllegalArgumentException](IsaVersion.ofSchema(4))
  }

  test("the register file of mvp-2: an even count from 2, two RF banks by register index") {
    assertRejected(text, "$.register_file.count: must be even in ISA mvp-2")(
      _("register_file")("count") = 15
    )
    assertRejected(text, "$.register_file.count: must be in 2..16, got 1")(
      _("register_file")("count") = 1
    )
    assertRejected(text, "$.register_file.count: must be in 2..16, got 18")(
      _("register_file")("count") = 18
    )
    assert(edited(text)(_("register_file")("count") = 2).registerCount == 2)
    assertRejected(text, "$.register_file.banks: must be 2, got 1")(_("register_file")("banks") = 1)
    assertRejected(text, """$.register_file.bank_mapping: must be "register_index_mod_2"""")(
      _("register_file")("bank_mapping") = "register_index_mod_4"
    )
    assertRejected(text, """$.register_file: missing key "banks"""")(
      _("register_file").obj.remove("banks")
    )
    assertRejected(text, """$.register_file: missing key "bank_mapping"""")(
      _("register_file").obj.remove("bank_mapping")
    )
    assertRejected(text, "$.register_file.read_ports: must be in 3..4, got 2")(
      _("register_file")("read_ports") = 2
    )
    assertRejected(text, "$.register_file.read_ports: must be in 3..4, got 5")(
      _("register_file")("read_ports") = 5
    )
  }

  test("max_words_per_access decides LD.W and ST.W, and 2 needs two scratchpad banks") {
    assertRejected(text, "$.scratchpad.max_words_per_access: must be in 1..2, got 0")(
      _("scratchpad")("max_words_per_access") = 0
    )
    assertRejected(text, "$.scratchpad.max_words_per_access: must be in 1..2, got 3")(
      _("scratchpad")("max_words_per_access") = 3
    )
    // A missing key is reported once; the operations are then read as those without LD.W/ST.W.
    val missing = errorsAfter(text)(_("scratchpad").obj.remove("max_words_per_access"))
    assert(missing.count(_.contains("max_words_per_access")) == 1, missing)
    assert(missing.contains("""$.scratchpad: missing key "max_words_per_access""""), missing)
    assert(missing.contains("""$.operations: unknown key "LD.W""""), missing)
    def oneBank(v: ujson.Value): Unit = {
      v("scratchpad")("banks") = 1
      v("scratchpad")("bank_mapping") = "word_address_mod_1"
      v("scratchpad")("row_mapping") = "word_address_div_1"
    }
    assertRejected(text, "$.scratchpad.max_words_per_access: 2 needs at least 2 banks")(oneBank)
    assert(edited(wregText)(oneBank).banks == 1, "W-reg needs no second bank")
    assertRejected(wregText, """$.operations: unknown key "LD.W"""")(
      _("operations")("LD.W") = ujson.read(text)("operations")("LD.W")
    )
    assertRejected(text, """$.operations: missing key "ST.W"""")(_("operations").obj.remove("ST.W"))
    assertRejected(text, """$.operations: missing key "VADD"""")(_("operations").obj.remove("VADD"))
    assertRejected(wregText, """$.operations: missing key "VMAC"""")(
      _("operations").obj.remove("VMAC")
    )
  }

  test("the W operations follow the rules of the other operations") {
    assertRejected(text, "opcode 1 is used more than once in the memory slot: LD, LD.W")(
      _("operations")("LD.W")("opcode") = 1
    )
    assertRejected(text, "opcode 15 is used more than once in the compute slot: HALT, VADD")(
      _("operations")("VADD")("opcode") = 15
    )
    assertRejected(text, "opcode 4 is used more than once in the compute slot: MAC, VMAC")(
      _("operations")("VMAC")("opcode") = 4
    )
    assertRejected(text, "$.operations.VMUL.opcode: must be in 1..15, got 0")(
      _("operations")("VMUL")("opcode") = 0
    )
    assertRejected(text, """$.operations.VSUB.slot: must be "compute"""")(
      _("operations")("VSUB")("slot") = "memory"
    )
    assertRejected(text, "$.operations.ST.W.writeback: must be null")(
      _("operations")("ST.W")("writeback") = "memory"
    )
    assertRejected(text, """$.operations.LD.W.writeback: must be "memory"""")(
      _("operations")("LD.W")("writeback") = "compute"
    )
    assertRejected(text, "$.operations.LD.W.latency: must be in 1..8, got 9")(
      _("operations")("LD.W")("latency") = 9
    )
    assertRejected(text, "$.operations.VMAC.ii: must be in 1..8, got 0")(
      _("operations")("VMAC")("ii") = 0
    )
    // ST.W takes ST's latency, so no rule is needed to order two stores to one word (ADR-0022).
    val sameLatency = "$.operations.ST.W.latency: must equal operations.ST.latency"
    assertRejected(text, s"$sameLatency (1), got 2")(_("operations")("ST.W")("latency") = 2)
    assertRejected(text, s"$sameLatency (3), got 1")(_("operations")("ST")("latency") = 3)
    val slowStores = edited(text) { v =>
      Seq("ST", "ST.W").foreach(op => v("operations")(op)("latency") = 3)
    }
    assert(slowStores.operation("ST.W").latency == 3)
    // The same opcode in different slots is fine, as for ADD and LD.
    assert(machine.operation("ADD").opcode == machine.operation("LD").opcode)
  }

  test("the keys of mvp-2 are unknown in schema 1 and 2") {
    Seq(mvp0Text, mvp1Text).foreach { t =>
      assertRejected(t, """$.register_file: unknown key "banks"""")(
        _("register_file")("banks") = 2
      )
      assertRejected(t, """$.register_file: unknown key "bank_mapping"""")(
        _("register_file")("bank_mapping") = "register_index_mod_2"
      )
      assertRejected(t, """$.scratchpad: unknown key "max_words_per_access"""")(
        _("scratchpad")("max_words_per_access") = 1
      )
      W.foreach { op =>
        assertRejected(t, s"""$$.operations: unknown key "$op"""")(
          _("operations")(op) = ujson.read(text)("operations")(op)
        )
      }
    }
  }

  // Encoding (docs/ISA.md "mvp-2: W", エンコード).

  /** The hand-computed examples of docs/ISA.md "mvp-2: W": bundle, canonical text, word. */
  private val examples: Seq[(Bundle, String, Long)] = Seq(
    (Bundle(ComputeOp.Mac(5, 1, 2, 3)), "mac r5, r1, r2, r3 | nop", 0x0000000045123000L),
    (
      Bundle(ComputeOp.VMac(4, 2, 6, 8, broadcast = true)),
      "vmac r4:r5, r2, r6:r7, r8:r9 | nop",
      0x0000000084268800L
    ),
    (
      Bundle(ComputeOp.VMac(4, 2, 6, 8, broadcast = false)),
      "vmac r4:r5, r2:r3, r6:r7, r8:r9 | nop",
      0x0000000084268000L
    ),
    (
      Bundle(ComputeOp.VMul(4, 1, 6, broadcast = true)),
      "vmul r4:r5, r1, r6:r7 | nop",
      0x0000000074160800L
    ),
    (
      Bundle(ComputeOp.VMul(4, 2, 6, broadcast = false)),
      "vmul r4:r5, r2:r3, r6:r7 | nop",
      0x0000000074260000L
    ),
    (Bundle(ComputeOp.VAdd(4, 2, 6)), "vadd r4:r5, r2:r3, r6:r7 | nop", 0x0000000054260000L),
    (Bundle(ComputeOp.VSub(4, 2, 6)), "vsub r4:r5, r2:r3, r6:r7 | nop", 0x0000000064260000L),
    (Bundle(MemoryOp.LdW(10, 17)), "nop | ld.w r10:r11, [17]", 0x3a08800000000000L),
    (Bundle(MemoryOp.LdW(10, 510)), "nop | ld.w r10:r11, [510]", 0x3aff000000000000L),
    (Bundle(MemoryOp.LdW(10, 511)), "nop | ld.w r10:r11, [511]", 0x3aff800000000000L),
    (Bundle(MemoryOp.StW(17, 12)), "nop | st.w [17], r12:r13", 0x4c08800000000000L),
    (
      Bundle(ComputeOp.VMac(4, 2, 6, 8, broadcast = true), MemoryOp.St(0, 1)),
      "vmac r4:r5, r2, r6:r7, r8:r9 | st [0], r1",
      0x2100000084268800L
    ),
    (
      Bundle(ComputeOp.VMac(4, 2, 6, 8, broadcast = true), MemoryOp.LdW(10, 17)),
      "vmac r4:r5, r2, r6:r7, r8:r9 | ld.w r10:r11, [17]",
      0x3a08800084268800L
    )
  )

  test("the W examples encode to the hand-computed words and decode back") {
    examples.foreach { case (bundle, _, word) =>
      assert(enc(bundle) == word, f"$bundle: got 0x${enc(bundle)}%016x, expected 0x$word%016x")
      assert(codec.decode(word) == Right(bundle))
    }
    // `ld.w [511]` encodes; that it reads word 512 is L7, which the verifier checks.
    assert(machine.scratchpadWords == 512)
  }

  test("field placement: each W operand lands in its documented bit range") {
    assert(enc(Bundle(ComputeOp.VAdd(14, 0, 0))) == 0x5e000000L)
    assert(enc(Bundle(ComputeOp.VSub(0, 14, 0))) == 0x60e00000L)
    assert(enc(Bundle(ComputeOp.VAdd(0, 0, 14))) == 0x500e0000L)
    assert(enc(Bundle(ComputeOp.VMul(0, 15, 0, broadcast = true))) == 0x70f00800L)
    assert(enc(Bundle(ComputeOp.VMac(0, 0, 0, 14, broadcast = false))) == 0x8000e000L)
    assert(enc(Bundle(ComputeOp.VMac(0, 0, 0, 0, broadcast = true))) == 0x80000800L)
    assert(enc(Bundle(MemoryOp.LdW(14, 0))) == 0x3e000000L << 32)
    assert(enc(Bundle(MemoryOp.StW(511, 0))) == 0x40ff8000L << 32)
  }

  private val pairs = 0 until 16 by 2
  private val regs = 0 until 16
  private val wCompute: Seq[ComputeOp] =
    (for (d <- pairs; a <- pairs; b <- pairs)
      yield Seq(
        ComputeOp.VAdd(d, a, b),
        ComputeOp.VSub(d, a, b),
        ComputeOp.VMul(d, a, b, broadcast = false)
      )).flatten ++
      (for (d <- pairs; a <- regs; b <- pairs) yield ComputeOp.VMul(d, a, b, broadcast = true)) ++
      (for (d <- pairs; a <- pairs; b <- pairs; c <- pairs)
        yield ComputeOp.VMac(d, a, b, c, broadcast = false)) ++
      (for (d <- pairs; a <- regs; b <- pairs; c <- pairs)
        yield ComputeOp.VMac(d, a, b, c, broadcast = true))
  private val wMemory: Seq[MemoryOp] =
    (for (p <- pairs; a <- 0 to 511) yield Seq(MemoryOp.LdW(p, a), MemoryOp.StW(a, p))).flatten

  test("every W operation and every mvp-1 operation round-trips on mvp-2 (exhaustive)") {
    val scalar: Seq[ComputeOp] = Seq(ComputeOp.Nop, ComputeOp.Halt) ++
      (for (d <- regs; a <- regs; b <- regs)
        yield Seq(
          ComputeOp.Add(d, a, b),
          ComputeOp.Sub(d, a, b),
          ComputeOp.Mul(d, a, b)
        )).flatten ++
      (for (d <- regs; a <- regs; b <- regs; c <- regs) yield ComputeOp.Mac(d, a, b, c))
    val computes = scalar ++ wCompute
    val memories: Seq[MemoryOp] = MemoryOp.Nop +:
      ((for (r <- regs; a <- 0 to 511) yield Seq(MemoryOp.Ld(r, a), MemoryOp.St(a, r))).flatten ++
        wMemory)
    val cw = computes.map(c => enc(Bundle(c)))
    computes.zip(cw).foreach { case (c, w) => assert(codec.decode(w) == Right(Bundle(c))) }
    val mw = memories.map(m => enc(Bundle(m)))
    memories.zip(mw).foreach { case (m, w) => assert(codec.decode(w) == Right(Bundle(m))) }
    assert(cw.toSet.size == computes.size && mw.toSet.size == memories.size, "injective")
    // W-reg has the vector operations but not LD.W and ST.W.
    wCompute.foreach(c => assert(wregCodec.decode(enc(Bundle(c), wregCodec)) == Right(Bundle(c))))
    wMemory.foreach(m => assert(encErr(Bundle(m), wregCodec).contains("is not an operation")))
  }

  /** A random bundle word: usually with the reserved bits of its opcodes clear, often with even
    * register fields, sometimes with arbitrary bits.
    */
  private def randomWord(rng: Random, computeOps: Seq[Int], memoryOps: Seq[Int]): Long = {
    val computeReserved =
      Map(0 -> 0x0fffffff, 15 -> 0x0fffffff, 4 -> 0xfff, 7 -> 0xf7ff, 8 -> 0x7ff)
    def word(opcode: Int, reserved: Int, lowBits: Int) = {
      val operands = rng.nextInt() & 0x0fffffff
      val plausible = if (rng.nextInt(4) == 0) operands else operands & ~reserved
      (opcode << 28) | (if (rng.nextBoolean()) plausible & ~lowBits else plausible)
    }
    val c = computeOps(rng.nextInt(computeOps.size))
    val m = memoryOps(rng.nextInt(memoryOps.size))
    val cw = word(c, computeReserved.getOrElse(c, 0xffff), 0x01111000)
    val mw = word(m, if (m == 0) 0x0fffffff else 0x7fff, 0x01000000)
    (mw.toLong << 32) | (cw.toLong & 0xffffffffL)
  }

  test("random mvp-2 words: whatever decodes re-encodes to the same word") {
    val rng = new Random(20261020L)
    var vector = 0
    var wide = 0
    (0 until 300000).foreach { _ =>
      val word = randomWord(rng, Seq(0, 1, 2, 3, 4, 5, 6, 7, 8, 15), Seq(0, 1, 2, 3, 4))
      codec.decode(word).foreach { b =>
        b.compute match {
          case _: ComputeOp.VAdd | _: ComputeOp.VSub | _: ComputeOp.VMul | _: ComputeOp.VMac =>
            vector += 1
          case _ => ()
        }
        if (b.memory.isInstanceOf[MemoryOp.LdW] || b.memory.isInstanceOf[MemoryOp.StW]) wide += 1
        assert(enc(b) == word, f"0x$word%016x decoded to $b")
      }
    }
    assert(vector > 20000 && wide > 20000, s"only $vector vector and $wide wide words decoded")
  }

  test("illegal W words are rejected with a reason (rule L1)") {
    // docs/ISA.md "mvp-2: W": the L1 words, which assembly cannot write.
    assert(decErr(0x0000000085268800L).contains("rd r5 is odd"))
    assert(decErr(0x0000000074360000L).contains("rs1 r3 is odd"))
    assert(decErr(0x0000000054260800L).contains("reserved bits 15:0 are not zero"))
    assert(decErr(0x0000000054260800L).contains("bit 11 broadcasts on VMUL and VMAC only"))
    assert(decErr(0x0000000045123800L).contains("reserved bits 11:0 are not zero"))
    assert(decErr(0x0000000074168800L).contains("reserved bits 15:12 and 10:0 are not zero"))
    assert(decErr(0x0000000084268801L).contains("reserved bits 10:0 are not zero"))
    assert(decErr(0x3b08800000000000L).contains("register r11 is odd"))
    assert(decErr(0x3a08c00000000000L).contains("reserved bits 14:0 are not zero"))
    assert(decErr(0x3a08800000000000L, wregCodec).contains("unknown opcode 3"))
    assert(decErr(0x0000000084268800L, mvp1Codec).contains("unknown opcode 8"))
    // An odd pair in each pair field.
    Seq(
      0x55260000L -> "rd r5",
      0x54360000L -> "rs1 r3",
      0x54270000L -> "rs2 r7",
      0x65260000L -> "rd r5",
      0x64360000L -> "rs1 r3",
      0x64270000L -> "rs2 r7",
      0x75160800L -> "rd r5",
      0x74170800L -> "rs2 r7",
      0x75260000L -> "rd r5",
      0x74270000L -> "rs2 r7",
      0x85268000L -> "rd r5",
      0x84368000L -> "rs1 r3",
      0x84278800L -> "rs2 r7",
      0x84269800L -> "rs3 r9",
      0x84269000L -> "rs3 r9",
      0x4d08800000000000L -> "register r13"
    ).foreach { case (w, field) => assert(decErr(w).contains(s"$field is odd"), f"0x$w%016x") }
    // The broadcast bit on the operations without it, and the other reserved bits.
    assert(decErr(0x0000000064260800L).contains("reserved bits 15:0 are not zero"))
    assert(decErr(0x0000000014310800L).contains("reserved bits 15:0 are not zero"))
    assert(decErr(0x0000000074260400L).contains("reserved bits 15:12 and 10:0 are not zero"))
    assert(decErr(0x0000000074261000L).contains("reserved bits 15:12 and 10:0 are not zero"))
    assert(decErr(0x0000000084268400L).contains("reserved bits 10:0 are not zero"))
    assert(decErr(0x0000000054260001L).contains("reserved bits 15:0 are not zero"))
    assert(decErr(0x4c08800100000000L).contains("reserved bits 14:0 are not zero"))
    // With the broadcast bit, rs1 of VMUL and VMAC may be odd.
    assert(codec.decode(0x0000000074160800L).isRight && codec.decode(0x0000000084168800L).isRight)
    // HALT still needs a NOP memory word.
    assert(decErr(0x3a088000f0000000L).contains("HALT requires a NOP memory word"))
    // The W opcodes are unknown on mvp-0 and mvp-1, LD.W and ST.W also on W-reg.
    Seq(5, 6, 7, 8).foreach { op =>
      Seq(mvp0Codec, mvp1Codec).foreach { c =>
        assert(decErr((op.toLong << 28) | 0x04260000L, c).contains(s"unknown opcode $op"))
      }
      assert(wregCodec.decode((op.toLong << 28) | 0x04260000L).isRight)
    }
    Seq(3, 4).foreach { op =>
      Seq(mvp0Codec, mvp1Codec, wregCodec).foreach { c =>
        assert(decErr((op.toLong << 60) | 0x0a08800000000000L, c).contains(s"unknown opcode $op"))
      }
    }
  }

  test("encoding refuses what rule L1 forbids, and W operations on machines without them") {
    assert(encErr(Bundle(ComputeOp.VAdd(5, 2, 6))).contains("rd r5 is odd"))
    assert(encErr(Bundle(ComputeOp.VSub(4, 3, 6))).contains("rs1 r3 is odd"))
    assert(encErr(Bundle(ComputeOp.VMul(4, 3, 6, broadcast = false))).contains("rs1 r3 is odd"))
    assert(codec.encode(Bundle(ComputeOp.VMul(4, 3, 6, broadcast = true))).isRight)
    assert(encErr(Bundle(ComputeOp.VMul(4, 2, 7, broadcast = false))).contains("rs2 r7 is odd"))
    assert(encErr(Bundle(ComputeOp.VMac(4, 2, 6, 9, broadcast = true))).contains("rs3 r9 is odd"))
    assert(encErr(Bundle(MemoryOp.LdW(11, 17))).contains("rd r11 is odd"))
    assert(encErr(Bundle(MemoryOp.StW(17, 13))).contains("rs r13 is odd"))
    assert(encErr(Bundle(MemoryOp.LdW(10, 512))).contains("address 512 does not fit in 9 bits"))
    assert(encErr(Bundle(ComputeOp.VAdd(16, 2, 6))).contains("rd r16 is outside r0..r15"))
    assert(encErr(Bundle(ComputeOp.VMul(4, 16, 6, broadcast = true))).contains("rs1 r16"))
    assert(encErr(Bundle(ComputeOp.Halt, MemoryOp.LdW(0, 0))).contains("HALT requires a NOP"))
    assert(
      encErr(Bundle(ComputeOp.VAdd(4, 2, 6)), mvp1Codec)
        .contains("VADD is not an operation of vliw2-i32-sp2-mac (ISA mvp-1)")
    )
    assert(
      encErr(Bundle(ComputeOp.VMac(4, 2, 6, 8, broadcast = true)), mvp0Codec)
        .contains("VMAC is not an operation of vliw2-i32-sp2 (ISA mvp-0)")
    )
    assert(
      encErr(Bundle(MemoryOp.LdW(10, 17)), wregCodec)
        .contains("LD.W is not an operation of vliw2-i32-sp2-mac-w2-mw1 (ISA mvp-2)")
    )
    assert(
      encErr(Bundle(MemoryOp.StW(17, 12)), mvp1Codec)
        .contains("ST.W is not an operation of vliw2-i32-sp2-mac (ISA mvp-1)")
    )
    // A smaller register file: pairs up to r6:r7 on 8 registers.
    val small = new BundleCodec(edited(text)(_("register_file")("count") = 8))
    assert(small.encode(Bundle(ComputeOp.VAdd(6, 6, 6))).isRight)
    assert(encErr(Bundle(ComputeOp.VAdd(8, 0, 0)), small).contains("rd r8 is outside r0..r7"))
    assert(decErr(0x0000000080008000L, small).contains("rs3 r8 is outside r0..r7"))
    assert(decErr(0x3800000000000000L, small).contains("register r8 is outside r0..r7"))
  }

  test("mvp-1 words decode to the same operations on mvp-2") {
    val rng = new Random(20261021L)
    var legal = 0
    (0 until 200000).foreach { _ =>
      val word = randomWord(rng, Seq(0, 1, 2, 3, 4, 15), Seq(0, 1, 2))
      // The same verdict and the same operation; MAC with bit 11 set stays illegal.
      assert(codec.decode(word) == mvp1Codec.decode(word), f"0x$word%016x")
      if (mvp1Codec.decode(word).isRight) legal += 1
    }
    assert(legal > 50000, s"only $legal legal mvp-1 words")
    val golden = ProgramFile.fromHex(read("tests/golden/isa_example.hex")).toOption.get
    assert(codec.decodeAll(golden) == mvp0Codec.decodeAll(golden))
  }

  test("the opcodes of the W operations come from the machine description") {
    val c = new BundleCodec(edited(text) { v =>
      v("operations")("VADD")("opcode") = 9
      v("operations")("LD.W")("opcode") = 5
    })
    assert(c.encode(Bundle(ComputeOp.VAdd(4, 2, 6))) == Right(0x94260000L))
    assert(c.decode(0x0000000054260000L).left.toOption.get.contains("unknown opcode 5"))
    assert(c.encode(Bundle(MemoryOp.LdW(10, 17))) == Right(0x5a08800000000000L))
    assert(c.decode(0x3a08800000000000L).left.toOption.get.contains("unknown opcode 3"))
  }

  test("W adds no legality rule (L1-L11)") {
    assert(Rule.all.map(_.id) == (1 to 11).map(i => s"L$i"))
  }

  // Assembly syntax (docs/ASM.md).

  private def asm(src: String, m: MachineDescription = machine): Vector[Bundle] =
    Assembler.assemble(src, m).fold(es => fail(es.mkString("\n")), _.bundles)
  private def asmErr(src: String, m: MachineDescription): String =
    Assembler.assemble(src, m).left.getOrElse(fail(s"should not assemble: $src")).mkString("\n")

  test("the W syntax assembles to the documented bundles; the disassembly is canonical") {
    examples.foreach { case (bundle, line, word) =>
      assert(asm(line) == Vector(bundle), line)
      assert(codec.encodeAll(asm(line)) == Right(Vector(word)), line)
      val text = Disassembler.bundle(bundle)
      assert(text.split(" \\| ").map(_.trim).toSeq == line.split(" \\| ").toSeq, text)
    }
    // Mnemonics and registers are case-insensitive; a single rs1 is a broadcast.
    assert(
      asm("VMAC R4:R5, R2, R6:r7, r8:R9 | LD.W R10:R11, [0x11]\nvmul r0:r1, r0:r1, r0:r1") ==
        Vector(
          Bundle(ComputeOp.VMac(4, 2, 6, 8, broadcast = true), MemoryOp.LdW(10, 17)),
          Bundle(ComputeOp.VMul(0, 0, 0, broadcast = false))
        )
    )
    assert(
      asm("vmul r14:r15, r15, r14:r15") ==
        Vector(Bundle(ComputeOp.VMul(14, 15, 14, broadcast = true)))
    )
    // Canonical forms; vector operations are longer than 15 characters and push '|' right.
    assert(
      Disassembler.program(
        asm("vmul r4:r5, r1, r6:r7 | st.w [17], r12:r13\nnop | ld.w r2:r3, [8]")
      ) ==
        "@0: vmul r4:r5, r1, r6:r7 | st.w [17], r12:r13\n@1: nop             | ld.w r2:r3, [8]\n"
    )
  }

  test("assembly errors of the W syntax") {
    def has(src: String, expected: String, m: MachineDescription = machine) = {
      val e = asmErr(src, m)
      assert(e.contains(expected), e)
    }
    has("vadd r4:r5, r2, r6:r7", "vadd has no broadcast")
    has("vsub r4:r5, r2, r6:r7", "vsub has no broadcast")
    has("vmul r4:r6, r1, r2:r3", "a pair names two consecutive registers rP:rP+1, got r4:r6")
    has("vmul r4:r5, r1, r3:r2", "a pair names two consecutive registers")
    has("vmul r5:r6, r1, r2:r3", "pair r5:r6 starts at an odd register")
    has("vadd r4:r5, r3:r4, r6:r7", "pair r3:r4 starts at an odd register")
    has("| ld.w r11:r12, [17]", "pair r11:r12 starts at an odd register")
    has("vadd r4 : r5, r2:r3, r6:r7", "expected a register pair like r4:r5, got \"r4 : r5\"")
    has("vadd r4, r2:r3, r6:r7", "expected a register pair like r4:r5, got \"r4\"")
    has("add r4:r5, r1, r2", "expected a register, got \"r4:r5\"")
    has("| ld r4:r5, [0]", "expected a register, got \"r4:r5\"")
    has("vmac r4:r5, r2, r6:r7", "vmac takes 4 operand(s), got 3")
    has("vadd r4:r5, r2:r3", "vadd takes 3 operand(s), got 2")
    has("vadd r14:r15, r16:r17, r6:r7", "register r16 is outside r0..r15")
    has("| ld.w r10:r11, [512]", "does not fit in the 9-bit immediate")
    has("| st.w r12:r13, [17]", "expected an address")
    has("ld.w r2:r3, [0]", "ld.w is a memory operation; write it after '|'")
    has("st.w [0], r2:r3", "st.w is a memory operation; write it after '|'")
    has("| vadd r4:r5, r2:r3, r6:r7", "vadd is a compute operation; write it before '|'")
    has("| vmac r4:r5, r2, r6:r7, r8:r9", "vmac is a compute operation; write it before '|'")
    has("halt | ld.w r2:r3, [0]", "halt must be paired with a memory nop")
    has("ld.x r1, [0]", "unknown compute operation")
    has(
      "vadd r4:r5, r2:r3, r6:r7",
      "vadd is not an operation of vliw2-i32-sp2-mac (ISA mvp-1)",
      mvp1
    )
    has(
      "vmac r4:r5, r2, r6:r7, r8:r9",
      "vmac is not an operation of vliw2-i32-sp2 (ISA mvp-0)",
      mvp0
    )
    has(
      "| ld.w r10:r11, [17]",
      "ld.w is not an operation of vliw2-i32-sp2-mac-w2-mw1 (ISA mvp-2)",
      wreg
    )
    has("| st.w [17], r12:r13", "st.w is not an operation of vliw2-i32-sp2-mac (ISA mvp-1)", mvp1)
    // Within the 9-bit immediate, an address beyond the scratchpad is L7, not a syntax error.
    assert(asm("| ld.w r10:r11, [511]") == Vector(Bundle(MemoryOp.LdW(10, 511))))
    assert(asm("vadd r0:r1, r0:r1, r0:r1", wreg) == Vector(Bundle(ComputeOp.VAdd(0, 0, 0))))
  }

  test("program -> words -> disassembly -> program round-trips for random mvp-2 programs") {
    val rng = new Random(78L)
    def p = 2 * rng.nextInt(8)
    def r = rng.nextInt(16)
    def compute: ComputeOp = rng.nextInt(10) match {
      case 0 => ComputeOp.Nop
      case 1 => ComputeOp.Add(r, r, r)
      case 2 => ComputeOp.Mac(r, r, r, r)
      case 3 => ComputeOp.VAdd(p, p, p)
      case 4 => ComputeOp.VSub(p, p, p)
      case 5 => ComputeOp.VMul(p, p, p, broadcast = false)
      case 6 => ComputeOp.VMul(p, r, p, broadcast = true)
      case 7 => ComputeOp.VMac(p, p, p, p, broadcast = false)
      case 8 => ComputeOp.VMac(p, r, p, p, broadcast = true)
      case _ => ComputeOp.Halt
    }
    def memory: MemoryOp = rng.nextInt(5) match {
      case 0 => MemoryOp.Nop
      case 1 => MemoryOp.Ld(r, rng.nextInt(512))
      case 2 => MemoryOp.St(rng.nextInt(512), r)
      case 3 => MemoryOp.LdW(p, rng.nextInt(512))
      case _ => MemoryOp.StW(rng.nextInt(512), p)
    }
    (0 until 300).foreach { _ =>
      val bundles = Vector.fill(1 + rng.nextInt(40)) {
        val c = compute
        Bundle(c, if (c == ComputeOp.Halt) MemoryOp.Nop else memory)
      }
      val words = codec.encodeAll(bundles).fold(e => fail(e), identity)
      val text = Disassembler.program(codec.decodeAll(words).fold(e => fail(e), identity))
      assert(asm(text) == bundles)
      assert(codec.encodeAll(asm(text)) == Right(words))
    }
  }

  test("mvp-0 and mvp-1 disassembly is unchanged (Compute padded to 15 characters)") {
    val bundles = Vector(
      Bundle(ComputeOp.Mac(5, 1, 2, 3), MemoryOp.St(7, 4)),
      Bundle(ComputeOp.Add(4, 3, 1)),
      Bundle(MemoryOp.Ld(1, 0)),
      Bundle(ComputeOp.Halt)
    )
    assert(
      Disassembler.program(bundles) ==
        "@0: mac r5, r1, r2, r3 | st [7], r4\n@1: add r4, r3, r1  | nop\n" +
        "@2: nop             | ld r1, [0]\n@3: halt            | nop\n"
    )
    assert(asm(Disassembler.program(bundles), mvp1) == bundles)
  }
}
