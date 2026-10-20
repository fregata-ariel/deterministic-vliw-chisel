# テストケース一覧

形式は [docs/TESTCASE.md](../../docs/TESTCASE.md)。期待値はすべて、[docs/ISA.md](../../docs/ISA.md) から手で導いた。実装の出力をそのまま期待値にしない。

| ケース | 判定 | 確かめること | 備考 |
|---|---|---|---|
| [`arith_overflow`](arith_overflow.json) | 合法 | ADD/SUB/MULは2^32を法とする（下位32bit） | 7 cycles |
| [`completes_exactly_at_halt`](completes_exactly_at_halt.json) | 合法 | HALTの発行サイクル h にちょうど完了する操作は合法（MUL・LDは s+2 = h、STは s+1 = h） | 3 cycles |
| [`compute_and_load`](compute_and_load.json) | 合法 | 同じbundleでComputeとLDを発行し、別々の書戻しポートで同じ境界にcommitする | 4 cycles |
| [`consecutive_mul`](consecutive_mul.json) | 合法 | II=1: MULを毎サイクル発行する。各結果はs+2から見え、Computeポートは境界ごとに1回だけ使う | 6 cycles、golden trace |
| [`halt_only`](halt_only.json) | 合法 | 最短のプログラム: HALTだけ。実行サイクル数は1 | 1 cycles、golden trace |
| [`ii_spaced`](ii_spaced.json) | 合法 | II が2以上の構成で、各演算をちょうど II 空けて発行する。II は演算ごとの制約で、ADD の直後に SUB を発行できる | 7 cycles、ii_units.json |
| [`imem_full`](imem_full.json) | 合法 | 命令領域（256 bundle）をちょうど使い切る。HALTは255番 | 256 cycles |
| [`isa_example`](isa_example.json) | 合法 | docs/ISA.md「最初の命令例」: LD → MUL → ADD → ST → HALT | 7 cycles、golden trace |
| [`issue_cycle_read`](issue_cycle_read.json) | 合法 | 発行サイクル自身での読出しは旧値を読む（ADDのソースとSTのソース） | 2 cycles |
| [`mac3_spacing`](mac3_spacing.json) | 合法 | MAC のレイテンシーが3の構成。結果は s+3 から見える | 7 cycles、mvp1_mac3.json |
| [`mac_accumulate`](mac_accumulate.json) | 合法 | rd = rs3 の累算。発行サイクルの rs3 は旧値を読む | 5 cycles、machine-mvp1.json |
| [`mac_basic`](mac_basic.json) | 合法 | MAC は rd ← low32(rs1 × rs2 + rs3)。3つのソースを発行サイクルに読み、結果は s+2 から見える。LD と同じ境界に、別の書戻しポートで commit する | 7 cycles、machine-mvp1.json |
| [`mac_store_rp4`](mac_store_rp4.json) | 合法 | 読出しポートが4本の構成では、MAC と ST を同じ bundle に置ける（読出し4） | 3 cycles、mvp1_rp4.json |
| [`mac_then_mul`](mac_then_mul.json) | 合法 | MAC(s) と MUL(s+1) は、latency 2 では E_(s+2) と E_(s+3) に commit し、衝突しない | 4 cycles、machine-mvp1.json |
| [`mac_wrap`](mac_wrap.json) | 合法 | MAC は2^32を法とする。(2^32-1)^2 + (2^32-1) ≡ 0 | 5 cycles、machine-mvp1.json |
| [`register_address_bounds`](register_address_bounds.json) | 合法 | r0 / r15 とアドレス 0 / 511 の境界。r15 の加算は桁あふれする | 4 cycles |
| [`same_register_other_boundaries`](same_register_other_boundaries.json) | 合法 | 同じレジスタへのComputeとMemoryの書込みは、区間が重ならなければ合法 | 4 cycles |
| [`slow_store_same_bank`](slow_store_same_bank.json) | 合法 | ST のレイテンシーが2の構成で、書込み中の番地と同じ bank の別の番地は読める。ST(s) の番地は s+2 から読める | 6 cycles、slow_units.json |
| [`slow_units_spacing`](slow_units_spacing.json) | 合法 | MUL・LDのレイテンシーが3の構成。結果は s+3 から見える | 7 cycles、slow_units.json |
| [`store_then_load`](store_then_load.json) | 合法 | 先行するSTの値を次のサイクルのLDが読む（入力宣言の無いアドレス） | 4 cycles |
| [`address_out_of_range`](address_out_of_range.json) | 不正: L7 | 8 wordのScratchpadで、アドレス8へのST（L7） | small_scratchpad.json |
| [`early_halt_ld`](early_halt_ld.json) | 不正: L6 | HALT時にLDが完了していない（L6） |  |
| [`early_halt_mul`](early_halt_mul.json) | 不正: L6 | HALT時にMULが完了していない（L6） |  |
| [`empty_program`](empty_program.json) | 不正: L2 | bundleが1つも無い（L2） |  |
| [`halt_not_last`](halt_not_last.json) | 不正: L2 | HALTが最後のbundleでない（L2） |  |
| [`halt_with_memory_op`](halt_with_memory_op.json) | 不正: L1 | HALTとLDを同じbundleに置く（L1） | program_hex |
| [`ii_back_to_back_mul`](ii_back_to_back_mul.json) | 不正: L9 | MUL の II が2の構成で、MUL を連続するサイクルに発行する（L9） | ii_units.json |
| [`ii_ld_too_close`](ii_ld_too_close.json) | 不正: L9 | LD の II が3の構成で、LD(s) の次の LD を s+2 に発行する（L9） | ii_units.json |
| [`imem_overflow`](imem_overflow.json) | 不正: L2 | 命令領域を1 bundle超える（257 bundle、L2） |  |
| [`mac3_mul_port`](mac3_mul_port.json) | 不正: L5 | MAC のレイテンシーが3の構成では、MAC(s) と MUL(s+1) が E_(s+3) で衝突する（L5） | mvp1_mac3.json |
| [`mac_early_halt`](mac_early_halt.json) | 不正: L6 | HALT 時に MAC が完了していない（L6） | machine-mvp1.json |
| [`mac_overlapping_ld`](mac_overlapping_ld.json) | 不正: L4 | MAC と LD が同じ rd に書き、書込み区間が重なる（L4） | machine-mvp1.json |
| [`mac_reserved_bits`](mac_reserved_bits.json) | 不正: L1 | MAC の予約 bit 0 が1（L1）。最後は HALT | machine-mvp1.json、program_hex |
| [`mac_rs3_in_flight`](mac_rs3_in_flight.json) | 不正: L3 | MAC の rs3 も発行サイクルに読む。MUL の結果を次のサイクルの rs3 で読む（L3） | machine-mvp1.json |
| [`mac_store_read_ports`](mac_store_read_ports.json) | 不正: L11 | 読出しポートが3本の構成（既定）では、MAC と ST を同じ bundle に置けない（読出し4、L11） | machine-mvp1.json |
| [`mac_then_add_port`](mac_then_add_port.json) | 不正: L5 | MAC(s) と ADD(s+1) はどちらも E_(s+2) に Compute ポートを使う（L5） | machine-mvp1.json |
| [`mul_then_add_port`](mul_then_add_port.json) | 不正: L5 | MUL(s)とADD(s+1)はどちらもE_(s+2)にComputeポートを使う（L5） |  |
| [`no_halt`](no_halt.json) | 不正: L2 | HALTが無い（L2） |  |
| [`overlapping_mul_ld`](overlapping_mul_ld.json) | 不正: L4 | MULとLDの書込み区間が重なる（L4） |  |
| [`raw_ld_next_cycle`](raw_ld_next_cycle.json) | 不正: L3 | LDの結果を次のサイクルで読む（L3） |  |
| [`raw_mul_next_cycle`](raw_mul_next_cycle.json) | 不正: L3 | MULの結果を次のサイクルにSTで読む（L3）。ComputeポートはSTと競合しない |  |
| [`reserved_bits`](reserved_bits.json) | 不正: L1 | ADDの予約bit 0 が1（L1） | program_hex |
| [`same_boundary_double_write`](same_boundary_double_write.json) | 不正: L4 | LD(0)とADD(1)が同じE_2にr1へ書く（L4） |  |
| [`slow_store_before_halt`](slow_store_before_halt.json) | 不正: L6 | ST のレイテンシーが2の構成では、HALT の直前のサイクルの ST は完了しない（L6） | slow_units.json |
| [`slow_store_inflight_load`](slow_store_inflight_load.json) | 不正: L10 | ST のレイテンシーが2の構成で、書込み中の番地を LD で読む。旧値が入力宣言されていても不正（L10） | slow_units.json |
| [`slow_store_then_load`](slow_store_then_load.json) | 不正: L8, L10 | ST のレイテンシーが2の構成では、ST(s) の値は s+2 から見える。s+1 の LD は書込み中の番地を読み（L10）、入力宣言も無いので未定義値でもある（L8） | slow_units.json |
| [`slow_units_too_early`](slow_units_too_early.json) | 不正: L3 | MUL のレイテンシーが3の構成では、既定構成で合法な s+2 での読出しが L3 違反になる | slow_units.json |
| [`undefined_memory`](undefined_memory.json) | 不正: L8 | 入力宣言もSTも無いアドレスをLDで読む（L8） |  |
| [`undefined_register`](undefined_register.json) | 不正: L8 | 入力宣言の無いレジスタを読む（L8） |  |
| [`unknown_opcode`](unknown_opcode.json) | 不正: L1 | Compute opcode 4 は未定義（L1）。最後はHALT | program_hex |

