package vliw.compiler.dag

import scala.util.Random

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.dsl._
import vliw.compiler.dsl.LoweredKernel.{Bin, Read, Write}
import vliw.isa.MachineDescription

class DagSpec extends AnyFunSuite {
  private val machine = MachineDescription.load().fold(es => fail(es.mkString("\n")), identity)
  private val slow = MachineDescription
    .load(java.nio.file.Paths.get("tests", "machines", "slow_units.json"))
    .fold(es => fail(es.mkString("\n")), identity)

  private def edgeSet(d: Dag) = d.edges.map(e => (e.from, e.to, e.distance, e.kind)).toSet

  /** Executes the DAG node by node in id order, which is a topological order. */
  private def evaluate(d: Dag, memory: Map[Int, Long]): Map[Int, Long] = {
    val values = new Array[Long](d.nodes.size)
    d.nodes.zipWithIndex.foldLeft(memory) { case (mem, (n, i)) =>
      n match {
        case Node.Load(a)           => values(i) = mem(a); mem
        case Node.Compute(op, a, b) => values(i) = op(values(a), values(b)); mem
        case Node.Mac(a, b, c) => values(i) = Op.Add(Op.Mul(values(a), values(b)), values(c)); mem
        case Node.Store(a, v)  => mem.updated(a, values(v))
        case other             => fail(s"$other: not a node of a scalar DAG")
      }
    }
  }

  test("the ISA example as a DAG: LD, MUL, ADD, ST with latency distances; 7 cycles at least") {
    // [10] = [0] * [1] + [2]; docs/ISA.md "最初の命令例" runs this chain in 7 cycles.
    val d =
      Dag.build(machine, Vector(Write(10, Bin(Op.Add, Bin(Op.Mul, Read(0), Read(1)), Read(2)))))
    assert(
      d.nodes == Vector(
        Node.Load(0),
        Node.Load(1),
        Node.Compute(Op.Mul, 0, 1),
        Node.Load(2),
        Node.Compute(Op.Add, 2, 3),
        Node.Store(10, 4)
      )
    )
    assert(
      edgeSet(d) == Set(
        (0, 2, 2, EdgeKind.Data),
        (1, 2, 2, EdgeKind.Data),
        (2, 4, 2, EdgeKind.Data),
        (3, 4, 2, EdgeKind.Data),
        (4, 5, 1, EdgeKind.Data)
      )
    )
    assert(d.earliest == Vector(0, 0, 2, 0, 4, 5))
    assert(d.criticalPath == 6 && d.cyclesLowerBound == 7)
    assert(d.height == Vector(6, 6, 4, 4, 2, 1)) // ld [2]: 2 to the ADD, then 2 to completion
  }

  test("distances come from the machine description") {
    val w = Vector(Write(10, Bin(Op.Add, Bin(Op.Mul, Read(0), Read(1)), Read(2))))
    val d = Dag.build(slow, w) // MUL and LD latency 3, ST latency 2
    assert(
      edgeSet(d).collect { case (f, t, dist, _) if t == 2 || t == 5 => (f, t, dist) } ==
        Set((0, 2, 3), (1, 2, 3), (4, 5, 1))
    )
    assert(d.criticalPath == 3 + 3 + 1 + 2)
  }

  test("equal values share one node: a word read twice is loaded once") {
    val d = Dag.build(machine, Vector(Write(5, Bin(Op.Mul, Read(0), Read(0))), Write(6, Read(0))))
    assert(
      d.nodes == Vector(
        Node.Load(0),
        Node.Compute(Op.Mul, 0, 0),
        Node.Store(5, 1),
        Node.Store(6, 0)
      )
    )
    assert(
      edgeSet(d) == Set(
        (0, 1, 2, EdgeKind.Data),
        (1, 2, 2, EdgeKind.Data),
        (0, 3, 2, EdgeKind.Data)
      )
    )
  }

