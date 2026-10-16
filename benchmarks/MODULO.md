# Modulo Scheduling の記録

このファイルは `sbt "crosscheck/Test/runMain vliw.crosscheck.BenchmarkRecords"` が生成する。手で編集しない（`crosscheck/BenchmarkSpec` が一致を確かめる）。

**cycle ベースの比較である。** Fmax・面積・電力は未測定で（#57）、時間や電力の主張はしない。cycle の比を実時間の速度比と読まない。

## 条件

| 項目 | 値 |
|---|---|
| machine | `vliw2-i32-sp2` revision 2、`sha256:a4af6064e049415dc9822614264a01f06b6e1be39c86bc41c5fcdda3e7d89e21` |
| ループ | `vliw.compiler.loop.LoopBenchmarks`（[docs/COMPILER.md](../docs/COMPILER.md)「ループ IR」）。展開すると list scheduling のカーネルと同じになる |
| 配置と入力 | list scheduling と同じ（展開したカーネルの `MemoryLayout.fixedOrder`、`KernelCompiler.sampleInputs(kernel, 1)`） |
| Modulo Scheduling | II = MII から探索の上限（1反復を list scheduling した長さ）まで順に試し、最初に命令列ができた II を使う（[docs/COMPILER.md](../docs/COMPILER.md)「Modulo Scheduling」）。戦略は1つで、cycles 最小の II とは限らない |
| 実行 | 参照モデル。II は、トレースで確かめた値（`crosscheck/ModuloTrace`）を `metrics` の `ii` に記録する。RTL でも同じ cycles と出力になることを `BenchmarkSpec` が確かめる |

## ループごとの結果

| ループ | 反復 | ResMII（律速の資源） | RecMII | 探索の上限 | II | MII を超えた II の理由 | 開始 S | 配置した1反復の長さ | bundle | cycles | 同時に生きる値 | LD（ループの前 + 1反復 × 反復） | LD（list scheduling） |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `saxpy8`（i） | 8 | 3（`memory slot`） | 0 | 6 | 3 | - | 1 | 6 | 29 | 29 | 4 | 1 + 2 × 8 = 17 | 17 |
| `fir4x8`（i） | 8 | 7（`compute slot`、`compute write-back port`） | 0 | 11 | 8 | II 7: (a) 予約表なし（slot + write-back port (L5)） | 4 | 15 | 76 | 76 | 12 | 7 + 1 × 8 = 15 | 15 |
| `matmul3`（i） | 3 | 15（`compute slot`、`compute write-back port`） | 0 | 24 | 20 | II 15: (a) 予約表なし（slot + write-back port (L5)）。II 16〜19: (b) レジスタ（同時に生きる値 22、20、19、17 > 16） | 2 | 44 | 87 | 87 | 16 | 9 + 3 × 3 = 18 | 18 |
| `matmul3`（m） | 2 | 18（`compute slot`、`compute write-back port`） | 1 | 30 | 27 | II 18: (a) 予約表なし（slot + write-back port (L5)）。II 19〜26: (b) レジスタ（同時に生きる値 23、22、21、20、19、18、17、17 > 16） | 9 | 30 | 68 | 68 | 16 | 6 + 6 × 2 = 18 | 18 |

(a) は、1反復の演算が II 行の予約表（スロット、演算器の II（L9）、書戻しポート（L5））に収まらないこと、(b) は、命令列の同時に生きる値がレジスタ数を超えること。比べる4つのループのうち、RecMII > 0 は `matmul3`（m）（RecMII 1、ResMII 18 より小さい）だけ。RecMII が II を決めるループは、下の「他のループと構成」の `product8`。

各記録の全項目は `metrics/<kernel>_<loop>_modulo.json`（`vliw-metrics/1`、`ii` を含む）、命令列と入力・期待値は `cases/<kernel>_<loop>_modulo.json` にある。

## list scheduling（戦略を固定した系列）との比較

