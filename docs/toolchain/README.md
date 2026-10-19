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

入力 `verilator`（`5.052` のような版か、`apt:5.020-1` のような apt のパッケージの版）と `commit`（ソースからビルドする候補の、タグの commit。40 桁の16進）を使えるのは、`github.workflow_ref` が `/.github/workflows/toolchain-candidates.yml@` を含むときだけである（`VLIW_VERILATOR_EXPECTED` と同じ条件）。それ以外の workflow で入力を書くと、action の最初の step が失敗する。固定と違う版を入れたときは、action が `VLIW_VERILATOR_EXPECTED` をその版にして、後の step に渡す。`scripts/check.sh static`（`scripts/check-workflows.sh`）は、workflow に apt で Verilator を入れる行、`actions/setup-java`、`java-version` がないことと、`ci.yml` の setup-toolchain の step に `with:` がないことを確かめる。また、`VLIW_VERILATOR_EXPECTED`・`VLIW_VERILATOR_JOBS` を書けるのが、この action、`toolchain-candidates.yml` とその `scripts/toolchain-candidates.py`（timing の run ごとに期待する版を設定する）、`mutation.yml`、読み手（`scripts/toolchain.sh` と `vliw.hw.Toolchain`）、自己テスト（`scripts/test-*`）、文書（`docs/` と `*.md`）だけであることを、追跡しているファイルで確かめる（コメントの行は除く）。

`ci.yml` の `scala` ジョブの枠は 30 分である。ソースから入れる版でキャッシュがないときは、同じ job の中でビルドする。ビルドを別の job にして `needs` でつなぐと、その job が失敗したとき `scala` は skipped になり、GitHub は skipped を成功と扱うので、必須の check が通ってしまう。

