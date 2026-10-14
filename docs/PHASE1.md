# Phase 1 完了報告

完了日：2026-10-14（日本時間） / マイルストーン：Milestone 1（追跡Issue #22）

## 完了条件と根拠

[ROADMAP.md](ROADMAP.md) Phase 1 の完了条件「RTLと参照モデルのRF/memory/commit traceが一致」と、#22 の完了条件を、次のテストで確認した。すべて GitHub Actions の `scala` ジョブ（Verilator 5.020）で実行される。

| 完了条件 | 根拠となるテスト |
|---|---|
| `tests/cases` の全ての合法ケースで、RTL の cycles・最終状態・トレースが期待値と一致する | `hw/CaseSpec`（11件。golden trace 3件を含む） |
| 乱数で作った合法プログラムで、RTL と参照モデルのトレースが一致する | `crosscheck/RtlDifferentialSpec`「RTL and reference model produce identical traces ...」（400プログラム、トレースを1行ずつ照合） |
| 不正ケースで、対応する RTL の assertion が発火する（L8 を除く） | `hw/AssertionSpec`（不正ケース全件。L8 だけのケースは最後まで実行される） |
| 合法な実行がストール無しで完了する | 構造上ストールの機構が無い。`RtlDifferentialSpec` で cycles が bundle 数と一致することを確認 |
| 入力値に依存しない固定サイクル（RTL） | `crosscheck/RtlDifferentialSpec`「on the RTL, cycles and commit timing do not depend on input values」 |
| CI で自動検証される | `scala` ジョブ（全体で約3分）。mutation ジョブ（約35分）では、RTL の誤り3種類を含む10種類の誤りがすべて検出される |

ISA例は、RTL上でも7サイクルで `memory[1] = 28` となり、手書きの golden trace と一致する。

## 実装（`hw/`）

| 項目 | 内容 |
|---|---|
| ツールチェーン | Chisel 7.15.0、firtool 1.158.0、ChiselSim（Verilator 5.020）。[ADR-0004](DECISIONS.md#adr-0004-ツールチェーンとバージョン固定) |
| 構成 | `Core`、`Decoder`、`RegisterFile`（3R/2W）、`WriteReservations`、`ComputeSlot`、`MemorySlot`、`WritebackSchedule`（[hw/README.md](../hw/README.md)） |
| SystemVerilog | `sbt "hw/run [dir] [machine.json]"`。mvp-0 で設計本体が10ファイル・約860行（assertion は `verification/` 以下の別ファイル） |
| 検証用のポート | ホストポート（停止中の読み書き）、観測ポート（トレース用）、違反信号と assertion（[VERIFIER.md](VERIFIER.md)） |

## RTL が実装できる構成（#35 への入力）

machine description のローダが受け付ける値（レジスタ数1〜16、banks × words ≤ 512、latency と II は1〜8）のうち、RTL が対応しないのは次の3点である。範囲外の構成は、組み立て時に `CoreParams` の `require` が拒否する。

| 項目 | RTLの対応範囲 | 理由 |
|---|---|---|
| II | すべて1 | 全演算器を毎サイクル発行できるパイプラインにした。II>1 の資源予約は未実装 |
| LD のレイテンシー | 2以上 | Scratchpad が同期読出しメモリなので、1サイクル目は読み出しに使う |
| ST のレイテンシー | 1 | ST が書き込む前に、同じ番地への LD が読み出しを始めることがない、という前提で作っている。ST のレイテンシーを2以上にするには、書込み中の番地への読出しと bank の read/write 衝突を、合法性規則または RTL の側で扱う必要がある |

ADD/SUB/MUL のレイテンシー（1〜8）、レジスタ数、Scratchpad の構成は、ローダの範囲全体に対応する。

`tests/machines/slow_units.json`（ST のレイテンシー2）を使うケースは、RTL のテストではスキップとして表示する。

## 合成評価

Phase 1 では行わない。ターゲット（FPGA の品種、または ASIC のライブラリ）と制約が未定で、Fmax や面積を比べられる条件を書けないためである。数値を記録するときは、ターゲット、制約、周波数、面積の指標、使用ツールを併記する（[ROADMAP.md](ROADMAP.md)「評価記録」）。`vliw-metrics/1` の `rtl` 欄は、そのために空けてある。ターゲットを決めた段階で、別のIssueとして行う。

## Phase 2 へ引き継ぐもの

- 凍結した ISA と machine description、合法性規則（[ISA.md](ISA.md)）
- 独立 verifier（コンパイラの出力を最終検査する）、参照モデル、RTL、テストケース形式、トレース・メトリクスの形式
- 自動生成した命令列を、verifier・参照モデル・RTL の3者で照合する仕組み（`tests/cases` と `crosscheck`）

Phase 2 は #46（Milestone 2）で追跡する。最初に、レビュー対応 P1（#35: II の強制と構成契約）を、上の「RTL が実装できる構成」を踏まえて行う。
