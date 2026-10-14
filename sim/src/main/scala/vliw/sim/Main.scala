package vliw.sim

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import vliw.isa.TestCase

/** `sbt "sim/run <case.json> [--trace out.jsonl] [--metrics out.json]"`: run a test case on the
  * reference model, optionally writing its trace (docs/TRACE.md) and metrics (docs/METRICS.md).
  */
object Main {
  def main(args: Array[String]): Unit = {
    // Arguments: <case.json> followed by optional "--trace <path>" and "--metrics <path>".
    val (casePath, opts) = args.toList match {
      case c :: rest if !c.startsWith("--") && rest.size % 2 == 0 =>
        val pairs = rest.grouped(2).map(kv => kv.head -> kv.last).toMap
        if (pairs.keySet.subsetOf(Set("--trace", "--metrics")) && pairs.size * 2 == rest.size)
          (c, pairs)
        else usage()
      case _ => usage()
    }
    val tracePath = opts.get("--trace")
    val metricsPath = opts.get("--metrics")
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
            metricsPath.foreach { m =>
              val record =
                ujson.write(Metrics.of(c.machine, c.words, run, c.name), indent = 2) + "\n"
              Files.write(Paths.get(m), record.getBytes(StandardCharsets.UTF_8))
            }
            val regs = run.registers.zipWithIndex.collect { case (Some(v), r) => f"r$r=0x$v%08x" }
            println(s"${c.name}: completed in ${run.cycles} cycles")
            println(s"  registers: ${regs.mkString(" ")}")
            val mem = run.memory.zipWithIndex.collect { case (Some(v), a) => f"[$a]=0x$v%08x" }
            println(s"  memory:    ${mem.mkString(" ")}")
        }
    }
  }

  private def usage(): Nothing = {
    System.err.println("usage: <case.json> [--trace out.jsonl] [--metrics out.json]")
    sys.exit(2)
  }
}
