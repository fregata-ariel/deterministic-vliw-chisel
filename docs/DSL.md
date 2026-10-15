# カーネルDSL

Phase 2 の自動命令生成の入力となる、固定サイズの整数カーネルを書くための Scala 内部DSL。実装は `vliw.compiler.dsl`（sbtプロジェクト `compiler`）。目的はアーキテクチャの比較に使うカーネルを書くことで、DSL 自体の機能は最小限にする（[ADR-0010](DECISIONS.md)）。ループ構造を残したまま書く API は、下の「[ループ IR](#ループ-ir)」にある（Phase 3、#76）。

## 書き方

```scala
import vliw.compiler.dsl._

val saxpy = Kernel("saxpy8") { k =>
  val a = k.scalar("a")        // スカラー入力
  val x = k.input("x", 8)      // 入力配列
  val y = k.input("y", 8)
  val out = k.output("out", 8) // 出力配列
  for (i <- 0 until 8) out(i) = a * x(i) + y(i)
}
```

| 構成要素 | 書き方 | 意味 |
|---|---|---|
| 入力配列 | `k.input(name, length)`、`x(i)` | 読出し専用。`i` は Scala の整数（静的に決まる） |
| スカラー入力 | `k.scalar(name)` | 読出し専用の1語 |
| 出力配列 | `k.output(name, length)`、`out(i) = e` | 書込み専用。各要素にちょうど1回だけ代入する |
| 定数 | `k.const(v)` | 32bit に正規化する（`-1` は `0xffffffff`） |
| 演算 | `e1 + e2`、`e1 - e2`、`e1 * e2` | ISA の ADD・SUB・MUL に対応する |
| ループ | Scala の `for` | カーネルを組み立てる時点ですべて展開される。ループ構造を残すときは「ループ IR」の `LoopKernel` で書く |

名前は `[a-z][a-z0-9_]*` で、入力・スカラー・出力を通して一意とする。

## 意味

- 値はすべて32bitの語で、演算は 2^32 を法とする（ISA の ADD/SUB/MUL と同じ）。
- 出力 `out(i)` の値は、代入した式を入力の値で評価したもの。入力は読出し専用、出力は書込み専用なので、代入の順序は結果に影響しない。
- 参照実装 `Interpreter.run(kernel, values)` は、この意味を直接評価する。メモリ配置も命令列も使わない。生成コードの結果と比べる期待値はここから得る。

式（`Expr`）は、物理レジスタ・発行サイクル・アドレスを持たない仮想値である。共通部分式の共有、DAG の構築（#48）、スケジューリング（#49）、レジスタ割り当て（#50）は後の段階で行う。

## 制約

組み立て時に、次の違反を `IllegalArgumentException` で拒否する。

- 名前の重複、または形式違反
- 長さが1未満の配列
- 配列の範囲外の添字
- 同じ出力要素への2回目の代入、または代入されない出力要素

出力の読出しと、入力への書込みはできない。そのため、カーネルの中のメモリ操作が alias することはない。SAXPY は `y = a*x + y` を上書きせず、別の出力配列に書く。ループ IR も同じ制約で、反復をまたぐ値はメモリではなくレジスタの状態で渡す（[ADR-0014](DECISIONS.md#adr-0014-ループ-ir-では状態をレジスタで渡し複数の反復が読む語は1回だけ-ld-する)）。そのため、ループ IR でも alias は起きない。

## メモリ配置

DSL はアドレスを持たない。配置は別の処理 `MemoryLayout` が決める。初期実装 `MemoryLayout.fixedOrder` は、アドレス0から隙間なく次の順に割り当てる。

1. 入力配列（宣言順）
2. スカラー（宣言順）
3. 定数（最初に現れた順。同じ値は1語にまとめる）
4. 出力配列（宣言順）

合計が machine description の Scratchpad 容量（`banks × words_per_bank`）を超えると、理由付きで拒否する。

現在の ISA には即値演算が無いので、スカラーと定数は**初期化済みメモリ**に置く。ホストが開始前に書き込む語（入力・スカラー・定数）が入力宣言 M_in になり、R_in は空である（`MemoryLayout.inputs`）。テストケースや実行の入力宣言は、ここから作る。

## lowering

`Lowering.lower(layout)` は、式の葉（入力の要素・スカラー・定数）を、配置したアドレスの読出し `Read(addr)` に置き換える。出力への代入は `Write(addr, value)` になる。値はまだ仮想値で、レジスタも発行順序も決めていない。DAG の構築（#48）はこの形を入力にする。

`Lowering.evaluate` は、lowering した形をメモリの内容の上で評価する。配置と lowering が意味を変えないことを、参照実装と比べて確かめるために使う。

## ベンチマーク

`Benchmarks` に、Phase 2 で記録する3つのカーネルを置く（[benchmarks/README.md](../benchmarks/README.md)）。

| カーネル | 定義 | 規模 | 配置（語） |
|---|---|---|---|
| `saxpy8` | `out(i) = a * x(i) + y(i)` | 8要素 | 25 |
| `fir4x8` | `out(i) = Σ_t h(t) * x(i + t)`、係数 `h = (1, 2, 3, 4)` は定数 | 4タップ、出力8 | 23 |
| `matmul3` | `c = a × b`（3×3、行優先） | 3×3 | 27 |

## ループ IR

Modulo Scheduling（#68）のために、ループを展開せずに書く API（`vliw.compiler.loop.LoopKernel`）。1つの数え上げのループを持つカーネルを組み立てる。命令生成での扱い（lowering、依存グラフ、反復と II）は [COMPILER.md](COMPILER.md) の「ループ IR」にある。

```scala
import vliw.compiler.loop._

// out(i) = a * x(i) + y(i)：i のループ、8反復
val saxpy = LoopKernel("saxpy8", "i", 8) { k =>
  val a = k.scalar("a")
  val x = k.input("x", 8)
  val y = k.input("y", 8)
  val out = k.output("out", 8)
  k.body(i => out(i) = a * x(i) + y(i))
}

// out(0) = x(0) * x(1) * ... * x(7)：状態 p、i = 1〜7 の7反復
val product = LoopKernel("product8", "i", 7) { k =>
  val x = k.input("x", 8)
  val out = k.output("out", 1)
  val p = k.state("p", x(0))       // 初期値
  k.body(t => p := p.value * x(t + 1))  // 次の反復の値
  out(0) = p                        // ループの後の値
}
```

| 構成要素 | 書き方 | 意味 |
|---|---|---|
| ループ | `LoopKernel(name, index, trips) { k => ... }` | 反復 k = 0 〜 trips − 1。`index` は、ループにした段の名前（`"i"`、`"m"` など） |
| 本体 | `k.body(i => ...)` | ちょうど1回書く。`i` は反復番号で、添字は `i + c`、`i * c` の組合せ（反復番号の一次式 `Index(stride, offset)`） |
| 入力・スカラー・定数・演算 | `dsl.Kernel` と同じ | `x(i + 1)` は反復ごとに違う要素、`x(3)` は全反復で同じ要素 |
| ストア | 本体の中で `out(i) = e` | 各反復で、その反復の添字の要素に書く |
| 状態 | `k.state(name, init)`、本体の中で `s := e`、値は `s.value` | 本体の前に宣言する。`init` はループ不変の式。`s.value` は反復の開始時の値で、`s := e` の値が次の反復の開始時の値になる |
| 後の格納 | 本体の外で `out(i) = s` | 最後の反復の後の、状態の値を書く |

ループにしない添字は、本体の中で Scala の `for` で展開してよい（例: `matmul3` の行のループで、j と m を展開する）。`LoopKernel.unroll` はループを完全に展開した `dsl.Kernel` を返し、その意味は `Interpreter.run` で定まる。`LoopBenchmarks` に、記録するループ（`saxpy`、`fir`、`matmulRows`、`matmulAcc`、`product`、`dot`）を置く。

組み立て時に、次の違反を `IllegalArgumentException` で拒否する。

- `dsl.Kernel` の制約（名前、長さ、各出力要素にちょうど1回だけ書くこと）。添字は、最初と最後の反復の両方で範囲内であること
- 反復回数が1未満、本体が無い、または2回ある
- 本体の外のストア（後の格納を除く）、本体の中の後の格納、本体の中の状態の宣言
- 状態の初期値が反復によって変わる式であること
- 状態の次の値が、反復によって変わる演算（`+`・`-`・`*`）でないこと。`s := x(i)` のように LD をそのまま状態にすることはできない
- 本体で更新しない状態、2回更新する状態、本体のどこでも読まない状態

## テスト

`compiler/src/test/.../dsl/KernelSpec`：

| 確認すること | 方法 |
|---|---|
| 参照実装の値 | 小さな規模の SAXPY・FIR・行列積を、手で計算した値と比べる |
| カーネルの書き方 | 3カーネルを、DSL を使わずに素の Scala で書いた実装（`ReferenceKernels`）と、乱数の入力で比べる |
| 32bit の折り返し | 加算・減算・乗算と定数の正規化 |
| 制約 | 不正なカーネルを組み立て時に拒否する |
| 配置 | 固定順のアドレス、M_in の内容、容量超過の拒否 |
| lowering | 初期化済みの語だけを読み、各出力に1回だけ書き、評価結果が参照実装と一致する |

ループ IR のテスト（`compiler/src/test/.../loop/`）は、[COMPILER.md](COMPILER.md) の「ループ IR」にある。
