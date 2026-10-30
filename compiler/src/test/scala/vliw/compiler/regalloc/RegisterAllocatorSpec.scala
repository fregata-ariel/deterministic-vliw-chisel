package vliw.compiler.regalloc

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.{KernelCompiler, Verifier}
import vliw.compiler.dag.{Dag, Edge, EdgeKind, Fusion, Node, Src}
import vliw.compiler.dsl._
import vliw.compiler.dsl.LoweredKernel.{Bin, Read, Write}
import vliw.compiler.schedule.{ListScheduler, Priority, Schedule}
import vliw.isa._

class RegisterAllocatorSpec extends AnyFunSuite {
  private def machineFile(name: String) =
    MachineDescription
      .load(java.nio.file.Paths.get("tests", "machines", name))
      .fold(es => fail(es.mkString("\n")), identity)
  private val machine = MachineDescription.load().fold(es => fail(es.mkString("\n")), identity)
  private val machines = Seq(machine, machineFile("slow_units.json"), machineFile("ii_units.json"))

  private def withRegisters(m: MachineDescription, n: Int) = {
    val v = ujson.read(m.canonicalJson)
    v("register_file")("count") = n
    MachineDescription.parse(ujson.write(v)).fold(es => fail(es.mkString), identity)
  }

  private def compiled(k: Kernel, m: MachineDescription) = {
    val layout = MemoryLayout.fixedOrder(k, m).fold(e => fail(e), identity)
    val dag = Dag.of(Lowering.lower(layout), m)
    (layout, RegisterAllocator.scheduleAndAllocate(dag))
  }

  /** The verifier's verdict on an allocated kernel, with the layout's declaration (M_in only). */
  private def findings(m: MachineDescription, layout: MemoryLayout, a: Allocated) = {
    val words = new BundleCodec(m).encodeAll(a.allocation.bundles).fold(e => fail(e), identity)
    Verifier.verify(
      m,
      words,
      Verifier.Declaration(
        Set.empty,
        layout.constants.values.toSet ++
          layout.kernel.inputs.flatMap(i => (0 until i.length).map(layout.elem(i.name, _))) ++
          layout.kernel.scalars.map(layout.scalar)
      )
    )
  }

  test(
    "intervals run from the write's issue to the last reader's issue; a register is reused from there"
  ) {
    // [5] = ([0] + [1]) + [2], [6] = [3] + [4], scheduled without limit.
    val d = Dag.build(
      machine,
      Vector(
        Write(5, Bin(Op.Add, Bin(Op.Add, Read(0), Read(1)), Read(2))),
        Write(6, Bin(Op.Add, Read(3), Read(4)))
      )
    )
    val s = ListScheduler.schedule(d)
    val iv = RegisterAllocator.intervals(s)
    iv.foreach { i =>
      val readers = d.successors(i.value).map(e => s.issue(e.to))
      assert(i.end == readers.max && i.start == s.issue(i.value))
    }
    val a = RegisterAllocator.allocate(s).fold(e => fail(e), identity)
    // Two values share a register only if one's interval ends before the other's starts.
    for (x <- iv; y <- iv if x.value < y.value && a.register(x.value) == a.register(y.value))
      assert(x.end <= y.start || y.end <= x.start, s"$x and $y share r${a.register(x.value)}")
    assert(a.register.values.max + 1 == RegisterAllocator.maxLive(s))
  }

