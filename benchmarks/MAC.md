# MAC のある構成とない構成の比較（ISA mvp-1）

このファイルは `sbt "crosscheck/Test/runMain vliw.crosscheck.BenchmarkRecords"` が生成する。手で編集しない（`crosscheck/BenchmarkSpec` が一致を確かめる）。

**cycle ベースの比較である。** Fmax・面積・電力は未測定で（#57）、時間や電力の主張はしない。cycle の比を実時間の速度比と読まない。

## 条件

| 構成 | machine description | machine | ISA | `read_ports` | MAC の latency / II（`operations.MAC`） | 実行 |
|---|---|---|---|---|---|---|
| mvp-0 | `config/machine.json` | `vliw2-i32-sp2` revision 2（frozen）、`sha256:a4af6064e049415dc9822614264a01f06b6e1be39c86bc41c5fcdda3e7d89e21` | `mvp-0` | 3 | -（MAC なし） | 参照モデルと RTL |
| mvp-1 | `config/machine-mvp1.json` | `vliw2-i32-sp2-mac` revision 1（frozen）、`sha256:90f6a9667a8b9fa7be448117438c04ff40a1af6495f13c0b8d8d1ccd95f89162` | `mvp-1` | 3 | 2 / 1 | 参照モデルと RTL |
| rp4 | `tests/machines/mvp1_rp4.json` | `vliw2-i32-sp2-mac-rp4` revision 1（proposed）、`sha256:600710348640442ebaba9d0df2dbef62949eab15f948d42ca899924051f4226b` | `mvp-1` | 4 | 2 / 1 | 参照モデルと verifier（RTL の範囲外: read_ports is 4; the RTL has 3 register read ports, port 2 shared by MAC's rs3 and the ST source (4 read ports come in #57)） |
| mac3 | `tests/machines/mvp1_mac3.json` | `vliw2-i32-sp2-mac3` revision 1（proposed）、`sha256:2abe65317bed4df87bb33c2b0ad8e600a16c873aeac7962fa8b0e65164e84c89` | `mvp-1` | 3 | 3 / 1 | 参照モデルと RTL |

