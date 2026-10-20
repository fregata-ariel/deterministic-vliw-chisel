# MVP ISAとサイクル契約

状態：Phase 0で検証する仕様案。ISAバージョン`mvp-0`。実行意味論と合法性規則は #5 で確定。規則 L9・L10 は #35 で追加した（凍結した machine description では判定が変わらない。[ADR-0009](DECISIONS.md)）。

ISAバージョン `mvp-1` は、`mvp-0` に MAC を1つ足した版である（#69、[ADR-0018](DECISIONS.md)。#77 で実装した）。差分は「[mvp-1: MAC](#mvp-1-mac)」の節（エンコードの節の後）にまとめ、この文書の mvp-0 の記述は変えない。規則 L11 は両方の版に共通で、mvp-0 では常に満たされる。

ISAバージョン `mvp-2` は、`mvp-1` に W を足した版である。W は、偶数・奇数のレジスタの組を 32bit×2 のレーンとして使う演算と、2語の LD/ST である（#78 のオーナー判断 2026-10-20、[ADR-0022](DECISIONS.md)）。差分は「[mvp-2: W](#mvp-2-w)」の節（mvp-1 の節の後）にまとめ、mvp-0 と mvp-1 の記述は変えない。規則は L1〜L11 のままで、新しい番号の規則は足さない。#110 で段階的に実装する（第1段で契約・ローダ・エンコーダ・デコーダ・アセンブラ、第2段で verifier と参照モデル、第3段で RTL、第4〜5段でコンパイラ）。

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
| L7 アドレス範囲 | LD/STのアドレスは `banks × words_per_bank` 未満。9bit即値は0〜511をすべて表せるため、mvp-0・mvp-1 の現行のmachine定義では、エンコード後に違反は起こらない。違反は、アセンブラ入力の範囲外（例: `[512]`）と、容量を縮小したmachine定義で起こる（mvp-2 の LD.W・ST.W は `a+1` にもアクセスするので、`[511]` でも起こる。「[mvp-2: W](#mvp-2-w)」）。 |
| L8 未定義値の読出し禁止 | サイクル t のレジスタ r の読出しは、r ∈ R_in であるか、`s+L <= t` を満たす r への書込みがあるときだけ合法。LD [a] は、a ∈ M_in であるか、`s+L <= t` を満たす ST [a] があるときだけ合法（mvp-0 の ST は L=1 なので、t より前に発行された ST [a] があればよい）。アドレスは即値なので静的に判定できる。 |
| L9 発行間隔 | 同じ演算の2つの発行サイクル s < s' は `s' - s >= II` を満たす。異なる演算の間には制約が無い（例: ADD の II が2でも、ADD の直後に SUB を発行できる）。mvp-0 では全演算の II が1なので、常に満たされる。 |
| L10 書込み中のメモリ語の読出し禁止 | サイクル t の LD [a] は、ST [a] の書込み区間 `[s, s+L)` が `s < t < s+L` となるとき不正。旧値が定義されていても（a ∈ M_in）不正とする。レジスタの L3 と同じ扱いで、STの書込み時刻に依存するプログラムを排除する。同じbankの別の番地は対象外。mvp-0 では ST の L が1なので、常に満たされる。 |
| L11 読出しポート | bundle s のレジスタの読出しの数は、`register_file.read_ports` 以下でなければならない。数はオペランドの欄で数える（ADD/SUB/MUL は 2、mvp-1 の MAC は 3、ST は 1、LD・NOP・HALT は 0）。同じレジスタを2つの欄で読んでも2と数える。mvp-0 の演算の読出しは bundle 当たり最大3で、`read_ports` は3なので、mvp-0 では常に満たされる（#69、ADR-0018）。 |

次の性質は構造上成り立つため、規則を設けない。

- 発行数: Compute・Memoryとも、bundle当たり高々1操作。
- Scratchpadのbank競合と同一境界のread/write: Memoryスロットが1つで、ST のレイテンシーが1なら発生しない。ST のレイテンシーが2以上の構成では、STの書込みと後続のLDの読出しが同じbankで同じ境界に重なり得る。意味論（verifier・参照モデル）では値の競合は L10 で排除されており、bank の物理的な競合は RTL の実装の問題として扱う（現行の RTL は ST のレイテンシー1だけに対応する。[MACHINE.md](MACHINE.md)「実装ごとの対応範囲」）。将来DMAや複数Memoryスロットを追加する際は、この節に規則を追加し、verifierとRTLで維持する。
- mvp-2 の LD.W・ST.W: 語 `a` と `a+1` にアクセスする。`banks` が2以上なら2語は別の bank にあり、各 bank へのアクセスは1サイクルに高々1つである。Memory の操作は1サイクルに1つのままである（「[mvp-2: W](#mvp-2-w)」）。
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

## mvp-2: W

`mvp-2` は、`mvp-1` に W を足した版である（#78 のオーナー判断 2026-10-20、[ADR-0022](DECISIONS.md)。#110 で段階的に実装する）。W は、同じ i32 の計算を保ったまま、演算の幅と転送の幅を広げる実験である。偶数・奇数のレジスタの組を 32bit×2 のレーンとして使い、各レーンはスカラーの i32 の演算とまったく同じ計算をする。新しい状態は足さない。

- 演算: Compute スロットの VADD・VSUB・VMUL・VMAC（mvp-2 の machine は必ず持つ）と、Memory スロットの LD.W・ST.W（`scratchpad.max_words_per_access` が2の machine だけが持つ）。2の machine を W-full、1の machine を W-reg と呼ぶ（[MACHINE.md](MACHINE.md)）
- 命令語の互換性: スカラーの opcode が mvp-1 の既定と同じ mvp-2 の machine（既定の machine はそうである）では、mvp-1 で合法な命令語は、どれも同じ操作に decode される。bit 11 を立てた MAC は、MAC の bits 11:0 が予約のままなので、L1 で不正のままである
- 命令列の互換性: スカラーの演算の latency・II とレジスタ数が mvp-1 と同じ mvp-2 の machine（既定の machine はそうである）では、mvp-1 で合法な命令列は合法のままで、タイミングも同じである。スカラーの演算については、L1 の decode、L3、L4、L8、L10、操作ごとの L5、L7、欄の数で数える L11 が変わらないからである。#110 の第2段で、mvp-1 のケースとベンチマークの命令列を mvp-2 で実行し、判定・出力・cycles・トレースの本体が同じことを確かめる（ヘッダの `isa`・`machine`・`machine_revision`・`machine_hash` だけが違う）

### 用語

- **組 p**: レジスタ `r[p]` と `r[p+1]`（p は偶数）。`rP:rQ`（Q = P+1）と書く（例: `r4:r5`）。レーン0 は `r[p]`、レーン1 は `r[p+1]` である
- **RF の bank**: `r mod 2`。bank 0 が偶数のレジスタ、bank 1 が奇数のレジスタを持つ（16本なら8本ずつ）。Scratchpad の bank とは別のものである。RTL のデータ経路と、L11 の形 S・C（後で検討する。下の表）で使う。この版の L11（形 F）は使わない
- **欄**: bundle のレジスタのオペランドの欄。Compute の rs1・rs2・rs3 と、Memory の ST・ST.W のソースである。組の欄は2つのレジスタを読み、ブロードキャストの rs1 は1つのレジスタを読む。L11 では、どの欄も1と数える
- 算術は `modulo_2_pow_32` のままで、**レーンごと**に当てはめる。桁上がり・借り・積の bit はレーンをまたがない

### 意味

| Slot | 演算 | 既定の opcode | 意味（レーン i = 0, 1。各レーンは 2^32 を法とする） | Latency（既定） | II（既定） |
|---|---|---|---|---|---|
| Compute | VADD | 5 | `r[rd+i] ← r[rs1+i] + r[rs2+i]` | 1 | 1 |
| Compute | VSUB | 6 | `r[rd+i] ← r[rs1+i] − r[rs2+i]` | 1 | 1 |
| Compute | VMUL | 7 | `r[rd+i] ← low32(A_i × r[rs2+i])`、`A_i = B ? r[rs1] : r[rs1+i]` | 2 | 1 |
| Compute | VMAC | 8 | `r[rd+i] ← low32(A_i × r[rs2+i] + r[rs3+i])` | 2 | 1 |
| Memory | LD.W | 3 | `r[rd] ← M[a]`、`r[rd+1] ← M[a+1]` | 2 | 1 |
| Memory | ST.W | 4 | `M[a] ← r[rs]`、`M[a+1] ← r[rs+1]` | 1 | 1 |

- B はブロードキャストの bit（bit 11。下の「エンコード」）で、VMUL と VMAC だけにある。B=1 の rs1 は1つのレジスタで、1回読んで両方のレーンに使う
- 各演算は `operations` に自分の項目を持ち、opcode・latency・ii は machine description から取る。latency と ii の既定は、対応するスカラーの演算と同じである（VADD・VSUB は ADD、VMUL は MUL、VMAC は MAC、LD.W は LD、ST.W は ST）
- 6つの演算は、それぞれ専用の演算器と `ii` を持ち、L9 は演算ごとに数える（VMUL と MUL は別の演算）。演算器を複数の演算で共有する構成は、mvp-2 でも表現しない。RTL は、レーン0 の乗算器を MUL・MAC・VMUL・VMAC で共有してよい（II が1の構成では外から区別できない。mvp-1 の MAC と同じ理由）。レーン1 には2つ目の乗算器が要る（#57 のために記録する）
- rd は、どのソースの組と同じでもよい。`vmac r4:r5, r2, r6:r7, r4:r5`（rd = rs3）は累算の形で、ソースは発行サイクルの旧い値を読む。ブロードキャストの rs1 が `r[rd]` や `r[rd+1]` でもよい

1サイクルの手順への追加:

1. **読出し**: どの欄も発行サイクル s に読む。組の欄は `r[p]` と `r[p+1]` を読み、ブロードキャストの rs1 は `r[rs1]` を1回読んで両方のレーンに使う。ST.W は `r[p]` と `r[p+1]` を読む
2. **計算と予約**: ベクトル演算は、レーンごとの2つの値を、Compute の書戻しポートの E_(s+L) に予約する。LD.W の値は、**サイクル s で見えるScratchpad** の `M[a]` と `M[a+1]` で、Memory の書戻しポートの E_(s+L) に予約する。ST.W の値は、サイクル s で読んだ2つのレジスタで、`M[a]` と `M[a+1]` に E_(s+L) で予約する
3. **commit**: ベクトル演算と LD.W は、**1つの操作**が**1つのポート**で**2つのレジスタ**を commit する。ST.W は、1つの操作が2語を commit する

LD の値を「サイクル s で見えるScratchpad」と定義できる理由（「実行意味論」）は、LD.W でも**語ごと**に成り立つ。Memory スロットは1つで、L10 は語ごとに判定するからである。

### LD.W・ST.W の語・番地・bank

- 2語は連続した `a` と `a+1` である。**`a` は奇数でもよい**。`a` は9bit の即値（0〜511）である
- レーン0 は常に語 `a`、レーン1 は常に語 `a+1` である。bank と行は今の対応のまま（`bank = addr mod banks`、`row = addr div banks`）
- `banks` が2以上なら、2語は常に**別の bank** にあり、各 bank へのアクセスは1サイクルに高々1つである。既定の2 bank では次のとおり
  - `a` が偶数: レーン0 は bank 0 の行 a/2、レーン1 は bank 1 の同じ行
  - `a` が奇数: レーン0 は bank 1 の行 (a−1)/2、レーン1 は bank 0 の行 (a+1)/2（次の行）。RTL はレーンを入れ替え、bank 0 の行に1を足す
- `max_words_per_access` が2の machine には、`scratchpad.banks` が2以上必要である（ローダが確かめる）。1 bank の W-full は表現しない
- Memory の操作は1サイクルに1つのまま（`global_issue_per_cycle` 1）である。LD.W・ST.W は、Memory の操作の数ではなく、Core の Memory の語の帯域を2倍にする。これは #109 の範囲のメモリの帯域の拡張で、#111 で DMA との bank のポートの共有を数えるときは、LD.W・ST.W のサイクルは両方の bank を使う（#78 の質問2）
- L7: 2語とも `banks × words_per_bank` 未満でなければならない。`ld.w …, [511]` はエンコードできるが、512語の machine では L7（語 512）の違反である
- `ports_per_bank` は `1RW` のままである

### 合法性規則での扱い

新しい番号の規則は足さず、W は L1〜L11 で扱う。偶数の組の欄とブロードキャストの bit は L1 に入る。組の部分更新には規則が要らない（下）。

| 規則 | mvp-2 での扱い |
|---|---|
| L1 | opcode は machine description から取る。組の欄は偶数。B は VMUL・VMAC だけに置ける。予約 bit（0）は、VADD/VSUB が bits 15:0、VMUL が bits 15:12 と 10:0、VMAC が bits 10:0、LD.W/ST.W が bits 14:0、MAC が bits 11:0（mvp-1 と同じ）。組の p+1 を含め、どのレジスタも `register_file.count` より小さい。`max_words_per_access` が1の machine では、LD.W・ST.W の opcode は未知である |
| L2 | 変わらない |
| L3 | 欄が読むすべてのレジスタに当てはめる（組の欄は両方のレジスタ、ブロードキャストの rs1 は1つ、ST.W は両方）。発行サイクルの読出しは合法で、旧い値を読む。`vmac r4:r5, r2, r6:r7, r4:r5`（rd = rs3）は累算の形である |
| L4 | 書込み区間はレジスタごと。ベクトル演算と LD.W は、p と p+1 の両方に `[s, s+L)` を占める。組の書込みは、どちらかのレジスタへのスカラーの書込みと重なる（同じ bundle の `vmac r4:r5, … \| ld r5, [0]` も） |
| L5 | 各境界で、各ポート（Compute、Memory）で commit する**操作**は高々1つ。ベクトル演算と LD.W は組を commit し、ポートを1回使う。`mul r1`(s) と `add r4`(s+1) は、r1 と r4 の RF の bank が違っても不正のままである |
| L6 | 「全操作」に6つの新しい演算を含める |
| L7 | LD・ST・LD.W・ST.W がアクセスするすべての語が `banks × words_per_bank` 未満（LD.W・ST.W は `a` と `a+1`） |
| L8 | レジスタごと（組は両方、ブロードキャストの rs1 は1つ）。語ごと: LD.W [a] は、`a` と `a+1` のどちらも、M_in にあるか、`s+L <= t` を満たす ST・ST.W が書いていなければならない |
| L9 | 形は変わらない。6つの新しい演算は、それぞれ専用の演算器と `ii` を持つ |
| L10 | 語ごと。サイクル t の LD・LD.W は、読むどれかの語を書く ST・ST.W の書込み区間が `s < t < s+L` となるとき不正 |
| L11 | 形 F（欄の数）: bundle の欄の数は `register_file.read_ports` 以下。欄の数は、ADD/SUB/MUL が2、MAC が3、VADD/VSUB/VMUL が2、VMAC が3、ST が1、ST.W が1、LD/LD.W/NOP/HALT が0。組・ブロードキャスト・スカラーのどの欄も1と数え、同じレジスタを2つの欄で読んでも2と数える |

依存命令の配置 `s_consumer >= s_producer + L` は、組の両方のレジスタと、LD.W・ST.W の両方の語に当てはまる。

構造上の性質への追加: ST のレイテンシーが2以上の構成で起こり得る、STの書込みと後続のLDの読出しの同じ bank での重なりは、ST.W でも語ごとに当てはまる。ST と同じく、verifier と参照モデルは値の競合を L10 で排除し、RTL は ST.W のレイテンシー1だけに対応する。

L11 の形は3つを比べ、F を採った（[ADR-0022](DECISIONS.md)）。

| 形 | 規則 | 不正になる場合 |
|---|---|---|
| **F**（採用） | bundle の欄の数 ≤ `read_ports` | `read_ports` 3 で、MAC・VMAC と ST・ST.W が同じ bundle |
| S（後で検討する） | どの RF の bank のどのポートも、2つの欄に使わない | rs3 と Memory のソースが同じ RF の bank を読む |
| C（後で検討する） | RF の bank ごとの読むレジスタの数 ≤ `read_ports` | ある RF の bank の読出しが4 |

F で合法な命令列は S でも合法で、S で合法な命令列は C でも合法である。F から S、さらに `read_ports` 4 へと緩めても、合法な命令列は合法のままである（ADR-0018 と同じ理由）。`read_ports` 3 で形を分ける例: `vmac r4:r5, r2, r6:r7, r8:r9 | st [0], r1` は、F（欄4）でも S（RF の bank 1 のポート2を rs3 と ST が使う）でも不正で、C（bank ごとの読出し3）では合法である。`mac r10, r0, r2, r4 | st [0], r3` は F でだけ不正である。

RTL のポートの構造（S のデータ経路。#110 の第3段で作る）: RF の各 bank で、ポート0 は rs1、ポート1 は rs2、ポート2 は rs3 か Memory のソース（ST・ST.W）を読む。`read_ports` 4 では、Memory のソースはポート3を使う。スカラーの欄は自分の RF の bank のポートを、組の欄は両方の bank の同じポートを、ブロードキャストの rs1 は `bank(rs1)` のポート0だけを使う。今の割り当ての「ポート k」を「欄が読む各 RF の bank のポート k」に置き換えたものである。L11 の assertion は「(MAC か VMAC) かつ (ST か ST.W)」で、今の「MAC かつ ST」の両側を広げたものである。

組の部分更新は合法で、新しい規則は要らない。組は2つの 32bit レジスタの名前にすぎず、どの操作も 32bit のレジスタ全体を書く（スカラーの演算は1つ、ベクトル演算と LD.W は2つ）。そのため、ADR-0006 の「各レジスタの値は、最後に commit された書込みだけで決まる」は、レジスタごとに成り立つ。コンパイラは、集めた組（スカラーの LD 2つで両方を入れた組）でこれを使う。

### エンコード

bundle は64bit のままで、Compute word が bits 31:0、Memory word が bits 63:32 である。未使用の bit と予約 bit は0とする。

Compute word:

| Bits | ADD/SUB/MUL | MAC | VADD/VSUB | VMUL | VMAC |
|---|---|---|---|---|---|
| 31:28 | opcode 1 / 2 / 3 | 4 | 5 / 6 | 7 | 8 |
| 27:24 | rd | rd | rd の組（偶数） | rd の組（偶数） | rd の組（偶数） |
| 23:20 | rs1 | rs1 | rs1 の組（偶数） | B=0 なら rs1 の組（偶数）、B=1 なら任意のレジスタ | VMUL と同じ |
| 19:16 | rs2 | rs2 | rs2 の組（偶数） | rs2 の組（偶数） | rs2 の組（偶数） |
| 15:12 | 0 | rs3 | 0 | 0 | rs3 の組（偶数） |
| 11 | 0 | 0 | 0（予約） | **B** | **B** |
| 10:0 | 0 | 0 | 0 | 0 | 0 |

Memory word: LD.W・ST.W の opcode は既定で 3・4 である。bits 27:24 は組（偶数。LD.W の書込み先、ST.W のソース）、bits 23:15 は番地 `a`（奇数でもよい）、bits 14:0 は0。W-reg（`max_words_per_access` 1）では、Memory の opcode 3 と 4 は未知（L1）である。

- 組の欄には偶数の番号 p を書き、`(p, p+1)` を表す。mvp-2 のレジスタ数は偶数なので（[MACHINE.md](MACHINE.md)）、レジスタ数より小さい偶数 p では、p+1 もレジスタ数より小さい
- B=1 の rs1 は1つのレジスタで、偶数でも奇数でもよい
- B は VMUL・VMAC だけにある。融合（ADR-0019）の後、ブロードキャストはどれも乗算の rs1 にあるからである（`saxpy` の `a`、FIR の `h(t)`、`matmul` の `a(i,m)`）。VADD・VSUB の bit 11 は予約で、後で緩められる
- bit 0 はどの語でも予約のままなので、今の L1 のケースの `0x10000001`（`reserved_bits`）と `0x45123001`（`mac_reserved_bits`）は L1 の違反のままである
- opcode は machine description から取る。上の値は既定である

例（ビットの配置から手で計算した値。`isa/Mvp2Spec` で固定している）:

| bundle | 16進 | 注 |
|---|---|---|
| `mac r5, r1, r2, r3 \| nop` | `0x0000000045123000` | mvp-1 の例と同じ |
| `vmac r4:r5, r2, r6:r7, r8:r9 \| nop` | `0x0000000084268800` | B=1、r2 をブロードキャスト |
| `vmac r4:r5, r2:r3, r6:r7, r8:r9 \| nop` | `0x0000000084268000` | B=0 |
| `vmul r4:r5, r1, r6:r7 \| nop` | `0x0000000074160800` | 奇数のレジスタ（RF の bank 1）からブロードキャスト |
| `vmul r4:r5, r2:r3, r6:r7 \| nop` | `0x0000000074260000` | B=0 |
| `vadd r4:r5, r2:r3, r6:r7 \| nop` | `0x0000000054260000` | |
| `vsub r4:r5, r2:r3, r6:r7 \| nop` | `0x0000000064260000` | |
| `nop \| ld.w r10:r11, [17]` | `0x3a08800000000000` | 奇数の a: レーン0 は bank 1、レーン1 は bank 0 の次の行 |
| `nop \| ld.w r10:r11, [510]` | `0x3aff000000000000` | 512語の machine で合法 |
| `nop \| ld.w r10:r11, [511]` | `0x3aff800000000000` | エンコードできるが、L7（語 512） |
| `nop \| st.w [17], r12:r13` | `0x4c08800000000000` | |
| `vmac r4:r5, r2, r6:r7, r8:r9 \| st [0], r1` | `0x2100000084268800` | `read_ports` 3 で L11（欄4） |
| `vmac r4:r5, r2, r6:r7, r8:r9 \| ld.w r10:r11, [17]` | `0x3a08800084268800` | 合法（欄3） |

L1 に違反する命令語（アセンブリでは書けないので、テストケースでは `program_hex` を使う）:

| 16進 | 違反の理由 |
|---|---|
| `0x0000000085268800` | VMAC の rd の組が奇数（r5） |
| `0x0000000074360000` | B=0 の VMUL の rs1 の組が奇数（r3） |
| `0x0000000054260800` | VADD に B |
| `0x0000000045123800` | MAC に B（MAC の bits 11:0 は予約のまま） |
| `0x0000000074168800` | VMUL の bits 15:12 が0でない |
| `0x0000000084268801` | bit 0 が1 |
| `0x3b08800000000000` | LD.W の組が奇数（r11） |
| `0x3a08c00000000000` | LD.W の予約 bit 14 が1 |
| `0x3a08800000000000`（`tests/machines/mvp2_mw1.json`） | W-reg では Memory の opcode 3 は未知 |
| `0x0000000084268800`（`config/machine-mvp1.json`） | mvp-1 では Compute の opcode 8 は未知 |

アセンブリの構文は [ASM.md](ASM.md) にある。

### 例

ソースはすべて入力宣言済みとし、注目する規則以外の違反（末尾のHALTの欠落など）は無視する（M_in を明記した行を除く）。`read_ports` は3（4 と書いた行を除く）、L11 は形 F とする。

| 命令列（cycle: Compute \| Memory） | 判定 | 理由 |
|---|---|---|
| 0: `vmul r4:r5, r1, r2:r3` / 1: `add r6, r1, r2` | 不正 | L5（どちらも E_2） |
| 0: `vmul r4:r5, r1, r2:r3` / 1: `vmul r6:r7, r1, r2:r3` | 合法 | E_2 と E_3 |
| 0: `mul r1, r2, r3` / 1: `add r4, r5, r6` | 不正 | L5 は操作で数える（RF の bank が違っても） |
| 0: `vmul r4:r5, r1, r2:r3` / 1: `mul r8, r5, r1` | 不正 | L3（r5 が書込み中） |
| 0: `vmul r4:r5, r1, r2:r3` / 1: `nop` / 2: `add r5, r1, r2` / 3: `vadd r6:r7, r4:r5, r8:r9` | 合法 | 組の部分更新 |
| 0: `vmac r4:r5, r2, r6:r7, r8:r9 \| ld r5, [0]` | 不正 | L4（r5） |
| 0: `vmac r4:r5, r2, r6:r7, r8:r9 \| st [0], r1` | `read_ports` 3 で不正、4 で合法 | L11（欄4） |
| 0: `mac r10, r0, r2, r4 \| st [0], r3` | 不正 | L11（形 F。S なら合法） |
| 0: `vmul r4:r5, r1, r2:r3 \| st.w [0], r6:r7` | 合法 | 欄3 |
| 0: `nop \| ld.w r2:r3, [510]` / 1: `nop` / 2: `halt` | 合法 | 語 510 と 511 |
| 0: `nop \| ld.w r2:r3, [511]` | 不正 | L7（語 512） |
| 0: `nop \| ld.w r2:r3, [8]`（8 ∈ M_in、9 ∉ M_in） | 不正 | L8（語 9） |
| 0: `vmac r4:r5, r2, r6:r7, r8:r9` / 1: `halt` | 不正 | L6（2 > 1） |

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
