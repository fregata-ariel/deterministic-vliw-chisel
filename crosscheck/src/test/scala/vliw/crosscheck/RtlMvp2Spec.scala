package vliw.crosscheck

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.util.{Failure, Random, Success, Try}

import chisel3.simulator.Exceptions.AssertionFailed
import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.Verifier
import vliw.hw.{Core, CoreDriver, CoreParams, CoreRun, VliwChiselSim}
import vliw.isa._
import vliw.sim.{Execution, ReferenceModel, Violation}

import RtlPrograms.canonical

/** ISA mvp-2 (W, docs/ISA.md "mvp-2: W") on the RTL (#110 stage 3), against the hand-written
  * expectations of the W cases, the reference model and the verifier.
  *
  * One build per machine the RTL implements (W-full, W-reg, the latency variant, and a generated
  * W-full variant), each running many programs (ADR-0013: every `simulate` is a Verilator build):
  *   - the W cases of the machine (the generated variant has none): the legal ones against their
  *     expectations and the model's trace, the illegal ones with [[CoreDriver.run]]'s
  *     `stopAtViolation` (the rules the core signals in the first violating cycle are some of the
  *     case's rules), the L8-only ones to done without a signal;
  *   - random legal programs of [[WPrograms]] against the model (trace, cycles, defined values),
  *     with minimum counts of the shapes that W must exercise;
  *   - random illegal programs of the same generator against the verifier and the model, stopped at
  *     the first violation: the core signals only rules the verifier finds, in the cycle in which
  *     the model stops (one cycle later for L5 on the memory port, docs/VERIFIER.md), and nothing
  *     for programs that break only L8;
  *   - last, in the three builds with cases, one case whose assertion must really fire, so that the
  *     bound assertions of the build are checked too. The generated variant runs only the random
  *     programs, in stop mode: no assertion fires there.
  *
  * Only the W-full build also decodes ISA.md's example words of mvp-2 and runs the mvp-0 and mvp-1
  * programs (program compatibility).
  *
  * The cases of `mvp2_rp4` (4 read ports, #57) and `mvp2_vii` (II 2, store latency 2) are outside
  * the RTL's range and shown as ignored, with the reasons of [[CoreParams.check]].
  */
class RtlMvp2Spec extends AnyFunSuite with VliwChiselSim {
  import RtlMvp2Spec.{Drawn, Plan}

  private val FiredRule = """Assertion failed: (L\d+) """.r.unanchored

  private def load(p: Path) =
    MachineDescription.load(p).fold(es => fail(es.mkString("\n")), identity)
  private def machineFile(name: String) = load(Paths.get("tests", "machines", s"$name.json"))

  private val full = load(MachineDescription.Mvp2Path)
  private val mw1 = machineFile("mvp2_mw1")
  private val vlat = machineFile("mvp2_vlat")

  /** W-full with 8 registers (a 3-bit register index), 4 banks of 64 words (lane 1 of a wide access
    * in bank (b + 1) mod 4, in the next row only at a mod 4 = 3; the words 510 and 511 are outside,
    * L7), and latencies in the other direction from mvp2_vlat: LD 3 and LD.W 2, ADD 2 and VADD 1,
    * MUL 4 and VMUL 1, MAC 1 and VMAC 4 (pairs that commit at once, the schedule's "now" path).
    */
  private val variant = {
    val v = ujson.read(full.canonicalJson)
    v("register_file")("count") = 8
    v("scratchpad")("banks") = 4
    v("scratchpad")("words_per_bank") = 64
    v("scratchpad")("bank_mapping") = "word_address_mod_4"
    v("scratchpad")("row_mapping") = "word_address_div_4"
    Seq("LD" -> 3, "LD.W" -> 2, "ADD" -> 2, "VADD" -> 1, "MUL" -> 4, "VMUL" -> 1)
      .foreach { case (op, l) => v("operations")(op)("latency") = l }
    Seq("MAC" -> 1, "VMAC" -> 4).foreach { case (op, l) => v("operations")(op)("latency") = l }
    MachineDescription.parse(ujson.write(v)).fold(es => fail(es.mkString("\n")), identity)
  }

