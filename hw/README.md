# Hardware

Phase 1のChisel実装先。sbtプロジェクト `hw`、パッケージ `vliw.hw`。依存は `isa`。Chisel 7.15.0、firtool 1.158.0、ChiselSim（Verilator 5.020）。

Machine Descriptionの読み込み、RF、Compute slot、Memory slot、Instruction ROM、PC、開始・終了制御を実装する。ChiselSimで`sim/`の参照トレースと結果・commit時刻・doneを比較する。

公式chisel-templateの確認済み構成を参考に、既存のsbtビルドへサブプロジェクト `hw` として追加し、依存バージョンを固定する（Scala 2.13.18、Chisel 7.x。[ADR-0004](../docs/DECISIONS.md)）。SyncReadMemの出力捕捉とRF格納を含むLD遅延を検証する。

## コマンド

```sh
sbt hw/test                 # ChiselSim のテスト（Verilator が必要）
sbt "hw/run [target-dir] [machine.json]"   # Core の SystemVerilog を出力（既定は generated/ と config/machine.json）
```

ChiselSim の作業ファイルは `build/chiselsim/` に作られる（gitignore 済み）。

## 構成（Phase 1）

| モジュール | 役割 |
|---|---|
| `Core` | トップ。Instruction memory（`Mem`、組合せ読出し）、PC、start/busy/done、ホストポート、観測ポート、違反信号と assertion（[docs/VERIFIER.md](../docs/VERIFIER.md)） |
| `Decoder` | 64bit bundle のデコードと、規則 L1 の検出（`vliw.isa.BundleCodec` とは独立に実装） |
| `RegisterFile` | 3R/2W のレジスタファイル |
| `WriteReservations` | レジスタごとの書込み区間（L3・L4・L6 の検出用） |
| `ComputeSlot` | ADD/SUB/MUL と、Compute書戻しポートの予約表 |
| `MemorySlot` | bank分割した Scratchpad、LD/ST と、Memory書戻しポートの予約表 |
| `WritebackSchedule` | 1つの書戻しポートの予約表。E_(s+L) での書込みと、L5 の検出 |
| `CoreParams` | machine description からのパラメータ。RTLが対応する範囲（II=1、LD L>=2、ST L=1）を検査する |

ホストは停止中に、命令・レジスタ・Scratchpad を書き込み、プログラム長を与えて start をかける。実行中は内部状態を変更しない。

## テスト

| テスト | 内容 |
|---|---|
| 単体（`RegisterFileSpec` ほか） | 各モジュールのタイミングと違反信号 |
| `CoreSpec` | ISA例、HALT のみ、再スタート |
| `CaseSpec` | `tests/cases` の全ての合法ケース（cycles、最終状態、golden trace） |
| `AssertionSpec` | `tests/cases` の全ての不正ケース（期待した規則の assertion） |
| `EmitSpec` | SystemVerilog の生成と、対応しない構成の拒否 |

RTL と参照モデルの乱数による突き合わせは、`crosscheck/RtlDifferentialSpec` で行う。
