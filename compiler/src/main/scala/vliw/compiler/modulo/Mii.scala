package vliw.compiler.modulo

import vliw.compiler.loop.{LoopEdge, LoopGraph, Place}
import vliw.isa.{MachineDescription, OperationSpec, Slot}

/** One bound of ResMII: the uses per iteration of a resource with one use per cycle. */
final case class ResourceBound(resource: String, uses: Int)

/** The lower bounds of the loop's II (docs/COMPILER.md, "Modulo Scheduling"). `resMII` comes from
  * the resources one iteration uses, `recMII` from the dependence cycles across iterations (0 when
  * there is none). `mii` is the larger of the two, and at least 1.
  */
final case class Mii(resources: Vector[ResourceBound], recMII: Int) {
  def resMII: Int = resources.map(_.uses).maxOption.getOrElse(0)

  /** The resources that set `resMII`. */
  def bounding: Vector[String] = resources.filter(_.uses == resMII).map(_.resource)

  def mii: Int = resMII.max(recMII).max(1)

  /** The bounds without the third read port ([[Mii.ReadPort]]). Whether a loop is fused is decided
    * on these (`FusionChoice`), so that three and four read ports fuse the same loops.
    */
  def withoutReadPort: Mii = copy(resources = resources.filterNot(_.resource == Mii.ReadPort))
}

object Mii {

  /** The resource of the third read port (rule L11, ADR-0018). */
  val ReadPort: String = "third read port"

  /** The operations that use the third read port of `m`: MAC and ST on a machine with MAC and three
    * read ports, where a bundle with both would read four registers (L11); none otherwise. ADD, SUB
    * and MUL read two registers, MAC three, ST one and LD none, so no other bundle reads more than
    * three.
    */
  def readPortUsers(m: MachineDescription): Set[String] =
    if (m.hasMac && m.readPorts < 4) Set("MAC", "ST") else Set.empty

  /** Each resource has one use per cycle: the compute and memory slots, the unit of each operation
    * (an issue holds it for the operation's II, L9), the two write-back ports (L5), and on a
    * machine with MAC and three read ports the third read port, which each MAC and each ST takes at
    * issue (L11). The slot and the port of each operation come from the machine.
    */
  def of(g: LoopGraph): Mii = {
    val each = g.ids(Place.Each).map(g.nodes(_).mnemonic)
    def count(ms: Set[String]) = each.count(ms.contains)
    def where(p: OperationSpec => Boolean) =
      g.machine.operations.values.filter(p).map(_.mnemonic).toSet
    val units = each.distinct.sorted.map(m =>
      ResourceBound(s"$m unit", count(Set(m)) * g.machine.operation(m).ii)
    )
    val resources = Vector(
      ResourceBound("compute slot", count(where(_.slot == Slot.Compute))),
      ResourceBound("memory slot", count(where(_.slot == Slot.Memory))),
      ResourceBound("compute write-back port", count(where(_.writeback.contains(Slot.Compute)))),
      ResourceBound("memory write-back port", count(where(_.writeback.contains(Slot.Memory)))),
      ResourceBound(ReadPort, count(readPortUsers(g.machine)))
    ) ++ units
    Mii(resources.filter(_.uses > 0), recMII(g))
  }

  /** The smallest II with no dependence cycle of `g.edges` that needs more: a cycle with total
    * cycles c over total iterations d needs II >= c / d. 0 without a cycle.
    */
  def recMII(g: LoopGraph): Int = {
    val edges = g.edges
    if (!hasCycle(g.nodes.size, edges)) 0
    else {
      // A cycle has iterations >= 1 (LoopGraph.edges), so II = the sum of all cycles suffices.
      val bound = edges.map(_.cycles).sum.max(1)
      (1 to bound).find(ii => !positiveCycle(g.nodes.size, edges, ii)).getOrElse(bound)
    }
  }

  private def hasCycle(n: Int, edges: Seq[LoopEdge]): Boolean = {
    val succ = edges.groupBy(_.from).view.mapValues(_.map(_.to)).toMap
    val state = Array.fill(n)(0) // 0 new, 1 on the path, 2 done
    def visit(v: Int): Boolean = {
      state(v) = 1
      val found =
        succ.getOrElse(v, Seq.empty).exists(w => state(w) == 1 || (state(w) == 0 && visit(w)))
      state(v) = 2
      found
    }
    (0 until n).exists(v => state(v) == 0 && visit(v))
  }

  /** Whether some cycle has `sum(cycles) - ii * sum(iterations) > 0`: longest paths from every node
    * (Bellman-Ford) still grow after n rounds.
    */
  private def positiveCycle(n: Int, edges: Seq[LoopEdge], ii: Int): Boolean = {
    val d = Array.fill(n)(0L)
    def relax(): Boolean = edges.foldLeft(false) { (changed, e) =>
      val w = d(e.from) + e.cycles - ii.toLong * e.iterations
      if (w > d(e.to)) { d(e.to) = w; true }
      else changed
    }
    (0 until n).foreach(_ => relax())
    relax()
  }
}
