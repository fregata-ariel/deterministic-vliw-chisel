# deterministic-vliw-chisel

[![CI](https://github.com/fregata-ariel/deterministic-vliw-chisel/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/fregata-ariel/deterministic-vliw-chisel/actions/workflows/ci.yml)

Chiselで構築する、コンパイラ主導の静的スケジューリング・固定サイクル実行の研究用マイクロエンジン。

設計原則：**コンパイラが時間配置を決め、ハードウェアがそのスケジュールを実行する。**

Phase 0（ISAと実行意味論の確定、machine description、アセンブラ、独立verifier、サイクル精度の参照モデル、テストスイート）と、Phase 1（Chisel RTL。参照モデルとサイクル単位で一致）が完了しています（[Phase 0](docs/PHASE0.md)・[Phase 1](docs/PHASE1.md) の完了報告）。次は Phase 2 の自動命令生成です。性能値は未測定です。

## 最初に読む文書

- [設計方針・技術的系譜](docs/DESIGN.md)
- [ISAとサイクル契約](docs/ISA.md)
- [開発順序・完了条件](docs/ROADMAP.md)
- [設計判断記録（ADR）](docs/DECISIONS.md)
- [Phase 0 完了報告](docs/PHASE0.md)・[Phase 1 完了報告](docs/PHASE1.md)
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
| `compiler/` | アセンブラ、DAG、静的スケジューラ、独立検証器 |
| `sim/` | ISA参照モデル、サイクル精度モデル |
| `tests/` | 実装をまたいで共有するテストデータ（[テストケース](tests/cases/README.md)、golden、縮小machine定義） |
| `crosscheck/` | verifierと参照モデルの差分テスト（テストコードのみ） |
| `benchmarks/` | 整数SAXPY、FIR、固定サイズ行列積 |
| `docs/` | 方針、仕様、判断理由、開発順序 |
| `scripts/check.sh` | 手元とCIで共通のチェック入口 |
| `.github/workflows/ci.yml` | GitHub Actions CI |

## 開発

必要なもの: JDK 21、sbt、jq、shellcheck、Verilator（5.020。`hw` の ChiselSim テストに使う）。バージョンと構成の根拠は[ADR-0004](docs/DECISIONS.md#adr-0004-ツールチェーンとバージョン固定)にあります。

```sh
scripts/check.sh          # 全チェック（CIと同じ内容）
scripts/check.sh static   # JSON構文・shellcheck・空白エラー
scripts/check.sh scala    # scalafmt・コンパイル（警告はエラー）・テスト
sbt test                  # テストだけを実行

sbt "isa/run machine"                                   # machine description の検証とhash
sbt "isa/run asm prog.asm prog.hex"                     # アセンブル（.hex / .bin）
sbt "isa/run disasm prog.hex"                           # 逆アセンブル
sbt "compiler/run verify tests/cases/isa_example.json"  # 合法性の静的検査
sbt "sim/run tests/cases/isa_example.json --trace out.jsonl --metrics out.json"  # 参照モデルで実行
```

`compiler` と `sim` は `isa` にのみ依存し、互いに依存しません。両者の突き合わせは `crosscheck` で行います（[ADR-0002](docs/DECISIONS.md#adr-0002-相互検査は言語ではなく状態表現と判定方法の違いで設計する)）。

## MVP

32bit整数、16レジスタ、演算＋メモリの2スロット、2バンクScratchpad、固定遅延ADD/SUB/MUL/LD/ST、HALT。
Phase 0ではループを展開し、開始前に入力を配置します。実行中の外部メモリアクセスと内部バックプレッシャーは対象外です。

`config/machine.json` は mvp-0 の**実装契約**です（revision 2、frozen）。RTL・命令列・verifier・参照モデルは、すべて `vliw.isa.MachineDescription` を通して同じ定義を参照します。
