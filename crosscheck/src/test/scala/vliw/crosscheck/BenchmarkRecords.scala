package vliw.crosscheck

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import vliw.compiler.{CompiledKernel, KernelCompiler}
import vliw.compiler.dsl.{Benchmarks, Kernel}
import vliw.compiler.regalloc.{Attempt, NoFit, RegisterAllocator}
import vliw.compiler.schedule.{ListScheduler, Priority}
import vliw.hw.{FixedOp, Toolchain}
import vliw.isa.{MachineDescription, Slot, TestCase}
import vliw.sim.{Metrics, ReferenceModel}

/** The benchmark records in benchmarks/ (benchmarks/README.md), in two series:
  *   - the fewest cycles per mode: for each kernel and each issue mode, the compiler's choice among
  *     its attempts, with a test case and a vliw-metrics/1 record;
  *   - fixed strategies (#79): every strategy of the compiler (priority, register limit, load
  *     reserve) given to both modes, so that a ratio compares the modes under the same strategy.
  *
  * Everything is computed from the sources; BenchmarkSpec checks that the committed files match.
  *
  * `sbt "crosscheck/Test/runMain vliw.crosscheck.BenchmarkRecords"` rewrites the files.
  */
object BenchmarkRecords {
  val Seed = 1L
  val Dir: Path = Paths.get("benchmarks")

  sealed abstract class Mode(val name: String, val singleIssue: Boolean, val label: String)
  object Mode {
    case object Vliw extends Mode("vliw", false, "VLIW（Compute 1 + Memory 1）")
    case object SingleIssue extends Mode("single", true, "単一発行（1サイクル1操作）")
    val all: Seq[Mode] = Seq(Vliw, SingleIssue)
  }

  final case class Record(
      kernel: Kernel,
      mode: Mode,
      compiled: CompiledKernel,
      testCase: ujson.Obj,
      metrics: ujson.Obj,
      suffix: String = ""
  ) {
    def name: String = s"${kernel.name}_${mode.name}$suffix"
  }

  /** A strategy of the compiler without the issue mode: what the fixed series holds constant. */
  final case class Strategy(priority: Priority, limit: Option[ListScheduler.Limit]) {
    def attempt(mode: Mode): Attempt = Attempt(priority, limit, mode.singleIssue)
  }

  /** The strategies of `RegisterAllocator.attempts`, in its order, which also breaks ties: each
    * priority without a register limit, then each priority with the machine's registers as the
    * limit and each load reserve.
    */
  def strategies(m: MachineDescription): Seq[Strategy] = {
    val r = m.registerCount
    Priority.all.map(Strategy(_, None)) ++
      (for (p <- Priority.all; reserve <- 0 to r / 2)
        yield Strategy(p, Some(ListScheduler.Limit(r, reserve))))
  }

  /** Whether both modes of `kernel` chose the same strategy in the fewest-cycles series. */
  def sameStrategy(rs: Seq[Record], kernel: Kernel): Boolean =
    rs.filter(_.kernel == kernel)
      .map(r => r.compiled.allocated.attempt.copy(singleIssue = false))
      .distinct
      .size == 1

  /** One strategy in one mode: the compiled kernel, or why the strategy does not fit the machine.
    */
  final case class Fixed(
      kernel: Kernel,
      strategy: Strategy,
      mode: Mode,
      result: Either[NoFit, CompiledKernel]
  )

  def machine: MachineDescription =
    MachineDescription.load().fold(es => sys.error(es.mkString("\n")), identity)

  /** The LDs of a program: more than its kernel's DAG has when the attempt copied LDs (#70). */
  def loads(c: CompiledKernel): Int = c.allocated.dag.loads

  /** The cycles of `c` (bold when chosen), with its LDs when the attempt copied LDs (#70). */
  def cyclesCell(c: CompiledKernel, bold: Boolean = false): String = {
    val cycles = if (bold) s"**${c.cycles}**" else c.cycles.toString
    if (loads(c) > c.dag.loads) s"$cycles（LD ${loads(c)}）" else cycles
  }

