package vliw.compiler.dag

import scala.collection.mutable

import vliw.compiler.dsl.Op

/** Why an output word stays a scalar lane of the packed DAG (docs/COMPILER.md "ISA mvp-2（W）"): the
  * SLP rule of ADR-0023 that the store pair broke, or one of two reasons that are not rules
  * (`Cycle`, `StoredTwice`). The names are #110 defaults (既定).
  */
sealed abstract class LaneReason(val name: String, val rule: String)
    extends Product
    with Serializable {
  def text: String

  /** The rule and the text, for messages and records. */
  def describe: String = s"$rule, $text"
}
object LaneReason {

  /** Rule 1: no adjacent output word, or it is already paired. */
  case object NoNeighbour extends LaneReason("NoNeighbour", "rule 1") {
    def text: String = "no adjacent output word, or it is already paired"
  }

  /** Rule 1 on W-reg: the two stored values differ at the root, so no vector operation and no ST.W
    * would remain.
    */
  case object RootDiffers extends LaneReason("RootDiffers", "rule 1") {
    def text: String = "the two stored values differ at the root, and W-reg has no ST.W"
  }

  /** Rule 2: the same node in both lanes outside a multiplication's rs1. */
  final case class SameValue(position: String) extends LaneReason("SameValue", "rule 2") {
    def text: String = s"the same value in both lanes at $position, not a multiplication's rs1"
  }

  /** Rule 4: the computed value of `node` (in the fused DAG) is needed in another pair, in another
    * lane, or as a scalar.
    */
  final case class NeededElsewhere(node: Int) extends LaneReason("NeededElsewhere", "rule 4") {
    def text: String = s"the value of node $node is needed in another pair or as a scalar"
  }

  /** Pairing would make the packed graph cyclic (through shared nodes or memory order). */
  case object Cycle extends LaneReason("Cycle", "no rule") {
    def text: String = "pairing would create a dependence cycle"
  }

  /** A word of the pair is stored more than once. */
  case object StoredTwice extends LaneReason("StoredTwice", "no rule") {
    def text: String = "a word of the pair is stored more than once"
  }
}

/** An output word that stays scalar, with the reason. */
final case class ScalarLane(addr: Int, reason: LaneReason)

/** The store pair of words `addr` and `addr + 1`, packed: `root` is the stored field in the packed
  * DAG (a pair node, or on W-full a gather stored by ST.W).
  */
final case class PackedPair(addr: Int, root: Src)

/** The result of [[Pack]]: the fused scalar DAG it packed (the kernel fallback compiles it), the
  * packed DAG, and every output word in exactly one packed pair or one scalar lane. `copies` is the
  * number of LD nodes `Pack` added, for gather lanes of a word another gather also reads.
  */
final case class Packing(
    scalar: Dag,
    dag: Dag,
    pairs: Vector[PackedPair],
    scalarLanes: Vector[ScalarLane],
    copies: Int
) {

  /** No store pair packs (ADR-0023's `NothingToPack`). */
  def nothingToPack: Boolean = pairs.isEmpty

  /** Nodes of the packed DAG with mnemonic `m` ("ST" counts the lane STs too). */
  def count(m: String): Int = dag.nodes.count(_.mnemonic == m)

  /** The vector operations of the packed DAG. */
  def vectorOperations: Int = dag.nodes.count {
    case _: Node.VCompute | _: Node.VMac => true
    case _                               => false
  }

  /** Lane STs of W-reg pairs. */
  def laneStores: Int = dag.nodes.count(_.isInstanceOf[Node.StoreLane])

  /** Distinct nodes read as a broadcast. */
  def broadcasts: Int =
    dag.nodes.flatMap(_.sources).collect { case Src.Broadcast(n) => n }.distinct.size

  /** Gathers (pairs filled by two scalar operations). */
  def gathers: Int = dag.gathers.size
}

