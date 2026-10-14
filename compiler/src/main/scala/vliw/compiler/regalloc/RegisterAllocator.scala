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

/** How a schedule was obtained: the priority, and the register limit of the scheduler if any. */
final case class Attempt(priority: Priority, limit: Option[ListScheduler.Limit]) {
  override def toString: String =
    priority.name + limit.fold(", no register limit")(l =>
      s", ${l.registers} registers, load reserve ${l.loadReserve}"
    )
}

final case class Allocated(attempt: Attempt, allocation: Allocation) {
  def schedule: Schedule = allocation.schedule
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
  def attempts(dag: Dag, priorities: Seq[Priority]): Seq[Attempt] = {
    val r = dag.machine.registerCount
    priorities.map(Attempt(_, None)) ++
      (for (p <- priorities; reserve <- 0 to r / 2)
        yield Attempt(p, Some(ListScheduler.Limit(r, reserve))))
  }

  /** Schedules and allocates, rescheduling under register limits when the registers do not suffice
    * (no spilling). Of the attempts that succeed, the one with the fewest cycles is chosen; ties go
    * to the earlier attempt.
    */
  def scheduleAndAllocate(
      dag: Dag,
      priorities: Seq[Priority] = Priority.all
  ): Either[String, Allocated] = {
    val done = attempts(dag, priorities).flatMap { a =>
      ListScheduler
        .run(dag, a.priority, a.limit)
        .flatMap(s => allocate(s).toOption)
        .map(Allocated(a, _))
    }
    done
      .minByOption(_.schedule.cycles)
      .toRight(
        s"no schedule fits ${dag.machine.registerCount} registers (tried ${attempts(dag, priorities).size} attempts; spilling is not supported)"
      )
  }
}
