# MVP ISAとサイクル契約

状態：Phase 0で検証する仕様案。ISAバージョン`mvp-0`。実行意味論と合法性規則は #5 で確定。規則 L9・L10 は #35 で追加した（凍結した machine description では判定が変わらない。[ADR-0009](DECISIONS.md)）。

ISAバージョン `mvp-1` は、`mvp-0` に MAC を1つ足した版である（#69、[ADR-0018](DECISIONS.md)。#77 で実装した）。差分は「[mvp-1: MAC](#mvp-1-mac)」の節（エンコードの節の後）にまとめ、この文書の mvp-0 の記述は変えない。規則 L11 は両方の版に共通で、mvp-0 では常に満たされる。

## サイクルの定義

サイクルsはクロック境界E_sからE_(s+1)まで。境界で完了したRF更新は、その直後のサイクルの組合せRF読出しから見える。サイクルsの命令は、そのサイクルで見えるソース値を読んで実行する。

Latency Lの結果は境界E_(s+L)でcommitされ、サイクルs+Lで使用できる。したがって依存命令は`s_consumer >= s_producer + L`とする。同一バンドル内で発行した演算結果を別スロットへ直接転送する機能はない。

ADDはサイクルsで計算しE_(s+1)でRFへ格納。MULは2段構成でE_(s+2)に格納。LDはサイクルsの要求をSyncReadMemがE_(s+1)で受け付け、サイクルs+1の出力をE_(s+2)でRFへ格納する。**SRAMの1-cycle読出しと、ISAのLD 2-cycleは異なる契約**である。

## 実行意味論

この節と次の「MVPの合法性規則」が、verifier・参照モデル・RTLの共通の基準である。各実装はここから独立に導く（[ADR-0002](DECISIONS.md)）。骨格は次の3点で、MVPではこれを変えない。

1. ソースは**発行サイクル**で読む。
2. 結果は**固定レイテンシー後**の境界でcommitする。
3. 同一bundle内で、あるスロットの結果を別スロットへ直接転送しない。

### 状態

| 状態 | 内容 |
|---|---|
| RF | r0〜r15、各32bit |
| Scratchpad | word address 0〜511（`bank=addr%2`、`row=addr/2`） |
| 実行中の操作 | 発行済みで未commitの操作。commit境界、書込み先、値を持つ |
| 制御 | PC、busy、done |

分岐が無いため、サイクル s に実行するbundleは常に命令領域の s 番目（PC = s）である。

### 1サイクルの手順

サイクル s では、境界 E_s までのcommitをすべて反映した状態が見える。この状態を「サイクル s で見える状態」と呼ぶ。

1. **読出し**: bundle s の全ソースを、サイクル s で見えるRFから読む。ADD/SUB/MULの rs1・rs2 に加え、STのソースレジスタも s で読む。
2. **計算と予約**: 各操作の結果を、commit境界 E_(s+L) とともに予約する。
   - ADD/SUB: 値は `rs1 ± rs2` の下位32bit、L=1
   - MUL: 値は `rs1 × rs2` の下位32bit、L=2
   - LD: 値は**サイクル s で見えるScratchpad**の `[addr]`、L=2（RFへのcommit）
   - ST: 値はサイクル s で読んだソース、L=1（Scratchpadへのcommit）
3. **commit**: 境界 E_(s+1) で、E_(s+1) に予約された操作をすべて反映する。RFへのcommitはComputeとMemoryの書戻しポート、Scratchpadへのcommitは ST による。

LDの値を「サイクル s で見えるScratchpad」と定義できるのは、Memoryスロットが1つで、規則 L10 があるからである。ハードウェアでは、SyncReadMem が E_(s+1) で要求を受け付け、サイクル s+1 の出力を E_(s+2) でRFへ格納する。同じ番地へのSTが E_(s+1) にcommitすることはない。s に発行されたSTは同じスロットなので存在せず、s より前に発行されてまだcommitしていないSTは L10 で禁止され、s より後に発行されたSTは E_(s+2) 以降にcommitする。したがって両者の定義は一致する。mvp-0 では ST のレイテンシーが1なので、L10 は常に満たされる。

### 制御

