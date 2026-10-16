package vliw.crosscheck

import java.nio.file.{Path, Paths}

import vliw.compiler.CompiledKernel
import vliw.compiler.dsl.Benchmarks
import vliw.compiler.regalloc.NoFit
import vliw.compiler.schedule.Priority
import vliw.isa.MachineDescription

/** The register-count records (#70; benchmarks/README.md): benchmarks/REGISTERS.md, and the test
  * case and the vliw-metrics/1 record of the fewest-cycles series at 8 and 12 registers. The
  * machines differ from the default one only in the register count (tests/machines/regs8.json,
  * regs12.json); the kernels, layout, inputs and modes are those of RESULTS.md. Written with the
  * other records by `BenchmarkRecords`.
  */
object RegisterRecords {
  import BenchmarkRecords.{Dir, Fixed, Mode, Record, cyclesCell, loads, noFit}

  /** One register count: its machine, and the path the test cases give (None for the default). */
  final case class Config(registers: Int, machine: MachineDescription, path: Option[String]) {
    def suffix: String = path.fold("")(_ => s"_r$registers")
  }

  /** Both series of one register count. */
  final case class Series(config: Config, records: Seq[Record], fixed: Seq[Fixed])

  /** The register counts compared, fewest first; 16 is the default machine. */
  val Counts: Seq[Int] = Seq(8, 12, 16)

  def path(registers: Int): String = s"tests/machines/regs$registers.json"

  def machineOf(registers: Int): MachineDescription = MachineDescription
    .load(Paths.get(path(registers)))
    .fold(es => sys.error(es.mkString("\n")), identity)

  /** The series at 8, 12 and 16 registers; those at 16 are RESULTS.md's (`rs`, `fs` on `m`). */
  def series(rs: Seq[Record], fs: Seq[Fixed], m: MachineDescription): Seq[Series] =
    Counts.map { r =>
      if (r == m.registerCount) Series(Config(r, m, None), rs, fs)
      else {
        val c = Config(r, machineOf(r), Some(path(r)))
        Series(
          c,
          BenchmarkRecords.records(c.machine, c.path, c.suffix),
          BenchmarkRecords.fixed(c.machine)
        )
      }
    }

  /** A row of the fixed series across register counts: a priority, and no limit or a load reserve
    * under the limit of each count's registers.
    */
  final case class Row(priority: Priority, reserve: Option[Int]) {
    def has(registers: Int): Boolean = reserve.forall(_ <= registers / 2)
  }

  /** The rows in the order of the attempts of the most registers. */
  def rows(most: Int): Seq[Row] =
    Priority.all.map(Row(_, None)) ++
      (for (p <- Priority.all; reserve <- 0 to most / 2) yield Row(p, Some(reserve)))

  private def result(s: Series, kernel: String, mode: Mode, row: Row) =
    s.fixed.find(f =>
      f.kernel.name == kernel && f.mode == mode && f.strategy.priority == row.priority &&
        f.strategy.limit.map(_.loadReserve) == row.reserve
    )

  private def ratio(a: CompiledKernel, b: CompiledKernel) = f"${a.cycles.toDouble / b.cycles}%.2f"

