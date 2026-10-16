package vliw.crosscheck

import java.nio.file.{Path, Paths}

import vliw.compiler.{CompiledKernel, KernelCompiler, Verifier}
import vliw.compiler.dsl.{Benchmarks, Kernel, MemoryLayout}
import vliw.compiler.loop.{LoopBenchmarks, LoopKernel, Place}
import vliw.compiler.modulo.{ModuloCompiled, ModuloScheduler}
import vliw.compiler.regalloc.NoFit
import vliw.hw.CoreParams
import vliw.isa.{Bundle, ComputeOp, MachineDescription, MemoryOp, Rule, Slot}

/** The records of ISA mvp-1 (#77, Phase A; benchmarks/README.md): benchmarks/MAC.md, which compares
  * the machines with MAC and mvp-0 on the same kernels, inputs, layout and strategies, and the test
  * case and the vliw-metrics/2 record of each mvp-1 program (the fewest-cycles series and the
  * Modulo Scheduling loops). The configurations are mvp-1 (config/machine-mvp1.json), four read
  * ports (no L11 constraint; reference model and verifier only) and MAC latency 3 (sensitivity).
  * Written with the other records by `BenchmarkRecords`.
  */
object MacRecords {
  import BenchmarkRecords.{Dir, Fixed, Mode, Record, cyclesCell, loads, noFit}

  /** One configuration: its name in the records, its machine, the path the test cases give (None
    * for mvp-0, the default machine) and the suffix of its record files.
    */
  final case class Config(
      name: String,
      machine: MachineDescription,
      path: Option[String],
      suffix: String
  ) {

    /** Why the RTL does not implement the machine (`CoreParams.unsupported`); empty if it does. */
    def unsupported: Seq[String] = CoreParams.unsupported(machine)
  }

  /** Both list-scheduling series and the Modulo Scheduling loops of one configuration. */
  final case class Series(
      config: Config,
      records: Seq[Record],
      fixed: Seq[Fixed],
      loops: Seq[ModuloRecords.Compared]
  )

  /** The configurations with MAC, after mvp-0: name, machine description and record suffix. */
  val Variants: Seq[(String, String, String)] = Seq(
    ("mvp-1", "config/machine-mvp1.json", "_mac"),
    ("rp4", "tests/machines/mvp1_rp4.json", "_mac_rp4"),
    ("mac3", "tests/machines/mvp1_mac3.json", "_mac3")
  )

  /** The loops recorded: the six of `LoopBenchmarks.all`. */
  val loops: Seq[LoopKernel] = LoopBenchmarks.all

  def machineAt(path: String): MachineDescription = MachineDescription
    .load(Paths.get(path))
    .fold(es => sys.error(es.mkString("\n")), identity)

  /** mvp-0 (RESULTS.md's series `rs` and `fs` on `m`, and MODULO.md's loops `ms`), then each
    * configuration with MAC.
    */
  def series(
      rs: Seq[Record],
      fs: Seq[Fixed],
      ms: Seq[ModuloRecords.Compared],
      m: MachineDescription
  ): Seq[Series] = {
    val base = Series(
      Config("mvp-0", m, None, ""),
      rs,
      fs,
      loops.map(k => ms.find(_.kernel == k).getOrElse(ModuloRecords.record(k, m)))
    )
    base +: Variants.map { case (name, path, suffix) =>
      val c = Config(name, machineAt(path), Some(path), suffix)
      Series(
        c,
        BenchmarkRecords.records(c.machine, c.path, suffix),
        BenchmarkRecords.fixed(c.machine),
        loops.map(ModuloRecords.record(_, c.machine, c.path, suffix))
      )
    }
  }

  /** Register reads of a bundle, counted per operand field as rule L11 counts them. */
  def reads(b: Bundle): Int = {
    val compute = b.compute match {
      case _: ComputeOp.Add | _: ComputeOp.Sub | _: ComputeOp.Mul => 2
      case _: ComputeOp.Mac                                       => 3
      case ComputeOp.Nop | ComputeOp.Halt                         => 0
    }
    val memory = b.memory match {
      case _: MemoryOp.St                => 1
      case _: MemoryOp.Ld | MemoryOp.Nop => 0
    }
    compute + memory
  }

  /** Whether a bundle holds a MAC and a ST: four reads, which three read ports do not allow (L11).
    */
  def hasMacAndSt(b: Bundle): Boolean = b match {
    case Bundle(_: ComputeOp.Mac, _: MemoryOp.St) => true
    case _                                        => false
  }

  /** The bundles with a MAC and a ST. */
  def macWithSt(bundles: Seq[Bundle]): Int = bundles.count(hasMacAndSt)

  /** The bundles by register reads, 0 to 4: from the vliw-metrics/2 record, or counted from the
    * program for mvp-0, whose vliw-metrics/1 record has no such field.
    */
  def byReads(metrics: ujson.Obj, bundles: Seq[Bundle]): Vector[Int] =
    metrics("issue").obj.get("register_reads") match {
      case Some(r) =>
        (0 to 4).map(k => r("bundles_by_reads").obj.get(k.toString).fold(0)(_.num.toInt)).toVector
      case None => (0 to 4).map(k => bundles.count(reads(_) == k)).toVector
    }

