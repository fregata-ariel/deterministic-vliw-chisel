package vliw.compiler.loop

import scala.util.Random

import vliw.compiler.dsl.Op

/** Random loop kernels, for the loop graph and its fusion into MAC (`LoopGraphSpec`,
  * `LoopFusionSpec`).
  */
object RandomLoops {
  private def word(rng: Random) = rng.nextLong() & 0xffffffffL

  /** Random loop kernels: strides -2 to 2, 1 to 6 iterations, up to two states (each next value
    * reads its own state and may read the other), stores in the body and finals. Returns whether
    * each array is read with one stride, so that the loop loads each word once.
    */
  def kernel(rng: Random, id: Int): (LoopKernel, Boolean) = {
    val trips = 1 + rng.nextInt(6)
    val length = 2 * trips + 4
    def some(from: Seq[Int]) = Seq.fill(1 + rng.nextInt(2))(from(rng.nextInt(from.size))).distinct
    val strides = Map("x" -> some(Seq(-2, -1, 1, 2)), "y" -> some(Seq(-2, -1, 0, 1, 2)))
    val states = Seq("s0", "s1").take(rng.nextInt(3))
    val ops = Seq(Op.Add, Op.Sub, Op.Mul)
    val k = LoopKernel(s"rand$id", "i", trips) { b =>
      val in = strides.keys.toSeq.sorted.map(a => a -> b.input(a, length)).toMap
      val a = b.scalar("a")
      def elem(name: String, s: Int): LoopExpr = {
        val lo = if (s < 0) -s * (trips - 1) else 0
        val hi = if (s > 0) length - 1 - s * (trips - 1) else length - 1
        in(name)(Index(s, lo + rng.nextInt(hi - lo + 1)))
      }
      def leaf(invariant: Boolean): LoopExpr = {
        val elems =
          for ((n, ss) <- strides.toSeq.sortBy(_._1); s <- ss if !invariant || s == 0)
            yield (() => elem(n, s))
        val all =
          elems ++ Seq(() => a, () => b.const(word(rng)), () => b.const(rng.nextInt(4).toLong))
        all(rng.nextInt(all.size))()
      }
      def expr(depth: Int, invariant: Boolean): LoopExpr =
        if (depth == 0 || rng.nextInt(3) == 0) leaf(invariant)
        else
          LoopExpr.Bin(ops(rng.nextInt(3)), expr(depth - 1, invariant), expr(depth - 1, invariant))
      def varying(depth: Int): LoopExpr =
        LoopExpr.Bin(
          ops(rng.nextInt(3)),
          elem("x", strides("x").head),
          expr(depth, invariant = false)
        )
      def withState(e: LoopExpr): LoopExpr =
        if (states.isEmpty || rng.nextBoolean()) e
        else {
          val s = states(rng.nextInt(states.size))
          if (rng.nextBoolean()) LoopExpr.Bin(ops(rng.nextInt(3)), LoopExpr.State(s), e)
          else LoopExpr.Bin(ops(rng.nextInt(3)), e, LoopExpr.State(s))
        }
      val vars = states.map(s => b.state(s, expr(2, invariant = true)))
      val out = if (states.isEmpty || rng.nextBoolean()) Some(b.output("out", trips)) else None
      val w = if (rng.nextBoolean()) Some(b.output("w", 2 * trips)) else None
      val fin = if (states.nonEmpty) Some(b.output("fin", states.size)) else None
      b.body { i =>
        vars.foreach(v => v := LoopExpr.Bin(ops(rng.nextInt(3)), v.value, withState(varying(2))))
        out.foreach { o =>
          if (rng.nextBoolean()) o(i) = withState(expr(3, invariant = false))
          else o(i * -1 + (trips - 1)) = withState(expr(3, invariant = false))
        }
        w.foreach { o =>
          o(i * 2) = withState(expr(2, invariant = false))
          o(i * 2 + 1) = withState(expr(2, invariant = false))
        }
      }
      fin.foreach(o => vars.zipWithIndex.foreach { case (v, j) => o(j) = v })
    }
    (k, strides.values.forall(_.size == 1))
  }

  /** `n` kernels from the seed. */
  def kernels(seed: Long, n: Int): Vector[(LoopKernel, Boolean)] = {
    val rng = new Random(seed)
    Vector.tabulate(n)(kernel(rng, _))
  }

