# Verilator 5.052 の verilate の並列の測定

#121 の §7（`--verilate-jobs 1` を外す検査）を始める前に、手元で測ったものである（2026-10-19、main 8a691a3）。この結果から、オーナーは当面、verilate を1スレッドで行う今の方式（ADR-0013）を残すと決めた。並列処理が効く場合と効かない場合は、#127 で後で調べる。生の出力は残していない。測り方は下に書いた。

## 機械

- Firecracker の VM（KVM）、Intel Xeon Processor @ 2.10GHz、4 vCPU
- `lscpu` は 1 socket × 4 cores × 1 thread。`thread_siblings_list` はどの CPU も自分だけで、`smt/control` は `notsupported`
- cgroup の CPU の上限はない（v1 の `cfs_quota_us` が -1、cpuset は 0-3）。steal は約 2%
- CPU を使い切る処理（C の整数の乗算の連鎖、xorshift のループ、Python のループ）を k 個同時に走らせたときの壁時計（3回、k=1 に対する比）:

| 処理 | k=1 | k=2 | k=4 | k=8 | 1つの vCPU に2つ |
|---|---|---|---|---|---|
| 乗算の連鎖（8本） | 1.000 | 1.057 | 1.085 | 2.165 | 2.011 |
| xorshift | 1.000 | 1.030 | 1.024 | 2.088 | 1.916 |
| Python | 1.000 | 1.178 | 1.145 | 2.317 | 2.136 |

4つまでは並列に動く。vCPU の6つの組それぞれに2つを固定しても、C の2つの処理では 1.01〜1.09（Python は 1.08〜1.20。1つの CPU に1つ固定しただけで 1.10）で、SMT の兄弟のように振る舞う組はなかった。

## 設計

- D1: `RandomKernelSpec` で最初に RTL をビルドするテスト（60 random kernels compute the DSL results on the RTL in the predicted cycles）
- D2: `RtlDifferentialSpec` の最大の設計（add L 2、sub L 3、mul L 4、ld L 3、4 banks x 64 words）

`build/chiselsim/` の Makefile から Verilator の引数を取り（カタログ 025f545 の `scripts/verilator-exit-race.py` の `verilator_args`）、jobs の指定と `--build` を外して、1回ごとに新しい `--Mdir` に verilate した（C++ のビルドはしない）。`VERILATOR_ROOT` と `VERILATOR_BIN` は外した。

## スレッドの数

`strace -f -qq -e trace=clone,clone3` で、`CLONE_THREAD` の clone を数えた（5回とも同じ値）。

| 版 | `-j 0` | `--verilate-jobs 1` |
|---|---|---|
| 5.052（ソース、commit ea338be） | 4 | 0 |
| 5.020（apt の 5.020-1、ソースの 5c5314b） | 3 | 0 |

スレッドはどれも最後の `verilator_bin` の中で作られる。60 回すべてが exit 0 だった。

## 出力

5.052 で、`--verilate-jobs 1`、`--build-jobs 0 --verilate-jobs 1`、`-j 0` の2回の、生成された `*.cpp`・`*.h`（D1・D2 とも 14 ファイル）と `*.mk` は byte 単位で同じだった。違うのは、コマンド行（`--Mdir` と jobs の指定）と時刻が入る `VsvsimTestbench__ver.d` と `VsvsimTestbench__verFiles.dat` だけである。速さの測定のすべての run でも、（版、設計）ごとにハッシュは1つだった。

## 時間

1回の verilate の壁時計（秒、50 回、順を入れ替えて交互に。平均 / 最小 / 最大）:

| 版、設定 | D1 | D2 |
|---|---|---|
| 5.052、`-j 0` | 0.143 / 0.122 / 0.176 | 0.156 / 0.126 / 0.204 |
| 5.052、`--verilate-jobs 1` | 0.142 / 0.106 / 0.208 | 0.153 / 0.115 / 0.200 |
| 5.020 apt、`-j 0` | 0.124 / 0.088 / 0.490 | 0.128 / 0.104 / 0.159 |
| 5.020 apt、`--verilate-jobs 1` | 0.116 / 0.096 / 0.161 | 0.131 / 0.104 / 0.157 |

5.052 で、子プロセスを含む user+sys と壁時計（20 回の平均）:

| 設計、設定 | 壁時計 | user+sys | 比 | 自発的 / 非自発的なコンテキストスイッチ |
|---|---|---|---|---|
| D2、`-j 0` | 0.154 | 0.155 | 1.01 | 29.9 / 1.7 |
| D2、`--verilate-jobs 1` | 0.155 | 0.150 | 0.97 | 8.9 / 0.8 |
| D1、`-j 0` | 0.146 | 0.146 | 1.00 | 29.4 / 1.9 |
| D1、`--verilate-jobs 1` | 0.138 | 0.133 | 0.97 | 9.0 / 0.8 |

`-j 0` でも user+sys は壁時計とほぼ等しい（1.00〜1.01）ので、同時に動いているのは平均1コアほどである。増えたスイッチは、worker が眠って起きる分と考えられる。5.020 apt の `-j 0` の D1 には、下の abort の run（exit 255、0.108 秒）が入っている。

## どこが並列か

v5.052 のソースで `V3ThreadScope` を使うのは2か所だけである。

