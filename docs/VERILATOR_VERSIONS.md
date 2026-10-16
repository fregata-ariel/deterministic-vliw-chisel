# Verilator の版ごとの挙動（カタログ）

#85 で、Verilator の各版をこのプロジェクトの ChiselSim のテストで調べた結果をまとめる。目的は、ADR-0013 の対処（verilation を1スレッドにする）を外せる版と、Verilator を上げるときの影響を、版ごとに比べられるようにすることである。版を上げるかどうかは、このカタログを根拠に別に決める（ADR-0004 と CI は 5.020 のまま）。

- 版ごとの詳しい結果: [`verilator-versions/<版>.md`](verilator-versions/)、Issue #86〜#100。元のデータ: `verilator-versions/<版>/`
- 測り方: `.github/workflows/verilator-catalog.yml` と `scripts/verilator-catalog.sh`（下の「方法」）
- 調べた版: 5.020（ubuntu-24.04 の apt と、ソースからのビルド）、5.024、5.026、5.030〜5.052 のすべてのタグ。5.022・5.028 は調べていない（5.022 の終了処理は 5.020 と同じ）。2026-10-15 時点の最新のタグは 5.052

## まとめ

### 起きていたこと（#74）
- ADR-0013 の原因（Verilator 5.020 の終了時の abort、upstream issue 4931）は、runner でも手元でも再現した。`-j 0`（svsim の既定）で verilation を繰り返すと、`attempted to destroy locked Thread Pool`、`exit 255` になる。
- 頻度は機械による差が大きい。ソースからビルドした 5.020 は 70000 回に 29 回（0.041%）で、その 29 回のうち 20 回は、Intel Xeon 6973P-C の1台の runner（3000 回に 20 回、0.67%）で起きた。AMD EPYC 7763 でも 35000 回に 2 回起きた。手元の Intel Xeon のコンテナでは、10000 回に 15 回（0.15%）だった。
- 53 回の ChiselSim のビルドを含む1回の `sbt test` が失敗する確率は、0.041% なら約 2.2%、0.15% なら約 7.7% になる。
- CI が使う Debian の apt の 5.020 は、runner では 70000 回に 2 回（0.0029%）で、ソースビルドの 29 回より少なかった。ただし、(1) ソース版の 29 回のうち 20 回は1台の runner に集中していて、それを除くと 67000 回に 9 回で、差は小さくなる（片側 Fisher で p = 0.028。事後的な比較なので弱い）、(2) 手元のコンテナでは、apt 版は 3000 回に 6 回（0.20%）で、ソース版（10000 回に 15 回、0.15%）と同程度だった。apt とソースの違いより、**機械の違い**のほうが大きいと見られる。runner での頻度が低いこと（apt 版で1回の `sbt test` あたり約 0.15%）は、CI でまだ起きていないことと矛盾しない。ビルドの違い（最適化のフラグなど）の影響は、調べていない。
- `--build-jobs 0 --verilate-jobs 1`（ADR-0013 の対処）では、5.020 でも、ソース版・apt 版とも 10000 回に 0 回だった。ソースを読んでも、worker のスレッドがなく、ロックも無効になるので、起きない。
- 対処を外した `sbt test`（`-j 0`）は、手元のコンテナでソース版の 5.020 が2回のうち1回失敗した（`WriteReservationsSpec`、`make ... Error 255`）。#43 の作業中に見た失敗と同じテストである。

