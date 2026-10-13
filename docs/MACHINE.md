# Machine description（config/machine.json）

状態：`schema_version` 1。ローダは `vliw.isa.MachineDescription`（sbtプロジェクト `isa`）。

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
| `operations.<op>.latency` / `.ii` | 1〜8 |
| `operations.HALT` | `slot` は `compute`。`requires_no_pending_operations` と `requires_memory_nop` は `true`、`done_after_cycles` は1 |
| `nop_opcode` | 固定: 0 |
| `instruction_memory.bundles` | 1〜4096 |
| `control` | 固定: `branches` は `false`、`loops` は `unrolled`、`internal_stalls` は `false` |
| `timing` | 固定: `source_read` は `issue_cycle`、`availability` は `issue_cycle_plus_latency`、`dependency_constraint` は `consumer_issue_gte_producer_issue_plus_latency`、`writeback_cycle_reservation` は `true`、`kernel_cycles` は `halt_issue_cycle_plus_1` |

規則を変えるときは、`schema_version` を上げ、この表、ローダ、`MachineDescriptionSpec` を同時に変える。
