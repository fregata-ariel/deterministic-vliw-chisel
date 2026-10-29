# 独立 schedule verifier

実装は `vliw.compiler.Verifier`（sbtプロジェクト `compiler`）。命令列と入力宣言だけから、[ISA.md](ISA.md) の合法性規則 L1〜L11 を**静的に**判定する（ISA mvp-1 の MAC を含む。L11 と MAC は #77 で加えた。#69、ADR-0018）。ISA mvp-2 の W（VADD・VSUB・VMUL・VMAC・LD.W・ST.W。[ISA.md](ISA.md)「mvp-2: W」）は、#110 の第2段で加えた（ADR-0022）。手書きの命令列も、Phase 2 のスケジューラが生成した命令列も、同じ入口で検査する。

## 独立性

- 値を一切計算しない。参照モデル（`vliw.sim`）は値を持つ実行中操作のキューで実行するが、verifierは次の表だけを使う。
- `sim` のコードを使わない（ビルド上も依存できない。[ADR-0002](DECISIONS.md)）。
- 照合は、テストケースの手作りの期待値（`expect.verdict`）を介して行う。verifierが報告する規則の集合は期待値と一致し、参照モデルが最初に検出する違反はその集合に含まれる（[TESTCASE.md](TESTCASE.md)）。

## 方法

全bundleをdecodeし、命令列を次の表に要約する。

| 表 | 内容 |
|---|---|
| 書込み区間 | レジスタへの書込みごとに `(r, [s, s+L), port, 操作)`。「操作」は書いた操作の id（スロットとサイクルで決まる）。ベクトル演算と LD.W は、組の2つのレジスタに、同じ id の行を1つずつ作る |
| 読出し | オペランドの欄ごとに `(r, t)`。STのソースと MAC の rs3 を含む。mvp-2 の組の欄（ST.W のソースを含む）は組 `r:r+1` の1行で、ブロードキャストの rs1 は1つのレジスタの1行である。L3 と L8 は、組の行を2つのレジスタに展開して調べる |
| メモリアクセス | LD/STがアクセスする語ごとに `(addr, s, 種別, L)`。LD.W・ST.W は、語 `a` と `a+1` の2行を作る（`a` が奇数なら、2つ目の語は bank 0 の次の行にあるが、規則は語の番地だけで判定する） |
| 発行 | 演算ごとの発行サイクル `(op, s)`。6つの W の演算は、それぞれ別の演算として数える |

各規則は、これらの表に対する独立した検査として書く。違反は**すべて**、サイクル順に報告する。

| 規則 | 検査 |
|---|---|
| L1 符号化 | 各bundleがdecodeできる。1つでもdecodeできないと他の表が作れないため、L1の違反がある場合は、L1と命令領域の容量（L2）だけを報告する |
| L2 プログラム形状 | bundle数が1以上で命令領域の容量以下。HALTが存在し、最後のbundle以外にHALTが無い |
| L3 書込み中の読出し | 読出し `(r, t)` と書込み区間 `[s, e)` について、`s < t < e` となる組が無い。組の欄は2つのレジスタのそれぞれで調べる |
| L4 書込み区間の重複 | 同じレジスタの書込み区間が互いに重ならない。組の書込みはレジスタごとの区間なので、どちらかのレジスタへのスカラーの書込みと重なれば違反になる（組の部分更新は、区間が重ならなければ合法） |
| L5 書戻しポート | 各 `(port, s+L)` を使う書込みの**操作**が高々1つ（書込みの行を操作の id で数える。組の2つの行は1回の使用） |
| L6 HALT時の完了 | 最後のbundleがHALT（h）のとき、全ての書込みとSTが `s+L <= h` を満たす。違反は操作ごとに1つ報告する（組の2つの書込みと ST.W の2語は1つ） |
| L7 アドレス範囲 | 全アクセスが `addr < banks × words_per_bank`（LD.W・ST.W は語 `a+1` も） |
| L8 未定義値 | 読出し `(r, t)` ごとに、r ∈ R_in であるか、`s+L <= t` の書込みがある。LD `(a, t)` ごとに、a ∈ M_in であるか、`s+L <= t` の ST がある。組はレジスタごと、LD.W は語ごと（ST.W も語ごとに書く）。範囲外の語（例: LD.W [511] の語 512）は M_in に入れられないので、その LD は L7 とともに L8 にもなる。ただし、その語を書く ST・ST.W（それ自体が L7 の違反）が `s+L <= t` にあれば、L8 にはならない |
| L9 発行間隔 | 演算ごとに発行サイクルを並べ、隣り合う2つの差が II 以上 |
| L10 書込み中のメモリ語の読出し | LD `(a, t)` と ST `(a, s, L)` について、`s < t < s+L` となる組が無い。語ごとに調べる（LD.W・ST.W の2つ目の語を含む） |
| L11 読出しポート（#77） | サイクルごとに読出し `(r, t)` を欄の数で数え、`register_file.read_ports` 以下。組とブロードキャストの欄も1と数える（形 F）。mvp-0 では常に満たされる |

