# Machine description（config/machine.json）

状態：`schema_version` 1（ISA `mvp-0`）。ISA `mvp-1`（MAC）のための `schema_version` 2 は、下の「[mvp-1（schema_version 2）](#mvp-1schema_version-2)」で定める（#69、ADR-0018。ローダは #77 で実装した）。ISA `mvp-2`（W）のための `schema_version` 3 は、「[mvp-2（schema_version 3）](#mvp-2schema_version-3)」で定める（#78、ADR-0022。ローダは #110 の第1段で実装した）。ローダは `vliw.isa.MachineDescription`（sbtプロジェクト `isa`）。

RTL・命令列・verifier・参照モデルは、このファイルをローダ経由でのみ参照する。値の意味は [ISA.md](ISA.md) が定める。

## 版と同一性

| 項目 | 意味 | 変更する時 |
|---|---|---|
| `schema_version` | このファイル形式（キーの構成と検証規則）の版。現在は1。 | キーの追加・削除、検証規則の変更。ローダも同時に変える。 |
| `revision` | 同じ `machine_id` の内容の版。1から始まる整数。 | 値を変えるたびに1増やす。 |
| `status` | `proposed`（検討中）または `frozen`（確定）。 | Phase 0 完了時に `frozen` にした（revision 2）。 |
| hash | 正規化形式のSHA-256。`sha256:<64桁の16進>`。 | 自動的に変わる（ファイルに書かない）。 |

人が読む識別子は `machine_id` と `revision`、機械的な同一性は hash で表す。トレースと評価記録には、両方を記録する。

### 正規化形式とhash

正規化形式は、RFC 8785（JCS）を整数だけに制限したものである。

- オブジェクトのキーを UTF-16 のコード単位順に並べる。
- 意味のない空白を入れない。
- 文字列は、`"`、`\`、制御文字だけをエスケープする。
- 数値は整数だけを許し、10進で書く（小数部・指数なし）。整数でない数値を含む文書は拒否する。

hash は、正規化形式のUTF-8バイト列のSHA-256である。ASCIIだけで整数だけの文書では、次のコマンドと一致する。

```sh
jq -cS . config/machine.json | tr -d '\n' | sha256sum
sbt "isa/run machine"    # machine_id、revision、hash を表示する
```

キーの順序や空白だけを変えても、hash は変わらない。

## 検証規則

ローダは、次の文書をすべて理由付きで拒否する。理由には `$.operations.MUL.latency` のようなJSONパスが付く。

- JSONとして不正な文書、重複したキーを含む文書（深さを問わない）、整数でない数値を含む文書
- 未知のキーがある、または必要なキーが無い
- 型が違う、または値が範囲外

キーごとの規則は次のとおり。「固定」は、mvp-0 の実装が対応する唯一の値である。異なる値を使うには、ISAとローダの変更が必要になる。

| キー | 規則 |
|---|---|
| `schema_version` | 1 |
| `revision` | 1以上の整数 |
| `status` | `proposed` / `frozen` |
| `isa_version` | 固定: `mvp-0` |
| `machine_id` | `[a-z0-9][a-z0-9-]*` |
| `word_bits` / `bundle_bits` | 固定: 32 / 64 |
| `binary_endianness` / `arithmetic` | 固定: `little` / `modulo_2_pow_32` |
| `slots.compute` / `slots.memory` | 固定: 1 / 1 |
| `register_file.count` | 1〜16（4bitのレジスタフィールド） |
| `register_file.zero_register` | 固定: `null` |
| `register_file.read_ports` | 固定: 3 |
| `register_file.write_ports.compute` / `.memory` | 固定: 1 / 1 |
| `register_file.same_cycle_same_destination_write` | 固定: `forbidden` |
| `scratchpad.banks` / `scratchpad.words_per_bank` | 1以上の整数。積は512以下（9bitの即値アドレス） |
| `scratchpad.bank_mapping` / `row_mapping` | `word_address_mod_<banks>` / `word_address_div_<banks>` |
| `scratchpad.ports_per_bank`、`global_issue_per_cycle`、`address_unit`、`read_during_write` | 固定: `1RW`、1、`word`、`forbidden` |
| `operations` | ちょうど ADD、SUB、MUL、LD、ST、HALT の6つ |
| `operations.<op>.slot` / `.writeback` | 固定: ADD/SUB/MUL は `compute` / `compute`、LD は `memory` / `memory`、ST は `memory` / `null` |
| `operations.<op>.opcode` | 1〜15。同じスロット内で一意（HALTを含む）。`nop_opcode` と異なる |
| `operations.<op>.latency` / `.ii` | 1〜8。ii は演算ごとの演算器の発行間隔（[ISA.md](ISA.md) の L9） |
| `operations.HALT` | `slot` は `compute`。`requires_no_pending_operations` と `requires_memory_nop` は `true`、`done_after_cycles` は1 |
| `nop_opcode` | 固定: 0 |
| `instruction_memory.bundles` | 1〜4096 |
| `control` | 固定: `branches` は `false`、`loops` は `unrolled`、`internal_stalls` は `false` |
| `timing` | 固定: `source_read` は `issue_cycle`、`availability` は `issue_cycle_plus_latency`、`dependency_constraint` は `consumer_issue_gte_producer_issue_plus_latency`、`writeback_cycle_reservation` は `true`、`kernel_cycles` は `halt_issue_cycle_plus_1` |

規則を変えるときは、`schema_version` を上げ、この表、ローダ、`MachineDescriptionSpec` を同時に変える。

### mvp-1（schema_version 2）

`schema_version` 2 は ISA `mvp-1` 専用で、`schema_version` 1 は `mvp-0` 専用のままにする（#69、[ADR-0018](DECISIONS.md)）。ローダは両方を受け付け、`config/machine.json`（mvp-0 revision 2、frozen）は変えない。上の表との違いは次のとおり。

| キー | 規則 |
|---|---|
| `schema_version` | 2 |
| `isa_version` | 固定: `mvp-1` |
| `register_file.read_ports` | 3 または 4。規則 L11（[ISA.md](ISA.md)）の上限で、ローダは値を保持する |
| `operations` | ちょうど ADD、SUB、MUL、MAC、LD、ST、HALT の7つ |
| `operations.MAC.slot` / `.writeback` | 固定: `compute` / `compute` |
| `operations.MAC.opcode` / `.latency` / `.ii` | ほかの演算と同じ規則（opcode は同じスロット内で一意、latency と ii は1〜8） |

ほかのキーの規則は `schema_version` 1 と同じである。

| ファイル | 内容 |
|---|---|
| `config/machine-mvp1.json` | `vliw2-i32-sp2-mac`、revision 1、`frozen`（#77 Phase A で凍結した）。`read_ports` 3、MAC は opcode 4・latency 2・ii 1、ほかは `config/machine.json` と同じ。#57 で方式を変えるときは revision を上げる（ADR-0018） |
| `tests/machines/` の mvp-1 の変種 | `read_ports` だけ4にしたもの（制約なしの比較用。verifier と参照モデルだけで動かす）と、MAC の latency だけ3にしたもの（感度の確認）（#77） |

### mvp-2（schema_version 3）

`schema_version` 3 は ISA `mvp-2`（W。[ISA.md](ISA.md)「mvp-2: W」）専用である。`schema_version` 1 は `mvp-0`、2 は `mvp-1` 専用のままにする（#78、[ADR-0022](DECISIONS.md)）。ローダは3つとも受け付け、`config/machine.json` と `config/machine-mvp1.json` は1 byte も変えない。`schema_version` 2 との違いは次のとおりで、表に無いキーの規則は `schema_version` 2 と同じである。`arithmetic` は `modulo_2_pow_32` のままで、レーンごとに当てはめる。

| キー | 規則 |
|---|---|
| `schema_version` | 3 |
| `isa_version` | 固定: `mvp-2` |
| `register_file.count` | 2〜16 の偶数（組の両方のレジスタが要る） |
| `register_file.banks`（新） | 固定: 2 |
| `register_file.bank_mapping`（新） | 固定: `register_index_mod_2` |
| `register_file.read_ports` | 3 または 4。RF の bank ごとの数で、規則 L11（形 F）は欄の数の上限に使う |
| `register_file.write_ports.compute` / `.memory` | 固定: 1 / 1。各境界で、各ポートで1つの操作が commit し、1つのレジスタか1つの組を書く |
| `scratchpad.max_words_per_access`（新） | 1 または 2。1つの Memory の操作が動かす語の数で、1 が W-reg、2 が W-full |
| `scratchpad.banks` | 1〜512。`max_words_per_access` が2なら2以上 |
| `scratchpad.global_issue_per_cycle` | 固定: 1（1サイクルの Memory の操作の数） |
| `operations` | ちょうど ADD、SUB、MUL、MAC、VADD、VSUB、VMUL、VMAC、LD、ST、HALT の11個。`max_words_per_access` が2なら、加えて LD.W と ST.W の13個 |
| `operations.VADD` / `.VSUB` / `.VMUL` / `.VMAC` の `slot` / `writeback` | 固定: `compute` / `compute` |
| `operations.LD.W.slot` / `.writeback` | 固定: `memory` / `memory` |
| `operations.ST.W.slot` / `.writeback` | 固定: `memory` / `null` |
| 新しい6つの演算の `opcode` / `latency` / `ii` | ほかの演算と同じ規則（opcode は1〜15で、同じスロット内で HALT を含めて一意、`nop_opcode` と異なる。latency と ii は1〜8）。加えて、`operations.ST.W.latency` は `operations.ST.latency` と同じ |

演算のキーは `"LD.W"`・`"ST.W"` と書く。JSON パスは `$.operations.LD.W.latency` のようになる。

キーの読み方:

- `register_file.read_ports` は「RF の bank ごと」の数に広げた。mvp-1 の RF は1つの bank なので、bank ごとと全体は同じで、mvp-1 のファイルの読み方は変わらない。L11 の文（形 F）も変わらない
- `global_issue_per_cycle` は、Memory の操作の数のままにした。W-full では1つの操作が2語を動かす
- 語の数は新しいキーにした。`global_issue_per_cycle` を語の数に使うと、固定の定数が1〜2のパラメータになり、名前（issue）と意味が合わなくなる。新しいキー1つで、LD.W・ST.W の有無、`scratchpad.banks` が2以上という要求、RTL の範囲が決まり、#111 の bank のポートの問いとも分けられる
- ST.W の latency は ST と同じにした。Memory の操作は1サイクルに1つなので、ST・ST.W の書込みは発行の順に別の境界で効き、同じ語への2つの書込みの順を決める規則が要らない（[ISA.md](ISA.md)「mvp-2: W」の構造上の性質への追加）。2つが違ってよい構成を足すなら、その規則も足す（ADR-0022）。ii は別々でよい
- LD.W・ST.W をキーで切り替えても、#69 の項目4の案B（同じ機械に名前と hash が2つできる。ADR-0018 で採らなかった案）にはならない。`max_words_per_access` は、64bit のメモリの経路と bank ごとの行アドレスという、ハードウェアの能力を表す。W-reg と W-full は、`read_ports` 3 と 4 と同じく、別の machine である

ローダは、上の表に合わないものを、ほかのキーと同じく JSON パス付きの理由で拒否する。例えば、奇数の `count` と2未満の `count`、2でない `register_file.banks`、`register_index_mod_2` でない `bank_mapping`、1〜2の外の `max_words_per_access`、`max_words_per_access` が2で `scratchpad.banks` が1、`max_words_per_access` が1で LD.W・ST.W がある（未知のキー）か、2で LD.W・ST.W が無い、`schema_version` 1・2 のファイルの新しいキー（未知のキー）、`schema_version` 3 で `isa_version` が `mvp-2` でない（と、その逆）、opcode の重複（例: LD.W の opcode 1）、ST の latency と違う ST.W の latency である。`max_words_per_access` が無いか不正なときは、その誤りを1回だけ報告し、`operations` は LD.W・ST.W の無い11個の表で確かめる。正規化形式と hash の決め方は変わらない。

| ファイル | machine_id | 内容 |
|---|---|---|
| `config/machine-mvp2.json` | `vliw2-i32-sp2-mac-w2` | W-full（既定）。revision 1、`proposed`（#110 Phase A の後に `frozen` にする。mvp-1 を #77 Phase A の後に凍結したのと同じ）。`config/machine-mvp1.json` に対し、`schema_version`・`isa_version`・`machine_id`・`status` を変え、RF の2つのキー、`max_words_per_access` 2、6つの演算を足した。`read_ports` は RF の bank ごとに3。#57 で変えるときは revision を上げ、ISA の版は mvp-2 のままにする（ADR-0018 と同じ） |
| `tests/machines/mvp2_mw1.json` | `vliw2-i32-sp2-mac-w2-mw1` | W-reg: `max_words_per_access` 1 で、`operations` に LD.W・ST.W が無い |
| `tests/machines/mvp2_rp4.json` | `vliw2-i32-sp2-mac-w2-rp4` | `read_ports` だけ4（制約なしの比較用。verifier と参照モデルだけで動かす） |
| `tests/machines/mvp2_vlat.json` | `vliw2-i32-sp2-mac-w2-vlat` | 新しい演算の latency だけ変えたもの（VADD・VSUB 2、VMUL・VMAC 3、LD.W 3）。verifier・参照モデル・RTL のテスト用 |
| `tests/machines/mvp2_vii.json` | `vliw2-i32-sp2-mac-w2-vii` | 新しい6つの演算の ii を2、ST と ST.W の latency を2にしたもの（ST.W の latency は ST と同じでなければならない）。verifier と参照モデルのテスト用（L9、語ごとの L10） |

変種は `proposed` のままにする（`mvp1_rp4`・`mvp1_mac3` と同じ）。latency を変えた2つは、ベンチマークの系列にも記録にも使わず、テストだけで確かめる（#78 の質問3）。変種が書いたキーだけで W-full と違うことは `isa/Mvp2Spec` が確かめる。どの machine もレジスタは16本で、machine_id は今の接尾辞（`-rp4`、`-mac3`）の形に合わせた。

## 実装ごとの対応範囲

machine description の契約は3段に分かれる。ローダが受け付ける構成は、すべて verifier と参照モデルで扱える（mvp-2 は #110 の第2段から。下）。RTL はその一部だけを実装し、範囲外の構成は組み立て時に理由付きで拒否する（[ADR-0009](DECISIONS.md)）。

| 段 | 判定する場所 | 範囲 |
|---|---|---|
| 形式 | `MachineDescription` のローダ | 上の「検証規則」の表 |
| 意味論 | `Verifier`（`compiler`）、`ReferenceModel`（`sim`） | ローダが受け付ける全構成。latency と ii の意味は [ISA.md](ISA.md) の実行意味論と L1〜L10、`read_ports` の意味は L11（#77）。mvp-2 は #110 の第2段で加えた（下） |
| RTL | `CoreParams`（`hw`） | 全演算の ii が1、LD の latency が2以上、ST の latency が1。ほかの値はローダの範囲全体。mvp-1 では、加えて `read_ports` が3（読出し4本の RTL は #57 で足す。ADR-0018）。mvp-2 は #110 の第3段で扱う（下） |
| 固定パイプライン（#75） | `FixedParams`（`hw`） | RTL と同じ。`CoreParams.unsupported` と同じ理由で拒否する（ADR-0017） |

RTL が対応しない理由は次のとおり。

| 制約 | 理由 |
|---|---|
| ii が1 | 全演算器を毎サイクル発行できるパイプラインにしている。II が2以上の演算器の予約は実装していない |
| LD の latency が2以上 | Scratchpad は同期読出しメモリで、発行の次のサイクルに値が出る |
| ST の latency が1 | 書込みを発行の境界で行う。2以上にすると、STの書込みと後続のLDの読出しが同じ bank で同じ境界に重なり得る。1RW の bank でこれを扱う設計は未定 |
| `read_ports` が3（mvp-1） | RF は 3R/2W のまま。ポート2のアドレスを MAC の rs3 と ST のソースで切り替える（MAC と ST が同じ bundle にあると L11）。読出し4本の RTL は #57（ADR-0018） |

mvp-2（W）の範囲は次のとおりで、#110 の各段で実装する（○の括弧の中が、その段）。

| 構成 | ローダ | verifier・参照モデル | RTL | 理由 |
|---|---|---|---|---|
| W-full、RF の bank ごとに `read_ports` 3（既定） | ○ | ○（第2段） | ○（第3段） | |
| W-reg、RF の bank ごとに `read_ports` 3 | ○ | ○（第2段） | ○（第3段） | 演算の幅とメモリの幅を分けて測る（ADR-0012） |
| W-full、RF の bank ごとに `read_ports` 4 | ○ | ○（第2段） | × | 読出し4本の RTL は #57（ADR-0018） |
| ii が1でない（新しい演算を含む） | ○ | ○（第2段） | × | 上の「ii が1」と同じ |
| LD.W の latency が2未満、ST・ST.W の latency が1でない | ○ | ○（第2段） | × | 上の LD・ST と同じ理由 |
| 固定パイプライン（#75） | — | — | × | MAC と同じ扱い（[PHASE3.md](PHASE3.md)） |

その段が入るまでは、mvp-2 の machine を mvp-1 として扱わずに、理由付きで拒否する。mvp-1 として扱うと、黙って誤るからである（RTL は W の命令語を未知の opcode として実行し、コンパイラは mvp-2 の名前で mvp-1 の命令列を作る）。`KernelCompiler.prepare`・`ModuloCompiler.prepare` は理由を返し（第4〜5段まで）、`CoreParams.unsupported` は理由を返す（第3段まで。`FixedParams` も同じ理由で拒否する）。`ReferenceModel`・`Metrics`（`sim`）と `Verifier`（`compiler`）、`compiler/run verify` と `sim/run` は、第1段では例外を投げるか入力の誤り（終了コード2）で終わっていたが、第2段で mvp-2 を扱うようになった（`Metrics` は `vliw-metrics/3` を書く）。第3段では、`CoreParams.unsupported` と `computeLatencies` を、決まった演算の一覧ではなく machine の全演算で数えるように直す（今のままだと、新しい演算の ii が1でなくても受け付け、`maxLatency` が小さくなり得る）。

`CoreParams.check` は、範囲外の設定を理由付きですべて返す。`sbt "hw/run [dir] [machine.json]"` は理由を表示して終了し、`CoreParams` を直接作ると組み立て時に例外になる。

RTL のテスト（`hw`）は、範囲外の構成のケースを、理由を付けてスキップとして表示し、検証済みには数えない。範囲内の別の構成（レイテンシー、レジスタ数、bank 数を変えたもの）は、`crosscheck/RtlDifferentialSpec` で RTL と参照モデルを照合する。範囲の両端（latency 1 と 8、レジスタ1個と16個、Scratchpad 1 word と 512 word、命令領域 1 と 4096 bundle）は `hw/EmitSpec` で組み立てられることを確かめる。