  /** The L11 findings, and the other findings, of the verifier on `on` for `words` (a program of
    * another configuration) with the declaration of `layout`.
    */
  def findingsOn(
      on: MachineDescription,
      layout: MemoryLayout,
      words: Vector[Long]
  ): (Int, Int) = {
    val fs = Verifier.verify(on, words, KernelCompiler.declaration(layout))
    (fs.count(_.rule == Rule.ReadPorts), fs.count(_.rule != Rule.ReadPorts))
  }

  /** The bundles with a MAC and a ST of a loop's program, with where in the loop the STs are. */
  def loopMacWithSt(c: ModuloCompiled): String = {
    val both = c.bundles.indices.filter(i => hasMacAndSt(c.bundles(i))).toSet
    val places = c.result.map
      .filter(e => e.slot == Slot.Memory && both(e.bundle))
      .map(e => c.graph.places(e.instance.node))
    val by = Seq(Place.Before -> "ループの前", Place.Each -> "反復の中", Place.After -> "ループの後")
      .map { case (p, w) => w -> places.count(_ == p) }
      .filter(_._2 > 0)
      .map { case (w, n) => s"$w $n" }
    s"${both.size}${if (by.isEmpty) "" else by.mkString("（", "、", "）")}"
  }

  /** How the chosen II was placed (`ModuloScheduler.schedule`): in node order, with the look-ahead,
    * or as the schedule of one iteration alone at the largest II.
    */
  def placement(c: ModuloCompiled): String =
    if (ModuloScheduler.place(c.graph, c.ii, lookahead = false).isRight) "番号の順"
    else if (ModuloScheduler.place(c.graph, c.ii, lookahead = true).isRight) "先読み"
    else "1反復だけのスケジュール"

  private def ratio(a: Int, b: Int) = f"${a.toDouble / b}%.2f"
  private def rate(v: ujson.Value): String = v.numOpt.fold("-")(x => f"$x%.3f")
  private def op(r: Record, mnemonic: String): Int =
    r.metrics("issue")("by_operation").obj.get(mnemonic).fold(0)(_.num.toInt)
  private def row(cells: Seq[String]) = cells.mkString("| ", " | ", " |")
  private def head(cells: Seq[String]) =
    s"${row(cells)}\n|${cells.map(_ => "---|").mkString}"

  private def record(s: Series, k: Kernel, mode: Mode) =
    s.records.find(r => r.kernel == k && r.mode == mode).get
  private def result(s: Series, k: Kernel, mode: Mode, strategy: BenchmarkRecords.Strategy) =
    s.fixed.find(f => f.kernel == k && f.mode == mode && f.strategy == strategy).get.result
  private def loop(s: Series, k: LoopKernel) = s.loops.find(_.kernel == k).get

  private def attemptOf(c: CompiledKernel) = c.allocated.attempt.copy(singleIssue = false)

  private def failures(fs: Seq[NoFit]): String = {
    val by = Seq(
      "同時に生きる値が多い" -> fs.count(_.isInstanceOf[NoFit.TooManyLive]),
      "上限の下で行き詰まる" -> fs.count(_ == NoFit.Stuck)
    ).filter(_._2 > 0).map { case (w, n) => s"$w $n" }
    s"${fs.size}${if (by.isEmpty) "" else by.mkString("（", "、", "）")}"
  }

  /** The conditions section: the machines and what the records hold constant. */
  private def conditions(ss: Seq[Series]): String = {
    val machines = ss.map { s =>
      val m = s.config.machine
      val mac = m.operations
        .get("MAC")
        .fold("-（MAC なし）")(o => s"${o.latency} / ${o.ii}")
      val run =
        if (s.config.unsupported.isEmpty) "参照モデルと RTL"
        else s"参照モデルと verifier（RTL の範囲外: ${s.config.unsupported.mkString("; ")}）"
      row(
        Seq(
          s.config.name,
          s"`${s.config.path.getOrElse("config/machine.json")}`",
          s"`${m.machineId}` revision ${m.revision}（${m.status}）、`${m.hash}`",
          s"`${m.isaVersion}`",
          m.readPorts.toString,
          mac,
          run
        )
      )
    }
    val m = ss.head.config.machine
    s"""## 条件

${head(
        Seq(
          "構成",
          "machine description",
          "machine",
          "ISA",
          "`read_ports`",
          "MAC の latency / II（`operations.MAC`）",
          "実行"
        )
      )}
${machines.mkString("\n")}

| 項目 | 値 |
|---|---|
| 差 | mvp-1 は mvp-0 に MAC を足した版である（ISA mvp-1、[ADR-0018](../docs/DECISIONS.md#adr-0018-mac-は-isa-mvp-1-で入れ読出しポートは3本のまま規則-l11-で数える)）。rp4 は mvp-1 と `read_ports` だけ、mac3 は MAC の latency だけが違う（`BenchmarkSpec` が確かめる）。どの構成もレジスタは ${m.registerCount} 本 |
| カーネル・配置・入力・方式 | [RESULTS.md](RESULTS.md) と同じ。${ss.size}つの構成で、配置と入力は同じである（`BenchmarkSpec` が確かめる） |
| コンパイラの試行 | RESULTS.md と同じ。優先度 ×（上限なし、上限 ${m.registerCount} で load reserve 0〜${m.registerCount / 2}）。rematerialization も同じ（[ADR-0016](../docs/DECISIONS.md#adr-0016-rematerialization-は行き詰まる試行でだけ書込みのない語の-ld-をレジスタの詰まりで複製する)） |
| MAC への融合 | MAC のある構成では、読む側が1つだけの MUL と、それを読む ADD を、常に1つの MAC にする。`ADD(m, m)`、SUB、読む側が複数ある MUL は融合しない（[ADR-0019](../docs/DECISIONS.md#adr-0019-読む側が1つの-mul-とそれを読む-add-を-mac-に融合しループでは-mii-が上がるときは融合しない)）。融合は戦略の次元にしない。DAG はカーネルと machine だけで決まり、全ての試行が同じ DAG を使う |
| 読出しポート | `read_ports` 3 の list scheduling は素朴な方法に固定する。各サイクルで Compute を先に埋め、MAC を置いた bundle では ST を待たせる（ADR-0019）。ST のために MAC を遅らせる工夫はしない。rp4 は制約のない比較のための構成で、読出し4本の RTL は #57 で足す |
| ループの融合 | Modulo Scheduling は、融合した後の MII が融合しない MII より大きくならないループだけを融合する。MII は3本目の読出しポートを除いて比べるので、mvp-1 と rp4 は同じグラフをスケジュールする（ADR-0019） |
| 実行 | 参照モデル。RTL の範囲内の構成（mvp-1 と mac3）の記録は、RTL でも同じ出力と cycles になり、RTL のトレースが参照モデルのトレースと一致することを `BenchmarkSpec` が確かめる |
| 記録の形式 | mvp-0 は `vliw-metrics/1`、MAC のある構成は `vliw-metrics/2`（MAC の発行数と、読出しポートの欄。[docs/METRICS.md](../docs/METRICS.md)） |

MAC の latency と II は、machine description の `operations.MAC` の契約の値である。この II は MAC の演算器の発行間隔（規則 L9）で、下の Modulo Scheduling の II（ループの反復の開始間隔、`metrics` の `ii`）とは別のものである。"""
  }

