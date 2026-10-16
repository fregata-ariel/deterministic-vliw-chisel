# Machine description（config/machine.json）

状態：`schema_version` 1（ISA `mvp-0`）。ISA `mvp-1`（MAC）のための `schema_version` 2 は、下の「[mvp-1（schema_version 2）](#mvp-1schema_version-2)」で定める（#69、ADR-0018。ローダの実装は #77）。ローダは `vliw.isa.MachineDescription`（sbtプロジェクト `isa`）。

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

## 実装ごとの対応範囲

machine description の契約は3段に分かれる。ローダが受け付ける構成は、すべて verifier と参照モデルで扱える。RTL はその一部だけを実装し、範囲外の構成は組み立て時に理由付きで拒否する（[ADR-0009](DECISIONS.md)）。

| 段 | 判定する場所 | 範囲 |
|---|---|---|
| 形式 | `MachineDescription` のローダ | 上の「検証規則」の表 |
| 意味論 | `Verifier`（`compiler`）、`ReferenceModel`（`sim`） | ローダが受け付ける全構成。latency と ii の意味は [ISA.md](ISA.md) の実行意味論と L1〜L10、`read_ports` の意味は L11（#77 で実装する） |
| RTL | `CoreParams`（`hw`） | 全演算の ii が1、LD の latency が2以上、ST の latency が1。ほかの値はローダの範囲全体。mvp-1 では、加えて `read_ports` が3（読出し4本の RTL は #57 で足す。ADR-0018） |
| 固定パイプライン（#75） | `FixedParams`（`hw`） | RTL と同じ。`CoreParams.unsupported` と同じ理由で拒否する（ADR-0017） |

RTL が対応しない理由は次のとおり。

| 制約 | 理由 |
|---|---|
| ii が1 | 全演算器を毎サイクル発行できるパイプラインにしている。II が2以上の演算器の予約は実装していない |
| LD の latency が2以上 | Scratchpad は同期読出しメモリで、発行の次のサイクルに値が出る |
| ST の latency が1 | 書込みを発行の境界で行う。2以上にすると、STの書込みと後続のLDの読出しが同じ bank で同じ境界に重なり得る。1RW の bank でこれを扱う設計は未定 |

`CoreParams.check` は、範囲外の設定を理由付きですべて返す。`sbt "hw/run [dir] [machine.json]"` は理由を表示して終了し、`CoreParams` を直接作ると組み立て時に例外になる。

RTL のテスト（`hw`）は、範囲外の構成のケースを、理由を付けてスキップとして表示し、検証済みには数えない。範囲内の別の構成（レイテンシー、レジスタ数、bank 数を変えたもの）は、`crosscheck/RtlDifferentialSpec` で RTL と参照モデルを照合する。範囲の両端（latency 1 と 8、レジスタ1個と16個、Scratchpad 1 word と 512 word、命令領域 1 と 4096 bundle）は `hw/EmitSpec` で組み立てられることを確かめる。
