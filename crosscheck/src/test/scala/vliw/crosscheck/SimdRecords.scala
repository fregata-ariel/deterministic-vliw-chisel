package vliw.crosscheck

import java.nio.file.Path

import vliw.compiler.{CompiledKernel, FallbackReason, KernelCompiler, Simd, SimdCandidate}
import vliw.compiler.dag.{Dag, Node, Pack, Packing}
import vliw.compiler.dsl.{Benchmarks, Interpreter, Kernel, MemoryLayout, Values}
import vliw.compiler.loop.{LoopBenchmarks, LoopGraph, LoopKernel, LoopNode, LoopPack, Place}
import vliw.compiler.modulo.{
  LoopCandidate,
  LoopDecision,
  LoopSimd,
  Mii,
  ModuloCompiled,
  ModuloCompiler,
  ModuloScheduler,
  NoSchedule,
  Outcome
}
import vliw.compiler.regalloc.{Attempt, NoFit, RegisterAllocator}
import vliw.hw.CoreParams
import vliw.isa.{Bundle, ComputeOp, MachineDescription, MemoryOp, Slot}
import vliw.sim.{Execution, Metrics, ReferenceModel}

/** The comparison records of ISA mvp-2 (W, #110 stage 6; ADR-0022, ADR-0023; benchmarks/README.md):
  * benchmarks/SIMD.md, which compares mvp-1 with W on the same kernels, inputs, layout and
  * strategies, and the test case and the metrics of each W program and of `matmul4`'s mvp-1
  * baseline. The configurations are mvp-1 and `mvp1_rp4` (the baseline, MAC.md's series), W-reg
  * (`tests/machines/mvp2_mw1.json`), W-full (`config/machine-mvp2.json`) and `mvp2_rp4`.
  *
  * The W candidates are never replaced by scalar values (ADR-0023, #78 Q10): a kernel whose every
  * attempt of the packed DAG fails is infeasible with each attempt's failure, a fixed row keeps the
  * failure of its attempt on the packed DAG, a W-full loop that fits no II is infeasible with every
  * II's outcome, and W-reg loops are not measured (#78 Q8 (c)). A kernel or loop with nothing to
  * pack is measured as its scalar program, with the reason.
  *
  * Written with the other records by `BenchmarkRecords`; BenchmarkSpec checks the files and the
  * facts that the generated sentences state.
  */
object SimdRecords {
  import BenchmarkRecords.{Dir, Mode, Record, Seed, Strategy, cyclesCell, loads, noFit}

  /** One configuration: its name in the records, machine, machine description and record suffix. */
  final case class Config(name: String, machine: MachineDescription, path: String, suffix: String) {
    def w: Boolean = machine.hasVector

    /** Why the RTL does not implement the machine (`CoreParams.unsupported`); empty if it does. */
    def unsupported: Seq[String] = CoreParams.unsupported(machine)
  }

  /** The configurations: name, machine description and record suffix. The baseline's suffixes are
    * MAC.md's, and its records of `saxpy8`, `fir4x8` and `matmul3` are MAC.md's series.
    */
  val Configs: Seq[(String, String, String)] = Seq(
    ("mvp-1", "config/machine-mvp1.json", "_mac"),
    ("W-reg", "tests/machines/mvp2_mw1.json", "_wreg"),
    ("W-full", "config/machine-mvp2.json", "_w"),
    ("mvp1_rp4", "tests/machines/mvp1_rp4.json", "_mac_rp4"),
    ("mvp2_rp4", "tests/machines/mvp2_rp4.json", "_w_rp4")
  )

  /** The configuration of MAC.md that each baseline configuration is. */
  val MacName: Map[String, String] = Map("mvp-1" -> "mvp-1", "mvp1_rp4" -> "rp4")

  /** The same-row ratios: `(a, b)` is a's cycles over b's. The first three are ADR-0023's pairs
    * (operation width, memory width, both), then W at four read ports, and the fourth read port
    * under W.
    */
  val Pairs: Seq[(String, String)] = Seq(
    "mvp-1" -> "W-reg",
    "W-reg" -> "W-full",
    "mvp-1" -> "W-full",
    "mvp1_rp4" -> "mvp2_rp4",
    "W-full" -> "mvp2_rp4"
  )

  /** The W configuration of each baseline at the same read ports. */
  val Baseline: Map[String, String] =
    Map("W-reg" -> "mvp-1", "W-full" -> "mvp-1", "mvp2_rp4" -> "mvp1_rp4")

  /** `matmul4` (#78 Q13 (b)): recorded here only, not in `Benchmarks.all` (ADR-0023). */
  val matmul4: Kernel = Benchmarks.matmul(4)
  val kernels: Seq[Kernel] = Benchmarks.all :+ matmul4
  val matmul4Loops: Seq[LoopKernel] =
    Seq(LoopBenchmarks.matmulRows(4), LoopBenchmarks.matmulAcc(4))
  val loops: Seq[LoopKernel] = LoopBenchmarks.all ++ matmul4Loops

  /** A program of a fixed row run on the reference model with the record's inputs. */
  final case class Ran(compiled: CompiledKernel, run: Execution, metrics: ujson.Obj)

  /** One kernel and mode on one configuration, fewest cycles per mode: the record, or (W only) the
    * failure of every attempt of the packed DAG. `dag` is the DAG that the attempts schedule: the
    * packed DAG, or the fused scalar DAG (the baseline, and a W kernel with nothing to pack).
    */
  final case class Fewest(
      config: Config,
      kernel: Kernel,
      mode: Mode,
      prepared: KernelCompiler.Prepared,
      dag: Dag,
      result: Either[Vector[(Attempt, NoFit)], Record]
  ) {
    def packing: Option[Packing] = prepared.packing
    def nothingToPack: Boolean = packing.exists(_.nothingToPack)
  }

  /** One strategy in one mode on one configuration: the program run, or why the attempt does not
    * fit (on W, of the packed DAG).
    */
  final case class Row(
      config: Config,
      kernel: Kernel,
      strategy: Strategy,
      mode: Mode,
      result: Either[NoFit, Ran]
  )

  /** A loop on one configuration. */
  sealed trait LoopResult extends Product with Serializable
  object LoopResult {

    /** A program: the baseline's, the W graph's, or the scalar loop of a loop with nothing to pack.
      */
    final case class Measured(record: ModuloRecords.Compared) extends LoopResult

    /** The baseline fits no II (`ModuloCompiler.compile` is Left): every II's outcome. */
    final case class NoProgram(failure: NoSchedule) extends LoopResult

    /** The W graph fits no II (`LoopCandidate.Infeasible`): every II's outcome. */
    final case class Infeasible(decision: LoopDecision, failure: NoSchedule) extends LoopResult

    /** W-reg (`LoopCandidate.NotMeasured`, #78 Q8 (c)). */
    final case class NotMeasured(reason: String) extends LoopResult
  }

  final case class LoopEntry(
      config: Config,
      kernel: LoopKernel,
      prepared: ModuloCompiler.Prepared,
      candidate: Option[LoopCandidate],
      result: LoopResult
  ) {
    def compiled: Option[ModuloCompiled] = result match {
      case LoopResult.Measured(r) => Some(r.compiled)
      case _                      => None
    }
  }

  /** Every record of the comparison, and the expected outputs of each kernel. */
  final case class Series(
      configs: Seq[Config],
      fewest: Seq[Fewest],
      fixed: Seq[Row],
      loops: Seq[LoopEntry],
      expected: Map[String, Values]
  ) {
    private lazy val fewestBy = fewest.map(f => (f.config.name, f.kernel.name, f.mode) -> f).toMap
    private lazy val rowBy =
      fixed.map(r => (r.config.name, r.kernel.name, r.mode, r.strategy) -> r).toMap
    private lazy val loopBy = loops.map(l => (l.config.name, l.kernel) -> l).toMap
    def config(name: String): Config = configs.find(_.name == name).get
    def of(c: String, k: Kernel, mode: Mode): Fewest = fewestBy((c, k.name, mode))
    def row(c: String, k: Kernel, mode: Mode, s: Strategy): Row = rowBy((c, k.name, mode, s))
    def loop(c: String, k: LoopKernel): LoopEntry = loopBy((c, k))
    def strategies: Seq[Strategy] = BenchmarkRecords.strategies(configs.head.machine)
  }

  /** The SIMD series, with the baseline from MAC.md's series `mac`. */
  def series(mac: Seq[MacRecords.Series]): Series = {
    val configs = Configs.map { case (n, p, s) => Config(n, MacRecords.machineAt(p), p, s) }
    val expected = kernels.map { k =>
      k.name -> Interpreter.run(k, KernelCompiler.sampleInputs(k, Seed)).fold(sys.error, identity)
    }.toMap
    def macOf(c: Config) = mac.find(_.config.name == MacName(c.name)).get
    def prepare(k: Kernel, m: MachineDescription) =
      KernelCompiler.prepare(k, m).fold(sys.error, identity)
    def ran(c: CompiledKernel, name: String): Ran = {
      val in =
        c.layout.inputs(KernelCompiler.sampleInputs(c.kernel, Seed)).fold(sys.error, identity)
      val run =
        ReferenceModel.run(c.machine, c.words, in).fold(v => sys.error(s"$name: $v"), identity)
      Ran(c, run, Metrics.of(c.machine, c.words, run, name))
    }
    def rowName(c: Config, k: Kernel, mode: Mode, s: Strategy) =
      s"${k.name}_${mode.name}${c.suffix} (${s.attempt(mode)})"

    val fewest = configs.flatMap { c =>
      val m = c.machine
      if (!c.w) {
        val base = macOf(c).records ++
          BenchmarkRecords.records(m, Some(c.path), c.suffix, Seq(matmul4))
        for (k <- kernels; mode <- Mode.all) yield {
          val r = base.find(x => x.kernel == k && x.mode == mode).get
          Fewest(c, k, mode, prepare(k, m), r.compiled.dag, Right(r))
        }
      } else
        for (k <- kernels; mode <- Mode.all) yield {
          val p = prepare(k, m)
          val packing = p.packing.get
          val dag = if (packing.nothingToPack) p.dag else packing.dag
          val record =
            (cc: CompiledKernel) => BenchmarkRecords.record(cc, mode, Some(c.path), c.suffix)
          KernelCompiler
            .candidate(k, m, singleIssue = mode.singleIssue)
            .fold(sys.error, identity) match {
            case SimdCandidate.Packed(cc)           => Fewest(c, k, mode, p, dag, Right(record(cc)))
            case SimdCandidate.NothingToPack(_, cc) => Fewest(c, k, mode, p, dag, Right(record(cc)))
            case SimdCandidate.Infeasible(_, fs)    => Fewest(c, k, mode, p, dag, Left(fs))
          }
        }
    }

    val fixed = configs.flatMap { c =>
      val m = c.machine
      if (!c.w)
        (macOf(c).fixed ++ BenchmarkRecords.fixed(m, Seq(matmul4))).map { f =>
          Row(
            c,
            f.kernel,
            f.strategy,
            f.mode,
            f.result.map(ran(_, rowName(c, f.kernel, f.mode, f.strategy)))
          )
        }
      else
        for {
          k <- kernels
          p = prepare(k, m)
          packing = p.packing.get
          (dag, simd) =
            if (packing.nothingToPack)
              (p.dag, Simd.Fallback(FallbackReason.NothingToPack, packing): Simd)
            else (packing.dag, Simd.Packed(packing): Simd)
          s <- BenchmarkRecords.strategies(m)
          mode <- Mode.all
        } yield {
          // Code that the verifier rejects is a compiler error, never "does not fit".
          val result = RegisterAllocator
            .run(dag, s.attempt(mode))
            .map(a => KernelCompiler.finish(p, a, dag, simd).fold(sys.error, identity))
          Row(c, k, s, mode, result.map(ran(_, rowName(c, k, mode, s))))
        }
    }

    val loopEntries = for (c <- configs; k <- loops) yield {
      val m = c.machine
      val p = ModuloCompiler.prepare(k, m).fold(sys.error, identity)
      def measured(cc: ModuloCompiled) =
        LoopResult.Measured(ModuloRecords.recordOf(cc, Some(c.path), c.suffix))
      if (!c.w) {
        val result = macOf(c).loops.find(_.kernel == k) match {
          case Some(r) => LoopResult.Measured(r)
          case None    =>
            ModuloCompiler.compile(k, m) match {
              case Right(cc) => measured(cc)
              case Left(_)   => LoopResult.NoProgram(ModuloRecords.failure(k, m))
            }
        }
        LoopEntry(c, k, p, None, result)
      } else {
        val candidate = ModuloCompiler.candidate(k, m).fold(sys.error, identity)
        val result = candidate match {
          case LoopCandidate.Packed(cc)           => measured(cc)
          case LoopCandidate.NothingToPack(_, cc) => measured(cc)
          case LoopCandidate.Infeasible(d, _)     =>
            LoopResult.Infeasible(d, ModuloRecords.failure(k, m))
          case LoopCandidate.NotMeasured(why) => LoopResult.NotMeasured(why)
        }
        LoopEntry(c, k, p, Some(candidate), result)
      }
    }
    Series(configs, fewest, fixed, loopEntries, expected)
  }

