# 命令生成

カーネルDSL（[DSL.md](DSL.md)）から命令列を作る処理の仕様。実装は sbtプロジェクト `compiler`。順序は次のとおりで、各段の出力が次の段の入力になる。

| 段 | 入力 → 出力 | 実装 | Issue |
|---|---|---|---|
| メモリ配置・lowering | `Kernel` → `LoweredKernel`（アドレス単位の式） | `vliw.compiler.dsl` | #47 |
| DAG | `LoweredKernel` → `Dag` | `vliw.compiler.dag` | #48 |
| list scheduling | `Dag` → `Schedule`（各ノードの発行サイクルと HALT。値は仮想レジスタ） | `vliw.compiler.schedule` | #49 |
| register allocation | `Dag` → `Allocated`（スケジュールと物理レジスタ、bundle 列） | `vliw.compiler.regalloc` | #50 |
| コード生成と検証 | → 命令語（verifier の最終検査に通ったもの）、`.hex`、テストケース | `vliw.compiler.KernelCompiler` | #51 |

Phase 3 の Modulo Scheduling（#68）の入力になる、ループ構造を保つ IR は「[ループ IR](#ループ-ir)」の節に（#76）、そのスケジューリングは「[Modulo Scheduling](#modulo-scheduling)」の節にある（#68）。

## DAG

`Dag.build(machine, writes)`（または `Dag.of(lowered, machine)`）は、lowering した式から演算の DAG を作る。

### ノード

| ノード | 内容 |
|---|---|
| `Load(addr)` | LD。アドレスは即値なので静的に決まる |
| `Compute(op, a, b)` | ADD・SUB・MUL。オペランドはノード番号 |
| `Store(addr, value)` | ST。値はノード番号 |

値はまだ仮想値で、レジスタは決めていない。等しい値は1つのノードにまとめる（値番号付け）。

- LD は「アドレスと、そのアドレスへのそれまでの ST の数」で識別する。同じ語を2回読んでも LD は1つで、ST の後に読めば別の LD になる
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

### テスト

`compiler/src/test/.../dag/DagSpec`：ISA の命令例の DAG（ノード・辺・距離・下限）、machine description による距離の変化、値番号付け、メモリ順序の4種類の辺、ベンチマークのノード数と下限、辺が ASAP で満たされること、DAG をノード順に実行した結果が lowering の評価と一致すること。

## list scheduling

`ListScheduler.schedule(dag, priority)` は、サイクルの順に、準備のできたノードを優先度の高い順に置く。結果の `Schedule` は、各ノードの発行サイクルと HALT のサイクルを持つ。値はまだ仮想レジスタで、ノード n の結果を仮想レジスタ n とする（レジスタ割り当ては #50）。

### 守る制約

| 制約 | 方法 | 規則（[ISA.md](ISA.md)） |
|---|---|---|
| 依存 | 辺 `(from, to, d)` について、`to` は `issue(from) + d` 以降 | L3、L8、L10 |
| スロット | 各サイクルに Compute・Memory それぞれ高々1つ | 構造 |
| 書戻しポート | ADD/SUB/MUL は Compute ポートを、LD は Memory ポートを、境界 E_(s+L) に予約する。予約済みの境界を使う配置はしない（例: MUL(s) の後の ADD(s+1)） | L5 |
| II | 同じ演算の発行は II 以上空ける | L9 |
| HALT | 全操作が完了する境界 `max(s+L)` に置く。HALT の bundle には他の操作を置かない | L2、L6 |

同じレジスタへの書込み区間の重複（L4）と、書込み中のレジスタの読出し（L3 のうち、レジスタの再利用によるもの）は、レジスタ割り当て（#50）で扱う。レジスタの上限を与えると、生きている値の数も制約にする（下の「register allocation」）。

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

### テスト

- `compiler/.../schedule/ScheduleSpec`: スケジューラとは独立に書いた検査で、依存・スロット・書戻しポート・II・HALT の位置を確かめる。対象はベンチマーク3つ × 構成3つ（既定、`slow_units`、`ii_units`）× 優先度2つ。cycles が両方の下限以上であることと、上の表の値も確かめる
- 命令列にした結果の確認は、レジスタ割り当ての後で行う（下の「register allocation」のテスト）

## register allocation

`RegisterAllocator.scheduleAndAllocate(dag)` は、スケジュールと物理レジスタの割り当てを行い、命令列（`Allocation.bundles`、最後が HALT）を作る。spill はしない。

### 入出力とレジスタ

レジスタは入力にも出力にも使わない。入力・スカラー・定数は初期化済みの Scratchpad から LD し、出力は ST する（[DSL.md](DSL.md)「メモリ配置」）。R_in は空で、実行後のレジスタの値は規定しない。

### 生存区間

