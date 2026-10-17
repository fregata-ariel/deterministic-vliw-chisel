# Benchmarks

Phase 2 のベンチマーク（整数 SAXPY、FIR、固定サイズ行列積）の記録。結果の表は [RESULTS.md](RESULTS.md) にある。MAC のある構成（ISA mvp-1、#77）とない構成の比較は [MAC.md](MAC.md) にある。

**cycle ベースの比較である。** Fmax・面積・電力は未測定（#57）で、時間や電力の主張はしない。

## カーネル

定義は DSL で書いた `vliw.compiler.dsl.Benchmarks`（[docs/DSL.md](../docs/DSL.md)）。算術はすべて32bit整数で、2^32 を法とする（ISA の ADD/SUB/MUL と同じ）。

| カーネル | 計算 | 入力 | 出力 | Scratchpad（語） |
|---|---|---|---|---|
| `saxpy8` | `out(i) = a * x(i) + y(i)` | スカラー `a`、`x`・`y` 各8語 | `out` 8語 | 25 |
| `fir4x8` | `out(i) = Σ_{t<4} h(t) * x(i + t)`、係数 `h = (1, 2, 3, 4)` は定数 | `x` 11語 | `out` 8語 | 23（定数4語を含む） |
| `matmul3` | `c = a × b`（3×3、行優先） | `a`・`b` 各9語 | `c` 9語 | 27 |

期待出力は、DSL の参照実装（`vliw.compiler.dsl.Interpreter`）で、命令列を経由せずに計算する。

## 比べる方式

| 方式 | 内容 |
|---|---|
| `vliw` | 1 bundle に Compute 1・Memory 1 を発行する（mvp-0 の通常の命令列） |
| `single` | **単一発行ベースライン**。同じ machine description・DAG・メモリ配置・レジスタ割り当ての方法で、1サイクルに1操作だけ発行する（`--single-issue`） |

算術・入出力・データ配置・カーネルの DAG は両方で同じで（LD の複製（#70）は方式ごとに違うことがある）、どちらの命令列も mvp-0 の合法なプログラムとして同じ RTL で実行できる。

## 系列

コンパイラの試行は、優先度（critical-path、program-order）×（レジスタ上限なし、上限 = レジスタ数で `loadReserve` 0〜レジスタ数/2）の組である（[docs/COMPILER.md](../docs/COMPILER.md)）。コンパイラは、まず各優先度を上限なしで、次に各優先度を上限付きで `loadReserve` の小さい順に試す（`RegisterAllocator.attempts`）。上限付きの試行は、どの語も1回だけ読むままでは行き詰まるときだけ、入力・スカラー・定数の LD を複製する（rematerialization、#70）。記録は、この試行の選び方が違う2つの系列からなる（#79）。

| 系列 | 試行の選び方 | 何を比べられるか |
|---|---|---|
| 方式ごとに cycles 最小 | 方式ごとに、全ての試行のうち cycles 最小のもの（同じならば LD の少ない方、それも同じならば試す順で先の試行） | 各方式で今のコンパイラが出せる最良の cycles。**選ばれた試行が方式ごとに違うことがあり**（[RESULTS.md](RESULTS.md) の16本の記録では `fir4x8` と `matmul3`。`saxpy8` は両方式とも同じ試行。8・12本は [REGISTERS.md](REGISTERS.md)）、そのカーネルの比は発行幅だけの効果ではない |
| 戦略を固定 | 全ての試行を、両方の方式に同じ組で与える | 同じ行の比は、両方の方式が LD を複製しない行では、発行幅だけを変えた効果。片方の方式だけが複製する行や、複製した LD の数が方式で違う行は、複製の違いの効果も含む（比を書くときは両方の LD の数も書く）。ある方式で収まらない組は「不成立」と理由付きで記録し、別の試行で置き換えない |

方式ごとに cycles 最小の試行は、固定した系列の中で、その方式の cycles が最小の行と一致する（`BenchmarkSpec` が確かめる）。

## 比較の書き方

