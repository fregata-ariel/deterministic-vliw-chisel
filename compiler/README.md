# Compiler

Phase 0〜2のソフトウェア実装先。sbtプロジェクト `compiler`、パッケージ `vliw.compiler`。依存先は `isa` のみ。現時点では未実装。

アセンブラとエンコーダは共通基盤の `isa` に置く（[ADR-0008](../docs/DECISIONS.md)）。ここでは最初に独立Schedule Verifierを実装する。次にDSL/DAG・list scheduling・register allocationを追加する。共有Machine Descriptionを使い、issueだけでなく未来のwriteback資源も予約する。

実装言語はScala（[ADR-0001](../docs/DECISIONS.md)）。最初からLLVM連携を必須としない。verifierは `sim` のコードを使わず、値を持たない静的判定として実装する（[ADR-0002](../docs/DECISIONS.md)）。
