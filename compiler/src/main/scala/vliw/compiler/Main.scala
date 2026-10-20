package vliw.compiler

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.util.Try

import vliw.compiler.dsl.Benchmarks
import vliw.compiler.schedule.Priority
import vliw.isa.{MachineDescription, ProgramFile, TestCase}

/** `sbt "compiler/run verify <case.json>..."`: exit 0 if all are legal, 1 on violations, 2 on input
  * errors.
  *
  * `sbt "compiler/run compile <kernel>... [--machine m.json] [--out dir] [--seed n] [--priority p] [--single-issue]"`:
  * compiles benchmark kernels and writes `<kernel>.hex` and the test case `<kernel>.json`; exit 2
  * on input errors, 3 on compiler errors (docs/COMPILER.md).
  */
object Main {
  private val usage =
    s"""usage: verify <case.json>...
       |       compile <kernel>... [--machine <machine.json>] [--out <dir>] [--seed <n>] [--priority <p>]
       |               [--single-issue]
       |         kernels: ${Benchmarks.all.map(_.name).mkString(", ")}
       |         priorities: ${Priority.all
        .map(_.name)
        .mkString(", ")} (default: all, best result)""".stripMargin

  private def exit(code: Int, message: String): Nothing = {
    System.err.println(message)
    sys.exit(code)
  }

  def main(args: Array[String]): Unit = args.toList match {
    case "verify" :: cases if cases.nonEmpty => verify(cases)
    case "compile" :: rest                   => compile(rest)
    case _                                   => exit(2, usage)
  }

  private def verify(cases: List[String]): Unit = {
    val illegal = cases.map { path =>
      TestCase.load(Paths.get(path)) match {
        case Left(errors) => exit(2, errors.mkString("\n"))
        case Right(c)     =>
          val findings = Verifier.verify(c.machine, c.words, Verifier.Declaration.of(c.inputs))
          if (findings.isEmpty) println(s"${c.name}: legal")
          else {
            println(s"${c.name}: illegal (${findings.map(_.rule.id).distinct.mkString(", ")})")
            findings.foreach(f => println(s"  $f"))
          }
          findings.nonEmpty
      }
    }
    if (illegal.contains(true)) sys.exit(1)
  }

  private final case class Options(
      kernels: Vector[String] = Vector.empty,
      machine: Option[String] = None,
      out: Path = Paths.get("generated", "kernels"),
      seed: Long = 1L,
      priorities: Seq[Priority] = Priority.all,
      singleIssue: Boolean = false
  )

  @annotation.tailrec
  private def parse(args: List[String], o: Options): Options = args match {
    case Nil                      => o
    case "--machine" :: m :: rest => parse(rest, o.copy(machine = Some(m)))
    case "--single-issue" :: rest => parse(rest, o.copy(singleIssue = true))
    case "--out" :: d :: rest     => parse(rest, o.copy(out = Paths.get(d)))
    case "--seed" :: n :: rest    =>
      parse(rest, o.copy(seed = Try(n.toLong).getOrElse(exit(2, s"not a number: $n"))))
    case "--priority" :: p :: rest =>
      val priority =
        Priority.all.find(_.name == p).getOrElse(exit(2, s"unknown priority: $p\n$usage"))
      parse(rest, o.copy(priorities = Seq(priority)))
    case flag :: _ if flag.startsWith("--") => exit(2, s"unknown option: $flag\n$usage")
    case k :: rest                          => parse(rest, o.copy(kernels = o.kernels :+ k))
  }

  private def compile(args: List[String]): Unit = {
    val o = parse(args, Options())
    if (o.kernels.isEmpty) exit(2, usage)
    val machine = o.machine
      .fold(MachineDescription.load())(m => MachineDescription.load(Paths.get(m)))
      .fold(es => exit(2, es.mkString("\n")), identity)
    val kernels = o.kernels.map { name =>
      Benchmarks.all.find(_.name == name).getOrElse(exit(2, s"unknown kernel: $name\n$usage"))
    }
    Files.createDirectories(o.out)
    kernels.foreach { k =>
      // A kernel that does not fit the machine is an input error; rejected code is a compiler error.
      val c = KernelCompiler
        .compile(k, machine, o.priorities, o.singleIssue)
        .fold(e => exit(if (e.startsWith("compiler error")) 3 else 2, e), identity)
      val tc = KernelCompiler
        .testCase(c, KernelCompiler.sampleInputs(k, o.seed), o.machine)
        .fold(e => exit(3, e), identity)
      val hex = o.out.resolve(s"${k.name}.hex")
      val json = o.out.resolve(s"${k.name}.json")
      ProgramFile.write(hex, c.words).fold(e => exit(2, e), identity)
      Files.write(json, (ujson.write(tc, indent = 2) + "\n").getBytes(StandardCharsets.UTF_8))
      val fused = if (machine.hasMac) s", ${c.dag.macs} MUL + ADD pairs fused into MAC" else ""
      println(
        s"${k.name}: ${c.cycles} cycles (lower bounds ${c.dag.cyclesLowerBound} from dependences, " +
          s"${c.dag.resourceLowerBound} from slots), ${c.allocated.attempt}$fused; " +
          s"wrote $hex and $json"
      )
    }
  }
}
