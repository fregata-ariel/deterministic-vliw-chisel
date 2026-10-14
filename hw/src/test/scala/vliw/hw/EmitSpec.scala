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
  }
}
