package vliw.hw

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

import vliw.isa.MachineDescription

class EmitSpec extends AnyFunSuite {
  test("the core for config/machine.json elaborates and compiles to SystemVerilog") {
    val machine = MachineDescription.load().fold(es => fail(es.mkString), identity)
    val sv = ChiselStage.emitSystemVerilog(
      new Core(CoreParams(machine)),
      firtoolOpts = Emit.FirtoolOptions
    )
    assert(sv.contains("module Core("))
    assert(sv.contains("module Decoder("))
    // mvp-0 has no MAC logic: no rs3, no L11 signal (the RTL is that of mvp-0 before #77).
    assert(!sv.contains("rs3") && !sv.contains("readPorts") && !sv.contains("L11"))
  }

  test("the core for config/machine-mvp1.json has MAC, rs3 on read port 2 and the L11 assertion") {
    val machine =
      MachineDescription.load(MachineDescription.Mvp1Path).fold(es => fail(es.mkString), identity)
    val sv = ChiselStage.emitSystemVerilog(
      new Core(CoreParams(machine)),
      firtoolOpts = Emit.FirtoolOptions
    )
    assert(sv.contains("module Core("))
    assert(sv.contains("io_observe_decoded_rs3") && sv.contains("io_violation_readPorts"))
    assert(sv.contains("io_c"), "the compute slot's third operand")
    assert(sv.contains("L11 (more register reads than read ports)"))
  }

  private val base = MachineDescription.load().fold(es => fail(es.mkString), identity)
  private def edited(edit: ujson.Value => Unit) = {
    val v = ujson.read(base.canonicalJson)
    edit(v)
    MachineDescription.parse(ujson.write(v)).fold(es => fail(es.mkString), identity)
  }
  private def machineFile(name: String) =
    MachineDescription
      .load(java.nio.file.Paths.get("tests", "machines", name))
      .fold(es => fail(es.mkString), identity)

  test("configurations the RTL cannot implement are rejected at elaboration") {
    assertThrows[IllegalArgumentException](CoreParams(edited(_("operations")("ST")("latency") = 2)))
    assertThrows[IllegalArgumentException](CoreParams(edited(_("operations")("LD")("latency") = 1)))
    assertThrows[IllegalArgumentException](CoreParams(edited(_("operations")("MUL")("ii") = 2)))
  }

  test("CoreParams.check reports every unsupported setting with its reason") {
    assert(CoreParams.check(base).isRight)
    def reasons(m: MachineDescription) =
      CoreParams.check(m).left.getOrElse(fail(s"${m.machineId} was accepted"))
    val slow = reasons(machineFile("slow_units.json")) // MUL, LD latency 3; ST latency 2
    assert(slow.size == 1 && slow.head.startsWith("ST has latency 2"))
    val ii = reasons(machineFile("ii_units.json")) // ADD, SUB, MUL II 2; LD II 3
    assert(
      ii.map(_.takeWhile(_ != ';')) == Seq(
        "ADD has II 2",
        "SUB has II 2",
        "MUL has II 2",
        "LD has II 3"
      )
    )
    val both = reasons(edited { v =>
      v("operations")("LD")("latency") = 1
      v("operations")("ST")("ii") = 2
    })
    assert(both.map(_.takeWhile(_ != ' ')) == Seq("ST", "LD"))
  }

  private val mvp1 =
    MachineDescription.load(MachineDescription.Mvp1Path).fold(es => fail(es.mkString), identity)
  private def editedMvp1(edit: ujson.Value => Unit) = {
    val v = ujson.read(mvp1.canonicalJson)
    edit(v)
    MachineDescription.parse(ujson.write(v)).fold(es => fail(es.mkString), identity)
  }

  test("mvp-1: 4 read ports and a MAC II above 1 are outside the RTL's range") {
    assert(CoreParams.check(mvp1).isRight)
    assert(CoreParams.check(machineFile("mvp1_mac3.json")).isRight)
    val rp4 = CoreParams.check(machineFile("mvp1_rp4.json")).left.getOrElse(fail("rp4 accepted"))
    assert(rp4.size == 1 && rp4.head.startsWith("read_ports is 4;") && rp4.head.contains("#57"))
    val ii = CoreParams
      .check(editedMvp1(_("operations")("MAC")("ii") = 2))
      .left
      .getOrElse(fail("MAC II 2 accepted"))
    assert(ii.size == 1 && ii.head.startsWith("MAC has II 2;"), ii)
    assertThrows[IllegalArgumentException](CoreParams(machineFile("mvp1_rp4.json")))
  }

