package vliw.compiler.regalloc

import scala.util.Random

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.KernelCompiler
import vliw.compiler.dag.{Dag, EdgeKind, Node}
import vliw.compiler.dsl._
import vliw.compiler.dsl.LoweredKernel.{Bin, Read, Write}
import vliw.compiler.schedule.{ListScheduler, Priority}
import vliw.isa._

/** Rematerialization (#70): copies of LDs of words that no store writes, made only when a limited
  * attempt gets stuck.
  */
class RematerializationSpec extends AnyFunSuite {
  private def machineFile(name: String) =
    MachineDescription
      .load(java.nio.file.Paths.get("tests", "machines", name))
      .fold(es => fail(es.mkString("\n")), identity)
  private val machine = MachineDescription.load().fold(es => fail(es.mkString("\n")), identity)
  private def withRegisters(m: MachineDescription, n: Int) = {
    val v = ujson.read(m.canonicalJson)
    v("register_file")("count") = n
    MachineDescription.parse(ujson.write(v)).fold(es => fail(es.mkString), identity)
  }
  // 8, 12 and 16 registers on the three machines of the benchmarks.
  private val machines = for {
    m <- Seq(machine, machineFile("slow_units.json"), machineFile("ii_units.json"))
    r <- Seq(8, 12, 16)
  } yield withRegisters(m, r)

  private def prepared(k: Kernel, m: MachineDescription) =
    KernelCompiler.prepare(k, m).fold(e => fail(e), identity)

  private def operandsOf(d: Dag, i: Int) =
    d.predecessors(i).filter(_.kind == EdgeKind.Data).map(_.from).toSet

  /** Executes the DAG node by node in id order (independent of the scheduler). */
  private def evaluate(d: Dag, memory: Map[Int, Long]): Map[Int, Long] = {
    val values = new Array[Long](d.nodes.size)
    d.nodes.zipWithIndex.foldLeft(memory) { case (mem, (n, i)) =>
      n match {
        case Node.Load(a)           => values(i) = mem(a); mem
        case Node.Compute(op, a, b) => values(i) = op(values(a), values(b)); mem
        case Node.Store(a, v)       => mem.updated(a, values(v))
      }
    }
  }

  test("a split gives the moved readers a copy of the LD; ids stay topological; values stay") {
    val rng = new Random(70L)
    Benchmarks.all.foreach { k =>
      val p = prepared(k, machine)
      val lowered = Lowering.lower(p.layout)
      // Moves the later half of the readers of every shared LD, one LD at a time.
      val split = p.dag.nodes.indices.reverse.foldLeft(p.dag) { (d, v) =>
        val rs = d.readers(v)
        if (d.rematerializable(v) && rs.size > 1) {
          val moved = rs.drop(rs.size / 2).toSet
          val s = d.split(v, moved)
          val at = moved.min
          assert(s.nodes(at) == d.nodes(v) && s.nodes.size == d.nodes.size + 1)
          assert(s.readers(at) == moved.toVector.sorted.map(_ + 1))
          assert(s.readers(v) == rs.filterNot(moved))
          s
        } else d
      }
      assert(split.edges.forall(e => e.from < e.to), k.name)
      // Every data edge names an operand of its node, and every operand has its edge.
      split.nodes.zipWithIndex.foreach {
        case (Node.Compute(_, a, b), i) => assert(operandsOf(split, i) == Set(a, b))
        case (Node.Store(_, v), i)      => assert(operandsOf(split, i) == Set(v))
        case (_: Node.Load, i)          => assert(operandsOf(split, i).isEmpty)
      }
      assert(split.loads > p.dag.loads)
      (0 until 5).foreach { _ =>
        val image =
          p.layout.memoryImage(KernelCompiler.sampleInputs(k, rng.nextLong())).toOption.get
        assert(evaluate(split, image) == Lowering.evaluate(lowered, image), k.name)
      }
    }
  }

