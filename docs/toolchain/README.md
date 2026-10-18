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

`check` は、PATH の Verilator が期待する版でなければ、直し方を出して失敗する。`scripts/check.sh` の `scala` と `sbt`、`scripts/mutation-check.sh`、`scripts/repeat-tests.sh` は、sbt の前に `check` を呼ぶ。`check` の出力は `env.txt` に残る。CI の action（下の「CI で入れる」）も、入れた後に `check` を呼ぶ。

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

apt にその版がない環境（Ubuntu 24.04 以外や macOS）では、同じ版をソースからビルドし、その `bin` を PATH の先頭に置く。

```sh
version="$(scripts/toolchain.sh get verilator.version)"
commit="$(scripts/toolchain.sh get verilator.commit)"
# apt で固定した版には commit がないので、タグの commit を読み、下に書いた commit と比べる。
[ -n "$commit" ] || commit="$(git ls-remote https://github.com/verilator/verilator "refs/tags/v$version^{}" | cut -f1)"
scripts/verilator-build.sh "$version" "$commit" "$HOME/verilator/$version"
export PATH="$HOME/verilator/$version/bin:$PATH"
scripts/toolchain.sh check
```

`scripts/verilator-build.sh <版> <commit> <prefix>` は、`https://github.com/verilator/verilator` のタグ `v<版>` を clone し、その commit が `<commit>` と違えば、ビルドの前に失敗する。タグが動かされたときに気づくためである。そのため `<commit>` には、`verilator.commit` があればその値を使う。今のタグから読んだ commit と比べても、タグが動いたことには気づけない。`verilator.commit` がない版（apt で固定した版）では、`git ls-remote` で読んだ commit を、記録した commit と比べてから使う。v5.020 のタグの commit は `5c5314b39cd888f427807d626e1502cbf222c292` である。

- ビルドには git、autoconf、make、g++、flex、bison、help2man、perl、python3 が要る。Ubuntu では `sudo apt-get install -y git autoconf make g++ flex bison libfl-dev help2man perl python3`。
- tcmalloc と jemalloc は使わない（Debian のパッケージと同じ）。版の違いが、Verilator 自身のソースの違いだけになるようにするためである。
- `<prefix>` には、テストが使わない `verilator_bin_dbg` と `share/verilator/examples` を残さない（CI のキャッシュを小さくするため）。
- 結果（版、commit、タグの commit、秒数、コンパイラの警告の数、入った版）は `<prefix>.build-info.txt` に、ログは `<prefix>.build-info.log` に残る。4つ目の引数で、結果のファイルの場所を変えられる。
- 時間は、4コアの手元で約 270 秒（v5.020）、CI で 200〜370 秒である。

ソースからビルドした 5.020 と apt の 5.020-1 は、`verilator --version` の末尾（`rev v5.020` と `rev (Debian 5.020-1)`）と `verilator_bin` の sha256 が違う。版は同じなので、`scripts/toolchain.sh check` は通る。

JDK は `jdk.major` の版を入れる。CI は Temurin を使う。

## CI で入れる

CI は、JDK・sbt・Verilator を [.github/actions/setup-toolchain](../../.github/actions/setup-toolchain/action.yml) の1か所で入れる（#121 の PR 2）。`ci.yml` の `scala` ジョブ、`mutation.yml`、`repeat-tests.yml` は、checkout の次にこの action を呼ぶ。action の shell の部分は `setup-toolchain.sh`（同じディレクトリ）にある。

