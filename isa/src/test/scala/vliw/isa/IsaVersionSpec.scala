package vliw.isa

import org.scalatest.funsuite.AnyFunSuite

class IsaVersionSpec extends AnyFunSuite {
  test("the implemented ISA revision is mvp-0") {
    assert(IsaVersion.Current == "mvp-0")
  }
}