  test("a LD of a word that a store writes is never copied") {
    // [0] = [1] + [1]; [3] = [0] * [2]; [4] = [0] + [2]: the LD of [0] after its store is shared.
    val d = Dag.build(
      machine,
      Vector(
        Write(0, Bin(Op.Add, Read(1), Read(1))),
        Write(3, Bin(Op.Mul, Read(0), Read(2))),
        Write(4, Bin(Op.Add, Read(0), Read(2)))
      )
    )
    val ld0 = d.nodes.indexOf(Node.Load(0))
    val ld2 = d.nodes.indexOf(Node.Load(2))
    assert(d.readers(ld0).size == 2 && d.readers(ld2).size == 2)
    assert(!d.rematerializable(ld0) && d.rematerializable(ld2))
    assertThrows[IllegalArgumentException](d.split(ld0, Set(d.readers(ld0).last)))
    assert(d.split(ld2, Set(d.readers(ld2).last)).loads == d.loads + 1)
    // Neither can every reader move: the original would have none.
    assertThrows[IllegalArgumentException](d.split(ld2, d.readers(ld2).toSet))
  }

  test("candidates are placed LDs with readers placed and not placed; the furthest is chosen") {
    // [5] = [0]*[1] + [0]*[2] + [0]*[3]: the LD of [0] has three readers.
    val d = Dag.build(
      machine,
      Vector(
        Write(
          5,
          Bin(
            Op.Add,
            Bin(Op.Add, Bin(Op.Mul, Read(0), Read(1)), Bin(Op.Mul, Read(0), Read(2))),
            Bin(Op.Mul, Read(0), Read(3))
          )
        )
      )
    )
    val ld0 = d.nodes.indexOf(Node.Load(0))
    val muls = d.readers(ld0)
    def stopped(placed: Set[Int]) =
      ListScheduler.Stopped(d.nodes.indices.map(i => if (placed(i)) 0 else -1).toVector, 0, true)
    val loads = d.nodes.indices.filter(d.nodes(_).isInstanceOf[Node.Load]).toSet
    assert(Rematerialization.candidates(d, stopped(loads)).isEmpty) // no reader placed
    val one = stopped(loads + muls.head)
    assert(Rematerialization.candidates(d, one) == Vector(ld0))
    assert(Rematerialization.choose(d, Priority.ProgramOrder, one) == Some(ld0))
    assert(Rematerialization.candidates(d, stopped(loads ++ muls)).isEmpty) // all placed
    assert(Rematerialization.candidates(d, stopped(Set(muls.head))).isEmpty) // LD not placed
  }

  test("an attempt that fits without copies is unchanged; copies only where it gets stuck") {
    for (m <- machines; k <- Benchmarks.all; single <- Seq(false, true)) {
      val dag = prepared(k, m).dag
      RegisterAllocator.attempts(dag, Priority.all, single).foreach { a =>
        val plain = RegisterAllocator.run(dag, a, Remat.Never)
        val remat = RegisterAllocator.run(dag, a)
        plain match {
          case Right(x) => assert(remat == Right(x), s"${k.name} ${m.machineId} $a")
          case Left(NoFit.TooManyLive(_, _)) => assert(remat == plain)
          case Left(NoFit.Stuck)             => assert(remat.forall(_.dag.loads > dag.loads))
        }
      }
    }
  }

  test("at 8, 12 and 16 registers, every kernel compiles and every program passes the verifier") {
    for (m <- machines; k <- Benchmarks.all; single <- Seq(false, true)) {
      val p = prepared(k, m)
      val name = s"${k.name} on ${m.machineId} with ${m.registerCount} registers"
      val fits = RegisterAllocator
        .attempts(p.dag, Priority.all, single)
        .flatMap(RegisterAllocator.run(p.dag, _).toOption)
      assert(fits.nonEmpty, name)
      fits.foreach { a =>
        val c = KernelCompiler.finish(p, a).fold(e => fail(s"$name (${a.attempt}): $e"), identity)
        assert(a.allocation.register.values.forall(_ < m.registerCount))
        // Independent of the DAG: in the program, a word read by more than one LD is never stored.
        val ld = c.bundles.collect { case Bundle(_, MemoryOp.Ld(_, addr)) => addr }
        val st = c.bundles.collect { case Bundle(_, MemoryOp.St(addr, _)) => addr }.toSet
        assert(ld.size == a.dag.loads)
        assert(ld.groupBy(identity).collect { case (w, n) if n.size > 1 => w }.forall(!st(_)))
        assert(ld.toSet.subsetOf(p.layout.initialized), name)
      }
    }
  }

