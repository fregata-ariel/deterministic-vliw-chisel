# ISA core

全プロジェクト（`compiler`、`sim`、Phase 1の`hw`）が共有する基盤。sbtプロジェクト `isa`、パッケージ `vliw.isa`。

- `config/machine.json` のローダと検証、正規化形式とhash（`MachineDescription`、`Json`。形式は [docs/MACHINE.md](../docs/MACHINE.md)）。`sbt "isa/run"` で識別子とhashを表示する
- 命令・bundleの内部表現（`Instruction.scala`）、64bitエンコーディング（`BundleCodec`。予約bitの検査と規則 L1 の拒否を含む）、`.bin` / `.hex` 形式の読み書き（`ProgramFile`）

ここには、実行意味論の解釈やハザード判定を置かない。それらは `sim`（実行による判定）と `compiler` のverifier（静的判定）が、[docs/ISA.md](../docs/ISA.md) からそれぞれ独立に実装する（[ADR-0002](../docs/DECISIONS.md)）。