  /** A random loop that tends to double (ISA mvp-2, #110 stage 5): no state, `trips` iterations,
    * reads of two inputs with strides mostly 1 (and −1, 2), values before the loop mostly at a
    * multiplication, `out(i)` or reversed, and with probability 1/3 also `w(2i)` and `w(2i+1)`.
    */
  def vectorizableKernel(rng: Random, id: Int, trips: Int): LoopKernel = {
    val n = 2 * trips + 4
    val ops = Seq(Op.Add, Op.Sub, Op.Mul)
    LoopKernel(s"vec$id", "i", trips) { b =>
      val arrays = Seq(b.input("x", n), b.input("y", n))
      val a = b.scalar("a")
      def elem(): LoopExpr = {
        val s = Seq(1, 1, 1, 1, -1, 2)(rng.nextInt(6))
        val lo = if (s < 0) -s * (trips - 1) else 0
        val hi = if (s > 0) n - 1 - s * (trips - 1) else n - 1
        arrays(rng.nextInt(2))(Index(s, lo + rng.nextInt(hi - lo + 1)))
      }
      def invariant(): LoopExpr = if (rng.nextBoolean()) a else b.const(rng.nextInt(5).toLong)
      def expr(d: Int): LoopExpr =
        if (d == 0 || rng.nextInt(4) == 0) elem()
        else
          rng.nextInt(8) match {
            case 0 | 1 => invariant() * expr(d - 1)
            case 2     => expr(d - 1) * invariant()
            case 3     =>
              if (rng.nextInt(3) == 0) expr(d - 1) + invariant() else expr(d - 1) - expr(d - 1)
            case _ => LoopExpr.Bin(ops(rng.nextInt(3)), expr(d - 1), expr(d - 1))
          }
      val out = b.output("out", trips)
      val w = Option.when(rng.nextInt(3) == 0)(b.output("w", 2 * trips))
      b.body { i =>
        if (rng.nextInt(4) > 0) out(i) = expr(3) else out(i * -1 + (trips - 1)) = expr(3)
        w.foreach { o => o(i * 2) = expr(2); o(i * 2 + 1) = expr(2) }
      }
    }
  }

  /** `n` loops of [[vectorizableKernel]] from the seed, with 1 to 8 iterations. */
  def vectorizable(seed: Long, n: Int): Vector[LoopKernel] = {
    val rng = new Random(seed)
    Vector.tabulate(n) { i =>
      val trips = 1 + rng.nextInt(8)
      vectorizableKernel(rng, i, trips)
    }
  }

  /** `n` long loops of [[vectorizableKernel]] from the seed, with an even trip count of 10 to 24,
    * so that doubled loops with LD.W chains and gathers reach a steady state.
    */
  def longVectorizable(seed: Long, n: Int): Vector[LoopKernel] = {
    val rng = new Random(seed)
    Vector.tabulate(n) { i =>
      val trips = 10 + 2 * rng.nextInt(8)
      vectorizableKernel(rng, i, trips)
    }
  }

  /** A leaf or an operation of a [[packableKernel]] template, instantiated for lanes 0 and 1. */
  private sealed trait Template
  private object Template {

    /** `x(s·i + o + lane)`: adjacent elements (LD.W). */
    final case class Adjacent(array: Int, s: Int, o: Int) extends Template

    /** `x(s·i + o + lane·d)`: elements that are not adjacent (a gather). */
    final case class Far(array: Int, s: Int, o: Int, d: Int) extends Template

    /** The same invariant in both lanes (`a`, a constant or `x(0)`): a broadcast or `SameValue`. */
    final case class Invariant(which: Int) extends Template

    /** The same element in both lanes. */
    final case class Same(array: Int, s: Int, o: Int) extends Template

    /** A constant per lane. */
    final case class Constants(c0: Long, c1: Long) extends Template

    /** `op` in lane 0 and `op1` in lane 1. */
    final case class Operation(op: Op, a: Template, b: Template, op1: Op) extends Template
  }