値 v（ST 以外のノードの結果）のレジスタは、v を書く操作の発行 s_v から、v を最後に読む操作の発行 u_v までの半開区間 `[s_v, u_v)` を占める。別の値の書込みは、u_v のサイクルから同じレジスタに置ける。

- L3: v の読出しはすべて u_v 以前で、新しい書込みの発行サイクル以前なので、書込み中の読出しにならない（u_v での読出しは発行サイクル自身の読出しで、旧値 v を読む）
- L4: v の書込み区間 `[s_v, s_v + L)` は、データ依存により u_v までに終わる
- 値: 新しい値は u_v + 1 以降に見えるので、v の読出しはすべて v を読む

### 割り当て

区間を開始の順に並べ、そのとき空いている最小番号のレジスタに割り当てる（区間グラフの貪欲彩色）。必要なレジスタの数は、同時に生きている値の最大数に等しい。

### 足りない場合の再スケジュール

最大数がレジスタ数を超えるときは、レジスタの上限付きで list scheduling をやり直す。上限付きのスケジューラは次のように置く。

- 値を作るノードは、置いた後に生きている値の数が上限以下のときだけ置く。そのノードが最後の読出しになるオペランドは、同じサイクルに解放されるものとして数える
- LD は、さらに `loadReserve` 本を空けておけるときだけ置く。読み込んだオペランドばかりでレジスタが埋まり、演算の結果を置けなくなることを防ぐ
- 生きている値が上限に近いときは、レジスタを多く解放するノードを優先する
- しばらくどのノードも置けなければ、その試行は失敗とする

試行は、各優先度の上限なし、次に各優先度で `loadReserve` を 0 からレジスタ数の半分まで変えたもの（`RegisterAllocator.attempts`）。成功した試行のうち cycles が最小のものを選び、同じならば先の試行を選ぶ。結果は決定的である。上限付きで成功したスケジュールは、生きている値の数が上限以下なので、割り当ては必ず成功する。

1つの試行だけを行うのは `RegisterAllocator.run(dag, attempt)` で、収まらない理由（`NoFit`）を返す。上限なしで同時に生きる値がレジスタ数を超える（`TooManyLive`）か、上限付きのスケジューラが行き詰まる（`Stuck`）かのどちらかである。ベンチマークの「戦略を固定した系列」（[benchmarks/README.md](../benchmarks/README.md)）は、全ての試行を `run` で行い、方式ごとの結果を並べる。

### 結果（16 レジスタ）

| 構成 | カーネル | cycles | 選ばれた試行 | 使ったレジスタ |
|---|---|---|---|---|
| 既定 | `saxpy8` | 26 | critical-path、上限なし | 11 |
| 既定 | `fir4x8` | 65 | program-order、上限16、reserve 4 | 16 |
| 既定 | `matmul3` | 56 | critical-path、上限16、reserve 1 | 16 |
| `slow_units` | `saxpy8` / `fir4x8` / `matmul3` | 27 / 70 / 60 | | 13 / 16 / 16 |
| `ii_units` | `saxpy8` / `fir4x8` / `matmul3` | 53 / 100 / 88 | | 9 / 13 / 16 |

既定の構成では、上限なしのスケジュール（63、52 cycles）に比べ、`fir4x8` は2、`matmul3` は4サイクル長い。上限なしでは、それぞれ33と28の値が同時に生きる。

### 制約

LD の共有（同じ語は1回だけ読む）を保ったまま上限を守るため、レジスタが少ない構成では失敗することがある。例えば、12本では `matmul3` が、8本では `fir4x8` と `matmul3` が、どの試行でも収まらない（`saxpy8` は8本で28 cycles）。このときは理由付きのエラーにする。値を捨てて再び LD する（rematerialization）ことは、今は行わない。

### テスト

- `compiler/.../regalloc/RegisterAllocatorSpec`
  - 区間の定義と、同じレジスタを使う値の区間が重ならないこと
  - 3構成のベンチマークで、割り当て後の命令列を verifier が合法と判定すること
  - 成功した全ての試行についても、verifier が合法と判定すること
  - 上の表の値、レジスタが足りないときのエラー
- `crosscheck/CompiledKernelSpec`: 割り当て後のベンチマークを参照モデル（3構成）と RTL（既定の構成）で実行し、結果が DSL の参照実装と、cycles がスケジュールと一致すること

## コード生成と最終検証

`KernelCompiler.compile(kernel, machine)` は、上の各段を順に行い、`CompiledKernel` を返す。

1. メモリ配置と lowering、DAG
2. スケジュールとレジスタ割り当て（`Allocation.bundles` が bundle 列を作る。最後は HALT）
3. エンコード
4. **独立の verifier（[VERIFIER.md](VERIFIER.md)）による最終検査**。入力宣言は、配置の初期化済みの語（M_in）だけで、R_in は空

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

