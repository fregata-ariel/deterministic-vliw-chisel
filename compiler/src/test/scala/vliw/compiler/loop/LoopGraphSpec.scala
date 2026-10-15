package vliw.compiler.loop

import scala.util.Random

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.dag.{Dag, EdgeKind, Node}
import vliw.compiler.dsl._
import vliw.compiler.loop.Loop.{Bin, Final, Read, StateRef, Update, Write}
import vliw.isa.MachineDescription

import LoopGraphSpec.{Cycles, Hand}

class LoopGraphSpec extends AnyFunSuite {
  private def machineFile(name: String) = MachineDescription
    .load(java.nio.file.Paths.get("tests", "machines", name))
    .fold(es => fail(es.mkString("\n")), identity)
  private val machine = MachineDescription.load().fold(es => fail(es.mkString("\n")), identity)
  private val machines = Seq(machine, machineFile("slow_units.json"), machineFile("ii_units.json"))

  private def graph(k: LoopKernel, m: MachineDescription): (MemoryLayout, LoopGraph) = {
    val layout = LoopLowering.layout(k, m).fold(e => fail(e), identity)
    (layout, LoopGraph.of(LoopLowering.lower(k, layout), m))
  }

  private def word(rng: Random) = rng.nextLong() & 0xffffffffL

  private def values(k: Kernel, rng: Random) =
    Values(
      k.inputs.map(a => a.name -> Vector.fill(a.length)(word(rng))).toMap,
      k.scalars.map(_ -> word(rng)).toMap
    )

  /** Runs a DAG in `order`, which must respect its edges; fails when an operand has not run yet.
    * Returns the memory afterwards and the value of each node.
    */
  private def execute(d: Dag, order: Seq[Int], memory: Map[Int, Long]) = {
    val result = new Array[Long](d.nodes.size)
    val done = new Array[Boolean](d.nodes.size)
    def operand(i: Int, j: Int) = {
      assert(done(j), s"node $i runs before its operand $j"); result(j)
    }
    val mem = order.foldLeft(memory) { (mem, i) =>
      val after = d.nodes(i) match {
        case Node.Load(a)           => result(i) = mem(a); mem
        case Node.Compute(op, a, b) => result(i) = op(operand(i, a), operand(i, b)); mem
        case Node.Store(a, v)       => mem.updated(a, operand(i, v))
      }
      done(i) = true
      after
    }
    (mem, result.toVector)
  }

