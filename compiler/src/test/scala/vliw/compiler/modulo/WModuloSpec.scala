package vliw.compiler.modulo

import java.nio.file.Paths

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.{IsaExecutor, KernelCompiler}
import vliw.compiler.dsl.Interpreter
import vliw.compiler.loop._
import vliw.isa.MachineDescription

import Outcome.{Fits, TooManyLive}
import WModuloSpec.{Row, Scalar}

/** Modulo Scheduling on ISA mvp-2 with loop vectorization (#110 stage 5a; docs/COMPILER.md "ルー
  * プのベクトル化と Modulo Scheduling（W-full）"): the records of the six loops on the mvp-2 machines, cell
  * by cell, the fallbacks and the comparison API, and the random loops' counts.
  */
class WModuloSpec extends AnyFunSuite {
  private def load(path: String*) = MachineDescription
    .load(Paths.get(path.head, path.tail: _*))
    .fold(es => fail(es.mkString("\n")), identity)
  private val full = load("config", "machine-mvp2.json")
  private val rp4 = load("tests", "machines", "mvp2_rp4.json")
  private val vlat = load("tests", "machines", "mvp2_vlat.json")
  private val vii = load("tests", "machines", "mvp2_vii.json")
  private val mw1 = load("tests", "machines", "mvp2_mw1.json")
  private val mvp1 = load("config", "machine-mvp1.json")
  private val mvp1rp4 = load("tests", "machines", "mvp1_rp4.json")
  private def withRegisters(m: MachineDescription, n: Int) = {
    val v = ujson.read(m.canonicalJson)
    v("register_file")("count") = n
    MachineDescription.parse(ujson.write(v)).fold(es => fail(es.mkString), identity)
  }

  /** `RtlMvp2Spec`'s W-full variant: 8 registers, 4 banks, latencies the other way from vlat. */
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

  private def fir(n: Int) = LoopBenchmarks.fir(Seq(1L, 2L, 3L, 4L), n)
  private def loop(label: String) =
    LoopBenchmarks.all.find(k => s"${k.name} ${k.index}" == label).getOrElse(fail(label))
  private def compile(k: LoopKernel, m: MachineDescription) =
    ModuloCompiler.compile(k, m).fold(e => fail(s"${k.name} (${k.index}): $e"), identity)

  /** The outputs of `c`'s program (`IsaExecutor`) on three inputs are the DSL's. */
  private def correct(c: ModuloCompiled, seeds: Seq[Long] = 1L to 3L): Unit = seeds.foreach { s =>
    val in = KernelCompiler.sampleInputs(c.kernel.unroll, s)
    val image = c.layout.memoryImage(in).fold(e => fail(e), identity)
    assert(
      c.layout.outputs(IsaExecutor.run(c.machine, c.bundles, image)) ==
        Interpreter.run(c.kernel.unroll, in).fold(e => fail(e), identity),
      s"${c.kernel.name} (${c.kernel.index}) on ${c.machine.machineId}"
    )
  }

  private val memory = Seq("memory slot")
  private val compute = Seq("compute slot", "compute write-back port", "third read port")
  private val notImplemented = LoopFallback.NotImplemented("body packing: #110 stage 5b")
  private val doubled = Some(Vector.empty[NotDoubled])