/** Packing into W pairs (ISA mvp-2; ADR-0023 and docs/COMPILER.md "ISA mvp-2（W）"), after `Fusion`
  * and before scheduling, with the fixed SLP rules of ADR-0023. The packed DAG depends only on the
  * kernel and the machine (W-full or W-reg, `MachineDescription.hasWideMemory`); every attempt
  * schedules it.
  */
object Pack {

  /** Packs the fused DAG `scalar` of an mvp-2 machine. */
  def pack(scalar: Dag): Packing = new Packer(scalar).run()

  /** The candidate store pairs of rule 1, as the lower word w of `(w, w + 1)`, in the order they
    * are tried: every w whose words w and w + 1 are both stored, by address from the lowest, over
    * all output words. A candidate is skipped when an earlier one paired one of its words, so a
    * word whose lower pair is rejected can still pair with the next word (既定).
    */
  def storePairs(dag: Dag): Vector[Int] = {
    val stored = dag.nodes.collect { case Node.Store(a, _) => a }.toSet
    stored.toVector.sorted.filter(w => stored(adjacent(w)))
  }

  /** Rule 1: the output word that word `w` pairs with (ST.W [w] writes `w` and `w + 1`). */
  private[dag] def adjacent(w: Int): Int = w + 1
}

/** One pass of [[Pack.pack]]. Ids below `d.nodes.size` are nodes of the fused DAG; ids from there
  * on are the LD copies made for gather lanes.
  */
private final class Packer(d: Dag) {
  import Packer._

  private val wide = d.machine.hasWideMemory
  private val stores: Map[Int, Vector[Int]] =
    d.nodes.indices.toVector
      .collect { case i if d.nodes(i).isInstanceOf[Node.Store] => i }
      .groupBy { i =>
        d.nodes(i) match {
          case Node.Store(a, _) => a
          case other            => sys.error(s"not a ST: $other")
        }
      }
  private def valueOf(st: Int): Int = d.nodes(st) match {
    case Node.Store(_, v) => v
    case other            => sys.error(s"not a ST: $other")
  }

  /** A LD of a word that no ST writes: it may be copied, and with its neighbour it may be a LD.W.
    */
  private def unwrittenLoad(n: Int): Option[Int] = d.nodes(n) match {
    case Node.Load(a) if !d.written(a) => Some(a)
    case _                             => None
  }

  private val StoreSource = Position("the store source", broadcast = false)
  private def position(mnemonic: String, field: Int) =
    Position(s"rs$field of $mnemonic", broadcast = field == 1 && Set("MUL", "MAC")(mnemonic))

  def run(): Packing = {
    val (state, reasons) =
      Pack.storePairs(d).foldLeft((State.empty, Map.empty[Int, LaneReason])) {
        case ((s, reasons), w) if s.paired(w) || s.paired(Pack.adjacent(w)) => (s, reasons)
        case ((s, reasons), w)                                              =>
          candidate(s, w) match {
            case Right(next) => (next, reasons)
            // The first rejection of a word gives its reason, unless it pairs later.
            case Left(r) =>
              val words = Seq(w, Pack.adjacent(w))
              (s, reasons ++ words.filterNot(reasons.contains).map(_ -> r))
          }
      }
    val built = graph(state).getOrElse(sys.error("the accepted pairs are cyclic"))
    val lanes = stores.keys.toVector.sorted.filterNot(state.paired).map { a =>
      ScalarLane(
        a,
        if (stores(a).size > 1) LaneReason.StoredTwice
        else reasons.getOrElse(a, LaneReason.NoNeighbour)
      )
    }
    Packing(
      d,
      built.dag,
      state.pairs.map(p => PackedPair(p.w, built.src(p.root))),
      lanes,
      state.copies.size
    )
  }

