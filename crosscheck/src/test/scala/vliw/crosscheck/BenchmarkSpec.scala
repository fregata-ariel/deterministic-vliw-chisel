package vliw.crosscheck

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters._
import scala.util.Try

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.{KernelCompiler, Simd, Verifier}
import vliw.compiler.dag.{Dag, Fusion}
import vliw.compiler.dsl.{Benchmarks, Lowering}
import vliw.compiler.modulo.{LoopCandidate, LoopFallback, LoopSimd, ModuloCompiler, Outcome}
import vliw.compiler.regalloc.{NoFit, RegisterAllocator}
import vliw.compiler.schedule.Priority
import vliw.hw.{
  Core,
  CoreDriver,
  CoreParams,
  FixedDriver,
  FixedOp,
  FixedPipeline,
  Toolchain,
  VliwChiselSim
}
import vliw.isa._
import vliw.sim.ReferenceModel

/** The committed benchmark records (benchmarks/) are what the sources produce, and the recorded
  * programs give the expected results in the recorded cycles on the reference model and the RTL.
  */
class BenchmarkSpec extends AnyFunSuite with VliwChiselSim {
  import BenchmarkSpec.ListSeries

  private val machine = BenchmarkRecords.machine
  private val records = BenchmarkRecords.records(machine)
  private val fixed = BenchmarkRecords.fixed(machine)
  private val modulo = ModuloRecords.records(machine)
  private val registers = RegisterRecords.series(records, fixed, machine)
  private val fixedPipelines = BenchmarkRecords.fixedPipelines(records, machine)
  private val mac = MacRecords.series(records, fixed, modulo, machine)
  private val regenerate = "sbt \"crosscheck/Test/runMain vliw.crosscheck.BenchmarkRecords\""

  /** Every machine with both list-scheduling series: the register counts (16 is the default
    * machine) and the configurations with MAC.
    */
  private val listSeries =
    registers.map(s =>
      ListSeries(
        s"${s.config.registers} registers",
        s.config.machine,
        s.config.path,
        s.records,
        s.fixed
      )
    ) ++ mac.tail.map(s =>
      ListSeries(s.config.name, s.config.machine, s.config.path, s.records, s.fixed)
    )

  private def withMac(name: String) = mac.find(_.config.name == name).get

  private def canonical(v: ujson.Value): String =
    Json.canonical(v).fold(e => fail(e.message), identity)

  /** The generator section depends on the JVM that ran it; everything else must match exactly. */
  private def comparable(path: java.nio.file.Path, text: String): String =
    if (path.getParent.getFileName.toString == "metrics") {
      val v = ujson.read(text)
      v.obj.remove("generator")
      ujson.write(v)
    } else text

  /** `path` holds `expected` (the generator section of a metrics file aside). */
  private def matches(path: Path, expected: String): Unit = {
    assert(Files.exists(path), s"$path is missing; run $regenerate")
    val actual = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    assert(
      comparable(path, actual) == comparable(path, expected),
      s"$path is out of date; run $regenerate"
    )
  }

  /** Every file under benchmarks/. */
  private def present(): Set[Path] =
    Files.walk(BenchmarkRecords.Dir).iterator().asScala.filter(Files.isRegularFile(_)).toSet

  test("benchmarks/ holds exactly the generated records") {
    val generated = BenchmarkRecords
      .files(
        records,
        fixed,
        machine,
        Some(modulo),
        Some(registers),
        Some(fixedPipelines),
        Some(mac)
      )
    generated.foreach { case (path, expected) => matches(path, expected) }
    // Every other file is the hand-written README or has a name of SimdRecords' files, which the
    // SIMD test below generates and compares (#110 stage 6).
    val other = present() -- generated.map(_._1) - BenchmarkRecords.Dir.resolve("README.md")
    assert(other.subsetOf(SimdRecords.names), s"not generated: ${other -- SimdRecords.names}")
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
    // ChiselSim runs the Verilator on the PATH. The expected one is the pinned one of
    // project/toolchain.properties, which RESULTS.md names. VLIW_VERILATOR_EXPECTED overrides it
    // only where ADR-0020 allows (never in the required checks), and RESULTS.md still names the
    // pinned one then. docs/PHASE3.md cites this test by its name.
    val expected = Toolchain.expectedVerilator
    assert(
      Toolchain.installedVerilator == expected.version,
      s"Verilator ${Toolchain.installedVerilator}; expected ${expected.version} (${expected.source})" +
        "; see scripts/toolchain.sh check"
    )
    assert(
      Runtime.version().feature().toString == Toolchain.jdkMajor,
      s"JDK ${Runtime.version().feature()}; project/toolchain.properties pins ${Toolchain.jdkMajor}"
    )
  }