## 照合するテスト

| テスト | 照合内容 |
|---|---|
| `isa/TestCaseSpec` | 全ケースが形式どおりに読めること |
| `compiler/VerifierSpec` | verifierが報告する規則の集合が `expect.verdict` と一致すること |
| `sim/ReferenceModelSpec` | 参照モデルの結果（cycles、最終状態、golden trace、最初の違反）が期待値と合うこと |
| `crosscheck/DifferentialSpec` | 乱数で作ったプログラムで、verifierと参照モデルの判定が一致すること（既定の構成と、レイテンシー・II・容量を変えた4構成）。ISA mvp-1 では MAC を含むプログラムで、`machine-mvp1.json`・`mvp1_rp4.json`・`mvp1_mac3.json` と、MAC の II を2にした構成の4つ。生成器は rd = rs3、書込み中の rs3（L3）、同じ bundle の MAC と ST（読出し3本で L11、4本で合法）、MAC と後の ADD/SUB/MUL の書戻しポートの衝突（L5）を作り、参照モデルが最初に当たる規則はすべて出る（mvp-1 では L11 と、MAC の II が2の構成の L9 を含む）。合法なプログラムで、cyclesとcommit時刻が入力値に依存しないこと |
| `sim/MetricsSpec` | 合法な全ケースで、メトリクスの値が命令列と整合すること |
| `hw/CaseSpec` | 合法な全ケースを RTL で実行し、cycles・最終状態・golden trace が期待値と一致すること |
| `hw/AssertionSpec` | 不正な全ケースで、期待した規則の RTL assertion が発火すること（L8 だけのケースは最後まで実行される） |
| `crosscheck/CompiledKernelSpec` | DSL のベンチマークを list scheduling と register allocation で命令列にし、最終検査に合格し、参照モデルと RTL で DSL の参照実装と同じ結果を、スケジュールどおりの cycles で出すこと。ISA mvp-1 では、MUL + ADD を MAC に融合した DAG から、レジスタ16・12・8本で作る。MAC の命令は融合した組の数だけあり、読出し3本の構成では MAC と ST が同じ bundle に無い（参照モデルは mvp-1 の3構成、RTL は `machine-mvp1.json` と `mvp1_mac3.json`。RTL はレジスタ16本の構成で、12本・8本の命令列も実行する） |
| `crosscheck/HandComputedSpec` | ベンチマークを記録した規模で、手で計算した出力と一致すること（vliw と単一発行、参照モデルと RTL）。ISA mvp-1 の MAC を含む命令列でも同じ出力になること（参照モデルは mvp-1 の3構成、RTL は `machine-mvp1.json`） |
| `crosscheck/BenchmarkSpec` | `benchmarks/` の記録がソースから生成し直した内容と一致し、記録した命令列が参照モデルと RTL で期待どおりになること |
| `crosscheck/RandomKernelSpec` | 乱数で作った DSL のカーネル（400個）をコンパイルし、すべて最終検査に合格し、参照モデルと RTL で DSL の参照実装と同じ結果を、予測どおりの cycles で出すこと。ISA mvp-1 でも同じ（参照モデルは `machine-mvp1.json` と `mvp1_rp4.json`、RTL は MAC を含む60個を `machine-mvp1.json` で）。読む側が複数ある MUL は、ADD が読んでも MUL のまま残ること |
| `crosscheck/ModuloKernelSpec` | ループのベンチマークを Modulo Scheduling（#68）で命令列にし、参照モデルと RTL で DSL の参照実装と同じ結果を、スケジュールどおりの cycles で出し、トレースから読んだ II が記録した II と一致すること（`ModuloTrace`）。ISA mvp-1 では、ループごとの融合の判定（`mvp1_mac3.json` の `dot8` は MII が上がるので融合しない）、II と cycles、融合したループの MAC の命令を確かめる（参照モデルは mvp-1 の3構成、RTL は `machine-mvp1.json` と `mvp1_mac3.json`） |
| `crosscheck/FixedPipelineKernelSpec` | ベンチマークの固定パイプライン（#75）を ChiselSim で実行し、乱数・全0・全1の入力で DSL の参照実装と同じ出力を、計画どおりの cycles と発行・書戻しの cycle で出すこと。FIR の係数を変えても同じ回路で正しいこと（係数可変） |
| `crosscheck/RtlDifferentialSpec` | 乱数で作った合法プログラムで、RTL と参照モデルのトレースと最終状態が一致すること（既定の構成と、RTL が対応する範囲でレイテンシー・レジスタ数・bank 数を変えた2構成。ISA mvp-1 では MAC を含むプログラムで、`machine-mvp1.json` と `mvp1_mac3.json`）。RTL のタイミングが入力値に依存しないこと |
| `crosscheck/InitStateSpec` | すべてのレジスタとメモリを全 0・全 1 から始めた RTL（`simulateInit`、#129）で、L8 だけに反するカナリア（未定義のレジスタと、各 bank の未定義の語を読む）が初期値（add のカナリアは 1 + 初期値）を commit すること、`RtlDifferentialSpec` の合法なプログラム 50 個のトレース・cycles・定義された最終値が参照モデルと一致すること、プログラムが書かない語と r4〜最後から2番目のレジスタが初期値のままであること（番号のすべての bit が 1 のレジスタは、最初の reset の境界で書かれることがあるので、最初の実行の後の値から変わらないこと）。既定の構成は全 0 と全 1、mvp-1 の2構成と `RtlDifferentialSpec` の2構成は全 1 で走らせる。saxpy8 の固定パイプラインを全 1 で、計画どおりの出力・cycles・発行と書戻しの cycle と、書かない語の値。乱数の初期値は、ほかのすべてのテストが受け持つ |