  /** A random loop with isomorphic adjacent stores, for body packing (ISA mvp-2, #110 stage 5b): 1
    * to 2 store pairs `out(per·i + 2j)` and `out(per·i + 2j + 1)` (`per` 2 to 5), each a random
    * template of depth 2 to 3 instantiated for lanes 0 and 1, and with probability 1/3 one more
    * scalar output. The template's leaves are adjacent elements (strides 1, 2, −1, 3), elements
    * that are not adjacent, the same invariant or element in both lanes, or a constant per lane;
    * one operation in 10 differs between the lanes. With probability 1/3, 1 to 2 pairs of states,
    * with initial values `y(2j + lane)`, `a · x(2j + lane)`, a constant per lane, or the same
    * constant 1, next values `s + e`, `s · e` or `s + other · e` (states that read each other), and
    * finals `fin(2j + lane)`. With `stateStores`, a loop with states also stores `t(2i)` and
    * `t(2i + 1)` from the first pair of states (the state value, the state plus a template, or a
    * template times the state), and that pair has no finals, so that a store of a vector state can
    * pack.
    */
  def packableKernel(rng: Random, id: Int, trips: Int, stateStores: Boolean): LoopKernel = {
    import Template._
    val pairs = 1 + rng.nextInt(2)
    val extra = rng.nextInt(3) == 0
    val per = 2 * pairs + (if (extra) 1 else 0)
    val statePairs = if (rng.nextInt(3) == 0) 1 + rng.nextInt(2) else 0
    val len = 4 * trips + 14
    val ops = Seq(Op.Add, Op.Sub, Op.Mul)
    LoopKernel(s"pack$id", "i", trips) { b =>
      val arrays = Seq(b.input("x", len), b.input("y", len))
      val a = b.scalar("a")
      def stride() = Seq(1, 1, 1, 1, 2, -1, 3)(rng.nextInt(7))
      def base(s: Int) = {
        val lo = (if (s < 0) -s * (trips - 1) else 0) + 3
        val hi = len - 1 - (if (s > 0) s * (trips - 1) else 0) - 3
        lo + rng.nextInt(hi - lo + 1)
      }
      def leaf(): Template = rng.nextInt(10) match {
        case 0 | 1 | 2 | 3 => val s = stride(); Adjacent(rng.nextInt(2), s, base(s))
        case 4             =>
          val s = stride(); Far(rng.nextInt(2), s, base(s), Seq(2, 3, -1)(rng.nextInt(3)))
        case 5 | 6 => Invariant(rng.nextInt(3))
        case 7     => val s = stride(); Same(rng.nextInt(2), s, base(s))
        case _     => Constants(rng.nextInt(6).toLong, rng.nextInt(6).toLong)
      }
      def template(d: Int): Template =
        if (d == 0 || rng.nextInt(4) == 0) leaf()
        else {
          val op = ops(rng.nextInt(3))
          val op1 = if (rng.nextInt(10) == 0) ops(rng.nextInt(3)) else op
          rng.nextInt(4) match {
            case 0 => Operation(Op.Mul, Invariant(rng.nextInt(3)), template(d - 1), Op.Mul)
            case _ => Operation(op, template(d - 1), template(d - 1), op1)
          }
        }
      def invariant(w: Int): LoopExpr = w match {
        case 0 => a
        case 1 => b.const(3L)
        case _ => arrays(0)(Index(0, 0))
      }
      def lane(t: Template, l: Int): LoopExpr = t match {
        case Adjacent(r, s, o)        => arrays(r)(Index(s, o + l))
        case Far(r, s, o, d)          => arrays(r)(Index(s, o + l * d))
        case Invariant(w)             => invariant(w)
        case Same(r, s, o)            => arrays(r)(Index(s, o))
        case Constants(c0, c1)        => b.const(if (l == 0) c0 else c1)
        case Operation(op, x, y, op1) =>
          LoopExpr.Bin(if (l == 0) op else op1, lane(x, l), lane(y, l))
      }
      val out = b.output("out", per * trips)
      val states = (0 until statePairs).map { j =>
        val initKind = rng.nextInt(4)
        def init(l: Int): LoopExpr = initKind match {
          case 0 => arrays(1)(Index(0, 2 * j + l))
          case 1 => a * arrays(0)(Index(0, 2 * j + l))
          case 2 => b.const((7 + 2 * j + l).toLong)
          case _ => b.const(1L)
        }
        (b.state(s"s${j}_0", init(0)), b.state(s"s${j}_1", init(1)))
      }
      val t = Option.when(stateStores && statePairs > 0)(b.output("t", 2 * trips))
      // With `t`, the first pair of states has no finals: `t` reads it, and nothing after the loop.
      val finals = if (t.isEmpty) states else states.drop(1)
      val fin = Option.when(finals.nonEmpty)(b.output("fin", 2 * finals.size))
      b.body { i =>
        for (j <- 0 until pairs) {
          val tp = template(if (pairs == 1) 2 + rng.nextInt(2) else 2)
          out(i * per + 2 * j) = lane(tp, 0)
          out(i * per + (2 * j + 1)) = lane(tp, 1)
        }
        if (extra) out(i * per + 2 * pairs) = lane(template(2), 0)
        states.foreach { case (s0, s1) =>
          val tp = template(2)
          val kind = rng.nextInt(6)
          def next(l: Int, me: LoopExpr, other: LoopExpr) = kind match {
            case 0 => me * lane(tp, l)
            case 1 => me + other * lane(tp, l)
            case _ => me + lane(tp, l)
          }
          s0 := next(0, s0.value, s1.value)
          s1 := next(1, s1.value, s0.value)
        }
        t.foreach { o =>
          val (s0, s1) = states.head
          val tp = template(1)
          val kind = rng.nextInt(3)
          def value(l: Int, s: LoopExpr) = kind match {
            case 0 => s
            case 1 => s + lane(tp, l)
            case _ => lane(tp, l) * s
          }
          o(i * 2) = value(0, s0.value)
          o(i * 2 + 1) = value(1, s1.value)
        }
      }
      fin.foreach(f =>
        finals.zipWithIndex.foreach { case ((s0, s1), j) => f(2 * j) = s0; f(2 * j + 1) = s1 }
      )
    }
  }

  /** `n` loops of [[packableKernel]] from the seed, with 1 to 8 iterations. */
  def packable(seed: Long, n: Int, stateStores: Boolean = false): Vector[LoopKernel] = {
    val rng = new Random(seed)
    Vector.tabulate(n)(i => packableKernel(rng, i, 1 + rng.nextInt(8), stateStores))
  }
}
