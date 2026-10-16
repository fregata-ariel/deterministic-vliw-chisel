package vliw.hw

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

import vliw.isa.{MachineDescription, Slot}

object FixedPipelineSpec {

  /** One machine and its hand-computed timings of the example. */
  final case class Expected(
      machine: MachineDescription,
      ld: Int,
      mul: Int,
      cycles: Int,
      computeIssues: Seq[Int],
      memoryIssues: Seq[Int],
      computeCommits: Seq[Int],
      memoryCommits: Seq[Int],
      store: Int
  )
}

/** The fixed pipeline on hand-written plans, with hand-computed timings (no compiler involved). */
class FixedPipelineSpec extends AnyFunSuite with VliwChiselSim {
  import FixedOp._
  import FixedPipelineSpec.Expected

  private val base = MachineDescription.load().fold(es => fail(es.mkString), identity)
  private def edited(edit: ujson.Value => Unit) = {
    val v = ujson.read(base.canonicalJson)
    edit(v)
    MachineDescription.parse(ujson.write(v)).fold(es => fail(es.mkString), identity)
  }
  private def machineFile(name: String) =
    MachineDescription
      .load(java.nio.file.Paths.get("tests", "machines", name))
      .fold(es => fail(es.mkString), identity)

  /** [2] = [0] * [1] + [0] as early as `ld` and `mul` (ADD and ST latency 1) allow. */
  private def example(ld: Int, mul: Int) = FixedPlan(
    "example",
    Vector(
      Load(0, 0),
      Load(1, 1),
      Compute(1 + ld, "MUL", 0, 1),
      Compute(1 + ld + mul, "ADD", 2, 0),
      Store(2 + ld + mul, 2, 3)
    )
  )

  test("a legal plan has no violation; every rule is checked") {
    assert(FixedPlan.violations(base, example(2, 2)).isEmpty)
    def broken(ops: FixedOp*) = FixedPlan.violations(base, FixedPlan("broken", ops.toVector))
    def has(found: Seq[String], text: String) =
      assert(found.exists(_.contains(text)), found.mkString("\n"))
    // MUL at 3 is available from 5.
    has(
      broken(Load(0, 0), Load(1, 1), Compute(3, "MUL", 0, 1), Compute(4, "ADD", 2, 0)),
      "available from 5"
    )
    // MUL at 3 and ADD at 4 both write at E_5.
    has(
      broken(Load(0, 0), Load(1, 1), Compute(3, "MUL", 0, 1), Compute(4, "ADD", 0, 1)),
      "boundary E_5 has 2 writes on the compute write-back port"
    )
    has(
      broken(Load(0, 0), Load(1, 1), Compute(3, "ADD", 0, 1), Compute(3, "SUB", 0, 1)),
      "cycle 3 issues 2 compute"
    )
    has(broken(Load(0, 0), Load(0, 1)), "cycle 0 issues 2 memory")
    has(broken(Load(0, base.scratchpadWords)), "outside the scratchpad")
    has(broken(Load(0, 0), Store(2, base.scratchpadWords, 0)), "outside the scratchpad")
    has(broken(Load(0, 0), Store(2, 0, 0)), "word 0 is both loaded and stored")
    has(broken(Load(0, 0), Store(2, 1, 0), Store(3, 1, 0)), "word 1 is stored 2 times")
    has(broken(Load(0, 0), Compute(2, "HALT", 0, 0)), "HALT is not a compute operation")
    has(broken(Load(0, 0), Store(2, 1, 1)), "reads 1, which is no value")
    has(broken(Load(-1, 0)), "before E_0")
    has(broken(), "no operation")
    val ii = edited(_("operations")("MUL")("ii") = 2)
    has(
      FixedPlan.violations(
        ii,
        FixedPlan("ii", Vector(Load(0, 0), Compute(2, "MUL", 0, 0), Compute(3, "MUL", 0, 0)))
      ),
      "less than its II 2 apart"
    )
  }

