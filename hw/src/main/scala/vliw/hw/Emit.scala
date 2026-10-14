package vliw.hw

import circt.stage.ChiselStage

/** `sbt "hw/run [target-dir]"`: writes SystemVerilog for the top module (default: generated/). */
object Emit {
  def main(args: Array[String]): Unit = {
    val targetDir = args.headOption.getOrElse("generated")
    ChiselStage.emitSystemVerilogFile(
      new Smoke,
      args = Array("--target-dir", targetDir),
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
    )
    println(s"SystemVerilog written to $targetDir/")
  }
}
