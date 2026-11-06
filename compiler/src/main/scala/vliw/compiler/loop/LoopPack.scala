package vliw.compiler.loop

import scala.collection.mutable

import vliw.compiler.dag.{EdgeKind, LaneReason}
import vliw.compiler.dsl.Op

/** A store pair of a packed loop: ST.W at `addr`, an Each `Address(b, s)` (words b + s × k and b +
  * 1 + s × k in iteration k) or an After `Address(w, 0)`.
  */
final case class LoopPair(place: Place, addr: Address) {
  def describe: String = place match {
    case Place.Each => s"each ${addr.base}, stride ${addr.stride}"
    case p          => s"${p.name} ${addr.base}"
  }
}

/** A store of G that stays a scalar lane, with the reason (`dag.Pack`'s names, ADR-0023).
  * `initialPair` says that the reason stands for a limit of the loop IR, not for the SLP rule of
  * its name: a vector state's initial pair must be one pair node before the loop, so equal initial
  * values (`SameValue("the initial value of a state")`) and an initial pair that would be a gather
  * (`NeededElsewhere` of lane 0's initial value) do not pack. `LaneReason` is sealed in
  * `dag/Pack.scala`, so the names stand in.
  */
final case class LoopLane(
    place: Place,
    addr: Address,
    reason: LaneReason,
    initialPair: Boolean = false
) {
  def describe: String = reason.name + (if (initialPair) " (initial pair)" else "")
}

/** The body of a loop packed by [[LoopPack]]: the store pairs (by address), every other store of G
  * as a scalar lane, and the number of vector states.
  */
final case class LoopBody(
    pairs: Vector[LoopPair],
    scalarLanes: Vector[LoopLane],
    vectorStates: Int
) {

  /** No store pair packs (ADR-0023's `NothingToPack`). */
  def nothingToPack: Boolean = pairs.isEmpty

  /** For messages: the pairs, the lanes with their reasons, and the vector states. */
  def describe: String = {
    def count(n: Int, what: String) = s"$n $what${if (n == 1) "" else "s"}"
    val after = pairs.filter(_.place == Place.After).map(_.addr.base)
    val where = pairs.filter(_.place == Place.Each).map(_.describe) ++
      Option.when(after.nonEmpty)(s"after ${after.mkString(", ")}")
    val reasons = scalarLanes.map(_.describe)
    val why = reasons.distinct match {
      case Vector()  => ""
      case Vector(r) => s" ($r)"
      case rs        => rs.map(r => s"${reasons.count(_ == r)} $r").mkString(" (", ", ", ")")
    }
    s"body: ${count(pairs.size, "pair")}${if (where.isEmpty) "" else where.mkString(" (", "; ", ")")}, " +
      s"${count(scalarLanes.size, "scalar lane")}$why" +
      (if (vectorStates > 0) s", ${count(vectorStates, "vector state")}" else "")
  }
}

/** The result of [[LoopPack.pack]]: G (`scalar`), the packed loop graph (G itself when nothing
  * packs) and the body.
  */
final case class LoopPacking(scalar: LoopGraph, graph: LoopGraph, body: LoopBody)

/** Body packing (ISA mvp-2, #110 stage 5b; ADR-0023 and docs/COMPILER.md "ループのベクトル化と Modulo
  * Scheduling（W-full）"): the store pairs of one iteration of a loop that is not doubled, packed by
  * `dag.Pack`'s SLP rules on the fused scalar loop graph G, on a machine with LD.W. It is a loop
  * port of `Pack`'s state machine: the candidates of rule 1 in two passes, the lockstep walk (rules
  * 2-4, 6), homes (rule 4), `Cycle` and `StoredTwice`, with `Pack`'s lane reasons. It adds what a
  * loop has and a DAG has not: reads compared without ADR-0014's sharing, LD.W chains of any
  * stride, values before the loop, and vector states.
  */
object LoopPack {

  /** A candidate of rule 1: the stores `st0` (at `addr`) and `st1` (at the next word) of G. */
  final case class Candidate(place: Place, addr: Address, st0: Int, st1: Int)

  /** Packs G (`machine.hasWideMemory`): the packed graph with its pairs, or G with nothing to pack.
    */
  def pack(g: LoopGraph): LoopPacking = packInOrder(g, storePairs(g))

  /** [[pack]] with the candidates tried in the order `candidates`, for `LoopPackSpec` to compare
    * #110 第4段の Q1 (a) (`byAddress`) with (b) ([[storePairs]]); `ModuloCompiler` uses [[pack]].
    */
  private[loop] def packInOrder(g: LoopGraph, candidates: Vector[Candidate]): LoopPacking = {
    require(g.machine.hasWideMemory, s"${g.machine.machineId} has no LD.W: body packing is W-full")
    new LoopPacker(g, oneIteration = false, candidates).run()
  }

  /** The decisions (pairs and lanes) of the walk as `Pack` walks one iteration of the unrolled
    * loop, where a value before the loop is a node like any other: a computed value before the loop
    * may be a gather lane or a vector lane next to a value of the iteration (which a packed loop
    * cannot express, so [[pack]] rejects it). The one-iteration oracle of `LoopPackSpec` compares
    * it with `Pack`; it is never compiled.
    */
  private[loop] def packOneIteration(g: LoopGraph): LoopBody =
    new LoopPacker(g, oneIteration = true, storePairs(g, oneIteration = true)).decisions()