- cycle の比を、実時間の速度比と呼ばない（Fmax 未測定、#57）
- どちらの系列の値かを書き、比べる各行の試行（優先度、レジスタ上限、`loadReserve`）を併記する
- 2つの方式・構成の効果を言うときは、戦略を固定した系列の同じ行どうしで比べる。「方式ごとに cycles 最小」どうしの比は、試行の違いの効果も含む
- 「不成立」の行は、比に含めない。不成立の数と理由も、比較の結果として書く
- 他の比較も、同じ2つの系列で記録している。MAC のある・なし（#77、[MAC.md](MAC.md)）とレジスタ数（#70、[REGISTERS.md](REGISTERS.md)）は両方の系列を、Modulo Scheduling と list scheduling（#68、[MODULO.md](MODULO.md)）は list scheduling の側に両方の系列の値を書く
- 固定パイプライン（#75、RESULTS.md）と比べるときは、どの回路の値かを書く。「VLIW の命令列」は VLIW と同じスケジュール・同じレジスタで、cycles は VLIW と同じになる（違いは命令メモリ・制御・RF で、面積と Fmax は #80）。「上限なし」は、同じスケジューラでレジスタの上限を外したもので、VLIW との cycles の差は RF の容量の効果である。値のレジスタは 16 本を超えることがある（`fir4x8` 44 本、`matmul3` 38 本）
- Modulo Scheduling と list scheduling の比較（[MODULO.md](MODULO.md)）では、list scheduling の側は戦略を固定した系列の各行で、Modulo Scheduling の側は1つの戦略（最初に命令列ができた II）である。比はその組合せの比で、Modulo Scheduling の cycles 最小ではない
- Modulo Scheduling の II は、MII から順に試して最初に命令列ができた II である。命令列を作れる最小の II とは書かない（[docs/DECISIONS.md](../docs/DECISIONS.md) ADR-0015）
  - 理由が (a) の II は、予約表が無く、その II では原理的に置けない
  - それ以外の理由（(b) レジスタ、配置の順で置けない（`NoTime`）、命令領域）の II は、今の配置の手順（番号の順、後戻りなし）と、spill も rematerialization もしない割り当てで収まらなかった II である。別の配置なら収まるかは確かめていない
- レジスタ数を変える比較（#70）では、レジスタ上限はそれぞれのレジスタ数になる。戦略を固定した系列は、優先度と `loadReserve` が同じ行（上限なしの行は上限なしの行）どうしを比べる。片方にしかない行（`loadReserve` がもう一方のレジスタ数/2 を超える行）は、片方だけの行として記録し、比に含めない
- 上限付きの試行は、どの語も1回だけ読むままでは行き詰まるときだけ、入力・スカラー・定数を読み直す（rematerialization、[docs/DECISIONS.md](../docs/DECISIONS.md) ADR-0016）。記録では、読み直した行（LD がカーネルの DAG より多い行）の cycles に「（LD n）」を添える。添えていない行の LD の数は、カーネルの DAG の数（[REGISTERS.md](REGISTERS.md) の、方式ごとに cycles 最小の表の括弧の値）である。同じ行でも、片方の構成だけが読み直すことがある（レジスタの少ない方が速くなることもある）ので、比を書くときは両方の LD の数も書く
- MAC のある構成とない構成の比較（#77、[MAC.md](MAC.md)）では、mvp-0（`config/machine.json`）と、MAC のある3つの構成を、同じカーネル・入力・配置・試行で比べる。3つの構成は、mvp-1（`config/machine-mvp1.json`）、読出しポート4本の rp4（`tests/machines/mvp1_rp4.json`）、MAC の latency 3 の mac3（`tests/machines/mvp1_mac3.json`）である。どれもレジスタは16本なので、戦略を固定した系列の行は同じで、同じ行どうしを比べる
- MAC のある構成の DAG は、MUL + ADD を MAC に融合したもので、演算数が mvp-0 と違う。融合は戦略の次元ではない（全ての試行が同じ DAG を使う。ADR-0019）ので、同じ行の比は、MAC と融合の効果と読む
- 読出しポート3本の制約（L11、MAC と ST を同じ bundle に置けない）の効果は、mvp-1 と rp4 の同じ行の差で言う。rp4 には RTL が無い（#57）ので、rp4 の値は参照モデルと verifier だけで確かめた値だと書く。mac3 は感度の確認で、mvp-1 との差は MAC の latency だけの効果である
- MAC の latency と II（`operations.MAC`）は演算器の契約の値である。Modulo Scheduling のループの II（`metrics` の `ii`）と混同しない