`mutation.yml` は、ツールチェーンを変えた PR（properties、action、`scripts/toolchain.sh`、`scripts/verilator-build.sh`、`Toolchain.scala`、`VliwChiselSim.scala`）でも走る。キャッシュがないときは shard ごとにビルドする。artifact の名前には Verilator の版と入れ方が入る（`mutation-logs-verilator-5.020-apt-5.020-1-shard1-attempt1` など）。エージェントの session は artifact を落とせないので、shard ごとの `results.tsv` とベースライン（mutant なしの run とそのやり直し）の判定を、job のログの最後にも出す（それぞれ `===BEGIN results.tsv ...===`、`===BEGIN baseline ...===` から `===END===` まで）。候補を試す workflow（下の[候補を試す](#候補を試す)）は、`workflow_call` の入力 `verilator`・`commit` で、版ごとに mutation を呼ぶ。

## 別の版を手元で試す

その版の `verilator` を PATH の先頭に置き（ソースからなら、上の `scripts/verilator-build.sh` でビルドする）、`VLIW_VERILATOR_EXPECTED` にその版を書く。

```sh
VLIW_VERILATOR_EXPECTED=5.052 scripts/check.sh scala
```

`VLIW_VERILATOR_EXPECTED` を受け付けるのは、手元（`GITHUB_ACTIONS` が空）と、候補を試す workflow（`.github/workflows/toolchain-candidates.yml`。下の[候補を試す](#候補を試す)）だけである。それ以外の CI で設定すると、`scripts/toolchain.sh check` とテストが失敗する。空文字列も誤りである。

上書きしても、`benchmarks/RESULTS.md` のツールの行は固定の版を書く。`benchmarks/` に差分が出てはならない（R2）。

## 候補を試す

候補の版は、[.github/workflows/toolchain-candidates.yml](../../.github/workflows/toolchain-candidates.yml) で、固定の版を対照にして同じ run で試す（#121 の PR 3）。run は PR への push で始まる。エージェントの session は、workflow_dispatch も job の re-run もできず（403）、artifact も落とせないが、job のログは読める。そのため、設定（knob）はすべて workflow の先頭の `env` に書き、結果はすべて job のログに出す。この workflow は必須の check ではない。

PR の差分が、この workflow、`project/toolchain.properties`、action（`.github/actions/setup-toolchain/`）、`scripts/verilator-build.sh`、`scripts/toolchain.sh`、`scripts/toolchain-candidates.py`、`Toolchain.scala`、`VliwChiselSim.scala` のどれかを含むと走る。マージの後は、オーナーが workflow_dispatch でも起動できる（同じ knob を読む）。定期実行はない。処理の中身は `scripts/toolchain-candidates.py` にあり、`scripts/check.sh static` が偽の入力で確かめる（`scripts/test-toolchain-candidates.py`）。

### knob

| knob | 既定 | 値 |
|---|---|---|
| `CANDIDATES` | `"5.032 5.052"` | 候補を空白で区切る。`5.052` はタグ `v5.052` のソースからビルドし、`apt:5.020-1` は apt のパッケージを入れる。3つまで。固定の版はいつも対照として先頭で走るので、候補に書いても1回だけ走る。同じ候補を2回書いても1回だけ走る |
| `MUTATION` | `""` | mutation を走らせる版（固定の版か候補を、版の番号で書く）。候補を書くときは、同じ run の対照として固定の版も書く（例: `"5.020 5.052"`）。1つの版で 8 shard、約 7 runner 時間かかる |
| `SEED_RUNS` | `"1"` | 版ごとの test の回数（1〜10）。run 1 は既定の seed、2 以降は `VLIW_SIM_SEED=random`（JVM ごとに新しい seed） |
| `TIMING` | `"false"` | `true` なら、固定の版とソースの候補を1つの job に入れ、A B C C B A の順に `sbt test` を走らせて時間を比べる |
| `PARTS` | `""` | 空ならすべての part を走らせる。`test`・`timing`・`mutation` を空白で区切って書くと、その part だけを走らせる（やり直し）。build はいつも走る |

`env` は job の `if`・`strategy`・`with` では使えないので、plan job が knob を確かめて outputs に出し、後の job はそれだけを使う。plan job は、次の場合に失敗し、何も走らせない: 書式の誤り（空白以外の区切り、版の形でない値）、upstream に annotated tag `v<版>` がない、同じ版の候補が2つ、候補が4つ以上、固定の版でも候補でもない版の `MUTATION`、候補を書いて固定の版を書かない `MUTATION`、範囲外の `SEED_RUNS`、`true`・`false` 以外の `TIMING`、`TIMING` が `true` でないのに `PARTS` に `timing`、`MUTATION` が空なのに `PARTS` に `mutation`、`TIMING=true` でソースの候補がない。knob が既定と違えば warning を出す（マージの前に戻すため）。既定は `scripts/toolchain-candidates.py` の `DEFAULTS` にもあり、`scripts/check.sh static`（`scripts/test-toolchain-candidates.py`）は、workflow の knob がそれと同じことを確かめる。そのため、knob を変えたブランチでは、ci.yml の `static` が失敗する。knob を戻すと通る。タグの日付が2週間以内なら warning を出す。ADR-0004 は2週間未満の版を採らないが、試すのはかまわない。

### job

- plan: 固定の版を先頭（対照）に置き、候補を続ける。ソースの候補は、`git ls-remote https://github.com/verilator/verilator 'refs/tags/v<版>^{}'` で今のタグの commit を決め、ログと記録に出す。この commit を action の入力 `commit` に渡すので、この run の間にタグが動けば、`scripts/verilator-build.sh` がビルドのときに気づく。run の前に動いたタグは、新しい commit のまま試される。そこで plan は、commit された `docs/toolchain/<版>/report.md` の記録の commit と違えば warning を出す。版を固定にするときは、記録の commit を `project/toolchain.properties` に書く。
- build（版ごと）: action で入れる。ソースの候補はここでビルドしてキャッシュに保存するので、後の job は復元するだけになる。fingerprint をログに出す。2回目の push では、fingerprint の `cache:` の行が `restored` になる。
- test（版 × run）: ci.yml の `scala` ジョブと同じ `scripts/check.sh scala` を、同じ時間の枠（`CHECK_TIMEOUT` 780 秒、step 15 分、job 30 分）で走らせる。候補では、action が `VLIW_VERILATOR_EXPECTED` を設定し、`GITHUB_WORKFLOW_REF` がこの workflow を指すので、読み手はそれを受け付ける。`[toolchain]` の行は、候補では `(env)`、対照では `(pinned)` でなければならず、`[sim-seed]` の行は、run 1 では `(default)`、2 以降では `(random)` でなければならない。違えば setup に分ける。失敗した run では、失敗したテストの failure text（下）も、その job のログに出す。
- timing（`TIMING=true` のとき）: 1つの job に、action で固定の版とソースの候補を1つずつ入れる。固定の版を先に入れる（候補の action が `VLIW_VERILATOR_EXPECTED` を設定するため）。ソースのビルドはそれぞれの prefix にあり、apt の版は `/usr/bin` にある。apt の候補は固定の版と同じ job に入れられないので、timing から除く。`Test/compile` を1回してから（時間に入れない）、`scripts/check.sh sbt test` を A B C C B A の順に走らせる。run ごとに、PATH にはその版の prefix だけを置き、候補では `VLIW_VERILATOR_EXPECTED` をその版にする（seed はどれも既定）。失敗した run の failure text は、この job のログに出る。比は、候補の2回の平均 ÷ 固定の版の2回の平均で、この job のログに出る。report は、比から #121 §5 の条件（最近の `check.sh scala` の最大 555 秒 × 比 × 1.1 が `CHECK_TIMEOUT` の 780 秒を超えるか）も出す。
- mutation（`MUTATION` の版ごと）: `mutation.yml` を `workflow_call` で呼ぶ（入力 `verilator`・`commit`、固定の版では空）。固定の版も呼んで、同じ run の対照にする。shard ごとの判定は、その job のログに出る。
- report（いつも走る）: すべての part を分類し、候補を対照と比べ、表と判定を出す。次に mutation の shard の failure text を出し、最後に commit する記録を出す。

build・test・timing の job は、`toolchain-candidates summary: {...}` の1行（JSON）をログに出し、同じものを artifact に上げる。report job は、同じ run の artifact（この要約と mutation の shard のログ）を落として読む。エージェントは artifact を落とせないが、同じ run の job は落とせる。

### 手順

1. PR のブランチで、workflow の `env` の knob を書き換えて push する。最初の push は既定の knob でよい（固定の版と 5.032・5.052 の test を1回ずつ）。判断の run は `CANDIDATES="5.032 5.052"`、`MUTATION="5.020 5.052"`、`SEED_RUNS="3"`、`TIMING="true"` にする。run の間は、そのブランチに push しない。concurrency は前の run を取り消さないが、次の run を待たせる。
2. report job のログを読む。版 × part の表と、part ごとの判定（`verdicts:`）が出る。
3. ログの最後の `===BEGIN docs/toolchain/<版>/<ファイル>===` から `===END===` までを、それぞれそのファイルとして commit する。`report.md` が既にあれば、新しい記録の `## run` の行から下だけを、その下に足す（前の run の節は消さない）。`mutation.tsv` は置き換え、前のものと違えば、その差を確かめる。
4. 判定不能の part があれば、`PARTS` にその part を書いて push し、やり直す。候補だけの失敗も、記録を commit してから、同じようにやり直す。
5. 終わったら、knob を既定に戻してからマージする。

わざと setup を失敗させて report を確かめるには、`CANDIDATES` に image の apt にないパッケージ（例: `apt:5.999-1`）を足す。その版の part はすべて setup（判定不能）になる。確かめたら戻す。

### 分類

report は part ごとに、次のどれかに分ける。test は run ごとに分け、1つでも test-failure があれば test-failure、すべて success なら success、それ以外は判定不能とする。

| 分類 | 意味 | 扱い |
|---|---|---|
| success | テストが通った。mutation では、すべての shard のベースラインが通り、すべての mutant が KILLED | 結果 |
| test-failure | テストが失敗した（失敗したテストか suite の名前つき）。mutation では、SURVIVED の mutant か、ベースライン（mutant なしの run）の失敗 | 結果 |
| setup | action（apt、キャッシュ、ビルド、`toolchain.sh check`、fingerprint の確認）の失敗、`scripts/check.sh` の中の `toolchain.sh check` の失敗、期待と違う `[toolchain]` か `[sim-seed]` の行 | 判定不能 |
| timeout | `CHECK_TIMEOUT` か、step の `timeout-minutes` で止まった（`CHECK_TIMEOUT` の後の exit 137、つまり TERM で止まらず KILL されたものも含む） | 判定不能 |
| cancelled | 取り消された | 判定不能 |
| skipped | 走らなかった | 判定不能 |
| compile | scalac か scalafmt の失敗 | 判定不能 |
| other | 上のどれでもない（テストの JVM が死んだ、job の要約がない、失敗した suite もテストも名前がない、対照の失敗がすべて環境の失敗、mutation の ERROR・欠けた shard・判定のない mutant など） | 判定不能 |

- 結果として数えるのは success と test-failure だけである。それ以外は「判定不能（やり直し）」で、skipped と cancelled も成功とは数えない。
- 候補と対照は、テストごとに比べる（`<suite> / <test>`。JUnit の報告がテストを名指さない suite だけは、suite で比べる）。同じ suite の別のテストが対照で失敗していても、候補のテストの失敗は消えない。mutation では、SURVIVED の mutant と、ベースラインで失敗したテスト（`baseline: <suite> / <test>`）を比べる。
- 候補だけが失敗したとき（対照は同じ part で結果があり、そのテストは失敗していない）、report は `candidate-only failure` と出す。これを候補の回帰（`REGRESSION`）とするのは、記録を commit してから `PARTS` で同じ part をやり直し、同じテストがまた候補だけで失敗したときだけである。report job は、commit された `report.md` の各 run の節にある機械用の行（`<!-- toolchain-candidates: {...} -->`）と比べて、それを判定する。
- 対照も同じテストで失敗したときは、候補の回帰ではない。対照が判定不能なら、候補を比べる相手がないので、候補の part も判定不能である。対照の失敗がすべて環境の失敗（`scripts/mutation-classify.py` の `env:sim-build`・`env:sim-exit` など）なら、候補については何も言えないので、対照を判定不能（other）とする。候補の環境の失敗は、候補の版のせいでありうるので、結果（test-failure）のままにする。
- 結果は版ごとである。ある版の結果で、別の版を判断しない。
- 失敗したテストには、`scripts/mutation-classify.py` の分類（assertion、`env:sim-exit`、`env:sim-build` など）も付ける。5.040 以降の失敗の見え方（assertion の `exit(1)`、`Error 134`・`Error 137`）は、判断の run の failure text から fixture にする（#121 の M8）。

### failure text

失敗した run のうち、`scripts/testdata/mutation-classify/` の fixture に要るファイルを、`===BEGIN failure-text <ラベル>/<ファイル>===` と `===END===` で挟んでログに出す（#121 の M8。5.040 以降の失敗の見え方を fixture にするため）。ファイルは、`verdict`（今の `scripts/mutation-classify.py` の判定）、`exit`、`classification.tsv`、`reports/<project>/TEST-<suite>.xml`（失敗したテストのうち分類ごとに最初の1つだけを残し、長い本文は、12000 文字の後ろを、スタックフレームでない行と各塊の最初のフレームだけにする）、`sbt.log` の最後の 60 行、ChiselSim の `simulation-log.txt`・`compilation-log.txt` の最後の 40 行、mutant の `mutant.diff` である。

- test job（`Summarize` の step）: 失敗した run のテスト。ラベルは `<版>/test-run<run>`。
- timing job: 失敗した run のテスト。ラベルは `<版>/timing-position<順>`。
- report job: mutation の版ごとに、失敗したベースライン（mutant なしの run）、KILLED と SURVIVED 以外の判定（verdict と category の組）ごとに最初の mutant、対照と category の違う KILLED ごとに最初の mutant（版ごとに 8 つまで）。ラベルは `<版>/mutation/<run のディレクトリ>`。

fixture にするには、1つのラベルのファイルを `scripts/testdata/mutation-classify/<名前>/` に置き、`expected` に期待する判定（verdict と category）を書く。分類器のコードを直す必要があれば、別の PR（#121 の PR 4）にする。

### 記録

- `docs/toolchain/<版>/report.md`: 版、入れ方（apt のパッケージ、またはタグ・commit・タグの日付）、run の URL と attempt、テストした main の commit（PR では merge commit の第1親）、PR の head と merge commit、knob、runner（CPU、nproc、image）、part ごとの分類と対照との比較、test の run ごとの seed・分類・終了コード・秒・テストの数・失敗した suite・Verilator のビルドの数・`%Warning-<CODE>` の数、fingerprint（build job のもの。test job の `verilator_bin` の sha256 がそれと同じか）、timing の時間と比、mutation の shard ごとのベースライン・判定の数・やり直し。
- `docs/toolchain/<版>/mutation.tsv`: mutation を走らせ、すべての mutant に判定がそろい、分類が結果（success か test-failure）のときだけ出す。列は `mutant`・`verdict`・`category` で、mutant の名前の順である（[5.020/mutation.tsv](5.020/mutation.tsv) と同じ形）。そろわないときは、report.md にその理由を書く。その場合は、mutation の job のログの `===BEGIN results.tsv ...===` から作る。

## 版を変える手順

ADR-0020 の決まりを、次の順で行う。

1. 候補の版を、固定の版を対照にして、同じ run で PR から試す（[候補を試す](#候補を試す)）。結果を `docs/toolchain/<版>/` に残す。
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
- 判断の run（#121 の PR 3。Toolchain candidates の run 39175083401、knob は `CANDIDATES="5.032 5.052"`・`MUTATION="5.020 5.052"`・`SEED_RUNS="3"`・`TIMING="true"`）: [5.020/report.md](5.020/report.md)、[5.032/report.md](5.032/report.md)、[5.052/report.md](5.052/report.md)、[5.052/mutation.tsv](5.052/mutation.tsv)。report job が出した記録を、そのまま commit した。どの版も test（seed 3 回）・timing が success で、5.052 の mutation は 5.020 と同じ判定（53 mutant すべて KILLED）。timing の比は 5.032 が 1.052、5.052 が 1.235。5.020 の mutation の判定は、上の PR 2 の run と同じだった
