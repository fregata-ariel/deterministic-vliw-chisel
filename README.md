# deterministic-vliw-chisel

[![CI](https://github.com/fregata-ariel/deterministic-vliw-chisel/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/fregata-ariel/deterministic-vliw-chisel/actions/workflows/ci.yml)

Chiselで構築する、コンパイラ主導の静的スケジューリング・固定サイクル実行の研究用マイクロエンジン。

設計原則：**コンパイラが時間配置を決め、ハードウェアがそのスケジュールを実行する。**

現段階は設計仕様の初期案と、Scala/sbtのビルド・CI基盤です。RTL、アセンブラ、コンパイラ、シミュレータは今後実装します。性能値は未測定です。

## 最初に読む文書

- [設計方針・技術的系譜](docs/DESIGN.md)
- [ISAとサイクル契約](docs/ISA.md)
- [開発順序・完了条件](docs/ROADMAP.md)
- [設計判断記録（ADR）](docs/DECISIONS.md)
- [一次資料](docs/REFERENCES.md)

## リポジトリの定義

| 項目 | 初期設定 |
|---|---|
| 名前 | deterministic-vliw-chisel |
| 説明 | Compiler-scheduled, fixed-cycle VLIW microengine in Chisel |
| 公開範囲 | private |
| 所有者 | Macのghで選択中のgithub.comアカウント |
| 初期ブランチ | main |
| 初期コミット | docs: define deterministic VLIW architecture and development plan |
| ライセンス | 未選定。公開や外部コード導入時に決定 |

## 構成

| パス | 責務 |
|---|---|
| `config/machine.json` | ISA、演算遅延、資源制約の共有定義案（形式: [docs/MACHINE.md](docs/MACHINE.md)） |
| `build.sbt`, `project/` | sbtマルチプロジェクト定義、バージョン固定 |
| `isa/` | machine description、命令表現、エンコーディング（全プロジェクトの共通基盤） |
| `hw/` | Chisel生成器、ChiselSimによるRTL検証（Phase 1） |
| `compiler/` | アセンブラ、DAG、静的スケジューラ、独立検証器 |
| `sim/` | ISA参照モデル、サイクル精度モデル |
| `benchmarks/` | 整数SAXPY、FIR、固定サイズ行列積 |
| `docs/` | 方針、仕様、判断理由、開発順序 |
| `scripts/check.sh` | 手元とCIで共通のチェック入口 |
| `.github/workflows/ci.yml` | GitHub Actions CI |
| `scripts/create-github-repo.sh` | Git初期化・初回commit・ghによるprivate repo作成・push |

## 開発

必要なもの: JDK 21、sbt、jq、shellcheck。バージョンと構成の根拠は[ADR-0004](docs/DECISIONS.md#adr-0004-ツールチェーンとバージョン固定)にあります。

```sh
scripts/check.sh          # 全チェック（CIと同じ内容）
scripts/check.sh static   # JSON構文・shellcheck・空白エラー
scripts/check.sh scala    # scalafmt・コンパイル（警告はエラー）・テスト
sbt test                  # テストだけを実行
```

`compiler` と `sim` は `isa` にのみ依存し、互いに依存しません（[ADR-0002](docs/DECISIONS.md#adr-0002-相互検査は言語ではなく状態表現と判定方法の違いで設計する)）。

## MVP

32bit整数、16レジスタ、演算＋メモリの2スロット、2バンクScratchpad、固定遅延ADD/SUB/MUL/LD/ST、HALT。
Phase 0ではループを展開し、開始前に入力を配置します。実行中の外部メモリアクセスと内部バックプレッシャーは対象外です。

`config/machine.json`の内容は**実装契約案**です。Phase 0でスキーマと検証器を実装し、RTL・命令列・シミュレータが同じ定義を参照する構成へ進めます。
