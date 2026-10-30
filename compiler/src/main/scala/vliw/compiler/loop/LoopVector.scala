package vliw.compiler.loop

import scala.collection.mutable

import vliw.compiler.dsl.Op

/** Why a loop is not doubled (ADR-0023; docs/COMPILER.md "ループのベクトル化と Modulo Scheduling（W-full）").
  * The decision is information, not a fallback. The names of ADR-0023 are its 既定; `SameValue` is
  * #110's (#110 第5段の Q2. オーナーが異を唱えなければ既定とする推奨で、決定ではない).
  */
sealed abstract class NotDoubled(val name: String) extends Product with Serializable {
  def describe: String = name
}
object NotDoubled {

  /** The trip count is odd: there is no remainder loop (ADR-0023). */
  case object OddTripCount extends NotDoubled("OddTripCount")

  /** A body store has stride 0 (unreachable from a `LoopKernel`: its trip count would be 1). */
  case object ZeroStoreStride extends NotDoubled("ZeroStoreStride")

  /** Every state is a reduction ([[LoopVector.reduction]]): vectorizing needs partial results, a
    * new association order, and the DAG keeps the DSL's order.
    */
  case object AssociationOrder extends NotDoubled("AssociationOrder")

  /** Some state is not a reduction: a recurrence, a scan, or states that read each other. */
  case object LoopState extends NotDoubled("LoopState")

  /** Rule 2: a value before the loop is read at `position`, not at a multiplication's rs1 (#110
    * 第5段の Q2. オーナーが異を唱えなければ既定とする推奨で、決定ではない).
    */
  final case class SameValue(position: String) extends NotDoubled("SameValue") {
    override def describe: String = s"SameValue($position)"
  }

  /** The doubled loop's MII, without the third read port, is more than twice the scalar loop's. */
  case object MiiIncrease extends NotDoubled("MiiIncrease")
}

/** Loop vectorization by 2 (ISA mvp-2, ADR-0023; docs/COMPILER.md "ループのベクトル化と Modulo
  * Scheduling（W-full）"), on the fused scalar loop graph G (after `LoopFusion`, ADR-0019). Iteration
  * k' of the doubled graph runs iterations 2k' (lane 0) and 2k' + 1 (lane 1) of G. Body packing of
  * the loops that are not doubled is #110 stage 5b.
  */
object LoopVector {
  import NotDoubled._

  private def isBefore(g: LoopGraph, r: Ref) = g.places(r.node) == Place.Before

  private def scalarOnly(g: LoopGraph): Unit =
    require(
      g.nodes.forall {
        case _: LoopNode.Load | _: LoopNode.Compute | _: LoopNode.Mac | _: LoopNode.Store => true
        case _                                                                            => false
      },
      "the loop vectorization works on a scalar loop graph"
    )

  /** Every reason of the loop not to double that is decided on G, in this order: `OddTripCount`,
    * `ZeroStoreStride`, `AssociationOrder` or `LoopState`, then `SameValue` for each position in
    * the order of the body. Empty when the loop may double; `MiiIncrease` needs the doubled graph
    * (`ModuloCompiler`).
    */
  def reasons(g: LoopGraph): Vector[NotDoubled] = {
    scalarOnly(g)
    val each = g.ids(Place.Each)
    val odd = Option.when(g.trips % 2 != 0)(OddTripCount)
    val zero = Option.when(
      each.exists(n =>
        g.nodes(n) match {
          case LoopNode.Store(a, _) => a.stride == 0
          case _                    => false
        }
      )
    )(ZeroStoreStride)
    val states = Option.when(g.states.nonEmpty)(
      if (g.states.forall(reduction(g, _))) AssociationOrder else LoopState
    )
    (odd ++ zero ++ states).toVector ++ sameValues(g)
  }