RTL が対応しない構成（`slow_units.json`、`ii_units.json`、`mvp1_rp4.json`）のケースは、`hw` のテストではスキップとして表示し、検証済みには数えない（[MACHINE.md](../../docs/MACHINE.md)「実装ごとの対応範囲」）。

新しいケースは `tests/cases/<name>.json` に置けば、上のテストすべてが自動的に対象にする。

## machine description の変種

| ファイル | 変更点 | 使うケース |
|---|---|---|
| `tests/machines/small_scratchpad.json` | Scratchpad 8 word | `address_out_of_range` |
| `tests/machines/slow_units.json` | MUL・LD のレイテンシー3、ST のレイテンシー2 | `slow_*` |
| `tests/machines/ii_units.json` | ADD・SUB・MUL の II が2、LD の II が3 | `ii_*` |
| `tests/machines/regs3.json` | レジスタ3本（ほかは既定の構成と同じ） | Modulo Scheduling のテスト（`compiler/.../modulo/ModuloSchedulerSpec`、#68） |
| `tests/machines/regs8.json`、`tests/machines/regs12.json` | レジスタ8本、12本（ほかは既定の構成と同じ） | レジスタ数を変えたベンチマーク（rematerialization、#70）。`crosscheck/CompiledKernelSpec` が参照モデルと RTL で実行する |
| `config/machine-mvp1.json` | ISA mvp-1 の既定の構成: mvp-0 に MAC（latency 2、II 1）を足し、読出しポートは3本（[ADR-0018](../../docs/DECISIONS.md)） | `mac_*`（`mac_store_rp4` を除く）。`crosscheck` の ISA mvp-1 のテスト |
| `tests/machines/mvp1_rp4.json` | mvp-1 で読出しポートだけ4本（制約なしの比較用。RTL の範囲外） | `mac_store_rp4`。`crosscheck` の ISA mvp-1 のテスト（verifier と参照モデル） |
| `tests/machines/mvp1_mac3.json` | mvp-1 で MAC のレイテンシーだけ3（感度の確認） | `mac3_*`。`crosscheck` の ISA mvp-1 のテスト |
| `config/machine-mvp2.json` | ISA mvp-2 の既定の構成（W-full）: mvp-1 に W の演算と LD.W・ST.W を足したもの（[ADR-0022](../../docs/DECISIONS.md)） | #110 の第2段から |
| `tests/machines/mvp2_mw1.json` | mvp-2 の W-reg: `max_words_per_access` 1 で、LD.W・ST.W が無い | #110 の第2段から |
| `tests/machines/mvp2_rp4.json` | mvp-2 で読出しポートだけ4本（制約なしの比較用。RTL の範囲外） | #110 の第2段から（verifier と参照モデル） |
| `tests/machines/mvp2_vlat.json` | mvp-2 で新しい演算のレイテンシーだけ変えたもの（VADD・VSUB 2、VMUL・VMAC 3、LD.W 3） | #110 の第2段から（RTL は第3段から） |
| `tests/machines/mvp2_vii.json` | mvp-2 で新しい6つの演算の II を2、ST と ST.W のレイテンシーを2にしたもの（ST.W のレイテンシーは ST と同じでなければならない。RTL の範囲外） | #110 の第2段から（verifier と参照モデル） |

