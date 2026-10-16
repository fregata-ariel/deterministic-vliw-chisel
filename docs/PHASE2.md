# Phase 2 完了報告

完了日：2026-10-14（日本時間） / マイルストーン：Milestone 2（追跡Issue #46）

## 完了条件と根拠

[ROADMAP.md](ROADMAP.md) Phase 2 の完了条件「SAXPY/FIR/行列積が自動命令生成で機能・サイクル一致」と、#46 の完了条件を、次のテストで確認した。すべて GitHub Actions の `scala` ジョブ（Verilator 5.020、全体で約5分）で実行される。

| 完了条件 | 根拠となるテスト |
|---|---|
| SAXPY・FIR・行列積を DSL から自動生成し、生成した命令列が verifier に合格する | `KernelCompiler` は verifier に合格した命令列しか返さない（`compiler/KernelCompilerSpec`。壊した命令列を止めることも確かめる）。`crosscheck/BenchmarkSpec` が記録した命令列を照合する |
| 参照モデルと RTL の両方で、結果が手で計算した期待値と一致する | `crosscheck/HandComputedSpec`（3カーネルを記録した規模で、手計算の出力と比べる。vliw と単一発行の両方、参照モデルと RTL の両方）。乱数入力では、DSL の参照実装と素の Scala の実装（`compiler/dsl/KernelSpec`）、生成コード（`crosscheck/CompiledKernelSpec`）を比べる |
| cycles が、スケジューラの予測と一致する | `CompiledKernelSpec`・`HandComputedSpec`・`BenchmarkSpec`（参照モデルと RTL）、`crosscheck/RandomKernelSpec`（乱数カーネル400個。参照モデル、うち60個は RTL） |
| 各カーネルの `vliw-metrics/1` 記録が残り、単一発行ベースラインと比較できる | [benchmarks/RESULTS.md](../benchmarks/RESULTS.md)、`benchmarks/metrics/`。`BenchmarkSpec` が、コミットした記録とソースから生成し直した内容の一致を確かめる |
| 上記が CI で自動検証される | `scala` ジョブ。mutation ジョブ（15 mutant、CI 上約45分）は required にしない。15 killed / 0 survived / 0 stale の結果は PR #63 のコミット 5b87a47（actions run 38562969235）でのもので、最終の main（be06208）での再実行ではない |

## 実装（`compiler/`）

| 段 | 内容 | 文書 | Issue |
|---|---|---|---|
| カーネルDSL | Scala 内部DSL。入力・スカラー・出力（書込み専用）・定数・ADD/SUB/MUL。ループは `for` で展開。式は仮想値 | [DSL.md](DSL.md) | #47 |
| 参照実装 | DSL を直接評価する `Interpreter` | [DSL.md](DSL.md) | #47 |
| メモリ配置・lowering | 固定順の配置。スカラーと定数は初期化済みメモリに置き、葉をアドレスの読出しにする | [DSL.md](DSL.md) | #47 |
| DAG | 値番号付け、データ依存とメモリ順序の辺、レイテンシーによる距離、下限 | [COMPILER.md](COMPILER.md) | #48 |
| list scheduling | スロット、書戻しポートの未来の予約（L5）、II（L9）、HALT の位置（L6）。優先度2種、単一発行 | [COMPILER.md](COMPILER.md) | #49、#52 |
| register allocation | 生存区間 `[発行, 最後の読出し)` の貪欲彩色。足りなければ上限付きで再スケジュール（spill なし） | [COMPILER.md](COMPILER.md) | #50 |
| コード生成と最終検査 | 独立 verifier に合格した命令列だけを返す。`.hex` とテストケースを出す CLI | [COMPILER.md](COMPILER.md) | #51 |

Phase 2 の最初に、レビュー対応 P1 として合法性規則 L9（II）・L10（書込み中のメモリ語の読出し）と、machine description の構成契約（形式・意味論・RTL の3段）を加えた（#35、ADR-0009）。また RTL トレースの issue 欄を Decoder の出力から作るようにした（#56）。