  /** The store pair `(w, w + 1)` tried on the state of the pairs accepted so far: the state with it
    * accepted, or why it is rejected. The walk works on a copy of the state.
    */
  private def candidate(s: State, w: Int): Either[LaneReason, State] = {
    val w1 = Pack.adjacent(w)
    if (stores(w).size > 1 || stores(w1).size > 1) Left(LaneReason.StoredTwice)
    else {
      val (st0, st1) = (stores(w).head, stores(w1).head)
      walk(s, valueOf(st0), valueOf(st1), StoreSource).flatMap { case (walked, root) =>
        // On W-reg, a gather at the root would leave no vector operation and no ST.W (既定).
        if (!wide && !root.isInstanceOf[PSrc.Pair]) Left(LaneReason.RootDiffers)
        else {
          val next = walked.copy(pairs = walked.pairs :+ StorePair(w, w1, st0, st1, root))
          homes(next).toLeft(next).flatMap(n => graph(n).toRight(LaneReason.Cycle).map(_ => n))
        }
      }
    }
  }

  /** Rules 2-4 on the two lanes' nodes `v0` and `v1` at position `at`: the field of the pair. */
  private def walk(s: State, v0: Int, v1: Int, at: Position): Either[LaneReason, (State, PSrc)] =
    if (v0 == v1) {
      if (!at.broadcast) Left(LaneReason.SameValue(at.name))
      else if (s.home.get(v0).exists(_.vector)) Left(LaneReason.NeededElsewhere(v0))
      else Right((s, PSrc.Broadcast(v0)))
    } else
      (d.nodes(v0), d.nodes(v1)) match {
        case (Node.Compute(op, a0, b0), Node.Compute(op1, a1, b1)) if op == op1 =>
          vector(s, v0, v1, Some(op), Vector((a0, a1), (b0, b1)))
        case (Node.Mac(a0, b0, c0), Node.Mac(a1, b1, c1)) =>
          vector(s, v0, v1, None, Vector((a0, a1), (b0, b1), (c0, c1)))
        case (Node.Load(w0), Node.Load(w1))
            if wide && w1 == w0 + 1 && unwrittenLoad(v0).nonEmpty && unwrittenLoad(v1).nonEmpty =>
          Right((s.copy(wideLanes = s.wideLanes + (w0 -> ((v0, v1)))), PSrc.Pair(PNode.Wide(w0))))
        case _ => gather(s, v0, v1)
      }

  /** The vector node of the lanes `v0` and `v1` (same operation), shared by its lanes (rule 6). A
    * multiplicand equal in both lanes moves to rs1 (multiplication commutes); its fields are walked
    * in order.
    */
  private def vector(
      s: State,
      v0: Int,
      v1: Int,
      op: Option[Op],
      operands: Vector[(Int, Int)]
  ): Either[LaneReason, (State, PSrc)] = {
    val key = (v0, v1)
    val pair = PSrc.Pair(PNode.Vector(v0, v1))
    if (s.vectors.contains(key)) Right((s, pair))
    else
      Seq(v0, v1).find(s.home.contains) match {
        case Some(n) => Left(LaneReason.NeededElsewhere(n)) // one home per node
        case None    =>
          val mnemonic = op.fold("MAC")(_.mnemonic)
          val multiply = mnemonic == "MUL" || mnemonic == "MAC"
          val (a, b) = (operands(0), operands(1))
          val ordered =
            if (multiply && a._1 != a._2 && b._1 == b._2) b +: a +: operands.drop(2) else operands
          val homed = s.copy(home =
            s.home + (v0 -> Home(key, 0, vector = true)) + (v1 -> Home(key, 1, vector = true))
          )
          ordered.zipWithIndex
            .foldLeft[Either[LaneReason, (State, Vector[Field])]](Right((homed, Vector.empty))) {
              case (acc, ((x0, x1), k)) =>
                acc.flatMap { case (st, fields) =>
                  walk(st, x0, x1, position(mnemonic, k + 1)).map { case (next, src) =>
                    (next, fields :+ Field(x0, x1, src))
                  }
                }
            }
            .map { case (st, fields) =>
              (st.copy(vectors = st.vectors + (key -> Vec(op, fields))), pair)
            }
      }
  }

