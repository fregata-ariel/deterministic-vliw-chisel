# 命令生成

カーネルDSL（[DSL.md](DSL.md)）から命令列を作る処理の仕様。実装は sbtプロジェクト `compiler`。順序は次のとおりで、各段の出力が次の段の入力になる。

| 段 | 入力 → 出力 | 実装 | Issue |
|---|---|---|---|
| メモリ配置・lowering | `Kernel` → `LoweredKernel`（アドレス単位の式） | `vliw.compiler.dsl` | #47 |
| DAG | `LoweredKernel` → `Dag` | `vliw.compiler.dag` | #48 |
| MAC への融合 | `Dag` → `Dag`（machine に MAC があるときだけ。MUL＋ADD を MAC に） | `vliw.compiler.dag.Fusion` | #77 |
| 組への詰め込み | `Dag` → `Packing`（ISA mvp-2 の machine だけ。値を W の組に詰めた DAG） | `vliw.compiler.dag.Pack` | #110 |
| list scheduling | `Dag` → `Schedule`（各ノードの発行サイクルと HALT。値は仮想レジスタ） | `vliw.compiler.schedule` | #49 |
| register allocation | `Dag` → `Allocated`（スケジュールと物理レジスタ、bundle 列） | `vliw.compiler.regalloc` | #50 |
| コード生成と検証 | → 命令語（verifier の最終検査に通ったもの）、`.hex`、テストケース | `vliw.compiler.KernelCompiler` | #51 |

Phase 3 の Modulo Scheduling（#68）の入力になる、ループ構造を保つ IR は「[ループ IR](#ループ-ir)」の節に（#76）、そのスケジューリングは「[Modulo Scheduling](#modulo-scheduling)」の節にある（#68）。