  test("values share registers only when their lifetimes do not overlap, per write-back port") {
    val plan = example(2, 2)
    val perValue = FixedPlan.bind(base, plan, StoragePolicy.PerValue)
    assert(perValue.flatten.map(_.register) == Seq(0, 1, 2, 3))
    // [0] lives 2..5 and [1] 3..3 (memory port); MUL 5..5 and ADD 6..6 (compute port) share one.
    val shared = FixedPlan.bind(base, plan, StoragePolicy.ByLifetime)
    assert(shared(4).isEmpty)
    assert(shared(2) == shared(3) && shared(2).get.port == Slot.Compute)
    assert(shared(0) != shared(1) && shared(0).get.port == Slot.Memory)
    assert(shared.flatten.map(_.register).distinct.size == 3)
  }

  test("assigned registers are checked for overlapping lifetimes") {
    val plan = example(2, 2)
    def assigned(rs: Option[Int]*) = StoragePolicy.Assigned(rs.toVector)
    // [0] 2..5 and MUL 5..5 overlap at cycle 5; [1] 3..3 and ADD 6..6 do not.
    assert(
      FixedParams.check(base, plan, assigned(Some(0), Some(1), Some(2), Some(1), None)).isRight
    )
    val overlap = FixedParams.check(base, plan, assigned(Some(0), Some(1), Some(0), Some(2), None))
    assert(overlap.left.toOption.exists(_.exists(_.contains("overlap in register 0"))), overlap)
    // The mirrored overlap: [0] (4..4) starts where [1] (2..4) is read last.
    val mirrored = FixedPlan(
      "mirrored",
      Vector(Load(2, 0), Load(0, 1), Compute(4, "ADD", 0, 1), Store(5, 2, 2))
    )
    val both0 = FixedParams.check(base, mirrored, assigned(Some(0), Some(0), Some(1), None))
    assert(both0.left.toOption.exists(_.exists(_.contains("overlap in register 0"))), both0)
    val missing = FixedParams.check(base, plan, assigned(Some(0), None, Some(2), Some(3), None))
    assert(missing.left.toOption.exists(_.exists(_.contains("value 1 has no register"))), missing)
    // A register written by both ports (the memory value [1], then the ADD) gets a write mux.
    val both = FixedParams(base, plan, assigned(Some(0), Some(1), Some(2), Some(1), None))
    assert(both.registers == 3 && both.storage(1).get.port != both.storage(3).get.port)
  }

  test("machines outside the Core's range are rejected with the Core's reasons") {
    Seq("slow_units.json", "ii_units.json").foreach { f =>
      val m = machineFile(f)
      val reasons = FixedParams.check(m, example(2, 2)).left.getOrElse(fail(s"$f was accepted"))
      assert(reasons == CoreParams.unsupported(m), f)
    }
    assertThrows[IllegalArgumentException](FixedParams(base, FixedPlan("empty", Vector.empty)))
  }

  test("the fixed pipeline elaborates and compiles to SystemVerilog") {
    val sv = ChiselStage.emitSystemVerilog(
      new FixedPipeline(FixedParams(base, example(2, 2))),
      firtoolOpts = Emit.FirtoolOptions
    )
    assert(sv.contains("module FixedPipeline("))
    assert(!sv.contains("module Decoder("))
    assert(!sv.contains("module RegisterFile("))
    // On an mvp-1 machine the ComputeSlot has MAC's third operand, tied off: plans have no MAC.
    val mvp1 =
      MachineDescription.load(MachineDescription.Mvp1Path).fold(es => fail(es.mkString), identity)
    ChiselStage.emitSystemVerilog(
      new FixedPipeline(FixedParams(mvp1, example(2, 2))),
      firtoolOpts = Emit.FirtoolOptions
    )
    assert(
      FixedPlan
        .violations(mvp1, FixedPlan("mac", Vector(Load(0, 0), Compute(2, "MAC", 0, 0))))
        .exists(_.contains("MAC is not a compute operation"))
    )
  }

  private val expected = Seq(
    // LD 0, 1; MUL 3 (writes E_5); ADD 5 (E_6); ST 6 (E_7): T = 7, done in cycle 8.
    Expected(base, 2, 2, 8, Seq(3, 5), Seq(0, 1, 6), Seq(4, 5), Seq(1, 2), 6),
    // LD latency 3, MUL latency 3: LD 0, 1 (E_3, E_4); MUL 4 (E_7); ADD 7 (E_8); ST 8 (E_9):
    // T = 9, done in cycle 10.
    Expected(
      edited { v => v("operations")("LD")("latency") = 3; v("operations")("MUL")("latency") = 3 },
      3,
      3,
      10,
      Seq(4, 7),
      Seq(0, 1, 8),
      Seq(6, 7),
      Seq(2, 3),
      8
    )
  )