  test("memory order: store to load, load to store, and store to store on the same address") {
    val d = Dag.build(
      machine,
      Vector(
        Write(5, Read(0)), // 0: ld [0]   1: st [5]
        Write(6, Read(5)), // 2: ld [5] after the store: st->ld   3: st [6]
        Write(0, Read(1)), // 4: ld [1]   5: st [0] after ld [0]: ld->st
        Write(5, Read(2)), // 6: ld [2]   7: st [5]: st->st, and ld [5] (2) -> st
        Write(7, Read(5)) //  8: ld [5] of the new value, not node 2   9: st [7]
      )
    )
    assert(d.nodes.collect { case Node.Load(a) => a } == Vector(0, 5, 1, 2, 5))
    val memoryEdges = edgeSet(d).filter(_._4 != EdgeKind.Data)
    assert(
      memoryEdges == Set(
        (1, 2, 1, EdgeKind.StoreToLoad),
        (0, 5, 0, EdgeKind.LoadToStore),
        (1, 7, 1, EdgeKind.StoreToStore),
        (2, 7, 0, EdgeKind.LoadToStore),
        (7, 8, 1, EdgeKind.StoreToLoad)
      )
    )
    val memory = Map(0 -> 1L, 1 -> 2L, 2 -> 3L)
    assert(evaluate(d, memory) == Map(0 -> 2L, 1 -> 2L, 2 -> 3L, 5 -> 3L, 6 -> 1L, 7 -> 3L))
  }

  test("benchmark DAGs: node counts and lower bounds") {
    def dag(k: Kernel) =
      Dag.of(
        Lowering.lower(MemoryLayout.fixedOrder(k, machine).fold(e => fail(e), identity)),
        machine
      )
    def counts(d: Dag) = d.nodes.groupBy(_.mnemonic).map { case (m, ns) => m -> ns.size }
    val saxpy = dag(Benchmarks.saxpy(8)) // a, x(8), y(8) loaded once each
    assert(counts(saxpy) == Map("LD" -> 17, "MUL" -> 8, "ADD" -> 8, "ST" -> 8))
    assert(saxpy.cyclesLowerBound == 7) // LD 0, MUL 2, ADD 4, ST 5, done at 6
    assert(saxpy.resourceLowerBound == 26) // 25 memory operations, then HALT
    val fir = dag(Benchmarks.fir(Seq(1L, 2L, 3L, 4L), 8)) // x(11) and 4 constants
    assert(counts(fir) == Map("LD" -> 15, "MUL" -> 32, "ADD" -> 24, "ST" -> 8))
    assert(fir.cyclesLowerBound == 9) // MUL 2, three chained ADDs 4..6, ST 7, done at 8
    assert(fir.resourceLowerBound == 57) // 56 compute operations, then HALT
    val matmul = dag(Benchmarks.matmul(3))
    assert(counts(matmul) == Map("LD" -> 18, "MUL" -> 27, "ADD" -> 18, "ST" -> 9))
    assert(matmul.cyclesLowerBound == 8)
    assert(matmul.resourceLowerBound == 46)
  }

  test("edges go forward, ASAP satisfies them, and the DAG keeps the kernel's meaning") {
    val rng = new Random(48L)
    Benchmarks.all.foreach { k =>
      val layout = MemoryLayout.fixedOrder(k, machine).fold(e => fail(e), identity)
      val lowered = Lowering.lower(layout)
      val d = Dag.of(lowered, machine)
      assert(
        d.edges.forall(e => e.from < e.to && d.earliest(e.to) >= d.earliest(e.from) + e.distance)
      )
      assert(d.height.max == d.criticalPath)
      (0 until 10).foreach { _ =>
        val in = Values(
          k.inputs.map(a => a.name -> Vector.fill(a.length)(rng.nextLong() & 0xffffffffL)).toMap,
          k.scalars.map(_ -> (rng.nextLong() & 0xffffffffL)).toMap
        )
        val image = layout.memoryImage(in).fold(e => fail(e), identity)
        assert(evaluate(d, image) == Lowering.evaluate(lowered, image), k.name)
      }
    }
  }
}