  test("every other setting the loader accepts is within the RTL's range") {
    def bounds(latency: Int, registers: Int, banks: Int, words: Int, bundles: Int) = edited { v =>
      Seq("ADD", "SUB", "MUL").foreach(op => v("operations")(op)("latency") = latency)
      v("operations")("LD")("latency") = latency.max(2)
      v("register_file")("count") = registers
      v("scratchpad")("banks") = banks
      v("scratchpad")("words_per_bank") = words
      v("scratchpad")("bank_mapping") = s"word_address_mod_$banks"
      v("scratchpad")("row_mapping") = s"word_address_div_$banks"
      v("instruction_memory")("bundles") = bundles
    }
    Seq(bounds(1, 1, 1, 1, 1), bounds(8, 16, 512, 1, 4096)).foreach { m =>
      assert(CoreParams.check(m).isRight)
      ChiselStage.emitSystemVerilog(new Core(CoreParams(m)), firtoolOpts = Emit.FirtoolOptions)
    }
    // MAC follows the same range as MUL.
    Seq(1, 8).map(l => editedMvp1(_("operations")("MAC")("latency") = l)).foreach { m =>
      assert(CoreParams.check(m).isRight)
      ChiselStage.emitSystemVerilog(new Core(CoreParams(m)), firtoolOpts = Emit.FirtoolOptions)
    }
  }

  // ISA mvp-2 (#110 stage 3, docs/ISA.md "mvp-2: W").
  private val mvp2 =
    MachineDescription.load(MachineDescription.Mvp2Path).fold(es => fail(es.mkString), identity)
  private def editedMvp2(edit: ujson.Value => Unit) = {
    val v = ujson.read(mvp2.canonicalJson)
    edit(v)
    MachineDescription.parse(ujson.write(v)).fold(es => fail(es.mkString), identity)
  }
  private def emit(m: MachineDescription) =
    ChiselStage.emitSystemVerilog(new Core(CoreParams(m)), firtoolOpts = Emit.FirtoolOptions)

  test(
    "mvp-2: W-full, W-reg and the latency variant elaborate; the W logic is in its own modules"
  ) {
    val sv = (mvp2 +: Seq("mvp2_mw1.json", "mvp2_vlat.json").map(machineFile)).map { m =>
      assert(CoreParams.check(m).isRight, m.machineId)
      m.machineId -> emit(m)
    }.toMap
    sv.values.foreach { s =>
      assert(s.contains("module BankedRegisterFile(") && s.contains("module VectorLane("))
      assert(!s.contains("module RegisterFile("))
      assert(s.contains("L11 (more register reads than read ports)"))
    }
    // LD.W and ST.W, lane 1 of the store and the word a+1, on W-full only.
    val full = sv(mvp2.machineId)
    assert(full.contains("io_storeData1") && full.contains("io_observe_memCommit1"))
    assert(full.contains("L7 (scratchpad address out of range)"), "word a+1 can be 512")
    val reg = sv("vliw2-i32-sp2-mac-w2-mw1")
    assert(!reg.contains("io_storeData1") && !reg.contains("memCommit1") && !reg.contains("wide"))
  }

  test("mvp-2: 4 read ports, II above 1 and store latency 2 are refused with every reason") {
    def reasons(m: MachineDescription) =
      CoreParams.check(m).left.getOrElse(fail(s"${m.machineId} was accepted"))
    assert(
      reasons(machineFile("mvp2_rp4.json")) == Seq(
        "read_ports is 4; the RTL has 3 register read ports per RF bank, port 2 shared by the rs3 of MAC and VMAC and the Memory source of ST and ST.W (4 read ports come in #57, ADR-0018)"
      )
    )
    val ii = "; the RTL issues every unit every cycle and supports only II 1"
    val store = "has latency 2; the RTL supports only 1 (stores write at the issue edge)"
    assert(
      reasons(machineFile("mvp2_vii.json")) ==
        Seq("VADD", "VSUB", "VMUL", "VMAC", "LD.W", "ST.W").map(op => s"$op has II 2$ii") ++
        Seq(s"ST $store", s"ST.W $store")
    )
    val ldw = reasons(editedMvp2(_("operations")("LD.W")("latency") = 1))
    assert(
      ldw == Seq("LD.W has latency 1; the RTL needs at least 2 (synchronous-read scratchpad)"),
      ldw
    )
    Seq("mvp2_rp4.json", "mvp2_vii.json").foreach { f =>
      assertThrows[IllegalArgumentException](CoreParams(machineFile(f)))
    }
  }

  test("mvp-2: the ends of the loader's ranges elaborate") {
    val vector = Seq("VADD", "VSUB", "VMUL", "VMAC")
    def ends(latency: Int, registers: Int, banks: Int, words: Int, bundles: Int, wide: Boolean) =
      editedMvp2 { v =>
        vector.foreach(op => v("operations")(op)("latency") = latency)
        v("register_file")("count") = registers
        v("scratchpad")("banks") = banks
        v("scratchpad")("words_per_bank") = words
        v("scratchpad")("bank_mapping") = s"word_address_mod_$banks"
        v("scratchpad")("row_mapping") = s"word_address_div_$banks"
        v("instruction_memory")("bundles") = bundles
        if (wide) v("operations")("LD.W")("latency") = latency.max(2)
        else {
          v("scratchpad")("max_words_per_access") = 1
          v("operations").obj.remove("LD.W")
          v("operations").obj.remove("ST.W")
        }
      }
    Seq(
      ends(1, 2, 2, 256, 1, wide = true),
      ends(8, 16, 512, 1, 4096, wide = true),
      ends(1, 2, 1, 512, 4096, wide = false),
      ends(8, 16, 1, 1, 1, wide = false)
    ).foreach { m =>
      assert(CoreParams.check(m).isRight, m.registerCount)
      emit(m)
    }
  }
}
