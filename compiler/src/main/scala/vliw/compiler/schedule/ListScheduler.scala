package vliw.compiler.schedule

import scala.collection.mutable

import vliw.compiler.dag.{Dag, EdgeKind, Node}
import vliw.isa.Slot

/** Operations issued in one bundle, as DAG node ids. Values are still virtual registers: the result
  * of node n is virtual register n.
  */
final case class Slots(compute: Option[Int], memory: Option[Int])

/** A schedule of a DAG (docs/COMPILER.md, "list scheduling"): the issue cycle of every node, and
  * HALT at `halt`, the boundary at which the last operation completes. `cycles = halt + 1`.
  *
  * List scheduling also counts the cycles in which one constraint alone held back a ready node, for
  * the records (other schedules leave the counts 0). `heldByReadPorts`: a ready ST met every other
  * constraint, but the reads of the bundle would exceed the read ports (L11). `heldByWritePort`: a
  * ready compute node met every other constraint, but its write-back port was taken at its boundary
  * (L5). The register limit is one of the constraints; another node may still issue in the slot.
  * The counts do not change the schedule.
  */
final case class Schedule(
    dag: Dag,
    issue: Vector[Int],
    halt: Int,
    heldByReadPorts: Int = 0,
    heldByWritePort: Int = 0
) {
  def cycles: Int = halt + 1

  /** One entry per cycle before HALT; the bundle at `halt` holds only HALT. */
  def bundles: Vector[Slots] = {
    val at = issue.zipWithIndex.groupBy(_._1)
    Vector.tabulate(halt) { c =>
      val ns = at.getOrElse(c, Vector.empty).map(_._2)
      Slots(
        ns.find(n => ListScheduler.slot(dag.nodes(n)) == Slot.Compute),
        ns.find(n => ListScheduler.slot(dag.nodes(n)) == Slot.Memory)
      )
    }
  }
}

/** Which ready node to issue first; higher keys first, ties to the lower node id. */
sealed abstract class Priority(val name: String) extends Product with Serializable {
  def key(dag: Dag, node: Int): Int
}
object Priority {

  /** Longest path to completion (DAG height): the usual list-scheduling priority. */
  case object CriticalPath extends Priority("critical-path") {
    def key(dag: Dag, node: Int): Int = dag.height(node)
  }

  /** Program order (node id), for comparison. */
  case object ProgramOrder extends Priority("program-order") {
    def key(dag: Dag, node: Int): Int = -node
  }

  val all: Seq[Priority] = Seq(CriticalPath, ProgramOrder)
}

/** Cycle-by-cycle list scheduling under the resource limits of the machine description. */
object ListScheduler {
  def slot(n: Node): Slot = n match {
    case _: Node.Compute | _: Node.Mac => Slot.Compute
    case _: Node.Load | _: Node.Store  => Slot.Memory
  }

  /** Write-back port used at E_(s+L), if any (ST commits to the scratchpad, not to the RF). */
  def port(n: Node): Option[Slot] = n match {
    case _: Node.Compute | _: Node.Mac => Some(Slot.Compute)
    case _: Node.Load                  => Some(Slot.Memory)
    case _: Node.Store                 => None
  }

  /** Registers read at issue, counted per operand field as rule L11 counts them. */
  def reads(n: Node): Int = n.operands.size

  /** Schedules without a register limit (values are virtual registers). */
  def schedule(dag: Dag, priority: Priority = Priority.CriticalPath): Schedule =
    run(dag, priority, None).getOrElse(sys.error("list scheduling without a register limit failed"))

  /** Register limit for [[run]]: at most `registers` values live at any cycle (docs/COMPILER.md,
    * "register allocation"). A load may issue only while `loadReserve` more registers stay free, so
    * that loaded operands leave room for the results computed from them.
    */
  final case class Limit(registers: Int, loadReserve: Int)

  /** List scheduling; with a limit, None when it cannot place every node within it. With
    * `singleIssue`, at most one operation issues per bundle (the single-issue baseline of #52).
    */
  def run(
      dag: Dag,
      priority: Priority,
      limit: Option[Limit],
      singleIssue: Boolean = false
  ): Option[Schedule] = attempt(dag, priority, limit, singleIssue).toOption

  /** Where a limited schedule stopped: the issue cycle of every node placed so far (-1 for the
    * others) and the cycle it stopped at. `blocked` when it stopped at a block (see [[attempt]]);
    * otherwise it got stuck: no node fitted for longer than any latency or II.
    */
  final case class Stopped(issue: Vector[Int], cycle: Int, blocked: Boolean) {
    def placed(n: Int): Boolean = issue(n) >= 0
  }

