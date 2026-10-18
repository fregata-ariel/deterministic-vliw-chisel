# ツールチェーンの版

Verilator と JDK の版は、[project/toolchain.properties](../../project/toolchain.properties) の1か所で決める（[ADR-0020](../DECISIONS.md#adr-0020-verilator-と-jdk-の版を1か所で決め版を変える前に候補を対照と同じ-run-で試す)、[#121](https://github.com/fregata-ariel/deterministic-vliw-chisel/issues/121)）。Chisel・firtool・Scala・sbt の版は、[ADR-0004](../DECISIONS.md#adr-0004-ツールチェーンとバージョン固定) のとおり `build.sbt` と `project/` で決める。

## properties の書式

| キー | 値 |
|---|---|
| `verilator.version` | Verilator の版。数字、点、3桁の数字（`verilator --version` の2語目） |
| `verilator.apt` | apt のパッケージの版（`<verilator.version>-<revision>`）。apt から入れるときに書く |
| `verilator.commit` | ソースの commit（40 桁の小文字の16進）。ソースから入れるときに書く。`verilator.apt` と、ちょうど一方を書く |
| `jdk.major` | JDK の major 版（数字） |

行は、空行、`#` で始まるコメント、`key=value`（`^[a-z][a-z0-9.]*=[A-Za-z0-9.+-]*$`）のどれかである。キーは上の4つで、どれも1回だけ書く。重複、CR、タブ、行末の空白、NUL は誤りである。コメントは行頭にだけ書ける。

読み手は2つある。テストの `vliw.hw.Toolchain` と `scripts/toolchain.sh` である。`ToolchainSpec` が、両方が同じファイルを受け付け、同じ値を読むことを確かめる。

## 確かめる

```sh
scripts/toolchain.sh get verilator.version   # 固定の版
scripts/toolchain.sh check                   # 固定の版、期待する版、PATH の verilator の場所と版、VERILATOR_ROOT、verilator_bin の場所と sha256
```

`check` は、PATH の Verilator が期待する版でなければ、直し方を出して失敗する。`scripts/check.sh` の `scala` と `sbt`、`scripts/mutation-check.sh`、`scripts/repeat-tests.sh` は、sbt の前に `check` を呼ぶ。`check` の出力は `env.txt` に残る。

シミュレーションをするテストの JVM は、sbt のログに次の行を1回出す。CI の `scala` ジョブは、すべての行が `(pinned)` であることを確かめる。`BenchmarkSpec` は、PATH の版が期待する版であることと、JDK が `jdk.major` であることを確かめる。

```
[toolchain] verilator=<PATH の版> expected=<期待する版> (pinned|env)
```

seed の記録（`seed.txt` と `[sim-seed]` の行）にも、PATH の Verilator の版が付く（ADR-0020 の R3）。例: `VLIW_SIM_SEED=1 (default) verilator=5.020`。同じ seed でも、版が違えば乱数の値が違うことがある。版ごとの乱数と seed の挙動は、カタログ（`claude/verilator-catalog` @ 025f545）の [docs/verilator-versions/](https://github.com/fregata-ariel/deterministic-vliw-chisel/tree/025f5458306187a061b645d3822c940e75e2a00c/docs/verilator-versions) の `<版>/seed.tsv` にある。

## 手元で入れる

今の固定は apt のパッケージである（`verilator.apt`）。Ubuntu 24.04 では、次のように入れる。

```sh
sudo apt-get update
sudo apt-get install -y "verilator=$(scripts/toolchain.sh get verilator.apt)"
scripts/toolchain.sh check
```

apt にその版がない環境では、同じ版をソースからビルドし、その `bin` を PATH の先頭に置く。ソースからビルドするスクリプト（`scripts/verilator-build.sh`）と手順は、#121 の PR 2 で足す。

JDK は `jdk.major` の版を入れる。CI は Temurin を使う。

## 別の版を手元で試す

その版の `verilator` を PATH の先頭に置き、`VLIW_VERILATOR_EXPECTED` にその版を書く。

```sh
VLIW_VERILATOR_EXPECTED=5.052 scripts/check.sh scala
```

`VLIW_VERILATOR_EXPECTED` を受け付けるのは、手元（`GITHUB_ACTIONS` が空）と、候補を試す workflow（`.github/workflows/toolchain-candidates.yml`。#121 の PR 3 で足す）だけである。それ以外の CI で設定すると、`scripts/toolchain.sh check` とテストが失敗する。空文字列も誤りである。

上書きしても、`benchmarks/RESULTS.md` のツールの行は固定の版を書く。`benchmarks/` に差分が出てはならない（R2）。

## 版を変える手順

ADR-0020 の決まりを、次の順で行う。

1. 候補の版を、固定の版を対照にして、同じ run で PR から試す（候補を試す workflow。#121 の PR 3）。結果を `docs/toolchain/<版>/` に残す。
2. `benchmarks/`・`tests/cases`・`tests/golden` に、ツールの行と `metrics` の `generator` 欄のほかの差分が出た版は採らない。候補を緑にするために記録を作り直さない（R2）。
3. 版を変える PR は、`project/toolchain.properties`、生成し直した `benchmarks/RESULTS.md` のツールの行（`sbt "crosscheck/Test/runMain vliw.crosscheck.BenchmarkRecords"`）、下の履歴の1行だけを変える。回避策（ADR-0013 の `--verilate-jobs 1`）には触れない。
4. その PR の CI と mutation が緑で、`[toolchain]` の行がすべて `(pinned)` であることを確かめてから、マージする。
5. 回避策を外すのは、版が main で安定した後の、別の PR にする。
6. 戻すのは、版を変えた PR の revert 1つである。

## 履歴

| 版 | 入れ方 | PR | 日付 | run |
|---|---|---|---|---|
| 5.020 | apt `5.020-1`（ubuntu-24.04） | #39（#23。CI に apt で入れた） | 2026-10-14 | — |

5.020 は、ubuntu-24.04 の apt にある唯一の版として入った。候補を試す run はない。固定を properties に移したのは #121 の PR 1 で、版は変えていない。
