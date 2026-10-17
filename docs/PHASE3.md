# Phase 3 完了報告

完了日：2026-10-17（日本時間） / マイルストーン：Milestone 3（追跡Issue #66）

## 完了条件と根拠

[ROADMAP.md](ROADMAP.md) Phase 3 の完了条件「プロローグ・定常・エピローグを検証しIIを測定」と、#66 の完了条件を、次のテストと記録で確認した。mutation を除き、テストは GitHub Actions の `scala` ジョブ（`scripts/check.sh scala`、Verilator 5.020）で実行される。#74 の「ChiselSim specs mix in VliwChiselSim (#74)」だけは、`static` ジョブ（`scripts/check.sh static`）の検査である。

コードを変えた最後の main は 296697a（#108 のマージコミット）である。#71 のマージで main は進むが、変わるのは docs だけである。296697a の CI（run 38994779213）では、`static` と `scala` がともに成功した。`scala` は 374 テストが成功し、失敗 0、ignored 14 である（`scripts/check.sh scala` は約9分）。ignored はすべて RTL の範囲外の構成である（注2）。`static` と `scala` は、repository ruleset「base」の required status checks である。

| 完了条件（#66） | 根拠となるテスト・記録 | 状態 |
|---|---|---|
| `saxpy8`・`fir4x8`・`matmul3` をループを持つ IR で表す（ループにした段と反復回数を記録する）。Modulo Scheduling した命令列が最終の verifier（L1〜L10）に合格し、参照モデル（ii が2以上の構成を含む）と、RTL の対応範囲内の構成の RTL で、結果と cycles が一致する。収まらない・置けないカーネルは理由付きで記録する（#76、#68） | ループにした段と反復は [COMPILER.md](COMPILER.md)「ループ IR」の表と [benchmarks/MODULO.md](../benchmarks/MODULO.md) にある（`saxpy8` は出力 i で 8 回、`fir4x8` は出力 i で 8 回、`matmul3` は行 i で 3 回と、m の累算で 2 回）。`compiler/.../loop/LoopKernelSpec`「the loop benchmarks unroll to the Phase 2 kernels exactly」。`ModuloCompiler` は最終の verifier（`KernelCompiler.checked`）に合格した命令列だけを返す。`crosscheck/ModuloKernelSpec`「on the reference model: DSL results, the scheduled cycles, and the II in the trace」（既定、`slow_units`、`ii_units`、`regs3`）と「on the RTL (default machine): DSL results, the scheduled cycles, and the II in the trace」。`crosscheck/BenchmarkSpec`「the Modulo Scheduling test cases hold on the reference model and the RTL」。収まらないものは MODULO.md「反復回数の系列」「他のループと構成」と #81 のコメント（2026-10-16）。`compiler/.../modulo/ModuloSchedulerSpec`「the instruction memory: saxpy(83) fits 256 bundles, saxpy(84) is an error with no program」「matmul3 accumulated over m does not fit slow_units: 17 values stay live at every II」「too few registers: the II goes up, and a loop that fits no II is an error with the reasons」 | 達成（注1、注2、注3） |
| 定常状態の II を、参照モデルと RTL の実行トレースで確かめて `ii` 欄に記録し、ResMII・RecMII と比べる。受入テストに RecMII = 0 と RecMII > 0 のループを含める。カーネルごとに、パイプライン化したループとその RecMII を記録する。list scheduling とは、#79 の戦略を固定した系列で比べる（#68） | `crosscheck/ModuloTrace` が、トレースを、表 (bundle, スロット) → (ノード, 反復) とループのグラフだけで照合し、II を確かめる。スケジュール・レジスタ・展開した DAG は使わない（ADR-0015、[COMPILER.md](COMPILER.md)「トレースによる II の確認」）。記録の `ii` は、参照モデルのトレースで確かめた値である。`ModuloKernelSpec`「the trace check finds a wrong table, address, producer, commit or II」「the trace check rejects correct code whose iterations do not start II apart」。`BenchmarkSpec`「the Modulo Scheduling records use the list-scheduling layout and inputs, and the II」。RecMII = 0 は `saxpy8`・`fir4x8`・`matmul3`（i）、RecMII > 0 は `matmul3`（m）（1）、`product8`（2 = II）、`dot8`（1）である。`ModuloSchedulerSpec`「MII, II, the attempts before it, and the cycles of the recorded loops on three machines」「hand-computed: product(4), three iterations through MUL, II 2 = RecMII」「hand-computed: dot(4), MUL and ADD per iteration, II 3 > MII 2 (slot and port)」。`ModuloKernelSpec`「hand-computed: product(4), issue and commits of the prologue, steady state and epilogue」「hand-computed: dot(4), issue and commits with II 3」。比較は MODULO.md「ループごとの結果」「list scheduling（戦略を固定した系列）との比較」 | 達成（注2、注3） |
| MAC のある構成とない構成を、同じカーネル・入力・配置・スケジュール戦略で比べた記録がある。list scheduling の戦略を固定した系列と、Modulo Scheduling の両方で比べ、ループの II と ResMII・RecMII を記録する（#77） | [benchmarks/MAC.md](../benchmarks/MAC.md)（mvp-0・mvp-1・rp4・mac3。list scheduling の2つの系列、読出しポートの制約、Modulo Scheduling の融合の判定と ResMII・RecMII・MII・II）。`BenchmarkSpec`「the configurations with MAC differ from config/machine-mvp1.json only as recorded」「the records with MAC use mvp-0's layout and inputs, and fuse every MUL + ADD pair」「each rp4 program has one L11 finding on mvp-1 per bundle with MAC and ST, and no other」「the test cases with MAC hold on the reference model, and on the RTL with the same trace」。`ModuloSchedulerSpec`「mvp-1: MII, II, the fusion choice and the cycles of the recorded loops on three machines」。`ModuloKernelSpec`「ISA mvp-1 on the reference model: fusion by the MII, MAC in the code, DSL results, the scheduled cycles, and the II in the trace」と「ISA mvp-1 on the RTL, vliw2-i32-sp2-mac: DSL results, the scheduled cycles, and the II」（mac3 も） | 達成（注4） |
| rematerialization により、レジスタ 8・12・16 本の構成で3カーネルを割り当てられ、参照モデルと RTL で結果と cycles が一致し、cycles が記録されている。割り当てられない構成は理由とともに記録する（#70） | [benchmarks/REGISTERS.md](../benchmarks/REGISTERS.md)。方式ごとに cycles 最小の系列は、3カーネル × 2方式 × 3つのレジスタ数がすべて成立する。戦略を固定した系列の不成立の行は理由付きで記録した。`compiler/.../regalloc/RematerializationSpec`「at 8, 12 and 16 registers, every kernel compiles and every program passes the verifier」「cycles and LDs at 8, 12 and 16 registers on the default machine」。`crosscheck/CompiledKernelSpec`「on the RTL, compiled kernels compute the DSL results in the scheduled cycles: vliw2-i32-sp2-regs8」（regs12 も）。`BenchmarkSpec`「the register-count test cases hold on the reference model and the RTL」。mvp-1 の 16・12・8 本は `compiler/.../MacCompilerSpec`「the benchmarks compile on the mvp-1 machines at 16, 12 and 8 registers」と、`CompiledKernelSpec` の「ISA mvp-1」の3件（参照モデルと、RTL の vliw2-i32-sp2-mac・vliw2-i32-sp2-mac3） | 達成（注5） |
| 資源条件を揃えた固定パイプラインが ChiselSim で DSL の参照実装と一致し、cycles が同じ数え方で記録されている（#75） | [benchmarks/RESULTS.md](../benchmarks/RESULTS.md)「資源条件を揃えた固定パイプライン（#75）」、ADR-0017。cycles は `CoreDriver` と同じく、start の受理から done までを数える。`crosscheck/FixedPipelineKernelSpec`「every plan is legal and touches the same words as the kernel's VLIW program」と、カーネル・系列ごとの「saxpy8: DSL reference outputs in 26 cycles (...)」など6件（乱数・全0・全1の入力と、FIR の係数を変えた場合）。`BenchmarkSpec`「the fixed pipelines give the recorded outputs in the recorded cycles on ChiselSim」「the fixed pipelines are measured with the Verilator and JDK that the records name」。mutant 5つ（下の「mutation」） | 達成 |
| 上記のうち、命令列・参照モデル・RTL の一致、`ii` と cycles の記録、固定パイプラインの一致が CI で自動検証される | `scala` ジョブ（scalafmt の検査と `sbt test`）が上の全スペックを実行する。`BenchmarkSpec`「benchmarks/ holds exactly the generated records」が、コミットした記録（RESULTS.md・MODULO.md・REGISTERS.md・MAC.md、`cases/`、`metrics/`）と、ソースから生成し直した内容の一致を確かめる。main 296697a の run 38994779213 で成功した | 達成（注2） |
| 間欠的な ChiselSim の失敗について、原因、または再現できなかった範囲が記録されている。原因に対処した場合は、その回帰テストも CI に含める（#74） | 原因は Verilator 5.020 の、複数スレッドの verilation の終了時の abort（upstream issue 4931）である。ADR-0013、#74 のコメント「再現試験の結果（2026-10-15）」（Repeat tests の run 38712525034・38713082086。対処の後、全体に相当する実行 112 回で失敗 0）、[hw/README.md](../hw/README.md)。対処は `VliwChiselSim`（1スレッドで verilate し、seed を明示して記録する）。回帰テストは `hw/VliwChiselSimSpec`「Verilator verilates in one thread and builds on all cores」「every simulation records its seed and is built and replayed with the project's settings」と、`static` の「ChiselSim specs mix in VliwChiselSim (#74)」 | 達成 |
| 最終の main のコミットで mutation ジョブを実行し、そのコミットと結果が記録されている（#71） | 下の「mutation」。53 mutant で、killed 53・survived 0・ERROR 0・stale 0。PR #108 の head b7d044d（tree は 296697a と同じ）での結果の引用で、最終の main（#71 のマージ）や 296697a での再実行ではない | 同じ tree の結果を引用 |

