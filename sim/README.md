# Reference Simulator

Phase 0のISA/サイクル参照モデル実装先。sbtプロジェクト `sim`、パッケージ `vliw.sim`。依存先は `isa` のみで、`compiler`（verifier）には依存しない（[ADR-0002](../docs/DECISIONS.md)）。

各cycleのPC、issue、RF commit、Memory commit、pending operation、doneを記録する。`docs/ISA.md`の例はmemory[1]=28、done=7cyclesとなる。

エンコード済み命令を入力とし、算術結果と予定完了時刻を独立して計算する。RTLとの比較に加え、異なる入力値でもcyclesが固定であることを検証する。

## 実装（Phase 0）

- `ReferenceModel`: 状態は、RF・Scratchpadと、値とcommit境界を持つ実行中操作のキュー。各サイクルで、見える状態からソースを読み、結果を E_(s+L) に予約し、E_(s+1) の分をcommitする（[docs/ISA.md](../docs/ISA.md)「実行意味論」）。
- 実行時チェック: RTLのassertionに相当する。合法性規則 L1〜L10 の違反を検出したら停止し、規則IDを報告する。verifierのコードは使わない。
- `Trace`: [docs/TRACE.md](../docs/TRACE.md) の形式で出力する。
- `Metrics`: 性能と構成の記録を [docs/METRICS.md](../docs/METRICS.md) の形式で出力する。
- CLI: `sbt "sim/run <case.json> [--trace out.jsonl] [--metrics out.json]"`

テスト（`ReferenceModelSpec`）は、`tests/cases` の全ケース、手書きの golden trace、入力値を乱数で変えたときのサイクル数とcommit時刻の不変性を確認する。