## 記録

| ファイル | 内容 |
|---|---|
| [RESULTS.md](RESULTS.md) | 条件と、2つの系列の結果の表（戦略を固定した系列は、この表だけに記録する）。方式ごとに cycles 最小の系列は cycles、演算数、下限、NOP率、書戻しポート・bank の利用率、レジスタ数、命令列の hash、選ばれた試行。戦略を固定した系列は、試行ごとの両方式の cycles と比、不成立の理由。資源条件を揃えた固定パイプライン（#75）の core latency（VLIW の命令列を焼き込んだ回路と、レジスタの上限なしでスケジュールした回路、下限）、揃えた条件と差、回路の遅延と II の契約、RTL のパラメータ、ツールの版 |
| `cases/<kernel>_<mode>.json` | 方式ごとに cycles 最小の系列の命令列、入力宣言（入力値は `sampleInputs(kernel, 1)`）、期待値（出力と cycles）。`vliw-testcase/1`（[docs/TESTCASE.md](../docs/TESTCASE.md)） |
| [MODULO.md](MODULO.md) | Modulo Scheduling（#68）の記録。比べる4つのループ（`saxpy8`、`fir4x8`、`matmul3` の行のループと累算のループ）の MII・II・II を上げた理由・cycles と、戦略を固定した系列の各行（vliw）との比。6つのループ × 3構成と `regs3` の値（#68 の受入テストのループを含む）と、`saxpy(n)` の系列 |
| `cases/<kernel>_<loop>_modulo.json`、`metrics/<kernel>_<loop>_modulo.json` | Modulo Scheduling したループの命令列・入力・期待値と、実行の記録。`metrics` の `ii` は、トレースで確かめた II |
| [REGISTERS.md](REGISTERS.md) | レジスタ数を変えた記録（#70）。8・12・16 本（`tests/machines/regs8.json`、`regs12.json`、既定の構成）の、方式ごとに cycles 最小の系列（cycles、16 本に対する比、LD の数、試行）と、戦略を固定した系列の同じ行どうしの比、片方だけの行と不成立の数 |
| `cases/<kernel>_<mode>_r8.json`・`_r12.json`、`metrics/` の同じ名前 | 8・12 本の、方式ごとに cycles 最小の系列の命令列・入力・期待値と、実行の記録 |
| [MAC.md](MAC.md) | MAC のある構成とない構成の記録（ISA mvp-1、#77 Phase A）。mvp-0・mvp-1・rp4・mac3 の条件（machine の id・revision・hash、MAC の latency と II）、方式ごとに cycles 最小の系列（cycles、mvp-0 に対する比、LD の数、試行、MUL・MAC・ADD の数と融合した組、Compute の NOP 率と書戻しポートの利用率、書戻しポートと読出しポートで待ったサイクル、MAC と ST の bundle、読出しの数ごとの bundle）、戦略を固定した系列（試行ごとの4つの構成の cycles と、同じ行どうしの比。同じ行の、書戻しポートと読出しポートで待ったサイクル、MAC と ST の bundle、読出しの数ごとの bundle）、読出しポートの制約（mvp-1 と rp4 の行ごとの差、制約が当たった bundle、rp4 の命令列を mvp-1 で検査した L11 の違反の数）、6つのループの Modulo Scheduling（3本目の読出しポートを含む・除く ResMII、RecMII、II と理由、配置、cycles、融合の判定）、ADR-0018 の見積りとの比較 |
| `cases/<kernel>_<mode>_mac.json`・`_mac_rp4.json`・`_mac3.json`、`metrics/` の同じ名前 | mvp-1・rp4・mac3 の、方式ごとに cycles 最小の系列の命令列・入力・期待値と、実行の記録（`vliw-metrics/2`） |
| `cases/<kernel>_<loop>_modulo_mac.json`・`_mac_rp4.json`・`_mac3.json`、`metrics/` の同じ名前 | mvp-1・rp4・mac3 の、6つのループを Modulo Scheduling した命令列・入力・期待値と、実行の記録（`vliw-metrics/2`。`ii` はトレースで確かめた II） |
| `metrics/<kernel>_<mode>.json` | 方式ごとに cycles 最小の系列の、参照モデルでの実行の記録。`vliw-metrics/1`（[docs/METRICS.md](../docs/METRICS.md)）。machine の revision と hash、命令列の hash を含む |

