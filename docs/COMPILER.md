# 命令生成

カーネルDSL（[DSL.md](DSL.md)）から命令列を作る処理の仕様。実装は sbtプロジェクト `compiler`。順序は次のとおりで、各段の出力が次の段の入力になる。

| 段 | 入力 → 出力 | 実装 | Issue |
|---|---|---|---|
| メモリ配置・lowering | `Kernel` → `LoweredKernel`（アドレス単位の式） | `vliw.compiler.dsl` | #47 |
| DAG | `LoweredKernel` → `Dag` | `vliw.compiler.dag` | #48 |
| list scheduling | `Dag` → `Schedule`（各ノードの発行サイクルと HALT。値は仮想レジスタ） | `vliw.compiler.schedule` | #49 |
| register allocation | `Dag` → `Allocated`（スケジュールと物理レジスタ、bundle 列） | `vliw.compiler.regalloc` | #50 |
| コード生成と検証 | → 命令語（verifier の最終検査に通ったもの）、`.hex`、テストケース | `vliw.compiler.KernelCompiler` | #51 |

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

現在の DSL は入力が読出し専用、出力が書込み専用なので、メモリ順序の辺は生じない。lowering 後の一般の形に対して正しく作り、手作りの例でテストする。

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

試行は、各優先度の上限なし、次に各優先度で `loadReserve` を 0 からレジスタ数の半分まで変えたもの。成功した試行のうち cycles が最小のものを選び、同じならば先の試行を選ぶ。結果は決定的である。上限付きで成功したスケジュールは、生きている値の数が上限以下なので、割り当ては必ず成功する。

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