mvp-2 の machine を使うケースは、#110 の第2段で verifier と参照モデルと一緒に足す。それまでは、verifier・参照モデル・RTL・コンパイラが mvp-2 の machine を理由付きで拒否する（[MACHINE.md](../../docs/MACHINE.md)「実装ごとの対応範囲」）。第1段では、ローダ・エンコーダ・デコーダ・アセンブラを `isa/Mvp2Spec` が確かめる。

## mutation チェック

`scripts/mutation-check.sh` は、`scripts/mutants.tsv` に列挙した誤り（verifier・参照モデル・エンコーダ・RTL・コンパイラに1か所ずつ入れる）を順に適用し（欄の `\n` は改行として扱う）、そのたびに `sbt test` が**テストの失敗として**失敗することを確かめる。mutant ごとに全テストを実行するため時間がかかる（CI 上、Verilator 5.020 で、1回の `sbt test` が約5.5分、最初の mutant なしの1回がコンパイルを含めて約10分。5.052 の `sbt test` は、同じ job の 5.020 の約 1.24 倍かかった（[docs/toolchain/5.052/report.md](../../docs/toolchain/5.052/report.md)））。1回の `sbt test` は `MUTATION_TIMEOUT`（既定 1200 秒）で打ち切る。`MUTATION_SHARD=k/n` を与えると、`mutants.tsv` の mutant の行のうち k, k+n, k+2n, … 番目（1 から数える）だけを実行する。mutant の id を与えると、その mutant だけを実行する。`mutants.tsv` にない id があれば、何も実行せずに成功しないよう、sbt の前に exit 2 で止まる（#129）。CI では mutant を8つの shard に分けて並列に実行し、shard ごとに最初の mutant なしの1回を行う（57 mutant で shard 当たり7〜8個）。shard ごとに、ジョブの timeout が150分、mutation のステップの timeout が140分である（打ち切られてもログを残せるようにするため。#121 で Verilator 5.052 に上げる前に、120分と110分から広げた）。ログは shard ごとの artifact（`mutation-logs-verilator-<Verilator の版と入れ方>-shard<k>-attempt<n>`）に残り、`results.tsv` は job のログの最後にも出る（#121）。CIでは手動実行（`Mutation check` ワークフロー）と、mutantの定義・スクリプトまたはツールチェーン（[docs/toolchain/README.md](../../docs/toolchain/README.md#ci-で入れる)）を変えたPRでだけ実行し、required にはしない。

