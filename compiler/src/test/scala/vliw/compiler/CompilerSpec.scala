package vliw.compiler

import org.scalatest.funsuite.AnyFunSuite

class CompilerSpec extends AnyFunSuite {
  test("compiler targets the ISA revision provided by isa") {
    assert(Compiler.targetIsa == "mvp-0")
  }
}