  test("the fixed series gives every attempt of the compiler, in its order, to both modes") {
    for (s <- listSeries; r <- s.records) {
      val attempts = RegisterAllocator.attempts(r.compiled.dag, Priority.all, r.mode.singleIssue)
      val rows = s.fixed.filter(f => f.kernel == r.kernel && f.mode == r.mode)
      assert(rows.map(_.strategy.attempt(r.mode)) == attempts, r.name)
      assert(r.compiled.machine == s.machine, r.name)
    }
    // A row holds the result of its own attempt: one that does not fit is never replaced.
    for (s <- listSeries; f <- s.fixed) {
      assert(
        f.result.forall(_.allocated.attempt == f.strategy.attempt(f.mode)),
        s"${f.kernel.name} ${f.mode.name} ${f.strategy} (${s.label})"
      )
    }
    // The configurations with MAC have as many registers as mvp-0: the same rows.
    mac.tail.foreach(s => assert(s.fixed.map(_.strategy) == fixed.map(_.strategy), s.config.name))
  }

  test("every program of the fixed series gives the expected outputs in its cycles") {
    for (s <- listSeries; f <- s.fixed) {
      val machine = s.machine
      f.result.foreach { c =>
        val name =
          s"${f.kernel.name} ${f.mode.name} ${c.allocated.attempt} (${s.label})"
        val tc = KernelCompiler
          .testCase(c, KernelCompiler.sampleInputs(f.kernel, BenchmarkRecords.Seed), s.path)
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
    for (s <- listSeries; r <- s.records) {
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
    listSeries.flatMap(_.records).groupBy(r => (r.kernel, r.suffix)).foreach { case ((k, _), rs) =>
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

  test("the configurations with MAC differ from config/machine-mvp1.json only as recorded") {
    assert(mac.map(_.config.name) == "mvp-0" +: MacRecords.Variants.map(_._1))
    assert(mac.head.config.machine == machine && !machine.hasMac)
    val mvp1 = withMac("mvp-1").config.machine
    assert(mvp1.hasMac && mvp1.status == "frozen" && mvp1.readPorts == 3)
    def without(m: MachineDescription)(edit: ujson.Value => Unit) = {
      val v = ujson.read(m.canonicalJson)
      Seq("machine_id", "revision", "status").foreach(v.obj.remove)
      edit(v)
      v
    }
    val rp4 = withMac("rp4").config.machine
    val mac3 = withMac("mac3").config.machine
    assert(rp4.readPorts == 4 && mac3.operation("MAC").latency == 3)
    assert(
      without(rp4)(_("register_file").obj.remove("read_ports")) ==
        without(mvp1)(_("register_file").obj.remove("read_ports"))
    )
    assert(
      without(mac3)(_("operations")("MAC").obj.remove("latency")) ==
        without(mvp1)(_("operations")("MAC").obj.remove("latency"))
    )
    // The RTL implements every configuration but rp4 (4 read ports come in #57).
    assert(mac.filter(_.config.unsupported.nonEmpty).map(_.config.name) == Seq("rp4"))
  }

  test("the records with MAC use mvp-0's layout and inputs, and fuse every MUL + ADD pair") {
    val base = mac.head
    def op(r: BenchmarkRecords.Record, m: String) =
      r.metrics("issue")("by_operation").obj.get(m).fold(0)(_.num.toInt)
    for (s <- mac.tail; r <- s.records) {
      val b = base.records.find(x => x.kernel == r.kernel && x.mode == r.mode).get
      assert(r.compiled.layout == b.compiled.layout, r.name)
      assert(r.testCase("inputs") == b.testCase("inputs"), r.name)
      assert(r.metrics("metrics").str == "vliw-metrics/2" && r.metrics("isa").str == "mvp-1")
      val unfused = Dag.of(Lowering.lower(r.compiled.layout), s.config.machine)
      val pairs = Fusion.pairs(unfused).size
      assert(pairs > 0 && r.compiled.dag.macs == pairs, r.name)
      assert(op(r, "MAC") == pairs && op(b, "MAC") == 0, r.name)
      assert(op(r, "MUL") + pairs == op(b, "MUL") && op(r, "ADD") + pairs == op(b, "ADD"), r.name)
      assert(op(r, "SUB") == op(b, "SUB") && op(r, "ST") == op(b, "ST"), r.name)
    }
    for (s <- mac.tail; l <- s.loops) {
      val b = base.loops.find(_.kernel == l.kernel).get
      assert(l.compiled.layout == b.compiled.layout, l.name)
      assert(l.testCase("inputs") == b.testCase("inputs"), l.name)
      assert(l.metrics("ii").num.toInt == l.compiled.ii, l.name)
      assert(l.compiled.fusion.isDefined && b.compiled.fusion.isEmpty, l.name)
      s.records
        .find(x => x.kernel == l.kernel.unroll && x.mode == BenchmarkRecords.Mode.Vliw)
        .foreach { r =>
          assert(l.compiled.layout == r.compiled.layout, l.name)
          assert(l.testCase("inputs") == r.testCase("inputs"), l.name)
        }
    }
  }

  test("vliw-metrics/2 counts the register reads and the bundles with MAC and ST") {
    for (s <- mac.tail; (name, doc, bundles) <- programs(s)) {
      val reads = doc("issue")("register_reads")
      assert(reads("read_ports").num.toInt == s.config.machine.readPorts, name)
      assert(reads("reads").num.toInt == bundles.map(MacRecords.reads).sum, name)
      assert(
        MacRecords.byReads(doc, bundles) == (0 to 4).map(k =>
          bundles.count(MacRecords.reads(_) == k)
        ),
        name
      )
      assert(doc("issue")("mac_with_st").num.toInt == MacRecords.macWithSt(bundles), name)
    }
  }

  test("the fixed series' write-back and read-port columns agree with the fewest-cycles records") {
    for (s <- mac; f <- s.fixed; c <- f.result.toOption) {
      val name = s"${f.kernel.name} ${f.mode.name} ${f.strategy} (${s.config.name})"
      val r = s.records.find(x => x.kernel == f.kernel && x.mode == f.mode).get
      val by = MacRecords.bundlesByReads(c.bundles)
      // One bundle per cycle, HALT's included; the reads are the DAG's, whatever the row.
      assert(by.sum == c.cycles, name)
      assert(
        c.bundles.map(MacRecords.reads).sum == r.compiled.bundles.map(MacRecords.reads).sum,
        name
      )
      // Without MAC, or with four read ports, no bundle reads more than the read ports.
      if (!MacRecords.readPortsBind(s.config.machine))
        assert(c.allocated.schedule.heldByReadPorts == 0, name)
      // The row of the chosen attempt has the record's counts and its vliw-metrics/2 reads.
      if (c.allocated.attempt == r.compiled.allocated.attempt) {
        val (a, b) = (c.allocated.schedule, r.compiled.allocated.schedule)
        assert(a.heldByWritePort == b.heldByWritePort, name)
        assert(a.heldByReadPorts == b.heldByReadPorts, name)
        assert(by == MacRecords.byReads(r.metrics, r.compiled.bundles), name)
      }
    }
    // The checks are not vacuous.
    assert(mac.exists(s => !MacRecords.readPortsBind(s.config.machine) && s.config.machine.hasMac))
    assert(mac.exists(_.fixed.exists(_.result.exists(_.allocated.schedule.heldByReadPorts > 0))))
  }

  /** The recorded programs of a configuration: name, metrics and bundles. */
  private def programs(s: MacRecords.Series) =
    s.records.map(r => (r.name, r.metrics, r.compiled.bundles)) ++
      s.loops.map(l => (l.name, l.metrics, l.compiled.bundles))

  test("each rp4 program has one L11 finding on mvp-1 per bundle with MAC and ST, and no other") {
    val rp3 = withMac("mvp-1")
    val rp4 = withMac("rp4")
    val rp4Programs =
      rp4.records.map(r => (r.name, r.compiled.layout, r.compiled.bundles, r.compiled.words)) ++
        rp4.fixed.flatMap(f =>
          f.result.toOption.map(c =>
            (s"${f.kernel.name} ${f.mode.name} ${f.strategy}", c.layout, c.bundles, c.words)
          )
        ) ++ rp4.loops.map(l => (l.name, l.compiled.layout, l.compiled.bundles, l.compiled.words))
    rp4Programs.foreach { case (name, layout, bundles, words) =>
      val legal = Verifier.verify(rp4.config.machine, words, KernelCompiler.declaration(layout))
      assert(legal.isEmpty, name)
      assert(
        MacRecords.findingsOn(rp3.config.machine, layout, words) ==
          ((MacRecords.macWithSt(bundles), 0)),
        name
      )
    }
    // The check is not vacuous.
    assert(rp4Programs.exists { case (_, _, b, _) => MacRecords.macWithSt(b) > 0 })
    // With three read ports, no program has a bundle with MAC and ST.
    for (s <- mac.tail if s.config.machine.readPorts == 3) {
      val bundles = s.records.map(_.compiled.bundles) ++ s.loops.map(_.compiled.bundles) ++
        s.fixed.flatMap(_.result.toOption.map(_.bundles))
      assert(bundles.forall(MacRecords.macWithSt(_) == 0), s.config.name)
    }
    // Fusion does not depend on the read ports: rp3 and rp4 schedule the same DAGs and loops.
    rp3.records.zip(rp4.records).foreach { case (a, b) =>
      assert(a.kernel == b.kernel && a.compiled.dag.nodes == b.compiled.dag.nodes, a.name)
    }
    rp3.loops.zip(rp4.loops).foreach { case (a, b) =>
      assert(a.kernel == b.kernel && a.compiled.graph.nodes == b.compiled.graph.nodes, a.name)
      assert(a.compiled.fusion.map(_.fuses) == b.compiled.fusion.map(_.fuses), a.name)
    }
    // Single issue reads at most three registers per bundle: the same programs on rp3 and rp4.
    rp3.fixed.filter(_.mode == BenchmarkRecords.Mode.SingleIssue).foreach { f =>
      val other =
        rp4.fixed.find(g => g.kernel == f.kernel && g.mode == f.mode && g.strategy == f.strategy)
      assert(
        other.map(_.result.map(_.words)).contains(f.result.map(_.words)),
        s"${f.kernel.name} ${f.strategy}"
      )
    }
  }

  test("the test cases with MAC hold on the reference model, and on the RTL with the same trace") {
    // SIMD.md's mvp-1 baseline of matmul4 (#110 stage 6) runs in the same mvp-1 build, from its
    // recorded test cases (the SIMD test compares the files with the generated records).
    val matmul4 = BenchmarkRecords.Mode.all.map { mode =>
      val name = s"${SimdRecords.matmul4.name}_${mode.name}_mac"
      val path = BenchmarkRecords.Dir.resolve("cases").resolve(s"$name.json")
      (name, TestCase.load(path).fold(es => fail(es.mkString("\n")), identity))
    }
    mac.tail.foreach { s =>
      val m = s.config.machine
      val cases =
        (s.records.map(r => (r.name, r.testCase, None)) ++
          s.loops.map(l => (l.name, l.testCase, Some(l.compiled)))).map { case (name, doc, loop) =>
          (
            name,
            TestCase.parse(ujson.write(doc)).fold(es => fail(es.mkString("\n")), identity),
            loop
          )
        } ++ (if (s.config.name == "mvp-1") matmul4.map { case (n, tc) => (n, tc, None) } else Nil)
      val runs = cases.map { case (name, tc, loop) =>
        assert(tc.machine == m, name)
        val run = ReferenceModel.run(m, tc.words, tc.inputs).fold(v => fail(s"$name: $v"), identity)
        assert(tc.expect.cycles.contains(run.cycles), name)
        tc.expect.memory.foreach { case (a, v) => assert(run.memory(a).contains(v), s"$name [$a]") }
        (name, tc, loop, run)
      }
      if (s.config.unsupported.isEmpty)
        simulate(new Core(CoreParams(m))) { dut =>
          runs.foreach { case (name, tc, loop, model) =>
            val rtl = CoreDriver.run(dut, m, tc.words, tc.inputs, tc.expect.memory.keySet)
            assert(rtl.violations.isEmpty && rtl.cycles == model.cycles, name)
            tc.expect.memory.foreach { case (a, v) =>
              assert(rtl.memory(a) == BigInt(v), s"$name [$a]")
            }
            assert(
              rtl.trace.map(canonical) == model.trace.cycles.map(c => canonical(c.toJson)),
              name
            )
            // The RTL's trace confirms the II of a loop as the reference model's does.
            loop.foreach { c =>
              assert(
                ModuloTrace.check(c.graph, c.result.map, rtl.trace, c.ii) == Right(Some(c.ii)),
                name
              )
            }
          }
        }
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

  // The SIMD records (#110 stage 6, SimdRecords, benchmarks/SIMD.md). They are built only inside
  // the tests below, so that a failure to build them (a verifier failure of a W program under a
  // mutant, say) fails these tests, which scripts/mutation-classify.py counts, and does not abort
  // the suite before the other records' tests run.

  private lazy val simd = Try(SimdRecords.series(mac))
  private lazy val simdReport = SimdRecords.report(simd.get)

  /** Every program of the SIMD series: name, configuration, layout, bundles, words, metrics. */
  private def simdPrograms(s: SimdRecords.Series) = {
    val (list, loops) = SimdRecords.recordsOf(s)
    list.map(r =>
      (
        r.name,
        r.compiled.machine,
        r.compiled.layout,
        r.compiled.bundles,
        r.compiled.words,
        r.metrics
      )
    ) ++ loops.map(l =>
      (
        l.name,
        l.compiled.machine,
        l.compiled.layout,
        l.compiled.bundles,
        l.compiled.words,
        l.metrics
      )
    ) ++ s.fixed.flatMap(r =>
      r.result.toOption.map(x =>
        (
          s"${r.kernel.name} ${r.mode.name} ${r.strategy} (${r.config.name})",
          r.config.machine,
          x.compiled.layout,
          x.compiled.bundles,
          x.compiled.words,
          x.metrics
        )
      )
    )
  }

  test("SIMD: benchmarks/ holds exactly the SIMD records (SIMD.md, the W files, matmul4's mvp-1)") {
    val s = simd.get
    val generated = SimdRecords.files(s)
    generated.foreach { case (path, expected) => matches(path, expected) }
    assert(generated.map(_._1).toSet.subsetOf(SimdRecords.names))
    val stale = present().filter(SimdRecords.names) -- generated.map(_._1)
    assert(stale.isEmpty, s"not generated: $stale")
    // 22 W list records (no W-reg matmul4), matmul4's 4 baseline records, 12 W loops.
    val (list, loops) = SimdRecords.recordsOf(s)
    assert((list.size, loops.size, generated.size) == ((26, 12, 77)))
    assert(!list.exists(r => r.suffix == "_wreg" && r.kernel == SimdRecords.matmul4))
  }

  test("SIMD: the test cases hold, with mvp-1's layout, inputs and fused scalar DAG everywhere") {
    val s = simd.get
    val (list, loops) = SimdRecords.recordsOf(s)
    (list.map(r => (r.name, r.testCase)) ++ loops.map(l => (l.name, l.testCase))).foreach {
      case (name, doc) =>
        val tc = TestCase.parse(ujson.write(doc)).fold(es => fail(es.mkString("\n")), identity)
        val run =
          ReferenceModel
            .run(tc.machine, tc.words, tc.inputs)
            .fold(v => fail(s"$name: $v"), identity)
        assert(tc.expect.cycles.contains(run.cycles), name)
        tc.expect.memory.foreach { case (a, v) => assert(run.memory(a).contains(v), s"$name [$a]") }
    }
    list.foreach { r =>
      val v3 = r.compiled.machine.hasVector
      assert(r.metrics("metrics").str == (if (v3) "vliw-metrics/3" else "vliw-metrics/2"), r.name)
    }
    for (f <- s.fewest) {
      val b = s.of("mvp-1", f.kernel, f.mode)
      val name = s"${f.kernel.name} ${f.mode.name} (${f.config.name})"
      assert(f.prepared.layout == b.prepared.layout, name)
      assert(f.prepared.dag.nodes == b.prepared.dag.nodes, name)
      assert(f.prepared.dag.edges == b.prepared.dag.edges, name)
      f.result.foreach(r =>
        assert(r.testCase("inputs") == b.result.toOption.get.testCase("inputs"))
      )
      // The packing is the same on W-full and mvp2_rp4 (read ports do not change it).
      if (f.config.name == "mvp2_rp4")
        assert(f.dag.nodes == s.of("W-full", f.kernel, f.mode).dag.nodes, name)
    }
    for (l <- s.loops) {
      val b = s.loop("mvp-1", l.kernel)
      val name = s"${ModuloRecords.label(l.kernel)} (${l.config.name})"
      assert(l.prepared.layout == b.prepared.layout, name)
      assert(
        l.prepared.scalar.nodes == b.prepared.scalar.nodes &&
          l.prepared.scalar.places == b.prepared.scalar.places,
        name
      )
      (l.result, b.result) match {
        case (SimdRecords.LoopResult.Measured(x), SimdRecords.LoopResult.Measured(y)) =>
          assert(x.testCase("inputs") == y.testCase("inputs"), name)
          assert(x.compiled.layout == y.compiled.layout, name)
        case _ => ()
      }
    }
  }

  test(
    "SIMD: the configurations differ from machine-mvp2.json (frozen) and mvp-1 only as recorded"
  ) {
    def at(name: String) = SimdRecords.machineOf(name)
    val full = at("W-full")
    assert(MachineDescription.load(MachineDescription.Mvp2Path) == Right(full))
    assert(full.status == "frozen" && full.hasWideMemory && full.readPorts == 3)
    assert(Seq("W-reg", "mvp2_rp4", "mvp1_rp4").map(at(_).status).distinct == Seq("proposed"))
    assert(at("mvp-1") == withMac("mvp-1").config.machine)
    assert(at("mvp1_rp4") == withMac("rp4").config.machine)
    def without(m: MachineDescription)(edit: ujson.Value => Unit) = {
      val v = ujson.read(m.canonicalJson)
      Seq("machine_id", "revision", "status").foreach(v.obj.remove)
      edit(v)
      v
    }
    def narrow(v: ujson.Value): Unit = {
      v("scratchpad").obj.remove("max_words_per_access")
      Seq("LD.W", "ST.W").foreach(v("operations").obj.remove)
    }
    val wreg = at("W-reg")
    assert(!wreg.hasWideMemory && wreg.hasVector && wreg.maxWordsPerAccess == 1)
    assert(without(wreg)(narrow) == without(full)(narrow))
    val rp4 = at("mvp2_rp4")
    assert(rp4.readPorts == 4)
    assert(
      without(rp4)(_("register_file").obj.remove("read_ports")) ==
        without(full)(_("register_file").obj.remove("read_ports"))
    )
    // The RTL implements mvp-1, W-reg and W-full; not the two with four read ports (#57).
    assert(
      SimdRecords.Configs.map(_._1).filter(n => CoreParams.unsupported(at(n)).nonEmpty) ==
        Seq("mvp1_rp4", "mvp2_rp4")
    )
  }

  test("SIMD: the fixed series gives every attempt, in order, to both modes, on the packed DAG") {
    val s = simd.get
    for (c <- s.configs; k <- SimdRecords.kernels; mode <- BenchmarkRecords.Mode.all) {
      val f = s.of(c.name, k, mode)
      val name = s"${k.name} ${mode.name} (${c.name})"
      val rows = s.strategies.map(st => s.row(c.name, k, mode, st))
      assert(
        rows.map(_.strategy.attempt(mode)) ==
          RegisterAllocator.attempts(f.dag, Priority.all, mode.singleIssue),
        name
      )
      // The W DAG is the packed one, or the scalar one with nothing to pack.
      if (c.w) assert(f.dag == (if (f.nothingToPack) f.prepared.dag else f.packing.get.dag), name)
      rows.foreach { r =>
        r.result.foreach { x =>
          val label = s"$name ${r.strategy}"
          val cc = x.compiled
          // A row holds its own attempt on the DAG of every row: never another attempt.
          assert(cc.allocated.attempt == r.strategy.attempt(mode) && cc.dag == f.dag, label)
          if (c.w) {
            val w = cc.bundles.exists(b =>
              WPrograms.isVector(b.compute) || b.memory.isInstanceOf[MemoryOp.LdW] ||
                b.memory.isInstanceOf[MemoryOp.StW]
            )
            cc.simd match {
              case Simd.Packed(_)      => assert(w, label)
              case Simd.Fallback(_, p) => assert(p.nothingToPack && !w, label)
              case Simd.Scalar         => fail(s"$label: no W outcome")
            }
          }
          assert(
            Verifier.verify(c.machine, cc.words, KernelCompiler.declaration(cc.layout)).isEmpty
          )
          assert(x.run.cycles == cc.cycles, label)
          assert(
            cc.layout.outputs(a => x.run.memory(a).getOrElse(-1L)) == s.expected(k.name),
            label
          )
        }
      }
    }
    // The checks are not vacuous: W rows of each kind, and rows that fail on each reason.
    val w = s.fixed.filter(_.config.w)
    assert(w.exists(_.result.isRight) && w.exists(_.result.left.exists(_ == NoFit.Stuck)))
    assert(w.exists(_.result.left.exists(_.isInstanceOf[NoFit.NoAlignedPair])))
    assert(w.exists(_.result.left.exists(_.isInstanceOf[NoFit.TooManyLive])))
  }

  test("SIMD: the fewest-cycles record is the fixed rows' first with the fewest cycles and LDs") {
    val s = simd.get
    for (f <- s.fewest) {
      val name = s"${f.kernel.name} ${f.mode.name} (${f.config.name})"
      val rows = s.strategies.map(st => s.row(f.config.name, f.kernel, f.mode, st))
      val fits = rows.flatMap(_.result.toOption).map(_.compiled)
      f.result match {
        case Right(r) =>
          // minBy keeps the first of equal values.
          val best = fits.minBy(c => (c.cycles, BenchmarkRecords.loads(c)))
          assert(best.allocated.attempt == r.compiled.allocated.attempt, name)
          assert(best.words == r.compiled.words, name)
        case Left(failures) =>
          // Infeasible: every attempt failed, as the fixed rows did, in their order.
          assert(fits.isEmpty, name)
          assert(
            failures == rows.map(r => r.strategy.attempt(f.mode) -> r.result.left.toOption.get),
            name
          )
      }
    }
    // W-reg matmul4 is the only kernel without a W record, in both modes (#110 第6段の Q2).
    assert(
      s.fewest.filter(_.result.isLeft).map(f => (f.config.name, f.kernel.name, f.mode.name)) ==
        Seq(("W-reg", "matmul4", "vliw"), ("W-reg", "matmul4", "single"))
    )
  }

  test("SIMD: operations, register words and transfer words agree with the programs") {
    val s = simd.get
    import SimdRecords._
    for ((name, m, _, bundles, _, x) <- simdPrograms(s)) {
      def n(p: Bundle => Boolean) = bundles.count(p)
      val ldw = n(_.memory.isInstanceOf[MemoryOp.LdW])
      val stw = n(_.memory.isInstanceOf[MemoryOp.StW])
      val vector = n(b => WPrograms.isVector(b.compute))
      val (ops, words) = (transferOperations(x), transfer(x))
      assert(words == ((ops._1 + ldw, ops._2 + stw)), name)
      assert(written(x) == ((commits(x)._1 + vector, commits(x)._2 + ldw)), name)
      if (!m.hasVector) {
        assert(words == ops && written(x) == commits(x) && readRegisters(x) == readFields(x), name)
        assert(x("metrics").str == "vliw-metrics/2", name)
      }
      assert(operations(x)._1 + operations(x)._2 == byOperation(x).map(_._2).sum, name)
    }
    // At the DAG level, W keeps mvp-1's transfer words but for FIR's windows: LD.W of overlapping
    // words on W-full, the gathers' LDs on W-reg.
    for (c <- s.configs.filter(_.w); k <- SimdRecords.kernels) {
      val w = dagWords(s.of(c.name, k, BenchmarkRecords.Mode.Vliw).dag)
      val b = dagWords(s.of(Baseline(c.name), k, BenchmarkRecords.Mode.Vliw).dag)
      if (k.name == "fir4x8") assert(w == ((24, 8)) && b == ((15, 8)), c.name)
      else assert(w == b, s"${k.name} (${c.name})")
    }
    // The program level sentence states the counts (no fixed claim that W keeps the words).
    val programs = for {
      c <- s.configs.filter(_.w)
      k <- SimdRecords.kernels
      mode <- BenchmarkRecords.Mode.all
      r <- s.of(c.name, k, mode).result.toOption
      b <- s.of(Baseline(c.name), k, mode).result.toOption
    } yield transfer(r.metrics) == transfer(b.metrics)
    assert(simdReport.contains(s"記録のある ${programs.size} のうち ${programs.count(identity)} で"))
    assert(programs.contains(false) && programs.contains(true))
  }

  test(
    "SIMD: each mvp2_rp4 program has one L11 finding on W-full per bundle with rs3 and a store"
  ) {
    val s = simd.get
    val full = s.config("W-full").machine
    val rp4 = simdPrograms(s).filter(_._2 == s.config("mvp2_rp4").machine)
    rp4.foreach { case (name, m, layout, bundles, words, x) =>
      assert(Verifier.verify(m, words, KernelCompiler.declaration(layout)).isEmpty, name)
      val both = bundles.count(SimdRecords.isRs3WithStore)
      assert(SimdRecords.rs3WithStore(x) == both, name)
      assert(MacRecords.findingsOn(full, layout, words) == ((both, 0)), name)
    }
    assert(rp4.exists { case (_, _, _, b, _, _) => b.exists(SimdRecords.isRs3WithStore) })
    // With three read ports, no W program has such a bundle.
    simdPrograms(s).filter(p => p._2.readPorts == 3).foreach { case (name, _, _, b, _, _) =>
      assert(!b.exists(SimdRecords.isRs3WithStore), name)
    }
    // No assertion that mvp2_rp4 is never slower: the generated text names the rows where it is.
    val slower = for {
      k <- SimdRecords.kernels
      st <- s.strategies
      x <- s.row("W-full", k, BenchmarkRecords.Mode.Vliw, st).result.toOption
      y <- s.row("mvp2_rp4", k, BenchmarkRecords.Mode.Vliw, st).result.toOption
      if y.compiled.cycles > x.compiled.cycles
    } yield s"は `mvp2_rp4` の方が遅い（${x.compiled.cycles} → ${y.compiled.cycles}。"
    slower.foreach(t => assert(simdReport.contains(t), t))
  }

  test("SIMD: the W loops record the II of the trace; W-reg loops are not measured") {
    val s = simd.get
    for (l <- s.loops) {
      val name = s"${ModuloRecords.label(l.kernel)} (${l.config.name})"
      (l.config.w, l.result) match {
        case (_, SimdRecords.LoopResult.Measured(r)) =>
          assert(r.metrics("ii").num.toInt == r.compiled.ii, name)
          // A doubled loop's iteration is two original iterations.
          val doubled = SimdRecords.doubled(l)
          assert(r.compiled.graph.trips * (if (doubled) 2 else 1) == l.kernel.trips, name)
          assert(
            SimdRecords.perOriginal(l, r.compiled.ii) ==
              (if (doubled) SimdRecords.half(r.compiled.ii) else r.compiled.ii.toString),
            name
          )
          l.candidate.foreach {
            case _: LoopCandidate.NothingToPack =>
              // Nothing to pack: the scalar loop, mvp-1's program.
              val b = s.loop(SimdRecords.Baseline(l.config.name), l.kernel).compiled.get
              assert(r.compiled.words == b.words, name)
              assert(r.compiled.simd.isInstanceOf[LoopSimd.Fallback], name)
            case _: LoopCandidate.Packed =>
              assert(r.compiled.simd.isInstanceOf[LoopSimd.Vectorized])
            case other => fail(s"$name: measured with $other")
          }
        case (true, SimdRecords.LoopResult.NotMeasured(why)) =>
          assert(l.config.name == "W-reg" && why == LoopFallback.WReg, name)
          assert(l.candidate.contains(LoopCandidate.NotMeasured(LoopFallback.WReg)), name)
        case (_, other) =>
          assert(SimdRecords.matmul4Loops.contains(l.kernel), s"$name: $other")
      }
    }
    assert(s.loops.filter(_.config.name == "W-reg").forall(_.compiled.isEmpty))
  }

  test(
    "SIMD: matmul4's loops fit no II at 16 registers, with every II's outcome; W-reg not measured"
  ) {
    val s = simd.get
    for (k <- SimdRecords.matmul4Loops; c <- s.configs) {
      val l = s.loop(c.name, k)
      val name = s"${ModuloRecords.label(k)} (${c.name})"
      l.result match {
        case SimdRecords.LoopResult.NoProgram(no) =>
          assert(!c.w && ModuloCompiler.compile(k, c.machine).isLeft, name)
          assert(no.attempts.map(_.ii) == (no.mii.mii to no.iiMax), name)
          assert(no.attempts.forall(_.outcome != Outcome.Fits), name)
        case SimdRecords.LoopResult.Infeasible(d, no) =>
          // The candidate's attempts are those of the W graph's search.
          assert(l.candidate.contains(LoopCandidate.Infeasible(d, no.attempts)), name)
          assert(no.attempts.map(_.ii) == (no.mii.mii to no.iiMax), name)
          assert(c.machine.hasWideMemory, name)
        case SimdRecords.LoopResult.NotMeasured(_) => assert(c.name == "W-reg", name)
        case other                                 => fail(s"$name: $other")
      }
      l.result match {
        case SimdRecords.LoopResult.NoProgram(no) =>
          assert(simdReport.contains(ModuloRecords.reasons(no.attempts)), name)
        case SimdRecords.LoopResult.Infeasible(_, no) =>
          assert(simdReport.contains(ModuloRecords.reasons(no.attempts)), name)
        case _ => ()
      }
    }
  }

  test("SIMD: the per-resource bounds hold for every row and give #78's table for three kernels") {
    val s = simd.get
    import SimdRecords._
    for (f <- s.fewest) {
      val b = bounds(f.dag)
      assert(b.binding.forall(n => b(n).contains(b.max)) && b.binding.nonEmpty)
      (0 until f.config.machine.banks).foreach(k => assert(b(s"bank $k").get <= b("ms").get))
      for (st <- s.strategies; x <- s.row(f.config.name, f.kernel, f.mode, st).result.toOption) {
        val cc = x.compiled
        assert(cc.cycles >= b.max, s"${f.kernel.name} ${f.mode.name} $st (${f.config.name})")
        if (f.mode.singleIssue) assert(cc.cycles >= cc.allocated.dag.nodes.size + 1)
      }
    }
    for (
      k <- Benchmarks.all;
      (e, c) <- Seq("M1" -> "mvp-1", "WR" -> "W-reg", "WF" -> "W-full", "WF4" -> "mvp2_rp4")
    ) {
      val (bound, names) = ListEstimates(k.name -> e)._2.get
      val b = bounds(s.of(c, k, BenchmarkRecords.Mode.Vliw).dag)
      assert(b.max == bound, s"${k.name} $e")
      assert(
        EstimateResource.filter(r => b.binding.contains(r._1)).map(_._2).mkString("・") == names,
        s"${k.name} $e: ${b.binding}"
      )
    }
  }

  test("SIMD: the per-row ports agree with the programs and the fewest-cycles records") {
    val s = simd.get
    import SimdRecords._
    for (r <- s.fixed; x <- r.result.toOption) {
      val name = s"${r.kernel.name} ${r.mode.name} ${r.strategy} (${r.config.name})"
      val f = s.of(r.config.name, r.kernel, r.mode)
      // One bundle per cycle, HALT's included; the fields read are the DAG's, whatever the row.
      assert(byReads(x.metrics).sum == x.compiled.cycles, name)
      assert(readFields(x.metrics) == f.dag.nodes.map(_.fields).sum, name)
      if (r.config.machine.readPorts == 4)
        assert(x.compiled.allocated.schedule.heldByReadPorts == 0, name)
      f.result.foreach { rec =>
        if (rec.compiled.allocated.attempt == x.compiled.allocated.attempt) {
          val (a, b) = (x.compiled.allocated.schedule, rec.compiled.allocated.schedule)
          assert(a.heldByWritePort == b.heldByWritePort && a.heldByReadPorts == b.heldByReadPorts)
          Seq(Seq("issue", "compute", "nop_rate"), Seq("writeback", "compute", "utilization"))
            .foreach(p =>
              assert(
                p.foldLeft(x.metrics: ujson.Value)(_(_)) == p.foldLeft(rec.metrics: ujson.Value)(
                  _(_)
                ),
                name
              )
            )
          assert(
            byReads(x.metrics) == byReads(rec.metrics) && rs3WithStore(x.metrics) == rs3WithStore(
              rec.metrics
            ),
            name
          )
        }
      }
    }
    assert(s.fixed.exists(_.result.exists(_.compiled.allocated.schedule.heldByReadPorts > 0)))
  }

  test("SIMD: the sentence on matmul3's third column states what Pack gives") {
    val s = simd.get
    val matmul3 = Benchmarks.all.find(_.name == "matmul3").get
    val f = s.of("W-full", matmul3, BenchmarkRecords.Mode.Vliw)
    val p = f.packing.get
    val c = matmul3.outputs.head
    val third = (0 until 3).map(i => f.prepared.layout.elem(c.name, 3 * i + 2))
    val lanes = p.scalarLanes.map(_.addr).toSet
    val text = SimdRecords.packingText(matmul3, f.prepared.layout, p)
    assert(simdReport.contains(s"W-full の `matmul3` の詰め方は $text で"))
    assert(
      simdReport.contains(
        s"3列目の語 c(0,2)、c(1,2)、c(2,2) は" +
          (if (third.forall(lanes)) "どれもスカラーのレーンである" else "すべてがスカラーのレーンではない")
      )
    )
    // Each scalar lane and its reason, and the pairs that cross a row, are in the text.
    p.scalarLanes.foreach { l =>
      assert(
        text.contains(s"${SimdRecords.word(matmul3, f.prepared.layout, l.addr)}（${l.reason.name}）")
      )
    }
    val crossing = p.pairs.count(q => (q.addr - f.prepared.layout.elem(c.name, 0)) % 3 == 2)
    assert(text.contains(s"（行をまたぐ組 $crossing）"))
  }
}

object BenchmarkSpec {

  /** Both list-scheduling series of one machine: a register count or a configuration with MAC, and
    * the path its test cases give (None for the default machine).
    */
  final case class ListSeries(
      label: String,
      machine: MachineDescription,
      path: Option[String],
      records: Seq[BenchmarkRecords.Record],
      fixed: Seq[BenchmarkRecords.Fixed]
  )
}
