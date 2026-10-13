package vliw.sim

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import vliw.isa.TestCase

/** `sbt "sim/run <case.json> [--trace out.jsonl]"`: run a test case on the reference model. */
object Main {
  def main(args: Array[String]): Unit = {
    val (casePath, tracePath) = args.toList match {
      case List(c)               => (c, None)
      case List(c, "--trace", t) => (c, Some(t))
      case _                     =>
        System.err.println("usage: <case.json> [--trace out.jsonl]")
        sys.exit(2)
    }
    TestCase.load(Paths.get(casePath)) match {
      case Left(errors) =>
        errors.foreach(System.err.println)
        sys.exit(2)
      case Right(c) =>
        ReferenceModel.run(c.machine, c.words, c.inputs) match {
          case Left(violation) =>
            println(s"${c.name}: illegal: $violation")
            sys.exit(1)
          case Right(run) =>
            tracePath.foreach { t =>
              Files.write(Paths.get(t), run.trace.jsonLines.getBytes(StandardCharsets.UTF_8))
            }
            val regs = run.registers.zipWithIndex.collect { case (Some(v), r) => f"r$r=0x$v%08x" }
            println(s"${c.name}: completed in ${run.cycles} cycles")
            println(s"  registers: ${regs.mkString(" ")}")
            val mem = run.memory.zipWithIndex.collect { case (Some(v), a) => f"[$a]=0x$v%08x" }
            println(s"  memory:    ${mem.mkString(" ")}")
        }
    }
  }
}
