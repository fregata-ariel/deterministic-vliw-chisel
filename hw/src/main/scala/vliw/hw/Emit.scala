package vliw.hw

import java.nio.file.Paths

import circt.stage.ChiselStage

import vliw.isa.MachineDescription

/** `sbt "hw/run [target-dir] [machine.json]"`: writes SystemVerilog for the core (default:
  * generated/ and config/machine.json).
  */
object Emit {
  val FirtoolOptions: Array[String] = Array("-disable-all-randomization", "-strip-debug-info")

  def main(args: Array[String]): Unit = {
    val targetDir = args.headOption.getOrElse("generated")
    val machinePath = args.lift(1).map(Paths.get(_)).getOrElse(MachineDescription.DefaultPath)
    val machine = MachineDescription.load(machinePath) match {
      case Right(m)     => m
      case Left(errors) =>
        errors.foreach(System.err.println)
        sys.exit(1)
    }
    ChiselStage.emitSystemVerilogFile(
      new Core(CoreParams(machine)),
      args = Array("--target-dir", targetDir),
      firtoolOpts = FirtoolOptions
    )
    println(
      s"SystemVerilog for ${machine.machineId} revision ${machine.revision} written to $targetDir/"
    )
  }
}
