package vliw.crosscheck

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import vliw.compiler.{CompiledKernel, KernelCompiler}
import vliw.compiler.dsl.{Benchmarks, Kernel}
import vliw.isa.{MachineDescription, TestCase}
import vliw.sim.{Metrics, ReferenceModel}

/** The benchmark records in benchmarks/ (benchmarks/README.md): for each kernel and each issue
  * mode, a test case and a vliw-metrics/1 record, and a summary table. Everything is computed from
  * the sources; BenchmarkSpec checks that the committed files match.
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

  private def rate(v: ujson.Value): String = v.numOpt.fold("-")(x => f"$x%.3f")
  private def int(v: ujson.Value): String = v.num.toLong.toString

  /** The summary table (benchmarks/RESULTS.md); cycle-based only, no time or power. */
  def results(rs: Seq[Record], m: MachineDescription): String = {
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
    s"""# ベンチマーク結果

このファイルは `sbt "crosscheck/Test/runMain vliw.crosscheck.BenchmarkRecords"` が生成する。手で編集しない（`crosscheck/BenchmarkSpec` が一致を確かめる）。

**cycle ベースの比較である。** Fmax・面積・電力は未測定で（#57）、時間や電力の主張はしない。

## 条件

| 項目 | 値 |
|---|---|
| machine | `${m.machineId}` revision ${m.revision}、`${m.hash}` |
| ISA | `${vliw.isa.IsaVersion.Current}` |
| カーネル | `vliw.compiler.dsl.Benchmarks`（[docs/DSL.md](../docs/DSL.md)） |
| 配置 | `MemoryLayout.fixedOrder` |
| コンパイラ | 優先度（クリティカルパス、プログラム順）× レジスタ上限の試行のうち cycles 最小（[docs/COMPILER.md](../docs/COMPILER.md)） |
| 入力 | `KernelCompiler.sampleInputs(kernel, $Seed)`。結果と cycles は入力値によらない |
| 実行 | 参照モデル（`vliw.sim.ReferenceModel`）。RTL でも同じ cycles と出力になることを `BenchmarkSpec` が確かめる |
| 方式 | `vliw`: 1 bundle に Compute 1・Memory 1。`single`: 同じ machine・DAG・配置で、1サイクルに1操作だけ発行する（単一発行ベースライン） |

## 結果

| カーネル | 方式 | cycles | cycles の vliw に対する比 | 演算数 | 下限（依存 / スロット） | Compute NOP率 | Memory NOP率 | 書戻し Compute 利用率 | 書戻し Memory 利用率 | bank アクセス/cycle | レジスタ | 命令列 hash |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
${rows.mkString("\n")}

演算数は ADD・SUB・MUL・LD・ST の合計で、HALT を含まない。単一発行の cycles は「演算数 + HALT」以上になる。

## 選ばれた試行

| カーネル | 方式 | 試行 |
|---|---|---|
${rs.map(r => s"| `${r.kernel.name}` | ${r.mode.name} | ${r.compiled.allocated.attempt} |")
        .mkString("\n")}

各記録の全項目は `metrics/<kernel>_<mode>.json`（`vliw-metrics/1`、[docs/METRICS.md](../docs/METRICS.md)）、命令列と入力・期待値は `cases/<kernel>_<mode>.json`（`vliw-testcase/1`）にある。
"""
  }

  /** Every file under benchmarks/ that this generates, with its content. */
  def files(rs: Seq[Record] = records(), m: MachineDescription = machine): Seq[(Path, String)] =
    rs.flatMap { r =>
      Seq(
        Dir.resolve("cases").resolve(s"${r.name}.json") -> (ujson
          .write(r.testCase, indent = 2) + "\n"),
        Dir.resolve("metrics").resolve(s"${r.name}.json") -> (ujson
          .write(r.metrics, indent = 2) + "\n")
      )
    } :+ (Dir.resolve("RESULTS.md") -> results(rs, m))

  def main(args: Array[String]): Unit =
    files().foreach { case (p, text) =>
      Files.createDirectories(p.getParent)
      Files.write(p, text.getBytes(StandardCharsets.UTF_8))
      println(s"wrote $p")
    }
}
