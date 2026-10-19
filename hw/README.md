# Hardware

Phase 1のChisel実装先。sbtプロジェクト `hw`、パッケージ `vliw.hw`。依存は `isa`。Chisel 7.15.0、firtool 1.158.0、ChiselSim（Verilator。版は [project/toolchain.properties](../project/toolchain.properties)。CI では action `.github/actions/setup-toolchain` がその版を入れる。手元で入れる方法は [docs/toolchain/README.md](../docs/toolchain/README.md)）。

Machine Descriptionの読み込み、RF、Compute slot、Memory slot、Instruction ROM、PC、開始・終了制御を実装する。ChiselSimで`sim/`の参照トレースと結果・commit時刻・doneを比較する。

公式chisel-templateの確認済み構成を参考に、既存のsbtビルドへサブプロジェクト `hw` として追加し、依存バージョンを固定する（Scala 2.13.18、Chisel 7.x。[ADR-0004](../docs/DECISIONS.md)）。SyncReadMemの出力捕捉とRF格納を含むLD遅延を検証する。

## コマンド

```sh
sbt hw/test                 # ChiselSim のテスト（Verilator が必要）
sbt "hw/run [target-dir] [machine.json]"   # Core の SystemVerilog を出力（既定は generated/ と config/machine.json）
```

ChiselSim の作業ファイルは `build/chiselsim/<Suite>/<test>/` に作られる（gitignore 済み）。

