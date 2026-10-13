# MVP ISAとサイクル契約

状態：Phase 0で検証する仕様案。ISAバージョン`mvp-0`。

## サイクルの定義

サイクルsはクロック境界E_sからE_(s+1)まで。境界で完了したRF更新は、その直後のサイクルの組合せRF読出しから見える。サイクルsの命令は、そのサイクルで見えるソース値を読んで実行する。

Latency Lの結果は境界E_(s+L)でcommitされ、サイクルs+Lで使用できる。したがって依存命令は`s_consumer >= s_producer + L`とする。同一バンドル内で発行した演算結果を別スロットへ直接転送する機能はない。

ADDはサイクルsで計算しE_(s+1)でRFへ格納。MULは2段構成でE_(s+2)に格納。LDはサイクルsの要求をSyncReadMemがE_(s+1)で受け付け、サイクルs+1の出力をE_(s+2)でRFへ格納する。**SRAMの1-cycle読出しと、ISAのLD 2-cycleは異なる契約**である。

## 演算

全レジスタr0〜r15は通常の32bitレジスタ。ゼロレジスタは設けない。開始前にホストが必要な入力レジスタを設定する。ADD/SUB/MULは結果を32bitへ切り詰め、符号付き・符号なしの下位32bitが一致するビットベクトル算術として扱う。

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

アドレスはバイト単位でなく32bit word index。範囲0〜511、`bank=addr%2`、`row=addr/2`。初期は即値アドレスのみで、間接アドレスと分岐は後続拡張とする。

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

## ハザード規則

- Computeは1発行/cycle、Memoryも1発行/cycle。
- 書き戻しはCompute専用1portとMemory専用1port。両方が同じRF registerへ同じ境界で書くことは禁止。
- MUL(s)とADD(s+1)はどちらもE_(s+2)にCompute書込を要求するため禁止。NOPを挟む、順序変更する等の静的解決が必要。
- 古い書込が後で新しい値を上書きしないようWAWとpending destinationを検査する。WARはソースが実際に読まれるサイクルに基づいて検査する。
- Memoryは全体で1操作/cycle、各bankは1RW port。LDの出力は1回だけ確実に捕捉し、非enable時のメモリ出力の保持に依存しない。
- 同一bankのread/write要求を同じクロック境界で受け付けることは禁止。MVPではMemoryが1スロットなので同時要求は構造上発生しない。将来DMAや複数Memoryスロットを追加する際は、この制約も検証器とRTLで維持する。
- アドレスが既知でも、aliasするload/storeの依存関係をDAGに残す。bank競合だけの検査では意味上の順序は保証できない。
- HALT発行サイクルhでは全操作が既に完了していること、つまり各操作の`s+L <= h`を要求する。隠れたdrain待ちで実行時間を延ばさない。

## 開始・終了

idle中だけstartを受理する。受理境界をE_0とし、最初のbundleをサイクル0で実行する。busy中のstartは受理しない。HALTをサイクルhに発行するとE_(h+1)でdoneを1cycle通知し、busyを解除する。実行サイクル数は`h+1`。

例：r1/r2を初期化してADD → HALTを実行する場合、ADDはcycle0、結果はE_1、HALTはcycle1、doneはE_2。期待サイクル数は2。

## 最初の命令例

アセンブリ構文も初期案。r2=3、scratchpad[0]=7を開始前に配置する。

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
