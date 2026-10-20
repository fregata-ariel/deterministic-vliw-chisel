# deterministic-vliw-chisel

[![CI](https://github.com/fregata-ariel/deterministic-vliw-chisel/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/fregata-ariel/deterministic-vliw-chisel/actions/workflows/ci.yml)

Chiselで構築する、コンパイラ主導の静的スケジューリング・固定サイクル実行の研究用マイクロエンジン。

設計原則：**コンパイラが時間配置を決め、ハードウェアがそのスケジュールを実行する。**

Phase 0（ISAと実行意味論の確定、machine description、アセンブラ、独立verifier、サイクル精度の参照モデル、テストスイート）、Phase 1（Chisel RTL。参照モデルとサイクル単位で一致）、Phase 2（DSL からの自動命令生成。SAXPY・FIR・行列積が機能・サイクルとも一致）、Phase 3（ループの Modulo Scheduling と MAC。II を参照モデルと RTL のトレースで確認）が完了しています（[Phase 0](docs/PHASE0.md)・[Phase 1](docs/PHASE1.md)・[Phase 2](docs/PHASE2.md)・[Phase 3](docs/PHASE3.md) の完了報告）。ベンチマークの記録は [benchmarks/RESULTS.md](benchmarks/RESULTS.md)（資源条件を揃えた固定パイプラインとの比較を含む）、Modulo Scheduling の記録は [benchmarks/MODULO.md](benchmarks/MODULO.md)、レジスタ数を変えた記録は [benchmarks/REGISTERS.md](benchmarks/REGISTERS.md)、MAC（ISA mvp-1）のある構成とない構成の比較は [benchmarks/MAC.md](benchmarks/MAC.md)（cycle ベース。Fmax・面積・電力は未測定）。次は Phase 4（DMA、Double Buffer、bank 最適化）で、最初に SIMD を、MAC 単独と分けて比べます（[#109](https://github.com/fregata-ariel/deterministic-vliw-chisel/issues/109)）。SIMD の方式は W（レジスタの組による 32bit×2 レーンと2語の LD/ST。ISA mvp-2、[#78](https://github.com/fregata-ariel/deterministic-vliw-chisel/issues/78) で決定）で、[#110](https://github.com/fregata-ariel/deterministic-vliw-chisel/issues/110) で段階的に実装しています。

## 最初に読む文書

- [設計方針・技術的系譜](docs/DESIGN.md)
- [ISAとサイクル契約](docs/ISA.md)
- [開発順序・完了条件](docs/ROADMAP.md)
- [設計判断記録（ADR）](docs/DECISIONS.md)
- [Phase 0 完了報告](docs/PHASE0.md)・[Phase 1 完了報告](docs/PHASE1.md)・[Phase 2 完了報告](docs/PHASE2.md)・[Phase 3 完了報告](docs/PHASE3.md)
- [命令生成](docs/COMPILER.md)
- 形式: [machine description](docs/MACHINE.md)・[カーネルDSL](docs/DSL.md)・[アセンブリ](docs/ASM.md)・[テストケース](docs/TESTCASE.md)・[トレース](docs/TRACE.md)・[メトリクス](docs/METRICS.md)・[verifier](docs/VERIFIER.md)
- [一次資料](docs/REFERENCES.md)

## リポジトリの定義

| 項目 | 設定 |
|---|---|
| 名前 | deterministic-vliw-chisel |
| 説明 | Compiler-scheduled, fixed-cycle VLIW microengine in Chisel |
| 公開範囲 | public（GitHub上の設定に合わせる。変更する場合はこの行も更新する） |
| 既定ブランチ | main（PRとCIを経てマージする） |
| ライセンス | 未選定。公開や外部コード導入時に決定 |

初回の作成に使ったスクリプト（`scripts/create-github-repo.sh`）は、作成完了に伴い削除した（git履歴に残る）。

## 構成

| パス | 責務 |
|---|---|
| `config/machine.json` | ISA、演算遅延、資源制約の共有定義案（形式: [docs/MACHINE.md](docs/MACHINE.md)）。ISA mvp-1（MAC）の既定は `config/machine-mvp1.json`、ISA mvp-2（W）の既定は `config/machine-mvp2.json` |
| `build.sbt`, `project/` | sbtマルチプロジェクト定義、バージョン固定（Verilator と JDK は `project/toolchain.properties`） |
| `isa/` | machine description、命令表現、エンコーディング（全プロジェクトの共通基盤） |
| `hw/` | Chisel生成器、ChiselSimによるRTL検証（Phase 1）。`sbt "hw/run"` で `generated/` にSystemVerilogを出力 |
| `compiler/` | 独立verifier、カーネルDSL、DAG、静的スケジューラ、register allocation、コード生成、ループ IR、Modulo Scheduling（[docs/COMPILER.md](docs/COMPILER.md)） |
| `sim/` | ISA参照モデル、サイクル精度モデル |
| `tests/` | 実装をまたいで共有するテストデータ（[テストケース](tests/cases/README.md)、golden、縮小machine定義） |
| `crosscheck/` | verifier・参照モデル・RTL・コンパイラの間の差分テストと、ベンチマーク記録の生成（テストコードのみ） |
| `benchmarks/` | 整数SAXPY、FIR、固定サイズ行列積の記録（[RESULTS.md](benchmarks/RESULTS.md)、MAC のある構成との比較は [MAC.md](benchmarks/MAC.md)） |
| `docs/` | 方針、仕様、判断理由、開発順序 |
| `scripts/check.sh` | 手元とCIで共通のチェック入口 |
| `scripts/mutation-check.sh` | mutation チェック（[tests/cases/README.md](tests/cases/README.md#mutation-チェック)） |
| `scripts/repeat-tests.sh` | テストの繰り返し実行（間欠的な失敗の頻度を測る。#74） |
| `scripts/toolchain.sh` | `project/toolchain.properties` の値を読み、PATH の Verilator が期待する版かを確かめる（[docs/toolchain/README.md](docs/toolchain/README.md)） |
| `scripts/verilator-build.sh` | Verilator の1つの版を、タグの commit を確かめてからソースでビルドする（[docs/toolchain/README.md](docs/toolchain/README.md)） |
| `scripts/check-workflows.sh` | workflow が Verilator と JDK を `.github/actions/setup-toolchain` でだけ入れることと、版の上書き（`VLIW_VERILATOR_EXPECTED`・`VLIW_VERILATOR_JOBS`）を候補の workflow と読み手だけが使うことを確かめる（`scripts/check.sh static`） |
| `scripts/toolchain-candidates.py` | 候補の Verilator を固定の版と同じ run で試す workflow（`.github/workflows/toolchain-candidates.yml`）の処理: knob の確認、job ごとの要約、timing、比較と記録（[docs/toolchain/README.md](docs/toolchain/README.md#候補を試す)） |
| `scripts/verilator-exit-race.py` | Verilator の終了時の abort の頻度を、jobs の設定ごとに測る（[ADR-0013](docs/DECISIONS.md#adr-0013-chiselsim-の-verilator-は1スレッドで-verilate-し乱数の-seed-を明示する)） |
| `.github/workflows/ci.yml` | GitHub Actions CI |
| `.github/actions/setup-toolchain/` | CI で JDK・sbt・Verilator を `project/toolchain.properties` の版で入れる action（[docs/toolchain/README.md](docs/toolchain/README.md#ci-で入れる)） |

## 開発

必要なもの: JDK、sbt、jq、shellcheck、Python 3（`scripts/check.sh static` と mutation チェック）、Verilator（`hw` の ChiselSim テストに使う）。JDK と Verilator の版は [project/toolchain.properties](project/toolchain.properties) にあり、`scripts/toolchain.sh check` で手元の Verilator を確かめられます（入れ方は [docs/toolchain/README.md](docs/toolchain/README.md)。apt にない版は `scripts/verilator-build.sh` でビルドできます）。CI では、action `.github/actions/setup-toolchain` が JDK・sbt・Verilator をこの版で入れます。バージョンと構成の根拠は[ADR-0004](docs/DECISIONS.md#adr-0004-ツールチェーンとバージョン固定)と[ADR-0020](docs/DECISIONS.md#adr-0020-verilator-と-jdk-の版を1か所で決め版を変える前に候補を対照と同じ-run-で試す)にあります。

```sh
scripts/check.sh          # 全チェック（CIと同じ内容）
scripts/check.sh static   # JSON構文・shellcheck・Python の構文・空白エラー・VliwChiselSim の使用・mutation の判定・toolchain.sh と setup-toolchain の検査・workflow の検査・候補の workflow の処理の検査
scripts/check.sh scala    # scalafmt・コンパイル（警告はエラー）・テスト（sbt の前に scripts/toolchain.sh check）
scripts/check.sh sbt "hw/testOnly vliw.hw.CaseSpec"  # 任意の sbt コマンドを scala と同じログ付きで実行
sbt test                  # テストだけを実行

sbt "isa/run machine"                                   # machine description の検証とhash
sbt "isa/run asm prog.asm prog.hex"                     # アセンブル（.hex / .bin）
sbt "isa/run disasm prog.hex"                           # 逆アセンブル
sbt "isa/run asm mac.asm mac.hex --machine config/machine-mvp1.json"  # mvp-1（MAC）は --machine で指定（disasm も同じ）
sbt "compiler/run verify tests/cases/isa_example.json"  # 合法性の静的検査
sbt "compiler/run compile saxpy8 fir4x8 matmul3"       # DSL のカーネルを命令列にする（generated/kernels/）
sbt "sim/run tests/cases/isa_example.json --trace out.jsonl --metrics out.json"  # 参照モデルで実行
```

`scripts/check.sh` の `scala` と `sbt` は、sbt の出力（`sbt.log`）、環境（JDK・sbt の版、`scripts/toolchain.sh check` の出力、CPU・メモリ、同時に動いていたプロセス）、5秒ごとのプロセスとメモリの記録、テストの並行性を決める sbt の実効設定（`show` の出力）を `build/check-logs/<UTC時刻>/`（`CHECK_LOG_DIR` で変更可）に残します。失敗したときは、その時点のプロセス・メモリ・カーネルログと、その実行で書かれた ChiselSim のログ・JUnit レポートを加えます。`CHECK_TIMEOUT`（秒）を与えると、その時間で sbt を止め、失敗として同じ記録を残します。CI の `scala` ジョブは `CHECK_TIMEOUT` を付けて実行し、失敗したときにこれらを artifact として残します（#74）。ChiselSim の乱数の seed と Verilator の設定は [hw/README.md](hw/README.md) を参照してください。

同じチェックアウトで sbt を2つ同時に動かさないでください（`scripts/check.sh` や `scripts/repeat-tests.sh` から起動するものも含みます）。2つめの sbt は、先の sbt のサーバーの起動を検出して `ServerAlreadyBootingException` で終了するか、起動しても同じ `build/chiselsim/<Suite>/<test>/` を使うので、Verilator のビルドが `make: *** [Makefile:12: simulation] Error 2` で失敗します（#74 の `scripts/repeat-tests.sh` の `hw-concurrent` で10回中10回）。並行に動かすときは、`git worktree add` などで別のチェックアウトを使ってください。

`compiler` と `sim` は `isa` にのみ依存し、互いに依存しません。両者の突き合わせは `crosscheck` で行います（[ADR-0002](docs/DECISIONS.md#adr-0002-相互検査は言語ではなく状態表現と判定方法の違いで設計する)）。

## MVP

32bit整数、16レジスタ、演算＋メモリの2スロット、2バンクScratchpad、固定遅延ADD/SUB/MUL/LD/ST、HALT。
Phase 0ではループを展開し、開始前に入力を配置します。実行中の外部メモリアクセスと内部バックプレッシャーは対象外です。

`config/machine.json` は mvp-0 の**実装契約**です（revision 2、frozen）。RTL・命令列・verifier・参照モデルは、すべて `vliw.isa.MachineDescription` を通して同じ定義を参照します。