  /** [[run]], with where it stopped instead of None. With `blockedAfter`, it also stops at the
    * first cycle after that one at which the register limit blocks a slot: the slot stays empty
    * although a ready node of it meets every other constraint (rematerialization, #70).
    */
  def attempt(
      dag: Dag,
      priority: Priority,
      limit: Option[Limit],
      singleIssue: Boolean = false,
      blockedAfter: Option[Int] = None
  ): Either[Stopped, Schedule] = {
    val n = dag.nodes.size
    val issue = Array.fill(n)(-1)
    val missing = Array.tabulate(n)(i => dag.predecessors(i).size) // unscheduled predecessors
    val readyAt = Array.fill(n)(0) // earliest cycle allowed by scheduled predecessors
    val ports = mutable.Set.empty[(Slot, Int)] // write-back reservations (port, boundary)
    var bundleReads = 0 // registers read by the nodes placed in this cycle (L11)
    val unitFree = mutable.Map.empty[String, Int].withDefaultValue(0) // L9
    val ready = mutable.Set.from((0 until n).filter(missing(_) == 0))
    // A value is live from its issue until its last reader issues (interval [s, u)).
    val operands = dag.predecessors.map(_.filter(_.kind == EdgeKind.Data).map(_.from).distinct)
    val readers = Array.tabulate(n)(i => dag.successors(i).count(_.kind == EdgeKind.Data))
    var live = 0
    var cycle = 0
    var left = n
    var idle = 0
    val stuck = dag.nodes.indices.map(i => dag.latency(i)).maxOption.getOrElse(0) +
      dag.machine.operations.values.map(_.ii).max + 2
    def latency(i: Int) = dag.latency(i)
    def mnemonic(i: Int) = dag.nodes(i).mnemonic
    def produces(i: Int) = !dag.nodes(i).isInstanceOf[Node.Store]
    def liveAfter(i: Int) = live - operands(i).count(readers(_) == 1) + (if (produces(i)) 1 else 0)

    def timely(i: Int) = readyAt(i) <= cycle && unitFree(mnemonic(i)) <= cycle // edges, L9
    def portFree(i: Int) = port(dag.nodes(i)).forall(p => !ports.contains((p, cycle + latency(i))))
    def readsFit(i: Int) = bundleReads + reads(dag.nodes(i)) <= dag.machine.readPorts // L11
    def underLimit(i: Int) = limit.forall { l =>
      val reserve = if (dag.nodes(i).isInstanceOf[Node.Load]) l.loadReserve else 0
      liveAfter(i) <= l.registers - reserve
    }
    // Every constraint but the register limit.
    def free(i: Int): Boolean = timely(i) && portFree(i) && readsFit(i)
    def fits(i: Int): Boolean = free(i) && underLimit(i)
    // Held back by one constraint alone: the read ports (L11), or the write-back port (L5).
    def onlyReadPorts(i: Int) = timely(i) && portFree(i) && !readsFit(i) && underLimit(i)
    def onlyWritePort(i: Int) = timely(i) && !portFree(i) && readsFit(i) && underLimit(i)

    def place(i: Int): Unit = {
      live = liveAfter(i)
      operands(i).foreach(o => readers(o) -= 1)
      issue(i) = cycle
      ready -= i
      left -= 1
      port(dag.nodes(i)).foreach(p => ports += ((p, cycle + latency(i))))
      bundleReads += reads(dag.nodes(i))
      unitFree(mnemonic(i)) = cycle + dag.machine.operation(mnemonic(i)).ii
      dag.successors(i).foreach { e =>
        readyAt(e.to) = readyAt(e.to).max(cycle + e.distance)
        missing(e.to) -= 1
        if (missing(e.to) == 0) ready += e.to
      }
    }

    // Under register pressure, prefer the nodes that free the most registers.
    def key(i: Int) = limit match {
      case Some(l) if live >= l.registers - 1 => (liveAfter(i) - live, -priority.key(dag, i), i)
      case _                                  => (0, -priority.key(dag, i), i)
    }

    var blocked = false
    var heldByReadPorts = 0
    var heldByWritePort = 0
    // Places the first node of `of` that fits, if any. A slot is filled once per cycle, so each
    // count grows at most once per cycle; the counts do not change what is placed.
    def fill(of: Int => Boolean): Unit = {
      val candidates = ready.toSeq.filter(of)
      if (candidates.exists(i => dag.nodes(i).isInstanceOf[Node.Store] && onlyReadPorts(i)))
        heldByReadPorts += 1
      if (candidates.exists(i => slot(dag.nodes(i)) == Slot.Compute && onlyWritePort(i)))
        heldByWritePort += 1
      candidates.filter(fits).sortBy(key).headOption match {
        case Some(i) => place(i)
        case None    => blocked ||= blockedAfter.exists(_ < cycle) && candidates.exists(free)
      }
    }
    while (left > 0 && idle <= stuck && !blocked) {
      // A node made ready by a placement in this cycle has distance >= 1, except LD -> ST (0),
      // which shares the memory slot; so both slots can be filled in one pass.
      val before = left
      bundleReads = 0
      if (singleIssue) fill(_ => true)
      else Seq(Slot.Compute, Slot.Memory).foreach(s => fill(i => slot(dag.nodes(i)) == s))
      idle = if (left < before) 0 else idle + 1
      cycle += 1
    }
    Either.cond(
      left == 0, {
        val halt = (0 until n).map(i => issue(i) + latency(i)).maxOption.getOrElse(0)
        Schedule(dag, issue.toVector, halt, heldByReadPorts, heldByWritePort)
      },
      Stopped(issue.toVector, cycle - 1, blocked)
    )
  }
}