  /** The candidates of rule 1, by address from the lowest: two body stores at `Address(b, s)` and
    * `Address(b + 1, s)` with the same stride (adjacent in every iteration), or two stores after
    * the loop at w and w + 1. A body store never pairs with a store after the loop.
    */
  def byAddress(g: LoopGraph): Vector[Candidate] = {
    val stores = g.nodes.indices.collect {
      case n if g.places(n) != Place.Before && g.nodes(n).isInstanceOf[LoopNode.Store] =>
        (g.places(n), g.nodes(n).asInstanceOf[LoopNode.Store].addr, n)
    }
    (for {
      (p0, a0, s0) <- stores
      (p1, a1, s1) <- stores
      if p0 == p1 && a1 == Address(a0.base + 1, a0.stride)
    } yield Candidate(p0, a0, s0, s1)).toVector
      .sortBy(c => (c.addr.base, c.addr.stride, c.st0, c.st1))
  }

  /** The candidates in the order they are tried, as `dag.Pack.storePairs` (#110 第4段の Q1 (b), which
    * the owner adopted on 2026-11-06): first the gather-free ones (`gatherFree`) by address, then
    * every candidate by address. A candidate is skipped when an earlier pair holds one of its
    * stores.
    *
    * Rule 1 as written (one pass, Q1 (a)) is `byAddress` alone; nothing else changes.
    */
  def storePairs(g: LoopGraph): Vector[Candidate] = storePairs(g, oneIteration = false)

  private[loop] def storePairs(g: LoopGraph, oneIteration: Boolean): Vector[Candidate] = {
    val byAddress = this.byAddress(g)
    byAddress.filter(gatherFree(g, _, oneIteration)) ++ byAddress
  }

  /** Whether the candidate needs no gather (pass 1), as `dag.Pack.gatherFree` on G. Each of its
    * stores is its words' only store, the stored values differ and are the same operation in the
    * same place, read with back 0 (a store of a state value is tried in pass 2 only: in iteration 0
    * it stores the initial values). The lockstep walk (the multiplicand equal in both lanes moves
    * to rs1) reaches only: the same operation in the same place in both lanes; the same ref in both
    * lanes at a multiplication's rs1; two LDs whose reads without ADR-0014's sharing
    * (`LoopVector.unshared`) have the same place and stride, bases b and b + 1, and words that no
    * iteration writes; and two state values `Ref(n0, 1)` and `Ref(n1, 1)` of different states whose
    * next values walk gather-free (a cycle counts as free) and whose initial pair is gather-free.
    * It depends on G's shape alone, as in `Pack` (ADR-0012).
    */
  def gatherFree(g: LoopGraph, c: Candidate): Boolean = gatherFree(g, c, oneIteration = false)

  private[loop] def gatherFree(g: LoopGraph, c: Candidate, oneIteration: Boolean): Boolean = {
    val writes = this.writes(g)
    val states = stateOfNext(g)
    def samePlace(n0: Int, n1: Int) = oneIteration || g.places(n0) == g.places(n1)
    val memo = mutable.Map.empty[(Ref, Ref, Boolean), Boolean]
    val busy = mutable.Set.empty[(Int, Int)]
    def lanes(r0: Ref, r1: Ref, broadcast: Boolean): Boolean =
      if (r0 == r1) broadcast
      else
        memo.getOrElseUpdate(
          (r0, r1, broadcast),
          (LoopVector.unshared(g, r0), LoopVector.unshared(g, r1)) match {
            case (Some((p0, a0)), Some((p1, a1))) =>
              p0 == p1 && a0.stride == a1.stride && a1.base == a0.base + 1 &&
              unwrittenPair(g, writes, p0, a0)
            case (None, None) if r0.back == 0 && r1.back == 0 => operation(r0.node, r1.node)
            case (None, None) if r0.back == 1 && r1.back == 1 =>
              (states.get(r0.node), states.get(r1.node)) match {
                case (Some(s0), Some(s1)) if s0 != s1 =>
                  operation(r0.node, r1.node) &&
                  lanes(Ref(g.states(s0).init), Ref(g.states(s1).init), broadcast = false)
                case _ => false
              }
            case _ => false
          }
        )
    def operation(v0: Int, v1: Int): Boolean =
      if (busy((v0, v1))) true // a vector state reaching itself
      else if (!samePlace(v0, v1)) false
      else {
        busy += ((v0, v1))
        val free = (g.nodes(v0), g.nodes(v1)) match {
          case (LoopNode.Compute(op, a0, b0), LoopNode.Compute(op1, a1, b1)) if op == op1 =>
            fields(op == Op.Mul, Vector((a0, a1), (b0, b1)))
          case (LoopNode.Mac(a0, b0, c0), LoopNode.Mac(a1, b1, c1)) =>
            fields(multiply = true, Vector((a0, a1), (b0, b1), (c0, c1)))
          case _ => false
        }
        busy -= ((v0, v1))
        free
      }
    def fields(multiply: Boolean, operands: Vector[(Ref, Ref)]): Boolean = {
      val (a, b) = (operands(0), operands(1))
      val ordered =
        if (multiply && a._1 != a._2 && b._1 == b._2) b +: a +: operands.drop(2) else operands
      ordered.zipWithIndex.forall { case ((x0, x1), k) => lanes(x0, x1, multiply && k == 0) }
    }
    (g.nodes(c.st0), g.nodes(c.st1)) match {
      case (LoopNode.Store(_, v0), LoopNode.Store(_, v1)) =>
        !storedTwice(g, writes, c.st0) && !storedTwice(g, writes, c.st1) && v0 != v1 &&
        v0.back == 0 && v1.back == 0 && operation(v0.node, v1.node)
      case _ => false
    }
  }

