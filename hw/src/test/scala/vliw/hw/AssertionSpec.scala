package vliw.hw

import chisel3.simulator.Exceptions.AssertionFailed
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

import vliw.isa._

/** Every illegal case in tests/cases must stop the RTL with an assertion of an expected rule. L8
  * (undefined values) is outside the RTL: the hardware holds no definedness state.
  */
class AssertionSpec extends AnyFunSuite with ChiselSim {
  private val FiredRule = """Assertion failed: (L\d+) """.r.unanchored

  private val cases = TestCase.loadAll().fold(es => fail(es.mkString("\n")), identity)

  /** The unsupported settings of a case's machine, in short form (docs/MACHINE.md). */
  private def outside(c: TestCase) =
    CoreParams.check(c.machine).left.toOption.get.map(_.takeWhile(_ != ';')).mkString(", ")
  private val illegal = cases.collect {
    case c @ TestCase(_, _, _, _, _, _, Expectation(Verdict.Illegal(r), _, _, _, _)) =>
      (c, r)
  }

  private def supported(c: TestCase) = CoreParams.check(c.machine).isRight

  test("illegal cases exist for every rule the RTL checks") {
    val covered = illegal.filter(x => supported(x._1)).flatMap(_._2).toSet
    // L9 needs an II above 1 and L10 a store latency above 1: neither occurs on a machine the RTL
    // implements (CoreParams).
    val unreachable = Set[Rule](Rule.UndefinedRead, Rule.IssueInterval, Rule.InFlightStoreRead)
    assert((Rule.all.toSet -- unreachable).subsetOf(covered), covered.map(_.id))
  }

  illegal.foreach { case (c, rules) =>
    val rtlRules = rules - Rule.UndefinedRead
    if (!supported(c)) {
      ignore(s"${c.name}: outside the RTL's range (${outside(c)})") {}
    } else if (rtlRules.isEmpty) {
      test(s"${c.name}: L8 only, so the RTL runs to completion without an assertion") {
        simulate(new Core(CoreParams(c.machine))) { dut =>
          CoreDriver.run(dut, c.machine, c.words, c.inputs, Set.empty)
        }
      }
    } else if (c.words.size > c.machine.instructionMemoryBundles) {
      test(s"${c.name}: rejected when loading (L2)") {
        simulate(new Core(CoreParams(c.machine))) { dut =>
          val e =
            intercept[LoadRejected](CoreDriver.run(dut, c.machine, c.words, c.inputs, Set.empty))
          assert(e.getMessage.startsWith("L2"))
          assert(rtlRules.contains(Rule.ProgramShape))
        }
      }
    } else {
      test(
        s"${c.name}: an assertion of ${rtlRules.map(_.id).toSeq.sorted.mkString(" or ")} fires"
      ) {
        val e = intercept[AssertionFailed] {
          simulate(new Core(CoreParams(c.machine))) { dut =>
            CoreDriver.run(dut, c.machine, c.words, c.inputs, Set.empty)
          }
        }
        e.getMessage match {
          case FiredRule(id) =>
            assert(rtlRules.map(_.id).contains(id), s"fired $id: ${e.getMessage}")
          case _ => fail(s"no rule id in: ${e.getMessage}")
        }
      }
    }
  }
}
