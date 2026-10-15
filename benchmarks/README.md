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

算術・入出力・データ配置・DAG は両方で同じで、どちらの命令列も mvp-0 の合法なプログラムとして同じ RTL で実行できる。ただし、コンパイラは方式ごとに、優先度とレジスタ上限の試行のうち cycles が最小のものを選ぶので、**選ばれた試行（優先度・レジスタ割り当て）も方式ごとに違う**（[RESULTS.md](RESULTS.md)「選ばれた試行」）。したがって cycles の比は、発行幅だけを変えた効果ではない。戦略を固定した系列は #79 で加える。

比較の書き方:
- cycle の比を、実時間の速度比と呼ばない（Fmax 未測定、#57）
- 比較する系列ごとに、選ばれた試行を併記する

## 記録

| ファイル | 内容 |
|---|---|
| [RESULTS.md](RESULTS.md) | 条件と結果の表（cycles、演算数、下限、NOP率、書戻しポート・bank の利用率、レジスタ数、命令列の hash、選ばれた試行） |
| `cases/<kernel>_<mode>.json` | 命令列、入力宣言（入力値は `sampleInputs(kernel, 1)`）、期待値（出力と cycles）。`vliw-testcase/1`（[docs/TESTCASE.md](../docs/TESTCASE.md)） |
| `metrics/<kernel>_<mode>.json` | 参照モデルでの実行の記録。`vliw-metrics/1`（[docs/METRICS.md](../docs/METRICS.md)）。machine の revision と hash、命令列の hash を含む |

記録は、すべてソースから生成する。手で編集しない。

```sh
sbt "crosscheck/Test/runMain vliw.crosscheck.BenchmarkRecords"   # benchmarks/ を書き直す
sbt "compiler/run verify benchmarks/cases/fir4x8_vliw.json"      # 記録した命令列の検査
sbt "sim/run benchmarks/cases/fir4x8_vliw.json"                  # 参照モデルで実行
```

CI の `crosscheck/BenchmarkSpec` は、次を確かめる。

- コミットされた記録が、生成し直した内容と一致すること（`metrics` の `generator` 欄は実行環境に依存するので比べない）。コンパイラや machine description を変えて結果が変わったら、生成し直してコミットする
- 記録したテストケースが、参照モデルでも RTL でも、期待した出力と cycles になること
- 単一発行の命令列は1 bundle に1操作だけで、vliw より速くならないこと