  def noFit(f: NoFit): String = f match {
    case NoFit.TooManyLive(live, registers) => s"不成立: 同時に生きる値 $live > $registers"
    case NoFit.Stuck                        => "不成立: 上限の下で行き詰まる"
    // ISA mvp-2 only (#110); the records of mvp-0 and mvp-1 never have it.
    case NoFit.NoAlignedPair(cycle, live, registers) =>
      s"不成立: cycle $cycle で整列した組が空かない（生きているレジスタ $live ≤ $registers）"
  }

  /** The fewest-cycles series on `m`. `machinePath` goes into the test cases (None for the default
    * machine), and `suffix` ends the record names.
    */
  def records(
      m: MachineDescription = machine,
      machinePath: Option[String] = None,
      suffix: String = ""
  ): Seq[Record] =
    for (k <- Benchmarks.all; mode <- Mode.all) yield {
      val c = KernelCompiler.compile(k, m, singleIssue = mode.singleIssue).fold(sys.error, identity)
      val name = s"${k.name}_${mode.name}$suffix"
      val tc = KernelCompiler
        .testCase(c, KernelCompiler.sampleInputs(k, Seed), machinePath, Some(name))
        .fold(sys.error, identity)
      val loaded =
        TestCase.parse(ujson.write(tc)).fold(es => sys.error(es.mkString("\n")), identity)
      val run =
        ReferenceModel.run(m, c.words, loaded.inputs).fold(v => sys.error(v.toString), identity)
      Record(k, mode, c, tc, Metrics.of(m, c.words, run, name), suffix)
    }

  /** The fixed-strategy series: every strategy for every kernel, in both modes. */
  def fixed(m: MachineDescription = machine): Seq[Fixed] =
    for {
      k <- Benchmarks.all
      p = KernelCompiler.prepare(k, m).fold(sys.error, identity)
      s <- strategies(m)
      mode <- Mode.all
    } yield {
      // Code that the verifier rejects is a compiler error, never "does not fit".
      val result = RegisterAllocator
        .run(p.dag, s.attempt(mode))
        .map(a => KernelCompiler.finish(p, a).fold(sys.error, identity))
      Fixed(k, s, mode, result)
    }

  /** The fixed pipelines of one kernel (#75): its VLIW program burnt in, and the planner's plan
    * without a register limit, next to the kernel's two records.
    */
  final case class FixedPipelineRecord(
      vliw: Record,
      single: Record,
      burntIn: FixedPipelines.Planned,
      planned: FixedPipelines.Planned
  ) {
    def kernel: Kernel = vliw.kernel
    def bound: (Int, String) = FixedPipelines.lowerBound(planned.prepared.dag)
    def circuits: Seq[(String, FixedPipelines.Planned)] =
      Seq("VLIW の命令列" -> burntIn, "上限なし" -> planned)
  }

  def fixedPipelines(rs: Seq[Record], m: MachineDescription = machine): Seq[FixedPipelineRecord] =
    Benchmarks.all.map { k =>
      def of(mode: Mode) = rs.find(r => r.kernel == k && r.mode == mode).get
      val vliw = of(Mode.Vliw)
      FixedPipelineRecord(
        vliw,
        of(Mode.SingleIssue),
        FixedPipelines.burntIn(vliw.compiled),
        FixedPipelines.planned(k, m).fold(sys.error, identity)
      )
    }

  /** The versions of the tools that build and measure the fixed pipelines under ChiselSim: Chisel,
    * firtool and Scala from the build, Verilator and the JDK as pinned in
    * `project/toolchain.properties` (ADR-0020). A run that expects another Verilator
    * (`VLIW_VERILATOR_EXPECTED`) names the pinned one too, so that benchmarks/ does not change with
    * it (R2). `BenchmarkSpec` checks that the environment that measures the fixed pipelines has the
    * expected versions.
    */
  def tools: Seq[(String, String)] = Seq(
    "Chisel" -> chisel3.BuildInfo.version,
    "firtool" -> chisel3.BuildInfo.firtoolVersion.getOrElse("unknown"),
    "Verilator" -> Toolchain.pinnedVerilator,
    "Scala" -> scala.util.Properties.versionNumberString,
    "JDK" -> Toolchain.jdkMajor
  )

