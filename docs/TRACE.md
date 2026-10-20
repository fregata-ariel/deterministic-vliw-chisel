# トレース形式（vliw-trace/1）

正しさを照合するための記録。性能の比較には [METRICS.md](METRICS.md) を使う。

参照モデル（`vliw.sim`）が出力し、Phase 1 のRTLテスト（ChiselSim）も同じ形式で出力して照合する、サイクルごとの記録。JSON Lines で、1行目がヘッダ、2行目以降が1サイクル1行の本体である。

## ヘッダ

```json
{"trace":"vliw-trace/1","isa":"mvp-0","machine":"vliw2-i32-sp2","machine_revision":1,"machine_hash":"sha256:..."}
```

`machine_hash` は [MACHINE.md](MACHINE.md) の hash。照合する2つのトレースは、ヘッダが一致しなければならない。

`isa` は machine description の `isa_version`（`mvp-0`、`mvp-1`、`mvp-2`）。形式はどの版でも同じである。mvp-1 の MAC は、`issue.compute` に `mac rd, rs1, rs2, rs3` と書き、結果は `port` が `compute` の `rf_commit` に入る（[ISA.md](ISA.md)「mvp-1: MAC」）。

mvp-2 の W（[ISA.md](ISA.md)「mvp-2: W」）も `vliw-trace/1` のままで、キーを足さない（#78 の質問9、[ADR-0022](DECISIONS.md)）。`issue` は [ASM.md](ASM.md) の正規形で書く（例: `vmul r4:r5, r1, r6:r7`、`st.w [20], r4:r5`）。組の commit は、同じ `port` の `rf_commit` 2つ（レジスタ p と p+1）になり、ST.W は `mem_commit` 2つになる。mvp-2 のトレースは、#110 の第2段で参照モデルが出力する。

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
- `rf_commit` は、Computeポート、Memoryポートの順に並べ、同じポートの中はレジスタ番号の順に並べる。`mem_commit` はアドレス順に並べる。
- 1つの操作が2つのレジスタを commit したのか、2つの操作がそれぞれ commit したのかは、`port` で分かる。規則 L5 により、各境界で各ポートに commit する操作は高々1つなので、合法な実行では、1行の中で同じ `port` を持つ `rf_commit` は1つの操作のもの（組の commit）である。同じ境界に commit する2つの操作は、必ず違う `port` を持つ。参照モデルは L5 の違反で止まるので、1つのポートに2つの操作の commit があるトレースは出ない（mvp-2。mvp-0・mvp-1 では、1つのポートの `rf_commit` は高々1つである）。`mem_commit` も、1行の中のものは高々1つの操作のものである（ST なら1つ、ST.W なら語 a と a+1 の2つ）。ST.W の latency は ST と同じで（ローダが確かめる）、Memory の操作は1サイクルに1つなので、2つの書込みが同じ境界に効くことはないからである（[ISA.md](ISA.md)「mvp-2: W」の構造上の性質への追加）。
- 値は 0〜2^32−1 の整数。
- 照合は、各行をJSONとして読み、正規化形式（[MACHINE.md](MACHINE.md)）で比較する。キーの順序や空白の違いは問わない。

## golden trace

`tests/golden/<case>.trace.jsonl` には、[ISA.md](ISA.md) の表などから**手で書き起こした**本体（ヘッダ行なし）を置く。テストケースの `expect.trace` で参照する（[TESTCASE.md](TESTCASE.md)）。参照モデルの出力をそのまま golden にはしない（[ADR-0002](DECISIONS.md)）。

## 出力方法

RTL のトレースは、テスト用の `vliw.hw.CoreDriver` がコアの観測ポートから同じ形式で作る。`issue` 欄は、命令語を復号し直すのではなく、RTL の `Decoder` が出した演算の種類・レジスタ・アドレスから組み立てる（#56）。そのため、Decoder の誤りは、値に影響しなくても（例えば可換な演算のソースの入れ替え）トレースの照合で見つかる。L1 に違反する bundle は `?` と表示する。`hw/CaseSpec` が golden trace と、`crosscheck/RtlDifferentialSpec` が参照モデルのトレースと、1行ずつ照合する。


```sh
sbt "sim/run tests/cases/isa_example.json --trace out.jsonl"
```
