package vliw.crosscheck

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.KernelCompiler
import vliw.compiler.regalloc.RegisterAllocator
import vliw.compiler.schedule.Priority
import vliw.hw.{Core, CoreDriver, CoreParams, FixedDriver, FixedOp, FixedPipeline, VliwChiselSim}
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
  private val registers = RegisterRecords.series(records, fixed, machine)
  private val fixedPipelines = BenchmarkRecords.fixedPipelines(records, machine)
  private val regenerate = "sbt \"crosscheck/Test/runMain vliw.crosscheck.BenchmarkRecords\""

  /** The generator section depends on the JVM that ran it; everything else must match exactly. */
  private def comparable(path: java.nio.file.Path, text: String): String =
    if (path.getParent.getFileName.toString == "metrics") {
      val v = ujson.read(text)
      v.obj.remove("generator")
      ujson.write(v)
    } else text

  test("benchmarks/ holds exactly the generated records") {
    BenchmarkRecords
      .files(records, fixed, machine, Some(modulo), Some(registers), Some(fixedPipelines))
      .foreach { case (path, expected) =>
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

  test("the fixed pipelines give the recorded outputs in the recorded cycles on ChiselSim") {
    fixedPipelines.foreach { f =>
      val name = f.kernel.name
      // The burnt-in circuit takes the recorded VLIW program's cycles.
      assert(f.burntIn.cycles == f.vliw.compiled.cycles, name)
      assert(f.planned.cycles >= f.bound._1 && f.planned.cycles <= f.burntIn.cycles, name)
      val tc =
        TestCase.parse(ujson.write(f.vliw.testCase)).fold(es => fail(es.mkString("\n")), identity)
      // Cycle by cycle, the burnt-in plan issues what the recorded program's bundles issue.
      val bundles = new BundleCodec(machine).decodeAll(tc.words).toOption.get
      def compute(o: ComputeOp) = o match {
        case _: ComputeOp.Add => Seq("ADD")
        case _: ComputeOp.Sub => Seq("SUB")
        case _: ComputeOp.Mul => Seq("MUL")
        case _                => Nil
      }
      def memory(o: MemoryOp) = o match {
        case MemoryOp.Ld(_, a) => Seq(s"LD $a")
        case MemoryOp.St(a, _) => Seq(s"ST $a")
        case _                 => Nil
      }
      val ops = f.burntIn.plan.ops
      bundles.zipWithIndex.foreach { case (b, c) =>
        assert(
          ops.collect { case x: FixedOp.Compute if x.issue == c => x.mnemonic } == compute(
            b.compute
          ),
          s"$name $c"
        )
        assert(
          ops.collect {
            case FixedOp.Load(`c`, a)     => s"LD $a"
            case FixedOp.Store(`c`, a, _) => s"ST $a"
          } == memory(b.memory),
          s"$name $c"
        )
      }
      assert(bundles.last.compute == ComputeOp.Halt && bundles.size == f.burntIn.cycles, name)
      f.circuits.foreach { case (series, c) =>
        assert(c.prepared.layout == f.vliw.compiled.layout, s"$name $series")
        simulate(new FixedPipeline(c.params)) { dut =>
          val run = FixedDriver.run(dut, tc.inputs.memory, tc.expect.memory.keySet)
          assert(run.cycles == c.cycles, s"$name $series")
          tc.expect.memory.foreach { case (a, v) =>
            assert(run.memory(a) == BigInt(v), s"$name $series [$a]")
          }
        }
      }
    }
  }

  test("the fixed pipelines are measured with the Verilator and JDK that the records name") {
    // RESULTS.md names the versions that ADR-0004 pins; ChiselSim runs the Verilator on the PATH.
    assert(
      BenchmarkRecords.verilatorVersion == BenchmarkRecords.PinnedVerilator,
      s"Verilator ${BenchmarkRecords.verilatorVersion}; the records and ADR-0004 name ${BenchmarkRecords.PinnedVerilator}"
    )
    assert(
      Runtime.version().feature().toString == BenchmarkRecords.PinnedJdk,
      s"JDK ${Runtime.version().feature()}; the records and ADR-0004 name ${BenchmarkRecords.PinnedJdk}"
    )
  }

  test("the fixed series gives every attempt of the compiler, in its order, to both modes") {
    for (s <- registers; r <- s.records) {
      val attempts = RegisterAllocator.attempts(r.compiled.dag, Priority.all, r.mode.singleIssue)
      val rows = s.fixed.filter(f => f.kernel == r.kernel && f.mode == r.mode)
      assert(rows.map(_.strategy.attempt(r.mode)) == attempts, r.name)
      assert(r.compiled.machine == s.config.machine, r.name)
    }
    // A row holds the result of its own attempt: one that does not fit is never replaced.
    for (s <- registers; f <- s.fixed) {
      assert(
        f.result.forall(_.allocated.attempt == f.strategy.attempt(f.mode)),
        s"${f.kernel.name} ${f.mode.name} ${f.strategy} (${s.config.registers} registers)"
      )
    }
  }

  test("every program of the fixed series gives the expected outputs in its cycles") {
    for (s <- registers; f <- s.fixed) {
      val machine = s.config.machine
      f.result.foreach { c =>
        val name =
          s"${f.kernel.name} ${f.mode.name} ${c.allocated.attempt} (${s.config.registers} registers)"
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

  test(
    "the fewest-cycles series is the fixed series' first attempt with the fewest cycles and LDs"
  ) {
    for (s <- registers; r <- s.records) {
      val order = RegisterAllocator.attempts(r.compiled.dag, Priority.all, r.mode.singleIssue)
      val fits = s.fixed
        .filter(f => f.kernel == r.kernel && f.mode == r.mode)
        .flatMap(_.result.toOption)
        .sortBy(c => order.indexOf(c.allocated.attempt))
      // minBy keeps the first of equal values.
      val best = fits.minBy(c => (c.cycles, BenchmarkRecords.loads(c)))
      assert(best.cycles == r.compiled.cycles, r.name)
      assert(best.allocated.attempt == r.compiled.allocated.attempt, r.name)
      assert(best.words == r.compiled.words, r.name)
    }
  }

  test("the single-issue baseline issues one operation per bundle and is never faster") {
    registers.flatMap(_.records).groupBy(r => (r.kernel, r.suffix)).foreach { case ((k, _), rs) =>
      val vliw = rs.find(_.mode == BenchmarkRecords.Mode.Vliw).get
      val single = rs.find(_.mode == BenchmarkRecords.Mode.SingleIssue).get
      assert(
        single.compiled.bundles.forall(b => b.compute == ComputeOp.Nop || b.memory == MemoryOp.Nop),
        k.name
      )
      val ops = single.compiled.allocated.dag.nodes.size
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

  test("the register-count machines differ from the default one only in the register count") {
    registers.filter(_.config.path.isDefined).foreach { s =>
      val v = ujson.read(s.config.machine.canonicalJson)
      val d = ujson.read(machine.canonicalJson)
      assert(v("register_file")("count").num.toInt == s.config.registers)
      Seq(v, d).foreach { x =>
        x.obj.remove("machine_id"); x.obj.remove("revision"); x.obj.remove("status")
        x("register_file").obj.remove("count")
      }
      assert(v == d, s.config.path)
    }
    assert(registers.map(_.config.registers) == RegisterRecords.Counts)
  }

  test("the register-count test cases hold on the reference model and the RTL") {
    registers.filter(_.config.path.isDefined).foreach { s =>
      val m = s.config.machine
      val cases = s.records.map(r =>
        r.name -> TestCase
          .parse(ujson.write(r.testCase))
          .fold(es => fail(es.mkString("\n")), identity)
      )
      cases.foreach { case (name, tc) =>
        assert(tc.machine == m, name)
        val run = ReferenceModel.run(m, tc.words, tc.inputs).fold(v => fail(s"$name: $v"), identity)
        assert(tc.expect.cycles.contains(run.cycles), name)
        tc.expect.memory.foreach { case (a, v) => assert(run.memory(a).contains(v), s"$name [$a]") }
      }
      simulate(new Core(CoreParams(m))) { dut =>
        cases.foreach { case (name, tc) =>
          val run = CoreDriver.run(dut, m, tc.words, tc.inputs, tc.expect.memory.keySet)
          assert(run.violations.isEmpty && tc.expect.cycles.contains(run.cycles), name)
          tc.expect.memory.foreach { case (a, v) =>
            assert(run.memory(a) == BigInt(v), s"$name [$a]")
          }
        }
      }
    }
  }
}