  /** The fixed-pipeline section of RESULTS.md (#75). */
  private def fixedPipelineSection(fx: Seq[FixedPipelineRecord], m: MachineDescription): String = {
    val cycles = fx.map { f =>
      val (bound, reason) = f.bound
      Seq(
        s"`${f.kernel.name}`",
        f.single.compiled.cycles.toString,
        f.vliw.compiled.cycles.toString,
        f.burntIn.cycles.toString,
        f.planned.cycles.toString,
        s"$bound（$reason）"
      ).mkString("| ", " | ", " |")
    }
    val params = for (f <- fx; (series, c) <- f.circuits) yield {
      val p = c.params
      def reg(v: Int) = p.storage(v).get.register
      def inputs(rs: Seq[Int]) = rs.distinct.size.toString
      val ops = c.plan.ops
      val both = p.storage.flatten.groupBy(_.register).count(_._2.map(_.port).distinct.size == 2)
      Seq(
        s"`${f.kernel.name}`",
        series,
        c.strategy.toString,
        p.completion.toString,
        s"${p.registers}（Compute ${p.registersOf(Slot.Compute)} / Memory ${p.registersOf(Slot.Memory)} / 両方 $both）",
        Seq(
          inputs(ops.collect { case x: FixedOp.Compute => reg(x.a) }),
          inputs(ops.collect { case x: FixedOp.Compute => reg(x.b) }),
          inputs(ops.collect { case x: FixedOp.Store => reg(x.value) })
        ).mkString(" / "),
        s"${p.counterBits} bit",
        p.units.map(u => s"$u（L${m.operation(u).latency}）").mkString("、")
      ).mkString("| ", " | ", " |")
    }
    val lat =
      Seq("ADD", "SUB", "MUL", "LD", "ST").map(o => s"$o ${m.operation(o).latency}").mkString("、")
    val ii = Seq("ADD", "SUB", "MUL", "LD", "ST").map(o => m.operation(o).ii).distinct.mkString("、")
    s"""## 資源条件を揃えた固定パイプライン（#75）

命令メモリ・命令制御・RF を持たない、カーネル専用の回路（`vliw.hw.FixedPipeline`）の core latency。cycles は VLIW の記録と同じく start の受理（E_0）から done までで、ホストによる初期配置と完了後の読出しを含めない。ChiselSim で `FixedDriver`（`CoreDriver` と同じ数え方）が測り、`BenchmarkSpec` が上の記録と同じ入力で cycles と出力を確かめる。**cycle の比を実時間の速度比と呼ばない**（Fmax・面積は未測定。#80）。

| カーネル | single | vliw | 固定: VLIW の命令列 | 固定: 上限なし | 下限（固定） |
|---|---|---|---|---|---|
${cycles.mkString("\n")}

| 系列 | スケジュール | 値の置き場 |
|---|---|---|
| 固定: VLIW の命令列 | 上の vliw の行の命令列と同じ発行サイクル（同じ試行、同じ DAG） | VLIW のレジスタ割当て（${m.registerCount} 本以内）。両方の書戻しポートから書く語には 2:1 の mux |
| 固定: 上限なし | VLIW と同じ `ListScheduler` を、同じ DAG に、レジスタの上限なしで、両方の優先度で（cycles 最小、同じならば値レジスタ最少、それも同じならば先の優先度）。VLIW との違いは RF の容量だけである | 寿命が重ならない値で共有（書戻しポートごとの left-edge。書込みの mux なし）。値レジスタは ${m.registerCount} 本を超えてよい（**差**） |

下限は、どの合法な計画にも成り立つ値で、理由を添える。memory: Memory の操作は1サイクルに1つ。compute: 最初の演算は LD の値だけを読み（2語を読むならば 1 + L(LD) 以降）、演算は1サイクルに1つ、最後の演算の後に ST が続く。L5: 最初になれる演算がすべてレイテンシー l+1、最後になれる演算がすべて l ならば、どこかで l+1 の演算の次に l の演算が来て、その2つを続くサイクルに置くと同じ境界に書き戻すので、Compute が1サイクル空く。cycles は、最後の書込みの境界 T に1を足した値である（下の「終了」）。

### 揃えた条件と、揃えられない差

| 項目 | VLIW（`Core`） | 固定パイプライン（`FixedPipeline`） |
|---|---|---|
| 算術・カーネル・入力 | 32bit、2^32 を法とする。`Benchmarks` の3カーネル | 同じ。同じ `ComputeSlot` |
| 配置 | `MemoryLayout.fixedOrder` | 同じ。入力・scalar・定数（FIR の係数を含む）を、layout のアドレスから LD で読む。アドレスは回路の定数 |
| Scratchpad | ${m.banks} bank × ${m.wordsPerBank} 語、1RW、1サイクル1アクセス | 同じ `MemorySlot` |
| 演算器 | ADD・SUB・MUL、レイテンシー ${lat}、II $ii。Compute の発行は1サイクルに1つ、書戻しは境界ごとに1つ（L5） | 同じ `ComputeSlot`（発行1つ、書戻し1つ）。計画が使わない種類（SUB）の演算器も slot の中にあるが、kind の decode がそれを選ばない（合成で消えるかは #80） |
| MUL→ADD の連結 | なし | なし。値はいつもレジスタを経る（MAC は #77、#80） |
| 中間値（**差**） | ${m.registerCount} 本の RF（3R/2W） | 値のレジスタ。読む側の mux は、そのポートが読むレジスタだけを入力にする |
| 制御（**差**） | 命令メモリ ${m.instructionMemoryBundles} bundle、PC、decoder、HALT | サイクルカウンタと、その値の decode。選択・有効・アドレスはすべてサイクルの定数 |
| 終了 | HALT を h（全ての操作が完了する境界、L6）に発行し、E_(h+1) で done（h+1 cycles） | HALT の命令はない。最後の書込みの境界 E_T の cycle T を HALT の cycle とみなし、E_(T+1) で done（T+1 cycles）。同じスケジュールは VLIW と同じ cycles になる。E_T で done を上げれば1サイクル少ない（HALT の bundle の分。この表では使わない） |
| 違反の扱い | L1〜L7 を信号にし、assertion にする | 計画を組み立て時に検査する（`FixedPlan.violations`: スロット、L3、L5、L7、L9。L4 にあたる値のレジスタの重なりは `FixedPlan.storageViolations`。命令列がないので L1・L2・L6 は対象外）。シミュレーションでは書戻しと範囲の assertion |
| 対応する machine | `CoreParams.check` の範囲 | 同じ範囲を同じ理由で拒否する |

### 回路の固定の遅延と II の契約

- 開始: idle 中だけ start を受理し、受理の境界を E_0 とする。busy 中の start は無視する。
- 遅延: 1回の実行は T+1 cycles（上の表）で、入力値によらない。busy は cycle 0〜T に high、done は cycle T+1 に1サイクルだけ high になり、そのとき出力は Scratchpad にある。
- 操作: サイクル s に発行した LD と演算は E_(s+L) でレジスタに書き、ST は E_(s+1) で Scratchpad に書く（L は machine description の値）。
- II: 実行が終わるまで次の start を受けない。次の start を cycle T+1（done の cycle）に出すと E_(T+2) で受理するので、受理から次の受理まで最短で T+2 cycles（Core と同じ）。これはホストが入出力をしない場合で、ホストは idle の間（cycle T+1 以降）だけ Scratchpad を読み書きできるので、入力を書き出力を読むときは、その cycles が加わる。入出力のストリーミングを含む throughput・II は測っていない。

### 係数可変

FIR の係数は layout の定数の語（`fir4x8` ではアドレス ${fx
        .find(_.kernel.name == "fir4x8")
        .map(f => f.planned.prepared.layout.constants.values.toSeq.sorted.mkString("、"))
        .getOrElse("-")}）から読み、回路は係数の値を持たない。互いに異なる4つの係数ならば、layout と DAG は (1,2,3,4) と同じになり、同じ回路になる。等しい係数を含む組（例 (2,2,3,3)）では、`MemoryLayout` が同じ値の定数を1語にまとめるので、配置（出力のアドレスを含む）と DAG が変わり、別の layout・別の回路として扱う。(1,2,3,4) の回路に、その4つの値を係数の4語として書けば、出力は DSL の参照実装と一致する。どちらも `FixedPipelineKernelSpec` が確かめる。

### RTL のパラメータ

| カーネル | 系列 | 計画 | T（最後の書込み） | 値レジスタ（32bit） | operand mux の入力（A / B / ST） | カウンタ | 演算器 |
|---|---|---|---|---|---|---|---|
${params.mkString("\n")}

### 条件の出典

| 項目 | 値 |
|---|---|
| machine | `${m.machineId}` revision ${m.revision}、`${m.hash}` |
| ツール | ${tools.map { case (t, v) => s"$t $v" }.mkString("、")} |
| 計測 | ChiselSim（Verilator、`VliwChiselSim`）。`FixedDriver` が start から done までを数える |
"""
  }