- 注1: verifier は今、L1〜L11 を検査する。L11（読出しポート）は #69 で決め（ADR-0018）、#77 で verifier に加えた規則で、mvp-0 では常に満たされる。#66 の L1〜L10 を含む
- 注2: RTL の対応範囲は、全演算の ii 1、LD の latency 2 以上、ST の latency 1、read_ports 3 である。`slow_units`・`ii_units`・rp4 は、参照モデルと verifier だけで確かめた。CI の ignored 14 件はすべてこれである（hw の `AssertionSpec` 6、`CaseSpec` 4、crosscheck の `RtlDifferentialSpec` 1、`ModuloKernelSpec` 3）。RTL のトレースで II を確かめたのも、既定・mvp-1・mac3 の構成だけである
- 注3: パイプライン化したのは、各カーネルで1段だけである。内側の段（FIR のタップ、`matmul3` の行のループの j と m）は本体に展開した。FIR のタップのループは作らない（ADR-0014。係数は値で重複を除いた定数なので、アドレスが反復番号の一次式にならない）。ベンチマークの3カーネルでは、RecMII は II を決めない（`matmul3`（m）は RecMII 1、ResMII 18）。RecMII が II を決めるのは、受入テストの `product8` だけである
- 注4: MAC のある構成の記録は `vliw-metrics/2` である（`ii` の欄の意味は同じ。[METRICS.md](METRICS.md)）。融合は戦略の次元ではないので、MAC のある構成の DAG は演算数が違う。同じ行の比は「MAC と融合」の効果である
- 注5: RTL で確かめるのは、方式ごとに cycles 最小の系列の記録だけである。戦略を固定した系列の各行は、参照モデルだけで確かめる。mvp-1 の RTL のテストは、16 本の machine で 12・8 本の命令列を実行する

