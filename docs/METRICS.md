# メトリクス形式（vliw-metrics/1）

プログラムやマシン構成を比較するための、1回の実行の記録。[ROADMAP.md](ROADMAP.md)「評価記録」の項目を、機械可読な形で残す。正しさの照合は [TRACE.md](TRACE.md) のトレースが担い、この形式は性能の比較にだけ使う。

```sh
sbt "sim/run tests/cases/isa_example.json --metrics out.json"
```

実装は `vliw.sim.Metrics`。参照モデルで完了した（合法な）実行だけについて記録する。

## 例（ISA例）

```json
{
  "metrics": "vliw-metrics/1",
  "isa": "mvp-0",
  "machine": {"id": "vliw2-i32-sp2", "revision": 2, "hash": "sha256:a4af6064..."},
  "program": {"name": "isa_example", "hash": "sha256:3eca8de7...", "bundles": 7},
  "generator": {"model": "vliw.sim.ReferenceModel", "scala": "2.13.18", "java": "21.0.12.1"},
  "cycles": 7,
  "issue": {
    "compute": {"operations": 2, "nop": 4, "nop_rate": 0.571429},
    "memory": {"operations": 2, "nop": 5, "nop_rate": 0.714286},
    "by_operation": {"ADD": 1, "SUB": 0, "MUL": 1, "LD": 1, "ST": 1, "HALT": 1}
  },
  "writeback": {
    "compute": {"commits": 2, "utilization": 0.285714},
    "memory": {"commits": 1, "utilization": 0.142857}
  },
  "scratchpad": {
    "reads": 1, "writes": 1, "accesses_per_cycle": 0.285714,
    "banks": [
      {"bank": 0, "reads": 1, "writes": 0, "accesses_per_cycle": 0.142857},
      {"bank": 1, "reads": 0, "writes": 1, "accesses_per_cycle": 0.142857}
    ]
  },
  "ii": null,
  "rtl": null
}
```

## キー

| キー | 内容 |
|---|---|
| `machine` | machine description の id、revision、hash（[MACHINE.md](MACHINE.md)） |
| `program.hash` | 命令列の `.hex` 表現（[ISA.md](ISA.md)）のSHA-256。同じ命令列なら同じ値になる |
| `program.bundles` | bundle数（命令数） |
| `generator` | 記録を作った実装と、その実行環境の版 |
| `cycles` | start受理から done までのサイクル数（h+1） |
| `issue.<slot>.operations` | そのスロットで発行した演算の数。Compute は ADD/SUB/MUL、Memory は LD/ST。HALT は含めない |
| `issue.<slot>.nop` / `nop_rate` | NOPのbundle数と、bundle数に対する割合。Compute の NOP は「bundle数 − 演算数 − HALT数」 |
| `issue.by_operation` | 演算ごとの発行数（HALT を含む） |
| `writeback.<port>.commits` / `utilization` | 書戻しポートごとのRFへのcommit数と、サイクル数に対する割合 |
| `scratchpad.reads` / `writes` | LD / ST の回数 |
| `scratchpad.accesses_per_cycle` | (reads + writes) / cycles。bank別の値は `banks` に入れる |
| `ii` | Modulo Scheduling したループの、定常状態の反復の開始間隔（ループの II）。記録を作る側が、トレースで確かめた値を渡す（`Metrics.of` の `ii`、`crosscheck/ModuloTrace`。[COMPILER.md](COMPILER.md)「Modulo Scheduling」）。展開済みのカーネルと `sim/run --metrics` では定義しないので `null`。演算器の II（`operations.<op>.ii`）とは別のもの |
| `rtl` | RTLの評価結果（Fmax、面積・資源量、ターゲット、ツール）のための予約欄。Phase 1 以降で定義する。それまでは `null` |

- 割合は小数点以下6桁に丸める。分母が0のときは `null` にする。
- 回数はすべて整数。
- 固定スケジュールなので、入力値を変えても記録は変わらない（`MetricsSpec` で確認している）。

## 版の扱い

キーを追加・削除するとき、または意味を変えるときは、`metrics` の版を上げる（`vliw-metrics/2`）。古い記録と比べるときは、版の違いを明示する。
