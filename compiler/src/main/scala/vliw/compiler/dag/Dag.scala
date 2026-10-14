package vliw.compiler.dag

import scala.collection.mutable

import vliw.compiler.dsl.{LoweredKernel, Op}
import vliw.isa.MachineDescription

/** One operation of the kernel. Operands refer to node ids; values are still virtual registers. */
sealed trait Node extends Product with Serializable {

  /** ISA mnemonic, which selects the latency and II in the machine description. */
  def mnemonic: String
}
object Node {
  final case class Load(addr: Int) extends Node { def mnemonic: String = "LD" }
  final case class Compute(op: Op, a: Int, b: Int) extends Node {
    def mnemonic: String = op.mnemonic
  }
  final case class Store(addr: Int, value: Int) extends Node { def mnemonic: String = "ST" }
}

sealed abstract class EdgeKind(val name: String) extends Product with Serializable
object EdgeKind {

  /** The consumer reads the producer's result (L3, L8). */
  case object Data extends EdgeKind("data")

  /** A load of a word after a store to it (L8, L10). */
  case object StoreToLoad extends EdgeKind("st->ld")

  /** A store to a word after a load of its previous value. */
  case object LoadToStore extends EdgeKind("ld->st")

  /** Two stores to the same word, in program order. */
  case object StoreToStore extends EdgeKind("st->st")
}

/** `issue(to) >= issue(from) + distance`. */
final case class Edge(from: Int, to: Int, distance: Int, kind: EdgeKind)

/** Dependence DAG of a lowered kernel (docs/COMPILER.md, "DAG"). Node ids are a topological order:
  * every edge goes from a lower id to a higher one.
  */
final case class Dag(machine: MachineDescription, nodes: Vector[Node], edges: Vector[Edge]) {
  def latency(n: Int): Int = machine.operation(nodes(n).mnemonic).latency

  lazy val predecessors: Vector[Vector[Edge]] = {
    val by = edges.groupBy(_.to)
    nodes.indices.map(i => by.getOrElse(i, Vector.empty)).toVector
  }
  lazy val successors: Vector[Vector[Edge]] = {
    val by = edges.groupBy(_.from)
    nodes.indices.map(i => by.getOrElse(i, Vector.empty)).toVector
  }

  /** Earliest issue cycle of each node without resource limits (ASAP). */
  lazy val earliest: Vector[Int] =
    nodes.indices.foldLeft(Vector.empty[Int]) { (acc, i) =>
      acc :+ predecessors(i).map(e => acc(e.from) + e.distance).maxOption.getOrElse(0)
    }

  /** Longest path from the issue of each node to the completion of the last operation, including
    * the node's own latency. The usual list-scheduling priority (#49).
    */
  lazy val height: Vector[Int] =
    nodes.indices.reverse
      .foldLeft(Map.empty[Int, Int]) { (acc, i) =>
        val h = (latency(i) +: successors(i).map(e => e.distance + acc(e.to))).max
        acc.updated(i, h)
      }
      .toSeq
      .sortBy(_._1)
      .map(_._2)
      .toVector

  /** Cycles from the first issue until every operation has completed: `max(earliest + L)`. */
  def criticalPath: Int = nodes.indices.map(i => earliest(i) + latency(i)).maxOption.getOrElse(0)

  /** Lower bound of the kernel's cycles from dependences: HALT issues at the critical path or
    * later, and `cycles = h + 1` (docs/ISA.md, "制御").
    */
  def cyclesLowerBound: Int = criticalPath + 1

  /** Lower bound from issue slots alone: one compute and one memory operation per bundle, plus
    * HALT, which takes a compute slot of its own.
    */
  def resourceLowerBound: Int = {
    val (memory, compute) = nodes.partition {
      case _: Node.Load | _: Node.Store => true
      case _                            => false
    }
    (compute.size + 1).max(memory.size + 1)
  }
}

object Dag {

  /** Builds the DAG of `k` for `machine`. Equal values share one node (value numbering); a load is
    * identified by its address and the number of stores to that address before it.
    */
  def build(machine: MachineDescription, writes: Vector[LoweredKernel.Write]): Dag = {
    val nodes = mutable.ArrayBuffer.empty[Node]
    val edges = mutable.ArrayBuffer.empty[Edge]
    val numbering = mutable.HashMap.empty[Any, Int]
    val version = mutable.HashMap.empty[Int, Int].withDefaultValue(0)
    val lastStore = mutable.HashMap.empty[Int, Int]
    val loadsSinceStore = mutable.HashMap.empty[Int, Vector[Int]].withDefaultValue(Vector.empty)
    def lat(m: String) = machine.operation(m).latency

    def add(n: Node): Int = { nodes += n; nodes.size - 1 }

    def value(v: LoweredKernel.Value): Int = v match {
      case LoweredKernel.Read(addr) =>
        numbering.getOrElseUpdate(
          ("ld", addr, version(addr)), {
            val id = add(Node.Load(addr))
            lastStore
              .get(addr)
              .foreach(st => edges += Edge(st, id, lat("ST"), EdgeKind.StoreToLoad))
            loadsSinceStore(addr) = loadsSinceStore(addr) :+ id
            id
          }
        )
      case LoweredKernel.Bin(op, a, b) =>
        val (x, y) = (value(a), value(b))
        numbering.getOrElseUpdate(
          (op, x, y), {
            val id = add(Node.Compute(op, x, y))
            edges += Edge(x, id, lat(nodes(x).mnemonic), EdgeKind.Data)
            if (y != x) edges += Edge(y, id, lat(nodes(y).mnemonic), EdgeKind.Data)
            id
          }
        )
    }

    writes.foreach { w =>
      val v = value(w.value)
      val id = add(Node.Store(w.addr, v))
      edges += Edge(v, id, lat(nodes(v).mnemonic), EdgeKind.Data)
      loadsSinceStore(w.addr).foreach(ld => edges += Edge(ld, id, 0, EdgeKind.LoadToStore))
      lastStore.get(w.addr).foreach(st => edges += Edge(st, id, 1, EdgeKind.StoreToStore))
      lastStore(w.addr) = id
      loadsSinceStore(w.addr) = Vector.empty
      version(w.addr) += 1
    }
    assert(edges.forall(e => e.from < e.to), "node ids are not a topological order")
    Dag(machine, nodes.toVector, edges.toVector)
  }

  def of(k: LoweredKernel, machine: MachineDescription): Dag = build(machine, k.writes)
}