  /** COMPILER.md "結果（W）": per machine and loop, the decision (the reasons not to double; None for
    * W-reg), the fallback, MII and its resources, II, the attempts, cycles, MinAvg at the MII
    * (ASAP) and at the II, and MaxLive.
    */
  private val rows: Seq[(MachineDescription, String, Row)] = {
    import NotDoubled._
    def scalar(reasons: Vector[NotDoubled], fallback: LoopFallback) =
      Scalar(Some(reasons), Some(fallback))
    val matmulI = Vector(OddTripCount)
    val matmulM = Vector(AssociationOrder)
    val reduction = Vector(OddTripCount, AssociationOrder)
    val nothing = LoopFallback.NothingToPack
    Seq(
      (full, "saxpy8 i", Row(doubled, None, 3, memory, 3, Seq(Fits), 17, 5, 6, 7)),
      (
        full,
        "fir4x8 i",
        Row(doubled, None, 4, compute, 5, Seq(TooManyLive(18, 16), Fits), 29, 13, 12, 14)
      ),
      (full, "matmul3 i", scalar(matmulI, notImplemented).row(9, compute, 9, 38, 13, 14, 14)),
      (
        full,
        "matmul3 m",
        scalar(matmulM, notImplemented).row(9, compute :+ "MAC unit", 9, 40, 11, 14, 14)
      ),
      (full, "product8 i", scalar(reduction, nothing).row(2, Nil, 2, 19, 2, 2, 2)),
      (full, "dot8 i", scalar(reduction, nothing).row(2, Nil, 2, 21, 3, 4, 4)),
      (rp4, "saxpy8 i", Row(doubled, None, 3, memory, 3, Seq(Fits), 17, 5, 6, 7)),
      (rp4, "fir4x8 i", Row(doubled, None, 4, compute.init, 4, Seq(Fits), 28, 13, 15, 16)),
      (rp4, "matmul3 i", scalar(matmulI, notImplemented).row(9, compute.init, 9, 45, 13, 16, 16)),
      (
        rp4,
        "matmul3 m",
        scalar(matmulM, notImplemented).row(9, compute.init :+ "MAC unit", 9, 34, 11, 14, 14)
      ),
      (rp4, "product8 i", scalar(reduction, nothing).row(2, Nil, 2, 19, 2, 2, 2)),
      (rp4, "dot8 i", scalar(reduction, nothing).row(2, Nil, 2, 21, 3, 4, 4)),
      (vlat, "saxpy8 i", Row(doubled, None, 3, memory, 3, Seq(Fits), 20, 7, 9, 9)),
      (
        vlat,
        "fir4x8 i",
        Row(doubled, None, 4, compute, 5, Seq(TooManyLive(20, 16), Fits), 34, 18, 15, 16)
      ),
      (vlat, "matmul3 i", scalar(matmulI, notImplemented).row(9, compute, 9, 38, 13, 14, 14)),
      (
        vlat,
        "matmul3 m",
        scalar(matmulM, notImplemented).row(9, compute :+ "MAC unit", 9, 40, 11, 14, 14)
      ),
      (vlat, "product8 i", scalar(reduction, nothing).row(2, Nil, 2, 19, 2, 2, 2)),
      (vlat, "dot8 i", scalar(reduction, nothing).row(2, Nil, 2, 21, 3, 4, 4)),
      (vii, "saxpy8 i", Row(doubled, None, 4, Seq("LD.W unit"), 4, Seq(Fits), 22, 4, 6, 7)),
      (vii, "fir4x8 i", Row(doubled, None, 6, Seq("VMAC unit"), 6, Seq(Fits), 35, 12, 12, 14)),
      (vii, "matmul3 i", scalar(matmulI, notImplemented).row(9, compute, 9, 39, 13, 14, 14)),
      (
        vii,
        "matmul3 m",
        scalar(matmulM, notImplemented).row(9, compute :+ "MAC unit", 9, 41, 11, 14, 14)
      ),
      (vii, "product8 i", scalar(reduction, nothing).row(2, Nil, 2, 20, 2, 2, 2)),
      (vii, "dot8 i", scalar(reduction, nothing).row(2, Nil, 2, 22, 3, 4, 4))
    )
  }

  test("the records of the six loops on W-full, rp4, vlat and vii, cell by cell") {
    for ((m, label, want) <- rows) {
      val c = compile(loop(label), m)
      val s = c.result
      val (decision, fallback) = c.simd match {
        case LoopSimd.Vectorized(d)  => (Some(d.notDoubled), None)
        case LoopSimd.Fallback(r, d) => (d.map(_.notDoubled), Some(r))
        case LoopSimd.Scalar         => fail(s"$label on ${m.machineId}: no W outcome")
      }
      // The resources that set the MII; product8 and dot8 are set by RecMII and keep MII 2.
      val bound = if (s.mii.recMII >= s.mii.resMII) Nil else s.mii.bounding
      val got = Row(
        decision,
        fallback,
        s.mii.mii,
        bound,
        s.ii,
        s.attempts.map(_.outcome),
        c.cycles,
        c.minAvgAtMii,
        c.minAvgAtIi,
        c.maxLive
      )
      assert(got == want, s"$label on ${m.machineId}")
      correct(c)
    }
  }

  test("the loops that run as their scalar loop have the mvp-1 code (vii: its ST latency 2)") {
    val mvp1Of = Map(full -> mvp1, rp4 -> mvp1rp4, vlat -> mvp1, mw1 -> mvp1)
    for ((m, label, _) <- rows; m1 <- mvp1Of.get(m)) {
      val c = compile(loop(label), m)
      if (!c.simd.isInstanceOf[LoopSimd.Vectorized])
        assert(c.bundles == compile(loop(label), m1).bundles, s"$label on ${m.machineId}")
    }
    // vii stores with latency 2 (mvp-1 has 1): the scalar loops end one cycle later.
    for (label <- Seq("matmul3 i", "matmul3 m", "product8 i", "dot8 i"))
      assert(compile(loop(label), vii).cycles == compile(loop(label), mvp1).cycles + 1, label)
  }

