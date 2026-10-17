package vliw.crosscheck

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.{KernelCompiler, Verifier}
import vliw.compiler.dag.{Dag, Fusion}
import vliw.compiler.dsl.Lowering
import vliw.compiler.regalloc.RegisterAllocator
import vliw.compiler.schedule.Priority
import vliw.hw.{Core, CoreDriver, CoreParams, FixedDriver, FixedOp, FixedPipeline, VliwChiselSim}
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

  test("benchmarks/ holds exactly the generated records") {
    BenchmarkRecords
      .files(
        records,
        fixed,
        machine,
        Some(modulo),
        Some(registers),
        Some(fixedPipelines),
        Some(mac)
      )
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
        }
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