  /** How many times the loop writes each word: a body store in every iteration, an After store
    * once.
    */
  private[loop] def writes(g: LoopGraph): Map[Int, Int] =
    g.nodes.indices.flatMap(words(g, _)).groupBy(identity).view.mapValues(_.size).toMap

  /** The words the store `st` of G writes. */
  private[loop] def words(g: LoopGraph, st: Int): Seq[Int] = (g.nodes(st), g.places(st)) match {
    case (LoopNode.Store(a, _), Place.Each) => (0 until g.trips).map(a.at)
    case (LoopNode.Store(a, _), _)          => Seq(a.base)
    case _                                  => Nil
  }

  /** A word of the store `st` is written by another store, or twice by `st`. */
  private[loop] def storedTwice(g: LoopGraph, writes: Map[Int, Int], st: Int): Boolean = {
    val ws = words(g, st)
    ws.distinct.size != ws.size || ws.exists(w => writes.getOrElse(w, 0) > 1)
  }

  /** No iteration writes the words of the read `a` in `p` nor the words after them (a LD.W). */
  private[loop] def unwrittenPair(g: LoopGraph, writes: Map[Int, Int], p: Place, a: Address) =
    if (p == Place.Each)
      (0 until g.trips).forall(k => !writes.contains(a.at(k)) && !writes.contains(a.at(k) + 1))
    else !writes.contains(a.base) && !writes.contains(a.base + 1)

  /** No iteration writes the words of the read `a` in `p`. */
  private[loop] def unwrittenWord(g: LoopGraph, writes: Map[Int, Int], p: Place, a: Address) =
    if (p == Place.Each) (0 until g.trips).forall(k => !writes.contains(a.at(k)))
    else !writes.contains(a.base)

  /** The state of each node that is the next value of exactly one state. */
  private[loop] def stateOfNext(g: LoopGraph): Map[Int, Int] =
    g.states.zipWithIndex.groupBy(_._1.next).collect { case (n, Seq((_, s))) => n -> s }
}

/** One packing of G (`LoopPack`), trying `candidates` in order. In `oneIteration` mode, places do
  * not separate values (the walk of `Pack` on one iteration); that mode only decides, and is never
  * built.
  */
