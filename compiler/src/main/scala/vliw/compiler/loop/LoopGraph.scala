package vliw.compiler.loop

import scala.collection.mutable

import vliw.compiler.dag.{Dag, Edge, EdgeKind, Node, Src}
import vliw.compiler.dsl.Op
import vliw.isa.MachineDescription

/** Where a node of a loop runs: once before the loop, in every iteration, or once after it. */
sealed abstract class Place(val name: String) extends Product with Serializable
object Place {
  case object Before extends Place("before")
  case object Each extends Place("each")
  case object After extends Place("after")
}

/** An operand: the value of `node` from `back` iterations earlier. In iteration k < back, that
  * iteration does not exist and the value is that of the Before node `initial(k)`. A Before node
  * has one value; an After node reads an Each node in the last iteration (`back` is 0 there).
  */
final case class Ref(node: Int, back: Int = 0, initial: Vector[Int] = Vector.empty)

sealed trait LoopNode extends Product with Serializable {

  /** ISA mnemonic, which selects the latency and II in the machine description. */
  def mnemonic: String

  /** The operands, one per operand field of the instruction. */
  def operands: Vector[Ref]
}
object LoopNode {
  final case class Load(addr: Address) extends LoopNode {
    def mnemonic: String = "LD"
    def operands: Vector[Ref] = Vector.empty
  }
  final case class Compute(op: Op, a: Ref, b: Ref) extends LoopNode {
    def mnemonic: String = op.mnemonic
    def operands: Vector[Ref] = Vector(a, b)
  }

  /** `a * b + c` (ISA mvp-1). Only fusing a MUL and an ADD makes it (`LoopFusion`). */
  final case class Mac(a: Ref, b: Ref, c: Ref) extends LoopNode {
    def mnemonic: String = "MAC"
    def operands: Vector[Ref] = Vector(a, b, c)
  }
  final case class Store(addr: Address, value: Ref) extends LoopNode {
    def mnemonic: String = "ST"
    def operands: Vector[Ref] = Vector(value)
  }

  // ISA mvp-2 (W). Only the loop vectorization (`LoopVector.double`, and `LoopPack` for a body
  // that is not doubled) makes these nodes, as only `dag.Pack` makes the W nodes of a DAG
  // (docs/COMPILER.md "ループのベクトル化と Modulo Scheduling（W-full）").

  /** LD.W [addr.at(k)]: the words `addr.at(k)` and `addr.at(k) + 1` into an aligned pair. */
  final case class LoadW(addr: Address) extends LoopNode {
    def mnemonic: String = "LD.W"
    def operands: Vector[Ref] = Vector.empty
  }

  /** VADD, VSUB or VMUL: `op` in each lane. Only VMUL's `a` may be a broadcast. */
  final case class VCompute(op: Op, a: VRef, b: VRef) extends LoopNode {
    def mnemonic: String = "V" + op.mnemonic
    def operands: Vector[Ref] = a.refs ++ b.refs
  }

  /** VMAC: `a * b + c` in each lane; `a` may be a broadcast. */
  final case class VMac(a: VRef, b: VRef, c: VRef) extends LoopNode {
    def mnemonic: String = "VMAC"
    def operands: Vector[Ref] = a.refs ++ b.refs ++ c.refs
  }

  /** ST.W [addr.at(k)]: the pair `value` to the words `addr.at(k)` and `addr.at(k) + 1`. */
  final case class StoreW(addr: Address, value: VRef) extends LoopNode {
    def mnemonic: String = "ST.W"
    def operands: Vector[Ref] = value.refs
  }

  /** ST [addr.at(k)] of lane `lane` of the pair node `pair`, register r(p + lane). */
  final case class StoreLane(addr: Address, pair: Ref, lane: Int) extends LoopNode {
    def mnemonic: String = "ST"
    def operands: Vector[Ref] = Vector(pair)
  }

  /** The operand fields of a vector operation or of ST.W, in order; empty for the other nodes. */
  def fields(n: LoopNode): Vector[VRef] = n match {
    case VCompute(_, a, b) => Vector(a, b)
    case VMac(a, b, c)     => Vector(a, b, c)
    case StoreW(_, v)      => Vector(v)
    case _                 => Vector.empty
  }

  /** Whether the node's value is an aligned register pair (LD.W and the vector operations). */
  def isPair(n: LoopNode): Boolean = n match {
    case _: LoadW | _: VCompute | _: VMac => true
    case _                                => false
  }

  /** Whether the node stores (ST, ST.W, a lane ST), and so has no value. */
  def isStore(n: LoopNode): Boolean = n match {
    case _: Store | _: StoreW | _: StoreLane => true
    case _                                   => false
  }
}