## 実装

| 段 | 内容 | 文書 | Issue（PR） |
|---|---|---|---|
| 間欠的な失敗の対処 | `VliwChiselSim`。Verilator に `--build-jobs 0 --verilate-jobs 1` を渡し、seed を明示して記録する。CI の失敗時にログを artifact に残す | ADR-0013、[hw/README.md](../hw/README.md) | #74（#83、#84、#102） |
| 戦略を固定した系列 | コンパイラの各試行（優先度 × レジスタ上限 × load reserve）の cycles を、方式ごとに cycles 最小の系列と並べて記録する | [benchmarks/README.md](../benchmarks/README.md) | #79（#101） |
| ループ IR | 1重の数え上げのループ。添字とアドレスは反復番号の一次式。反復距離付きの依存。状態はレジスタで渡す。展開すると Phase 2 のカーネルと同じになる | [COMPILER.md](COMPILER.md)「ループ IR」、[DSL.md](DSL.md)、ADR-0014 | #76（#103） |
| Modulo Scheduling | MII（ResMII・RecMII）、予約表への配置（`RowFit`）、II を MII から順に試す探索、直線の命令列への展開（案A）、最終の verifier。II はトレースで確かめる（`ModuloTrace`） | [COMPILER.md](COMPILER.md)「Modulo Scheduling」、ADR-0011、ADR-0015 | #68（#104） |
| rematerialization | 行き詰まる試行でだけ、書込みのない語の LD を複製する | [COMPILER.md](COMPILER.md)「rematerialization」、ADR-0016 | #70（#105） |
| 固定パイプライン | `FixedPipeline`。スケジュールから生成し、Core の演算器と Scratchpad をそのまま使う。ChiselSim で機能と cycles を確かめる | [benchmarks/RESULTS.md](../benchmarks/RESULTS.md)、ADR-0017 | #75（#106） |
| MAC の契約 | ISA mvp-1 = mvp-0 + MAC（`rd = low32(rs1 × rs2 + rs3)`、latency 2・II 1）。読出しポートは3本のままで、規則 L11 で数える | [ISA.md](ISA.md)、[MACHINE.md](MACHINE.md)、ADR-0018 | #69（#107） |
| MAC の実装と比較 | ローダ・符号化・verifier・参照モデル・RTL・`vliw-metrics/2`。読む側が1つの MUL と、それを読む ADD を MAC に融合する。ループは MII が上がるときは融合しない。4つの構成で比べる | [COMPILER.md](COMPILER.md)、[METRICS.md](METRICS.md)、[benchmarks/MAC.md](../benchmarks/MAC.md)、ADR-0019 | #77（#108） |

Phase 3 の最初に、ループの実行モデル（案A、ADR-0011）と、MAC を先に入れる方針（ADR-0012）を記録した（#67、#69、PR #82）。

## ベンチマークの結果

条件と全項目は [benchmarks/MODULO.md](../benchmarks/MODULO.md)、[benchmarks/MAC.md](../benchmarks/MAC.md)、[benchmarks/REGISTERS.md](../benchmarks/REGISTERS.md)、[benchmarks/RESULTS.md](../benchmarks/RESULTS.md) にある。**すべて cycle ベースの比較**で、Fmax・面積・電力は測っていない（#57、#80）。cycle の比を実時間の速度比と読まない。

### 測定の条件

| 項目 | 値 |
|---|---|
| 数え方 | 参照モデル（`vliw.sim.ReferenceModel`）の cycles。RTL の範囲内の構成では、方式ごとに cycles 最小の系列と Modulo Scheduling の記録が RTL でも同じ出力と cycles になることを、`BenchmarkSpec` が確かめる。戦略を固定した系列の各行は、参照モデルだけで確かめる。固定パイプラインの cycles は ChiselSim で、start の受理から done までを数える |
| カーネル | `vliw.compiler.dsl.Benchmarks`（`saxpy8`、`fir4x8`、`matmul3`）。ループは `vliw.compiler.loop.LoopBenchmarks`（展開すると同じカーネル） |
| 配置・入力 | `MemoryLayout.fixedOrder`、`KernelCompiler.sampleInputs(kernel, 1)`。結果と cycles は入力値によらない |
| list scheduling の戦略 | 優先度（critical-path、program-order）×（上限なし、上限 = レジスタ数で load reserve 0〜レジスタ数/2）。系列は「方式ごとに cycles 最小」と「戦略を固定」（#79）。rematerialization は行き詰まる試行だけ（ADR-0016） |
| Modulo Scheduling の戦略 | 1つ。II = MII から探索の上限まで順に試し、最初に命令列ができた II を使う（ADR-0015）。cycles 最小の II とは限らない |
| `ii` | ループの II（反復の開始間隔）。参照モデルのトレースで確かめた値を記録する（`ModuloTrace`）。演算器の II（`operations.<op>.ii`）とは別のもの |
| レジスタ | 16 本（レジスタ数の比較を除く） |
| ツール（固定パイプライン） | Chisel 7.15.0、firtool 1.158.0、Verilator 5.020、Scala 2.13.18、JDK 21 |

