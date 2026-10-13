package vliw.compiler

import java.nio.file.Paths

import vliw.isa.TestCase

/** `sbt "compiler/run verify <case.json>..."`: exit 0 if all are legal, 1 on violations. */
object Main {
  def main(args: Array[String]): Unit = args.toList match {
    case "verify" :: cases if cases.nonEmpty =>
      val illegal = cases.map { path =>
        TestCase.load(Paths.get(path)) match {
          case Left(errors) =>
            errors.foreach(System.err.println)
            sys.exit(2)
          case Right(c) =>
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
    case _ =>
      System.err.println("usage: verify <case.json>...")
      sys.exit(2)
  }
}