  test("fir4x8 on W-full: the sweep of every II; registers raise the II from 4 to 5") {
    val c = compile(fir(8), full)
    val sweep = ModuloScheduler.sweep(c.graph)
    assert(
      sweep == Vector(
        Sweep(4, TooManyLive(18, 16), Some(31), Some(18), Some(18)),
        Sweep(5, Fits, Some(29), Some(14), Some(12)),
        Sweep(6, Fits, Some(33), Some(14), Some(12)),
        Sweep(7, Fits, Some(37), Some(14), Some(12)),
        Sweep(8, Fits, Some(41), Some(14), Some(11)),
        Sweep(9, Fits, Some(41), Some(12), Some(10))
      )
    )
    assert(c.result.iiMax == 9 && c.result.attempts.map(_.ii) == Seq(4, 5))
  }

  test("describeW gives the decision, the shape and the records") {
    def line(label: String, m: MachineDescription = full) =
      ModuloCompiler.describeW(compile(loop(label), m))
    assert(
      line(
        "saxpy8 i"
      ) == "saxpy8 (i): W: doubled (MII 3 vs 2 × 3), 4 iterations of II 3 (1.5 per " +
        "original iteration), per iteration 2 LD.W, 1 VMAC, 1 ST.W, 1 broadcast, 0 gathers; " +
        "MinAvg 5 at MII, 6 at II; MaxLive 7"
    )
    assert(
      line("matmul3 m") == "matmul3 (m): W: scalar fallback (NotImplemented: body packing: " +
        "#110 stage 5b; not doubled: AssociationOrder)"
    )
    assert(
      line("dot8 i") ==
        "dot8 (i): W: scalar fallback (NothingToPack; not doubled: OddTripCount, AssociationOrder)"
    )
    assert(
      line("fir4x8 i", mw1) ==
        "fir4x8 (i): W: scalar fallback (NotImplemented: W-reg Modulo Scheduling (#78 Q8 (c)))"
    )
    assert(line("saxpy8 i", mvp1) == "")
  }

  test("odd trips run the scalar loop with OddTripCount and NothingToPack; even ones double") {
    for (n <- 1 to 9; k <- Seq(LoopBenchmarks.saxpy(n), fir(n))) {
      val c = compile(k, full)
      if (n % 2 == 1) {
        val d = LoopDecision(
          Vector(NotDoubled.OddTripCount),
          c.result.mii.withoutReadPort.mii,
          None,
          false
        )
        assert(c.simd == LoopSimd.Fallback(LoopFallback.NothingToPack, Some(d)), k.name)
        assert(c.bundles == compile(k, mvp1).bundles, k.name)
        assert(ModuloCompiler.candidate(k, full).exists {
          case LoopCandidate.NothingToPack(`d`, s) => s.bundles == c.bundles
          case _                                   => false
        })
      } else assert(c.simd.isInstanceOf[LoopSimd.Vectorized], k.name)
      correct(c)
    }
    // ADR-0023's required test: saxpy(7), 26 cycles; fir4x7 42.
    assert(
      compile(LoopBenchmarks.saxpy(7), full).cycles == 26 && compile(fir(7), full).cycles == 42
    )
  }

  test("long FIR loops: LD.W chains through a full steady state") {
    for ((n, cycles) <- Seq(16 -> 49, 32 -> 89)) {
      val c = compile(fir(n), full)
      assert(c.simd.isInstanceOf[LoopSimd.Vectorized] && c.ii == 5 && c.cycles == cycles, n)
      correct(c)
    }
  }

  test("saxpy(n) on W-full: II 3 for two elements, 1.5n + 5 cycles") {
    for (n <- Seq(8, 16, 32, 64)) {
      val c = compile(LoopBenchmarks.saxpy(n), full)
      assert(c.ii == 3 && 2 * c.cycles == 3 * n + 10, n)
      correct(c, Seq(1L))
    }
  }