  // Quantities read from the records (vliw-metrics/2 for the baseline, /3 for W; docs/METRICS.md).

  private def int(v: ujson.Value): Int = v.num.toInt
  private def v3(x: ujson.Obj): Boolean = x("metrics").str == "vliw-metrics/3"

  /** Operations issued: Compute, Memory (HALT excluded). */
  def operations(x: ujson.Obj): (Int, Int) =
    (int(x("issue")("compute")("operations")), int(x("issue")("memory")("operations")))

  /** Operations by mnemonic, those issued, HALT excluded. */
  def byOperation(x: ujson.Obj): Seq[(String, Int)] =
    x("issue")("by_operation").obj.toSeq.collect {
      case (k, v) if k != "HALT" && int(v) > 0 => k -> int(v)
    }

  /** Write-back commits (operations) of the Compute and Memory ports. */
  def commits(x: ujson.Obj): (Int, Int) =
    (int(x("writeback")("compute")("commits")), int(x("writeback")("memory")("commits")))

  /** Register words written by each port: /3's `registers`; on /2 one per commit. */
  def written(x: ujson.Obj): (Int, Int) =
    if (v3(x))
      (int(x("writeback")("compute")("registers")), int(x("writeback")("memory")("registers")))
    else commits(x)

  /** Register fields read (L11's count). */
  def readFields(x: ujson.Obj): Int = int(x("issue")("register_reads")("reads"))

  /** Register words read: /3's `registers`; on /2 one per field. */
  def readRegisters(x: ujson.Obj): Int =
    if (v3(x)) int(x("issue")("register_reads")("registers")) else readFields(x)

  /** Transfer words read and written. */
  def transfer(x: ujson.Obj): (Int, Int) =
    (int(x("scratchpad")("reads")), int(x("scratchpad")("writes")))

  /** Memory operations that read and write: /3's `read_operations`; on /2 one per word. */
  def transferOperations(x: ujson.Obj): (Int, Int) =
    if (v3(x))
      (int(x("scratchpad")("read_operations")), int(x("scratchpad")("write_operations")))
    else transfer(x)

  /** The bundles with (MAC or VMAC) and (ST or ST.W): /3's `rs3_with_store`, /2's `mac_with_st`. */
  def rs3WithStore(x: ujson.Obj): Int =
    int(x("issue")(if (v3(x)) "rs3_with_store" else "mac_with_st"))

  /** The bundles by register fields read, 0 to `read_ports`. */
  def byReads(x: ujson.Obj): Vector[Int] = {
    val by = x("issue")("register_reads")("bundles_by_reads").obj
    (0 until by.size).map(k => int(by(k.toString))).toVector
  }

  /** Transfer words of each scratchpad bank. */
  def bankWords(x: ujson.Obj): Vector[Int] =
    x("scratchpad")("banks").arr.map(b => int(b("reads")) + int(b("writes"))).toVector

  /** Register words read from each RF bank (/3 only). */
  def rfBanks(x: ujson.Obj): Option[Vector[Int]] =
    x("issue")("register_reads").obj.get("banks").map(_.arr.map(b => int(b("registers"))).toVector)

  /** The transfer words of a DAG: LD 1, LD.W 2; ST and a lane ST 1, ST.W 2. */
  def dagWords(d: Dag): (Int, Int) = (
    d.nodes.map {
      case _: Node.Load  => 1
      case _: Node.LoadW => 2
      case _             => 0
    }.sum,
    d.nodes.map {
      case _: Node.Store | _: Node.StoreLane => 1
      case _: Node.StoreW                    => 2
      case _                                 => 0
    }.sum
  )

  // Per-resource lower bounds of list scheduling (#109, #110, ADR-0023 "記録").

  /** The units with a bound of their own. */
  val Units: Seq[String] = Seq("MUL", "MAC", "VMUL", "VMAC", "LD", "LD.W")

  /** The lower bounds of a schedule of `d`, in cycles with HALT (+1), one per resource: `dep` (the
    * critical path), `cs` and `ms` (one Compute and one Memory operation per bundle), `wbC` and
    * `wbM` (one commit per write-back port per boundary, L5), `rp` (⌈fields / read_ports⌉, L11),
    * each unit's uses × II (L9; None when unused), and each scratchpad bank's words (1RW).
    */
  final case class Bounds(values: Vector[(String, Option[Int])]) {
    def max: Int = values.flatMap(_._2).max
    def binding: Vector[String] = values.collect { case (n, Some(v)) if v == max => n }
    def apply(name: String): Option[Int] = values.find(_._1 == name).flatMap(_._2)
  }

  def bounds(d: Dag): Bounds = {
    val m = d.machine
    def spec(n: Node) = m.operation(n.mnemonic)
    val fields = d.nodes.map(_.fields).sum
    val words = d.nodes.flatMap {
      case Node.Load(a)            => Seq(a)
      case Node.LoadW(a)           => Seq(a, a + 1)
      case Node.Store(a, _)        => Seq(a)
      case Node.StoreW(a, _)       => Seq(a, a + 1)
      case Node.StoreLane(a, _, _) => Seq(a)
      case _                       => Nil
    }
    Bounds(
      Vector(
        "dep" -> Some(d.cyclesLowerBound),
        "cs" -> Some(d.nodes.count(spec(_).slot == Slot.Compute) + 1),
        "ms" -> Some(d.nodes.count(spec(_).slot == Slot.Memory) + 1),
        "wbC" -> Some(d.nodes.count(spec(_).writeback.contains(Slot.Compute)) + 1),
        "wbM" -> Some(d.nodes.count(spec(_).writeback.contains(Slot.Memory)) + 1),
        "rp" -> Some((fields + m.readPorts - 1) / m.readPorts + 1)
      ) ++ Units.map { u =>
        val uses = d.nodes.count(_.mnemonic == u)
        u -> Option.when(uses > 0)(uses * m.operation(u).ii + 1)
      } ++ (0 until m.banks).map(b => s"bank $b" -> Some(words.count(_ % m.banks == b) + 1))
    )
  }

  // #78's estimates (the proposal's comment of 2026-10-20: a throwaway port of the compiler, no
  // rematerialization, 16 registers). Information, not targets (ADR-0023).

  /** #78's configurations: name, the configuration measured here (None: estimated only, not
    * implemented, ADR-0022), and what it is.
    */
  val EstimateConfigs: Seq[(String, Option[String], String)] = Seq(
    ("M1", Some("mvp-1"), "mvp-1"),
    ("M1rp4", Some("mvp1_rp4"), "mvp-1 で `read_ports` 4"),
    ("S2", None, "Compute スロット2つ（Compute の書戻しポート2、読出し7、演算器を2つずつ）"),
    ("S2a", None, "Compute スロット2つ、書戻しポートと読出しポートは mvp-1 のまま（ループだけ）"),
    ("WR", Some("W-reg"), "W-reg"),
    ("WF", Some("W-full"), "W-full"),
    ("WFa", None, "W-full で LD.W・ST.W を整列した番地に限る"),
    ("WF4", Some("mvp2_rp4"), "W-full で `read_ports` 4"),
    ("P16", None, "2×16 packed（16bit の結果、mod 2^16）")
  )

  /** List scheduling, fewest cycles (vliw): cycles, and #78's lower bound with its binding
    * resources where #78's table gives it (Compute, Memory, WB = the Compute write-back port, rp =
    * the read ports).
    */
  val ListEstimates: Map[(String, String), (Int, Option[(Int, String)])] = Map(
    ("saxpy8", "M1") -> ((27, Some(26 -> "Memory"))),
    ("fir4x8", "M1") -> ((40, Some(33 -> "Compute・WB・rp"))),
    ("matmul3", "M1") -> ((39, Some(28 -> "Compute・Memory・WB・rp"))),
    ("saxpy8", "M1rp4") -> ((26, None)),
    ("fir4x8", "M1rp4") -> ((40, None)),
    ("matmul3", "M1rp4") -> ((33, None)),
    ("saxpy8", "S2") -> ((26, Some(26 -> "Memory"))),
    ("fir4x8", "S2") -> ((28, Some(24 -> "Memory"))),
    ("matmul3", "S2") -> ((28, Some(28 -> "Memory"))),
    ("saxpy8", "WR") -> ((27, Some(26 -> "Memory"))),
    ("fir4x8", "WR") -> ((35, Some(33 -> "Memory"))),
    ("matmul3", "WR") -> ((32, Some(28 -> "Memory"))),
    ("saxpy8", "WF") -> ((15, Some(14 -> "Memory"))),
    ("fir4x8", "WF") -> ((26, Some(19 -> "Memory"))),
    ("matmul3", "WF") -> ((28, Some(22 -> "Memory"))),
    ("saxpy8", "WFa") -> ((19, Some(18 -> "Memory"))),
    ("fir4x8", "WFa") -> ((30, Some(28 -> "Memory"))),
    ("matmul3", "WFa") -> ((30, Some(25 -> "Memory"))),
    ("saxpy8", "WF4") -> ((14, Some(14 -> "Memory"))),
    ("fir4x8", "WF4") -> ((26, Some(19 -> "Memory"))),
    ("matmul3", "WF4") -> ((24, Some(22 -> "Memory"))),
    ("saxpy8", "P16") -> ((15, Some(14 -> "Memory"))),
    ("fir4x8", "P16") -> ((29, Some(22 -> "Compute・WB"))),
    ("matmul3", "P16") -> ((28, Some(19 -> "Compute・Memory・WB・rp")))
  )

  /** The names of #78's table for the bounds of [[bounds]]. */
  val EstimateResource: Seq[(String, String)] =
    Seq("cs" -> "Compute", "ms" -> "Memory", "wbC" -> "WB", "rp" -> "rp")

  /** The loops' MII per original unit, by #78 configuration (M1, S2, S2a, WR, WF, WFa, WF4, P16).
    */
  val LoopConfigs: Seq[String] = Seq("M1", "S2", "S2a", "WR", "WF", "WFa", "WF4", "P16")
  val LoopEstimates: Map[(String, String), Seq[String]] = Map(
    ("saxpy8", "i") -> Seq("3", "3", "3", "3", "1.5", "2", "1.5", "1.5"),
    ("fir4x8", "i") -> Seq("4", "2", "4", "3", "2", "2.5", "2", "2.5"),
    ("matmul3", "i") -> Seq("9", "6", "9", "7", "6", "7", "6", "6"),
    ("matmul3", "m") -> Seq("9", "6", "9", "6", "6", "6", "6", "6")
  )

  /** W-full's MinAvg at the MII with ASAP times (the estimate's definition, docs/COMPILER.md). */
  val MinAvgEstimates: Map[(String, String), Int] = Map(
    ("saxpy8", "i") -> 5,
    ("fir4x8", "i") -> 13,
    ("matmul3", "i") -> 14,
    ("matmul3", "m") -> 11
  )

  /** The reasons of the known differences from the estimates (ADR-0023's stage 4 supplement,
    * docs/COMPILER.md "ISA mvp-2（W）"), given only where a measured value differs.
    */
  val KnownDifferences: Map[(String, String), String] = Map(
    ("fir4x8", "mvp1_rp4") -> "見積りは rematerialization を入れていない（LD の複製）",
    ("fir4x8", "W-full") -> "見積りが数えていない LD.W の rematerialization と、詰めた DAG のノードの番号の付け方（既定）",
    ("fir4x8", "W-reg") -> "詰めた DAG のノードの番号の付け方（既定）と見積りの番号の違い（program-order の試行）",
    ("fir4x8", "mvp2_rp4") -> "見積りが数えていない LD.W の rematerialization と、詰めた DAG のノードの番号の付け方（既定）"
  )

  // Text helpers.

  private def row(cells: Seq[String]) = cells.mkString("| ", " | ", " |")
  private def head(cells: Seq[String]) = s"${row(cells)}\n|${cells.map(_ => "---|").mkString}"
  private def ratio(a: Int, b: Int) = f"${a.toDouble / b}%.2f"
  private def ratioOf(a: Double, b: Double) = f"${a / b}%.2f"
  private def rate(v: ujson.Value): String = v.numOpt.fold("-")(x => f"$x%.3f")
  private def code(s: String) = s"`$s`"

