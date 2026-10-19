# ベンチマーク結果

このファイルは `sbt "crosscheck/Test/runMain vliw.crosscheck.BenchmarkRecords"` が生成する。手で編集しない（`crosscheck/BenchmarkSpec` が一致を確かめる）。

**cycle ベースの比較である。** Fmax・面積・電力は未測定で（#57）、時間や電力の主張はしない。cycle の比を実時間の速度比と読まない。

## 条件

| 項目 | 値 |
|---|---|
| machine | `vliw2-i32-sp2` revision 2、`sha256:a4af6064e049415dc9822614264a01f06b6e1be39c86bc41c5fcdda3e7d89e21` |
| ISA | `mvp-0` |
| カーネル | `vliw.compiler.dsl.Benchmarks`（[docs/DSL.md](../docs/DSL.md)） |
| 配置 | `MemoryLayout.fixedOrder` |
| コンパイラの試行 | 優先度（critical-path、program-order）×（上限なし、上限 16（レジスタ数）で load reserve 0〜8）（[docs/COMPILER.md](../docs/COMPILER.md)） |
| rematerialization | 上限付きの試行が、どの語も1回だけ読むままでは行き詰まるときだけ、入力・スカラー・定数の LD を複製する（[ADR-0016](../docs/DECISIONS.md#adr-0016-rematerialization-は行き詰まる試行でだけ書込みのない語の-ld-をレジスタの詰まりで複製する)、#70）。複製した行は cycles に LD の数を添える |
| 入力 | `KernelCompiler.sampleInputs(kernel, 1)`。結果と cycles は入力値によらない |
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
| `saxpy8` | vliw | 26 | 1.00 | 41 | 7 / 26 | 0.346 | 0.038 | 0.615 | 0.654 | 0.500 / 0.462 | 11 | `sha256:56f158076bf6…` |
| `saxpy8` | single | 42 | 1.62 | 41 | 7 / 26 | 0.595 | 0.405 | 0.381 | 0.405 | 0.310 / 0.286 | 16 | `sha256:b9080366df71…` |
| `fir4x8` | vliw | 65 | 1.00 | 79 | 9 / 57 | 0.123 | 0.646 | 0.862 | 0.231 | 0.185 / 0.169 | 16 | `sha256:2f97d5523854…` |
| `fir4x8` | single | 81 | 1.25 | 79 | 9 / 57 | 0.296 | 0.716 | 0.691 | 0.185 | 0.148 / 0.136 | 12 | `sha256:26f6f691c40c…` |
| `matmul3` | vliw | 56 | 1.00 | 72 | 8 / 46 | 0.179 | 0.518 | 0.804 | 0.321 | 0.250 / 0.232 | 16 | `sha256:0560f1e7441a…` |
| `matmul3` | single | 75 | 1.34 | 72 | 8 / 46 | 0.387 | 0.640 | 0.600 | 0.240 | 0.187 / 0.173 | 16 | `sha256:69ecf8733437…` |

演算数は ADD・SUB・MUL・LD・ST の合計で、HALT を含まない。単一発行の cycles は「演算数 + HALT」以上になる。

選ばれた試行（両方式で違うカーネルでは、上の比は試行の違いの効果も含む）:

| カーネル | 方式 | 試行 | 両方式で同じ試行か |
|---|---|---|---|
| `saxpy8` | vliw | critical-path, no register limit | 同じ |
| `saxpy8` | single | critical-path, no register limit, single issue | 同じ |
| `fir4x8` | vliw | program-order, 16 registers, load reserve 4 | 違う |
| `fir4x8` | single | program-order, no register limit, single issue | 違う |
| `matmul3` | vliw | critical-path, 16 registers, load reserve 1 | 違う |
| `matmul3` | single | program-order, 16 registers, load reserve 0, single issue | 違う |

各記録の全項目は `metrics/<kernel>_<mode>.json`（`vliw-metrics/1`、[docs/METRICS.md](../docs/METRICS.md)）、命令列と入力・期待値は `cases/<kernel>_<mode>.json`（`vliw-testcase/1`）にある。

## 戦略を固定した系列

各行は1つの試行（優先度、レジスタ上限、load reserve）で、両方の方式に同じ組を与えた cycles を並べる。行の順はコンパイラが試す順（`RegisterAllocator.attempts`）で、cycles と LD の数が同じならば先の行の試行が選ばれる。**太字**は、上の「方式ごとに cycles 最小の系列」で選ばれた試行である。「（LD n）」は、その試行が LD を複製し、カーネルの DAG より多い n 個の LD を発行することを示す（#70）。「不成立」は、その試行ではレジスタに収まらないことを示し、別の試行で置き換えない（spill はしない）。理由は2つある。「同時に生きる値 n > r」は、上限なしのスケジュールが同時に n 個の値を生かし、レジスタ r 本に割り当てられないこと。「上限の下で行き詰まる」は、上限付きのスケジューラが、LD を複製しても、残りのどの操作も上限を超えずに置けない状態になること。どの行の命令列も verifier が合法と判定し、参照モデルで同じ出力と cycles になることを `BenchmarkSpec` が確かめる（RTL での確認は、上の系列の記録だけ）。

### `saxpy8`

| 優先度 | レジスタ上限 | load reserve | vliw の cycles | single の cycles | single / vliw |
|---|---|---|---|---|---|
| critical-path | なし | - | **26** | **42** | 1.62 |
| program-order | なし | - | 28 | 43 | 1.54 |
| critical-path | 16 | 0 | 26 | 42 | 1.62 |
| critical-path | 16 | 1 | 26 | 42 | 1.62 |
| critical-path | 16 | 2 | 26 | 42 | 1.62 |
| critical-path | 16 | 3 | 26 | 42 | 1.62 |
| critical-path | 16 | 4 | 26 | 42 | 1.62 |
| critical-path | 16 | 5 | 26 | 42 | 1.62 |
| critical-path | 16 | 6 | 27 | 42 | 1.56 |
| critical-path | 16 | 7 | 28 | 43 | 1.54 |
| critical-path | 16 | 8 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - |
| program-order | 16 | 0 | 28 | 43 | 1.54 |
| program-order | 16 | 1 | 28 | 43 | 1.54 |
| program-order | 16 | 2 | 28 | 43 | 1.54 |
| program-order | 16 | 3 | 28 | 43 | 1.54 |
| program-order | 16 | 4 | 28 | 43 | 1.54 |
| program-order | 16 | 5 | 28 | 43 | 1.54 |
| program-order | 16 | 6 | 28 | 43 | 1.54 |
| program-order | 16 | 7 | 28 | 43 | 1.54 |
| program-order | 16 | 8 | 28 | 43 | 1.54 |

### `fir4x8`

| 優先度 | レジスタ上限 | load reserve | vliw の cycles | single の cycles | single / vliw |
|---|---|---|---|---|---|
| critical-path | なし | - | 不成立: 同時に生きる値 33 > 16 | 不成立: 同時に生きる値 33 > 16 | - |
| program-order | なし | - | 不成立: 同時に生きる値 36 > 16 | **81** | - |
| critical-path | 16 | 0 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - |
| critical-path | 16 | 1 | 75（LD 47） | 100（LD 33） | 1.33 |
| critical-path | 16 | 2 | 84（LD 52） | 105（LD 38） | 1.25 |
| critical-path | 16 | 3 | 84（LD 52） | 103（LD 36） | 1.23 |
| critical-path | 16 | 4 | 83（LD 54） | 102（LD 35） | 1.23 |
| critical-path | 16 | 5 | 87（LD 53） | 104（LD 37） | 1.20 |
| critical-path | 16 | 6 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - |
| critical-path | 16 | 7 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - |
| critical-path | 16 | 8 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - |
| program-order | 16 | 0 | 67 | 81 | 1.21 |
| program-order | 16 | 1 | 66 | 81 | 1.23 |
| program-order | 16 | 2 | 66 | 81 | 1.23 |
| program-order | 16 | 3 | 66 | 81 | 1.23 |
| program-order | 16 | 4 | **65** | 81 | 1.25 |
| program-order | 16 | 5 | 66 | 84 | 1.27 |
| program-order | 16 | 6 | 66 | 86 | 1.30 |
| program-order | 16 | 7 | 67 | 86 | 1.28 |
| program-order | 16 | 8 | 76 | 91 | 1.20 |

### `matmul3`

| 優先度 | レジスタ上限 | load reserve | vliw の cycles | single の cycles | single / vliw |
|---|---|---|---|---|---|
| critical-path | なし | - | 不成立: 同時に生きる値 28 > 16 | 不成立: 同時に生きる値 28 > 16 | - |
| program-order | なし | - | 不成立: 同時に生きる値 29 > 16 | 不成立: 同時に生きる値 19 > 16 | - |
| critical-path | 16 | 0 | 61（LD 37） | 不成立: 上限の下で行き詰まる | - |
| critical-path | 16 | 1 | **56** | 89（LD 32） | 1.59 |
| critical-path | 16 | 2 | 57 | 98（LD 43） | 1.72 |
| critical-path | 16 | 3 | 62 | 79 | 1.27 |
| critical-path | 16 | 4 | 67（LD 44） | 78 | 1.16 |
| critical-path | 16 | 5 | 72（LD 46） | 91（LD 33） | 1.26 |
| critical-path | 16 | 6 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - |
| critical-path | 16 | 7 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - |
| critical-path | 16 | 8 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - |
| program-order | 16 | 0 | 57 | **75** | 1.32 |
| program-order | 16 | 1 | 56 | 76 | 1.36 |
| program-order | 16 | 2 | 59 | 76 | 1.29 |
| program-order | 16 | 3 | 63 | 76 | 1.21 |
| program-order | 16 | 4 | 56（LD 37） | 81（LD 25） | 1.45 |
| program-order | 16 | 5 | 56（LD 38） | 82（LD 26） | 1.46 |
| program-order | 16 | 6 | 57（LD 39） | 83（LD 27） | 1.46 |
| program-order | 16 | 7 | 58（LD 39） | 84（LD 28） | 1.45 |
| program-order | 16 | 8 | 59（LD 41） | 87（LD 31） | 1.47 |

## 資源条件を揃えた固定パイプライン（#75）

命令メモリ・命令制御・RF を持たない、カーネル専用の回路（`vliw.hw.FixedPipeline`）の core latency。cycles は VLIW の記録と同じく start の受理（E_0）から done までで、ホストによる初期配置と完了後の読出しを含めない。ChiselSim で `FixedDriver`（`CoreDriver` と同じ数え方）が測り、`BenchmarkSpec` が上の記録と同じ入力で cycles と出力を確かめる。**cycle の比を実時間の速度比と呼ばない**（Fmax・面積は未測定。#80）。

| カーネル | single | vliw | 固定: VLIW の命令列 | 固定: 上限なし | 下限（固定） |
|---|---|---|---|---|---|
| `saxpy8` | 42 | 26 | 26 | 26 | 26（memory） |
| `fir4x8` | 81 | 65 | 65 | 63 | 62（compute + L5） |
| `matmul3` | 75 | 56 | 56 | 52 | 51（compute + L5） |

| 系列 | スケジュール | 値の置き場 |
|---|---|---|
| 固定: VLIW の命令列 | 上の vliw の行の命令列と同じ発行サイクル（同じ試行、同じ DAG） | VLIW のレジスタ割当て（16 本以内）。両方の書戻しポートから書く語には 2:1 の mux |
| 固定: 上限なし | VLIW と同じ `ListScheduler` を、同じ DAG に、レジスタの上限なしで、両方の優先度で（cycles 最小、同じならば値レジスタ最少、それも同じならば先の優先度）。VLIW との違いは RF の容量だけである | 寿命が重ならない値で共有（書戻しポートごとの left-edge。書込みの mux なし）。値レジスタは 16 本を超えてよい（**差**） |

下限は、どの合法な計画にも成り立つ値で、理由を添える。memory: Memory の操作は1サイクルに1つ。compute: 最初の演算は LD の値だけを読み（2語を読むならば 1 + L(LD) 以降）、演算は1サイクルに1つ、最後の演算の後に ST が続く。L5: 最初になれる演算がすべてレイテンシー l+1、最後になれる演算がすべて l ならば、どこかで l+1 の演算の次に l の演算が来て、その2つを続くサイクルに置くと同じ境界に書き戻すので、Compute が1サイクル空く。cycles は、最後の書込みの境界 T に1を足した値である（下の「終了」）。

### 揃えた条件と、揃えられない差

| 項目 | VLIW（`Core`） | 固定パイプライン（`FixedPipeline`） |
|---|---|---|
| 算術・カーネル・入力 | 32bit、2^32 を法とする。`Benchmarks` の3カーネル | 同じ。同じ `ComputeSlot` |
| 配置 | `MemoryLayout.fixedOrder` | 同じ。入力・scalar・定数（FIR の係数を含む）を、layout のアドレスから LD で読む。アドレスは回路の定数 |
| Scratchpad | 2 bank × 256 語、1RW、1サイクル1アクセス | 同じ `MemorySlot` |
| 演算器 | ADD・SUB・MUL、レイテンシー ADD 1、SUB 1、MUL 2、LD 2、ST 1、II 1。Compute の発行は1サイクルに1つ、書戻しは境界ごとに1つ（L5） | 同じ `ComputeSlot`（発行1つ、書戻し1つ）。計画が使わない種類（SUB）の演算器も slot の中にあるが、kind の decode がそれを選ばない（合成で消えるかは #80） |
| MUL→ADD の連結 | なし | なし。値はいつもレジスタを経る（MAC は #77、#80） |
| 中間値（**差**） | 16 本の RF（3R/2W） | 値のレジスタ。読む側の mux は、そのポートが読むレジスタだけを入力にする |
| 制御（**差**） | 命令メモリ 256 bundle、PC、decoder、HALT | サイクルカウンタと、その値の decode。選択・有効・アドレスはすべてサイクルの定数 |
| 終了 | HALT を h（全ての操作が完了する境界、L6）に発行し、E_(h+1) で done（h+1 cycles） | HALT の命令はない。最後の書込みの境界 E_T の cycle T を HALT の cycle とみなし、E_(T+1) で done（T+1 cycles）。同じスケジュールは VLIW と同じ cycles になる。E_T で done を上げれば1サイクル少ない（HALT の bundle の分。この表では使わない） |
| 違反の扱い | L1〜L7 を信号にし、assertion にする | 計画を組み立て時に検査する（`FixedPlan.violations`: スロット、L3、L5、L7、L9。L4 にあたる値のレジスタの重なりは `FixedPlan.storageViolations`。命令列がないので L1・L2・L6 は対象外）。シミュレーションでは書戻しと範囲の assertion |
| 対応する machine | `CoreParams.check` の範囲 | 同じ範囲を同じ理由で拒否する |

### 回路の固定の遅延と II の契約

- 開始: idle 中だけ start を受理し、受理の境界を E_0 とする。busy 中の start は無視する。
- 遅延: 1回の実行は T+1 cycles（上の表）で、入力値によらない。busy は cycle 0〜T に high、done は cycle T+1 に1サイクルだけ high になり、そのとき出力は Scratchpad にある。
- 操作: サイクル s に発行した LD と演算は E_(s+L) でレジスタに書き、ST は E_(s+1) で Scratchpad に書く（L は machine description の値）。
- II: 実行が終わるまで次の start を受けない。次の start を cycle T+1（done の cycle）に出すと E_(T+2) で受理するので、受理から次の受理まで最短で T+2 cycles（Core と同じ）。これはホストが入出力をしない場合で、ホストは idle の間（cycle T+1 以降）だけ Scratchpad を読み書きできるので、入力を書き出力を読むときは、その cycles が加わる。入出力のストリーミングを含む throughput・II は測っていない。

### 係数可変

FIR の係数は layout の定数の語（`fir4x8` ではアドレス 11、12、13、14）から読み、回路は係数の値を持たない。互いに異なる4つの係数ならば、layout と DAG は (1,2,3,4) と同じになり、同じ回路になる。等しい係数を含む組（例 (2,2,3,3)）では、`MemoryLayout` が同じ値の定数を1語にまとめるので、配置（出力のアドレスを含む）と DAG が変わり、別の layout・別の回路として扱う。(1,2,3,4) の回路に、その4つの値を係数の4語として書けば、出力は DSL の参照実装と一致する。どちらも `FixedPipelineKernelSpec` が確かめる。

### RTL のパラメータ

| カーネル | 系列 | 計画 | T（最後の書込み） | 値レジスタ（32bit） | operand mux の入力（A / B / ST） | カウンタ | 演算器 |
|---|---|---|---|---|---|---|---|
| `saxpy8` | VLIW の命令列 | the VLIW program's schedule and registers (critical-path, no register limit) | 25 | 11（Compute 9 / Memory 10 / 両方 8） | 9 / 10 / 5 | 5 bit | ADD（L1）、MUL（L2） |
| `saxpy8` | 上限なし | critical-path, no register limit | 25 | 10（Compute 8 / Memory 2 / 両方 0） | 9 / 2 / 5 | 5 bit | ADD（L1）、MUL（L2） |
| `fir4x8` | VLIW の命令列 | the VLIW program's schedule and registers (program-order, 16 registers, load reserve 4) | 64 | 16（Compute 16 / Memory 11 / 両方 11） | 10 / 15 / 2 | 7 bit | ADD（L1）、MUL（L2） |
| `fir4x8` | 上限なし | program-order, no register limit | 62 | 44（Compute 32 / Memory 12 / 両方 0） | 12 / 33 / 1 | 6 bit | ADD（L1）、MUL（L2） |
| `matmul3` | VLIW の命令列 | the VLIW program's schedule and registers (critical-path, 16 registers, load reserve 1) | 55 | 16（Compute 16 / Memory 12 / 両方 12） | 15 / 14 / 3 | 6 bit | ADD（L1）、MUL（L2） |
| `matmul3` | 上限なし | critical-path, no register limit | 51 | 38（Compute 27 / Memory 11 / 両方 0） | 21 / 26 / 1 | 6 bit | ADD（L1）、MUL（L2） |

### 条件の出典

| 項目 | 値 |
|---|---|
| machine | `vliw2-i32-sp2` revision 2、`sha256:a4af6064e049415dc9822614264a01f06b6e1be39c86bc41c5fcdda3e7d89e21` |
| ツール | Chisel 7.15.0、firtool 1.158.0、Verilator 5.052、Scala 2.13.18、JDK 21 |
| 計測 | ChiselSim（Verilator、`VliwChiselSim`）。`FixedDriver` が start から done までを数える |
