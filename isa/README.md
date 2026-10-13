# ISA core

全プロジェクト（`compiler`、`sim`、Phase 1の`hw`）が共有する基盤。sbtプロジェクト `isa`、パッケージ `vliw.isa`。

- `config/machine.json` のローダと検証、正規化形式とhash（`MachineDescription`、`Json`。形式は [docs/MACHINE.md](../docs/MACHINE.md)）。`sbt "isa/run machine"` で識別子とhashを表示する
- 命令・bundleの内部表現（`Instruction.scala`）、64bitエンコーディング（`BundleCodec`。予約bitの検査と規則 L1 の拒否を含む）、`.bin` / `.hex` 形式の読み書き（`ProgramFile`）
- アセンブラ・逆アセンブラ（`Assembler`、`Disassembler`。構文は [docs/ASM.md](../docs/ASM.md)）
- テストケースの形式とローダ（`TestCase`、規則IDの `Rule`。形式は [docs/TESTCASE.md](../docs/TESTCASE.md)）
- CLI（`Cli`）: `sbt "isa/run machine|asm|disasm|case ..."`

ここには、実行意味論の解釈やハザード判定を置かない。それらは `sim`（実行による判定）と `compiler` のverifier（静的判定）が、[docs/ISA.md](../docs/ISA.md) からそれぞれ独立に実装する（[ADR-0002](../docs/DECISIONS.md)）。