  test("a MAC reads its three operands at issue: their intervals end there (mvp-1)") {
    val mvp1 = MachineDescription
      .load(java.nio.file.Paths.get("config", "machine-mvp1.json"))
      .fold(es => fail(es.mkString("\n")), identity)
    def mac(a: Int, b: Int, c: Int) = Bin(Op.Add, Bin(Op.Mul, Read(a), Read(b)), Read(c))
    // [10] = [0] * [1] + [2]: LD 0, LD 1, LD 2, MAC 3, ST 4.
    val d = Fusion.fuse(Dag.build(mvp1, Vector(Write(10, mac(0, 1, 2)))))
    val s = ListScheduler.schedule(d)
    assert(s.issue == Vector(0, 1, 2, 4, 6))
    val iv = RegisterAllocator.intervals(s)
    assert(iv == Vector(Interval(0, 0, 4), Interval(1, 1, 4), Interval(2, 2, 4), Interval(3, 4, 6)))
    val a = RegisterAllocator.allocate(s).fold(e => fail(e), identity)
    assert(a.bundles(4).compute == ComputeOp.Mac(a.register(3), 0, 1, 2))
    // [10] = [0] * [0] + [0]: one LD read by three fields; the MAC may reuse its register.
    val one = Fusion.fuse(Dag.build(mvp1, Vector(Write(10, mac(0, 0, 0)))))
    val so = ListScheduler.schedule(one)
    assert(RegisterAllocator.intervals(so) == Vector(Interval(0, 0, 2), Interval(1, 2, 4)))
    val ao = RegisterAllocator.allocate(so).fold(e => fail(e), identity)
    assert(ao.bundles(2).compute == ComputeOp.Mac(0, 0, 0, 0))
    assert(ao.bundles(4).memory == MemoryOp.St(10, 0))
  }

  test("allocated benchmarks pass the verifier on three machines with 16 registers") {
    for (m <- machines; k <- Benchmarks.all) {
      val (layout, result) = compiled(k, m)
      val a = result.fold(e => fail(s"${k.name} on ${m.machineId}: $e"), identity)
      assert(a.allocation.register.values.forall(r => r >= 0 && r < m.registerCount))
      val f = findings(m, layout, a)
      assert(
        f.isEmpty,
        s"${k.name} on ${m.machineId} (${a.attempt}):\n${Disassembler.program(a.allocation.bundles)}${f.mkString("\n")}"
      )
      assert(RegisterAllocator.maxLive(a.schedule) <= m.registerCount)
    }
  }

  test("cycles after register allocation on the default machine, and the attempt that gave them") {
    val got = Benchmarks.all.map { k =>
      val a = compiled(k, machine)._2.fold(e => fail(e), identity)
      k.name -> (a.schedule.cycles, a.attempt.toString)
    }.toMap
    // saxpy8 keeps its unlimited schedule (11 live values). fir4x8 and matmul3 need 33 and 28
    // registers unlimited, so they are rescheduled under the limit; this costs 2 and 4 cycles over
    // the unlimited schedules (63, 52). Recorded so that changes show up (docs/COMPILER.md).
    assert(got("saxpy8") == (26, "critical-path, no register limit"))
    assert(got("fir4x8")._1 == 65)
    assert(got("matmul3")._1 == 56)
  }

  test("too few registers are reported, not spilled") {
    // With 3 registers, a MUL of two LDs leaves one for the sum: no attempt fits, even with copies.
    val tiny = withRegisters(machine, 3)
    val (_, result) = compiled(Benchmarks.matmul(3), tiny)
    assert(result.left.exists(_.contains("no schedule fits 3 registers")))
    val one = RegisterAllocator.allocate(
      ListScheduler.schedule(compiled(Benchmarks.saxpy(8), machine)._2.toOption.get.schedule.dag)
    )
    assert(one.isRight)
  }

  test("one attempt gives its schedule or why it does not fit") {
    val layout = MemoryLayout
      .fixedOrder(Benchmarks.fir(Seq(1L, 2L, 3L, 4L), 8), machine)
      .fold(e => fail(e), identity)
    val dag = Dag.of(Lowering.lower(layout), machine)
    def run(p: Priority, limit: Option[ListScheduler.Limit], remat: Remat = Remat.WhenStuck) =
      RegisterAllocator.run(dag, Attempt(p, limit), remat)
    // Without a limit, 33 values are live at once (docs/COMPILER.md); with 16 registers and any load
    // reserve, critical-path gets stuck while every word is read once. Issuing LDs again (#70), it
    // fits with load reserve 1 to 5, in more cycles than program-order with reserve 4, which stays
    // the compiler's choice.
    assert(run(Priority.CriticalPath, None) == Left(NoFit.TooManyLive(33, 16)))
    val remat = (0 to 8).map { reserve =>
      val limit = Some(ListScheduler.Limit(16, reserve))
      assert(run(Priority.CriticalPath, limit, Remat.Never) == Left(NoFit.Stuck), reserve)
      run(Priority.CriticalPath, limit).map(a => (a.schedule.cycles, a.dag.loads))
    }
    assert(
      remat == Seq(Left(NoFit.Stuck)) ++ Seq((75, 47), (84, 52), (84, 52), (83, 54), (87, 53))
        .map(Right(_)) ++ Seq.fill(3)(Left(NoFit.Stuck))
    )
    val chosen = run(Priority.ProgramOrder, Some(ListScheduler.Limit(16, 4)))
    assert(chosen.map(_.schedule.cycles) == Right(65))
    assert(chosen.map(_.attempt) == RegisterAllocator.scheduleAndAllocate(dag).map(_.attempt))
  }