| 構成 | machine | 違い | 記録 |
|---|---|---|---|
| mvp-0（既定） | `vliw2-i32-sp2` revision 2（frozen）、`sha256:a4af6064e049415dc9822614264a01f06b6e1be39c86bc41c5fcdda3e7d89e21` | - | RESULTS.md、MODULO.md、REGISTERS.md、MAC.md |
| mvp-1 | `vliw2-i32-sp2-mac` revision 1（frozen）、`sha256:90f6a9667a8b9fa7be448117438c04ff40a1af6495f13c0b8d8d1ccd95f89162` | MAC（latency 2、II 1）、read_ports 3 | MAC.md |
| rp4 | `vliw2-i32-sp2-mac-rp4` revision 1（proposed）、`sha256:600710348640442ebaba9d0df2dbef62949eab15f948d42ca899924051f4226b` | mvp-1 で read_ports 4（RTL なし） | MAC.md |
| mac3 | `vliw2-i32-sp2-mac3` revision 1（proposed）、`sha256:2abe65317bed4df87bb33c2b0ad8e600a16c873aeac7962fa8b0e65164e84c89` | mvp-1 で MAC の latency 3 | MAC.md |
| regs8 | `vliw2-i32-sp2-regs8` revision 1、`sha256:663c8469d133d76b8fe21a5cb3f9aeb2f8b167e7edc9a67899b4f00b1ab8a97e` | レジスタ 8 本 | REGISTERS.md |
| regs12 | `vliw2-i32-sp2-regs12` revision 1、`sha256:c9792896adb32bc6028ec699b6b046272e10d00f89de2eb5e8c5835bb543fca4` | レジスタ 12 本 | REGISTERS.md |
| slow・ii・regs3 | `vliw2-i32-sp2-slow` rev 1（`sha256:bf5cc152c727…`）、`vliw2-i32-sp2-ii` rev 1（`sha256:644ed9a5ad49…`）、`vliw2-i32-sp2-regs3` rev 1（`sha256:dd9bc1e79bff…`） | latency、演算器の II、レジスタ 3 本 | MODULO.md「他のループと構成」（参照モデルだけ） |

### Modulo Scheduling と list scheduling（mvp-0）

list scheduling は、方式ごとに cycles 最小の系列の vliw の値である。比は、その試行の行の値（MODULO.md）である。

| ループ | 反復 | ResMII（律速の資源） | RecMII | II（MII を超えた理由） | cycles | list scheduling（試行） | Modulo / list |
|---|---|---|---|---|---|---|---|
| `saxpy8`（i） | 8 | 3（Memory スロット） | 0 | 3 | 29 | 26（critical-path, no register limit） | 1.12 |
| `fir4x8`（i） | 8 | 7（Compute スロット、Compute の書戻しポート） | 0 | 8（II 7: (a)） | 76 | 65（program-order, 16 registers, load reserve 4） | 1.17 |
| `matmul3`（i） | 3 | 15（同上） | 0 | 20（II 15: (a)、II 16〜19: (b)） | 87 | 56（critical-path, 16 registers, load reserve 1） | 1.55 |
| `matmul3`（m） | 2 | 18（同上） | 1 | 27（II 18: (a)、II 19〜26: (b)） | 68 | 56（同上） | 1.21 |

- (a) は II 行の予約表が無いこと、(b) は同時に生きる値がレジスタ数（16）を超えること
- 4つとも、list scheduling の cycles 最小より cycles が多い。反復が 2〜8 と少なく、プロローグとエピローグの分が大きい
- 戦略を固定した系列には、Modulo Scheduling より cycles の多い行もある。`fir4x8` の critical-path, 16, reserve 2〜5（84・84・83・87 cycles、LD 52・52・54・53。比 0.90・0.90・0.92・0.87）と、`matmul3`（m）と比べた critical-path, 16, reserve 5（72 cycles、LD 46。比 0.94）である。どれも LD を複製した行で、Modulo Scheduling の LD は 15・18 である
- LD の数は list scheduling と同じである（`saxpy8` 1 + 2 × 8 = 17、`fir4x8` 7 + 1 × 8 = 15、`matmul3`（i）9 + 3 × 3 = 18、`matmul3`（m）6 + 6 × 2 = 18）
- 受入テストのループ（既定の構成）: `product8`（i）は II 2 = RecMII 2 で 19 cycles。`dot8`（i）は MII 2、II 3（II 2: (a)）で 28 cycles
- 演算器の II が律速の例: `ii_units` の `saxpy8`（i）は ResMII 6（LD の演算器）、II 6 で 54 cycles
- `saxpy(n)` の系列（#81 の材料）: n = 8・16・32・64 で II 3、29・53・101・197 bundle（= cycles）。n = 128 は II 3〜6 で 389・517・645・770 bundle となり、256 を超えて不成立。命令領域に収まる最大の n は 83 である

### MAC のある構成とない構成（ISA mvp-1）

融合した組は `saxpy8` 8、`fir4x8` 24、`matmul3` 18 である。乗算の数（MUL + MAC）は変わらない。

list scheduling、方式ごとに cycles 最小の系列（選ばれた試行は構成で違う。4つの構成で同じなのは `saxpy8`（vliw）だけ）:

| カーネル | 方式 | mvp-0 | mvp-1 | rp4 | mac3 |
|---|---|---|---|---|---|
| `saxpy8` | vliw | 26 | 27 | 26 | 27 |
| `fir4x8` | vliw | 65 | 40 | 38（LD 27） | 44（LD 27） |
| `matmul3` | vliw | 56 | 39 | 33 | 40 |
| `saxpy8` | single | 42 | 34 | 34 | 34 |
| `fir4x8` | single | 81 | 58 | 58 | 58（LD 17） |
| `matmul3` | single | 75 | 55 | 55 | 55 |

戦略を固定した系列（同じ試行の行どうし）:

- 例: `fir4x8`（vliw）の program-order, 16, reserve 4 は 65 / 40 / 40 / 53（mvp-0 / mvp-1 / rp4 / mac3。mvp-0 に対する比 0.62 / 0.62 / 0.82）。`matmul3`（vliw）の critical-path, 16, reserve 1 は 56 / 40 / 33 / 40（0.71 / 0.59 / 0.71）。`saxpy8`（vliw）の critical-path, 上限なしは 26 / 27 / 26 / 27（1.04 / 1.00 / 1.04）
- mvp-1 と mvp-0 の両方で成立する行: `saxpy8`（vliw）は 19 行で、mvp-1 の cycles が少ない 1、多い 7、同じ 11。`saxpy8`（single）は 18 行すべてで mvp-1 の cycles が少ない。`fir4x8`（vliw）は 14 行、`fir4x8`（single）は 15 行、`matmul3`（vliw）は 15 行、`matmul3`（single）は 14 行で、すべて mvp-1 の cycles が少ない
- 読出しポートの制約（mvp-1 − rp4、vliw）: 両方で成立する行のうち、mvp-1 の cycles が多いのは `saxpy8` 20 行中 10 行（差の最大 1）、`fir4x8` 16 行中 7 行（最大 6）、`matmul3` 15 行中 7 行（最大 7）である。単一発行では、制約は当たらない

