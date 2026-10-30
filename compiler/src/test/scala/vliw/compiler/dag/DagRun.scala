package vliw.compiler.dag

import scala.util.Random

import vliw.compiler.dsl.Op

/** Runs a DAG, scalar or packed (ISA mvp-2), from the definitions of its nodes (docs/COMPILER.md,
  * "DAG" and "ISA mvp-2（W）"), independently of `Pack` and of the scheduler: a scalar node has one
  * value, a pair node two lanes; a gather field reads its two scalar nodes, a broadcast one node
  * for both lanes.
  */
object DagRun {

  /** Executes `d` in `order`, which must respect its edges; fails on an operand not run yet, and on
    * a field that reads a scalar as a pair or a pair as a scalar. Returns the memory after.
    */
  def execute(d: Dag, order: Seq[Int], memory: Map[Int, Long]): Map[Int, Long] = {
    val scalar = new Array[Long](d.nodes.size)
    val pair = new Array[(Long, Long)](d.nodes.size)
    val done = new Array[Boolean](d.nodes.size)
    def ready(i: Int, o: Int) = assert(done(o), s"node $i runs before its operand $o")
    def s(i: Int, o: Int): Long = {
      ready(i, o)
      assert(!d.nodes(o).isPair && !d.nodes(o).isStore, s"node $i reads ${d.nodes(o)} as a scalar")
      scalar(o)
    }
    def p(i: Int, o: Int): (Long, Long) = {
      ready(i, o)
      assert(d.nodes(o).isPair, s"node $i reads ${d.nodes(o)} as a pair")
      pair(o)
    }
    def lanes(i: Int, src: Src): (Long, Long) = src match {
      case Src.Pair(n)        => p(i, n)
      case Src.Gather(lo, hi) => (s(i, lo), s(i, hi))
      case Src.Broadcast(n)   => val v = s(i, n); (v, v)
    }
    order.foldLeft(memory) { (mem, i) =>
      val after = d.nodes(i) match {
        case Node.Load(a)            => scalar(i) = mem(a); mem
        case Node.Compute(op, a, b)  => scalar(i) = op(s(i, a), s(i, b)); mem
        case Node.Mac(a, b, c)       => scalar(i) = Op.Add(Op.Mul(s(i, a), s(i, b)), s(i, c)); mem
        case Node.Store(a, v)        => mem.updated(a, s(i, v))
        case Node.LoadW(a)           => pair(i) = (mem(a), mem(a + 1)); mem
        case Node.VCompute(op, a, b) =>
          val (x, y) = (lanes(i, a), lanes(i, b))
          pair(i) = (op(x._1, y._1), op(x._2, y._2)); mem
        case Node.VMac(a, b, c) =>
          val (x, y, z) = (lanes(i, a), lanes(i, b), lanes(i, c))
          pair(i) = (Op.Add(Op.Mul(x._1, y._1), z._1), Op.Add(Op.Mul(x._2, y._2), z._2)); mem
        case Node.StoreW(a, v) =>
          assert(!v.isInstanceOf[Src.Broadcast], s"ST.W of a broadcast at node $i")
          val (l0, l1) = lanes(i, v)
          mem.updated(a, l0).updated(a + 1, l1)
        case Node.StoreLane(a, v, lane) =>
          val (l0, l1) = p(i, v)
          mem.updated(a, if (lane == 0) l0 else l1)
      }
      done(i) = true
      after
    }
  }

  /** A random order that respects every edge of `d`. */
  def randomOrder(d: Dag, rng: Random): Vector[Int] = {
    val indegree = Array.fill(d.nodes.size)(0)
    d.edges.foreach(e => indegree(e.to) += 1)
    val ready = scala.collection.mutable.ArrayBuffer.from(d.nodes.indices.filter(indegree(_) == 0))
    val order = Vector.newBuilder[Int]
    while (ready.nonEmpty) {
      val n = ready.remove(rng.nextInt(ready.size))
      order += n
      d.successors(n).foreach { e =>
        indegree(e.to) -= 1
        if (indegree(e.to) == 0) ready += e.to
      }
    }
    order.result()
  }

  /** The words each store node writes, with repeats (ST.W writes two). */
  def storedWords(d: Dag): Vector[Int] = d.nodes.flatMap {
    case Node.Store(a, _)        => Seq(a)
    case Node.StoreW(a, _)       => Seq(a, a + 1)
    case Node.StoreLane(a, _, _) => Seq(a)
    case _                       => Nil
  }.sorted
}
