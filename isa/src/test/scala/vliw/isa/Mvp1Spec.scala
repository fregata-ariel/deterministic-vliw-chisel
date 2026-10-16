package vliw.isa

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import scala.util.Random

import org.scalatest.funsuite.AnyFunSuite

/** ISA mvp-1: the schema 2 machine description, MAC's encoding and its assembly syntax (docs/ISA.md
  * "mvp-1: MAC", docs/MACHINE.md "mvp-1（schema_version 2）", ADR-0018).
  */
class Mvp1Spec extends AnyFunSuite {
  private def read(path: String): String =
    new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)
  private val text = read("config/machine-mvp1.json")
  private def loadOk(t: String): MachineDescription =
    MachineDescription.parse(t).fold(es => fail(es.mkString("\n")), identity)
  private val machine = loadOk(text)
  private val mvp0 = MachineDescription.load().fold(es => fail(es.mkString("\n")), identity)
  private val codec = new BundleCodec(machine)

  private def errorsAfter(base: String)(edit: ujson.Value => Unit): List[String] = {
    val v = ujson.read(base)
    edit(v)
    MachineDescription.parse(ujson.write(v)).left.getOrElse(fail("expected a rejection"))
  }
  private def assertRejected(base: String, expected: String)(edit: ujson.Value => Unit): Unit = {
    val errors = errorsAfter(base)(edit)
    assert(errors.exists(_.contains(expected)), errors.mkString("\n"))
  }
  private def enc(b: Bundle): Long = codec.encode(b).fold(e => fail(e), identity)

  test("config/machine-mvp1.json is mvp-0 plus MAC (opcode 4, latency 2, II 1)") {
    assert((machine.schemaVersion, machine.isaVersion) == ((2, IsaVersion.Mvp1)))
    assert(
      (machine.machineId, machine.revision, machine.status) == ((
        "vliw2-i32-sp2-mac",
        1,
        "frozen"
      ))
    )
    assert(machine.hasMac && !mvp0.hasMac)
    assert(machine.readPorts == 3 && mvp0.readPorts == 3)
    assert(
      machine.operation("MAC") == OperationSpec("MAC", Slot.Compute, 4, 2, 1, Some(Slot.Compute))
    )
    assert(machine.operations - "MAC" == mvp0.operations)
    val same = (m: MachineDescription) =>
      (
        m.registerCount,
        m.banks,
        m.wordsPerBank,
        m.haltOpcode,
        m.nopOpcode,
        m.instructionMemoryBundles
      )
    assert(same(machine) == same(mvp0))
  }

  test("config/machine.json stays the frozen mvp-0 revision 2, byte for byte") {
    assert((mvp0.schemaVersion, mvp0.isaVersion, mvp0.revision) == ((1, IsaVersion.Mvp0, 2)))
    assert(mvp0.status == "frozen" && mvp0.machineId == "vliw2-i32-sp2")
    assert(mvp0.hash == "sha256:a4af6064e049415dc9822614264a01f06b6e1be39c86bc41c5fcdda3e7d89e21")
  }

  test("the mvp-1 variants differ from the default only in read ports or MAC latency") {
    // The variants are test machines and stay proposed; config/machine-mvp1.json is frozen.
    val rp4 = loadOk(read("tests/machines/mvp1_rp4.json"))
    val mac3 = loadOk(read("tests/machines/mvp1_mac3.json"))
    assert(rp4.status == "proposed" && mac3.status == "proposed")
    assert(
      rp4.copy(
        machineId = machine.machineId,
        status = machine.status,
        readPorts = 3,
        canonicalJson = "",
        hash = ""
      ) ==
        machine.copy(canonicalJson = "", hash = "")
    )
    assert(rp4.readPorts == 4)
    val mac = machine.operation("MAC")
    assert(
      mac3.copy(
        machineId = machine.machineId,
        status = machine.status,
        operations = mac3.operations.updated("MAC", mac),
        canonicalJson = "",
        hash = ""
      ) == machine.copy(canonicalJson = "", hash = "")
    )
    assert(mac3.operation("MAC").latency == 3)
  }

  test("schema 1 is for mvp-0 and schema 2 for mvp-1") {
    assertRejected(text, "$.isa_version: schema_version 2 is for mvp-1, got mvp-0")(
      _("isa_version") = "mvp-0"
    )
    assertRejected(read("config/machine.json"), "schema_version 1 is for mvp-0, got mvp-1")(
      _("isa_version") = "mvp-1"
    )
    assertRejected(text, "$.isa_version: must be one of mvp-0, mvp-1")(_("isa_version") = "mvp-2")
    assertRejected(text, "$.schema_version: must be in 1..2, got 3")(_("schema_version") = 3)
  }

  test("MAC is required in mvp-1 and unknown in mvp-0") {
    assertRejected(text, """$.operations: missing key "MAC"""")(_("operations").obj.remove("MAC"))
    assertRejected(read("config/machine.json"), """$.operations: unknown key "MAC"""")(v =>
      v("operations")("MAC") = ujson.read(text)("operations")("MAC")
    )
    assertRejected(text, """$.operations.MAC.slot: must be "compute"""")(
      _("operations")("MAC")("slot") = "memory"
    )
    assertRejected(text, """$.operations.MAC.writeback: must be "compute"""")(
      _("operations")("MAC")("writeback") = "memory"
    )
    assertRejected(text, "$.operations.MAC.latency: must be in 1..8, got 9")(
      _("operations")("MAC")("latency") = 9
    )
    assertRejected(text, "opcode 3 is used more than once in the compute slot: MAC, MUL")(
      _("operations")("MAC")("opcode") = 3
    )
    assertRejected(text, "opcode 15 is used more than once in the compute slot: HALT, MAC")(
      _("operations")("MAC")("opcode") = 15
    )
  }

  test("read_ports is 3 or 4 in mvp-1 and 3 in mvp-0 (rule L11)") {
    assertRejected(text, "$.register_file.read_ports: must be in 3..4, got 2")(
      _("register_file")("read_ports") = 2
    )
    assertRejected(text, "$.register_file.read_ports: must be in 3..4, got 5")(
      _("register_file")("read_ports") = 5
    )
    assertRejected(read("config/machine.json"), "$.register_file.read_ports: must be 3, got 4")(
      _("register_file")("read_ports") = 4
    )
  }

  test("MAC encodes rs3 in bits 15:12 (docs/ISA.md example)") {
    assert(enc(Bundle(ComputeOp.Mac(5, 1, 2, 3))) == 0x0000000045123000L)
    assert(enc(Bundle(ComputeOp.Mac(0, 0, 0, 0xf))) == 0x4000f000L)
    assert(enc(Bundle(ComputeOp.Mac(0xf, 0, 0, 0))) == 0x4f000000L)
    val b = Bundle(ComputeOp.Mac(1, 2, 3, 4), MemoryOp.St(7, 5))
    assert(codec.decode(enc(b)) == Right(b))
  }

  test("every MAC and every mvp-0 compute operation round-trips on mvp-1 (exhaustive)") {
    val regs = 0 until 16
    val ops: Seq[ComputeOp] = Seq(ComputeOp.Nop, ComputeOp.Halt) ++
      (for (d <- regs; a <- regs; b <- regs)
        yield Seq(
          ComputeOp.Add(d, a, b),
          ComputeOp.Sub(d, a, b),
          ComputeOp.Mul(d, a, b)
        )).flatten ++
      (for (d <- regs; a <- regs; b <- regs; c <- regs) yield ComputeOp.Mac(d, a, b, c))
    val words = ops.map(c => enc(Bundle(c)))
    ops.zip(words).foreach { case (c, w) => assert(codec.decode(w) == Right(Bundle(c))) }
    assert(words.toSet.size == ops.size, "encoding must be injective")
  }

  test("mvp-0 programs encode to the same words on mvp-1") {
    val mvp0Codec = new BundleCodec(mvp0)
    val rng = new Random(20261016L)
    (0 until 20000).foreach { _ =>
      val w = (rng.nextLong() & 0x7fffffffffffffffL)
      mvp0Codec.decode(w).foreach(b => assert(codec.encode(b) == Right(w)))
    }
    val golden = ProgramFile.fromHex(read("tests/golden/isa_example.hex")).toOption.get
    assert(codec.decodeAll(golden) == mvp0Codec.decodeAll(golden))
  }

  test("random mvp-1 words: whatever decodes re-encodes to the same word") {
    val rng = new Random(20261017L)
    var macs = 0
    (0 until 200000).foreach { _ =>
      val opcode = Seq(0, 1, 2, 3, 4, 15)(rng.nextInt(6))
      val operands = rng.nextInt() & 0x0fffffff
      val reserved = if (rng.nextInt(4) == 0) operands else operands & ~0xfff
      val c = (opcode << 28) | reserved
      codec.decode(c.toLong & 0xffffffffL).foreach { b =>
        if (b.compute.isInstanceOf[ComputeOp.Mac]) macs += 1
        assert(enc(b) == (c.toLong & 0xffffffffL), f"0x$c%08x decoded to $b")
      }
    }
    assert(macs > 10000, s"only $macs MACs decoded")
  }

  test("invalid MAC words and MAC on mvp-0 are rejected with a reason") {
    def decErr(c: BundleCodec, w: Long): String =
      c.decode(w).left.getOrElse(fail(f"0x$w%016x should not decode"))
    assert(decErr(codec, 0x45123001L).contains("reserved bits 11:0 are not zero"))
    assert(decErr(codec, 0x45123800L).contains("reserved bits 11:0 are not zero"))
    // ADD, SUB and MUL keep bits 15:0 reserved in mvp-1.
    assert(decErr(codec, 0x14313000L).contains("reserved bits 15:0 are not zero"))
    assert(decErr(new BundleCodec(mvp0), 0x45123000L).contains("unknown opcode 4"))
    assert(
      new BundleCodec(mvp0)
        .encode(Bundle(ComputeOp.Mac(5, 1, 2, 3)))
        .left
        .toOption
        .get
        .contains("MAC is not an operation of vliw2-i32-sp2 (ISA mvp-0)")
    )
    val v = ujson.read(text)
    v("register_file")("count") = 8
    val small = new BundleCodec(loadOk(ujson.write(v)))
    assert(small.encode(Bundle(ComputeOp.Mac(0, 0, 0, 8))).left.toOption.get.contains("rs3 r8"))
    assert(small.decode(0x40008000L).isLeft)
    assert(small.decode(0x47777000L).isRight)
  }

  test("the opcode of MAC comes from the machine description") {
    val v = ujson.read(text)
    v("operations")("MAC")("opcode") = 9
    val c = new BundleCodec(loadOk(ujson.write(v)))
    assert(c.encode(Bundle(ComputeOp.Mac(5, 1, 2, 3))) == Right(0x95123000L))
    assert(c.decode(0x45123000L).left.toOption.get.contains("unknown opcode 4"))
  }

  test("mac assembles and disassembles on mvp-1 only") {
    def asm(m: MachineDescription, src: String) = Assembler.assemble(src, m)
    val program = "mac r5, r1, r2, r3 | st [7], r4\nMAC R1, R2, R3, R1\nnop | ld r3, [0]\nhalt\n"
    val bundles = asm(machine, program).fold(es => fail(es.mkString), _.bundles)
    assert(
      bundles.take(2) == Vector(
        Bundle(ComputeOp.Mac(5, 1, 2, 3), MemoryOp.St(7, 4)),
        Bundle(ComputeOp.Mac(1, 2, 3, 1))
      )
    )
    assert(Disassembler.compute(ComputeOp.Mac(5, 1, 2, 3)) == "mac r5, r1, r2, r3")
    assert(asm(machine, Disassembler.program(bundles)).map(_.bundles) == Right(bundles))
    assert(asm(machine, "add r1, r2, r3, r4").left.toOption.get.head.contains("add takes 3"))
    assert(
      asm(machine, "mac r1, r2, r3").left.toOption.get.head
        .contains("mac takes 4 operand(s), got 3")
    )
    assert(asm(machine, "mac r1, r2, r3, r16").left.toOption.get.head.contains("r16 is outside"))
    assert(
      asm(machine, "| mac r1, r2, r3, r4").left.toOption.get.head.contains("is a compute operation")
    )
    assert(
      asm(mvp0, "mac r1, r2, r3, r4").left.toOption.get.head
        .contains("mac is not an operation of vliw2-i32-sp2 (ISA mvp-0)")
    )
  }

  test("L11 is a legality rule") {
    assert(Rule.byId("L11") == Rule.ReadPorts)
    assert(Rule.all.map(_.id) == (1 to 11).map(i => s"L$i"))
  }
}