  test("cycles and LDs at 8, 12 and 16 registers on the default machine") {
    val got = for (r <- Seq(8, 12, 16); k <- Benchmarks.all) yield {
      val c = KernelCompiler.compile(k, withRegisters(machine, r)).fold(e => fail(e), identity)
      (r, k.name) -> (c.cycles, c.allocated.dag.loads)
    }
    // Recorded so that changes show up (docs/COMPILER.md, benchmarks/REGISTERS.md). matmul3 takes
    // fewer cycles with 12 registers than with 16: at 16 its attempts fit without copies.
    assert(
      got.toMap == Map(
        (8, "saxpy8") -> (28, 17),
        (8, "fir4x8") -> (70, 55),
        (8, "matmul3") -> (61, 44),
        (12, "saxpy8") -> (26, 17),
        (12, "fir4x8") -> (66, 45),
        (12, "matmul3") -> (55, 37),
        (16, "saxpy8") -> (26, 17),
        (16, "fir4x8") -> (65, 15),
        (16, "matmul3") -> (56, 18)
      )
    )
  }

  test("cycles and LDs at 16 registers on slow_units and ii_units (docs/COMPILER.md)") {
    val got = for (f <- Seq("slow_units.json", "ii_units.json"); k <- Benchmarks.all) yield {
      val c = KernelCompiler.compile(k, machineFile(f)).fold(e => fail(e), identity)
      (f, k.name) -> (c.cycles, c.allocated.dag.loads)
    }
    // slow_units' matmul3 gets 57 cycles from an attempt that is stuck without copies; 60 before.
    assert(
      got.toMap == Map(
        ("slow_units.json", "saxpy8") -> (27, 17),
        ("slow_units.json", "fir4x8") -> (70, 15),
        ("slow_units.json", "matmul3") -> (57, 34),
        ("ii_units.json", "saxpy8") -> (53, 17),
        ("ii_units.json", "fir4x8") -> (100, 15),
        ("ii_units.json", "matmul3") -> (88, 18)
      )
    )
  }

  test("the compiler's choice changes only where copies give strictly fewer cycles") {
    for (m <- machines; k <- Benchmarks.all; single <- Seq(false, true)) {
      val dag = prepared(k, m).dag
      val before = RegisterAllocator.scheduleAndAllocate(dag, Priority.all, single, Remat.Never)
      val after = RegisterAllocator.scheduleAndAllocate(dag, Priority.all, single)
      val name = s"${k.name} ${m.machineId} ${m.registerCount} single=$single"
      assert(after.isRight, name)
      before.foreach { b =>
        val a = after.toOption.get
        assert(a == b || (a.schedule.cycles < b.schedule.cycles && a.dag.loads > dag.loads), name)
      }
    }
  }

  test("compiling twice gives the same program") {
    for (r <- Seq(8, 12); k <- Benchmarks.all) {
      val m = withRegisters(machine, r)
      val a = KernelCompiler.compile(k, m).fold(e => fail(e), identity)
      val b = KernelCompiler.compile(k, m).fold(e => fail(e), identity)
      assert(a.words == b.words && a.allocated.attempt == b.allocated.attempt)
    }
  }

  test("the benchmarks' copies need no memory-order edge: their DAGs have data edges only") {
    val p = prepared(Benchmarks.fir(Seq(1L, 2L, 3L, 4L), 8), withRegisters(machine, 8))
    val a = KernelCompiler.compile(p.kernel, p.machine).fold(e => fail(e), identity)
    val d = a.allocated.dag
    assert(d.edges.forall(e => e.kind == EdgeKind.Data))
    assert(d.loads == 55)
  }
}