  /** The fewest-cycles series: per kernel, mode and configuration. */
  private def fewest(ss: Seq[Series]): String = {
    val base = ss.head
    val sub = ss.exists(_.records.exists(op(_, "SUB") > 0))
    val ops = Seq("MUL", "MAC", "ADD") ++ (if (sub) Seq("SUB") else Nil)
    val cycles = for (k <- Benchmarks.all; mode <- Mode.all; s <- ss) yield {
      val r = record(s, k, mode)
      val c = r.compiled
      row(
        Seq(
          s"`${k.name}`",
          mode.name,
          s.config.name,
          c.cycles.toString,
          ratio(c.cycles, record(base, k, mode).compiled.cycles),
          s"${loads(c)}（${c.dag.loads}）"
        ) ++ ops.map(op(r, _).toString) ++ Seq(
          c.dag.macs.toString,
          c.allocated.attempt.toString,
          s"`${r.metrics("program")("hash").str.take(19)}…`"
        )
      )
    }
    val ports = for (k <- Benchmarks.all; mode <- Mode.all; s <- ss) yield {
      val r = record(s, k, mode)
      val c = r.compiled
      val by = byReads(r.metrics, c.bundles)
      val limit = s.config.machine.readPorts
      row(
        Seq(
          s"`${k.name}`",
          mode.name,
          s.config.name,
          rate(r.metrics("issue")("compute")("nop_rate")),
          rate(r.metrics("writeback")("compute")("utilization")),
          c.allocated.schedule.heldByWritePort.toString,
          c.allocated.schedule.heldByReadPorts.toString,
          if (s.config.machine.hasMac) r.metrics("issue")("mac_with_st").num.toInt.toString
          else "-",
          c.bundles.map(reads).sum.toString,
          by.indices.map(i => if (i > limit) "-" else by(i).toString).mkString(" / ")
        )
      )
    }
    val same = (for (k <- Benchmarks.all; mode <- Mode.all) yield k -> mode).collect {
      case (k, mode) if ss.map(s => attemptOf(record(s, k, mode).compiled)).distinct.size == 1 =>
        s"`${k.name}`（${mode.name}）"
    }
    val chosen =
      if (same.isEmpty) s"${ss.size}つの構成で同じ試行が選ばれたカーネルと方式は無い"
      else s"${ss.size}つの構成で同じ試行が選ばれたのは ${same.mkString("、")}で、ほかは構成によって違う"
    s"""## list scheduling: 方式ごとに cycles 最小の系列

各構成で、方式ごとに、全ての試行のうち cycles 最小のもの（同じならば LD の少ない方、それも同じならば試す順で先の試行）。選ばれた試行は構成ごとに違うことがあり、そのときの比は、試行の違いの効果も含む（[README.md](README.md)「比較の書き方」）。同じ試行どうしの比は、下の「戦略を固定した系列」にある。

### cycles と演算数

${head(
        Seq(
          "カーネル",
          "方式",
          "構成",
          "cycles",
          "mvp-0 に対する cycles の比",
          "LD（カーネルの DAG）"
        ) ++ ops ++ Seq("融合した組", "試行", "命令列 hash")
      )}
${cycles.mkString("\n")}

演算数は発行した数である（`metrics` の `issue.by_operation`）。融合した組は、カーネルの DAG の MAC の数（`Dag.macs`）で、DSL に MAC は無いので、MUL + ADD を MAC にした数である。MAC のある構成の MUL と ADD は、mvp-0 より組の数だけ少なく、乗算の数（MUL + MAC）は変わらない（`BenchmarkSpec` が確かめる）。$chosen。

### スロットとポート

${head(
        Seq(
          "カーネル",
          "方式",
          "構成",
          "Compute NOP率",
          "書戻し Compute 利用率",
          "`heldByWritePort`",
          "`heldByReadPorts`",
          "MAC と ST の bundle",
          "読出し",
          "読出しの数ごとの bundle（0 / 1 / 2 / 3 / 4）"
        )
      )}
${ports.mkString("\n")}

- `heldByWritePort`（`Schedule`）は、準備のできた Compute のノードが、書戻しポート（L5）以外の制約（依存、スロット、演算器の II、読出しポート、レジスタの上限）を全て満たすのに、その境界の Compute の書戻しポートが予約済みなので置けなかったサイクルの数である（書戻しの衝突）
- `heldByReadPorts`（`Schedule`）は、準備のできた ST が、読出しポート（L11）以外の制約（依存、スロット、演算器の II、レジスタの上限）を全て満たすのに、その bundle の読出しが `read_ports` を超えるので置けなかったサイクルの数である
- どちらも、待ったノードの代わりに同じスロットへ別のノードを置いたサイクルも数え（例: ST が待ち、LD を置いた）、サイクル当たり高々1つ増える。スケジュールは変えない。LD を複製した試行では、最後に成功したスケジュールの値である。単一発行では、ST だけの bundle は読出し1なので `heldByReadPorts` は0である
- MAC と ST の bundle は `vliw-metrics/2` の `issue.mac_with_st` で、`read_ports` 3 では常に0、rp4 では、3本では置けない bundle の数である
- 読出しはオペランドの欄の数（ADD/SUB/MUL は2、MAC は3、ST は1）の合計で、読出しの数ごとの bundle（HALT の bundle を含む）とともに、MAC のある構成は `vliw-metrics/2` の `issue.register_reads`、mvp-0 は命令列から同じ数え方で数えた値である（`vliw-metrics/1` に、この欄は無い）。「-」は `read_ports` を超える数である"""
  }

