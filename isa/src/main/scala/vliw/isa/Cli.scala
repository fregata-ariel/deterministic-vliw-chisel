package vliw.isa

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/** `sbt "isa/run <command> ..."`:
  *
  *   - `machine [machine.json]`: validate a machine description and print its identity
  *   - `asm <in.asm> <out.bin|out.hex> [--machine <machine.json>]`: assemble
  *   - `disasm <in.bin|in.hex> [--machine <machine.json>]`: disassemble to standard output
  *   - `case <case.json>`: validate a test case and print its program
  *
  * `asm` and `disasm` use `config/machine.json` (mvp-0) unless `--machine` names another machine
  * description, such as `config/machine-mvp1.json` for `mac` or `config/machine-mvp2.json` for the
  * W operations of ISA mvp-2 (the option of compiler/Main.scala).
  */
object Cli {
  val usage: String =
    """usage: machine [machine.json]
      |       asm <in.asm> <out.bin|out.hex> [--machine <machine.json>]
      |       disasm <in.bin|in.hex> [--machine <machine.json>]
      |       case <case.json>""".stripMargin

  def main(args: Array[String]): Unit =
    run(args.toList) match {
      case Right(out)   => println(out)
      case Left(errors) =>
        errors.foreach(System.err.println)
        sys.exit(1)
    }

  /** Runs one command: `Right` is its standard output, `Left` its errors (exit 1). */
  def run(args: List[String]): Either[List[String], String] = args match {
    case "machine" :: rest if rest.size <= 1 =>
      val path = rest.headOption.map(Paths.get(_)).getOrElse(MachineDescription.DefaultPath)
      MachineDescription.load(path).map { m =>
        s"""path:    $path
           |machine: ${m.machineId} revision ${m.revision} (${m.status})
           |isa:     ${m.isaVersion}
           |hash:    ${m.hash}""".stripMargin
      }
    case "asm" :: WithMachine(machine, List(in, out)) =>
      for {
        m <- MachineDescription.load(machine)
        src <- read(in)
        program <- Assembler.assemble(src, m).left.map(_.map(e => s"$in: $e"))
        words <- new BundleCodec(m).encodeAll(program.bundles).left.map(List(_))
        _ <- ProgramFile.write(Paths.get(out), words).left.map(List(_))
      } yield s"$out: ${words.size} bundles"
    case "disasm" :: WithMachine(machine, List(in)) =>
      for {
        m <- MachineDescription.load(machine)
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
    case _ => Left(List(usage))
  }

  /** The machine description and the operands of `asm` and `disasm`. `--machine <machine.json>` may
    * stand anywhere, as in compiler/Main.scala, and defaults to config/machine.json.
    */
  private object WithMachine {
    def unapply(args: List[String]): Option[(Path, List[String])] = {
      @annotation.tailrec
      def go(
          rest: List[String],
          machine: Path,
          operands: Vector[String]
      ): Option[(Path, List[String])] =
        rest match {
          case Nil                                => Some((machine, operands.toList))
          case "--machine" :: m :: more           => go(more, Paths.get(m), operands)
          case flag :: _ if flag.startsWith("--") => None
          case a :: more                          => go(more, machine, operands :+ a)
        }
      go(args, MachineDescription.DefaultPath, Vector.empty)
    }
  }

  private def read(path: String): Either[List[String], String] =
    scala.util
      .Try(new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8))
      .toEither
      .left
      .map(e => List(s"$path: cannot read: ${e.getMessage}"))
}
