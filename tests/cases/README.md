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
| `crosscheck/DifferentialSpec` | 乱数で作ったプログラムで、verifierと参照モデルの判定が一致すること（既定の構成と、レイテンシー・II・容量を変えた4構成）。合法なプログラムで、cyclesとcommit時刻が入力値に依存しないこと |
| `sim/MetricsSpec` | 合法な全ケースで、メトリクスの値が命令列と整合すること |
| `hw/CaseSpec` | 合法な全ケースを RTL で実行し、cycles・最終状態・golden trace が期待値と一致すること |
| `hw/AssertionSpec` | 不正な全ケースで、期待した規則の RTL assertion が発火すること（L8 だけのケースは最後まで実行される） |
| `crosscheck/RtlDifferentialSpec` | 乱数で作った合法プログラムで、RTL と参照モデルのトレースと最終状態が一致すること（既定の構成と、RTL が対応する範囲でレイテンシー・レジスタ数・bank 数を変えた2構成）。RTL のタイミングが入力値に依存しないこと |

RTL が対応しない構成（`slow_units.json` と `ii_units.json`）のケースは、`hw` のテストではスキップとして表示し、検証済みには数えない（[MACHINE.md](../../docs/MACHINE.md)「実装ごとの対応範囲」）。

新しいケースは `tests/cases/<name>.json` に置けば、上のテストすべてが自動的に対象にする。

## machine description の変種

| ファイル | 変更点 | 使うケース |
|---|---|---|
| `tests/machines/small_scratchpad.json` | Scratchpad 8 word | `address_out_of_range` |
| `tests/machines/slow_units.json` | MUL・LD のレイテンシー3、ST のレイテンシー2 | `slow_*` |
| `tests/machines/ii_units.json` | ADD・SUB・MUL の II が2、LD の II が3 | `ii_*` |

## mutation チェック

`scripts/mutation-check.sh` は、`scripts/mutants.tsv` に列挙した誤り（verifier・参照モデル・エンコーダ・RTL に1か所ずつ入れる）を順に適用し、そのたびに `sbt test` が**失敗する**ことを確かめる。テストが検出できない種類の誤りがあれば「SURVIVED」と表示する。mutant ごとに全テストを実行するため時間がかかる（14 mutant で約50分。timeout は120分）。CIでは手動実行（`Mutation check` ワークフロー）と、mutantの定義を変えたPRでだけ実行し、required にはしない。

```sh
scripts/mutation-check.sh                    # 全mutant
scripts/mutation-check.sh model-ld-latency   # 指定したmutantだけ
```
