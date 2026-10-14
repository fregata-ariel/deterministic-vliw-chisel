package vliw.compiler.schedule

import scala.collection.mutable

import vliw.compiler.dag.{Dag, Node}
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

  def schedule(dag: Dag, priority: Priority = Priority.CriticalPath): Schedule = {
    val n = dag.nodes.size
    val issue = Array.fill(n)(-1)
    val missing = Array.tabulate(n)(i => dag.predecessors(i).size) // unscheduled predecessors
    val readyAt = Array.fill(n)(0) // earliest cycle allowed by scheduled predecessors
    val ports = mutable.Set.empty[(Slot, Int)] // write-back reservations (port, boundary)
    val unitFree = mutable.Map.empty[String, Int].withDefaultValue(0) // L9
    val ready = mutable.Set.from((0 until n).filter(missing(_) == 0))
    var cycle = 0
    var left = n
    def latency(i: Int) = dag.latency(i)
    def mnemonic(i: Int) = dag.nodes(i).mnemonic

    def fits(i: Int): Boolean =
      readyAt(i) <= cycle && unitFree(mnemonic(i)) <= cycle &&
        port(dag.nodes(i)).forall(p => !ports.contains((p, cycle + latency(i))))

    def place(i: Int): Unit = {
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

    while (left > 0) {
      // Pick both slots from the nodes ready at the start of the cycle: a node made ready by a
      // placement in this cycle has distance >= 0 and is considered from this cycle on only if its
      // distance is 0, which the slot it shares (memory, LD -> ST) excludes anyway.
      Seq(Slot.Compute, Slot.Memory).foreach { s =>
        ready.toSeq
          .filter(i => slot(dag.nodes(i)) == s && fits(i))
          .sortBy(i => (-priority.key(dag, i), i))
          .headOption
          .foreach(place)
      }
      cycle += 1
      require(cycle <= 64 * (n + 1) * 8, "list scheduling made no progress") // cannot happen
    }
    val halt = (0 until n).map(i => issue(i) + latency(i)).maxOption.getOrElse(0)
    Schedule(dag, issue.toVector, halt)
  }
}
