package vliw.hw

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import org.scalatest.funsuite.AnyFunSuite

import vliw.isa._

/** Every legal case in tests/cases, run on the RTL, against its hand-written expectations. */
class CaseSpec extends AnyFunSuite with VliwChiselSim {
  private val cases = TestCase.loadAll().fold(es => fail(es.mkString("\n")), identity)

  /** The unsupported settings of a case's machine, in short form (docs/MACHINE.md). */
  private def outside(c: TestCase) =
    CoreParams.check(c.machine).left.toOption.get.map(_.takeWhile(_ != ';')).mkString(", ")

  private def canonical(v: ujson.Value): String =
    Json.canonical(v).fold(e => fail(e.message), identity)

  cases.filter(_.expect.verdict == Verdict.Legal).foreach { c =>
    if (CoreParams.check(c.machine).isLeft)
      ignore(s"${c.name}: outside the RTL's range (${outside(c)})") {}
    else
      test(s"${c.name}: cycles, final state and trace match the expectations") {
        simulate(new Core(CoreParams(c.machine))) { dut =>
          val r = CoreDriver.run(dut, c.machine, c.words, c.inputs, c.expect.memory.keySet)
          assert(r.violations.isEmpty, s"violations: ${r.violations.map(_.id)}")
          c.expect.cycles.foreach(n => assert(r.cycles == n))
          c.expect.registers.foreach { case (reg, v) =>
            assert(r.registers(reg) == BigInt(v), s"r$reg")
          }
          c.expect.memory.foreach { case (a, v) => assert(r.memory(a) == BigInt(v), s"[$a]") }
          c.expect.trace.foreach { path =>
            val golden = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).linesIterator
              .filter(_.nonEmpty)
              .map(l => canonical(ujson.read(l)))
              .toVector
            assert(r.trace.map(canonical) == golden)
          }
        }
      }
  }
}
