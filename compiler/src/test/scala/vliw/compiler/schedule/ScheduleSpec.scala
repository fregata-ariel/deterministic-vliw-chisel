package vliw.compiler.schedule

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.dag.{Dag, Node}
import vliw.compiler.dsl._
import vliw.compiler.dsl.LoweredKernel.{Bin, Read, Write}
import vliw.isa._

class ScheduleSpec extends AnyFunSuite {
  private def machineFile(name: String) =
    MachineDescription
      .load(java.nio.file.Paths.get("tests", "machines", name))
      .fold(es => fail(es.mkString("\n")), identity)
  private val machine = MachineDescription.load().fold(es => fail(es.mkString("\n")), identity)
  private val machines =
    Seq(machine, machineFile("slow_units.json"), machineFile("ii_units.json"))

  private def dagOf(k: Kernel, m: MachineDescription) =
    Dag.of(Lowering.lower(MemoryLayout.fixedOrder(k, m).fold(e => fail(e), identity)), m)

  /** Every constraint a schedule must meet, checked independently of the scheduler. */
  private def violations(s: Schedule): Seq[String] = {
    val d = s.dag
    def isCompute(i: Int) = d.nodes(i).isInstanceOf[Node.Compute]
    val all = d.nodes.indices
    val edges = d.edges.collect {
      case e if s.issue(e.to) < s.issue(e.from) + e.distance =>
        s"edge $e: ${s.issue(e.from)} -> ${s.issue(e.to)}"
    }
    val slots = all.groupBy(i => (s.issue(i), isCompute(i))).collect {
      case ((c, comp), ns) if ns.size > 1 =>
        s"cycle $c: ${ns.size} ${if (comp) "compute" else "memory"} operations"
    }
    val portUsers = all.collect {
      case i if !d.nodes(i).isInstanceOf[Node.Store] =>
        (isCompute(i), s.issue(i) + d.latency(i)) -> i
    }
    val ports = portUsers.groupBy(_._1).collect {
      case ((comp, e), us) if us.size > 1 =>
        s"E_$e: ${us.size} commits on the ${if (comp) "compute" else "memory"} port"
    }
    val ii = all.groupBy(i => d.nodes(i).mnemonic).toSeq.flatMap { case (m, ns) =>
      ns.map(s.issue).sorted.sliding(2).collect {
        case Seq(a, b) if b - a < d.machine.operation(m).ii =>
          s"$m issued at $a and $b (II ${d.machine.operation(m).ii})"
      }
    }
    val halt =
      (if (all.exists(i => s.issue(i) + d.latency(i) > s.halt))
         Seq("an operation completes after HALT")
       else Nil) ++
        (if (all.nonEmpty && s.halt != all.map(i => s.issue(i) + d.latency(i)).max)
           Seq("HALT is later than needed")
         else Nil) ++
        (if (all.exists(i => s.issue(i) < 0 || s.issue(i) >= s.halt))
           Seq("an operation is not before HALT")
         else Nil)
    edges ++ slots ++ ports ++ ii ++ halt
  }

  test("the ISA example chain is scheduled in 7 cycles, as in docs/ISA.md") {
    val d =
      Dag.build(machine, Vector(Write(10, Bin(Op.Add, Bin(Op.Mul, Read(0), Read(1)), Read(2)))))
    val s = ListScheduler.schedule(d)
    assert(violations(s).isEmpty, violations(s))
    // LD [0] 0, LD [1] 1, LD [2] 2, MUL 3, ADD 5, ST 6, HALT 7: 8 cycles, one more than the ISA
    // example, which loads only two words. The DAG bound is 7; one memory slot costs one cycle.
    assert(s.issue == Vector(0, 1, 3, 2, 5, 6) && s.cycles == 8)
    val two =
      Dag.build(machine, Vector(Write(1, Bin(Op.Add, Bin(Op.Mul, Read(0), Read(0)), Read(0)))))
    assert(ListScheduler.schedule(two).cycles == two.cyclesLowerBound) // one load: 7 cycles
    assert(two.cyclesLowerBound == 7)
  }

  test("write-back ports are reserved for future boundaries (L5)") {
    // MUL at s commits at E_(s+2); an independent ADD must not issue at s+1.
    val d = Dag.build(
      machine,
      Vector(Write(5, Bin(Op.Mul, Read(0), Read(1))), Write(6, Bin(Op.Add, Read(2), Read(3))))
    )
    val s = ListScheduler.schedule(d)
    assert(violations(s).isEmpty, violations(s))
    val mul = d.nodes.indexWhere { case Node.Compute(Op.Mul, _, _) => true; case _ => false }
    val add = d.nodes.indexWhere { case Node.Compute(Op.Add, _, _) => true; case _ => false }
    assert(s.issue(add) + 1 != s.issue(mul) + 2)
  }

  test("the II of each operation is respected (L9)") {
    val ii = machineFile("ii_units.json") // MUL II 2
    val d =
      Dag.build(ii, (0 until 4).map(i => Write(10 + i, Bin(Op.Mul, Read(0), Read(i + 1)))).toVector)
    val s = ListScheduler.schedule(d)
    assert(violations(s).isEmpty, violations(s))
    val muls = d.nodes.indices.filter(i => d.nodes(i).mnemonic == "MUL").map(s.issue).sorted
    assert(muls.sliding(2).forall { case Seq(a, b) => b - a >= 2; case _ => true })
  }

  test("benchmark schedules meet every constraint and both lower bounds, on three machines") {
    for (m <- machines; k <- Benchmarks.all; p <- Priority.all) {
      val d = dagOf(k, m)
      val s = ListScheduler.schedule(d, p)
      val label = s"${k.name} on ${m.machineId} with ${p.name}"
      assert(violations(s).isEmpty, s"$label: ${violations(s)}")
      assert(s.cycles >= d.cyclesLowerBound && s.cycles >= d.resourceLowerBound, label)
    }
  }

  test("cycles of the benchmarks on the default machine") {
    def cycles(p: Priority) =
      Benchmarks.all.map(k => k.name -> ListScheduler.schedule(dagOf(k, machine), p).cycles).toMap
    // saxpy8 meets the slot bound (26). fir4x8 and matmul3 are compute-bound (bounds 57 and 46);
    // the gap is the start (two loads before the first MUL: 3 cycles), the end (ST, then HALT) and
    // up to two compute-slot holes where an ADD would share a write-back boundary with a MUL (L5).
    // Recorded here so that scheduler changes show up (docs/COMPILER.md).
    assert(cycles(Priority.CriticalPath) == Map("saxpy8" -> 26, "fir4x8" -> 63, "matmul3" -> 52))
    assert(cycles(Priority.ProgramOrder) == Map("saxpy8" -> 28, "fir4x8" -> 63, "matmul3" -> 53))
  }
}
