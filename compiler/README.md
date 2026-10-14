# Compiler

Phase 0〜2のソフトウェア実装先。sbtプロジェクト `compiler`、パッケージ `vliw.compiler`。依存先は `isa` のみ。

アセンブラとエンコーダは共通基盤の `isa` に置く（[ADR-0008](../docs/DECISIONS.md)）。ここでは最初に独立Schedule Verifierを実装する。次にDSL/DAG・list scheduling・register allocationを追加する。共有Machine Descriptionを使い、issueだけでなく未来のwriteback資源も予約する。

実装言語はScala（[ADR-0001](../docs/DECISIONS.md)）。最初からLLVM連携を必須としない。verifierは `sim` のコードを使わず、値を持たない静的判定として実装する（[ADR-0002](../docs/DECISIONS.md)）。

## 実装（Phase 0）

- `Verifier`: 合法性規則 L1〜L10 の静的verifier（[docs/VERIFIER.md](../docs/VERIFIER.md)）。値を持たず、書込み区間・読出し・メモリアクセスの表で判定し、全違反を報告する。
- CLI: `sbt "compiler/run verify <case.json>..."`