/** An operand field of a W loop node (`dag.Src` on the loop graph): the pair of a pair node, the
  * gather of two scalar nodes into r(p) (`lo`) and r(p+1) (`hi`), or one scalar node for both lanes
  * (only the rs1 of VMUL and VMAC). `refs` flattens the field: a gather gives two refs.
  */
sealed trait VRef extends Product with Serializable {
  def refs: Vector[Ref]
}
object VRef {
  final case class Pair(r: Ref) extends VRef {
    def refs: Vector[Ref] = Vector(r)
  }
  final case class Gather(lo: Ref, hi: Ref) extends VRef {
    def refs: Vector[Ref] = Vector(lo, hi)
  }
  final case class Broadcast(r: Ref) extends VRef {
    def refs: Vector[Ref] = Vector(r)
  }
}

/** `issue(to, k + iterations) >= issue(from, k) + cycles` for every iteration k, between two Each
  * nodes. `iterations` > 0 is a dependence across iterations (the input of RecMII, #68).
  */
final case class LoopEdge(from: Int, to: Int, cycles: Int, iterations: Int, kind: EdgeKind)

/** The nodes of a state: its value before the first iteration (a Before node) and the operation
  * that gives its value for the next iteration (an Each node). In iteration k the state's value is
  * `next` in iteration k - 1, or `init` for k = 0; after the loop, `next` in the last iteration.
  */
final case class StateNodes(init: Int, next: Int)

/** One operation of the fully unrolled loop: `node` in `iteration` (None for Before and After). */
final case class Instance(node: Int, iteration: Option[Int])

/** The loop fully unrolled as a `dag.Dag` (Before nodes, then the Each nodes of each iteration,
  * then After nodes), and where each of its nodes comes from.
  */
final case class Expanded(dag: Dag, origin: Vector[Instance])

/** The dependence graph of a loop (docs/COMPILER.md, "ループ IR"), the input of Modulo Scheduling
  * (#68). Node ids of one place are in program order: an operand with `back` = 0 of an Each node is
  * a lower id.
  */
