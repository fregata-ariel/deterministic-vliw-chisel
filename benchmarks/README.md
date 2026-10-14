# Benchmarks

Phase 2以降の整数SAXPY、FIR、固定サイズ行列積を配置する。カーネル自体は未実装で、自動命令生成（Phase 2）と一緒に追加する。

各kernelで算術仕様、入力サイズ、期待出力、期待cycles、machine revision、コンパイラ設定を記録する。単一発行モデル・固定パイプラインとの比較条件を揃える。

## 記録の方法

各実行の性能と構成は、[docs/METRICS.md](../docs/METRICS.md) の `vliw-metrics/1` 形式で記録する。

```sh
sbt "sim/run <case.json> --metrics <kernel>.metrics.json"
```

記録には machine description の hash と、命令列の hash が入る。そのため、どの構成のどの命令列の結果かを後から特定できる。RTLの評価（Fmax、面積）は、Phase 1 以降に同じ記録の `rtl` 欄へ追加する。
