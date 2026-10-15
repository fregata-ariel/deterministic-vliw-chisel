# ベンチマーク結果

このファイルは `sbt "crosscheck/Test/runMain vliw.crosscheck.BenchmarkRecords"` が生成する。手で編集しない（`crosscheck/BenchmarkSpec` が一致を確かめる）。

**cycle ベースの比較である。** Fmax・面積・電力は未測定で（#57）、時間や電力の主張はしない。

## 条件

| 項目 | 値 |
|---|---|
| machine | `vliw2-i32-sp2` revision 2、`sha256:a4af6064e049415dc9822614264a01f06b6e1be39c86bc41c5fcdda3e7d89e21` |
| ISA | `mvp-0` |
| カーネル | `vliw.compiler.dsl.Benchmarks`（[docs/DSL.md](../docs/DSL.md)） |
| 配置 | `MemoryLayout.fixedOrder` |
| コンパイラ | 優先度（クリティカルパス、プログラム順）× レジスタ上限の試行のうち cycles 最小（[docs/COMPILER.md](../docs/COMPILER.md)） |
| 入力 | `KernelCompiler.sampleInputs(kernel, 1)`。結果と cycles は入力値によらない |
| 実行 | 参照モデル（`vliw.sim.ReferenceModel`）。RTL でも同じ cycles と出力になることを `BenchmarkSpec` が確かめる |
| 方式 | `vliw`: 1 bundle に Compute 1・Memory 1。`single`: 同じ machine・DAG・配置で、1サイクルに1操作だけ発行する（単一発行ベースライン）。どちらも、試行のうち cycles 最小のものを方式ごとに選ぶ |

## 結果

| カーネル | 方式 | cycles | cycles の vliw に対する比 | 演算数 | 下限（依存 / スロット） | Compute NOP率 | Memory NOP率 | 書戻し Compute 利用率 | 書戻し Memory 利用率 | bank アクセス/cycle | レジスタ | 命令列 hash |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `saxpy8` | vliw | 26 | 1.00 | 41 | 7 / 26 | 0.346 | 0.038 | 0.615 | 0.654 | 0.500 / 0.462 | 11 | `sha256:56f158076bf6…` |
| `saxpy8` | single | 42 | 1.62 | 41 | 7 / 26 | 0.595 | 0.405 | 0.381 | 0.405 | 0.310 / 0.286 | 16 | `sha256:b9080366df71…` |
| `fir4x8` | vliw | 65 | 1.00 | 79 | 9 / 57 | 0.123 | 0.646 | 0.862 | 0.231 | 0.185 / 0.169 | 16 | `sha256:2f97d5523854…` |
| `fir4x8` | single | 81 | 1.25 | 79 | 9 / 57 | 0.296 | 0.716 | 0.691 | 0.185 | 0.148 / 0.136 | 12 | `sha256:26f6f691c40c…` |
| `matmul3` | vliw | 56 | 1.00 | 72 | 8 / 46 | 0.179 | 0.518 | 0.804 | 0.321 | 0.250 / 0.232 | 16 | `sha256:0560f1e7441a…` |
| `matmul3` | single | 75 | 1.34 | 72 | 8 / 46 | 0.387 | 0.640 | 0.600 | 0.240 | 0.187 / 0.173 | 16 | `sha256:69ecf8733437…` |

演算数は ADD・SUB・MUL・LD・ST の合計で、HALT を含まない。単一発行の cycles は「演算数 + HALT」以上になる。

## 選ばれた試行

方式ごとに選ばれた試行（優先度・レジスタ割り当て）が違うので、上の cycles の比は発行幅だけの効果ではない。戦略を固定した系列は #79 で加える。

| カーネル | 方式 | 試行 |
|---|---|---|
| `saxpy8` | vliw | critical-path, no register limit |
| `saxpy8` | single | critical-path, no register limit, single issue |
| `fir4x8` | vliw | program-order, 16 registers, load reserve 4 |
| `fir4x8` | single | program-order, no register limit, single issue |
| `matmul3` | vliw | critical-path, 16 registers, load reserve 1 |
| `matmul3` | single | program-order, 16 registers, load reserve 0, single issue |

各記録の全項目は `metrics/<kernel>_<mode>.json`（`vliw-metrics/1`、[docs/METRICS.md](../docs/METRICS.md)）、命令列と入力・期待値は `cases/<kernel>_<mode>.json`（`vliw-testcase/1`）にある。