  /** `n` as a count with a half: 3 / 2 = "1.5". */
  def half(n: Int): String = if (n % 2 == 0) (n / 2).toString else s"${n / 2}.5"

  /** Failures by reason, in the order of `KernelCompiler.failureCounts`. */
  def failureCounts(fs: Seq[NoFit]): String = {
    val counts = fs.groupMapReduce(_.name)(_ => 1)(_ + _)
    Seq("TooManyLive", "NoAlignedPair", "Stuck")
      .filter(counts.contains)
      .map(n => s"$n ${counts(n)}")
      .mkString("、")
  }

  private def failed(fs: Seq[(Attempt, NoFit)]) =
    s"不成立（${fs.size} の試行がすべて失敗: ${failureCounts(fs.map(_._2))}）"

  /** An output word of `k` at `addr` in `layout`: `c(i,j)` for a matrix, `out(i)` otherwise. */
  def word(k: Kernel, layout: MemoryLayout, addr: Int): String =
    k.outputs
      .collectFirst {
        case o if addr >= layout.elem(o.name, 0) && addr < layout.elem(o.name, 0) + o.length =>
          val i = addr - layout.elem(o.name, 0)
          val n = math.sqrt(o.length.toDouble).round.toInt
          if (k.name.startsWith("matmul") && n * n == o.length) s"${o.name}(${i / n},${i % n})"
          else s"${o.name}($i)"
      }
      .getOrElse(s"[$addr]")

  /** The row of matrix word `addr` (None for a kernel that is not a matrix product). */
  private def matrixRow(k: Kernel, layout: MemoryLayout, addr: Int): Option[Int] =
    k.outputs.collectFirst {
      case o
          if k.name.startsWith("matmul") && addr >= layout.elem(o.name, 0) &&
            addr < layout.elem(o.name, 0) + o.length =>
        (addr - layout.elem(o.name, 0)) / math.sqrt(o.length.toDouble).round.toInt
    }

  /** The packing of a kernel: pairs (and how many cross a matrix row), scalar lanes with their
    * reasons, broadcasts, gathers and LD copies of `Pack`.
    */
  def packingText(k: Kernel, layout: MemoryLayout, p: Packing): String = {
    val crossing =
      p.pairs.count(q => matrixRow(k, layout, q.addr) != matrixRow(k, layout, q.addr + 1))
    val rows = if (k.name.startsWith("matmul")) s"（行をまたぐ組 $crossing）" else ""
    val lanes =
      if (p.scalarLanes.isEmpty) "スカラーのレーン 0"
      else
        s"スカラーのレーン ${p.scalarLanes.size}: " + p.scalarLanes
          .map(l => s"${word(k, layout, l.addr)}（${l.reason.name}）")
          .mkString("、")
    if (p.nothingToPack) s"`NothingToPack`（$lanes）。スカラーの命令列"
    else
      s"組 ${p.pairs.size}$rows、$lanes、ブロードキャスト ${p.broadcasts}、集め ${p.gathers}" +
        (if (p.copies > 0) s"、`Pack` の LD の複製 ${p.copies}" else "")
  }

  /** Which reading of rule 1 (#110 第4段の Q1) the compiler implements, for list scheduling and for
    * loops: true for (b), the two passes (the gather-free candidates by address, then every
    * candidate by address), false for (a), rule 1 as written (by address alone). It is read from
    * the order in which `Pack.storePairs` tries the candidates of `matmul3`'s fused DAG and
    * `LoopPack.storePairs` those of its loop (m) on W-full, where the two readings differ. An order
    * that is neither is an error, so that the sentences built from it follow the compiler.
    */
  def rule1(s: Series): (Boolean, Boolean) = {
    def twoPasses[A](what: String, order: Vector[A], byAddress: Vector[A], free: A => Boolean) = {
      val two = byAddress.filter(free) ++ byAddress
      require(two != byAddress, s"$what: the two readings of rule 1 try matmul3's stores alike")
      if (order == two) true
      else if (order == byAddress) false
      else sys.error(s"$what: the order of rule 1 is neither (a) nor (b): $order")
    }
    val matmul3 = Benchmarks.all.find(_.name == "matmul3").get
    val dag = s.of("W-full", matmul3, Mode.Vliw).prepared.dag
    val stored = dag.nodes.collect { case Node.Store(a, _) => a }.toSet
    val list = twoPasses[Int](
      "Pack",
      Pack.storePairs(dag),
      stored.toVector.sorted.filter(w => stored(w + 1)),
      Pack.gatherFree(dag, _)
    )
    val acc = loops.find(l => l.name == "matmul3" && l.index == "m").get
    val g = s.loop("W-full", acc).prepared.scalar
    val loop = twoPasses[LoopPack.Candidate](
      "LoopPack",
      LoopPack.storePairs(g),
      LoopPack.byAddress(g),
      LoopPack.gatherFree(g, _)
    )
    (list, loop)
  }

  /** The sentence on rule 1, from [[rule1]]. */
  def rule1Text(s: Series): String = {
    def text(twoPasses: Boolean) =
      if (twoPasses) "2段の規則（#110 第4段の Q1 の (b)。オーナーの答えを待つ推奨で、決定ではない）"
      else "書かれたとおりの1段の規則（#110 第4段の Q1 の (a)）"
    val (list, loop) = rule1(s)
    val which =
      if (list == loop) s"${text(list)}で、list scheduling とループで同じである"
      else s"list scheduling では${text(list)}、ループでは${text(loop)}である"
    s"規則1は$which（`Pack.storePairs` と `LoopPack.storePairs` が `matmul3` とその（m）の候補を試す順から作る）"
  }

  /** The cycles of a fewest-cycles entry, or 不成立 with the failures by reason. */
  private def fewestCell(f: Fewest): String =
    f.result.fold(fs => s"不成立（${failureCounts(fs.map(_._2))}）", r => cyclesCell(r.compiled))

  /** The W outcome of a fewest-cycles entry. */
  private def outcome(f: Fewest): String = f.packing match {
    case None    => "-（W なし）"
    case Some(p) =>
      f.result match {
        case Left(fs) => s"${failed(fs)}。${packingText(f.kernel, f.prepared.layout, p)}"
        case Right(_) => packingText(f.kernel, f.prepared.layout, p)
      }
  }

  /** A fixed-row cell: cycles (bold when chosen; with the LDs when the attempt copied LDs) or the
    * failure.
    */
  private def cell(r: Row, chosen: Option[Attempt]): String =
    r.result.fold(noFit, x => cyclesCell(x.compiled, chosen.contains(x.compiled.allocated.attempt)))

  private def attemptCells(st: Strategy) = Seq(
    st.priority.name,
    st.limit.fold("なし")(_.registers.toString),
    st.limit.fold("-")(_.loadReserve.toString)
  )

  private def names(cs: Seq[Config]) = cs.map(_.name).mkString(" / ")

  // The sections of benchmarks/SIMD.md.

  private def conditions(s: Series): String = {
    val machines = s.configs.map { c =>
      val m = c.machine
      val run =
        if (c.unsupported.isEmpty) "参照モデルと RTL"
        else s"参照モデルと verifier（RTL の範囲外: ${c.unsupported.mkString("; ")}）"
      row(
        Seq(
          c.name,
          code(c.path),
          s"`${m.machineId}` revision ${m.revision}（${m.status}）、`${m.hash}`",
          code(m.isaVersion),
          m.readPorts.toString,
          m.maxWordsPerAccess.toString,
          if (m.hasWideMemory) "あり" else "なし",
          code(c.suffix),
          run
        )
      )
    }
    val r = s.configs.head.machine.registerCount
    s"""## 条件

${head(
        Seq(
          "構成",
          "machine description",
          "machine",
          "ISA",
          "`read_ports`",
          "`max_words_per_access`",
          "LD.W・ST.W",
          "記録の接尾辞",
          "実行"
        )
      )}
${machines.mkString("\n")}

構成ごとに足すもの（[ADR-0022](../docs/DECISIONS.md)「影響」の #78 の増分。書いた値で、測っていない。#57）:

| 構成 | mvp-1 に対して足すもの |
|---|---|
| W-reg | VADD・VSUB・VMUL・VMAC（レーン1 に2つ目の 32×32 の乗算器、32bit の加算器 3 → 6）、RF を2 bank × 8 × 32bit にし bank ごとに 3R/2W（全部で 6R/4W、読出しの mux 約 1,444 → 約 1,510）、書戻しの予約のデータ 32bit → 64bit と組の印 |
| W-full | W-reg に加え、LD.W・ST.W（Scratchpad の bank ごとの行の +1 の回路、64bit の経路、32bit のレーンの入れ替えの mux 2つ） |
| `mvp1_rp4` | mvp-1 の RF の4本目の読出しポート（RTL なし、#57） |
| `mvp2_rp4` | W-full の RF の bank ごとの4本目の読出しポート（RTL なし、#57） |

| 項目 | 値 |
|---|---|
| カーネル | `Benchmarks.all`（`saxpy8`・`fir4x8`・`matmul3`）と `matmul4`（`Benchmarks.matmul(4)`、#78 の質問13 の (b)）。`matmul4` は `Benchmarks.all` に入れず、[RESULTS.md](RESULTS.md)・[MAC.md](MAC.md) には無い。mvp-1 と `mvp1_rp4` の `matmul4` の記録（`_mac`・`_mac_rp4`）は、このファイルの基準である（ADR-0023） |
| 基準 | mvp-1 と `mvp1_rp4` の `saxpy8`・`fir4x8`・`matmul3` とループの記録は、[MAC.md](MAC.md) の記録そのものである（同じオブジェクト。MAC.md と食い違わない） |
| 配置・入力 | `MemoryLayout.fixedOrder`（ループは `LoopLowering.layout`）と `KernelCompiler.sampleInputs(kernel, $Seed)`。期待値は DSL の参照実装。どの構成でも、配置・入力・融合したスカラーの DAG は mvp-1 と同じである（`BenchmarkSpec` が確かめる） |
| レジスタ | どの構成も $r 本。戦略を固定した系列の行（20 の試行）は、どの構成でも同じである |
| コンパイラの試行 | 優先度 ×（上限なし、上限 $r で load reserve 0〜${r / 2}）× 方式。rematerialization は行き詰まる試行だけ（[ADR-0016](../docs/DECISIONS.md)。W-full では LD.W も複製する。集めのレーンの LD は複製しない、#110 第4段の Q5） |
| W の詰め込み | 固定の規則で、戦略の次元にしない（[ADR-0023](../docs/DECISIONS.md)）。W の行は、どれも同じ詰めた DAG（`Prepared.packing`）をスケジュールする。${rule1Text(
        s
      )} |
| ループ | 戦略は1つ。II = MII から順に試し、最初に命令列ができた II を使う（ADR-0015）。融合は `FusionChoice`、W-full のベクトル化（2倍か本体の詰め込み）は固定の規則（ADR-0023） |
| 比較での fallback | W の候補をスカラーの値に置き換えない（#78 の質問10、ADR-0023）。詰めた DAG のすべての試行が失敗した候補は理由付きの「不成立」、詰めるものの無いカーネルとループ（`NothingToPack`）はスカラーの命令列を理由と一緒に測る。W-reg の Modulo Scheduling は「測っていない（#78 の質問8 の (c)）」とする |
| 記録の形式 | 基準は `vliw-metrics/2`、W は `vliw-metrics/3`（[docs/METRICS.md](../docs/METRICS.md)）。W では、操作の数、レジスタの語の数、転送の語の数を分ける（#78 の質問9）。`/2` では、どの操作も1語を動かすので、3つは一致する |
| 実行 | 参照モデル。RTL の範囲内の構成（mvp-1、W-reg、W-full）の記録は RTL でも同じ出力・cycles・トレースになることを、`BenchmarkSpec`（mvp-1 の `matmul4`）と `RtlMvp2Spec`（W-reg と W-full）が確かめる |
| 算術 | W のどのレーンも i32 の演算で、mvp-1 と同じ算術である。#110 の「SIMD でレーンの幅（算術）が変わる場合は、同じ算術のカーネルを作って比べる」には当たらない |"""
  }

  private def chosenOf(s: Series, c: String, k: Kernel, mode: Mode): Option[Attempt] =
    s.of(c, k, mode).result.toOption.map(_.compiled.allocated.attempt)

