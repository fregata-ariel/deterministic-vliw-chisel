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

  test("configurations the RTL cannot implement are rejected at elaboration") {
    val base = MachineDescription.load().fold(es => fail(es.mkString), identity)
    def edited(edit: ujson.Value => Unit) = {
      val v = ujson.read(base.canonicalJson)
      edit(v)
      MachineDescription.parse(ujson.write(v)).fold(es => fail(es.mkString), identity)
    }
    assertThrows[IllegalArgumentException](CoreParams(edited(_("operations")("ST")("latency") = 2)))
    assertThrows[IllegalArgumentException](CoreParams(edited(_("operations")("LD")("latency") = 1)))
    assertThrows[IllegalArgumentException](CoreParams(edited(_("operations")("MUL")("ii") = 2)))
  }
}