| 項目 | 値 |
|---|---|
| 差 | mvp-1 は mvp-0 に MAC を足した版である（ISA mvp-1、[ADR-0018](../docs/DECISIONS.md#adr-0018-mac-は-isa-mvp-1-で入れ読出しポートは3本のまま規則-l11-で数える)）。rp4 は mvp-1 と `read_ports` だけ、mac3 は MAC の latency だけが違う（`BenchmarkSpec` が確かめる）。どの構成もレジスタは 16 本 |
| カーネル・配置・入力・方式 | [RESULTS.md](RESULTS.md) と同じ。4つの構成で、配置と入力は同じである（`BenchmarkSpec` が確かめる） |
| コンパイラの試行 | RESULTS.md と同じ。優先度 ×（上限なし、上限 16 で load reserve 0〜8）。rematerialization も同じ（[ADR-0016](../docs/DECISIONS.md#adr-0016-rematerialization-は行き詰まる試行でだけ書込みのない語の-ld-をレジスタの詰まりで複製する)） |
| MAC への融合 | MAC のある構成では、読む側が1つだけの MUL と、それを読む ADD を、常に1つの MAC にする。`ADD(m, m)`、SUB、読む側が複数ある MUL は融合しない（[ADR-0019](../docs/DECISIONS.md#adr-0019-読む側が1つの-mul-とそれを読む-add-を-mac-に融合しループでは-mii-が上がるときは融合しない)）。融合は戦略の次元にしない。DAG はカーネルと machine だけで決まり、全ての試行が同じ DAG を使う |
| 読出しポート | `read_ports` 3 の list scheduling は素朴な方法に固定する。各サイクルで Compute を先に埋め、MAC を置いた bundle では ST を待たせる（ADR-0019）。ST のために MAC を遅らせる工夫はしない。rp4 は制約のない比較のための構成で、読出し4本の RTL は #57 で足す |
| ループの融合 | Modulo Scheduling は、融合した後の MII が融合しない MII より大きくならないループだけを融合する。MII は3本目の読出しポートを除いて比べるので、mvp-1 と rp4 は同じグラフをスケジュールする（ADR-0019） |
| 実行 | 参照モデル。RTL の範囲内の構成（mvp-1 と mac3）の記録は、RTL でも同じ出力と cycles になり、RTL のトレースが参照モデルのトレースと一致することを `BenchmarkSpec` が確かめる |
| 記録の形式 | mvp-0 は `vliw-metrics/1`、MAC のある構成は `vliw-metrics/2`（MAC の発行数と、読出しポートの欄。[docs/METRICS.md](../docs/METRICS.md)） |

MAC の latency と II は、machine description の `operations.MAC` の契約の値である。この II は MAC の演算器の発行間隔（規則 L9）で、下の Modulo Scheduling の II（ループの反復の開始間隔、`metrics` の `ii`）とは別のものである。

## list scheduling: 方式ごとに cycles 最小の系列

各構成で、方式ごとに、全ての試行のうち cycles 最小のもの（同じならば LD の少ない方、それも同じならば試す順で先の試行）。選ばれた試行は構成ごとに違うことがあり、そのときの比は、試行の違いの効果も含む（[README.md](README.md)「比較の書き方」）。同じ試行どうしの比は、下の「戦略を固定した系列」にある。

### cycles と演算数

| カーネル | 方式 | 構成 | cycles | mvp-0 に対する cycles の比 | LD（カーネルの DAG） | MUL | MAC | ADD | 融合した組 | 試行 | 命令列 hash |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `saxpy8` | vliw | mvp-0 | 26 | 1.00 | 17（17） | 8 | 0 | 8 | 0 | critical-path, no register limit | `sha256:56f158076bf6…` |
| `saxpy8` | vliw | mvp-1 | 27 | 1.04 | 17（17） | 0 | 8 | 0 | 8 | critical-path, no register limit | `sha256:97e9480edd56…` |
| `saxpy8` | vliw | rp4 | 26 | 1.00 | 17（17） | 0 | 8 | 0 | 8 | critical-path, no register limit | `sha256:64142c16b234…` |
| `saxpy8` | vliw | mac3 | 27 | 1.04 | 17（17） | 0 | 8 | 0 | 8 | critical-path, no register limit | `sha256:97e9480edd56…` |
| `saxpy8` | single | mvp-0 | 42 | 1.00 | 17（17） | 8 | 0 | 8 | 0 | critical-path, no register limit, single issue | `sha256:b9080366df71…` |
| `saxpy8` | single | mvp-1 | 34 | 0.81 | 17（17） | 0 | 8 | 0 | 8 | critical-path, 16 registers, load reserve 0, single issue | `sha256:3df548fcc9a3…` |
| `saxpy8` | single | rp4 | 34 | 0.81 | 17（17） | 0 | 8 | 0 | 8 | critical-path, 16 registers, load reserve 0, single issue | `sha256:3df548fcc9a3…` |
| `saxpy8` | single | mac3 | 34 | 0.81 | 17（17） | 0 | 8 | 0 | 8 | critical-path, 16 registers, load reserve 0, single issue | `sha256:3df548fcc9a3…` |
| `fir4x8` | vliw | mvp-0 | 65 | 1.00 | 15（15） | 32 | 0 | 24 | 0 | program-order, 16 registers, load reserve 4 | `sha256:2f97d5523854…` |
| `fir4x8` | vliw | mvp-1 | 40 | 0.62 | 15（15） | 8 | 24 | 0 | 24 | program-order, no register limit | `sha256:31bc090907b5…` |
| `fir4x8` | vliw | rp4 | 38 | 0.58 | 27（15） | 8 | 24 | 0 | 24 | critical-path, 16 registers, load reserve 1 | `sha256:61042cd0eeab…` |
| `fir4x8` | vliw | mac3 | 44 | 0.68 | 27（15） | 8 | 24 | 0 | 24 | critical-path, 16 registers, load reserve 1 | `sha256:c633eac9e7d0…` |
| `fir4x8` | single | mvp-0 | 81 | 1.00 | 15（15） | 32 | 0 | 24 | 0 | program-order, no register limit, single issue | `sha256:26f6f691c40c…` |
| `fir4x8` | single | mvp-1 | 58 | 0.72 | 15（15） | 8 | 24 | 0 | 24 | program-order, no register limit, single issue | `sha256:bedcbf4dcc05…` |
| `fir4x8` | single | rp4 | 58 | 0.72 | 15（15） | 8 | 24 | 0 | 24 | program-order, no register limit, single issue | `sha256:bedcbf4dcc05…` |
| `fir4x8` | single | mac3 | 58 | 0.72 | 17（15） | 8 | 24 | 0 | 24 | critical-path, 16 registers, load reserve 0, single issue | `sha256:f2ceb427ecba…` |
| `matmul3` | vliw | mvp-0 | 56 | 1.00 | 18（18） | 27 | 0 | 18 | 0 | critical-path, 16 registers, load reserve 1 | `sha256:0560f1e7441a…` |
| `matmul3` | vliw | mvp-1 | 39 | 0.70 | 18（18） | 9 | 18 | 0 | 18 | program-order, 16 registers, load reserve 0 | `sha256:4aac47809422…` |
| `matmul3` | vliw | rp4 | 33 | 0.59 | 18（18） | 9 | 18 | 0 | 18 | critical-path, 16 registers, load reserve 0 | `sha256:84bcd150eb5f…` |
| `matmul3` | vliw | mac3 | 40 | 0.71 | 18（18） | 9 | 18 | 0 | 18 | critical-path, 16 registers, load reserve 0 | `sha256:be9724b59efd…` |
| `matmul3` | single | mvp-0 | 75 | 1.00 | 18（18） | 27 | 0 | 18 | 0 | program-order, 16 registers, load reserve 0, single issue | `sha256:69ecf8733437…` |
| `matmul3` | single | mvp-1 | 55 | 0.73 | 18（18） | 9 | 18 | 0 | 18 | critical-path, 16 registers, load reserve 0, single issue | `sha256:8ad2b63557ac…` |
| `matmul3` | single | rp4 | 55 | 0.73 | 18（18） | 9 | 18 | 0 | 18 | critical-path, 16 registers, load reserve 0, single issue | `sha256:8ad2b63557ac…` |
| `matmul3` | single | mac3 | 55 | 0.73 | 18（18） | 9 | 18 | 0 | 18 | critical-path, no register limit, single issue | `sha256:24ffaddd2924…` |

演算数は発行した数である（`metrics` の `issue.by_operation`）。融合した組は、カーネルの DAG の MAC の数（`Dag.macs`）で、DSL に MAC は無いので、MUL + ADD を MAC にした数である。MAC のある構成の MUL と ADD は、mvp-0 より組の数だけ少なく、乗算の数（MUL + MAC）は変わらない（`BenchmarkSpec` が確かめる）。4つの構成で同じ試行が選ばれたのは `saxpy8`（vliw）で、ほかは構成によって違う。

### スロットとポート

| カーネル | 方式 | 構成 | Compute NOP率 | 書戻し Compute 利用率 | `heldByWritePort` | `heldByReadPorts` | MAC と ST の bundle | 読出し | 読出しの数ごとの bundle（0 / 1 / 2 / 3 / 4） |
|---|---|---|---|---|---|---|---|---|---|
| `saxpy8` | vliw | mvp-0 | 0.346 | 0.615 | 1 | 0 | - | 40 | 5 / 5 / 13 / 3 / - |
| `saxpy8` | vliw | mvp-1 | 0.667 | 0.296 | 0 | 7 | 0 | 32 | 11 / 8 / 0 / 8 / - |
| `saxpy8` | vliw | rp4 | 0.654 | 0.308 | 0 | 0 | 1 | 32 | 11 / 7 / 0 / 7 / 1 |
| `saxpy8` | vliw | mac3 | 0.667 | 0.296 | 0 | 6 | 0 | 32 | 11 / 8 / 0 / 8 / - |
| `saxpy8` | single | mvp-0 | 0.595 | 0.381 | 7 | 0 | - | 40 | 18 / 8 / 16 / 0 / - |
| `saxpy8` | single | mvp-1 | 0.735 | 0.235 | 0 | 0 | 0 | 32 | 18 / 8 / 0 / 8 / - |
| `saxpy8` | single | rp4 | 0.735 | 0.235 | 0 | 0 | 0 | 32 | 18 / 8 / 0 / 8 / 0 |
| `saxpy8` | single | mac3 | 0.735 | 0.235 | 0 | 0 | 0 | 32 | 18 / 8 / 0 / 8 / - |
| `fir4x8` | vliw | mvp-0 | 0.123 | 0.862 | 28 | 0 | - | 120 | 8 / 1 / 49 / 7 / - |
| `fir4x8` | vliw | mvp-1 | 0.175 | 0.800 | 0 | 0 | 0 | 96 | 6 / 2 / 2 / 30 / - |
| `fir4x8` | vliw | rp4 | 0.132 | 0.842 | 0 | 0 | 6 | 96 | 4 / 2 / 8 / 18 / 6 |
| `fir4x8` | vliw | mac3 | 0.250 | 0.727 | 0 | 8 | 0 | 96 | 4 / 8 / 8 / 24 / - |
| `fir4x8` | single | mvp-0 | 0.296 | 0.691 | 20 | 0 | - | 120 | 17 / 8 / 56 / 0 / - |
| `fir4x8` | single | mvp-1 | 0.431 | 0.552 | 0 | 0 | 0 | 96 | 18 / 8 / 8 / 24 / - |
| `fir4x8` | single | rp4 | 0.431 | 0.552 | 0 | 0 | 0 | 96 | 18 / 8 / 8 / 24 / 0 |
| `fir4x8` | single | mac3 | 0.431 | 0.552 | 13 | 0 | 0 | 96 | 18 / 8 / 8 / 24 / - |
| `matmul3` | vliw | mvp-0 | 0.179 | 0.804 | 21 | 0 | - | 99 | 10 / 1 / 37 / 8 / - |
| `matmul3` | vliw | mvp-1 | 0.282 | 0.692 | 0 | 0 | 0 | 81 | 8 / 4 / 4 / 23 / - |
| `matmul3` | vliw | rp4 | 0.152 | 0.818 | 0 | 0 | 7 | 81 | 4 / 2 / 9 / 11 / 7 |
| `matmul3` | vliw | mac3 | 0.300 | 0.675 | 0 | 6 | 0 | 81 | 4 / 9 / 9 / 18 / - |
| `matmul3` | single | mvp-0 | 0.387 | 0.600 | 15 | 0 | - | 99 | 21 / 9 / 45 / 0 / - |
| `matmul3` | single | mvp-1 | 0.491 | 0.491 | 0 | 0 | 0 | 81 | 19 / 9 / 9 / 18 / - |
| `matmul3` | single | rp4 | 0.491 | 0.491 | 0 | 0 | 0 | 81 | 19 / 9 / 9 / 18 / 0 |
| `matmul3` | single | mac3 | 0.491 | 0.491 | 0 | 0 | 0 | 81 | 19 / 9 / 9 / 18 / - |

- `heldByWritePort`（`Schedule`）は、準備のできた Compute のノードが、書戻しポート（L5）以外の制約（依存、スロット、演算器の II、読出しポート、レジスタの上限）を全て満たすのに、その境界の Compute の書戻しポートが予約済みなので置けなかったサイクルの数である（書戻しの衝突）
- `heldByReadPorts`（`Schedule`）は、準備のできた ST が、読出しポート（L11）以外の制約（依存、スロット、演算器の II、レジスタの上限）を全て満たすのに、その bundle の読出しが `read_ports` を超えるので置けなかったサイクルの数である
- どちらも、待ったノードの代わりに同じスロットへ別のノードを置いたサイクルも数え（例: ST が待ち、LD を置いた）、サイクル当たり高々1つ増える。スケジュールは変えない。LD を複製した試行では、最後に成功したスケジュールの値である。単一発行では、ST だけの bundle は読出し1なので `heldByReadPorts` は0である
- MAC と ST の bundle は `vliw-metrics/2` の `issue.mac_with_st` で、`read_ports` 3 では常に0、rp4 では、3本では置けない bundle の数である
- 読出しはオペランドの欄の数（ADD/SUB/MUL は2、MAC は3、ST は1）の合計で、読出しの数ごとの bundle（HALT の bundle を含む）とともに、MAC のある構成は `vliw-metrics/2` の `issue.register_reads`、mvp-0 は命令列から同じ数え方で数えた値である（`vliw-metrics/1` に、この欄は無い）。「-」は `read_ports` を超える数である

各記録の全項目は `metrics/<kernel>_<mode><構成>.json`（MAC のある構成は `vliw-metrics/2`）、命令列と入力・期待値は `cases/` の同じ名前にある。構成の接尾辞は、mvp-1 が `_mac`、rp4 が `_mac_rp4`、mac3 が `_mac3` で、mvp-0 の記録は [RESULTS.md](RESULTS.md) のものである。

## list scheduling: 戦略を固定した系列

各行は1つの試行（優先度、レジスタ上限、load reserve）で、4つの構成に同じ組を与えた cycles を並べる。どの構成もレジスタは同じ数なので、行は同じである。行の順はコンパイラが試す順（`RegisterAllocator.attempts`）である。**太字**は、その構成の「方式ごとに cycles 最小の系列」で選ばれた試行、「（LD n）」は、その試行が LD を複製し、カーネルの DAG より多い n 個の LD を発行することを示す（#70）。比は、両方の行が成立するときだけ書く。片方の構成だけが LD を複製する行や、複製した LD の数が違う行の比は、複製の違いの効果も含む。「不成立」の理由は [RESULTS.md](RESULTS.md) と同じで、別の試行で置き換えない。どの行の命令列も verifier が合法と判定し、参照モデルで期待した出力と、スケジューラの予測どおりの cycles になることを `BenchmarkSpec` が確かめる（RTL での確認は、方式ごとに cycles 最小の系列の記録だけ）。

### `saxpy8`（vliw）

| 優先度 | レジスタ上限 | load reserve | mvp-0 | mvp-1 | rp4 | mac3 | mvp-1 / mvp-0 | rp4 / mvp-0 | mac3 / mvp-0 |
|---|---|---|---|---|---|---|---|---|---|
| critical-path | なし | - | **26** | **27** | **26** | **27** | 1.04 | 1.00 | 1.04 |
| program-order | なし | - | 28 | 28 | 28 | 29 | 1.00 | 1.00 | 1.04 |
| critical-path | 16 | 0 | 26 | 27 | 26 | 27 | 1.04 | 1.00 | 1.04 |
| critical-path | 16 | 1 | 26 | 27 | 26 | 27 | 1.04 | 1.00 | 1.04 |
| critical-path | 16 | 2 | 26 | 27 | 26 | 27 | 1.04 | 1.00 | 1.04 |
| critical-path | 16 | 3 | 26 | 27 | 26 | 27 | 1.04 | 1.00 | 1.04 |
| critical-path | 16 | 4 | 26 | 27 | 26 | 27 | 1.04 | 1.00 | 1.04 |
| critical-path | 16 | 5 | 26 | 27 | 26 | 27 | 1.04 | 1.00 | 1.04 |
| critical-path | 16 | 6 | 27 | 27 | 26 | 27 | 1.00 | 0.96 | 1.00 |
| critical-path | 16 | 7 | 28 | 27 | 26 | 27 | 0.96 | 0.93 | 0.96 |
| critical-path | 16 | 8 | 不成立: 上限の下で行き詰まる | 27 | 26 | 27 | - | - | - |
| program-order | 16 | 0 | 28 | 28 | 28 | 29 | 1.00 | 1.00 | 1.04 |
| program-order | 16 | 1 | 28 | 28 | 28 | 29 | 1.00 | 1.00 | 1.04 |
| program-order | 16 | 2 | 28 | 28 | 28 | 29 | 1.00 | 1.00 | 1.04 |
| program-order | 16 | 3 | 28 | 28 | 28 | 29 | 1.00 | 1.00 | 1.04 |
| program-order | 16 | 4 | 28 | 28 | 28 | 29 | 1.00 | 1.00 | 1.04 |
| program-order | 16 | 5 | 28 | 28 | 28 | 29 | 1.00 | 1.00 | 1.04 |
| program-order | 16 | 6 | 28 | 28 | 28 | 29 | 1.00 | 1.00 | 1.04 |
| program-order | 16 | 7 | 28 | 28 | 28 | 29 | 1.00 | 1.00 | 1.04 |
| program-order | 16 | 8 | 28 | 28 | 28 | 29 | 1.00 | 1.00 | 1.04 |

- mvp-1 と mvp-0: 両方で成立する行 19（mvp-1 の cycles が少ない 1、多い 7、同じ 11）、片方だけで成立する行 1（mvp-1 だけ 1、mvp-0 だけ 0）
- rp4 と mvp-0: 両方で成立する行 19（rp4 の cycles が少ない 2、多い 0、同じ 17）、片方だけで成立する行 1（rp4 だけ 1、mvp-0 だけ 0）
- mac3 と mvp-0: 両方で成立する行 19（mac3 の cycles が少ない 1、多い 17、同じ 1）、片方だけで成立する行 1（mac3 だけ 1、mvp-0 だけ 0）
- 不成立の行は mvp-0 1（上限の下で行き詰まる 1）、mvp-1 0、rp4 0、mac3 0。LD を複製した行は mvp-0 0、mvp-1 0、rp4 0、mac3 0

### `saxpy8`（single）

| 優先度 | レジスタ上限 | load reserve | mvp-0 | mvp-1 | rp4 | mac3 | mvp-1 / mvp-0 | rp4 / mvp-0 | mac3 / mvp-0 |
|---|---|---|---|---|---|---|---|---|---|
| critical-path | なし | - | **42** | 不成立: 同時に生きる値 17 > 16 | 不成立: 同時に生きる値 17 > 16 | 不成立: 同時に生きる値 17 > 16 | - | - | - |
| program-order | なし | - | 43 | 35 | 35 | 37 | 0.81 | 0.81 | 0.86 |
| critical-path | 16 | 0 | 42 | **34** | **34** | **34** | 0.81 | 0.81 | 0.81 |
| critical-path | 16 | 1 | 42 | 34 | 34 | 34 | 0.81 | 0.81 | 0.81 |
| critical-path | 16 | 2 | 42 | 34 | 34 | 34 | 0.81 | 0.81 | 0.81 |
| critical-path | 16 | 3 | 42 | 34 | 34 | 34 | 0.81 | 0.81 | 0.81 |
| critical-path | 16 | 4 | 42 | 34 | 34 | 34 | 0.81 | 0.81 | 0.81 |
| critical-path | 16 | 5 | 42 | 34 | 34 | 34 | 0.81 | 0.81 | 0.81 |
| critical-path | 16 | 6 | 42 | 34 | 34 | 34 | 0.81 | 0.81 | 0.81 |
| critical-path | 16 | 7 | 43 | 34 | 34 | 34 | 0.79 | 0.79 | 0.79 |
| critical-path | 16 | 8 | 不成立: 上限の下で行き詰まる | 34 | 34 | 34 | - | - | - |
| program-order | 16 | 0 | 43 | 35 | 35 | 37 | 0.81 | 0.81 | 0.86 |
| program-order | 16 | 1 | 43 | 35 | 35 | 37 | 0.81 | 0.81 | 0.86 |
| program-order | 16 | 2 | 43 | 35 | 35 | 37 | 0.81 | 0.81 | 0.86 |
| program-order | 16 | 3 | 43 | 35 | 35 | 37 | 0.81 | 0.81 | 0.86 |
| program-order | 16 | 4 | 43 | 35 | 35 | 37 | 0.81 | 0.81 | 0.86 |
| program-order | 16 | 5 | 43 | 35 | 35 | 37 | 0.81 | 0.81 | 0.86 |
| program-order | 16 | 6 | 43 | 35 | 35 | 37 | 0.81 | 0.81 | 0.86 |
| program-order | 16 | 7 | 43 | 35 | 35 | 37 | 0.81 | 0.81 | 0.86 |
| program-order | 16 | 8 | 43 | 35 | 35 | 37 | 0.81 | 0.81 | 0.86 |

- mvp-1 と mvp-0: 両方で成立する行 18（mvp-1 の cycles が少ない 18、多い 0、同じ 0）、片方だけで成立する行 2（mvp-1 だけ 1、mvp-0 だけ 1）
- rp4 と mvp-0: 両方で成立する行 18（rp4 の cycles が少ない 18、多い 0、同じ 0）、片方だけで成立する行 2（rp4 だけ 1、mvp-0 だけ 1）
- mac3 と mvp-0: 両方で成立する行 18（mac3 の cycles が少ない 18、多い 0、同じ 0）、片方だけで成立する行 2（mac3 だけ 1、mvp-0 だけ 1）
- 不成立の行は mvp-0 1（上限の下で行き詰まる 1）、mvp-1 1（同時に生きる値が多い 1）、rp4 1（同時に生きる値が多い 1）、mac3 1（同時に生きる値が多い 1）。LD を複製した行は mvp-0 0、mvp-1 0、rp4 0、mac3 0

### `fir4x8`（vliw）

| 優先度 | レジスタ上限 | load reserve | mvp-0 | mvp-1 | rp4 | mac3 | mvp-1 / mvp-0 | rp4 / mvp-0 | mac3 / mvp-0 |
|---|---|---|---|---|---|---|---|---|---|
| critical-path | なし | - | 不成立: 同時に生きる値 33 > 16 | 不成立: 同時に生きる値 20 > 16 | 不成立: 同時に生きる値 20 > 16 | 不成立: 同時に生きる値 20 > 16 | - | - | - |
| program-order | なし | - | 不成立: 同時に生きる値 36 > 16 | **40** | 40 | 53 | - | - | - |
| critical-path | 16 | 0 | 不成立: 上限の下で行き詰まる | 46 | 40 | 46 | - | - | - |
| critical-path | 16 | 1 | 75（LD 47） | 44（LD 27） | **38**（LD 27） | **44**（LD 27） | 0.59 | 0.51 | 0.59 |
| critical-path | 16 | 2 | 84（LD 52） | 44（LD 30） | 39（LD 30） | 44（LD 30） | 0.52 | 0.46 | 0.52 |
| critical-path | 16 | 3 | 84（LD 52） | 44（LD 31） | 40（LD 31） | 44（LD 31） | 0.52 | 0.48 | 0.52 |
| critical-path | 16 | 4 | 83（LD 54） | 44（LD 32） | 41（LD 32） | 44（LD 32） | 0.53 | 0.49 | 0.53 |
| critical-path | 16 | 5 | 87（LD 53） | 44（LD 33） | 42（LD 33） | 44（LD 33） | 0.51 | 0.48 | 0.51 |
| critical-path | 16 | 6 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - |
| critical-path | 16 | 7 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - |
| critical-path | 16 | 8 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - |
| program-order | 16 | 0 | 67 | 40 | 40 | 53 | 0.60 | 0.60 | 0.79 |
| program-order | 16 | 1 | 66 | 40 | 40 | 53 | 0.61 | 0.61 | 0.80 |
| program-order | 16 | 2 | 66 | 40 | 40 | 53 | 0.61 | 0.61 | 0.80 |
| program-order | 16 | 3 | 66 | 40 | 40 | 53 | 0.61 | 0.61 | 0.80 |
| program-order | 16 | 4 | **65** | 40 | 40 | 53 | 0.62 | 0.62 | 0.82 |
| program-order | 16 | 5 | 66 | 40 | 40 | 53 | 0.61 | 0.61 | 0.80 |
| program-order | 16 | 6 | 66 | 40 | 40 | 53 | 0.61 | 0.61 | 0.80 |
| program-order | 16 | 7 | 67 | 40 | 40 | 53 | 0.60 | 0.60 | 0.79 |
| program-order | 16 | 8 | 76 | 49 | 45 | 55 | 0.64 | 0.59 | 0.72 |

- mvp-1 と mvp-0: 両方で成立する行 14（mvp-1 の cycles が少ない 14、多い 0、同じ 0）、片方だけで成立する行 2（mvp-1 だけ 2、mvp-0 だけ 0）
- rp4 と mvp-0: 両方で成立する行 14（rp4 の cycles が少ない 14、多い 0、同じ 0）、片方だけで成立する行 2（rp4 だけ 2、mvp-0 だけ 0）
- mac3 と mvp-0: 両方で成立する行 14（mac3 の cycles が少ない 14、多い 0、同じ 0）、片方だけで成立する行 2（mac3 だけ 2、mvp-0 だけ 0）
- 不成立の行は mvp-0 6（同時に生きる値が多い 2、上限の下で行き詰まる 4）、mvp-1 4（同時に生きる値が多い 1、上限の下で行き詰まる 3）、rp4 4（同時に生きる値が多い 1、上限の下で行き詰まる 3）、mac3 4（同時に生きる値が多い 1、上限の下で行き詰まる 3）。LD を複製した行は mvp-0 5、mvp-1 5、rp4 5、mac3 5

### `fir4x8`（single）

| 優先度 | レジスタ上限 | load reserve | mvp-0 | mvp-1 | rp4 | mac3 | mvp-1 / mvp-0 | rp4 / mvp-0 | mac3 / mvp-0 |
|---|---|---|---|---|---|---|---|---|---|
| critical-path | なし | - | 不成立: 同時に生きる値 33 > 16 | 不成立: 同時に生きる値 18 > 16 | 不成立: 同時に生きる値 18 > 16 | 不成立: 同時に生きる値 17 > 16 | - | - | - |
| program-order | なし | - | **81** | **58** | **58** | 66 | 0.72 | 0.72 | 0.81 |
| critical-path | 16 | 0 | 不成立: 上限の下で行き詰まる | 59 | 59 | **58**（LD 17） | - | - | - |
| critical-path | 16 | 1 | 100（LD 33） | 60（LD 19） | 60（LD 19） | 59（LD 18） | 0.60 | 0.60 | 0.59 |
| critical-path | 16 | 2 | 105（LD 38） | 62（LD 21） | 62（LD 21） | 62（LD 21） | 0.59 | 0.59 | 0.59 |
| critical-path | 16 | 3 | 103（LD 36） | 64（LD 23） | 64（LD 23） | 64（LD 23） | 0.62 | 0.62 | 0.62 |
| critical-path | 16 | 4 | 102（LD 35） | 66（LD 25） | 66（LD 25） | 66（LD 25） | 0.65 | 0.65 | 0.65 |
| critical-path | 16 | 5 | 104（LD 37） | 68（LD 27） | 68（LD 27） | 68（LD 27） | 0.65 | 0.65 | 0.65 |
| critical-path | 16 | 6 | 不成立: 上限の下で行き詰まる | 88（LD 37） | 88（LD 37） | 不成立: 上限の下で行き詰まる | - | - | - |
| critical-path | 16 | 7 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - |
| critical-path | 16 | 8 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - |
| program-order | 16 | 0 | 81 | 58 | 58 | 66 | 0.72 | 0.72 | 0.81 |
| program-order | 16 | 1 | 81 | 58 | 58 | 66 | 0.72 | 0.72 | 0.81 |
| program-order | 16 | 2 | 81 | 58 | 58 | 66 | 0.72 | 0.72 | 0.81 |
| program-order | 16 | 3 | 81 | 58 | 58 | 66 | 0.72 | 0.72 | 0.81 |
| program-order | 16 | 4 | 81 | 58 | 58 | 66 | 0.72 | 0.72 | 0.81 |
| program-order | 16 | 5 | 84 | 58 | 58 | 66 | 0.69 | 0.69 | 0.79 |
| program-order | 16 | 6 | 86 | 58 | 58 | 66 | 0.67 | 0.67 | 0.77 |
| program-order | 16 | 7 | 86 | 58 | 58 | 66 | 0.67 | 0.67 | 0.77 |
| program-order | 16 | 8 | 91 | 58 | 58 | 66 | 0.64 | 0.64 | 0.73 |

- mvp-1 と mvp-0: 両方で成立する行 15（mvp-1 の cycles が少ない 15、多い 0、同じ 0）、片方だけで成立する行 2（mvp-1 だけ 2、mvp-0 だけ 0）
- rp4 と mvp-0: 両方で成立する行 15（rp4 の cycles が少ない 15、多い 0、同じ 0）、片方だけで成立する行 2（rp4 だけ 2、mvp-0 だけ 0）
- mac3 と mvp-0: 両方で成立する行 15（mac3 の cycles が少ない 15、多い 0、同じ 0）、片方だけで成立する行 1（mac3 だけ 1、mvp-0 だけ 0）
- 不成立の行は mvp-0 5（同時に生きる値が多い 1、上限の下で行き詰まる 4）、mvp-1 3（同時に生きる値が多い 1、上限の下で行き詰まる 2）、rp4 3（同時に生きる値が多い 1、上限の下で行き詰まる 2）、mac3 4（同時に生きる値が多い 1、上限の下で行き詰まる 3）。LD を複製した行は mvp-0 5、mvp-1 6、rp4 6、mac3 6

### `matmul3`（vliw）

| 優先度 | レジスタ上限 | load reserve | mvp-0 | mvp-1 | rp4 | mac3 | mvp-1 / mvp-0 | rp4 / mvp-0 | mac3 / mvp-0 |
|---|---|---|---|---|---|---|---|---|---|
| critical-path | なし | - | 不成立: 同時に生きる値 28 > 16 | 不成立: 同時に生きる値 19 > 16 | 不成立: 同時に生きる値 19 > 16 | 不成立: 同時に生きる値 19 > 16 | - | - | - |
| program-order | なし | - | 不成立: 同時に生きる値 29 > 16 | 不成立: 同時に生きる値 17 > 16 | 不成立: 同時に生きる値 17 > 16 | 不成立: 同時に生きる値 17 > 16 | - | - | - |
| critical-path | 16 | 0 | 61（LD 37） | 40 | **33** | **40** | 0.66 | 0.54 | 0.66 |
| critical-path | 16 | 1 | **56** | 40 | 33 | 40 | 0.71 | 0.59 | 0.71 |
| critical-path | 16 | 2 | 57 | 40 | 34 | 40 | 0.70 | 0.60 | 0.70 |
| critical-path | 16 | 3 | 62 | 42 | 37 | 42 | 0.68 | 0.60 | 0.68 |
| critical-path | 16 | 4 | 67（LD 44） | 45（LD 32） | 39（LD 29） | 45（LD 32） | 0.67 | 0.58 | 0.67 |
| critical-path | 16 | 5 | 72（LD 46） | 62（LD 39） | 57（LD 37） | 63（LD 39） | 0.86 | 0.79 | 0.88 |
| critical-path | 16 | 6 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - |
| critical-path | 16 | 7 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - |
| critical-path | 16 | 8 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - |
| program-order | 16 | 0 | 57 | **39** | 39 | 46 | 0.68 | 0.68 | 0.81 |
| program-order | 16 | 1 | 56 | 39 | 39 | 46 | 0.70 | 0.70 | 0.82 |
| program-order | 16 | 2 | 59 | 39 | 39 | 48 | 0.66 | 0.66 | 0.81 |
| program-order | 16 | 3 | 63 | 43 | 43 | 45 | 0.68 | 0.68 | 0.71 |
| program-order | 16 | 4 | 56（LD 37） | 39（LD 24） | 38（LD 24） | 45（LD 28） | 0.70 | 0.68 | 0.80 |
| program-order | 16 | 5 | 56（LD 38） | 39（LD 25） | 39（LD 25） | 46（LD 29） | 0.70 | 0.70 | 0.82 |
| program-order | 16 | 6 | 57（LD 39） | 43（LD 27） | 43（LD 27） | 47（LD 31） | 0.75 | 0.75 | 0.82 |
| program-order | 16 | 7 | 58（LD 39） | 47（LD 30） | 47（LD 30） | 51（LD 36） | 0.81 | 0.81 | 0.88 |
| program-order | 16 | 8 | 59（LD 41） | 49（LD 32） | 49（LD 32） | 54（LD 37） | 0.83 | 0.83 | 0.92 |

- mvp-1 と mvp-0: 両方で成立する行 15（mvp-1 の cycles が少ない 15、多い 0、同じ 0）、片方だけで成立する行 0（mvp-1 だけ 0、mvp-0 だけ 0）
- rp4 と mvp-0: 両方で成立する行 15（rp4 の cycles が少ない 15、多い 0、同じ 0）、片方だけで成立する行 0（rp4 だけ 0、mvp-0 だけ 0）
- mac3 と mvp-0: 両方で成立する行 15（mac3 の cycles が少ない 15、多い 0、同じ 0）、片方だけで成立する行 0（mac3 だけ 0、mvp-0 だけ 0）
- 不成立の行は mvp-0 5（同時に生きる値が多い 2、上限の下で行き詰まる 3）、mvp-1 5（同時に生きる値が多い 2、上限の下で行き詰まる 3）、rp4 5（同時に生きる値が多い 2、上限の下で行き詰まる 3）、mac3 5（同時に生きる値が多い 2、上限の下で行き詰まる 3）。LD を複製した行は mvp-0 8、mvp-1 7、rp4 7、mac3 7

### `matmul3`（single）

| 優先度 | レジスタ上限 | load reserve | mvp-0 | mvp-1 | rp4 | mac3 | mvp-1 / mvp-0 | rp4 / mvp-0 | mac3 / mvp-0 |
|---|---|---|---|---|---|---|---|---|---|
| critical-path | なし | - | 不成立: 同時に生きる値 28 > 16 | 不成立: 同時に生きる値 19 > 16 | 不成立: 同時に生きる値 19 > 16 | **55** | - | - | - |
| program-order | なし | - | 不成立: 同時に生きる値 19 > 16 | 57 | 57 | 61 | - | - | - |
| critical-path | 16 | 0 | 不成立: 上限の下で行き詰まる | **55** | **55** | 55 | - | - | - |
| critical-path | 16 | 1 | 89（LD 32） | 55 | 55 | 55 | 0.62 | 0.62 | 0.62 |
| critical-path | 16 | 2 | 98（LD 43） | 55 | 55 | 55 | 0.56 | 0.56 | 0.56 |
| critical-path | 16 | 3 | 79 | 57 | 57 | 57 | 0.72 | 0.72 | 0.72 |
| critical-path | 16 | 4 | 78 | 59（LD 22） | 59（LD 22） | 60（LD 23） | 0.76 | 0.76 | 0.77 |
| critical-path | 16 | 5 | 91（LD 33） | 76（LD 31） | 76（LD 31） | 76（LD 31） | 0.84 | 0.84 | 0.84 |
| critical-path | 16 | 6 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - |
| critical-path | 16 | 7 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - |
| critical-path | 16 | 8 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - |
| program-order | 16 | 0 | **75** | 57 | 57 | 61 | 0.76 | 0.76 | 0.81 |
| program-order | 16 | 1 | 76 | 57 | 57 | 61 | 0.75 | 0.75 | 0.80 |
| program-order | 16 | 2 | 76 | 57 | 57 | 61 | 0.75 | 0.75 | 0.80 |
| program-order | 16 | 3 | 76 | 57 | 57 | 64 | 0.75 | 0.75 | 0.84 |
| program-order | 16 | 4 | 81（LD 25） | 59（LD 20） | 59（LD 20） | 60（LD 21） | 0.73 | 0.73 | 0.74 |
| program-order | 16 | 5 | 82（LD 26） | 59（LD 22） | 59（LD 22） | 64（LD 22） | 0.72 | 0.72 | 0.78 |
| program-order | 16 | 6 | 83（LD 27） | 61（LD 23） | 61（LD 23） | 63（LD 23） | 0.73 | 0.73 | 0.76 |
| program-order | 16 | 7 | 84（LD 28） | 64（LD 25） | 64（LD 25） | 66（LD 24） | 0.76 | 0.76 | 0.79 |
| program-order | 16 | 8 | 87（LD 31） | 67（LD 29） | 67（LD 29） | 68（LD 27） | 0.77 | 0.77 | 0.78 |

- mvp-1 と mvp-0: 両方で成立する行 14（mvp-1 の cycles が少ない 14、多い 0、同じ 0）、片方だけで成立する行 2（mvp-1 だけ 2、mvp-0 だけ 0）
- rp4 と mvp-0: 両方で成立する行 14（rp4 の cycles が少ない 14、多い 0、同じ 0）、片方だけで成立する行 2（rp4 だけ 2、mvp-0 だけ 0）
- mac3 と mvp-0: 両方で成立する行 14（mac3 の cycles が少ない 14、多い 0、同じ 0）、片方だけで成立する行 3（mac3 だけ 3、mvp-0 だけ 0）
- 不成立の行は mvp-0 6（同時に生きる値が多い 2、上限の下で行き詰まる 4）、mvp-1 4（同時に生きる値が多い 1、上限の下で行き詰まる 3）、rp4 4（同時に生きる値が多い 1、上限の下で行き詰まる 3）、mac3 3（上限の下で行き詰まる 3）。LD を複製した行は mvp-0 8、mvp-1 7、rp4 7、mac3 7

## 読出しポートの制約（mvp-1 と rp4）

mvp-1 と rp4 は `read_ports` だけが違い、同じカーネル・入力・配置・DAG・試行で比べる（融合は読出しポートによらない）。差は L11 の制約（MAC と ST を同じ bundle に置けない）と、それによるスケジュールの違いの効果である。制約が当たった bundle の数は2通りに数える。mvp-1 の `heldByReadPorts`（ST が読出しポートだけで待ったサイクル）と、rp4 の命令列の MAC と ST の bundle（3本では置けない bundle）である。rp4 の命令列を mvp-1 の machine で verifier にかけると、MAC と ST の bundle ごとにちょうど1つ L11 の違反になり、ほかの違反は無い（`BenchmarkSpec` が全ての rp4 の命令列で確かめる）。

### 方式ごとに cycles 最小の系列

| カーネル | 方式 | mvp-1 | rp4 | 差（mvp-1 − rp4） | `heldByReadPorts`（mvp-1） | MAC と ST の bundle（rp4） | L11 の違反（rp4 の命令列を mvp-1 で検査） | 試行（mvp-1 / rp4） |
|---|---|---|---|---|---|---|---|---|
| `saxpy8` | vliw | 27 | 26 | 1 | 7 | 1 | 1 | critical-path, no register limit / critical-path, no register limit（同じ） |
| `saxpy8` | single | 34 | 34 | 0 | 0 | 0 | 0 | critical-path, 16 registers, load reserve 0, single issue / critical-path, 16 registers, load reserve 0, single issue（同じ） |
| `fir4x8` | vliw | 40 | 38（LD 27） | 2 | 0 | 6 | 6 | program-order, no register limit / critical-path, 16 registers, load reserve 1（違う） |
| `fir4x8` | single | 58 | 58 | 0 | 0 | 0 | 0 | program-order, no register limit, single issue / program-order, no register limit, single issue（同じ） |
| `matmul3` | vliw | 39 | 33 | 6 | 0 | 7 | 7 | program-order, 16 registers, load reserve 0 / critical-path, 16 registers, load reserve 0（違う） |
| `matmul3` | single | 55 | 55 | 0 | 0 | 0 | 0 | critical-path, 16 registers, load reserve 0, single issue / critical-path, 16 registers, load reserve 0, single issue（同じ） |

### 戦略を固定した系列

#### `saxpy8`（vliw）

| 優先度 | レジスタ上限 | load reserve | mvp-1 | rp4 | 差（mvp-1 − rp4） | `heldByReadPorts`（mvp-1） | MAC と ST の bundle（rp4） | L11 の違反（rp4 の命令列を mvp-1 で検査） |
|---|---|---|---|---|---|---|---|---|
| critical-path | なし | - | 27 | 26 | 1 | 7 | 1 | 1 |
| program-order | なし | - | 28 | 28 | 0 | 5 | 1 | 1 |
| critical-path | 16 | 0 | 27 | 26 | 1 | 7 | 1 | 1 |
| critical-path | 16 | 1 | 27 | 26 | 1 | 7 | 1 | 1 |
| critical-path | 16 | 2 | 27 | 26 | 1 | 7 | 1 | 1 |
| critical-path | 16 | 3 | 27 | 26 | 1 | 7 | 1 | 1 |
| critical-path | 16 | 4 | 27 | 26 | 1 | 7 | 1 | 1 |
| critical-path | 16 | 5 | 27 | 26 | 1 | 7 | 1 | 1 |
| critical-path | 16 | 6 | 27 | 26 | 1 | 7 | 1 | 1 |
| critical-path | 16 | 7 | 27 | 26 | 1 | 7 | 1 | 1 |
| critical-path | 16 | 8 | 27 | 26 | 1 | 7 | 1 | 1 |
| program-order | 16 | 0 | 28 | 28 | 0 | 5 | 1 | 1 |
| program-order | 16 | 1 | 28 | 28 | 0 | 5 | 1 | 1 |
| program-order | 16 | 2 | 28 | 28 | 0 | 5 | 1 | 1 |
| program-order | 16 | 3 | 28 | 28 | 0 | 5 | 1 | 1 |
| program-order | 16 | 4 | 28 | 28 | 0 | 5 | 1 | 1 |
| program-order | 16 | 5 | 28 | 28 | 0 | 5 | 1 | 1 |
| program-order | 16 | 6 | 28 | 28 | 0 | 5 | 1 | 1 |
| program-order | 16 | 7 | 28 | 28 | 0 | 5 | 1 | 1 |
| program-order | 16 | 8 | 28 | 28 | 0 | 5 | 1 | 1 |

- 両方で成立する行 20: mvp-1 の cycles が多い 10（差の最大 1）、同じ 10、少ない 0。片方だけで成立する行 0
- mvp-1 で `heldByReadPorts` が0でない行 20（合計 120）

#### `fir4x8`（vliw）

| 優先度 | レジスタ上限 | load reserve | mvp-1 | rp4 | 差（mvp-1 − rp4） | `heldByReadPorts`（mvp-1） | MAC と ST の bundle（rp4） | L11 の違反（rp4 の命令列を mvp-1 で検査） |
|---|---|---|---|---|---|---|---|---|
| critical-path | なし | - | 不成立: 同時に生きる値 20 > 16 | 不成立: 同時に生きる値 20 > 16 | - | - | - | - |
| program-order | なし | - | 40 | 40 | 0 | 0 | 0 | 0 |
| critical-path | 16 | 0 | 46 | 40 | 6 | 6 | 6 | 6 |
| critical-path | 16 | 1 | 44（LD 27） | 38（LD 27） | 6 | 9 | 6 | 6 |
| critical-path | 16 | 2 | 44（LD 30） | 39（LD 30） | 5 | 6 | 5 | 5 |
| critical-path | 16 | 3 | 44（LD 31） | 40（LD 31） | 4 | 6 | 4 | 4 |
| critical-path | 16 | 4 | 44（LD 32） | 41（LD 32） | 3 | 6 | 3 | 3 |
| critical-path | 16 | 5 | 44（LD 33） | 42（LD 33） | 2 | 6 | 2 | 2 |
| critical-path | 16 | 6 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - |
| critical-path | 16 | 7 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - |
| critical-path | 16 | 8 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - |
| program-order | 16 | 0 | 40 | 40 | 0 | 0 | 0 | 0 |
| program-order | 16 | 1 | 40 | 40 | 0 | 0 | 0 | 0 |
| program-order | 16 | 2 | 40 | 40 | 0 | 0 | 0 | 0 |
| program-order | 16 | 3 | 40 | 40 | 0 | 0 | 0 | 0 |
| program-order | 16 | 4 | 40 | 40 | 0 | 0 | 0 | 0 |
| program-order | 16 | 5 | 40 | 40 | 0 | 0 | 0 | 0 |
| program-order | 16 | 6 | 40 | 40 | 0 | 0 | 0 | 0 |
| program-order | 16 | 7 | 40 | 40 | 0 | 0 | 0 | 0 |
| program-order | 16 | 8 | 49 | 45 | 4 | 8 | 5 | 5 |

- 両方で成立する行 16: mvp-1 の cycles が多い 7（差の最大 6）、同じ 9、少ない 0。片方だけで成立する行 0
- mvp-1 で `heldByReadPorts` が0でない行 7（合計 47）

#### `matmul3`（vliw）

| 優先度 | レジスタ上限 | load reserve | mvp-1 | rp4 | 差（mvp-1 − rp4） | `heldByReadPorts`（mvp-1） | MAC と ST の bundle（rp4） | L11 の違反（rp4 の命令列を mvp-1 で検査） |
|---|---|---|---|---|---|---|---|---|
| critical-path | なし | - | 不成立: 同時に生きる値 19 > 16 | 不成立: 同時に生きる値 19 > 16 | - | - | - | - |
| program-order | なし | - | 不成立: 同時に生きる値 17 > 16 | 不成立: 同時に生きる値 17 > 16 | - | - | - | - |
| critical-path | 16 | 0 | 40 | 33 | 7 | 7 | 7 | 7 |
| critical-path | 16 | 1 | 40 | 33 | 7 | 7 | 7 | 7 |
| critical-path | 16 | 2 | 40 | 34 | 6 | 7 | 6 | 6 |
| critical-path | 16 | 3 | 42 | 37 | 5 | 7 | 5 | 5 |
| critical-path | 16 | 4 | 45（LD 32） | 39（LD 29） | 6 | 8 | 6 | 6 |
| critical-path | 16 | 5 | 62（LD 39） | 57（LD 37） | 5 | 7 | 4 | 4 |
| critical-path | 16 | 6 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - |
| critical-path | 16 | 7 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - |
| critical-path | 16 | 8 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - |
| program-order | 16 | 0 | 39 | 39 | 0 | 0 | 0 | 0 |
| program-order | 16 | 1 | 39 | 39 | 0 | 8 | 2 | 2 |
| program-order | 16 | 2 | 39 | 39 | 0 | 0 | 0 | 0 |
| program-order | 16 | 3 | 43 | 43 | 0 | 0 | 0 | 0 |
| program-order | 16 | 4 | 39（LD 24） | 38（LD 24） | 1 | 4 | 1 | 1 |
| program-order | 16 | 5 | 39（LD 25） | 39（LD 25） | 0 | 6 | 2 | 2 |
| program-order | 16 | 6 | 43（LD 27） | 43（LD 27） | 0 | 0 | 0 | 0 |
| program-order | 16 | 7 | 47（LD 30） | 47（LD 30） | 0 | 0 | 0 | 0 |
| program-order | 16 | 8 | 49（LD 32） | 49（LD 32） | 0 | 0 | 0 | 0 |

- 両方で成立する行 15: mvp-1 の cycles が多い 7（差の最大 7）、同じ 8、少ない 0。片方だけで成立する行 0
- mvp-1 で `heldByReadPorts` が0でない行 9（合計 61）

単一発行では、1 bundle に1操作なので読出しは高々3で、制約は当たらない。戦略を固定した系列で、mvp-1 と rp4 の命令列（不成立ならその理由）が同じ行は、`saxpy8` 20 行のうち 20 行、`fir4x8` 20 行のうち 20 行、`matmul3` 20 行のうち 20 行である。

## Modulo Scheduling

`vliw.compiler.loop.LoopBenchmarks` の6つのループを、4つの構成で。最初の4つは展開すると list scheduling のカーネルと同じになり、配置と入力は list scheduling と同じである（[MODULO.md](MODULO.md)。`BenchmarkSpec` が確かめる）。戦略は1つで、II = MII から探索の上限まで順に試し、最初に命令列ができた II を使う。ここでの II はループの II（反復の開始間隔）で、`metrics` の `ii` に、トレースで確かめた値（`crosscheck/ModuloTrace`）を記録する。MAC の演算器の II（`operations.MAC.ii`）ではない。

### 融合の判定

| ループ | 構成 | 融合する組 | MII（融合しない → 融合する） | 判定 |
|---|---|---|---|---|
| `saxpy8`（i） | mvp-1 | 1 | 3 → 3 | 融合する |
| `saxpy8`（i） | rp4 | 1 | 3 → 3 | 融合する |
| `saxpy8`（i） | mac3 | 1 | 3 → 3 | 融合する |
| `fir4x8`（i） | mvp-1 | 3 | 7 → 4 | 融合する |
| `fir4x8`（i） | rp4 | 3 | 7 → 4 | 融合する |
| `fir4x8`（i） | mac3 | 3 | 7 → 4 | 融合する |
| `matmul3`（i） | mvp-1 | 6 | 15 → 9 | 融合する |
| `matmul3`（i） | rp4 | 6 | 15 → 9 | 融合する |
| `matmul3`（i） | mac3 | 6 | 15 → 9 | 融合する |
| `matmul3`（m） | mvp-1 | 9 | 18 → 9 | 融合する |
| `matmul3`（m） | rp4 | 9 | 18 → 9 | 融合する |
| `matmul3`（m） | mac3 | 9 | 18 → 9 | 融合する |
| `product8`（i） | mvp-1 | 0 | 2 → 2 | 融合しない |
| `product8`（i） | rp4 | 0 | 2 → 2 | 融合しない |
| `product8`（i） | mac3 | 0 | 2 → 2 | 融合しない |
| `dot8`（i） | mvp-1 | 1 | 2 → 2 | 融合する |
| `dot8`（i） | rp4 | 1 | 2 → 2 | 融合する |
| `dot8`（i） | mac3 | 1 | 2 → 3 | 融合しない |

融合する組があり、融合した後の MII が融合しない MII 以下のときだけ融合する（`FusionChoice`、ADR-0019）。MII は3本目の読出しポートを除いて比べる。累算の閉路は、融合すると ADD の latency から MAC の latency に延びる。

### ループごとの結果

| ループ | 構成 | ResMII（律速の資源） | ResMII（3本目の読出しポートを除く） | RecMII | MII | II | MII を超えた II の理由 | 配置 | 1反復の長さ | cycles | mvp-0 に対する cycles の比 | MAC と ST の bundle |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `saxpy8`（i） | mvp-0 | 3（`memory slot`） | 3 | 0 | 3 | 3 | - | 番号の順 | 6 | 29 | 1.00 | - |
| `saxpy8`（i） | mvp-1 | 3（`memory slot`） | 3 | 0 | 3 | 3 | - | 番号の順 | 6 | 29 | 1.00 | 0 |
| `saxpy8`（i） | rp4 | 3（`memory slot`） | 3 | 0 | 3 | 3 | - | 番号の順 | 6 | 29 | 1.00 | 0 |
| `saxpy8`（i） | mac3 | 3（`memory slot`） | 3 | 0 | 3 | 3 | - | 番号の順 | 9 | 32 | 1.10 | 0 |
| `fir4x8`（i） | mvp-0 | 7（`compute slot`、`compute write-back port`） | 7 | 0 | 7 | 8 | II 7: (a) 予約表なし（slot + write-back port (L5)） | 先読み | 15 | 76 | 1.00 | - |
| `fir4x8`（i） | mvp-1 | 4（`compute slot`、`compute write-back port`、`third read port`） | 4 | 0 | 4 | 4 | - | 先読み | 14 | 46 | 0.61 | 0 |
| `fir4x8`（i） | rp4 | 4（`compute slot`、`compute write-back port`） | 4 | 0 | 4 | 4 | - | 番号の順 | 10 | 43 | 0.57 | 7（反復の中 7） |
| `fir4x8`（i） | mac3 | 4（`compute slot`、`compute write-back port`、`third read port`） | 4 | 0 | 4 | 5 | II 4: (a) 予約表なし（slot + write-back port (L5)） | 番号の順 | 20 | 60 | 0.79 | 0 |
| `matmul3`（i） | mvp-0 | 15（`compute slot`、`compute write-back port`） | 15 | 0 | 15 | 20 | II 15: (a) 予約表なし（slot + write-back port (L5)）。II 16〜19: (b) レジスタ（同時に生きる値 22、20、19、17 > 16） | 先読み | 44 | 87 | 1.00 | - |
| `matmul3`（i） | mvp-1 | 9（`compute slot`、`compute write-back port`、`third read port`） | 9 | 0 | 9 | 9 | - | 先読み | 15 | 38 | 0.44 | 0 |
| `matmul3`（i） | rp4 | 9（`compute slot`、`compute write-back port`） | 9 | 0 | 9 | 9 | - | 番号の順 | 23 | 45 | 0.52 | 1（反復の中 1） |
| `matmul3`（i） | mac3 | 9（`compute slot`、`compute write-back port`、`third read port`） | 9 | 0 | 9 | 10 | II 9: (a) 予約表なし（slot + write-back port (L5)） | 先読み | 15 | 39 | 0.45 | 0 |
| `matmul3`（m） | mvp-0 | 18（`compute slot`、`compute write-back port`） | 18 | 1 | 18 | 27 | II 18: (a) 予約表なし（slot + write-back port (L5)）。II 19〜26: (b) レジスタ（同時に生きる値 23、22、21、20、19、18、17、17 > 16） | 番号の順 | 30 | 68 | 1.00 | - |
| `matmul3`（m） | mvp-1 | 9（`compute slot`、`compute write-back port`、`third read port`、`MAC unit`） | 9 | 2 | 9 | 9 | - | 番号の順 | 13 | 40 | 0.59 | 0 |
| `matmul3`（m） | rp4 | 9（`compute slot`、`compute write-back port`、`MAC unit`） | 9 | 2 | 9 | 9 | - | 番号の順 | 13 | 34 | 0.50 | 6（ループの後 6） |
| `matmul3`（m） | mac3 | 9（`compute slot`、`compute write-back port`、`third read port`、`MAC unit`） | 9 | 3 | 9 | 9 | - | 番号の順 | 14 | 40 | 0.59 | 0 |
| `product8`（i） | mvp-0 | 1（`compute slot`、`memory slot`、`compute write-back port`、`memory write-back port`、`LD unit`、`MUL unit`） | 1 | 2 | 2 | 2 | - | 番号の順 | 4 | 19 | 1.00 | - |
| `product8`（i） | mvp-1 | 1（`compute slot`、`memory slot`、`compute write-back port`、`memory write-back port`、`LD unit`、`MUL unit`） | 1 | 2 | 2 | 2 | - | 番号の順 | 4 | 19 | 1.00 | 0 |
| `product8`（i） | rp4 | 1（`compute slot`、`memory slot`、`compute write-back port`、`memory write-back port`、`LD unit`、`MUL unit`） | 1 | 2 | 2 | 2 | - | 番号の順 | 4 | 19 | 1.00 | 0 |
| `product8`（i） | mac3 | 1（`compute slot`、`memory slot`、`compute write-back port`、`memory write-back port`、`LD unit`、`MUL unit`） | 1 | 2 | 2 | 2 | - | 番号の順 | 4 | 19 | 1.00 | 0 |
| `dot8`（i） | mvp-0 | 2（`compute slot`、`memory slot`、`compute write-back port`、`memory write-back port`、`LD unit`） | 2 | 1 | 2 | 3 | II 2: (a) 予約表なし（slot + write-back port (L5)） | 番号の順 | 6 | 28 | 1.00 | - |
| `dot8`（i） | mvp-1 | 2（`memory slot`、`memory write-back port`、`LD unit`） | 2 | 2 | 2 | 2 | - | 番号の順 | 5 | 21 | 0.75 | 0 |
| `dot8`（i） | rp4 | 2（`memory slot`、`memory write-back port`、`LD unit`） | 2 | 2 | 2 | 2 | - | 番号の順 | 5 | 21 | 0.75 | 0 |
| `dot8`（i） | mac3 | 2（`compute slot`、`memory slot`、`compute write-back port`、`memory write-back port`、`LD unit`） | 2 | 1 | 2 | 3 | II 2: (a) 予約表なし（slot + write-back port (L5)） | 番号の順 | 6 | 28 | 1.00 | 0 |

- MII などは、スケジュールしたグラフ（融合したときは融合したグラフ）の値である。`third read port` は、`read_ports` 3 の構成で MAC と ST が使う資源（L11）で、rp4 と mvp-0 には無い
- 理由の (a) は、1反復の演算が II 行の予約表（スロット、演算器の II（L9）、書戻しポート（L5）、3本目の読出しポート（L11））に収まらないこと、(b) は、命令列の同時に生きる値がレジスタ数を超えること（[MODULO.md](MODULO.md)）
- 配置は、選んだ II の予約表への置き方である。「番号の順」は後戻りなしの配置、「先読み」は番号の順で置けないときに、残りのノードがまだ予約表に収まる時刻だけを使う配置（[docs/COMPILER.md](../docs/COMPILER.md)「Modulo Scheduling」）
- 1反復の長さは、配置した1反復の最初の発行から、最後の演算が終わる（latency と演算器の II の大きい方）までのサイクル数
- MAC と ST の bundle の括弧は、その ST がループのどこにあるか（反復の中、ループの後）である

各記録の全項目は `metrics/<kernel>_<loop>_modulo<構成>.json`（`vliw-metrics/2`、`ii` を含む）、命令列と入力・期待値は `cases/` の同じ名前にある。構成の接尾辞は、mvp-1 が `_mac`、rp4 が `_mac_rp4`、mac3 が `_mac3` で、mvp-0 の記録は [MODULO.md](MODULO.md) の4つである。

### 読出しポートの制約（mvp-1 と rp4）

| ループ | II（mvp-1 / rp4） | cycles（mvp-1 / rp4） | 差（mvp-1 − rp4） | 配置（mvp-1 / rp4） | 1反復の長さ（mvp-1 / rp4） | MAC と ST の bundle（rp4） | L11 の違反（rp4 の命令列を mvp-1 で検査） |
|---|---|---|---|---|---|---|---|
| `saxpy8`（i） | 3 / 3 | 29 / 29 | 0 | 番号の順 / 番号の順 | 6 / 6 | 0 | 0 |
| `fir4x8`（i） | 4 / 4 | 46 / 43 | 3 | 先読み / 番号の順 | 14 / 10 | 7（反復の中 7） | 7 |
| `matmul3`（i） | 9 / 9 | 38 / 45 | -7 | 先読み / 番号の順 | 15 / 23 | 1（反復の中 1） | 1 |
| `matmul3`（m） | 9 / 9 | 40 / 34 | 6 | 番号の順 / 番号の順 | 13 / 13 | 6（ループの後 6） | 6 |
| `product8`（i） | 2 / 2 | 19 / 19 | 0 | 番号の順 / 番号の順 | 4 / 4 | 0 | 0 |
| `dot8`（i） | 2 / 2 | 21 / 21 | 0 | 番号の順 / 番号の順 | 5 / 5 | 0 | 0 |

mvp-1 と rp4 は同じグラフをスケジュールする。mvp-1 の方が cycles が多いループは `fir4x8`（i）（3 cycles）、`matmul3`（m）（6 cycles）である。rp4 では、上の表の括弧に示した場所の ST が、MAC と同じ bundle に入る。`matmul3`（i）は、同じ II 9 で rp4 の方が 7 cycles 多い。rp4 では番号の順の配置で置け、1反復が 23 サイクルになる。mvp-1 では番号の順では置けず、先読みの配置で置け、1反復が 15 サイクルになる。読出しポートの制約の効果ではなく、配置の手順の効果である。Modulo Scheduling は II = MII から順に試し、最初に命令列ができた II を使い、配置は番号の順に後戻りなしで、番号の順で置けないときだけ先読みする。どちらも cycles を最小にしない（[ADR-0015](../docs/DECISIONS.md#adr-0015-modulo-scheduling-は-ii-を下限から順に試し最初に命令列ができた-ii-を使う)）。

## 注

### ADR-0018 の見積りと測定

ADR-0018 の見積りは、#69 の提案のときに、使い捨ての実装（`Dag.build` と `ListScheduler` を移したもの）で求めた値である。条件は16レジスタで、rematerialization を入れていない。ADR-0018 は、このうち `fir4x8` と `matmul3` の mvp-1 と rp4 の値を書いた。見積りと比べるのは、LD を複製しない行の最小である。どれも見積りと同じである。

方式ごとに cycles 最小（vliw）:

| カーネル | 構成 | 見積り | 測定 | LD を複製しない行の最小（試行） |
|---|---|---|---|---|
| `saxpy8` | mvp-0 | 26 | 26 | 26（critical-path, no register limit） |
| `saxpy8` | mvp-1 | 27 | 27 | 27（critical-path, no register limit） |
| `saxpy8` | rp4 | 26 | 26 | 26（critical-path, no register limit） |
| `saxpy8` | mac3 | 27 | 27 | 27（critical-path, no register limit） |
| `fir4x8` | mvp-0 | 65 | 65 | 65（program-order, 16 registers, load reserve 4） |
| `fir4x8` | mvp-1 | 40 | 40 | 40（program-order, no register limit） |
| `fir4x8` | rp4 | 40 | 38（LD 27） | 40（program-order, no register limit） |
| `fir4x8` | mac3 | 46 | 44（LD 27） | 46（critical-path, 16 registers, load reserve 0） |
| `matmul3` | mvp-0 | 56 | 56 | 56（critical-path, 16 registers, load reserve 1） |
| `matmul3` | mvp-1 | 39 | 39 | 39（program-order, 16 registers, load reserve 0） |
| `matmul3` | rp4 | 33 | 33 | 33（critical-path, 16 registers, load reserve 0） |
| `matmul3` | mac3 | 40 | 40 | 40（critical-path, 16 registers, load reserve 0） |

戦略を固定した系列の行（vliw）:

| カーネル | 試行 | 見積り（mvp-0 / rp4 / mvp-1） | 測定（mvp-0 / rp4 / mvp-1） | MAC と ST の bundle（rp4。見積り / 測定） |
|---|---|---|---|---|
| `matmul3` | critical-path, 16 registers, load reserve 1 | 56 / 33 / 40 | 56 / 33 / 40 | 7 / 7 |
| `fir4x8` | critical-path, 16 registers, load reserve 0 | - / 40 / 46 | 不成立: 上限の下で行き詰まる / 40 / 46 | 6 / 6 |

ループの ResMII:

| ループ | 見積り（mvp-0 → mvp-1） | 測定（mvp-0 → mvp-1） |
|---|---|---|
| `fir4x8`（i） | 7 → 4 | 7 → 4 |
| `matmul3`（i） | 15 → 9 | 15 → 9 |
| `matmul3`（m） | 18 → 9 | 18 → 9 |

- rp4 の `fir4x8`（vliw）の 38 cycles は、LD を複製した試行（critical-path, 16 registers, load reserve 1、LD 27）である。複製しない行の最小は 40 cycles（program-order, no register limit）
- mac3 の `fir4x8`（vliw）の 44 cycles は、LD を複製した試行（critical-path, 16 registers, load reserve 1、LD 27）である。複製しない行の最小は 46 cycles（critical-path, 16 registers, load reserve 0）
- mac3 の `fir4x8`（single）の 58 cycles は、LD を複製した試行（critical-path, 16 registers, load reserve 0, single issue、LD 17）である。複製しない行の最小は 66 cycles（program-order, no register limit, single issue）

### 測っていないもの

- cycle だけを比べている。MAC の演算器の増分、4本目の読出しポートの増分（3R/2W と 4R/2W の面積）、ポート2のアドレスの選択が Fmax に効くかは、#57（Phase B）で別々に測る。それまで、cycle の比を実時間の比と読まない
- rp4 の RTL は無い（#57）。rp4 の値は参照モデルと verifier だけで確かめている
- `read_ports` 3 の損失を減らすスケジューラの工夫（ST のために MAC を遅らせる、など）は入れていない（ADR-0019）