  private val cases: Vector[TestCase] = TestCase
    .loadAll()
    .fold(es => fail(es.mkString("\n")), identity)
    .filter(_.machine.isaVersion == IsaVersion.Mvp2)
  private def casesOf(m: MachineDescription) = cases.filter(_.machine.hash == m.hash)
  private def supported(c: TestCase) = CoreParams.check(c.machine).isRight
  private def rulesOf(c: TestCase): Set[Rule] = c.expect.verdict match {
    case Verdict.Illegal(rules) => rules
    case Verdict.Legal          => Set.empty
  }

  /** The case whose assertion fires at the end of each build, with its rule. */
  private val firing = Map(
    full.hash -> ("w_vmac_stw_fields" -> Rule.ReadPorts),
    mw1.hash -> ("w_ldw_on_wreg" -> Rule.Encoding),
    vlat.hash -> ("w_vlat_vmul_mul_port" -> Rule.WritebackPort)
  )

  test("the W cases of the RTL's machines cover every rule the RTL checks, in three builds") {
    val (in, out) = cases.partition(supported)
    assert(cases.size == 37 && in.size == 29 && out.size == 8, cases.map(_.name))
    assert(in.map(_.machine.hash).toSet == Set(full.hash, mw1.hash, vlat.hash))
    assert(out.map(_.machine.machineId).toSet.map((_: String).split("-").last) == Set("rp4", "vii"))
    // L8 is outside the RTL, and L9 and L10 need an II or a store latency the RTL refuses.
    val unreachable = Set[Rule](Rule.UndefinedRead, Rule.IssueInterval, Rule.InFlightStoreRead)
    val covered = in.flatMap(rulesOf).toSet
    assert((Rule.all.toSet -- unreachable).subsetOf(covered), covered.map(_.id))
    firing.values.foreach { case (name, rule) =>
      assert(in.exists(c => c.name == name && rulesOf(c) == Set(rule)), name)
    }
  }

  cases.filterNot(supported).foreach { c =>
    val reasons = CoreParams.check(c.machine).left.toOption.get.map(_.takeWhile(_ != ';'))
    ignore(s"${c.name}: outside the RTL's range (${reasons.mkString(", ")})") {}
  }

  // Random programs.

  /** How the programs of a test are drawn: the program, then its inputs. */
  private type Gen = Random => (Vector[Bundle], Inputs)
  private def dense(m: MachineDescription): Gen =
    rng => (WPrograms.program(rng, m), WPrograms.inputs(rng))
  private def spaced(m: MachineDescription): Gen =
    rng => (WPrograms.spaced(rng, m), WPrograms.allInputs(rng))
  private def clash(m: MachineDescription): Gen =
    rng => (WPrograms.loadClash(rng, m), WPrograms.allInputs(rng))

  /** Programs drawn with `seed` on `m`, judged by the verifier and the model. Inputs at words
    * outside the scratchpad (on the 256-word variant) are left out.
    */
  private def judged(m: MachineDescription, seed: Long, gen: Gen): Iterator[Drawn] = {
    val rng = new Random(seed)
    val codec = new BundleCodec(m)
    Iterator.continually {
      val (bundles, drawn) = gen(rng)
      val in = drawn.copy(memory = drawn.memory.filter(_._1 < m.scratchpadWords))
      val words = codec.encodeAll(bundles).fold(e => fail(e), identity)
      val found = Verifier.verify(m, words, Verifier.Declaration.of(in)).map(_.rule).toSet
      val d = Drawn(bundles, words, in, found, ReferenceModel.run(m, words, in))
      assert(found.isEmpty == d.model.isRight, d.listing(m))
      d
    }
  }

  /** The first `legal` legal and `illegal` illegal programs of `gen`. */
  private def draw(
      m: MachineDescription,
      seed: Long,
      legal: Int,
      illegal: Int,
      gen: Gen
  ): (Vector[Drawn], Vector[Drawn]) = {
    val all = judged(m, seed, gen)
    val (ok, bad) = (Vector.newBuilder[Drawn], Vector.newBuilder[Drawn])
    var (nOk, nBad) = (0, 0)
    while (nOk < legal || nBad < illegal) {
      val d = all.next()
      if (d.found.isEmpty) {
        if (nOk < legal) { ok += d; nOk += 1 }
      } else if (nBad < illegal) { bad += d; nBad += 1 }
    }
    (ok.result(), bad.result())
  }

