package vliw.isa

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** `sbt "isa/run <command> ..."`:
  *
  *   - `machine [machine.json]`: validate a machine description and print its identity
  *   - `asm <in.asm> <out.bin|out.hex>`: assemble
  *   - `disasm <in.bin|in.hex>`: disassemble to standard output
  *   - `case <case.json>`: validate a test case and print its program
  */
object Cli {
  def main(args: Array[String]): Unit = {
    val result = args.toList match {
      case "machine" :: rest if rest.size <= 1 =>
        val path = rest.headOption.map(Paths.get(_)).getOrElse(MachineDescription.DefaultPath)
        MachineDescription.load(path).map { m =>
          s"""path:    $path
             |machine: ${m.machineId} revision ${m.revision} (${m.status})
             |isa:     ${IsaVersion.Current}
             |hash:    ${m.hash}""".stripMargin
        }
      case List("asm", in, out) =>
        for {
          m <- MachineDescription.load()
          src <- read(in)
          program <- Assembler.assemble(src, m).left.map(_.map(e => s"$in: $e"))
          words <- new BundleCodec(m).encodeAll(program.bundles).left.map(List(_))
          _ <- ProgramFile.write(Paths.get(out), words).left.map(List(_))
        } yield s"$out: ${words.size} bundles"
      case List("disasm", in) =>
        for {
          m <- MachineDescription.load()
          words <- ProgramFile.read(Paths.get(in)).left.map(List(_))
          bundles <- new BundleCodec(m).decodeAll(words).left.map(e => List(s"$in: $e"))
        } yield Disassembler.program(bundles).stripSuffix("\n")
      case List("case", in) =>
        TestCase.load(Paths.get(in)).flatMap { c =>
          new BundleCodec(c.machine).decodeAll(c.words) match {
            case Right(bundles) =>
              Right(
                s"${c.name}: ${c.description}\n" + Disassembler.program(bundles).stripSuffix("\n")
              )
            case Left(e) => Right(s"${c.name}: ${c.description}\n(program does not decode: $e)")
          }
        }
      case _ =>
        Left(
          List(
            "usage: machine [machine.json] | asm <in.asm> <out.bin|out.hex> | disasm <in.bin|in.hex> | case <case.json>"
          )
        )
    }
    result match {
      case Right(out)   => println(out)
      case Left(errors) =>
        errors.foreach(System.err.println)
        sys.exit(1)
    }
  }

  private def read(path: String): Either[List[String], String] =
    scala.util
      .Try(new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8))
      .toEither
      .left
      .map(e => List(s"$path: cannot read: ${e.getMessage}"))
}
