package vliw.compiler.regalloc

import vliw.compiler.dag.{Dag, EdgeKind, Node, Src}
import vliw.compiler.dsl.Op
import vliw.compiler.schedule.{ListScheduler, Priority, Schedule}
import vliw.isa.{Bundle, ComputeOp, MemoryOp}

/** The register of value `value` is occupied over `[start, end)`: from the issue of the operation
  * that writes it to the issue of its last reader (docs/COMPILER.md, "register allocation").
  */
final case class Interval(value: Int, start: Int, end: Int)

/** The registers of register group `group` (`Dag.groups`: a value, a pair, or a gather) are
  * occupied over `[start, end)`: from the issue of its first definer (`first`, the lower id when
  * two issue together) to the issue of its last reader. `weight` registers: 1 for a scalar, 2 for a
  * pair or a gather (ISA mvp-2, docs/COMPILER.md "ISA mvp-2（W）"). On a DAG without pairs, the
  * groups are the [[Interval]]s, with weight 1.
  */
final case class GroupInterval(group: Int, first: Int, start: Int, end: Int, weight: Int)

/** Physical register of every value (every node but stores) of a schedule. A pair node has the even
  * register p of its aligned pair; a lane node of a gather has p + lane.
  */
final case class Allocation(schedule: Schedule, register: Map[Int, Int]) {

  /** The program: one bundle per cycle, HALT last. */
  def bundles: Vector[Bundle] = {
    val dag = schedule.dag
    // The even register of a pair field; a gather's pair is that of its lanes.
    def pair(s: Src): Int = s match {
      case Src.Pair(n)       => register(n)
      case Src.Gather(lo, _) => register(lo) & ~1
      case Src.Broadcast(n)  => sys.error(s"a broadcast of node $n is not a pair field")
    }
    // The rs1 of VMUL and VMAC: one register when it is a broadcast (bit B), else a pair.
    def rs1(s: Src): (Int, Boolean) = s match {
      case Src.Broadcast(n) => (register(n), true)
      case other            => (pair(other), false)
    }
    def compute(i: Int): ComputeOp = dag.nodes(i) match {
      case Node.Compute(Op.Add, a, b) => ComputeOp.Add(register(i), register(a), register(b))
      case Node.Compute(Op.Sub, a, b) => ComputeOp.Sub(register(i), register(a), register(b))
      case Node.Compute(Op.Mul, a, b) => ComputeOp.Mul(register(i), register(a), register(b))
      case Node.Mac(a, b, c) => ComputeOp.Mac(register(i), register(a), register(b), register(c))
      case Node.VCompute(Op.Add, a, b) => ComputeOp.VAdd(register(i), pair(a), pair(b))
      case Node.VCompute(Op.Sub, a, b) => ComputeOp.VSub(register(i), pair(a), pair(b))
      case Node.VCompute(Op.Mul, a, b) =>
        val (x, broadcast) = rs1(a)
        ComputeOp.VMul(register(i), x, pair(b), broadcast)
      case Node.VMac(a, b, c) =>
        val (x, broadcast) = rs1(a)
        ComputeOp.VMac(register(i), x, pair(b), pair(c), broadcast)
      case other => sys.error(s"not a compute node: $other")
    }
    def memory(i: Int): MemoryOp = dag.nodes(i) match {
      case Node.Load(addr)               => MemoryOp.Ld(register(i), addr)
      case Node.Store(addr, v)           => MemoryOp.St(addr, register(v))
      case Node.LoadW(addr)              => MemoryOp.LdW(register(i), addr)
      case Node.StoreW(addr, v)          => MemoryOp.StW(addr, pair(v))
      case Node.StoreLane(addr, v, lane) => MemoryOp.St(addr, register(v) + lane)
      case other                         => sys.error(s"not a memory node: $other")
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
sealed trait NoFit {

  /** The name of the reason, as the records and the fallback of ISA mvp-2 give it. */
  def name: String
}
object NoFit {

  /** The schedule keeps more values live at once than there are registers (only without a limit: a
    * schedule under the limit never does). A pair and a gather count 2 (ISA mvp-2).
    */
  final case class TooManyLive(live: Int, registers: Int) extends NoFit {
    def name: String = "TooManyLive"
    override def toString: String =
      s"$live values are live at once; the machine has $registers registers"
  }

  /** Under the register limit, the scheduler reaches a point where no remaining operation fits, and
    * no copy of a LD (rematerialization, when it is on) gets it further.
    */
  case object Stuck extends NoFit {
    def name: String = "Stuck"
    override def toString: String = "no remaining operation fits under the register limit"
  }

  /** ISA mvp-2 (ADR-0023): the registers live at `cycle` number `live`, no more than the machine
    * has, but no aligned pair is free for the pair or gather that starts there (fragmentation). It
    * happens only on a DAG with pairs, with or without a register limit.
    */
  final case class NoAlignedPair(cycle: Int, live: Int, registers: Int) extends NoFit {
    def name: String = "NoAlignedPair"
    override def toString: String =
      s"no aligned register pair is free at cycle $cycle ($live of $registers registers live)"
  }
}

final case class Allocated(attempt: Attempt, allocation: Allocation) {
  def schedule: Schedule = allocation.schedule

  /** The DAG scheduled: the kernel's, or with the copies of LDs when the attempt rematerialized. */
  def dag: Dag = schedule.dag
}

object RegisterAllocator {

  /** The interval of every value (node but store). On a DAG with pairs, see [[groups]]. */
  def intervals(s: Schedule): Vector[Interval] = {
    val dag = s.dag
    dag.nodes.indices.collect {
      case v if !dag.nodes(v).isStore =>
        val readers = dag.successors(v).filter(_.kind == EdgeKind.Data).map(e => s.issue(e.to))
        Interval(v, s.issue(v), readers.maxOption.getOrElse(s.issue(v) + dag.latency(v)))
    }.toVector
  }

  /** The interval of every register group of the schedule's DAG, in group order. */
  def groups(s: Schedule): Vector[GroupInterval] = {
    val dag = s.dag
    dag.groups.indices.map { g =>
      val definers = dag.groups(g).definers
      val first = definers.minBy(n => (s.issue(n), n))
      val start = s.issue(first)
      val end =
        dag.groupReaders(g).map(s.issue).maxOption.getOrElse(start + dag.latency(first))
      GroupInterval(g, first, start, end, dag.groups(g).weight)
    }.toVector
  }

  /** The most registers live in one cycle (a pair and a gather count 2); at least this many
    * registers are needed. On a DAG without pairs, the most values live in one cycle.
    */
  def maxLive(s: Schedule): Int = {
    val gs = groups(s)
    (0 to s.halt).map(c => liveAt(gs, c)).maxOption.getOrElse(0)
  }

  private def liveAt(gs: Seq[GroupInterval], c: Int) =
    gs.filter(g => g.start <= c && c < g.end).map(_.weight).sum

  /** Assigns registers in order of interval start (then of the group's first definer). A pair or a
    * gather gets the lowest aligned pair whose two registers are free; a gather's `lo` gets r(p)
    * and `hi` r(p+1). A scalar gets the lowest free register on a DAG without pairs, as on ISA
    * mvp-0 and mvp-1; on a DAG with pairs, the lowest free register whose partner (r xor 1) is in
    * use (best fit, ADR-0023), else the lowest free one. A register is free for a value from the
    * issue of the last reader of its previous value: the reader sees the old value (L3), and the
    * old write interval has ended by then (L4). Left gives why the schedule does not fit: too many
    * registers live at once ([[NoFit.TooManyLive]]), or no aligned pair free
    * ([[NoFit.NoAlignedPair]]).
    */
  def assign(s: Schedule): Either[NoFit, Allocation] = {
    val dag = s.dag
    val registers = dag.machine.registerCount
    val freeFrom = Array.fill(registers)(0)
    val gs = groups(s)
    val withPairs = gs.exists(_.weight == 2)
    def free(r: Int, at: Int) = freeFrom(r) <= at
    gs.sortBy(g => (g.start, g.first))
      .foldLeft[Either[NoFit, Map[Int, Int]]](Right(Map.empty)) { (acc, iv) =>
        acc.flatMap { m =>
          val definers = dag.groups(iv.group).definers
          if (iv.weight == 2)
            (0 until registers - 1 by 2)
              .find(p => free(p, iv.start) && free(p + 1, iv.start)) match {
              case Some(p) =>
                freeFrom(p) = iv.end
                freeFrom(p + 1) = iv.end
                // One pair node at p, or the two lanes of a gather at p and p + 1.
                if (definers.size == 1) Right(m.updated(definers.head, p))
                else
                  Right(definers.zipWithIndex.foldLeft(m) { case (m, (n, lane)) =>
                    m.updated(n, p + lane)
                  })
              case None =>
                val live = maxLive(s)
                Left(
                  if (live > registers) NoFit.TooManyLive(live, registers)
                  else NoFit.NoAlignedPair(iv.start, liveAt(gs, iv.start), registers)
                )
            }
          else {
            val open = freeFrom.indices.filter(free(_, iv.start))
            val bestFit =
              if (withPairs) open.find(r => (r ^ 1) < registers && !free(r ^ 1, iv.start))
              else None
            bestFit.orElse(open.headOption) match {
              case Some(r) => freeFrom(r) = iv.end; Right(m.updated(definers.head, r))
              case None    => Left(NoFit.TooManyLive(maxLive(s), registers))
            }
          }
        }
      }
      .map(Allocation(s, _))
  }

  /** [[assign]], with the reason as text. On a DAG without pairs, it fails only when more values
    * are live at once than there are registers.
    */
  def allocate(s: Schedule): Either[String, Allocation] = assign(s).left.map(_.toString)

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
      .flatMap(assign)
      .map(Allocated(a, _))

  /** Every attempt of [[attempts]] with its result, in order (the fallback and the records of ISA
    * mvp-2 name each attempt's failure).
    */
  def allAttempts(
      dag: Dag,
      priorities: Seq[Priority] = Priority.all,
      singleIssue: Boolean = false,
      remat: Remat = Remat.WhenStuck
  ): Vector[(Attempt, Either[NoFit, Allocated])] =
    attempts(dag, priorities, singleIssue).map(a => a -> run(dag, a, remat)).toVector

  /** Of the attempts that succeed, the one with the fewest cycles, then the one with the fewest LDs
    * (so that a copy never replaces an equal schedule without it), then the earlier attempt.
    */
  def best(tried: Seq[(Attempt, Either[NoFit, Allocated])]): Option[Allocated] =
    tried.flatMap(_._2.toOption).minByOption(a => (a.schedule.cycles, a.dag.loads))

  /** Schedules and allocates, rescheduling under register limits when the registers do not suffice
    * (no spilling; a limited attempt that gets stuck issues LDs of inputs, scalars and constants
    * again). The attempt chosen is [[best]].
    */
  def scheduleAndAllocate(
      dag: Dag,
      priorities: Seq[Priority] = Priority.all,
      singleIssue: Boolean = false,
      remat: Remat = Remat.WhenStuck
  ): Either[String, Allocated] =
    best(allAttempts(dag, priorities, singleIssue, remat))
      .toRight(
        s"no schedule fits ${dag.machine.registerCount} registers (tried ${attempts(dag, priorities, singleIssue).size} attempts${Rematerialization.describe(remat)}; spilling is not supported)"
      )
}