  private def rate(v: ujson.Value): String = v.numOpt.fold("-")(x => f"$x%.3f")
  private def int(v: ujson.Value): String = v.num.toLong.toString

  /** One table per kernel: a row per strategy, the cycles of both modes and their ratio. Bold
    * cycles are the attempts that the fewest-cycles series chose.
    */
  private def fixedTables(rs: Seq[Record], fs: Seq[Fixed]): String =
    Benchmarks.all
      .map { k =>
        val chosen = rs.filter(_.kernel == k).map(_.compiled.allocated.attempt).toSet
        val rows = fs.filter(_.kernel == k).groupBy(_.strategy).toSeq.sortBy { case (s, _) =>
          fs.indexWhere(_.strategy == s)
        }
        val lines = rows.map { case (s, both) =>
          def in(mode: Mode) = both.find(_.mode == mode).get.result
          def cycles(mode: Mode) =
            in(mode).fold(noFit, c => cyclesCell(c, chosen(c.allocated.attempt)))
          val ratio = (in(Mode.Vliw), in(Mode.SingleIssue)) match {
            case (Right(v), Right(s1)) => f"${s1.cycles.toDouble / v.cycles}%.2f"
            case _                     => "-"
          }
          Seq(
            s.priority.name,
            s.limit.fold("なし")(_.registers.toString),
            s.limit.fold("-")(_.loadReserve.toString),
            cycles(Mode.Vliw),
            cycles(Mode.SingleIssue),
            ratio
          ).mkString("| ", " | ", " |")
        }
        s"""### `${k.name}`

| 優先度 | レジスタ上限 | load reserve | vliw の cycles | single の cycles | single / vliw |
|---|---|---|---|---|---|
${lines.mkString("\n")}"""
      }
      .mkString("\n\n")