private final class LoopPacker(
    g: LoopGraph,
    oneIteration: Boolean,
    candidates: Vector[LoopPack.Candidate]
) {
  import LoopPacker._

  private val trips = g.trips
  private val writes = LoopPack.writes(g)
  private val nextOf = LoopPack.stateOfNext(g)
  private def storedTwice(st: Int) = LoopPack.storedTwice(g, writes, st)
  private def samePlace(n0: Int, n1: Int) = oneIteration || g.places(n0) == g.places(n1)
  private def valueOf(st: Int): Ref = g.nodes(st) match {
    case LoopNode.Store(_, v) => v
    case other                => sys.error(s"not a ST: $other")
  }

  /** Each node's readers: (reader, ref), through an operand (any back) or through `Ref.initial`.
    */
  private val readers: Map[Int, Vector[(Int, Ref)]] = {
    val b = mutable.Map.empty[Int, Vector[(Int, Ref)]].withDefaultValue(Vector.empty)
    for (n <- g.nodes.indices; r <- g.nodes(n).operands) {
      b(r.node) = b(r.node) :+ ((n, r))
      r.initial.distinct.foreach(x => b(x) = b(x) :+ ((n, r)))
    }
    b.toMap.withDefaultValue(Vector.empty)
  }

  private val StoreSource = Position("the store source", broadcast = false)
  private val StateInit = Position("the initial value of a state", broadcast = false)
  private def position(mnemonic: String, field: Int) =
    Position(s"rs$field of $mnemonic", broadcast = field == 1 && Set("MUL", "MAC")(mnemonic))
  private def reject[A](r: LaneReason): Result[A] = Left(Rejection(r, initialPair = false))

  /** Every candidate in order (`LoopPack.storePairs`); a candidate is skipped when an earlier pair
    * holds one of its stores, and the first rejection of a store gives its reason, unless the store
    * pairs later.
    */
  private def decide(): (State, Map[Int, Rejection]) =
    candidates
      .foldLeft((State.empty, Map.empty[Int, Rejection])) {
        case ((s, rs), c) if s.paired(c.st0) || s.paired(c.st1) => (s, rs)
        case ((s, rs), c)                                       =>
          candidate(s, c) match {
            case Right(next) => (next, rs)
            case Left(r)     => (s, rs ++ Seq(c.st0, c.st1).filterNot(rs.contains).map(_ -> r))
          }
      }

  private def body(s: State, rejections: Map[Int, Rejection]): LoopBody = {
    val stores = g.nodes.indices.filter(n =>
      g.places(n) != Place.Before && g.nodes(n).isInstanceOf[LoopNode.Store]
    )
    val lanes = stores
      .filterNot(s.paired)
      .map { st =>
        val a = g.nodes(st).asInstanceOf[LoopNode.Store].addr
        if (storedTwice(st)) LoopLane(g.places(st), a, LaneReason.StoredTwice)
        else
          rejections.get(st) match {
            case Some(r) => LoopLane(g.places(st), a, r.reason, r.initialPair)
            case None    => LoopLane(g.places(st), a, LaneReason.NoNeighbour)
          }
      }
      .toVector
    LoopBody(
      s.pairs.map(p => LoopPair(p.c.place, p.c.addr)).sortBy(p => (p.addr.base, p.addr.stride)),
      lanes,
      s.states.size
    )
  }

  def decisions(): LoopBody = {
    val (s, rejections) = decide()
    body(s, rejections)
  }

  def run(): LoopPacking = {
    val (s, rejections) = decide()
    val b = body(s, rejections)
    LoopPacking(g, if (b.nothingToPack) g else build(s), b)
  }

  /** The candidate tried on the state of the pairs accepted so far: the state with it accepted, or
    * why it is rejected.
    */
  private def candidate(s: State, c: LoopPack.Candidate): Result[State] =
    if (storedTwice(c.st0) || storedTwice(c.st1)) reject(LaneReason.StoredTwice)
    else
      walk(s, valueOf(c.st0), valueOf(c.st1), StoreSource, c.place).flatMap { case (walked, root) =>
        val next = walked.copy(pairs = walked.pairs :+ StorePair(c, root))
        homes(next) match {
          case Some(n)              => reject(LaneReason.NeededElsewhere(n))
          case None if cyclic(next) => reject(LaneReason.Cycle)
          case None                 => Right(next)
        }
      }

  /** Rules 2-4 and 6 on the lanes' refs `r0` and `r1` at `pos`, read by a node in `reader`: the
    * field of the pair.
    */
  private def walk(
      s: State,
      r0: Ref,
      r1: Ref,
      pos: Position,
      reader: Place
  ): Result[(State, PSrc)] =
    if (r0 == r1) {
      if (!pos.broadcast) reject(LaneReason.SameValue(pos.name))
      else if (s.home.get(r0.node).exists(_.vector)) reject(LaneReason.NeededElsewhere(r0.node))
      else Right((s, PSrc.Broadcast(r0)))
    } else
      (LoopVector.unshared(g, r0), LoopVector.unshared(g, r1)) match {
        case (Some((p0, a0)), Some((p1, a1)))
            if p0 == p1 && a0.stride == a1.stride && a1.base == a0.base + 1 &&
              LoopPack.unwrittenPair(g, writes, p0, a0) =>
          Right((s.copy(wides = s.wides + ((p0, a0))), PSrc.Wide(p0, a0)))
        case (None, None) if r0.back == 0 && r1.back == 0 && samePlace(r0.node, r1.node) =>
          operation(r0.node, r1.node) match {
            case Some(shape) =>
              vector(s, r0.node, r1.node, shape).map { case (st, k) => (st, PSrc.Pair(k)) }
            case None => gather(s, r0, r1, reader)
          }
        case (None, None) if r0.back == 1 && r1.back == 1 => vectorState(s, r0.node, r1.node)
        case _                                            => gather(s, r0, r1, reader)
      }

  /** The operation and the operand pairs of `n0` and `n1` when they are the same operation. */
  private def operation(n0: Int, n1: Int): Option[Shape] = (g.nodes(n0), g.nodes(n1)) match {
    case (LoopNode.Compute(op, a0, b0), LoopNode.Compute(op1, a1, b1)) if op == op1 =>
      Some(Shape(Some(op), Vector((a0, a1), (b0, b1))))
    case (LoopNode.Mac(a0, b0, c0), LoopNode.Mac(a1, b1, c1)) =>
      Some(Shape(None, Vector((a0, a1), (b0, b1), (c0, c1))))
    case _ => None
  }

  /** The vector node of the lanes `n0` and `n1` (the same operation in the same place), shared by
    * its lanes (rule 6); it may already be accepted or being walked (a vector state reaches
    * itself). The multiplicand equal in both lanes moves to rs1; the fields are walked in order, in
    * the node's place.
    */
  private def vector(s: State, n0: Int, n1: Int, shape: Shape): Result[(State, (Int, Int))] = {
    val key = (n0, n1)
    if (
      s.home.get(n0).contains(Home(key, 0, vector = true)) &&
      s.home.get(n1).contains(Home(key, 1, vector = true))
    ) Right((s, key))
    else
      Seq(n0, n1).find(s.home.contains) match {
        case Some(n) => reject(LaneReason.NeededElsewhere(n)) // one home per node
        case None    =>
          val mnemonic = shape.op.fold("MAC")(_.mnemonic)
          val multiply = mnemonic == "MUL" || mnemonic == "MAC"
          val (a, b) = (shape.operands(0), shape.operands(1))
          val ordered =
            if (multiply && a._1 != a._2 && b._1 == b._2) b +: a +: shape.operands.drop(2)
            else shape.operands
          val homed = s.copy(home =
            s.home + (n0 -> Home(key, 0, vector = true)) + (n1 -> Home(key, 1, vector = true))
          )
          ordered.zipWithIndex
            .foldLeft[Result[(State, Vector[Field])]](Right((homed, Vector.empty))) {
              case (acc, ((x0, x1), k)) =>
                acc.flatMap { case (st, fields) =>
                  walk(st, x0, x1, position(mnemonic, k + 1), g.places(n0)).map { case (nx, src) =>
                    (nx, fields :+ Field(x0, x1, src))
                  }
                }
            }
            .map { case (st, fields) =>
              (st.copy(vectors = st.vectors + (key -> Vec(shape.op, fields))), key)
            }
      }
  }

  /** The values `Ref(n0, 1)` and `Ref(n1, 1)` of two states as a vector state: n0 and n1 are the
    * next values of two different states, the same operation, and the vector node of this walk
    * (new, being walked, or accepted). The vector node reads itself with back 1, and its value in
    * iteration 0 is the initial pair (init(s0), init(s1)), walked once in Before. The loop IR gives
    * a state's iteration-0 value as one node (`Ref.initial`), so the initial pair must be one pair
    * node before the loop: a Before LD.W or a Before vector operation. Equal initial values
    * (`SameValue`) and a pair that would be a gather (`NeededElsewhere` of lane 0's value) are
    * rejected as limits of the loop IR (`LoopLane.initialPair`). Any other value of an earlier
    * iteration does not pair: the state value stays in its own scalar register (`NeededElsewhere`).
    */
  private def vectorState(s: State, n0: Int, n1: Int): Result[(State, PSrc)] =
    (nextOf.get(n0), nextOf.get(n1), operation(n0, n1)) match {
      case (Some(s0), Some(s1), Some(shape)) if s0 != s1 =>
        vector(s, n0, n1, shape).flatMap { case (st, key) =>
          st.states.get(key) match {
            case Some(init) => Right((st, PSrc.State(key, init)))
            case None       =>
              val (i0, i1) = (g.states(s0).init, g.states(s1).init)
              if (i0 == i1)
                Left(Rejection(LaneReason.SameValue(StateInit.name), initialPair = true))
              else
                walk(st, Ref(i0), Ref(i1), StateInit, Place.Before).flatMap {
                  case (next, init @ (_: PSrc.Pair | _: PSrc.Wide)) =>
                    Right((next.copy(states = next.states + (key -> init)), PSrc.State(key, init)))
                  case _ =>
                    Left(Rejection(LaneReason.NeededElsewhere(i0), initialPair = true))
                }
          }
        }
      case _ => reject(LaneReason.NeededElsewhere(n0))
    }

  /** Rule 4: the gather of `r0` and `r1`, shared by its lanes (rule 6). It is before the loop when
    * both lanes are values before the loop (an invariant pair, read by every iteration), otherwise
    * in the reader's place, and in the body for a reader after the loop: a node after the loop
    * reads the body's values in the last iteration, so the gather of two final values is the last
    * iteration's gather of those values, one gather with the body's gather of the same refs (as
    * `Pack` gathers them once on the unrolled loop, whichever pair is walked first). A LD lane
    * whose words no iteration writes is a fresh LD of its read without ADR-0014's sharing: gathers
    * are not shared across iterations (#110 第5段の Q3: オーナーが 2026-11-06 に第5段の範囲として採った; sharing one
    * iteration's pair of refs between the body and after the loop is rule 6, not that exception),
    * and a LD before the loop gathered in the body is loaded in every iteration (rule 4's LD copy
    * in a loop, an exception to ADR-0014's invariants held in registers). Any other lane is the
    * node itself, homed as that gather's lane: it must have back 0, be in the gather's place, and
    * have no other home. A computed value before the loop next to a value of the iteration is the
    * loop-only case of rule 4: it would be a lane of every iteration's gather.
    */
  private def gather(s: State, r0: Ref, r1: Ref, reader: Place): Result[(State, PSrc)] = {
    val at =
      if (Seq(r0, r1).forall(r => r.back == 0 && g.places(r.node) == Place.Before)) Place.Before
      else if (reader == Place.After) Place.Each
      else reader
    val key = (at, (r0, r1))
    def placed(n: Int) = oneIteration || g.places(n) == at
    def lane(st: State, r: Ref, l: Int): Result[(State, GLane)] =
      LoopVector.unshared(g, r) match {
        case Some((p, a)) if LoopPack.unwrittenWord(g, writes, p, a) => Right((st, GLane.Fresh(a)))
        case _                                                       =>
          val n = r.node
          if (r.back != 0 || !placed(n) || st.home.contains(n))
            reject(LaneReason.NeededElsewhere(n))
          else
            Right(
              (
                st.copy(home = st.home + (n -> Home((r0.node, r1.node), l, vector = false))),
                GLane.Node(n)
              )
            )
      }
    s.gathers.get(key) match {
      case Some(src) => Right((s, src))
      case None      =>
        lane(s, r0, 0).flatMap { case (s0, lo) =>
          lane(s0, r1, 1).map { case (s1, hi) =>
            val src = PSrc.Gather(at, lo, hi, s1.gathers.size)
            (s1.copy(gathers = s1.gathers + (key -> src)), src)
          }
        }
    }
  }

  /** Rule 4 on the loop graph: the first computed node in a vector lane with a reader that does not
    * take it from that pair. A reader reads the node through an operand (any back) or through
    * `Ref.initial`; stores after the loop are readers too. Every reader must be the same lane of an
    * accepted vector node whose fields take the node only from this pair (`Pair` with back 0,
    * `State` with back 1, a `State` whose initial pair is this pair through `Ref.initial`), or the
    * store of that lane of a pair whose root is such a field. A reader not walked yet is a scalar,
    * so a node shared with a later tree is not packed (deterministic, conservative). Gather homes
    * are not checked, as in `Pack`.
    */
  private def homes(s: State): Option[Int] = {
    val inPair = s.pairs.flatMap(p => Seq(p.c.st0 -> ((p, 0)), p.c.st1 -> ((p, 1)))).toMap
    def fromPair(n: Int, h: Home, x: Ref, src: PSrc): Boolean = {
      val pair = PSrc.Pair(h.key)
      val direct = x.node != n || (x.back match {
        case 0 => src == pair
        case 1 =>
          src match {
            case PSrc.State(k, _) => k == h.key
            case _                => false
          }
        case _ => false
      })
      val initial = !x.initial.contains(n) || (src match {
        case PSrc.State(_, i) => i == pair
        case _                => false
      })
      direct && initial
    }
    def ok(n: Int, h: Home, reader: Int, ref: Ref): Boolean =
      if (LoopNode.isStore(g.nodes(reader)))
        inPair.get(reader).exists { case (p, lane) =>
          lane == h.lane && fromPair(n, h, ref, p.root)
        }
      else
        s.home.get(reader).exists { hr =>
          hr.vector && hr.lane == h.lane && s.vectors
            .get(hr.key)
            .exists(
              _.fields.forall(f => fromPair(n, h, f.lanes(h.lane), f.src))
            )
        }
    s.home.toSeq.sortBy(_._1).collectFirst {
      case (n, h) if h.vector && !readers(n).forall { case (r, ref) => ok(n, h, r, ref) } => n
    }
  }

  /** Whether the packed graph of one iteration is cyclic: its edges are the back-0 operands of its
    * vector, pair-store and scalar nodes, and the memory order of G within one iteration.
    * Dependences across iterations never make a cycle.
    */
  private def cyclic(s: State): Boolean = {
    def nodeOf(n: Int): PN = s.home.get(n) match {
      case Some(h) if h.vector => PN.V(h.key)
      case _                   => PN.S(n)
    }
    val inPair = s.pairs.flatMap(p => Seq(p.c.st0 -> p, p.c.st1 -> p)).toMap
    def memoryNode(n: Int): PN = inPair.get(n).fold(nodeOf(n))(p => PN.PS(p.c.st0))
    def ofSrc(src: PSrc): Seq[PN] = src match {
      case PSrc.Pair(k)              => Seq(PN.V(k))
      case PSrc.State(_, _)          => Nil
      case PSrc.Wide(_, _)           => Nil
      case PSrc.Broadcast(r)         => if (r.back == 0) Seq(nodeOf(r.node)) else Nil
      case PSrc.Gather(_, lo, hi, _) => Seq(lo, hi).collect { case GLane.Node(n) => PN.S(n) }
    }
    val preds = mutable.Map.empty[PN, Set[PN]]
    def add(p: PN, ds: Seq[PN]): Unit = preds(p) = preds.getOrElse(p, Set.empty) ++ ds
    g.nodes.indices.foreach { n =>
      memoryNode(n) match {
        case p @ PN.V(k)  => add(p, s.vectors(k).fields.flatMap(f => ofSrc(f.src)))
        case p @ PN.PS(_) => add(p, ofSrc(inPair(n).root))
        case p @ PN.S(_) => add(p, g.nodes(n).operands.filter(_.back == 0).map(r => nodeOf(r.node)))
      }
    }
    g.edges.filter(e => e.iterations == 0 && e.kind != EdgeKind.Data).foreach { e =>
      val (f, t) = (memoryNode(e.from), memoryNode(e.to))
      if (f != t) add(t, Seq(f))
    }
    val done = mutable.Set.empty[PN]
    var left = preds.keySet.toSet ++ preds.values.flatten
    var progress = true
    while (left.nonEmpty && progress) {
      val ready = left.filter(p => preds.getOrElse(p, Set.empty).forall(done))
      progress = ready.nonEmpty
      done ++= ready
      left --= ready
    }
    left.nonEmpty
  }

  /** The packed loop graph. Node ids are the post-order from G's roots (every store, and the next
    * value of every scalar state) in G's order, fields in order, as `LoopVector.double` and
    * `LoopGraph.build` number a body; before a LD or a store, its predecessors in G's memory order
    * within one iteration come first, so that the packed graph keeps G's order of every word. A
    * store pair is its ST.W at its first store. A node read with back > 0 that is not made yet (a
    * state's next value, a vector state) is read through a placeholder and made after the root that
    * first reads it, patched at the end: the walk of a root follows only back-0 operands and G's
    * memory order within one iteration, the edges of `cyclic`, so it never reaches a node being
    * made, and a ST.W is placed before every node that follows it in that order. A LD read with
    * back > 0 (a chain of G, ADR-0014) is made where it is first read, as `LoopGraph.build` makes
    * it. Each LD.W reads are shared by ADR-0014's rule on their stride (`LoopVector.chains`); a
    * read k < back iterations from the start reads the Before LD.W `Address(a.at(k), 0)`.
    */
  private def build(s: State): LoopGraph = {
    val nodes = mutable.ArrayBuffer.empty[LoopNode]
    val places = mutable.ArrayBuffer.empty[Place]
    val numbering = mutable.HashMap.empty[(Place, LoopNode), Int]
    def add(p: Place, n: LoopNode): Int = { nodes += n; places += p; nodes.size - 1 }
    def shared(p: Place, n: LoopNode): Int = numbering.getOrElseUpdate((p, n), add(p, n))
    val scalarId = mutable.HashMap.empty[Int, Int]
    val vectorId = mutable.HashMap.empty[(Int, Int), Int]
    val placeholders = mutable.LinkedHashMap.empty[Either[Int, (Int, Int)], Int]
    def placeholder(k: Either[Int, (Int, Int)]): Int =
      placeholders.getOrElseUpdate(k, -(placeholders.size + 1))
    // The nodes read with back > 0 through a placeholder, made after the root (`drain`).
    val pending = mutable.LinkedHashSet.empty[Either[Int, (Int, Int)]]
    def later(k: Either[Int, (Int, Int)]): Int = { pending += k; placeholder(k) }
    val inPair = s.pairs.flatMap(p => Seq(p.c.st0 -> p, p.c.st1 -> p)).toMap
    val memoryPreds = g.edges
      .filter(e => e.iterations == 0 && e.kind != EdgeKind.Data)
      .groupMap(_.to)(_.from)
    val busyS = mutable.Set.empty[Int]
    val busyV = mutable.Set.empty[(Int, Int)]
    val gatherLanes = mutable.HashMap.empty[Int, (Int, Int)]
    val stored = mutable.Set.empty[Int]
    val busyP = mutable.Set.empty[Int]

    val chains =
      LoopVector.chains(s.wides.toVector.collect { case (Place.Each, a) => a }, trips)
    def loadW(p: Place, a: Address): Ref =
      if (p == Place.Before) Ref(shared(Place.Before, LoopNode.LoadW(a)))
      else {
        val (leader, back) = chains(a)
        val l = shared(Place.Each, LoopNode.LoadW(leader))
        if (back == 0) Ref(l)
        else
          Ref(
            l,
            back,
            (0 until back.min(trips))
              .map(k => shared(Place.Before, LoopNode.LoadW(Address(a.at(k), 0))))
              .toVector
          )
      }

    def scalarRef(r: Ref): Ref = {
      val init = r.initial.map(scalar)
      val load = g.nodes(r.node).isInstanceOf[LoopNode.Load] && !busyS(r.node)
      if (r.back > 0 && !scalarId.contains(r.node) && !load)
        Ref(later(Left(r.node)), r.back, init)
      else Ref(scalar(r.node), r.back, init)
    }
    def memoryFirst(n: Int): Unit = memoryPreds.getOrElse(n, Vector.empty).foreach { m =>
      inPair.get(m).fold(scalar(m): Unit)(pairStore)
    }
    def scalar(n: Int): Int = scalarId.get(n) match {
      case Some(i) => i
      case None    =>
        require(!s.home.get(n).exists(_.vector), s"node $n is in a vector lane, not a scalar")
        require(!busyS(n), s"node $n reads itself with back 0")
        busyS += n
        val node = g.nodes(n) match {
          case l: LoopNode.Load           => memoryFirst(n); l
          case LoopNode.Compute(op, a, b) => LoopNode.Compute(op, scalarRef(a), scalarRef(b))
          case LoopNode.Mac(a, b, c)      => LoopNode.Mac(scalarRef(a), scalarRef(b), scalarRef(c))
          case LoopNode.Store(a, v)       =>
            val x = scalarRef(v)
            memoryFirst(n)
            LoopNode.Store(a, x)
          case other => sys.error(s"$other: LoopPack packs a fused scalar loop graph")
        }
        val i = add(g.places(n), node)
        scalarId(n) = i
        i
    }
    def vref(src: PSrc): VRef = src match {
      case PSrc.Broadcast(r)   => VRef.Broadcast(scalarRef(r))
      case PSrc.Wide(p, a)     => VRef.Pair(loadW(p, a))
      case PSrc.Pair(k)        => VRef.Pair(Ref(vector(k)))
      case PSrc.State(k, init) =>
        val i = vref(init) match {
          case VRef.Pair(Ref(n, 0, _)) => n
          case other                   => sys.error(s"the initial pair $other is not one pair node")
        }
        VRef.Pair(Ref(vectorId.getOrElse(k, later(Right(k))), 1, Vector(i)))
      case PSrc.Gather(at, lo, hi, index) =>
        val (l0, l1) = gatherLanes.getOrElseUpdate(
          index, {
            def one(l: GLane) = l match {
              case GLane.Fresh(a) => add(at, LoopNode.Load(a))
              case GLane.Node(n)  => scalar(n)
            }
            val x = one(lo)
            (x, one(hi))
          }
        )
        VRef.Gather(Ref(l0), Ref(l1))
    }
    def vector(k: (Int, Int)): Int = vectorId.get(k) match {
      case Some(i) => i
      case None    =>
        require(!busyV(k), s"the vector node $k reads itself with back 0")
        busyV += k
        val v = s.vectors(k)
        val f = v.fields.map(x => vref(x.src))
        val node =
          v.op.fold[LoopNode](LoopNode.VMac(f(0), f(1), f(2)))(LoopNode.VCompute(_, f(0), f(1)))
        val i = add(g.places(k._1), node)
        vectorId(k) = i
        i
    }
    def pairStore(p: StorePair): Unit =
      if (stored.add(p.c.st0)) {
        busyP += p.c.st0
        val v = vref(p.root)
        memoryFirst(p.c.st0)
        memoryFirst(p.c.st1)
        add(p.c.place, LoopNode.StoreW(p.c.addr, v))
        busyP -= p.c.st0
        ()
      } else
        require(!busyP(p.c.st0), s"the ST.W of ${p.c.addr} comes before and after a node")
    def drain(): Unit = while (pending.nonEmpty) {
      val k = pending.head
      pending -= k
      k match {
        case Left(n)  => scalar(n)
        case Right(v) => vector(v)
      }
    }
    val nexts = g.states.map(_.next).toSet
    g.nodes.indices.foreach { n =>
      g.nodes(n) match {
        case _: LoopNode.Store if inPair.contains(n)          => pairStore(inPair(n))
        case _: LoopNode.Store                                => scalar(n)
        case _ if nexts(n) && !s.home.get(n).exists(_.vector) => scalar(n)
        case _                                                => ()
      }
      drain()
    }
    val target: Map[Int, Int] = placeholders.map {
      case (Left(n), ph)  => ph -> scalarId(n)
      case (Right(k), ph) => ph -> vectorId(k)
    }.toMap
    def fix(r: Ref): Ref = if (r.node < 0) r.copy(node = target(r.node)) else r
    def fixV(v: VRef): VRef = v match {
      case VRef.Pair(r)        => VRef.Pair(fix(r))
      case VRef.Broadcast(r)   => VRef.Broadcast(fix(r))
      case VRef.Gather(lo, hi) => VRef.Gather(fix(lo), fix(hi))
    }
    val fixed = nodes.toVector.map {
      case LoopNode.Compute(op, a, b)  => LoopNode.Compute(op, fix(a), fix(b))
      case LoopNode.Mac(a, b, c)       => LoopNode.Mac(fix(a), fix(b), fix(c))
      case LoopNode.Store(a, v)        => LoopNode.Store(a, fix(v))
      case LoopNode.VCompute(op, a, b) => LoopNode.VCompute(op, fixV(a), fixV(b))
      case LoopNode.VMac(a, b, c)      => LoopNode.VMac(fixV(a), fixV(b), fixV(c))
      case LoopNode.StoreW(a, v)       => LoopNode.StoreW(a, fixV(v))
      case n                           => n
    }
    // The scalar states that remain; a vector state is no `StateNodes` (only `LoopFusion` reads
    // them, and it never sees a W graph).
    val states = g.states.collect {
      case st if scalarId.contains(st.next) && scalarId.contains(st.init) =>
        StateNodes(scalarId(st.init), scalarId(st.next))
    }
    LoopGraph(g.machine, trips, places.toVector, fixed, states)
  }
}

