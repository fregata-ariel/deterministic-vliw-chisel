package vliw.hw

import chisel3._

class SmokeIO extends Bundle {
  val a = Input(UInt(32.W))
  val b = Input(UInt(32.W))
  val sum = Output(UInt(32.W))
}

/** Placeholder until the core lands: registers the sum of two inputs. */
class Smoke extends Module {
  val io = IO(new SmokeIO)
  io.sum := RegNext(io.a + io.b, 0.U)
}
