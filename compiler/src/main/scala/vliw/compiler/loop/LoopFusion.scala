package vliw.compiler.loop

import vliw.compiler.dsl.Op

/** Fusion of a MUL and the ADD that reads it into one MAC on a loop graph (ISA mvp-1; ADR-0019,
  * docs/COMPILER.md "ループ IR"): the rule of `dag.Fusion`, for every iteration at once. A MUL fuses
  * when its only use is an operand of an ADD of the same place, in the same iteration. Every use
  * counts: an operand of any node with any `back`, an initial value (`Ref.initial`), and a state
  * that starts or ends with it. Whether a loop is fused is decided by the MII
  * (`modulo.FusionChoice`).
  */
object LoopFusion {

  /** The pairs (MUL, ADD) that fuse, by ADD id; none when the machine has no MAC. Of two MULs that
    * can fuse into one ADD, the later one (higher id) fuses. SUB does not fuse, nor an ADD whose
    * two operands are the same MUL (it has two uses).
    */
  def pairs(g: LoopGraph): Vector[(Int, Int)] = {
    val uses = Array.fill(g.nodes.size)(0)
    for (n <- g.nodes; r <- n.operands) {
      uses(r.node) += 1
      r.initial.foreach(uses(_) += 1)
    }
    g.states.foreach { s => uses(s.init) += 1; uses(s.next) += 1 }
    def fusable(add: Int, r: Ref) =
      r.back == 0 && g.places(r.node) == g.places(add) && uses(r.node) == 1 &&
        (g.nodes(r.node) match {
          case LoopNode.Compute(Op.Mul, _, _) => true
          case _                              => false
        })
    if (!g.machine.hasMac) Vector.empty
    else
      g.nodes.indices.flatMap { i =>
        g.nodes(i) match {
          case LoopNode.Compute(Op.Add, a, b) =>
            Seq(a, b).filter(fusable(i, _)).map(_.node).maxOption.map(_ -> i)
          case _ => None
        }
      }.toVector
  }

  /** The graph with every pair of [[pairs]] fused: the MAC `(m.a, m.b, other operand of the ADD)`
    * takes the ADD's place and id, and the MUL is removed; every id after it is one lower, so the
    * ids of each place stay in program order. The states point to the same values. With no pair,
    * the same graph comes back.
    */
  def fuse(g: LoopGraph): LoopGraph = {
    val fused = pairs(g)
    if (fused.isEmpty) g
    else {
      val mulOf = fused.map(_.swap).toMap
      val removed = fused.map(_._1).toSet
      val id = g.nodes.indices.scanLeft(0)((n, i) => if (removed(i)) n else n + 1).toVector
      def to(i: Int) = { assert(!removed(i), s"node $i is fused away"); id(i) }
      def ref(r: Ref) = Ref(to(r.node), r.back, r.initial.map(to))
      val kept = g.nodes.indices.filterNot(removed).toVector
      val nodes = kept.map { i =>
        (g.nodes(i), mulOf.get(i)) match {
          case (LoopNode.Compute(Op.Add, a, b), Some(m)) =>
            g.nodes(m) match {
              case LoopNode.Compute(Op.Mul, x, y) =>
                LoopNode.Mac(ref(x), ref(y), ref(if (a.node == m) b else a))
              case other => sys.error(s"not a MUL: $other")
            }
          case (LoopNode.Compute(op, a, b), _) => LoopNode.Compute(op, ref(a), ref(b))
          case (LoopNode.Mac(a, b, c), _)      => LoopNode.Mac(ref(a), ref(b), ref(c))
          case (LoopNode.Store(addr, v), _)    => LoopNode.Store(addr, ref(v))
          case (l: LoopNode.Load, _)           => l
        }
      }
      g.copy(
        places = kept.map(g.places),
        nodes = nodes,
        states = g.states.map(s => StateNodes(to(s.init), to(s.next)))
      )
    }
  }
}