private object LoopPacker {

  /** A rejection: the lane reason, and whether it stands for the loop IR's limit on a vector
    * state's initial pair (`LoopLane.initialPair`).
    */
  final case class Rejection(reason: LaneReason, initialPair: Boolean)
  type Result[A] = Either[Rejection, A]

  /** Where a walk is: a field of an operation, which may be a broadcast only at a multiplication's
    * rs1 (rule 2).
    */
  final case class Position(name: String, broadcast: Boolean)

  /** An operation (None: MAC) and its operand pairs (lane 0, lane 1). */
  final case class Shape(op: Option[Op], operands: Vector[(Ref, Ref)])

  /** A lane of a gather: a fresh LD of a read, or a node of G. */
  sealed trait GLane extends Product with Serializable
  object GLane {
    final case class Fresh(a: Address) extends GLane
    final case class Node(n: Int) extends GLane
  }

  /** A field of a vector operation or of a store pair, before numbering. */
  sealed trait PSrc extends Product with Serializable
  object PSrc {

    /** The vector node of the lanes `key`. */
    final case class Pair(key: (Int, Int)) extends PSrc

    /** The vector state of the lanes `key` (its value one iteration back), with its initial pair.
      */
    final case class State(key: (Int, Int), init: PSrc) extends PSrc