Modulo Scheduling（II / cycles）:

| ループ | mvp-0 | mvp-1 | rp4 | mac3 |
|---|---|---|---|---|
| `saxpy8`（i） | 3 / 29 | 3 / 29 | 3 / 29 | 3 / 32 |
| `fir4x8`（i） | 8 / 76 | 4 / 46 | 4 / 43 | 5 / 60 |
| `matmul3`（i） | 20 / 87 | 9 / 38 | 9 / 45 | 10 / 39 |
| `matmul3`（m） | 27 / 68 | 9 / 40 | 9 / 34 | 9 / 40 |
| `product8`（i） | 2 / 19 | 2 / 19 | 2 / 19 | 2 / 19 |
| `dot8`（i） | 3 / 28 | 2 / 21 | 2 / 21 | 3 / 28 |

- ResMII（mvp-0 → mvp-1）は、`fir4x8`（i）7 → 4、`matmul3`（i）15 → 9、`matmul3`（m）18 → 9 である。ADR-0018 の見積りと同じである
- RecMII は、`matmul3`（m）で mvp-0 1、mvp-1・rp4 2、mac3 3 である（ResMII 9 より小さい）。`dot8` は mac3 では融合しない（融合すると MII が 2 から 3 に上がる）。`product8` には融合する組が無い
- mvp-1 の `fir4x8`（i）・`matmul3`（i）・`matmul3`（m）の律速は、Compute スロット・Compute の書戻しポート・3本目の読出しポートである（`matmul3`（m）は MAC の演算器も）。MAC の後も Compute 側が律速である
- mvp-1 − rp4 は、`fir4x8`（i）3 cycles、`matmul3`（m）6 cycles である。`matmul3`（i）は −7 cycles（rp4 の方が多い）で、読出しポートの制約ではなく配置の手順の効果である

### レジスタ 8・12・16 本（rematerialization）

方式ごとに cycles 最小の系列（mvp-0）である。括弧は LD の数で、カーネルの DAG では `saxpy8` 17、`fir4x8` 15、`matmul3` 18 である。

| カーネル | 方式 | 8 本 | 12 本 | 16 本 | 16 本に対する比（8 / 12） |
|---|---|---|---|---|---|
| `saxpy8` | vliw | 28 | 26 | 26 | 1.08 / 1.00 |
| `fir4x8` | vliw | 70（LD 55） | 66（LD 45） | 65 | 1.08 / 1.02 |
| `matmul3` | vliw | 61（LD 44） | 55（LD 37） | 56 | 1.09 / 0.98 |
| `saxpy8` | single | 43 | 42 | 42 | 1.02 / 1.00 |
| `fir4x8` | single | 92（LD 26） | 81 | 81 | 1.14 / 1.00 |
| `matmul3` | single | 88（LD 31） | 80（LD 24） | 75 | 1.17 / 1.07 |

- 選ばれた試行はレジスタ数で違うことがある。比は、試行と LD の複製の効果も含む。`matmul3`（vliw）は 12 本の方が cycles が少ない（複製を作る条件の効果。ADR-0016）
- 戦略を固定した系列の不成立の行（vliw、8 本）は、`saxpy8` 6、`fir4x8` 7、`matmul3` 7 である。理由付きで記録した
- mvp-1（16 / 12 / 8 本。[COMPILER.md](COMPILER.md)「結果（mvp-1、8・12・16 レジスタ、#77）」）: `saxpy8` 27 / 27 / 27、`fir4x8` 40 / 40 / 40（LD 15 / 15 / 29）、`matmul3` 39 / 39 / 49（LD 18 / 24 / 32）

### 資源条件を揃えた固定パイプライン（mvp-0、ChiselSim）

cycles は start の受理（E_0）から done までである。

| カーネル | single | vliw | 固定: VLIW の命令列 | 固定: 上限なし | 下限（固定） |
|---|---|---|---|---|---|
| `saxpy8` | 42 | 26 | 26 | 26 | 26（memory） |
| `fir4x8` | 81 | 65 | 65 | 63 | 62（compute + L5） |
| `matmul3` | 75 | 56 | 56 | 52 | 51（compute + L5） |

- 「VLIW の命令列」は、VLIW と同じスケジュールとレジスタで、cycles も同じである。違いは命令メモリ・制御・RF で、その面積と Fmax は #80 で比べる
- 「上限なし」との差は、RF の容量（16 本）の効果である。値レジスタは `saxpy8` 10、`fir4x8` 44、`matmul3` 38 本である（VLIW の命令列は 11・16・16 本）

## mutation

| 項目 | 値 |
|---|---|
| 引用する run | Mutation check run 38989929697（`.github/workflows/mutation.yml`、event `pull_request`、conclusion success） |
| 対象のコミット | PR #108 の head `b7d044d7fc9f74a89796317a3b8b55d5c7dc5b2a` |
| 分割 | 8 shard（`MUTATION_SHARD` k/8）。shard 1〜5 は 7 個、6〜8 は 6 個。各 shard は、最初に mutant を入れない実行を行う |
| mutant | 53（verifier 11、参照モデル 13、RTL・decoder 10、固定パイプライン 5、エンコーダ 2、コンパイラ 12）。Phase 2 の末は 15 |
| 結果 | killed 53、survived 0、ERROR 0、stale 0。8 shard とも success |
| 時間 | mutation のステップは shard ごとに 35〜69 分。run 全体で約69分 |

