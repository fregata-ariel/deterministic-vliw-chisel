# アセンブリ構文（mvp-0）

実装は `vliw.isa.Assembler` / `vliw.isa.Disassembler`。この構文は命令の表記だけを定め、合法性（[ISA.md](ISA.md) の規則 L2〜L8）は判定しない。それは verifier と参照モデルの役割である。

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
| Compute | `halt` | 終了。同じ行のMemoryスロットは `nop` でなければならない |
| Memory | `nop` | 操作なし |
| Memory | `ld rd, [addr]` | rd ← scratchpad[addr] |
| Memory | `st [addr], rs` | scratchpad[addr] ← rs |

- レジスタは `r0`〜`r15`。machine description のレジスタ数を超える番号はエラーになる。
- アドレスは word index で、10進または `0x` で始まる16進で書く。9bitの即値に収まらない値（512以上）はエラーになる。512未満でも、machine description のScratchpad容量を超えるアドレスは、構文上は受理する。これは規則 L7 の違反として verifier が拒否する。

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

## コマンド

```sh
sbt "isa/run asm prog.asm prog.hex"    # .hex / .bin は拡張子で選ぶ
sbt "isa/run disasm prog.hex"
sbt "isa/run case tests/cases/isa_example.json"
```
