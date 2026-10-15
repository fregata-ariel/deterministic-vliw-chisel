# Hardware

Phase 1のChisel実装先。sbtプロジェクト `hw`、パッケージ `vliw.hw`。依存は `isa`。Chisel 7.15.0、firtool 1.158.0、ChiselSim（Verilator 5.020）。

Machine Descriptionの読み込み、RF、Compute slot、Memory slot、Instruction ROM、PC、開始・終了制御を実装する。ChiselSimで`sim/`の参照トレースと結果・commit時刻・doneを比較する。

公式chisel-templateの確認済み構成を参考に、既存のsbtビルドへサブプロジェクト `hw` として追加し、依存バージョンを固定する（Scala 2.13.18、Chisel 7.x。[ADR-0004](../docs/DECISIONS.md)）。SyncReadMemの出力捕捉とRF格納を含むLD遅延を検証する。

## コマンド

```sh
sbt hw/test                 # ChiselSim のテスト（Verilator が必要）
sbt "hw/run [target-dir] [machine.json]"   # Core の SystemVerilog を出力（既定は generated/ と config/machine.json）
```

ChiselSim の作業ファイルは `build/chiselsim/<Suite>/<test>/` に作られる（gitignore 済み）。

ChiselSim のテストは `ChiselSim` ではなく `VliwChiselSim` を mix in する（`scripts/check.sh static` が検査する。[ADR-0013](../docs/DECISIONS.md#adr-0013-chiselsim-の-verilator-は1スレッドで-verilate-し乱数の-seed-を明示する)）。`VliwChiselSim` は、すべてのシミュレーションに次の2つを加える。

- Verilator に `--build-jobs 0 --verilate-jobs 1` を渡す。verilation は1スレッドで、C++ のビルドは全コアで行う。svsim の既定の `-j 0` では、Verilator 5.020 がまれに終了時に `attempted to destroy locked Thread Pool` で abort し、`make ... Error 255` になる（#74）
- 乱数の seed を明示し、記録する。ChiselSim はレジスタとメモリを Verilator の乱数で初期化してビルドする（`RANDOMIZE_REG_INIT`・`RANDOMIZE_MEM_INIT`、reset までの値）。`VliwChiselSim` は、その seed を `+verilator+seed+<n>` として渡す。seed は、`[sim-seed]` の行で sbt のログに、`seed.txt` で各シミュレーションのディレクトリに残る。seed は環境変数 `VLIW_SIM_SEED` で選ぶ

```sh
sbt hw/test                        # 既定の seed（1）
VLIW_SIM_SEED=123 sbt hw/test      # 1 から 2147483647 の値
VLIW_SIM_SEED=random sbt hw/test   # テストの JVM ごとに新しい seed（ログに残る）
```

Verilator 5.020 は、`+verilator+seed+` がないと毎回同じ状態から乱数を始める（`srand48` を呼ばない）。このため seed を渡さなくても実行ごとの値は同じだが、その値は Verilator の版に依存する。`build/chiselsim/<Suite>/<test>/workdir-verilator/` で `make replay` を実行すると、ビルドし直してから、同じ入力と seed でシミュレーションを再実行する。Makefile は絶対パスを含むので、そのディレクトリでだけ動く。また、ビルドし直すときに、そのディレクトリの `compilation-log.txt`・`simulation-log.txt` を消す。

## 構成（Phase 1）

| モジュール | 役割 |
|---|---|
| `Core` | トップ。Instruction memory（`Mem`、組合せ読出し）、PC、start/busy/done、ホストポート、観測ポート（PC、Decoder の出力、commit）、違反信号と assertion（[docs/VERIFIER.md](../docs/VERIFIER.md)） |
| `Decoder` | 64bit bundle のデコードと、規則 L1 の検出（`vliw.isa.BundleCodec` とは独立に実装） |
| `RegisterFile` | 3R/2W のレジスタファイル |
| `WriteReservations` | レジスタごとの書込み区間（L3・L4・L6 の検出用） |
| `ComputeSlot` | ADD/SUB/MUL と、Compute書戻しポートの予約表 |
| `MemorySlot` | bank分割した Scratchpad、LD/ST と、Memory書戻しポートの予約表 |
| `WritebackSchedule` | 1つの書戻しポートの予約表。E_(s+L) での書込みと、L5 の検出 |
| `CoreParams` | machine description からのパラメータ。RTLが対応する範囲（II=1、LD L>=2、ST L=1）を検査し、`CoreParams.check` で範囲外の理由をすべて返す（[docs/MACHINE.md](../docs/MACHINE.md)「実装ごとの対応範囲」） |

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