  /** Runs one program on the core and on the model: no violation signal, the same cycles and trace,
    * and the same value at every location the model defines.
    */
  private def matchesModel(
      dut: Core,
      m: MachineDescription,
      words: Vector[Long],
      in: Inputs,
      label: => String
  ): CoreRun = {
    val model = ReferenceModel.run(m, words, in).fold(v => fail(s"$label: $v"), identity)
    val defined = model.memory.zipWithIndex.collect { case (Some(_), a) => a }.toSet
    val rtl = CoreDriver.run(dut, m, words, in, defined)
    assert(rtl.violations.isEmpty, s"$label: violations ${rtl.violations.map(_.id)}")
    assert(rtl.cycles == model.cycles, label)
    val expected = model.trace.cycles.map(c => canonical(c.toJson))
    val actual = rtl.trace.map(canonical)
    expected.zip(actual).zipWithIndex.find { case ((e, a), _) => e != a }.foreach {
      case ((e, a), line) => fail(s"$label: differs at trace line $line\n  model: $e\n  rtl:   $a")
    }
    assert(actual.size == expected.size, s"$label: trace length")
    model.registers.zipWithIndex.collect { case (Some(v), r) =>
      assert(rtl.registers(r) == BigInt(v), s"$label: r$r")
    }
    model.memory.zipWithIndex.collect { case (Some(v), a) =>
      assert(rtl.memory(a) == BigInt(v), s"$label: [$a]")
    }
    rtl
  }