L1 の W の部分（偶数の組の欄、VMUL・VMAC だけの B、予約 bit、`max_words_per_access` が1の machine の LD.W・ST.W の opcode）は、ほかの L1 と同じく decode（`vliw.isa.BundleCodec`）の誤りとして報告する。

1つの命令が複数の規則に違反することがある。例えば `MUL r3, r1, r2` の直後のサイクルの `ADD r4, r3, r1` は、L3（書込み中の r3 の読出し）と L5（同じ E_2 でのComputeポートの使用）の両方に違反する。r3 が入力宣言されていなければ、L8 にも違反する。

## 出力

各違反は `Finding(rule, cycle, message)` として報告する。`cycle` はbundle番号で、プログラム全体に関わる違反では空になる。

```sh
sbt "compiler/run verify tests/cases/isa_example.json ..."
# 終了コード: 0 = すべて合法、1 = 違反あり、2 = 入力エラー
```

## RTLのassertionとの対応

RTL（`vliw.hw.Core`）は、違反をサイクルごとの信号（`io.violation`）として出し、シミュレーション用の `assert` で停止する。メッセージは `L3 (read of a register being written) at pc 1` のように規則IDで始まる。合法なプログラムでは発火せず、ストールで違反を隠すことはしない。

| 規則 | RTLでの検出 |
|---|---|
| L1 符号化 | `Decoder` が、未知opcode・予約bit・NOP/HALT のオペランドbit・範囲外のレジスタ・HALT と memory操作の組合せを検出する。mvp-2 では、奇数の組の欄、VADD・VSUB の B、演算ごとの予約 bit（VMUL は bits 15:12 と 10:0、VMAC は 10:0、LD.W・ST.W は 14:0）、W-reg の LD.W・ST.W の opcode も |
| L2 プログラム形状 | 命令領域を超えるプログラムは、ロード時に拒否する（ホストが書き込めない）。実行中は、PC がプログラム長に達した場合と、最後以外の bundle の HALT で発火する |
| L3 書込み中の読出し | `WriteReservations` で、読むレジスタ（rs1、rs2、ST のソース、mvp-1 の MAC の rs3）が書込み中なら発火する。mvp-2 では、組の欄（ST.W のソースと VMAC の rs3 を含む）は両方のレジスタを問い合わせ、ブロードキャストの rs1 は1つのレジスタを問い合わせる |
| L4 書込み区間の重複 | 書込み中のレジスタへの新しい書込みと、同じ bundle の Compute と LD の書込み先の一致で発火する。mvp-2 では、ベクトル演算と LD.W の書込みは組の両方のレジスタを予約し、同じ bundle の書込み先はレジスタの集合で比べる（`vmac r4:r5, … \| ld r5, [0]` も） |
| L5 書戻しポート | `WritebackSchedule` が、同じ境界に予約済みのポートへの書込みを検出する。mvp-2 のベクトル演算と LD.W は、組を1つの項目として予約するので、操作で数える。LD と LD.W の latency が違う構成（`mvp2_vlat.json` など）の Memory ポートの衝突は、後の load の値が予約表に入るとき、つまり後の load の発行の1サイクル後に発火する（pc は次の bundle を指す）。ほかの規則は、違反した bundle の発行サイクルに発火する |
| L6 HALT時の完了 | HALT の発行時に、書込み予約・予約表・LD の取り込み段のいずれかが残っていれば発火する |
| L7 アドレス範囲 | `MemorySlot` が、容量を超えるアドレスを検出する（そのアクセスは行わない）。LD.W・ST.W は語 a+1 も確かめ、どちらかが範囲外なら、どちらの語にもアクセスしない |
| L8 未定義値 | RTL は値の定義状態を持たないので対象外。verifier と参照モデルだけで検査する |
| L9 発行間隔、L10 書込み中のメモリ語の読出し | RTL が対応する構成（全 II が1、ST のレイテンシー1）では起こらない。それ以外の構成は `CoreParams` が拒否する（[MACHINE.md](MACHINE.md)「実装ごとの対応範囲」） |
| L11 読出しポート | mvp-1 で、MAC と ST が同じ bundle にあると発火する（読出し4、ポート3本）。mvp-2 では「（MAC か VMAC）かつ（ST か ST.W）」（欄4、形 F）。`read_ports` 4 の構成は `CoreParams` が拒否する。mvp-0 では起こらないので、信号を作らない |

