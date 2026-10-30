package vliw.hw

import vliw.isa._

/** Every illegal case in tests/cases must stop the RTL with a violation of an expected rule. L8
  * (undefined values) is outside the RTL: the hardware holds no definedness state.
  *
  * One build per machine (#110 Q3, [[CaseBuildSpec]]): the illegal cases of the machine in turn,
  * each stopped in the first cycle in which the core signals a violation (the condition of an
  * assertion), whose rules must be some of the case's; then the machine's firing case again, whose
  * assertion must really fire with its rule, at the pc at which it stopped. The firing cases differ
  * from those of [[CaseSpec]] but on `mvp1_mac3`, whose only illegal case is `mac3_mul_port`
  * ([[CaseBuildSpec]]).
  *
  * The cases of ISA mvp-2 (W) run in `crosscheck/RtlMvp2Spec`, in the same way.
  */
class AssertionSpec extends CaseBuildSpec {
  import CaseBuildSpec._

  protected val firing: Set[String] = AssertionSpec.firing

  private val illegal = cases.filter(rulesOf(_).nonEmpty)

  test("illegal cases exist for every rule the RTL checks") {
    val covered = illegal.filter(outside(_).isEmpty).flatMap(rulesOf).toSet
    // L9 needs an II above 1 and L10 a store latency above 1: neither occurs on a machine the RTL
    // implements (CoreParams).
    val unreachable = Set[Rule](Rule.UndefinedRead, Rule.IssueInterval, Rule.InFlightStoreRead)
    assert((Rule.all.toSet -- unreachable).subsetOf(covered), covered.map(_.id))
  }

  /** The test of `c` in the build of its machine: its name and its run. */
  private def step(c: TestCase): Step = {
    val rules = rtlRules(c)
    def run(dut: Core) =
      CoreDriver.run(dut, c.machine, c.words, c.inputs, Set.empty, stopAtViolation = true)
    if (rules.isEmpty)
      Step(
        c,
        s"${c.name}: L8 only, so the RTL runs to completion without an assertion",
        dut => {
          val r = run(dut)
          assert(
            r.stoppedAt.isEmpty && r.violations.isEmpty,
            s"${c.name}: signalled ${r.violations.map(_.id)} at cycle ${r.stoppedAt}"
          )
        }
      )
    else if (c.words.size > c.machine.instructionMemoryBundles)
      Step(
        c,
        s"${c.name}: rejected when loading (L2)",
        dut => {
          val e = intercept[LoadRejected](run(dut))
          assert(e.getMessage.startsWith("L2"), s"${c.name}: ${e.getMessage}")
          assert(rules.contains(Rule.ProgramShape), s"${c.name}: ${rules.map(_.id)}")
        }
      )
    else
      Step(
        c,
        s"${c.name}: the core signals ${rules.map(_.id).toSeq.sorted.mkString(" or ")} in its first violating cycle",
        dut => {
          val r = run(dut)
          assert(
            r.stoppedAt.nonEmpty && r.violations.nonEmpty && r.violations.subsetOf(rules),
            s"${c.name}: signalled ${r.violations.map(_.id)} at cycle ${r.stoppedAt}, " +
              s"expected some of ${rules.map(_.id)}"
          )
        }
      )
  }

  byMachine(illegal.filter(outside(_).isEmpty)).foreach { case (m, cs) =>
    build(m, "illegal", cs.map(step))
  }

  illegal.foreach { c =>
    outside(c).foreach(why => ignore(s"${c.name}: outside the RTL's range ($why)") {})
  }
}

object AssertionSpec {

  /** The firing case of each machine of the illegal cases (L2, L7, L5 and L3). */
  val firing: Set[String] =
    Set("halt_not_last", "address_out_of_range", "mac3_mul_port", "mac_rs3_in_flight")
}
