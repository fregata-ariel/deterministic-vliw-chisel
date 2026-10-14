# トレース形式（vliw-trace/1）

正しさを照合するための記録。性能の比較には [METRICS.md](METRICS.md) を使う。

参照モデル（`vliw.sim`）が出力し、Phase 1 のRTLテスト（ChiselSim）も同じ形式で出力して照合する、サイクルごとの記録。JSON Lines で、1行目がヘッダ、2行目以降が1サイクル1行の本体である。

## ヘッダ

```json
{"trace":"vliw-trace/1","isa":"mvp-0","machine":"vliw2-i32-sp2","machine_revision":1,"machine_hash":"sha256:..."}
```

`machine_hash` は [MACHINE.md](MACHINE.md) の hash。照合する2つのトレースは、ヘッダが一致しなければならない。

## 本体

```json
{"cycle":2,"pc":2,"issue":{"compute":"mul r3, r1, r2","memory":"nop"},"rf_commit":[{"reg":1,"value":7,"port":"memory"}],"mem_commit":[],"busy":true,"done":false}
```

| キー | 内容 |
|---|---|
| `cycle` | サイクル番号 s（start受理境界 E_0 の直後が0） |
| `pc` | サイクル s に実行するbundleの番号。HALT後のサイクルでは `null` |
| `issue` | サイクル s に発行したbundle（[ASM.md](ASM.md) の正規形で、スロットごとに書く）。HALT後は `null` |
| `rf_commit` | 境界 E_s でcommitされ、**サイクル s から見える**RFの更新。`port` は `compute` / `memory` |
| `mem_commit` | 境界 E_s でcommitされたScratchpadの更新 |
| `busy` / `done` | サイクル s の間の制御信号 |

- 行は cycle 0 から h+1 まで並ぶ（h はHALTの発行サイクル）。最後の行は `pc` と `issue` が `null` で、`done` が `true`、`busy` が `false` になる。したがって本体の行数は、実行サイクル数 h+1 より1行多い。
- `rf_commit` は、Computeポート、Memoryポートの順に並べる。`mem_commit` はアドレス順に並べる。
- 値は 0〜2^32−1 の整数。
- 照合は、各行をJSONとして読み、正規化形式（[MACHINE.md](MACHINE.md)）で比較する。キーの順序や空白の違いは問わない。

## golden trace

`tests/golden/<case>.trace.jsonl` には、[ISA.md](ISA.md) の表などから**手で書き起こした**本体（ヘッダ行なし）を置く。テストケースの `expect.trace` で参照する（[TESTCASE.md](TESTCASE.md)）。参照モデルの出力をそのまま golden にはしない（[ADR-0002](DECISIONS.md)）。

## 出力方法

```sh
sbt "sim/run tests/cases/isa_example.json --trace out.jsonl"
```
