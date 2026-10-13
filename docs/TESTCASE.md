# テストケース形式（vliw-testcase/1）

verifier、参照モデル、Phase 1 のRTLテストが共有する、手作りの期待値の形式。ファイルは `tests/cases/<name>.json` に置き、ローダは `vliw.isa.TestCase`。

期待値は人が書く。実装の出力をそのまま期待値にしない（[ADR-0002](DECISIONS.md)）。

## 例

```json
{
  "format": "vliw-testcase/1",
  "name": "isa_example",
  "description": "docs/ISA.md「最初の命令例」",
  "program": [
    "@0: nop            | ld r1, [0]",
    "@1: nop",
    "@2: mul r3, r1, r2",
    "@3: nop",
    "@4: add r4, r3, r1",
    "@5: nop            | st [1], r4",
    "@6: halt"
  ],
  "inputs": {"registers": {"r2": 3}, "memory": {"0": 7}},
  "expect": {
    "verdict": "legal",
    "cycles": 7,
    "registers": {"r3": 21, "r4": 28},
    "memory": {"1": 28}
  }
}
```

## キー

| キー | 必須 | 内容 |
|---|---|---|
| `format` | ○ | `"vliw-testcase/1"` |
| `name` | ○ | `[a-z0-9][a-z0-9_]*`。ファイル名と同じにする |
| `description` | ○ | 何を確かめるケースか |
| `machine` | | machine description のパス（リポジトリのルートからの相対パス）。省略時は `config/machine.json`。容量を縮小した定義で規則 L7 を確かめる場合などに使う |
| `program` | どちらか一方 | [ASM.md](ASM.md) の構文で、1要素が1行 |
| `program_hex` | どちらか一方 | 1要素が1bundleの16桁16進。アセンブラで書けない命令語（規則 L1 の違反）に使う |
| `inputs.registers` | ○ | 開始前にホストが設定するレジスタ（入力宣言 R_in）。キーは `r0`〜`r15` |
| `inputs.memory` | ○ | 開始前にホストが設定するScratchpad（入力宣言 M_in）。キーは10進のword address |
| `expect.verdict` | ○ | `"legal"`、または `{"illegal": ["L3", ...]}` |
| `expect.cycles` | | 合法な場合だけ書ける。start受理から done までのサイクル数（h+1） |
| `expect.registers` / `expect.memory` | | 合法な場合だけ書ける。最終状態の部分指定で、書いた場所だけを照合する |
| `expect.trace` | | 合法な場合だけ書ける。手作りのgolden trace本体（[TRACE.md](TRACE.md)、ヘッダ行なし）のパス。サイクルごとに照合する |

値は 0〜4294967295 の整数、または `"0xFFFFFFFF"` のような16進文字列で書く。

## 判定の照合方法

- `"legal"`: verifier は違反を報告しない。参照モデルはエラー無しで完了し、`cycles`、最終状態、`trace` が期待値と一致する。
- `{"illegal": [...]}`: verifier が報告する規則の集合が、列挙した集合と**一致**する。参照モデルが実行中に最初に検出する違反は、この集合に含まれる。