final case class LoopGraph(
    machine: MachineDescription,
    trips: Int,
    places: Vector[Place],
    nodes: Vector[LoopNode],
    states: Vector[StateNodes]
) {
  def latency(n: Int): Int = machine.operation(nodes(n).mnemonic).latency

  def ids(p: Place): Vector[Int] = nodes.indices.filter(places(_) == p).toVector

  /** The loop unrolled, with every dependence of `Dag.build`: data, and memory order by word in the
    * order of execution (ST -> LD, LD -> ST, ST -> ST).
    */
  lazy val expanded: Expanded = {
    val before = ids(Place.Before)
    val each = ids(Place.Each)
    val after = ids(Place.After)
    val origin = before.map(Instance(_, None)) ++
      (0 until trips).flatMap(k => each.map(Instance(_, Some(k)))) ++
      after.map(Instance(_, None))
    val index = origin.zipWithIndex.toMap
    def resolve(r: Ref, k: Option[Int]): Int = places(r.node) match {
      case Place.Before => index(Instance(r.node, None))
      case Place.Each   =>
        k match {
          case Some(i) if i >= r.back => index(Instance(r.node, Some(i - r.back)))
          case Some(i)                => index(Instance(r.initial(i), None))
          case None                   => index(Instance(r.node, Some(trips - 1 - r.back)))
        }
      case Place.After => index(Instance(r.node, None))
    }
    val dagNodes = mutable.ArrayBuffer.empty[Node]
    val edges = mutable.ArrayBuffer.empty[Edge]
    val lastStore = mutable.HashMap.empty[Int, Int]
    val loadsSinceStore = mutable.HashMap.empty[Int, Vector[Int]].withDefaultValue(Vector.empty)
    def lat(m: String) = machine.operation(m).latency
    def data(from: Int, to: Int): Unit =
      edges += Edge(from, to, lat(dagNodes(from).mnemonic), EdgeKind.Data)
    def src(v: VRef, k: Option[Int]): Src = v match {
      case VRef.Pair(r)        => Src.Pair(resolve(r, k))
      case VRef.Gather(lo, hi) => Src.Gather(resolve(lo, k), resolve(hi, k))
      case VRef.Broadcast(r)   => Src.Broadcast(resolve(r, k))
    }
    def dataFrom(fields: Seq[Src], id: Int): Unit =
      fields.flatMap(_.nodes).distinct.foreach(data(_, id))
    // Memory order by word: LD.W and ST.W have the edges of both their words.
    def read(w: Int, id: Int): Unit = {
      lastStore.get(w).foreach(st => edges += Edge(st, id, lat("ST"), EdgeKind.StoreToLoad))
      loadsSinceStore(w) = loadsSinceStore(w) :+ id
    }
    def write(w: Int, id: Int): Unit = {
      loadsSinceStore(w).foreach(ld => edges += Edge(ld, id, 0, EdgeKind.LoadToStore))
      lastStore.get(w).foreach(st => edges += Edge(st, id, 1, EdgeKind.StoreToStore))
      lastStore(w) = id
      loadsSinceStore(w) = Vector.empty
    }
    origin.zipWithIndex.foreach { case (Instance(n, k), id) =>
      val at = k.getOrElse(0)
      nodes(n) match {
        case LoopNode.Load(a) =>
          val w = a.at(at)
          dagNodes += Node.Load(w)
          read(w, id)
        case LoopNode.Compute(op, a, b) =>
          val (x, y) = (resolve(a, k), resolve(b, k))
          dagNodes += Node.Compute(op, x, y)
          data(x, id)
          if (y != x) data(y, id)
        case LoopNode.Mac(a, b, c) =>
          val (x, y, z) = (resolve(a, k), resolve(b, k), resolve(c, k))
          dagNodes += Node.Mac(x, y, z)
          Seq(x, y, z).distinct.foreach(data(_, id))
        case LoopNode.Store(a, v) =>
          val w = a.at(at)
          val x = resolve(v, k)
          dagNodes += Node.Store(w, x)
          data(x, id)
          write(w, id)
        case LoopNode.LoadW(a) =>
          val w = a.at(at)
          dagNodes += Node.LoadW(w)
          read(w, id)
          read(w + 1, id)
        case LoopNode.VCompute(op, a, b) =>
          val (x, y) = (src(a, k), src(b, k))
          dagNodes += Node.VCompute(op, x, y)
          dataFrom(Seq(x, y), id)
        case LoopNode.VMac(a, b, c) =>
          val (x, y, z) = (src(a, k), src(b, k), src(c, k))
          dagNodes += Node.VMac(x, y, z)
          dataFrom(Seq(x, y, z), id)
        case LoopNode.StoreW(a, v) =>
          val w = a.at(at)
          val x = src(v, k)
          dagNodes += Node.StoreW(w, x)
          dataFrom(Seq(x), id)
          write(w, id)
          write(w + 1, id)
        case LoopNode.StoreLane(a, p, lane) =>
          val w = a.at(at)
          val x = resolve(p, k)
          dagNodes += Node.StoreLane(w, x, lane)
          data(x, id)
          write(w, id)
      }
    }
    assert(edges.forall(e => e.from < e.to), "the expanded loop is not in a topological order")
    Expanded(Dag(machine, dagNodes.toVector, edges.toVector), origin)
  }

  /** The dependences between Each nodes: the edges of `expanded` between two Each instances, with
    * the difference of their iterations. A modulo schedule gives every iteration the same times, so
    * an edge found in some iterations is kept for all of them.
    */
  lazy val edges: Vector[LoopEdge] = {
    val origin = expanded.origin
    val found = expanded.dag.edges.collect {
      case e if origin(e.from).iteration.isDefined && origin(e.to).iteration.isDefined =>
        val (f, t) = (origin(e.from), origin(e.to))
        LoopEdge(f.node, t.node, e.distance, t.iteration.get - f.iteration.get, e.kind)
    }.distinct
    assert(
      found.forall(e => e.iterations > 0 || (e.iterations == 0 && e.from < e.to)),
      "an edge within an iteration goes backwards"
    )
    found
  }
}

object LoopGraph {

