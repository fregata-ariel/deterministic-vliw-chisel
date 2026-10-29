# Hardware

Phase 1のChisel実装先。sbtプロジェクト `hw`、パッケージ `vliw.hw`。依存は `isa`。Chisel 7.15.0、firtool 1.158.0、ChiselSim（Verilator。版は [project/toolchain.properties](../project/toolchain.properties)。CI では action `.github/actions/setup-toolchain` がその版を入れる。手元で入れる方法は [docs/toolchain/README.md](../docs/toolchain/README.md)）。

Machine Descriptionの読み込み、RF、Compute slot、Memory slot、Instruction ROM、PC、開始・終了制御を実装する。ChiselSimで`sim/`の参照トレースと結果・commit時刻・doneを比較する。

公式chisel-templateの確認済み構成を参考に、既存のsbtビルドへサブプロジェクト `hw` として追加し、依存バージョンを固定する（Scala 2.13.18、Chisel 7.x。[ADR-0004](../docs/DECISIONS.md)）。SyncReadMemの出力捕捉とRF格納を含むLD遅延を検証する。

## コマンド

```sh
sbt hw/test                 # ChiselSim のテスト（Verilator が必要）
sbt "hw/run [target-dir] [machine.json]"   # Core の SystemVerilog を出力（既定は generated/ と config/machine.json）
```

ChiselSim の作業ファイルは `build/chiselsim/<Suite>/<test>/` に作られる（gitignore 済み）。`CaseSpec`・`AssertionSpec` のビルドは machine ごとに1つで、その machine のテストの名前のディレクトリに作られる（下の「テスト」）。

