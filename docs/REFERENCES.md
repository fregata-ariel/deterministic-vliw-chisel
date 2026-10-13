# 一次資料と利用目的

2026-10-14に基礎資料を確認。設計案の数値・ISAは本プロジェクトの提案であり、以下の製品や研究の仕様の転載ではない。

| 資料 | 利用目的 |
|---|---|
| [GitHub CLI: gh repo create](https://cli.github.com/manual/gh_repo_create) | private repo、source、remote、pushのCLI仕様 |
| [Chisel Installation](https://www.chisel-lang.org/docs/installation) | Scala/Chisel、JDK、ビルド導入 |
| [CHIPS Alliance chisel-template](https://github.com/chipsalliance/chisel-template) | 標準的なプロジェクトの起点 |
| [Chisel Testing](https://www.chisel-lang.org/docs/explanations/testing) | ChiselSimによる検証 |
| [Chisel Memories](https://www.chisel-lang.org/docs/explanations/memories) | SyncReadMemの同期読出しと競合時の制約 |
| [CIRCT Scheduling](https://circt.llvm.org/docs/Scheduling/) | 依存・資源制約、静的スケジューリングの基盤 |
| [T-CREST Patmos](https://github.com/t-crest/patmos) | 時間予測可能なVLIWの参照実装 |

以下は次の詳細調査で原論文や実装資料を確認する比較対象。上の確認済み資料と区別する。

- SDF：Lee / Messerschmitt, Synchronous Data Flow (1987)。生成・消費率、繰り返し周期、バッファ解析。
- PRET / PTARM：時間契約と予測可能な実行基盤。
- [OpenASIP](https://openasip.org/)：TTA、Machine Descriptionとcompilerの共同設計。
- [Calyx](https://calyxir.org/)：static control、Latency契約、制御IR。
- Groq TSP原論文：コンパイラによる演算・転送の時間配置。
- TI C6000の公式ISA資料：VLIW/DSP、パイプライン、ソフトウェアパイプライニング。

新しい調査結果を追加する際は、資料URL、日付、対象世代、確認した制約を記録する。製品名だけから固定実行時間や外部I/O保証を推定しない。