  test("body packing is stage 5b: matmul3 runs its scalar loop and is not measured") {
    for (label <- Seq("matmul3 i", "matmul3 m"); m <- Seq(full, rp4, vlat, vii)) {
      val c = compile(loop(label), m)
      assert(
        c.simd match {
          case LoopSimd.Fallback(`notImplemented`, Some(d)) => d.candidate && !d.doubled
          case _                                            => false
        },
        s"$label on ${m.machineId}"
      )
      assert(
        ModuloCompiler.candidate(loop(label), m) ==
          Right(LoopCandidate.NotMeasured("body packing: #110 stage 5b"))
      )
    }
  }

  test(
    "W-reg runs every loop as its scalar loop, the mvp-1 code, and is not measured (#78 Q8 (c))"
  ) {
    val reason = "W-reg Modulo Scheduling (#78 Q8 (c))"
    for (k <- LoopBenchmarks.all) {
      val c = compile(k, mw1)
      assert(c.simd == LoopSimd.Fallback(LoopFallback.NotImplemented(reason), None), k.name)
      assert(c.bundles == compile(k, mvp1).bundles, k.name)
      assert(ModuloCompiler.candidate(k, mw1) == Right(LoopCandidate.NotMeasured(reason)))
      correct(c, Seq(1L))
    }
  }

  test("fewer registers: a doubled loop that fits no II falls back to its scalar loop") {
    val m12 = withRegisters(full, 12)
    val m10 = withRegisters(full, 10)
    val m8 = withRegisters(full, 8)
    for (m <- Seq(m12, m10, m8)) {
      val s = compile(LoopBenchmarks.saxpy(8), m)
      assert((s.ii, s.cycles) == ((3, 17)) && s.simd.isInstanceOf[LoopSimd.Vectorized])
      // matmul3's scalar loop holds 14 values at every II: no program, as on mvp-1.
      for (label <- Seq("matmul3 i", "matmul3 m"))
        assert(
          ModuloCompiler.compile(loop(label), m).left.exists(_.contains("values live at once"))
        )
    }
    val f12 = compile(fir(8), m12)
    assert((f12.ii, f12.cycles) == ((9, 41)) && f12.simd.isInstanceOf[LoopSimd.Vectorized])
    assert(
      f12.result.attempts.map(_.outcome) ==
        (TooManyLive(18, 12) +: Seq.fill(4)(TooManyLive(14, 12))) :+ Fits
    )
    // 10 registers: no W II (II 4 to 9); the scalar loop fits at II 5, the mvp-1 code.
    val f10 = compile(fir(8), m10)
    val noIi = Vector(18, 14, 14, 14, 14, 12).zip(4 to 9).map { case (l, ii) =>
      Attempt(ii, TooManyLive(l, 10))
    }
    assert(f10.simd match {
      case LoopSimd.Fallback(LoopFallback.NoIiFits(`noIi`), Some(d)) => d.doubled
      case _                                                         => false
    })
    assert(
      (f10.ii, f10.cycles) == ((5, 49)) && f10.bundles == compile(
        fir(8),
        withRegisters(mvp1, 10)
      ).bundles
    )
    assert(ModuloCompiler.candidate(fir(8), m10).exists {
      case LoopCandidate.Infeasible(d, `noIi`) => d.doubled
      case _                                   => false
    })
    correct(f10)
    val f8 = compile(fir(8), m8)
    assert((f8.ii, f8.cycles) == ((9, 77)) && f8.simd.isInstanceOf[LoopSimd.Fallback])
    correct(f8)
    // RtlMvp2Spec's variant (8 registers): saxpy8 doubles at II 5 (II 3 and 4: 9 live), fir4x8
    // falls back (8 / 69), product8 4 / 34, dot8 2 / 23; matmul3 has no program.
    val sv = compile(LoopBenchmarks.saxpy(8), variant)
    assert((sv.ii, sv.cycles) == ((5, 26)) && sv.simd.isInstanceOf[LoopSimd.Vectorized])
    assert(sv.result.attempts.map(_.outcome) == Seq(TooManyLive(9, 8), TooManyLive(9, 8), Fits))
    val fv = compile(fir(8), variant)
    assert((fv.ii, fv.cycles) == ((8, 69)) && fv.simd.isInstanceOf[LoopSimd.Fallback])
    assert(
      (compile(loop("product8 i"), variant).ii, compile(loop("product8 i"), variant).cycles) == ((
        4,
        34
      ))
    )
    assert(
      (compile(loop("dot8 i"), variant).ii, compile(loop("dot8 i"), variant).cycles) == ((2, 23))
    )
    Seq("matmul3 i", "matmul3 m").foreach(l =>
      assert(ModuloCompiler.compile(loop(l), variant).isLeft)
    )
    Seq(sv, fv).foreach(correct(_))
  }