  /** The fixed series: every strategy in every configuration, a table per kernel and mode. */
  private def fixedSeries(ss: Seq[Series]): String = {
    val base = ss.head
    val others = ss.tail
    val tables = for (k <- Benchmarks.all; mode <- Mode.all) yield {
      val chosen = ss.map(s => s.config.name -> record(s, k, mode).compiled.allocated.attempt).toMap
      val strategies = BenchmarkRecords.strategies(base.config.machine)
      val lines = strategies.map { st =>
        val cells = ss.map { s =>
          result(s, k, mode, st)
            .fold(noFit, c => cyclesCell(c, c.allocated.attempt == chosen(s.config.name)))
        }
        val ratios = others.map { s =>
          (result(s, k, mode, st), result(base, k, mode, st)) match {
            case (Right(a), Right(b)) => ratio(a.cycles, b.cycles)
            case _                    => "-"
          }
        }
        row(
          Seq(
            st.priority.name,
            st.limit.fold("なし")(_.registers.toString),
            st.limit.fold("-")(_.loadReserve.toString)
          ) ++ cells ++ ratios
        )
      }
      val pairs = others.map { s =>
        val both = strategies.flatMap { st =>
          (result(s, k, mode, st), result(base, k, mode, st)) match {
            case (Right(a), Right(b)) => Some(a.cycles - b.cycles)
            case _                    => None
          }
        }
        val only = strategies.count(st => result(s, k, mode, st).isRight) - both.size
        val baseOnly = strategies.count(st => result(base, k, mode, st).isRight) - both.size
        s"${s.config.name} と ${base.config.name}: 両方で成立する行 ${both.size}（${s.config.name} の cycles が少ない ${both
            .count(_ < 0)}、多い ${both.count(_ > 0)}、同じ ${both.count(_ == 0)}）、片方だけで成立する行 ${only + baseOnly}（${s.config.name} だけ $only、${base.config.name} だけ $baseOnly）"
      }
      val failed = ss.map { s =>
        val fs = strategies.flatMap(st => result(s, k, mode, st).left.toOption)
        s"${s.config.name} ${failures(fs)}"
      }
      val copied = ss.map { s =>
        val n = strategies
          .flatMap(st => result(s, k, mode, st).toOption)
          .count(c => loads(c) > c.dag.loads)
        s"${s.config.name} $n"
      }
      s"""### `${k.name}`（${mode.name}）

${head(
          Seq("優先度", "レジスタ上限", "load reserve") ++ ss.map(_.config.name) ++
            others.map(s => s"${s.config.name} / ${base.config.name}")
        )}
${lines.mkString("\n")}

- ${pairs.mkString("\n- ")}
- 不成立の行は ${failed.mkString("、")}。LD を複製した行は ${copied.mkString("、")}"""
    }
    s"""## list scheduling: 戦略を固定した系列

各行は1つの試行（優先度、レジスタ上限、load reserve）で、${ss.size}つの構成に同じ組を与えた cycles を並べる。どの構成もレジスタは同じ数なので、行は同じである。行の順はコンパイラが試す順（`RegisterAllocator.attempts`）である。**太字**は、その構成の「方式ごとに cycles 最小の系列」で選ばれた試行、「（LD n）」は、その試行が LD を複製し、カーネルの DAG より多い n 個の LD を発行することを示す（#70）。比は、両方の行が成立するときだけ書く。片方の構成だけが LD を複製する行や、複製した LD の数が違う行の比は、複製の違いの効果も含む。「不成立」の理由は [RESULTS.md](RESULTS.md) と同じで、別の試行で置き換えない。どの行の命令列も verifier が合法と判定し、参照モデルで期待した出力と、スケジューラの予測どおりの cycles になることを `BenchmarkSpec` が確かめる（RTL での確認は、方式ごとに cycles 最小の系列の記録だけ）。

${tables.mkString("\n\n")}"""
  }

  /** The read-port constraint: mvp-1 against rp4, row by row. */
  private def readPorts(ss: Seq[Series]): String = {
    val rp3 = ss.find(_.config.name == "mvp-1").get
    val rp4 = ss.find(_.config.name == "rp4").get
    val on = rp3.config.machine
    val fewestRows = for (k <- Benchmarks.all; mode <- Mode.all) yield {
      val (a, b) = (record(rp3, k, mode).compiled, record(rp4, k, mode).compiled)
      val same = if (attemptOf(a) == attemptOf(b)) "同じ" else "違う"
      row(
        Seq(
          s"`${k.name}`",
          mode.name,
          cyclesCell(a),
          cyclesCell(b),
          (a.cycles - b.cycles).toString,
          a.allocated.schedule.heldByReadPorts.toString,
          macWithSt(b.bundles).toString,
          findingsOn(on, b.layout, b.words)._1.toString,
          s"${a.allocated.attempt} / ${b.allocated.attempt}（$same）"
        )
      )
    }
    val strategies = BenchmarkRecords.strategies(on)
    val tables = Benchmarks.all.map { k =>
      val lines = strategies.map { st =>
        val (a, b) = (result(rp3, k, Mode.Vliw, st), result(rp4, k, Mode.Vliw, st))
        row(
          Seq(
            st.priority.name,
            st.limit.fold("なし")(_.registers.toString),
            st.limit.fold("-")(_.loadReserve.toString),
            a.fold(noFit, cyclesCell(_)),
            b.fold(noFit, cyclesCell(_)),
            (a, b) match {
              case (Right(x), Right(y)) => (x.cycles - y.cycles).toString
              case _                    => "-"
            },
            a.fold(_ => "-", _.allocated.schedule.heldByReadPorts.toString),
            b.fold(_ => "-", c => macWithSt(c.bundles).toString),
            b.fold(_ => "-", c => findingsOn(on, c.layout, c.words)._1.toString)
          )
        )
      }
      val diffs = strategies.flatMap { st =>
        (result(rp3, k, Mode.Vliw, st), result(rp4, k, Mode.Vliw, st)) match {
          case (Right(x), Right(y)) => Some(x.cycles - y.cycles)
          case _                    => None
        }
      }
      val one = strategies.count(st =>
        result(rp3, k, Mode.Vliw, st).isRight != result(rp4, k, Mode.Vliw, st).isRight
      )
      val hit = strategies.flatMap(st => result(rp3, k, Mode.Vliw, st).toOption)
      s"""#### `${k.name}`（vliw）

${head(
          Seq(
            "優先度",
            "レジスタ上限",
            "load reserve",
            "mvp-1",
            "rp4",
            "差（mvp-1 − rp4）",
            "`heldByReadPorts`（mvp-1）",
            "MAC と ST の bundle（rp4）",
            "L11 の違反（rp4 の命令列を mvp-1 で検査）"
          )
        )}
${lines.mkString("\n")}

- 両方で成立する行 ${diffs.size}: mvp-1 の cycles が多い ${diffs.count(_ > 0)}（差の最大 ${diffs.maxOption
          .getOrElse(0)}）、同じ ${diffs.count(_ == 0)}、少ない ${diffs.count(_ < 0)}。片方だけで成立する行 $one
- mvp-1 で `heldByReadPorts` が0でない行 ${hit.count(_.allocated.schedule.heldByReadPorts > 0)}（合計 ${hit
          .map(_.allocated.schedule.heldByReadPorts)
          .sum}）"""
    }
    val single = Benchmarks.all.map { k =>
      val same = strategies.count { st =>
        (result(rp3, k, Mode.SingleIssue, st), result(rp4, k, Mode.SingleIssue, st)) match {
          case (Right(x), Right(y)) => x.words == y.words
          case (Left(x), Left(y))   => x == y
          case _                    => false
        }
      }
      s"`${k.name}` ${strategies.size} 行のうち $same 行"
    }
    s"""## 読出しポートの制約（mvp-1 と rp4）

mvp-1 と rp4 は `read_ports` だけが違い、同じカーネル・入力・配置・DAG・試行で比べる（融合は読出しポートによらない）。差は L11 の制約（MAC と ST を同じ bundle に置けない）と、それによるスケジュールの違いの効果である。制約が当たった bundle の数は2通りに数える。mvp-1 の `heldByReadPorts`（ST が読出しポートだけで待ったサイクル）と、rp4 の命令列の MAC と ST の bundle（3本では置けない bundle）である。rp4 の命令列を mvp-1 の machine で verifier にかけると、MAC と ST の bundle ごとにちょうど1つ L11 の違反になり、ほかの違反は無い（`BenchmarkSpec` が全ての rp4 の命令列で確かめる）。

### 方式ごとに cycles 最小の系列

${head(
        Seq(
          "カーネル",
          "方式",
          "mvp-1",
          "rp4",
          "差（mvp-1 − rp4）",
          "`heldByReadPorts`（mvp-1）",
          "MAC と ST の bundle（rp4）",
          "L11 の違反（rp4 の命令列を mvp-1 で検査）",
          "試行（mvp-1 / rp4）"
        )
      )}
${fewestRows.mkString("\n")}

### 戦略を固定した系列

${tables.mkString("\n\n")}

単一発行では、1 bundle に1操作なので読出しは高々3で、制約は当たらない。戦略を固定した系列で、mvp-1 と rp4 の命令列（不成立ならその理由）が同じ行は、${single
        .mkString("、")}である。"""
  }

  /** The Modulo Scheduling section: the six loops on every configuration. */
  private def modulo(ss: Seq[Series]): String = {
    val base = ss.head
    val choice = for (k <- loops; s <- ss.tail) yield {
      val c = loop(s, k).compiled
      c.fusion match {
        case Some(f) =>
          row(
            Seq(
              ModuloRecords.label(k),
              s.config.name,
              f.pairs.toString,
              s"${f.unfusedMII} → ${f.fusedMII}",
              if (f.fuses) "融合する" else "融合しない"
            )
          )
        case None => row(Seq(ModuloRecords.label(k), s.config.name, "-", "-", "-（MAC なし）"))
      }
    }
    val results = for (k <- loops; s <- ss) yield {
      val r = loop(s, k)
      val c = r.compiled
      val mii = c.result.mii
      row(
        Seq(
          ModuloRecords.label(k),
          s.config.name,
          ModuloRecords.bound(c),
          mii.withoutReadPort.resMII.toString,
          mii.recMII.toString,
          mii.mii.toString,
          c.ii.toString,
          ModuloRecords.reasons(c.result.attempts),
          placement(c),
          c.result.span.toString,
          c.cycles.toString,
          ratio(c.cycles, loop(base, k).compiled.cycles),
          if (s.config.machine.hasMac) loopMacWithSt(c) else "-"
        )
      )
    }
    val rp3 = ss.find(_.config.name == "mvp-1").get
    val rp4 = ss.find(_.config.name == "rp4").get
    val ports = loops.map { k =>
      val (a, b) = (loop(rp3, k).compiled, loop(rp4, k).compiled)
      row(
        Seq(
          ModuloRecords.label(k),
          s"${a.ii} / ${b.ii}",
          s"${a.cycles} / ${b.cycles}",
          (a.cycles - b.cycles).toString,
          s"${placement(a)} / ${placement(b)}",
          s"${a.result.span} / ${b.result.span}",
          loopMacWithSt(b),
          findingsOn(rp3.config.machine, b.layout, b.words)._1.toString
        )
      )
    }
    def pair(k: LoopKernel) = (loop(rp3, k).compiled, loop(rp4, k).compiled)
    def more(k: LoopKernel) = pair(k) match { case (a, b) => a.cycles - b.cycles }
    val constrained = loops.filter(more(_) > 0)
    val constrainedNote =
      if (constrained.isEmpty) "mvp-1 の方が cycles の多いループは無い。"
      else
        constrained
          .map(k => s"${ModuloRecords.label(k)}（${more(k)} cycles）")
          .mkString(
            "mvp-1 の方が cycles が多いループは ",
            "、",
            "である。rp4 では、上の表の括弧に示した場所の ST が、MAC と同じ bundle に入る。"
          )
    def placed(c: ModuloCompiled) = placement(c) match {
      case "番号の順" => "番号の順の配置で置け"
      case "先読み"  => "番号の順では置けず、先読みの配置で置け"
      case other  => s"${other}を使い"
    }
    val slower = loops.filter(k => pair(k) match { case (a, b) => a.ii == b.ii && more(k) < 0 })
    val slowerNote =
      if (slower.isEmpty) "同じ II で rp4 の方が cycles の多いループは無い。"
      else
        slower.map { k =>
          val (a, b) = pair(k)
          s"${ModuloRecords.label(k)}は、同じ II ${a.ii} で rp4 の方が ${b.cycles - a.cycles} cycles 多い。rp4 では${placed(b)}、1反復が ${b.result.span} サイクルになる。mvp-1 では${placed(a)}、1反復が ${a.result.span} サイクルになる。"
        }.mkString + "読出しポートの制約の効果ではなく、配置の手順の効果である。Modulo Scheduling は II = MII から順に試し、最初に命令列ができた II を使い、配置は番号の順に後戻りなしで、番号の順で置けないときだけ先読みする。どちらも cycles を最小にしない（[ADR-0015](../docs/DECISIONS.md#adr-0015-modulo-scheduling-は-ii-を下限から順に試し最初に命令列ができた-ii-を使う)）。"
    s"""## Modulo Scheduling

`vliw.compiler.loop.LoopBenchmarks` の${loops.size}つのループを、${ss.size}つの構成で。最初の${ModuloRecords.compared.size}つは展開すると list scheduling のカーネルと同じになり、配置と入力は list scheduling と同じである（[MODULO.md](MODULO.md)。`BenchmarkSpec` が確かめる）。戦略は1つで、II = MII から探索の上限まで順に試し、最初に命令列ができた II を使う。ここでの II はループの II（反復の開始間隔）で、`metrics` の `ii` に、トレースで確かめた値（`crosscheck/ModuloTrace`）を記録する。MAC の演算器の II（`operations.MAC.ii`）ではない。

### 融合の判定

${head(Seq("ループ", "構成", "融合する組", "MII（融合しない → 融合する）", "判定"))}
${choice.mkString("\n")}

融合する組があり、融合した後の MII が融合しない MII 以下のときだけ融合する（`FusionChoice`、ADR-0019）。MII は3本目の読出しポートを除いて比べる。累算の閉路は、融合すると ADD の latency から MAC の latency に延びる。

### ループごとの結果

${head(
        Seq(
          "ループ",
          "構成",
          "ResMII（律速の資源）",
          "ResMII（3本目の読出しポートを除く）",
          "RecMII",
          "MII",
          "II",
          "MII を超えた II の理由",
          "配置",
          "1反復の長さ",
          "cycles",
          "mvp-0 に対する cycles の比",
          "MAC と ST の bundle"
        )
      )}
${results.mkString("\n")}

- MII などは、スケジュールしたグラフ（融合したときは融合したグラフ）の値である。`third read port` は、`read_ports` 3 の構成で MAC と ST が使う資源（L11）で、rp4 と mvp-0 には無い
- 理由の (a) は、1反復の演算が II 行の予約表（スロット、演算器の II（L9）、書戻しポート（L5）、3本目の読出しポート（L11））に収まらないこと、(b) は、命令列の同時に生きる値がレジスタ数を超えること（[MODULO.md](MODULO.md)）
- 配置は、選んだ II の予約表への置き方である。「番号の順」は後戻りなしの配置、「先読み」は番号の順で置けないときに、残りのノードがまだ予約表に収まる時刻だけを使う配置（[docs/COMPILER.md](../docs/COMPILER.md)「Modulo Scheduling」）
- 1反復の長さは、配置した1反復の最初の発行から、最後の演算が終わる（latency と演算器の II の大きい方）までのサイクル数
- MAC と ST の bundle の括弧は、その ST がループのどこにあるか（反復の中、ループの後）である

各記録の全項目は `metrics/<kernel>_<loop>_modulo<構成>.json`（`vliw-metrics/2`、`ii` を含む）、命令列と入力・期待値は `cases/` の同じ名前にある。構成の接尾辞は、mvp-1 が `_mac`、rp4 が `_mac_rp4`、mac3 が `_mac3` で、mvp-0 の記録は [MODULO.md](MODULO.md) の${ModuloRecords.compared.size}つである。

### 読出しポートの制約（mvp-1 と rp4）

${head(
        Seq(
          "ループ",
          "II（mvp-1 / rp4）",
          "cycles（mvp-1 / rp4）",
          "差（mvp-1 − rp4）",
          "配置（mvp-1 / rp4）",
          "1反復の長さ（mvp-1 / rp4）",
          "MAC と ST の bundle（rp4）",
          "L11 の違反（rp4 の命令列を mvp-1 で検査）"
        )
      )}
${ports.mkString("\n")}

mvp-1 と rp4 は同じグラフをスケジュールする。$constrainedNote$slowerNote"""
  }

  /** The estimates of the proposal that ADR-0018 rests on (#69, a throwaway port of `Dag.build` and
    * `ListScheduler`, 16 registers, no rematerialization): the fewest cycles in vliw by kernel and
    * configuration.
    */
  val Estimates: Map[(String, String), Int] = Map(
    ("saxpy8", "mvp-0") -> 26,
    ("saxpy8", "mvp-1") -> 27,
    ("saxpy8", "rp4") -> 26,
    ("saxpy8", "mac3") -> 27,
    ("fir4x8", "mvp-0") -> 65,
    ("fir4x8", "mvp-1") -> 40,
    ("fir4x8", "rp4") -> 40,
    ("fir4x8", "mac3") -> 46,
    ("matmul3", "mvp-0") -> 56,
    ("matmul3", "mvp-1") -> 39,
    ("matmul3", "rp4") -> 33,
    ("matmul3", "mac3") -> 40
  )

  /** The same proposal's rows of the fixed series (vliw): kernel, priority, load reserve, cycles on
    * mvp-0 (None when it does not fit), rp4 and mvp-1, and the bundles with MAC and ST on rp4.
    */
  val RowEstimates: Seq[(String, String, Int, Option[Int], Int, Int, Int)] = Seq(
    ("matmul3", "critical-path", 1, Some(56), 33, 40, 7),
    ("fir4x8", "critical-path", 0, None, 40, 46, 6)
  )

  /** The same proposal's ResMII of the loops: mvp-0, then mvp-1 (fused). */
  val MiiEstimates: Seq[(String, String, Int, Int)] =
    Seq(("fir4x8", "i", 7, 4), ("matmul3", "i", 15, 9), ("matmul3", "m", 18, 9))

  /** The notes: the estimates against the measurements, and what the records do not measure. */
  private def notes(ss: Seq[Series]): String = {
    val base = ss.head
    def named(name: String) = ss.find(_.config.name == name).get
    def plain(s: Series, k: Kernel, mode: Mode) = BenchmarkRecords
      .strategies(s.config.machine)
      .flatMap(st => result(s, k, mode, st).toOption)
      .filter(x => loads(x) == x.dag.loads)
      .minByOption(_.cycles)
    val fewestRows = for (k <- Benchmarks.all; s <- ss) yield {
      val c = record(s, k, Mode.Vliw).compiled
      row(
        Seq(
          s"`${k.name}`",
          s.config.name,
          Estimates.get(k.name -> s.config.name).fold("-")(_.toString),
          cyclesCell(c),
          plain(s, k, Mode.Vliw).fold("-")(p => s"${p.cycles}（${p.allocated.attempt}）")
        )
      )
    }
    val compared =
      for (k <- Benchmarks.all; s <- ss; e <- Estimates.get(k.name -> s.config.name))
        yield plain(s, k, Mode.Vliw).exists(_.cycles == e)
    val agree =
      if (compared.forall(identity)) "どれも見積りと同じである。"
      else s"見積りと同じなのは ${compared.size} のうち ${compared.count(identity)} である。"
    val fixedRows = RowEstimates.map { case (kernel, priority, reserve, mvp0, four, three, both) =>
      val k = Benchmarks.all.find(_.name == kernel).get
      val st = BenchmarkRecords
        .strategies(base.config.machine)
        .find(x => x.priority.name == priority && x.limit.exists(_.loadReserve == reserve))
        .get
      def at(name: String) = result(named(name), k, Mode.Vliw, st)
      row(
        Seq(
          s"`$kernel`",
          s"$priority, ${base.config.machine.registerCount} registers, load reserve $reserve",
          s"${mvp0.fold("-")(_.toString)} / $four / $three",
          Seq("mvp-0", "rp4", "mvp-1").map(at(_).fold(noFit, cyclesCell(_))).mkString(" / "),
          s"$both / ${at("rp4").fold(_ => "-", c => macWithSt(c.bundles).toString)}"
        )
      )
    }
    val miiRows = MiiEstimates.map { case (kernel, index, before, after) =>
      val k = loops.find(l => l.name == kernel && l.index == index).get
      def mii(s: Series) = loop(s, k).compiled.result.mii.resMII
      row(
        Seq(
          ModuloRecords.label(k),
          s"$before → $after",
          s"${mii(base)} → ${mii(named("mvp-1"))}"
        )
      )
    }
    val copies = for (s <- ss; k <- Benchmarks.all; mode <- Mode.all) yield {
      val c = record(s, k, mode).compiled
      Option.when(loads(c) > c.dag.loads) {
        s"${s.config.name} の `${k.name}`（${mode.name}）の ${c.cycles} cycles は、LD を複製した試行（${c.allocated.attempt}、LD ${loads(c)}）である。複製しない行の最小は ${plain(s, k, mode)
            .fold("-")(p => s"${p.cycles} cycles（${p.allocated.attempt}）")}"
      }
    }
    s"""## 注

### ADR-0018 の見積りと測定

ADR-0018 の見積りは、#69 の提案のときに、使い捨ての実装（`Dag.build` と `ListScheduler` を移したもの）で求めた値である。条件は16レジスタで、rematerialization を入れていない。ADR-0018 は、このうち `fir4x8` と `matmul3` の mvp-1 と rp4 の値を書いた。見積りと比べるのは、LD を複製しない行の最小である。$agree

方式ごとに cycles 最小（vliw）:

${head(Seq("カーネル", "構成", "見積り", "測定", "LD を複製しない行の最小（試行）"))}
${fewestRows.mkString("\n")}

戦略を固定した系列の行（vliw）:

${head(
        Seq(
          "カーネル",
          "試行",
          "見積り（mvp-0 / rp4 / mvp-1）",
          "測定（mvp-0 / rp4 / mvp-1）",
          "MAC と ST の bundle（rp4。見積り / 測定）"
        )
      )}
${fixedRows.mkString("\n")}

ループの ResMII:

${head(Seq("ループ", "見積り（mvp-0 → mvp-1）", "測定（mvp-0 → mvp-1）"))}
${miiRows.mkString("\n")}

${copies.flatten.map(t => s"- $t").mkString("\n")}

### 測っていないもの

- cycle だけを比べている。MAC の演算器の増分、4本目の読出しポートの増分（3R/2W と 4R/2W の面積）、ポート2のアドレスの選択が Fmax に効くかは、#57（Phase B）で別々に測る。それまで、cycle の比を実時間の比と読まない
- rp4 の RTL は無い（#57）。rp4 の値は参照モデルと verifier だけで確かめている
- `read_ports` 3 の損失を減らすスケジューラの工夫（ST のために MAC を遅らせる、など）は入れていない（ADR-0019）"""
  }

  /** benchmarks/MAC.md. */
  def report(ss: Seq[Series]): String =
    s"""# MAC のある構成とない構成の比較（ISA mvp-1）

このファイルは `sbt "crosscheck/Test/runMain vliw.crosscheck.BenchmarkRecords"` が生成する。手で編集しない（`crosscheck/BenchmarkSpec` が一致を確かめる）。

**cycle ベースの比較である。** Fmax・面積・電力は未測定で（#57）、時間や電力の主張はしない。cycle の比を実時間の速度比と読まない。

${conditions(ss)}

${fewest(ss)}

各記録の全項目は `metrics/<kernel>_<mode><構成>.json`（MAC のある構成は `vliw-metrics/2`）、命令列と入力・期待値は `cases/` の同じ名前にある。構成の接尾辞は、mvp-1 が `_mac`、rp4 が `_mac_rp4`、mac3 が `_mac3` で、mvp-0 の記録は [RESULTS.md](RESULTS.md) のものである。

${fixedSeries(ss)}

${readPorts(ss)}

${modulo(ss)}

${notes(ss)}
"""

  /** The files of this record under benchmarks/: the test cases and metrics of the configurations
    * with MAC (mvp-0's are RESULTS.md's and MODULO.md's), and MAC.md.
    */
  def files(ss: Seq[Series]): Seq[(Path, String)] = {
    val variants = ss.filter(_.config.path.isDefined)
    BenchmarkRecords.recordFiles(variants.flatMap(_.records)) ++
      ModuloRecords.recordFiles(variants.flatMap(_.loops)) :+
      (Dir.resolve("MAC.md") -> report(ss))
  }
}
