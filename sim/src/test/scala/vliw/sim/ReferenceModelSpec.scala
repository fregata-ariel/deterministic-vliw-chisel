package vliw.sim

import org.scalatest.funsuite.AnyFunSuite

class ReferenceModelSpec extends AnyFunSuite {
  test("reference model targets the ISA revision provided by isa") {
    assert(ReferenceModel.modeledIsa == "mvp-0")
  }
}