  /** The gathers of a doubled graph that equal another gather of the loop d >= 1 iterations later
    * (#110 第5段の Q3): by ADR-0014's rule on whole gathers (same stride and lane offset, base modulo
    * the stride, chains split where no word is shared), all but the first reader of each chain.
    */
  private def sharable(v: LoopGraph): Int = {
    def addr(r: Ref) = v.nodes(r.node).asInstanceOf[LoopNode.Load].addr
    v.ids(Place.Each)
      .flatMap(n => LoopNode.fields(v.nodes(n)))
      .collect { case VRef.Gather(lo, hi) => (addr(lo), addr(hi)) }
      .distinct
      .groupBy { case (lo, hi) =>
        (lo.stride, hi.base - lo.base, Math.floorMod(lo.base, Math.abs(lo.stride)))
      }
      .values
      .map { group =>
        val bases = group.map(_._1.base).sorted
        val chains = bases.tail.foldLeft(Vector(Vector(bases.head))) { (cs, b) =>
          if (b - cs.last.last >= Math.abs(group.head._1.stride) * v.trips) cs :+ Vector(b)
          else cs.init :+ (cs.last :+ b)
        }
        chains.map(_.size - 1).sum
      }
      .sum
  }

  /** On `m`: the loops without a reason not to double, by whether they double; of those that do,
    * the ones with a sharable gather (and the gathers), and the ones whose MII without the third
    * read port would drop without those gathers' LDs; of the `MiiIncrease` ones, those that would
    * then double.
    */
  private def gatherCounts(ks: Seq[LoopKernel], m: MachineDescription) = {
    val ld = m.operation("LD").ii
    ks.map { k =>
      val p = ModuloCompiler.prepare(k, m).fold(e => fail(e), identity)
      if (LoopVector.reasons(p.scalar).nonEmpty) (0, 0, 0, 0, 0, 0)
      else {
        val v = LoopVector.double(p.scalar)
        val n = sharable(v)
        val mii = Mii.of(v).withoutReadPort
        val without = Mii(
          mii.resources.map { b =>
            b.resource match {
              case "memory slot" | "memory write-back port" => b.copy(uses = b.uses - 2 * n)
              case "LD unit"                                => b.copy(uses = b.uses - 2 * n * ld)
              case _                                        => b
            }
          },
          mii.recMII
        ).mii
        val g = Mii.of(p.scalar).withoutReadPort.mii
        if (mii.mii <= 2 * g)
          (1, if (n > 0) 1 else 0, n, if (without < mii.mii) 1 else 0, 0, 0)
        else (0, 0, 0, 0, 1, if (without <= 2 * g) 1 else 0)
      }
    }.foldLeft((0, 0, 0, 0, 0, 0)) { case ((a, b, c, d, e, f), (u, v, w, x, y, z)) =>
      (a + u, b + v, c + w, d + x, e + y, f + z)
    }
  }