ChiselSim のテストは `ChiselSim` ではなく `VliwChiselSim` を mix in する（`scripts/check.sh static` が検査する。[ADR-0013](../docs/DECISIONS.md#adr-0013-chiselsim-の-verilator-は1スレッドで-verilate-し乱数の-seed-を明示する)）。`VliwChiselSim` は、すべてのシミュレーションに次の3つを加える。

- Verilator に `--build-jobs 0 --verilate-jobs 1` を渡す。verilation は1スレッドで、C++ のビルドは全コアで行う。svsim の既定の `-j 0` では、Verilator 5.020（#74 のときの固定の版）がまれに終了時に `attempted to destroy locked Thread Pool` で abort し、`make ... Error 255` になる（#74）
- 乱数の seed を明示し、記録する。ChiselSim はレジスタとメモリを Verilator の乱数で初期化してビルドする（`RANDOMIZE_REG_INIT`・`RANDOMIZE_MEM_INIT`・`RANDOM=$urandom`。下の「初期状態」）。`VliwChiselSim` は、その seed を `+verilator+seed+<n>` として渡す。seed は、`[sim-seed]` の行で sbt のログに、`seed.txt` で各シミュレーションのディレクトリに残る。seed は環境変数 `VLIW_SIM_SEED` で選ぶ
- 初期値の設定を確かめ、記録する（#129、[ADR-0021](../docs/DECISIONS.md#adr-0021-rtl-の初期状態への非依存を全-0全-1乱数の初期値で確かめる)）。ビルドの define に `RANDOMIZE_REG_INIT`・`RANDOMIZE_MEM_INIT`・`RANDOM` が1つずつあり、`RANDOM` の値が下の3つのモードのどれかで、`SYNTHESIS` がないことを確かめる。生成した SystemVerilog のすべての `reg` とメモリが、`` `ifndef SYNTHESIS `` の内側の初期化のブロックで `` `RANDOM `` から初期化されることも確かめる（`SvInitCheck`）。満たさなければ、そのテストはビルドの前に、ディレクトリ名を付けて失敗する。モードは、`seed.txt` の隣の `init.txt` に `init=ones RANDOM=32'hFFFFFFFF` の形で残る

```sh
sbt hw/test                        # 既定の seed（1）
VLIW_SIM_SEED=123 sbt hw/test      # 1 から 2147483647 の値
VLIW_SIM_SEED=random sbt hw/test   # テストの JVM ごとに新しい seed（ログに残る）
```

`+verilator+seed+` がないときに乱数をどこから始めるかは、Verilator の版で違い、どこにも記録されない。同じ seed でも、版が違えば乱数の値が違うことがある。このため `VliwChiselSim` は、seed をいつも渡し、PATH の Verilator の版と一緒に記録する（`VLIW_SIM_SEED=1 (default) verilator=<版>` の形。[ADR-0020](../docs/DECISIONS.md#adr-0020-verilator-と-jdk-の版を1か所で決め版を変える前に候補を対照と同じ-run-で試す) の R3）。版ごとの挙動は [docs/toolchain/README.md](../docs/toolchain/README.md) を参照する。`build/chiselsim/<Suite>/<test>/workdir-verilator/` で `make replay` を実行すると、ビルドし直してから、同じ入力と seed でシミュレーションを再実行する。Makefile は絶対パスを含むので、そのディレクトリでだけ動く。また、ビルドし直すときに、そのディレクトリの `compilation-log.txt`・`simulation-log.txt` を消す。

`VliwChiselSim` は、テストの JVM ごとに1回、`[toolchain] verilator=<PATH の版> expected=<期待する版> (pinned|env)` の行も sbt のログに出す（`vliw.hw.Toolchain`。[docs/toolchain/README.md](../docs/toolchain/README.md)）。

### 初期状態

すべてのレジスタとメモリの初期値は、firtool の `RANDOM` の値で決まる。モードは3つあり、ビルドごとに選ぶ（`vliw.hw.InitMode`）。`simulate` は `urandom` でビルドする。`simulateInit(mode)(module)(body)` は、選んだモードでビルドする。モード1つにつき、ビルドが1回増える。

| モード | `RANDOM` の値 | 使うテスト |
|---|---|---|
| `zeros` | `32'h0` | `crosscheck/InitStateSpec` |
| `ones` | `32'hFFFFFFFF` | `crosscheck/InitStateSpec`、`VliwChiselSimSpec` |
| `urandom` | `$urandom`（ChiselSim の既定。seed は `VLIW_SIM_SEED`） | ほかのすべてのテスト |

全 0・全 1 は、幅のある 32 bit の値にする。`0` や `'1` では、メモリの初期化のブロックで Verilator が警告（`WIDTHCONCAT`・`WIDTHEXPAND`）を出し、ビルドが失敗する。全 0・全 1 のビルドは乱数の関数を呼ばないので、値は seed にも Verilator の版にもよらない。`make replay` は、Makefile の define で同じモードを使う。

reset のある状態（PC、busy、done、プログラム長、書戻しの予約表、`loadStage`、固定パイプラインのカウンタなど）は、最初の reset の境界で reset の値になる。reset のない次の状態は、書き込まれるまで初期値を保つ。

| reset のない状態 | 場所 |
|---|---|
| RF のレジスタ | `RegisterFile` の `regs`（mvp-2 では `BankedRegisterFile` の `bank0`・`bank1`） |
| 命令メモリ | `Core` の `imem` |
| Scratchpad の bank と、その読出しポートのレジスタ（firtool が足す `_RW0_*`） | `MemorySlot` の `banks` |
| 読み出した bank の番号 | `MemorySlot` の `readBank` |
| 固定パイプラインの値のレジスタ | `FixedPipeline` の `regs` |

ただし、最初の reset の境界で RF に書込みが起きることがある。書戻しの予約表と `loadStage` には reset があるが、その境界ではまだ初期値を持っており、RF への書込みは reset 中も止まらないからである。全 1 では、両方のポートが、番号のすべての bit が 1 のレジスタ（16本なら r15、8本なら r7）に書く（残るのは Memory のポートの値）。mvp-2 では、予約表の項目の組の印（`pair`）と `loadWide` も全 1 から始まるので、そのレジスタの組（16本なら r14:r15）に書く（`machine-mvp2.json` の試行では、どちらも 0 が残った）。全 0 では何も書かない。乱数では、行き先も値も乱数になり、busy・pc・プログラム長の初期値しだいで、その境界に命令が1つ発行され、ST が Scratchpad に書くこともある。この境界の書込みがあるので、ホストは reset の後に入力を書く（下の「構成」）。そうすれば、書かれるのは未定義の位置だけで、合法なプログラムの結果は変わらない。この書込みを RTL で止めるかは、状態を足す #110・#112 で決める（ADR-0021）。#110 の第3段（W）は、止めなくても合法なプログラムの結果が変わらないので、今の振る舞いを保ち、止めるかどうかはオーナーへの問いとして残した（ADR-0021 の 2026-10-29 の補足）。

#110 の第3段で足した状態は、次のとおりに分ける（ADR-0021 の 2026-10-29 の補足）。

| 状態 | reset | 理由 |
|---|---|---|
| `BankedRegisterFile` の `bank0`・`bank1`（mvp-2 の RF。ビットの数は `RegisterFile` の `regs` と同じ） | なし | `regs` と同じ。ホストは reset の後に入力を書き、合法なプログラムは宣言したか書いたレジスタだけを読む（L8）。組は2つの bank のレジスタ2つ |
| 書戻しの予約表（`WritebackSchedule` の `slot`）の `pair`・`data1` | あり | 今の `RegInit`（項目のすべての欄を 0 にする）に含まれる |
| `MemorySlot` の `loadWide`（`loadStage` の load が LD.W か） | あり | `loadStage` と同じく `RegInit`（`RegInit(false.B)`） |
| `MemorySlot` の `readBank` | なし（変えない） | レーン1 の bank は `(readBank + 1) mod banks` で、組合せ回路で作る。レジスタは足さない |
| Scratchpad の bank と `_RW0_*` | なし（変えない） | メモリは足さない。各 bank のアクセスは1サイクルに1つのまま |
| `WriteReservations` の `remaining`、busy、done、pc、プログラム長、`imem` | 変えない | |

## 構成（Phase 1）

| モジュール | 役割 |
|---|---|
| `Core` | トップ。Instruction memory（`Mem`、組合せ読出し）、PC、start/busy/done、ホストポート、観測ポート（PC、Decoder の出力、commit）、違反信号と assertion（[docs/VERIFIER.md](../docs/VERIFIER.md)） |
| `Decoder` | 64bit bundle のデコードと、規則 L1 の検出（`vliw.isa.BundleCodec` とは独立に実装）。mvp-1 では MAC も、mvp-2 では W の演算も |
| `RegisterFile` | 3R/2W のレジスタファイル。ポート2は ST のソース、mvp-1 の MAC では rs3 を読む |
| `BankedRegisterFile` | mvp-2 のレジスタファイル。偶数と奇数のレジスタの2つの bank で、bank ごとに 3R/2W。組の読出しと書込み |
| `WriteReservations` | レジスタごとの書込み区間（L3・L4・L6 の検出用） |
| `ComputeSlot` | ADD/SUB/MUL（mvp-1 では MAC も、mvp-2 では VADD/VSUB/VMUL/VMAC も）と、Compute書戻しポートの予約表 |
| `VectorLane` | mvp-2 のベクトル演算のレーン1（2つ目の 32×32 の乗算器）。#57 で面積を分けて測るため、別のモジュールにした |
| `MemorySlot` | bank分割した Scratchpad、LD/ST（W-full では LD.W・ST.W も）と、Memory書戻しポートの予約表 |
| `WritebackSchedule` | 1つの書戻しポートの予約表。E_(s+L) での書込みと、L5 の検出 |
| `CoreParams` | machine description からのパラメータ。RTLが対応する範囲（全演算の II=1、LD・LD.W の L>=2、ST・ST.W の L=1、読出しポート3本）を全演算で検査し、`CoreParams.check` で範囲外の理由をすべて返す（[docs/MACHINE.md](../docs/MACHINE.md)「実装ごとの対応範囲」） |
| `FixedPipeline` | 資源条件を揃えた固定パイプライン（#75、[ADR-0017](../docs/DECISIONS.md)）。命令メモリ・命令制御・RF を持たず、静的なスケジュール（`FixedPlan`）をサイクルカウンタで実行する。`ComputeSlot` と `MemorySlot` をそのまま使う。値は32bit のレジスタ（`StoragePolicy`）。start/done と cycles の数え方は `Core` と同じ（E_T の cycle T を HALT とみなし、T+1 cycles） |
| `FixedPlan` | 固定パイプラインの計画（各操作の発行サイクルと読む値）と、組み立て時の検査（スロット、L3、L5、L7、L9。verifier とは独立）。値のレジスタの割り当てと、その寿命の検査 |
| `FixedParams` | 固定パイプラインのパラメータ。対応する machine は ISA mvp-0・mvp-1 の `Core` と同じで、`CoreParams.unsupported` と同じ理由で拒否する。mvp-2 の machine は、その前に自分の理由で拒否する（ADR-0022） |

ホストは停止中に、reset の後で、命令・レジスタ・Scratchpad を書き込み、プログラム長を与えて start をかける（`CoreDriver.start` と同じ）。最初の reset の境界は RF や Scratchpad に書くことがあるので（上の「初期状態」）、reset の前に書いた値は残るとは限らない。実行中は内部状態を変更しない。

## ISA mvp-1（MAC、#77）

machine description に MAC があれば（`config/machine-mvp1.json` など）、RTL は MAC を実装する（[docs/ISA.md](../docs/ISA.md)「mvp-1: MAC」、[ADR-0018](../docs/DECISIONS.md)）。MAC がない mvp-0 の machine では、MAC の論理を一切作らない。`config/machine.json` から出力する SystemVerilog は、#77 の前と同じである。

- `Decoder`: opcode は machine description から取る。rs3 は bits 15:12 で、bits 11:0 は予約。ADD/SUB/MUL の bits 15:0 は予約のまま。mvp-0 では opcode 4 は未知の opcode（L1）のまま
- `ComputeSlot`: MAC は発行サイクルに読んだ値で `low32(rs1 × rs2 + rs3)` を計算し、ほかの演算と同じ予約表で E_(s+L_MAC) に Compute の書戻しポートから書く。L5 の衝突も同じく検出する。積は MUL と同じ式なので、出力する SystemVerilog では乗算器を MUL と共有する（Compute スロットは1サイクルに1つしか発行しないので、II 1 では外から区別できない）
- `Core`: RF の読出しポートは3本のまま。ポート2のアドレスは、Compute の演算が MAC なら rs3、それ以外なら ST のソースにする。L3 の検査に MAC の rs3 を足す。MAC と ST を同じ bundle に置くと読出しが4つになり、違反信号 `readPorts` と L11 の assertion を出す（ポート2は rs3 を読むので、ST は正しい値を書かない）
- `CoreParams`: `read_ports` 4 は範囲外（読出し4本の RTL は #57）。MAC の ii は1だけ。MAC の latency は、MUL と同じくローダの範囲全体（1〜8）
- `FixedPipeline` は MAC を使わない。mvp-1 の machine では、`ComputeSlot` の3つ目のオペランドを0に固定する

## ISA mvp-2（W、#110）

machine description が ISA mvp-2 なら（`config/machine-mvp2.json` など）、RTL は W を実装する（[docs/ISA.md](../docs/ISA.md)「mvp-2: W」、[ADR-0022](../docs/DECISIONS.md)。#110 の第3段）。mvp-0 と mvp-1 の machine では、W の論理を一切作らない。`config/machine.json`・`config/machine-mvp1.json`・`tests/machines/mvp1_mac3.json` から出力する SystemVerilog は、第3段の前と1 byte も変わらない（48 ファイルの sha256 で確かめた）。

- `CoreParams`: W-full と W-reg（RF の bank ごとに `read_ports` 3）を受け付ける。ii と latency は machine の全演算で確かめ、`read_ports` 4（#57）、II が1でない演算、LD.W の latency 1、ST・ST.W の latency 2 以上を理由付きで拒否する
- `Decoder`: VADD・VSUB・VMUL・VMAC は、種類 Add・Sub・Mul・Mac に `vector` を付けて decode する（`ComputeKind` を増やすと mvp-0・mvp-1 の幅が変わるため）。B（bit 11）は `broadcast`、LD.W・ST.W は Ld・St に `wide` を付ける。組の欄は偶数、予約 bit は演算ごと（L1）。VMAC の rs3 は MAC と同じ bits 15:12
- `BankedRegisterFile`: bank 0 が偶数、bank 1 が奇数のレジスタを持つ。欄 k はどちらの bank もポート k で行 `reg >> 1` を読み、スカラーの欄は自分の bank の値、組の欄は両方の bank の値、ブロードキャストの rs1 は自分の bank の値だけを使う（ISA.md の形 S のデータ経路）。書込みは、スカラーなら `reg(0)` の bank に、組なら両方の bank の行 `reg >> 1` に書く。ポート2は、MAC・VMAC の rs3 か ST・ST.W のソースを読む
- `ComputeSlot`: レーン0 は今のデータ経路（乗算器を MUL・MAC・VMUL・VMAC で共有する）、レーン1 は `VectorLane`（2つ目の乗算器）。ベクトル演算は自分の latency を machine description から取り、予約表に組の項目1つとして入る（L5 は操作で数える）
- `MemorySlot`: LD.W・ST.W の語 a はレーン0、a+1 はレーン1。a+1 の bank がレーン1 の行と ST.W のデータを取る（2 bank で a が奇数なら、レーンが入れ替わり、bank 0 は次の行）。各 bank のアクセスは1サイクルに1つのまま。レーン1 の読出しは `(readBank + 1) mod banks` の bank から取る。どちらかの語が範囲外なら、どちらにもアクセスしない（L7）。LD.W は自分の latency で、組の項目として予約表に入る
- `WriteReservations`: 組の予約は両方のレジスタを、組の問い合わせは両方のレジスタを見る（L3・L4 はレジスタごと）
- `Core`: L11 は「（MAC か VMAC）かつ（ST か ST.W）」。同じ bundle の Compute と LD の書込み先は、レジスタの集合で比べる（L4）。観測ポートは組の commit（`pair`・`data1`）と ST.W の語 a+1（`memCommit1`）を出す。start・busy・pc・done・HALT、ストールしない方針は変えない
- `FixedPipeline` は W を使わず、mvp-2 の machine を拒否する

## テスト

| テスト | 内容 |
|---|---|
| 単体（`RegisterFileSpec` ほか） | 各モジュールのタイミングと違反信号 |
| `DecoderSpec` | MAC の欄、予約 bit、レジスタ数を超える rs3、mvp-0 に MAC がないこと、mvp-1 では VMAC の opcode 8 が未知であること |
| `CoreSpec` | ISA例、HALT のみ、再スタート。mvp-1: ポート2の読出し、MAC と ST の L11、MUL・LD・MAC・ST・ADD の命令列（MAC の latency 2 と 3） |
| `CaseSpec` | `tests/cases` の全ての合法ケース（違反の信号がないこと、cycles、最終状態、golden trace）。machine ごとに1回のビルド（下記）。mvp-2 のケースは `crosscheck/RtlMvp2Spec` |
| `AssertionSpec` | `tests/cases` の全ての不正ケース（最初に違反したサイクルで知らせた規則が、ケースの規則に含まれること。L8 だけのケースは最後まで走ること。命令領域を超えるケースはロードで拒否されること）と、machine ごとに1つのケースで assertion が実際に発火すること。machine ごとに1回のビルド（下記）。mvp-2 のケースは `crosscheck/RtlMvp2Spec` |
| `EmitSpec` | SystemVerilog の生成（mvp-0、mvp-1、mvp-2 の W-full・W-reg・latency の変種と、mvp-2 の範囲の両端）と、対応しない構成の拒否（理由の文） |
| `FixedPipelineSpec` | 固定パイプライン: 計画の検査の各規則、値のレジスタの共有と寿命、Core と同じ範囲の拒否、SystemVerilog（Decoder と RegisterFile がない）、手で計算した例の発行・書戻し・done の cycle（`FixedDriver` は `CoreDriver` と同じ数え方） |

`CaseSpec`・`AssertionSpec` は、RTL が対応する machine ごとに1回だけビルドする（#110 の Q3。共通の部分は `CaseBuildSpec`）。`simulate` は呼ぶたびに Verilator でビルドするので、ケースごとにビルドすると 40 回になる（合法 16、不正 24）。まとめた後は 7 回である（`CaseSpec` は `config/machine.json`・`config/machine-mvp1.json`・`mvp1_mac3.json` の3回、`AssertionSpec` はそれに `small_scratchpad.json` を足した4回）。手元（4 コア、Verilator 5.052）の JUnit で、2つのスイートは計 226.8 秒から 42.5 秒になった（[ADR-0022](../docs/DECISIONS.md) の 2026-10-29 の Q3 の補足）。

- 1つのビルドで、その machine のケースを名前の順に、テスト用の `CoreDriver.run` の `stopAtViolation` で走らせる。違反の信号が立てば、そのサイクルで止める（そのサイクルの終わりの境界の間 reset を立てるので assertion は発火せず、次のケースの `start` がコアを reset し直す）。信号が立たなければ、assertion が生きたまま done まで走る。合法なケースは信号がないことと期待値を、不正なケースは最初に違反したサイクルで知らせた規則がケースの規則（L8 を除く）に含まれることを確かめる。命令領域を超えるケースはロードで拒否され（L2）、シミュレーションに触れない
- ビルドの最後に、その machine の不正なケースの1つ（発火させるケース。RTL が確かめる規則が1つのもの）を、まず `stopAtViolation` で、次に `stopAtViolation` なしで走らせる。その規則の assertion が実際に発火し（ChiselSim の `AssertionFailed`）、その pc が止めたときの pc と同じであることを確かめる。違反の信号は assertion の条件そのものなので、これで、そのビルドの assertion が生きていて、違反の信号から発火までつながっていることを、1つの規則で確かめる。発火させるケースは、`CaseSpec` が `early_halt_mul`（L6）・`mac_overlapping_ld`（L4）・`mac3_mul_port`（L5）、`AssertionSpec` が `halt_not_last`（L2）・`mac_rs3_in_flight`（L3）・`address_out_of_range`（L7）・`mac3_mul_port`（L5）である。assertion が発火するとシミュレーションが終わるので、1つのビルドで発火させられる規則は1つである。そのため、2つのスイートは、不正なケースが2つ以上ある machine（`config/machine.json`・`config/machine-mvp1.json`）では違う規則を発火させる。`mvp1_mac3.json` の不正なケースは `mac3_mul_port` だけなので、2つのスイートが同じ L5 を発火させる。`crosscheck/RtlMvp2Spec` の L11・L1・L5 と合わせて、RTL が確かめるすべての規則（L1〜L7、L11）の assertion が、どこかのビルドで発火する（`RtlMvp2Spec` のテストが、発火させるケースの規則で確かめる）。ケースごとのビルドでは、不正なケースのそれぞれで assertion を発火させていた。今は、発火させるケース以外は、assertion の条件である違反の信号を確かめる
- テストは、ビルドごとに machine のテスト（machine_id で始まる名前）と、ケースごとのテストがある。ビルドは、このうち最初に走るテスト（ふつうは machine のテスト）で1回だけ行い、その名前のディレクトリに作られる。machine のテストは、ビルドが失敗したとき、発火させるケースが期待どおりに発火しないとき、ケースが失敗したときに失敗する。そのメッセージには、問題をすべて並べる（発火させるケースの問題、失敗したケースの数と名前、最初に失敗したケースのメッセージの順）。ビルドが止まったときは、止まった場所、走らなかったケース、失敗したケースの名前を info に出し、止めた例外をそのまま投げ直す。ケースごとのテストは、ビルドの中のそのケースの結果を報告する。名前は、`CaseSpec` と、`AssertionSpec` の L8 だけのケース・ロードで拒否されるケースでは、ケースごとにビルドしていたときと同じで、`AssertionSpec` のほかのケースは「`<name>: the core signals L6 in its first violating cycle`」の形になった
- ケースの検査の失敗（ScalaTest の assertion）の後は、次のケースに進む。それ以外の例外（cycle の上限、シミュレーションの異常終了）や、発火させるケースより前に発火した assertion は、ビルドをそこで止め、走らなかったケースのテストも失敗する。環境の失敗（Verilator のビルド、シミュレーションの異常終了など）は、止まったすべてのテストで、その例外をそのまま投げ直す。`scripts/mutation-classify.py` が、mutant を検出したのではなく ERROR と数えるためである
- RTL の範囲外の machine のケースは、今までどおり、ケースごとに理由付きのスキップとして表示する。新しいケースは、その machine のビルドに自動的に入る。新しい machine のケースを足すときは、その machine の発火させるケースを `CaseSpec`・`AssertionSpec` のコンパニオンオブジェクトの `firing` に足す（無ければ machine のテストがその旨で失敗する）

RTL と参照モデルの乱数による突き合わせは、`crosscheck/RtlDifferentialSpec` で行う。初期値を全 0・全 1 にした RTL の確認（L8 だけに反するカナリア、合法なプログラム、書かれない位置）は、`crosscheck/InitStateSpec` で行う（#129。mvp-2 は `machine-mvp2.json` を全 1 で）。ISA mvp-2 の RTL は、`crosscheck/RtlMvp2Spec` が machine ごとに1回のビルドで、W のケースと乱数の合法・不正な W のプログラムを、期待値・参照モデル・verifier と照合する。ISA.md の命令語と、mvp-0・mvp-1 の命令列の互換性は W-full のビルドだけで確かめ、W-full の変種（レジスタ8本・4 bank × 64 語）のビルドは乱数のプログラムだけを走らせる。不正なプログラムは、テスト用の `CoreDriver.run` の `stopAtViolation` で、最初の違反のサイクルで止める（[docs/VERIFIER.md](../docs/VERIFIER.md)「RTLのassertionとの対応」）。
