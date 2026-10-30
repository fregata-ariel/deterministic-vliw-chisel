package vliw.compiler.dag

import scala.collection.mutable

import vliw.compiler.dsl.{LoweredKernel, Op}
import vliw.isa.{MachineDescription, Slot}

/** An operand field of a vector operation or of ST.W (ISA mvp-2, docs/COMPILER.md "ISA mvp-2（W）"):
  * the nodes whose values the field reads. Only `Pack` makes them.
  */
sealed trait Src extends Product with Serializable {

  /** The nodes the field reads. */
  def nodes: Vector[Int]
}
object Src {

  /** The aligned register pair of a pair node (LD.W, a vector operation). */
  final case class Pair(node: Int) extends Src {
    def nodes: Vector[Int] = Vector(node)
  }

  /** Two scalar nodes whose results go to r(p) (`lo`, lane 0) and r(p+1) (`hi`, lane 1) of one
    * aligned pair, with no move instruction (docs/ISA.md: partial pair updates are legal).
    */
  final case class Gather(lo: Int, hi: Int) extends Src {
    def nodes: Vector[Int] = Vector(lo, hi)
  }

  /** One scalar node, read once for both lanes: only the rs1 of VMUL and VMAC (bit B). */
  final case class Broadcast(node: Int) extends Src {
    def nodes: Vector[Int] = Vector(node)
  }
}

/** One operation of the kernel. Operands refer to node ids; values are still virtual registers. */
sealed trait Node extends Product with Serializable {

  /** ISA mnemonic, which selects the latency and II in the machine description. */
  def mnemonic: String

  /** The nodes read, one per operand field of a scalar instruction: a node read by two fields
    * appears twice. A gather field reads two nodes.
    */
  def operands: Vector[Int]

  /** The operand fields of a vector operation or of ST.W; empty for the other nodes. */
  def sources: Vector[Src] = Vector.empty

  /** The number of operand fields, which rule L11 counts (form F): a pair, a gather and a broadcast
    * are one field each. For the scalar nodes, `operands.size`.
    */
  def fields: Int = operands.size

  /** Whether the node's value is an aligned register pair (LD.W and the vector operations). */
  def isPair: Boolean = false

  /** Whether the node stores (ST, ST.W, a lane ST), and so has no value. */
  def isStore: Boolean = false
}
object Node {
  final case class Load(addr: Int) extends Node {
    def mnemonic: String = "LD"
    def operands: Vector[Int] = Vector.empty
  }
  final case class Compute(op: Op, a: Int, b: Int) extends Node {
    def mnemonic: String = op.mnemonic
    def operands: Vector[Int] = Vector(a, b)
  }

  /** `a * b + c` (ISA mvp-1). The kernel language has no MAC: it comes only from fusing a MUL and
    * an ADD (`Fusion`).
    */
  final case class Mac(a: Int, b: Int, c: Int) extends Node {
    def mnemonic: String = "MAC"
    def operands: Vector[Int] = Vector(a, b, c)
  }
  final case class Store(addr: Int, value: Int) extends Node {
    def mnemonic: String = "ST"
    def operands: Vector[Int] = Vector(value)
    override def isStore: Boolean = true
  }

  // ISA mvp-2 (W). Only `Pack` makes these nodes (docs/COMPILER.md "ISA mvp-2（W）").

  /** LD.W [addr]: the words `addr` and `addr + 1` into an aligned pair (W-full only). */
  final case class LoadW(addr: Int) extends Node {
    def mnemonic: String = "LD.W"
    def operands: Vector[Int] = Vector.empty
    override def isPair: Boolean = true
  }

  /** VADD, VSUB or VMUL: `op` in each lane. Only VMUL's `a` may be a broadcast. */
  final case class VCompute(op: Op, a: Src, b: Src) extends Node {
    def mnemonic: String = "V" + op.mnemonic
    def operands: Vector[Int] = a.nodes ++ b.nodes
    override def sources: Vector[Src] = Vector(a, b)
    override def fields: Int = 2
    override def isPair: Boolean = true
  }

