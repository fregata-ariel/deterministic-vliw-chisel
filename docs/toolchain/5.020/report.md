# Verilator 5.020（固定の版）の記録

[toolchain-candidates.yml](../../../.github/workflows/toolchain-candidates.yml) の run の記録である（[#121](https://github.com/fregata-ariel/deterministic-vliw-chisel/issues/121)）。report job が job のログに出したものを、そのまま commit した。後の run の記録は、下に `## run` の節として足す。分類の意味は、[docs/toolchain/README.md](../README.md#候補を試す) にある。

## run 39175083401（attempt 1、2026-10-19）

<!-- toolchain-candidates: {"attempt":1,"kind":"pinned","main":"2440f0dd096cf1750e090240c276b72b37c9e240","parts":{"build":{"class":"success"},"mutation":{"class":"success"},"test":{"class":"success"},"timing":{"class":"success","ratio":1.0}},"pinned":"5.020","run_id":"39175083401","version":"5.020"} -->

| 項目 | 値 |
|---|---|
| 版 | 5.020（固定の版。この run の対照） |
| 入れ方 | apt のパッケージ `5.020-1`（ubuntu-24.04） |
| run | [39175083401](https://github.com/fregata-ariel/deterministic-vliw-chisel/actions/runs/39175083401)（attempt 1） |
| 起動 | pull_request #124（main `2440f0dd096cf1750e090240c276b72b37c9e240`、head `3cecf528040fa00414b803a24956cbae11e01a90`、merge commit `ebc2046613c04fbc3e54265ef71bce773bbbd003`） |
| knob | `CANDIDATES="5.032 5.052"`、`MUTATION="5.020 5.052"`、`SEED_RUNS="3"`、`TIMING="true"`、`PARTS=""` |
| runner（build job） | AMD EPYC 9V45 96-Core Processor、nproc 4、image ubuntu24 20261012.334.1 |

### 判定

success と test-failure だけが結果である。ほかの分類（setup、timeout、cancelled、skipped、compile、other）は判定不能で、その part を `PARTS` でやり直す。

| part | 分類 | 詳細 |
|---|---|---|
| build | success | installed and checked |
| test | success (3/3) | every run passed |
| timing | success | both runs passed |
| mutation | success [53 KILLED, 0 SURVIVED, 0 other] | every mutant KILLED (53) |

### test

run 1 は既定の seed、2 以降は `VLIW_SIM_SEED=random`（JVM ごとに新しい seed）で、ci.yml の `scala` ジョブと同じ `scripts/check.sh scala` を走らせた。秒は、その step の壁時計の時間である。

| run | seed | 分類 | exit | 秒 | テスト（成功/失敗/取消/無視/保留） | suite（完了/中断） | 失敗した suite | ビルド | %Warning | CPU | nproc | image |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | `VLIW_SIM_SEED=1 (default) verilator=5.020` | success | 0 | 565 | 380/0/0/14/0 | 45/0 | — | 103 | — | AMD EPYC 7763 64-Core Processor | 4 | ubuntu24 20261012.334.1 |
| 2 | `VLIW_SIM_SEED=819568351 (random) verilator=5.020`<br>`VLIW_SIM_SEED=420094947 (random) verilator=5.020` | success | 0 | 428 | 380/0/0/14/0 | 45/0 | — | 103 | — | AMD EPYC 9V74 80-Core Processor | 4 | ubuntu24 20261012.334.1 |
| 3 | `VLIW_SIM_SEED=816971358 (random) verilator=5.020`<br>`VLIW_SIM_SEED=2116592301 (random) verilator=5.020` | success | 0 | 445 | 380/0/0/14/0 | 45/0 | — | 103 | — | AMD EPYC 9V74 80-Core Processor | 4 | ubuntu24 20261012.334.1 |

### fingerprint

build job で入れて確かめたもの:

~~~
pinned: verilator 5.020 (apt 5.020-1), jdk 21 (project/toolchain.properties)
expected: verilator 5.020 (--expect)
verilator: /usr/bin/verilator
VERILATOR_ROOT: unset
verilator --version: Verilator 5.020 2024-01-01 rev (Debian 5.020-1)
verilator_bin: /usr/bin/verilator_bin
verilator_bin sha256: 25f33046f0055a6982c67cdc55081685a844abb50d044cfda087bd418db9be6c
dpkg-query verilator: 5.020-1
java -version: openjdk version "21.0.12.1" 2026-08-18 LTS
VLIW_VERILATOR_EXPECTED: unset
~~~

test job の `verilator_bin` の sha256: すべて build job と同じ。

### timing

1つの job（CPU AMD EPYC 7763 64-Core Processor、nproc 4、image ubuntu24 20261012.334.1）で、5.020 5.032 5.052 5.052 5.032 5.020 の順に `scripts/check.sh sbt test` を走らせた（コンパイルは先に1回だけ行い、時間に入れない）。

| 順 | 版 | 秒 | exit | 分類 |
|---|---|---|---|---|
| 1 | 5.020 | 518 | 0 | success |
| 2 | 5.032 | 540 | 0 | success |
| 3 | 5.052 | 634 | 0 | success |
| 4 | 5.052 | 636 | 0 | success |
| 5 | 5.032 | 542 | 0 | success |
| 6 | 5.020 | 510 | 0 | success |

### mutation

`mutation.yml` を `workflow_call` で呼んだ。shard ごとの判定は、その job のログの `===BEGIN results.tsv ...===` と `===BEGIN baseline ...===` にもある。

| shard | ベースライン | KILLED | SURVIVED | その他 | やり直した mutant |
|---|---|---|---|---|---|
| 1 | SURVIVED passed | 7 | 0 | 0 | — |
| 2 | SURVIVED passed | 7 | 0 | 0 | — |
| 3 | SURVIVED passed | 7 | 0 | 0 | — |
| 4 | SURVIVED passed | 7 | 0 | 0 | — |
| 5 | SURVIVED passed | 7 | 0 | 0 | — |
| 6 | SURVIVED passed | 6 | 0 | 0 | — |
| 7 | SURVIVED passed | 6 | 0 | 0 | — |
| 8 | SURVIVED passed | 6 | 0 | 0 | — |

mutant ごとの判定は [mutation.tsv](mutation.tsv) にある（列は mutant、verdict、category）。

## run 39201497326（attempt 1、2026-10-19）

<!-- toolchain-candidates: {"attempt":1,"kind":"candidate","main":"4296081b659024e2da6a61411fa1676f1e027b2c","parts":{"build":{"class":"success"},"test":{"candidate_only":[],"class":"success"}},"pinned":"5.052","run_id":"39201497326","version":"5.020"} -->

| 項目 | 値 |
|---|---|
| 版 | 5.020（候補。対照は 5.052） |
| 入れ方 | apt のパッケージ `5.020-1`（ubuntu-24.04） |
| run | [39201497326](https://github.com/fregata-ariel/deterministic-vliw-chisel/actions/runs/39201497326)（attempt 1） |
| 起動 | pull_request #126（main `4296081b659024e2da6a61411fa1676f1e027b2c`、head `c65517c048a37937f3ce70194743170fe11e65b5`、merge commit `05b1a570205e77e61132b80d685b8c122f945e31`） |
| knob | `CANDIDATES="apt:5.020-1"`、`MUTATION=""`、`SEED_RUNS="10"`、`TIMING="false"`、`PARTS=""` |
| runner（build job） | INTEL(R) XEON(R) PLATINUM 8573C、nproc 4、image ubuntu24 20261012.334.1 |

### 判定

success と test-failure だけが結果である。ほかの分類（setup、timeout、cancelled、skipped、compile、other）は判定不能で、その part を `PARTS` でやり直す。

| part | 分類 | 詳細 |
|---|---|---|
| build | success | installed and checked |
| test | success (10/10) | same as the control: success |

### test

run 1 は既定の seed、2 以降は `VLIW_SIM_SEED=random`（JVM ごとに新しい seed）で、ci.yml の `scala` ジョブと同じ `scripts/check.sh scala` を走らせた。秒は、その step の壁時計の時間である。

| run | seed | 分類 | exit | 秒 | テスト（成功/失敗/取消/無視/保留） | suite（完了/中断） | 失敗した suite | ビルド | %Warning | CPU | nproc | image |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | `VLIW_SIM_SEED=1 (default) verilator=5.020` | success | 0 | 576 | 380/0/0/14/0 | 45/0 | — | 103 | — | AMD EPYC 7763 64-Core Processor | 4 | ubuntu24 20261012.334.1 |
| 2 | `VLIW_SIM_SEED=1673555632 (random) verilator=5.020`<br>`VLIW_SIM_SEED=157914956 (random) verilator=5.020` | success | 0 | 552 | 380/0/0/14/0 | 45/0 | — | 103 | — | AMD EPYC 7763 64-Core Processor | 4 | ubuntu24 20261012.334.1 |
| 3 | `VLIW_SIM_SEED=456010621 (random) verilator=5.020`<br>`VLIW_SIM_SEED=1007560793 (random) verilator=5.020` | success | 0 | 558 | 380/0/0/14/0 | 45/0 | — | 103 | — | AMD EPYC 7763 64-Core Processor | 4 | ubuntu24 20261012.334.1 |
| 4 | `VLIW_SIM_SEED=171536709 (random) verilator=5.020`<br>`VLIW_SIM_SEED=1744803988 (random) verilator=5.020` | success | 0 | 360 | 380/0/0/14/0 | 45/0 | — | 103 | — | AMD EPYC 9V45 96-Core Processor | 4 | ubuntu24 20261012.334.1 |
| 5 | `VLIW_SIM_SEED=1074992211 (random) verilator=5.020`<br>`VLIW_SIM_SEED=1518564826 (random) verilator=5.020` | success | 0 | 559 | 380/0/0/14/0 | 45/0 | — | 103 | — | AMD EPYC 7763 64-Core Processor | 4 | ubuntu24 20261012.334.1 |
| 6 | `VLIW_SIM_SEED=700272306 (random) verilator=5.020`<br>`VLIW_SIM_SEED=1771016515 (random) verilator=5.020` | success | 0 | 555 | 380/0/0/14/0 | 45/0 | — | 103 | — | AMD EPYC 7763 64-Core Processor | 4 | ubuntu24 20261012.334.1 |
| 7 | `VLIW_SIM_SEED=1393725354 (random) verilator=5.020`<br>`VLIW_SIM_SEED=981304451 (random) verilator=5.020` | success | 0 | 543 | 380/0/0/14/0 | 45/0 | — | 103 | — | AMD EPYC 9V74 80-Core Processor | 4 | ubuntu24 20261012.334.1 |
| 8 | `VLIW_SIM_SEED=1777710924 (random) verilator=5.020`<br>`VLIW_SIM_SEED=68827325 (random) verilator=5.020` | success | 0 | 526 | 380/0/0/14/0 | 45/0 | — | 103 | — | INTEL(R) XEON(R) PLATINUM 8573C | 4 | ubuntu24 20261012.334.1 |
| 9 | `VLIW_SIM_SEED=1911312104 (random) verilator=5.020`<br>`VLIW_SIM_SEED=1177116796 (random) verilator=5.020` | success | 0 | 459 | 380/0/0/14/0 | 45/0 | — | 103 | — | AMD EPYC 9V74 80-Core Processor | 4 | ubuntu24 20261012.334.1 |
| 10 | `VLIW_SIM_SEED=1740212346 (random) verilator=5.020`<br>`VLIW_SIM_SEED=1588231377 (random) verilator=5.020` | success | 0 | 568 | 380/0/0/14/0 | 45/0 | — | 103 | — | AMD EPYC 7763 64-Core Processor | 4 | ubuntu24 20261012.334.1 |

### fingerprint

build job で入れて確かめたもの:

~~~
pinned: verilator 5.052 (commit ea338be98e1e838d3518809ce8899f85a009963c), jdk 21 (project/toolchain.properties)
expected: verilator 5.020 (--expect)
verilator: /usr/bin/verilator
VERILATOR_ROOT: unset
verilator --version: Verilator 5.020 2024-01-01 rev (Debian 5.020-1)
verilator_bin: /usr/bin/verilator_bin
verilator_bin sha256: 25f33046f0055a6982c67cdc55081685a844abb50d044cfda087bd418db9be6c
dpkg-query verilator: 5.020-1
java -version: openjdk version "21.0.12.1" 2026-08-18 LTS
VLIW_VERILATOR_EXPECTED: 5.020
~~~

test job の `verilator_bin` の sha256: すべて build job と同じ。
