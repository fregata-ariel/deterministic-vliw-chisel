package vliw.compiler.regalloc

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.Verifier
import vliw.compiler.dag.Dag
import vliw.compiler.dsl._
import vliw.compiler.dsl.LoweredKernel.{Bin, Read, Write}
import vliw.compiler.schedule.{ListScheduler, Priority}
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
}
