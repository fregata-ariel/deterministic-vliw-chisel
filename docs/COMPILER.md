# 命令生成

カーネルDSL（[DSL.md](DSL.md)）から命令列を作る処理の仕様。実装は sbtプロジェクト `compiler`。順序は次のとおりで、各段の出力が次の段の入力になる。

| 段 | 入力 → 出力 | 実装 | Issue |
|---|---|---|---|
| メモリ配置・lowering | `Kernel` → `LoweredKernel`（アドレス単位の式） | `vliw.compiler.dsl` | #47 |
| DAG | `LoweredKernel` → `Dag` | `vliw.compiler.dag` | #48 |
| list scheduling | `Dag` → `Schedule`（各ノードの発行サイクルと HALT。値は仮想レジスタ） | `vliw.compiler.schedule` | #49 |
| register allocation | → 物理レジスタの bundle 列 | 未実装 | #50 |
| コード生成と検証 | → 命令語、verifier による最終検査 | 未実装 | #51 |

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

同じレジスタへの書込み区間の重複（L4）と、書込み中のレジスタの読出し（L3 のうち、レジスタの再利用によるもの）は、レジスタ割り当て（#50）で扱う。

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
- 値ごとに別のレジスタを割り当てる、テスト用の素朴なコード生成（`NaiveCodegen`）で、小さなカーネルを命令列にする
  - `ScheduleSpec`: verifier が合法と判定すること
  - `crosscheck/ScheduledKernelSpec`: 参照モデルと RTL での実行結果が DSL の参照実装と一致し、cycles がスケジュールと一致すること