ChiselSim のテストは `ChiselSim` ではなく `VliwChiselSim` を mix in する（`scripts/check.sh static` が検査する。[ADR-0013](../docs/DECISIONS.md#adr-0013-chiselsim-の-verilator-は1スレッドで-verilate-し乱数の-seed-を明示する)）。`VliwChiselSim` は、すべてのシミュレーションに次の2つを加える。

- Verilator に `--build-jobs 0 --verilate-jobs 1` を渡す。verilation は1スレッドで、C++ のビルドは全コアで行う。svsim の既定の `-j 0` では、Verilator 5.020（#74 のときの固定の版）がまれに終了時に `attempted to destroy locked Thread Pool` で abort し、`make ... Error 255` になる（#74）
- 乱数の seed を明示し、記録する。ChiselSim はレジスタとメモリを Verilator の乱数で初期化してビルドする（`RANDOMIZE_REG_INIT`・`RANDOMIZE_MEM_INIT`）。reset のあるレジスタ（PC、busy、スロットのパイプラインなど）は reset でその値を失うが、reset のない `RegisterFile` のレジスタとメモリ（Scratchpad、命令メモリ）は、書き込まれるまで乱数の値を保つ。`VliwChiselSim` は、その seed を `+verilator+seed+<n>` として渡す。seed は、`[sim-seed]` の行で sbt のログに、`seed.txt` で各シミュレーションのディレクトリに残る。seed は環境変数 `VLIW_SIM_SEED` で選ぶ

```sh
sbt hw/test                        # 既定の seed（1）
VLIW_SIM_SEED=123 sbt hw/test      # 1 から 2147483647 の値
VLIW_SIM_SEED=random sbt hw/test   # テストの JVM ごとに新しい seed（ログに残る）
```

`+verilator+seed+` がないときに乱数をどこから始めるかは、Verilator の版で違い、どこにも記録されない。同じ seed でも、版が違えば乱数の値が違うことがある。このため `VliwChiselSim` は、seed をいつも渡し、PATH の Verilator の版と一緒に記録する（`VLIW_SIM_SEED=1 (default) verilator=<版>` の形。[ADR-0020](../docs/DECISIONS.md#adr-0020-verilator-と-jdk-の版を1か所で決め版を変える前に候補を対照と同じ-run-で試す) の R3）。版ごとの挙動は [docs/toolchain/README.md](../docs/toolchain/README.md) を参照する。`build/chiselsim/<Suite>/<test>/workdir-verilator/` で `make replay` を実行すると、ビルドし直してから、同じ入力と seed でシミュレーションを再実行する。Makefile は絶対パスを含むので、そのディレクトリでだけ動く。また、ビルドし直すときに、そのディレクトリの `compilation-log.txt`・`simulation-log.txt` を消す。

`VliwChiselSim` は、テストの JVM ごとに1回、`[toolchain] verilator=<PATH の版> expected=<期待する版> (pinned|env)` の行も sbt のログに出す（`vliw.hw.Toolchain`。[docs/toolchain/README.md](../docs/toolchain/README.md)）。

## 構成（Phase 1）

| モジュール | 役割 |
|---|---|
| `Core` | トップ。Instruction memory（`Mem`、組合せ読出し）、PC、start/busy/done、ホストポート、観測ポート（PC、Decoder の出力、commit）、違反信号と assertion（[docs/VERIFIER.md](../docs/VERIFIER.md)） |
| `Decoder` | 64bit bundle のデコードと、規則 L1 の検出（`vliw.isa.BundleCodec` とは独立に実装）。mvp-1 では MAC も |
| `RegisterFile` | 3R/2W のレジスタファイル。ポート2は ST のソース、mvp-1 の MAC では rs3 を読む |
| `WriteReservations` | レジスタごとの書込み区間（L3・L4・L6 の検出用） |
| `ComputeSlot` | ADD/SUB/MUL（mvp-1 では MAC も）と、Compute書戻しポートの予約表 |
| `MemorySlot` | bank分割した Scratchpad、LD/ST と、Memory書戻しポートの予約表 |
| `WritebackSchedule` | 1つの書戻しポートの予約表。E_(s+L) での書込みと、L5 の検出 |
| `CoreParams` | machine description からのパラメータ。RTLが対応する範囲（II=1、LD L>=2、ST L=1、読出しポート3本）を検査し、`CoreParams.check` で範囲外の理由をすべて返す（[docs/MACHINE.md](../docs/MACHINE.md)「実装ごとの対応範囲」） |
| `FixedPipeline` | 資源条件を揃えた固定パイプライン（#75、[ADR-0017](../docs/DECISIONS.md)）。命令メモリ・命令制御・RF を持たず、静的なスケジュール（`FixedPlan`）をサイクルカウンタで実行する。`ComputeSlot` と `MemorySlot` をそのまま使う。値は32bit のレジスタ（`StoragePolicy`）。start/done と cycles の数え方は `Core` と同じ（E_T の cycle T を HALT とみなし、T+1 cycles） |
| `FixedPlan` | 固定パイプラインの計画（各操作の発行サイクルと読む値）と、組み立て時の検査（スロット、L3、L5、L7、L9。verifier とは独立）。値のレジスタの割り当てと、その寿命の検査 |
| `FixedParams` | 固定パイプラインのパラメータ。対応する machine は `Core` と同じで、`CoreParams.unsupported` と同じ理由で拒否する |

ホストは停止中に、命令・レジスタ・Scratchpad を書き込み、プログラム長を与えて start をかける。実行中は内部状態を変更しない。

## ISA mvp-1（MAC、#77）

machine description に MAC があれば（`config/machine-mvp1.json` など）、RTL は MAC を実装する（[docs/ISA.md](../docs/ISA.md)「mvp-1: MAC」、[ADR-0018](../docs/DECISIONS.md)）。MAC がない mvp-0 の machine では、MAC の論理を一切作らない。`config/machine.json` から出力する SystemVerilog は、#77 の前と同じである。

- `Decoder`: opcode は machine description から取る。rs3 は bits 15:12 で、bits 11:0 は予約。ADD/SUB/MUL の bits 15:0 は予約のまま。mvp-0 では opcode 4 は未知の opcode（L1）のまま
- `ComputeSlot`: MAC は発行サイクルに読んだ値で `low32(rs1 × rs2 + rs3)` を計算し、ほかの演算と同じ予約表で E_(s+L_MAC) に Compute の書戻しポートから書く。L5 の衝突も同じく検出する。積は MUL と同じ式なので、出力する SystemVerilog では乗算器を MUL と共有する（Compute スロットは1サイクルに1つしか発行しないので、II 1 では外から区別できない）
- `Core`: RF の読出しポートは3本のまま。ポート2のアドレスは、Compute の演算が MAC なら rs3、それ以外なら ST のソースにする。L3 の検査に MAC の rs3 を足す。MAC と ST を同じ bundle に置くと読出しが4つになり、違反信号 `readPorts` と L11 の assertion を出す（ポート2は rs3 を読むので、ST は正しい値を書かない）
- `CoreParams`: `read_ports` 4 は範囲外（読出し4本の RTL は #57）。MAC の ii は1だけ。MAC の latency は、MUL と同じくローダの範囲全体（1〜8）
- `FixedPipeline` は MAC を使わない。mvp-1 の machine では、`ComputeSlot` の3つ目のオペランドを0に固定する

## テスト

| テスト | 内容 |
|---|---|
| 単体（`RegisterFileSpec` ほか） | 各モジュールのタイミングと違反信号 |
| `DecoderSpec` | MAC の欄、予約 bit、レジスタ数を超える rs3、mvp-0 に MAC がないこと |
| `CoreSpec` | ISA例、HALT のみ、再スタート。mvp-1: ポート2の読出し、MAC と ST の L11、MUL・LD・MAC・ST・ADD の命令列（MAC の latency 2 と 3） |
| `CaseSpec` | `tests/cases` の全ての合法ケース（cycles、最終状態、golden trace） |
| `AssertionSpec` | `tests/cases` の全ての不正ケース（期待した規則の assertion） |
| `EmitSpec` | SystemVerilog の生成（mvp-0 と mvp-1）と、対応しない構成の拒否 |
| `FixedPipelineSpec` | 固定パイプライン: 計画の検査の各規則、値のレジスタの共有と寿命、Core と同じ範囲の拒否、SystemVerilog（Decoder と RegisterFile がない）、手で計算した例の発行・書戻し・done の cycle（`FixedDriver` は `CoreDriver` と同じ数え方） |

RTL と参照モデルの乱数による突き合わせは、`crosscheck/RtlDifferentialSpec` で行う。
