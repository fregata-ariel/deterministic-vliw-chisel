package vliw.compiler.modulo

import java.nio.file.Paths

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.{IsaExecutor, KernelCompiler}
import vliw.compiler.dag.LaneReason.NoNeighbour
import vliw.compiler.dsl.Interpreter
import vliw.compiler.loop._
import vliw.isa.MachineDescription

import Outcome.{Fits, NoMrt, TooManyLive}
import WModuloSpec.{Row, Scalar}

/** Modulo Scheduling on ISA mvp-2 with loop vectorization (#110 stage 5a: doubling; stage 5b: body
  * packing; docs/COMPILER.md "ループのベクトル化と Modulo Scheduling（W-full）"): the records of the six loops
  * on the mvp-2 machines, cell by cell, the fallbacks and the comparison API, and the random loops'
  * counts.
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

  /** Whether an II of `c`'s search, or of the doubled graph's when `c` fell back to G (`NoIiFits`),
    * found no aligned pair free (b′).
    */
  private def noAlignedPair(c: ModuloCompiled) = (c.result.attempts ++ (c.simd match {
    case LoopSimd.Fallback(LoopFallback.NoIiFits(as), _) => as
    case _                                               => Nil
  })).exists(_.outcome.isInstanceOf[Outcome.NoAlignedPair])

  private type Counts = scala.collection.mutable.Map[String, Int]
  private def counter: Counts = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)

  /** The vectorization decision of a loop prepared on a machine with LD.W. */
  private def decision(p: ModuloCompiler.Prepared): LoopDecision = p.simd match {
    case LoopSimd.Vectorized(d)        => d
    case LoopSimd.Fallback(_, Some(d)) => d
    case other                         => fail(s"${p.kernel.name}: $other")
  }

  /** A packed loop's shape: its pairs, vector states, gathers (also of two values before the loop),
    * vector operations before the loop, LDs of a word before the loop in the body (rule 4's LD copy
    * in a loop, an exception to ADR-0014), and whether its MII is above G's.
    */
  private def shape(p: ModuloCompiler.Prepared, d: LoopDecision, counts: Counts): Unit = {
    val g = p.graph
    val b = d.body.getOrElse(fail(s"${p.kernel.name}: no body"))
    b.pairs.foreach(x => counts(s"pair ${x.place.name}") += 1)
    if (b.vectorStates > 0) counts("packed: vector states") += 1
    val fields = g.ids(Place.Each).flatMap(n => LoopNode.fields(g.nodes(n)))
    if (fields.exists(_.isInstanceOf[VRef.Gather])) counts("packed: gathers") += 1
    if (
      fields.exists {
        case VRef.Gather(lo, _) => g.places(lo.node) == Place.Before
        case _                  => false
      }
    )
      counts("packed: gather before the loop") += 1
    if (
      g.ids(Place.Before).exists(n => LoopNode.isPair(g.nodes(n)) && g.nodes(n).mnemonic != "LD.W")
    )
      counts("packed: vector operation before the loop") += 1
    if (
      g.ids(Place.Each)
        .exists(n =>
          g.nodes(n) match {
            case LoopNode.Load(a) => a.stride == 0
            case _                => false
          }
        )
    )
      counts("packed: LD before the loop in the body") += 1
    if (Mii.of(g).withoutReadPort.mii > d.scalarMII) counts("packed: MII > G's") += 1
  }

  /** The lane reasons of a loop that is not doubled; a reason for the initial pair's limit apart.
    */
  private def lanes(d: LoopDecision, counts: Counts): Unit =
    d.body.foreach(_.scalarLanes.foreach(l => counts(s"lane ${l.describe}") += 1))

  /** `c`'s outputs (`IsaExecutor`), (b′) and `NoIiFits` by kind (doubled or packed), and a doubled
    * or packed program against the scalar loop G on the same machine (the mvp-1 code): the II (per
    * original iteration when doubled) and cycles.
    */
  private def compareWithG(c: ModuloCompiled, p: ModuloCompiler.Prepared, counts: Counts): Unit = {
    correct(c, Seq(1L))
    val d = decision(p)
    val kind = if (d.doubled) "doubled" else if (d.packed) "packed" else "scalar"
    c.simd match {
      case LoopSimd.Fallback(_: LoopFallback.NoIiFits, _) =>
        counts(s"NoIiFits ($kind)") += 1
        if (noAlignedPair(c)) counts(s"NoAlignedPair ($kind, NoIiFits)") += 1
      case LoopSimd.Vectorized(_) =>
        if (noAlignedPair(c)) counts(s"NoAlignedPair ($kind)") += 1
        ModuloScheduler.schedule(p.scalar) match {
          case Right(s) if d.doubled =>
            counts(s"II per iteration ${c.ii.compare(2 * s.ii).sign}") += 1
            counts(s"cycles ${c.cycles.compare(s.cycles).sign}") += 1
          case Right(s) =>
            counts(s"packed: II ${c.ii.compare(s.ii).sign}") += 1
            counts(s"packed: cycles ${c.cycles.compare(s.cycles).sign}") += 1
          case Left(_) => counts("scalar loop: no program") += 1
        }
      case _ => assert(!noAlignedPair(c))
    }
  }

  private val memory = Seq("memory slot")
  private val compute = Seq("compute slot", "compute write-back port", "third read port")
  private val doubled = Some(Vector.empty[NotDoubled])

  /** matmul3's packed bodies (the same on every machine with LD.W), and a reduction's. */
  private val bodyI = Some("body: 1 pair (each 18, stride 3), 1 scalar lane (NoNeighbour)")
  private val bodyM =
    Some("body: 3 pairs (after 18, 21, 24), 3 scalar lanes (NoNeighbour), 3 vector states")
  private val noPair = Some("body: 0 pairs, 1 scalar lane (NoNeighbour)")

  /** COMPILER.md "結果（16 レジスタ）": per machine and loop, the decision (the reasons not to double; None
    * for W-reg) and the packed body, the fallback, MII and its resources, II, the attempts, cycles,
    * MinAvg at the MII (ASAP) and at the II, and MaxLive.
    */
  private val rows: Seq[(MachineDescription, String, Row)] = {
    import NotDoubled._
    val reduction = Vector(OddTripCount, AssociationOrder)
    val nothing = Scalar(Some(reduction), noPair, Some(LoopFallback.NothingToPack))
    // Packed: MII 6 on every machine; then II, the attempts, cycles, MinAvg at MII and II, MaxLive.
    def packedI(bound: Seq[String], ii: Int, attempts: Seq[Outcome], cycles: Int, a: Int) =
      Row(Some(Vector(OddTripCount)), bodyI, None, 6, bound, ii, attempts, cycles, a, 15, 16)
    def packedM(bound: Seq[String], ii: Int, attempts: Seq[Outcome], cycles: Int, a: Int) =
      (atIi: Int, live: Int) =>
        Row(
          Some(Vector(AssociationOrder)),
          bodyM,
          None,
          6,
          bound,
          ii,
          attempts,
          cycles,
          a,
          atIi,
          live
        )
    // vlat: no table at II 6 (slots and write-back ports, L5); 20 and 17 values live at II 7 and 8.
    val vlatI = Seq(NoMrt("slot + write-back port (L5)"), TooManyLive(20, 16), TooManyLive(17, 16))
    val vlatM = Seq(NoMrt("slot + write-back port (L5)"), TooManyLive(18, 16))
    Seq(
      (full, "saxpy8 i", Row(doubled, None, None, 3, memory, 3, Seq(Fits), 17, 5, 6, 7)),
      (
        full,
        "fir4x8 i",
        Row(doubled, None, None, 4, compute, 5, Seq(TooManyLive(18, 16), Fits), 29, 13, 12, 14)
      ),
      (full, "matmul3 i", packedI(compute, 6, Seq(Fits), 28, 14)),
      (full, "matmul3 m", packedM(compute, 6, Seq(Fits), 28, 11)(14, 15)),
      (full, "product8 i", nothing.row(2, Nil, 2, 19, 2, 2, 2)),
      (full, "dot8 i", nothing.row(2, Nil, 2, 21, 3, 4, 4)),
      (rp4, "saxpy8 i", Row(doubled, None, None, 3, memory, 3, Seq(Fits), 17, 5, 6, 7)),
      (rp4, "fir4x8 i", Row(doubled, None, None, 4, compute.init, 4, Seq(Fits), 28, 13, 15, 16)),
      (rp4, "matmul3 i", packedI(compute.init, 6, Seq(Fits), 28, 14)),
      (rp4, "matmul3 m", packedM(compute.init, 6, Seq(Fits), 24, 11)(14, 15)),
      (rp4, "product8 i", nothing.row(2, Nil, 2, 19, 2, 2, 2)),
      (rp4, "dot8 i", nothing.row(2, Nil, 2, 21, 3, 4, 4)),
      (vlat, "saxpy8 i", Row(doubled, None, None, 3, memory, 3, Seq(Fits), 20, 7, 9, 9)),
      (
        vlat,
        "fir4x8 i",
        Row(doubled, None, None, 4, compute, 5, Seq(TooManyLive(20, 16), Fits), 34, 18, 15, 16)
      ),
      (vlat, "matmul3 i", packedI(compute, 9, vlatI :+ Fits, 37, 16)),
      (vlat, "matmul3 m", packedM(compute, 8, vlatM :+ Fits, 31, 12)(14, 16)),
      (vlat, "product8 i", nothing.row(2, Nil, 2, 19, 2, 2, 2)),
      (vlat, "dot8 i", nothing.row(2, Nil, 2, 21, 3, 4, 4)),
      (vii, "saxpy8 i", Row(doubled, None, None, 4, Seq("LD.W unit"), 4, Seq(Fits), 22, 4, 6, 7)),
      (
        vii,
        "fir4x8 i",
        Row(doubled, None, None, 6, Seq("VMAC unit"), 6, Seq(Fits), 35, 12, 12, 14)
      ),
      (vii, "matmul3 i", packedI(compute, 6, Seq(Fits), 31, 14)),
      (vii, "matmul3 m", packedM(compute :+ "VMAC unit", 6, Seq(Fits), 29, 11)(16, 16)),
      (vii, "product8 i", nothing.row(2, Nil, 2, 20, 2, 2, 2)),
      (vii, "dot8 i", nothing.row(2, Nil, 2, 22, 3, 4, 4))
    )
  }

  test("the records of the six loops on W-full, rp4, vlat and vii, cell by cell") {
    for ((m, label, want) <- rows) {
      val c = compile(loop(label), m)
      val s = c.result
      val (decision, fallback) = c.simd match {
        case LoopSimd.Vectorized(d)  => (Some(d), None)
        case LoopSimd.Fallback(r, d) => (d, Some(r))
        case LoopSimd.Scalar         => fail(s"$label on ${m.machineId}: no W outcome")
      }
      // The resources that set the MII; product8 and dot8 are set by RecMII and keep MII 2.
      val bound = if (s.mii.recMII >= s.mii.resMII) Nil else s.mii.bounding
      val got = Row(
        decision.map(_.notDoubled),
        decision.flatMap(_.body.map(_.describe)),
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
    for (label <- Seq("product8 i", "dot8 i"))
      assert(compile(loop(label), vii).cycles == compile(loop(label), mvp1).cycles + 1, label)
  }

  test("matmul3 on vlat: registers raise the packed II, without costing cycles") {
    // (i): II 6 has no table; II 7 and 8 hold 20 and 17 values; II 9 fits in 37 cycles (II 7 would
    // take 39). (m): II 7 holds 18 values; II 8 fits in 31 (II 7: 32).
    def fits(ii: Int, cycles: Int, live: Int, minAvg: Int) =
      Sweep(ii, Fits, Some(cycles), Some(live), Some(minAvg))
    val noTable = Sweep(6, NoMrt("slot + write-back port (L5)"), None, None, None)
    assert(
      ModuloScheduler.sweep(compile(loop("matmul3 i"), vlat).graph) == Vector(
        noTable,
        Sweep(7, TooManyLive(20, 16), Some(39), Some(20), Some(20)),
        Sweep(8, TooManyLive(17, 16), Some(41), Some(17), Some(17)),
        fits(9, 37, 16, 15),
        fits(10, 39, 15, 14),
        fits(11, 42, 15, 14),
        fits(12, 45, 15, 14),
        fits(13, 48, 15, 14),
        fits(14, 50, 14, 13),
        fits(15, 49, 14, 13)
      )
    )
    assert(
      ModuloScheduler.sweep(compile(loop("matmul3 m"), vlat).graph) == Vector(
        noTable,
        Sweep(7, TooManyLive(18, 16), Some(32), Some(18), Some(16)),
        fits(8, 31, 16, 14),
        fits(9, 32, 16, 13),
        fits(10, 33, 16, 13),
        fits(11, 34, 16, 12),
        fits(12, 35, 16, 12)
      )
    )
  }

  test("fir4x8 on W-full and vlat: the sweep of every II; registers raise the II from 4 to 5") {
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
    // On vlat, raising the II costs 2 cycles: II 4 would take 32, II 5 takes 34.
    val v = compile(fir(8), vlat)
    def fits(ii: Int, cycles: Int, live: Int, minAvg: Int) =
      Sweep(ii, Fits, Some(cycles), Some(live), Some(minAvg))
    assert(
      ModuloScheduler.sweep(v.graph) == Vector(
        Sweep(4, TooManyLive(20, 16), Some(32), Some(20), Some(19)),
        fits(5, 34, 16, 15),
        fits(6, 39, 16, 14),
        fits(7, 40, 14, 12),
        fits(8, 43, 14, 12),
        fits(9, 47, 14, 12),
        fits(10, 49, 14, 11),
        fits(11, 53, 14, 11),
        fits(12, 57, 14, 11),
        fits(13, 58, 12, 11)
      )
    )
    assert(v.result.iiMax == 13 && v.result.attempts.map(_.ii) == Seq(4, 5))
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
    // A packed loop: its body; one iteration of II 6 is one original iteration.
    assert(
      line("matmul3 m") == "matmul3 (m): W: not doubled: AssociationOrder; body: 3 pairs " +
        "(after 18, 21, 24), 3 scalar lanes (NoNeighbour), 3 vector states; 2 iterations of II 6, " +
        "per iteration 4 LD, 1 LD.W, 3 MAC, 3 VMAC, 3 broadcasts, 0 gathers; MinAvg 11 at MII, " +
        "14 at II; MaxLive 15"
    )
    assert(
      line("matmul3 i") == "matmul3 (i): W: not doubled: OddTripCount; body: 1 pair (each 18, " +
        "stride 3), 1 scalar lane (NoNeighbour); 3 iterations of II 6, per iteration 3 LD, 1 MUL, " +
        "2 MAC, 1 VMUL, 2 VMAC, 1 ST.W, 1 ST, 3 broadcasts, 0 gathers; MinAvg 14 at MII, 15 at II; " +
        "MaxLive 16"
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
        // One store: no rule-1 candidate, nothing to pack.
        val out = Address(c.layout.elem("out", 0), 1)
        val d = LoopDecision(
          Vector(NotDoubled.OddTripCount),
          c.result.mii.withoutReadPort.mii,
          None,
          Some(LoopBody(Vector.empty, Vector(LoopLane(Place.Each, out, NoNeighbour)), 0))
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

  test("matmul3 packs its body on every machine with LD.W, and the comparisons measure it") {
    for (
      (label, body) <- Seq("matmul3 i" -> bodyI, "matmul3 m" -> bodyM);
      m <- Seq(full, rp4, vlat, vii)
    ) {
      val c = compile(loop(label), m)
      assert(
        c.simd match {
          case LoopSimd.Vectorized(d) => d.packed && !d.doubled && d.body.map(_.describe) == body
          case _                      => false
        },
        s"$label on ${m.machineId}"
      )
      assert(ModuloCompiler.candidate(loop(label), m).exists {
        case LoopCandidate.Packed(p) => p.bundles == c.bundles
        case _                       => false
      })
      // (m)'s vector states read themselves one VMAC back: RecMII 2 (vlat: VMAC latency 3).
      val recMII = if (label == "matmul3 m") (if (m eq vlat) 3 else 2) else 0
      assert(c.result.mii.recMII == recMII, s"$label on ${m.machineId}")
    }
    // mvp-1's records (COMPILER.md): MinAvg at the MII and at the II, and MaxLive.
    for (
      (m, label, want) <- Seq(
        (mvp1, "matmul3 i", (13, 14, 14)),
        (mvp1, "matmul3 m", (11, 14, 14)),
        (mvp1rp4, "matmul3 i", (13, 16, 16))
      )
    ) {
      val c = compile(loop(label), m)
      assert((c.minAvgAtMii, c.minAvgAtIi, c.maxLive) == want, s"$label on ${m.machineId}")
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
      // matmul3's packed loop fits no II, and its scalar loop holds 14 values at every II: no
      // program, as on mvp-1.
      for (label <- Seq("matmul3 i", "matmul3 m"))
        assert(ModuloCompiler.compile(loop(label), m).left.exists { e =>
          e.contains("values live at once") && e.contains("the packed loop fits no II either")
        })
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
      val d = decision(p)
      if (d.doubled) {
        counts("doubled") += 1
        val fields = p.graph.ids(Place.Each).flatMap(n => LoopNode.fields(p.graph.nodes(n)))
        if (fields.exists(_.isInstanceOf[VRef.Gather])) counts("gathers") += 1
        if (p.graph.nodes.exists(_.isInstanceOf[LoopNode.StoreLane])) counts("lane STs") += 1
      } else {
        counts(if (d.packed) "packed" else "NothingToPack") += 1
        if (!d.packed && LoopPack.byAddress(p.scalar).nonEmpty)
          counts("NothingToPack: a candidate") += 1
        d.notDoubled.map(_.name).distinct.foreach(counts(_) += 1)
        if (d.notDoubled.forall(_.name == "SameValue")) counts("SameValue only") += 1
        if (d.packed) shape(p, d, counts)
      }
      ModuloCompiler.compile(k, full) match {
        case Left(e) =>
          counts("no program") += 1; assert(e.contains("loop fits no II either"), e)
        case Right(c) => compareWithG(c, p, counts)
      }
    }
    // Measured (COMPILER.md): 130 doubled (77 with a gather, 50 with lane STs), 143 OddTripCount,
    // 44 SameValue (27 alone); of the 170 not doubled, 66 pack (the w(2i)/w(2i+1) pairs: one ST.W
    // of a gathered root each) and 104 have nothing to pack. NoAlignedPair: 2 packed loops.
    assert(
      counts.toMap == Map(
        "doubled" -> 130,
        "gathers" -> 77,
        "lane STs" -> 50,
        "OddTripCount" -> 143,
        "SameValue" -> 44,
        "SameValue only" -> 27,
        "packed" -> 66,
        "NothingToPack" -> 104,
        "NothingToPack: a candidate" -> 1,
        "pair each" -> 66,
        "packed: gathers" -> 66,
        "packed: gather before the loop" -> 1,
        "packed: LD before the loop in the body" -> 5,
        "packed: MII > G's" -> 2,
        "NoIiFits (doubled)" -> 7,
        "NoIiFits (packed)" -> 1,
        "no program" -> 1,
        "scalar loop: no program" -> 1,
        "NoAlignedPair (packed)" -> 2,
        "II per iteration -1" -> 114,
        "II per iteration 0" -> 6,
        "II per iteration 1" -> 1,
        "cycles -1" -> 111,
        "cycles 0" -> 6,
        "cycles 1" -> 4,
        "packed: II -1" -> 30,
        "packed: II 0" -> 26,
        "packed: II 1" -> 9,
        "packed: cycles -1" -> 40,
        "packed: cycles 0" -> 10,
        "packed: cycles 1" -> 15
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

  test(
    "random vectorizable loops on the other configurations: (b′) only in packed loops, right outputs"
  ) {
    // COMPILER.md "NoAlignedPair（(b′)）" and "乱数のループ": vlat, rp4, vii, RtlMvp2Spec's variant,
    // and W-full with 8 and 6 registers. A doubled loop never meets (b′) (5a's measured sets); the
    // packed loops that do, as (packed loops, loops that fell back with (b′) among their W IIs). A
    // loop with no program has no II for the W graph nor for G. On vlat, 20 doubled loops take more
    // cycles than their scalar loop G and 3 have a worse II per original iteration (W-full: 4 and
    // 1, above).
    val ks = RandomLoops.vectorizable(110, 300)
    val configurations = Seq(
      vlat -> ((3, 0)),
      rp4 -> ((2, 0)),
      vii -> ((2, 0)),
      variant -> ((2, 6)),
      withRegisters(full, 8) -> ((1, 3)),
      withRegisters(full, 6) -> ((0, 3))
    )
    for ((m, want) <- configurations) {
      var (slower, worseIi, packed, fellBack) = (0, 0, 0, 0)
      ks.foreach { k =>
        ModuloCompiler.compile(k, m) match {
          case Left(e)  => assert(e.contains("no II from"), s"${k.name} on ${m.machineId}: $e")
          case Right(c) =>
            correct(c, Seq(1L))
            val where = s"${k.name} on ${m.machineId}"
            c.simd match {
              case LoopSimd.Vectorized(d) if d.doubled =>
                assert(!noAlignedPair(c), where)
                if (m eq vlat) {
                  val p = ModuloCompiler.prepare(k, m).fold(e => fail(e), identity)
                  ModuloScheduler.schedule(p.scalar).foreach { s =>
                    if (c.cycles > s.cycles) slower += 1
                    if (c.ii > 2 * s.ii) worseIi += 1
                  }
                }
              case LoopSimd.Vectorized(_) => if (noAlignedPair(c)) packed += 1
              case LoopSimd.Fallback(_: LoopFallback.NoIiFits, Some(d)) =>
                if (d.doubled) assert(!noAlignedPair(c), where)
                else if (noAlignedPair(c)) fellBack += 1
              case _ => assert(!noAlignedPair(c), where)
            }
        }
      }
      assert((packed, fellBack) == want, m.machineId)
      if (m eq vlat) assert((slower, worseIi) == ((20, 3)))
    }
  }

  test(
    "no NoAlignedPair in the benchmark loops with fewer registers; the sweeps of the random loops"
  ) {
    for (m <- Seq(full, rp4, vlat, vii); n <- Seq(16, 12, 10, 8); k <- LoopBenchmarks.all)
      ModuloCompiler
        .compile(k, withRegisters(m, n))
        .foreach(c => assert(!noAlignedPair(c), s"${k.name} on ${m.machineId}, $n registers"))
    LoopBenchmarks.all.foreach(k =>
      ModuloCompiler.compile(k, variant).foreach(c => assert(!noAlignedPair(c), k.name))
    )
    // Every II from the MII to the span: of the 130 doubled random loops, 568 outcomes for each
    // register count, each one fits, has no table (a) or has too many values live (b); of the 66
    // packed ones, 361 outcomes, of which 5, 9, 11 and 0 are (b′) with 16, 8, 6 and 4 registers.
    val ks = RandomLoops.vectorizable(110, 300)
    for ((n, packedB) <- Seq(16 -> 5, 8 -> 9, 6 -> 11, 4 -> 0)) {
      val m = withRegisters(full, n)
      val (doubled, packed) = ks
        .flatMap { k =>
          val p = ModuloCompiler.prepare(k, m).fold(e => fail(e), identity)
          p.simd match {
            case LoopSimd.Vectorized(d) =>
              ModuloScheduler.sweep(p.graph).map(s => (d.doubled, s.outcome))
            case _ => Nil
          }
        }
        .partition(_._1)
      assert(doubled.size == 568 && packed.size == 361, n)
      def kinds(os: Seq[(Boolean, Outcome)], b: Boolean) = os.forall {
        case (_, Fits | _: Outcome.NoMrt | _: TooManyLive) => true
        case (_, _: Outcome.NoAlignedPair)                 => b
        case _                                             => false
      }
      assert(kinds(doubled, b = false) && kinds(packed, b = true), n)
      assert(packed.count(_._2.isInstanceOf[Outcome.NoAlignedPair]) == packedB, n)
    }
  }

  test("RandomLoops(76): the reasons by the reduction definition, the packed bodies and the code") {
    val ks = RandomLoops.kernels(76, 300).map(_._1)
    val counts = counter
    ks.foreach { k =>
      val p = ModuloCompiler.prepare(k, full).fold(e => fail(e), identity)
      val d = decision(p)
      if (d.doubled) counts("doubled") += 1
      else {
        counts(if (d.packed) "packed" else "NothingToPack") += 1
        if (!d.packed && LoopPack.byAddress(p.scalar).nonEmpty)
          counts("NothingToPack: a candidate") += 1
        d.notDoubled.map(_.name).distinct.foreach(counts(_) += 1)
        if (d.packed) shape(p, d, counts)
        lanes(d, counts)
      }
      // The doubled and packed loops give the DSL's outputs; every other loop is the mvp-1 code
      // (states included), and a loop with no program on W-full has none on mvp-1 either.
      ModuloCompiler.compile(k, full) match {
        case Right(c) if c.simd.isInstanceOf[LoopSimd.Vectorized] => compareWithG(c, p, counts)
        case w                                                    =>
          (w, ModuloCompiler.compile(k, mvp1)) match {
            case (Right(c), Right(c1)) =>
              assert(c.bundles == c1.bundles, k.name)
              if (c.simd.isInstanceOf[LoopSimd.Fallback]) c.simd match {
                case LoopSimd.Fallback(_: LoopFallback.NoIiFits, _) =>
                  counts("NoIiFits (packed)") += 1
                  if (noAlignedPair(c)) counts("NoAlignedPair (packed, NoIiFits)") += 1
                case _ => ()
              }
            case (Left(_), Left(_)) => counts("no program") += 1
            case (c, c1)            => fail(s"${k.name}: W-full ${c.isRight}, mvp-1 ${c1.isRight}")
          }
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
        "packed" -> 115,
        "NothingToPack" -> 182,
        "NothingToPack: a candidate" -> 81,
        "no program" -> 4,
        "pair each" -> 112,
        "pair after" -> 4,
        "packed: vector states" -> 4,
        "packed: gathers" -> 114,
        "packed: gather before the loop" -> 13,
        "packed: vector operation before the loop" -> 5,
        "packed: LD before the loop in the body" -> 30,
        "packed: MII > G's" -> 2,
        "lane NoNeighbour" -> 302,
        "lane NeededElsewhere" -> 212,
        "lane NeededElsewhere (initial pair)" -> 44,
        "lane SameValue" -> 12,
        "NoIiFits (packed)" -> 7,
        "NoAlignedPair (packed, NoIiFits)" -> 2,
        "II per iteration -1" -> 2,
        "II per iteration 0" -> 1,
        "cycles -1" -> 2,
        "cycles 0" -> 1,
        "packed: II -1" -> 43,
        "packed: II 0" -> 56,
        "packed: II 1" -> 8,
        "packed: cycles -1" -> 59,
        "packed: cycles 0" -> 20,
        "packed: cycles 1" -> 28
      )
    )
    val (_, _, _, _, increase, wouldDouble) = gatherCounts(ks, full)
    assert(increase == 3 && wouldDouble == 0) // no gather of these three is sharable
  }

  test("packable(5, 300) on W-full: decisions, packed shapes, lane reasons, outputs and (b′)") {
    val ks = RandomLoops.packable(5, 300)
    val counts = counter
    ks.foreach { k =>
      val p = ModuloCompiler.prepare(k, full).fold(e => fail(e), identity)
      val d = decision(p)
      if (d.doubled) counts("doubled") += 1
      else {
        counts(if (d.packed) "packed" else "NothingToPack") += 1
        if (!d.packed && LoopPack.byAddress(p.scalar).nonEmpty)
          counts("NothingToPack: a candidate") += 1
        if (d.packed) {
          shape(p, d, counts)
          if (LoopPack.byAddress(p.scalar).exists(LoopPack.gatherFree(p.scalar, _)))
            counts("packed: a gather-free candidate") += 1
        }
        lanes(d, counts)
      }
      ModuloCompiler.compile(k, full) match {
        case Left(_)  => counts("no program") += 1
        case Right(c) => compareWithG(c, p, counts)
      }
    }
    assert(
      counts.toMap == Map(
        "doubled" -> 22,
        "packed" -> 231,
        "NothingToPack" -> 47,
        "NothingToPack: a candidate" -> 47,
        "pair each" -> 297,
        "pair after" -> 45,
        "packed: vector states" -> 41,
        "packed: gathers" -> 138,
        "packed: gather before the loop" -> 22,
        "packed: vector operation before the loop" -> 45,
        "packed: LD before the loop in the body" -> 19,
        "packed: MII > G's" -> 3,
        "packed: a gather-free candidate" -> 102,
        "lane NoNeighbour" -> 58,
        "lane NeededElsewhere" -> 89,
        "lane NeededElsewhere (initial pair)" -> 29,
        "lane SameValue" -> 332,
        "lane SameValue (initial pair)" -> 72,
        "no program" -> 27,
        "NoIiFits (doubled)" -> 1,
        "NoIiFits (packed)" -> 11,
        "NoAlignedPair (packed)" -> 5,
        "NoAlignedPair (packed, NoIiFits)" -> 1,
        "scalar loop: no program" -> 3,
        "II per iteration -1" -> 8,
        "II per iteration 0" -> 13,
        "cycles -1" -> 16,
        "cycles 0" -> 1,
        "cycles 1" -> 4,
        "packed: II -1" -> 152,
        "packed: II 0" -> 30,
        "packed: II 1" -> 10,
        "packed: cycles -1" -> 157,
        "packed: cycles 0" -> 22,
        "packed: cycles 1" -> 13
      )
    )
  }

  test("packable loops on vlat, rp4 and vii: the same bodies; on vlat, outputs and (b′)") {
    val ks = RandomLoops.packable(5, 100)
    def bodies(m: MachineDescription) =
      ks.map(k => decision(ModuloCompiler.prepare(k, m).fold(e => fail(e), identity)).body)
    val onFull = bodies(full)
    Seq(rp4, vlat, vii).foreach(m => assert(bodies(m) == onFull, m.machineId))
    val counts = counter
    ks.foreach { k =>
      val p = ModuloCompiler.prepare(k, vlat).fold(e => fail(e), identity)
      ModuloCompiler.compile(k, vlat) match {
        case Left(_)  => counts("no program") += 1
        case Right(c) => compareWithG(c, p, counts)
      }
    }
    assert(
      Seq("no program", "NoIiFits (packed)", "NoAlignedPair (packed)")
        .:+("NoAlignedPair (packed, NoIiFits)")
        .map(counts) == Seq(6, 5, 1, 1)
    )
  }

  test("a doubled loop meets (b′) with 8 registers: packable loops 19 (W-full) and 67 (variant)") {
    val ks = RandomLoops.packable(5, 68)
    for ((m, i) <- Seq(withRegisters(full, 8) -> 19, variant -> 67)) {
      val c = compile(ks(i), m)
      assert(
        c.simd match {
          case LoopSimd.Fallback(_: LoopFallback.NoIiFits, Some(d)) => d.doubled && noAlignedPair(c)
          case _                                                    => false
        },
        s"${ks(i).name} on ${m.machineId}"
      )
      correct(c, Seq(1L))
    }
  }
}

object WModuloSpec {

  /** One record: the reasons not to double (None on W-reg), the packed body (`LoopBody.describe`;
    * None when doubled), the fallback (None when doubled or packed), MII and the resources that set
    * it (none when RecMII sets it), II, the outcome of each II tried, cycles, MinAvg at the MII
    * (ASAP) and at the II, and MaxLive.
    */
  final case class Row(
      notDoubled: Option[Vector[NotDoubled]],
      body: Option[String],
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
  final case class Scalar(
      notDoubled: Option[Vector[NotDoubled]],
      body: Option[String],
      fallback: Option[LoopFallback]
  ) {
    def row(mii: Int, bounding: Seq[String], ii: Int, cycles: Int, a: Int, b: Int, live: Int): Row =
      Row(notDoubled, body, fallback, mii, bounding, ii, Seq(Outcome.Fits), cycles, a, b, live)
  }
}