記録は、すべてソースから生成する。手で編集しない。

```sh
sbt "crosscheck/Test/runMain vliw.crosscheck.BenchmarkRecords"   # benchmarks/ を書き直す
sbt "compiler/run verify benchmarks/cases/fir4x8_vliw.json"      # 記録した命令列の検査
sbt "sim/run benchmarks/cases/fir4x8_vliw.json"                  # 参照モデルで実行
sbt "sim/run benchmarks/cases/fir4x8_vliw_mac.json"              # mvp-1 の記録（machine はテストケースが指す）
```

CI の `crosscheck/BenchmarkSpec` は、次を確かめる。

- コミットされた記録が、生成し直した内容と一致すること（`metrics` の `generator` 欄は実行環境に依存するので比べない）。コンパイラや machine description を変えて結果が変わったら、生成し直してコミットする
- 記録したテストケースが、参照モデルでも RTL でも、期待した出力と cycles になること
- 戦略を固定した系列が、コンパイラの全ての試行を、試す順に、両方の方式に与えていること。各行がその行の試行の結果であること（不成立を別の試行で置き換えないこと）。各行の命令列（成立したもの）が、verifier に合法と判定され、参照モデルで期待した出力と、スケジューラの予測どおりの cycles になること
- 方式ごとに cycles 最小の系列が、固定した系列の中の、その方式で cycles 最小（同じならば LD 最少）の最初の試行と一致すること
- 単一発行の命令列は1 bundle に1操作だけで、vliw より速くならないこと
- Modulo Scheduling のテストケースが、参照モデルでも RTL でも成り立つこと。配置と入力が list scheduling と同じで、`metrics` の `ii` が記録した II であること
- 固定パイプライン（#75）が、記録した入力で、記録した出力と cycles を ChiselSim で出すこと。「VLIW の命令列」の回路の計画が、記録した命令列の各 bundle と同じ操作を同じ cycle に発行し、cycles が VLIW と同じであること。記録が名前を挙げる Verilator と JDK で測っていること
- 8・12 本の machine description が、既定の構成とレジスタ数だけ違うこと。8・12 本のテストケースが、参照モデルでも RTL でも成り立つこと。戦略を固定した系列と、方式ごとに cycles 最小の系列についての上の確認を、8・12・16 本のそれぞれで行うこと
- MAC のある構成（#77）: rp4 と mac3 が、`config/machine-mvp1.json` と `read_ports` だけ、MAC の latency だけ違うこと。RTL の範囲外は rp4 だけであること。配置と入力が mvp-0 と同じで、融合した組の数だけ MUL と ADD が減り、MAC が増えること。テストケースが参照モデルで成り立ち、mvp-1 と mac3 では RTL でも同じ出力と cycles になり、RTL のトレースが参照モデルのトレースと一致すること（ループは RTL のトレースでも II を確かめる）。戦略を固定した系列と、方式ごとに cycles 最小の系列についての上の確認を、3つの構成でも行うこと。`vliw-metrics/2` の読出しの欄が命令列と一致すること。戦略を固定した系列の各行で、読出しの数ごとの bundle の合計が cycles になり、読出しの合計が行によらないこと。MAC の無い構成と読出し4本の構成で `heldByReadPorts` が0であること。選ばれた試行の行の `heldByWritePort`・`heldByReadPorts`・読出しの数ごとの bundle が、方式ごとに cycles 最小の系列の記録と同じであること。rp4 の全ての命令列（両方の系列とループ）を mvp-1 の machine で検査すると、MAC と ST の bundle ごとにちょうど1つ L11 の違反になり、ほかの違反が無いこと。読出し3本の構成の命令列に MAC と ST の bundle が無く、単一発行の命令列が mvp-1 と rp4 で同じであること