list scheduling の各行は、[RESULTS.md](RESULTS.md) の「戦略を固定した系列」の vliw の列で、同じ配置と入力である。「（LD n）」の行は、その試行が LD を複製した行で（#70）、LD の数が Modulo Scheduling（上の表の LD の列）と違う。Modulo Scheduling の側は1つの戦略なので、各行は「その試行の list scheduling」と「Modulo Scheduling」の比になる。

### `saxpy8`

Modulo Scheduling: `saxpy8`（i） 29 cycles（II 3）。list scheduling の、方式ごとに cycles 最小の系列（vliw）は 26 cycles（critical-path, no register limit）。

| 優先度 | レジスタ上限 | load reserve | list scheduling の cycles（vliw） | Modulo（i、29 cycles）/ list |
|---|---|---|---|---|
| critical-path | なし | - | 26 | 1.12 |
| program-order | なし | - | 28 | 1.04 |
| critical-path | 16 | 0 | 26 | 1.12 |
| critical-path | 16 | 1 | 26 | 1.12 |
| critical-path | 16 | 2 | 26 | 1.12 |
| critical-path | 16 | 3 | 26 | 1.12 |
| critical-path | 16 | 4 | 26 | 1.12 |
| critical-path | 16 | 5 | 26 | 1.12 |
| critical-path | 16 | 6 | 27 | 1.07 |
| critical-path | 16 | 7 | 28 | 1.04 |
| critical-path | 16 | 8 | 不成立: 上限の下で行き詰まる | - |
| program-order | 16 | 0 | 28 | 1.04 |
| program-order | 16 | 1 | 28 | 1.04 |
| program-order | 16 | 2 | 28 | 1.04 |
| program-order | 16 | 3 | 28 | 1.04 |
| program-order | 16 | 4 | 28 | 1.04 |
| program-order | 16 | 5 | 28 | 1.04 |
| program-order | 16 | 6 | 28 | 1.04 |
| program-order | 16 | 7 | 28 | 1.04 |
| program-order | 16 | 8 | 28 | 1.04 |

不成立の行は 1（上限の下で行き詰まる 1）で、比に含めない。

### `fir4x8`

Modulo Scheduling: `fir4x8`（i） 76 cycles（II 8）。list scheduling の、方式ごとに cycles 最小の系列（vliw）は 65 cycles（program-order, 16 registers, load reserve 4）。

| 優先度 | レジスタ上限 | load reserve | list scheduling の cycles（vliw） | Modulo（i、76 cycles）/ list |
|---|---|---|---|---|
| critical-path | なし | - | 不成立: 同時に生きる値 33 > 16 | - |
| program-order | なし | - | 不成立: 同時に生きる値 36 > 16 | - |
| critical-path | 16 | 0 | 不成立: 上限の下で行き詰まる | - |
| critical-path | 16 | 1 | 75（LD 47） | 1.01 |
| critical-path | 16 | 2 | 84（LD 52） | 0.90 |
| critical-path | 16 | 3 | 84（LD 52） | 0.90 |
| critical-path | 16 | 4 | 83（LD 54） | 0.92 |
| critical-path | 16 | 5 | 87（LD 53） | 0.87 |
| critical-path | 16 | 6 | 不成立: 上限の下で行き詰まる | - |
| critical-path | 16 | 7 | 不成立: 上限の下で行き詰まる | - |
| critical-path | 16 | 8 | 不成立: 上限の下で行き詰まる | - |
| program-order | 16 | 0 | 67 | 1.13 |
| program-order | 16 | 1 | 66 | 1.15 |
| program-order | 16 | 2 | 66 | 1.15 |
| program-order | 16 | 3 | 66 | 1.15 |
| program-order | 16 | 4 | 65 | 1.17 |
| program-order | 16 | 5 | 66 | 1.15 |
| program-order | 16 | 6 | 66 | 1.15 |
| program-order | 16 | 7 | 67 | 1.13 |
| program-order | 16 | 8 | 76 | 1.00 |

不成立の行は 6（同時に生きる値が多い 2、上限の下で行き詰まる 4）で、比に含めない。

### `matmul3`

Modulo Scheduling: `matmul3`（i） 87 cycles（II 20）、`matmul3`（m） 68 cycles（II 27）。list scheduling の、方式ごとに cycles 最小の系列（vliw）は 56 cycles（critical-path, 16 registers, load reserve 1）。