    /** LD.W of `addr` in `place`. */
    final case class Wide(place: Place, addr: Address) extends PSrc

    /** A gather in `at`; `id` numbers it within one packing. */
    final case class Gather(at: Place, lo: GLane, hi: GLane, id: Int) extends PSrc

    /** One ref for both lanes (a multiplication's rs1). */
    final case class Broadcast(r: Ref) extends PSrc
  }

  /** A node of the packed graph of one iteration, for the cycle check. */
  sealed trait PN extends Product with Serializable
  object PN {
    final case class S(n: Int) extends PN
    final case class V(key: (Int, Int)) extends PN
    final case class PS(st0: Int) extends PN
  }

  /** The one home of a node: lane `lane` of the vector node (`vector`) or of the gather walked from
    * the lanes `key`.
    */
  final case class Home(key: (Int, Int), lane: Int, vector: Boolean)

  /** A field of a vector operation: the lanes' operands walked, and the field made of them. */
  final case class Field(lane0: Ref, lane1: Ref, src: PSrc) {
    def lanes(l: Int): Ref = if (l == 0) lane0 else lane1
  }

  /** A vector operation: VADD, VSUB or VMUL (`op`), or VMAC (None). */
  final case class Vec(op: Option[Op], fields: Vector[Field])

  /** An accepted candidate and its stored field. */
  final case class StorePair(c: LoopPack.Candidate, root: PSrc)

  final case class State(
      home: Map[Int, Home],
      vectors: Map[(Int, Int), Vec],
      wides: Set[(Place, Address)],
      gathers: Map[(Place, (Ref, Ref)), PSrc],
      states: Map[(Int, Int), PSrc],
      pairs: Vector[StorePair]
  ) {
    def paired(st: Int): Boolean = pairs.exists(p => p.c.st0 == st || p.c.st1 == st)
  }
  object State {
    val empty: State = State(Map.empty, Map.empty, Set.empty, Map.empty, Map.empty, Vector.empty)
  }
}