## ベンチマークの結果

条件と全項目は [benchmarks/RESULTS.md](../benchmarks/RESULTS.md) にある。machine は `vliw2-i32-sp2` revision 2（凍結）、配置は固定順、入力は `sampleInputs(kernel, 1)`（結果と cycles は入力値によらない）。**cycle ベースの比較**で、Fmax・面積・電力は未測定（#57）。

| カーネル | vliw | 単一発行 | 単一発行 / vliw | 下限（依存 / スロット） |
|---|---|---|---|---|
| `saxpy8` | 26 | 42 | 1.62 | 7 / 26 |
| `fir4x8` | 65 | 81 | 1.25 | 9 / 57 |
| `matmul3` | 56 | 75 | 1.34 | 8 / 46 |

- `saxpy8` は Memory スロットが律速で、スロットの下限に一致する
- `fir4x8` と `matmul3` は Compute スロットが律速である。下限との差は次による
  - 開始時に、最初の MUL が LD を待つ
  - 終了時の ST と HALT
  - MUL と ADD の書戻しポートの競合（L5）による空き
  - レジスタの上限による再スケジュール（それぞれ +2、+4 cycles）
- 2スロットの効果は、Memory 律速の `saxpy8` で大きく、Compute 律速の2つでは小さい。Compute 側の改善のうち MAC は Phase 3 の課題である（#69・#77）。SIMD・複数 Compute スロットは MAC 単独の評価の後に回す（#78、Milestone は未定。ADR-0012）
- ただし、方式ごとに cycles 最小の試行を選んでいるため、選ばれた優先度・レジスタ割り当てが方式ごとに違うことがある（第三者レビュー、#46）。今の記録では `fir4x8` と `matmul3` で違い（`saxpy8` は両方式とも同じ試行）、その2つの比は発行幅だけを変えた効果ではない。戦略を固定した比較は #79 で `benchmarks/RESULTS.md` に加えた

## 既知の制約

| 制約 | 影響 | 引き継ぎ先 |
|---|---|---|
| spill も rematerialization もしない | レジスタを減らすと割り当てに失敗する（12本で `matmul3`、8本で `fir4x8`・`matmul3`） | #70（Phase 3 で、行き詰まる試行に rematerialization を加えた。8・12本でも3カーネルとも収まる。[benchmarks/REGISTERS.md](../benchmarks/REGISTERS.md)、ADR-0016） |
| DSL の出力は書込み専用で、各要素に1回だけ代入する | 上書き（`y = a*x + y`）や中間配列は書けない。メモリ順序の辺は生じない（DAG は一般の形に対応し、手作りの例でテストしている） | 必要になったときに緩める |
| スロットの下限は、開始・終了と書戻しポートの競合を数えない | 下限と実際の差（Compute 律速の2つで 8〜10 cycles）が残る | 必要になったときに改良する |
| mutant はコンパイラの各段を対象にしていない | コンパイラの誤りは、verifier の最終検査と差分テスト（参照モデル・RTL）で検出する | — |
| 合成評価をしていない | Fmax・面積・電力は主張しない | #57 |

## Phase 3 へ引き継ぐもの

- DSL・DAG・スケジューラ・レジスタ割り当て・最終検査を通したコンパイラ（`KernelCompiler`）と CLI
- ベンチマークの記録と、CI での一致の確認（`benchmarks/`、`BenchmarkSpec`）
- 単一発行ベースライン

Phase 3（Modulo Scheduling、MAC）は #66（Milestone 3）で追跡する。ループの扱いは案A（ISA を変えずに展開し、IR に反復の情報を残す。#67、ADR-0011）、MAC/SIMD は MAC を先に（#69、ADR-0012）とオーナーが決めた（2026-10-15）。