| 優先度 | レジスタ上限 | load reserve | list scheduling の cycles（vliw） | Modulo（i、87 cycles）/ list | Modulo（m、68 cycles）/ list |
|---|---|---|---|---|---|
| critical-path | なし | - | 不成立: 同時に生きる値 28 > 16 | - | - |
| program-order | なし | - | 不成立: 同時に生きる値 29 > 16 | - | - |
| critical-path | 16 | 0 | 61（LD 37） | 1.43 | 1.11 |
| critical-path | 16 | 1 | 56 | 1.55 | 1.21 |
| critical-path | 16 | 2 | 57 | 1.53 | 1.19 |
| critical-path | 16 | 3 | 62 | 1.40 | 1.10 |
| critical-path | 16 | 4 | 67（LD 44） | 1.30 | 1.01 |
| critical-path | 16 | 5 | 72（LD 46） | 1.21 | 0.94 |
| critical-path | 16 | 6 | 不成立: 上限の下で行き詰まる | - | - |
| critical-path | 16 | 7 | 不成立: 上限の下で行き詰まる | - | - |
| critical-path | 16 | 8 | 不成立: 上限の下で行き詰まる | - | - |
| program-order | 16 | 0 | 57 | 1.53 | 1.19 |
| program-order | 16 | 1 | 56 | 1.55 | 1.21 |
| program-order | 16 | 2 | 59 | 1.47 | 1.15 |
| program-order | 16 | 3 | 63 | 1.38 | 1.08 |
| program-order | 16 | 4 | 56（LD 37） | 1.55 | 1.21 |
| program-order | 16 | 5 | 56（LD 38） | 1.55 | 1.21 |
| program-order | 16 | 6 | 57（LD 39） | 1.53 | 1.19 |
| program-order | 16 | 7 | 58（LD 39） | 1.50 | 1.17 |
| program-order | 16 | 8 | 59（LD 41） | 1.47 | 1.15 |

不成立の行は 5（同時に生きる値が多い 2、上限の下で行き詰まる 3）で、比に含めない。

## 他のループと構成（#68 の受入テストを含む）

`LoopBenchmarks.all` の各ループを、既定の構成（上の4つを除く）、`slow_units`、`ii_units` で。`regs3` は、レジスタが足りずに II が上がる例と、どの II でも収まらない例。list scheduling の記録（#79）の対象外。

