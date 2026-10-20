# アセンブリ構文（mvp-0、mvp-1、mvp-2）

実装は `vliw.isa.Assembler` / `vliw.isa.Disassembler`。この構文は命令の表記だけを定め、合法性（[ISA.md](ISA.md) の規則 L2〜L11）は判定しない。それは verifier と参照モデルの役割である。

## 行

- 1行が1bundle、つまり1サイクルに対応する。空行とコメントだけの行は数えない。
- `;` から行末まではコメント。
- `|` の左がComputeスロット、右がMemoryスロット。どちらも省略でき、省略した側は `nop` になる。`|` が無い行は、Computeスロットだけを書いたものとして扱う。
- 行頭に `@N:` を付けられる。N はその行のbundle番号（0始まり）と一致しなければならない。手書き時のずれを検出するために使う。
- ニーモニックとレジスタ名は大文字・小文字を区別しない。

```asm
; docs/ISA.md の命令例
@0: nop            | ld r1, [0]     ; r1 = 7 at E_2
@1: nop            | nop
@2: mul r3, r1, r2                   ; r3 = 21 at E_4
@3: nop
@4: add r4, r3, r1
@5:                | st [1], r4
@6: halt
```

## 命令

| スロット | 構文 | 意味 |
|---|---|---|
| Compute | `nop` | 操作なし |
| Compute | `add rd, rs1, rs2` / `sub ...` / `mul ...` | [ISA.md](ISA.md) の演算 |
| Compute | `mac rd, rs1, rs2, rs3` | mvp-1 と mvp-2（[ISA.md](ISA.md)「mvp-1: MAC」。#77） |
| Compute | `vadd rd:rd', rs1:rs1', rs2:rs2'` / `vsub ...` | mvp-2 だけ（[ISA.md](ISA.md)「mvp-2: W」。#110）。オペランドはすべて組。rs1 を1つのレジスタにするとエラー（vadd・vsub にブロードキャストは無い） |
| Compute | `vmul rd:rd', rs1, rs2:rs2'` | mvp-2 だけ。rs1 が1つのレジスタなら B=1（ブロードキャスト）、組なら B=0 |
| Compute | `vmac rd:rd', rs1, rs2:rs2', rs3:rs3'` | mvp-2 だけ。rs1 は vmul と同じ |
| Compute | `halt` | 終了。同じ行のMemoryスロットは `nop` でなければならない |
| Memory | `nop` | 操作なし |
| Memory | `ld rd, [addr]` | rd ← scratchpad[addr] |
| Memory | `st [addr], rs` | scratchpad[addr] ← rs |
| Memory | `ld.w rd:rd', [addr]` | mvp-2 の W-full だけ。rd ← scratchpad[addr]、rd' ← scratchpad[addr+1]。addr は奇数でもよい |
| Memory | `st.w [addr], rs:rs'` | mvp-2 の W-full だけ。scratchpad[addr] ← rs、scratchpad[addr+1] ← rs' |

- レジスタは `r0`〜`r15`。machine description のレジスタ数を超える番号はエラーになる。
- アドレスは word index で、10進または `0x` で始まる16進で書く。9bitの即値に収まらない値（512以上）はエラーになる。512未満でも、machine description のScratchpad容量を超えるアドレスは、構文上は受理する。これは規則 L7 の違反として verifier が拒否する。512語の machine の `ld.w r2:r3, [511]` も同じで、構文上は受理し、語 512 を読むので L7 の違反になる。
- 組（mvp-2）は `rP:rQ` と書き、Q は P+1、P は偶数でなければならない。`r4:r6` は「a pair names two consecutive registers」、`r5:r6` は「starts at an odd register」のエラーになる。奇数の組は L1 の違反で、エンコーダもその命令語を作らないからである（L1 を確かめるケースは `program_hex` で書く）。トークンの中に空白は書けない（`r4 : r5` はエラー）。スカラーの欄に組を書くと「expected a register」のエラーになる。
- `ld.w`・`st.w` のように、ニーモニックには `.w` などの接尾辞が付く。大文字でもよい（`LD.W`）。
- machine に無い演算はエラーになる（例: `vadd is not an operation of vliw2-i32-sp2-mac (ISA mvp-1)`、`ld.w is not an operation of vliw2-i32-sp2-mac-w2-mw1 (ISA mvp-2)`）。`mac` は mvp-0 で同じエラーになる。

## エラー

エラーは `line N: 理由` の形式で、ソースの順にすべて報告する。

## 正規形（逆アセンブル）

`Disassembler.program` は次の形式で出力する。この出力をアセンブルすると、元と同じbundle列に戻る。

```
@0: nop             | ld r1, [0]
@4: add r4, r3, r1  | nop
```

- 各行に `@N:` を付け、Computeスロットを15文字に揃え、両スロットを明示する。
- アドレスは10進で書く。
- 組は常に `rP:rP+1` と書く。mvp-2 の正規形は `vadd r4:r5, r2:r3, r6:r7`、`vmul r4:r5, r1, r6:r7`（B=1）、`vmul r4:r5, r2:r3, r6:r7`（B=0）、`vmac r4:r5, r2, r6:r7, r8:r9`、`ld.w r10:r11, [17]`、`st.w [17], r12:r13` である。ベクトル演算は15文字より長いので、`|` が右にずれる。mvp-0・mvp-1 の命令列の出力は、#110 の前と1文字も変わらない。

## コマンド

```sh
sbt "isa/run asm prog.asm prog.hex"    # .hex / .bin は拡張子で選ぶ
sbt "isa/run disasm prog.hex"
sbt "isa/run asm mac.asm mac.hex --machine config/machine-mvp1.json"  # mvp-1（mac を書く）
sbt "isa/run disasm mac.hex --machine config/machine-mvp1.json"
sbt "isa/run asm w.asm w.hex --machine config/machine-mvp2.json"      # mvp-2（vadd・ld.w などを書く）
sbt "isa/run case tests/cases/isa_example.json"
```

- `asm` と `disasm` は、既定で `config/machine.json`（mvp-0）を使う。mvp-0 では `mac` はエラーになり、MAC の命令語は「unknown opcode 4」で読めない。mvp-1 では W の演算がエラーになり、VMAC の命令語は「unknown opcode 8」で読めない。
- `--machine <machine.json>` で別の machine description を使う。`compiler/run compile` と同じオプションで、オペランドの前にも後にも書ける。
- `case` は、テストケースの `machine`（[TESTCASE.md](TESTCASE.md)）を使う。