**最終の main（#71 のマージコミット）でも、コードを変えた最後の main（296697a）でも、再実行ではない。** #71 の作業からは、workflow_dispatch で mutation ジョブを起動できなかった（403）。そのため、同じ tree の結果を引用する。

- 296697a は #108 のマージコミットで、親は 1e80b49 と b7d044d である。2つのコミットの tree は同じ（`c17f4faee8d4bb995fa07f68286363993e3fff0a`）で、`git diff b7d044d 296697a --stat` は空である
- `pull_request` の run は、PR とその時の main をマージした結果（`refs/pull/108/merge`）を試す。その時の main 1e80b49 は b7d044d の祖先なので、試した tree も同じである
- #71 の PR は docs（このファイル、ROADMAP.md、DESIGN.md、README.md）だけを変える。マージした後の main でも、コードと mutant は上の run と同じである
- mutation ジョブは required ではない。手動の実行と、mutant やスクリプトを変えた PR で動く

## 既知の制約

| 制約 | 影響 | 引き継ぎ先 |
|---|---|---|
| 比較は cycle ベースだけ | Fmax・面積・電力は測っていない。`metrics` の `rtl` は null のまま。cycle の比を実時間の速度比と読まない | #57、#80 |
| MUL と MAC は物理的な多段パイプラインではない。RTL の MAC は、出力する Verilog で乗算器を MUL と共有する（firtool。ADR-0018 の補足） | MAC の演算器の面積を測るときに考慮する | #57 |
| Modulo Scheduling は1つの戦略（II を MII から順に試し、最初に命令列ができた II）。配置は番号の順・後戻りなしで、置けないときだけ先読みする | cycles 最小を探さない。cycles は II について単調でない（`matmul3`（i）は II 20 で 87、II 21 で 70 cycles）。rp4 と mvp-1 の `matmul3`（i）の差（7 cycles）も配置の手順の効果 | 必要になったときに改良する |
| Modulo Scheduling は spill も rematerialization もしない | レジスタが足りないと II が上がる。どの II にも収まらないループは不成立（`slow_units` の `matmul3`（m）、`regs3` の `dot8`） | 必要になったときに改良する |
| 記録したループは反復が 2〜8 と少ない | 4つとも list scheduling の cycles 最小より cycles が多い | 反復の多いループは Phase 4（#114、#116） |
| 各カーネルでパイプライン化したのは1段だけ。ループ IR は1重の数え上げのループ | 内側の段は本体に展開する。FIR のタップのループは作らない（ADR-0014） | — |
| 案A（直線に展開）。命令領域は 256 bundle | 命令領域が反復回数を制限する（`saxpy(n)` は n ≦ 83。Scratchpad には n ≦ 170 が収まる） | #81（Milestone 4 で見積もる） |
| `RowFit` の正確な判定は、II ≦ 62 で、探索が10万手以内のときだけ | それ以外は「決まらない」と記録する | — |
| read_ports 3 の list scheduling は素朴な方法に固定した（MAC の bundle では ST を待たせる。ADR-0019）。読出しポートの方式は暫定 | 戦略を固定した系列で、mvp-1 は rp4 より最大 1・6・7 cycles 多い（`saxpy8`・`fir4x8`・`matmul3`、vliw） | #57 の結果で見直す（ADR-0012、ADR-0018） |
| rp4（read_ports 4）の RTL は無い | rp4 の値は参照モデルと verifier だけで確かめた | #57 |
| MAC への融合の規則は固定で、戦略の次元にしない | 依存が律速のカーネルでは、MAC で遅くなりうる（直線の `dot16` の試算で、MAC なし 42 cycles、latency 2 で 37 cycles、latency 3 で 52 cycles） | — |
| MAC の後も、`fir4x8`・`matmul3` のループの律速は Compute スロットと書戻しポート（と3本目の読出しポート） | Compute 側の改善が残る。一方、list scheduling の `saxpy8` は Memory スロットが律速である | #78（Milestone 4 の最初） |
| rematerialization は list scheduling だけで、書込みのない語の LD だけを複製する。spill はしない | 3本では `fir4x8`・`matmul3` が収まらない。レジスタの少ない方が cycles の少ない行がある（`matmul3` vliw: 12 本 55、16 本 56 cycles） | 必要になったときに改良する |
| 固定パイプラインは mvp-0 の既定の構成だけ。MAC・MUL→ADD の連結は無い。制御は命令列を焼き込んだのと同じ大きさで、最適化した回路ではない | ホストの入出力を含む throughput・II は測っていない。`ComputeSlot` の SUB の演算器が合成で消えるかは未確認 | #80（Milestone 5） |
| RTL の対応範囲は、全演算の ii 1、LD の latency 2 以上、ST の latency 1、read_ports 3 | `slow_units`・`ii_units`・rp4 は参照モデルと verifier だけで確かめた。CI では 14 件を ignored と表示する | rp4 は #57 |
| RTL で確かめるのは、方式ごとに cycles 最小の系列の記録と、Modulo Scheduling の記録だけ | 戦略を固定した系列の各行は、参照モデルだけで確かめる | — |
| Verilator は 5.020 のまま | ADR-0013 の対処（1スレッドの verilation）が要る。版の調査（#85〜#100、#74 のコメント）はブランチ `claude/verilator-catalog` にあり、main に未マージ。版を上げるかは決めていない | オーナー判断 |
| mutation ジョブは required ではない。8 shard で1回約70分かかる | 最後の結果は、コードを変えた最後の main 296697a と同じ tree の、PR #108 の head のもの（上の「mutation」） | 各 Phase の完了レビュー |
| mutant は、コンパイラの MAC と読出しポートの部分（融合、L11、3本目の読出しポート、MAC の rs3）を対象にする。Modulo Scheduling の本体（スロットと RecMII の計算、配置、展開）、rematerialization の選び方、`ModuloTrace` は対象にしていない | これらの誤りは、verifier の最終検査と差分テスト（参照モデル・RTL）で検出する。`ModuloTrace` の誤り検出は `ModuloKernelSpec` のテストで確かめる | — |
| Phase 2 の制約（DSL の出力は書込み専用で、各要素に1回だけ代入する。スロットの下限は、開始・終了と書戻しの衝突を数えない） | そのまま残る | 必要になったときに緩める・改良する |
| ベンチマークは小さい（8 要素、3×3）。データは Scratchpad に置いたまま | 転送を含む評価は無い | Phase 4（#109） |

