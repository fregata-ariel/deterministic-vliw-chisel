package vliw.compiler.regalloc

import scala.util.Random

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.KernelCompiler
import vliw.compiler.dag.{Dag, DagRun, EdgeKind, Fusion, Node, Pack, Src}
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
  private val mvp1 = MachineDescription
    .load(java.nio.file.Paths.get("config", "machine-mvp1.json"))
    .fold(es => fail(es.mkString("\n")), identity)
  // 8, 12 and 16 registers on the three machines of the benchmarks, and on the three mvp-1
  // machines (#77), whose DAGs have MACs.
  private val machines = for {
    m <- Seq(machine, machineFile("slow_units.json"), machineFile("ii_units.json")) ++
      Seq(mvp1, machineFile("mvp1_rp4.json"), machineFile("mvp1_mac3.json"))
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
        case Node.Mac(a, b, c) => values(i) = Op.Add(Op.Mul(values(a), values(b)), values(c)); mem
        case Node.Store(a, v)  => mem.updated(a, values(v))
        case other             => fail(s"$other: not a node of a scalar DAG")
      }
    }
  }

  test("a split gives the moved readers a copy of the LD; ids stay topological; values stay") {
    val rng = new Random(70L)
    for (m <- Seq(machine, mvp1); k <- Benchmarks.all) {
      val p = prepared(k, m)
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
        case (Node.Mac(a, b, c), i)     => assert(operandsOf(split, i) == Set(a, b, c))
        case (Node.Store(_, v), i)      => assert(operandsOf(split, i) == Set(v))
        case (_: Node.Load, i)          => assert(operandsOf(split, i).isEmpty)
        case (other, _)                 => fail(s"$other: not a node of a scalar DAG")
      }
      assert(split.loads > p.dag.loads)
      (0 until 5).foreach { _ =>
        val image =
          p.layout.memoryImage(KernelCompiler.sampleInputs(k, rng.nextLong())).toOption.get
        assert(evaluate(split, image) == Lowering.evaluate(lowered, image), k.name)
      }
    }
  }

  test("a ST that reads a moved LD reads the copy") {
    // [5] = [0]; [6] = [0] * [1]: the LD of [0] is read by the ST of [5] and by the MUL.
    val d = Dag.build(machine, Vector(Write(5, Read(0)), Write(6, Bin(Op.Mul, Read(0), Read(1)))))
    val ld0 = d.nodes.indexOf(Node.Load(0))
    val st = d.nodes.indexOf(Node.Store(5, ld0))
    assert(d.readers(ld0).size == 2 && d.readers(ld0).contains(st))
    val s = d.split(ld0, Set(st))
    assert(s.nodes(st) == Node.Load(0) && s.nodes(st + 1) == Node.Store(5, st))
    s.nodes.zipWithIndex.foreach {
      case (Node.Compute(_, a, b), i) => assert(operandsOf(s, i) == Set(a, b))
      case (Node.Mac(a, b, c), i)     => assert(operandsOf(s, i) == Set(a, b, c))
      case (Node.Store(_, v), i)      => assert(operandsOf(s, i) == Set(v))
      case (_: Node.Load, i)          => assert(operandsOf(s, i).isEmpty)
      case (other, _)                 => fail(s"$other: not a node of a scalar DAG")
    }
  }

  test("a split re-points whichever operand of a MAC reads the LD") {
    def mac(a: Int, b: Int, c: Int) = Bin(Op.Add, Bin(Op.Mul, Read(a), Read(b)), Read(c))
    // [10] = [0] * [1] + [2]; [11] = [1] * [0] + [3]; [12] = [3] * [2] + [0]: fused, LD 0, LD 1,
    // LD 2, MAC 3, ST 4, LD 3, MAC 6, ST 7, MAC 8, ST 9. The LD of [0] is the first operand of
    // MAC 3, the second of MAC 6 and the addend of MAC 8.
    val d = Fusion.fuse(
      Dag.build(
        mvp1,
        Vector(Write(10, mac(0, 1, 2)), Write(11, mac(1, 0, 3)), Write(12, mac(3, 2, 0)))
      )
    )
    assert(d.nodes(3) == Node.Mac(0, 1, 2) && d.nodes(6) == Node.Mac(1, 0, 5))
    assert(d.nodes(8) == Node.Mac(5, 2, 0) && d.readers(0) == Vector(3, 6, 8))
    val later = d.split(0, Set(6, 8)) // the copy at 6; MAC 3 keeps the original
    assert(later.nodes(3) == Node.Mac(0, 1, 2) && later.nodes(6) == Node.Load(0))
    assert(later.nodes(7) == Node.Mac(1, 6, 5) && later.nodes(9) == Node.Mac(5, 2, 6))
    val first = d.split(0, Set(3)) // the copy at 3
    assert(first.nodes(3) == Node.Load(0) && first.nodes(4) == Node.Mac(3, 1, 2))
    assert(first.nodes(7) == Node.Mac(1, 0, 6) && first.nodes(9) == Node.Mac(6, 2, 0))
    val memory = Map(0 -> 2L, 1 -> 3L, 2 -> 5L, 3 -> 7L)
    val expected = memory ++ Map(10 -> 11L, 11 -> 13L, 12 -> 37L)
    for (s <- Seq(d, later, first)) {
      assert(evaluate(s, memory) == expected)
      s.nodes.zipWithIndex.foreach { case (n, i) => assert(operandsOf(s, i) == n.operands.toSet) }
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

  test("of two candidates, the one whose next use is furthest is chosen; ties to the lower id") {
    def stopped(d: Dag, placed: Set[Int]) =
      ListScheduler.Stopped(d.nodes.indices.map(i => if (placed(i)) 0 else -1).toVector, 0, true)
    def mul(a: Int, b: Int) = Bin(Op.Mul, Read(a), Read(b))
    // [10] = [0]*[1]; [11] = [2]*[3]; [12] = [0]*[4] + [5]; [13] = [2]*[5], with the LDs and the
    // first MULs of [0] and [2] placed. [0]'s next use, the MUL before an ADD, is higher (critical
    // path) and earlier (program order) than [2]'s: [2] is the furthest by both.
    val d = Dag.build(
      machine,
      Vector(
        Write(10, mul(0, 1)),
        Write(11, mul(2, 3)),
        Write(12, Bin(Op.Add, mul(0, 4), Read(5))),
        Write(13, mul(2, 5))
      )
    )
    val (ld0, ld2) = (d.nodes.indexOf(Node.Load(0)), d.nodes.indexOf(Node.Load(2)))
    val loads = d.nodes.indices.filter(d.nodes(_).isInstanceOf[Node.Load]).toSet
    val at = stopped(d, loads + d.readers(ld0).head + d.readers(ld2).head)
    assert(Rematerialization.candidates(d, at) == Vector(ld0, ld2))
    assert(d.height(d.readers(ld0).last) > d.height(d.readers(ld2).last))
    Priority.all.foreach(p => assert(Rematerialization.choose(d, p, at) == Some(ld2), p))
    // Without the ADD the next uses tie on height: the lower id wins under critical-path.
    val t = Dag.build(
      machine,
      Vector(Write(10, mul(0, 1)), Write(11, mul(2, 3)), Write(12, mul(0, 4)), Write(13, mul(2, 5)))
    )
    val (t0, t2) = (t.nodes.indexOf(Node.Load(0)), t.nodes.indexOf(Node.Load(2)))
    val tLoads = t.nodes.indices.filter(t.nodes(_).isInstanceOf[Node.Load]).toSet
    val tAt = stopped(t, tLoads + t.readers(t0).head + t.readers(t2).head)
    assert(t.height(t.readers(t0).last) == t.height(t.readers(t2).last))
    assert(Rematerialization.choose(t, Priority.CriticalPath, tAt) == Some(t0))
    assert(Rematerialization.choose(t, Priority.ProgramOrder, tAt) == Some(t2))
  }

  test("candidates are placed LDs with readers placed and not placed") {
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
          case Left(other) => fail(s"$other: a DAG without pairs (${k.name} ${m.machineId} $a)")
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
    // fewer cycles with 12 registers than with 16: program-order, reserve 0 is stuck at 12 and
    // copies (55, LD 37), but fits at 16 without copies (57), so it makes none there.
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

  test("cycles and LDs at 8 and 12 registers on slow_units and ii_units") {
    val got = for {
      f <- Seq("slow_units.json", "ii_units.json"); r <- Seq(8, 12); k <- Benchmarks.all
    } yield {
      val c =
        KernelCompiler.compile(k, withRegisters(machineFile(f), r)).fold(e => fail(e), identity)
      (f, r, k.name) -> (c.cycles, c.allocated.dag.loads)
    }
    // A slot left empty only by an operation's II (ii_units) is not a block of the register limit.
    assert(
      got.toMap == Map(
        ("slow_units.json", 8, "saxpy8") -> (31, 17),
        ("slow_units.json", 8, "fir4x8") -> (82, 56),
        ("slow_units.json", 8, "matmul3") -> (64, 45),
        ("slow_units.json", 12, "saxpy8") -> (28, 17),
        ("slow_units.json", 12, "fir4x8") -> (72, 45),
        ("slow_units.json", 12, "matmul3") -> (58, 37),
        ("ii_units.json", 8, "saxpy8") -> (60, 17),
        ("ii_units.json", 8, "fir4x8") -> (100, 29),
        ("ii_units.json", 8, "matmul3") -> (89, 27),
        ("ii_units.json", 12, "saxpy8") -> (53, 17),
        ("ii_units.json", 12, "fir4x8") -> (100, 15),
        ("ii_units.json", 12, "matmul3") -> (88, 24)
      )
    )
  }

  test("copying at every block from the start (Remat.Always), the values ADR-0016 cites") {
    val dag = prepared(Benchmarks.matmul(3), machine).dag
    def cycles(r: Either[_, Allocated]) = r.map(a => (a.schedule.cycles, a.dag.loads))
    // matmul3 at 16 registers: 54 cycles chosen (56 when only stuck attempts copy), but critical-
    // path with load reserve 1 goes from 56 to 63, and the single-issue choice from 75 to 77.
    assert(
      cycles(RegisterAllocator.scheduleAndAllocate(dag, remat = Remat.Always)) == Right((54, 35))
    )
    val cr1 = Attempt(Priority.CriticalPath, Some(ListScheduler.Limit(16, 1)))
    assert(cycles(RegisterAllocator.run(dag, cr1)) == Right((56, 18)))
    assert(RegisterAllocator.run(dag, cr1, Remat.Always).map(_.schedule.cycles) == Right(63))
    assert(
      RegisterAllocator
        .scheduleAndAllocate(dag, Priority.all, singleIssue = true, Remat.Always)
        .map(_.schedule.cycles) == Right(77)
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

  // ISA mvp-2 (W, #110 stage 4; ADR-0023): LD.W [a] may be copied when no ST writes a or a + 1, a
  // broadcast LD as any LD; a LD that is a lane of a gather is not copied (既定).
  private val mvp2 = MachineDescription
    .load(java.nio.file.Paths.get("config", "machine-mvp2.json"))
    .fold(es => fail(es.mkString("\n")), identity)
  private val mw1 = machineFile("mvp2_mw1.json")
  private def packed(k: Kernel, m: MachineDescription) = prepared(k, m).packing.get

  test("ISA mvp-2: a split renames pair, broadcast and store fields; the values stay") {
    // [10] = [0]; [11] = [1]; [12] = [0] + [2]; [13] = [1] + [3]: LD.W [0] is read by ST.W [10] and
    // by the VADD of the pair (12, 13).
    val ws = Vector(
      Write(10, Read(0)),
      Write(11, Read(1)),
      Write(12, Bin(Op.Add, Read(0), Read(2))),
      Write(13, Bin(Op.Add, Read(1), Read(3)))
    )
    val d = Pack.pack(Fusion.fuse(Dag.build(mvp2, ws))).dag
    val ldw = d.nodes.indexOf(Node.LoadW(0))
    val stw = d.readers(ldw).find(d.nodes(_).isInstanceOf[Node.StoreW]).get
    assert(d.readers(ldw).size == 2 && d.rematerializable(ldw))
    val s = d.split(ldw, Set(stw))
    assert(s.nodes(stw) == Node.LoadW(0) && s.nodes(stw + 1) == Node.StoreW(10, Src.Pair(stw)))
    // [0..3] = 7, 14, 21, 28: [10] = 7, [11] = 14, [12] = 7 + 21, [13] = 14 + 28.
    val memory = (0 until 4).map(a => a -> (a + 1L) * 7).toMap
    val expected = memory ++ Map(10 -> 7L, 11 -> 14L, 12 -> 28L, 13 -> 42L)
    assert(DagRun.execute(s, s.nodes.indices, memory) == expected)
    // The packed benchmarks: every LD.W and broadcast LD with several readers, split for the later
    // half of its readers, one at a time.
    val rng = new Random(110L)
    for (m <- Seq(mvp2, mw1); k <- Benchmarks.all :+ Benchmarks.saxpy(7)) {
      val p = prepared(k, m)
      val d0 = p.packing.get.dag
      var kinds = Set.empty[String]
      val split = d0.nodes.indices.reverse.foldLeft(d0) { (d, v) =>
        val rs = d.readers(v)
        if (d.rematerializable(v) && rs.size > 1) {
          val moved = rs.drop(rs.size / 2).toSet
          val s = d.split(v, moved)
          val at = moved.min
          assert(s.nodes(at) == d.nodes(v) && s.readers(at) == moved.toVector.sorted.map(_ + 1))
          moved.foreach { r =>
            val fields = s.nodes(r + 1).sources
            fields.foreach(f => assert(!f.nodes.contains(v)))
            fields
              .collect { case Src.Pair(`at`) => "pair"; case Src.Broadcast(`at`) => "broadcast" }
              .foreach(kinds += _)
          }
          s
        } else d
      }
      assert(split.edges.forall(e => e.from < e.to), k.name)
      if (m == mvp2 && k.name == "fir4x8") assert(kinds == Set("pair", "broadcast"))
      val image = p.layout.memoryImage(KernelCompiler.sampleInputs(k, rng.nextLong())).toOption.get
      assert(
        DagRun.execute(split, DagRun.randomOrder(split, rng), image) ==
          Lowering.evaluate(Lowering.lower(p.layout), image),
        s"${k.name} on ${m.machineId}"
      )
    }
  }

  test("ISA mvp-2: copies only in stuck attempts, of LD.W and LDs outside gathers") {
    var (wide, broadcast) = (0, 0)
    for {
      base <- Seq(mvp2, mw1); r <- Seq(8, 12, 16)
      k <- Benchmarks.all ++ Seq(Benchmarks.saxpy(7)) ++ RandomKernels.kernels(51L, 60)
    } {
      val m = withRegisters(base, r)
      val p = packed(k, m)
      val d = p.dag
      val name = s"${k.name} on ${m.machineId} with $r registers"
      d.gathers.foreach(g => assert(!d.rematerializable(g.lo) && !d.rematerializable(g.hi), name))
      val sources = d.nodes.flatMap(_.sources).collect { case Src.Broadcast(n) => d.nodes(n) }.toSet
      if (!p.nothingToPack) RegisterAllocator.attempts(d, Priority.all).foreach { a =>
        val plain = RegisterAllocator.run(d, a, Remat.Never)
        val remat = RegisterAllocator.run(d, a)
        plain match {
          case Right(x)          => assert(remat == Right(x), s"$name $a")
          case Left(NoFit.Stuck) => assert(remat.forall(_.dag.loads > d.loads), s"$name $a")
          case Left(_)           => assert(remat == plain, s"$name $a") // scheduled, not allocated
        }
        remat.foreach { al =>
          val c = al.dag
          // The gathers and their lanes are those of the packed DAG.
          assert(c.gathers.size == d.gathers.size, s"$name $a")
          if (c.loads > d.loads) {
            if (c.nodes.count(_.isInstanceOf[Node.LoadW]) > p.count("LD.W")) wide += 1
            if (c.nodes.count(sources) > d.nodes.count(sources)) broadcast += 1
          }
          // In the program, a word read by more than one LD or LD.W is never stored.
          val code = al.allocation.bundles
          val read = code.flatMap {
            case Bundle(_, MemoryOp.Ld(_, x))  => Seq(x)
            case Bundle(_, MemoryOp.LdW(_, x)) => Seq(x, x + 1)
            case _                             => Nil
          }
          val stored = code.flatMap {
            case Bundle(_, MemoryOp.St(x, _))  => Seq(x)
            case Bundle(_, MemoryOp.StW(x, _)) => Seq(x, x + 1)
            case _                             => Nil
          }.toSet
          assert(
            read.groupBy(identity).collect { case (w, n) if n.size > 1 => w }.forall(!stored(_))
          )
        }
      }
    }
    assert(wide > 0 && broadcast > 0, s"LD.W copies $wide, broadcast copies $broadcast")
  }
}
