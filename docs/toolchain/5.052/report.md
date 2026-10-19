# Verilator 5.052（候補）の記録

[toolchain-candidates.yml](../../../.github/workflows/toolchain-candidates.yml) の run の記録である（[#121](https://github.com/fregata-ariel/deterministic-vliw-chisel/issues/121)）。report job が job のログに出したものを、そのまま commit した。後の run の記録は、下に `## run` の節として足す。分類の意味は、[docs/toolchain/README.md](../README.md#候補を試す) にある。

## run 39175083401（attempt 1、2026-10-19）

<!-- toolchain-candidates: {"attempt":1,"commit":"ea338be98e1e838d3518809ce8899f85a009963c","kind":"candidate","main":"2440f0dd096cf1750e090240c276b72b37c9e240","parts":{"build":{"class":"success"},"mutation":{"candidate_only":[],"class":"success"},"test":{"candidate_only":[],"class":"success"},"timing":{"candidate_only":[],"class":"success","ratio":1.235}},"pinned":"5.020","run_id":"39175083401","version":"5.052"} -->

| 項目 | 値 |
|---|---|
| 版 | 5.052（候補。対照は 5.020） |
| 入れ方 | ソースからビルド（タグ `v5.052`、commit `ea338be98e1e838d3518809ce8899f85a009963c`、タグの日付 2026-09-05） |
| run | [39175083401](https://github.com/fregata-ariel/deterministic-vliw-chisel/actions/runs/39175083401)（attempt 1） |
| 起動 | pull_request #124（main `2440f0dd096cf1750e090240c276b72b37c9e240`、head `3cecf528040fa00414b803a24956cbae11e01a90`、merge commit `ebc2046613c04fbc3e54265ef71bce773bbbd003`） |
| knob | `CANDIDATES="5.032 5.052"`、`MUTATION="5.020 5.052"`、`SEED_RUNS="3"`、`TIMING="true"`、`PARTS=""` |
| runner（build job） | AMD EPYC 7763 64-Core Processor、nproc 4、image ubuntu24 20261012.334.1 |

### 判定

success と test-failure だけが結果である。ほかの分類（setup、timeout、cancelled、skipped、compile、other）は判定不能で、その part を `PARTS` でやり直す。

| part | 分類 | 詳細 |
|---|---|---|
| build | success | installed and checked |
| test | success (3/3) | same as the control: success |
| timing | success r=1.235 | same as the control: success。ratio 1.235: the mean time of 5.052 over that of 5.020 in one job; #121 §5: 555 s x 1.235 x 1.1 = 754 s, within CHECK_TIMEOUT 780 s |
| mutation | success [53 KILLED, 0 SURVIVED, 0 other] | same as the control: success |

### test

run 1 は既定の seed、2 以降は `VLIW_SIM_SEED=random`（JVM ごとに新しい seed）で、ci.yml の `scala` ジョブと同じ `scripts/check.sh scala` を走らせた。秒は、その step の壁時計の時間である。

| run | seed | 分類 | exit | 秒 | テスト（成功/失敗/取消/無視/保留） | suite（完了/中断） | 失敗した suite | ビルド | %Warning | CPU | nproc | image |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | `VLIW_SIM_SEED=1 (default) verilator=5.052` | success | 0 | 408 | 380/0/0/14/0 | 45/0 | — | 103 | — | AMD EPYC 9V45 96-Core Processor | 4 | ubuntu24 20261012.334.1 |
| 2 | `VLIW_SIM_SEED=1096187003 (random) verilator=5.052`<br>`VLIW_SIM_SEED=291709216 (random) verilator=5.052` | success | 0 | 644 | 380/0/0/14/0 | 45/0 | — | 103 | — | Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz | 4 | ubuntu24 20261012.334.1 |
| 3 | `VLIW_SIM_SEED=2080377174 (random) verilator=5.052`<br>`VLIW_SIM_SEED=1872343549 (random) verilator=5.052` | success | 0 | 696 | 380/0/0/14/0 | 45/0 | — | 103 | — | AMD EPYC 7763 64-Core Processor | 4 | ubuntu24 20261012.334.1 |

### fingerprint

build job で入れて確かめたもの:

~~~
pinned: verilator 5.020 (apt 5.020-1), jdk 21 (project/toolchain.properties)
expected: verilator 5.052 (--expect)
verilator: /home/runner/.cache/vliw-toolchain/verilator-5.052-ea338be98e1e/bin/verilator
VERILATOR_ROOT: unset
verilator --version: Verilator 5.052 2026-09-05 rev v5.052
verilator_bin: /home/runner/.cache/vliw-toolchain/verilator-5.052-ea338be98e1e/bin/verilator_bin
verilator_bin sha256: 5cb88115f40be3548bd9e26e7cea7f2e9c26cbfd40e8422283c5c9abcff1a365
build-info: version: 5.052
build-info: commit: ea338be98e1e838d3518809ce8899f85a009963c
build-info: tag commit: ea338be98e1e838d3518809ce8899f85a009963c
build-info: result: success
build-info: seconds: 345
build-info: cpus: 4
build-info: g++: g++ (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0
build-info: compiler warnings: 3
build-info: verilator: Verilator 5.052 2026-09-05 rev v5.052
cache: restored (build-info.txt written 2026-10-18T22:52:32Z, before this job)
java -version: openjdk version "21.0.12.1" 2026-08-18 LTS
VLIW_VERILATOR_EXPECTED: 5.052
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

ratio 1.235: the mean time of 5.052 over that of 5.020 in one job; #121 §5: 555 s x 1.235 x 1.1 = 754 s, within CHECK_TIMEOUT 780 s

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

<!-- toolchain-candidates: {"attempt":1,"commit":"ea338be98e1e838d3518809ce8899f85a009963c","kind":"pinned","main":"4296081b659024e2da6a61411fa1676f1e027b2c","parts":{"build":{"class":"success"},"test":{"class":"success"}},"pinned":"5.052","run_id":"39201497326","version":"5.052"} -->

| 項目 | 値 |
|---|---|
| 版 | 5.052（固定の版。この run の対照） |
| 入れ方 | ソースからビルド（タグ `v5.052`、commit `ea338be98e1e838d3518809ce8899f85a009963c`） |
| run | [39201497326](https://github.com/fregata-ariel/deterministic-vliw-chisel/actions/runs/39201497326)（attempt 1） |
| 起動 | pull_request #126（main `4296081b659024e2da6a61411fa1676f1e027b2c`、head `c65517c048a37937f3ce70194743170fe11e65b5`、merge commit `05b1a570205e77e61132b80d685b8c122f945e31`） |
| knob | `CANDIDATES="apt:5.020-1"`、`MUTATION=""`、`SEED_RUNS="10"`、`TIMING="false"`、`PARTS=""` |
| runner（build job） | AMD EPYC 7763 64-Core Processor、nproc 4、image ubuntu24 20261012.334.1 |

### 判定

success と test-failure だけが結果である。ほかの分類（setup、timeout、cancelled、skipped、compile、other）は判定不能で、その part を `PARTS` でやり直す。

| part | 分類 | 詳細 |
|---|---|---|
| build | success | installed and checked |
| test | success (10/10) | every run passed |

### test

run 1 は既定の seed、2 以降は `VLIW_SIM_SEED=random`（JVM ごとに新しい seed）で、ci.yml の `scala` ジョブと同じ `scripts/check.sh scala` を走らせた。秒は、その step の壁時計の時間である。

| run | seed | 分類 | exit | 秒 | テスト（成功/失敗/取消/無視/保留） | suite（完了/中断） | 失敗した suite | ビルド | %Warning | CPU | nproc | image |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | `VLIW_SIM_SEED=1 (default) verilator=5.052` | success | 0 | 666 | 380/0/0/14/0 | 45/0 | — | 103 | — | Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz | 4 | ubuntu24 20261012.334.1 |
| 2 | `VLIW_SIM_SEED=303565723 (random) verilator=5.052`<br>`VLIW_SIM_SEED=2100359870 (random) verilator=5.052` | success | 0 | 445 | 380/0/0/14/0 | 45/0 | — | 103 | — | AMD EPYC 9V45 96-Core Processor | 4 | ubuntu24 20261012.334.1 |
| 3 | `VLIW_SIM_SEED=1526766143 (random) verilator=5.052`<br>`VLIW_SIM_SEED=1686475440 (random) verilator=5.052` | success | 0 | 531 | 380/0/0/14/0 | 45/0 | — | 103 | — | AMD EPYC 9V74 80-Core Processor | 4 | ubuntu24 20261012.334.1 |
| 4 | `VLIW_SIM_SEED=157576318 (random) verilator=5.052`<br>`VLIW_SIM_SEED=1455992103 (random) verilator=5.052` | success | 0 | 701 | 380/0/0/14/0 | 45/0 | — | 103 | — | AMD EPYC 7763 64-Core Processor | 4 | ubuntu24 20261012.334.1 |
| 5 | `VLIW_SIM_SEED=8322236 (random) verilator=5.052`<br>`VLIW_SIM_SEED=943138918 (random) verilator=5.052` | success | 0 | 672 | 380/0/0/14/0 | 45/0 | — | 103 | — | AMD EPYC 7763 64-Core Processor | 4 | ubuntu24 20261012.334.1 |
| 6 | `VLIW_SIM_SEED=1641310668 (random) verilator=5.052`<br>`VLIW_SIM_SEED=994641478 (random) verilator=5.052` | success | 0 | 426 | 380/0/0/14/0 | 45/0 | — | 103 | — | AMD EPYC 9V45 96-Core Processor | 4 | ubuntu24 20261012.334.1 |
| 7 | `VLIW_SIM_SEED=1076498647 (random) verilator=5.052`<br>`VLIW_SIM_SEED=952134929 (random) verilator=5.052` | success | 0 | 677 | 380/0/0/14/0 | 45/0 | — | 103 | — | AMD EPYC 7763 64-Core Processor | 4 | ubuntu24 20261012.334.1 |
| 8 | `VLIW_SIM_SEED=584418899 (random) verilator=5.052`<br>`VLIW_SIM_SEED=1099270403 (random) verilator=5.052` | success | 0 | 684 | 380/0/0/14/0 | 45/0 | — | 103 | — | AMD EPYC 7763 64-Core Processor | 4 | ubuntu24 20261012.334.1 |
| 9 | `VLIW_SIM_SEED=673899494 (random) verilator=5.052`<br>`VLIW_SIM_SEED=2115234541 (random) verilator=5.052` | success | 0 | 674 | 380/0/0/14/0 | 45/0 | — | 103 | — | AMD EPYC 7763 64-Core Processor | 4 | ubuntu24 20261012.334.1 |
| 10 | `VLIW_SIM_SEED=1164998782 (random) verilator=5.052`<br>`VLIW_SIM_SEED=946900952 (random) verilator=5.052` | success | 0 | 657 | 380/0/0/14/0 | 45/0 | — | 103 | — | AMD EPYC 9V74 80-Core Processor | 4 | ubuntu24 20261012.334.1 |

### fingerprint

build job で入れて確かめたもの:

~~~
pinned: verilator 5.052 (commit ea338be98e1e838d3518809ce8899f85a009963c), jdk 21 (project/toolchain.properties)
expected: verilator 5.052 (--expect)
verilator: /home/runner/.cache/vliw-toolchain/verilator-5.052-ea338be98e1e/bin/verilator
VERILATOR_ROOT: unset
verilator --version: Verilator 5.052 2026-09-05 rev v5.052
verilator_bin: /home/runner/.cache/vliw-toolchain/verilator-5.052-ea338be98e1e/bin/verilator_bin
verilator_bin sha256: 5cb88115f40be3548bd9e26e7cea7f2e9c26cbfd40e8422283c5c9abcff1a365
build-info: version: 5.052
build-info: commit: ea338be98e1e838d3518809ce8899f85a009963c
build-info: tag commit: ea338be98e1e838d3518809ce8899f85a009963c
build-info: result: success
build-info: seconds: 333
build-info: cpus: 4
build-info: g++: g++ (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0
build-info: compiler warnings: 3
build-info: verilator: Verilator 5.052 2026-09-05 rev v5.052
cache: built in this job (build-info.txt written 2026-10-19T04:56:40Z)
java -version: openjdk version "21.0.12.1" 2026-08-18 LTS
VLIW_VERILATOR_EXPECTED: unset
~~~

test job の `verilator_bin` の sha256: すべて build job と同じ。