- HALTをサイクル h に発行すると、E_(h+1) で done を1サイクル通知し、busyを解除する。done はサイクル h+1 の間だけ1になる。
- 実行サイクル数は `h+1`。
- 合法なプログラムでは、HALT発行時点で実行中の操作は無い（規則 L6）。そのため、最終状態はサイクル h で見える状態と一致する。

## MVPの合法性規則

合法なプログラムの範囲を、意味論で定義できる範囲よりも保守的に限定する。不正なプログラムはverifierが拒否する。参照モデルは、実行中に違反を検出したら停止してエラーを報告する。RTLは違反をassertionで検出する（シミュレーション時）。ストールで違反を隠すことはしない。

用語を2つ定める。

- **書込み区間**: サイクル s に発行され、レジスタ r に書き込む L サイクルの操作は、r に対して半開区間 `[s, s+L)` を占める。値は s+L から見える。
- **入力宣言**: 開始前にホストが設定するレジスタの集合 R_in と、Scratchpadのアドレスの集合 M_in。プログラムの合法性は入力宣言とあわせて判定する（テストケースで宣言する。#7）。
- **演算器と II**: ADD、SUB、MUL、LD、ST は、それぞれ専用の演算器を持つ。演算 op をサイクル s に発行すると、op の演算器が次の op を受け付けるのは s + II_op から（`operations.<op>.ii`）。演算器を複数の演算で共有する構成は、mvp-0 では表現しない。

