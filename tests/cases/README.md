# テストケース一覧

形式は [docs/TESTCASE.md](../../docs/TESTCASE.md)。期待値はすべて、[docs/ISA.md](../../docs/ISA.md) から手で導いた。実装の出力をそのまま期待値にしない。

| ケース | 判定 | 確かめること | 備考 |
|---|---|---|---|
| [`arith_overflow`](arith_overflow.json) | 合法 | ADD/SUB/MULは2^32を法とする（下位32bit） | 7 cycles |
| [`compute_and_load`](compute_and_load.json) | 合法 | 同じbundleでComputeとLDを発行し、別々の書戻しポートで同じ境界にcommitする | 4 cycles |
| [`consecutive_mul`](consecutive_mul.json) | 合法 | II=1: MULを毎サイクル発行する。各結果はs+2から見え、Computeポートは境界ごとに1回だけ使う | 6 cycles、golden trace |
| [`halt_only`](halt_only.json) | 合法 | 最短のプログラム: HALTだけ。実行サイクル数は1 | 1 cycles、golden trace |
| [`isa_example`](isa_example.json) | 合法 | docs/ISA.md「最初の命令例」: LD → MUL → ADD → ST → HALT | 7 cycles、golden trace |
| [`issue_cycle_read`](issue_cycle_read.json) | 合法 | 発行サイクル自身での読出しは旧値を読む（ADDのソースとSTのソース） | 2 cycles |
| [`same_register_other_boundaries`](same_register_other_boundaries.json) | 合法 | 同じレジスタへのComputeとMemoryの書込みは、区間が重ならなければ合法 | 4 cycles |
| [`store_then_load`](store_then_load.json) | 合法 | 先行するSTの値を次のサイクルのLDが読む（入力宣言の無いアドレス） | 4 cycles |
| [`address_out_of_range`](address_out_of_range.json) | 不正: L7 | 8 wordのScratchpadで、アドレス8へのST（L7） | small_scratchpad.json |
| [`early_halt_ld`](early_halt_ld.json) | 不正: L6 | HALT時にLDが完了していない（L6） |  |
| [`early_halt_mul`](early_halt_mul.json) | 不正: L6 | HALT時にMULが完了していない（L6） |  |
| [`empty_program`](empty_program.json) | 不正: L2 | bundleが1つも無い（L2） |  |
| [`halt_not_last`](halt_not_last.json) | 不正: L2 | HALTが最後のbundleでない（L2） |  |
| [`halt_with_memory_op`](halt_with_memory_op.json) | 不正: L1 | HALTとLDを同じbundleに置く（L1） | program_hex |
| [`mul_then_add_port`](mul_then_add_port.json) | 不正: L5 | MUL(s)とADD(s+1)はどちらもE_(s+2)にComputeポートを使う（L5） |  |
| [`no_halt`](no_halt.json) | 不正: L2 | HALTが無い（L2） |  |
| [`overlapping_mul_ld`](overlapping_mul_ld.json) | 不正: L4 | MULとLDの書込み区間が重なる（L4） |  |
| [`raw_ld_next_cycle`](raw_ld_next_cycle.json) | 不正: L3 | LDの結果を次のサイクルで読む（L3） |  |
| [`raw_mul_next_cycle`](raw_mul_next_cycle.json) | 不正: L3 | MULの結果を次のサイクルにSTで読む（L3）。ComputeポートはSTと競合しない |  |
| [`reserved_bits`](reserved_bits.json) | 不正: L1 | ADDの予約bit 0 が1（L1） | program_hex |
| [`same_boundary_double_write`](same_boundary_double_write.json) | 不正: L4 | LD(0)とADD(1)が同じE_2にr1へ書く（L4） |  |
| [`undefined_memory`](undefined_memory.json) | 不正: L8 | 入力宣言もSTも無いアドレスをLDで読む（L8） |  |
| [`undefined_register`](undefined_register.json) | 不正: L8 | 入力宣言の無いレジスタを読む（L8） |  |
| [`unknown_opcode`](unknown_opcode.json) | 不正: L1 | Compute opcode 4 は未定義（L1）。最後はHALT | program_hex |

## 照合するテスト

| テスト | 照合内容 |
|---|---|
| `isa/TestCaseSpec` | 全ケースが形式どおりに読めること |
| `compiler/VerifierSpec` | verifierが報告する規則の集合が `expect.verdict` と一致すること |
| `sim/ReferenceModelSpec` | 参照モデルの結果（cycles、最終状態、golden trace、最初の違反）が期待値と合うこと |
| `crosscheck/DifferentialSpec` | 乱数で作ったプログラムで、verifierと参照モデルの判定が一致すること。合法なプログラムで、cyclesとcommit時刻が入力値に依存しないこと |

新しいケースは `tests/cases/<name>.json` に置けば、上のテストすべてが自動的に対象にする。