  /** The summary table (benchmarks/RESULTS.md); cycle-based only, no time or power. */
  def results(
      rs: Seq[Record],
      fs: Seq[Fixed],
      m: MachineDescription,
      fx: Seq[FixedPipelineRecord]
  ): String = {
    val rows = rs.map { r =>
      val x = r.metrics
      val ops = x("issue")("by_operation")
      val opCount = Seq("ADD", "SUB", "MUL", "LD", "ST").map(o => ops(o).num.toLong).sum
      val vliw = rs.find(o => o.kernel == r.kernel && o.mode == Mode.Vliw).get.compiled.cycles
      Seq(
        s"`${r.kernel.name}`",
        r.mode.name,
        int(x("cycles")),
        f"${r.compiled.cycles.toDouble / vliw}%.2f",
        opCount.toString,
        s"${r.compiled.dag.cyclesLowerBound} / ${r.compiled.dag.resourceLowerBound}",
        rate(x("issue")("compute")("nop_rate")),
        rate(x("issue")("memory")("nop_rate")),
        rate(x("writeback")("compute")("utilization")),
        rate(x("writeback")("memory")("utilization")),
        x("scratchpad")("banks").arr.map(b => rate(b("accesses_per_cycle"))).mkString(" / "),
        (r.compiled.allocated.allocation.register.values.max + 1).toString,
        s"`${x("program")("hash").str.take(19)}…`"
      ).mkString("| ", " | ", " |")
    }
    val limits = s"上限なし、上限 ${m.registerCount}（レジスタ数）で load reserve 0〜${m.registerCount / 2}"
    s"""# ベンチマーク結果

このファイルは `sbt "crosscheck/Test/runMain vliw.crosscheck.BenchmarkRecords"` が生成する。手で編集しない（`crosscheck/BenchmarkSpec` が一致を確かめる）。

**cycle ベースの比較である。** Fmax・面積・電力は未測定で（#57）、時間や電力の主張はしない。cycle の比を実時間の速度比と読まない。

## 条件

| 項目 | 値 |
|---|---|
| machine | `${m.machineId}` revision ${m.revision}、`${m.hash}` |
| ISA | `${m.isaVersion}` |
| カーネル | `vliw.compiler.dsl.Benchmarks`（[docs/DSL.md](../docs/DSL.md)） |
| 配置 | `MemoryLayout.fixedOrder` |
| コンパイラの試行 | 優先度（critical-path、program-order）×（$limits）（[docs/COMPILER.md](../docs/COMPILER.md)） |
| rematerialization | 上限付きの試行が、どの語も1回だけ読むままでは行き詰まるときだけ、入力・スカラー・定数の LD を複製する（[ADR-0016](../docs/DECISIONS.md#adr-0016-rematerialization-は行き詰まる試行でだけ書込みのない語の-ld-をレジスタの詰まりで複製する)、#70）。複製した行は cycles に LD の数を添える |
| 入力 | `KernelCompiler.sampleInputs(kernel, $Seed)`。結果と cycles は入力値によらない |
| 実行 | 参照モデル（`vliw.sim.ReferenceModel`）。RTL でも同じ cycles と出力になることを `BenchmarkSpec` が確かめる |
| 方式 | `vliw`: 1 bundle に Compute 1・Memory 1。`single`: 同じ machine・カーネルの DAG・配置で、1サイクルに1操作だけ発行する（単一発行ベースライン）。LD を複製した試行では、スケジュールする DAG が方式ごとに違うことがある |

記録は2つの系列からなる。比べるときは、どちらの系列か、各行の試行を併記する（[README.md](README.md)「比較の書き方」）。

| 系列 | 試行 | 何を比べられるか |
|---|---|---|
| 方式ごとに cycles 最小 | 方式ごとに、全ての試行のうち cycles 最小のもの（同じならば LD の少ない方、それも同じならば下の表の行の順で先の試行） | 各方式で今のコンパイラが出せる最良の cycles。選ばれた試行が方式ごとに違うことがあり、そのカーネルの比は発行幅だけの効果ではない |
| 戦略を固定 | 全ての試行を、両方の方式に同じ組で与える | 同じ行の比は、両方の方式が LD を複製しない行では、発行幅だけを変えた効果。片方の方式だけが複製する行や、複製した LD の数が方式で違う行（「（LD n）」の行）の比は、複製の違いの効果も含む |

## 方式ごとに cycles 最小の系列

| カーネル | 方式 | cycles | cycles の vliw に対する比 | 演算数 | 下限（依存 / スロット） | Compute NOP率 | Memory NOP率 | 書戻し Compute 利用率 | 書戻し Memory 利用率 | bank アクセス/cycle | レジスタ | 命令列 hash |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
${rows.mkString("\n")}

演算数は ADD・SUB・MUL・LD・ST の合計で、HALT を含まない。単一発行の cycles は「演算数 + HALT」以上になる。

選ばれた試行（両方式で違うカーネルでは、上の比は試行の違いの効果も含む）:

| カーネル | 方式 | 試行 | 両方式で同じ試行か |
|---|---|---|---|
${rs.map { r =>
        val same = if (sameStrategy(rs, r.kernel)) "同じ" else "違う"
        s"| `${r.kernel.name}` | ${r.mode.name} | ${r.compiled.allocated.attempt} | $same |"
      }.mkString("\n")}

各記録の全項目は `metrics/<kernel>_<mode>.json`（`vliw-metrics/1`、[docs/METRICS.md](../docs/METRICS.md)）、命令列と入力・期待値は `cases/<kernel>_<mode>.json`（`vliw-testcase/1`）にある。

## 戦略を固定した系列

各行は1つの試行（優先度、レジスタ上限、load reserve）で、両方の方式に同じ組を与えた cycles を並べる。行の順はコンパイラが試す順（`RegisterAllocator.attempts`）で、cycles と LD の数が同じならば先の行の試行が選ばれる。**太字**は、上の「方式ごとに cycles 最小の系列」で選ばれた試行である。「（LD n）」は、その試行が LD を複製し、カーネルの DAG より多い n 個の LD を発行することを示す（#70）。「不成立」は、その試行ではレジスタに収まらないことを示し、別の試行で置き換えない（spill はしない）。理由は2つある。「同時に生きる値 n > r」は、上限なしのスケジュールが同時に n 個の値を生かし、レジスタ r 本に割り当てられないこと。「上限の下で行き詰まる」は、上限付きのスケジューラが、LD を複製しても、残りのどの操作も上限を超えずに置けない状態になること。どの行の命令列も verifier が合法と判定し、参照モデルで同じ出力と cycles になることを `BenchmarkSpec` が確かめる（RTL での確認は、上の系列の記録だけ）。

${fixedTables(rs, fs)}

${fixedPipelineSection(fx, m)}"""
  }