  private def fewestSection(s: Series): String = {
    val cycles = for (k <- kernels; mode <- Mode.all; c <- s.configs) yield {
      val f = s.of(c.name, k, mode)
      val base = s.of(Baseline.getOrElse(c.name, c.name), k, mode).result.toOption.get.compiled
      f.result match {
        case Right(r) =>
          val x = r.compiled
          row(
            Seq(
              code(k.name),
              mode.name,
              c.name,
              x.cycles.toString,
              ratio(base.cycles, x.cycles),
              s"${loads(x)}（${x.dag.loads}）",
              x.allocated.attempt.toString,
              outcome(f),
              s"`${r.metrics("program")("hash").str.take(19)}…`"
            )
          )
        case Left(_) =>
          row(
            Seq(
              code(k.name),
              mode.name,
              c.name,
              "不成立",
              "-",
              s"-（${f.dag.loads}）",
              "-",
              outcome(f),
              "-"
            )
          )
      }
    }
    val kinds = for (k <- kernels; mode <- Mode.all; c <- s.configs) yield {
      val f = s.of(c.name, k, mode)
      val (dr, dw) = dagWords(f.dag)
      f.result match {
        case Right(r) =>
          val x = r.metrics
          val (oc, om) = operations(x)
          val (wc, wm) = written(x)
          val (cc, cm) = commits(x)
          val (tr, tw) = transfer(x)
          val (or, ow) = transferOperations(x)
          val scalar = byOperation(x)
            .filter { case (o, _) => Set("ADD", "SUB", "MUL", "MAC")(o) }
            .map(_._2)
            .sum
          val vector = byOperation(x).filter(_._1.startsWith("V")).map(_._2).sum
          row(
            Seq(
              code(k.name),
              mode.name,
              c.name,
              s"${oc + om}（Compute $oc、Memory $om）",
              byOperation(x).map { case (o, n) => s"$o $n" }.mkString("、"),
              s"$scalar / $vector",
              s"$wc + $wm（commit $cc + $cm）",
              s"${readRegisters(x)}（欄 ${readFields(x)}）",
              s"$tr + $tw（操作 $or + $ow）",
              s"$dr + $dw"
            )
          )
        case Left(_) =>
          val ops = f.dag.nodes.groupMapReduce(_.mnemonic)(_ => 1)(_ + _)
          val order = Seq(
            "ADD",
            "SUB",
            "MUL",
            "MAC",
            "VADD",
            "VSUB",
            "VMUL",
            "VMAC",
            "LD",
            "ST",
            "LD.W",
            "ST.W"
          )
          row(
            Seq(
              code(k.name),
              mode.name,
              c.name,
              "不成立",
              "詰めた DAG: " + order.filter(ops.contains).map(o => s"$o ${ops(o)}").mkString("、"),
              "-",
              "-",
              "-",
              "-",
              s"$dr + $dw"
            )
          )
      }
    }
    val ports = for (k <- kernels; mode <- Mode.all; c <- s.configs) yield {
      val f = s.of(c.name, k, mode)
      f.result match {
        case Right(r) =>
          val x = r.metrics
          val sch = r.compiled.allocated.schedule
          row(
            Seq(
              code(k.name),
              mode.name,
              c.name,
              rate(x("issue")("compute")("nop_rate")),
              rate(x("issue")("memory")("nop_rate")),
              s"${rate(x("writeback")("compute")("utilization"))} / ${rate(x("writeback")("memory")("utilization"))}",
              sch.heldByWritePort.toString,
              sch.heldByReadPorts.toString,
              rs3WithStore(x).toString,
              byReads(x).mkString(" / "),
              bankWords(x).mkString(" / "),
              rfBanks(x).fold("-")(_.mkString(" / "))
            )
          )
        case Left(_) => row(Seq(code(k.name), mode.name, c.name) ++ Seq.fill(9)("-"))
      }
    }
    val boundRows = for (k <- kernels; c <- s.configs) yield {
      val f = s.of(c.name, k, Mode.Vliw)
      val b = bounds(f.dag)
      val single = s.of(c.name, k, Mode.SingleIssue)
      row(
        Seq(
          code(k.name),
          c.name,
          f.result.fold(_ => "不成立", _.compiled.cycles.toString),
          single.result.fold(_ => "不成立", _.compiled.cycles.toString)
        ) ++ b.values.map(_._2.fold("-")(_.toString)) ++ Seq(
          s"${b.max}（${b.binding.mkString("、")}）",
          (f.dag.nodes.size + 1).toString
        )
      )
    }
    val boundHead = bounds(s.of("W-full", matmul4, Mode.Vliw).dag).values.map(_._1)
    // The words at the DAG level and the program level (the copies of rematerialization).
    val dagLevel = for (k <- kernels; c <- s.configs.filter(_.w)) yield {
      val (w, b) = (
        dagWords(s.of(c.name, k, Mode.Vliw).dag),
        dagWords(s.of(Baseline(c.name), k, Mode.Vliw).dag)
      )
      (k, c, w, b)
    }
    val (same, differ) = dagLevel.partition { case (_, _, w, b) => w == b }
    val sameKernels = kernels.filter(k => same.count(_._1 == k) == s.configs.count(_.w))
    val dagSentence =
      s"カーネルの DAG（スケジュールする DAG、rematerialization の前）の転送の語は、${if (sameKernels.isEmpty) "どのカーネルでも"
        else sameKernels.map(k => code(k.name)).mkString("、") + " では、3つの W の構成で"}基準と同じである" +
        (if (differ.isEmpty) "。"
         else
           "。違うのは " + differ
             .map { case (k, c, w, b) =>
               s"${code(k.name)} の ${c.name}（読む語 ${w._1}、書く語 ${w._2}。${Baseline(c.name)} は ${b._1}、${b._2}）"
             }
             .mkString("、") + "である。")
    val programLevel = for {
      k <- kernels
      mode <- Mode.all
      c <- s.configs.filter(_.w)
      r <- s.of(c.name, k, mode).result.toOption
      b <- s.of(Baseline(c.name), k, mode).result.toOption
    } yield (k, mode, c, transfer(r.metrics), transfer(b.metrics))
    val (pSame, pDiffer) = programLevel.partition { case (_, _, _, w, b) => w == b }
    val programSentence =
      s"命令列の転送の語（rematerialization の複製を含む。LD.W の複製は2語を読む）が基準と同じなのは、記録のある ${programLevel.size} のうち ${pSame.size} で、違うのは " +
        (if (pDiffer.isEmpty) "無い。"
         else
           pDiffer
             .map { case (k, mode, c, w, b) =>
               s"${code(k.name)}（${mode.name}）の ${c.name}（読む語 ${w._1}、${Baseline(c.name)} は ${b._1}）"
             }
             .mkString("、") + "である。")
    s"""## list scheduling: 方式ごとに cycles 最小の系列

各構成で、方式ごとに、全ての試行のうち cycles 最小のもの（同じならば LD の少ない方、それも同じならば試す順で先の試行）。W の構成では、詰めた DAG の試行から選び、どの試行も失敗すれば「不成立」とし、失敗の数を理由ごとに書く。通常の利用（`KernelCompiler.compile`）は、そのときスカラーの命令列に戻るが、その値は W の値として書かない（[ADR-0023](../docs/DECISIONS.md)）。比は、同じ読出しポート数の基準（W-reg と W-full は mvp-1、`mvp2_rp4` は `mvp1_rp4`）の cycles をこの構成の cycles で割った値で、選ばれた試行が違えば試行の違いの効果も含む（同じ試行どうしの比は「戦略を固定した系列」にある）。

### cycles と W の詰め方

${head(
        Seq(
          "カーネル",
          "方式",
          "構成",
          "cycles",
          "基準 / この構成",
          "LD（カーネルの DAG）",
          "試行",
          "W の詰め方",
          "命令列 hash"
        )
      )}
${cycles.mkString("\n")}

LD の欄は LD と LD.W の数で、括弧の中はスケジュールした DAG（rematerialization の前）の数である。W の詰め方は `Pack` の結果で、どの試行にも共通である。スカラーのレーンの理由は、[docs/COMPILER.md](../docs/COMPILER.md)「ISA mvp-2（W）」の名前である。

### 操作の数、レジスタの語の数、転送の語の数

${head(
        Seq(
          "カーネル",
          "方式",
          "構成",
          "操作",
          "操作（mnemonic）",
          "スカラー / ベクトルの演算",
          "書いたレジスタの語（Compute + Memory）",
          "読んだレジスタの語",
          "転送の語（読む + 書く）",
          "転送の語（DAG）"
        )
      )}
${kinds.mkString("\n")}

- 操作は発行した命令の数で、VMAC・VMUL・LD.W・ST.W・レーンの ST・スカラーの操作をそれぞれ1と数える。集めは2つの LD で、移動の命令は無い。HALT は数えない（`issue.<slot>.operations`、`issue.by_operation`）
- 書いたレジスタの語は、組を2、スカラーを1と数える（`writeback.<port>.registers`）。commit は書戻しポートを使った操作の数である（`writeback.<port>.commits`）
- 読んだレジスタの語は、組の欄を2、ブロードキャストとスカラーを1と数える（`issue.register_reads.registers`）。欄は L11 の数え方（`issue.register_reads.reads`）である
- 転送の語は Scratchpad の語の数で、LD.W・ST.W を2と数える（`scratchpad.reads` / `writes`）。操作は LD + LD.W と ST + ST.W の数（`read_operations` / `write_operations`）。DAG の欄は、スケジュールした DAG の LD・LD.W・ST・ST.W・レーンの ST から数えた語である
- 基準（`vliw-metrics/2`）では、どの操作も1語を動かすので、語の数は操作の数である
- $dagSentence
- $programSentence

### スロットとポート

${head(
        Seq(
          "カーネル",
          "方式",
          "構成",
          "Compute NOP率",
          "Memory NOP率",
          "書戻し利用率（Compute / Memory）",
          "`heldByWritePort`",
          "`heldByReadPorts`",
          "(MAC か VMAC) と (ST か ST.W) の bundle",
          "読出しの欄の数ごとの bundle（0 / 1 / …）",
          "Scratchpad の bank ごとの語（0 / 1）",
          "RF の bank ごとに読んだレジスタ（0 / 1）"
        )
      )}
${ports.mkString("\n")}

- `heldByWritePort` と `heldByReadPorts` の定義は [MAC.md](MAC.md) と同じで、W では `heldByReadPorts` が L11 だけで待った ST と ST.W を数える（ADR-0023）
- (MAC か VMAC) と (ST か ST.W) の bundle は、W では `issue.rs3_with_store`、基準では `issue.mac_with_st` である。`read_ports` 3 では常に0
- Scratchpad の bank ごとの語は、各 bank で読み書きした語の数である（DMA との bank のサイクルの共有は #111 で、ここでは測っていない）。RF の bank ごとのレジスタは `vliw-metrics/3` だけにある

### 資源ごとの下限

#109 の最初の完了条件と ADR-0023「記録」の、資源ごとの下限である。スケジュールした DAG（rematerialization の前。不成立の W-reg の行は詰めた DAG）から数え、HALT の1を足した cycles で書く。最大の値と、それに当たる資源（律速）を最後に書く。

${head(
        Seq("カーネル", "構成", "cycles（vliw）", "cycles（single）") ++ boundHead ++ Seq(
          "最大（律速）",
          "single の下限（演算 + HALT）"
        )
      )}
${boundRows.mkString("\n")}

- `dep`: 依存（`Dag.cyclesLowerBound`、クリティカルパス + 1）
- `cs` / `ms`: Compute / Memory の操作の数 + 1（1 bundle に1つずつ）
- `wbC` / `wbM`: Compute の書戻しポートを使う演算 / LD と LD.W の数 + 1（L5、境界ごとに1つ）
- `rp`: ⌈読出しの欄の合計 / `read_ports`⌉ + 1（L11。3本目の読出しポートを欄で数える）
- `MUL`〜`LD.W`: 演算器ごとの数 × II + 1（L9）。「-」はその演算器を使わない
- `bank 0` / `bank 1`: Scratchpad の bank ごとの語の数 + 1（1RW、1サイクルに1語。LD.W・ST.W は2つの bank を1語ずつ使う）。`ms` 以下である
- single の下限は、1サイクルに1操作なので、DAG の操作の数 + HALT である"""
  }

  private def fixedSection(s: Series): String = {
    val strategies = s.strategies
    val tables = for (k <- kernels; mode <- Mode.all) yield {
      val chosen = s.configs.map(c => c.name -> chosenOf(s, c.name, k, mode)).toMap
      def at(c: String, st: Strategy) = s.row(c, k, mode, st)
      def cyc(c: String, st: Strategy) = at(c, st).result.toOption.map(_.compiled.cycles)
      val lines = strategies.map { st =>
        val cells = s.configs.map(c => cell(at(c.name, st), chosen(c.name)))
        val ratios = Pairs.map { case (a, b) =>
          (cyc(a, st), cyc(b, st)) match {
            case (Some(x), Some(y)) => ratio(x, y)
            case _                  => "-"
          }
        }
        row(attemptCells(st) ++ cells ++ ratios)
      }
      val pairs = Pairs.map { case (a, b) =>
        val both = strategies.flatMap(st => cyc(a, st).zip(cyc(b, st)))
        val onlyA = strategies.count(st => cyc(a, st).nonEmpty && cyc(b, st).isEmpty)
        val onlyB = strategies.count(st => cyc(a, st).isEmpty && cyc(b, st).nonEmpty)
        val neither = strategies.count(st => cyc(a, st).isEmpty && cyc(b, st).isEmpty)
        val rs = both.map { case (x, y) => x.toDouble / y }
        val range =
          if (rs.isEmpty) ""
          else s"。比 ${f"${rs.min}%.2f"}〜${f"${rs.max}%.2f"}"
        s"$a / $b: 両方で成立する行 ${both.size}（$b の cycles が少ない ${both.count { case (x, y) => y < x }}、同じ ${both
            .count { case (x, y) => y == x }}、多い ${both.count { case (x, y) => y > x }}$range）、$a だけで成立 $onlyA、$b だけで成立 $onlyB、どちらも不成立 $neither"
      }
      val failures = s.configs.map { c =>
        val fs = strategies.flatMap(st => at(c.name, st).result.left.toOption)
        s"${c.name} ${fs.size}${if (fs.isEmpty) "" else s"（${failureCounts(fs)}）"}"
      }
      val copied = s.configs.map { c =>
        val n = strategies
          .flatMap(st => at(c.name, st).result.toOption)
          .count(x => loads(x.compiled) > x.compiled.dag.loads)
        s"${c.name} $n"
      }
      def each(c: Config, st: Strategy)(f: Ran => String) = at(c.name, st).result.fold(_ => "-", f)
      def joined(st: Strategy)(f: Ran => String) =
        s.configs.map(c => each(c, st)(f)).mkString(" / ")
      val portLines = strategies.map { st =>
        row(
          attemptCells(st) ++ Seq(
            joined(st)(x => rate(x.metrics("issue")("compute")("nop_rate"))),
            joined(st)(x => rate(x.metrics("writeback")("compute")("utilization"))),
            joined(st)(x => rate(x.metrics("writeback")("memory")("utilization"))),
            joined(st)(_.compiled.allocated.schedule.heldByWritePort.toString),
            joined(st)(_.compiled.allocated.schedule.heldByReadPorts.toString),
            joined(st)(x => rs3WithStore(x.metrics).toString)
          ) ++ s.configs.map(c => each(c, st)(x => byReads(x.metrics).mkString(" / ")))
        )
      }
      def nonZero(f: Ran => Int) = s.configs
        .map { c =>
          val ns = strategies.flatMap(st => at(c.name, st).result.toOption).map(f)
          s"${c.name} ${ns.count(_ > 0)}（合計 ${ns.sum}）"
        }
        .mkString("、")
      s"""### `${k.name}`（${mode.name}）

${head(
          Seq("優先度", "レジスタ上限", "load reserve") ++ s.configs.map(_.name) ++ Pairs.map {
            case (a, b) => s"$a / $b"
          }
        )}
${lines.mkString("\n")}

- ${pairs.mkString("\n- ")}
- 不成立の行は ${failures.mkString("、")}。LD を複製した行は ${copied.mkString("、")}

同じ行のスロットとポート（構成の順は ${names(s.configs)}）:

${head(
          Seq(
            "優先度",
            "レジスタ上限",
            "load reserve",
            "Compute NOP率",
            "書戻し Compute 利用率",
            "書戻し Memory 利用率",
            "`heldByWritePort`",
            "`heldByReadPorts`",
            "(MAC か VMAC) と (ST か ST.W) の bundle"
          ) ++ s.configs.map(c => s"読出しの欄の数ごとの bundle（${c.name}）")
        )}
${portLines.mkString("\n")}

- `heldByWritePort` が0でない行: ${nonZero(_.compiled.allocated.schedule.heldByWritePort)}
- `heldByReadPorts` が0でない行: ${nonZero(
          _.compiled.allocated.schedule.heldByReadPorts
        )}。(MAC か VMAC) と (ST か ST.W) の bundle がある行: ${nonZero(x => rs3WithStore(x.metrics))}"""
    }
    val r = s.configs.head.machine.registerCount
    s"""## list scheduling: 戦略を固定した系列

各行は1つの試行（優先度、レジスタ上限、load reserve）で、${s.configs.size}つの構成に同じ組を与えた cycles を並べる。どの構成もレジスタは $r 本なので、行は同じである。行の順はコンパイラが試す順（`RegisterAllocator.attempts`）である。W の構成の各行は、その試行で詰めた DAG をスケジュールした結果で、詰め込みは戦略の次元ではない（どの行も同じ詰めた DAG。ADR-0023）。**太字**は、その構成の「方式ごとに cycles 最小の系列」で選ばれた試行、「（LD n）」は、その試行が LD か LD.W を複製し、DAG より多い n 個を発行することを示す。「不成立」の行は別の試行で置き換えず、スカラーの値も書かない。理由は [RESULTS.md](RESULTS.md) の2つと、W の「cycle c で整列した組が空かない」（`NoAlignedPair`、断片化）である。比は、両方の行が成立するときだけ書く。片方だけが LD を複製する行の比は、複製の違いの効果も含む。どの行の命令列も verifier が合法と判定し、参照モデルで期待した出力と、スケジューラの予測どおりの cycles になることを `BenchmarkSpec` が確かめる。

カーネルと方式ごとに表は2つある。2つ目の表は、1つ目と同じ行を同じ順に並べ、ADR-0023「記録」の行ごとの値（Compute の NOP 率、書戻しポートの使用率、L11 だけで待った ST・ST.W）と、書戻しの衝突、(MAC か VMAC) と (ST か ST.W) の bundle、読出しの欄の数ごとの bundle（HALT の bundle を含み、合計は cycles）を書く。どの値も、その行の命令列を参照モデルで実行した記録（基準は `vliw-metrics/2`、W は `/3`）とスケジュールの値で、行が成立しない構成は「-」である。読出しの欄の合計は、その構成の DAG で決まり、行によらない。

${tables.mkString("\n\n")}"""
  }

  /** The L11 findings and the other findings of the verifier on W-full for a program of mvp2_rp4.
    */
  private def onFull(s: Series, layout: MemoryLayout, words: Vector[Long]) =
    MacRecords.findingsOn(s.config("W-full").machine, layout, words)

  private def readPortSection(s: Series): String = {
    val fewestRows = for (k <- kernels; mode <- Mode.all) yield {
      val (a, b) = (s.of("W-full", k, mode), s.of("mvp2_rp4", k, mode))
      (a.result, b.result) match {
        case (Right(x), Right(y)) =>
          val (l11, other) = onFull(s, y.compiled.layout, y.compiled.words)
          row(
            Seq(
              code(k.name),
              mode.name,
              cyclesCell(x.compiled),
              cyclesCell(y.compiled),
              (x.compiled.cycles - y.compiled.cycles).toString,
              x.compiled.allocated.schedule.heldByReadPorts.toString,
              rs3WithStore(y.metrics).toString,
              s"$l11 / $other",
              s"${x.compiled.allocated.attempt} / ${y.compiled.allocated.attempt}"
            )
          )
        case _ => row(Seq(code(k.name), mode.name, "不成立") ++ Seq.fill(6)("-"))
      }
    }
    val slowerFewest = for {
      k <- kernels
      mode <- Mode.all
      x <- s.of("W-full", k, mode).result.toOption
      y <- s.of("mvp2_rp4", k, mode).result.toOption
      if y.compiled.cycles > x.compiled.cycles
    } yield s"${code(k.name)}（${mode.name}。${x.compiled.cycles} → ${y.compiled.cycles}。`mvp2_rp4` の命令列の (MAC か VMAC) と (ST か ST.W) の bundle ${rs3WithStore(y.metrics)}）"
    val fewestNote =
      if (slowerFewest.isEmpty) "方式ごとに cycles 最小で `mvp2_rp4` の方が遅いカーネルは無い。"
      else
        s"方式ごとに cycles 最小で `mvp2_rp4` の方が遅いのは ${slowerFewest.mkString("、")}である。読出しポートの制約の無い方が遅いので、読出しポートの効果ではなく、選ばれた試行とスケジュールと割り当ての違いの効果である（同じ試行どうしは下の戦略を固定した系列にある）。"
    val strategies = s.strategies
    val tables = kernels.map { k =>
      val lines = strategies.map { st =>
        val (a, b) =
          (s.row("W-full", k, Mode.Vliw, st).result, s.row("mvp2_rp4", k, Mode.Vliw, st).result)
        row(
          attemptCells(st) ++ Seq(
            a.fold(noFit, x => cyclesCell(x.compiled)),
            b.fold(noFit, x => cyclesCell(x.compiled)),
            (a, b) match {
              case (Right(x), Right(y)) => (x.compiled.cycles - y.compiled.cycles).toString
              case _                    => "-"
            },
            a.fold(_ => "-", _.compiled.allocated.schedule.heldByReadPorts.toString),
            b.fold(_ => "-", y => rs3WithStore(y.metrics).toString),
            b.fold(_ => "-", y => onFull(s, y.compiled.layout, y.compiled.words)._1.toString)
          )
        )
      }
      val diffs = strategies.flatMap { st =>
        (
          s.row("W-full", k, Mode.Vliw, st).result,
          s.row("mvp2_rp4", k, Mode.Vliw, st).result
        ) match {
          case (Right(x), Right(y)) => Some(x.compiled.cycles - y.compiled.cycles)
          case _                    => None
        }
      }
      // Rows that fit W-full where mvp2_rp4 is slower or fails: no read-port gain explains them.
      val worse = strategies.flatMap { st =>
        (
          s.row("W-full", k, Mode.Vliw, st).result,
          s.row("mvp2_rp4", k, Mode.Vliw, st).result
        ) match {
          case (Right(x), Right(y)) if y.compiled.cycles > x.compiled.cycles =>
            val l11 = onFull(s, y.compiled.layout, y.compiled.words)._1
            Some(
              s"${st.priority.name}・${st.limit.fold("上限なし")(l => s"load reserve ${l.loadReserve}")} は `mvp2_rp4` の方が遅い（${x.compiled.cycles} → ${y.compiled.cycles}。`mvp2_rp4` の命令列の (MAC か VMAC) と (ST か ST.W) の bundle ${rs3WithStore(y.metrics)}、W-full で検査した L11 の違反 $l11）"
            )
          case (Right(x), Left(no)) =>
            Some(
              s"${st.priority.name}・${st.limit.fold("上限なし")(l => s"load reserve ${l.loadReserve}")} は W-full で ${x.compiled.cycles} cycles、`mvp2_rp4` で${noFit(no)}"
            )
          case _ => None
        }
      }
      val worseNote =
        if (worse.isEmpty) "`mvp2_rp4` の方が遅いか、`mvp2_rp4` だけ不成立の行は無い"
        else
          s"W-full で成立し、`mvp2_rp4` の方が遅いか不成立の行: ${worse.mkString("、")}。読出しポートの制約が無い方が遅いので、この差はスケジュールと割り当ての違いの効果である"
      s"""#### `${k.name}`（vliw）

${head(
          Seq(
            "優先度",
            "レジスタ上限",
            "load reserve",
            "W-full",
            "`mvp2_rp4`",
            "差（W-full − `mvp2_rp4`）",
            "`heldByReadPorts`（W-full）",
            "(MAC か VMAC) と (ST か ST.W) の bundle（`mvp2_rp4`）",
            "L11 の違反（`mvp2_rp4` の命令列を W-full で検査）"
          )
        )}
${lines.mkString("\n")}

- 両方で成立する行 ${diffs.size}: W-full の cycles が多い ${diffs.count(_ > 0)}、同じ ${diffs.count(
          _ == 0
        )}、少ない ${diffs.count(_ < 0)}。片方だけで成立する行 ${strategies.count(st =>
          s.row("W-full", k, Mode.Vliw, st).result.isRight != s
            .row("mvp2_rp4", k, Mode.Vliw, st)
            .result
            .isRight
        )}
- $worseNote"""
    }
    val single = kernels.map { k =>
      val same = strategies.count { st =>
        (
          s.row("W-full", k, Mode.SingleIssue, st).result,
          s.row("mvp2_rp4", k, Mode.SingleIssue, st).result
        ) match {
          case (Right(x), Right(y)) => x.compiled.words == y.compiled.words
          case (Left(x), Left(y))   => x == y
          case _                    => false
        }
      }
      s"`${k.name}` ${strategies.size} 行のうち $same 行"
    }
    val loopRows = loops.map { k =>
      (programOrWhy(s.loop("W-full", k)), programOrWhy(s.loop("mvp2_rp4", k))) match {
        case (Right(a), Right(b)) =>
          val (l11, other) = onFull(s, b.layout, b.words)
          val both = b.bundles.count(isRs3WithStore)
          row(
            Seq(
              ModuloRecords.label(k),
              s"${a.ii} / ${b.ii}",
              s"${a.cycles} / ${b.cycles}",
              (a.cycles - b.cycles).toString,
              both.toString,
              s"$l11 / $other"
            )
          )
        case (a, b) =>
          val rp4 = b.toOption.map { y =>
            val (l11, other) = onFull(s, y.layout, y.words)
            (y.bundles.count(isRs3WithStore).toString, s"$l11 / $other")
          }
          row(
            Seq(
              ModuloRecords.label(k),
              s"${a.fold(identity, _.ii.toString)} / ${b.fold(identity, _.ii.toString)}",
              s"${a.fold(_ => "-", _.cycles.toString)} / ${b.fold(_ => "-", _.cycles.toString)}",
              "-",
              rp4.fold("-")(_._1),
              rp4.fold("-")(_._2)
            )
          )
      }
    }
    s"""## 読出しポートの制約（W-full と `mvp2_rp4`）

W-full と `mvp2_rp4` は `read_ports` だけが違い、同じカーネル・入力・配置・詰めた DAG で比べる（詰め方は読出しポートによらない）。戦略を固定した系列は同じ試行どうしの比較だが、差は L11 の制約（(MAC か VMAC) と (ST か ST.W) を同じ bundle に置けない）の損失だけではなく、それによるスケジュールと割り当ての違い（`NoAlignedPair` を含む）も含み、`mvp2_rp4` の方が遅いこともある。`mvp2_rp4` の命令列を W-full の machine で verifier にかけると、(MAC か VMAC) と (ST か ST.W) の bundle ごとにちょうど1つ L11 の違反になり、ほかの違反は無い（`BenchmarkSpec` が全ての `mvp2_rp4` の命令列で確かめる）。mvp-1 と `mvp1_rp4` の比較は [MAC.md](MAC.md) にある。

### 方式ごとに cycles 最小の系列

${head(
        Seq(
          "カーネル",
          "方式",
          "W-full",
          "`mvp2_rp4`",
          "差（W-full − `mvp2_rp4`）",
          "`heldByReadPorts`（W-full）",
          "(MAC か VMAC) と (ST か ST.W) の bundle（`mvp2_rp4`）",
          "W-full で検査した違反（L11 / ほか）",
          "試行（W-full / `mvp2_rp4`）"
        )
      )}
${fewestRows.mkString("\n")}

$fewestNote

### 戦略を固定した系列

${tables.mkString("\n\n")}

単一発行では、1 bundle に1操作なので読出しの欄は高々3で、制約は当たらない。戦略を固定した系列で、W-full と `mvp2_rp4` の命令列（不成立ならその理由）が同じ行は、${single
        .mkString("、")}である。

### ループ

${head(
        Seq(
          "ループ",
          "II（W-full / `mvp2_rp4`）",
          "cycles（W-full / `mvp2_rp4`）",
          "差（W-full − `mvp2_rp4`）",
          "(MAC か VMAC) と (ST か ST.W) の bundle（`mvp2_rp4`）",
          "W-full で検査した違反（L11 / ほか）"
        )
      )}
${loopRows.mkString("\n")}

不成立のループは、命令列が無いので cycles と違反を書かない。すべての II の結果は「Modulo Scheduling」にある。"""
  }

  /** A loop's program, or why it has none: 不成立 with the IIs tried, or 測っていない. */
  private def programOrWhy(l: LoopEntry): Either[String, ModuloCompiled] = l.result match {
    case LoopResult.Measured(r)       => Right(r.compiled)
    case LoopResult.NoProgram(no)     => Left(noFitText(l, no))
    case LoopResult.Infeasible(_, no) => Left(noFitText(l, no))
    case LoopResult.NotMeasured(_)    => Left(form(l))
  }

  /** A loop that fits no II: its MII (per original iteration) and the IIs tried. */
  private def noFitText(l: LoopEntry, no: NoSchedule): String =
    s"不成立（MII ${no.mii.mii}（元の1反復 ${perOriginal(l, no.mii.mii)}）、II ${no.mii.mii}〜${no.iiMax} のどれにも収まらない）"

  /** The MII of a loop's graph that was scheduled (None when not measured). */
  private def miiOf(l: LoopEntry): Option[Mii] = l.result match {
    case LoopResult.Measured(r)       => Some(r.compiled.result.mii)
    case LoopResult.NoProgram(no)     => Some(no.mii)
    case LoopResult.Infeasible(_, no) => Some(no.mii)
    case LoopResult.NotMeasured(_)    => None
  }

  /** Whether a bundle holds (MAC or VMAC) and (ST or ST.W). */
  def isRs3WithStore(b: Bundle): Boolean =
    (b.compute, b.memory) match {
      case (_: ComputeOp.Mac | _: ComputeOp.VMac, _: MemoryOp.St | _: MemoryOp.StW) => true
      case _                                                                        => false
    }

  /** The unit of one iteration of a loop: 2 original iterations when doubled, else 1. */
  def doubled(l: LoopEntry): Boolean = l.prepared.simd match {
    case LoopSimd.Vectorized(d) => d.doubled
    case _                      => false
  }

  /** An II per original iteration. */
  def perOriginal(l: LoopEntry, ii: Int): String = if (doubled(l)) half(ii) else ii.toString
  private def perOriginalValue(l: LoopEntry, ii: Int): Double =
    if (doubled(l)) ii / 2.0 else ii.toDouble

  /** The form of a loop on a configuration. */
  def form(l: LoopEntry): String = l.result match {
    case LoopResult.NotMeasured(_) => "測っていない（#78 の質問8 の (c)）"
    case _                         =>
      l.prepared.simd match {
        case LoopSimd.Scalar                     => "スカラー（W なし）"
        case LoopSimd.Vectorized(d) if d.doubled => "2倍"
        case LoopSimd.Vectorized(_)              => "本体の詰め込み"
        case LoopSimd.Fallback(r, _)             => s"スカラー（`${r.describe}`）"
      }
  }

  /** The weights live through every iteration: the invariants (Before values that Each nodes read
    * with back 0) and the states.
    */
  def throughLoop(g: LoopGraph): (Int, Int) = {
    def weight(n: Int) = if (LoopNode.isPair(g.nodes(n))) 2 else 1
    val refs = g.ids(Place.Each).flatMap(g.nodes(_).operands)
    val invariants = refs.collect {
      case r if g.places(r.node) == Place.Before && r.back == 0 => r.node
    }.distinct
    // A value read `back` iterations later is held for `back` iterations: a state (back 1), or
    // a LD passed to a later iteration (ADR-0014).
    val carried = refs
      .filter(r => g.places(r.node) == Place.Each && r.back > 0)
      .groupMapReduce(_.node)(_.back)(_ max _)
    (invariants.map(weight).sum, carried.map { case (n, back) => weight(n) * back }.sum)
  }

  /** The fewest values live at once over the IIs that failed on registers ((b)), if any. */
  private def fewestLive(no: NoSchedule): Option[Int] =
    no.attempts
      .collect { case a if a.outcome.isInstanceOf[Outcome.TooManyLive] => a.outcome }
      .collect { case Outcome.TooManyLive(l, _) => l }
      .minOption

  private def allocationOf(c: ModuloCompiled): String = {
    val before = c.result.attempts.filter(a =>
      a.ii < c.ii && (a.outcome match {
        case _: Outcome.TooManyLive | _: Outcome.NoAlignedPair => true
        case _                                                 => false
      })
    )
    if (before.isEmpty) "成功"
    else
      before
        .map(a =>
          a.outcome match {
            case Outcome.TooManyLive(l, _)       => s"II ${a.ii} は `TooManyLive` $l"
            case Outcome.NoAlignedPair(cy, _, _) => s"II ${a.ii} は `NoAlignedPair`（cycle $cy）"
            case other                           => s"II ${a.ii}: $other"
          }
        )
        .mkString("、") + s"、II ${c.ii} で成功"
  }

  private def moduloSection(s: Series): String = {
    val decisions = for (k <- loops; c <- s.configs.filter(_.machine.hasWideMemory)) yield {
      val l = s.loop(c.name, k)
      val decision = l.prepared.simd match {
        case LoopSimd.Vectorized(d)        => d.describe
        case LoopSimd.Fallback(r, Some(d)) => s"${d.describe}; ${r.describe}"
        case LoopSimd.Fallback(r, None)    => r.describe
        case LoopSimd.Scalar               => "-"
      }
      val fits = l.result match {
        case LoopResult.Measured(_)       => "命令列あり"
        case LoopResult.Infeasible(_, no) => s"不成立: II ${no.mii.mii}〜${no.iiMax} のどれにも収まらない"
        case LoopResult.NotMeasured(_)    => "測っていない（#78 の質問8 の (c)）"
        case LoopResult.NoProgram(_)      => "-"
      }
      row(Seq(ModuloRecords.label(k), c.name, form(l), code(decision), fits))
    }
    val results = for (k <- loops; c <- s.configs) yield {
      val l = s.loop(c.name, k)
      val base = s.loop(Baseline.getOrElse(c.name, c.name), k)
      l.result match {
        case LoopResult.Measured(r) =>
          val x = r.compiled
          val mii = x.result.mii
          val ii = int(r.metrics("ii"))
          val baseRatio = base.compiled.fold("-") { b =>
            ratioOf(perOriginalValue(base, b.ii), perOriginalValue(l, ii))
          }
          row(
            Seq(
              ModuloRecords.label(k),
              c.name,
              form(l),
              ModuloRecords.bound(x),
              mii.recMII.toString,
              s"${mii.mii}（${perOriginal(l, mii.mii)}）",
              ii.toString,
              perOriginal(l, ii),
              baseRatio,
              ModuloRecords.reasons(x.result.attempts),
              x.cycles.toString,
              base.compiled.fold("-")(b => ratio(b.cycles, x.cycles)),
              x.minAvgAtMii.toString,
              x.minAvgAtIi.toString,
              x.maxLive.toString,
              allocationOf(x)
            )
          )
        case LoopResult.NoProgram(no) =>
          noRow(k, c, l, no, "不成立（命令列なし）")
        case LoopResult.Infeasible(_, no) =>
          noRow(k, c, l, no, "不成立（`LoopCandidate.Infeasible`）")
        case LoopResult.NotMeasured(_) =>
          row(Seq(ModuloRecords.label(k), c.name, form(l)) ++ Seq.fill(13)("-"))
      }
    }
    val structural = for (k <- matmul4Loops; c <- s.configs) yield {
      val l = s.loop(c.name, k)
      val (inv, carried) = throughLoop(l.prepared.graph)
      // Normal use: the scalar loop G (on W-full after the W graph; on W-reg G alone).
      val g = if (ModuloScheduler.schedule(l.prepared.scalar).isLeft) "収まらない" else "収まる"
      val cells = l.result match {
        case LoopResult.NoProgram(no) =>
          Seq(inv.toString, carried.toString, fewestLive(no).fold("-")(_.toString), "-")
        case LoopResult.Infeasible(_, no) =>
          Seq(inv.toString, carried.toString, fewestLive(no).fold("-")(_.toString), g)
        case LoopResult.NotMeasured(_) => Seq("-", "-", "-", s"$g（W の値ではない）")
        case LoopResult.Measured(r)    =>
          Seq(inv.toString, carried.toString, s"（II ${r.compiled.ii} で収まる）", "-")
      }
      row(Seq(ModuloRecords.label(k), c.name, form(l)) ++ cells)
    }
    val registers = s.configs.head.machine.registerCount
    val resourceRows = for {
      k <- loops
      c <- s.configs
      l = s.loop(c.name, k)
      mii <- miiOf(l)
    } yield {
      def uses(r: String) = mii.resources.find(_.resource == r).fold("-")(_.uses.toString)
      val units = mii.resources
        .filter(_.resource.endsWith(" unit"))
        .map(r => s"${r.resource.stripSuffix(" unit")} ${r.uses}")
      val without = mii.withoutReadPort
      row(
        Seq(
          ModuloRecords.label(k),
          c.name,
          form(l),
          uses("compute slot"),
          uses("memory slot"),
          uses("compute write-back port"),
          uses("memory write-back port"),
          uses(Mii.ReadPort),
          if (units.isEmpty) "-" else units.mkString("、"),
          s"${mii.resMII}（${mii.bounding.map(code).mkString("、")}）",
          s"${without.resMII}（${without.bounding.map(code).mkString("、")}）",
          s"${without.mii}（${perOriginal(l, without.mii)}）"
        )
      )
    }
    def m4(p: LoopResult => Boolean) =
      s.configs.filter(c => matmul4Loops.forall(k => p(s.loop(c.name, k).result)))
    val m4None = m4 {
      case _: LoopResult.NoProgram | _: LoopResult.Infeasible => true
      case _                                                  => false
    }
    val m4NotMeasured = m4(_.isInstanceOf[LoopResult.NotMeasured])
    val m4Others = s.configs.filterNot(c => m4None.contains(c) || m4NotMeasured.contains(c))
    val m4Sentence =
      s"レジスタ $registers 本では、測った構成（${m4None.map(_.name).mkString("、")}）のどれでも、2つのループの命令列ができない" +
        (if (m4NotMeasured.isEmpty) ""
         else s"（${m4NotMeasured.map(_.name).mkString("、")} は測っていない。#78 の質問8 の (c)）") +
        (if (m4Others.isEmpty) ""
         else s"。${m4Others.map(_.name).mkString("、")} では、どちらかのループに命令列がある")
    s"""## Modulo Scheduling

`LoopBenchmarks.all` の6つのループと、`matmul4` の2つのループ（`matmulRows(4)`、`matmulAcc(4)`。#110 第6段の Q1。オーナーが異を唱えなければ既定とする推奨で、決定ではない）を、${s.configs.size}つの構成で。配置と入力は list scheduling と同じである（`BenchmarkSpec` が確かめる）。戦略は1つで、II = MII から順に試し、最初に命令列ができた II を使う（ADR-0015）。ここでの II はループの II で、`metrics` の `ii` に、トレースで確かめた値（`crosscheck/ModuloTrace`）を記録する。`vliw-metrics/3` の `ii` は、スケジュールしたループの反復の開始間隔で、2倍にしたループではベクトルの1反復（元の2反復）の間隔である（[docs/METRICS.md](../docs/METRICS.md)）。比べるときは、元の1反復当たりの II（2倍にしたループでは `ii` / 2、本体を詰めたループとスカラーのループでは `ii`）を使い、`ii` だけを比べない。W-reg の Modulo Scheduling は「測っていない（#78 の質問8 の (c)）」で、値を書かない（`LoopCandidate.NotMeasured`）。

### ループのベクトル化の判定（W-full と `mvp2_rp4`）

${head(Seq("ループ", "構成", "形", "判定（`LoopDecision`）", "W の候補"))}
${decisions.mkString("\n")}

判定の MII（G と、作ったときは2倍にしたグラフ）は、3本目の読出しポートを除いて比べる（`Mii.withoutReadPort`。[ADR-0023](../docs/DECISIONS.md)、[docs/COMPILER.md](../docs/COMPILER.md)）。そのため `read_ports` 3 と 4 で同じ判定になり、下の「ループごとの結果」の MII（3本目の読出しポートを含む）と違うことがある。除いた値は「資源ごとの ResMII」にある。

### ループごとの結果

${head(
        Seq(
          "ループ",
          "構成",
          "形",
          "ResMII（律速の資源）",
          "RecMII",
          "MII（元の1反復）",
          "`ii`",
          "元の1反復当たりの II",
          "元の1反復当たりの II の比（基準 / この構成）",
          "MII を超えた II の理由",
          "cycles",
          "cycles の比（基準 / この構成）",
          "MinAvg（MII、ASAP）",
          "MinAvg（II）",
          "MaxLive",
          "割り当て"
        )
      )}
${results.mkString("\n")}

- 基準は、同じ読出しポート数の基準の構成（W-full は mvp-1、`mvp2_rp4` は `mvp1_rp4`）である。mvp-1 と `mvp1_rp4` の6つのループは [MAC.md](MAC.md) の記録そのもので、この表で MaxLive（同時に生きる値、#110）を足した
- MinAvg と MaxLive は重みで数える（組は2。[docs/COMPILER.md](../docs/COMPILER.md)「記録の定義」）。割り当ては、レジスタ（(b) と (b′)）で II を上げたときの、その II の結果である
- 理由の (a)・(b)・(b′) は [MAC.md](MAC.md) と [docs/COMPILER.md](../docs/COMPILER.md) と同じ。「不成立」の行は、すべての II の結果を書き、cycles を書かない（W の値をスカラーの値で置き換えない）

### 資源ごとの ResMII

スケジュールしたグラフ（W-full と `mvp2_rp4` では W のグラフ、`NothingToPack` ではスカラーのループ）の1反復が、資源ごとに使う回数である（[docs/COMPILER.md](../docs/COMPILER.md)「Modulo Scheduling」。どの資源も1サイクルに1回）。2倍にしたループでは、1反復は元の2反復である。「-」はその資源を使わないか、その構成に無い（3本目の読出しポートは、MAC があり `read_ports` 3 の構成だけの資源である）。演算器は、その演算の数 × II である。W-reg は測っていない（#78 の質問8 の (c)）ので、この表に無い。

${head(
        Seq(
          "ループ",
          "構成",
          "形",
          "Compute のスロット",
          "Memory のスロット",
          "Compute の書戻しポート",
          "Memory の書戻しポート",
          "3本目の読出しポート",
          "演算器",
          "ResMII（律速の資源）",
          "ResMII（3本目の読出しポートを除く）",
          "MII（3本目の読出しポートを除く。元の1反復）"
        )
      )}
${resourceRows.mkString("\n")}

### `matmul4` のループ

$m4Sentence。理由は構造的で、スケジュールしたグラフから数えた、ループの間ずっと生きる値の重み（不変の値はループの前に LD してループの間レジスタに置く（ADR-0014）。Modulo Scheduling は LD を複製しない（ADR-0016））と、レジスタ（(b)）で不成立の II の同時に生きる値を書く。

${head(
        Seq(
          "ループ",
          "構成",
          "形",
          "不変の値の重み",
          "反復をまたいで渡す値の重み",
          "レジスタ（(b)）で不成立の II の、同時に生きる値の最小",
          "通常の利用のスカラーのループ G"
        )
      )}
${structural.mkString("\n")}

- 不変の値は、反復が読むループの前の値（`matmul4`（i）では b の16語）で、ループの間ずっと生きる。反復をまたいで渡す値は、状態（`matmul4`（m）では c の16の累算。本体を詰めたループでは8つのベクトルの状態）と、後の反復が読む LD の値で、渡す反復の数を掛けて数える。重みは組を2と数える
- 通常の利用は、W のグラフが収まらなければスカラーのループ G に戻る（`NoIiFits`）。G も収まらなければ命令列は無い。この欄は情報で、W の値ではない。W-reg は W のグラフを作らず、G だけを通常の利用で試す"""
  }

  private def noRow(k: LoopKernel, c: Config, l: LoopEntry, no: NoSchedule, what: String): String =
    row(
      Seq(
        ModuloRecords.label(k),
        c.name,
        form(l),
        s"${no.mii.resMII}（${no.mii.bounding.map(code).mkString("、")}）",
        no.mii.recMII.toString,
        s"${no.mii.mii}（${perOriginal(l, no.mii.mii)}）",
        "-",
        "-",
        "-",
        s"$what: ${ModuloRecords.reasons(no.attempts)}（II ${no.mii.mii}〜${no.iiMax} のどれにも収まらない）",
        "-",
        "-",
        ModuloScheduler.minAvgAtMii(l.prepared.graph).toString,
        "-",
        "-",
        "どの II でも割り当てられない"
      )
    )

  private def estimatesSection(s: Series): String = {
    val listRows =
      for (
        k <- Benchmarks.all; (e, measured, _) <- EstimateConfigs
        if ListEstimates.contains(k.name -> e)
      ) yield {
        val (cycles, bound) = ListEstimates(k.name -> e)
        val est = s"$cycles${bound.fold("")(b => s"（${b._1} ${b._2}）")}"
        measured match {
          case None =>
            val p16 = if (e == "P16") "。16bit の結果で、同じ算術の基準が無い" else ""
            row(Seq(code(k.name), e, est, s"見積りだけ（実装しない。ADR-0022）$p16", "-", "-"))
          case Some(c) =>
            val f = s.of(c, k, Mode.Vliw)
            val b = bounds(f.dag)
            val plain = s.strategies
              .flatMap(st => s.row(c, k, Mode.Vliw, st).result.toOption)
              .map(_.compiled)
              .filter(x => loads(x) == x.dag.loads)
              .minByOption(_.cycles)
            val binding = EstimateResource
              .filter { case (n, _) => b.binding.contains(n) }
              .map(_._2)
              .mkString("・")
            row(
              Seq(
                code(k.name),
                s"$e（$c）",
                est,
                fewestCell(f),
                plain.fold("-")(p => s"${p.cycles}（${p.allocated.attempt}）"),
                s"${b.max}（$binding）"
              )
            )
        }
      }
    val differ = for {
      k <- Benchmarks.all
      (e, Some(c), _) <- EstimateConfigs
      (cycles, _) <- ListEstimates.get(k.name -> e)
      plain = s.strategies
        .flatMap(st => s.row(c, k, Mode.Vliw, st).result.toOption)
        .map(_.compiled)
        .filter(x => loads(x) == x.dag.loads)
        .minByOption(_.cycles)
      f = s.of(c, k, Mode.Vliw)
      if !plain.exists(_.cycles == cycles) || !f.result.exists(_.compiled.cycles == cycles)
    } yield s"- ${code(k.name)} の ${c}: 見積り $cycles、測定 ${fewestCell(f)}、LD を複製しない行の最小 ${plain.fold("-")(_.cycles.toString)}。${KnownDifferences.getOrElse(k.name -> c, "理由は調べていない")}"
    val m4 = s.configs.map { c =>
      val f = s.of(c.name, matmul4, Mode.Vliw)
      val b = bounds(f.dag)
      val bound = s"下限 ${b.max}、${b.binding.mkString("・")}"
      f.result.fold(
        fs => s"${c.name} 不成立（${failureCounts(fs.map(_._2))}。$bound）",
        r => s"${c.name} ${r.compiled.cycles}（$bound）"
      )
    }
    val loopRows =
      for (
        (key, values) <- LoopEstimates.toSeq
          .sortBy(x => loops.indexWhere(l => (l.name, l.index) == x._1))
      ) yield {
        val k = loops.find(l => (l.name, l.index) == key).get
        def measured(c: String) = {
          val l = s.loop(c, k)
          l.result match {
            case LoopResult.Measured(r)       => perOriginal(l, r.compiled.result.mii.mii)
            case LoopResult.NoProgram(no)     => noFitText(l, no)
            case LoopResult.Infeasible(_, no) => noFitText(l, no)
            case LoopResult.NotMeasured(_)    => form(l)
          }
        }
        val wf = s.loop("W-full", k)
        val wfMinAvg = wf.result match {
          case LoopResult.Measured(r)    => r.compiled.minAvgAtMii.toString
          case LoopResult.NotMeasured(_) => "-"
          case _ => s"${ModuloScheduler.minAvgAtMii(wf.prepared.graph)}（不成立のグラフ）"
        }
        row(
          Seq(
            ModuloRecords.label(k),
            LoopConfigs.zip(values).map { case (cfg, v) => s"$cfg $v" }.mkString("、"),
            s"M1 ${measured("mvp-1")}、WR ${measured("W-reg")}、WF ${measured("W-full")}、WF4 ${measured("mvp2_rp4")}",
            s"${MinAvgEstimates.get(key).fold("-")(_.toString)} / $wfMinAvg"
          )
        )
      }
    s"""## #78 の見積りと測定

見積りは、#78 の提案（2026-10-20 のコメント）のときに、使い捨ての実装（今のコンパイラを移したもの、rematerialization なし、16 レジスタ）で求めた値である。情報で、目標ではない（ADR-0023）。S2・S2a・WFa・P16 は実装しないので見積りだけで（ADR-0022）、P16 は 16bit の結果（mod 2^16）なので、i32 の基準と比べられない（同じ算術の基準が無い。#78）。W は i32 を保つので、同じ算術の基準を作らずに mvp-1 と比べられる。`matmul4` は #78 の後に足したので、見積りが無い。

list scheduling、方式ごとに cycles 最小（vliw）。見積りの括弧は #78 の表の下限と律速の資源で、測定の下限は上の「資源ごとの下限」の最大である。見積りと比べるのは、LD を複製しない行の最小である（MAC.md と同じ）。

${head(Seq("カーネル", "構成", "見積り（下限 律速）", "測定（方式ごとに cycles 最小）", "LD を複製しない行の最小（試行）", "測定の下限（律速）"))}
${listRows.mkString("\n")}

見積りと違う値（理由は [docs/COMPILER.md](../docs/COMPILER.md)「ISA mvp-2（W）」と ADR-0023 の第4段の後半の補足。どれもコンパイラの誤りではない）:

${if (differ.isEmpty) "- 無い" else differ.mkString("\n")}

`matmul4`（見積りなし）: ${m4.mkString("、")}。

ループの MII（元の1単位当たり。`saxpy8` は要素、`fir4x8` は出力、`matmul3`（i）は行、（m）は段）と、W-full の MinAvg（MII、ASAP）:

${head(Seq("ループ", "見積り", "測定", "MinAvg（見積り / 測定、W-full）"))}
${loopRows.mkString("\n")}"""
  }

  private def risksSection(s: Series): String = {
    val strategies = s.strategies
    val matmul3 = Benchmarks.all.find(_.name == "matmul3").get
    def nap(c: String) = for (k <- kernels; mode <- Mode.all) yield {
      val n = strategies.count(st =>
        s.row(c, k, mode, st).result.left.exists(_.isInstanceOf[NoFit.NoAlignedPair])
      )
      (k, mode, n)
    }
    val napText = s.configs.filter(_.w).map { c =>
      val hits = nap(c.name).filter(_._3 > 0)
      s"${c.name} は ${if (hits.isEmpty) "0"
        else hits.map { case (k, mode, n) => s"${code(k.name)}（${mode.name}）$n" }.mkString("、")}"
    }
    val loopNap = s.loops.filter(_.config.w).flatMap { l =>
      val attempts = l.result match {
        case LoopResult.Measured(r)       => r.compiled.result.attempts
        case LoopResult.Infeasible(_, no) => no.attempts
        case _                            => Vector.empty
      }
      Option.when(attempts.exists(_.outcome.isInstanceOf[Outcome.NoAlignedPair]))(
        s"${ModuloRecords.label(l.kernel)}（${l.config.name}）"
      )
    }
    val wreg = kernels.map { k =>
      val fs =
        Mode.all.map(mode => strategies.count(st => s.row("W-reg", k, mode, st).result.isLeft))
      s"${code(k.name)} ${fs.mkString(" / ")}"
    }
    val wregInfeasible =
      for (k <- kernels; mode <- Mode.all; fs <- s.of("W-reg", k, mode).result.left.toOption)
        yield s"${code(k.name)}（${mode.name}。${failureCounts(fs.map(_._2))}）"
    // The longest recorded program of the W configurations, and of the baseline.
    def longest(w: Boolean): (Int, String) =
      (s.fewest
        .filter(_.config.w == w)
        .flatMap(_.result.toOption)
        .map(r => (r.compiled.bundles.size, r.name)) ++ s.loops.filter(_.config.w == w).collect {
        case LoopEntry(_, _, _, _, LoopResult.Measured(r)) => (r.compiled.bundles.size, r.name)
      }).maxBy(_._1)
    val (wLongest, baseLongest) = (longest(true), longest(false))
    val loopsWithAttempts = s.loops.filter(l =>
      l.config.w && (l.result match {
        case _: LoopResult.Measured | _: LoopResult.Infeasible => true
        case _                                                 => false
      })
    )
    val capacity = s.configs.head.machine.instructionMemoryBundles
    val third = s.of("W-full", matmul3, Mode.Vliw)
    val thirdPacking = third.packing.get
    val n3 = math.sqrt(matmul3.outputs.head.length.toDouble).round.toInt
    val thirdColumn =
      (0 until n3).map(i => third.prepared.layout.elem(matmul3.outputs.head.name, i * n3 + n3 - 1))
    val allScalar = thirdColumn.forall(a => thirdPacking.scalarLanes.exists(_.addr == a))
    val thirdText =
      s"W-full の ${code(matmul3.name)} の詰め方は ${packingText(matmul3, third.prepared.layout, thirdPacking)} で、3列目の語 ${thirdColumn
          .map(word(matmul3, third.prepared.layout, _))
          .mkString("、")} は${if (allScalar) "どれもスカラーのレーンである（危険のとおり）"
        else "すべてがスカラーのレーンではない（危険の文と違う）"}。`matmul4` は ${packingText(matmul4, s.of("W-full", matmul4, Mode.Vliw).prepared.layout, s.of("W-full", matmul4, Mode.Vliw).packing.get)} である"
    val nothing = for (k <- LoopBenchmarks.all; c <- Seq("W-full", "mvp2_rp4")) yield {
      val l = s.loop(c, k)
      (l.candidate, l.compiled, s.loop(Baseline(c), k).compiled) match {
        case (Some(_: LoopCandidate.NothingToPack), Some(x), Some(b)) =>
          Some(s"${ModuloRecords.label(k)}（$c）${x.cycles} cycles、${Baseline(c)} ${b.cycles} cycles")
        case _ => None
      }
    }
    s"""## ADR-0022「引き受ける危険」の確認

- 16本のレジスタは8組しかない・割り当てが断片化する: 戦略を固定した系列で `NoAlignedPair` の行は、${napText.mkString(
        "。"
      )}である（不成立の行の数と理由は上の各表）。記録した W のループ（${loopsWithAttempts
        .map(_.config.name)
        .distinct
        .mkString(" と ")} で ${loopsWithAttempts.size}）${if (loopNap.isEmpty)
        "には、II を (b′) で上げたものも、(b′) で不成立の II も無い"
      else
        s"で (b′) の II があったのは ${loopNap.mkString("、")}"}（ほかのループについては [ADR-0023](../docs/DECISIONS.md) の第5段の後半の補足）
- W-reg では集めが要る: W-reg の不成立の行（vliw / single）は ${wreg.mkString("、")}。方式ごとに cycles 最小で不成立なのは ${if (
        wregInfeasible.isEmpty
      ) "無い"
      else
        wregInfeasible.mkString(
          "、"
        )}（集めのレーンの LD は複製しない。#110 第4段の Q5 の既定）。集め全体の複製を足さずに不成立と記録するのは #110 第6段の Q2 で、オーナーが異を唱えなければ既定とする推奨で、決定ではない。W-reg の Modulo Scheduling は測っていない（#78 の質問8 の (c)）
- 2つ目の乗算器と RF の配線の費用: 測っていない（#57）。上の「構成ごとに足すもの」は書いた値である
- DMA（#111）との bank のサイクル: Scratchpad の bank ごとの語を「スロットとポート」に記録した。DMA との共有は測っていない（#111）
- 命令領域（#81）: 記録した命令列の bundle は、W の構成では最大 ${wLongest._1}（`${wLongest._2}`）、基準（mvp-1 と `mvp1_rp4`）では最大 ${baseLongest._1}（`${baseLongest._2}`）である（命令領域 $capacity bundle）
- `matmul3` の3列目はスカラーのまま残る: $thirdText（この文の詰め方は `Pack` の結果から作る）。${rule1Text(s)}
- `dot8`・`product8` は速くならない: ${nothing.flatten.mkString(
        "、"
      )}（`NothingToPack`。スカラーの命令列で、mvp-1 と同じ cycles）"""
  }

  private val notMeasured: String =
    """## 測っていないもの

- Fmax・面積・電力（#57）。cycle の比を実時間の速度比と読まない。この判断は #57 まで暫定である（#78 の質問14）
- `read_ports` 4 の RTL（`mvp1_rp4`・`mvp2_rp4`。#57）。この2つの値は参照モデルと verifier だけで確かめている
- 新しい演算の latency と II を変えた構成（`mvp2_vlat`・`mvp2_vii`）は、テストだけで確かめ、ベンチマークの系列にしない（#78 の質問3。数は [docs/COMPILER.md](../docs/COMPILER.md)）
- レジスタ 8・12 本（比較は #78 の表と同じ16本。mvp-1 の 8・12 本の系列も無い）
- W-reg の Modulo Scheduling（#78 の質問8 の (c)）
- P16・S2・S2a・WFa（実装しない。ADR-0022。見積りだけ）
- 固定パイプライン（W を使わない。ADR-0022）"""

  /** benchmarks/SIMD.md. */
  def report(s: Series): String =
    s"""# W（ISA mvp-2）と mvp-1 の比較

このファイルは `sbt "crosscheck/Test/runMain vliw.crosscheck.BenchmarkRecords"` が生成する。手で編集しない（`crosscheck/BenchmarkSpec` が一致を確かめる）。

**cycle ベースの比較である。** Fmax・面積・電力は未測定で（#57）、時間や電力の主張はしない。cycle の比を実時間の速度比と読まない。W の判断は #57 まで暫定である（#78 の質問14）。#78 の見積りは情報で、目標ではない。

比べる組は、同じ行の mvp-1 と W-reg（演算の幅）、W-reg と W-full（メモリの幅）、mvp-1 と W-full（両方）で（[ADR-0023](../docs/DECISIONS.md)）、読出しポート4本の `mvp1_rp4` と `mvp2_rp4`、W の4本目の読出しポート（W-full と `mvp2_rp4`）も並べる。

${conditions(s)}

${fewestSection(s)}

各記録の全項目は `metrics/<kernel>_<mode><構成>.json`、命令列と入力・期待値は `cases/` の同じ名前にある。構成の接尾辞は W-reg が `_wreg`、W-full が `_w`、`mvp2_rp4` が `_w_rp4` で、W-reg の不成立のカーネルにはファイルが無い。mvp-1 と `mvp1_rp4` の `saxpy8`・`fir4x8`・`matmul3` は MAC.md の記録（`_mac`・`_mac_rp4`）で、`matmul4` の `_mac`・`_mac_rp4` はこのファイルの基準である（`_mac3` は無い）。

${fixedSection(s)}

${readPortSection(s)}

${moduloSection(s)}

ループの記録の全項目は `metrics/<kernel>_<loop>_modulo<構成>.json`（`ii` はトレースで確かめた値）、命令列と入力・期待値は `cases/` の同じ名前にある。W-full が `_w`、`mvp2_rp4` が `_w_rp4` で、`NothingToPack` のループはスカラーの命令列（mvp-1 と同じ）、不成立と測っていないループにはファイルが無い。

${estimatesSection(s)}

${risksSection(s)}

$notMeasured
"""

  /** The record files of the series: the W programs and `matmul4`'s baseline. */
  def recordsOf(s: Series): (Seq[Record], Seq[ModuloRecords.Compared]) = (
    s.fewest.filter(f => f.config.w || f.kernel == matmul4).flatMap(_.result.toOption),
    s.loops.filter(l => l.config.w || matmul4Loops.contains(l.kernel)).collect {
      case LoopEntry(_, _, _, _, LoopResult.Measured(r)) => r
    }
  )

  /** The files of this record under benchmarks/. */
  def files(s: Series): Seq[(Path, String)] = {
    val (list, loopRecords) = recordsOf(s)
    BenchmarkRecords.recordFiles(list) ++ ModuloRecords.recordFiles(loopRecords) :+
      (Dir.resolve("SIMD.md") -> report(s))
  }

  /** Every file that this record may write, from the kernels, loops and configurations alone
    * (without generating): a file under benchmarks/ with another name is not SimdRecords'.
    */
  def names: Set[Path] = {
    val suffixes = Configs.map { case (n, _, s) => (n, s) }
    def ours(config: String) = !MacName.contains(config)
    val list =
      for ((c, s) <- suffixes; k <- kernels if ours(c) || k == matmul4; mode <- Mode.all)
        yield s"${k.name}_${mode.name}$s"
    val loopNames =
      for ((c, s) <- suffixes; k <- loops if ours(c) || matmul4Loops.contains(k))
        yield s"${k.name}_${k.index}_modulo$s"
    (list ++ loopNames)
      .flatMap(n =>
        Seq(Dir.resolve("cases").resolve(s"$n.json"), Dir.resolve("metrics").resolve(s"$n.json"))
      )
      .toSet + Dir.resolve("SIMD.md")
  }

  /** The machine of a configuration name. */
  def machineOf(name: String): MachineDescription =
    MacRecords.machineAt(Configs.find(_._1 == name).get._2)
}
