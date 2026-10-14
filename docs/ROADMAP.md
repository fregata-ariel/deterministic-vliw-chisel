# 開発ロードマップ

期間の見積りではなく、着手順序と完了条件を定義する。

| Phase | 実装 | 完了条件 |
|---|---|---|
| 0 | machine schema、encoder/decoder、assembler、独立verifier、参照モデル | 命令のround-trip、ISA例の28/7cycles一致、不正スケジュール拒否（**完了**: [PHASE0.md](PHASE0.md)） |
| 1 | Chisel RF、ALU/MUL、Scratchpad、PC、start/done、ChiselSim | RTLと参照モデルのRF/memory/commit traceが一致（**完了**: [PHASE1.md](PHASE1.md)） |
| 2 | 小さなDSL、DAG、list scheduling、register allocation | SAXPY/FIR/行列積が自動命令生成で機能・サイクル一致（**完了**: [PHASE2.md](PHASE2.md)） |
| 3 | Modulo Scheduling、SIMD/MAC | プロローグ・定常・エピローグを検証しIIを測定 |
| 4 | DMA、Double Buffer、bank最適化 | 転送と演算を重畳し、開始条件・容量・帯域を契約化 |
| 5 | 複数タイル、静的通信 | 通信遅延・経路・バッファ競合を共有モデルで検証 |

## Phase 0の最初の作業

完了。各項目の実施Issueと根拠は [PHASE0.md](PHASE0.md) にまとめた。

1. `config/machine.json`のスキーマ、revision、正規化形式を定義する。
2. bit配置からencoder/decoderを作り、予約bitを検査する。
3. cycleごとのissue/commitを記録する参照モデルを作る。
4. Compute書込衝突、RFハザード、Memory衝突、早すぎるHALTを独立verifierで拒否する。
5. ISA例、演算overflow、連続MUL、独立Compute+LD、不正アドレスを検証する。

## RTL検証

算術結果だけでなく、発行・書込時刻、store commit、done cycleを比較する。同じkernelでデータを変更しても実行サイクル数が固定であることを確認する。

異常系には依存違反、書込ポート衝突、同一RFへの二重書込、Memory read/write衝突、実行中のホスト操作を含める。合法な実行はストール無しで完了し、違反は検証器またはassertionで検出する。

## 評価記録

kernel、入力サイズ、算術仕様、machine revision/hash、toolchain version、命令数、cycles、II、NOP率、利用帯域を記録する。合成結果を追加する際はターゲット、制約、周波数、面積指標、使用ツールを併記する。

比較用の単一発行と固定パイプラインは同じ算術・入出力条件を使う。測定結果が出るまで、スループットや電力の数値目標を達成値として記載しない。