  /** Whether state `s` is a reduction (docs/COMPILER.md; ADR-0023 の補足): its next value `x` is
    * `Compute(Add | Mul)` with the state value `Ref(x, 1)` as exactly one of its two operands, or
    * `Mac(a, b, Ref(x, 1))` with the state value only as the addend; no other Each node reads `x`
    * (not the state value elsewhere, nor `x` itself with back 0; After nodes may); and the other
    * operands read no state, directly or through other nodes.
    */
  def reduction(g: LoopGraph, s: StateNodes): Boolean = {
    val x = s.next
    val nexts = g.states.map(_.next).toSet
    def state(r: Ref) = r.node == x && r.back == 1
    val form: Option[(Int, Vector[Ref])] = g.nodes(x) match {
      case LoopNode.Compute(Op.Add | Op.Mul, a, b) if state(a) && b.node != x =>
        Some((0, Vector(b)))
      case LoopNode.Compute(Op.Add | Op.Mul, a, b) if state(b) && a.node != x =>
        Some((1, Vector(a)))
      case LoopNode.Mac(a, b, c) if state(c) && a.node != x && b.node != x =>
        Some((2, Vector(a, b)))
      case _ => None
    }
    val reads = for {
      c <- g.ids(Place.Each)
      (r, i) <- g.nodes(c).operands.zipWithIndex if r.node == x
    } yield (c, i, r.back)
    g.states.count(_.next == x) == 1 && form.exists { case (i, others) =>
      reads == Vector((x, i, 1)) && others.forall(o => !readsState(g, o, nexts))
    }
  }

  /** Whether `r` reads the value of a state (a next value node, at any distance), directly or
    * through the operands of Each nodes.
    */
  private def readsState(g: LoopGraph, r: Ref, nexts: Set[Int]): Boolean = {
    val seen = mutable.HashSet.empty[Int]
    def go(n: Int): Boolean =
      nexts(n) || (seen
        .add(n) && g.places(n) == Place.Each && g.nodes(n).operands.exists(o => go(o.node)))
    go(r.node)
  }

  /** Rule 2 on G: the positions where an Each node reads a value before the loop, other than a
    * multiplication's rs1 (a broadcast). A multiplication whose rs2 is before the loop and rs1 is
    * not swaps them first, as `dag.Pack` does. The positions are named as `Pack` names them.
    */
  private def sameValues(g: LoopGraph): Vector[NotDoubled] = {
    def multiply(m: String, x: Ref, y: Ref, rest: Vector[Ref]) = {
      val b = if (isBefore(g, y) && !isBefore(g, x)) x else y // the rs2 after the swap
      (b +: rest).zipWithIndex.collect {
        case (r, i) if isBefore(g, r) => SameValue(s"rs${i + 2} of $m")
      }
    }
    g.ids(Place.Each)
      .flatMap { n =>
        g.nodes(n) match {
          case LoopNode.Compute(Op.Mul, a, b) => multiply("MUL", a, b, Vector.empty)
          case LoopNode.Mac(a, b, c)          => multiply("MAC", a, b, Vector(c))
          case LoopNode.Compute(op, a, b)     =>
            Vector(a, b).zipWithIndex.collect {
              case (r, i) if isBefore(g, r) => SameValue(s"rs${i + 1} of ${op.mnemonic}")
            }
          case LoopNode.Store(_, v) if isBefore(g, v) => Vector(SameValue("the store source"))
          case _                                      => Vector.empty
        }
      }
      .distinct
  }

  /** Whether G has a rule-1 candidate: two body stores at `Address(b, s)` and `Address(b + 1, s)`
    * with the same stride (adjacent in every iteration), or two stores after the loop at w and w + 1.
    * A body store never pairs with a final. Without one no pair can form, so the loop has nothing
    * to pack (ADR-0023's `NothingToPack`); #110 stage 5a decides only this, and stage 5b packs the
    * body.
    */
  def ruleOneCandidate(g: LoopGraph): Boolean = {
    val stores = g.nodes.indices.collect {
      case n if g.places(n) != Place.Before && g.nodes(n).isInstanceOf[LoopNode.Store] =>
        (g.places(n), g.nodes(n).asInstanceOf[LoopNode.Store].addr)
    }.toSet
    stores.exists { case (p, a) => stores.contains((p, Address(a.base + 1, a.stride))) }
  }

  /** The words the loop writes, in every iteration. */
  private def written(g: LoopGraph): Set[Int] = g.nodes.indices.flatMap { n =>
    (g.nodes(n), g.places(n)) match {
      case (LoopNode.Store(a, _), Place.Each) => (0 until g.trips).map(a.at)
      case (LoopNode.Store(a, _), _)          => Seq(a.base)
      case _                                  => Nil
    }
  }.toSet

