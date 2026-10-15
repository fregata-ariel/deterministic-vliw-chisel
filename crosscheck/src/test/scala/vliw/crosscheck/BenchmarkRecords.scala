package vliw.crosscheck

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import vliw.compiler.{CompiledKernel, KernelCompiler}
import vliw.compiler.dsl.{Benchmarks, Kernel}
import vliw.compiler.regalloc.{Attempt, NoFit, RegisterAllocator}
import vliw.compiler.schedule.{ListScheduler, Priority}
import vliw.isa.{MachineDescription, TestCase}
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
      metrics: ujson.Obj
  ) {
    def name: String = s"${kernel.name}_${mode.name}"
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

  def records(m: MachineDescription = machine): Seq[Record] =
    for (k <- Benchmarks.all; mode <- Mode.all) yield {
      val c = KernelCompiler.compile(k, m, singleIssue = mode.singleIssue).fold(sys.error, identity)
      val name = s"${k.name}_${mode.name}"
      val tc = KernelCompiler
        .testCase(c, KernelCompiler.sampleInputs(k, Seed), None, Some(name))
        .fold(sys.error, identity)
      val loaded =
        TestCase.parse(ujson.write(tc)).fold(es => sys.error(es.mkString("\n")), identity)
      val run =
        ReferenceModel.run(m, c.words, loaded.inputs).fold(v => sys.error(v.toString), identity)
      Record(k, mode, c, tc, Metrics.of(m, c.words, run, name))
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
          def cycles(mode: Mode) = in(mode).fold(
            {
              case NoFit.TooManyLive(live, registers) => s"不成立: 同時に生きる値 $live > $registers"
              case NoFit.Stuck                        => "不成立: 上限の下で行き詰まる"
            },
            c => if (chosen(c.allocated.attempt)) s"**${c.cycles}**" else c.cycles.toString
          )
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
  def results(rs: Seq[Record], fs: Seq[Fixed], m: MachineDescription): String = {
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
| ISA | `${vliw.isa.IsaVersion.Current}` |
| カーネル | `vliw.compiler.dsl.Benchmarks`（[docs/DSL.md](../docs/DSL.md)） |
| 配置 | `MemoryLayout.fixedOrder` |
| コンパイラの試行 | 優先度（critical-path、program-order）×（$limits）（[docs/COMPILER.md](../docs/COMPILER.md)） |
| 入力 | `KernelCompiler.sampleInputs(kernel, $Seed)`。結果と cycles は入力値によらない |
| 実行 | 参照モデル（`vliw.sim.ReferenceModel`）。RTL でも同じ cycles と出力になることを `BenchmarkSpec` が確かめる |
| 方式 | `vliw`: 1 bundle に Compute 1・Memory 1。`single`: 同じ machine・DAG・配置で、1サイクルに1操作だけ発行する（単一発行ベースライン） |

記録は2つの系列からなる。比べるときは、どちらの系列か、各行の試行を併記する（[README.md](README.md)「比較の書き方」）。

| 系列 | 試行 | 何を比べられるか |
|---|---|---|
| 方式ごとに cycles 最小 | 方式ごとに、全ての試行のうち cycles 最小のもの（同じならば、下の表の行の順で先の試行） | 各方式で今のコンパイラが出せる最良の cycles。選ばれた試行が方式ごとに違うことがあり、そのカーネルの比は発行幅だけの効果ではない |
| 戦略を固定 | 全ての試行を、両方の方式に同じ組で与える | 同じ行の比は、発行幅だけを変えた効果 |

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

各行は1つの試行（優先度、レジスタ上限、load reserve）で、両方の方式に同じ組を与えた cycles を並べる。行の順はコンパイラが試す順（`RegisterAllocator.attempts`）で、cycles が同じならば先の行の試行が選ばれる。**太字**は、上の「方式ごとに cycles 最小の系列」で選ばれた試行である。「不成立」は、その試行ではレジスタに収まらないことを示し、別の試行で置き換えない（spill はしない）。理由は2つある。「同時に生きる値 n > r」は、上限なしのスケジュールが同時に n 個の値を生かし、レジスタ r 本に割り当てられないこと。「上限の下で行き詰まる」は、上限付きのスケジューラが、残りのどの操作も上限を超えずに置けない状態になること。どの行の命令列も verifier が合法と判定し、参照モデルで同じ出力と cycles になることを `BenchmarkSpec` が確かめる（RTL での確認は、上の系列の記録だけ）。

${fixedTables(rs, fs)}
"""
  }

  /** Every file under benchmarks/ that this generates, with its content (with ModuloRecords). */
  def files(
      rs: Seq[Record] = records(),
      fs: Seq[Fixed] = fixed(),
      m: MachineDescription = machine,
      ms: Option[Seq[ModuloRecords.Compared]] = None
  ): Seq[(Path, String)] =
    ModuloRecords.files(ms.getOrElse(ModuloRecords.records(m)), rs, fs, m) ++ rs.flatMap { r =>
      Seq(
        Dir.resolve("cases").resolve(s"${r.name}.json") -> (ujson
          .write(r.testCase, indent = 2) + "\n"),
        Dir.resolve("metrics").resolve(s"${r.name}.json") -> (ujson
          .write(r.metrics, indent = 2) + "\n")
      )
    } :+ (Dir.resolve("RESULTS.md") -> results(rs, fs, m))

  def main(args: Array[String]): Unit =
    files().foreach { case (p, text) =>
      Files.createDirectories(p.getParent)
      Files.write(p, text.getBytes(StandardCharsets.UTF_8))
      println(s"wrote $p")
    }
}