  /** The W cases of `m` but the firing one: the legal ones against their expectations, their golden
    * trace and the model; the illegal ones stopped at their first violation; the L8-only ones to
    * done. Returns the number of each.
    */
  private def runCases(dut: Core, m: MachineDescription): (Int, Int, Int) = {
    val mine = casesOf(m).filterNot(c => firing.get(m.hash).exists(_._1 == c.name))
    mine.foreach { c =>
      rulesOf(c) match {
        case none if none.isEmpty =>
          val r = matchesModel(dut, m, c.words, c.inputs, c.name)
          c.expect.cycles.foreach(n => assert(r.cycles == n, c.name))
          c.expect.registers.foreach { case (reg, v) =>
            assert(r.registers(reg) == BigInt(v), s"${c.name}: r$reg")
          }
          c.expect.memory.foreach { case (a, v) =>
            assert(r.memory.get(a).contains(BigInt(v)), s"${c.name}: [$a]")
          }
          c.expect.trace.foreach { path =>
            val golden = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).linesIterator
              .filter(_.nonEmpty)
              .map(l => canonical(ujson.read(l)))
              .toVector
            assert(r.trace.map(canonical) == golden, s"${c.name}: golden trace")
          }
        case rules =>
          val rtlRules = rules - Rule.UndefinedRead
          val r = CoreDriver.run(dut, m, c.words, c.inputs, Set.empty, stopAtViolation = true)
          if (rtlRules.isEmpty)
            assert(r.stoppedAt.isEmpty && r.violations.isEmpty, s"${c.name}: ${r.violations}")
          else
            assert(
              r.stoppedAt.nonEmpty && r.violations.nonEmpty && r.violations.subsetOf(rtlRules),
              s"${c.name}: signalled ${r.violations.map(_.id)}, expected some of ${rtlRules.map(_.id)}"
            )
      }
    }
    val legal = mine.count(rulesOf(_).isEmpty)
    val l8 = mine.count(c => rulesOf(c) == Set(Rule.UndefinedRead))
    (legal, mine.size - legal - l8, l8)
  }

  /** The random illegal programs, each stopped at its first violation: the core signals some of the
    * verifier's rules, no earlier than the model's first violation and at most one cycle later
    * (only for L5 on the memory port), and nothing for programs that break only L8. Returns the
    * count of each rule the core signalled, and of programs that stopped in the model's cycle with
    * the model's rule.
    */
  private def runIllegal(
      dut: Core,
      m: MachineDescription,
      programs: Vector[Drawn]
  ): (Map[Rule, Int], Int, Int) = {
    val signalled = collection.mutable.Map.empty[Rule, Int].withDefaultValue(0)
    var (same, late) = (0, 0)
    val outside = Set[Rule](Rule.UndefinedRead, Rule.IssueInterval, Rule.InFlightStoreRead)
    programs.foreach { d =>
      def listing = d.listing(m)
      val r = CoreDriver.run(dut, m, d.words, d.inputs, Set.empty, stopAtViolation = true)
      if ((d.found -- outside).isEmpty)
        assert(r.stoppedAt.isEmpty && r.violations.isEmpty, s"L8 only\n$listing")
      else {
        // A HALT before the last bundle breaks L2. The core checks L6 at every HALT it issues,
        // the verifier only at the last bundle.
        val earlyHalt = r.violations.contains(Rule.ProgramShape) &&
          r.stoppedAt.flatMap(d.bundles.lift).exists(_.compute == ComputeOp.Halt)
        val allowed = d.found ++ Option.when(earlyHalt)(Rule.HaltCompletion)
        assert(r.stoppedAt.nonEmpty && r.violations.subsetOf(allowed), s"${r.violations}\n$listing")
        r.violations.foreach(signalled(_) += 1)
        val first = d.model.left.toOption.filter(_.rule != Rule.UndefinedRead)
        first.flatMap(v => v.cycle.map(v.rule -> _)).foreach { case (rule, t) =>
          val at = r.stoppedAt.get
          if (at == t && r.violations.contains(rule)) same += 1
          else {
            // L5 on the memory port shows when the second load's data enters the schedule, one
            // cycle after its issue; in its issue cycle the core may signal another rule.
            assert(
              rule == Rule.WritebackPort && WPrograms.Shapes.loadPortClashAt(m, d.bundles, t) &&
                (at == t || (at == t + 1 && r.violations.contains(rule))),
              s"stopped at $at with ${r.violations.map(_.id)}; the model at $t with ${rule.id}\n$listing"
            )
            late += 1
          }
        }
      }
    }
    (signalled.toMap, same, late)
  }

  /** Builds the core for `m`, runs `body` on it, then the firing case of `m`, whose assertion must
    * stop the simulation with its rule.
    */
  private def build(m: MachineDescription)(body: Core => Unit): Unit = {
    val (name, rule) = firing(m.hash)
    val c = casesOf(m).find(_.name == name).get
    // ChiselSim turns the simulation's stop into AssertionFailed after the body; any other failure
    // of the body propagates as it is.
    val e = Try(simulate(new Core(CoreParams(m))) { dut =>
      body(dut)
      CoreDriver.run(dut, m, c.words, c.inputs, Set.empty)
    }) match {
      case Failure(e: AssertionFailed) => e
      case Failure(other)              => throw other
      case Success(_)                  => fail(s"$name: no assertion fired")
    }
    e.getMessage match {
      case FiredRule(id) => assert(id == rule.id, s"$name fired $id: ${e.getMessage}")
      case _             => fail(s"$name: no rule id in: ${e.getMessage}")
    }
  }

  /** Legal shapes that W must exercise, counted over `legal`. */
  private def legalShapes(m: MachineDescription, legal: Vector[Drawn]): Map[String, Int] = {
    import WPrograms.Shapes._
    def n(p: Drawn => Boolean) = legal.count(p)
    Map(
      "vector" -> n(_.bundles.exists(x => WPrograms.isVector(x.compute))),
      "pair commit" -> n(d => pairCommit(m, d.bundles)),
      "vmac rd = rs3" -> n(d => vmacAccumulates(d.bundles)),
      "even broadcast" -> n(d => evenBroadcast(d.bundles)),
      "broadcast alone" -> n(d => broadcastAlone(d.bundles, d.inputs)),
      "partial update" -> n(d => partialUpdate(m, d.bundles))
    ) ++ (if (m.hasWideMemory)
            Map(
              "pairs on both ports" -> n(d => pairsOnBothPorts(m, d.bundles)),
              "vmac + ld.w" -> n(d => vmacWithLdW(d.bundles)),
              "odd ld.w" -> n(d => oddWide(d.bundles)),
              "odd st.w" -> n(d => oddStore(d.bundles)),
              "odd st.w read back" -> n(d => oddStoreReadBack(d.bundles))
            )
          else Map.empty)
  }

  private def show(counts: Map[_ <: Any, Int]): String =
    counts.toSeq
      .map { case (k, v) => s"${k match { case r: Rule => r.id; case o => o }} $v" }
      .sorted
      .mkString(", ")

  /** The random programs of a build: legal ones (dense, then spaced) against the model, illegal
    * ones (dense, spaced, and LD/LD.W clashes) against the verifier and the model. Returns the
    * dense legal programs.
    */
  private def randomPrograms(dut: Core, m: MachineDescription, plan: Plan): Vector[Drawn] = {
    val (legal, illegal) = draw(m, plan.seed, plan.dense, plan.illegal, dense(m))
    val (spacedLegal, spacedIllegal) =
      judged(m, plan.seed + 1000, spaced(m)).take(plan.spaced).toVector.partition(_.found.isEmpty)
    val clashes = draw(m, plan.seed + 2000, 0, plan.clashes, clash(m))._2
    (legal ++ spacedLegal).zipWithIndex.foreach { case (d, i) =>
      matchesModel(dut, m, d.words, d.inputs, s"#$i\n${d.listing(m)}")
    }
    val shapes = legalShapes(m, legal ++ spacedLegal)
    info(
      s"${legal.size} dense and ${spacedLegal.size} spaced legal programs: ${show(shapes)}; " +
        s"dense alone: ${show(legalShapes(m, legal))}"
    )
    plan.shapes.foreach { case (shape, k) =>
      assert(shapes(shape) >= k, s"$shape: ${shapes(shape)}")
    }
    val bad = illegal ++ spacedIllegal ++ clashes
    val (signalled, same, late) = runIllegal(dut, m, bad)
    val secondLane = bad.count(d =>
      WPrograms.Shapes.secondLaneWritten(m, d.bundles) && d.found.contains(Rule.InFlightRead)
    )
    info(
      s"${illegal.size} dense, ${spacedIllegal.size} spaced and ${clashes.size} LD/LD.W illegal programs, " +
        s"rules signalled first: ${show(signalled)}; in the model's cycle $same, one cycle later " +
        s"(L5, memory port) $late; L3 on the second register of a pair $secondLane"
    )
    plan.rules.foreach { case (rule, k) => assert(signalled.getOrElse(rule, 0) >= k, rule.id) }
    assert(secondLane >= plan.secondLane && late >= plan.late)
    legal
  }

  // The builds.

  /** ISA.md's example words of mvp-2 (hand-computed), with what the decoder must make of them. */
  private val examples: Seq[(Long, String, String, Set[Rule])] = Seq(
    (0x0000000045123000L, "mac r5, r1, r2, r3", "nop", Set()),
    (0x0000000084268800L, "vmac r4:r5, r2, r6:r7, r8:r9", "nop", Set()),
    (0x0000000084268000L, "vmac r4:r5, r2:r3, r6:r7, r8:r9", "nop", Set()),
    (0x0000000074160800L, "vmul r4:r5, r1, r6:r7", "nop", Set()),
    (0x0000000074260000L, "vmul r4:r5, r2:r3, r6:r7", "nop", Set()),
    (0x0000000054260000L, "vadd r4:r5, r2:r3, r6:r7", "nop", Set()),
    (0x0000000064260000L, "vsub r4:r5, r2:r3, r6:r7", "nop", Set()),
    (0x3a08800000000000L, "nop", "ld.w r10:r11, [17]", Set()),
    (0x3aff000000000000L, "nop", "ld.w r10:r11, [510]", Set()),
    (0x3aff800000000000L, "nop", "ld.w r10:r11, [511]", Set(Rule.AddressRange)),
    (0x4c08800000000000L, "nop", "st.w [17], r12:r13", Set()),
    (0x2100000084268800L, "vmac r4:r5, r2, r6:r7, r8:r9", "st [0], r1", Set(Rule.ReadPorts)),
    (0x3a08800084268800L, "vmac r4:r5, r2, r6:r7, r8:r9", "ld.w r10:r11, [17]", Set())
  )

  /** ISA.md's words that break L1 on W-full. */
  private val l1Words = Seq(
    0x0000000085268800L, // VMAC rd odd
    0x0000000074360000L, // VMUL rs1 pair odd (B = 0)
    0x0000000054260800L, // B on VADD
    0x0000000045123800L, // B on MAC (MAC's bits 11:0 are reserved)
    0x0000000074168800L, // VMUL bits 15:12
    0x0000000084268801L, // bit 0
    0x3b08800000000000L, // LD.W pair odd
    0x3a08c00000000000L // LD.W reserved bit 14
  )
  private val Halt = 0xf0000000L

  private def decodes(dut: Core): Unit = {
    examples.foreach { case (word, compute, memory, rules) =>
      val r = CoreDriver.run(
        dut,
        full,
        Vector(word, 0L, 0L, Halt),
        Inputs(Map.empty, Map.empty),
        Set.empty,
        stopAtViolation = true
      )
      val issue = r.trace.head("issue")
      assert(
        issue("compute").str == compute && issue("memory").str == memory,
        f"0x$word%016x: $issue"
      )
      assert(r.violations == rules && r.stoppedAt.isEmpty == rules.isEmpty, f"0x$word%016x")
    }
    l1Words.foreach { word =>
      val r = CoreDriver.run(
        dut,
        full,
        Vector(word, Halt),
        Inputs(Map.empty, Map.empty),
        Set.empty,
        stopAtViolation = true
      )
      assert(r.stoppedAt.contains(0) && r.violations == Set(Rule.Encoding), f"0x$word%016x")
      assert(r.trace.head("issue")("compute").str == "?", f"0x$word%016x")
    }
  }

  /** The legal mvp-0 and mvp-1 programs of the cases and benchmarks that Mvp2CompatibilitySpec maps
    * to machine-mvp2: on the mvp-2 core they run as on the model (docs/ISA.md, program
    * compatibility).
    */
  private val compatible: Vector[TestCase] = {
    val mvp0 = load(MachineDescription.DefaultPath)
    val mvp1 = load(MachineDescription.Mvp1Path)
    (TestCase.loadAll().fold(es => fail(es.mkString("\n")), identity) ++
      TestCase
        .loadAll(Paths.get("benchmarks", "cases"))
        .fold(es => fail(es.mkString("\n")), identity))
      .filter(c => Set(mvp0.hash, mvp1.hash)(c.machine.hash) && c.expect.verdict == Verdict.Legal)
  }

  // The minimum counts are about half of those measured when they were set (#110 stage 3); the
  // seeds are fixed, so the counts change only with the generators or the machines.

  test(
    "W-full (machine-mvp2) on the RTL: cases, ISA.md's words, random W programs, mvp-1 programs; the L11 assertion fires"
  ) {
    build(full) { dut =>
      assert(runCases(dut, full) == ((8, 13, 2)))
      decodes(dut)
      val programs = randomPrograms(
        dut,
        full,
        Plan(
          seed = 110L,
          dense = 300,
          illegal = 300,
          spaced = 100,
          clashes = 0,
          shapes = Map(
            "vector" -> 54,
            "pair commit" -> 62,
            "vmac rd = rs3" -> 10,
            "even broadcast" -> 14,
            "broadcast alone" -> 4,
            "partial update" -> 20,
            "pairs on both ports" -> 10,
            "vmac + ld.w" -> 5,
            "odd ld.w" -> 11,
            "odd st.w" -> 9,
            "odd st.w read back" -> 1
          ),
          rules = Map(
            Rule.ProgramShape -> 25,
            Rule.InFlightRead -> 56,
            Rule.OverlappingWrites -> 72,
            Rule.WritebackPort -> 28,
            Rule.HaltCompletion -> 8,
            Rule.AddressRange -> 5,
            Rule.ReadPorts -> 34
          ),
          secondLane = 18,
          late = 0
        )
      )
      val withW = programs.filter(d => d.bundles.exists(x => WPrograms.isVector(x.compute)))
      RtlPrograms.sameTimingOn(
        dut,
        full,
        withW.take(60).map(d => (d.words, d.inputs)),
        new Random(1101L)
      )
      // Program compatibility: random mvp-1 programs, and the legal mvp-0 and mvp-1 cases and
      // benchmark programs, run on the mvp-2 core as on the model.
      RtlPrograms.compareOn(dut, full, RtlPrograms.legalPrograms(full, 7710L, 100, mac = true))
      compatible.foreach(c => matchesModel(dut, full, c.words, c.inputs, c.name))
      info(s"${compatible.size} mvp-0 and mvp-1 cases and benchmark programs")
    }
  }

  test("W-reg (mvp2_mw1) on the RTL: cases and random W programs; LD.W's opcode fires L1") {
    build(mw1) { dut =>
      assert(runCases(dut, mw1) == ((1, 0, 0)))
      randomPrograms(
        dut,
        mw1,
        Plan(
          seed = 111L,
          dense = 200,
          illegal = 100,
          spaced = 50,
          clashes = 0,
          shapes = Map(
            "vector" -> 41,
            "pair commit" -> 41,
            "vmac rd = rs3" -> 6,
            "even broadcast" -> 6,
            "broadcast alone" -> 3,
            "partial update" -> 11
          ),
          rules = Map(
            Rule.ProgramShape -> 11,
            Rule.InFlightRead -> 17,
            Rule.OverlappingWrites -> 24,
            Rule.WritebackPort -> 9,
            Rule.HaltCompletion -> 5,
            Rule.ReadPorts -> 13
          ),
          secondLane = 5,
          late = 0
        )
      )
    }
  }

  test(
    "latency variant (mvp2_vlat) on the RTL: cases and random W programs; the VMUL and MUL port clash fires L5"
  ) {
    build(vlat) { dut =>
      assert(runCases(dut, vlat) == ((1, 1, 0)))
      randomPrograms(
        dut,
        vlat,
        Plan(
          seed = 113L,
          dense = 200,
          illegal = 100,
          spaced = 50,
          clashes = 40,
          shapes = Map(
            "vector" -> 29,
            "pair commit" -> 32,
            "vmac rd = rs3" -> 5,
            "even broadcast" -> 5,
            "broadcast alone" -> 1,
            "partial update" -> 8,
            "pairs on both ports" -> 1,
            "vmac + ld.w" -> 1,
            "odd ld.w" -> 2,
            "odd st.w" -> 5,
            "odd st.w read back" -> 1
          ),
          rules = Map(
            Rule.ProgramShape -> 7,
            Rule.InFlightRead -> 29,
            Rule.OverlappingWrites -> 40,
            Rule.WritebackPort -> 6,
            Rule.HaltCompletion -> 3,
            Rule.AddressRange -> 1,
            Rule.ReadPorts -> 13
          ),
          secondLane = 4,
          late = 3
        )
      )
    }
  }

  test(
    "W-full variant (8 registers, 4 banks x 64 words, mixed latencies) on the RTL: random W programs"
  ) {
    assert(CoreParams.check(variant).isRight)
    simulate(new Core(CoreParams(variant))) { dut =>
      randomPrograms(
        dut,
        variant,
        Plan(
          seed = 114L,
          dense = 150,
          illegal = 100,
          spaced = 50,
          clashes = 40,
          shapes = Map(
            "vector" -> 28,
            "pair commit" -> 29,
            "vmac rd = rs3" -> 2,
            "even broadcast" -> 3,
            "broadcast alone" -> 2,
            "partial update" -> 5,
            "pairs on both ports" -> 1,
            "vmac + ld.w" -> 1,
            "odd ld.w" -> 1,
            "odd st.w" -> 7,
            "odd st.w read back" -> 1
          ),
          rules = Map(
            Rule.ProgramShape -> 10,
            Rule.InFlightRead -> 18,
            Rule.OverlappingWrites -> 34,
            Rule.WritebackPort -> 5,
            Rule.HaltCompletion -> 4,
            Rule.AddressRange -> 3,
            Rule.ReadPorts -> 12
          ),
          secondLane = 10,
          late = 3
        )
      )
    }
  }
}

object RtlMvp2Spec {

  /** How many programs of each generator a build runs (dense legal, dense illegal, spaced, LD/LD.W
    * clashes), and the minimum counts of their shapes, of the rules the core signals first, of L3
    * on the second register of a pair, and of L5 on the memory port signalled one cycle late.
    */
  final case class Plan(
      seed: Long,
      dense: Int,
      illegal: Int,
      spaced: Int,
      clashes: Int,
      shapes: Map[String, Int],
      rules: Map[Rule, Int],
      secondLane: Int,
      late: Int
  )

  /** A program of [[WPrograms]] with what the verifier and the model make of it. */
  final case class Drawn(
      bundles: Vector[Bundle],
      words: Vector[Long],
      inputs: Inputs,
      found: Set[Rule],
      model: Either[Violation, Execution]
  ) {
    def listing(m: MachineDescription): String =
      s"${m.machineId}\n" + Disassembler.program(bundles) +
        s"inputs: ${inputs.registers.keys.toSeq.sorted.map("r" + _)} ${inputs.memory.keys.toSeq.sorted}\n" +
        s"verifier: ${found.map(_.id).toSeq.sorted}; model: ${model.left.toOption}"
  }
}