  /** Rule 4: the gather of `v0` and `v1`, shared by its lanes (rule 6). A lane node that already
    * has a home elsewhere is copied if it is a LD of a word no ST writes; otherwise the pair is not
    * packed (`NeededElsewhere`).
    */
  private def gather(s: State, v0: Int, v1: Int): Either[LaneReason, (State, PSrc)] = {
    val key = (v0, v1)
    s.gathers.get(key) match {
      case Some((lo, hi)) => Right((s, PSrc.Gather(lo, hi)))
      case None           =>
        lane(s, v0, key, 0).flatMap { case (s0, lo) =>
          lane(s0, v1, key, 1).map { case (s1, hi) =>
            (s1.copy(gathers = s1.gathers + (key -> ((lo, hi)))), PSrc.Gather(lo, hi))
          }
        }
    }
  }

  private def lane(s: State, n: Int, key: (Int, Int), l: Int): Either[LaneReason, (State, Int)] =
    if (!s.home.contains(n)) Right((s.copy(home = s.home + (n -> Home(key, l, vector = false))), n))
    else if (unwrittenLoad(n).nonEmpty) {
      val copy = d.nodes.size + s.copies.size
      Right(
        (
          s.copy(
            copies = s.copies :+ Copy(n, key, l),
            home = s.home + (copy -> Home(key, l, vector = false))
          ),
          copy
        )
      )
    } else Left(LaneReason.NeededElsewhere(n))

  /** The first computed node in a vector lane with a reader that does not take it from that pair
    * (rule 4): every reader must be the same lane of a vector node whose fields read the node only
    * from this pair, or a ST of that lane of a pair whose root is this pair. A reader not walked
    * yet is a scalar, so a node shared with a later tree is not packed (deterministic,
    * conservative).
    */
  private def homes(s: State): Option[LaneReason] = {
    val inPair = s.pairs.flatMap(p => Seq(p.st0 -> ((p, 0)), p.st1 -> ((p, 1)))).toMap
    def fromPair(r: Int, n: Int, h: Home): Boolean = {
      val pair = PSrc.Pair(PNode.Vector(h.key._1, h.key._2))
      if (d.nodes(r).isStore) inPair.get(r).exists { case (p, lane) =>
        lane == h.lane && p.root == pair
      }
      else
        s.home.get(r).exists { hr =>
          hr.vector && hr.lane == h.lane &&
          s.vectors(hr.key).fields.forall(f => f.lanes(h.lane) != n || f.src == pair)
        }
    }
    s.home.toSeq.sortBy(_._1).collectFirst {
      case (n, h) if h.vector && !d.readers(n).forall(fromPair(_, n, h)) =>
        LaneReason.NeededElsewhere(n)
    }
  }