## 後回しにした Issue の扱い（#71）

#66 で Milestone 3 の対象外とした Issue の扱いを、M3 の結果から次のように決めた。

| Issue | 内容 | 扱い | 理由・前提 |
|---|---|---|---|
| #78 | SIMD・複数 Compute スロット | Milestone 4 の最初（DMA の前）。DESIGN.md の拡張順は変えない | MAC の後も、`fir4x8`（i）（II 4）と `matmul3` の2つのループ（II 9）は Compute が律速である（`saxpy8`（i）は Memory スロット）。ただし list scheduling の `saxpy8` は Memory スロットが律速のままで、`matmul3` は Compute と Memory の操作が同じ数（27）になった。このため、比較には Memory スロットの下限と、書戻しポート・読出しポートの下限を併記する。前提は #77・#79（完了） |
| #57 | 合成ターゲット、MUL の物理パイプライン化、Fmax・面積、MAC の Phase B | Milestone 4（#78・DMA と並行） | ADR-0012・0018 で、読出しポートの方式（3本と L11）は #57 まで暫定とした。rp4 の RTL も #57 で足す。#78 の方式を比べるのに、RF のポートの費用が要る。最初の作業は、オーナーが合成ターゲット・クロック制約・ツールを決めること（**オーナー判断**）。M4 の完了条件には Fmax・面積の値を含めない。M4 を閉じる時点で #57 が終わっていなければ、その扱いは Phase 4 の完了レビューで決める |
| #80 | 固定パイプラインと VLIW の Fmax・面積、最適化した専用回路 | Milestone 5（#57 の後） | #57 のターゲット・制約・ツールと測定の流れが要る。Phase 4 の範囲（転送と演算の重畳）と独立である |
| #81 | ISA の固定回数ループ命令（#67 の案B） | Milestone 4 で見積もる（Double Buffer と評価の後）。案Bを入れるかは、#81 でオーナーが決める（**オーナー判断**） | 案Aでは、命令領域（256 bundle）が Scratchpad より先に効く。DMA の契約によっては、Double Buffer のブロックのループが、案Bの候補の形の材料になる見込みである |

### #78（SIMD・複数 Compute スロット）

- M3 の結果は、DESIGN.md の順（MAC → SIMD → DMA）を変える根拠にならない。MAC の後も、`fir4x8`（i）（II 4）と `matmul3` の2つのループ（II 9）は Compute が律速である。1反復の Memory の操作は、`fir4x8`（i）で 2、`matmul3`（i）・（m）で 6 である
- ただし、list scheduling では、`saxpy8` は Memory スロットが律速のままである（mvp-1 で Memory の操作 25、27 cycles）。`matmul3` は Compute と Memory の操作が同じ数（27）になった
- Compute スロットを2つにした場合の ResMII を、1反復の操作の数から見積もった（見積りで、測った値ではない）。mvp-1 の `fir4x8`（i）・`matmul3`（i）・（m）では、Compute の書戻しポート（L5）と3本目の読出しポート（L11）も、Compute スロットと同じ数（4・9・9）だけ使う。`matmul3`（m）は MAC の演算器（9）も同じである（[benchmarks/MAC.md](../benchmarks/MAC.md)「ループごとの結果」）
- このため、スロットだけを2つにしても、ResMII は下がらない（`fir4x8`（i）4、`matmul3`（i）・（m）9 のまま）
- Compute スロット、Compute の書戻しポート、3本目の読出しポート（L11）を、`matmul3`（m）では MAC の演算器も、すべて2倍にした場合に限り、ResMII は `fir4x8`（i）4 → 2、`matmul3`（i）・（m）9 → 6、`saxpy8`（i）3 → 3 になる。どれも Memory の操作で止まる。このとき RF は 3R/2W から 7R/3W になる（MAC 2つと ST を同じ bundle で読む）
- このため、#78 の比較には、Memory スロットの下限と、書戻しポート・読出しポート・MAC の演算器の下限を併記し、各方式で増やすポートと演算器の数を記録する。SIMD は1つの操作で複数のレーンを扱うので、レーンあたりのスロット・書戻しポート・読出しポートの使用が減る。SIMD を選ぶ場合は、packed の LD/ST を入れるかを契約で決める
- 独立した段にせず、Milestone 4 の最初に置く。M3 も1つの Milestone に Modulo Scheduling と MAC を順に入れた。Milestone と Phase の番号も揃ったままになる。追跡 Issue の作業ステップで、DMA の実装を #78 の後に置き、ADR-0012 の「1つずつ」を守る。DMA の契約の検討は並行してよい
- #78 は方式と契約を決める Issue（オーナー判断）とする。実装と比較は #110 で行う（#69 と #77 の分け方）
- #57 の結果が無いうちは、ADR-0018 と同じく cycle だけで暫定に選び、#57 で見直す

### #57（合成評価、MAC の Phase B）

