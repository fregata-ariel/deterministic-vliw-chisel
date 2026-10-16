package vliw.crosscheck

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.KernelCompiler
import vliw.compiler.regalloc.RegisterAllocator
import vliw.compiler.schedule.Priority
import vliw.hw.{Core, CoreDriver, CoreParams, VliwChiselSim}
import vliw.isa._
import vliw.sim.ReferenceModel

/** The committed benchmark records (benchmarks/) are what the sources produce, and the recorded
  * programs give the expected results in the recorded cycles on the reference model and the RTL.
  */
class BenchmarkSpec extends AnyFunSuite with VliwChiselSim {
  private val machine = BenchmarkRecords.machine
  private val records = BenchmarkRecords.records(machine)
  private val fixed = BenchmarkRecords.fixed(machine)
  private val modulo = ModuloRecords.records(machine)
  private val regenerate = "sbt \"crosscheck/Test/runMain vliw.crosscheck.BenchmarkRecords\""

  /** The generator section depends on the JVM that ran it; everything else must match exactly. */
  private def comparable(path: java.nio.file.Path, text: String): String =
    if (path.getParent.getFileName.toString == "metrics") {
      val v = ujson.read(text)
      v.obj.remove("generator")
      ujson.write(v)
    } else text

  test("benchmarks/ holds exactly the generated records") {
    BenchmarkRecords.files(records, fixed, machine, Some(modulo)).foreach { case (path, expected) =>
      assert(Files.exists(path), s"$path is missing; run $regenerate")
      val actual = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
      assert(
        comparable(path, actual) == comparable(path, expected),
        s"$path is out of date; run $regenerate"
      )
    }
  }

  test("the recorded test cases hold on the reference model") {
    records.foreach { r =>
      val tc = TestCase.parse(ujson.write(r.testCase)).fold(es => fail(es.mkString("\n")), identity)
      val run = ReferenceModel
        .run(tc.machine, tc.words, tc.inputs)
        .fold(v => fail(s"${r.name}: $v"), identity)
      assert(tc.expect.cycles.contains(run.cycles), r.name)
      tc.expect.memory.foreach { case (a, v) =>
        assert(run.memory(a).contains(v), s"${r.name} [$a]")
      }
    }
  }

  test("the Modulo Scheduling records use the list-scheduling layout and inputs, and the II") {
    modulo.foreach { r =>
      val list =
        records.find(x => x.kernel == r.kernel.unroll && x.mode == BenchmarkRecords.Mode.Vliw).get
      assert(r.compiled.layout == list.compiled.layout, r.name)
      assert(r.testCase("inputs") == list.testCase("inputs"), r.name)
      assert(r.metrics("ii").num.toInt == r.compiled.ii, r.name)
    }
    assert(records.forall(_.metrics("ii").isNull))
  }

  test("the Modulo Scheduling test cases hold on the reference model and the RTL") {
    val cases = modulo.map(r =>
      r.name -> TestCase
        .parse(ujson.write(r.testCase))
        .fold(es => fail(es.mkString("\n")), identity)
    )
    cases.foreach { case (name, tc) =>
      val run =
        ReferenceModel.run(tc.machine, tc.words, tc.inputs).fold(v => fail(s"$name: $v"), identity)
      assert(tc.expect.cycles.contains(run.cycles), name)
      tc.expect.memory.foreach { case (a, v) => assert(run.memory(a).contains(v), s"$name [$a]") }
    }
    simulate(new Core(CoreParams(machine))) { dut =>
      cases.foreach { case (name, tc) =>
        val run = CoreDriver.run(dut, machine, tc.words, tc.inputs, tc.expect.memory.keySet)
        assert(run.violations.isEmpty && tc.expect.cycles.contains(run.cycles), name)
        tc.expect.memory.foreach { case (a, v) =>
          assert(run.memory(a) == BigInt(v), s"$name [$a]")
        }
      }
    }
  }