  /** The test case and the metrics of each record. */
  def recordFiles(rs: Seq[Record]): Seq[(Path, String)] = rs.flatMap { r =>
    Seq(
      Dir.resolve("cases").resolve(s"${r.name}.json") -> (ujson
        .write(r.testCase, indent = 2) + "\n"),
      Dir.resolve("metrics").resolve(s"${r.name}.json") -> (ujson
        .write(r.metrics, indent = 2) + "\n")
    )
  }

  /** Every file under benchmarks/ that this generates, with its content (with ModuloRecords,
    * RegisterRecords and MacRecords).
    */
  def files(
      rs: Seq[Record] = records(),
      fs: Seq[Fixed] = fixed(),
      m: MachineDescription = machine,
      ms: Option[Seq[ModuloRecords.Compared]] = None,
      regs: Option[Seq[RegisterRecords.Series]] = None,
      fx: Option[Seq[FixedPipelineRecord]] = None,
      mac: Option[Seq[MacRecords.Series]] = None
  ): Seq[(Path, String)] = {
    val loops = ms.getOrElse(ModuloRecords.records(m))
    ModuloRecords.files(loops, rs, fs, m) ++
      RegisterRecords.files(regs.getOrElse(RegisterRecords.series(rs, fs, m))) ++
      MacRecords.files(mac.getOrElse(MacRecords.series(rs, fs, loops, m))) ++
      recordFiles(rs) :+ (Dir.resolve("RESULTS.md") -> results(
        rs,
        fs,
        m,
        fx.getOrElse(fixedPipelines(rs, m))
      ))
  }

  def main(args: Array[String]): Unit =
    files().foreach { case (p, text) =>
      Files.createDirectories(p.getParent)
      Files.write(p, text.getBytes(StandardCharsets.UTF_8))
      println(s"wrote $p")
    }
}