  /** The packed graph of `s`: everything reachable backwards from the stores, numbered by Kahn's
    * algorithm with the order keys of docs/COMPILER.md (a scalar node: its id; a pair node or a
    * ST.W: the largest id of its lanes; a lane ST: its ST's id; scalar before pair, then the other
    * lane). None when it is cyclic.
    */
  private def graph(s: State): Option[Built] = {
    val inPair = s.pairs.flatMap(p => Seq(p.st0 -> ((p, 0)), p.st1 -> ((p, 1)))).toMap
    val byWord = s.pairs.map(p => p.w -> p).toMap
    def storeNode(st: Int): PNode = inPair.get(st) match {
      case Some((p, _)) if wide => PNode.PairStore(p.w)
      case Some(_)              => PNode.Lane(st)
      case None                 => PNode.Scalar(st)
    }
    def ofSrc(src: PSrc): Vector[PNode] = src match {
      case PSrc.Pair(p)        => Vector(p)
      case PSrc.Gather(lo, hi) => Vector(PNode.Scalar(lo), PNode.Scalar(hi))
      case PSrc.Broadcast(n)   => Vector(PNode.Scalar(n))
    }
    def operands(p: PNode): Vector[PNode] = p match {
      case PNode.Scalar(n) if n >= d.nodes.size => Vector.empty
      case PNode.Scalar(n)                      =>
        assert(!s.home.get(n).exists(_.vector), s"node $n is in a vector lane, not a scalar")
        d.nodes(n).operands.map(PNode.Scalar(_))
      case PNode.Vector(n0, n1) => s.vectors((n0, n1)).fields.flatMap(f => ofSrc(f.src))
      case PNode.Wide(_)        => Vector.empty
      case PNode.PairStore(w)   => ofSrc(byWord(w).root)
      case PNode.Lane(st)       => ofSrc(inPair(st)._1.root)
    }
    def memoryNode(n: Int): PNode = if (d.nodes(n).isStore) storeNode(n) else PNode.Scalar(n)

    // Reachable from the stores, in a fixed order.
    val roots = d.nodes.indices.filter(d.nodes(_).isStore).map(storeNode).distinct
    val seen = mutable.LinkedHashSet.empty[PNode]
    def visit(p: PNode): Unit = if (seen.add(p)) operands(p).foreach(visit)
    roots.foreach(visit)
    val present = seen.toVector
    val memory = d.edges.filter(_.kind != EdgeKind.Data).flatMap { e =>
      val (from, to) = (memoryNode(e.from), memoryNode(e.to))
      Option.when(from != to && seen(from) && seen(to))((from, to, e))
    }
    val preds: Map[PNode, Set[PNode]] = present.map { p =>
      p -> (operands(p).toSet ++ memory.collect { case (f, `p`, _) => f })
    }.toMap

    def key(p: PNode): (Int, Int, Int, Int) = p match {
      case PNode.Scalar(n) if n < d.nodes.size => (n, 0, 0, 0)
      case PNode.Scalar(n)                     =>
        val c = s.copies(n - d.nodes.size)
        (c.original, 1, if (c.lane == 0) c.key._2 else c.key._1, c.lane)
      case PNode.Vector(n0, n1) => (n0.max(n1), 2, n0.min(n1), 0)
      case PNode.Wide(a)        =>
        val (l0, l1) = s.wideLanes(a)
        (l0.max(l1), 2, l0.min(l1), 1)
      case PNode.PairStore(w) =>
        val p = byWord(w)
        (p.st0.max(p.st1), 3, p.st0.min(p.st1), 0)
      case PNode.Lane(st) => (st, 0, 0, 0)
    }
    val order = Vector.newBuilder[PNode]
    val done = mutable.Set.empty[PNode]
    var left = present.toSet
    var stuck = false
    while (left.nonEmpty && !stuck) {
      left.filter(p => preds(p).subsetOf(done)).minByOption(key) match {
        case Some(p) => order += p; done += p; left -= p
        case None    => stuck = true
      }
    }
    Option.when(!stuck) {
      val ordered = order.result()
      val id = ordered.zipWithIndex.toMap
      def src(p: PSrc): Src = p match {
        case PSrc.Pair(x)        => Src.Pair(id(x))
        case PSrc.Gather(lo, hi) => Src.Gather(id(PNode.Scalar(lo)), id(PNode.Scalar(hi)))
        case PSrc.Broadcast(n)   => Src.Broadcast(id(PNode.Scalar(n)))
      }
      def scalar(n: Int) = id(PNode.Scalar(n))
      val nodes = ordered.map {
        case PNode.Scalar(n) if n >= d.nodes.size => d.nodes(s.copies(n - d.nodes.size).original)
        case PNode.Scalar(n)                      =>
          d.nodes(n) match {
            case l: Node.Load           => l
            case Node.Compute(op, a, b) => Node.Compute(op, scalar(a), scalar(b))
            case Node.Mac(a, b, c)      => Node.Mac(scalar(a), scalar(b), scalar(c))
            case Node.Store(addr, v)    => Node.Store(addr, scalar(v))
            case other                  => sys.error(s"$other: Pack packs a fused scalar DAG")
          }
        case PNode.Vector(n0, n1) =>
          val v = s.vectors((n0, n1))
          val f = v.fields.map(x => src(x.src))
          v.op.fold[Node](Node.VMac(f(0), f(1), f(2)))(op => Node.VCompute(op, f(0), f(1)))
        case PNode.Wide(a)      => Node.LoadW(a)
        case PNode.PairStore(w) => Node.StoreW(w, src(byWord(w).root))
        case PNode.Lane(st)     =>
          val (p, lane) = inPair(st)
          val vector = p.root match {
            case PSrc.Pair(v) => id(v)
            case other        => sys.error(s"a lane ST of $other")
          }
          Node.StoreLane(if (lane == 0) p.w else p.w1, vector, lane)
      }
      def lat(i: Int) = d.machine.operation(nodes(i).mnemonic).latency
      // For every node in id order: its data edges in operand order, then the memory-order edges
      // into it, in the order of the fused DAG's edges.
      val edges = ordered.indices.flatMap { i =>
        nodes(i).operands.distinct.map(o => Edge(o, i, lat(o), EdgeKind.Data)) ++
          memory.collect { case (f, t, e) if t == ordered(i) => Edge(id(f), i, e.distance, e.kind) }
      }.toVector
      assert(edges.forall(e => e.from < e.to), "node ids are not a topological order")
      Built(Dag(d.machine, nodes, edges), src)
    }
  }
}

