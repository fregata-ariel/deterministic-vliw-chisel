# Compiler

Phase 0〜2のソフトウェア実装先。sbtプロジェクト `compiler`、パッケージ `vliw.compiler`。依存先は `isa` のみ。

アセンブラとエンコーダは共通基盤の `isa` に置く（[ADR-0008](../docs/DECISIONS.md)）。ここでは最初に独立Schedule Verifierを実装する。次にDSL/DAG・list scheduling・register allocationを追加する。共有Machine Descriptionを使い、issueだけでなく未来のwriteback資源も予約する。

実装言語はScala（[ADR-0001](../docs/DECISIONS.md)）。最初からLLVM連携を必須としない。verifierは `sim` のコードを使わず、値を持たない静的判定として実装する（[ADR-0002](../docs/DECISIONS.md)）。

## 実装（Phase 0）

- `Verifier`: 合法性規則 L1〜L11 の静的verifier（[docs/VERIFIER.md](../docs/VERIFIER.md)）。値を持たず、書込み区間・読出し・メモリアクセスの表で判定し、全違反を報告する。
- CLI: `sbt "compiler/run verify <case.json>..."`

## 実装（Phase 2）

- `dsl`: カーネルDSL（`Kernel`、`Expr`）、参照実装（`Interpreter`）、メモリ配置（`MemoryLayout`）、lowering（`Lowering`）、ベンチマークのカーネル（`Benchmarks`）。[docs/DSL.md](../docs/DSL.md)
- `dag`: 依存 DAG（データ依存とメモリ順序、レイテンシーによる距離、クリティカルパスと資源による下限）。[docs/COMPILER.md](../docs/COMPILER.md)
- `schedule`: 資源制約付き list scheduling（スロット、書戻しポートの未来の予約、II、HALT の位置。優先度はクリティカルパスとプログラム順。レジスタの上限付きも可）
- `regalloc`: 生存区間による register allocation と、足りないときの上限付き再スケジュール。命令列（bundle）を作る
- `KernelCompiler`: 全段をまとめ、独立 verifier の最終検査に通った命令列だけを返す。`.hex` とテストケースを出力する
- CLI: `sbt "compiler/run compile <kernel>... [--machine m.json] [--out dir]"`（[docs/COMPILER.md](../docs/COMPILER.md)「CLI」）