  test("random vectorizable loops on W-full: decisions, the 5a split, outputs and the gathers") {
    val ks = RandomLoops.vectorizable(110, 300)
    val counts = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    ks.foreach { k =>
      val p = ModuloCompiler.prepare(k, full).fold(e => fail(e), identity)
      p.simd match {
        case LoopSimd.Vectorized(_) =>
          counts("doubled") += 1
          val fields = p.graph.ids(Place.Each).flatMap(n => LoopNode.fields(p.graph.nodes(n)))
          if (fields.exists(_.isInstanceOf[VRef.Gather])) counts("gathers") += 1
          if (p.graph.nodes.exists(_.isInstanceOf[LoopNode.StoreLane])) counts("lane STs") += 1
        case LoopSimd.Fallback(r, Some(d)) =>
          counts(r.describe) += 1
          d.notDoubled.map(_.name).distinct.foreach(counts(_) += 1)
          if (d.notDoubled.forall(_.name == "SameValue")) counts("SameValue only") += 1
        case other => fail(s"${k.name}: $other")
      }
      ModuloCompiler.compile(k, full) match {
        case Left(e) =>
          counts("no program") += 1; assert(e.contains("the doubled loop fits no II either"), e)
        case Right(c) =>
          correct(c, Seq(1L))
          if (c.result.attempts.exists(_.outcome.isInstanceOf[Outcome.NoAlignedPair]))
            counts("NoAlignedPair") += 1
          c.simd match {
            case LoopSimd.Fallback(_: LoopFallback.NoIiFits, _) => counts("NoIiFits") += 1
            case LoopSimd.Vectorized(_)                         =>
              // Against the scalar loop G on the same machine (the mvp-1 code).
              ModuloScheduler.schedule(p.scalar) match {
                case Right(s) =>
                  counts(s"II per iteration ${c.ii.compare(2 * s.ii).sign}") += 1
                  counts(s"cycles ${c.cycles.compare(s.cycles).sign}") += 1
                case Left(_) => counts("scalar loop: no program") += 1
              }
            case _ => ()
          }
      }
    }
    // Measured (COMPILER.md): 130 doubled (77 with a gather, 50 with lane STs), 143 OddTripCount,
    // 44 SameValue (27 alone); of the 170 not doubled, 67 have a rule-1 candidate.
    assert(
      counts.toMap == Map(
        "doubled" -> 130,
        "gathers" -> 77,
        "lane STs" -> 50,
        "OddTripCount" -> 143,
        "SameValue" -> 44,
        "SameValue only" -> 27,
        "NotImplemented: body packing: #110 stage 5b" -> 67,
        "NothingToPack" -> 103,
        "NoIiFits" -> 7,
        "no program" -> 1,
        "scalar loop: no program" -> 1,
        "II per iteration -1" -> 114,
        "II per iteration 0" -> 6,
        "II per iteration 1" -> 1,
        "cycles -1" -> 111,
        "cycles 0" -> 6,
        "cycles 1" -> 4
      )
    )
    // The gather exception (#110 第5段の Q3), measured: on W-full, 3 of the 130 doubled loops have
    // a sharable gather (6 gathers) and would have a lower MII without it; no loop is MiiIncrease.
    // With W-reg's counts, 85 double and 45 are MiiIncrease, 17 of which would double.
    val (d, withSharable, gathers, drops, increase, _) = gatherCounts(ks, full)
    assert(
      d == 130 && withSharable >= 2 && gathers >= 4 && drops >= 2 && increase == 0,
      (withSharable, gathers, drops)
    )
    val (dr, _, _, _, ir, wr) = gatherCounts(ks, mw1)
    assert(dr + ir == 130 && ir >= 30 && wr >= 10, (dr, ir, wr))
  }

  test("RandomLoops(76): the reasons by the reduction definition and the 5a split") {
    val ks = RandomLoops.kernels(76, 300).map(_._1)
    val counts = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    ks.foreach { k =>
      ModuloCompiler.prepare(k, full).fold(e => fail(e), identity).simd match {
        case LoopSimd.Vectorized(_)        => counts("doubled") += 1
        case LoopSimd.Fallback(r, Some(d)) =>
          counts(r.describe) += 1
          d.notDoubled.map(_.name).distinct.foreach(counts(_) += 1)
        case other => fail(s"${k.name}: $other")
      }
    }
    assert(
      counts.toMap == Map(
        "doubled" -> 3,
        "MiiIncrease" -> 3,
        "OddTripCount" -> 140,
        "AssociationOrder" -> 28,
        "LoopState" -> 192,
        "SameValue" -> 263,
        "NotImplemented: body packing: #110 stage 5b" -> 196,
        "NothingToPack" -> 101
      )
    )
    val (_, _, _, _, increase, wouldDouble) = gatherCounts(ks, full)
    assert(increase == 3 && wouldDouble == 0) // no gather of these three is sharable
  }
}

object WModuloSpec {

  /** One record: the reasons not to double (None on W-reg), the fallback (None when doubled), MII
    * and the resources that set it (none when RecMII sets it), II, the outcome of each II tried,
    * cycles, MinAvg at the MII (ASAP) and at the II, and MaxLive.
    */
  final case class Row(
      notDoubled: Option[Vector[NotDoubled]],
      fallback: Option[LoopFallback],
      mii: Int,
      bounding: Seq[String],
      ii: Int,
      attempts: Seq[Outcome],
      cycles: Int,
      minAvgAtMii: Int,
      minAvgAtIi: Int,
      maxLive: Int
  )

  /** A loop that runs as its scalar loop at its MII. */
  final case class Scalar(notDoubled: Option[Vector[NotDoubled]], fallback: Option[LoopFallback]) {
    def row(mii: Int, bounding: Seq[String], ii: Int, cycles: Int, a: Int, b: Int, live: Int): Row =
      Row(notDoubled, fallback, mii, bounding, ii, Seq(Outcome.Fits), cycles, a, b, live)
  }
}