| ループ | machine | ResMII（律速の資源） | RecMII | 探索の上限 | II | MII を超えた II の理由 | cycles |
|---|---|---|---|---|---|---|---|
| `product8`（i） | `vliw2-i32-sp2` rev 2（`sha256:a4af6064e049…`） | 1（`compute slot`、`memory slot`、`compute write-back port`、`memory write-back port`、`LD unit`、`MUL unit`） | 2 | 4 | 2 | - | 19 |
| `dot8`（i） | `vliw2-i32-sp2` rev 2（`sha256:a4af6064e049…`） | 2（`compute slot`、`memory slot`、`compute write-back port`、`memory write-back port`、`LD unit`） | 1 | 6 | 3 | II 2: (a) 予約表なし（slot + write-back port (L5)） | 28 |
| `saxpy8`（i） | `vliw2-i32-sp2-slow` rev 1（`sha256:bf5cc152c727…`） | 3（`memory slot`） | 0 | 9 | 3 | - | 33 |
| `fir4x8`（i） | `vliw2-i32-sp2-slow` rev 1（`sha256:bf5cc152c727…`） | 7（`compute slot`、`compute write-back port`） | 0 | 11 | 8 | II 7: (a) 予約表なし（slot + write-back port (L5)） | 74 |
| `matmul3`（i） | `vliw2-i32-sp2-slow` rev 1（`sha256:bf5cc152c727…`） | 15（`compute slot`、`compute write-back port`） | 0 | 24 | 19 | II 15: (a) 予約表なし（slot + write-back port (L5)）。II 16〜18: (b) レジスタ（同時に生きる値 18、17、17 > 16） | 65 |
| `matmul3`（m） | `vliw2-i32-sp2-slow` rev 1（`sha256:bf5cc152c727…`） | 18（`compute slot`、`compute write-back port`） | 1 | 24 | - | 不成立: II 18〜24: (b) レジスタ（同時に生きる値 17、17、17、17、17、17、17 > 16）（II 18〜24 のどれにも収まらない） | - |
| `product8`（i） | `vliw2-i32-sp2-slow` rev 1（`sha256:bf5cc152c727…`） | 1（`compute slot`、`memory slot`、`compute write-back port`、`memory write-back port`、`LD unit`、`MUL unit`） | 3 | 6 | 3 | - | 28 |
| `dot8`（i） | `vliw2-i32-sp2-slow` rev 1（`sha256:bf5cc152c727…`） | 2（`compute slot`、`memory slot`、`compute write-back port`、`memory write-back port`、`LD unit`） | 1 | 8 | 2 | - | 25 |
| `saxpy8`（i） | `vliw2-i32-sp2-ii` rev 1（`sha256:644ed9a5ad49…`） | 6（`LD unit`） | 0 | 7 | 6 | - | 54 |
| `fir4x8`（i） | `vliw2-i32-sp2-ii` rev 1（`sha256:644ed9a5ad49…`） | 8（`MUL unit`） | 0 | 12 | 11 | II 8〜10: (a) 予約表なし（slot + unit (L9) + write-back port (L5)） | 106 |
| `matmul3`（i） | `vliw2-i32-sp2-ii` rev 1（`sha256:644ed9a5ad49…`） | 18（`MUL unit`） | 0 | 28 | 24 | II 18〜23: (a) 予約表なし（slot + unit (L9) + write-back port (L5)） | 91 |
| `matmul3`（m） | `vliw2-i32-sp2-ii` rev 1（`sha256:644ed9a5ad49…`） | 18（`compute slot`、`compute write-back port`、`ADD unit`、`LD unit`、`MUL unit`） | 1 | 33 | 27 | II 18: (a) 予約表なし（slot + write-back port (L5)）。II 19〜26: (a) 予約表なし（slot + unit (L9) + write-back port (L5)） | 82 |
| `product8`（i） | `vliw2-i32-sp2-ii` rev 1（`sha256:644ed9a5ad49…`） | 3（`LD unit`） | 2 | 4 | 3 | - | 27 |
| `dot8`（i） | `vliw2-i32-sp2-ii` rev 1（`sha256:644ed9a5ad49…`） | 6（`LD unit`） | 1 | 9 | 6 | - | 52 |
| `saxpy8`（i） | `vliw2-i32-sp2-regs3` rev 1（`sha256:dd9bc1e79bff…`） | 3（`memory slot`） | 0 | 6 | 6 | II 3〜5: (b) レジスタ（同時に生きる値 4、4、4 > 3） | 50 |
| `dot8`（i） | `vliw2-i32-sp2-regs3` rev 1（`sha256:dd9bc1e79bff…`） | 2（`compute slot`、`memory slot`、`compute write-back port`、`memory write-back port`、`LD unit`） | 1 | 6 | - | 不成立: II 2: (a) 予約表なし（slot + write-back port (L5)）。II 3〜6: (b) レジスタ（同時に生きる値 4、4、4、4 > 3）（II 2〜6 のどれにも収まらない） | - |

## 反復回数の系列（`saxpy(n)`、既定の構成）

| n | II | bundle | cycles |
|---|---|---|---|
| 8 | 3 | 29 | 29 |
| 16 | 3 | 53 | 53 |
| 32 | 3 | 101 | 101 |
| 64 | 3 | 197 | 197 |
| 128 | - | - | 不成立: II 3〜6: 命令領域（389、517、645、770 bundle > 256）（II 3〜6 のどれにも収まらない） |

命令領域（256 bundle）に収まる最大の n は 83。n = 1 から 85 まですべて試した（Scratchpad に収まるのは n ≦ 170、メモリ操作 3n + 1 個が1 bundle に1個ずつ命令領域に収まるのは n ≦ 85）。
