package vliw.crosscheck

import java.nio.file.{Path, Paths}

import vliw.compiler.KernelCompiler
import vliw.compiler.loop.{LoopBenchmarks, LoopKernel}
import vliw.compiler.modulo.{
  Attempt,
  ModuloCompiled,
  ModuloCompiler,
  ModuloScheduler,
  NoSchedule,
  Outcome
}
import vliw.compiler.regalloc.{NoFit, RegisterAllocator}
import vliw.isa.{MachineDescription, TestCase}
import vliw.sim.{Metrics, ReferenceModel}

/** The Modulo Scheduling records in benchmarks/ (#68; benchmarks/README.md): benchmarks/MODULO.md,
  * and a test case and a vliw-metrics/1 record per compared loop, whose `ii` is the value the trace
  * check confirms (`ModuloTrace`). Written with the other records by `BenchmarkRecords`.
  */
object ModuloRecords {
  import BenchmarkRecords.{Dir, Fixed, Mode, Record, Seed}

  /** The loops compared with list scheduling: the Phase 2 kernels, on the default machine. */
  val compared: Seq[LoopKernel] = LoopBenchmarks.all.take(4)

  final case class Compared(
      kernel: LoopKernel,
      compiled: ModuloCompiled,
      testCase: ujson.Obj,
      metrics: ujson.Obj,
      suffix: String = ""
  ) {
    def name: String = s"${kernel.name}_${kernel.index}_modulo$suffix"
  }

  /** The record of loop `k` on `m`: its program, test case and metrics. `machinePath` goes into the
    * test case (None for the default machine), and `suffix` ends the record name.
    */
  def record(
      k: LoopKernel,
      m: MachineDescription,
      machinePath: Option[String] = None,
      suffix: String = ""
  ): Compared = {
    val c = ModuloCompiler.compile(k, m).fold(sys.error, identity)
    val name = s"${k.name}_${k.index}_modulo$suffix"
    val tc = KernelCompiler
      .testCaseOf(
        k.unroll,
        c.layout,
        c.bundles,
        c.cycles,
        s"generated from the loop of ${k.name} over ${k.index} by Modulo Scheduling at II ${c.ii}; expected outputs from the DSL reference implementation, cycles predicted by the scheduler",
        KernelCompiler.sampleInputs(k.unroll, Seed),
        machinePath,
        name
      )
      .fold(sys.error, identity)
    val loaded = TestCase.parse(ujson.write(tc)).fold(es => sys.error(es.mkString("\n")), identity)
    val run =
      ReferenceModel.run(m, c.words, loaded.inputs).fold(v => sys.error(v.toString), identity)
    val ii = ModuloTrace.check(c.graph, c.result.map, run.trace.cycles.map(_.toJson), c.ii) match {
      case Right(Some(ii)) => ii
      case other           => sys.error(s"$name: the trace does not confirm II ${c.ii}: $other")
    }
    Compared(k, c, tc, Metrics.of(m, c.words, run, name, Some(ii)), suffix)
  }

  /** `record`, whose run must also take the cycles the scheduler predicted: what a table row of
    * MODULO.md that has no record file of its own is written from (ADR-0015).
    */
  def confirmed(k: LoopKernel, m: MachineDescription): Compared = {
    val r = record(k, m)
    val cycles = r.metrics("cycles").num.toInt
    if (cycles != r.compiled.cycles)
      sys.error(
        s"${r.name}: the run takes $cycles cycles, the scheduler predicted ${r.compiled.cycles}"
      )
    r
  }

  def records(m: MachineDescription = BenchmarkRecords.machine): Seq[Compared] =
    compared.map(record(_, m))

  private def machineFile(name: String) = MachineDescription
    .load(Paths.get("tests", "machines", name))
    .fold(es => sys.error(es.mkString("\n")), identity)

  /** The other loops and machines: every loop of `LoopBenchmarks.all` on the default machine (but
    * the compared ones), slow_units and ii_units, and the loops of #68's register test on regs3.
    */
  def acceptance(
      m: MachineDescription = BenchmarkRecords.machine
  ): Seq[(LoopKernel, MachineDescription)] = {
    val (slow, iiUnits, regs3) =
      (machineFile("slow_units.json"), machineFile("ii_units.json"), machineFile("regs3.json"))
    Seq(m, slow, iiUnits).flatMap(mach =>
      LoopBenchmarks.all.filter(k => mach != m || !compared.contains(k)).map(_ -> mach)
    ) ++ Seq(LoopBenchmarks.saxpy(8) -> regs3, LoopBenchmarks.dot(8) -> regs3)
  }

  /** The sizes of `saxpy(n)` recorded (#68). */
  val series: Seq[Int] = Seq(8, 16, 32, 64, 128)

