package vliw.compiler.regalloc

import vliw.compiler.dag.{Dag, EdgeKind, Node}
import vliw.compiler.dsl.Op
import vliw.compiler.schedule.{ListScheduler, Priority, Schedule}
import vliw.isa.{Bundle, ComputeOp, MemoryOp}

/** The register of value `value` is occupied over `[start, end)`: from the issue of the operation
  * that writes it to the issue of its last reader (docs/COMPILER.md, "register allocation").
  */
final case class Interval(value: Int, start: Int, end: Int)

/** Physical register of every value (every node but stores) of a schedule. */
final case class Allocation(schedule: Schedule, register: Map[Int, Int]) {

  /** The program: one bundle per cycle, HALT last. */
  def bundles: Vector[Bundle] = {
    val dag = schedule.dag
    def compute(i: Int): ComputeOp = dag.nodes(i) match {
      case Node.Compute(Op.Add, a, b) => ComputeOp.Add(register(i), register(a), register(b))
      case Node.Compute(Op.Sub, a, b) => ComputeOp.Sub(register(i), register(a), register(b))
      case Node.Compute(Op.Mul, a, b) => ComputeOp.Mul(register(i), register(a), register(b))
      case other                      => sys.error(s"not a compute node: $other")
    }
    def memory(i: Int): MemoryOp = dag.nodes(i) match {
      case Node.Load(addr)     => MemoryOp.Ld(register(i), addr)
      case Node.Store(addr, v) => MemoryOp.St(addr, register(v))
      case other               => sys.error(s"not a memory node: $other")
    }
    schedule.bundles.map(b =>
      Bundle(
        b.compute.fold[ComputeOp](ComputeOp.Nop)(compute),
        b.memory.fold[MemoryOp](MemoryOp.Nop)(memory)
      )
    ) :+ Bundle(ComputeOp.Halt)
  }
}

/** How a schedule was obtained: the priority, the register limit of the scheduler if any, and
  * whether it issues one operation per bundle (the single-issue baseline).
  */
final case class Attempt(
    priority: Priority,
    limit: Option[ListScheduler.Limit],
    singleIssue: Boolean = false
) {
  override def toString: String =
    priority.name + limit.fold(", no register limit")(l =>
      s", ${l.registers} registers, load reserve ${l.loadReserve}"
    ) + (if (singleIssue) ", single issue" else "")
}

/** Why one attempt does not fit the machine (no spilling). */
sealed trait NoFit
object NoFit {

  /** The schedule keeps more values live at once than there are registers (only without a limit: a
    * schedule under the limit never does).
    */
  final case class TooManyLive(live: Int, registers: Int) extends NoFit {
    override def toString: String =
      s"$live values are live at once; the machine has $registers registers"
  }

  /** Under the register limit, the scheduler reaches a point where no remaining operation fits, and
    * no copy of a LD (rematerialization) gets it further.
    */
  case object Stuck extends NoFit {
    override def toString: String =
      "no remaining operation fits under the register limit, even with LDs issued again"
  }
}

final case class Allocated(attempt: Attempt, allocation: Allocation) {
  def schedule: Schedule = allocation.schedule

  /** The DAG scheduled: the kernel's, or with the copies of LDs when the attempt rematerialized. */
  def dag: Dag = schedule.dag
}

object RegisterAllocator {

  def intervals(s: Schedule): Vector[Interval] = {
    val dag = s.dag
    dag.nodes.indices.collect {
      case v if !dag.nodes(v).isInstanceOf[Node.Store] =>
        val readers = dag.successors(v).filter(_.kind == EdgeKind.Data).map(e => s.issue(e.to))
        Interval(v, s.issue(v), readers.maxOption.getOrElse(s.issue(v) + dag.latency(v)))
    }.toVector
  }

  /** The most values live in one cycle; at least this many registers are needed. */
  def maxLive(s: Schedule): Int = {
    val iv = intervals(s)
    (0 to s.halt).map(c => iv.count(i => i.start <= c && c < i.end)).maxOption.getOrElse(0)
  }

  /** Assigns registers in order of interval start, each to the lowest register that is free. A
    * register is free for a value from the issue of the last reader of its previous value: the
    * reader sees the old value (L3), and the old write interval has ended by then (L4).
    */
  def allocate(s: Schedule): Either[String, Allocation] = {
    val registers = s.dag.machine.registerCount
    val freeFrom = Array.fill(registers)(0)
    val sorted = intervals(s).sortBy(i => (i.start, i.value))
    sorted
      .foldLeft[Either[String, Map[Int, Int]]](Right(Map.empty)) { (acc, iv) =>
        acc.flatMap { m =>
          freeFrom.indices.find(r => freeFrom(r) <= iv.start) match {
            case Some(r) => freeFrom(r) = iv.end; Right(m.updated(iv.value, r))
            case None    =>
              Left(s"${maxLive(s)} values are live at once; the machine has $registers registers")
          }
        }
      }
      .map(Allocation(s, _))
  }

  /** Every attempt, in order: each priority without a register limit, then with the machine's
    * registers as the limit and a growing load reserve.
    */
  def attempts(dag: Dag, priorities: Seq[Priority], singleIssue: Boolean = false): Seq[Attempt] = {
    val r = dag.machine.registerCount
    priorities.map(Attempt(_, None, singleIssue)) ++
      (for (p <- priorities; reserve <- 0 to r / 2)
        yield Attempt(p, Some(ListScheduler.Limit(r, reserve)), singleIssue))
  }

  /** One attempt: schedules with its priority, register limit and issue mode, then allocates. A
    * limited attempt that gets stuck issues some LDs again (`Rematerialization`, `remat`). Left
    * gives why the attempt does not fit the machine.
    */
  def run(dag: Dag, a: Attempt, remat: Remat = Remat.WhenStuck): Either[NoFit, Allocated] =
    Rematerialization
      .schedule(dag, a, remat)
      .flatMap(s =>
        allocate(s).left.map(_ => NoFit.TooManyLive(maxLive(s), dag.machine.registerCount))
      )
      .map(Allocated(a, _))

  /** Schedules and allocates, rescheduling under register limits when the registers do not suffice
    * (no spilling; a limited attempt that gets stuck issues LDs of inputs, scalars and constants
    * again). Of the attempts that succeed, the one with the fewest cycles is chosen, then the one
    * with the fewest LDs (so that a copy never replaces an equal schedule without it), then the
    * earlier attempt.
    */
  def scheduleAndAllocate(
      dag: Dag,
      priorities: Seq[Priority] = Priority.all,
      singleIssue: Boolean = false,
      remat: Remat = Remat.WhenStuck
  ): Either[String, Allocated] = {
    val done = attempts(dag, priorities, singleIssue).flatMap(run(dag, _, remat).toOption)
    done
      .minByOption(a => (a.schedule.cycles, a.dag.loads))
      .toRight(
        s"no schedule fits ${dag.machine.registerCount} registers (tried ${attempts(dag, priorities, singleIssue).size} attempts, issuing LDs again when stuck; spilling is not supported)"
      )
  }
}