  test("every successful attempt gives a legal program, not only the chosen one") {
    for (k <- Benchmarks.all) {
      val layout = MemoryLayout.fixedOrder(k, machine).fold(e => fail(e), identity)
      val dag = Dag.of(Lowering.lower(layout), machine)
      RegisterAllocator.attempts(dag, Priority.all).foreach { at =>
        ListScheduler
          .run(dag, at.priority, at.limit)
          .flatMap(s => RegisterAllocator.allocate(s).toOption)
          .foreach { al =>
            assert(findings(machine, layout, Allocated(at, al)).isEmpty, s"${k.name} ($at)")
          }
      }
    }
  }

  // ISA mvp-2 (W, #110 stage 4; ADR-0023): pair-aware allocation.
  private val mvp2 = MachineDescription
    .load(java.nio.file.Paths.get("config", "machine-mvp2.json"))
    .fold(es => fail(es.mkString("\n")), identity)
  private val mw1 = machineFile("mvp2_mw1.json")

  /** A schedule made by hand (the allocator reads only the issue cycles and the DAG): every node is
    * read by its own ST at the cycle given, so its interval is `[issue, read)`.
    */
  private def handMade(m: MachineDescription, values: Seq[(Node, Int, Int)]): Schedule = {
    val n = values.size
    val nodes = values.map(_._1).toVector ++ values.indices.map { i =>
      if (values(i)._1.isPair) Node.StoreW(100 + 2 * i, Src.Pair(i)) else Node.Store(100 + 2 * i, i)
    }
    val edges =
      values.indices.map(i => Edge(i, n + i, m.operation(nodes(i).mnemonic).latency, EdgeKind.Data))
    val issue = values.map(_._2).toVector ++ values.map(_._3)
    Schedule(Dag(m, nodes, edges.toVector), issue, values.map(_._3).max + 1)
  }

  test("ISA mvp-2: aligned pairs and gather lanes in p and p + 1 for every packed attempt") {
    for (m <- Seq(mvp2, mw1); k <- Benchmarks.all :+ Benchmarks.saxpy(7)) {
      val p = KernelCompiler.prepare(k, m).fold(e => fail(e), identity)
      RegisterAllocator.allAttempts(p.packing.get.dag).foreach {
        case (at, Right(a)) =>
          val d = a.dag
          val reg = a.allocation.register
          val label = s"${k.name} on ${m.machineId} ($at)"
          d.nodes.indices.filter(d.nodes(_).isPair).foreach(i => assert(reg(i) % 2 == 0, label))
          d.gathers.foreach(g => assert(reg(g.lo) % 2 == 0 && reg(g.hi) == reg(g.lo) + 1, label))
          assert(reg.values.forall(r => r >= 0 && r < m.registerCount), label)
          assert(findings(m, p.layout, a).isEmpty, label)
          assert(RegisterAllocator.maxLive(a.schedule) <= m.registerCount, label)
        case _ => ()
      }
    }
  }

  test("ISA mvp-2: a gather is live from its earlier lane; pairs count 2 in maxLive") {
    val p = KernelCompiler.prepare(Benchmarks.saxpy(8), mw1).fold(e => fail(e), identity)
    val s = ListScheduler.schedule(p.packing.get.dag)
    val d = s.dag
    val groups = RegisterAllocator.groups(s)
    d.gathers.foreach { g =>
      val iv = groups(d.group(g.lo))
      assert(d.group(g.hi) == iv.group && iv.weight == 2)
      assert(iv.start == (s.issue(g.lo).min(s.issue(g.hi))) && s.issue(g.lo) != s.issue(g.hi))
      val readers = d.nodes.indices.filter(j => d.nodes(j).operands.exists(Set(g.lo, g.hi)))
      assert(iv.end == readers.map(s.issue).max)
    }
    // Weighted: a counts 1, each gather and VMAC result 2; more than the groups live at once.
    def live(f: GroupInterval => Int) =
      (0 to s.halt).map(c => groups.filter(g => g.start <= c && c < g.end).map(f).sum).max
    assert(RegisterAllocator.maxLive(s) == live(_.weight) && live(_.weight) > live(_ => 1))
  }