| 規則 | 内容 |
|---|---|
| L1 符号化 | 全bundleが正しくdecodeできる。未知opcode、予約bitが非ゼロ、NOP/HALTのopcode以外のbitが非ゼロ、のいずれも不正。HALTのMemory wordはNOPでなければならない。 |
| L2 プログラム形状 | bundle数は1以上、命令領域の容量（`instruction_memory.bundles`）以下。HALTはちょうど1つで、最後のbundleに置く。 |
| L3 書込み中の読出し禁止 | サイクル t のレジスタ r の読出しは、r の書込み区間 `[s, s+L)` が `s < t < s+L` となるとき不正。発行サイクル t = s 自身での読出しは合法で、旧値を読む（例: `ADD r1, r1, r2`）。 |
| L4 書込み区間の重複禁止 | 同じレジスタに対する書込み区間は互いに重ならない。同一境界での二重commitと、commit順序に依存するプログラムを排除する。 |
| L5 書戻しポート | 各境界でのcommitは、Computeポート・Memoryポートともに高々1つ。例: MUL(s) と ADD/SUB(s+1) はどちらも E_(s+2) にComputeポートを使うため不正。 |
| L6 HALT時の完了 | HALTの発行サイクル h で、全操作が `s+L <= h` を満たす。隠れたdrain待ちで実行時間を延ばさない。 |
| L7 アドレス範囲 | LD/STのアドレスは `banks × words_per_bank` 未満。9bit即値は0〜511をすべて表せるため、現行のmachine定義では、エンコード後に違反は起こらない。違反は、アセンブラ入力の範囲外（例: `[512]`）と、容量を縮小したmachine定義で起こる。 |
| L8 未定義値の読出し禁止 | サイクル t のレジスタ r の読出しは、r ∈ R_in であるか、`s+L <= t` を満たす r への書込みがあるときだけ合法。LD [a] は、a ∈ M_in であるか、`s+L <= t` を満たす ST [a] があるときだけ合法（mvp-0 の ST は L=1 なので、t より前に発行された ST [a] があればよい）。アドレスは即値なので静的に判定できる。 |
| L9 発行間隔 | 同じ演算の2つの発行サイクル s < s' は `s' - s >= II` を満たす。異なる演算の間には制約が無い（例: ADD の II が2でも、ADD の直後に SUB を発行できる）。mvp-0 では全演算の II が1なので、常に満たされる。 |
| L10 書込み中のメモリ語の読出し禁止 | サイクル t の LD [a] は、ST [a] の書込み区間 `[s, s+L)` が `s < t < s+L` となるとき不正。旧値が定義されていても（a ∈ M_in）不正とする。レジスタの L3 と同じ扱いで、STの書込み時刻に依存するプログラムを排除する。同じbankの別の番地は対象外。mvp-0 では ST の L が1なので、常に満たされる。 |
| L11 読出しポート | bundle s のレジスタの読出しの数は、`register_file.read_ports` 以下でなければならない。数はオペランドの欄で数える（ADD/SUB/MUL は 2、mvp-1 の MAC は 3、ST は 1、LD・NOP・HALT は 0）。同じレジスタを2つの欄で読んでも2と数える。mvp-0 の演算の読出しは bundle 当たり最大3で、`read_ports` は3なので、mvp-0 では常に満たされる（#69、ADR-0018）。 |

次の性質は構造上成り立つため、規則を設けない。

- 発行数: Compute・Memoryとも、bundle当たり高々1操作。
- Scratchpadのbank競合と同一境界のread/write: Memoryスロットが1つで、ST のレイテンシーが1なら発生しない。ST のレイテンシーが2以上の構成では、STの書込みと後続のLDの読出しが同じbankで同じ境界に重なり得る。意味論（verifier・参照モデル）では値の競合は L10 で排除されており、bank の物理的な競合は RTL の実装の問題として扱う（現行の RTL は ST のレイテンシー1だけに対応する。[MACHINE.md](MACHINE.md)「実装ごとの対応範囲」）。将来DMAや複数Memoryスロットを追加する際は、この節に規則を追加し、verifierとRTLで維持する。
- メモリ順序: 同一アドレスへの2操作は必ず別サイクルになり、先に発行した方が先に効く（LDの値の定義を参照）。ただし、DAGやスケジューラ（Phase 2）は、aliasするLD/STの依存を落としてはならない。bank競合の検査だけでは、意味上の順序は保証できない。

依存命令の配置 `s_consumer >= s_producer + L` は、L3とL8から導かれる（メモリ経由の依存では L10 と L8）。ソースの書込み区間の外側で、かつcommit後に読む必要があるからである。

### 例

ソースはすべて入力宣言済みとし、注目する規則以外の違反（末尾のHALTの欠落など）は無視する。

| 命令列（cycle: Compute \| Memory） | 判定 | 理由 |
|---|---|---|
| 0: `MUL r3, r1, r2` / 1: `NOP \| ST [0], r3` | 不正 | L3（r3 の書込み区間 [0, 2) 内で読出し） |
| 0: `MUL r3, r1, r2` / 1: `ADD r4, r3, r1` | 不正 | L3 と L5（ADDは r3 を書込み中に読み、さらに MUL と同じ E_2 にComputeポートを使う） |
| 0: `MUL r3, r1, r2` / 1: `NOP` / 2: `ADD r4, r3, r1` | 合法 | r3 は cycle 2 から見える |
| 0: `MUL r3, r1, r2` / 1: `ADD r5, r1, r2` | 不正 | L5（両方が E_2 にComputeポートを使う） |
| 0: `ADD r1, r1, r2` | 合法 | 発行サイクル自身での読出し |
| 0: `ADD r1, r2, r3 \| LD r1, [0]` | 不正 | L4（r1 の区間 [0, 1) と [0, 2) が重なる） |
| 0: `MUL r1, r2, r3` / 1: `NOP \| LD r1, [0]` | 不正 | L4（[0, 2) と [1, 3) が重なる） |
| 0: `NOP \| ST [1], r4` / 1: `HALT` | 合法 | ST は E_1 で完了し、1 <= 1 |
| 0: `NOP \| LD r1, [0]` / 1: `HALT` | 不正 | L6（2 > 1） |

L9 と L10 は、既定の構成（全 II が1、ST のレイテンシー1）では起こらない。構成を変えた例は、[tests/cases](../tests/cases/README.md) の `ii_*` と `slow_store_*` にある。

## 演算

全レジスタr0〜r15は通常の32bitレジスタ。ゼロレジスタは設けない。開始前にホストが必要な入力レジスタを設定し、その集合を入力宣言 R_in とする（規則 L8）。ADD/SUB/MULは結果を32bitへ切り詰め、符号付き・符号なしの下位32bitが一致するビットベクトル算術として扱う。

| Slot | Opcode | 意味 | Latency | II |
|---|---|---|---|---|
| Compute | NOP | 操作なし | — | — |
| Compute | ADD | rd ← rs1 + rs2 | 1 | 1 |
| Compute | SUB | rd ← rs1 − rs2 | 1 | 1 |
| Compute | MUL | rd ← low32(rs1 × rs2) | 2 | 1 |
| Compute | HALT | 既存操作完了後に終了 | 発行後1 | — |
| Memory | NOP | 操作なし | — | — |
| Memory | LD | rd ← scratchpad[addr] | 2 | 1 |
| Memory | ST | scratchpad[addr] ← rs | 1 | 1 |

アドレスはバイト単位でなく32bit word index。範囲0〜511、`bank=addr%2`、`row=addr/2`。初期は即値アドレスのみで、間接アドレスと分岐は後続拡張とする。命令領域は256 bundle（`instruction_memory.bundles`）。

## 64bit bundleのエンコード案

低位32bitがCompute、高位32bitがMemory。ファイル上の1bundleは8byte little-endianとする。未使用フィールドと予約bitは0。未知opcode・予約bit非ゼロは不正命令として検証器が拒否する。

| Word | Bits | 内容 |
|---|---|---|
| Compute | 31:28 | opcode: NOP=0, ADD=1, SUB=2, MUL=3, HALT=15 |
| Compute | 27:24 | rd |
| Compute | 23:20 | rs1 |
| Compute | 19:16 | rs2 |
| Compute | 15:0 | reserved=0 |
| Memory | 31:28 | opcode: NOP=0, LD=1, ST=2 |
| Memory | 27:24 | LD destination / ST source |
| Memory | 23:15 | 9bit immediate word address |
| Memory | 14:0 | reserved=0 |

NOPとHALTはopcode以外の全bitを0とする。HALTに対応するMemory wordはNOPでなければならない。

命令列のファイル形式は2つある。実装は `vliw.isa.BundleCodec` と `vliw.isa.ProgramFile`。

| 形式 | 内容 | 用途 |
|---|---|---|
| `.bin` | 1bundle = 8byte little-endian | 配布・ホストからのロード |
| `.hex` | 1行1bundle、64bit値を16桁の小文字16進で書く（Memory wordが上位） | `$readmemh`、Chiselの `loadMemoryFromFile`、差分レビュー |

下の命令例のエンコード結果は、[tests/golden/isa_example.hex](../tests/golden/isa_example.hex) に手作業で求めた値として固定している。

## mvp-1: MAC

`mvp-1` は、`mvp-0` に Compute スロットの演算 MAC を1つ足した版である（#69、[ADR-0018](DECISIONS.md)）。mvp-0 の命令語は、mvp-1 でも同じ意味になる。#77 で実装した。

### 意味

| Slot | Opcode | 意味 | Latency | II |
|---|---|---|---|---|
| Compute | MAC | rd ← low32(rs1 × rs2 + rs3) | 2（既定） | 1（既定） |

- 32bit の整数で、2^32 を法とする。3つのソース rs1・rs2・rs3 は、すべて発行サイクルに読む（実行意味論の骨格の1）。結果は E_(s+L) で Compute の書戻しポートから commit する
- rd は rs3 と独立に選べる。`rd = rs3` も合法で、rs3 は旧い値を読む（累算の形）
- latency と II は machine description の `operations.MAC` から取る。MAC は専用の演算器を持ち、L9 は演算ごとに数える（MUL と MAC は別の演算）。演算器を複数の演算で共有する構成は、mvp-1 でも表現しない。Compute スロットは1サイクルに1つの演算しか発行せず、RTL は値を発行サイクルに計算するので、RTL が乗算器を MUL と MAC で共有しても、II が1の構成では外から区別できない

1サイクルの手順への追加:

1. **読出し**: MAC は rs1・rs2 に加えて rs3 もサイクル s で読む
2. **計算と予約**: MAC の値は `rs1 × rs2 + rs3` の下位32bit、L は `operations.MAC.latency`

### 合法性規則での扱い

| 規則 | MAC での扱い |
|---|---|
| L1 | opcode は machine description から取る（既定 4）。MAC の Compute word の bits 11:0 は0。rs3 はレジスタ数より小さい。ADD/SUB/MUL の bits 15:0 は、今と同じく予約で0 |
| L3・L8 | 読出しに rs3 を含める（MAC のときだけ） |
| L4 | rd の書込み区間は `[s, s+L_MAC)`。同じ bundle の LD が同じ rd に書けば不正 |
| L5 | Compute ポートを E_(s+L_MAC) に使う。L_MAC=2 では、MAC(s) と ADD/SUB(s+1) が衝突し、MAC(s) と MUL(s+1) は衝突しない |
| L6 | 「全操作」に MAC を含める |
| L9 | MAC の発行間隔は `operations.MAC.ii` |
| L11 | MAC は読出しを3つ数える。`read_ports` が3の構成では、MAC と ST を同じ bundle に置けない（読出し4）。4の構成では置ける |

依存命令の配置 `s_consumer >= s_producer + L` は、rs3 の生産者にも当てはまる。

### エンコード

Memory word は mvp-0 と同じ。Compute word は次のとおり。

| Bits | 内容 |
|---|---|
| 31:28 | opcode。既定は NOP=0, ADD=1, SUB=2, MUL=3, **MAC=4**, HALT=15。値は machine description から取る |
| 27:24 | rd |
| 23:20 | rs1 |
| 19:16 | rs2 |
| 15:12 | rs3（MAC だけ。ADD/SUB/MUL では予約で0） |
| 11:0 | reserved=0 |

例: `mac r5, r1, r2, r3`（Memory は NOP）は `0x0000000045123000` である。

### 例

ソースはすべて入力宣言済みとし、注目する規則以外の違反は無視する。`read_ports` は3とする（最後から2つ目の行を除く）。

| 命令列（cycle: Compute \| Memory） | 判定 | 理由 |
|---|---|---|
| 0: `MUL r3, r1, r2` / 1: `MAC r4, r1, r2, r3` | 不正 | L3（rs3 の r3 が書込み中） |
| 0: `MUL r3, r1, r2` / 1: `NOP` / 2: `MAC r4, r1, r2, r3` | 合法 | r3 は cycle 2 から見える |
| 0: `MAC r1, r2, r3, r1` / 1: `NOP` / 2: `MAC r1, r2, r3, r1` | 合法 | rd = rs3 の累算 |
| 0: `MAC r1, r2, r3, r1` / 1: `MAC r1, r4, r5, r1` | 不正 | L3 と L4 |
| 0: `MAC r4, r1, r2, r3` / 1: `ADD r5, r1, r2` | 不正 | L5（どちらも E_2） |
| 0: `MAC r4, r1, r2, r3` / 1: `MUL r5, r1, r2` | 合法 | E_2 と E_3 |
| 0: `MAC r4, r1, r2, r3 \| LD r4, [0]` | 不正 | L4（[0, 2) が重なる） |
| 0: `MAC r4, r1, r2, r3` / 1: `HALT` | 不正 | L6（2 > 1） |
| 0: `MAC r4, r1, r2, r3 \| ST [0], r5` | read_ports 3 で不正、4 で合法 | L11（読出し4） |
| 0: `MUL r4, r1, r2 \| ST [0], r5` | 合法 | 読出し3 |

## 開始・終了

idle中だけstartを受理する。受理境界をE_0とし、最初のbundleをサイクル0で実行する。busy中のstartは受理しない。HALTをサイクルhに発行するとE_(h+1)でdoneを1cycle通知し、busyを解除する。実行サイクル数は`h+1`。

例：r1/r2を初期化してADD → HALTを実行する場合、ADDはcycle0、結果はE_1、HALTはcycle1、doneはE_2。期待サイクル数は2。

## 最初の命令例

アセンブリ構文も初期案。r2=3、scratchpad[0]=7を開始前に配置する（入力宣言 R_in={r2}、M_in={0}）。

| Cycle | Compute | Memory | 結果・制約 |
|---|---|---|---|
| 0 | NOP | LD r1, [0] | r1はE_2で7 |
| 1 | NOP | NOP | LD待ちを命令列で表現 |
| 2 | MUL r3, r1, r2 | NOP | r3はE_4で21 |
| 3 | NOP | NOP | 次のADDとは依存あり |
| 4 | ADD r4, r3, r1 | NOP | r4はE_5で28 |
| 5 | NOP | ST [1], r4 | memory[1]はE_6で28 |
| 6 | HALT | NOP | done=E_7、計7cycles |

この例をPhase 0の参照モデルとPhase 1のRTLで同一トレースとして検証する。バイナリエンコードと逆変換の一致も確認する。
