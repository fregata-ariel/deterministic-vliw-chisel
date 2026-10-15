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
| 入力 | `KernelCompiler.sampleInputs(kernel, 1)`。結果と cycles は入力値によらない |
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

各行は1つの試行（優先度、レジスタ上限、load reserve）で、両方の方式に同じ組を与えた cycles を並べる。行の順はコンパイラが試す順（`RegisterAllocator.attempts`）で、cycles が同じならば先の行の試行が選ばれる。**太字**は、上の「方式ごとに cycles 最小の系列」で選ばれた試行である。「不成立」は、その試行ではレジスタに収まらないことを示し、別の試行で置き換えない（spill はしない）。理由は2つある。「同時に生きる値 n > r」は、上限なしのスケジュールが同時に n 個の値を生かし、レジスタ r 本に割り当てられないこと。「上限の下で行き詰まる」は、上限付きのスケジューラが、残りのどの操作も上限を超えずに置けない状態になること。どの行の命令列も verifier が合法と判定し、参照モデルで同じ出力と cycles になることを `BenchmarkSpec` が確かめる（RTL での確認は、上の系列の記録だけ）。

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
| critical-path | 16 | 1 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - |
| critical-path | 16 | 2 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - |
| critical-path | 16 | 3 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - |
| critical-path | 16 | 4 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - |
| critical-path | 16 | 5 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - |
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
| critical-path | 16 | 0 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - |
| critical-path | 16 | 1 | **56** | 不成立: 上限の下で行き詰まる | - |
| critical-path | 16 | 2 | 57 | 不成立: 上限の下で行き詰まる | - |
| critical-path | 16 | 3 | 62 | 79 | 1.27 |
| critical-path | 16 | 4 | 不成立: 上限の下で行き詰まる | 78 | - |
| critical-path | 16 | 5 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - |
| critical-path | 16 | 6 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - |
| critical-path | 16 | 7 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - |
| critical-path | 16 | 8 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - |
| program-order | 16 | 0 | 57 | **75** | 1.32 |
| program-order | 16 | 1 | 56 | 76 | 1.36 |
| program-order | 16 | 2 | 59 | 76 | 1.29 |
| program-order | 16 | 3 | 63 | 76 | 1.21 |
| program-order | 16 | 4 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - |
| program-order | 16 | 5 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - |
| program-order | 16 | 6 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - |
| program-order | 16 | 7 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - |
| program-order | 16 | 8 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - |
