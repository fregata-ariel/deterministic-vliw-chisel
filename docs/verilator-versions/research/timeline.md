> **注意**: これは、調査エージェントが、**測定の前に**、ソースの調査結果（同じディレクトリの `<版>.json`）から作った、版をまたぐ整理である。「実測」「期待」「予測」の記述は測定前のもので、測定の結果は [VERILATOR_VERSIONS.md](../../VERILATOR_VERSIONS.md) と各版の報告にある（たとえば、ここで「実測は 5.020 のみ」とあるのは、測定前の状態）。5.050・5.052 の項目は、このあとの検証で訂正・追加があった。人が全行を確かめたものではない。

# Verilator 版別挙動カタログ(issue #85):版横断タイムライン

## 0. 読み方

- 判定欄は次の4種である。
  - 確認:レビューが confirmed とした。
  - 修正:corrected_claim を採用した。
  - 不確実:レビューが uncertain とした。
  - reviewer-added, unverified:レビューの missed 項目である。
- refuted は1件だけである。5.042 の `--trace-max-width` で、原主張は除外し、レビューの修正版のみを「修正(原主張は refuted)」として採録した。
- 実測は 5.020 のみである(B 6/3000、C 0/3000、$urandom 値、警告 0)。他の版は git オブジェクトのコード読解に基づく予測である。
- 評価対象外の 5.022 は 5.024 の範囲に、5.028 は 5.030 の範囲に含まれる。該当行には元の版名を併記した。
- 根拠は `タグ:ファイル:行` または `Changes` で示す。

## 1. カテゴリ別タイムライン

### 1.1 thread_pool_exit

| 版 | 内容 | 判定 | 根拠 |
|---|---|---|---|
| 5.020 | 静的シングルトンの `~V3ThreadPool` が `m_mutex.try_lock()` を1回だけ試す。失敗かつジョブ無しなら 'attempted to destroy locked Thread Pool' を出して `std::abort()` する。 最後の mtDisabler ブロックの `resumeOtherThreads()` は notify_all のみで待たない。起こされた idle worker が `m_mutex` を短時間保持し、そこに重なる。`bin/verilator` が SIGABRT を exit 255 にする。`-j 0` で実測 6/3000。発生の引き金はコードとアップストリームのコメントからの推論で、率は読解では確認できない。 | 確認 | `v5.020:src/V3ThreadPool.h:142-157,175-178`、`v5.020:src/Verilator.cpp:822-831`、`v5.020:src/V3ThreadPool.cpp:76-105`、`v5.020:bin/verilator:202-236` |
| 5.020 | 致命 abort は 5.020 で導入された(75a44e5a, #4672)。5.018 では try_lock 失敗時に resize(0) へ進み無害だった。 | 確認 | `Changes 5.020`(#4672)、commit 75a44e5a の `v5.018:src/V3ThreadPool.h` との差分 |
| 5.020 | `--verilate-jobs 1` では `V3MutexConfig` が無効(enable=verilateJobs>1)で `try_lock` は常に true、`resize(1)` は worker を作らない。競合は起こりえない。実測 0/3000。 | 確認 | `v5.020:src/Verilator.cpp:633-636`、`v5.020:src/V3Mutex.h:107-109`、`v5.020:src/V3ThreadPool.cpp:28-30,50-56` |
| 5.022(5.024 の範囲) | `V3ThreadPool.h` の差分は VL_NOT_FINAL のみで、終了経路は 5.020 と同じである。 | 確認 | `git diff v5.020 v5.022 -- src/V3ThreadPool.h`(5.020 レビュー index 1) |
| 5.024 | 01dadb0a(#4938)で try_lock を VL_LOCK_SPINS=50000 回の busy retry(sleep 無し)に変更した。5.026 の追加修正(#5040)が必要だったので、-j 0 ではなお稀に abort しうると推論される。「どの版でも起こらない」は過大な表現である。n=3000 では 0〜約10件は区別できず、非0でも #5040 未修正と整合する。 | 修正 | `Changes 5.024`(#4931 #4938)、`v5.024:src/V3ThreadPool.h:142-149`、`v5.024:src/V3Mutex.h:40` |
| 5.026 | #5040(d1b45b8d)で `m_shutdown=true` を先に立て、retry を 1ms sleep×最大1000回(約1s)に変更した。リトライ機構自体は 5.024 由来である。ロックが約1s取れなければ abort 経路は残るので、0/3000 は予測でありバグ除去の保証ではない。 | 修正 | `Changes 5.026`(#5040)、`v5.026:src/V3ThreadPool.h:118,148-151,168` |
| 5.030 | Thread pool rewrite(#5161, ffe76717)で、プールはヒープ確保され `main()` が `V3Global::shutdown()` で delete する。デストラクタは lock → `m_shutdown` → `notify_all` → worker join で、try_lock も abort も無い。観測された 5.020 の競合の修正を 5.030 に帰属させてはならない。5.024・5.026 で既に対処されていた。 | 修正 | `Changes 5.030`(#5161)、`v5.030:src/V3ThreadPool.cpp:31-38,51-58`、`v5.030:src/Verilator.cpp:663`、`v5.030:src/V3Global.cpp:43` |
| 5.030 | worker 数は N(旧 N-1)で、N==1 では worker 無し・`enqueue()` は同期実行になる。worker スレッド上のエラーは `::_exit(1)` で終了する。 | 確認 | `v5.030:src/V3ThreadPool.cpp:23-29`、`v5.030:src/V3Error.cpp:88-100` |
| 5.030 | 5.024・5.026 で既に緩和・修正済みなので、5.030 を「競合を直した版」と記録しない。5.024・5.026 も `-j 0` で測る価値がある。 | reviewer-added, unverified | `v5.030:Changes`(5.024 節・5.026 節)、`git log v5.020..v5.026 -- src/V3ThreadPool.h` |
| 5.032 | プール・mutex・終了経路は不変で、新規利用者は V3FuncOpt のみ(CFunc ごとに enqueue)。`--verilate-jobs 1` なら inline 実行である。修正:`-j 0` の期待は「5.020 と同程度の率」ではなく、この abort は 0 見込みである。他の `-j 0` 失敗モードは読解では除外できない。 | 修正 | `v5.032:src/V3ThreadPool.cpp:25,40-42`、`v5.032:src/Verilator.cpp:502,664`、`v5.032:src/V3FuncOpt.cpp:325-331` |
| 5.032 | 'destroy locked' 文字列は 5.030 以降のソースに存在しない(5.020 のみ)。 | reviewer-added, unverified | `git grep 'destroy locked'`(`v5.020:src/V3ThreadPool.h:155` のみ) |
| 5.034 | プール・mutex・Verilator.cpp は不変である。`V3Error.cpp` は1行(`!m_errorSuppressed`)のみ変更された。 | 確認 | `git diff --stat v5.032 v5.034`、`v5.034:src/Verilator.cpp:667` |
| 5.036 | プール・mutex・終了経路は不変である。 | 確認 | `git diff v5.034 v5.036 -- src/V3ThreadPool.*`(空) |
| 5.038 | 不変である(`V3DiagSarif::output(true)` の追加は `--diagnostics-sarif` 無しなら即 return)。期待は 5.026(#5040)で対処、5.030 で機構削除のため、両設定とも 0/3000。 | 修正 | `v5.038:src/Verilator.cpp:674-677`、`v5.038:src/V3DiagSarif.cpp:191-192`、`Changes 5.026/5.030` |
| 5.038 | abort 文字列は 5.030 以降に無い(5.036 に存在しない)。 | reviewer-added, unverified | `git grep 'attempted to destroy'` |
| 5.040 | プール・mutex は不変である。`verilate()` 末尾で netlist を解放し、`--stats` 報告を build 後へ移した。 | 確認 | `v5.040:src/Verilator.cpp:779-786,883` |
| 5.042 | 57d8bb5d(#6416)で `std::exit` を `v3Global.vlExit()` に置換した。`shutdown()`(プール delete=worker join、FileLine 解放)の後に終了する。成功経路は実質同じである。エラー時に V3Error の mutex を保持したまま join する可能性はコード読解の仮説であり、`--verilate-jobs 1` では worker が無く起こらない。 | 確認 | `v5.042:src/V3Global.cpp:56-69`、`v5.042:src/V3Error.cpp:95-110,373-378` |
| 5.042 | `bin/verilator` がシグナル死を exit 255 ではなく 128+signal(SIGABRT=134)で返す。`exit $! if $!` が先に評価されるので、判定は abort メッセージか非0終了で行う。 | 確認 | `Changes 5.042`(#6554)、`v5.042:bin/verilator:268` |
| 5.042 | b60ad953c が V3FuncOpt のマルチスレッドを無効化した。5.032〜5.040 の `-j 0` では upstream 自身が not thread safe と認める pass が worker で走っていた。 | reviewer-added, unverified | `v5.040:src/V3FuncOpt.cpp:327-333`、commit b60ad953c |
| 5.044 | プール・mutex・終了経路は不変である(`--debug-exit-uvm` 削除、`--debug-exit-elab` 追加、`--build` 失敗メッセージ変更のみ)。 | 確認 | `v5.044:src/V3ThreadPool.cpp:25`、`v5.044:src/Verilator.cpp:217,846`、`v5.044:src/V3Error.cpp:102-107` |
| 5.046 | プール・mutex・終了経路は不変である(Verilator.cpp は xml/cmake 除去等のみ)。 | 確認 | `git diff v5.044 v5.046 -- src/V3ThreadPool.* src/V3Mutex.h`(ヘッダのみ) |
| 5.048 | 不変である(const 修飾のみ)。デストラクタは worker を join し、メッセージは消滅済みである。2系統とも 0 見込みで、「5.046 から継承された競合」ではない。 | 修正 | `v5.048:src/V3ThreadPool.cpp:31-38`、commit 31757df22 |
| 5.050 | プール・mutex・終了経路は不変である。 | 確認 | `git diff --stat v5.048 v5.050 -- src/V3ThreadPool.* src/V3Mutex.h`(空) |
| 5.050 | 5.048 以降、configure が jemalloc(無ければ tcmalloc)を verilator_bin に自動リンクする。ソースビルド版同士の abort 率比較ではアロケータを揃える。Debian 5.020 パッケージには無い交絡要因である。 | reviewer-added, unverified | `Changes 5.048`(#7250)、`Changes 5.050`(#7736)、`v5.050:configure.ac:97-115` |
| 5.052 | プール・mutex・終了経路は不変である。 | 確認 | `git diff --quiet v5.050 v5.052 -- src/V3ThreadPool.*`(rc=0)、`v5.052:src/Verilator.cpp:720,926` |
| 5.052 | mtask 粗大化・ハザード修正(#8120 #8133)は `--threads` モデルのみで、`-j`/`--verilate-jobs` と無関係である。 | reviewer-added, unverified | `Changes 5.052`(#8120 #8133) |

### 1.2 jobs_options

| 版 | 内容 | 判定 | 根拠 |
|---|---|---|---|
| 5.020 | `--build-jobs`/`--verilate-jobs` は常に自分の値を設定し、0 は `hardware_concurrency()` になる。`-j [N]` は未設定(-1)の値だけ埋めるので、明示指定が引数順に依らず優先される。素の `-j` は両方 0 になる。未設定は 1。make `-j N` は MAKEFLAGS に `-jobserver-auth` が無いときだけ付く。svsim 既定 `-j 0` は make -j 4 + worker 3 + mutex 有効、VliwChiselSim は make -j 4 + 単一スレッド + mutex 無効である。`hardware_concurrency` はオンライン CPU 数で cgroup クォータではない。 | 確認 | `v5.020:src/V3Options.cpp:1095-1105,1503-1513,1635-1645,1677-1678`、`v5.020:src/Verilator.cpp:731-748` |
| 5.024 | 不変である。svsim のフラグは有効である。 | 確認 | `v5.024:src/V3Options.cpp:1127-1137,1557-1567,1700-1710,1742-1743` |
| 5.026 | 不変である。 | 確認 | `v5.026:src/V3Options.cpp:1148-1158,1580-1590,1723-1733,1765-1766` |
| 5.030 | 意味は不変である。階層 Verilation での `-j`(引数無し)の子実行ストリップのみ修正された。 | 確認 | `Changes 5.030`(#5514)、`v5.030:src/V3Options.cpp:552,1173-1183,1614-1624,1758-1767` |
| 5.032 | 意味は不変である。`stripOptionsForChildRun` が `-F` もストリップする(階層子実行のみ)。 | 確認 | `v5.032:src/V3Options.cpp:1185-1195,1652-1662,1796-1806`、`Changes 5.032`(#5199 #5669) |
| 5.034 | 既定値1の設定が `parseOptsList()` 末尾から `notify()` に移った(d4f4fefd, #5749)。`-f`/`-F` の後ろの `-j` が有効になる。svsim は jobs を `-F` より前に置くので影響しない。意味は不変である。 | 確認 | `Changes 5.034`(#5749)、`v5.034:src/V3Options.cpp:965-966,1835-1836` |
| 5.036 | `--output-groups` の既定が `--build-jobs`(無ければ `-j`)の値になった。5.034 までは 0 で無効だった。svsim の `--build-jobs 0` は N=nproc(4)でグルーピングが有効になる。ただし `MIN_FILES_COUNT=16`、`MIN_FILES_PER_BUCKET=4` を満たす場合のみで、小設計は不変である。グループファイルは `<prefix>_vm_classes_<n>.cpp`、`_Slow_<n>.cpp`。生成ファイル一覧がビルド機の CPU 数に依存する(再現性・ccache に影響)。`--output-groups 0` で 5.034 相当に戻る。 | 確認 | `Changes 5.036` Important(#5751)、`v5.036:src/V3Options.h:327`、`v5.036:src/V3Options.cpp:963,1202-1203,1853`、`v5.036:src/V3EmitMk.cpp:62,66,185-191,327,524-526` |
| 5.038 | 解析・既定は不変である。 | 確認 | `v5.038:src/V3Options.cpp:1204-1214,1716-1726,1864-1877,970` |
| 5.040 | 不変である。追加は `-fno-dfg-break-cycles`、`-fdfg-synthesize-all` のみ。 | 確認 | `v5.040:src/V3Options.cpp:1206,1719,1870` |
| 5.042 | 0 の意味が `VlOs::getProcessDefaultParallelism()`(affinity mask の CPU 数、読めなければ `hardware_concurrency`)に変わった(ffbb3229, #6604)。同値が実行時 `m_threads` の既定にもなる。`addModel()` で VlThreadPool を作る挙動自体は 5.020 から不変である。制限なしの4CPU箱では値は変わらない。taskset・cpuset 下では減る。CFS クォータには無効である。 | 修正 | `Changes 5.042`(#6604)、`v5.042:src/V3Options.cpp:1296,1812,1963`、`v5.042:include/verilatedos_c.h:110-133`、`v5.042:include/verilated.h:445` |
| 5.044 | `-j` の解析を汎用ループ内の特例に書き換えたが、意味は不変である。追加は `--quiet-build`(make に `-s --no-print-directory`)のみである。 | 確認 | `v5.044:src/V3Options.cpp:1313,1845,1977,1077-1079`、`v5.044:src/Verilator.cpp:822` |
| 5.046 | 不変である。 | 確認 | `v5.046:src/V3Options.cpp:1311,1848,1968` |
| 5.046 | Changes の「`--max-replication`(#7139)」は実装上は `--replication-limit`(既定 8192、従来の固定値と同じ)で、WIDTHCONCAT のしきい値は不変である。 | reviewer-added, unverified | `v5.046:src/V3Options.cpp:1703`、`v5.046:src/V3Options.h:341` |
| 5.048 | 不変である。 | 確認 | `v5.048:src/V3Options.cpp:1305-1315,1850-1860,1969-1978,1070-1071` |
| 5.048 | svsim の `--build-jobs 0` で `--output-groups` が nproc になり V3EmitMk のグルーピングが走る。716b40425(#7154)で bucket 割当のゼロ除算ガードが入った。 | reviewer-added, unverified | `v5.048:src/V3Options.cpp:1069`、`V3EmitMk.cpp` assignBuckets |
| 5.050 | 不変である(追加は `-assert-unroll-limit` 等のみ)。 | 確認 | `git diff v5.048 v5.050 -- src/V3Options.*` |
| 5.052 | 不変である。`-assert-unroll-limit` は deprecated になった。 | 確認 | `v5.052:src/V3Options.cpp:1325,1885,1306-1307` |

### 1.3 seed_random

| 版 | 内容 | 判定 | 根拠 |
|---|---|---|---|
| 5.020 | `$urandom`(seed 無し)は `VL_RANDOM_I()` で、`vl_rand64()` の下位32bit(thread_local Xoroshiro128+)である。`randSeedDefault64()` は両 state に `(N<<32)^N` を入れる。低32bitの popcount<10 なら反転する。seed 1 は `~0x0000000100000001`、両語が等しい。このため先頭出力は全1に近い(fffffffc ff7fffff f77f7fff 8084023f aef7a536 …)。seed 2 は fffffffa feffffff eeff3fff f1080460。測定値の4語は生成順の逆に並ぶ。独立再実装で再現済みである。 | 確認 | `v5.020:include/verilated.cpp:294-302,347-364,2791-2793`、`v5.020:include/verilated_funcs.h:85,685`、`v5.020:include/verilated_types.h:302` |
| 5.020 | seed 無しでは `m_randSeed=0` で `vl_sys_rand32()=(lrand48()<<16)^lrand48()`、`srand48` は誰も呼ばない。値は毎回同一である(797673c4 0b1e5d9c 9efdd502 48c2e0e4 …、測定値はその逆順)。`+verilator+seed+0` は拒否される(最小1)。docs の「zero or not specified picks from system RNG」は実挙動と不一致である。`lrand48` の評価順は C++ で未規定、結果は glibc 依存である。 | 確認 | `v5.020:include/verilated.h:368`、`v5.020:include/verilated.cpp:334-345,2725-2727,2794-2797`、`v5.020:docs/guide/exe_sim.rst:93-97` |
| 5.020 | `--x-initial unique`(既定)で `VL_RAND_RESET_*` が使われるが、`randReset` 既定 0 なので乱数を消費しない。RNG 消費者は firtool の `RANDOM=$urandom` と利用者の `$urandom` のみである。`+verilator+rand+reset+2` なら構築時に乱数を引き、ストリームがずれる。初期ブロック実行順に依存する。 | 確認 | `v5.020:docs/guide/exe_verilator.rst:1641`、`v5.020:include/verilated.h:367`、`v5.020:include/verilated.cpp:389-397,2723-2724` |
| 5.024 | RNG・seed 処理は不変である。5.020 と同値で、初期ブロックの順序も V3Order 改修では変わらない(V3Sched は `orderSequentially`)。 | 確認 | `v5.024:include/verilated.cpp:2784-2786,2850,400-401`、`git diff v5.020 v5.024`(rand/seed/rng 無し) |
| 5.026 | RNG・seed は不変である。`$readmem` が z を許容し、x/z 桁を `VL_RAND_RESET_I(hex?4:1)` で処理する。`@address` 内の x は 5.024 でも致命エラーで、新規は z と明確なメッセージである。`randReset==0` ではストリームに触れない。 | 修正 | `Changes 5.026`(#5070 #5078)、`v5.026:include/verilated.cpp:2134,2811,2877-2882` |
| 5.030 | RNG・seed は 5.020 と md5 一致である。`--x-assign unique` は常に `VL_RANDOM` を引くが(5.028 #5214)、svsim は使わない(既定 fast)。 | 確認 | `Changes 5.028`(#5214)、`v5.030:include/verilated.cpp:353-380,2856-2858,2922-2927`、`v5.030:src/V3AstNodeExpr.h:1802` |
| 5.032 | RNG・seed は不変である。`randSeed` の doc コメント取り違えの修正のみである。 | 確認 | `v5.032:include/verilated.cpp:2856`、`v5.032:include/verilated_types.h:255`、commit c98744b9 |
| 5.034 | RNG 実行時は不変である(5.020 と同一)。`AstRand::isPure()` は seed 付き・Reset のみ impure になる(dfe28f7e, #5703)。seed 無し `$urandom` は従来どおり pure である。 | 確認 | `Changes 5.034`(#5703)、`v5.034:src/V3AstNodeExpr.h:1832`、`v5.034:src/V3Unknown.cpp:362`、`v5.034:include/verilated.cpp:2856,2922-2928` |
| 5.036 | RNG は不変である。automatic 関数ローカルの毎回ゼロ化(#5747)は RNG を消費しない。「5.020 から不変」は VL_RAND_RESET_ASSIGN_*(5.028)等の追加を除いた意味で成立する。 | 修正 | `v5.036:include/verilated.cpp:2877,2943-2949`、`Changes 5.036` |
| 5.038 | `--x-initial`/`--x-assign unique` の乱数が変数別 VlRNG(`randSeed()^hash(instance)^hash(var)`)に変わった(#6018 ほか)。 `VL_SCOPED_RAND_RESET_*` は `randReset==0` なら 0 を返す。これが効くのは `+verilator+rand+reset+2` または `--x-assign unique` の場合のみである。`+rand+reset+1` では旧新とも RNG を使わない(原主張の「(or 1)」は誤り)。svsim は不使用である。`$urandom` ストリームは不変で、RNG ブロックは 5.020 から byte 一致である。 | 修正 | `Changes 5.038`(#2662 #5958 #6018 #6025 #6075)、`v5.038:include/verilated.cpp:2964`、`v5.038:include/verilated_types.h:294`、`v5.038:include/verilated.h:412` |
| 5.040 | RNG・seed は不変で、VlRNG 全体が 5.020 と byte 一致である。新規は `VlStdRandomizer`(std::randomize)のみ。設計毎の値は初期ブロック実行順に依存し、5.040 の Inline/Sched/NBA リファクタで入れ替わりうる(厳密一致が言えるのは単一 initial 内の連続 `$urandom` のみ)。 | 修正 | `v5.040:include/verilated.cpp:3027-3033,2961`、`v5.040:include/verilated_random.h`、commit 48a12fb0 |
| 5.042 | RNG・seed は不変である。`std::process::get_randstate` のラッパのみ変更された(#6550)。 | 確認 | `v5.042:include/verilated.cpp:382-398,3005,3071-3077`、commit 94525cae |
| 5.044 | RNG・seed は不変で、5.020 と byte 一致である。Verilator 生成 temp のゼロ初期化(94d0513b, #6611)は `randReset` 0 なら実質変化無しである。`+rand+reset+1` では temp が全1から 0 に変わり、`+2` では乱数から 0 に変わる。 | 修正 | `Changes 5.044`(#6611)、`v5.044:src/V3EmitCFunc.cpp:673`、`v5.044:include/verilated.cpp:378,391-396,425-430,3005,3071-3077` |
| 5.046 | RNG が Xoroshiro128+ から Xoroshiro128** + SplitMix64 シードに変更された(c1e343d9, #6956)。seed 有無によらず全値が変わる。seed 無しは `lrand48` 経由で依然決定的である。seed 1/2 の全1偏りは消える。seed 範囲 1..INT_MAX と 0 拒否は不変である。 | 確認 | `Changes 5.046`(#6945 #6956)、`v5.046:include/verilated.cpp:323,339,345,374-375,3157` |
| 5.046 | `$get_initial_random_seed()` が追加された(`randSeed()` を返す)。 | 確認 | `Changes 5.046`(#7056 #7069)、`v5.046:src/V3EmitCFunc.h:1370` |
| 5.046 | `vl_stop()` が `gotFinish()` 済みなら即 return する(→ 1.8 semantics)。 | 確認 | `v5.046:include/verilated.cpp:161-163` |
| 5.048 | `vl_rand64` が `vl_current_rng_rand64()` 経由になった(6ba45d33, #7408)。プロセス(fork・timing・process::srandom)が無ければ従来のスレッド RNG を使い、アルゴリズムと値は 5.046 と同じである。 | 修正 | `Changes 5.048`(#7408 #7415)、`v5.048:include/verilated_types.h:218`、`v5.048:include/verilated.cpp:336-339` |
| 5.048 | seed の最小 1 と 0 拒否、seed 無しは未種 `lrand48` で毎回同一である(変更なし)。seed1/2 は 5.046 と同値で 5.020 と異なる。 | 修正 | `v5.048:include/verilated.cpp:3263-3265,3328-3333,441-465` |
| 5.050 | 既定 seed が 1 になった(0e423a4b, #7516)。seed 無しは seed 1 と同一出力になる。`+verilator+seed+0` は受理され、実行毎に時計から seed を選ぶ(再現不能)。x-initial の golden も変更された(`randSeed()^scopeHash^salt`)。 | 確認 | `Changes 5.050`(#7325 #7516)、`v5.050:include/verilated.h:416`、`v5.050:include/verilated.cpp:3539,3595,3602,3677-3682` |
| 5.050 | 5.048 → 5.050 で RNG 経路の変更は無い。seed 指定値は 5.048 と同じで、5.020 とは異なる。 | 確認 | `git diff v5.048 v5.050 -- include/verilated.cpp`(vl_rng_* 無し) |
| 5.052 | RNG・seed 経路は不変である。ランダム初期化の最適化(1106e89c)は値を変えず、`VL_RAND_RESET_Q/W` が削除された。seed 引数の R/W 参照修正(#8112)は seed 変数を渡す場合のみ有効である。 | 確認 | `v5.052:include/verilated.cpp:425-548,3621-3629,3703`、`v5.052:include/verilated.h:528`、commit 1106e89c、621a67e8 |
| 5.052 | `VlProcess` デストラクタが thread-local の current process を戻す(`VlRNG::VlRNG()` の use-after-free 修正 #7865)。svsim は `--timing` 無しで影響しない。 | reviewer-added, unverified | commit 58bd13b62、`v5.052:include/verilated_types.h:356` |

### 1.4 svsim_compat

| 版 | 内容 | 判定 | 根拠 |
|---|---|---|---|
| 5.020 | svsim が使うオプションは削除・改名されていない(削除は debug `-O<letter>`、`--prof-threads` 等のみ)。C++14 が必須で `-std=c++17` を満たす。`-CFLAGS`(`VM_USER_CFLAGS`)は `CFG_CXXFLAGS_STD` より前に付くが、g++13 では後者が空なので svsim の `-std=c++17` が有効である。`eval_step()` は `didInit=true` を init 前に立て、再入ガードは無い(svsim の `always @(simulationState)` → DPI の入れ子 eval が成立する)。 | 修正 | `Changes 5.020`(#4663 #4784 #4786)、`v5.020:src/V3EmitCModel.cpp:381-411,432-445`、`v5.020:include/verilated.mk.in:120,165`、`v5.020:src/V3Options.cpp:1041-1459` |
| 5.020 | svsim は条件付きで `-Wno-fatal`、`-Wno-<w>`、`-O1/-O3 --x-assign fast --x-initial fast`、`-MAKEFLAGS -j N`、`--output-split*`、`--trace*`、`--coverage*`、`--timing/--no-timing` も出す。すべて 5.020 に存在する(現行コマンドラインには無い)。 | reviewer-added, unverified | svsim `Backend.class`、`v5.020:src/V3Options.cpp` |
| 5.024 | 使用オプションに削除・改名は無い。`trace()` が `VerilatedTraceBaseC*`、`write()` は引数無し版が維持され、`using namespace std::literals` が追加された。`verilatedos.h` が `__cplusplus>=201402L` を厳格に要求する。 | 確認 | `v5.024:include/verilated.h:76,461,471,553,578`、`v5.024:include/verilated_cov.h:97`、`v5.024:include/verilatedos.h:269`、`v5.024:src/V3EmitCModel.cpp:456` |
| 5.024 | `bin/verilator` は VERILATOR_ROOT が `realpath($RealBin/..)` と異なると `exit 1` する(#4927)。 | 確認 | `Changes 5.024`(#4927)、`v5.024:bin/verilator:72-82` |
| 5.024 | 書式出力が `VL_*_NX(const std::string& format, argc, ...)` に変わり、printf ごとに一時 std::string が作られる。 svdpi.h は追加のみ(svGetTime 等)である。1800-2023 モードで `+/-`、`+%-` 演算子トークンが追加された。 | reviewer-added, unverified | commit 9ccef4180、`v5.024:src/V3EmitCFunc.cpp:190-194`、`v5.024:src/verilog.l:898-902` |
| 5.026 | `addModel` が time 非0 で致命エラーになる(#5167)。svsim は時刻 0 で生成するので無影響である。公開 API の削除・改名は無い。追加は `solverProgram()`、`traceCapable` 等で、-std=c++17 で足りる。 | 修正 | `Changes 5.026`(#5167 #5053)、`v5.026:include/verilated.cpp:2664-2674`、`simulation-driver.cpp:1003-1011` |
| 5.026 | `VL_CVT_N_CSTR`(DPI import の null C 文字列, #5179)と `-F` の `m_lineArgs` 記録(階層のみ)が入った。 | reviewer-added, unverified | `Changes 5.026`(#5179 #5114)、`v5.026:include/verilated_funcs.h` |
| 5.030 | `assertOn` が `std::atomic<uint32_t>` ビットマスクになった。`assertOn(bool)`/`assertOn()` は維持される。`VL_VALUE_STRING_MAX_CHARS`、`lockCheckStopRequest` は削除された(ドライバ不使用)。C++14 下限は不変である。 | 確認 | `v5.030:include/verilated.h:385-386`、`v5.030:configure.ac:589-591`、`v5.030:src/V3EmitCModel.cpp:211-213` |
| 5.032 | ドライバの API は不変である。`VerilatedScope::configure` のシグネチャ変更は生成 `__Syms` のみが呼ぶ。識別子検証は `--prefix` 等のみに適用される。 | 確認 | `v5.032:include/verilated.h`、`v5.032:src/V3EmitCModel.cpp:186-212`、`v5.032:include/verilatedos.h:281-284` |
| 5.032 | deprecations.rst は「C++20 を要求するのは 2025年5月以降」と予告している。5.032 自体は C++14 を許す。以降の版で svsim 固定の `-std=c++17` が壊れうる。 | reviewer-added, unverified | `v5.032:docs/guide/deprecations.rst`、`v5.032:include/verilatedos.h:281-284` |
| 5.034 | `+incdir+` が '+' で複数ディレクトリに分割される(svsim は `includeDirs` 設定時のみ出力)。`dpi context` の ScopeName 重複修正(#5788)。 `varInsert` のシグネチャ変更は生成コードのみである。 | 確認 | `Changes 5.034`(+incdir, #5788)、`v5.034:src/V3Options.cpp:1135`、`v5.034:src/V3LinkResolve.cpp:224` |
| 5.034 | V3EmitCSyms の DPI スコープ登録の作り直し(m_dpiScopeNames)。DPI スコープ失敗は起動時エラーとして即座に表面化する。 | reviewer-added, unverified | `git diff v5.032 v5.034 -- src/V3EmitCSyms.cpp` |
| 5.036 | `--trace` は deprecated 表記だが警告なしで受理される(VCD)。ランタイムヘッダは追加のみで、ドライバが使う API は不変である。 | 修正 | `Changes 5.036`(--trace-vcd)、`v5.036:src/V3Options.cpp:1643`、`v5.036:include/verilated.h` |
| 5.038 | `--assert` が既定になった(svsim は明示指定)。ドライバの API は不変で、`VL_RAND_RESET_ASSIGN_*` 削除は生成コードのみである。 | 確認 | `Changes 5.038` Important(f77af4e6)、`v5.038:src/V3Options.cpp:1137-1663`、`v5.038:src/V3EmitCModel.cpp:211-213` |
| 5.040 | `verilated.mk` が `-DVERILATOR=1` を常時付与する(ドライバは `#ifdef VERILATOR` 不使用)。API 不変である。ランタイムの致命エラーが `exit(1)` になる(→ 1.8)。 | 確認 | `Changes 5.040`(833c31b0)、`v5.040:include/verilated.mk.in:87`、`v5.040:include/verilated.cpp:172-176` |
| 5.040 | VPI/coverage の toggle カウンタは別系統になった(svsim の coverage 無効時は無関係)。`VlOs::memUsageBytes` のシグネチャが変更され、`statsPrintSummary` は `--main` の main からのみ呼ばれる。 | reviewer-added, unverified | `Changes 5.040`(#6086)、`v5.040:include/verilated_cov.h` |
| 5.042 | ドライバの API は不変である。`roundUpToMultipleOf` が `vlstd` へ移動し、`addModel` の致命メッセージの文言が変わった。 | 確認 | `v5.042:include/verilatedos.h:284,734`、`v5.042:src/V3EmitCModel.cpp:186-212` |
| 5.042 | 複数の trace 形式の同時指定はエラーになる。 `--trace-max-width`/`--trace-max-array` を svsim が出しうる。 | reviewer-added, unverified | `v5.042:src/V3Options.cpp:978-984` |
| 5.044 | `VerilatedModule` 削除、`VerilatedScope` はコンストラクタ化、`varInsert` の `finalize` 引数削除、`pushPrefix` は const char* になった。ドライバが使う API はすべて存続する。 | 確認 | `v5.044:include/verilated.h:713`、`v5.044:include/verilated_vcd_c.h:137`、`v5.044:src/V3EmitCModel.cpp:185-206`、commit 2fabf508 |
| 5.044 | V3EmitCSyms.cpp(約1165行変更)と `VerilatedSyms::name()` の純粋仮想追加は、svsim の `svGetScope/svSetScope` を支える箇所で最も壊れうる。実走が唯一の確認手段である。 | reviewer-added, unverified | `git diff v5.042 v5.044 --stat`、commit 3b7ddce20 |
| 5.046 | `--xml-only`、`--xml-output`、`--make cmake` が削除された(svsim 不使用)。`--top-module` は `AstNode::encodeName()` を通る(`svsimTestbench` は不変)。VLVF_* が再採番され、`useNumaAssign()` 等が追加された。ドライバの API は不変である。 | 確認 | `Changes 5.046`、`v5.046:src/V3Options.cpp:1800`、`v5.046:include/verilated.h`、`v5.046:src/V3EmitCModel.cpp:468-469` |
| 5.046 | `__Vm_didInit=true` が init/settle の後に移された(bc3c5b32d)。 `always @(simulationState)` は act ループで didInit 後に走るという静的解析では、入れ子 `eval_step` が init を再実行しない。実走で未確認である。`--timing` 時 `eventsPending()` は `gotFinish` 後 false になる(#7095)。 | reviewer-added, unverified | commit bc3c5b32d、702d6ede0、`v5.046:src/V3EmitCModel.cpp` |
| 5.048 | `--trace-threads`/`--trace-fst-thread` は deprecated で効果無しになり、DEPRECATED 警告(既定で致命)が出る。svsim の `TraceKind.Fst(traceThreads=Some(n))` が該当し、既定の非 trace フローには影響しない。 | 確認 | `Changes 5.048`(#7443)、`v5.048:src/V3Options.cpp:1825-1827`、svsim `TraceKind$Fst` バイトコード |
| 5.048 | `final()` が `executingFinal` を設定する(#7249)。PCH の `-c -o $@` から `-o` が外れた(#7327)。ドライバの API は不変である。 | 確認 | `v5.048:src/V3EmitCModel.cpp:492-494`、`v5.048:include/verilated.cpp:164-167`、`v5.048:include/verilated.mk.in:283,286` |
| 5.048 | `--coverage` が FSM coverage も有効にする。FSM 検出は enum 型の state 変数か属性付きのみ対象で、firtool 出力は enum を出さないので影響しない見込みである。 | 修正 | `Changes 5.048`(#7412)、`v5.048:src/V3Options.h:451`、`v5.048:src/V3FsmDetect.cpp:441-444` |
| 5.050 | `WData`、`WDataInP`、`WDataOutP` の typedef が削除され、opaque クラスになった(#7642)。ドライバは不使用である。他の API は不変で、-std=c++17 で足りる。 | 確認 | commit c99aa8ed、`v5.050:include/verilated_types.h:149`、`simulation-driver.cpp` |
| 5.050 | `assertOnGet` が `assertCtlGet()` に再実装され、`$assertcontrol` が拡張された。既定は全有効なので意味は不変である。 | reviewer-added, unverified | `git diff v5.048 v5.050 -- include/verilated.cpp/.h`、`Changes 5.050`(#7788 #7807) |
| 5.052 | eval ループが生成コードからランタイムの `VerilatedEvalLoop` に移った(8546d5db)。 `eval`/`eval_step`/`final`/`eventsPending`/`nextTimeSlot` は後方互換である。順序(static → initial → settle の後に didInit)は 5.050 と同じである。 | 確認 | commit 8546d5db、`v5.052:include/verilated.cpp:4055-4078`、`v5.052:src/V3EmitCModel.cpp:190-216` |
| 5.052 | `--assert-unroll-limit` は deprecated になった(svsim 不使用)。 | 確認 | `Changes 5.052`(#8096)、`v5.052:src/V3Options.cpp:1306-1307` |
| 5.052 | VPI シンボル登録が静的テーブル化(#7936)。DPI 公開 API 自体は不変である。`dpi_c_decl` pragma は opt-in である。 | reviewer-added, unverified | commit 25377b20a、646dcd383 |

### 1.5 warnings_lint

| 版 | 内容 | 判定 | 根拠 |
|---|---|---|---|
| 5.020 | スタイル系と IMPERFECTSCH/I_CELLDEFINE 以外のすべての警告コードが既定オン(lint 系・MULTIDRIVEN・UNOPTFLAT・SIDEEFFECT を含む)で、警告は既定で致命である。svsim は `-Wno-fatal` を付けない。5.020 自体は新規コード無しで、実測は 53 ビルド全て警告 0。svsim の `-Wno-fatal` は設定次第の任意オプションである。 | 確認 | `v5.020:src/V3Error.h:220-256,327`、`v5.020:src/V3FileLine.cpp:106-110`、`v5.020:src/V3Error.cpp:271-280`、`Changes 5.018` |
| 5.024 | 新規コード:UNUSEDLOOP(スタイル、既定オフ)、PREPROCZERO(`ifdef (expr)` のみ)、ZEROREPL(旧エラー、pretendError)。PROCASSWIRE が出力・inout 引数に拡張された。firtool/svsim で該当構文は無い。 | 確認 | `Changes 5.024`(#4926 #4966)、`Changes 5.022`(#4753 #4762)、`v5.024:src/V3Undriven.cpp:397` |
| 5.026 | ゼロ幅 select が硬エラーになった(#5028)。新規コード NONSTD(既定オン)は `$psprintf` のみで発火する。 | 確認 | `Changes 5.026`、`v5.026:src/V3Width.cpp:928`、`v5.026:src/V3Error.h:124`、`v5.026:src/verilog.l:254` |
| 5.030 | 新規コード無しである。 `hardError()` コード(E_UNSUPPORTED 等)は lint_off で抑制不能になった。インスタンスの括弧省略、スコープ外 genvar、空 case はエラー。 未サイズ数値の >32bit エラーを撤廃した。 | 確認 | `Changes 5.028`(#5351 #5363)、`Changes 5.030`、`v5.030:src/V3Error.h:227-229`、`v5.030:src/V3LinkCells.cpp:517`、`v5.030:src/V3LinkResolve.cpp:113`、`v5.030:src/V3Number.cpp:368`、commit 798bbe98 |
| 5.030 | 607c19a67 で coverage/trace 文が impure になり SIDEEFFECT が増えうる(`--coverage`/`--trace` 時のみ)。WIDTH 系は削減された(size cast 下 #3417、`<<` #5284)。静的変数初期化子が自動変数依存ならエラー(#5296)である。 | reviewer-added, unverified | commit 607c19a67、02e88e384、cb5d03ff0、99c619a4c |
| 5.032 | 新規コード無し。新規エラーは enum 基底型、`wait`、パラメータ代入、`--prefix`、`--savable --timing`、global constraint 警告 など firtool 非対応構文である。UNDRIVEN/BLKANDNBLK の ref 誤検出を除去した。 | 確認 | `v5.032:src/V3Error.h`、`Changes 5.032`、commit a72009fb |
| 5.032 | `module automatic` 修正(192236a83):モジュール変数は常に STATIC。 | reviewer-added, unverified | `git diff -w v5.030 v5.032 -- src/V3LinkParse.cpp` |
| 5.034 | 新規 COVERIGN(既定オン)は、未対応の covergroup/coverpoint/`cover sequence` 構文で E_UNSUPPORTED の代わりに出る。`cover property` は従来どおり AstCover になる。SYMRSVDWORD は裸の list/map/deque/set/vector で出なくなった(`nullptr` は引き続き出る)。BLKSEQ はスタイル(既定オフ)。 | 修正 | `Changes 5.034`、`v5.034:src/verilog.y:6188,6192`、`v5.034:src/V3FileLine.h` |
| 5.034 | 「タスクを関数として呼ぶ」検査が V3LinkDot から V3Width に移動した。 | reviewer-added, unverified | commit 78d6ec811 |
| 5.036 | 新規 DEFOVERRIDE(既定オン)。コマンドラインで定義済みのマクロをソースの `` `define `` が再定義すると、値が同じでも発火し、コマンドライン値が優先される(5.034 は後勝ちで異値のみ REDEFMACRO)。svsim の `+define+` マクロへの無ガード同値 define のみが新規リスクである。 `--xml-only/--xml-output` は DEPRECATED。 | 確認 | `Changes 5.036`(#5900 #5908)、`v5.036:src/V3Error.h:93`、`v5.036:src/V3PreProc.cpp:354` |
| 5.036 | 'For warning description see…' ヒントが警告コード毎に1回出る;EC_FATALEXIT → EC_FATALMANY;オプション値エラーは v3error 化された。 | reviewer-added, unverified | `v5.034..v5.036 -- src/V3Error.cpp/.h, V3Options.cpp` |
| 5.038 | PROCASSINIT(スタイル、既定オフ)。BADVLTPRAGMA と MODMISSING は pretendError(従来エラーだったものの命名)で、チェックは厳格化されない。集約型の比較(`==`、`<` 等)に硬エラー 'Comparison requires matching data types' が追加された。 | 確認 | `Changes 5.038`(#2481 #5945 #6054 #5570 #5950)、`v5.038:src/V3Error.h`、`v5.038:src/V3Width.cpp:6695` |
| 5.040 | 既定オンの新規警告:ALWNEVER(読む変数無しの `always @*` を削除)、NOEFFECT(変数無し `foreach`)、SPECIFYIGN(specify/`$sdf_annotate`)。pretendError:ENUMITEMWIDTH、PARAMNODEFAULT、PROTOTYPEMIS。net が 4-state integral でないと硬エラー、非 ANSI 宣言矛盾エラーも追加された。svsim の `always @(simulationState)` は ALWNEVER 対象外である。 | 確認 | `Changes 5.040`、`v5.040:src/V3Error.h:252-254`、`v5.040:src/V3WidthCommit.cpp:183`、`v5.040:src/V3Width.cpp:2543` |
| 5.040 | さらに IEEE 厳格化がある:ASSIGNIN(入力への代入)、force/release 非定数 select、gate 原始のポート幅、genvar、CVTREAL 等。ハンドライトの BlackBox 以外では無縁の見込みである。 | reviewer-added, unverified | `Changes 5.040` Other、commit 409e036ec ほか |
| 5.042 | IMPLICITSTATIC が always/タスク/関数内の初期化子付き無修飾変数に拡張された(initial/final は除外)。NORETURN(非 void 関数が戻り値を代入しない)と ASSIGNEQEXPR(式中 `=`)は新規でオンである。FUNCTIMECTL と HIERPARAM は pretendError。svsim の関数はすべて `function void`、firtool の automatic ローカルは該当しない見込み。 | 確認 | `Changes 5.042`(#6534 #5567 #6385 #6456)、`v5.042:src/V3LinkParse.cpp:~269-294`、`v5.042:src/V3Width.cpp:6329-6334`、`v5.042:src/verilog.y:4844-4847`、`v5.042:src/V3Error.h:255-271` |
| 5.042 | モジュールスコープの `automatic` はエラーになった(procedure/タスク/関数/クラス内は可)。`--clk`、clocker 属性、`--make cmake` 等が DEPRECATED になった。 | reviewer-added, unverified | commit 4e866fd71、`Changes 5.042`(#6443 #6463 #6540) |
| 5.044 | 新規コードは SUPERNFIRST(`super.new` の位置エラー)のみである。UNUSED が実コードになった。警告制御(CODE/CTRL 分離、#6876)は書き換えられたが、svsim は `-W` 無しである。`defaultsOff()`、`styleError()` は不変。 | 確認 | `Changes 5.044`(#6784 #6876)、`v5.044:src/V3Error.h:162,241,267`、`v5.044:src/V3FileLine.h` |
| 5.044 | ソースの `lint_on` メタコメントが制御ファイルで無効化された警告を再有効化しなくなった(f990dd747)。 | reviewer-added, unverified | commit f990dd747、4080284e5 |
| 5.046 | IMPLICITSTATIC は initial/final 内の初期化子付き無修飾変数にも拡張された(5a236dd3。Changes の名称 INITIALSTATIC は誤記)。致命になるが、firtool の automatic ローカルと for ヘッダ変数は対象外である。 | 確認 | `Changes 5.046`(#7020)、`v5.046:src/V3LinkParse.cpp:345-349`、`v5.046:src/V3Error.h:243-245` |
| 5.046 | 2-state と 4-state の unpacked 配列代入に硬エラーが追加された(#6895)。MULTIDRIVEN がタスク呼び出し経由の書き込みを追う(新 V3UndrivenCapture)。 | 確認 | `Changes 5.046`(#6895 #6858)、`v5.046:src/V3Width.cpp:5816`、commit c388e793 |
| 5.046 | INSIDETRUE(`inside` が常に真、既定オン)、UNSATCONSTR(`randomize()` 実行時)、自動変数を連続代入 LHS にするとエラー、tagged union の早期 E_UNSUPPORTED が追加された。 | reviewer-added, unverified | `v5.046:src/V3Error.h`、commit 54b130e43 |
| 5.048 | unpacked 配列の代入互換性の硬エラー(次元数・サイズ・要素型)が追加された。`--structs-packed`、`-fdfg-pre-inline`/`post-inline`/`scoped` は DEPRECATED。新規コードは FSMMULTI、FUTURE で、`--coverage-fsm`/`--fourstate` 時のみ(COVERIGN は既存)。 | 修正 | `Changes 5.048`(#7052 #7222 #7394 #7412)、`v5.048:src/V3Width.cpp:8438,8475`、`v5.048:src/V3Options.cpp:1741-1744,1460-1471` |
| 5.048 | DFG パイプライン再構成(1回のみ、scope 後)で UNOPTFLAT などの順序依存警告が変わりうる。定数 `%c` に 8bit 超を与えると WIDTHTRUNC(既定オン)が出る。 | reviewer-added, unverified | `Changes 5.048`(#7362 #7394)、`v5.048:src/V3Number.cpp:709-710` |
| 5.050 | E_CONTASSINIT(抑制不能の硬エラー):初期値を持つ変数への連続代入。NOTREDOP(`!` の直後に縮約演算子、抑制可能)。MULTIDRIVEN が `always_ff` と他プロセスの書込みにも拡大された。svsim の `reg … = '0` は手続き書込みのみ、firtool は `always @(posedge …)` を出すので該当しない見込みである。 | 確認 | `Changes 5.050`(#7352 #7357 #7417 #7623 #7351 #7621)、`v5.050:src/V3Undriven.cpp:231-233,573`、`v5.050:src/V3ParseImp.cpp:914-917` |
| 5.050 | IEEEMAYDEPRECATE(手続き `assign`/`deassign`、既定オン)。V3Case 再構成で CASEINCOMPLETE/CASEOVERLAP の発火条件が変わった。 | reviewer-added, unverified | `v5.050:src/verilog.y:3600,3603`、`v5.050:src/V3Case.cpp` |
| 5.052 | MULTIDRIVENPROC と SIMILARNAME はスタイル(既定オフ)である。NEVERMATCH(既定オン)は SVA intersect/within の恒偽のみ。MULTIDRIVEN は clocking-block 出力にも拡大された。静的 virtual 関数と配列タスク引数へのスカラ渡しはエラーになった。 | 確認 | `Changes 5.052`、`v5.052:src/V3Error.h:300-305`、`v5.052:src/V3AssertNfa.cpp:1267`、commit 29fd2cf9 |

### 1.6 build_requirements

| 版 | 内容 | 判定 | 根拠 |
|---|---|---|---|
| 5.020 | autoconf、flex、bison、perl、python3(astgen/bisonpre/flexfix)、make、help2man、C++14 コンパイラが必要である。g++13 の既定 gnu++17 で足りる。`verilator` 本体は Perl スクリプトなので実行時も perl が要る。実測対象は Debian 5.020-1 パッケージである。 | 確認 | `v5.020:configure.ac:177-199`、`v5.020:src/Makefile_obj.in:346-347`、`v5.020:bin/verilator:1`、commit 2d7e8b58 |
| 5.024 | 新規ツール要件なし。`--enable-m32` は廃止、`-latomic` は任意の追加である。configure 自体の C++14 チェックは不変で、厳格化は `verilatedos.h` の `__cplusplus` ゲートである。perl は install の redirect スタブに必要で、File::Spec は core である。 | 修正 | `Changes 5.022`、`v5.024:configure.ac:478`、`v5.024:include/verilatedos.h:269` |
| 5.026 | configure が SMT ソルバ(z3→cvc5→cvc4)を任意検出する(`--with-solver`)。ビルド時は不要で、C++ 標準・bison・flex の要件は不変である。 | 確認 | `v5.026:configure.ac`、`v5.026:docs/guide/install.rst`、`v5.026:src/config_build.h` |
| 5.030 | 新規最低要件なし。 `AC_CHECK_PROG`(裸名)、コルーチンフラグ検出は 空 → `-fcoroutines-ts` → `-fcoroutines` → `-fcoroutines-ts -Wno-deprecated-experimental-coroutine` → `-std=gnu++20`(`--timing` モデル専用)。テスト駆動は Python 化(`driver.pl` 廃止)。 | 修正 | `Changes 5.030`(#5504 #5505)、`v5.030:configure.ac:193-212,423-427,589-591`、`v5.030:include/verilated.mk.in:166-170` |
| 5.030 | `make install` の配置が逆転した:実行ファイルは `$(bindir)`、redirect スクリプトは `$(pkgdatadir)/bin` になる。 | 確認 | `Changes 5.030`(#5140 #5544)、`v5.030:Makefile.in:269,273,277`、`v5.030:bin/verilator:73` |
| 5.032 | 変更なし(configure に `CFG_CXX_VERSION` が追加されただけ)。 | 確認 | `v5.032:configure.ac:404-413` |
| 5.034 | 新規要件なし。`-fno-exceptions` は 5.044 の項を参照。5.034 は Ubuntu 20.04 対応の最終見込みで、`-Wno-subobject-linkage` が追加された。 | 確認 | `v5.034:Changes:16`、`v5.034:configure.ac:441-444,481` |
| 5.036 | 新規ツール要件なし。静的 tcmalloc は `--whole-archive` でリンクされる(「mold/`-gc-sections` で落ちる」という理由は推論である)。CI は ubuntu-24.04 gcc/clang を使い、20.04 は廃止された。 | 修正 | `Changes 5.036`(#5817 #5818)、`v5.036:.github/workflows/build.yml:36`、commit 99064d3e、d31bce91 |
| 5.038 | C++20 要求(c0b50584)は revert(11998ccc)され、正味は不変である。docker 基底は Ubuntu 24.04 になった。 | 確認 | `Changes 5.038`(#6147 #6153)、commit c0b50584、11998ccc |
| 5.040 | configure が `HAVE_TCMALLOC` を定義し、`V3Os.cpp` が `<gperftools/malloc_extension.h>`(libgoogle-perftools-dev)を条件付きで include する。`-Wno-int-in-bool-context` が追加された。 | 確認 | `git diff v5.038 v5.040 -- configure.ac`、`src/V3Os.cpp` |
| 5.042 | C++14 プローブが link から run(`AC_RUN_IFELSE`)になった。`--enable-coverage` が `--enable-dev-gcov` に改名され、`--enable-dev-asan` が追加された。 | 確認 | `Changes 5.042`(#6488 #6515 #6526 #6404)、configure.ac diff |
| 5.044 | verilator_bin を `-fno-exceptions` でビルドする(Changes に記載無し、commit 587f363e)。g++13 は許容する。`make venv` が追加された(ビルドには不要)。 | 確認 | `v5.044:configure.ac:478`、`Changes 5.044`(#6775)、`v5.044:docs/guide/install.rst` |
| 5.046 | 要件は 5.044 と同じである。ソースの増減(V3DfgPushDownSels 等)のみ。 | 確認 | `v5.046:configure.ac:442-443`、`src/Makefile_obj.in` |
| 5.048 | configure が jemalloc を優先検出し、見つかれば verilator_bin にリンクする。無ければ tcmalloc、さらに無ければ libc malloc。jemalloc は任意で、C++/flex/bison の要件は不変である。 | 確認 | `Changes 5.048`(#7250)、`v5.048:configure.ac:612-626,457-466` |
| 5.048 | `--output-groups` の 0 除算ガード(→ 1.2 の5.048 の reviewer-added 行)。 | reviewer-added, unverified | 同上 |
| 5.050 | verilator_bin に新規依存は無い。FST trace モデルのみ `-llz4 -lz` が要る(旧 gtkwave 同梱の fstapi.c を fstcpp に置換、liblz4-dev)。 | 確認 | `Changes 5.050`(#6871 #6992)、`v5.050:include/verilated.mk.in:212-214`、`v5.050:configure.ac:696` |
| 5.052 | 要件は不変である。デバッグ実行ファイルの既定が `-O0 -ggdb`(`-gz` 無し)になり、`--enable-light-debug` で `-Og -ggdb1 -gz` に戻る。`-fsanitize=thread` の探査が追加された。 | 確認 | `Changes 5.052`(#7886)、`git diff v5.050 v5.052 -- configure.ac` |

### 1.7 performance

| 版 | 内容 | 判定 | 根拠 |
|---|---|---|---|
| 5.020 | 内部プールの実作業は `V3EmitCImp` の slow/fast C++ emit ジョブ(+trace)のみである。他は単一スレッドで、`--verilate-jobs>1` は C++ 書き出しだけを速くする。 | 確認 | `v5.020:src/V3EmitCImp.cpp:982-1004`、`V3Error.cpp:226,294`、`Verilator.cpp:636,664` |
| 5.020 | C++ ファイルが 128 未満なら全体が単一 `__ALL.cpp` になる。OPT_FAST=-Os、OPT_SLOW は空、OPT_GLOBAL=-Os である。PCH ルールは 5.018 由来で、並列(≥128)ブランチのみに関わる。 | 確認 | `v5.020:src/V3EmitMk.h:32`、`v5.020:src/Verilator.cpp:598-604`、`v5.020:include/verilated.mk.in:131-139,204-218` |
| 5.024 | 最適化項目なし。DFG regularize は performance neutral で、V3Order 改修は `--threads` のみ関係する。 | 確認 | `Changes 5.024`(#4937 #4957)、commit 5e1fc6e2、6ffff856、9a3aed57 |
| 5.026 | `--localize-max-size`(既定 1024 バイト, #5072)。V3Delayed の再構成と、定数 LSB の bit-select 最適化(#5089)。NBA commit queue(#5092)は動的回数の NBA のみ。 | 確認 | `Changes 5.026`、`v5.026:src/V3Localize.cpp:73`、`v5.026:src/V3Delayed.cpp:348-354`、commit 8fd038f8、b5b937e2、80b08b71 |
| 5.030 | トリガーを SenItem 単位で生成する(#5483)。 | 確認 | `Changes 5.030`、`v5.030:src/V3Sched.cpp:621` |
| 5.030 | 並列化は `--verilate-jobs>1` のみ有効で、V3VariableOrder(#5406)が新規である。V3EmitCImp は 5.026 から並列だった。 | 修正 | `Changes 5.030`(#5406)、`v5.030:src/V3VariableOrder.cpp:274`、`v5.030:src/V3ThreadPool.cpp` |
| 5.030 | `--output-groups`(#5257)は既定オフで、キャッシュ・再現性に悪影響の恐れと、コンパイラメモリ増がある(推奨 2〜20)。 | 修正 | `Changes 5.030`(#5257)、`v5.030:src/V3Options.h:319`、`docs/guide/exe_verilator.rst` |
| 5.030 | 生成コードが `vlSelfRef` 参照を使う(#5254)。 | reviewer-added, unverified | commit 164a7a7c5 |
| 5.032 | V3FuncOpt が既定オンになった(#5598):>64bit 代入の Concat 分割と 4 項超の Concat 再バランス。`AstRand` は isPure()==true で除外されないが、`CONCAT(RAND,RAND)` は V3Const が先に1つの wide RAND に融合する。 | 確認 | `Changes 5.032`(#5598 #5599 #5602)、`v5.032:src/V3FuncOpt.cpp:120,230,243`、`v5.032:src/V3Const.cpp:2414` |
| 5.032 | V3Gate は expand-limit 語(既定 64語=2048bit)超の変数で、非自明な式かつ同一 active 内で2回超読まれるものを inline しなくなった。幅が小さい VLIW 設計では無影響の見込みである。 | 修正 | `Changes 5.032`(#5637)、`v5.032:src/V3Gate.cpp:695-716,816`、`v5.032:src/V3Options.h`(m_expandLimit=64) |
| 5.032 | V3PreProc が全行を `lint_off --contents` 用に二重保持する。未サイズ数値の解析高速化(#5577)。 | reviewer-added, unverified | `v5.032:src/V3PreProc.cpp:827-839`、commit f458951b1 |
| 5.034 | トリガー計算・ダンプの分割(#5798)、ラベルの final if 化(#5744)、空関数本体の最適化(#5750)。単一クロック設計では影響は小さい見込みである。 | 確認 | `Changes 5.034`、`v5.034:src/V3Sched.cpp:793-794`、commit 812861e7、05c7a3b9、001c098e |
| 5.036 | `-fvar-split` が既定オンで、定数・非重複 select のみで参照される packed 変数を自動分割する。 | 確認 | `Changes 5.036`(#5843)、`v5.036:src/V3Options.cpp:1357,2182`、`v5.036:src/V3SplitVar.cpp:1321` |
| 5.036 | トリガーベクタを語単位で設定する(#5857):≥64 トリガー時のみ効く。単一クロック設計では影響は小さい。 | reviewer-added, unverified | commit 59cb53cfb |
| 5.038 | scoped DFG パス(#6141)と DFG ピープホール追加。DFG `binToOneHot`(#6096)は、7〜20bit 幅の同一ソースが ≥65 個の定数と比較され、COND チェーンでない場合のみ発動する。小さなデコーダは対象外である。 | 修正 | `Changes 5.038`、`v5.038:src/Verilator.cpp`、`v5.038:src/V3DfgPasses.cpp:326-331` |
| 5.040 | DFG 合成・サイクル破断(既定オン)・CFG 層の大改修:`always_comb`/`always @*`(sentree 無し、4ブロック/4辺以内、または循環内)が CFG 経由で合成される。clocked always は対象外。assign は従来から変換済み。5.042 で複数の DFG バグが修正された。 | 修正 | `Changes 5.040`(#6205 #6209 #6298 #6331 #6168 #6261)、`v5.040:src/V3DfgSynthesize.cpp:1734-1784`、`v5.040:src/V3Options.h` |
| 5.040 | `--build` 前に netlist を解放し、tcmalloc があれば OS に返す(#6226)。`--skip-identical` が内容ハッシュ(SHA-256, ≤1MB)でも判定される(#6109, 既定は `--cc` で有効)。 再利用 Mdir で同一内容を再生成すると verilation がスキップされる。 | 確認 | `Changes 5.040`(#6192 #6226 #6109)、`v5.040:src/Verilator.cpp:779-786`、`v5.040:src/V3File.cpp:63,111,317` |
| 5.042 | `--expand-limit` の既定が 64 → 256 語(8192bit)になった。影響は 2049〜8192bit の式のみである。 | 確認 | `Changes 5.042`(c5f31925)、`v5.042:src/V3Options.h:316`、`v5.042:src/V3Expand.cpp:98` |
| 5.042 | DFG 選択の絞り込み(#6370)、DFG 型システム、条件結合、重複 if、デッド関数、ループ展開の書き換え(603f4c615)等でビルド時間・生成コードが変わりうる。 | reviewer-added, unverified | `Changes 5.042`、commit 603f4c615、922223a9c |
| 5.044 | `V3InlineCFuncs` が既定オン(`--inline-cfuncs 20`、積 200)になった。`--trace`/`--prof-cfuncs` では自動的に無効になる。空関数呼出しの除去も入った。 | 確認 | `Changes 5.044`(#6815 #6626 #6664)、`v5.044:src/V3Options.h:324-325`、`v5.044:src/Verilator.cpp:569` |
| 5.044 | const-bit-op-tree の O(n²) 修正(#6791)、DFG 独立ビット解析の性能(#6713)、DFG 循環最適化(#6744)。 | reviewer-added, unverified | `v5.044` Changes、commit diffstat |
| 5.046 | 多数の DFG/生成コード最適化。`-fdfg-push-down-sels` が既定オン。NUMA ピン留めは `threads()` 呼出し時のみで、svsim は無関係である。 | 確認 | `Changes 5.046`(#7013 #6970 #6981 #7081 #7084 #6954 #6826 #6880)、`v5.046:src/V3Options.h`、`v5.046:include/verilated_threads.cpp` |
| 5.048 | DFG を scope 後に1回のみ実行し、extract パスを削除した。`V3LiftExpr` が既定オン。 | 確認 | `Changes 5.048`(#7362 #7394 #7141 #6707)、`v5.048:src/Verilator.cpp:305,428`、`v5.048:src/V3Options.h:416` |
| 5.050 | ランタイムの `assertOn()` チェックを結合・hoist する(#7707)。ただし同範囲で `assertOnGet()` が重い `assertCtlGet()` 経由になったため、正味効果は予測不能である(測定要)。 | 不確実(修正付き) | `Changes 5.050`(#7707)、commit 41811d43、b973b1df、`v5.050:src/V3Assert.cpp:236-300` |
| 5.050 | モジュール inline ヒューリスティック書き換え(#7837)と生成関数 inline(#7811, `-fno-inline-cfuncs`)で、生成 C++ 量が減り 10〜20% 高速化と upstream は述べている。 | 確認 | `Changes 5.050`、commit b73a897db、bcaa110f6 |
| 5.050 | 入力組合せ領域に変化検出(`-fico-change-detect`、既定オン, #7784)。svsim のトップは入力ポート無し、DUT 入力は DPI 書込みの内部 reg である。 | reviewer-added, unverified | commit 5ab2bf1ec、`V3Sched.cpp` createInputCombLoop |
| 5.052 | eval ループがランタイム仮想呼出しで全領域を毎回実行する(upstream 自身が超小型モデルでは遅くなると言う)。DFG が「変数1つを駆動する combinational always」を全て合成する(#7775)。 | 確認 | commit 8546d5db、`v5.052:include/verilated.cpp:4055-4135`、`Changes 5.052`(#7775)、commit 2285e5b6 |
| 5.052 | wide 値からの非定数 Sel の指数的展開を修正した(#8137)。`VL_WORDS_I`/`VL_BYTES_I` をシフト化した(#8095)。 | reviewer-added, unverified | commit 7523a0cd8、1397d5d01 |

### 1.8 semantics

| 版 | 内容 | 判定 | 根拠 |
|---|---|---|---|
| 5.020 | `--assert` の失敗は `[t] %Error: …Assertion failed` の後に `VL_STOP_MT` → `vl_stop_maybe` → `vl_stop` → `vl_fatal`(`Verilog $stop`/`Aborting...`)→ `std::abort()`(SIGABRT)となる。`AstStop` の maybe 引数は無視される。`assertOn()` は既定 true、`errorLimit=1`、`fatalOnError=true`。 | 確認 | `v5.020:src/V3Assert.cpp:102-129`、`v5.020:src/V3EmitCFunc.h:966-972`、`v5.020:include/verilated.cpp:118-171`、`v5.020:include/verilated.h:355-366` |
| 5.020 | `$finish` を2回呼んでも `exit(0)` しなくなった(#4757)。 | 確認 | `Changes 5.020`、`v5.020:include/verilated.cpp:108-115` |
| 5.020 | SVA 下地:`|->`→`!a||b`、`|=>`→`$past`、`disable iff`→同期チェック(`d||prop`)、`cover` はデフォルトで削除、多数の SVA 構文は E_UNSUPPORTED。 | reviewer-added, unverified | `v5.020:src/verilog.y:6125-6220`、`v5.020:src/V3AssertPre.cpp:72,362-457`、`v5.020:src/V3Assert.cpp:138-216` |
| 5.024 | V3Const の誤コード修正(5.022):bit-op-tree の NOT、EQ/NEQ、replaceShiftOp 幅、SHIFTR 下の EQ/NE(999c9ae2 の変更はデフォルトオンの最適化側で、題名の `-fno-const-bit-op-tree` ではない)。5.020 のプロジェクト論理が誤コンパイルされていたかは不明である。差異が出たら最初の bisect 候補。 | 修正 | `Changes 5.022`(#4847 #4863 #4867 #4849)、`v5.024:src/V3Const.cpp:544,598` |
| 5.024 | `_vl_insert_WI`:宛先幅が32の倍数で、`hword==rword && roffset==0` のとき、末尾の次語へ越境書込みしていたのを修正した(5.022)。 | 修正 | `Changes 5.022`(#4850 #4855)、`v5.024:include/verilated_funcs.h:1306` |
| 5.024 | `$time` を timeunit が粗いとき四捨五入に変更(#4790)。ログ出力の `$time` のみ変わりうる。`--stop-fail`(既定オン)、`--assert-case`、unique/priority case のメッセージ細分化。assertion 失敗の意味論は不変である。 | 確認 | `Changes 5.022`(#4790 #4792 #4904 #4919 #4905 #4892)、`v5.024:include/verilated_funcs.h:316-317`、`v5.024:src/V3Assert.cpp:57,144` |
| 5.024 | `$readmem` が改行で終わらないファイルの最終語を落とさなくなった(#5019)。ROM/データを `$readmem` で読む場合は 5.020 と結果が変わりうる。 | reviewer-added, unverified | `Changes 5.024`(#5019)、commit 0114cb67c |
| 5.026 | V3Number `opSub` が結果幅で rhs を負にするよう修正された(#5153)。Changes では 5.020 節に載る。 | 確認 | `Changes`(#5148 #5153)、`v5.026:src/V3Number.cpp:1894` |
| 5.026 | 動的 select(packed 配列)の index に明示拡張が入った(#5095)。`3'd2 - cnt[2:0]` のように幅が回る index で誤要素を選ぶ誤コンパイルの修正(失敗機構は diff とテストからの読み)。ChiselSim の結果が 5.024 と 5.026 で食い違えば最初に疑う。 | reviewer-added, unverified | commit 3cb4033c9、`v5.026:src/V3WidthSel.cpp`、test `t_select_mul_extend.v` |
| 5.026 | 範囲外定数 select の拡張(#5164)、パラメータ無し `$asserton/$assertoff/$assertkill`(#5010)、マクロ引数の文字列内置換の廃止(#5094)。 | reviewer-added, unverified | commit 2bc883f3b、`Changes 5.026`、commit ec45a77d9 |
| 5.030 | `assertOn` がタイプ別・ディレクティブ別ビットマスクになった(#5236 #5310)。既定は全オンで `--assert` の挙動は不変である。 | 確認 | `Changes 5.028`、`v5.030:src/V3Assert.cpp:71`、`v5.030:include/verilated.h:385-386` |
| 5.030 | `$fatal` は `+verilator+error+limit` の影響を受けず必ず停止する(#5135)。既定 errorLimit=1 では不変である。 | 確認 | `Changes 5.030`、`v5.030:include/verilated.cpp:175-187`、`v5.030:src/V3EmitCFunc.h:1006` |
| 5.030 | `$sampled` 処理が V3Clock から新 V3Sampled に移った(#5235)。`__Vsampled_*` は `_eval` 先頭で代入され、意図は不変である。 | 確認 | `Changes 5.028`(#5235)、`v5.030:src/Verilator.cpp:441` |
| 5.030 | 幅不一致の誤結果修正(#5189)。NBA-Post→変数のエッジが hard 制約化された(041f6603, 違反なら新エラーのみ)。 | 確認 | `Changes 5.028`(#5186 #5189)、`v5.030:src/V3Const.cpp:858`、`v5.030:src/V3OrderGraphBuilder.cpp:222` |
| 5.030 | V3Table が 'x' ビットを 0 にする(#5491)。範囲外 select の内部エラー修正(#5443)。wide 式の unique/unique0 検査(#5324)。timing 系修正は `--timing` のみ有効である。 | reviewer-added, unverified | `Changes 5.030`、commit 2a01365f9、289012611、31c1df638 |
| 5.032 | `default disable iff` と `$inferred_disable` が対応された(#4016)。V3Unknown の配列書込みで、5.030 が足した旧要素の読み込みを除去した(24b5c641)。値・境界ガードは 5.030 と同じである。 | 確認 | `Changes 5.032`(#4016 #5605 #5618 #5628)、`v5.032:src/V3AssertPre.cpp:549-551`、`v5.032:src/V3Unknown.cpp` |
| 5.032 | `module automatic` 修正(192236a83)、unpacked 配列の NBA スキームは基本要素なら FlagShared のまま。 | reviewer-added, unverified | `git diff -w v5.030 v5.032 -- src/V3LinkParse.cpp, V3Delayed.cpp:248-281` |
| 5.034 | V3Simulate の定数再利用修正(#5709):`user1` の衝突を世代アロケータで解決した。定数関数・V3Table・V3Unroll が対象で、firtool の RANDOMIZE for ループは V3Unroll を通る。誤定数の危険を除くのみで、結果変化は想定しない。V3Assert.cpp は `$monitor` 感度のみ変更された。 | 修正 | `Changes 5.034`(#5709)、`v5.034:src/V3Simulate.h:143,253` |
| 5.034 | V3Life が `AssignForce` を最適化対象外にした(#5757)。force/release を使う ChiselSim テストだけに影響する。 | reviewer-added, unverified | commit a379382d3 |
| 5.034 | 空関数の定義を出力しなくなった(`{}` インライン宣言、keepIfEmpty 除く)。 | reviewer-added, unverified | commit 001c098e5 |
| 5.036 | 変化検出の 'previous value' 変数を `_eval_static` で初期化する(#5864, 4a221294)。時刻0 の initial による posedge/negedge が論理を起動する。svsim の changed 感度は DidInit トリガで従来どおり起動する。 | 確認 | `Changes 5.036`(#5499 #5864)、`v5.036:src/V3Sched.cpp:459,717,1239` |
| 5.036 | FlagShared NBA で連続する別配列の NBA が前の変数の commit ブロックを再利用していた誤コンパイルを修正した(#5848)。5.030〜5.034 のバグである(5.020 に FlagShared は無い)。 | 確認 | `Changes 5.036`(#5848)、`v5.036:src/V3Delayed.cpp:273,479`、commit 6ecdd14f、5acced1e3 |
| 5.036 | automatic 関数ローカルを毎呼出しでゼロ化する(#5747, RNG 非消費)。 | 確認 | `Changes 5.036`、`src/V3Task.cpp`、`v5.036:src/V3EmitCFunc.cpp:752` |
| 5.036 | V3MergeCond の分岐を fence 扱い、V3SenExprBuilder・V3Gate・V3Delayed の assert 緩和、範囲外 lvalue select の幅(#5820)ほか。内部エラー/稀な誤コンパイルの修正である。 | reviewer-added, unverified | commit 14f618b0f、fc302a520、0133bc6b0、96bffd49a、39c3e79a3、f3684a85b、51fcb881d |
| 5.038 | DFG の LteS 折りたたみ修正(#6004, 5.020 から存在したバグ):定数同士の `<=` で等しいとき誤っていた。 | 確認 | `Changes 5.038`(#6000 #6004)、`v5.020:src/V3DfgPeephole.cpp:113`、commit 2f6ecd18 |
| 5.038 | 定数シフト等の修正:算術右シフト(#5994, 幅>32 の rhs)、左シフト(#6007, 2^31 以上のシフト量)、post-expand 定数伝播、V3Expand の幅、shifted-out 変数、single-bit mask の const-bit-op-tree。 | 修正 | `Changes 5.038`(#5994 #6007 #6015 #5955 #5975 #6016 #5993)、commit 0f528d13 |
| 5.038 | case 式の拡張が EXTEND_EXP になった(符号付き case 式 × 符号無し item はゼロ拡張)。suspendable always は `$finish` 後に停止する(`--timing` のみ)。 | 確認 | `Changes 5.038`(#5968 #5971)、commit 8da539ed、15ebbd30 |
| 5.038 | 'Safely support non-overlapping blocking/non-blocking assignments (#6137)':V3Delayed に ShadowVarMasked が入り、BLKANDNBLK をビット範囲で判定する。initial/static は無視される。#6150 は 5.038 内で発生・修正された。 | reviewer-added, unverified | commit bc892deac、ae0f29ed3、08fef668c |
| 5.040 | ランタイム致命エラー($stop/$fatal/assert 失敗)が `std::abort()` ではなく `std::exit(1)` で終了する(`Verilated::debug()` 非0 のときのみ abort)。通常ビルドでは `debug()` は constexpr 0 で abort 分岐に入らない。SIGABRT(134)が status 1 になり、core は出ず、静的デストラクタが走る。`+verilator+seed+0` 拒否も status 1 になる。 | 確認 | `Changes 5.040`(fbaff526)、`v5.040:include/verilated.cpp:172-176`、`v5.040:include/verilated.h:402` |
| 5.040 | wide(>64bit)値からの非定数 select の展開・置換のバグ修正(#6345)。LSB 式の再評価、末尾語の OOB 読みを除去した。結果が変わるのは旧コードが誤っていた場合のみ。 | 修正 | `Changes 5.040`(#6341 #6345)、commit c33f2b42a |
| 5.040 | NBA/スケジューリング/inliner の内部書き換え(#6306 #6307 #6286 #6300 #6322 #6305 #6221 #6219 #6217)と、ループ展開が初期化代入を保持する修正(#4237)。initial ブロックの `$urandom` 消費順を変えうる。 | reviewer-added, unverified | commit c9a6d0654、0bf9fc270、f6edf26eb、1c86ff0af、1725ee9c5 |
| 5.040 | 5.042 で修正された DFG 不具合(#6453 #6459 #6561 #6557)は、5.040 の DFG 合成が未成熟だった目印である。 | reviewer-added, unverified | `v5.042:Changes`、commit bbcb9315f、e24f84f71、e33b24fad、92b490f2f、ffd7ec100 |
| 5.042 | アサーション:`##N` を properties 内で対応(fork ベースの AstPExpr)。平文 assert の生成は `IF(assertOn){IF(cond) pass else fail}` のまま。ただし V3AssertPre が再構成され、overlapped/non-overlapped の分離、`$rose/$fell/$stable` の sentree 付与、disable-iff 処理、cover の入れ子が変わった。非 sequence の同等性はコード読みのみで、`layer$Verification$Assert$Temporal` を定義する本プロジェクトでは実テストで確認する。 | 修正 | `Changes 5.042`(#6508 #6434)、`v5.042:src/V3Assert.cpp`、`V3AssertPre.cpp` |
| 5.042 | `$finish` がループから抜ける、fork 内 `$finish`(#6555)、timeprecision の宣言時マージ(#6469)。`--timescale 1ns/100ps` の root precision は維持される。 | reviewer-added, unverified | commit 717667034、9bd30baba、f719d6612 |
| 5.044 | `disable iff` の簡易プロパティ処理(#6783):旧来 `disable||prop` に書き換えていたものを、本体全体を `if(!disable)` で包む。disabled 中は pass アクションも走らない。簡易プロパティの disable は 5.044 でも sampled のまま(5.046 で再変更)。 | 確認 | `Changes 5.044`(#6783)、`v5.044:src/V3Assert.cpp:458-468`、`v5.044:src/verilog.y:6356`、commit 0f355c71 |
| 5.044 | 新 V3AssertProp パス(DFA, 多サイクル)、`##` 系の対応(#6639 #6691)、`$past` 遅延変数共有(#6689)、`assert under assert`(#6146, Changes では 5.042 節)、`%p` の spaceless 化(7a2b20cff)、gate マージが impure RHS を複製しない(#6630)。 | reviewer-added, unverified | commit 5adecb9fa、41c4f948f、7a2b20cff、faaa2db84 |
| 5.046 | `vl_stop()` が `gotFinish()` 済みなら即 return する(da28c67e)。`$finish` の後に走る `$stop/$fatal` は黙って無視され、5.044 なら出力して `exit(1)` した。svsim は `gotFinish()` を見たら進行を止める。 | 確認 | `Changes 5.046`(#6891)、`v5.046:include/verilated.cpp:161-163` |
| 5.046 | 並行アサーションで property のみが sampled になる(#6890)。disable iff は現在値を使う。`cover property` の property も sampled になる。 | 確認 | `Changes 5.046`(#6890)、commit da14e7c4、`v5.046:src/V3AssertPre.cpp:650-652` |
| 5.046 | `#0`(Inactive 領域)は `--timing` 時のみ、`--sched-zero-delay` と ZERODLY。静的変数の初期化の正規化(#7086)で、`needsCReset()` 変数すべてに明示 CReset(`randReset` 0 では値不変)。V3Life は DPI 書込み変数を定数伝播しない(#7074)。svsim の `simulation_body()` は initial でなく `always @(simulationState)` から呼ばれる。 | 確認 | `Changes 5.046` Important(#7079)、`Changes 5.046`(#7086 #7074)、`v5.046:src/V3Life.cpp:174`、commit 505d33b3、7607f0e7 |
| 5.046 | case item の重複式(#6886, assert の誤失敗を減らす)、packed 配列の符号(#6902)、`__Vm_didInit` の位置は 1.4 参照。 | reviewer-added, unverified | commit 112e1e375、b926fcb76 |
| 5.048 | `final()` 中は `executingFinal` が立ち、`vl_stop` は抑止されない(#7249)。`$finish` で suspended プロセスを再開しない(`--timing` のみ)。`--converge-limit` の既定が 100 → 10000。V3Life は DPI-read 変数への代入を消さない(#7158)。スケジューリングが決定的になった(#7165)。 | 確認 | `Changes 5.048`、`v5.048:include/verilated.cpp:164-167`、`v5.048:src/V3Options.h:316`、`v5.048:src/V3Life.cpp:132`、`v5.048:src/V3Sched.cpp:857` |
| 5.048 | 多サイクル SVA が NFA エンジン(V3AssertNfa)に置き換わり、V3Assert/V3AssertPre も変更された(action ブロックの反復順、暗黙 sampling、`failsp` の clone)。平文 Chisel assert の挙動が不変かは証明されていない。 | 不確実(修正付き) | `Changes 5.048`(#7430 #7298)、`v5.048:src/Verilator.cpp:258`、`v5.048:src/V3Assert.cpp:747`、commit e0b4d5ad |
| 5.048 | `$display` の書式プロトコル(VFormatAttr)変更、V3Clean/`VL_COND_WIWW`/V3Gate(unpacked 配列)の誤コンパイル修正。 | reviewer-added, unverified | `include/verilatedos.h`、`verilated.cpp _vl_vsformat`、commit 8705bc56c、d40036239 |
| 5.050 | initial ブロック内の NBA が実 NBA になり(#7754, 旧 INITIALDLY 警告)、FINALDLY エラーが追加された。firtool の初期化は blocking で、svsim の initial も blocking である。 | 確認 | `Changes 5.050`(#7754 #7566 #7600)、commit e269b914、`v5.050:src/V3Active.cpp:364` |
| 5.050 | 多サイクル SVA で、`disable iff` の条件が連続して真のとき抑止される(#7841)。単一サイクルのアサーションは無関係である。 | 確認 | `Changes 5.050`(#7841 #7773)、commit 38533013、`v5.050:src/V3AssertNfa.cpp:1986-1997` |
| 5.050 | 2-state 配列の範囲外読みは明示的に 0 になる(#7785, 既定 `--x-assign fast` では従来も 0)。 | reviewer-added, unverified | commit 38fd99b37 |
| 5.052 | V3Gate が initial 内の非定数代入を静的初期化扱いにして inline しない(#8250)。firtool の RANDOMIZE 初期ブロックは理論上関与しうるが、該当パターンは確認されていない。 | 確認 | `Changes 5.052`(#8250)、commit 3c121c0a、`v5.052:src/V3Gate.cpp:228-230,588` |
| 5.052 | 名前付き event のトリガのみ `$finish` 後停止(#7950)。`$finish/$stop` の要求順序は mtask のみ(#7952)。`$past` 系は初期サイクルで既定サンプル値を返す(#8162)。多サイクルの途中 disable iff(#7869)。 | 確認 | `Changes 5.052`、`v5.052:src/V3SenExprBuilder.h:290-304`、`v5.052:include/verilated_imp.h:158-163`、commit b70696fc、925a6640、7f933372、ec124a09 |
| 5.052 | DFG の境界ガード誤最適化(#7755)、ネストシフト幅のオーバーフロー(#7977)、符号付き乗算の UB(#8248)の修正。5.050 に誤りがあった場合のみ結果が変わる。 | 確認 | `Changes 5.052`、commit 853ee5df、a6f4dd03、`v5.052:include/verilated_funcs.h:1198` |
| 5.052 | V3Life が不透明呼出し(DPI 等)を分岐/ループ内で見たとき外側ブロックも noopt にする(#7933)。 | reviewer-added, unverified | commit 877fc1e81、`v5.052:src/V3Life.cpp` |
| 5.052 | `+verilator+assert+lock` が追加され、既定オフである。NFA のリングバッファ化(#7885 #8061 #8096 #8145)で V3AssertNfa が約1.5k行変更された。複数サイクル性質のみ関係。 | reviewer-added, unverified | commit 44fe96b2d、6c20fdb7b、8c2b7410f |

### 1.9 other

| 版 | 内容 | 判定 | 根拠 |
|---|---|---|---|
| 5.020 | make 実行前にビルドディレクトリを `utimes()` と open で flush する(#4708)。ファイルシステム競合による空入力の flaky ビルド要因を1つ除く。 | 確認 | `Changes 5.020`、`v5.020:src/V3Os.cpp:300-325`、`v5.020:src/Verilator.cpp:724-725,757` |
| 5.020 | 乗算幅上限 VL_MULS_MAX_WORDS=128(#4781)、シフト・pow 修正(#4719 #4721 #4763)、`-Oz`、非 exe の既定 make ターゲット変更(540cd772)。`--exe` の svsim には無影響である。 | reviewer-added, unverified | `Changes 5.020`、commit 4655ee014、ca3b5fbe、7167d4bdc、540cd772 |
| 5.024 | 成功実行の末尾に3行のサマリレポート('- V e r i l a t i o n   R e p o r t' 等)を stdout に出す。`--quiet`/`--quiet-stats` で抑止できる。実行時レポートは `--main` 生成コードのみで、svsim は出さない。 | 確認 | `Changes 5.024`(#4909)、`v5.024:src/Verilator.cpp:872-875`、`v5.024:src/V3StatsReport.cpp:248` |
| 5.024 | インストールが relocatable になった。実行ファイルは `$prefix/share/verilator/bin`、`$prefix/bin` は redirect スタブである。`VERILATOR_ROOT` が異なる値だと即 `exit 1` する。切替用ハーネスは unset にする。 | 確認 | `Changes 5.024`(#4927)、`v5.024:bin/verilator:72-82`、`v5.024:Makefile.in:257` |
| 5.024 | 1800-2023 が既定言語になった。新規キーワードは無いが、`+/-`、`+%-` の演算子トークンが追加された。 | 修正 | `Changes 5.024`、`v5.024:src/V3LangCode.h`、`v5.024:src/verilog.l:629,898-902` |
| 5.024 | 生成モデルが `Verilated::stackCheck()` を呼ぶ(#4799)。スタック rlimit が予測の1.5倍未満なら '%Warning: System has stack size…' を stdout に出す。svsim の stdout はログファイルで、LOG コマンドで返却される(プロトコルは壊れない)。この設計で発火するかは未確認である。 | 修正 | `Changes 5.022`(#4799)、`v5.024:src/V3EmitCSyms.cpp:842`、`v5.024:include/verilated.cpp:3118` |
| 5.024 | 'TOP' という名前のモジュールが硬エラーになった。 | reviewer-added, unverified | commit a4813ec67、`v5.024:src/V3LinkParse.cpp:648-651` |
| 5.026 | stack rlimit を予測の1.5倍に `setrlimit` で引き上げる(#5104)。失敗時のみ警告する。 | 確認 | `Changes 5.026`(#5071 #5104)、`v5.026:include/verilated.cpp:3155-3168`、`v5.026:src/V3EmitCSyms.cpp:842` |
| 5.026 | `verilated.mk.in` の '$(info Archive …)' 行が削除された(#5059)。ログ文言のみ変わる。 | reviewer-added, unverified | `Changes 5.026`(#5059)、`git diff v5.024 v5.026 -- include/verilated.mk.in` |
| 5.030 | CPU 時間が常に 0 だった不具合の修正(5.028)。使われるのは `statsPrintSummary()` で、`--main` 生成の main からのみ呼ばれる。svsim の実行には無影響である。 | 修正 | `Changes 5.028`、`v5.030:include/verilatedos_c.h:54`、commit 1609c23fe |
| 5.030 | v5.030:Changes の 5.026 節に 5.026 タグ後のコミットが載る(--pins-sc-uint-bool #5192 ほか)。Changes の見出しで範囲を切ると漏れる。svsim には無関係である。 | reviewer-added, unverified | `v5.030:Changes`(5.026 節) |
| 5.032 | 組み込みの lint waiver ファイル `include/verilated_std_waiver.vlt` を既定で読む(`--no-std-waiver` で無効化)。見つからなければ verilation が失敗する(古い include/ との混在で起こる)。規則は UVM ファイルにのみ作用する。 | 確認 | `Changes 5.032`(#5607)、`v5.032:src/V3Global.cpp:62-66`、commit e223228e |
| 5.032 | waiver の `-match` が複数行メッセージ全体と比較される。`lint_off -contents`、`--waiver-multiline` が追加された。 | reviewer-added, unverified | `v5.032:src/V3Config.cpp`、`v5.032:src/V3FileLine.cpp` |
| 5.032 | VerilatedScope/runtime の致命メッセージ重複 '%Error:' 接頭辞の除去。 | reviewer-added, unverified | commit 6e204ed0d |
| 5.034 | 式カバレッジ(#5719):`--coverage` が式カバレッジも有効にする。svsim は line+toggle+user が全オンのときだけ `--coverage` を出す。 | 確認 | `Changes 5.034`(#4677 #5719)、`v5.034:src/V3Options.h:433` |
| 5.034 | `-Wno-subobject-linkage` が `CFG_CXXFLAGS_NO_UNUSED` に追加された(Changes に無い)。 | 確認 | commit 70c6651a、`v5.034:configure.ac:481` |
| 5.034 | `--preproc-token-limit` は既定 40000 のままである。 | reviewer-added, unverified | commit 283f6c743 |
| 5.036 | VCD の重複タイムスタンプ削除(34a760b7, Changes に無い)。 VCD は 5.034/5.020 と byte 一致しない。同版内では再現的である。 | 確認 | commit 34a760b7、`v5.036:include/verilated_vcd_c.cpp:196` |
| 5.036 | NUMA 風の affinity 自動割当(#5911)は `numaAssign()` で、2スレッド未満なら no-op('too few threads')である。svsim は `--threads` を使わないので無関係である。 | reviewer-added, unverified | commit 6d1e82b90、`v5.036:include/verilated_threads.cpp` |
| 5.038 | VCD の `emitTimeChange` の nullptr segfault 修正(#5980)。5.036 は VCD trace 有効時に落ちうる。 | 確認 | `Changes 5.038`(#5978 #5980)、commit 295fae0e |
| 5.038 | トレースのスコープ階層の作り直し(`$rootio`、順序)。波形のスコープ構成が変わる。 | 確認 | `Changes 5.038`(#5668 #6076)、commit 4990b441 |
| 5.038 | シンボルテーブルをパーサから除去した(型の非型への再宣言、大規模パーサ変更)。パースの回帰リスクの主な面である。 | 確認 | `Changes 5.038`(#2412 #6020 #6042 #6044)、commits 0c8c7fb0、6bb16d6c、76aced4d、5fcd0e52 |
| 5.040 | 5.040 の DFG 合成は、複数 basic block の combinational always で、ポインタキーの `unordered_map` を反復する。生成 C++ が実行ごとに変わりうる(5.042 で修正)。assign と clocked always のみの設計は影響を受けない見込みである。 | 修正 | `v5.040:src/V3DfgSynthesize.cpp:485,1010,1266`、`Changes 5.042`(#6557 #6568) |
| 5.042 | DFG 合成の非決定性を `std::map`(名前順)で修正した(ffd7ec10)。5.040 はより影響を受け、5.042 は #6370 の絞り込みと併せて恩恵を受ける。 | 修正 | `Changes 5.042`(#6557 #6568)、`v5.042:src/V3DfgSynthesize.cpp:536-541`、commit a966e6aa1 |
| 5.042 | `--trace-max-width` は 5.020〜5.040 では宣言だけで未適用だった。5.042 で適用され、既定 4096、配列は全要素の総ビット数で判定する。4096bit 超の信号・配列は trace から外れ、新たに trace される信号は無い。 | 修正(原主張は refuted) | `v5.042:src/V3Options.h:347`、`v5.042:src/V3TraceDecl.cpp:181`、commit bfe39ce5b |
| 5.042 | `bin/verilator` が毎回 `verilator_bin --get-supported DEV_ASAN` を余分に1回実行する(数 ms)。 `--aslr/--no-aslr` が追加された。 | 確認 | `Changes 5.042`(#6404)、`v5.042:bin/verilator:36,152,213` |
| 5.044 | プリプロセッサが裸の `include`/`library` トークンを LIBMODE に入れ、次の `;` まで素通しする(`-libmap` 対応)。ただし 5.042 でもこれらは未対応・予約語として拒否されており、有効な SV に新たな失敗は生じない。 | 確認 | commit 394d9cf1、`v5.044:src/V3PreLex.l:123-124,330` |
| 5.044 | リビジョン文字列が `git describe --always` から作られる。タグ無しでは 'vUNKNOWN-built<日付>' になり、警告は出なくなる。タグ付きなら 'rev v5.044' のままである。 | 確認 | `Changes 5.044`、`v5.044:src/config_rev:28` |
| 5.044 | `--lint-only`/`--json-only`(と `--xml-only`)は、`--timing` が既定のとき `--timing` を暗黙にする。svsim の `--cc --exe --build` には適用されない。5.044 が `--xml-only`/`--make cmake` の最終サポート版である。 | 修正 | `Changes 5.044` Important(#6790)、`v5.044:src/V3Options.cpp:1042-1044` |
| 5.044 | ループ展開の上限は `--unroll-limit`(既定 16384)に統一された。生成 for ループは 16384 まで、定数関数 simulate も 16384 反復である。firtool の RANDOMIZE ループは対象外である。 | reviewer-added, unverified | commit c7ff73a08、`docs/guide/exe_verilator.rst:1860-1868` |
| 5.044 | ランタイム worker スレッドの macOS 専用変更、`contextp->threads(N)` は `--main` の main のみ。 | reviewer-added, unverified | `include/verilated_threads.cpp`、commit 47d1a50aa |
| 5.046 | V3Reloop のローカルが `AstCFunc::varsp` に入る(#7132)。5.044 の `--inline-cfuncs`(既定20)で同名ローカルが inline されて重複する C++ コンパイルエラーを防ぐ。 | 確認 | `Changes 5.046`(#7132)、`v5.046:src/V3Reloop.cpp:71`、commit 8c7f08df |
| 5.046 | automatic 変数は trace に出なくなった。非 inline のトレース階層を inline 時に揃えた(#7041)。 | 確認 | `Changes 5.046`、`v5.046:src/V3TraceDecl.cpp:211` |
| 5.046 | ランタイムのエラー行 'UPPERCASE: msg' が '%Error-CODE: file:line: msg' になる(DIDNOTCONVERGE ほか)。'Verilog $stop' の形式は不変である。 | 確認 | commit c75fb4cd、`v5.046:include/verilated.cpp:119`、`v5.046:src/V3SchedUtil.cpp:99` |
| 5.048 | FST は新しい Surfer が必要になった(GTKWave は不要)。配列 trace は左→右の添字順になった(#7205)。 | 確認 | `Changes 5.048` Important(#7255 #7205)、commit cedec65c |
| 5.048 | `bin/verilator` が `VERILATOR_RUNNING` のネスト深さを数え、16 超で die する(#7497)。VERILATOR_BIN が wrapper に戻る構成を避ける。 | 確認 | `Changes 5.048`(#7496 #7497)、`v5.048:bin/verilator:36-43` |
| 5.048 | DFG 再構成による警告変化は 1.5 を参照。 | reviewer-added, unverified | 同上 |
| 5.050 | FST ライタが fstcpp に置換された(1.6 参照)。 | 確認 | `Changes 5.050`(#6871 #6992) |
| 5.052 | Changes の '* Fix NFA assertion scheduling to fix outcome counts (#7946)' は revert 済み(f694e9e1)で、c4a4f35e が再追加した。コードは #7946 以前の状態で、assertion 数の変化を #7946 に帰属させない。 | 確認 | commits c81be029、f694e9e1、c4a4f35e |
| 5.052 | `VerilatedContext`/`VerilatedScope` 破棄の segfault 修正(#8265)。`VerilatedScope` のコンストラクタ/デストラクタが自分のコンテキスト(`m_symsp->_vm_contextp__`)を使うようになった。`VerilatedContext` のデストラクタは thread-local に依存しなくなったわけではなく、一時的に自分をセットして末尾で nullptr に戻す。主に複数コンテキスト向けの修正で、svsim の単一コンテキストでは中立である。 | 不確実(修正付き) | `Changes 5.052`(#8265)、commit e012f7c7、`simulation-driver.cpp:1023-1024` |

## 2. 版別の予測結果

### 2.0 前提と用語

- B は svsim 既定の `-j 0` での終了時 abort(exit 255、5.042 以降は 134)である。C は `--verilate-jobs 1`(VliwChiselSim の `--build-jobs 0 --verilate-jobs 1`)での同事象である。
- D はプロジェクト設定(`--build-jobs 0 --verilate-jobs 1`)での svsim 経由 `sbt test` である。
- E は「ADR-0013 の修正なしの sbt test」である。入力に ADR-0013 の定義が無いので、これを VliwChiselSim の jobs 回避策を外し svsim 既定 `-j 0` に戻した状態と解釈した(推定)。E は B の影響を受ける。
- 以下はすべて予測で、実測は 5.020 のみである。
- 5.020 の「≈10%」だけは入力の数値(6/3000、53ビルド)から各ビルド独立と仮定した算術試算である。

### 2.1 B / C / E(終了時 abort)

| 版 | B(`-j 0`) | C(`--verilate-jobs 1`) | E(ADR-0013 回避策なし) | 根拠 |
|---|---|---|---|---|
| 5.020 | 実測 6/3000 が exit 255(ロック保持中の idle worker と単発 try_lock の衝突)。 | 実測 0/3000。mutex 無効・worker 無し。 | 53ビルドの各 verilation が約 0.2% で abort する。valid なビルドが失敗扱いになり、`sbt test` が不定に落ちる。試算では約 10%。 | `v5.020:src/V3ThreadPool.h:142-157`、`v5.020:src/Verilator.cpp:633-636`、`v5.020:src/V3Mutex.h:107-109` |
| 5.024 | 低下する見込みだが 0 とは限らない。busy retry のみで、5.026 の追加修正が必要だった。測定要。 | 0 見込み(mutex 無効・worker 無し)。 | 稀に落ちうる(B と同じ)。 | `Changes 5.024`(#4938)、`v5.024:src/V3ThreadPool.h:142-149`、`v5.024:src/Verilator.cpp:662` |
| 5.026 | 0 見込み。ロックを約 1s 待つが、取れなければ abort 経路は残る。予測であり保証ではない。 | 0 見込み。 | 0 見込み(ただし B の保証なし)。 | `Changes 5.026`(#5040)、`v5.026:src/V3ThreadPool.h:118,148-168` |
| 5.030 | 0 見込み(abort 経路を削除、デストラクタは worker を join)。 | 0 見込み(N==1 は worker 無し、enqueue は inline)。 | 0 見込み。 | `Changes 5.030`(#5161)、`v5.030:src/V3ThreadPool.cpp:23-38`、`v5.030:src/V3Global.cpp:43` |
| 5.032 | 'destroy locked' abort は 0 見込み。V3FuncOpt が worker で走る(5.042 で MT 無効化されたのでリスクあり、reviewer-added, unverified)。他の失敗モードは読解では除外できず測定要。 | 0 見込み(inline 実行)。 | 同上。 | `v5.032:src/V3FuncOpt.cpp:325-331`、`v5.032:src/V3ThreadPool.cpp:25,40-42`、commit b60ad953c |
| 5.034 | 5.032 と同じ(V3FuncOpt の注記を含む)。 | 0 見込み。 | 5.032 と同じ。 | `git diff --stat v5.032 v5.034`、`v5.034:src/Verilator.cpp:667` |
| 5.036 | 5.032 と同じ。 | 0 見込み。 | 5.032 と同じ。`--output-groups` が nproc で有効になるが、abort には無関係。 | `git diff v5.034 v5.036 -- src/V3ThreadPool.*`、`Changes 5.036`(#5751) |
| 5.038 | 5.032 と同じ。 | 0 見込み。 | 5.032 と同じ。 | `v5.038:src/Verilator.cpp:674-677` |
| 5.040 | 5.032 と同じ(V3FuncOpt はこの版まで並列)。 | 0 見込み。 | 5.032 と同じ。 | `v5.040:src/V3FuncOpt.cpp:327-333`、`v5.040:src/Verilator.cpp:779-786` |
| 5.042 | 0 見込み(V3FuncOpt の MT 無効化、`vlExit` が worker を join)。abort が起きた場合は exit 134。判定は exit 255 でなく abort メッセージ/非0終了で行う。 | 0 見込み。 | 0 見込み。 | `Changes 5.042`(#6416 #6554)、`v5.042:src/V3Global.cpp:56-69`、`v5.042:bin/verilator:268` |
| 5.044 | 5.042 と同じ。 | 0 見込み。 | 5.042 と同じ。 | `v5.044:src/V3ThreadPool.cpp:25`、`v5.044:src/V3Error.cpp:102-107` |
| 5.046 | 5.042 と同じ。 | 0 見込み。 | 5.042 と同じ。 | `git diff v5.044 v5.046 -- src/V3ThreadPool.*` |
| 5.048 | 5.042 と同じ。ソースビルドでは jemalloc/tcmalloc の自動リンクが交絡要因である。 | 0 見込み。 | 5.042 と同じ。 | `v5.048:src/V3ThreadPool.cpp:31-38`、`Changes 5.048`(#7250) |
| 5.050 | 5.048 と同じ。 | 0 見込み。 | 5.048 と同じ。 | `git diff --stat v5.048 v5.050 -- src/V3ThreadPool.*`、`Changes 5.050`(#7736) |
| 5.052 | 5.048 と同じ。 | 0 見込み。 | 5.048 と同じ。 | `git diff --quiet v5.050 v5.052 -- src/V3ThreadPool.*`、`v5.052:src/Verilator.cpp:720,926` |

### 2.2 D / H(sbt test と新規警告)

| 版 | D(プロジェクト設定の `sbt test`) | H(新規警告。5.020 は実測 0) | 根拠 |
|---|---|---|---|
| 5.020 | 53 ビルドが走る(基準)。assertion 失敗は SIGABRT で終了する。 | 0(実測)。 | `v5.020:src/V3Error.h:220-256`、`v5.020:include/verilated.cpp:118-171` |
| 5.024 | 通る見込み。 stdout に summary 3 行(`--quiet-stats` で抑止可)。ハーネスが `VERILATOR_ROOT` を export していると即 exit 1。 | 0 見込み(UNUSEDLOOP は既定オフ、PREPROCZERO/ZEROREPL/PROCASSWIRE は該当構文無し)。 | `Changes 5.024`、`v5.024:bin/verilator:72-82`、`v5.024:src/V3Error.h` |
| 5.026 | 通る見込み。`$readmem` の末尾語、packed 配列 select(#5095)の誤コンパイル修正で、5.024 と結果が変わる可能性がある(reviewer-added, unverified)。 | 0 見込み(ゼロ幅 select エラー、NONSTD は `$psprintf` のみ)。 | `Changes 5.026`、`v5.026:src/V3Width.cpp:928`、commit 3cb4033c9 |
| 5.030 | 通る見込み。assertion 失敗は 5.020 と同様に停止する。 | 0 見込み(新規コード無し、hardError は抑止不能になるが svsim は抑止を使わない)。 | `Changes 5.028/5.030`、`v5.030:src/V3Error.h:227-229` |
| 5.032 | 通る見込み。ビルド・インストール済み include/ に `verilated_std_waiver.vlt` が必要。 | 0 見込み(新規コード無し)。 | `Changes 5.032`(#5607)、`v5.032:src/V3Global.cpp:62-66` |
| 5.034 | 通る見込み。 | COVERIGN(既定オン)の新規リスクは、firtool 出力で想定されない。0 見込み。 | `Changes 5.034`、`v5.034:src/verilog.y:6188,6192` |
| 5.036 | 通る見込み。`--output-groups`=nproc(≥16 ファイルのとき)で生成ファイルが nproc に依存する。 | DEFOVERRIDE(既定オン・致命)。svsim の `+define+` マクロを無ガードで同値再定義する firtool 出力のみ該当し、0 見込みだが唯一の新規リスクである。 | `Changes 5.036`(#5751 #5900)、`v5.036:src/V3PreProc.cpp:354`、`v5.036:src/V3EmitMk.cpp:62,66` |
| 5.038 | 通る見込み。`--assert` 既定化は svsim が明示済みなので不変。パーサ大改修(シンボルテーブル除去)が回帰面。 | 0 見込み(PROCASSINIT はオフ、BADVLTPRAGMA/MODMISSING は命名のみ、集約型比較エラーは firtool に無い)。 | `Changes 5.038`、commit f77af4e6、0c8c7fb0 |
| 5.040 | 通る見込み。assertion 失敗は SIGABRT でなく exit(1) で終わる。134 を前提にしたテストや判定は status 1 に変更する。生成 C++ の決定性(複数ブロック comb always)、再利用 Mdir の skip-identical に注意。 | 0 見込み(ALWNEVER/NOEFFECT/SPECIFYIGN/ENUMITEMWIDTH/PARAMNODEFAULT/PROTOTYPEMIS は該当構文無し)。 | `Changes 5.040`(fbaff526 #6109)、`v5.040:include/verilated.cpp:172-176`、`v5.040:src/V3WidthCommit.cpp:183` |
| 5.042 | 通る見込み。DFG の非決定性は修正済み。 | 0 見込み(IMPLICITSTATIC 拡張・NORETURN・ASSIGNEQEXPR は svsim の関数が `function void`、firtool が automatic のため該当しない)。 | `Changes 5.042`、`v5.042:src/V3LinkParse.cpp:~269-294`、`v5.042:src/V3Width.cpp:6329-6334` |
| 5.044 | 通る見込み。V3EmitCSyms の DPI スコープ周りの作り直しが、svsim 固有で最も壊れうる箇所である(reviewer-added, unverified、実走で確認)。 | 0 見込み(SUPERNFIRST のみ)。 | `v5.044:src/V3Error.h:162`、`git diff v5.042 v5.044 --stat -- src/V3EmitC*.cpp` |
| 5.046 | 通る見込み。RNG 変更で、乱数初期値(`RANDOMIZE_*_INIT`)が 5.020 と全シードで異なる。正しくリセットされた設計のサイクル数は不変の見込みだが、未リセット状態への潜在依存は表面化しうる。`__Vm_didInit` の位置変更は静的解析上は svsim の入れ子 eval に影響しない。 | IMPLICITSTATIC が initial/final に拡張された(致命)。firtool の automatic ローカルと for ヘッダ変数は該当しない見込みで、これが唯一の新規リスクである。 | `Changes 5.046`(#6956 #7020)、`v5.046:src/V3LinkParse.cpp:345-349`、commit bc3c5b32d |
| 5.048 | 通る見込み。非 trace フローで影響する変更は無い。FST + `traceThreads` 設定は致命 DEPRECATED で失敗する。 | 0 見込み(DEPRECATED は svsim 不使用オプションのみ)。DFG 再構成による UNOPTFLAT 増減は reviewer-added, unverified。 | `Changes 5.048`(#7443)、`v5.048:src/V3Options.cpp:1825-1827,1741-1744`、`v5.048:src/V3Width.cpp:8438` |
| 5.050 | 通る見込み。既定 seed 1 は、プロジェクトが常に `+verilator+seed+<n>`(n≥1)を渡すので無影響。 | E_CONTASSINIT・NOTREDOP・MULTIDRIVEN(always_ff)は svsim/firtool の構文に該当しない見込み。0 見込み。 | `Changes 5.050`、`v5.050:src/V3Undriven.cpp:231-233,573`、`v5.050:src/V3ParseImp.cpp:914-917` |
| 5.052 | 通る見込み。eval ループがランタイムへ移動したため、53 ビルド全てのリビルドによる実走確認が要る。 | 0 見込み(MULTIDRIVENPROC/SIMILARNAME はオフ、NEVERMATCH は SVA intersect/within のみ)。 | commit 8546d5db、`v5.052:src/V3Error.h:300-305`、`Changes 5.052` |

### 2.3 F(seed 挙動)

出力は 5.020 の測定と同じ印字順(生成順の逆)で示した。印字順は 5.020 の測定からの外挿で、g++ の引数評価順が右から左であることを仮定している。`$urandom` の設計内の割当ては、初期ブロックの実行順が不変であることが前提である。5.040 は内部リファクタで入れ替わりうる。

| 版 | seed 無しは毎回同一か | seed 1 は 5.020 と同値か | 予測値(seed 無し / seed 1 / seed 2) | 根拠 |
|---|---|---|---|---|
| 5.020 | 同一(実測、未種 `lrand48`) | 基準 | 48c2e0e4 9efdd502 0b1e5d9c 797673c4 / 8084023f f77f7fff ff7fffff fffffffc / f1080460 eeff3fff feffffff fffffffa(実測)。seed 0 は拒否。 | `v5.020:include/verilated.cpp:334-364,2725-2727,2791-2797` |
| 5.024〜5.044(5.024, 5.026, 5.030, 5.032, 5.034, 5.036, 5.038, 5.040, 5.042, 5.044) | 同一 | 同値(RNG 実装は 5.020 と byte 一致、seed 0 は拒否のまま) | 5.020 と同じ3行。 | `v5.024:include/verilated.cpp:2784-2786,2850`、`v5.030:include/verilated.cpp:353-380`、`v5.038:include/verilated.cpp:2964`、`v5.040:include/verilated.cpp:3027-3033`、`v5.042:include/verilated.cpp:382-398,3005`、`v5.044:include/verilated.cpp:378,391-396,3005,3071-3077` |
| 5.046 | 同一(`lrand48` 経由で決定的)。ただし値は変わる。 | 異なる(Xoroshiro128** + SplitMix64 に置換、全値が変化、全1偏りは消失) | 73b2233c a107c0d1 613e3743 aadb0c9a / b722072d fd8d9d77 24800459 5fa24450 / fded6676 d3189547 516497e1 1be5aac4。seed 0 は拒否。seed 1/2 は独立再実装で検証済みで、seed 無しの具体値は 5.046 レビューでは再現、5.048 研究者は再現できず。値は不確実、決定性は確実。 | `Changes 5.046`(#6956)、`v5.046:include/verilated.cpp:323,339,345,374-375,3157` |
| 5.048 | 同一 | 異なる(5.046 と同値) | 5.046 と同じ3行。プロセス別 RNG はプロセス無しなら不活性。 | `Changes 5.048`(#7408)、`v5.048:include/verilated.cpp:336-339,3263-3265`、`v5.048:include/verilated_types.h:218` |
| 5.050 | 同一、かつ seed 1 と同一出力。 | 異なる(5.046 と同値) | seed 無し = seed 1:b722072d fd8d9d77 24800459 5fa24450 / seed 2:fded6676 d3189547 516497e1 1be5aac4。`+verilator+seed+0` は受理され、実行ごとに異なる。 | `Changes 5.050`(#7516)、`v5.050:include/verilated.h:416`、`v5.050:include/verilated.cpp:3595,3602,3677-3682` |
| 5.052 | 5.050 と同じ。 | 異なる(5.050 と同じ) | 5.050 と同じ。 | `v5.052:include/verilated.h:528`、`v5.052:include/verilated.cpp:3621-3629,3703` |

## 3. 留意点

- 「保証ではなく予測」の箇所は表中に付記した。特に 5.024・5.026 の `-j 0` は測定で確定する必要がある(5.024 レビュー、5.026 レビューの指摘)。
- 5.046 の「seed 無し」具体値は、5.046 のレビューが独立再実装で再現したのに対し、5.048 の研究者は 5.020 の seed 無し値を再現できず、評価が割れている。決定性(毎回同一)のみが確定事項である。
- E の定義(ADR-0013)は入力に無く、推定である。