1. `project/toolchain.properties` を読む。入力を省くと、固定の版を入れる。
2. JDK（`actions/setup-java`、Temurin、`jdk.major`、sbt のキャッシュ付き）と sbt（`sbt/setup-sbt`）を入れる。
3. `verilator.apt` があれば、`sudo apt-get update` の後、`sudo apt-get install -y verilator=<verilator.apt>` で入れる。image の apt にその版がなければ、apt は exit 100 で止まる。
4. `verilator.commit` があれば、`actions/cache/restore` でビルドを復元する。キーは `verilator-<版>-<commit の先頭 12 桁>-<ImageOS>-<verilator-build.sh の sha256>` である。なければ、ビルドに要るパッケージを apt で入れ、`scripts/verilator-build.sh` でビルドし、すぐに `actions/cache/save` で保存する。`actions/cache` の post の step は job が成功したときしか保存しないので、テストが失敗すると次の run でまたビルドすることになるからである。
5. PATH には `<prefix>/bin` だけを足す。`/usr/bin` のようなディレクトリを先に置くと、setup-java の JDK も隠れる（カタログの 5.020-apt の job は、そのため image の JDK 17 で動いた）。
6. 最後の step で、`scripts/toolchain.sh check`（期待する版は、入れた版）、PATH の先頭の `verilator` が入れたものであること（apt なら `/usr/bin/verilator` とパッケージの版）、それが動かす `verilator_bin` がその隣のものであること（`VERILATOR_ROOT` があれば、その下のものが動くので失敗する）、`java -version` の major が `jdk.major` であることを確かめる。fingerprint（verilator の場所と版、`verilator_bin` の sha256、パッケージの版か commit とキャッシュ、java の場所と版、runner の image）を、job のログと step summary の両方に出す。

入力 `verilator`（`5.052` のような版か、`apt:5.020-1` のような apt のパッケージの版）と `commit`（ソースからビルドする候補の、タグの commit。40 桁の16進）を使えるのは、`github.workflow_ref` が `/.github/workflows/toolchain-candidates.yml@` を含むときだけである（`VLIW_VERILATOR_EXPECTED` と同じ条件）。それ以外の workflow で入力を書くと、action の最初の step が失敗する。固定と違う版を入れたときは、action が `VLIW_VERILATOR_EXPECTED` をその版にして、後の step に渡す。`scripts/check.sh static`（`scripts/check-workflows.sh`）は、workflow に apt で Verilator を入れる行、`actions/setup-java`、`java-version` がないことと、`ci.yml` の setup-toolchain の step に `with:` がないことを確かめる。また、`VLIW_VERILATOR_EXPECTED`・`VLIW_VERILATOR_JOBS` を書けるのが、この action、`toolchain-candidates.yml`、`mutation.yml`、読み手（`scripts/toolchain.sh` と `vliw.hw.Toolchain`）、自己テスト、文書（`docs/` と `*.md`）だけであることを、追跡しているファイルで確かめる（コメントの行は除く）。

`ci.yml` の `scala` ジョブの枠は 30 分である。ソースから入れる版でキャッシュがないときは、同じ job の中でビルドする。ビルドを別の job にして `needs` でつなぐと、その job が失敗したとき `scala` は skipped になり、GitHub は skipped を成功と扱うので、必須の check が通ってしまう。

`mutation.yml` は、ツールチェーンを変えた PR（properties、action、`scripts/toolchain.sh`、`scripts/verilator-build.sh`、`Toolchain.scala`、`VliwChiselSim.scala`）でも走る。キャッシュがないときは shard ごとにビルドする。artifact の名前には Verilator の版と入れ方が入る（`mutation-logs-verilator-5.020-apt-5.020-1-shard1-attempt1` など）。エージェントの session は artifact を落とせないので、shard ごとの `results.tsv` とベースライン（mutant なしの run とそのやり直し）の判定を、job のログの最後にも出す（それぞれ `===BEGIN results.tsv ...===`、`===BEGIN baseline ...===` から `===END===` まで）。候補を試す workflow（#121 の PR 3）は、`workflow_call` の入力 `verilator`・`commit` で、候補の版の mutation を呼ぶ。

## 別の版を手元で試す

その版の `verilator` を PATH の先頭に置き（ソースからなら、上の `scripts/verilator-build.sh` でビルドする）、`VLIW_VERILATOR_EXPECTED` にその版を書く。

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

5.020 は、ubuntu-24.04 の apt にある唯一の版として入った。候補を試す run はない。固定を properties に移したのは #121 の PR 1 で、版は変えていない。CI の入れ方を action の1か所にしたのは #121 の PR 2 で、版も入れ方（apt の 5.020-1）も変えていない。

版ごとの記録は `docs/toolchain/<版>/` に置く。

- [5.020/mutation.tsv](5.020/mutation.tsv): #121 の PR 2 で、action で入れた 5.020（apt の 5.020-1、JDK 21）の mutation の判定（Mutation check の run 39130793620、PR の head 0411409）。53 mutant すべて KILLED（category は assertion）。mutant を入れない実行は、8 shard とも1回目で通った。列は `mutant`・`verdict`・`category` で、job のログに出た `results.tsv` から作った