- C++ の出力（`V3EmitC::emitcImp`、`V3EmitCImp.cpp:936`）: 残った module ごとに2つの task（slow と fast の C++ ファイルを作って書く）。`--trace` のときは trace の task も加わる（D1・D2 は使わない）
- 変数の並べ替え（`V3VariableOrder::orderAll`、`V3VariableOrder.cpp:244`）: module ごとに1つの task

parse、link、width、inline、gate、schedule などの段階と、ほかの出力（Syms、model、header、Makefile）は直列である。pool の大きさは `--verilate-jobs` で、`-j 0` は affinity の CPU の数になる（`verilatedos_c.h:117-145`）。`-j N` は `--build-jobs` と `--output-groups` も設定する（`V3Options.cpp` の 2000〜2013 行付近）。

D1・D2 では、inline の後に残る module が `$root` と `$unit` の2つなので、C++ の出力の task は4つで、3つのスレッドが4つのファイルを書く。C++ の出力は 5.0〜5.8 ミリ秒（strace の時刻の中央値、10 回）、変数の並べ替えは 0.1〜0.3 ミリ秒で、合わせて 5.3〜5.9 ミリ秒である。Verilator 自身の時間（D2 で約 95 ミリ秒、D1 で約 80 ミリ秒）の約 6〜7%、壁時計（約 140〜155 ミリ秒）の約 4% である。壁時計のうち約 60 ミリ秒は、perl の wrapper、`verilator_bin --get-supported DEV_ASAN` の子、`sh -c ulimit` で、これも直列である。

## 大きな設計

D2 の RTL を N 個、名前を変えて並べ、clock と reset を共有する top を作って verilate した（5.052、3回ずつ、秒。括弧は1スレッドに対する比）。

| N、inline | `--verilate-jobs 1` | `-j 0` | `--verilate-jobs 0` | 1 CPU（`taskset -c 0`）で `-j 0` | 1 CPU で `--verilate-jobs 4` | `-j 0` の user+sys / 壁時計 |
|---|---|---|---|---|---|---|
| 12、既定 | 1.07 | 1.09（1.01） | 1.15（1.07） | 1.11（1.04） | 1.16（1.08） | 1.04 |
| 12、`-fno-inline` | 1.22 | 1.17（0.96） | 1.22（1.01） | 1.24（1.02） | 1.15（0.95） | 1.16 |
| 48、既定 | 6.57 | 6.67（1.02） | 6.76（1.03） | 7.11（1.08） | 6.77（1.03） | 1.03 |
| 48、`-fno-inline` | 7.47 | 7.34（0.98） | 7.44（1.00） | 7.20（0.96） | 7.79（1.04） | 1.23 |
| 160、既定 | 30.19 | 28.63（0.95） | 28.96（0.96） | 29.96（0.99） | 30.26（1.00） | 1.03 |
| 160、`-fno-inline` | 30.15 | 30.60（1.02） | 30.96（1.03） | 31.80（1.05） | 31.48（1.04） | 1.12 |

run ごとのばらつきは、N=12 で最大 ±20%、N=48 で ±9%、N=160 で ±4% ほどである。N=160 では、並列の段階そのものは速くなる（C++ の出力が、既定の inline で 1.19 秒から 0.77 秒、`-fno-inline` の 5136 ファイルで 2.75 秒から 1.41 秒）。しかし全体の 4〜9% にすぎず、最初の出力ファイルを開く前の直列の段階が、既定の inline で約 93〜95%、`-fno-inline` で約 82〜86% を占める。生成される C++ は、設定によらず同じだった（出力ファイルが多いときに `-j` が足す `_vm_classes_*.cpp` を除く）。

## abort

5.020 の apt 版の `-j 0` で、112 回に1回、pool の abort が出た（exit 255、`%Error: Internal Error: attempted to destroy locked Thread Pool` の次に `%Error: Verilator aborted.`。出力は書き終わっていた。#74 と同じ abort である）。5.020 のソース版の `-j 0` は 10 回で 0 回だった。5.052 の複数スレッドの run（`-j 0`、`--verilate-jobs 0`、`--verilate-jobs 4`）は約 320 回で 0 回だった。回数が少なく、5.052 の頻度は言えない。

## まとめ

- 速くならないのは、この機械のせいではない。コアは4つあり、上限も SMT もない
- 5.052 で並列に動く部分は、verilate の数 % しかない。Amdahl の法則で、D1・D2 で縮められるのは最大で約 4〜6%（約 5 ミリ秒）で、測定のばらつき（数ミリ秒）に埋もれ、`-j 0` の壁時計は1スレッドと差がなかった。30 秒かかる大きさでも、最大で約 4〜9% である
- 1回の `sbt test` のビルドは 103 回で、D2 は 103 の設計で最も大きいので、verilate は合わせて多くても 103 × 約 0.15 秒 = 約 15 秒と見積もれる（測ってはいない。#126 の run で、CI の `check.sh scala` の step は 426〜701 秒）。C++ のビルドは今も `--build-jobs 0` で全コアを使う
- CI の runner（4 vCPU）が 2 コア × SMT かどうかは、まだ確かめていない（#127）。verilate には影響しないが、C++ のビルドの並列には影響しうる
