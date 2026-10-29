package vliw.hw

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import vliw.isa._

/** Every legal case in tests/cases, run on the RTL, against its hand-written expectations: no
  * violation, the cycles, the final state and the golden trace.
  *
  * One build per machine (#110 Q3, [[CaseBuildSpec]]): the legal cases of the machine in turn, then
  * one illegal case of the machine whose assertion must really fire, so that a legal case that
  * signals nothing is known to have run with live assertions. The firing cases differ from those of
  * [[AssertionSpec]], so that the two suites fire the assertions of different rules (with
  * `crosscheck/RtlMvp2Spec`, every rule the RTL checks fires in some build).
  *
  * The cases of ISA mvp-2 (W) run in `crosscheck/RtlMvp2Spec`, in the same way.
  */
class CaseSpec extends CaseBuildSpec {
  import CaseBuildSpec._

  protected val firing: Set[String] = Set("early_halt_mul", "mac3_mul_port", "mac_overlapping_ld")

  private def canonical(v: ujson.Value): String =
    Json.canonical(v).fold(e => fail(e.message), identity)

  /** Runs `c` and checks it against its expectations. */
  private def check(c: TestCase)(dut: Core): Unit = {
    val r = CoreDriver.run(
      dut,
      c.machine,
      c.words,
      c.inputs,
      c.expect.memory.keySet,
      stopAtViolation = true
    )
    assert(
      r.violations.isEmpty,
      s"${c.name}: violations ${r.violations.map(_.id)} at cycle ${r.stoppedAt.getOrElse("?")}"
    )
    c.expect.cycles.foreach(n => assert(r.cycles == n, s"${c.name}: cycles"))
    c.expect.registers.foreach { case (reg, v) =>
      assert(r.registers(reg) == BigInt(v), s"${c.name}: r$reg")
    }
    c.expect.memory.foreach { case (a, v) =>
      assert(r.memory(a) == BigInt(v), s"${c.name}: [$a]")
    }
    c.expect.trace.foreach { path =>
      val golden = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).linesIterator
        .filter(_.nonEmpty)
        .map(l => canonical(ujson.read(l)))
        .toVector
      assert(r.trace.map(canonical) == golden, s"${c.name}: golden trace")
    }
  }

  private val legal = cases.filter(_.expect.verdict == Verdict.Legal)

  byMachine(legal.filter(outside(_).isEmpty)).foreach { case (m, cs) =>
    build(
      m,
      "legal",
      cs.map(c =>
        Step(c, s"${c.name}: cycles, final state and trace match the expectations", check(c))
      )
    )
  }

  legal.foreach { c =>
    outside(c).foreach(why => ignore(s"${c.name}: outside the RTL's range ($why)") {})
  }
}