  /** The doubled graph of G, which has no reason not to double ([[reasons]] is empty): an even trip
    * count, no state, stores with nonzero strides, and values before the loop only at a
    * multiplication's rs1. Iteration k' runs G's iterations 2k' (lane 0) and 2k' + 1 (lane 1); the
    * result has `trips / 2` iterations.
    *
    *   - Reads (rule 3): the LD sharing of G (ADR-0014) is undone. A LD at `Address(b, s)` read
    *     `back` iterations later reads `Address(b − s × back, s)`; its lanes read b' + 2s k' and b'
    *     + s + 2s k'. With s = 1 and LD.W (W-full), and both words unwritten, that is LD.W at
    *     `Address(b', 2)` (b' may be odd), shared again by ADR-0014's rule on stride 2. Otherwise
    *     it is a gather of two LDs at `Address(b', 2s)` and `Address(b' + s, 2s)`, with LDs of its
    *     own: gathers are not shared across iterations (an exception to ADR-0014; #110 第5段の Q3.
    *     オーナーが異を唱えなければ既定とする推奨で、決定ではない).
    *   - Values before the loop: a broadcast at a multiplication's rs1 (rule 2).
    *   - Each operation becomes VADD, VSUB, VMUL or VMAC (value-numbered, rule 6).
    *   - Stores (rule 5): with s = 1 and ST.W, ST.W at `Address(b, 2)`; otherwise two lane STs at
    *     `Address(b, 2s)` and `Address(b + s, 2s)`, or, when the stored value is a gather (a copy),
    *     two STs of its lane LDs.
    *
    * Node ids are the post-order from G's stores in G's order, fields in order (as
    * `LoopGraph.build` numbers a body); shared nodes keep their first id, and LD.W leaders are made
    * where first read (既定).
    */
  def double(g: LoopGraph): LoopGraph = {
    require(reasons(g).isEmpty, s"the loop does not double: ${reasons(g).map(_.describe)}")
    require(g.ids(Place.After).isEmpty, "a loop without states has nothing after it")
    val m = g.machine
    require(m.hasVector, s"${m.machineId} has no W")
    val wide = m.hasWideMemory
    val trips = g.trips / 2
    val words = written(g)
    val each = g.ids(Place.Each)

    val nodes = mutable.ArrayBuffer.empty[LoopNode]
    val places = mutable.ArrayBuffer.empty[Place]
    val numbering = mutable.HashMap.empty[(Place, LoopNode), Int]
    def add(p: Place, n: LoopNode): Int = { nodes += n; places += p; nodes.size - 1 }
    def shared(p: Place, n: LoopNode): Int = numbering.getOrElseUpdate((p, n), add(p, n))

    // Values before the loop, copied with their operands.
    val before = mutable.HashMap.empty[Int, Int]
    def copy(n: Int): Int = before.getOrElseUpdate(
      n,
      g.nodes(n) match {
        case l: LoopNode.Load           => shared(Place.Before, l)
        case LoopNode.Compute(op, a, b) =>
          val (x, y) = (copy(a.node), copy(b.node))
          shared(Place.Before, LoopNode.Compute(op, Ref(x), Ref(y)))
        case LoopNode.Mac(a, b, c) =>
          val (x, y, z) = (copy(a.node), copy(b.node), copy(c.node))
          shared(Place.Before, LoopNode.Mac(Ref(x), Ref(y), Ref(z)))
        case other => sys.error(s"$other before the loop")
      }
    )

    // The word that an Each operand of G reads in iteration 2k', when it is a LD: the sharing undone.
    def word(r: Ref): Option[Address] = g.nodes(r.node) match {
      case LoopNode.Load(a) if g.places(r.node) == Place.Each =>
        require((0 until g.trips).forall(k => !words(a.at(k))), s"the loop reads a word it writes")
        Some(Address(a.base - a.stride * r.back, a.stride))
      case _ => None
    }
    def unwritten(a: Address) = (0 until trips).forall(k => !words(a.at(k)) && !words(a.at(k) + 1))
    // LD.W at `Address(b', 2)` for the read `Address(b', 1)` on W-full; None for a gather.
    def wideRead(read: Address): Option[Address] =
      Option
        .when(wide && read.stride == 1 && unwritten(Address(read.base, 2)))(Address(read.base, 2))

    // The LD.W reads, shared by ADR-0014's rule on stride 2: by (stride, base mod stride), chains
    // split where no word is shared, the highest base leads and the others read it `back` later.
    val wideReads = mutable.LinkedHashSet.empty[Address]
    for (n <- each; r <- g.nodes(n).operands; read <- word(r); w <- wideRead(read)) wideReads += w
    val chains: Map[Address, (Address, Int)] = wideReads.toVector
      .groupBy(a => (a.stride, Math.floorMod(a.base, a.stride)))
      .values
      .flatMap { group =>
        val sorted = group.sortBy(_.base)
        val split = sorted.tail.foldLeft(Vector(Vector(sorted.head))) { (cs, a) =>
          if (a.base - cs.last.last.base >= a.stride * trips) cs :+ Vector(a)
          else cs.init :+ (cs.last :+ a)
        }
        split.flatMap { chain =>
          val leader = chain.last
          chain.map(a => a -> (leader, (leader.base - a.base) / a.stride))
        }
      }
      .toMap
    def loadW(a: Address): Ref = {
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

    // The two LDs of a gather, its own (value-numbered per gather, not shared across iterations).
    val lanes = mutable.HashMap.empty[(Address, Address), (Int, Int)]
    def lanesOf(lo: Address, hi: Address): (Int, Int) = lanes.getOrElseUpdate(
      (lo, hi), {
        val l0 = add(Place.Each, LoopNode.Load(lo))
        (l0, add(Place.Each, LoopNode.Load(hi)))
      }
    )
    def gather(read: Address): VRef.Gather = {
      val (l0, l1) = lanesOf(
        Address(read.base, 2 * read.stride),
        Address(read.base + read.stride, 2 * read.stride)
      )
      VRef.Gather(Ref(l0), Ref(l1))
    }

    // An Each operand of G as a field of the doubled graph.
    val vectors = mutable.HashMap.empty[Int, Int]
    def field(r: Ref): VRef =
      if (isBefore(g, r)) VRef.Broadcast(Ref(copy(r.node)))
      else
        word(r) match {
          case Some(read) => wideRead(read).fold[VRef](gather(read))(w => VRef.Pair(loadW(w)))
          case None       =>
            require(r.back == 0, s"$r: a value of an earlier iteration in a loop without states")
            VRef.Pair(Ref(vector(r.node)))
        }
    def vector(n: Int): Int = vectors.getOrElseUpdate(
      n,
      g.nodes(n) match {
        case LoopNode.Compute(op, x, y) =>
          // A multiplicand before the loop moves to rs1 (multiplication commutes).
          val (a, b) = if (op == Op.Mul && isBefore(g, y) && !isBefore(g, x)) (y, x) else (x, y)
          val (fa, fb) = (field(a), field(b))
          shared(Place.Each, LoopNode.VCompute(op, fa, fb))
        case LoopNode.Mac(x, y, z) =>
          val (a, b) = if (isBefore(g, y) && !isBefore(g, x)) (y, x) else (x, y)
          val (fa, fb, fc) = (field(a), field(b), field(z))
          shared(Place.Each, LoopNode.VMac(fa, fb, fc))
        case other => sys.error(s"$other is not an operation")
      }
    )

    each.foreach { n =>
      g.nodes(n) match {
        case LoopNode.Store(st, v) =>
          val lane0 = Address(st.base, 2 * st.stride)
          val lane1 = Address(st.base + st.stride, 2 * st.stride)
          field(v) match {
            case p @ VRef.Pair(r) =>
              if (wide && st.stride == 1) add(Place.Each, LoopNode.StoreW(Address(st.base, 2), p))
              else {
                add(Place.Each, LoopNode.StoreLane(lane0, r, 0))
                add(Place.Each, LoopNode.StoreLane(lane1, r, 1))
              }
            case gather @ VRef.Gather(lo, hi) =>
              if (wide && st.stride == 1)
                add(Place.Each, LoopNode.StoreW(Address(st.base, 2), gather))
              else {
                add(Place.Each, LoopNode.Store(lane0, lo))
                add(Place.Each, LoopNode.Store(lane1, hi))
              }
            case b: VRef.Broadcast => sys.error(s"a store of $b")
          }
        case _ => ()
      }
    }
    LoopGraph(m, trips, places.toVector, nodes.toVector, Vector.empty)
  }
}
