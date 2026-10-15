# Benchmarks

Phase 2 のベンチマーク（整数 SAXPY、FIR、固定サイズ行列積）の記録。結果の表は [RESULTS.md](RESULTS.md) にある。

**cycle ベースの比較である。** Fmax・面積・電力は未測定（#57）で、時間や電力の主張はしない。

## カーネル

定義は DSL で書いた `vliw.compiler.dsl.Benchmarks`（[docs/DSL.md](../docs/DSL.md)）。算術はすべて32bit整数で、2^32 を法とする（ISA の ADD/SUB/MUL と同じ）。

| カーネル | 計算 | 入力 | 出力 | Scratchpad（語） |
|---|---|---|---|---|
| `saxpy8` | `out(i) = a * x(i) + y(i)` | スカラー `a`、`x`・`y` 各8語 | `out` 8語 | 25 |
| `fir4x8` | `out(i) = Σ_{t<4} h(t) * x(i + t)`、係数 `h = (1, 2, 3, 4)` は定数 | `x` 11語 | `out` 8語 | 23（定数4語を含む） |
| `matmul3` | `c = a × b`（3×3、行優先） | `a`・`b` 各9語 | `c` 9語 | 27 |

期待出力は、DSL の参照実装（`vliw.compiler.dsl.Interpreter`）で、命令列を経由せずに計算する。

## 比べる方式

| 方式 | 内容 |
|---|---|
| `vliw` | 1 bundle に Compute 1・Memory 1 を発行する（mvp-0 の通常の命令列） |
| `single` | **単一発行ベースライン**。同じ machine description・DAG・メモリ配置・レジスタ割り当ての方法で、1サイクルに1操作だけ発行する（`--single-issue`） |

算術・入出力・データ配置・DAG は両方で同じで、どちらの命令列も mvp-0 の合法なプログラムとして同じ RTL で実行できる。

## 系列

コンパイラの試行は、優先度（critical-path、program-order）×（レジスタ上限なし、上限 = レジスタ数で `loadReserve` 0〜レジスタ数/2）の組である（[docs/COMPILER.md](../docs/COMPILER.md)）。コンパイラは、まず各優先度を上限なしで、次に各優先度を上限付きで `loadReserve` の小さい順に試す（`RegisterAllocator.attempts`）。記録は、この試行の選び方が違う2つの系列からなる（#79）。

| 系列 | 試行の選び方 | 何を比べられるか |
|---|---|---|
| 方式ごとに cycles 最小 | 方式ごとに、全ての試行のうち cycles 最小のもの（同じならば、試す順で先の試行） | 各方式で今のコンパイラが出せる最良の cycles。**選ばれた試行が方式ごとに違うことがあり**（今の記録では `fir4x8` と `matmul3`。`saxpy8` は両方式とも同じ試行）、そのカーネルの比は発行幅だけの効果ではない |
| 戦略を固定 | 全ての試行を、両方の方式に同じ組で与える | 同じ行の比は、発行幅だけを変えた効果。ある方式で収まらない組は「不成立」と理由付きで記録し、別の試行で置き換えない |

方式ごとに cycles 最小の試行は、固定した系列の中で、その方式の cycles が最小の行と一致する（`BenchmarkSpec` が確かめる）。

## 比較の書き方

- cycle の比を、実時間の速度比と呼ばない（Fmax 未測定、#57）
- どちらの系列の値かを書き、比べる各行の試行（優先度、レジスタ上限、`loadReserve`）を併記する
- 2つの方式・構成の効果を言うときは、戦略を固定した系列の同じ行どうしで比べる。「方式ごとに cycles 最小」どうしの比は、試行の違いの効果も含む
- 「不成立」の行は、比に含めない。不成立の数と理由も、比較の結果として書く
- 今後の比較（MAC のある・なし #77、Modulo Scheduling と list scheduling #68、レジスタ数 #70）も、同じ2つの系列で記録する
- レジスタ数を変える比較（#70）では、レジスタ上限はそれぞれのレジスタ数になる。戦略を固定した系列は、優先度と `loadReserve` が同じ行（上限なしの行は上限なしの行）どうしを比べる。片方にしかない行（`loadReserve` がもう一方のレジスタ数/2 を超える行）は、片方だけの行として記録し、比に含めない

## 記録

| ファイル | 内容 |
|---|---|
| [RESULTS.md](RESULTS.md) | 条件と、2つの系列の結果の表（戦略を固定した系列は、この表だけに記録する）。方式ごとに cycles 最小の系列は cycles、演算数、下限、NOP率、書戻しポート・bank の利用率、レジスタ数、命令列の hash、選ばれた試行。戦略を固定した系列は、試行ごとの両方式の cycles と比、不成立の理由 |
| `cases/<kernel>_<mode>.json` | 方式ごとに cycles 最小の系列の命令列、入力宣言（入力値は `sampleInputs(kernel, 1)`）、期待値（出力と cycles）。`vliw-testcase/1`（[docs/TESTCASE.md](../docs/TESTCASE.md)） |
| `metrics/<kernel>_<mode>.json` | 方式ごとに cycles 最小の系列の、参照モデルでの実行の記録。`vliw-metrics/1`（[docs/METRICS.md](../docs/METRICS.md)）。machine の revision と hash、命令列の hash を含む |

記録は、すべてソースから生成する。手で編集しない。

```sh
sbt "crosscheck/Test/runMain vliw.crosscheck.BenchmarkRecords"   # benchmarks/ を書き直す
sbt "compiler/run verify benchmarks/cases/fir4x8_vliw.json"      # 記録した命令列の検査
sbt "sim/run benchmarks/cases/fir4x8_vliw.json"                  # 参照モデルで実行
```

CI の `crosscheck/BenchmarkSpec` は、次を確かめる。

- コミットされた記録が、生成し直した内容と一致すること（`metrics` の `generator` 欄は実行環境に依存するので比べない）。コンパイラや machine description を変えて結果が変わったら、生成し直してコミットする
- 記録したテストケースが、参照モデルでも RTL でも、期待した出力と cycles になること
- 戦略を固定した系列が、コンパイラの全ての試行を、試す順に、両方の方式に与えていること。各行がその行の試行の結果であること（不成立を別の試行で置き換えないこと）。各行の命令列（成立したもの）が、verifier に合法と判定され、参照モデルで期待した出力と、スケジューラの予測どおりの cycles になること
- 方式ごとに cycles 最小の系列が、固定した系列の中の、その方式で cycles 最小の最初の試行と一致すること
- 単一発行の命令列は1 bundle に1操作だけで、vliw より速くならないこと
