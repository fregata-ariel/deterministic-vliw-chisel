package vliw.hw

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

import chisel3.simulator.Exceptions.AssertionFailed
import org.scalatest.exceptions.TestFailedException
import org.scalatest.funsuite.AnyFunSuite

import vliw.isa._

/** The cases of `tests/cases` on the RTL, one Verilator build per machine (#110): [[CaseSpec]] runs
  * the legal cases and [[AssertionSpec]] the illegal ones. The cases of ISA mvp-2 (W) run in
  * `crosscheck/RtlMvp2Spec`, in the same way.
  *
  * Every `simulate` is a Verilator build (ADR-0013). The build of a machine runs its cases in turn
  * on one core, each with [[CoreDriver.run]]'s `stopAtViolation`: a program that signals a
  * violation stops in that cycle, and the driver holds reset over the clock edge that ends it, so
  * that no assertion fires and the next program's `start` resets the core again. A program that
  * signals nothing runs to done with every assertion live. Last, the build runs its firing case, an
  * illegal case of the machine with one rule the RTL checks: first with `stopAtViolation`, then
  * without, and the assertion of that rule must really fire, at the pc at which the same case
  * stopped. So the assertions of each build are checked too, not only the signals that are their
  * conditions (docs/VERIFIER.md).
  *
  * A firing case ends its build, since the simulation stops at the assertion: a build checks the
  * assertion of one rule. The two suites build the same machines, but for `small_scratchpad`
  * (illegal cases only), and their firing cases differ on the machines with more than one illegal
  * case, so that each of the two builds of such a machine fires another rule; on `mvp1_mac3`, whose
  * only illegal case is `mac3_mul_port`, both fire L5. With `crosscheck/RtlMvp2Spec`, the assertion
  * of every rule the RTL checks fires in some build, which RtlMvp2Spec tests.
  *
  * Each build registers one test, named after the machine, and then one test per case, named after
  * the case, as when every case had a build of its own (until #110 Q3). The build runs once, in the
  * first of these tests that runs (the machine's, unless a filter selects only cases). The
  * machine's test fails when the build fails, when the firing case does not fire as expected, or
  * when cases fail, with every problem in its message: the firing case's, then the names of the
  * cases that failed. Each case's test reports that case's result in the build. A failure of the
  * environment (a Verilator build, a simulation that died) is rethrown as it is, in every test it
  * stops, so that `scripts/mutation-classify.py` counts it as an error, not as a kill.
  */
abstract class CaseBuildSpec extends AnyFunSuite with VliwChiselSim {
  import CaseBuildSpec._

  /** The firing case of each machine, by name: exactly one illegal case per machine with a build,
    * with one rule the RTL checks and a program that fits the instruction memory.
    */
  protected def firing: Set[String]

  private val FiredRule = """Assertion failed: (L\d+) \([^)]*\) at pc +(\d+)""".r.unanchored

  /** The message of the first assertion in the simulation's log, e.g. `Assertion failed: L6 (...)
    * at pc 1` (also HOST).
    */
  private val Fired = """Assertion failed: [A-Z]+\d* \([^)]*\) at pc +\d+""".r

  /** The firing case of `m`, or why there is none. */
  protected def firingOf(m: MachineDescription): Either[String, TestCase] =
    cases.filter(c => c.machine.hash == m.hash && firing(c.name)) match {
      case Vector(c) if rtlRules(c).size == 1 && c.words.size <= m.instructionMemoryBundles =>
        Right(c)
      case Vector(c) =>
        Left(s"${c.name} cannot be the firing case: it needs one rule the RTL checks and to load")
      case Vector() => Left(s"no firing case for ${m.machineId}: name one of its illegal cases")
      case more     => Left(s"${more.size} firing cases for ${m.machineId}: ${more.map(_.name)}")
    }

  /** Registers the test of the build of `m` (`what` names its cases, e.g. "legal"), then the test
    * of each step.
    */
  protected def build(m: MachineDescription, what: String, steps: Seq[Step]): Unit = {
    val fire = firingOf(m)
    val fires = fire.fold(_ => "no case fires", c => s"${c.name} fires ${rtlRules(c).head.id}")
    lazy val outcome = run(m, fire.toOption, steps)
    val count = if (steps.size == 1) s"1 $what case" else s"${steps.size} $what cases"
    test(s"${label(m)}: $count in one build; $fires") {
      val o = outcome // the build runs here, unless a test of a case ran first
      val failed =
        steps.map(_.c.name).flatMap(n => o.results.get(n).flatMap(_.failed.toOption).map(n -> _))
      val casesFailed = Option.when(failed.nonEmpty)(
        s"${failed.size} of ${steps.size} cases failed: ${failed.map(_._1).mkString(", ")}"
      )
      o.stop.foreach { case (where, e) =>
        val notRun = Option.when(o.notRun.nonEmpty)(s"not run: ${o.notRun.mkString(", ")}")
        info((Seq(s"the build stopped in $where") ++ notRun ++ casesFailed).mkString("; "))
        throw e
      }
      // Every problem in one message: the firing case's, then the cases that failed.
      val problems = (fire.swap.toOption ++ o.firing ++ casesFailed).mkString("; ")
      failed.headOption match {
        case Some((first, e)) => fail(s"$problems; $first: ${e.getMessage}", e)
        case None             => if (problems.nonEmpty) fail(problems)
      }
    }
    steps.foreach { s =>
      test(s.test) {
        outcome.results.get(s.c.name) match {
          case Some(r) => r.get
          case None    =>
            outcome.stop match {
              case Some((where, _: TestFailedException)) =>
                fail(s"not run: the build stopped in $where")
              case Some((_, e)) => throw e
              case None         => fail("not run") // only with a failed build, which sets stop
            }
        }
      }
    }
  }