### 新しい版では
- **5.024〜5.052 の14版は、`-j 0` で各 70000 回（合計 980000 回、6種類の CPU）に abort が 0 回**だった。abort の出た shard の数でも、5.020 は 25 のうち 6、各版は 25 のうち 0 で、片側 Fisher 検定は p = 0.011 である（回数の合算では p ≈ 2×10⁻⁹ だが、shard ごとに頻度が違うので、shard の数での検定の方が頑健である）。手元の Intel のコンテナでも、5.024、5.026、5.030、5.052 は各 10000 回で 0 回（5.020 は同じ機械で 15 回）。
- ソースでは、5.024 は `try_lock` を最大 50000 回繰り返すだけ、5.026 は最大約1秒（1 ms の sleep つき）繰り返すだけで、**abort の経路は残っている**。観測されなかったのは、待つ時間が長くなったためと考えられる。**5.030 でスレッドプールが書き直され、`try_lock` も abort もなくなった**。5.030〜5.052 は、スレッドプールの変更が実質ない。
- 対処を外した `sbt test`（`-j 0`、2回ずつ）は、5.024 以降の全版の runner で成功した。`sbt test` は全版で成功し、`%Warning` はどの版でも 0 件だった（ChiselSim のビルド 52〜53 回）。svsim（Chisel 7.15.0）との互換性の問題は見つからなかった。
- `--verilate-jobs 1` は、verilation 1回の時間をほとんど変えない（5.020: `-j 0` で 0.092 秒、`--verilate-jobs 1` で 0.091 秒）。外した後も、残して実害はない。

### ADR-0013 の対処を外せる条件
- **5.030 以降**に上げれば、外せる。理由は、(1) スレッドプールの破棄で abort する経路がソースにない、(2) 5.030〜5.052 の全版で `-j 0` が 70000 回に 0 回、対処なしの `sbt test` も成功した、の2つである。
- 5.024・5.026 は、観測の上では問題がなかったが、abort の経路が残っているので、根拠にはしない。
- 版を上げる前に外すと、ソースビルドの 5.020 では1回の `sbt test` が約 2〜8% の確率で失敗する（CI が使う apt 版では約 0.15%）。変更は一度に1つずつ（まず版を上げ、CI が安定してから外す）のが安全だろう。

### 上げるときの注意（版ごとの変更の一部。詳しくは各版の報告）
| 版 | 注意 |
|---|---|
| 5.030 | スレッドプールの書き直し（abort の経路の消滅）。`E_LIFETIME` などのエラー系のコードが `lint_off` で抑止できなくなる。 |
| 5.040 | 失敗した assertion などが `abort()` でなく `exit(1)` で終わる（debug が0のとき）。DFG の大改修で、生成された C++ が同じ入力でも実行ごとに変わりうる（5.042 で直った）。新しく既定オンの警告（`ALWNEVER`、`NOEFFECT`、`SPECIFYIGN`）。 |
| 5.042 | シグナルで死んだ `verilator_bin` の終了コードが 255 から 128＋シグナル番号（SIGABRT で 134）に変わる。#74 の症状の見え方が `Error 255` から `Error 134` に変わる。`-j 0` が CPU affinity の数になる。 |
| 5.046 | **乱数の実装が替わる**。`+verilator+seed+1` でも、reset のないレジスタとメモリの初期値が、5.044 までと変わる（5.020〜5.044 は同じ値）。テストは通った。V3Life が DPI export を考慮するようになる（svsim の DPI に関わる正しさの修正）。 |
| 5.050 | 既定の seed が 0 から 1 になる。`+verilator+seed+0` は毎回ランダムになる（このプロジェクトの `SimSeed` は 0 を受け付けないので、影響しない）。`V3Life` が DPI などの呼び出しをまたいで変数への書き込みを落としうる不具合があり、5.052 で直った（このプロジェクトのテストは通った）。5.050 に上げるなら 5.052 のほうがよい。 |
| 5.052 | 生成モデルの eval ループが実行時のクラスに移る。小さなモデルでは遅くなる、と upstream は言う。 |

- **seed の値を変えたくないなら、5.044 以下**に上げる（5.020〜5.044 は、seed あり・なしとも同じ値）。5.046 以降では、同じ seed でも乱数の値が変わる。
- どの版でも、このプロジェクトの `sbt test`（`hw` と `crosscheck`、RTL と参照モデルのサイクル単位の一致を含む）は通った。

