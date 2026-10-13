# Phase 0 完了報告

完了日：2026-10-14（日本時間） / マイルストーン：Milestone 0（追跡Issue #1）

## 完了条件と根拠

[ROADMAP.md](ROADMAP.md) の Phase 0 の完了条件を、次のテストで確認した。すべて `main` へのPRごとに、GitHub Actions の `scala` ジョブで実行される。

| 完了条件 | 根拠となるテスト |
|---|---|
| 命令の round-trip | `isa/BundleCodecSpec`「every valid compute and memory operation round-trips (exhaustive)」「random words: whatever decodes re-encodes to the same word」、`isa/AssemblerSpec`「asm -> bin -> asm round-trips for random programs」「disassembly is canonical and assembles back to the same bundles」 |
| ISA例の 28 / 7 cycles | `sim/ReferenceModelSpec`「ISA example: memory[1] = 28 in 7 cycles, trace equals the hand-written golden trace」。エンコード結果は `isa/BundleCodecSpec`「the ISA example encodes to the hand-computed words」 |
| 不正スケジュールの拒否 | `compiler/VerifierSpec`「every test case in tests/cases gets exactly the expected rules」「the examples table of docs/ISA.md」、[tests/cases](../tests/cases/README.md) の不正ケース16件（L1〜L8 の全規則） |
| 入力値に依存しない固定サイクル | `sim/ReferenceModelSpec`「cycle count and commit timing do not depend on input values」、`crosscheck/DifferentialSpec`「for legal programs, cycles and commit timing do not depend on input values」 |
| verifier と参照モデルの独立な一致 | `crosscheck/DifferentialSpec`「verifier and reference model agree on legality for random programs」（3万プログラム）。誤りを人為的に入れると失敗することも確認した（PR #20） |

ROADMAP「Phase 0の最初の作業」の5項目は、それぞれ次のIssueで実施した。

1. machine schema → #4
2. encoder/decoder → #6
3. 参照モデル → #8
4. 独立verifier → #9
5. 検証ケース → #10

実行意味論は #5 で確定した。

## 確定した定義

| 対象 | 値 |
|---|---|
| ISA | `mvp-0`（[ISA.md](ISA.md)「実行意味論」「MVPの合法性規則」） |
| machine description | `vliw2-i32-sp2` revision 2、status `frozen` |
| machine hash | `sha256:a4af6064e049415dc9822614264a01f06b6e1be39c86bc41c5fcdda3e7d89e21` |
| ツールチェーン | [ADR-0004](DECISIONS.md#adr-0004-ツールチェーンとバージョン固定)（JDK 21、Scala 2.13.18、sbt 1.12.15、ScalaTest 3.2.20、ujson 4.4.3、scalafmt 3.11.5） |

ISAや machine description を変える場合は、`revision` を上げる。トレースのヘッダの `machine_hash` が変わるため、Phase 1 の比較で検出できる。

## Phase 1 へ引き継ぐもの

| 成果物 | 場所 | Phase 1 での使い方 |
|---|---|---|
| 実行意味論と合法性規則 | [ISA.md](ISA.md) | RTLの各段の動作とassertionの基準 |
| machine description とローダ | `config/machine.json`、`vliw.isa.MachineDescription` | `hw` が `isa` に依存し、パラメータ（opcode、latency、容量）を読む |
| 命令表現とエンコーディング | `vliw.isa.BundleCodec`、`ProgramFile` | Instruction ROM の初期化（`.hex`） |
| テストケース | `tests/cases/*.json`（[一覧](../tests/cases/README.md)） | ChiselSim で全合法ケースを実行し、cycles と最終状態を照合する |
| golden trace | `tests/golden/*.trace.jsonl` | ChiselSim が [TRACE.md](TRACE.md) 形式で出力し、行ごとに照合する |
| 参照モデル | `vliw.sim.ReferenceModel` | golden trace の無いケースや乱数プログラムで、RTLのトレースと突き合わせる |
| verifier | `vliw.compiler.Verifier` | RTLに入れる命令列を事前に検査する。不正ケースでRTLのassertionが発火することを確かめる |

### 合法性規則と RTL assertion の対応（案）

| 規則 | RTLでの扱い |
|---|---|
| L1 符号化 | デコーダで未知opcode・予約bitを検出し、assertionで停止する。通常は verifier が事前に拒否する |
| L2 プログラム形状 | ロード時の検査とする（ROMの長さとHALTの位置）。実行中は、PCが末尾を越えたらassertionで停止する |
| L3 書込み中の読出し | RFの読出しポートごとに、書込み予約中のレジスタを読んだらassertionで停止する |
| L4 書込み区間の重複 | 書込み予約表で、同じレジスタへの重複予約を検出してassertionで停止する |
| L5 書戻しポート | 書戻しポートごとに、同じ境界での2つ目のcommitを検出してassertionで停止する |
| L6 HALT時の完了 | HALT発行時に予約が残っていたらassertionで停止する |
| L7 アドレス範囲 | アドレスデコーダでの範囲検査をassertionにする |
| L8 未定義値 | RTLは値の定義状態を持たない。verifierと参照モデルだけで検査する |

## 次のマイルストーン

Phase 1 の追跡Issueは #22、作業Issueは #23〜#30（`milestone-1` ラベル）。

## 残っている作業（オーナー）

- GitHub上の Milestone 0 の作成と、`milestone-0` ラベルのIssueの割り当て
- `main` のブランチ保護で、`static` / `scala` を Required status check に設定すること
- README の公開範囲（private）と、GitHub上の設定（public）の不一致の解消