  private def run(m: MachineDescription, fire: Option[TestCase], steps: Seq[Step]): Outcome = {
    val results = mutable.LinkedHashMap.empty[String, Try[Unit]]
    var current = "the build" // where the build is: before the first case, a case, or the firing
    var probe = Option.empty[CoreRun] // the firing case with stopAtViolation
    var firingRun = false
    val sim = Try(simulate(new Core(CoreParams(m))) { dut =>
      steps.foreach { s =>
        current = s.c.name
        val r = Try(s.run(dut))
        results(s.c.name) = r
        // A check that failed leaves the core as it is; the next `start` resets it. Anything else
        // (a cycle limit, the simulation) stops the build.
        r.failed.toOption.filterNot(_.isInstanceOf[TestFailedException]).foreach(e => throw e)
      }
      fire.foreach { c =>
        current = s"${c.name} (the firing case)"
        probe = Some(CoreDriver.run(dut, m, c.words, c.inputs, Set.empty, stopAtViolation = true))
        firingRun = true
        CoreDriver.run(dut, m, c.words, c.inputs, Set.empty)
      }
    })
    val notRun = steps.map(_.c.name).filterNot(results.contains)
    // ChiselSim turns the simulation's stop into AssertionFailed after the body, in place of
    // whatever the body threw after the assertion (as in crosscheck/RtlMvp2Spec).
    val (stop, fired) = (sim, fire) match {
      case (Failure(e: AssertionFailed), Some(c)) if firingRun =>
        (None, firingProblem(c, probe.get, e))
      case (Failure(e: AssertionFailed), _) =>
        val fired = Fired.findFirstIn(e.getMessage).getOrElse(e.getMessage)
        val f = new TestFailedException(
          s"$current: an assertion fired, which only the firing case may do: $fired",
          e,
          0
        )
        if (results.contains(current)) results(current) = Failure(f)
        (Some(current -> f), None)
      case (Failure(e), _) =>
        if (results.get(current).exists(_.isSuccess)) results(current) = Failure(e)
        (Some(current -> e), None)
      case (Success(_), Some(c)) => (None, Some(s"${c.name}: no assertion fired"))
      case (Success(_), None)    => (None, None)
    }
    Outcome(results.toMap, notRun, stop, fired)
  }

  /** What is wrong with the assertion that the firing case `c` fired, compared with `probe`, the
    * same case stopped at its first violation; None when it fired the case's rule at that pc.
    */
  private def firingProblem(c: TestCase, probe: CoreRun, e: AssertionFailed): Option[String] = {
    val rule = rtlRules(c).head
    val stoppedPc = probe.trace.lastOption.flatMap(_("pc").numOpt).map(_.toInt)
    e.getMessage match {
      case _ if !(probe.stoppedAt.nonEmpty && probe.violations == Set(rule)) =>
        Some(
          s"${c.name}: with stopAtViolation, signalled ${probe.violations.map(_.id)} at cycle " +
            s"${probe.stoppedAt}; expected ${rule.id}"
        )
      case FiredRule(id, pc) if id == rule.id && stoppedPc.contains(pc.toInt) => None
      case FiredRule(id, pc)                                                  =>
        Some(
          s"${c.name} fired $id at pc $pc; expected ${rule.id} at pc $stoppedPc: ${e.getMessage}"
        )
      case other => Some(s"${c.name}: no rule id and pc in: $other")
    }
  }

  /** The machine in test names: its id, with its hash if another machine of the cases has the same.
    */
  private def label(m: MachineDescription): String =
    if (cases.map(_.machine).distinctBy(_.hash).count(_.machineId == m.machineId) > 1)
      s"${m.machineId} ${m.hash.take(12)}"
    else m.machineId
}

object CaseBuildSpec {

  /** The cases of ISA mvp-0 and mvp-1, sorted by name; the cases of mvp-2 run in
    * `crosscheck/RtlMvp2Spec`.
    */
  lazy val cases: Vector[TestCase] = TestCase
    .loadAll()
    .fold(es => throw new IllegalStateException(es.mkString("\n")), identity)
    .filter(_.machine.isaVersion != IsaVersion.Mvp2)

  def rulesOf(c: TestCase): Set[Rule] = c.expect.verdict match {
    case Verdict.Illegal(rules) => rules
    case Verdict.Legal          => Set.empty
  }

  /** The rules of a case that the RTL checks: L8 (undefined values) is outside the RTL. */
  def rtlRules(c: TestCase): Set[Rule] = rulesOf(c) - Rule.UndefinedRead

  /** Why the RTL cannot run a case's machine, in short form (docs/MACHINE.md); None when it can. */
  def outside(c: TestCase): Option[String] =
    CoreParams.check(c.machine).left.toOption.map(_.map(_.takeWhile(_ != ';')).mkString(", "))

  /** `cs` by machine, in the order of each machine's first case. */
  def byMachine(cs: Seq[TestCase]): Seq[(MachineDescription, Seq[TestCase])] =
    cs.map(_.machine).distinctBy(_.hash).map(m => m -> cs.filter(_.machine.hash == m.hash))

  /** A case in a build: the name of its test, and what it runs on the core and checks. The run must
    * use `stopAtViolation` or leave no violation, so that no assertion fires.
    */
  final case class Step(c: TestCase, test: String, run: Core => Unit)

  /** What a build did: the result of each step it ran, by case name; the steps it did not reach;
    * where and why it stopped before its end, if it did; and what went wrong with its firing case.
    */
  private final case class Outcome(
      results: Map[String, Try[Unit]],
      notRun: Seq[String],
      stop: Option[(String, Throwable)],
      firing: Option[String]
  )
}