カーネルごとに次の2つを書き、cycles・下限・選ばれた試行を表示する。終了コードは、入力の誤りが2、コンパイラの誤りが3。

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

## Modulo Scheduling

`LoopGraph`（「ループ IR」）の反復を重ねてスケジュールし、mvp-0 の直線の命令列に展開する（ADR-0011、[ADR-0015](DECISIONS.md#adr-0015-modulo-scheduling-は-ii-を下限から順に試し最初に命令列ができた-ii-を使う)、#68）。実装は `vliw.compiler.modulo`。

| 段 | 内容 | 実装 |
|---|---|---|
| MII | 1反復の資源（ResMII）と、反復をまたぐ依存の閉路（RecMII） | `Mii` |
| II の探索 | II = MII から、1反復だけのスケジュールの長さまで順に試す | `ModuloScheduler.schedule` |
| 予約表への配置 | Each のノードを、II 行の予約表（MRT）に置く | `ModuloScheduler.place`、`RowFit` |
| 展開 | 全反復と、ループの前後のノードを、絶対サイクルに置く | `ModuloScheduler.expand` |
| レジスタとコード | 展開した命令列のレジスタ割り当て、エンコード、verifier の最終検査 | `RegisterAllocator.allocate`、`ModuloCompiler.compile` |

### MII

- **ResMII**: 1サイクルに1回だけ使える資源ごとの、1反復の使用数の最大。資源は、Compute スロット（ADD・SUB・MUL）、Memory スロット（LD・ST）、各演算の演算器（1回の発行が演算器の II だけ占める。L9。使用数は「演算の数 × II」）、Compute と Memory の書戻しポート（L5。ST は使わない）。`Mii.bounding` が最大の資源を挙げる
- **RecMII**: `LoopGraph.edges` のどの閉路も `Σcycles − II × Σiterations ≤ 0` となる最小の II（Bellman-Ford で正の閉路を調べる）。閉路が無ければ 0
- **MII** = max(ResMII, RecMII, 1)

### 予約表への配置

予約表は II 行で、反復 k の演算 n は `start + t(n) + k × II` に発行する。時刻 t の演算は、スロットを行 t mod II、演算器を行 t〜t + II_op − 1、書戻しポートを行 (t + L) mod II で使う。

- Each のノードを番号の順に置く。ノード n の最早は、置いたノードからの辺 `t(from) + cycles − II × iterations` の最大（0 以上）、最遅は、n から置いたノードへの辺 `t(to) − cycles + II × iterations` の最小である
- 最早から II サイクルの範囲（最遅まで）で、予約表の行が空いている最初の時刻に置く。後戻りはしない
- これで置けないときは、もう一度、各時刻に「同じスロットの種類の残りのノードが、まだ予約表に収まる」条件を足して置き直す（先読み。`RowFit`）。先読みなしで置けたときは、先読みしても同じ結果になる
- 予約表に収まるかは、`RowFit` が正確に判定する。行を順に、どの演算をそこに置くかを決める深さ優先探索で、失敗した状態を覚える（状態は、残りの演算の数と、まだ影響する行の使用だけ）。II が62を超えるとき、または探索が `RowFit.Budget`（10万手）を超えたときは打ち切り、「決まらない」とする。決まらないとき、その II を予約表なし（(a)）とは記録せず、先読みはその時刻を受け入れる（以降はその II の先読みをしない）

### II の探索と結果

II ごとの結果は次のどれかで、`ModuloSchedule.attempts` に記録する。最初に「収まる」になった II を使う。cycles は II について単調ではないので、cycles 最小の II とは限らない。

| 結果 | 意味 | #68 の原因 |
|---|---|---|
| 予約表なし（`NoMrt`） | 1反復の演算が II 行の予約表に収まらない（`RowFit` が依存を無視して判定する）。ポートと演算器を外して判定し直し、衝突する資源の組（スロット、スロット + 書戻しポート、スロット + 演算器、…）を挙げる | (a) |
| 配置できない（`NoTime`） | 番号の順・後戻りなしの配置で、あるノードの範囲に置ける時刻が無い。ふつうは予約表があるときだが、`RowFit` が打ち切ったときは予約表があるかが決まっていない（理由に「whether a reservation table exists is not decided」と書く。最遅が無いときは範囲の上端を空けて書く） | (a) と区別する |
| レジスタ（`TooManyLive`） | 展開した命令列の、同時に生きる値がレジスタ数を超える | (b) |
| 命令領域（`Overflow`） | 展開した命令列が `instruction_memory.bundles` を超える | - |
| 収まる（`Fits`） | | |

探索の上限は、Each のノードだけを番号の順に、反復の中の依存と絶対サイクルの資源で list scheduling した1反復の長さ（`ModuloScheduler.oneIteration`、最後の演算のレイテンシーと演算器の II の大きい方まで）である。この II では反復が重ならないので、配置に失敗しても、この1反復のスケジュールが modulo schedule になる。上限まで収まらなければ、カーネル名、反復回数、ループの段と、II ごとの理由を含むエラーにする（命令列は出力しない）。spill と rematerialization はしない。

### 展開とレジスタ

- Each の各反復を `t(n) + k × II` に置く
- Before のノードは、番号の逆順に、使う側（置いたノード）の許す最も遅い時刻から前に、スロット・演算器・書戻しポートが空いている時刻に置く。After のノードは、番号の順に、作る側の許す最も早い時刻から後に置く。演算器は両側を調べる（前後どちらの発行とも II_op 以上離す。L9）
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

### テスト

- `compiler/.../modulo/ModuloSchedulerSpec`
  - 6つのループ × 3構成（既定、`slow_units`、`ii_units`）の ResMII と律速の資源、RecMII、II、探索の上限、開始、cycles、II ごとの理由
  - 全スケジュールを、スケジューラと独立に確かめる。展開した辺、サイクルごとのスロット、演算器の II（L9）、書戻しポート（L5）、HALT（L6）、`start + t + k × II`、表
  - 手で計算した表: `product(4)`（3反復、II 2 = RecMII）と `dot(4)`（3反復、II 3 > MII 2）の、各演算の bundle とスロット
  - 先読みが要る配置（`fir4x8` の II 8）と、次の反復の書戻しポートによる移動。移動しないと verifier が L5 で拒否すること
  - 探索の上限で、1反復のスケジュールが modulo schedule であること。配置がすべての II で失敗すると、上限の II で1反復のスケジュールを使い、verifier に合格すること
  - 打ち切り: SUB のレイテンシー5、MUL のレイテンシー8の構成の、36演算の鎖のループ。予約表があるかが決まらない II を (a) と記録せず、1分以内に II 44 を見つけること
  - レジスタ3本（`tests/machines/regs3.json`）: `saxpy8` は II 3〜5 で4つの値が生き、II 6 で収まる。`dot8` はどの II にも収まらず、理由付きのエラーになる
  - 命令領域: `saxpy(83)` は 254 bundle で収まり、`saxpy(84)` は 257 bundle で、カーネル名・反復回数・bundle 数・容量を含むエラーになる。`saxpy(n)`（n = 8〜64）は II 3、3n + 5 cycles。`dot(84)` はちょうど 256 bundle で収まり、`dot(85)` は 259 bundle で収まらない。命令領域を 80 bundle にすると、`matmul3`（i）は II 20 で 87 bundle になって収まらず、探索を続けて II 21（70 bundle）を使う
  - SUB が Compute スロットと書戻しポートと SUB の演算器を使うこと（ResMII 3）
  - メモリを通る依存のある手作りの `Loop`（反復距離2の依存、ループの状態を後のノードが読むもの、状態と配列の両方を書くものを含む。RecMII、`NoTime`、cycles、verifier）
- `compiler/.../modulo/RowFitSpec`: `RowFit` を、行への割り当てを全て試す方法と、すでに使われた行のある3000の乱数の表で比べる。使用済みのポートと演算器を数えること。62行を超えるときと、手数の上限を超えたときに `None`（決まらない）を返すこと
- `crosscheck/ModuloKernelSpec`
  - 参照モデル（3構成とレジスタ3本）: 結果が DSL の参照実装と、cycles が予測と一致し、トレースの照合で II が記録と一致すること。RTL（既定の構成）でも同じ。`slow_units` と `ii_units` は RTL の対応範囲外なので、理由を付けてスキップと表示する
  - 手で計算したトレース: `product(4)` と `dot(4)` の、各行の発行、書戻しのポート、メモリへの commit、cycles
  - メモリを通る依存のある手作りの `Loop` を参照モデルで実行し、メモリが `LoopLowering.evaluate` と一致し、トレースの照合に合格すること
  - トレースの照合が、表の反復の入れ替え、初期値の誤り、LD と ST のアドレスの誤り、ソースの入れ替え、別の演算が書いたレジスタを読むソース（ADD と ST）、1行ずれた commit、別のポートの commit、表と違う種類の演算、表に無いスロットの演算、状態の反復距離の誤り、記録と違う II を見つけること
  - 正しいが、反復が II ごとに始まらない命令列を拒否すること: `dot(4)` を展開して list scheduling した命令列（表はトレースと一致する）と、`saxpy8` の最後の ST を1サイクル遅らせた命令列
- `crosscheck/BenchmarkSpec`: [benchmarks/MODULO.md](../benchmarks/MODULO.md) と、Modulo Scheduling のテストケース・メトリクスが生成し直した内容と一致すること。テストケースが参照モデルと RTL で成り立つこと。配置と入力が list scheduling と同じで、`metrics` の `ii` が記録した II であること