private object Packer {

  /** Where a walk is: a field of an operation, which may be a broadcast only at a multiplication's
    * rs1 (rule 2).
    */
  final case class Position(name: String, broadcast: Boolean)

  /** A node of the packed graph before numbering. */
  sealed trait PNode extends Product with Serializable
  object PNode {

    /** A node of the fused DAG that stays scalar (also a gather lane), or a LD copy. */
    final case class Scalar(n: Int) extends PNode

    /** The vector operation of the lane nodes `n0` and `n1`. */
    final case class Vector(n0: Int, n1: Int) extends PNode

    /** LD.W [addr]. */
    final case class Wide(addr: Int) extends PNode

    /** ST.W of the store pair of word `w` (W-full). */
    final case class PairStore(w: Int) extends PNode

    /** The lane ST of the ST node `st` of a store pair (W-reg). */
    final case class Lane(st: Int) extends PNode
  }

  /** A field of a vector operation or of a store pair, before numbering. */
  sealed trait PSrc extends Product with Serializable
  object PSrc {
    final case class Pair(of: PNode) extends PSrc
    final case class Gather(lo: Int, hi: Int) extends PSrc
    final case class Broadcast(n: Int) extends PSrc
  }

  /** The one home of a node: lane `lane` of the vector node (`vector`) or of the gather walked from
    * the lanes `key`.
    */
  final case class Home(key: (Int, Int), lane: Int, vector: Boolean)

  /** A field of a vector operation: the lanes' operands walked, and the field made of them. */
  final case class Field(lane0: Int, lane1: Int, src: PSrc) {
    def lanes(l: Int): Int = if (l == 0) lane0 else lane1
  }

  /** A vector operation: VADD, VSUB or VMUL (`op`), or VMAC (None). */
  final case class Vec(op: Option[Op], fields: Vector[Field])

  /** A LD copy of `original` for lane `lane` of the gather walked from `key`. */
  final case class Copy(original: Int, key: (Int, Int), lane: Int)

  /** A store pair: words `w` and `w1` (`Pack.adjacent(w)`), stored by the ST nodes `st0` and `st1`.
    */
  final case class StorePair(w: Int, w1: Int, st0: Int, st1: Int, root: PSrc)

  final case class State(
      home: Map[Int, Home],
      vectors: Map[(Int, Int), Vec],
      wideLanes: Map[Int, (Int, Int)],
      gathers: Map[(Int, Int), (Int, Int)],
      copies: Vector[Copy],
      pairs: Vector[StorePair]
  ) {
    def paired(a: Int): Boolean = pairs.exists(p => p.w == a || p.w1 == a)
  }
  object State {
    val empty: State = State(Map.empty, Map.empty, Map.empty, Map.empty, Vector.empty, Vector.empty)
  }

  /** The packed DAG, and the numbering of the fields. */
  final case class Built(dag: Dag, src: PSrc => Src)
}