  test("ISA mvp-2: best fit for scalars only when the DAG has pairs; lowest free otherwise") {
    // r0, r1 and r2 are free again at 2, r3 is in use until 9; the LD at 3 takes the lowest free
    // register r0 without pairs, and r2 (its partner r3 is in use) with a pair in the DAG.
    val scalars = Seq(
      (Node.Load(0), 0, 2),
      (Node.Load(1), 0, 2),
      (Node.Load(2), 0, 2),
      (Node.Load(3), 0, 9),
      (Node.Load(4), 3, 5)
    )
    val lowest =
      RegisterAllocator.assign(handMade(mvp2, scalars)).fold(e => fail(e.toString), identity)
    assert(lowest.register(4) == 0)
    val withPair = scalars :+ ((Node.LoadW(6), 6, 8))
    val best =
      RegisterAllocator.assign(handMade(mvp2, withPair)).fold(e => fail(e.toString), identity)
    assert(best.register(4) == 2 && best.register(5) == 0)
    assert((0 to 3).map(best.register) == (0 to 3) && (0 to 3).map(lowest.register) == (0 to 3))
  }

  test("ISA mvp-2: NoAlignedPair when the registers are free but not as an aligned pair") {
    // 4 registers. A [0, 9) r0, B [1, 3) r1, C [2, 9) r2 (best fit: none, so the lowest free);
    // at 4 the pair D [4, 6) finds r1 and r3 free, not aligned. Live: A, C and D = 4 <= 4.
    val four = withRegisters(mvp2, 4)
    val abcd = Seq(
      (Node.Load(0), 0, 9),
      (Node.Load(1), 1, 3),
      (Node.Load(2), 2, 9),
      (Node.LoadW(4), 4, 6)
    )
    assert(RegisterAllocator.assign(handMade(four, abcd)) == Left(NoFit.NoAlignedPair(4, 4, 4)))
    // With 6 registers D gets r4:r5.
    val six = RegisterAllocator.assign(handMade(withRegisters(mvp2, 6), abcd))
    assert(six.map(a => (0 to 3).map(a.register)) == Right(Seq(0, 1, 2, 4)))
    // B until 9 as well: 5 registers live at 4 > 4 is TooManyLive, not NoAlignedPair.
    val five = abcd.updated(1, (Node.Load(1), 1, 9))
    assert(RegisterAllocator.assign(handMade(four, five)) == Left(NoFit.TooManyLive(5, 4)))
    assert(
      RegisterAllocator.allocate(handMade(four, five)) == Left(
        "5 values are live at once; the machine has 4 registers"
      )
    )
  }

  test("ISA mvp-2: a limited attempt can fail with NoAlignedPair; another attempt may still fit") {
    // Measured on the benchmarks and random kernels at 8 registers (W-full): limited attempts that
    // the scheduler completes, whose allocation finds no aligned pair.
    val eight = withRegisters(mvp2, 8)
    val tried = for {
      k <- Benchmarks.all ++ RandomKernels.kernels(51L, 60)
      p = KernelCompiler.prepare(k, eight).fold(e => fail(e), identity).packing.get
      if !p.nothingToPack
      (at, r) <- RegisterAllocator.allAttempts(p.dag)
    } yield (k.name, at, r)
    val limited = tried.collect {
      case (k, at, Left(f: NoFit.NoAlignedPair)) if at.limit.nonEmpty => (k, f)
    }
    assert(limited.nonEmpty)
    limited.foreach { case (_, f) => assert(f.live <= f.registers && f.registers == 8) }
    // Some kernel with such a failure still has an attempt that fits.
    val names = limited.map(_._1).toSet
    assert(tried.exists { case (k, _, r) => names(k) && r.isRight })
  }
}