  /** benchmarks/REGISTERS.md. */
  def report(ss: Seq[Series]): String = {
    val base = ss.last
    val fewer = ss.init

    val conditions = ss
      .map { s =>
        val m = s.config.machine
        s"| ${s.config.registers} | ${s.config.path.fold("`config/machine.json`")(p => s"`$p`")} | `${m.machineId}` revision ${m.revision}、`${m.hash}` |"
      }
      .mkString("\n")

    val fewest = for {
      k <- Benchmarks.all
      mode <- Mode.all
      s <- ss
    } yield {
      val r = s.records.find(x => x.kernel == k && x.mode == mode).get
      val at16 = base.records.find(x => x.kernel == k && x.mode == mode).get
      val c = r.compiled
      Seq(
        s"`${k.name}`",
        mode.name,
        s.config.registers.toString,
        c.cycles.toString,
        ratio(c, at16.compiled),
        s"${loads(c)}（${c.dag.loads}）",
        (c.allocated.allocation.register.values.max + 1).toString,
        c.allocated.attempt.toString,
        s"`${r.metrics("program")("hash").str.take(19)}…`"
      ).mkString("| ", " | ", " |")
    }

    val tables = Benchmarks.all.map { k =>
      val lines = rows(base.config.registers).map { row =>
        val cells = Mode.all.flatMap { mode =>
          val at = ss.map(s =>
            if (!row.has(s.config.registers)) "（行なし）"
            else result(s, k.name, mode, row).get.result.fold(noFit, c => cyclesCell(c))
          )
          val ratios = fewer.map { s =>
            (
              result(s, k.name, mode, row).map(_.result),
              result(base, k.name, mode, row).map(_.result)
            ) match {
              case (Some(Right(a)), Some(Right(b))) => ratio(a, b)
              case _                                => "-"
            }
          }
          at ++ ratios
        }
        (Seq(
          row.priority.name,
          row.reserve.fold("なし")(_ => "レジスタ数"),
          row.reserve.fold("-")(_.toString)
        ) ++ cells).mkString("| ", " | ", " |")
      }
      val counts = Mode.all.map { mode =>
        val pairs = fewer.map { s =>
          val all = rows(base.config.registers)
          val oneSided = all.count(r => !r.has(s.config.registers))
          val both = all.filter(_.has(s.config.registers)).count { row =>
            result(s, k.name, mode, row).exists(_.result.isRight) &&
            result(base, k.name, mode, row).exists(_.result.isRight)
          }
          val faster = all.filter(_.has(s.config.registers)).count { row =>
            (
              result(s, k.name, mode, row).map(_.result),
              result(base, k.name, mode, row).map(_.result)
            ) match {
              case (Some(Right(a)), Some(Right(b))) => a.cycles < b.cycles
              case _                                => false
            }
          }
          s"${s.config.registers} と ${base.config.registers}: 比べられる行 $both、片方だけの行 $oneSided、レジスタの少ない方が速い行 $faster"
        }
        val failed = ss.map { s =>
          val fs =
            s.fixed.filter(f => f.kernel == k && f.mode == mode).flatMap(_.result.left.toOption)
          val by = Seq(
            "同時に生きる値が多い" -> fs.count(_.isInstanceOf[NoFit.TooManyLive]),
            "上限の下で行き詰まる" -> fs.count(_ == NoFit.Stuck)
          ).filter(_._2 > 0).map { case (w, n) => s"$w $n" }
          s"${s.config.registers} 本 ${fs.size}${if (by.isEmpty) "" else by.mkString("（", "、", "）")}"
        }
        val copied = ss.map { s =>
          val n = s.fixed
            .filter(f => f.kernel == k && f.mode == mode)
            .flatMap(_.result.toOption)
            .count(c => loads(c) > c.dag.loads)
          s"${s.config.registers} 本 $n"
        }
        s"- ${mode.name}: ${pairs.mkString("。")}。不成立の行は ${failed.mkString("、")}。LD を複製した行は ${copied.mkString("、")}"
      }
      val heads = Mode.all.flatMap(mode =>
        ss.map(s => s"${mode.name} ${s.config.registers} 本") ++
          fewer.map(s => s"${mode.name} ${s.config.registers} / ${base.config.registers}")
      )
      s"""### `${k.name}`

| 優先度 | レジスタ上限 | load reserve | ${heads.mkString(" | ")} |
|---|---|---|${heads.map(_ => "---|").mkString}
${lines.mkString("\n")}

${counts.mkString("\n")}"""
    }

    s"""# レジスタ数を変えた記録

このファイルは `sbt "crosscheck/Test/runMain vliw.crosscheck.BenchmarkRecords"` が生成する。手で編集しない（`crosscheck/BenchmarkSpec` が一致を確かめる）。

**cycle ベースの比較である。** Fmax・面積・電力は未測定で（#57）、時間や電力の主張はしない。cycle の比を実時間の速度比と読まない。レジスタを減らしたときの面積や配線の変化も測っていない。

## 条件

| レジスタ | machine description | machine |
|---|---|---|
$conditions

| 項目 | 値 |
|---|---|
| 差 | レジスタ数だけ。ほかは既定の構成と同じ（`BenchmarkSpec` が確かめる） |
| カーネル・配置・入力・方式 | [RESULTS.md](RESULTS.md) と同じ |
| コンパイラの試行 | 優先度 ×（上限なし、上限 = それぞれのレジスタ数で load reserve 0〜レジスタ数/2）。上限付きの試行が、どの語も1回だけ読むままでは行き詰まるときだけ、入力・スカラー・定数の LD を複製する（rematerialization、[ADR-0016](../docs/DECISIONS.md#adr-0016-rematerialization-は行き詰まる試行でだけ書込みのない語の-ld-をレジスタの詰まりで複製する)、#70） |
| 実行 | 参照モデル。RTL でも同じ cycles と出力になることを `BenchmarkSpec` が確かめる（RTL での確認は、方式ごとに cycles 最小の系列の記録だけ） |

${base.config.registers} 本の値は [RESULTS.md](RESULTS.md) の記録と同じである。

## 方式ごとに cycles 最小の系列

| カーネル | 方式 | レジスタ | cycles | ${base.config.registers} 本に対する比 | LD（カーネルの DAG） | 使ったレジスタ | 試行 | 命令列 hash |
|---|---|---|---|---|---|---|---|---|
${fewest.mkString("\n")}

選ばれた試行はレジスタ数ごとに違うことがあり、この比は試行の違いと LD の複製の効果も含む。${fewer.map(_.config.registers).mkString("・")} 本の各記録の全項目は `metrics/<kernel>_<mode>_r<レジスタ数>.json`、命令列と入力・期待値は `cases/<kernel>_<mode>_r<レジスタ数>.json` にある。

## 戦略を固定した系列

各行は1つの試行で、レジスタ上限は、それぞれのレジスタ数である（[README.md](README.md)「比較の書き方」）。上限なしの行は上限なしの行どうし、上限付きの行は優先度と load reserve が同じ行どうしを比べる。load reserve がレジスタ数/2 を超える行は、そのレジスタ数には無く（「行なし」）、片方だけの行として比に含めない。比は、両方の行が収まるときだけ書く。「（LD n）」は、その試行が LD を複製し、カーネルの DAG より多い n 個の LD を発行することを示す。同じ行でも、片方のレジスタ数だけが LD を複製することがあり、そのときはレジスタの少ない方が速いこともある。不成立の理由は [RESULTS.md](RESULTS.md) と同じである。

${tables.mkString("\n\n")}
"""
  }

  /** The files of this record under benchmarks/: the test cases and metrics of the counts other
    * than the default (whose are RESULTS.md's), and REGISTERS.md.
    */
  def files(ss: Seq[Series]): Seq[(Path, String)] =
    BenchmarkRecords.recordFiles(ss.filter(_.config.path.isDefined).flatMap(_.records)) :+
      (Dir.resolve("REGISTERS.md") -> report(ss))
}