### CI での入手手段（J）
- **ubuntu-24.04 の apt は 5.020 だけ**（noble-updates・backports にもない）。
- **ubuntu-26.04（resolute）の apt は 5.032-1**（[Launchpad](https://launchpad.net/ubuntu/+source/verilator)）。GitHub の `ubuntu-26.04` の runner は、2026-06 に public preview になった（[changelog](https://github.blog/changelog/2026-06-11-new-runner-images-in-public-preview/)）。preview の間は、runner の入れ替えが前提になる。
- **タグからのソースビルド**: `scripts/verilator-build.sh`。4 vCPU の runner で 200〜370 秒（tcmalloc・jemalloc なし）。`actions/cache` に置けば、2回目以降は不要になる。ubuntu-24.04 のまま使える。必要なものは `autoconf flex bison libfl-dev help2man`（と git、g++、make、python3）。
- **conda-forge** には 5.030、5.032、5.034、5.042〜5.052 がある（5.024、5.036〜5.040 はない。[anaconda.org](https://anaconda.org/conda-forge/verilator)）。プラットフォームごとの有無は確かめていない。
- 候補としては、**5.032**（Ubuntu 26.04 の apt の版。abort の経路がなく、seed が 5.020 と同じ）か、最新の 5.052 がある。Ubuntu の次の開発版（Stonking Stingray）の apt は 5.048-1（freeze 中）。どれにするかは別の判断である。

### この結果の限界
- race は、同じ1つの設計（`RandomKernelSpec` の最初のテスト）の verilation だけを、1回ずつ順に繰り返した。設計の大きさや、Verilator の終了時の状態によって頻度は変わりうる。
- 測った環境は、4 vCPU の ubuntu-24.04（g++ 13）と、手元の Intel Xeon の 4 CPU である。`-j 0` は CPU の数に応じて worker を作るので、コア数が多い機械では頻度が違うかもしれない。
- runner の CPU も機械も選べない。頻度は機械ごとに大きく違った（5.020: 0〜0.67%）ので、「0 回」は「その機械の分布の中で観測されなかった」という意味にとどまる。
- ソースの変更点の調査は、エージェントが Changes とソースを読み、別のエージェントが反証を試みたものである。人が全項目を確かめたわけではない。5.030 のスレッドプール、5.042 の終了コード、5.040 の `std::exit(1)` はソースで、seed の挙動は実測で確かめた。

## カタログ

B・C は「upstream issue 4931 の abort 数/回数」。B のカッコは abort の出た shard の数/shard の数（1 shard は1つの runner）。D・G は `sbt test`（RTL と参照モデルの差分テストを含む）、E は ADR-0013 の対処を外した `sbt test`（2回）。カッコの秒数は1回ごとの所要時間。

| 版 | Issue | A ビルド | B race `-j 0`（abort/回数、abort の出た shard） | C race `--verilate-jobs 1` | D・G `sbt test` | E 対処なし `sbt test` | H `%Warning` | F seed |
|---|---|---|---|---|---|---|---|---|
| 5.020-apt | #86 | apt | 2/70000（2/25 shard） | 0/10000 | 成功（247 秒） | 2/2 成功（157, 140 秒） | なし | seed なし≠seed 1、seed 1 の値は 1 のビットが多い、seed 0 はエラー |
| 5.020 | #86 | 成功 253 秒、警告 3 | 29/70000（6/25 shard） | 0/10000 | 成功（178 秒） | 2/2 成功（265, 231 秒） | なし | seed なし≠seed 1、seed 1 の値は 1 のビットが多い、seed 0 はエラー |
| 5.024 | #87 | 成功 270 秒、警告 3 | 0/70000（0/25 shard） | 0/10000 | 成功（288 秒） | 2/2 成功（278, 244 秒） | なし | seed なし≠seed 1、seed 1 の値は 1 のビットが多い、seed 0 はエラー |
| 5.026 | #88 | 成功 274 秒、警告 3 | 0/70000（0/25 shard） | 0/10000 | 成功（266 秒） | 2/2 成功（191, 175 秒） | なし | seed なし≠seed 1、seed 1 の値は 1 のビットが多い、seed 0 はエラー |
| 5.030 | #89 | 成功 216 秒、警告 3 | 0/70000（0/25 shard） | 0/10000 | 成功（262 秒） | 2/2 成功（272, 246 秒） | なし | seed なし≠seed 1、seed 1 の値は 1 のビットが多い、seed 0 はエラー |
| 5.032 | #90 | 成功 275 秒、警告 3 | 0/70000（0/25 shard） | 0/10000 | 成功（267 秒） | 2/2 成功（275, 248 秒） | なし | seed なし≠seed 1、seed 1 の値は 1 のビットが多い、seed 0 はエラー |
| 5.034 | #91 | 成功 278 秒、警告 3 | 0/70000（0/25 shard） | 0/10000 | 成功（288 秒） | 2/2 成功（268, 246 秒） | なし | seed なし≠seed 1、seed 1 の値は 1 のビットが多い、seed 0 はエラー |
| 5.036 | #92 | 成功 200 秒、警告 3 | 0/70000（0/25 shard） | 0/10000 | 成功（287 秒） | 2/2 成功（295, 259 秒） | なし | seed なし≠seed 1、seed 1 の値は 1 のビットが多い、seed 0 はエラー |
| 5.038 | #93 | 成功 296 秒、警告 3 | 0/70000（0/25 shard） | 0/10000 | 成功（291 秒） | 2/2 成功（260, 236 秒） | なし | seed なし≠seed 1、seed 1 の値は 1 のビットが多い、seed 0 はエラー |
| 5.040 | #94 | 成功 316 秒、警告 3 | 0/70000（0/25 shard） | 0/10000 | 成功（282 秒） | 2/2 成功（192, 176 秒） | なし | seed なし≠seed 1、seed 1 の値は 1 のビットが多い、seed 0 はエラー |
| 5.042 | #95 | 成功 348 秒、警告 3 | 0/70000（0/25 shard） | 0/10000 | 成功（302 秒） | 2/2 成功（226, 205 秒） | なし | seed なし≠seed 1、seed 1 の値は 1 のビットが多い、seed 0 はエラー |
| 5.044 | #96 | 成功 228 秒、警告 3 | 0/70000（0/25 shard） | 0/10000 | 成功（232 秒） | 2/2 成功（307, 266 秒） | なし | seed なし≠seed 1、seed 1 の値は 1 のビットが多い、seed 0 はエラー |
| 5.046 | #97 | 成功 317 秒、警告 3 | 0/70000（0/25 shard） | 0/10000 | 成功（294 秒） | 2/2 成功（287, 271 秒） | なし | seed なし≠seed 1、seed 1 の値は偏りなし、seed 0 はエラー |
| 5.048 | #98 | 成功 350 秒、警告 3 | 0/70000（0/25 shard） | 0/10000 | 成功（323 秒） | 2/2 成功（288, 264 秒） | なし | seed なし≠seed 1、seed 1 の値は偏りなし、seed 0 はエラー |
| 5.050 | #99 | 成功 369 秒、警告 3 | 0/70000（0/25 shard） | 0/10000 | 成功（337 秒） | 2/2 成功（314, 286 秒） | なし | seed なし＝seed 1、seed 1 の値は偏りなし、seed 0 は受理 |
| 5.052 | #100 | 成功 345 秒、警告 3 | 0/70000（0/25 shard） | 0/10000 | 成功（334 秒） | 2/2 成功（202, 193 秒） | なし | seed なし＝seed 1、seed 1 の値は偏りなし、seed 0 は受理 |

## 方法

### 環境と実行の仕組み
- 測定は GitHub Actions の `ubuntu-24.04` の runner（4 vCPU、16 GB）で行う。ワークフロー `.github/workflows/verilator-catalog.yml` は、ブランチ `claude/verilator-catalog-<版>` への push で動き、ブランチ名の版を評価する。1つの版の中でも、次のジョブを別々の runner で並行に実行する
  - `build`: `scripts/verilator-build.sh` で、GitHub のタグからソースでビルドする（tcmalloc・jemalloc なし。Debian の 5.020 と同じ）。ビルドは cache に置き、ほかのジョブが使う
  - `measure`（版ごとに7つ）: `sbt`（`sbt test` と seed）、`nofix`（対処なしの `sbt test`）、`race-1`〜`race-5`（終了時の race を5つに分けたもの）
  - `collect`: 結果を `scripts/verilator-catalog-collect.sh` でまとめ、race の5つを合算し、同じブランチの `docs/verilator-versions/<版>/` にコミットする。このジョブだけ `contents: write` を持つ。ログは artifact に 90 日残す
- 5.020 のブランチは、apt の 5.020（Debian 5.020-1）も `5.020-apt` として測る。runner で abort が再現することを、ほかの版で abort が「0回」だったときの根拠にする（陽性対照）
- `docs/` と `README.md` だけを変える push では測らない（各版のブランチに書く報告で、測定をやり直さない）
- 手元（cloud のコンテナ、Intel Xeon @ 2.80GHz、4 CPU、15 GB）でも、同じスクリプトで、同じ機械の対照を測った（5.020、5.024、5.026、5.030、5.052）。結果は `docs/verilator-versions/local-container/` にある

### チェック項目
| # | 項目 | 方法（`scripts/verilator-catalog.sh` の step） |
|---|---|---|
| A | ソースからのビルド | `scripts/verilator-build.sh`: 成否、所要時間、コンパイラの警告の数 |
| B | 終了時の race（`-j 0`） | `race`: `scripts/verilator-exit-race.py` で、同じ設計（`RandomKernelSpec` の最初のテスト。その版でビルドしたもの）の verilation を繰り返す。upstream issue 4931 の abort、それ以外の abort、それ以外のエラーを数える |
| C | 終了時の race（`--build-jobs 0 --verilate-jobs 1`） | `race`: B と交互に同じ回数 |
| D | svsim との互換性 | `sbt`: `scripts/repeat-tests.sh full 1`（`sbt test`、`VliwChiselSimSpec` を含む） |
| E | ADR-0013 の対処を外した状態 | `nofix`: HEAD の worktree で `VliwChiselSim` の jobs の上書きを外し（svsim 既定の `-j 0`）、`VliwChiselSimSpec` を除いて `sbt test` を2回 |
| F | 乱数の seed | `seed`: `$urandom` を4回表示する設計を `--binary` でビルドし、seed なし（3回）、`+verilator+seed+1`（2回）、`+verilator+seed+2`、`+verilator+seed+0`（2回）で実行する |
| G | 結果の同一性 | `sbt`: `crosscheck` の差分テスト（RTL と参照モデルの結果・cycles の一致）が通るか |
| H | 診断 | `sbt`: `compilation-log.txt` の `%Warning-<CODE>` の数 |
| I | 性能 | `race` の verilation 1回の時間、`sbt` の `sbt test` 全体の時間、ビルドの時間 |
| J | 入手手段 | 調べるだけ（ubuntu-24.04 の CI で入れる方法） |

### 回数
- B・C は、全版で各 10000 回（2000 回 × 5 つの runner）。0 回なら、5.020 の 8/9663（#74）との片側 Fisher 検定で p ≈ 0.0034、95% の上限は約 0.03% になる。当初は二段（全版 3000 回、候補だけ 10000 回）としたが、runner で並行に測れるので、全版を 10000 回にした
- D は1回、E は2回
- 第2段（`race-cpu`）: B だけを、全版で 60000 回（3000 回 × 20 の runner）。5.020 の abort の頻度は runner（機械）で大きく違った。runner の CPU も機械も選べないので、shard を増やし、CPU ごと・shard ごとに数える（`race-by-cpu.tsv`）。結果は `docs/verilator-versions/<版>/race-cpu/` に置く。ワークフローの `STAGE` で段を選ぶ。第1段の6版では「abort は Intel の CPU でだけ起きる」と見えたが、第2段で AMD EPYC 7763 でも起きたので、その見方は誤りだった
