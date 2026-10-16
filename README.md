# deterministic-vliw-chisel

[![CI](https://github.com/fregata-ariel/deterministic-vliw-chisel/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/fregata-ariel/deterministic-vliw-chisel/actions/workflows/ci.yml)

Chiselで構築する、コンパイラ主導の静的スケジューリング・固定サイクル実行の研究用マイクロエンジン。

設計原則：**コンパイラが時間配置を決め、ハードウェアがそのスケジュールを実行する。**

Phase 0（ISAと実行意味論の確定、machine description、アセンブラ、独立verifier、サイクル精度の参照モデル、テストスイート）、Phase 1（Chisel RTL。参照モデルとサイクル単位で一致）、Phase 2（DSL からの自動命令生成。SAXPY・FIR・行列積が機能・サイクルとも一致）が完了しています（[Phase 0](docs/PHASE0.md)・[Phase 1](docs/PHASE1.md)・[Phase 2](docs/PHASE2.md) の完了報告）。ベンチマークの記録は [benchmarks/RESULTS.md](benchmarks/RESULTS.md)（資源条件を揃えた固定パイプラインとの比較を含む）、Modulo Scheduling の記録は [benchmarks/MODULO.md](benchmarks/MODULO.md)、レジスタ数を変えた記録は [benchmarks/REGISTERS.md](benchmarks/REGISTERS.md)（cycle ベース。Fmax・面積・電力は未測定）。次は Phase 3（Modulo Scheduling、MAC）です（[#66](https://github.com/fregata-ariel/deterministic-vliw-chisel/issues/66)）。

## 最初に読む文書

- [設計方針・技術的系譜](docs/DESIGN.md)
- [ISAとサイクル契約](docs/ISA.md)
- [開発順序・完了条件](docs/ROADMAP.md)
- [設計判断記録（ADR）](docs/DECISIONS.md)
- [Phase 0 完了報告](docs/PHASE0.md)・[Phase 1 完了報告](docs/PHASE1.md)・[Phase 2 完了報告](docs/PHASE2.md)
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
| `config/machine.json` | ISA、演算遅延、資源制約の共有定義案（形式: [docs/MACHINE.md](docs/MACHINE.md)） |
| `build.sbt`, `project/` | sbtマルチプロジェクト定義、バージョン固定 |
| `isa/` | machine description、命令表現、エンコーディング（全プロジェクトの共通基盤） |
| `hw/` | Chisel生成器、ChiselSimによるRTL検証（Phase 1）。`sbt "hw/run"` で `generated/` にSystemVerilogを出力 |
| `compiler/` | 独立verifier、カーネルDSL、DAG、静的スケジューラ、register allocation、コード生成、ループ IR、Modulo Scheduling（[docs/COMPILER.md](docs/COMPILER.md)） |
| `sim/` | ISA参照モデル、サイクル精度モデル |
| `tests/` | 実装をまたいで共有するテストデータ（[テストケース](tests/cases/README.md)、golden、縮小machine定義） |
| `crosscheck/` | verifier・参照モデル・RTL・コンパイラの間の差分テストと、ベンチマーク記録の生成（テストコードのみ） |
| `benchmarks/` | 整数SAXPY、FIR、固定サイズ行列積の記録（[RESULTS.md](benchmarks/RESULTS.md)） |
| `docs/` | 方針、仕様、判断理由、開発順序 |
| `scripts/check.sh` | 手元とCIで共通のチェック入口 |
| `scripts/mutation-check.sh` | mutation チェック（[tests/cases/README.md](tests/cases/README.md#mutation-チェック)） |
| `scripts/repeat-tests.sh` | テストの繰り返し実行（間欠的な失敗の頻度を測る。#74） |
| `scripts/verilator-exit-race.py` | Verilator の終了時の abort の頻度を、jobs の設定ごとに測る（[ADR-0013](docs/DECISIONS.md#adr-0013-chiselsim-の-verilator-は1スレッドで-verilate-し乱数の-seed-を明示する)） |
| `.github/workflows/ci.yml` | GitHub Actions CI |

## 開発

必要なもの: JDK 21、sbt、jq、shellcheck、Python 3（`scripts/check.sh static` と mutation チェック）、Verilator（5.020。`hw` の ChiselSim テストに使う）。バージョンと構成の根拠は[ADR-0004](docs/DECISIONS.md#adr-0004-ツールチェーンとバージョン固定)にあります。

```sh
scripts/check.sh          # 全チェック（CIと同じ内容）
scripts/check.sh static   # JSON構文・shellcheck・Python の構文・空白エラー・VliwChiselSim の使用・mutation の判定の検査
scripts/check.sh scala    # scalafmt・コンパイル（警告はエラー）・テスト
scripts/check.sh sbt "hw/testOnly vliw.hw.CaseSpec"  # 任意の sbt コマンドを scala と同じログ付きで実行
sbt test                  # テストだけを実行

sbt "isa/run machine"                                   # machine description の検証とhash
sbt "isa/run asm prog.asm prog.hex"                     # アセンブル（.hex / .bin）
sbt "isa/run disasm prog.hex"                           # 逆アセンブル
sbt "compiler/run verify tests/cases/isa_example.json"  # 合法性の静的検査
sbt "compiler/run compile saxpy8 fir4x8 matmul3"       # DSL のカーネルを命令列にする（generated/kernels/）
sbt "sim/run tests/cases/isa_example.json --trace out.jsonl --metrics out.json"  # 参照モデルで実行
```

`scripts/check.sh` の `scala` と `sbt` は、sbt の出力（`sbt.log`）、環境（JDK・sbt・Verilator の版、CPU・メモリ、同時に動いていたプロセス）、5秒ごとのプロセスとメモリの記録、テストの並行性を決める sbt の実効設定（`show` の出力）を `build/check-logs/<UTC時刻>/`（`CHECK_LOG_DIR` で変更可）に残します。失敗したときは、その時点のプロセス・メモリ・カーネルログと、その実行で書かれた ChiselSim のログ・JUnit レポートを加えます。`CHECK_TIMEOUT`（秒）を与えると、その時間で sbt を止め、失敗として同じ記録を残します。CI の `scala` ジョブは `CHECK_TIMEOUT` を付けて実行し、失敗したときにこれらを artifact として残します（#74）。ChiselSim の乱数の seed と Verilator の設定は [hw/README.md](hw/README.md) を参照してください。

同じチェックアウトで sbt を2つ同時に動かさないでください（`scripts/check.sh` や `scripts/repeat-tests.sh` から起動するものも含みます）。2つめの sbt は、先の sbt のサーバーの起動を検出して `ServerAlreadyBootingException` で終了するか、起動しても同じ `build/chiselsim/<Suite>/<test>/` を使うので、Verilator のビルドが `make: *** [Makefile:12: simulation] Error 2` で失敗します（#74 の `scripts/repeat-tests.sh` の `hw-concurrent` で10回中10回）。並行に動かすときは、`git worktree add` などで別のチェックアウトを使ってください。

`compiler` と `sim` は `isa` にのみ依存し、互いに依存しません。両者の突き合わせは `crosscheck` で行います（[ADR-0002](docs/DECISIONS.md#adr-0002-相互検査は言語ではなく状態表現と判定方法の違いで設計する)）。

## MVP

32bit整数、16レジスタ、演算＋メモリの2スロット、2バンクScratchpad、固定遅延ADD/SUB/MUL/LD/ST、HALT。
Phase 0ではループを展開し、開始前に入力を配置します。実行中の外部メモリアクセスと内部バックプレッシャーは対象外です。

`config/machine.json` は mvp-0 の**実装契約**です（revision 2、frozen）。RTL・命令列・verifier・参照モデルは、すべて `vliw.isa.MachineDescription` を通して同じ定義を参照します。
