# Compiler

Phase 0〜2のソフトウェア実装先。現時点では未実装。

最初は小さなCLIアセンブラ・エンコーダ・独立Schedule Verifier。次にDSL/DAG・list scheduling・register allocationを追加する。共有Machine Descriptionを使い、issueだけでなく未来のwriteback資源も予約する。

実装言語はPhase 0で選択する。小規模なPython実装またはScala実装を比較し、最初からLLVM連携を必須としない。