  expected.foreach { e =>
    test(s"the example takes ${e.cycles} cycles with LD latency ${e.ld} and MUL latency ${e.mul}") {
      val p = FixedParams(e.machine, example(e.ld, e.mul))
      assert(p.cycles == e.cycles)
      simulate(new FixedPipeline(p)) { dut =>
        Seq((7L, 3L), (0xffffffffL, 0x80000001L)).zipWithIndex.foreach { case ((a, b), i) =>
          val run = FixedDriver.run(dut, Map(0 -> a, 1 -> b), Set(2), reset = i == 0, extra = 3)
          val result = BigInt((a * b + a) & 0xffffffffL)
          assert(run.cycles == e.cycles)
          assert(run.doneCycles == Seq(e.cycles), "done is high for exactly one cycle")
          assert(run.busyCycles == (0 until e.cycles), "busy in cycles 0..T")
          assert(run.computeIssues == e.computeIssues)
          assert(run.memoryIssues == e.memoryIssues)
          assert(run.computeCommits == e.computeCommits)
          assert(run.memoryCommits == e.memoryCommits)
          assert(run.stores == Seq((e.store, 2, result)))
          assert(run.memory(2) == result)
        }
      }
    }
  }

  test("SUB: [2] = ([0] - [1]) * [0] - [1], modulo 2^32, with the operands in order") {
    val plan = FixedPlan(
      "sub",
      Vector(
        Load(0, 0),
        Load(1, 1),
        Compute(3, "SUB", 0, 1), // E_4
        Compute(4, "MUL", 2, 0), // E_6
        Compute(6, "SUB", 3, 1), // E_7
        Store(7, 2, 4) //           E_8: T = 8, done in cycle 9
      )
    )
    val p = FixedParams(base, plan)
    assert(p.cycles == 9)
    simulate(new FixedPipeline(p)) { dut =>
      Seq((3L, 7L), (0L, 0xffffffffL), (0x80000000L, 1L)).zipWithIndex.foreach { case ((a, b), i) =>
        val run = FixedDriver.run(dut, Map(0 -> a, 1 -> b), Set(2), reset = i == 0, extra = 3)
        val result = BigInt((((a - b) * a) - b) & 0xffffffffL)
        assert(run.cycles == 9 && run.doneCycles == Seq(9))
        assert(run.computeIssues == Seq(3, 4, 6))
        assert(run.computeCommits == Seq(3, 5, 6))
        assert(run.stores == Seq((7, 2, result)))
        assert(run.memory(2) == result)
      }
    }
  }

  test("a start in the done cycle is accepted: acceptances T + 2 cycles apart, as on the Core") {
    import chisel3.{fromBooleanToLiteral, fromIntToLiteral, fromIntToWidth, fromLongToLiteral}
    val p = FixedParams(base, example(2, 2))
    simulate(new FixedPipeline(p)) { dut =>
      val host = dut.io.host
      host.start.poke(false.B)
      host.memWrite.valid.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      Seq(0 -> 7L, 1 -> 3L).foreach { case (a, v) =>
        host.memWrite.valid.poke(true.B)
        host.memWrite.bits.addr.poke(a.U)
        host.memWrite.bits.data.poke(v.U(32.W))
        dut.clock.step()
      }
      host.memWrite.valid.poke(false.B)
      host.start.poke(true.B)
      dut.clock.step() // E_0
      host.start.poke(false.B)
      (0 until 2).foreach { run =>
        (0 until p.cycles).foreach { c =>
          assert(dut.io.busy.peek().litToBoolean && !dut.io.done.peek().litToBoolean, s"$run $c")
          dut.clock.step()
        }
        // Cycle T + 1: done, and a start here is accepted at E_(T+2).
        assert(dut.io.done.peek().litToBoolean && !dut.io.busy.peek().litToBoolean, s"$run")
        host.start.poke(true.B)
        dut.clock.step()
        host.start.poke(false.B)
      }
      assert(dut.io.busy.peek().litToBoolean)
    }
  }
}
