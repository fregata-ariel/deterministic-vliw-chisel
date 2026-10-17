package vliw.compiler.dag

import vliw.compiler.dsl.Op

/** Fusion of a MUL and the ADD that reads it into one MAC (ISA mvp-1; ADR-0019, docs/COMPILER.md
  * "MAC への融合"). A MUL fuses when its only reader is an ADD; the MAC computes the same value, and no
  * value is computed twice or dropped. The DAG depends only on the kernel and the machine.
  */
object Fusion {

  /** The pairs (MUL, ADD) that fuse, by ADD id; none when the machine has no MAC. An ADD fuses one
    * of its operands: a MUL whose only reader is the ADD (a ST or any other reader counts), and of
    * two such MULs the later one (higher id), so that the earlier one stays as the addend. SUB does
    * not fuse, nor an ADD whose two operands are the same MUL.
    */
  def pairs(dag: Dag): Vector[(Int, Int)] = {
    def fusable(m: Int) = dag.nodes(m) match {
      case Node.Compute(Op.Mul, _, _) => dag.readers(m).size == 1
      case _                          => false
    }
    if (!dag.machine.hasMac) Vector.empty
    else
      dag.nodes.indices.flatMap { i =>
        dag.nodes(i) match {
          case Node.Compute(Op.Add, a, b) if a != b =>
            Seq(a, b).filter(fusable).maxOption.map(_ -> i)
          case _ => None
        }
      }.toVector
  }

  /** The DAG with every pair of [[pairs]] fused: the MAC `(m.a, m.b, other operand of the ADD)`
    * takes the ADD's place, and the MUL is removed; every id after it is one lower, so ids stay a
    * topological order. Data edges are made again from the operands (the edges from the MAC have
    * its latency); memory-order edges join LDs and STs only, and keep their ends.
    */
  def fuse(dag: Dag): Dag = {
    val fused = pairs(dag)
    if (fused.isEmpty) dag
    else {
      val mulOf = fused.map(_.swap).toMap
      val removed = fused.map(_._1).toSet
      val id = dag.nodes.indices.scanLeft(0)((n, i) => if (removed(i)) n else n + 1).toVector
      def to(i: Int) = { assert(!removed(i), s"node $i is fused away"); id(i) }
      val nodes = dag.nodes.indices
        .filterNot(removed)
        .map { i =>
          (dag.nodes(i), mulOf.get(i)) match {
            case (Node.Compute(Op.Add, a, b), Some(m)) =>
              dag.nodes(m) match {
                case Node.Compute(Op.Mul, x, y) => Node.Mac(to(x), to(y), to(if (a == m) b else a))
                case other                      => sys.error(s"not a MUL: $other")
              }
            case (Node.Compute(op, a, b), _) => Node.Compute(op, to(a), to(b))
            case (Node.Mac(a, b, c), _)      => Node.Mac(to(a), to(b), to(c))
            case (Node.Store(addr, v), _)    => Node.Store(addr, to(v))
            case (l: Node.Load, _)           => l
          }
        }
        .toVector
      val memory = dag.edges.filter(_.kind != EdgeKind.Data).groupBy(e => to(e.to))
      // For every node in id order: its data edges in operand order, then the memory-order edges
      // into it, as `Dag.build` makes them.
      val edges = nodes.indices.flatMap { i =>
        nodes(i).operands.distinct.map { o =>
          Edge(o, i, dag.machine.operation(nodes(o).mnemonic).latency, EdgeKind.Data)
        } ++ memory.getOrElse(i, Vector.empty).map(e => e.copy(from = to(e.from), to = i))
      }.toVector
      assert(edges.forall(e => e.from < e.to), "node ids are not a topological order")
      Dag(dag.machine, nodes, edges)
    }
  }
}
