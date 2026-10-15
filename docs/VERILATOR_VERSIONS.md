# Verilator の版ごとの挙動（カタログ）

#85 で、Verilator の各版をこのプロジェクトの ChiselSim のテストで調べた結果をまとめる。目的は、ADR-0013 の対処（verilation を1スレッドにする）を外せる版と、Verilator を上げるときの影響を、版ごとに比べられるようにすることである。版を上げるかどうかは、このカタログを根拠に別に決める（ADR-0004 と CI は 5.020 のまま）。

- 調べた経過と各版の詳しい結果: `docs/verilator-versions/<版>.md`、Issue #86〜#100
- 測り方: `.github/workflows/verilator-catalog.yml` と `scripts/verilator-catalog.sh`（下の「方法」）

## まとめ

（すべての版を測った後に書く。推奨する版、ADR-0013 の対処を外せる条件、CI での入手手段）

## カタログ

B・C の abort は「upstream issue 4931 の abort / 回数」で、p は 5.020 の 8/9663（#74）より低いことの片側 Fisher 検定である。

| 版 | Issue | A ビルド | B race（`-j 0`） | C race（`--verilate-jobs 1`） | D `sbt test` | E 対処なし | F seed | G crosscheck | H `%Warning` | I 性能 |
|---|---|---|---|---|---|---|---|---|---|---|
| 5.020（apt） | #86 | | | | | | | | | |
| 5.020 | #86 | | | | | | | | | |
| 5.024 | #87 | | | | | | | | | |
| 5.026 | #88 | | | | | | | | | |
| 5.030 | #89 | | | | | | | | | |
| 5.032 | #90 | | | | | | | | | |
| 5.034 | #91 | | | | | | | | | |
| 5.036 | #92 | | | | | | | | | |
| 5.038 | #93 | | | | | | | | | |
| 5.040 | #94 | | | | | | | | | |
| 5.042 | #95 | | | | | | | | | |
| 5.044 | #96 | | | | | | | | | |
| 5.046 | #97 | | | | | | | | | |
| 5.048 | #98 | | | | | | | | | |
| 5.050 | #99 | | | | | | | | | |
| 5.052 | #100 | | | | | | | | | |

## 方法

### 環境と実行の仕組み
- 測定は GitHub Actions の `ubuntu-24.04` の runner（4 vCPU、16 GB）で行う。ワークフロー `.github/workflows/verilator-catalog.yml` は、ブランチ `claude/verilator-catalog-<版>` への push で動き、ブランチ名の版を評価する。1つの版の中でも、次のジョブを別々の runner で並行に実行する
  - `build`: `scripts/verilator-build.sh` で、GitHub のタグからソースでビルドする（tcmalloc・jemalloc なし。Debian の 5.020 と同じ）。ビルドは cache に置き、ほかのジョブが使う
  - `measure`（版ごとに7つ）: `sbt`（`sbt test` と seed）、`nofix`（対処なしの `sbt test`）、`race-1`〜`race-5`（終了時の race を5つに分けたもの）
  - `collect`: 結果を `scripts/verilator-catalog-collect.sh` でまとめ、race の5つを合算し、同じブランチの `docs/verilator-versions/<版>/` にコミットする。このジョブだけ `contents: write` を持つ。ログは artifact に 90 日残す
- 5.020 のブランチは、apt の 5.020（Debian 5.020-1）も `5.020-apt` として測る。runner で abort が再現することを、ほかの版で abort が「0回」だったときの根拠にする（陽性対照）
- `docs/` と `README.md` だけを変える push では動かない（各版のブランチに書く報告で、測定をやり直さない）
- 手元（cloud のコンテナ、4 CPU、15 GB）で同じスクリプトを使って測った結果は、参考として別に記録する

### チェック項目
| # | 項目 | 方法（`scripts/verilator-catalog.sh` の step） |
|---|---|---|
| A | ソースからのビルド | `scripts/verilator-build.sh`: 成否、所要時間、コンパイラの警告の数 |
| B | 終了時の race（`-j 0`） | `race`: `scripts/verilator-exit-race.py` で、同じ設計（`RandomKernelSpec` の最初のテスト。その版でビルドしたもの）の verilation を繰り返す。upstream issue 4931 の abort、それ以外の abort、それ以外のエラーを数える |
| C | 終了時の race（`--build-jobs 0 --verilate-jobs 1`） | `race`: B と交互に同じ回数 |
| D | svsim との互換性 | `sbt`: `scripts/repeat-tests.sh full 1`（`sbt test`、`VliwChiselSimSpec` を含む） |
| E | ADR-0013 の対処を外した状態 | `nofix`: HEAD の worktree で `VliwChiselSim` の jobs の上書きを外し（svsim 既定の `-j 0`）、`VliwChiselSimSpec` を除いて `sbt test` を2回 |
| F | 乱数の seed | `seed`: `$urandom` を4回表示する設計を `--binary` でビルドし、seed なし（3回）、`+verilator+seed+1`（2回）、`+verilator+seed+2`、`+verilator+seed+0` で実行する |
| G | 結果の同一性 | `sbt`: `crosscheck` の差分テスト（RTL と参照モデルの結果・cycles の一致）が通るか |
| H | 診断 | `sbt`: `compilation-log.txt` の `%Warning-<CODE>` の数 |
| I | 性能 | `race` の verilation 1回の時間、`sbt` の `sbt test` 全体の時間、ビルドの時間 |
| J | 入手手段 | 調べるだけ（ubuntu-24.04 の CI で入れる方法） |

### 回数
- B・C は、全版で各 10000 回（2000 回 × 5 つの runner）。0 回なら、5.020 の 8/9663（#74）との片側 Fisher 検定で p ≈ 0.0034、95% の上限は約 0.03% になる。当初は二段（全版 3000 回、候補だけ 10000 回）としたが、runner で並行に測れるので、全版を 10000 回にした
- D は1回、E は2回
