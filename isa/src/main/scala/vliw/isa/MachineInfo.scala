package vliw.isa

import java.nio.file.Paths

/** `sbt "isa/run [path]"`: validates a machine description and prints its identity. */
object MachineInfo {
  def main(args: Array[String]): Unit = {
    val path = args.headOption.map(Paths.get(_)).getOrElse(MachineDescription.DefaultPath)
    MachineDescription.load(path) match {
      case Right(m) =>
        println(s"path:      $path")
        println(s"machine:   ${m.machineId} revision ${m.revision} (${m.status})")
        println(s"isa:       ${IsaVersion.Current}")
        println(s"hash:      ${m.hash}")
      case Left(errors) =>
        errors.foreach(System.err.println)
        sys.exit(1)
    }
  }
}