  /** A random order that respects every edge of `d` (Kahn's algorithm, random choice). */
  private def randomOrder(d: Dag, rng: Random): Vector[Int] = {
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

  /** The DAG with nodes named by what they compute instead of their ids. */
  private def canonical(d: Dag) = {
    val key = d.nodes.foldLeft(Vector.empty[Any]) { (keys, n) =>
      keys :+ (n match {
        case Node.Load(a)           => ("LD", a)
        case Node.Compute(op, a, b) => (op, keys(a), keys(b))
        case Node.Store(a, v)       => ("ST", a, keys(v))
      })
    }
    def count[A](xs: Seq[A]) = xs.groupBy(identity).view.mapValues(_.size).toMap
    (count(key), count(d.edges.map(e => (key(e.from), key(e.to), e.distance, e.kind))))
  }

  private def ld(d: Dag) = d.nodes.count(_.isInstanceOf[Node.Load])

  test("unrolled, each loop's graph is the Phase 2 DAG of the unrolled kernel") {
    // Both load each word once, and these kernels share no operation between iterations.
    for (k <- LoopBenchmarks.all; m <- machines) {
      val (layout, g) = graph(k, m)
      val phase2 = Dag.of(Lowering.lower(layout), m)
      assert(
        canonical(g.expanded.dag) == canonical(phase2),
        s"${k.name} (${k.index}) on ${m.machineId}"
      )
    }
  }

  /** Per kernel: LD before the loop, LD and operations per iteration, ST per iteration and after
    * the loop, and the dependences across iterations (from, to, cycles, iterations, kind) with
    * their number. The table of docs/COMPILER.md, "ループ IR".
    */
  private def record(g: LoopGraph) = {
    def count(p: Place, m: String) = g.ids(p).count(i => g.nodes(i).mnemonic == m)
    val carried = g.edges
      .filter(_.iterations > 0)
      .map(e => (g.nodes(e.from).mnemonic, g.nodes(e.to).mnemonic, e.cycles, e.iterations, e.kind))
      .groupBy(identity)
      .view
      .mapValues(_.size)
      .toMap
    (
      count(Place.Before, "LD"),
      count(Place.Before, "MUL"),
      Seq("LD", "MUL", "ADD", "ST").map(count(Place.Each, _)),
      count(Place.After, "ST"),
      carried
    )
  }

  test("the recorded loops: loads, operations and dependences across iterations") {
    val data = EdgeKind.Data
    val expected = Map(
      ("saxpy8", "i") -> ((8, (1, 0, Seq(2, 1, 1, 1), 0, Map.empty), 17)),
      ("fir4x8", "i") -> ((
        8,
        (
          7,
          0,
          Seq(1, 4, 3, 1),
          0,
          Map(
            ("LD", "MUL", 2, 1, data) -> 1,
            ("LD", "MUL", 2, 2, data) -> 1,
            ("LD", "MUL", 2, 3, data) -> 1
          )
        ),
        15
      )),
      ("matmul3", "i") -> ((3, (9, 0, Seq(3, 9, 6, 3), 0, Map.empty), 18)),
      ("matmul3", "m") -> ((
        2,
        (6, 9, Seq(6, 9, 9, 0), 9, Map(("ADD", "ADD", 1, 1, data) -> 9)),
        18
      )),
      ("product8", "i") -> ((
        7,
        (1, 0, Seq(1, 1, 0, 0), 1, Map(("MUL", "MUL", 2, 1, data) -> 1)),
        8
      )),
      ("dot8", "i") -> ((7, (2, 1, Seq(2, 1, 1, 0), 1, Map(("ADD", "ADD", 1, 1, data) -> 1)), 16))
    )
    assert(LoopBenchmarks.all.map(k => (k.name, k.index)).toSet == expected.keySet)
    for (k <- LoopBenchmarks.all) {
      val (layout, g) = graph(k, machine)
      val (trips, rec, loads) = expected((k.name, k.index))
      assert((g.trips, record(g)) == ((trips, rec)), s"${k.name} (${k.index})")
      // The same number of LD as the list-scheduled kernel (ADR-0014).
      val (before, _, each, _, _) = rec
      assert(before + each.head * trips == loads)
      assert(ld(g.expanded.dag) == loads && ld(Dag.of(Lowering.lower(layout), machine)) == loads)
    }
    // The cycles of a dependence come from the machine: MUL takes 3 cycles in slow_units.
    val (_, slow) = graph(LoopBenchmarks.product(8), machineFile("slow_units.json"))
    assert(slow.edges.filter(_.iterations > 0).map(_.cycles) == Vector(3))
  }

  /** Random loop kernels: strides -2 to 2, 1 to 6 iterations, up to two states (each next value
    * reads its own state and may read the other), stores in the body and finals. Returns whether
    * each array is read with one stride, so that the loop loads each word once.
    */
  private def randomKernel(rng: Random, id: Int): (LoopKernel, Boolean) = {
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

  private lazy val randomKernels: Seq[(LoopKernel, Boolean)] = {
    val rng = new Random(76)
    (0 until 300).map(randomKernel(rng, _))
  }

  test("the expanded loop gives the outputs of the DSL reference implementation") {
    val rng = new Random(1)
    for (k <- LoopBenchmarks.all ++ randomKernels.map(_._1)) {
      val (layout, g) = graph(k, machine)
      val in = values(k.unroll, rng)
      val (mem, _) =
        execute(g.expanded.dag, g.expanded.dag.nodes.indices, layout.memoryImage(in).toOption.get)
      val want = Interpreter.run(k.unroll, in).toOption.get
      assert(layout.outputs(mem) == want, k.toString)
      // The sequential semantics of the lowered loop, against the same reference.
      val image = layout.memoryImage(in).toOption.get
      val lowered = LoopLowering.lower(k, layout).loop
      assert(layout.outputs(LoopLowering.evaluate(lowered, image)) == want, k.toString)
    }
  }

  test("each iteration's stores and states give the values of the DSL reference implementation") {
    val rng = new Random(2)
    for (k <- LoopBenchmarks.all ++ randomKernels.map(_._1)) {
      val (layout, g) = graph(k, machine)
      val in = values(k.unroll, rng)
      val (dag, origin) = (g.expanded.dag, g.expanded.origin)
      val (_, value) = execute(dag, dag.nodes.indices, layout.memoryImage(in).toOption.get)
      // Each store of each iteration writes the value that the reference gives to its element.
      val want = Interpreter.run(k.unroll, in).toOption.get
      val byAddress =
        for (o <- k.outputs; i <- 0 until o.length)
          yield layout.elem(o.name, i) -> want.arrays(o.name)(i)
      dag.nodes.foreach {
        case Node.Store(a, v) => assert(value(v) == byAddress.toMap.apply(a), s"${k.name}: [$a]")
        case _                => ()
      }
      // LoopGraph.states: each state's initial value before the loop and its next value, an
      // operation, in each iteration.
      assert(g.states.size == k.states.size, k.name)
      assert(
        g.states.forall(st =>
          g.places(st.init) == Place.Before && g.places(st.next) == Place.Each &&
            g.nodes(st.next).isInstanceOf[LoopNode.Compute]
        ),
        k.name
      )
      // Each state, before the loop and after each iteration, against its unrolled expression.
      if (k.states.nonEmpty) {
        val n = k.trips + 1
        val probe = Kernel("probe") { p =>
          k.inputs.foreach(a => p.input(a.name, a.length))
          k.scalars.foreach(p.scalar)
          val out = p.output("probe", k.states.size * n)
          for (s <- k.states.indices; t <- 0 until n) out(s * n + t) = k.stateValues(t)(s)
        }
        val ref = Interpreter.run(probe, in).toOption.get.arrays("probe")
        val index = origin.zipWithIndex.toMap
        for ((st, s) <- g.states.zipWithIndex) {
          assert(value(index(Instance(st.init, None))) == ref(s * n), s"${k.name}: state $s")
          for (t <- 0 until k.trips)
            assert(
              value(index(Instance(st.next, Some(t)))) == ref(s * n + t + 1),
              s"${k.name}: $s, $t"
            )
        }
      }
    }
  }

  test("a loop that reads each word with one stride loads it once, as the Phase 2 DAG") {
    val single = randomKernels.filter(_._2).map(_._1)
    assert(single.size >= 50, s"only ${single.size} kernels")
    for (k <- single) {
      val (layout, g) = graph(k, machine)
      assert(ld(g.expanded.dag) == ld(Dag.of(Lowering.lower(layout), machine)), k.toString)
    }
  }

  test("a constant that only an unused value reads gets a word after the outputs") {
    // One iteration: the next value of s, with the constant 7, is never used.
    val k = LoopKernel("dead", "i", 1) { b =>
      val x = b.input("x", 1)
      val out = b.output("out", 1)
      val s = b.state("s", b.const(5))
      b.body { i =>
        s := s.value + b.const(7) * x(i)
        out(i) = s.value + x(i)
      }
    }
    val (layout, g) = graph(k, machine)
    assert(layout.constants == Map(5L -> 1, 7L -> 3) && layout.words == 4)
    val in = Values(Map("x" -> Vector(10L)))
    val (mem, _) =
      execute(g.expanded.dag, g.expanded.dag.nodes.indices, layout.memoryImage(in).toOption.get)
    assert(layout.outputs(mem).arrays("out") == Vector(15L))
    // The initial value of a state that is not stored, with the constant 9: s(1) is never used.
    val init = LoopKernel("deadinit", "i", 1) { b =>
      val x = b.input("x", 1)
      val out = b.output("out", 1)
      val s = b.state("s", b.const(9))
      b.body { i =>
        s := s.value + x(i)
        out(i) = x(i) * x(i)
      }
    }
    val (l2, g2) = graph(init, machine)
    assert(l2.constants == Map(9L -> 2) && l2.words == 3)
    val (m2, _) =
      execute(g2.expanded.dag, g2.expanded.dag.nodes.indices, l2.memoryImage(in).toOption.get)
    assert(l2.outputs(m2).arrays("out") == Vector(100L))
  }

  test("every dependence within an iteration goes to a higher id") {
    for (k <- LoopBenchmarks.all ++ randomKernels.map(_._1); m <- machines) {
      val (_, g) = graph(k, m)
      assert(g.edges.forall(e => e.iterations > 0 || e.from < e.to))
      assert(g.edges.forall(e => g.places(e.from) == Place.Each && g.places(e.to) == Place.Each))
    }
  }

  // Hand-made loops that read and write the same words (a LoopKernel never does). `edges` are the
  // dependences between Each nodes (from, to, cycles, iterations, kind), with cycles from the
  // machine; `outside` are the memory dependences of `expanded` with a Before or After node.
  private def lat(op: String) = Cycles(_.operation(op).latency)
  private val zero = Cycles(_ => 0)
  private val one = Cycles(_ => 1)
  private val st = EdgeKind.StoreToLoad
  private val lds = EdgeKind.LoadToStore
  private val sts = EdgeKind.StoreToStore
  private val dt = EdgeKind.Data
  private def before(n: Int) = Instance(n, None)
  private def each(n: Int, k: Int) = Instance(n, Some(k))
  private def add(a: Loop.Value, b: Loop.Value) = Bin(Op.Add, a, b)
  private def mul(a: Loop.Value, b: Loop.Value) = Bin(Op.Mul, a, b)
  private def rd(base: Int, stride: Int) = Read(Address(base, stride))
  private val hand: Seq[Hand] = Seq(
    Hand(
      "shift left: [k] = [k + 1]",
      Loop(3, Vector(), Vector(Write(Address(0, 1), rd(1, 1))), Vector()),
      Set((0, 1, lat("LD"), 0, dt), (0, 1, zero, 1, lds))
    ),
    Hand(
      "through memory: [k + 1] = [k] + [10]",
      Loop(3, Vector(), Vector(Write(Address(1, 1), add(rd(0, 1), rd(10, 0)))), Vector()),
      Set((0, 2, lat("LD"), 0, dt), (2, 3, lat("ADD"), 0, dt), (3, 0, lat("ST"), 1, st))
    ),
    Hand(
      "the same word in every iteration: [5] = [k]",
      Loop(3, Vector(), Vector(Write(Address(5, 0), rd(0, 1))), Vector()),
      Set((0, 1, lat("LD"), 0, dt), (1, 1, one, 1, sts))
    ),
    Hand(
      "a word read and written in every iteration, with stride 0: [5] = [5] + [k]",
      Loop(3, Vector(), Vector(Write(Address(5, 0), add(rd(5, 0), rd(0, 1)))), Vector()),
      Set(
        (0, 2, lat("LD"), 0, dt),
        (1, 2, lat("LD"), 0, dt),
        (2, 3, lat("ADD"), 0, dt),
        (0, 3, zero, 0, lds),
        (3, 0, lat("ST"), 1, st),
        (3, 3, one, 1, sts)
      )
    ),
    Hand(
      "two reads of one stride of written words: [k + 1] = [k] + [k + 1]",
      Loop(3, Vector(), Vector(Write(Address(1, 1), add(rd(0, 1), rd(1, 1)))), Vector()),
      Set(
        (0, 2, lat("LD"), 0, dt),
        (1, 2, lat("LD"), 0, dt),
        (2, 3, lat("ADD"), 0, dt),
        (1, 3, zero, 0, lds),
        (3, 0, lat("ST"), 1, st)
      )
    ),
    Hand(
      "words written in a later iteration only: [2] = [k] + [k + 1]",
      Loop(3, Vector(), Vector(Write(Address(2, 0), add(rd(0, 1), rd(1, 1)))), Vector()),
      Set(
        (0, 2, lat("LD"), 0, dt),
        (1, 2, lat("LD"), 0, dt),
        (2, 3, lat("ADD"), 0, dt),
        (3, 1, lat("ST"), 1, st),
        (3, 0, lat("ST"), 1, st),
        (1, 3, zero, 0, lds),
        (0, 3, zero, 0, lds),
        (3, 3, one, 1, sts)
      )
    ),
    Hand(
      "words that a store with another stride writes: [1 + 2k] = [k + 2] + [k + 3]",
      Loop(4, Vector(), Vector(Write(Address(1, 2), add(rd(2, 1), rd(3, 1)))), Vector()),
      Set(
        (0, 2, lat("LD"), 0, dt),
        (1, 2, lat("LD"), 0, dt),
        (2, 3, lat("ADD"), 0, dt),
        (1, 3, zero, 1, lds),
        (0, 3, zero, 0, lds),
        (1, 3, zero, 0, lds),
        (3, 0, lat("ST"), 1, st)
      )
    ),
    Hand(
      "a store, then a load of its word: [k] = [10 + k]; [20 + k] = [k]",
      Loop(
        2,
        Vector(),
        Vector(Write(Address(0, 1), rd(10, 1)), Write(Address(20, 1), rd(0, 1))),
        Vector()
      ),
      Set((0, 1, lat("LD"), 0, dt), (1, 2, lat("ST"), 0, st), (2, 3, lat("LD"), 0, dt))
    ),
    Hand(
      "two loads of a word with its store between: [30 + k] = [k]; [k] = [10 + k]; [20 + k] = [k]",
      Loop(
        2,
        Vector(),
        Vector(
          Write(Address(30, 1), rd(0, 1)),
          Write(Address(0, 1), rd(10, 1)),
          Write(Address(20, 1), rd(0, 1))
        ),
        Vector()
      ),
      Set(
        (0, 1, lat("LD"), 0, dt),
        (0, 3, zero, 0, lds),
        (2, 3, lat("LD"), 0, dt),
        (3, 4, lat("ST"), 0, st),
        (4, 5, lat("LD"), 0, dt)
      )
    ),
    Hand(
      "an initial value read from a word the body writes: s = [0]; [0] = s + [1 + k]",
      Loop(
        2,
        Vector(rd(0, 0)),
        Vector(
          Write(Address(0, 0), add(StateRef(0), rd(1, 1))),
          Update(0, add(StateRef(0), rd(1, 1)))
        ),
        Vector(Final(9, 0))
      ),
      Set(
        (1, 2, lat("LD"), 0, dt),
        (2, 3, lat("ADD"), 0, dt),
        (2, 2, lat("ADD"), 1, dt),
        (3, 3, one, 1, sts)
      ),
      Set((before(0), each(3, 0), zero, lds))
    ),
    Hand(
      "a load before the loop, then a store not fed by it: s = [0]; [0] = [1 + k] * [2 + k]; s += [1 + k]",
      Loop(
        2,
        Vector(rd(0, 0)),
        Vector(
          Write(Address(0, 0), mul(rd(1, 1), rd(2, 1))),
          Update(0, add(StateRef(0), rd(1, 1)))
        ),
        Vector(Final(9, 0))
      ),
      Set(
        (1, 3, lat("LD"), 1, dt),
        (1, 3, lat("LD"), 0, dt),
        (3, 4, lat("MUL"), 0, dt),
        (1, 5, lat("LD"), 1, dt),
        (5, 5, lat("ADD"), 1, dt),
        (4, 4, one, 1, sts)
      ),
      Set((before(0), each(4, 0), zero, lds))
    ),
    Hand(
      "a final store to a word the body reads: s = [1]; s = s + [9]; [9] = s",
      Loop(2, Vector(rd(1, 0)), Vector(Update(0, add(StateRef(0), rd(9, 0)))), Vector(Final(9, 0))),
      Set((2, 2, lat("ADD"), 1, dt)),
      Set((before(1), Instance(3, None), zero, lds))
    ),
    Hand(
      "a final store after the body's stores to its word: s = [0]; [9] = [1 + k]; s += [1 + k]",
      Loop(
        2,
        Vector(rd(0, 0)),
        Vector(Write(Address(9, 0), rd(1, 1)), Update(0, add(StateRef(0), rd(1, 1)))),
        Vector(Final(9, 0))
      ),
      Set(
        (1, 2, lat("LD"), 0, dt),
        (1, 3, lat("LD"), 0, dt),
        (3, 3, lat("ADD"), 1, dt),
        (2, 2, one, 1, sts)
      ),
      Set((each(2, 1), Instance(4, None), one, sts))
    ),
    Hand(
      "a final store to a word that the last iteration loads: s = [5]; [20 + k] = [k]; s += [10 + k]",
      Loop(
        2,
        Vector(rd(5, 0)),
        Vector(Write(Address(20, 1), rd(0, 1)), Update(0, add(StateRef(0), rd(10, 1)))),
        Vector(Final(1, 0))
      ),
      Set((1, 2, lat("LD"), 0, dt), (3, 4, lat("LD"), 0, dt), (4, 4, lat("ADD"), 1, dt)),
      Set((each(1, 1), Instance(5, None), zero, lds))
    ),
    Hand(
      "states read at the start of the iteration: s0 += [2 + k]; s1 *= s0; [10 + k] = s0 * [2 + k]",
      Loop(
        2,
        Vector(rd(0, 0), rd(1, 0)),
        Vector(
          Update(0, add(StateRef(0), rd(2, 1))),
          Update(1, mul(StateRef(1), StateRef(0))),
          Write(Address(10, 1), mul(StateRef(0), rd(2, 1)))
        ),
        Vector(Final(20, 0), Final(21, 1))
      ),
      Set(
        (2, 3, lat("LD"), 0, dt),
        (3, 3, lat("ADD"), 1, dt),
        (4, 4, lat("MUL"), 1, dt),
        (3, 4, lat("ADD"), 1, dt),
        (3, 5, lat("ADD"), 1, dt),
        (2, 5, lat("LD"), 0, dt),
        (5, 6, lat("MUL"), 0, dt)
      )
    )
  )

  test("hand-made loops: dependences through memory, within and across iterations") {
    val rng = new Random(9)
    for (h <- hand; m <- machines) {
      val g = LoopGraph.build(m, h.loop)
      val where = s"${h.name} on ${m.machineId}"
      assert(
        g.edges.toSet == h.edges.map { case (f, t, c, i, k) => LoopEdge(f, t, c.f(m), i, k) },
        where
      )
      val o = g.expanded.origin
      val outside = g.expanded.dag.edges.collect {
        case e if e.kind != dt && (o(e.from).iteration.isEmpty || o(e.to).iteration.isEmpty) =>
          (o(e.from), o(e.to), e.distance, e.kind)
      }
      assert(outside.toSet == h.outside.map { case (f, t, c, k) => (f, t, c.f(m), k) }, where)
      val dag = g.expanded.dag
      for (_ <- 0 until 10) {
        val memory = (0 until 32).map(_ -> word(rng)).toMap
        val want = LoopLowering.evaluate(h.loop, memory)
        // Any order that respects the expanded edges gives the result of the sequential loop.
        for (order <- Seq(dag.nodes.indices.toVector) ++ Seq.fill(5)(randomOrder(dag, rng)))
          assert(execute(dag, order, memory)._1 == want, where)
      }
    }
  }

  test("the sequential semantics: states are read at the start of the iteration") {
    val loop = hand.last.loop
    val m = (0 until 32).map(i => i -> (i + 1).toLong * 10).toMap
    val got = LoopLowering.evaluate(loop, m)
    val (s0, s1) = (m(0), m(1))
    val (s0a, s1a) = (s0 + m(2), s1 * s0) // after iteration 0
    val (s0b, s1b) = (s0a + m(3), s1a * s0a) // after iteration 1
    assert(got(10) == s0 * m(2) && got(11) == s0a * m(3))
    assert(got(20) == s0b && got(21) == s1b)
  }

  test("the benchmarks give the same result in any order that respects the expanded edges") {
    val rng = new Random(10)
    for (k <- LoopBenchmarks.all) {
      val (layout, g) = graph(k, machine)
      val in = values(k.unroll, rng)
      val memory = layout.memoryImage(in).toOption.get
      val want = LoopLowering.evaluate(LoopLowering.lower(k, layout).loop, memory)
      for (_ <- 0 until 5)
        assert(execute(g.expanded.dag, randomOrder(g.expanded.dag, rng), memory)._1 == want, k.name)
    }
  }

  test("malformed loops are rejected when the graph is built") {
    def rejects(why: String)(loop: => Loop) = {
      val e = intercept[IllegalArgumentException](LoopGraph.build(machine, loop))
      assert(e.getMessage.contains(why), e.getMessage)
    }
    rejects("each state must be updated once")(
      Loop(1, Vector(Read(Address(0, 0))), Vector(), Vector())
    )
    rejects("an initial value reads a changing word") {
      Loop(
        1,
        Vector(Read(Address(0, 1))),
        Vector(Update(0, Bin(Op.Add, StateRef(0), Read(Address(1, 1))))),
        Vector()
      )
    }
    rejects("must be an operation in the loop") {
      Loop(2, Vector(Read(Address(0, 0))), Vector(Update(0, Read(Address(1, 1)))), Vector())
    }
    rejects("must be an operation in the loop") {
      Loop(
        2,
        Vector(Read(Address(0, 0))),
        Vector(Update(0, Bin(Op.Add, Read(Address(1, 0)), Read(Address(2, 0))))),
        Vector()
      )
    }
  }
}

object LoopGraphSpec {

  /** The cycles of an expected dependence, from the machine description. */
  final case class Cycles(f: MachineDescription => Int)

  /** A hand-made loop and its expected dependences. */
  final case class Hand(
      name: String,
      loop: Loop,
      edges: Set[(Int, Int, Cycles, Int, EdgeKind)],
      outside: Set[(Instance, Instance, Cycles, EdgeKind)] = Set.empty
  )
}