  /** Builds the graph of `loop` for `machine` (docs/COMPILER.md, "ループ IR"). Loads of words the body
    * never writes are shared in two ways. A read with stride 0 (and an initial value) loads its
    * word before the loop, once, and the value stays in a register. Reads with the same stride and
    * base modulo the stride form chains: the first reader of each word loads it, and the others use
    * that value `back` iterations later (a word that no iteration loads first is loaded before the
    * loop). A word read both ways, or with two strides, is loaded by each. Loads of words the body
    * writes are never shared. Equal operations share one node; an operation of Before values is
    * itself Before. States are registers: a state's value is its next-value node one iteration
    * back, and its initial value before the first iteration.
    */
  def build(machine: MachineDescription, loop: Loop): LoopGraph = {
    val nodes = mutable.ArrayBuffer.empty[LoopNode]
    val places = mutable.ArrayBuffer.empty[Place]
    val numbering = mutable.HashMap.empty[(Place, Any), Int]
    def add(p: Place, n: LoopNode): Int = { nodes += n; places += p; nodes.size - 1 }
    def shared(p: Place, n: LoopNode): Int = numbering.getOrElseUpdate((p, n), add(p, n))
    def isBefore(r: Ref): Boolean = r.node >= 0 && places(r.node) == Place.Before
    val trips = loop.trips

    val written: Set[Int] = loop.body
      .collect { case Loop.Write(a, _) =>
        (0 until trips).map(a.at)
      }
      .flatten
      .toSet
    def unwritten(a: Address): Boolean = (0 until trips).forall(k => !written(a.at(k)))

    // Reads of unwritten words with a stride: same stride and base modulo the stride may read the
    // same words. A chain splits where no word is shared (bases |stride| * trips or more apart).
    val reads = mutable.LinkedHashSet.empty[Address]
    def collect(v: Loop.Value): Unit = v match {
      case Loop.Read(a) if a.stride != 0 && unwritten(a) => reads += a; ()
      case Loop.Bin(_, x, y)                             => collect(x); collect(y)
      case _                                             => ()
    }
    loop.body.foreach {
      case Loop.Write(_, v)  => collect(v)
      case Loop.Update(_, v) => collect(v)
    }
    val reuse: Map[Address, (Address, Int)] = reads.toVector
      .groupBy(a => (a.stride, Math.floorMod(a.base, Math.abs(a.stride))))
      .values
      .flatMap { group =>
        val s = group.head.stride
        val sorted = group.sortBy(_.base)
        val chains = sorted.tail.foldLeft(Vector(Vector(sorted.head))) { (cs, a) =>
          if (a.base - cs.last.last.base >= Math.abs(s) * trips) cs :+ Vector(a)
          else cs.init :+ (cs.last :+ a)
        }
        chains.flatMap { chain =>
          val leader = if (s > 0) chain.last else chain.head // reads each word first
          chain.map(a => a -> (leader, (leader.base - a.base) / s))
        }
      }
      .toMap

    def beforeLoad(word: Int): Int = shared(Place.Before, LoopNode.Load(Address(word, 0)))
    def compute(p: Place, op: Op, a: Ref, b: Ref): Ref = Ref(shared(p, LoopNode.Compute(op, a, b)))

    // States are placeholders -(s + 1) until their next values are built.
    val init = mutable.ArrayBuffer.empty[Int]
    def value(v: Loop.Value, inBody: Boolean): Ref = v match {
      case Loop.Read(a) if !inBody || (a.stride == 0 && unwritten(a)) => Ref(beforeLoad(a.base))
      case Loop.Read(a) if reuse.contains(a)                          =>
        val (leader, back) = reuse(a)
        val l = shared(Place.Each, LoopNode.Load(leader))
        if (back == 0) Ref(l)
        else Ref(l, back, (0 until back.min(trips)).map(k => beforeLoad(a.at(k))).toVector)
      case Loop.Read(a)       => Ref(add(Place.Each, LoopNode.Load(a))) // a word the body writes
      case Loop.Bin(op, x, y) =>
        val (a, b) = (value(x, inBody), value(y, inBody))
        compute(if (isBefore(a) && isBefore(b)) Place.Before else Place.Each, op, a, b)
      case Loop.StateRef(s) =>
        require(inBody, s"state $s is read outside the body")
        Ref(-(s + 1), 1, Vector(init(s)))
    }
    loop.inits.foreach(v => init += value(v, inBody = false).node)

    val next = mutable.HashMap.empty[Int, Int]
    loop.body.foreach {
      case Loop.Write(a, v)  => add(Place.Each, LoopNode.Store(a, value(v, inBody = true)))
      case Loop.Update(s, v) =>
        val r = value(v, inBody = true)
        require(
          r.back == 0 && r.node >= 0 && places(r.node) == Place.Each &&
            nodes(r.node).isInstanceOf[LoopNode.Compute],
          s"the next value of state $s must be an operation in the loop"
        )
        next(s) = r.node
    }
    def patch(r: Ref): Ref = if (r.node < 0) r.copy(node = next(-r.node - 1)) else r
    nodes.indices.foreach { i =>
      nodes(i) = nodes(i) match {
        case LoopNode.Compute(op, a, b) => LoopNode.Compute(op, patch(a), patch(b))
        case LoopNode.Store(a, v)       => LoopNode.Store(a, patch(v))
        case n                          => n
      }
    }
    loop.finals.foreach(f =>
      add(Place.After, LoopNode.Store(Address(f.addr, 0), Ref(next(f.state))))
    )
    LoopGraph(
      machine,
      trips,
      places.toVector,
      nodes.toVector,
      init.indices.map(s => StateNodes(init(s), next(s))).toVector
    )
  }

  def of(l: LoweredLoop, machine: MachineDescription): LoopGraph = build(machine, l.loop)
}
