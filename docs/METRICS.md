# メトリクス形式（vliw-metrics/1、vliw-metrics/2、vliw-metrics/3）

プログラムやマシン構成を比較するための、1回の実行の記録。[ROADMAP.md](ROADMAP.md)「評価記録」の項目を、機械可読な形で残す。正しさの照合は [TRACE.md](TRACE.md) のトレースが担い、この形式は性能の比較にだけ使う。

```sh
sbt "sim/run tests/cases/isa_example.json --metrics out.json"
```

実装は `vliw.sim.Metrics`。参照モデルで完了した（合法な）実行だけについて記録する。ISA `mvp-0` の記録は `vliw-metrics/1`、`mvp-1` の記録は `vliw-metrics/2`、`mvp-2` の記録は `vliw-metrics/3` である（「[版の扱い](#版の扱い)」）。`vliw-metrics/3` は #110 の第2段で参照モデルと一緒に実装する。それまでは、`Metrics` は mvp-2 の machine を例外で拒否する（黙って `/1` を書かないため）。

## 例（ISA例）

```json
{
  "metrics": "vliw-metrics/1",
  "isa": "mvp-0",
  "machine": {"id": "vliw2-i32-sp2", "revision": 2, "hash": "sha256:a4af6064..."},
  "program": {"name": "isa_example", "hash": "sha256:3eca8de7...", "bundles": 7},
  "generator": {"model": "vliw.sim.ReferenceModel", "scala": "2.13.18", "java": "21.0.12.1"},
  "cycles": 7,
  "issue": {
    "compute": {"operations": 2, "nop": 4, "nop_rate": 0.571429},
    "memory": {"operations": 2, "nop": 5, "nop_rate": 0.714286},
    "by_operation": {"ADD": 1, "SUB": 0, "MUL": 1, "LD": 1, "ST": 1, "HALT": 1}
  },
  "writeback": {
    "compute": {"commits": 2, "utilization": 0.285714},
    "memory": {"commits": 1, "utilization": 0.142857}
  },
  "scratchpad": {
    "reads": 1, "writes": 1, "accesses_per_cycle": 0.285714,
    "banks": [
      {"bank": 0, "reads": 1, "writes": 0, "accesses_per_cycle": 0.142857},
      {"bank": 1, "reads": 0, "writes": 1, "accesses_per_cycle": 0.142857}
    ]
  },
  "ii": null,
  "rtl": null
}
```

## キー

| キー | 内容 |
|---|---|
| `machine` | machine description の id、revision、hash（[MACHINE.md](MACHINE.md)） |
| `program.hash` | 命令列の `.hex` 表現（[ISA.md](ISA.md)）のSHA-256。同じ命令列なら同じ値になる |
| `program.bundles` | bundle数（命令数） |
| `generator` | 記録を作った実装と、その実行環境の版 |
| `cycles` | start受理から done までのサイクル数（h+1） |
| `issue.<slot>.operations` | そのスロットで発行した演算の数。Compute は ADD/SUB/MUL、Memory は LD/ST。HALT は含めない |
| `issue.<slot>.nop` / `nop_rate` | NOPのbundle数と、bundle数に対する割合。Compute の NOP は「bundle数 − 演算数 − HALT数」 |
| `issue.by_operation` | 演算ごとの発行数（HALT を含む） |
| `writeback.<port>.commits` / `utilization` | 書戻しポートごとのRFへのcommit数と、サイクル数に対する割合 |
| `scratchpad.reads` / `writes` | LD / ST の回数 |
| `scratchpad.accesses_per_cycle` | (reads + writes) / cycles。bank別の値は `banks` に入れる |
| `ii` | Modulo Scheduling したループの、定常状態の反復の開始間隔（ループの II）。記録を作る側が、トレースで確かめた値を渡す（`Metrics.of` の `ii`、`crosscheck/ModuloTrace`。[COMPILER.md](COMPILER.md)「Modulo Scheduling」）。展開済みのカーネルと `sim/run --metrics` では定義しないので `null`。演算器の II（`operations.<op>.ii`）とは別のもの |
| `rtl` | RTLの評価結果（Fmax、面積・資源量、ターゲット、ツール）のための予約欄。Phase 1 以降で定義する。それまでは `null` |

- 割合は小数点以下6桁に丸める。分母が0のときは `null` にする。
- 回数はすべて整数。
- 固定スケジュールなので、入力値を変えても記録は変わらない（`MetricsSpec` で確認している）。

## 版の扱い

キーを追加・削除するとき、または意味を変えるときは、`metrics` の版を上げる。古い記録と比べるときは、版の違いを明示する。

版は machine description の ISA の版で決まる（#69、ADR-0018）。

| ISA | 版 |
|---|---|
| `mvp-0` | `vliw-metrics/1`（上の形式。mvp-1 を入れても変えない） |
| `mvp-1` | `vliw-metrics/2`（下の「[vliw-metrics/2](#vliw-metrics2isa-mvp-1)」） |
| `mvp-2` | `vliw-metrics/3`（下の「[vliw-metrics/3](#vliw-metrics3isa-mvp-2)」。#110 の第2段で実装する） |

## vliw-metrics/2（ISA mvp-1）

`vliw-metrics/1` のキーをすべて持ち、意味も同じである。違いは、MAC を Compute の演算に数えることと、`issue` に読出しポートの欄を足すことだけである（#77）。

例（`config/machine-mvp1.json`、`read_ports` 3）:

```text
mac r4, r1, r2, r3 | ld r5, [0]
nop
mac r6, r4, r5, r4
nop                | st [1], r4
halt
```

```json
{
  "metrics": "vliw-metrics/2",
  "isa": "mvp-1",
  "machine": {"id": "vliw2-i32-sp2-mac", "revision": 1, "hash": "sha256:90f6a966..."},
  "cycles": 5,
  "issue": {
    "compute": {"operations": 2, "nop": 2, "nop_rate": 0.4},
    "memory": {"operations": 2, "nop": 3, "nop_rate": 0.6},
    "by_operation": {"ADD": 0, "SUB": 0, "MUL": 0, "MAC": 2, "LD": 1, "ST": 1, "HALT": 1},
    "register_reads": {
      "read_ports": 3,
      "reads": 7,
      "bundles_by_reads": {"0": 2, "1": 1, "2": 0, "3": 2}
    },
    "mac_with_st": 0
  }
}
```

ほかのキー（`program`、`generator`、`writeback`、`scratchpad`、`ii`、`rtl`）は `vliw-metrics/1` と同じなので、例では省いた。

| キー | 内容 |
|---|---|
| `metrics` | `vliw-metrics/2` |
| `issue.compute.operations` | ADD/SUB/MUL/MAC の発行数。HALT は含めない |
| `issue.by_operation` | MUL と LD の間に `MAC` を足す |
| `issue.register_reads.read_ports` | machine description の `register_file.read_ports`（規則 L11 の上限） |
| `issue.register_reads.reads` | 全 bundle のレジスタの読出しの数。L11 と同じく、オペランドの欄で数える（ADD/SUB/MUL は2、MAC は3、ST は1、LD・NOP・HALT は0。同じレジスタを2つの欄で読んでも2） |
| `issue.register_reads.bundles_by_reads` | 読出しの数ごとの bundle 数。キーは `"0"` から `read_ports` までの10進の文字列で、0件のキーも書く。合法な実行では読出しは `read_ports` 以下なので、値の和は `program.bundles` になる |
| `issue.mac_with_st` | MAC と ST を同じ bundle に置いた数。`read_ports` 3 の合法な命令列では常に0。`read_ports` 4 では、3本では置けない bundle の数になる（`bundles_by_reads` の `"4"` と同じ） |

## vliw-metrics/3（ISA mvp-2）

ISA mvp-2（W。[ISA.md](ISA.md)「mvp-2: W」）の記録の形式である（#78 の質問9、[ADR-0022](DECISIONS.md)）。形式はここで決め、実装は #110 の第2段で参照モデルと一緒に行う。W では、1つの操作が2つのレジスタや2語を動かすので、次の3種類の数を分けて記録する。

| 種類 | キー |
|---|---|
| **操作**の数 | `issue.<slot>.operations`、`issue.by_operation`、`writeback.<port>.commits`、`scratchpad.read_operations` / `write_operations` |
| **レジスタの語**の数 | `writeback.<port>.registers`、`issue.register_reads.registers`、`issue.register_reads.banks` |
| **転送の語**の数 | `scratchpad.reads` / `writes`、`scratchpad.banks[].reads` / `writes`、`scratchpad.accesses_per_cycle` |

`vliw-metrics/2` のキーのうち、下の表に無いもの（`program`、`generator`、`cycles`、`ii`、`rtl`、`nop`・`nop_rate`）は、意味が変わらない。キーの集合は形式の版だけで決まり、machine では変わらない。そのため、W-reg の記録にも LD.W・ST.W のキーがあり、値は0になる。

| キー | 内容 |
|---|---|
| `metrics` / `isa` | `vliw-metrics/3` / `mvp-2` |
| `issue.compute.operations` | ADD・SUB・MUL・MAC・VADD・VSUB・VMUL・VMAC の発行数。HALT は含めない |
| `issue.memory.operations` | LD・ST・LD.W・ST.W の発行数 |
| `issue.by_operation` | `ADD`、`SUB`、`MUL`、`MAC`、`VADD`、`VSUB`、`VMUL`、`VMAC`、`LD`、`ST`、`LD.W`、`ST.W`、`HALT` の13個のキーを、0件でも書く |
| `issue.register_reads.read_ports` | machine description の `register_file.read_ports`（規則 L11 の上限。RF の bank ごとの数） |
| `issue.register_reads.reads` | 読んだ**欄**の数。L11 の形 F と同じく、組とブロードキャストも1と数える |
| `issue.register_reads.registers`（新） | 読んだ 32bit のレジスタの数。組は2、ブロードキャストは1、スカラーは1 |
| `issue.register_reads.banks`（新） | RF の bank ごとの、読んだレジスタの数: `[{"bank":0,"registers":n0},{"bank":1,"registers":n1}]` |
| `issue.register_reads.bundles_by_reads` | `vliw-metrics/2` と同じ（bundle ごとの欄の数。キーは `"0"` から `read_ports` まで） |
| `issue.rs3_with_store`（`mac_with_st` を置き換える） | (MAC か VMAC) と (ST か ST.W) を同じ bundle に置いた数。`read_ports` 3 の合法な命令列では常に0。`read_ports` 4 では、3本では置けない bundle の数になる |
| `writeback.<port>.commits` | そのポートで commit した**操作**の数（L5 と同じく、ポートを使った回数） |
| `writeback.<port>.registers`（新） | そのポートで書いたレジスタの数。組は2、スカラーは1 |
| `writeback.<port>.utilization` | `commits / cycles` |
| `scratchpad.read_operations` / `write_operations`（新） | LD + LD.W / ST + ST.W の回数 |
| `scratchpad.reads` / `writes` | 読んだ / 書いた**語**の数（LD.W・ST.W は2と数える） |
| `scratchpad.accesses_per_cycle` | (reads + writes) / cycles。語の数で数える |
| `scratchpad.banks[]` | Scratchpad の bank ごとの、読んだ語と書いた語の数と `accesses_per_cycle` |

`vliw-metrics/1` と `/2` では、どの操作も1つのレジスタか1語しか動かさないので、操作の数と語の数は一致する。そのため、古い記録のキーの意味は、そのまま `/3` の語の数にも操作の数にも読める。

例（`config/machine-mvp2.json`。入力は M[0]=3、M[17]=5、M[18]=0xFFFFFFFF。手で計算した値で、第2段の `MetricsSpec` で固定する）:

```text
@0: nop | ld.w r2:r3, [17]
@1: nop | ld r1, [0]
@2: nop
@3: vmul r4:r5, r1, r2:r3
@4: nop
@5: nop | st.w [20], r4:r5
@6: halt
```

```json
{
  "metrics": "vliw-metrics/3",
  "isa": "mvp-2",
  "cycles": 7,
  "issue": {
    "compute": {"operations": 1, "nop": 5, "nop_rate": 0.714286},
    "memory": {"operations": 3, "nop": 4, "nop_rate": 0.571429},
    "by_operation": {"ADD": 0, "SUB": 0, "MUL": 0, "MAC": 0, "VADD": 0, "VSUB": 0, "VMUL": 1, "VMAC": 0, "LD": 1, "ST": 0, "LD.W": 1, "ST.W": 1, "HALT": 1},
    "register_reads": {
      "read_ports": 3,
      "reads": 3,
      "registers": 5,
      "banks": [{"bank": 0, "registers": 2}, {"bank": 1, "registers": 3}],
      "bundles_by_reads": {"0": 5, "1": 1, "2": 1, "3": 0}
    },
    "rs3_with_store": 0
  },
  "writeback": {
    "compute": {"commits": 1, "registers": 2, "utilization": 0.142857},
    "memory": {"commits": 2, "registers": 3, "utilization": 0.285714}
  },
  "scratchpad": {
    "read_operations": 2, "write_operations": 1,
    "reads": 3, "writes": 2, "accesses_per_cycle": 0.714286,
    "banks": [
      {"bank": 0, "reads": 2, "writes": 1, "accesses_per_cycle": 0.428571},
      {"bank": 1, "reads": 1, "writes": 1, "accesses_per_cycle": 0.285714}
    ]
  }
}
```

ほかのキー（`machine`、`program`、`generator`、`ii`、`rtl`）は例では省いた。読んだレジスタは、bank 0 が r2 と r4、bank 1 が r1・r3・r5 である。読んだ語は 17・18・0、書いた語は 20・21 である。最終状態は r4 = 15、r5 = 4294967293（3 × 0xFFFFFFFF の下位32bit）、M[20] = 15、M[21] = 4294967293 になる。