  /** VMAC: `a * b + c` in each lane; `a` may be a broadcast. */
  final case class VMac(a: Src, b: Src, c: Src) extends Node {
    def mnemonic: String = "VMAC"
    def operands: Vector[Int] = a.nodes ++ b.nodes ++ c.nodes
    override def sources: Vector[Src] = Vector(a, b, c)
    override def fields: Int = 3
    override def isPair: Boolean = true
  }

  /** ST.W [addr]: the pair `value` to the words `addr` and `addr + 1` (W-full only). */
  final case class StoreW(addr: Int, value: Src) extends Node {
    def mnemonic: String = "ST.W"
    def operands: Vector[Int] = value.nodes
    override def sources: Vector[Src] = Vector(value)
    override def fields: Int = 1
    override def isStore: Boolean = true
  }

  /** ST [addr] of lane `lane` of the pair node `pair`, register r(p + lane) (W-reg, which has no
    * ST.W).
    */
  final case class StoreLane(addr: Int, pair: Int, lane: Int) extends Node {
    def mnemonic: String = "ST"
    def operands: Vector[Int] = Vector(pair)
    override def isStore: Boolean = true
  }
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

/** The registers that one value, or one gather, occupies (docs/COMPILER.md "ISA mvp-2（W）"): a
  * scalar node not in a gather (weight 1), a pair node (weight 2), or a gather (weight 2, defined
  * by its two lane nodes, `lo` first). The scheduler and the allocator count groups, not nodes.
  */
final case class Group(definers: Vector[Int], weight: Int)

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
    * HALT, which takes a compute slot of its own. LD.W, ST.W and lane STs are memory operations.
    */
  def resourceLowerBound: Int = {
    val (memory, compute) =
      nodes.partition(n => machine.operation(n.mnemonic).slot == Slot.Memory)
    (compute.size + 1).max(memory.size + 1)
  }

  /** Number of LD and LD.W nodes. */
  def loads: Int = nodes.count {
    case _: Node.Load | _: Node.LoadW => true
    case _                            => false
  }

  /** Number of MAC nodes: in a kernel's DAG, the MUL + ADD pairs fused (`Fusion`). */
  def macs: Int = nodes.count(_.isInstanceOf[Node.Mac])

  /** The nodes that read each node's value (its data successors), in id order. */
  lazy val readers: Vector[Vector[Int]] =
    successors.map(_.filter(_.kind == EdgeKind.Data).map(_.to).distinct.sorted)

  /** Addresses that some store of the DAG writes (ST.W writes two). */
  lazy val written: Set[Int] = nodes.flatMap {
    case Node.Store(a, _)        => Seq(a)
    case Node.StoreW(a, _)       => Seq(a, a + 1)
    case Node.StoreLane(a, _, _) => Seq(a)
    case _                       => Nil
  }.toSet

  /** The gathers that the fields of the DAG read, each once, in order of first use. */
  lazy val gathers: Vector[Src.Gather] =
    nodes.flatMap(_.sources).collect { case g: Src.Gather => g }.distinct

  /** The register group of each node (-1 for the stores, which have no value). */
  def group: Vector[Int] = grouping._1

  /** The groups in order of their first node. On a DAG without pairs every group is one node of
    * weight 1, in the order of the nodes.
    */
  def groups: Vector[Group] = grouping._2