このほか、ホストが実行中に状態を変更すると `HOST` の assertion が発火する。

`hw/AssertionSpec` は、`tests/cases` のすべての不正ケース（mvp-2 のものを除く）について、RTL が最初に違反したサイクルで知らせた規則が、期待した規則に含まれることを確かめる。RTL の対応する machine ごとに1回のビルドで、下の `stopAtViolation` を使い、ビルドの最後に1つのケースで assertion を実際に発火させる（#110 の Q3。[hw/README.md](../hw/README.md)「テスト」）。RTL が対応しない構成（ST のレイテンシー2など）のケースはスキップし、その旨を表示する。

mvp-2 のケースは、`crosscheck/RtlMvp2Spec` が RTL の対応する machine ごとに1回のビルドで実行する（ケースごとにビルドすると、CI の時間に収まらない）。1つの DUT で多くのプログラムを走らせるため、テスト用の `CoreDriver.run` に `stopAtViolation` を用意した。違反の信号（`io.violation`）が最初に立ったサイクルで、知らせた規則とサイクルを記録し、そのサイクルの終わりのクロックの境界の間 reset を立てて返す。生成される assertion はどれも reset で止められている（`if (~Core.reset & Core.io_violation_*)`）ので、その境界では発火せず、次のプログラムの `start` がコアを reset し直す。違反の信号は assertion の条件そのものなので、知らせた規則とサイクルは、assertion が発火する規則とサイクルである。これはテストの側の仕組みで、RTL はストールも停止もしない。不正なケースでは、最初に違反したサイクルで知らせた規則が、ケースの規則（L8 を除く）に含まれることを確かめ、L8 だけのケースは最後まで実行されることを確かめる。乱数の不正なプログラムでは、知らせた規則が verifier の見つけた規則に含まれ（最後の bundle でない HALT は L2 で、RTL はその HALT でも L6 を確かめるので、L2 と一緒の L6 は除く。verifier は L6 を最後の bundle の HALT でだけ確かめる）、止まったサイクルが参照モデルの最初の違反のサイクル（上の Memory ポートの L5 だけは、その1サイクル後）であることを確かめる。ケースを走らせる3つのビルド（`machine-mvp2.json`・`mvp2_mw1.json`・`mvp2_vlat.json`）の最後には、それぞれ1つのケース（`w_vmac_stw_fields` の L11、`w_ldw_on_wreg` の L1、`w_vlat_vmul_mul_port` の L5）を `stopAtViolation` なしで走らせ、assertion が実際に発火することを確かめる。4つ目のビルド（W-full の変種。レジスタ8本・4 bank × 64 語・latency を混ぜたもの）はケースを持たず、乱数のプログラムを `stopAtViolation` で走らせるだけで、assertion を発火させない。

mvp-0・mvp-1 のケースも、同じ仕組みで machine ごとに1回のビルドにまとめた（#110 の Q3）。`hw/AssertionSpec` は、各不正ケースを `stopAtViolation` で止め、知らせた規則がケースの規則（L8 を除く）に含まれることを確かめる。`hw/CaseSpec` は、各合法ケースで違反の信号が立たないことを確かめる。どちらのビルドも最後に、その machine の1つのケースを、`stopAtViolation` で止めた後に、`stopAtViolation` なしで走らせ、その規則の assertion が実際に発火し、メッセージの pc が止めたサイクルの pc と同じであることを確かめる（`AssertionSpec` は `halt_not_last` の L2、`mac_rs3_in_flight` の L3、`address_out_of_range` の L7、`mac3_mul_port` の L5、`CaseSpec` は `early_halt_mul` の L6、`mac_overlapping_ld` の L4、`mac3_mul_port` の L5）。上の `RtlMvp2Spec` の3つと合わせて、L1〜L7 と L11 の assertion が、それぞれどこかのビルドで実際に発火する（`RtlMvp2Spec` のテストが、発火させるケースの規則で確かめる）。assertion が発火するとシミュレーションが終わるので、1つのビルドで発火させられる規則は1つである。2つのスイートは同じ machine を別々にビルドするので、不正なケースが2つ以上ある machine（`config/machine.json`・`config/machine-mvp1.json`）では、2つの規則を発火させる（`mvp1_mac3.json` の不正なケースは `mac3_mul_port` だけなので、2つのスイートが同じ L5 を発火させる）。