`sbt test` が 0 以外で終わることだけでは、mutant を検出したとは言えない。Verilator・make のビルドの失敗も、ChiselSim ではそのテストの `*** FAILED ***` として表示されるからである（#74）。そこで、各実行の JUnit レポート（`*/target/test-reports`）と sbt のログを `scripts/mutation-classify.py` が読み、失敗したテストごとに原因を分類して、mutant を次のどれかと判定する。

| 判定 | 区分 | 条件 |
|---|---|---|
| KILLED | `assertion` | テストが値を確かめて失敗した: ScalaTest の assertion（`intercept` を含む）、ChiselSim の `expect`、RTL の assertion、Scala の `assert`、ChiselSim の cycle の上限 |
| KILLED | `exception` | それ以外の例外でテストが失敗した（assertion による失敗がないときだけこの区分になる） |
| ERROR | `scalac` ほか | scalac（`-Werror` を含む）の失敗、Verilator・make のビルドの失敗（`sim-build`）、firtool・elaboration、シミュレーションの起動の失敗・異常終了、svsim の中で起きた例外（Verilator が見つからないなど）、OOM・入出力などの環境の失敗、スイートの中止（`*** ABORTED ***`）、テストの JVM の終了、timeout |
| SURVIVED | `passed` | `sbt test` が成功した |