  test("the recorded test cases hold on the RTL, in the same cycles") {
    simulate(new Core(CoreParams(machine))) { dut =>
      records.foreach { r =>
        val tc =
          TestCase.parse(ujson.write(r.testCase)).fold(es => fail(es.mkString("\n")), identity)
        val run = CoreDriver.run(dut, machine, tc.words, tc.inputs, tc.expect.memory.keySet)
        assert(run.violations.isEmpty, r.name)
        assert(tc.expect.cycles.contains(run.cycles), r.name)
        tc.expect.memory.foreach { case (a, v) =>
          assert(run.memory(a) == BigInt(v), s"${r.name} [$a]")
        }
      }
    }
  }

  test("the fixed series gives every attempt of the compiler, in its order, to both modes") {
    records.foreach { r =>
      val attempts = RegisterAllocator.attempts(r.compiled.dag, Priority.all, r.mode.singleIssue)
      val rows = fixed.filter(f => f.kernel == r.kernel && f.mode == r.mode)
      assert(rows.map(_.strategy.attempt(r.mode)) == attempts, r.name)
    }
    // A row holds the result of its own attempt: one that does not fit is never replaced.
    fixed.foreach { f =>
      assert(
        f.result.forall(_.allocated.attempt == f.strategy.attempt(f.mode)),
        s"${f.kernel.name} ${f.mode.name} ${f.strategy}"
      )
    }
  }

  test("every program of the fixed series gives the expected outputs in its cycles") {
    fixed.foreach { f =>
      f.result.foreach { c =>
        val name = s"${f.kernel.name} ${f.mode.name} ${c.allocated.attempt}"
        val tc = KernelCompiler
          .testCase(c, KernelCompiler.sampleInputs(f.kernel, BenchmarkRecords.Seed), None)
          .fold(e => fail(s"$name: $e"), identity)
        val loaded = TestCase.parse(ujson.write(tc)).fold(es => fail(es.mkString("\n")), identity)
        val run = ReferenceModel
          .run(machine, c.words, loaded.inputs)
          .fold(v => fail(s"$name: $v"), identity)
        assert(run.cycles == c.cycles, name)
        loaded.expect.memory.foreach { case (a, v) =>
          assert(run.memory(a).contains(v), s"$name [$a]")
        }
      }
    }
  }

  test("the fewest-cycles series is the fixed series' first attempt with the fewest cycles") {
    records.foreach { r =>
      val order = RegisterAllocator.attempts(r.compiled.dag, Priority.all, r.mode.singleIssue)
      val fits = fixed
        .filter(f => f.kernel == r.kernel && f.mode == r.mode)
        .flatMap(_.result.toOption)
        .sortBy(c => order.indexOf(c.allocated.attempt))
      val best = fits.minBy(_.cycles) // minBy keeps the first of equal values
      assert(best.cycles == r.compiled.cycles, r.name)
      assert(best.allocated.attempt == r.compiled.allocated.attempt, r.name)
      assert(best.words == r.compiled.words, r.name)
    }
  }

  test("the single-issue baseline issues one operation per bundle and is never faster") {
    records.groupBy(_.kernel).foreach { case (k, rs) =>
      val vliw = rs.find(_.mode == BenchmarkRecords.Mode.Vliw).get
      val single = rs.find(_.mode == BenchmarkRecords.Mode.SingleIssue).get
      assert(
        single.compiled.bundles.forall(b => b.compute == ComputeOp.Nop || b.memory == MemoryOp.Nop),
        k.name
      )
      val ops = single.compiled.dag.nodes.size
      assert(
        single.compiled.cycles >= ops + 1 && single.compiled.cycles >= vliw.compiled.cycles,
        k.name
      )
      // Same arithmetic and data: the same DAG and layout.
      assert(
        single.compiled.dag.nodes == vliw.compiled.dag.nodes && single.compiled.layout == vliw.compiled.layout
      )
    }
  }
}
