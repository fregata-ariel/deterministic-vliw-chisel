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
  */
final case class Schedule(dag: Dag, issue: Vector[Int], halt: Int) {
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
    case _: Node.Compute              => Slot.Compute
    case _: Node.Load | _: Node.Store => Slot.Memory
  }

  /** Write-back port used at E_(s+L), if any (ST commits to the scratchpad, not to the RF). */
  def port(n: Node): Option[Slot] = n match {
    case _: Node.Compute => Some(Slot.Compute)
    case _: Node.Load    => Some(Slot.Memory)
    case _: Node.Store   => None
  }

  /** Schedules without a register limit (values are virtual registers). */
  def schedule(dag: Dag, priority: Priority = Priority.CriticalPath): Schedule =
    run(dag, priority, None).getOrElse(sys.error("list scheduling without a register limit failed"))

  /** Register limit for [[run]]: at most `registers` values live at any cycle (docs/COMPILER.md,
    * "register allocation"). A load may issue only while `loadReserve` more registers stay free, so
    * that loaded operands leave room for the results computed from them.
    */
  final case class Limit(registers: Int, loadReserve: Int)

  /** List scheduling; with a limit, None when it cannot place every node within it. */
  def run(dag: Dag, priority: Priority, limit: Option[Limit]): Option[Schedule] = {
    val n = dag.nodes.size
    val issue = Array.fill(n)(-1)
    val missing = Array.tabulate(n)(i => dag.predecessors(i).size) // unscheduled predecessors
    val readyAt = Array.fill(n)(0) // earliest cycle allowed by scheduled predecessors
    val ports = mutable.Set.empty[(Slot, Int)] // write-back reservations (port, boundary)
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

    def fits(i: Int): Boolean =
      readyAt(i) <= cycle && unitFree(mnemonic(i)) <= cycle &&
        port(dag.nodes(i)).forall(p => !ports.contains((p, cycle + latency(i)))) &&
        limit.forall { l =>
          val reserve = if (dag.nodes(i).isInstanceOf[Node.Load]) l.loadReserve else 0
          liveAfter(i) <= l.registers - reserve
        }

    def place(i: Int): Unit = {
      live = liveAfter(i)
      operands(i).foreach(o => readers(o) -= 1)
      issue(i) = cycle
      ready -= i
      left -= 1
      port(dag.nodes(i)).foreach(p => ports += ((p, cycle + latency(i))))
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

    while (left > 0 && idle <= stuck) {
      // A node made ready by a placement in this cycle has distance >= 1, except LD -> ST (0),
      // which shares the memory slot; so both slots can be filled in one pass.
      val before = left
      Seq(Slot.Compute, Slot.Memory).foreach { s =>
        ready.toSeq
          .filter(i => slot(dag.nodes(i)) == s && fits(i))
          .sortBy(key)
          .headOption
          .foreach(place)
      }
      idle = if (left < before) 0 else idle + 1
      cycle += 1
    }
    Option.when(left == 0) {
      val halt = (0 until n).map(i => issue(i) + latency(i)).maxOption.getOrElse(0)
      Schedule(dag, issue.toVector, halt)
    }
  }
}