scalac が失敗した実行は、ほかのテストの結果によらず ERROR（`scalac`）とする（mutant がコンパイルできないので、検出したとは言えない）。それ以外では、1つでも KILLED の区分に当たるテストがあれば KILLED とし、同じ実行の ERROR の数を併記する。ERROR は1回だけ再実行して判定する（最初の ERROR のログも `<id>` に残り、再実行は `<id>.retry1` に入る）。ただし、毎回同じ結果になる `scalac`・`firtool`・`elaboration` と、環境の例外によらないスイートの中止（`aborted`）は再実行しない。再実行の後も ERROR なら、その mutant を ERROR と報告し、次の mutant に進む。mutant を入れる前に、mutant なしで1回実行し（同じ規則で再実行する）、SURVIVED にならなければ中止する。ERROR・SURVIVED・STALE（元の文字列が1か所に見つからない）が1つでもあれば失敗で終わる。

結果は `build/mutation/<UTC時刻>/`（`MUTATION_LOG_DIR` で変更可）に残る。`results.tsv` に mutant ごとの判定・区分・最初の失敗を、mutant ごとのディレクトリに `sbt.log`、終了コード、`mutant.diff`、JUnit レポート、`classification.tsv`（失敗したテストごとの区分）、その実行で失敗した ChiselSim のビルドのログと、空でない `simulation-log.txt` を置く。CI ではこのディレクトリを artifact として残す。分類の規則は `scripts/testdata/mutation-classify/` の実例（sbt 1.12.15 の出力を縮めたもの。CI の色付きの出力、日本語のロケールの make の出力を含む）で、再実行と集計は偽の sbt を使う `scripts/test-mutation-check.sh` で、`scripts/check.sh static` が検査する。

```sh
scripts/mutation-check.sh                    # 全mutant
scripts/mutation-check.sh model-ld-latency   # 指定したmutantだけ
MUTATION_TIMEOUT=1800 MUTATION_RETRIES=0 scripts/mutation-check.sh   # 1回の sbt の上限（秒）、ERROR の再実行回数
```

`scripts/mutation-check.sh` は、始める前に `scripts/toolchain.sh check` で、PATH の Verilator が期待する版であることを確かめ、違えば exit 2 で止まる（[docs/toolchain/README.md](../../docs/toolchain/README.md)）。版がずれると `BenchmarkSpec` が失敗し、ベースラインを飛ばしたとき（`MUTATION_BASELINE=0`）には、すべての mutant が KILLED に見えるからである。`env.txt` には、固定の版、期待する版とその出どころ、PATH の Verilator の場所・版・`verilator_bin` の sha256 を書く。