  private lazy val grouping: (Vector[Int], Vector[Group]) = {
    val lane = mutable.Map.empty[Int, Src.Gather]
    gathers.foreach { g =>
      require(g.lo != g.hi, s"$g has the same node in both lanes")
      g.nodes.foreach { n =>
        require(!nodes(n).isPair && !nodes(n).isStore, s"$g: node $n is not a scalar value")
        require(!lane.contains(n), s"node $n is a lane of ${lane(n)} and of $g")
        lane(n) = g
      }
    }
    val of = Array.fill(nodes.size)(-1)
    val made = mutable.ArrayBuffer.empty[Group]
    nodes.indices.foreach { i =>
      if (!nodes(i).isStore && of(i) < 0) {
        val g = made.size
        lane.get(i) match {
          case Some(gather) => gather.nodes.foreach(of(_) = g); made += Group(gather.nodes, 2)
          case None         => of(i) = g; made += Group(Vector(i), if (nodes(i).isPair) 2 else 1)
        }
      }
    }
    (of.toVector, made.toVector)
  }

  /** The nodes that read each group (data successors of its definers), in id order. */
  lazy val groupReaders: Vector[Vector[Int]] =
    groups.map(_.definers.flatMap(readers).distinct.sorted)

  /** Whether node `n` may be issued again for some of its readers (rematerialization, #70; ADR-0023
    * for LD.W): a LD of a word that no store of the DAG writes (an input, a scalar or a constant),
    * so that every LD of it reads the same value and needs no memory-order edge, but not a lane of
    * a gather (#110 stage 4); or a LD.W of two such words.
    */
  def rematerializable(n: Int): Boolean = nodes(n) match {
    case Node.Load(addr)  => !written(addr) && groups(group(n)).definers.size == 1
    case Node.LoadW(addr) => !written(addr) && !written(addr + 1)
    case _                => false
  }

  /** The DAG in which `moved`, a nonempty proper subset of the readers of the rematerializable LD
    * (or LD.W) `load`, read a copy of it: a new LD of the same word, with the data edges of `load`
    * to them. The copy takes the id of the first of `moved`, and every id from there on grows by
    * one, so that ids stay a topological order (`load` < copy < every node of `moved`).
    */
  def split(load: Int, moved: Set[Int]): Dag = {
    require(rematerializable(load), s"node $load is not a LD of a word that no store writes")
    require(
      moved.nonEmpty && moved.subsetOf(readers(load).toSet) && moved.size < readers(load).size,
      s"$moved is not a nonempty proper subset of the readers of $load"
    )
    val at = moved.min
    def id(i: Int) = if (i < at) i else i + 1
    def operand(reader: Int, o: Int) = if (o == load && moved(reader)) at else id(o)
    def src(reader: Int, s: Src): Src = s match {
      case Src.Pair(n)        => Src.Pair(operand(reader, n))
      case Src.Gather(lo, hi) => Src.Gather(operand(reader, lo), operand(reader, hi))
      case Src.Broadcast(n)   => Src.Broadcast(operand(reader, n))
    }
    val renamed = nodes.zipWithIndex.map {
      case (Node.Compute(op, a, b), i)  => Node.Compute(op, operand(i, a), operand(i, b))
      case (Node.Mac(a, b, c), i)       => Node.Mac(operand(i, a), operand(i, b), operand(i, c))
      case (Node.Store(addr, v), i)     => Node.Store(addr, operand(i, v))
      case (l: Node.Load, _)            => l
      case (l: Node.LoadW, _)           => l
      case (Node.VCompute(op, a, b), i) => Node.VCompute(op, src(i, a), src(i, b))
      case (Node.VMac(a, b, c), i)      => Node.VMac(src(i, a), src(i, b), src(i, c))
      case (Node.StoreW(addr, v), i)    => Node.StoreW(addr, src(i, v))
      case (Node.StoreLane(addr, v, lane), i) => Node.StoreLane(addr, operand(i, v), lane)
    }
    val newEdges = edges.map { e =>
      if (e.kind == EdgeKind.Data && e.from == load && moved(e.to)) e.copy(from = at, to = id(e.to))
      else e.copy(from = id(e.from), to = id(e.to))
    }
    assert(newEdges.forall(e => e.from < e.to), "node ids are not a topological order")
    Dag(machine, (renamed.take(at) :+ nodes(load)) ++ renamed.drop(at), newEdges)
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