- Milestone 4 に入れ、#78・DMA と並行して進める（作業ステップ 0）。Phase 4 の転送と演算の比較の前提にはしない（比較は cycle ベース）
- 最初の作業は、オーナーが合成ターゲット（FPGA の品種、または ASIC のライブラリ）・クロック制約・ツールを決めること（**オーナー判断**）。ターゲットが決まってから、MUL の物理パイプライン化と Fmax・面積の測定を行う
- Milestone 4 の完了条件には、Fmax・面積の値を含めない。M4 の比較は cycle ベースとする。M4 を閉じる時点で #57 が終わっていなければ、その扱い（Milestone）は Phase 4 の完了レビュー（#117）で決める
- Phase 1 から3つの Phase で後回しにしている（[PHASE1.md](PHASE1.md)「合成評価」）

### #80（固定パイプラインの Fmax・面積）

- Milestone 5 にする。#57 の後に行う
- 最適化した専用回路（演算器の複製、空間的な配置、局所配線、定数係数）の設計は、Phase 4 の範囲と独立である。M4 に入れると、M4 の流れが #78・DMA・#57 に加えて4つになる
- Phase 5（複数タイル、静的通信）では、空間的な配置の比較の基準になる
- Phase 5 の追跡 Issue とほかの Issue は、Phase 4 の完了レビューで起票する

### #81（ISA のループ命令）

- #68 の材料（#81 のコメント、2026-10-16）では、案Aの命令列は反復1回あたり II bundle 伸びる。`saxpy(n)` は n = 8〜64 で 3n + 5 bundle（= cycles）で、命令領域に収まるのは n ≦ 83 である。Scratchpad には n ≦ 170 が収まるので、命令領域が先に効く。`dot(n)` も n = 85 で収まらない
- M3 で記録したループ（反復 2〜8）は、どれも命令領域に収まった。M3 の範囲では、案Bが必要になる場面は出なかった
- Phase 4 の Double Buffer は、Scratchpad より大きいデータをブロックに分けて流す。DMA の契約（#111。まだ決めていない）で、転送をカーネルの命令列から起動し、ブロックを1つのカーネルの中で固定回数繰り返す形になれば、案Aでは、ブロックの数だけ命令列が伸びる。ホストがブロックごとにカーネルを起動する契約なら、命令列はブロックの数では伸びない
- 前の形になれば、ブロックのループ（定常部の繰り返し）は、#81 の候補の形（定常部だけを固定回数ループで繰り返し、プロローグ・エピローグは展開のまま）と同じ形になる見込みである。ループ命令の発行と転送の開始の時刻の関係も、契約が決まった後の Phase 4 で初めて見積もれる
- このため、見積りの2項目（ループ制御の cycles、案Bに必要な変更）は Milestone 4 で、Double Buffer と評価の後に作る。#81 に、Double Buffer のブロックのループの bundle 数と、命令領域に収まるブロック数（#114、#116）を判断の材料として足すことを提案する（足すかは #81 で決める）
- 案Bを入れるか、入れるならどの形にするかは、見積りの後に #81 で決める（**オーナー判断**。ISA の版を変える判断なので、ADR-0011・0012 と同じ扱い）

### #66 の完了条件に含めないもの

#85〜#100（Verilator の版の調査）は milestone-3 のラベルを持つが、#66 のサブIssueではなく、#66 の完了条件にも含まれない。調査はブランチ `claude/verilator-catalog` にあり、main に未マージである。Verilator を上げるか（ADR-0004・0013）と、これらの Issue の扱いはオーナーが決める。

## Phase 4 へ引き継ぐもの

- ループ IR と Modulo Scheduling（案A。直線の命令列に展開し、II をトレースで確かめる）
- MAC（ISA mvp-1、`config/machine-mvp1.json` revision 1、frozen）と、MAC への融合
- rematerialization（8・12・16 本）と、戦略を固定した比較の系列
- 資源条件を揃えた固定パイプライン（ChiselSim で機能と cycles）
- ベンチマークの記録（RESULTS.md・MODULO.md・REGISTERS.md・MAC.md）と、CI での一致の確認

Phase 4（DMA、Double Buffer、bank 最適化）は #109（Milestone 4）で追跡する。最初に、#78（SIMD または複数 Compute スロット）を、MAC 単独と分けて比べる。DMA の契約（開始条件・容量・帯域・bank のポートの共有・保証範囲）は、オーナーが #111 で決める。

| 作業ステップ | Issue | 内容 |
|---|---|---|
| 0 | #57 | 合成ターゲット・クロック制約・ツールを決め（**オーナー判断**）、決まれば MUL の物理パイプライン化と Fmax・面積の測定を行う（以降と並行。Fmax・面積の値は M4 の完了条件に含めない） |
| 1 | #78 | SIMD または Compute スロットの増設の、方式と契約を決める（**オーナー判断**） |
| 2 | #110 | #78 の方式を実装し、ある構成とない構成を比べる |
| 3 | #111 | DMA の契約を決める（**オーナー判断**。1〜2 と並行して検討してよい） |
| 4 | #112 | DMA を実装する（ISA・machine description・verifier・参照モデル・RTL） |
| 5 | #113 | 転送を含むカーネルの IR と、転送してから演算する命令列（直列） |
| 6 | #114 | Double Buffer で、転送と演算を重ねる命令列 |
| 7 | #115 | bank を考えた配置と、固定順の配置の比較（6 と並行可） |
| 8 | #116 | 重ねた構成と重ねない構成の比較（cycle ベース） |
| 9 | #81 | ISA のループ命令（案B）の見積り（ループ制御の cycles、案Bに必要な変更）を作る。案Bを入れるか、入れるならどの形にするかを決める（**オーナー判断**） |
| 10 | #117 | Phase 4 完了レビュー・Phase 5 への引き継ぎ |

#80 は Milestone 5（#57 の後）とする。

GitHub の Milestone 4 と Milestone 5 は、オーナーが作る（この作業のツールでは Milestone を作れない）。それまでは、Milestone 4 の Issue（#57、#78、#81 と、新しく起票する Issue）にラベル `milestone-4` を付け、#109 のサブIssueにする。Milestone 4 ができたら、これらに付ける。#80 は、Milestone 5 ができるまで、本文の冒頭に Milestone 5 と書いておく。
