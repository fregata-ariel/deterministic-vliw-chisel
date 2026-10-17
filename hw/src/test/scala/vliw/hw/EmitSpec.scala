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
}