  /** The n tried for the largest `saxpy(n)`: every n whose 3n + 1 words fit the scratchpad and
    * whose 3n + 1 memory operations (LD of a, of x(i) and y(i), ST of out(i)), one per bundle, fit
    * the instruction memory. No larger n can fit.
    */
  def tried(m: MachineDescription): Int =
    math.min((m.scratchpadWords - 1) / 3, (m.instructionMemoryBundles - 1) / 3)

  /** The largest n up to `tried(m)` whose program fits the instruction memory. */
  def largest(m: MachineDescription): Int =
    (1 to tried(m)).filter(n => ModuloCompiler.compile(LoopBenchmarks.saxpy(n), m).isRight).max

  /** Why no II gives a program for `k` on `m`: the attempts of the search, on the graph that
    * `ModuloCompiler` schedules (fused into MAC when it chooses so).
    */
  def failure(k: LoopKernel, m: MachineDescription): NoSchedule =
    ModuloScheduler
      .schedule(ModuloCompiler.prepare(k, m).fold(sys.error, identity).graph)
      .swap
      .getOrElse(sys.error(s"${k.name} compiles on ${m.machineId}"))

  def label(k: LoopKernel): String = s"`${k.name}`（${k.index}）"

  /** The attempts before the chosen II, grouped by cause: (a) no reservation table, the order of
    * placement, (b) registers, the instruction memory.
    */
  def reasons(attempts: Seq[Attempt]): String = {
    // Consecutive IIs share a group when they fail the same way; for (a), on the same resources.
    def kind(o: Outcome) = o match {
      case Outcome.NoMrt(r)       => (0, r)
      case _: Outcome.NoTime      => (1, "")
      case _: Outcome.TooManyLive => (2, "")
      case _: Outcome.Overflow    => (3, "")
      case Outcome.Fits           => (4, "")
    }
    val groups =
      attempts.filter(_.outcome != Outcome.Fits).foldLeft(Vector.empty[Vector[Attempt]]) {
        (gs, a) =>
          if (
            gs.nonEmpty && kind(gs.last.last.outcome) == kind(
              a.outcome
            ) && gs.last.last.ii + 1 == a.ii
          )
            gs.init :+ (gs.last :+ a)
          else gs :+ Vector(a)
      }
    if (groups.isEmpty) "-"
    else
      groups
        .map { g =>
          val iis = if (g.size == 1) s"II ${g.head.ii}" else s"II ${g.head.ii}〜${g.last.ii}"
          g.head.outcome match {
            case Outcome.NoMrt(r)       => s"$iis: (a) 予約表なし（$r）"
            case _: Outcome.NoTime      => s"$iis: 配置の順で置けない（${g.map(_.outcome).mkString("、")}）"
            case _: Outcome.TooManyLive =>
              val live = g.map(_.outcome).collect { case Outcome.TooManyLive(l, _) => l }
              val r = g.head.outcome.asInstanceOf[Outcome.TooManyLive].registers
              s"$iis: (b) レジスタ（同時に生きる値 ${live.mkString("、")} > $r）"
            case Outcome.Overflow(_, cap) =>
              val b = g.map(_.outcome).collect { case Outcome.Overflow(b, _) => b }
              s"$iis: 命令領域（${b.mkString("、")} bundle > $cap）"
            case Outcome.Fits => ""
          }
        }
        .mkString("。")
  }

  private def loads(c: ModuloCompiled) = {
    val g = c.graph
    def ld(p: vliw.compiler.loop.Place) = g.ids(p).count(g.nodes(_).mnemonic == "LD")
    val (before, each) = (ld(vliw.compiler.loop.Place.Before), ld(vliw.compiler.loop.Place.Each))
    s"$before + $each × ${g.trips} = ${before + each * g.trips}"
  }

  def bound(c: ModuloCompiled): String =
    s"${c.result.mii.resMII}（${c.result.mii.bounding.map(r => s"`$r`").mkString("、")}）"