ISA `mvp-1` の MAC への融合（MUL＋ADD）と、読出しポートの制約（規則 L11）は、直線のカーネルについて「[MAC への融合](#mac-への融合)」と「[list scheduling](#list-scheduling)」の節に、ループについて「[ループの MAC への融合](#ループの-mac-への融合)」と「[Modulo Scheduling](#modulo-scheduling)」の節にある（#77）。規則は [ADR-0019](DECISIONS.md)（融合）と ADR-0018（L11）に書いた。mvp-0 の machine では、どの段の結果も変わらない。

ISA `mvp-2`（W）の組への詰め込み、組を考えたスケジュールと割り当て、スカラーへの fallback は「[ISA mvp-2（W）](#isa-mvp-2w)」の節にある（#110 の第4段。list scheduling だけ）。mvp-0 と mvp-1 の machine では、どの段の結果も変わらない。

## DAG

`Dag.build(machine, writes)`（または `Dag.of(lowered, machine)`）は、lowering した式から演算の DAG を作る。MAC への融合は、その後の別の段である（下の「[MAC への融合](#mac-への融合)」）。

### ノード

| ノード | 内容 |
|---|---|
| `Load(addr)` | LD。アドレスは即値なので静的に決まる |
| `Compute(op, a, b)` | ADD・SUB・MUL。オペランドはノード番号 |
| `Mac(a, b, c)` | MAC（ISA mvp-1）。値は `a × b + c` の下位32bit。DSL には無く、融合（下の「MAC への融合」）だけが作る |
| `Store(addr, value)` | ST。値はノード番号 |

値はまだ仮想値で、レジスタは決めていない。等しい値は1つのノードにまとめる（値番号付け）。

- LD は「アドレスと、そのアドレスへのそれまでの ST の数」で識別する。同じ語を2回読んでも LD は1つで、ST の後に読めば別の LD になる。レジスタが足りないときに作る LD の複製（下の「rematerialization」）は、スケジュールした DAG（`Dag.split`）にだけ現れる
- 演算は「演算とオペランドのノード」で識別する。交換則による正規化はしない

ノード番号は位相順で、辺は常に小さい番号から大きい番号へ向かう。

### 辺

辺 `(from, to, distance)` は `issue(to) >= issue(from) + distance` を意味する。距離は machine description のレイテンシーから取る。

| 種類 | 条件 | 距離 | 根拠（[ISA.md](ISA.md)） |
|---|---|---|---|
| データ依存 | 生産者の結果を消費者が読む（ST のソースを含む） | 生産者の L | L3、L8 |
| ST → LD | 同じアドレスへの ST の後の LD | ST の L | L8、L10 |
| LD → ST | 同じアドレスの古い値を読む LD の後の ST | 0 | ソースは発行サイクルで読むので、同じサイクルの ST の前の値を読む。実際には Memory スロットが1つなので1以上離れる |
| ST → ST | 同じアドレスへの2つの ST | 1 | ST のレイテンシーは共通なので、発行順に commit する |

現在の DSL は入力が読出し専用、出力が書込み専用なので、メモリ順序の辺は生じない。lowering 後の一般の形に対して正しく作り、手作りの例でテストする。ループ IR（下記）も同じ制約で、反復をまたぐ値はメモリではなくレジスタの状態で渡すので、メモリ順序の辺は生じない（[ADR-0014](DECISIONS.md#adr-0014-ループ-ir-では状態をレジスタで渡しループ不変の値と後の反復が再び読む語をレジスタに保持する)）。

### 下限と優先度

| 値 | 定義 |
|---|---|
| `earliest(n)` | 資源の制約を無視した最早発行サイクル（ASAP） |
| `height(n)` | n の発行から、最後の操作の完了までの最長経路（n 自身のレイテンシーを含む）。list scheduling の優先度に使う |
| `criticalPath` | `max(earliest(n) + L(n))`。全操作が完了する最早の境界 |
| `cyclesLowerBound` | `criticalPath + 1`。HALT は全操作の完了後に発行でき（L6）、`cycles = h + 1` |
| `resourceLowerBound` | スロット数による下限。Compute・Memory はサイクル当たり1操作で、HALT は Compute スロットを1つ使う |

既定の構成（mvp-0）での値は次のとおり。

| カーネル | LD | MUL | ADD | ST | `cyclesLowerBound` | `resourceLowerBound` |
|---|---|---|---|---|---|---|
| `saxpy8` | 17 | 8 | 8 | 8 | 7 | 26 |
| `fir4x8` | 15 | 32 | 24 | 8 | 9 | 57 |
| `matmul3` | 18 | 27 | 18 | 9 | 8 | 46 |

3カーネルとも資源の下限の方が大きく、スロット数が性能を決める。

### MAC への融合

machine に MAC があるとき（ISA mvp-1、`MachineDescription.hasMac`）、`KernelCompiler.prepare` は DAG を作った後、スケジュールの前に `Fusion.fuse` で MUL と ADD を MAC にする（[ADR-0019](DECISIONS.md)）。MAC のない machine では DAG を変えない。

| 形 | 扱い |
|---|---|
| MUL m の読む側が ADD a だけ | `Mac(m.a, m.b, a のもう1つのオペランド)` にする。m がどちらのオペランドでもよい |
| m に、ほかの読む側がある（ADD・SUB・MUL・ST のどれでも） | 融合しない。値を計算し直さない |
| `ADD(m, m)` | 融合しない |
| `ADD(m1, m2)` で、両方を融合できる | 後の MUL（番号の大きい方）を融合し、先の MUL を足す数に残す |
| SUB | 融合しない |

- 判定は、融合する前の DAG で ADD ごとに行う。`Fusion.pairs` が融合する組（MUL, ADD）を返す。融合した DAG に、融合できる組は残らない
- MAC は ADD の番号に入り、MUL を除いた後の番号を1つずつ詰める。番号は位相順のままである。データ依存の辺はオペランドから作り直す（MAC からの辺の距離は MAC の L）。メモリ順序の辺は LD と ST の間にしかないので、端の番号を付け替えるだけである
- MAC の値は `ADD(MUL(a, b), c)` と同じで、乗算の数は増えない
- 融合は戦略の次元にしない（rematerialization と同じ）。DAG はカーネルと machine だけで決まり、全ての試行が同じ DAG を使う。DSL に MAC は無いので、`Dag.macs`（MAC の数）が融合した組の数である

ベンチマークでは、全ての組を融合する。`fir4x8` と `matmul3` は、各出力の和の最初の MUL を足す数に残し、残りの MUL が MAC になる。

| カーネル | 融合した組 | LD | MUL | MAC | ST | `cyclesLowerBound`（MAC の L 2 / 3） | `resourceLowerBound` |
|---|---|---|---|---|---|---|---|
| `saxpy8` | 8 | 17 | 0 | 8 | 8 | 6 / 7 | 26 |
| `fir4x8` | 24 | 15 | 8 | 24 | 8 | 12 / 15 | 33 |
| `matmul3` | 18 | 18 | 9 | 18 | 9 | 10 / 12 | 28 |

Compute の演算が減るので、`fir4x8` と `matmul3` のスロットの下限は 57 → 33、46 → 28 になる。依存の鎖は延びる（`fir4x8` は 9 → 12）。

### テスト

`compiler/src/test/.../dag/DagSpec`：ISA の命令例の DAG（ノード・辺・距離・下限）、machine description による距離の変化、値番号付け、メモリ順序の4種類の辺、ベンチマークのノード数と下限、辺が ASAP で満たされること、DAG をノード順に実行した結果が lowering の評価と一致すること。

`compiler/src/test/.../dag/FusionSpec`（#77）
- 手作りの例: ISA の命令例（ノード・辺・距離・下限。MAC の L 3 の構成も）、ほかの読む側がある MUL（ADD・ST・SUB・MUL）、SUB と `ADD(m, m)`、`ADD(MUL, MUL)` で後の MUL を融合すること（後の MUL を ST も読むときは先の MUL）、MAC の鎖と MAC の積の融合、MAC のない machine で DAG が変わらないこと
- MUL を除いた後も、メモリ順序の辺（ST → LD・LD → ST・ST → ST）の端が正しいこと（手作りの例と、6語を読み書きする乱数の500の書込みの列）
- 乱数の書込みの列、ベンチマーク、乱数の DSL カーネル（`RandomKernels`、`crosscheck/RandomKernelSpec` と同じ作り方）で、組がノードのオペランドから数えた規則と一致すること、データ依存の辺がオペランドと一致し、メモリ順序の辺が規則どおりであること、融合した DAG を辺を守る乱数の順序で実行した結果が、融合しない DAG と lowering の評価に一致すること
- 上の表の値

## list scheduling

`ListScheduler.schedule(dag, priority)` は、サイクルの順に、準備のできたノードを優先度の高い順に置く。結果の `Schedule` は、各ノードの発行サイクルと HALT のサイクルを持つ。値はまだ仮想レジスタで、ノード n の結果を仮想レジスタ n とする（レジスタ割り当ては #50）。

### 守る制約

| 制約 | 方法 | 規則（[ISA.md](ISA.md)） |
|---|---|---|
| 依存 | 辺 `(from, to, d)` について、`to` は `issue(from) + d` 以降 | L3、L8、L10 |
| スロット | 各サイクルに Compute・Memory それぞれ高々1つ。MAC は Compute | 構造 |
| 書戻しポート | ADD/SUB/MUL/MAC は Compute ポートを、LD は Memory ポートを、境界 E_(s+L) に予約する。予約済みの境界を使う配置はしない（例: MUL(s) の後の ADD(s+1)） | L5 |
| II | 同じ演算の発行は II 以上空ける（MAC の L と II は `operations.MAC` から取る） | L9 |
| 読出しポート | bundle のオペランドの欄の数（ADD/SUB/MUL は 2、MAC は 3、ST は 1、LD は 0）の和を `register_file.read_ports` 以下にする | L11 |
| HALT | 全操作が完了する境界 `max(s+L)` に置く。HALT の bundle には他の操作を置かない | L2、L6 |

同じレジスタへの書込み区間の重複（L4）と、書込み中のレジスタの読出し（L3 のうち、レジスタの再利用によるもの）は、レジスタ割り当て（#50）で扱う。レジスタの上限を与えると、生きている値の数も制約にする（下の「register allocation」）。

読出しポートは、各サイクルで Compute を先に埋め、次に Memory を埋めるときに数える。`read_ports` が3の mvp-1 では、MAC を置いた bundle に ST を置けず、ST は次のサイクル以降に待つ。その bundle の Memory には、LD なら置ける。この素朴な方法に固定し、ST のために MAC を遅らせる工夫はしない（[ADR-0019](DECISIONS.md)）。mvp-0 の演算は bundle 当たり高々3つを読むので、mvp-0 では制約にならない。読出しポートの検査は、レジスタの上限以外の制約を調べる部分（`ListScheduler` の `free`）に入れる。このため、読出しポートだけで空く Memory スロットは、rematerialization の「詰まり」に数えない（下の「rematerialization」）。

### 制約で待ったサイクルの数

比較の記録のために、list scheduling は `Schedule` に2つの数を残す（#77）。どちらも置き方を変えない。Modulo Scheduling の `Schedule` では0である。

| 値 | 数えるサイクル |
|---|---|
| `heldByReadPorts` | 準備ができた ST（mvp-2 では ST.W とレーンの ST も）が、読出しポート（L11）以外の制約（依存・スロット・書戻しポート・II・レジスタの上限）を全て満たすのに、bundle の読出しが `read_ports` を超えるので置けなかったサイクル |
| `heldByWritePort` | 準備ができた Compute のノードが、書戻しポート（L5）以外の制約を全て満たすのに、その境界のポートが予約済みなので置けなかったサイクル |

- 各スロットはサイクルごとに1回だけ埋めるので、どちらもサイクル当たり高々1つ増える。数えるのは、そのスロットを埋める直前の状態である
- 待ったノードの代わりに、同じスロットに別のノードを置いたサイクルも数える（例: ST が待ち、LD を置いた）
- mvp-0 では `heldByReadPorts` は常に0である

### 優先度

| 方式 | 内容 |
|---|---|
| `CriticalPath`（既定） | DAG の高さ（完了までの最長経路）が大きい順 |
| `ProgramOrder` | ノード番号（lowering の順）の順。比較用 |

同じ優先度なら、ノード番号の小さい方を先に置く。結果は決定的である。

`singleIssue` を指定すると、1サイクルに1操作だけを置く（Compute と Memory のどちらか）。単一発行ベースライン（[benchmarks/README.md](../benchmarks/README.md)）に使う。CLI では `--single-issue`。

### 結果（既定の構成）

| カーネル | `CriticalPath` | `ProgramOrder` | 下限（依存 / スロット） |
|---|---|---|---|
| `saxpy8` | 26 | 28 | 7 / 26 |
| `fir4x8` | 63 | 63 | 9 / 57 |
| `matmul3` | 52 | 53 | 8 / 46 |

`saxpy8` は Memory スロットが律速で、スロットの下限に一致する。`fir4x8` と `matmul3` は Compute スロットが律速である。下限との差は、次の3つでほぼ説明できる。

- 開始: 最初の MUL は2つの LD を待つ（3サイクル）
- 終了: 最後の ADD の後の ST と HALT
- Compute スロットの空き（高々2つ）: MUL(s) と ADD(s+1) は同じ境界に Compute ポートを使う（L5）

スロットの下限は、これらを数えていない。

### 結果（mvp-1、レジスタの上限なし）

cycles と、括弧の中に `heldByReadPorts` / `heldByWritePort` を示す。mvp-1 の3つの構成は、`config/machine-mvp1.json`（`read_ports` 3、MAC の L 2）、`tests/machines/mvp1_rp4.json`（`read_ports` 4）、`tests/machines/mvp1_mac3.json`（MAC の L 3）である。

| 構成 | 優先度 | `saxpy8` | `fir4x8` | `matmul3` |
|---|---|---|---|---|
| mvp-0（既定） | `CriticalPath` | 26（0 / 1） | 63（0 / 30） | 52（0 / 25） |
| | `ProgramOrder` | 28（0 / 1） | 63（0 / 30） | 53（0 / 24） |
| mvp-1 | `CriticalPath` | 27（7 / 0） | 44（6 / 0） | 40（7 / 0） |
| | `ProgramOrder` | 28（5 / 0） | 40（0 / 0） | 39（0 / 0） |
| `read_ports` 4 | `CriticalPath` | 26（0 / 0） | 38（0 / 0） | 33（0 / 0） |
| | `ProgramOrder` | 28（0 / 0） | 40（0 / 0） | 39（0 / 0） |
| MAC の L 3 | `CriticalPath` | 27（6 / 0） | 44（5 / 0） | 40（6 / 0） |
| | `ProgramOrder` | 29（2 / 0） | 53（0 / 19） | 45（0 / 11） |

- MAC の L が2では、`fir4x8` と `matmul3` の Compute の演算は MUL と MAC だけで、どちらも L 2 なので、書戻しポートで待つことがない（ADR-0018）。L 3 では、MAC(s) と MUL(s+1) が同じ境界を使う
- `read_ports` 3 では、`CriticalPath` は MAC の後の ST を待たせ、`read_ports` 4 より `fir4x8` で6、`matmul3` で7サイクル長い。`saxpy8` は Memory スロットが律速で、ST が MAC と重なれないので1サイクル長い
- 16本のレジスタでの全ての試行（mvp-0 との比、`read_ports` 3 と 4 の行ごとの差と、制約が当たった bundle の数）は [benchmarks/MAC.md](../benchmarks/MAC.md) にある

### テスト

- `compiler/.../schedule/ScheduleSpec`: スケジューラとは独立に書いた検査で、依存・スロット・書戻しポート・II・読出しポート・HALT の位置を確かめる。対象はベンチマーク3つ × 構成3つ（既定、`slow_units`、`ii_units`）× 優先度2つ。cycles が両方の下限以上であることと、上の表の値も確かめる
- 同じ `ScheduleSpec` で、mvp-1（#77）: 3つの構成の、上限なしと上限付き（16本、reserve 0〜8）の全スケジュールが上の検査に合格し、`read_ports` 3 では MAC と ST が同じ bundle に無く、4 ではあること。手作りの例で、読出しポートで待つ ST（`read_ports` 3 と 4 の発行サイクル）、書戻しポートで待つ Compute のノード（ADD、MAC の L 3 での MUL）と、その数。2つの数が、発行サイクルだけから数え直した値と一致すること（6つの構成のベンチマークと、乱数の DSL カーネル）。MAC の II（L9）。上の表の値
- 命令列にした結果の確認は、レジスタ割り当ての後で行う（下の「register allocation」のテスト）

## register allocation

`RegisterAllocator.scheduleAndAllocate(dag)` は、スケジュールと物理レジスタの割り当てを行い、命令列（`Allocation.bundles`、最後が HALT）を作る。spill はしない。上限付きの試行が行き詰まるときは、入力・スカラー・定数の LD を、読む側の一部のために再び発行する（下の「rematerialization」、#70）。

### 入出力とレジスタ

レジスタは入力にも出力にも使わない。入力・スカラー・定数は初期化済みの Scratchpad から LD し、出力は ST する（[DSL.md](DSL.md)「メモリ配置」）。R_in は空で、実行後のレジスタの値は規定しない。

### 生存区間

値 v（ST 以外のノードの結果）のレジスタは、v を書く操作の発行 s_v から、v を最後に読む操作の発行 u_v までの半開区間 `[s_v, u_v)` を占める。別の値の書込みは、u_v のサイクルから同じレジスタに置ける。

- L3: v の読出しはすべて u_v 以前で、新しい書込みの発行サイクル以前なので、書込み中の読出しにならない（u_v での読出しは発行サイクル自身の読出しで、旧値 v を読む）
- L4: v の書込み区間 `[s_v, s_v + L)` は、データ依存により u_v までに終わる
- 値: 新しい値は u_v + 1 以降に見えるので、v の読出しはすべて v を読む

MAC は3つのオペランドを発行サイクルに読むので、3つとも読む側に数える（同じ値を2つの欄で読んでも、読む側は1つ）。

### 割り当て

区間を開始の順に並べ、そのとき空いている最小番号のレジスタに割り当てる（区間グラフの貪欲彩色）。必要なレジスタの数は、同時に生きている値の最大数に等しい。ISA mvp-2 の組のある DAG の割り当ては「[ISA mvp-2（W）](#isa-mvp-2w)」の節にあり、組の無い DAG（mvp-0・mvp-1 と、mvp-2 の fallback）はこの節のとおりである。

### 足りない場合の再スケジュール

最大数がレジスタ数を超えるときは、レジスタの上限付きで list scheduling をやり直す。上限付きのスケジューラは次のように置く。

- 値を作るノードは、置いた後に生きている値の数が上限以下のときだけ置く。そのノードが最後の読出しになるオペランドは、同じサイクルに解放されるものとして数える
- LD は、さらに `loadReserve` 本を空けておけるときだけ置く。読み込んだオペランドばかりでレジスタが埋まり、演算の結果を置けなくなることを防ぐ
- 生きている値が上限に近いときは、レジスタを多く解放するノードを優先する
- しばらくどのノードも置けなければ、その試行は失敗とする

試行は、各優先度の上限なし、次に各優先度で `loadReserve` を 0 からレジスタ数の半分まで変えたもの（`RegisterAllocator.attempts`）。成功した試行のうち cycles が最小のものを選び、同じならば LD の少ない方（複製のない方）を、それも同じならば先の試行を選ぶ。結果は決定的である。上限付きで成功したスケジュールは、生きている値の数が上限以下なので、割り当ては必ず成功する（組の無い DAG で。組のある DAG では、整列した組が空かずに失敗し得る。「[ISA mvp-2（W）](#isa-mvp-2w)」）。

1つの試行だけを行うのは `RegisterAllocator.run(dag, attempt)` で、収まらない理由（`NoFit`）を返す。上限なしで同時に生きる値がレジスタ数を超える（`TooManyLive`）か、上限付きのスケジューラが、複製（下記）を作っても行き詰まる（`Stuck`）かのどちらかである。ISA mvp-2 の組のある DAG では、整列した組が空かない `NoAlignedPair` もある（上限なしでも上限付きでも。「[ISA mvp-2（W）](#isa-mvp-2w)」）。ベンチマークの「戦略を固定した系列」（[benchmarks/README.md](../benchmarks/README.md)）は、全ての試行を `run` で行い、方式ごとの結果を並べる。

### rematerialization

上限付きの試行が、どの語も1回だけ読むままでは行き詰まるとき、その試行を最初からやり直し、LD の複製を作りながら置く（`Rematerialization`、[ADR-0016](DECISIONS.md#adr-0016-rematerialization-は行き詰まる試行でだけ書込みのない語の-ld-をレジスタの詰まりで複製する)）。行き詰まらない試行は、複製を作らず、今までと同じスケジュールになる。

| 項目 | 内容 |
|---|---|
| 複製してよい LD | DAG のどの ST も書かない語の LD（`Dag.rematerializable`）。今の DSL では入力・スカラー・定数の全て。ST のある語の LD は複製しない |
| 止める点 | レジスタの上限のために、準備ができて他の制約（依存・スロット・書戻しポート・II・読出しポート）を全て満たすノードがあるのに、スロットが空く cycle（詰まり） |
| 候補 | 置いた LD のうち、読む側に、置いたものと置いていないものの両方があるもの。複製は、置いていない読む側のための新しい LD で、元の LD のレジスタは置いた読む側の最後の発行で空く |
| 選ぶ候補 | 置いていない読む側の中で優先度が最も高いものの優先度が、最も低い候補（次の使用が最も遠い）。同じならば番号の小さい方 |
| 次 | 置いていない読む側を複製に付け替えた DAG（`Dag.split`）で、試行を最初からやり直し、前に止まった cycle 以降の最初の詰まりで止まる（それより前の詰まりは、候補があっても見送る）。候補がなければ、その cycle の詰まりは見送って先に進む。行き詰まって候補もなければ `Stuck` |

- DAG: 複製は、付け替える読む側の最小の番号に入り、そこから後の番号を1つずつ増やす。番号は位相順のままである。複製の辺は、読む側へのデータ依存の辺だけである（書込みのない語なので、メモリ順序の辺は生じない）。MAC は、3つのオペランドのどれが LD を読んでいても付け替える
- 終わること: 複製ごとに LD が1つ増え、各語の LD の数は読む側の数以下である。詰まりを探し始める cycle は、複製の後は前に止まった cycle、見送りの後はその次の cycle で、減らない
- 時間: 複製と見送りのたびに試行を最初からやり直すので、1つの試行の時間は、複製と見送りの数 × スケジュールの長さに比例する。3カーネルは全ての試行を合わせて数秒である。命令領域に収まらない大きなカーネル（例えば `matmul(6)`）では数十秒かかり、最後は verifier が命令領域の超過（L2）で拒否する
- 結果: 上限付きで成功したスケジュールなので、割り当ては必ず成功する。`Allocated.dag` は複製を含む DAG、`CompiledKernel.dag` はカーネルの DAG（下限の表はこちら）
- 選び方の規則（cycles、LD の数、試行の順）により、複製なしで収まる構成の選択は、複製で cycles が真に減るときだけ変わる

### 結果（16 レジスタ）

| 構成 | カーネル | cycles | 選ばれた試行 | 使ったレジスタ |
|---|---|---|---|---|
| 既定 | `saxpy8` | 26 | critical-path、上限なし | 11 |
| 既定 | `fir4x8` | 65 | program-order、上限16、reserve 4 | 16 |
| 既定 | `matmul3` | 56 | critical-path、上限16、reserve 1 | 16 |
| `slow_units` | `saxpy8` / `fir4x8` / `matmul3` | 27 / 70 / 57 | `matmul3` は critical-path、上限16、reserve 0（LD 34） | 13 / 16 / 16 |
| `ii_units` | `saxpy8` / `fir4x8` / `matmul3` | 53 / 100 / 88 | | 9 / 13 / 16 |

既定の構成では、上限なしのスケジュール（63、52 cycles）に比べ、`fir4x8` は2、`matmul3` は4サイクル長い。上限なしでは、それぞれ33と28の値が同時に生きる。

`slow_units` の `matmul3` は、複製なしでは 60 cycles（program-order、reserve 1）で、複製なしでは行き詰まる critical-path、reserve 0 の試行が、複製で 57 cycles になる。

### 結果（8・12・16 レジスタ、既定のレイテンシー）

| レジスタ | `saxpy8` | `fir4x8` | `matmul3` |
|---|---|---|---|
| 8（`tests/machines/regs8.json`） | 28（LD 17） | 70（LD 55） | 61（LD 44） |
| 12（`tests/machines/regs12.json`） | 26（LD 17） | 66（LD 45） | 55（LD 37） |
| 16（既定） | 26（LD 17） | 65（LD 15） | 56（LD 18） |

vliw の、方式ごとに cycles 最小の値である。複製なしでは、12本の `matmul3`、8本の `fir4x8` と `matmul3` はどの試行でも収まらず、12本の `fir4x8` は 69 cycles だった。`matmul3` は12本の方が16本より速い。12本で選ばれる program-order、reserve 0 の試行は、複製なしでは行き詰まり、複製で 55 cycles（LD 37）になる。16本では同じ試行が複製なしで収まる（57 cycles）ので、複製を作らない（常に複製を許せば 54 cycles。ADR-0016）。16本でも、行き詰まる試行（critical-path の reserve 0・4・5、program-order の reserve 4〜8）には複製が現れるが、どれも 56 cycles より速くない。単一発行と、戦略を固定した系列の比較は [benchmarks/REGISTERS.md](../benchmarks/REGISTERS.md) にある。

### 結果（mvp-1、8・12・16 レジスタ、#77）

方式ごとに cycles 最小の値で、括弧の中は LD の数である。mvp-0 の行は上の表と同じ。レジスタ数だけを変えた構成で測る。

| 構成 | レジスタ | `saxpy8` | `fir4x8` | `matmul3` |
|---|---|---|---|---|
| mvp-0（既定） | 16 / 12 / 8 | 26 / 26 / 28 | 65 / 66 / 70 | 56 / 55 / 61 |
| mvp-1（`read_ports` 3） | 16 / 12 / 8 | 27 / 27 / 27 | 40 / 40 / 40（LD 15 / 15 / 29） | 39 / 39 / 49（LD 18 / 24 / 32） |
| `read_ports` 4 | 16 / 12 / 8 | 26 / 26 / 26 | 38 / 40 / 40（LD 27 / 15 / 29） | 33 / 38 / 49（LD 18 / 24 / 32） |
| MAC の L 3 | 16 / 12 / 8 | 27 / 27 / 27 | 44 / 44 / 53（LD 27 / 32 / 15） | 40 / 44 / 55（LD 18 / 32 / 38） |

`saxpy8` の LD はどれも17である。16本で選ばれた試行は次のとおり。

| 構成 | `saxpy8` | `fir4x8` | `matmul3` |
|---|---|---|---|
| mvp-1 | critical-path、上限なし | program-order、上限なし | program-order、上限16、reserve 0 |
| `read_ports` 4 | critical-path、上限なし | critical-path、上限16、reserve 1（複製あり） | critical-path、上限16、reserve 0 |
| MAC の L 3 | critical-path、上限なし | critical-path、上限16、reserve 1（複製あり） | critical-path、上限16、reserve 0 |

- 16本では、ADR-0018 の見積り（`fir4x8` 65 → 40、`matmul3` 56 → 39（`read_ports` 3）・33（4）、`saxpy8` 26 → 27（3）・26（4））と一致する。ただし `read_ports` 4 の `fir4x8` は、複製（rematerialization）のある試行が 38 cycles になる。複製なしでは 40 cycles である（MAC の L 3 では 44 と 46）
- MAC で同時に生きる値が減る。上限なしの `fir4x8` は、critical-path で20、program-order で13（mvp-0 では critical-path で33、program-order で36）。mvp-1 の `fir4x8` は program-order の上限なしで16本に収まり、8本でも 40 cycles である
- 選ばれた試行の `heldByReadPorts` は、mvp-1 の16本で `saxpy8` 7、`fir4x8` と `matmul3` 0 である。`read_ports` 3 の損失は、選ばれた試行の cycles では `saxpy8` 1、`fir4x8` 2、`matmul3` 6 サイクルになる

### 制約

複製できるのは、置いた読む側のある LD だけである。読む側をまだ1つも置いていない LD がレジスタを占めて行き詰まる試行は、複製でも収まらない（例えば8本の `saxpy8` の critical-path の試行）。3本では、`fir4x8` と `matmul3` はどの試行でも収まらない（2つの LD の MUL と、和の値で3本を使う）。このときは理由付きのエラーにする。spill（計算した値を ST して後で LD する）はしない。

### テスト

- `compiler/.../regalloc/RegisterAllocatorSpec`
  - 区間の定義と、同じレジスタを使う値の区間が重ならないこと
  - MAC の3つのオペランドの区間が MAC の発行で終わること。同じ LD を3つの欄で読む MAC（#77）
  - 3構成のベンチマークで、割り当て後の命令列を verifier が合法と判定すること
  - 成功した全ての試行についても、verifier が合法と判定すること
  - 上の表の値、レジスタが足りないときのエラー
- `compiler/.../regalloc/RematerializationSpec`（#70）
  - `split`: 付け替えた読む側だけが複製を読み、番号が位相順のままで、DAG をノード順に実行した結果が lowering の評価と一致すること。ST のある語の LD と、全ての読む側の付け替えは拒むこと
  - 候補と選び方（手作りの停止状態。2つの候補から次の使用が遠い方を、同じならば番号の小さい方を選ぶこと）。ST が読む LD の付け替え
  - 3構成 × 8・12・16 本で、複製なしで収まる試行は同じ結果になり、複製は行き詰まる試行にだけ現れること。全てのカーネルが収まり、成功した全ての試行の命令列を verifier が合法と判定すること。命令列だけから見て、2回以上 LD する語を ST が書かないこと
  - コンパイラの選択が、複製で cycles が真に減るときだけ変わること。上の2つの表の値と、`slow_units` と `ii_units` の8・12・16本の値（II だけで空くスロットは詰まりに数えないこと）。常に複製を許したとき（`Remat.Always`）の、ADR-0016 が挙げる値。2回のコンパイルが同じ命令列になること
  - mvp-1（#77）: 上の3構成 × 8・12・16 本の検査を、mvp-1 の3構成でも行う。`split` が MAC の3つのオペランドのどれでも付け替えること（手作りの例と、融合したベンチマークの DAG）
- `compiler/.../MacCompilerSpec`（#77）: mvp-1 の3構成 × 8・12・16 本で、ベンチマークが verifier に合格し、MAC のノードごとに、割り当てたレジスタの `mac rd, rs1, rs2, rs3` があること。`read_ports` 3 では MAC と ST が同じ bundle に無いこと。命令列を ISA の意味論で実行した結果（テストの中の簡単な実行。ソースは発行サイクルに読み、結果は L サイクル後に commit する）が DSL の参照実装と一致すること。乱数で作った300のカーネルでも同じ。`read_ports` 4 の命令列を `read_ports` 3 の machine で検査すると、MAC と ST のある bundle ごとに L11 の違反になること。上の表の値
- `crosscheck/CompiledKernelSpec`: 割り当て後のベンチマークを参照モデル（3構成、`regs8`、`regs12`）と RTL（既定の構成、`regs8`、`regs12`）で実行し、結果が DSL の参照実装と、cycles がスケジュールと一致すること
- `crosscheck/BenchmarkSpec`: [benchmarks/REGISTERS.md](../benchmarks/REGISTERS.md) と、8・12本のテストケース・メトリクスが生成し直した内容と一致すること。それらのテストケースが参照モデルと RTL で成り立つこと。各レジスタ数の、戦略を固定した系列の全ての命令列が参照モデルで期待どおりになること

## コード生成と最終検証

`KernelCompiler.compile(kernel, machine)` は、上の各段を順に行い、`CompiledKernel` を返す。

1. メモリ配置と lowering、DAG（machine に MAC があれば融合する。ISA mvp-2 では組に詰める）
2. スケジュールとレジスタ割り当て（`Allocation.bundles` が bundle 列を作る。最後は HALT。MAC のノード `Mac(a, b, c)` は `mac rd, rs1, rs2, rs3` で、rs1・rs2 が a・b、rs3 が c のレジスタ）
3. エンコード
4. **独立の verifier（[VERIFIER.md](VERIFIER.md)）による最終検査**。入力宣言は、配置の初期化済みの語（M_in）だけで、R_in は空

ISA mvp-2 では、`CompiledKernel.simd` が W の結果（詰めたか、スカラーへの fallback とその理由）を持つ（「[ISA mvp-2（W）](#isa-mvp-2w)」）。mvp-0・mvp-1 では `Simd.Scalar` である。

verifier が1つでも違反を報告したら、その命令列は返さず、`compiler error:` で始まるエラーにする。コンパイラの誤りであり、生成コードを出力しない。カーネルが machine に収まらない場合（Scratchpad の容量、レジスタ数）は入力の誤りとして区別する。

### CLI

```sh
sbt "compiler/run compile saxpy8 fir4x8 matmul3"
sbt "compiler/run compile fir4x8 --machine tests/machines/slow_units.json --out out/ --seed 2 --priority program-order"
```

| オプション | 既定 | 内容 |
|---|---|---|
| `--machine` | `config/machine.json` | machine description |
| `--out` | `generated/kernels` | 出力先（gitignore 済み） |
| `--seed` | 1 | テストケースの入力値の乱数の種 |
| `--priority` | 全部 | 優先度を1つに固定する。既定では全部を試し、cycles 最小を選ぶ |

カーネルごとに次の2つを書き、cycles・下限・選ばれた試行を表示する（MAC のある machine では、融合した組の数も。ISA mvp-2 では W の結果も。「[ISA mvp-2（W）](#isa-mvp-2w)」の「CLI」）。終了コードは、入力の誤りが2、コンパイラの誤りが3。

| ファイル | 内容 |
|---|---|
| `<kernel>.hex` | 命令語（[ASM.md](ASM.md) の16進形式） |
| `<kernel>.json` | テストケース（[TESTCASE.md](TESTCASE.md)）。命令列、入力宣言（M_in に入力・スカラー・定数）、期待値 |

テストケースの期待値は、次のように命令列と独立に決める。

- 出力の値: DSL の参照実装（`Interpreter`）で、命令列を経由せずに計算する
- `cycles`: スケジューラの予測

このテストケースは手作りの期待値ではないので、`tests/cases` には置かない。そのまま `sbt "compiler/run verify ..."` や `sbt "sim/run ..."` に渡せる。

### テスト

- `compiler/.../KernelCompilerSpec`
  - ベンチマークがコンパイルでき、命令列が verifier に合格すること
  - 壊した命令列（MUL の直後の bundle を削る）を、コンパイラの誤りとして止めること
  - machine に収まらないカーネルを、理由付きで拒否すること
  - 生成したテストケースが読め、期待値が DSL の参照実装と一致すること
- `crosscheck/RandomKernelSpec`: 乱数で作った400のカーネルを、すべてコンパイルして確かめる
  - 最終検査に合格すること
  - 参照モデルでの結果が DSL の参照実装と、cycles が予測と一致すること
  - 生成したテストケースが参照モデルで成り立つこと（100個）
  - RTL でも結果と cycles が一致すること（60個）
  - ISA mvp-1 と mvp-2 の構成でも同じことを確かめる（mvp-2 は下の「[ISA mvp-2（W）](#isa-mvp-2w)」の「テスト」）

## ISA mvp-2（W）

ISA mvp-2 の machine（[ISA.md](ISA.md)「mvp-2: W」）では、`KernelCompiler` は融合した DAG の値を W の組に詰め（`dag.Pack`）、詰めた DAG を list scheduling し、組を考えて割り当てる（[ADR-0023](DECISIONS.md)、#110 の第4段）。Modulo Scheduling（`ModuloCompiler`）は、第5段まで mvp-2 の machine を理由付きで拒否する。「既定」と書いた点は、ADR-0023 にもオーナーの判断にも無く、#110 が決めた既定である（ADR-0023 の 2026-10-30 の2つの補足（第4段の前半と後半）に一覧がある）。

段の順は `Lowering → Dag.build → Fusion.fuse → Pack → ListScheduler → rematerialization（行き詰まる試行だけ）→ 組を考えた割り当て → Allocation.bundles → verifier` である。`KernelCompiler.prepare` は `Prepared.dag` を融合したスカラーの DAG のまま残し、mvp-2 の machine では `Prepared.packing` に `Pack.pack(dag)` を入れる（mvp-0・mvp-1 では None）。融合を先に行うので、詰めた MAC の組がそのまま VMAC になる。詰め込みは戦略の次元ではなく、どの試行も同じ詰めた DAG をスケジュールする。

### ノードと欄

| ノード | 命令 | スロット | 書戻しポート | 欄（L11） | 値 |
|---|---|---|---|---|---|
| `LoadW(addr)` | LD.W | Memory | Memory | 0 | 整列した組 |
| `VCompute(op, a, b)` | VADD・VSUB・VMUL | Compute | Compute | 2 | 整列した組 |
| `VMac(a, b, c)` | VMAC | Compute | Compute | 3 | 整列した組 |
| `StoreW(addr, v)` | ST.W | Memory | なし | 1 | なし |
| `StoreLane(addr, pair, lane)` | ST | Memory | なし | 1 | なし。W-reg で、組のノードの1つのレーン r(p + lane) を ST する |

ベクトル演算と ST.W の欄（`Src`）は3種類ある。

- `Pair(n)`: 組のノード（`LoadW`・`VCompute`・`VMac`）の組
- `Gather(lo, hi)`: 2つのスカラーのノードの値を、整列した組の r(p)（`lo`、レーン0）と r(p+1)（`hi`、レーン1）に入れたもの（集め）。移す命令は無い（組の部分更新は合法。ISA.md）
- `Broadcast(n)`: 1つのスカラーのノードを両方のレーンに使う。VMUL・VMAC の rs1 だけ

`Node.operands` は読むノード（集めは2つ）、`Node.fields` は欄の数で、スカラーのノードでは `operands.size` と同じである。`ListScheduler.reads` は `fields` を数える（L11 の形 F）。`Dag.resourceLowerBound` は LD.W・ST.W・レーンの ST を Memory の操作に数え、`Dag.loads` は LD と LD.W を数える。これらのノードを作るのは `Pack` だけで、`Dag.build`・`Fusion`・`LoopGraph` は作らない。

### 詰め込みの規則（`dag.Pack`）

ADR-0023 の規則1〜6を次のように実装した。

1. **ST の組**（規則1。2段。#110 第4段の Q1 の推奨で、オーナーの答えを待っている）: ST のある語 w と w+1 の組（候補）を、次の2段で試す（`Pack.storePairs`）。どちらの段も小さい番地から順に試し、前に受け入れた組がどちらかの語を組にしていれば飛ばす。このため、同じ段の中では、同じ語を含む2つの候補のうち番地の小さい方が先に組になり、1段目の候補は2段目の候補より先に組になる。番地だけで組にし、どの配列の語かは見ない（既定）。2回以上 ST する語は組にしない（既定）
   - 1段目: 集めの要らない候補（`Pack.gatherFree`）だけを試す。集めが要らないとは、2つの ST の値が同じ演算（ADD・SUB・MUL・MAC）で、2つの木を並べてたどると、どの位置でも次のどれかに行き着くことである: 同じ演算（欄を順にたどる。乗算の rs2 が両方のレーンで同じなら rs1 と入れ替える）、乗算の rs1 で両方のレーンが同じノード（ブロードキャスト）、レーン0が LD(u)・レーン1が LD(u+1) で、どちらの語も DAG のどの ST も書かないもの（規則3。W-full では LD.W、W-reg では2つの LD の集め）。融合した DAG の形だけで決まるので、W-full と W-reg で同じ候補になる（ADR-0012）。ST の値が2つの LD の候補（語の複写）は1段目に入れない（既定。入れると、乱数のカーネルで、1段目の複写の組がベクトル演算の組を妨げることがあった）
   - 2段目: すべての候補を番地の順に試す（第4段の前半の規則1と同じ。1段目で断った候補も、もう一度試す）。断った組の w+1 は、次の組 (w+1, w+2) で組になり得る（既定）
   - `Packing.pairs` は番地の順に並べる。組を受け入れた順は、集めのどちらのレーンが元の LD を使い、どちらが LD の複製を使うかを決め、詰めた DAG のノードの番号に表れる。そのため1段目の向き（小さい番地から）も規則に含める
   - 規則1を書かれたとおりに読む（1段だけ。#110 第4段の Q1 の (a)）には、`Pack.storePairs` が番地の順の候補だけを返すようにする（1か所）
2. **2つの木を並べてたどる**（規則2）: 両方のレーンで同じノードは、MUL・MAC の rs1 ならブロードキャストにし、ほかの位置ならその組を詰めない。両方が同じ MUL・MAC で、rs2 が両方のレーンで同じなら、rs1 と入れ替えてからブロードキャストにする（乗算は可換。入れ替えはこれだけ）。同じ演算の ADD・SUB・MUL・MAC はベクトル演算にし、欄を順にたどる
3. **LD.W**（規則3）: 語 w と w+1 の LD で、どちらの語も DAG のどの ST も書かないものは、W-full では `LD.W [w]`（w は奇数でもよい）、W-reg では集めにする。ST のある語の LD は、LD.W にせず、その LD のノードの集めにする（既定。メモリ順序の辺を保つ）
4. **集め**（規則4）: ほかの組（隣でない語の LD、違う演算、LD と計算した値）は集めにする。スカラーのノードの置き場は1つで、自分のレジスタ、1つの集めの1つのレーン、1つのベクトル演算の1つのレーンのどれかである。ほかの置き場をもう持つノードが集めに要るときは、書込みの無い語の LD なら複製し（新しい LD のノード）、ほかのノード（計算した値、ST のある語の LD）ならその組を詰めない。ADR-0023 は「別の組の逆のレーン」だけを挙げるが、同じレーンでも別の集めなら置き場がぶつかるので詰めない（既定）
5. **ST**（規則5）: W-full では `ST.W [w]` にする。根が集めでもよい（関係の無い2つの値の ST.W。既定）。W-reg では、根がベクトル演算なら2つのレーンの ST にし、根が集めなら、ベクトル演算も ST.W も残らないので詰めない（既定）
6. **共有**（規則6）: ベクトル演算は（レーン0のノード、レーン1のノード）で、LD.W は番地で、集めは2つのノードで値の番号を付け、同じものは1つにする（FIR の窓）。ST の組をまたいで共有されるのは LD.W と集めだけで、計算したベクトルは1つの ST の組の木の中でだけ共有される（下の「置き場」）。メモリ順序の辺は、融合した DAG の辺の両端を詰めた DAG のノードに付け替え、距離は変えない（ST.W の latency は ST と同じ。ADR-0022）。LD.W と LD の複製は、書込みの無い語だけを読むので、メモリ順序の辺を持たない

組ごとに、たどった後で次の2つを確かめ、満たさなければその組を詰めない。

- **置き場**: ベクトル演算のレーンにした計算した値は、読む側がすべて、同じレーンでその組を欄に読むベクトル演算か、そのレーンの ST でなければならない。まだたどっていない木の読む側はスカラーとして数えるので、後の木と共有するノードの組は詰めない（決定的で、保守的な読み。既定。#110 第4段の Q3）。後の組も詰めない: 先の組がスカラーのまま残るので、その木の読む側はスカラーになる。このため、計算したベクトルを共有する2つの ST の組は、どちらも詰めない（理由 `NeededElsewhere`。断った組の語は、隣の別の組になり得る）。レーンを取り出す命令は要らない
- **閉路**: 詰めた DAG に閉路ができる組は詰めない（既定。規則ではない理由）。例えば、語 w の ST の後に w を読む LD が、語 w+1 の値に要る組である

詰めた DAG は、ST・ST.W・レーンの ST から逆にたどれるノードからなる。LD.W に置き換わった LD と、ベクトル演算のレーンになったノードは消える。ノードの番号は Kahn の方法で付け、準備のできたノードのうち次の鍵の小さいものから番号を付ける（既定）: スカラーのノードは融合した DAG の番号、組のノードと ST.W はレーンのノードの番号の大きい方、レーンの ST は元の ST の番号。同じならスカラーを組より先に、次にもう一方のレーンの番号の順にする。組が1つも無ければ、融合した DAG の番号のままである。

組にならなかった出力の語（スカラーのレーン）の理由は次のとおりで、試した順（規則1の1段目、2段目）で最初に断った組の理由を書く。名前は既定である。

| 理由 | 規則 | 内容 |
|---|---|---|
| `NoNeighbour` | 1 | 隣の出力の語が無いか、もう組になっている |
| `RootDiffers` | 1 | W-reg で、2つの ST の値が根で違う |
| `SameValue(位置)` | 2 | 乗算の rs1 以外の位置で、両方のレーンが同じ値 |
| `NeededElsewhere(ノード)` | 4 | 計算した値が、別の組や別のレーン、スカラーでも要る |
| `Cycle` | — | 組にすると依存の閉路ができる |
| `StoredTwice` | — | 組の語を2回以上 ST する |

`Pack` の結果（`Packing`）は、融合したスカラーの DAG、詰めた DAG、組（`PackedPair`）、スカラーのレーン（`ScalarLane`）、LD の複製の数を持つ。どの出力の語も、ちょうど1つの組か1つのスカラーのレーンにある。

`saxpy8`（x は 0〜7、y は 8〜15、a は 16、out は 17〜24）は、W-full で `LD a`、`LD.W` 8つ（0、2、…、14）、`VMAC` 4つ（`a` をブロードキャスト）、奇数の語 17・19・21・23 の `ST.W` 4つになる（17ノード、Memory の操作 13 で、スロットの下限は 14、依存の下限は 6）。W-reg では、LD 17（`a` と集め8つ）、VMAC 4、レーンの ST 8 で、スロットの下限は 26 である。`fir4x8` は VMUL 4 と VMAC 12（`h` をブロードキャスト）で、窓は W-full で LD.W 10、W-reg で集め 10 と LD の複製 9 になる（窓の LD 20、スロットの下限 33）。`saxpy` と `fir4x` では、どの長さでも、どの候補も集めが要らないので、組は1段だけで試したときと同じになる。

`matmul3`（a は 0〜8、b は 9〜17、c は 18〜26）では、1段目の候補は各行の (c(i,0), c(i,1)) と (c(i,1), c(i,2)) で（a(i,m) をブロードキャストし、b(m,j) と b(m,j+1) を読む）、番地の小さい (c(i,0), c(i,1)) が組になり、(c(i,1), c(i,2)) は飛ばす。行をまたぐ (c(i,2), c(i+1,0)) は、a(i,m) と a(i+1,m)（と b(m,2)・b(m,0)）の集めが要るので1段目に入らず、2段目では c(i+1,0) がもう組になっているので飛ばす。c(i,2) はスカラーのレーン（`NoNeighbour`）になる。W-full では LD 12（a の9つはブロードキャスト、b(m,2) の3つ）、LD.W 3（[9]・[12]・[15]。3行で共有）、VMUL 3、VMAC 6、MUL 3、MAC 6、ST.W 3（[18]・[21]・[24]）、ST 3 で、Memory の操作は 21（スロットの下限 22）である。W-reg では LD 18（集め (b(m,0), b(m,1)) の3つを3行で共有）、レーンの ST 6 と ST 3 で、スロットの下限は 28 である。これは #78 の見積りが仮定した行ごとの組で、ADR-0022 の「引き受ける危険」の「`matmul3` の3列目はスカラーのまま残る」のとおりである。規則1を書かれたとおりに読むと（Q1 の (a)）、(c0,c1)〜(c6,c7) を組にし、c8 がスカラーのレーンになる。(c2,c3) = (c(0,2), c(1,0)) が集め6つを要し、W-full の Memory の操作は 26（スロットの下限 27）になる。

### スケジュール（`ListScheduler`）

ADR-0023 の素朴な方法のままで、各サイクルで Compute を先に埋め、ベクトルとスカラーの演算は同じ優先度で競う。

| 項目 | mvp-2 の扱い |
|---|---|
| スロット | VADD・VSUB・VMUL・VMAC は Compute、LD.W・ST.W・レーンの ST は Memory。どちらも1サイクルに1つ |
| 書戻しポート（L5） | 操作ごとに1つ予約する。ベクトル演算と LD.W は組を1回の予約で commit する。ST.W とレーンの ST は使わない |
| 演算器（L9） | 演算ごと（VMAC は MAC と別の演算器）。レーンの ST は ST の演算器を使う |
| 読出しポート（L11、形 F） | bundle の欄の数 ≤ `read_ports`。VMAC 3、VADD・VSUB・VMUL 2、ST・ST.W・レーンの ST 1、LD・LD.W 0。bank を見た配置や割り当てはしない |
| `heldByReadPorts` | L11 だけで待った ST・ST.W・レーンの ST を数える |
| レジスタの上限 | 生きているレジスタを重みで数える（下の「レジスタの組」）。LD と LD.W は `loadReserve` 本を空けておけるときだけ置く |

### レジスタの組

スケジューラと割り当ては、ノードではなくレジスタの組（`Dag.groups`）を数える。

- 集めに入らないスカラーのノード: 重み1
- 組のノード（LD.W、ベクトル演算）: 重み2
- 集め: 重み2。2つのレーンのノードが書く

組は、最初に発行した書く側の発行から、最後の読む側の発行まで生きる（`[s, u)`）。集めは、先に発行したレーンから2と数える（ADR-0023）。レーンごとには解放しない（W-reg の2つのレーンの ST の間も2のまま。既定。#110 第4段の Q10）。集めのレーンを読むスカラーの演算は、r(p) か r(p+1) をそのまま読む。上限付きのスケジューラは、置いた後の重みの和（`live` から、最後に読まれる組の重みを引き、始まる組の重みを足したもの）が上限以下のときだけ置く。mvp-0・mvp-1 の DAG では、どの組も1つのノードで重み1なので、今までと同じ数になる。

### 割り当て（組）

区間を、開始、次に組の最初の書く側の番号の順に割り当てる。

- 組と集め: 両方が空いている最も小さい整列した組 `r(p):r(p+1)`（p は偶数）。集めの `lo` が r(p)、`hi` が r(p+1) を書く
- スカラー（DAG に組があるとき）: 相方 `r xor 1` が使用中の、空いている最も小さいレジスタ（best fit）。無ければ、空いている最も小さいレジスタ
- スカラー（DAG に組が無いとき）: 空いている最も小さいレジスタ（今までと同じ。既定）。このため、カーネルの fallback の命令列は mvp-1 の命令列と同じになる

失敗の理由は2つに分ける。重みで数えた同時に生きるレジスタがレジスタ数を超えれば `TooManyLive`、超えないのに、組の始まる cycle に空いた整列した組が無ければ `NoAlignedPair`（cycle、その cycle の重みの和、レジスタ数）である。上限付きの試行でも、断片化で `NoAlignedPair` になり得る（上の「足りない場合の再スケジュール」の「割り当ては必ず成功する」は、組の無い DAG でだけ成り立つ）。試行の集合（20個）と選び方（cycles、LD の数、試行の順）は変えない。

rematerialization（ADR-0016）は、`LD.W [a]` を、DAG のどの ST も `a` と `a+1` を書かないときに複製する。ブロードキャストの LD は、ほかの LD と同じく複製する。集めのレーンの LD は複製しない（既定。#110 第4段の Q5）。集め全体（2つの LD と集め）を複製する rematerialization を第4段の後半で試作し、規則1の2段で測った（行き詰まる試行で、LD の複製と同じく次に読まれるのが最も遠いものを選ぶ版と、LD・LD.W の複製ができないときだけ選ぶ版）。W-reg のレジスタ 8・10・12・16 本のどれでも、ベンチマークは速くならなかった。W-reg の `fir4x8` と `matmul3` は Memory が律速で（スロットの下限 33 と 28）、集めの複製は Memory の操作を2つ増やす。`matmul3` は、12本では前の版で 42 → 45 cycles と遅くなり、10本ではどちらの版も、スカラーの fallback（42）の代わりに、それより遅い詰めた命令列（48 と 52）を選ぶ。W-full と `RtlMvp2Spec` の変種では、`fir4x8` と `matmul3` に集めが無い。乱数の400のカーネル（W-reg のレジスタ6本と8本、W-full の6本）でも、どちらの版も、速くなるものと遅くなるものが1つずつだった。そのため入れていない。

命令の生成（`Allocation.bundles`）: ベクトル演算の rd、`Pair` と `Gather` の欄、LD.W・ST.W には組の偶数のレジスタを、`Broadcast` の欄には1つのレジスタと B=1 を書く。レーンの ST は `p + lane` を ST する。生成の誤りは、エンコーダ（L1。奇数の組）と verifier が「コンパイラの誤り」として止める。

### fallback と結果の型

ADR-0023 の2つの粒度の fallback を、次のように結果に書く。

- レーンの fallback: 組にできない出力の語は、詰めた DAG の中でスカラーのまま残る（`Packing.scalarLanes` と理由）。カーネル全体はスカラーに戻さない
- カーネルの fallback: 組が1つもできないとき（`NothingToPack`）と、詰めた DAG のすべての試行が失敗したとき（`NoAttemptFits`。試行ごとの `TooManyLive`・`NoAlignedPair`・`Stuck`）は、融合したスカラーの DAG をコンパイルする。この命令列は W の演算を使わない

| API | 用途 | 結果 |
|---|---|---|
| `KernelCompiler.compile` | 通常の利用 | `CompiledKernel.simd` が `Packed(packing)`（レーンの fallback を含む）か `Fallback(reason, packing)`。スカラーの DAG も収まらないときだけ入力の誤りで、両方の理由を書く |
| `KernelCompiler.candidate` | 比較の実験（第6段） | `SimdCandidate.Packed`、`Infeasible`（試行ごとの失敗。スカラーの値に置き換えない）、`NothingToPack`（候補が無く、スカラーの命令列を理由と一緒に返す）。mvp-0・mvp-1 の machine では入力の誤り |

通常の利用は、詰めた命令列がスカラーの命令列より遅くても詰めた方を使う（ADR-0023: fallback は失敗のときだけ。テストは遅い数を情報として表示する）。`CompiledKernel.dag` は、詰めたときは詰めた DAG、fallback ではスカラーの DAG である（`Prepared.dag` はスカラーの DAG のまま）。`Prepared.dag` を1つの試行で割り当てて W の無い結果（`Simd.Scalar`）にする `KernelCompiler.finish(p, allocated)` は、mvp-0・mvp-1 の machine だけで使え、mvp-2 の machine では理由付きで拒否する。mvp-2 のスカラーの命令列は fallback か `NothingToPack` の命令列で、W の無い結果として記録しないためである（ADR-0023: 黙ってスカラーの値に置き換えない）。同じ理由で、戦略を固定した系列（`BenchmarkRecords.fixed`）も mvp-2 の machine を拒否する。

### 結果（W、16 レジスタ）

| カーネル | 構成 | cycles | 下限（依存 / スロット） | 選ばれた試行 | `heldByReadPorts` | 演算 | #78 の見積り |
|---|---|---|---|---|---|---|---|
| `saxpy8` | W-full | 15 | 6 / 14 | critical-path、上限なし | 3 | LD 1、LD.W 8、VMAC 4、ST.W 4 | 15 |
| `saxpy8` | W-reg | 27 | 6 / 26 | critical-path、上限なし | 3 | LD 17、VMAC 4、ST 8 | 27 |
| `fir4x8` | W-full | 25 | 12 / 19 | critical-path、上限16、reserve 0 | 2 | LD 4、LD.W 14（うち複製 4）、VMUL 4、VMAC 12、ST.W 4 | 26 |
| `fir4x8` | W-reg | 37 | 12 / 33 | program-order、上限なし | 6 | LD 24、VMUL 4、VMAC 12、ST 8 | 35 |
| `matmul3` | W-full | 28 | 10 / 22 | program-order、上限なし | 0 | LD 12、LD.W 3、VMUL 3、VMAC 6、MUL 3、MAC 6、ST.W 3、ST 3 | 28 |
| `matmul3` | W-reg | 32 | 10 / 28 | critical-path、上限16、reserve 0 | 4 | LD 18、VMUL 3、VMAC 6、MUL 3、MAC 6、ST 9 | 32 |
| `saxpy7` | W-full | 15 | 6 / 14 | critical-path、上限なし | 3 | LD 3、LD.W 6、VMAC 3、MAC 1、ST.W 3、ST 1 | — |
| `saxpy7` | W-reg | 24 | 6 / 23 | critical-path、上限なし | 3 | LD 15、VMAC 3、MAC 1、ST 7 | — |
| `saxpy1` | W-full・W-reg | 8 | 6 / 5 | critical-path、上限なし | 0 | `NothingToPack`。mvp-1 と同じ命令列 | — |

- 見積りは #78 の見積り（測った値ではない）で、比べるために並べた。W-full の `saxpy8` の 15 は、手書きの `tests/cases/w_saxpy8_hand.json` の cycles とも同じである
- W-full の `saxpy8` では、LD と LD.W は 0〜8、VMAC は 4・6・8・10、ST.W は 9・11・12・13、HALT は 14 に置かれる。ST.W は VMAC の横の 6・8・10 で L11 だけで待つ（欄 3 + 1 > 3）。6 と 8 では、その bundle に LD.W を置く
- `read_ports` 4（`mvp2_rp4.json`）では、`saxpy8` 14、`fir4x8` 23、`matmul3` 24 cycles で、どれも VMAC と ST.W が同じ bundle に入る（見積りは 14、26、24。`fir4x8` と `matmul3` は critical-path、上限16、reserve 0 の試行で、`fir4x8` は LD.W を4つ複製する）
- `fir4x8`・`matmul3` の行は #110 第4段の後半で足した（Q9）。`matmul3` の組は規則1の2段による（上の「詰め込みの規則」。#110 第4段の Q1 の推奨で、オーナーの答えを待っている）
- 見積りとの差は、見積りの前提（rematerialization を数えない）か、詰めた DAG のノードの番号の付け方（コンパイラの既定）による。コンパイラの誤りは見つからなかった（第4段の後半で、見積りのコード（#78）のスケジュールと bundle ごとに比べ、見積りのノードの順に番号を付け直した DAG をコンパイラのスケジューラと割り当てにかけた）
  - `matmul3`: W-reg は、見積りと bundle ごとに同じスケジュールである。W-full は、選ばれた試行（program-order、上限なし）の cycles が同じで、critical-path の上限なしのスケジュールは bundle ごとに同じである（どちらも17の値が同時に生き、16本に収まらない）。`read_ports` 4 も見積りと同じ 24 cycles である
  - `fir4x8` の W-full（25。見積り 26）と `read_ports` 4（23。見積り 26）: 見積りは rematerialization を数えない。critical-path、上限16 の試行は、見積りでは行き詰まり、コンパイラでは LD.W を4つ複製して 25 と 23 cycles になる。複製の無い試行では、コンパイラの最小は program-order の 27 cycles で、見積り（26）との1の差は、次の W-reg と同じくノードの番号の付け方による（見積りの順に番号を付けた DAG では、どちらの構成でも同じスケジューラで 26 になる）
  - `fir4x8` の W-reg（37。見積り 35）: どちらも program-order、上限なしの試行で、差は詰めた DAG のノードの番号の付け方による（program-order は番号を優先度にする）。見積りは ST の組ごとにベクトルの木を後順でたどった順で（窓の2つの LD を、最初に読む直前に並べる）、コンパイラは上の「詰め込みの規則」の鍵の順（既定）で、組の両方のレーンの木の LD をすべて最初のベクトル演算の前に並べる。見積りの順に番号を付けた DAG を同じスケジューラにかけると 35 cycles になる。critical-path の試行は、どちらでも上限なしで23の値が同時に生き、上限付きでは行き詰まる
  - 番号の付け方を変えるのは既定の変更で、詰めたどのカーネルの program-order の cycles も動かすので、変えていない
  - 規則1を書かれたとおりに読むと（Q1 の (a)）、`matmul3` は W-full と `read_ports` 4 で集め6つ、W-reg で集め 12 と LD の複製 9 を要し（W-reg の LD 27、Memory の操作 36、スロットの下限 37）、W-full 31、W-reg 40、`read_ports` 4 で 28 cycles になり（見積りの行ごとの組と比べられない）、`mvp2_vlat` ではどの試行も収まらず、スカラーの fallback（39 cycles）になる。2段では `mvp2_vlat` でも詰める（34 cycles）

### CLI

mvp-2 の machine では、表示の行に W の結果を足す。終了コードは変わらない（fallback も 0。カーネルは動く）。下限はスケジュールした DAG（詰めた DAG か、fallback ではスカラーの DAG）の値で、融合した組の数はスカラーの DAG で数える。

```
saxpy8: 15 cycles (lower bounds 6 from dependences, 14 from slots), critical-path, no register limit, 8 MUL + ADD pairs fused into MAC; W: 4 output pairs packed (4 VMAC, 8 LD.W, 4 ST.W, 1 broadcast, 0 gathers), no scalar lanes; wrote …
```

fallback は `W: scalar fallback, (NothingToPack: …)` か `W: scalar fallback, every attempt of the packed DAG failed (TooManyLive 2, Stuck 6)` と表示する。テストケースの `description` にも同じ文を足す（mvp-0・mvp-1 では変わらない）。

### テスト

- `compiler/.../dag/PackSpec`
  - `saxpy8`（W-full と W-reg）のノードの列、辺と距離、下限、2回詰めて同じになること。`saxpy7` と `saxpy1`。`matmul3` の2段の規則1（行ごとの組と `Pack.storePairs` の順。W-full と W-reg）。1段目が組を変える手作りの DAG、1段目が空の DAG、2段目が1段目より小さい番地を組にする DAG（`Packing.pairs` が番地の順であること）。`Pack.gatherFree` の定義（ブロードキャストと rs1 への入れ替え、語の複写、乗算の rs1 以外の同じ値（ADD と、rs1 をブロードキャストする MAC の rs3）、ST のある語（レーン 0 と 1 のどちらの語も）、レーンの逆の順、違う演算、2回の ST）
  - 詰めたどの DAG でも、欄が種類どおりであること（`Pair` は組のノード、`Gather` は違う2つのスカラーの値、`Broadcast` は VMUL・VMAC の rs1 のスカラーの値だけ。スカラーの演算はスカラーの値を、レーンの ST は組のノードを読む）
  - `saxpy(1..9)` と `fir4x(1..9)`（W-full と W-reg）の組の数と、奇数の長さの最後の語。どの候補も1段目に入ること。FIR の窓（W-full の LD.W の数、W-reg の集めと複製の数）
  - 規則と理由ごとの手作りの DAG: rs3 と ST のソースの `SameValue`、rs1 への入れ替え、ST のある語の集めと LD → ST の辺、隣でない語の集め、LD の複製、2つの集めに要る計算した値、スカラーの読む側がある計算したレーン、計算したベクトルを共有する2つの ST の組（1段目で断った理由）、W-full の根の集めの ST.W と W-reg の `RootDiffers`、メモリ順序による `Cycle`、`StoredTwice`
  - 6語を読み書きする乱数の500の書込みの列と、乱数の400の DSL カーネル（W-full と W-reg）: 詰めた DAG を、辺を守る乱数の順序で実行した結果（テストの中で、ノードの定義から書いた実行。`DagRun`）が lowering の評価と一致し、出力の語が組とスカラーのレーンに分かれ、ST が融合した DAG と同じ語を書くこと
  - mvp-0・mvp-1 では詰めないこと
- `compiler/.../schedule/ScheduleSpec`: 独立の検査に新しいノード（スロット、書戻しポート、欄、演算器、HALT）を足した。詰めた DAG（ベンチマーク、`saxpy7`、乱数の60のカーネル。W-full、W-reg、`mvp2_vlat`、`mvp2_vii`、`mvp2_rp4`、レジスタ8本の変種）の上限なしと上限付き（reserve 0〜r/2）の全スケジュールが検査に合格し、上限付きでは、スケジュールだけから数えた重みの和が上限を超えないこと。`read_ports` 3 では VMAC と ST.W が同じ bundle に無く、4 ではあること。`heldByReadPorts` が ST.W とレーンの ST を数え、発行サイクルだけから数え直した値と一致すること。`mvp2_vlat`（VMAC の L 3、MAC の L 2）の `saxpy7` で、MAC が VMAC と同じ境界の書戻しポートで2回待つこと
- `compiler/.../regalloc/RegisterAllocatorSpec`: 詰めた DAG の成功した全ての試行で、組が偶数、集めのレーンが p と p+1 で、verifier に合格すること。集めの区間が先のレーンから始まり、`maxLive` が重みで数えること。手作りのスケジュールで、組のある DAG でだけ best fit になること、`NoAlignedPair` と `TooManyLive` を分けること。上限付きの試行の `NoAlignedPair`
- `compiler/.../regalloc/RematerializationSpec`: `split` が `Pair`・`Broadcast`・ST.W の欄を付け替え、値が変わらないこと。詰めた DAG（8・12・16本）で、複製は行き詰まる試行にだけ現れ、集めのレーンは複製されず、LD.W とブロードキャストの LD が複製されること。命令列で2回以上 LD・LD.W する語を ST・ST.W が書かないこと。複製のある試行の命令列が verifier に合格し、出力（`IsaExecutor`）が DSL の参照実装と一致すること
- `compiler/.../WCompilerSpec`: `saxpy8` の W-full と W-reg の命令列と、上の表のすべての行の各欄（cycles、下限、選ばれた試行、`heldByReadPorts`、演算の数）。`matmul3` の W-full の LD.W・ST.W・ST の番地とスカラーのレーン、W-reg の ST の番地と集めの数。`read_ports` 4 の `fir4x8`・`matmul3` の cycles、VMAC と ST.W の同じ bundle と、`fir4x8` の LD.W の数（複製を含めて 14）。`mvp2_vlat` で `matmul3` を詰め、34 cycles になること。`saxpy7` のレーンの fallback、`saxpy1` の `NothingToPack`（mvp-1 と同じ命令列）。レジスタ4本の W-full の `saxpy8` の `Infeasible`（上限なしの2つが `TooManyLive`、上限付きの6つが `Stuck`）と `Fallback(NoAttemptFits)`（mvp-1 のレジスタ4本と同じ命令列）。ベンチマークと奇数の長さを5つの mvp-2 の構成と両方の発行の方式でコンパイルし、出力（テストの中の ISA の実行。`MacCompilerSpec` と共通の `IsaExecutor`）と、命令列が `simd` のとおりであること
- `compiler/.../KernelCompilerSpec`: mvp-2 の machine で list scheduling がコンパイルし、テストケースが読めて W の文を持つこと。Modulo Scheduling が第5段まで拒否すること。mvp-0・mvp-1 で `candidate` が入力の誤りになること
- `crosscheck/HandComputedSpec`: 手で計算した入出力を、W-full、W-reg、`mvp2_vlat`、`mvp2_rp4`、`mvp2_vii` の参照モデルで、両方の発行の方式で確かめる（5つの構成のどれでも、どのケースも詰める。規則1を書かれたとおりに読むと、`mvp2_vlat` の `matmul3` は `NoAttemptFits` で fallback する）
- `crosscheck/RandomKernelSpec`: 乱数の400のカーネルを、W-full、W-reg、レジスタ6本の W-full の変種でコンパイルし、参照モデルで結果と cycles を確かめる。6本で収まらないのは、mvp-1 の6本でも収まらないカーネルだけであること。2つの API が一致し（16本と6本）、カーネルの fallback（`NothingToPack`・`NoAttemptFits`）の命令列が、同じレジスタ数の mvp-1 の命令列と同じであること。テストケースが参照モデルで成り立つこと。詰めた・レーンの fallback・`NothingToPack`・`NoAttemptFits`・理由・形の数の下限
- `crosscheck/RtlMvp2Spec`: 今の4つのビルド（W-full、W-reg、`mvp2_vlat`、変種）の中で、ベンチマーク、奇数の長さ、手で計算した入力（W-full と W-reg で、両方の発行の方式）、乱数のカーネルをコンパイルした命令列を RTL で実行し、参照モデルとトレース・cycles・レジスタ・メモリが一致し、出力が DSL の参照実装と一致すること（新しいビルドは無い）

## ループ IR

Modulo Scheduling（#68）の入力として、反復の単位と反復をまたぐ依存を残す IR（ADR-0011、[ADR-0014](DECISIONS.md#adr-0014-ループ-ir-では状態をレジスタで渡しループ不変の値と後の反復が再び読む語をレジスタに保持する)、#76）。実装は `vliw.compiler.loop`。Phase 2 の段と同じく、3つの段からなる。

| 段 | 入力 → 出力 | Phase 2 の対応 |
|---|---|---|
| 組み立て | `LoopKernel.apply` → `LoopKernel`（名前と、反復番号の一次式の添字） | `dsl.Kernel` |
| メモリ配置・lowering | `LoopKernel` → `LoweredLoop`（`Loop`。アドレスが反復番号の一次式） | `Lowering.lower` |
| 依存グラフ | `Loop` → `LoopGraph`（ノードの場所と、反復距離付きの依存） | `Dag.build` |

### 反復と II

- **反復**は、ループ本体を1回実行すること。反復番号 k は 0 から trips − 1 で、反復回数 trips は組み立て時に決まる
- 添字とアドレスは、反復番号の一次式 `stride × k + offset` である（`Index`、`Address`）。stride 0 は全反復で同じ語
- **反復距離** d の依存は、反復 k の演算が、反復 k − d の演算の値（またはメモリへの書込み）を使うこと。d = 0 は同じ反復の中の依存である
- II には2つの意味がある
  - **演算器の II**: `operations.<op>.ii`。同じ演算の2つの発行の間隔の下限（[ISA.md](ISA.md) の L9）。machine description が決める。この文書の list scheduling と register allocation の節の II は、こちらである
  - **ループの II**: 反復の開始間隔。反復 k の各演算は、反復 0 の同じ演算の k × II サイクル後に発行する。Modulo Scheduling（#68）が決め、`vliw-metrics/1` の `ii` 欄に記録する（[METRICS.md](METRICS.md)）

### 組み立て

`LoopKernel` は、1つの数え上げのループを持つカーネルである。書き方と制約は [DSL.md](DSL.md) の「ループ IR」にある。

- 本体のストア `out(index) = value` は、各反復 k で `out(index.at(k))` に書く
- 状態は、初期値（ループ不変の式。ループの前に評価する）と次の値（演算）を持つ。本体の中の状態は、その反復の開始時の値である。反復 k の次の値が、反復 k + 1 の開始時の値になる
- 後の格納 `out(i) = s` は、最後の反復の後の状態の値を書く
- 入力は読出し専用、出力は書込み専用で、各出力要素にちょうど1回だけ書く（`dsl.Kernel` と同じ）。このため、ストアの順序は結果に影響しない
- `LoopKernel.unroll` は、ループを完全に展開した `dsl.Kernel` を返す。反復の順に本体のストアを並べ、最後に後の格納を置く。`stateValues(k)` は、反復 k の開始時の各状態の式（k = trips はループの後）

`LoopBenchmarks` に、記録するループを置く。最初の4つは、展開すると Phase 2 のカーネル（`Benchmarks`）とまったく同じになる。

| ループ | 展開した結果 | ループにした段 | 反復 |
|---|---|---|---|
| `saxpy(8)` | `saxpy8` | 出力 i | 8 |
| `fir(Seq(1, 2, 3, 4), 8)` | `fir4x8` | 出力 i（タップ t は本体に展開） | 8 |
| `matmulRows(3)` | `matmul3` | 行 i（j と m は本体に展開） | 3 |
| `matmulAcc(3)` | `matmul3` | m についての累算（m = 1, 2。m = 0 は状態の初期値）。状態は c の要素ごとに9つ | 2 |
| `product(8)` | `product8`（`out(0) = x(0) × … × x(7)`） | 積 `p ← p × x(i)`（i = 1〜7） | 7 |
| `dot(8)` | `dot8`（`out(0) = Σ a(i) × b(i)`） | 累算 `acc ← acc + a(i) × b(i)`（i = 1〜7） | 7 |

`matmul3` の2つのループは、展開した結果の名前が同じなので、（カーネル、ループの段）の組で区別する。FIR のタップのループは作らない（ADR-0014）。

### メモリ配置と lowering

- 配置は、展開したカーネルの `MemoryLayout.fixedOrder` である（`LoopLowering.layout`）。ループと list scheduling は、同じ語を同じアドレスで読む。使われない値（格納しない状態の、最後の反復の次の値など）だけが読む定数は、出力の後の語に置く
- `LoopLowering.lower` は、葉を `Read(Address)` に、状態を `StateRef` に、本体のストアを `Write` に、状態の次の値を `Update` に、後の格納を `Final` に置き換える
- `Loop` は一般の形で、ループが書く語を読んでもよい（`LoopKernel` からは生じない）。意味は `LoopLowering.evaluate` が与える。初期値をループの前に評価し、各反復で本体の文を順に実行する（`Write` はすぐに書き、`Update` の値は次の反復から見える）。最後に `Final` を書く

### 依存グラフ

`LoopGraph.build(machine, loop)`（または `LoopGraph.of(lowered, machine)`）は、`Loop` の依存グラフを作る。各ノードは、実行する場所を持つ。

| 場所 | 実行 | ノード |
|---|---|---|
| Before | ループの前に1回 | 状態の初期値、ループ不変の LD、Before の値だけを使う演算 |
| Each | 各反復に1回 | 本体の LD・演算・ST、状態の次の値 |
| After | ループの後に1回 | 状態を格納する ST |

オペランドは `Ref(node, back, initial)` で、`node` の `back` 反復前の値を表す。反復 k < back ではその反復が無いので、Before のノード `initial(k)` の値を使う。Each のノードの番号は本体の順で、back = 0 のオペランドは小さい番号である。

LD の共有（ADR-0014）。本体が書かない語について、次のようにする。

- stride 0 の語は、Before で1回だけ LD する
- stride が0でない LD は、stride と「base を |stride| で割った余り」が同じものを組にする。base の差が |stride| × trips 以上の所では、組を分ける（共有する語が無い）。組の中で、各語を最初に読む LD（stride > 0 なら base が最大のもの）だけを Each に置く。他の LD は、それを `back` = (その LD の base − 自分の base) / stride だけ前の反復から使う `Ref` にする。k < back の値は、Before で LD する
- 本体が書く語の LD は、共有しない
- 等しい演算は1つのノードにまとめる（値番号付け）。Before の値だけを使う演算は Before に置く

共有するのは、Before の LD どうしと、同じ組の中だけである。同じ語を stride 0 と stride のある LD の両方で読むループや、違う stride で読むループでは、その語を2回以上 LD する。また、本体はどの反復でも同じなので、使われない値（格納しない状態の、最後の反復の次の値）の LD と演算も残る。LD の数が list scheduling の DAG と同じになるのは、各配列を1つの stride で読み、すべての値を使うループで、下の記録のループはすべてこれに当たる。

状態はレジスタで渡す。状態 s の値は、反復 k ≥ 1 では次の値のノードの1反復前（back = 1）、反復 0 では初期値（Before）である。ループの後の値は、最後の反復の次の値である。`LoopGraph.states` が、各状態の初期値と次の値のノードを持つ。

依存は2つの形で持つ。

- `LoopGraph.expanded`: ループを完全に展開した `dag.Dag`（Before、反復ごとの Each、After の順）と、その各ノードの（ループのノード、反復）。辺は `Dag.build` と同じ規則で作る。データ依存と、語ごとの実行順の ST → LD・LD → ST・ST → ST である。#68 は、ループの前後の演算との制約、レジスタの生存期間、(bundle, スロット) → (反復、演算) の対応表をここから得る
- `LoopGraph.edges`: Each のノードどうしの依存 `LoopEdge(from, to, cycles, iterations, kind)`。意味は、すべての反復 k で `issue(to, k + iterations) >= issue(from, k) + cycles` である。`expanded` の辺のうち Each どうしのものを（from、to、反復の差、種類）でまとめて作る。modulo schedule はどの反復にも同じ時刻を使うので、ある反復で見つかった辺はすべての反復に課す。iterations = 0 の辺は小さい番号から大きい番号へ向かう。このため、依存の閉路は必ず iterations > 0 の辺を含む（RecMII の入力）

### 記録（既定の構成）

| ループ | Before | Each（1反復） | After | 反復をまたぐ依存（cycles、反復距離） | LD の合計（list scheduling の DAG） |
|---|---|---|---|---|---|
| `saxpy8`（i） | LD 1（`a`） | LD 2、MUL 1、ADD 1、ST 1 | - | なし | 1 + 2 × 8 = 17（17） |
| `fir4x8`（i） | LD 7（`h` 4、`x(0)`〜`x(2)` 3） | LD 1（`x(i+3)`）、MUL 4、ADD 3、ST 1 | - | `x(i+3)` の LD → MUL が3つ（2、1）（2、2）（2、3）。それぞれ `x(i+2)`・`x(i+1)`・`x(i)` の分で、閉路は無い | 7 + 1 × 8 = 15（15） |
| `matmul3`（i） | LD 9（`b`） | LD 3（`a`）、MUL 9、ADD 6、ST 3 | - | なし | 9 + 3 × 3 = 18（18） |
| `matmul3`（m） | LD 6、MUL 9（m = 0） | LD 6、MUL 9、ADD 9 | ST 9 | 状態ごとに ADD → 自分の ADD（1、1）。9つ | 6 + 6 × 2 = 18（18） |
| `product8`（i） | LD 1（`x(0)`） | LD 1、MUL 1 | ST 1 | MUL → 自分の MUL（2、1） | 1 + 1 × 7 = 8（8） |
| `dot8`（i） | LD 2、MUL 1 | LD 2、MUL 1、ADD 1 | ST 1 | ADD → 自分の ADD（1、1） | 2 + 2 × 7 = 16（16） |

依存の cycles は生産者のレイテンシーで、machine description から取る（`slow_units` では `product8` の MUL → MUL が 3）。反復をまたぐ依存の閉路（RecMII > 0）を持つのは、`matmul3`（m）、`product8`、`dot8` である。#68 の受入テストのうち、RecMII が II を決めるループは `product`、MUL 1つ・ADD 1つの累算は `dot` で作れる。

### ループの MAC への融合

machine に MAC があるとき（ISA mvp-1）、`LoopFusion.fuse` は、ループのグラフの MUL と ADD を MAC（`LoopNode.Mac(a, b, c)`。値は `a × b + c` の下位32bit）にする（#77）。規則は直線の DAG の融合（上の「[MAC への融合](#mac-への融合)」）と同じで、全ての反復に同時に当てはめる（[ADR-0019](DECISIONS.md)）。ループを融合するかは、Modulo Scheduling が MII で決める（下の「[MAC への融合と MII](#mac-への融合と-mii)」）。

| 形 | 扱い |
|---|---|
| MUL m の使用が、同じ場所の ADD a のオペランド（back = 0）の1つだけ | `Mac(m.a, m.b, a のもう1つのオペランド)` にする |
| m に、ほかの使用がある | 融合しない |
| `ADD(m, m)` | 融合しない（使用が2つ） |
| `ADD(m1, m2)` で、両方を融合できる | 後の MUL（番号の大きい方）を融合する |
| SUB | 融合しない |
| Before の MUL を Each の ADD が読む | 融合しない（場所が違う） |

- 使用は、全てのノードのオペランド（どの場所でも、どの back でも）、`Ref.initial`（反復 k < back の値）、状態の初期値と次の値（`LoopGraph.states`）で数える。次の反復が状態として読む MUL、ループの後の格納が読む MUL、どこからも読まれない状態の値の MUL は融合しない。状態は融合した後も、なくならないノードを指す
- `LoopFusion.pairs` が融合する組（MUL, ADD）を返す。MAC は ADD の場所と番号に入り、MUL を除いた後の番号を1つずつ詰める。各場所の番号はプログラムの順のままである。融合したグラフに、融合できる組は残らない。MAC のない machine では、同じグラフを返す
- `LoopGraph.expanded` は MAC を DAG の `Node.Mac` に展開し、データ依存の辺を3つのオペランドから作る。`LoopGraph.edges` もそこから作るので、MAC からの依存の cycles は MAC の L である

記録の6つのループを融合して展開すると、展開したカーネルの Phase 2 の DAG を融合したもの（`Fusion.fuse`）と、ノードも辺も一致する（mvp-1 の3構成）。

| ループ | 融合する組 | Before | Each（1反復） | After | 反復をまたぐ依存（cycles、反復距離） |
|---|---|---|---|---|---|
| `saxpy8`（i） | 1 | LD 1 | LD 2、MAC 1、ST 1 | - | なし |
| `fir4x8`（i） | 3 | LD 7 | LD 1、MUL 1、MAC 3、ST 1 | - | `x(i+3)` の LD → MUL（2、3）、LD → MAC（2、2）（2、1） |
| `matmul3`（i） | 6 | LD 9 | LD 3、MUL 3、MAC 6、ST 3 | - | なし |
| `matmul3`（m） | 9 | LD 6、MUL 9 | LD 6、MAC 9 | ST 9 | 状態ごとに MAC → 自分の MAC（L_MAC、1）。9つ |
| `product8`（i） | 0 | LD 1 | LD 1、MUL 1 | ST 1 | MUL → 自分の MUL（2、1） |
| `dot8`（i） | 1 | LD 2、MUL 1 | LD 2、MAC 1 | ST 1 | MAC → 自分の MAC（L_MAC、1） |

`fir4x8` と `matmul3`（i）は、各出力の和の最初の MUL を残す（直線の DAG と同じ）。累算の状態（`matmul3`（m）と `dot8`）は MAC になり、閉路が ADD の 1 から L_MAC に延びる。

### テスト

- `compiler/.../loop/LoopKernelSpec`
  - ベンチマークのループを展開すると、Phase 2 のカーネルとまったく同じになること（いくつかの大きさで。語でない定数の正規化を含む）
  - IR に、ループの段、反復回数、添字、状態が残ること
  - `product` と `dot` の値。`matmulAcc` の、各反復の開始時の状態の値（部分和）
  - 自分の状態を2回使う次の値でも、展開した式と配置が反復回数に対して線形のままであること
  - 不正なループを、組み立て時に拒否すること
- `compiler/.../loop/LoopGraphSpec`
  - ベンチマークのループのグラフを展開すると、展開したカーネルの Phase 2 の DAG と、ノードも辺も一致すること（3構成）
  - 上の表の値
  - 展開したグラフの実行結果と `LoopLowering.evaluate` の結果が、DSL の参照実装と一致すること。各反復の ST の値と、各反復の後の状態の値も一致すること。`LoopGraph.states` が各状態の初期値と次の値を指すこと。対象は、ベンチマークと、乱数で作った300のループ（stride −2〜2、1〜6反復、状態0〜2、本体のストアと後の格納）
  - 各配列を1つの stride で読むループは、各語を1回だけ LD すること（Phase 2 の DAG と同じ LD の数）
  - 使われない値だけが読む定数（次の値と初期値）を、出力の後の語に置くこと
  - 読み書きする語が重なる、手作りの15のループ（3構成）。左へのずらし、メモリを通る漸化式、毎反復同じ語への ST、stride 0 で読んで書く語、本体が書く語を同じ stride で2回読む LD、同じ反復の ST の後の LD、ST をはさんだ同じ語の2つの LD、ループの前の LD・後の格納と本体の LD・ST、反復の開始時の状態を読む次の値と ST。Each どうしの依存（cycles は machine description から）と、Before・After のノードとのメモリの依存が期待どおりであること。展開した辺を守るどの順序（乱数で作る）で実行しても、`LoopLowering.evaluate` の結果と一致すること。`LoopLowering.evaluate` の値を、手で計算した値とも比べる
  - 不正な `Loop` を拒否すること
- `compiler/.../loop/LoopFusionSpec`（#77）
  - 融合した記録のループを展開すると、Phase 2 の DAG を融合したものとノードも辺も一致すること、組と MAC の数、上の表の値（mvp-1 の3構成）
  - 手作りの13のループ。融合する形（MUL＋ADD、反復をまたぐオペランドを持つ MUL、`ADD(MUL, MUL)`、ループの前の初期値）の融合したグラフと、融合しない形（状態として次の反復が読む MUL、同じ反復と次の反復が読む MUL、読まれない状態の次の値、ループの後の格納が読む MUL、状態の初期値、ループの前の MUL、`ADD(m, m)`、SUB、ST も読む MUL）。展開した辺を守る乱数の順序で実行した結果が `LoopLowering.evaluate` と一致すること。MAC のない machine では融合しないこと
  - 乱数で作った300のループ（`RandomLoops`。`LoopGraphSpec` と同じ）。組が、ノードから数えた規則と一致すること。融合しない展開で、融合する MUL の各インスタンスを読むのが同じ反復の ADD だけであること。展開した結果が `LoopLowering.evaluate` と一致すること

## Modulo Scheduling

`LoopGraph`（「ループ IR」）の反復を重ねてスケジュールし、mvp-0 の直線の命令列（mvp-1 では MAC を含む）に展開する（ADR-0011、[ADR-0015](DECISIONS.md#adr-0015-modulo-scheduling-は-ii-を下限から順に試し最初に命令列ができた-ii-を使う)、#68）。実装は `vliw.compiler.modulo`。ISA mvp-2 の machine は、#110 の第5段まで理由付きで拒否する（`ModuloCompiler.notImplemented`。入力の誤り）。

| 段 | 内容 | 実装 |
|---|---|---|
| MAC への融合 | machine に MAC があるとき、MII が大きくならないループだけ、MUL＋ADD を MAC にする | `FusionChoice`、`LoopFusion`（#77） |
| MII | 1反復の資源（ResMII）と、反復をまたぐ依存の閉路（RecMII） | `Mii` |
| II の探索 | II = MII から、1反復だけのスケジュールの長さまで順に試す | `ModuloScheduler.schedule` |
| 予約表への配置 | Each のノードを、II 行の予約表（MRT）に置く | `ModuloScheduler.place`、`RowFit` |
| 展開 | 全反復と、ループの前後のノードを、絶対サイクルに置く | `ModuloScheduler.expand` |
| レジスタとコード | 展開した命令列のレジスタ割り当て、エンコード、verifier の最終検査 | `RegisterAllocator.allocate`、`ModuloCompiler.compile` |

### MII

- **ResMII**: 1サイクルに1回だけ使える資源ごとの、1反復の使用数の最大。資源は、Compute スロット（ADD・SUB・MUL、mvp-1 では MAC も）、Memory スロット（LD・ST）、各演算の演算器（1回の発行が演算器の II だけ占める。L9。使用数は「演算の数 × II」）、Compute と Memory の書戻しポート（L5。ST は使わない）、3本目の読出しポート（下記）。演算のスロットと書戻しポートは machine description から取る。`Mii.bounding` が最大の資源を挙げる
- **3本目の読出しポート**（`third read port`、#77）: MAC があり `read_ports` が3の machine では、MAC と ST を同じ bundle に置けない（L11。MAC は3つ、ST は1つを読む）。これを、各 MAC と各 ST が発行サイクルに1回使う資源とする（ADR-0018 の「3本目の読出しポート（RTL のポート2）」）。`read_ports` が4の machine と mvp-0 には、この資源は無い。`Mii.withoutReadPort` は、この資源を除いた値である
- **RecMII**: `LoopGraph.edges` のどの閉路も `Σcycles − II × Σiterations ≤ 0` となる最小の II（Bellman-Ford で正の閉路を調べる）。閉路が無ければ 0。MAC の L は、MAC からの辺の cycles として入る
- **MII** = max(ResMII, RecMII, 1)

### MAC への融合と MII

machine に MAC があるとき、`ModuloCompiler` はループのグラフを融合したもの（「[ループの MAC への融合](#ループの-mac-への融合)」）と融合しないものの MII を比べ、融合した後の MII が大きくならないときだけ、融合したグラフをスケジュールする（ADR-0019）。

- MII は3本目の読出しポートを除いて比べる。このため `read_ports` 3 と 4 は同じグラフをスケジュールし、cycles の差は L11 の制約だけから生じる
- 判定は `ModuloCompiled.fusion`（`FusionChoice`）に残る。融合する組の数、融合しないときと融合したときの `Mii`（3本目の読出しポートを含む）、比べた2つの MII（`unfusedMII`、`fusedMII`）、融合したか（`fuses`）である。MAC のない machine では None で、グラフは変えない。`ModuloCompiler.prepare` は、スケジュールするグラフと判定を返す
- 融合すると、累算の閉路は ADD の 1 から L_MAC に延びる。`dot8` は、L_MAC 2 では MII 2（融合しなくても 2）なので融合し、L_MAC 3 では RecMII 3 > 2 なので融合しない
- 判定は MII だけで行う。配置やレジスタのために、融合した後の II が融合しない II より大きくなることはありうる

### 予約表への配置

予約表は II 行で、反復 k の演算 n は `start + t(n) + k × II` に発行する。時刻 t の演算は、スロットを行 t mod II、演算器を行 t〜t + II_op − 1、書戻しポートを行 (t + L) mod II で使う。3本目の読出しポートのある machine では、MAC と ST はその資源も行 t mod II で使う。

- Each のノードを番号の順に置く。ノード n の最早は、置いたノードからの辺 `t(from) + cycles − II × iterations` の最大（0 以上）、最遅は、n から置いたノードへの辺 `t(to) − cycles + II × iterations` の最小である
- 最早から II サイクルの範囲（最遅まで）で、予約表の行が空いている最初の時刻に置く。後戻りはしない
- これで置けないときは、もう一度、各時刻に「同じスロットの種類の残りのノードが、まだ予約表に収まる」条件を足して置き直す（先読み。`RowFit`）。先読みなしで置けたときは、先読みしても同じ結果になる
- 予約表に収まるかは、`RowFit` が正確に判定する。行を順に、どの演算をそこに置くかを決める深さ優先探索で、失敗した状態を覚える（状態は、残りの演算の数と、まだ影響する行の使用だけ）。II が62を超えるとき、または探索が `RowFit.Budget`（10万手）を超えたときは打ち切り、「決まらない」とする。決まらないとき、その II を予約表なし（(a)）とは記録せず、先読みはその時刻を受け入れる（以降はその II の先読みをしない）
- 判定はスロットの種類ごとに行う。3本目の読出しポートのある machine で、MAC と ST の両方が残っているときは、そのポートが2つの種類を結び付ける。このときは、まず種類ごとに判定し（どちらかが収まらなければ収まらない）、両方が収まれば、2つの種類を一緒に探索して判定する（`RowFit.fitsTogether`。行ごとに、各種類がそこに置く演算を決め、MAC と ST は同じ行に置かない）。この探索も正確で、打ち切りの条件は同じである。先読みも、このときは両方の種類の残りのノードを調べる。どちらかだけが残っているときは、置いた MAC と ST の行を、残りの ST と MAC が使えない行として種類ごとに判定する。MAC か ST を置くときの先読みは、もう一方の種類の残りのノードも調べる（最後の MAC の行が、残りの ST の行を奪わないように。#77）
- ADR-0018 のとおり、3本目の読出しポートのある machine で MAC と ST の両方があり、どの種類にも予約表があるときは、2つの種類を一緒に探索して判定する（`RowFit.fitsTogether`）。仮にこの場合を「決まらない」とすると先読みが止まり、`read_ports` 3 で `fir4x8`（i）は II 5、`matmul3`（i）は II 10 になる（一緒に探索すると、どちらも `read_ports` 4 と同じ II 4 と 9。下の「結果（mvp-1、#77）」）

### II の探索と結果

II ごとの結果は次のどれかで、`ModuloSchedule.attempts` に記録する。最初に「収まる」になった II を使う。cycles は II について単調ではないので、cycles 最小の II とは限らない。

| 結果 | 意味 | #68 の原因 |
|---|---|---|
| 予約表なし（`NoMrt`） | 1反復の演算が II 行の予約表に収まらない（`RowFit` が依存を無視して判定する）。ポートと演算器を外して判定し直し、衝突する資源の組（スロット、スロット + 書戻しポート、スロット + 演算器、…）を挙げる。3本目の読出しポートを外すと収まるときだけ、組に「+ third read port (L11)」を足す | (a) |
| 配置できない（`NoTime`） | 番号の順・後戻りなしの配置で、あるノードの範囲に置ける時刻が無い。ふつうは予約表があるときだが、配置の前の、1反復全体の予約表の判定を `RowFit` が打ち切ったときは、予約表があるかが決まっていない（理由に「whether a reservation table exists is not decided」と書く）。先読みでの打ち切りは先読みを止めるだけで、この注は付けない。最遅が無いときは範囲の上端を空けて書く | (a) と区別する |
| レジスタ（`TooManyLive`） | 展開した命令列の、同時に生きる値がレジスタ数を超える | (b) |
| 命令領域（`Overflow`） | 展開した命令列が `instruction_memory.bundles` を超える | - |
| 収まる（`Fits`） | | |

探索の上限は、Each のノードだけを番号の順に、反復の中の依存と絶対サイクルの資源で list scheduling した1反復の長さ（`ModuloScheduler.oneIteration`、最後の演算のレイテンシーと演算器の II の大きい方まで）である。この II では反復が重ならないので、配置に失敗しても、この1反復のスケジュールが modulo schedule になる。上限まで収まらなければ、カーネル名、反復回数、ループの段と、II ごとの理由を含むエラーにする（命令列は出力しない）。spill と rematerialization はしない。

### 展開とレジスタ

- Each の各反復を `t(n) + k × II` に置く
- Before のノードは、番号の逆順に、使う側（置いたノード）の許す最も遅い時刻から前に、スロット・演算器・書戻しポート（と3本目の読出しポート）が空いている時刻に置く。After のノードは、番号の順に、作る側の許す最も早い時刻から後に置く。演算器は両側を調べる（前後どちらの発行とも II_op 以上離す。L9）
- 全体を、最初の発行が 0 になるようにずらす。そのずれが反復 0 の開始 `start` である。HALT は、全演算の完了 `max(issue + L)` に置く（L6）
- 依存は `LoopGraph.expanded` の辺（データ依存とメモリ順序）をすべて守る。展開した命令列は直線なので、各反復の値は別の仮想レジスタになる。レジスタは list scheduling と同じ `RegisterAllocator.allocate` で割り当てる
- ループのカーネルの命令列は、`ModuloCompiler.compile` が `KernelCompiler.checked` で verifier の最終検査に通す。一般の `Loop` を `ModuloScheduler.schedule` で直接スケジュールしたときは検査しないので、呼び出し側が入力宣言を与えて `KernelCompiler.verified` を呼ぶ（テストはそうしている）

`ModuloSchedule.map` は、発行する各演算の (bundle, スロット) → (ループのノード, 反復) の表である。mvp-0 では pc = サイクルなので、bundle の番号が発行サイクルになる。

### トレースによる II の確認

`crosscheck/ModuloTrace.check` は、参照モデルまたは RTL の `vliw-trace/1` のトレースを、上の表とループのグラフだけで照合する。スケジュール・レジスタ割り当て・展開した DAG は使わない（ADR-0002）。

1. 表が、全ノードの全反復をちょうど1回ずつ、その演算のスロットに挙げること
2. 各行の各スロットの `issue` が、表の演算の種類であること（表に無いスロットは NOP、最後の行の Compute は HALT）
3. LD・ST のアドレスが、そのノードのアドレス `addr.at(k)` であること
4. 各ソースレジスタについて、発行の行までで最後に commit した書込み（`rf_commit`。その行から見える）の発行元が、期待する演算であること。発行元は、そのポートに、その行で commit する、そのレジスタへの表の演算である。期待する演算は、オペランドの `Ref(node, back, initial)` から、同じ反復（back = 0）、back 反復前、k < back では初期値 `initial(k)`、ループの前のノード、ループの後なら最後の反復、と決める。ソースは位置で対応させる（rs1 が1つ目のオペランド）

照合に合格したら、Each の各ノードの、連続する反復の発行サイクルの差を求める。差がすべて同じで、それが記録した II と一致すれば、その値を返す。そうでなければ失敗である。ベンチマークの記録（[benchmarks/MODULO.md](../benchmarks/MODULO.md)）の `metrics` の `ii` 欄は、この値である。

### 結果（既定の構成）

| ループ | ResMII（律速） | RecMII | II | II を上げた理由 | cycles |
|---|---|---|---|---|---|
| `saxpy8`（i） | 3（Memory スロット） | 0 | 3 | - | 29 |
| `fir4x8`（i） | 7（Compute スロットと書戻しポート） | 0 | 8 | II 7: (a) | 76 |
| `matmul3`（i） | 15（同上） | 0 | 20 | II 15: (a)、II 16〜19: (b) | 87 |
| `matmul3`（m） | 18（同上） | 1 | 27 | II 18: (a)、II 19〜26: (b) | 68 |
| `product8`（i） | 1 | 2 | 2 | - | 19 |
| `dot8`（i） | 2 | 1 | 3 | II 2: (a)（スロットと書戻しポート） | 28 |

- `dot8` の II 2: Compute スロットが1つなので、MUL(s) と ADD(t) は s と t が別の行（mod 2）になる。MUL は s + 2、ADD は t + 1 に書戻しポートを使い、2つの行はどちらも MUL と ADD のポートの行と重なる
- `fir4x8` の II 8: 番号の順に置くと、ノード14の時刻が無い。先読みすると置ける。そのとき ADD（ノード11、最早 8）は、10 では次の反復の MUL（ノード7、t = 1）と同じ境界 E_11 に書戻すので、12 に置く
- `matmul3` の2つのループ: 予約表のある II でも、同時に生きる値が16を超えるので II を上げる（(b)）。`matmul3`（i）は、II 20 で 87 cycles、II 21 なら 70 cycles で、cycles は II について単調でない
- 6つのループ × 3構成（既定、`slow_units`、`ii_units`）と `regs3` の2つのループの値、list scheduling との比較、`saxpy(n)` の系列は [benchmarks/MODULO.md](../benchmarks/MODULO.md) にある。`slow_units` では `matmul3`（m）はどの II でも17の値が同時に生き、16 レジスタに収まらない（不成立）

### 結果（mvp-1、#77）

mvp-1 の3つの構成は、list scheduling の節と同じ `config/machine-mvp1.json`（`read_ports` 3、MAC の L 2）、`tests/machines/mvp1_rp4.json`（`read_ports` 4）、`tests/machines/mvp1_mac3.json`（MAC の L 3）である。融合の判定と MII は次のとおり。ResMII と RecMII は融合したグラフの値で、融合しない `product8` と、L 3 の `dot8` は融合しないグラフの値である。

| ループ | 融合する組 | MII（融合なし → あり。L 2 / L 3） | ResMII（律速。3本目の読出しポートの使用） | RecMII（L 2 / L 3） |
|---|---|---|---|---|
| `saxpy8`（i） | 1 | 3 → 3 / 3 → 3 | 3（Memory スロット。2） | 0 / 0 |
| `fir4x8`（i） | 3 | 7 → 4 / 7 → 4 | 4（Compute スロットと書戻しポート、3本目の読出しポート。4） | 0 / 0 |
| `matmul3`（i） | 6 | 15 → 9 / 15 → 9 | 9（同上。9） | 0 / 0 |
| `matmul3`（m） | 9 | 18 → 9 / 18 → 9 | 9（同上と MAC の演算器。9） | 2 / 3 |
| `product8`（i） | 0 | 2 / 2 | 1 | 2 / 2 |
| `dot8`（i） | 1 | 2 → 2 / 2 → 3（L 3 では融合しない） | 2（L 2 では Memory スロットと書戻しポート、LD の演算器。1） | 2 / 1 |

II と cycles（II / cycles）。括弧の中は、II を MII より上げた理由である。

| ループ | mvp-0（既定） | mvp-1 | `read_ports` 4 | MAC の L 3 |
|---|---|---|---|---|
| `saxpy8`（i） | 3 / 29 | 3 / 29 | 3 / 29 | 3 / 32 |
| `fir4x8`（i） | 8 / 76 | 4 / 46 | 4 / 43 | 5 / 60（II 4: (a)） |
| `matmul3`（i） | 20 / 87 | 9 / 38 | 9 / 45 | 10 / 39（II 9: (a)） |
| `matmul3`（m） | 27 / 68 | 9 / 40 | 9 / 34 | 9 / 40 |
| `product8`（i） | 2 / 19 | 2 / 19 | 2 / 19 | 2 / 19 |
| `dot8`（i） | 3 / 28 | 2 / 21 | 2 / 21 | 3 / 28 |

- ResMII は ADR-0018 の見積りどおり、`fir4x8`（i）で 7 → 4、`matmul3`（i）で 15 → 9、`matmul3`（m）で 18 → 9 になる。`matmul3`（m）の RecMII は L_MAC になるが、ResMII より小さい。3本目の読出しポートの使用は、`fir4x8`（i）で 4（MAC 3 + ST 1）、`matmul3`（i）で 9（MAC 6 + ST 3）で、Compute スロットの使用と等しい。このため、どのループでも、3本目の読出しポートを除いた ResMII は同じ値である
- `read_ports` 3 と 4 は、どのループでも同じ II になる。3 では、予約表の ST の行が MAC の行と重ならない。`fir4x8`（i）の II 4 では、ST は MUL の行に置くしかない。番号の順に置くと LD と MUL が行 0 を取り、ST の行が無くなるので、先読みで置く（1反復の長さは 14 サイクル。4 では番号の順で置け、10 サイクル）。`matmul3`（m）は、ループの後の ST が MAC と同じ bundle に入れない（4 では6つの bundle で重なる）
- `matmul3`（i）は、`read_ports` 4 の方が 7 cycles 多い。4 では番号の順の配置が II 9 で成功し、1反復が 23 サイクルになる。3 では番号の順の配置が失敗し、先読みのある配置が1反復を 15 サイクルに置く。制約の効果ではなく、配置の手順の効果である（配置も、cycles 最小を探さない。ADR-0015）
- MAC の L 3 では、`fir4x8`（i）の II 4 と `matmul3`（i）の II 9 に予約表が無い（スロットと書戻しポート）。Compute の行を全て使うと、書戻しポートの行も全てを1回ずつ使うので、Compute の演算の L の和が II の倍数でなければならない。`fir4x8` は 2 + 3 × 3 = 11、`matmul3`（i）は 3 × 2 + 6 × 3 = 24 で、そうならない。`dot8` は融合しない
- 4つの構成の記録（テストケースとメトリクス）と、配置の手順、`read_ports` 4 で MAC と ST が同じ bundle に入る数は [benchmarks/MAC.md](../benchmarks/MAC.md) にある

### テスト

- `compiler/.../modulo/ModuloSchedulerSpec`
  - 6つのループ × 3構成（既定、`slow_units`、`ii_units`）の ResMII と律速の資源、RecMII、II、探索の上限、開始、cycles、II ごとの理由。`slow_units` の `matmul3`（m）は不成立なので、ResMII と律速の資源、RecMII、探索の上限と、II ごとの理由（エラー）
  - 全スケジュールを、スケジューラと独立に確かめる。展開した辺、サイクルごとのスロット、演算器の II（L9）、書戻しポート（L5）、読出しポート（L11）、HALT（L6）、`start + t + k × II`、表
  - 手で計算した表: `product(4)`（3反復、II 2 = RecMII）と `dot(4)`（3反復、II 3 > MII 2）の、各演算の bundle とスロット
  - 先読みが要る配置（`fir4x8` の II 8）と、次の反復の書戻しポートによる移動。移動しないと verifier が L5 で拒否すること
  - 探索の上限で、1反復のスケジュールが modulo schedule であること。配置がすべての II で失敗すると、上限の II で1反復のスケジュールを使い、verifier に合格すること
  - 打ち切り: SUB のレイテンシー5、MUL のレイテンシー8の構成の、36演算の鎖のループ。予約表があるかが決まらない II を (a) と記録せず、1分以内に II 44 を見つけること
  - レジスタ3本（`tests/machines/regs3.json`）: `saxpy8` は II 3〜5 で4つの値が生き、II 6 で収まる。`dot8` はどの II にも収まらず、理由付きのエラーになる
  - 命令領域: `saxpy(83)` は 254 bundle で収まり、`saxpy(84)` は 257 bundle で、カーネル名・反復回数・bundle 数・容量を含むエラーになる。`saxpy(n)`（n = 8〜64）は II 3、3n + 5 cycles。`dot(84)` はちょうど 256 bundle で収まり、`dot(85)` は 259 bundle で収まらない。命令領域を 80 bundle にすると、`matmul3`（i）は II 20 で 87 bundle になって収まらず、探索を続けて II 21（70 bundle）を使う
  - SUB が Compute スロットと書戻しポートと SUB の演算器を使うこと（ResMII 3）
  - 手作りの `Loop`。メモリを通る依存のある5つ（反復距離2の依存を含む）と、メモリを通る依存は無いが、ループの状態を後のノードが読む2つ（1つは、反復をまたぐ辺が、それが値を渡す反復の中の辺より後に並ぶ）。RecMII、`NoTime`、cycles、verifier
  - mvp-1（#77）: 6つのループ × mvp-1 の3構成の値（上の表。3本目の読出しポートを除いた ResMII と、融合の判定を含む）。MAC のない machine では判定が無いこと。`dot8` の融合の判定（L 2 で融合して II 2、L 3 で融合しない）。3本目の読出しポートの使用（`read_ports` 4 と mvp-0 には無い）。`fir4x8` の II 4 で、番号の順の配置が ST の行で失敗し、先読みで置けること（`read_ports` 4 では番号の順で置ける）。3本目の読出しポートだけで予約表が無い II（MAC と ST が1つずつで II 1）と、その名前。融合の判定が3本目の読出しポートを除くこと（融合すると `read_ports` 3 で MII が 4 から 5 に上がるループが、3本でも4本でも融合すること。乱数の300のループで、3本と4本が同じグラフをスケジュールすること）。同じループの II 5 で、最後の MAC を置く先読みが残りの ST の行を調べること
  - mvp-1: 命令列を ISA の意味論で実行した結果（テストの中の簡単な実行）が DSL の参照実装と一致すること。MAC のノードごとに `mac` があり、`read_ports` 3 では MAC と ST が同じ bundle に無いこと。対象は記録のループと、乱数で作った300のループ（3構成。mvp-0 でどの II にも収まらない4つは、mvp-1 でも収まらない）
- `compiler/.../modulo/RowFitSpec`: `RowFit` を、行への割り当てを全て試す方法と、すでに使われた行のある3000の乱数の表で比べる。使用済みのポートと演算器を数えること。62行を超えるときと、手数の上限を超えたときに `None`（決まらない）を返すこと。種類ごとに発行できない行（3本目の読出しポート）。`fitsTogether` を、全て試す方法と1000の乱数の表（2つの種類と3本目の読出しポート）で比べること。種類ごとには収まるが一緒には収まらない例（#77）
- `crosscheck/ModuloKernelSpec`
  - 参照モデル（3構成とレジスタ3本）: 結果が DSL の参照実装と、cycles が予測と一致し、トレースの照合で II が記録と一致すること。RTL（既定の構成）でも同じ。既定の構成では、[benchmarks/MODULO.md](../benchmarks/MODULO.md)「反復回数の系列」の `saxpy(n)`（命令列ができる n）も確かめる（#119）。`slow_units` と `ii_units` は RTL の対応範囲外なので、理由を付けてスキップと表示する
  - 手で計算したトレース: `product(4)` と `dot(4)` の、各行の発行、書戻しのポート、メモリへの commit、cycles
  - 上の手作りの `Loop` を参照モデルで実行し、メモリが `LoopLowering.evaluate` と一致し、トレースの照合に合格すること
  - トレースの照合が、表の反復の入れ替え、初期値の誤り、LD と ST のアドレスの誤り、ソースの入れ替え、別の演算が書いたレジスタを読むソース（ADD と ST）、1行ずれた commit、別のポートの commit、表と違う種類の演算、表に無いスロットの演算、状態の反復距離の誤り、記録と違う II を見つけること
  - 正しいが、反復が II ごとに始まらない命令列を拒否すること: `dot(4)` を展開して list scheduling した命令列（表はトレースと一致する）と、`saxpy8` の最後の ST を1サイクル遅らせた命令列
- `crosscheck/BenchmarkSpec`: [benchmarks/MODULO.md](../benchmarks/MODULO.md) と、Modulo Scheduling のテストケース・メトリクスが生成し直した内容と一致すること。テストケースが参照モデルと RTL で成り立つこと。配置と入力が list scheduling と同じで、`metrics` の `ii` が記録した II であること