  /** benchmarks/MODULO.md. `rs` and `fs` are the list-scheduling records of BenchmarkRecords. */
  def report(cs: Seq[Compared], rs: Seq[Record], fs: Seq[Fixed], m: MachineDescription): String = {
    val perLoop = cs.map { r =>
      val c = r.compiled
      val s = c.result
      val list = rs.find(x => x.kernel == r.kernel.unroll && x.mode == Mode.Vliw).get.compiled
      val listLd = BenchmarkRecords.loads(list)
      Seq(
        label(r.kernel),
        r.kernel.trips.toString,
        bound(c),
        s.mii.recMII.toString,
        s.iiMax.toString,
        s.ii.toString,
        reasons(s.attempts),
        s.start.toString,
        s.span.toString,
        c.words.size.toString,
        c.cycles.toString,
        RegisterAllocator.maxLive(s.schedule).toString,
        loads(c),
        listLd.toString
      ).mkString("| ", " | ", " |")
    }

    val comparisons = compared.map(_.unroll).distinct.map { kernel =>
      val loops = cs.filter(_.kernel.unroll == kernel)
      val rows = fs.filter(f => f.kernel == kernel && f.mode == Mode.Vliw)
      val lines = rows.map { f =>
        val list = f.result.fold(BenchmarkRecords.noFit, BenchmarkRecords.cyclesCell(_))
        val ratios = loops
          .map(l => f.result.fold(_ => "-", c => f"${l.compiled.cycles.toDouble / c.cycles}%.2f"))
        (Seq(
          f.strategy.priority.name,
          f.strategy.limit.fold("なし")(_.registers.toString),
          f.strategy.limit.fold("-")(_.loadReserve.toString),
          list
        ) ++ ratios).mkString("| ", " | ", " |")
      }
      val failed = rows.flatMap(_.result.left.toOption)
      val counts = Seq(
        "同時に生きる値が多い" -> failed.count(_.isInstanceOf[NoFit.TooManyLive]),
        "上限の下で行き詰まる" -> failed.count(_ == NoFit.Stuck)
      ).filter(_._2 > 0).map { case (w, n) => s"$w $n" }
      val best = rs.find(x => x.kernel == kernel && x.mode == Mode.Vliw).get.compiled
      val heads = loops.map(l => s"Modulo（${l.kernel.index}、${l.compiled.cycles} cycles）/ list")
      s"""### `${kernel.name}`

Modulo Scheduling: ${loops
          .map(l => s"${label(l.kernel)} ${l.compiled.cycles} cycles（II ${l.compiled.ii}）")
          .mkString(
            "、"
          )}。list scheduling の、方式ごとに cycles 最小の系列（vliw）は ${best.cycles} cycles（${best.allocated.attempt}）。

| 優先度 | レジスタ上限 | load reserve | list scheduling の cycles（vliw） | ${heads.mkString(" | ")} |
|---|---|---|---|${heads.map(_ => "---|").mkString}
${lines.mkString("\n")}

不成立の行は ${failed.size}（${if (counts.isEmpty) "-" else counts.mkString("、")}）で、比に含めない。"""
    }

    val accept = acceptance(m)
      .map { case (k, mach) =>
        val r = ModuloCompiler.compile(k, mach)
        val machine = s"`${mach.machineId}` rev ${mach.revision}（`${mach.hash.take(19)}…`）"
        r match {
          case Right(c) =>
            Seq(
              label(k),
              machine,
              bound(c),
              c.result.mii.recMII.toString,
              c.result.iiMax.toString,
              c.ii.toString,
              reasons(c.result.attempts),
              c.cycles.toString
            )
          case Left(_) =>
            val no = failure(k, mach)
            Seq(
              label(k),
              machine,
              s"${no.mii.resMII}（${no.mii.bounding.map(r => s"`$r`").mkString("、")}）",
              no.mii.recMII.toString,
              no.iiMax.toString,
              "-",
              s"不成立: ${reasons(no.attempts)}（II ${no.mii.mii}〜${no.iiMax} のどれにも収まらない）",
              "-"
            )
        }
      }
      .map(_.mkString("| ", " | ", " |"))

    val saxpy = series.map { n =>
      ModuloCompiler.compile(LoopBenchmarks.saxpy(n), m) match {
        case Right(_) =>
          val r = confirmed(LoopBenchmarks.saxpy(n), m)
          s"| $n | ${r.metrics("ii").num.toInt} | ${r.compiled.words.size} | ${r.metrics("cycles").num.toInt} |"
        case Left(_) =>
          val no = failure(LoopBenchmarks.saxpy(n), m)
          s"| $n | - | - | 不成立: ${reasons(no.attempts)}（II ${no.mii.mii}〜${no.iiMax} のどれにも収まらない） |"
      }
    }
    val nMax = largest(m)

    val recurrent = cs.filter(_.compiled.result.mii.recMII > 0)
    val recNote =
      if (recurrent.isEmpty) "無い"
      else
        recurrent
          .map { r =>
            val mii = r.compiled.result.mii
            val than =
              if (mii.recMII < mii.resMII) "より小さい"
              else if (mii.recMII == mii.resMII) "と等しい"
              else "より大きい"
            s"${label(r.kernel)}（RecMII ${mii.recMII}、ResMII ${mii.resMII} $than）"
          }
          .mkString("、") + "だけ"

    s"""# Modulo Scheduling の記録

このファイルは `sbt "crosscheck/Test/runMain vliw.crosscheck.BenchmarkRecords"` が生成する。手で編集しない（`crosscheck/BenchmarkSpec` が一致を確かめる）。

**cycle ベースの比較である。** Fmax・面積・電力は未測定で（#57）、時間や電力の主張はしない。cycle の比を実時間の速度比と読まない。

## 条件

| 項目 | 値 |
|---|---|
| machine | `${m.machineId}` revision ${m.revision}、`${m.hash}` |
| ループ | `vliw.compiler.loop.LoopBenchmarks`（[docs/COMPILER.md](../docs/COMPILER.md)「ループ IR」）。展開すると list scheduling のカーネルと同じになる |
| 配置と入力 | list scheduling と同じ（展開したカーネルの `MemoryLayout.fixedOrder`、`KernelCompiler.sampleInputs(kernel, $Seed)`） |
| Modulo Scheduling | II = MII から探索の上限（1反復を list scheduling した長さ）まで順に試し、最初に命令列ができた II を使う（[docs/COMPILER.md](../docs/COMPILER.md)「Modulo Scheduling」）。戦略は1つで、cycles 最小の II とは限らない |
| 実行 | 参照モデル。II は、トレースで確かめた値（`crosscheck/ModuloTrace`）を `metrics` の `ii` に記録する。RTL でも同じ cycles と出力になることを `BenchmarkSpec` が確かめる |

## ループごとの結果

| ループ | 反復 | ResMII（律速の資源） | RecMII | 探索の上限 | II | MII を超えた II の理由 | 開始 S | 配置した1反復の長さ | bundle | cycles | 同時に生きる値 | LD（ループの前 + 1反復 × 反復） | LD（list scheduling） |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
${perLoop.mkString("\n")}

(a) は、1反復の演算が II 行の予約表（スロット、演算器の II（L9）、書戻しポート（L5））に収まらないこと、(b) は、命令列の同時に生きる値がレジスタ数を超えること。比べる4つのループのうち、RecMII > 0 は $recNote。RecMII が II を決めるループは、下の「他のループと構成」の `product8`。

各記録の全項目は `metrics/<kernel>_<loop>_modulo.json`（`vliw-metrics/1`、`ii` を含む）、命令列と入力・期待値は `cases/<kernel>_<loop>_modulo.json` にある。

## list scheduling（戦略を固定した系列）との比較

list scheduling の各行は、[RESULTS.md](RESULTS.md) の「戦略を固定した系列」の vliw の列で、同じ配置と入力である。「（LD n）」の行は、その試行が LD を複製した行で（#70）、LD の数が Modulo Scheduling（上の表の LD の列）と違う。Modulo Scheduling の側は1つの戦略なので、各行は「その試行の list scheduling」と「Modulo Scheduling」の比になる。

${comparisons.mkString("\n\n")}

## 他のループと構成（#68 の受入テストを含む）

`LoopBenchmarks.all` の各ループを、既定の構成（上の4つを除く）、`slow_units`、`ii_units` で。`regs3` は、レジスタが足りずに II が上がる例と、どの II でも収まらない例。list scheduling の記録（#79）の対象外。

| ループ | machine | ResMII（律速の資源） | RecMII | 探索の上限 | II | MII を超えた II の理由 | cycles |
|---|---|---|---|---|---|---|---|
${accept.mkString("\n")}

## 反復回数の系列（`saxpy(n)`、既定の構成）

| n | II | bundle | cycles |
|---|---|---|---|
${saxpy.mkString("\n")}

II と cycles は、各行の命令列を参照モデルで実行し、トレースで確かめた値である（上の「ループごとの結果」と同じ。cycles はスケジューラの予測とも一致する）。

命令領域（${m.instructionMemoryBundles} bundle）に収まる最大の n は $nMax。n = 1 から ${tried(
        m
      )} まですべて試した（Scratchpad に収まるのは n ≦ ${(m.scratchpadWords - 1) / 3}、メモリ操作 3n + 1 個が1 bundle に1個ずつ命令領域に収まるのは n ≦ ${(m.instructionMemoryBundles - 1) / 3}）。
"""
  }

  /** The test case and the metrics of each record. */
  def recordFiles(cs: Seq[Compared]): Seq[(Path, String)] = cs.flatMap { r =>
    Seq(
      Dir.resolve("cases").resolve(s"${r.name}.json") -> (ujson
        .write(r.testCase, indent = 2) + "\n"),
      Dir.resolve("metrics").resolve(s"${r.name}.json") -> (ujson
        .write(r.metrics, indent = 2) + "\n")
    )
  }

  /** The files of this record under benchmarks/. */
  def files(
      cs: Seq[Compared],
      rs: Seq[Record],
      fs: Seq[Fixed],
      m: MachineDescription
  ): Seq[(Path, String)] =
    recordFiles(cs) :+ (Dir.resolve("MODULO.md") -> report(cs, rs, fs, m))
}
