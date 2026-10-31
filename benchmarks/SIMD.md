# W（ISA mvp-2）と mvp-1 の比較

このファイルは `sbt "crosscheck/Test/runMain vliw.crosscheck.BenchmarkRecords"` が生成する。手で編集しない（`crosscheck/BenchmarkSpec` が一致を確かめる）。

**cycle ベースの比較である。** Fmax・面積・電力は未測定で（#57）、時間や電力の主張はしない。cycle の比を実時間の速度比と読まない。W の判断は #57 まで暫定である（#78 の質問14）。#78 の見積りは情報で、目標ではない。

比べる組は、同じ行の mvp-1 と W-reg（演算の幅）、W-reg と W-full（メモリの幅）、mvp-1 と W-full（両方）で（[ADR-0023](../docs/DECISIONS.md)）、読出しポート4本の `mvp1_rp4` と `mvp2_rp4`、W の4本目の読出しポート（W-full と `mvp2_rp4`）も並べる。

## 条件

| 構成 | machine description | machine | ISA | `read_ports` | `max_words_per_access` | LD.W・ST.W | 記録の接尾辞 | 実行 |
|---|---|---|---|---|---|---|---|---|
| mvp-1 | `config/machine-mvp1.json` | `vliw2-i32-sp2-mac` revision 1（frozen）、`sha256:90f6a9667a8b9fa7be448117438c04ff40a1af6495f13c0b8d8d1ccd95f89162` | `mvp-1` | 3 | 1 | なし | `_mac` | 参照モデルと RTL |
| W-reg | `tests/machines/mvp2_mw1.json` | `vliw2-i32-sp2-mac-w2-mw1` revision 1（proposed）、`sha256:50834abdfc813d5bbdf7df3c4a1357e578d5894f1532396b0263cdaf162b2859` | `mvp-2` | 3 | 1 | なし | `_wreg` | 参照モデルと RTL |
| W-full | `config/machine-mvp2.json` | `vliw2-i32-sp2-mac-w2` revision 1（frozen）、`sha256:9e582a03e98e95bc87f42074c1b4b899667424fbc68e9110405a517d21c01d96` | `mvp-2` | 3 | 2 | あり | `_w` | 参照モデルと RTL |
| mvp1_rp4 | `tests/machines/mvp1_rp4.json` | `vliw2-i32-sp2-mac-rp4` revision 1（proposed）、`sha256:600710348640442ebaba9d0df2dbef62949eab15f948d42ca899924051f4226b` | `mvp-1` | 4 | 1 | なし | `_mac_rp4` | 参照モデルと verifier（RTL の範囲外: read_ports is 4; the RTL has 3 register read ports, port 2 shared by MAC's rs3 and the ST source (4 read ports come in #57)） |
| mvp2_rp4 | `tests/machines/mvp2_rp4.json` | `vliw2-i32-sp2-mac-w2-rp4` revision 1（proposed）、`sha256:db0c16d2f3bb568ec27b6418c8fb1dc8e2301ed40ffa0fe516b618fb65580c73` | `mvp-2` | 4 | 2 | あり | `_w_rp4` | 参照モデルと verifier（RTL の範囲外: read_ports is 4; the RTL has 3 register read ports per RF bank, port 2 shared by the rs3 of MAC and VMAC and the Memory source of ST and ST.W (4 read ports come in #57, ADR-0018)） |

構成ごとに足すもの（[ADR-0022](../docs/DECISIONS.md)「影響」の #78 の増分。書いた値で、測っていない。#57）:

| 構成 | mvp-1 に対して足すもの |
|---|---|
| W-reg | VADD・VSUB・VMUL・VMAC（レーン1 に2つ目の 32×32 の乗算器、32bit の加算器 3 → 6）、RF を2 bank × 8 × 32bit にし bank ごとに 3R/2W（全部で 6R/4W、読出しの mux 約 1,444 → 約 1,510）、書戻しの予約のデータ 32bit → 64bit と組の印 |
| W-full | W-reg に加え、LD.W・ST.W（Scratchpad の bank ごとの行の +1 の回路、64bit の経路、32bit のレーンの入れ替えの mux 2つ） |
| `mvp1_rp4` | mvp-1 の RF の4本目の読出しポート（RTL なし、#57） |
| `mvp2_rp4` | W-full の RF の bank ごとの4本目の読出しポート（RTL なし、#57） |

| 項目 | 値 |
|---|---|
| カーネル | `Benchmarks.all`（`saxpy8`・`fir4x8`・`matmul3`）と `matmul4`（`Benchmarks.matmul(4)`、#78 の質問13 の (b)）。`matmul4` は `Benchmarks.all` に入れず、[RESULTS.md](RESULTS.md)・[MAC.md](MAC.md) には無い。mvp-1 と `mvp1_rp4` の `matmul4` の記録（`_mac`・`_mac_rp4`）は、このファイルの基準である（ADR-0023） |
| 基準 | mvp-1 と `mvp1_rp4` の `saxpy8`・`fir4x8`・`matmul3` とループの記録は、[MAC.md](MAC.md) の記録そのものである（同じオブジェクト。MAC.md と食い違わない） |
| 配置・入力 | `MemoryLayout.fixedOrder`（ループは `LoopLowering.layout`）と `KernelCompiler.sampleInputs(kernel, 1)`。期待値は DSL の参照実装。どの構成でも、配置・入力・融合したスカラーの DAG は mvp-1 と同じである（`BenchmarkSpec` が確かめる） |
| レジスタ | どの構成も 16 本。戦略を固定した系列の行（20 の試行）は、どの構成でも同じである |
| コンパイラの試行 | 優先度 ×（上限なし、上限 16 で load reserve 0〜8）× 方式。rematerialization は行き詰まる試行だけ（[ADR-0016](../docs/DECISIONS.md)。W-full では LD.W も複製する。集めのレーンの LD は複製しない、#110 第4段の Q5） |
| W の詰め込み | 固定の規則で、戦略の次元にしない（[ADR-0023](../docs/DECISIONS.md)）。W の行は、どれも同じ詰めた DAG（`Prepared.packing`）をスケジュールする。規則1は2段の規則（#110 第4段の Q1 の (b)。オーナーの答えを待つ推奨で、決定ではない）で、list scheduling とループで同じである（`Pack.storePairs` と `LoopPack.storePairs` が `matmul3` とその（m）の候補を試す順から作る） |
| ループ | 戦略は1つ。II = MII から順に試し、最初に命令列ができた II を使う（ADR-0015）。融合は `FusionChoice`、W-full のベクトル化（2倍か本体の詰め込み）は固定の規則（ADR-0023） |
| 比較での fallback | W の候補をスカラーの値に置き換えない（#78 の質問10、ADR-0023）。詰めた DAG のすべての試行が失敗した候補は理由付きの「不成立」、詰めるものの無いカーネルとループ（`NothingToPack`）はスカラーの命令列を理由と一緒に測る。W-reg の Modulo Scheduling は「測っていない（#78 の質問8 の (c)）」とする |
| 記録の形式 | 基準は `vliw-metrics/2`、W は `vliw-metrics/3`（[docs/METRICS.md](../docs/METRICS.md)）。W では、操作の数、レジスタの語の数、転送の語の数を分ける（#78 の質問9）。`/2` では、どの操作も1語を動かすので、3つは一致する |
| 実行 | 参照モデル。RTL の範囲内の構成（mvp-1、W-reg、W-full）の記録は RTL でも同じ出力・cycles・トレースになることを、`BenchmarkSpec`（mvp-1 の `matmul4`）と `RtlMvp2Spec`（W-reg と W-full）が確かめる |
| 算術 | W のどのレーンも i32 の演算で、mvp-1 と同じ算術である。#110 の「SIMD でレーンの幅（算術）が変わる場合は、同じ算術のカーネルを作って比べる」には当たらない |

## list scheduling: 方式ごとに cycles 最小の系列

各構成で、方式ごとに、全ての試行のうち cycles 最小のもの（同じならば LD の少ない方、それも同じならば試す順で先の試行）。W の構成では、詰めた DAG の試行から選び、どの試行も失敗すれば「不成立」とし、失敗の数を理由ごとに書く。通常の利用（`KernelCompiler.compile`）は、そのときスカラーの命令列に戻るが、その値は W の値として書かない（[ADR-0023](../docs/DECISIONS.md)）。比は、同じ読出しポート数の基準（W-reg と W-full は mvp-1、`mvp2_rp4` は `mvp1_rp4`）の cycles をこの構成の cycles で割った値で、選ばれた試行が違えば試行の違いの効果も含む（同じ試行どうしの比は「戦略を固定した系列」にある）。

### cycles と W の詰め方

| カーネル | 方式 | 構成 | cycles | 基準 / この構成 | LD（カーネルの DAG） | 試行 | W の詰め方 | 命令列 hash |
|---|---|---|---|---|---|---|---|---|
| `saxpy8` | vliw | mvp-1 | 27 | 1.00 | 17（17） | critical-path, no register limit | -（W なし） | `sha256:97e9480edd56…` |
| `saxpy8` | vliw | W-reg | 27 | 1.00 | 17（17） | critical-path, no register limit | 組 4、スカラーのレーン 0、ブロードキャスト 1、集め 8 | `sha256:c1bc147e0b74…` |
| `saxpy8` | vliw | W-full | 15 | 1.80 | 9（9） | critical-path, no register limit | 組 4、スカラーのレーン 0、ブロードキャスト 1、集め 0 | `sha256:5e0a1a3edd9c…` |
| `saxpy8` | vliw | mvp1_rp4 | 26 | 1.00 | 17（17） | critical-path, no register limit | -（W なし） | `sha256:64142c16b234…` |
| `saxpy8` | vliw | mvp2_rp4 | 14 | 1.86 | 9（9） | critical-path, no register limit | 組 4、スカラーのレーン 0、ブロードキャスト 1、集め 0 | `sha256:056e5ec64bd7…` |
| `saxpy8` | single | mvp-1 | 34 | 1.00 | 17（17） | critical-path, 16 registers, load reserve 0, single issue | -（W なし） | `sha256:3df548fcc9a3…` |
| `saxpy8` | single | W-reg | 30 | 1.13 | 17（17） | critical-path, 16 registers, load reserve 0, single issue | 組 4、スカラーのレーン 0、ブロードキャスト 1、集め 8 | `sha256:801e9f976f9f…` |
| `saxpy8` | single | W-full | 18 | 1.89 | 9（9） | critical-path, 16 registers, load reserve 0, single issue | 組 4、スカラーのレーン 0、ブロードキャスト 1、集め 0 | `sha256:2af84542a1b7…` |
| `saxpy8` | single | mvp1_rp4 | 34 | 1.00 | 17（17） | critical-path, 16 registers, load reserve 0, single issue | -（W なし） | `sha256:3df548fcc9a3…` |
| `saxpy8` | single | mvp2_rp4 | 18 | 1.89 | 9（9） | critical-path, 16 registers, load reserve 0, single issue | 組 4、スカラーのレーン 0、ブロードキャスト 1、集め 0 | `sha256:2af84542a1b7…` |
| `fir4x8` | vliw | mvp-1 | 40 | 1.00 | 15（15） | program-order, no register limit | -（W なし） | `sha256:31bc090907b5…` |
| `fir4x8` | vliw | W-reg | 37 | 1.08 | 24（24） | program-order, no register limit | 組 4、スカラーのレーン 0、ブロードキャスト 4、集め 10、`Pack` の LD の複製 9 | `sha256:ece4570fc276…` |
| `fir4x8` | vliw | W-full | 25 | 1.60 | 18（14） | critical-path, 16 registers, load reserve 0 | 組 4、スカラーのレーン 0、ブロードキャスト 4、集め 0 | `sha256:d4ce49fe80be…` |
| `fir4x8` | vliw | mvp1_rp4 | 38 | 1.00 | 27（15） | critical-path, 16 registers, load reserve 1 | -（W なし） | `sha256:61042cd0eeab…` |
| `fir4x8` | vliw | mvp2_rp4 | 23 | 1.65 | 18（14） | critical-path, 16 registers, load reserve 0 | 組 4、スカラーのレーン 0、ブロードキャスト 4、集め 0 | `sha256:a21c8cf39061…` |
| `fir4x8` | single | mvp-1 | 58 | 1.00 | 15（15） | program-order, no register limit, single issue | -（W なし） | `sha256:bedcbf4dcc05…` |
| `fir4x8` | single | W-reg | 51 | 1.14 | 24（24） | program-order, 16 registers, load reserve 3, single issue | 組 4、スカラーのレーン 0、ブロードキャスト 4、集め 10、`Pack` の LD の複製 9 | `sha256:3822cd5e25f9…` |
| `fir4x8` | single | W-full | 37 | 1.57 | 14（14） | program-order, no register limit, single issue | 組 4、スカラーのレーン 0、ブロードキャスト 4、集め 0 | `sha256:ecb9c33a8bae…` |
| `fir4x8` | single | mvp1_rp4 | 58 | 1.00 | 15（15） | program-order, no register limit, single issue | -（W なし） | `sha256:bedcbf4dcc05…` |
| `fir4x8` | single | mvp2_rp4 | 37 | 1.57 | 14（14） | program-order, no register limit, single issue | 組 4、スカラーのレーン 0、ブロードキャスト 4、集め 0 | `sha256:ecb9c33a8bae…` |
| `matmul3` | vliw | mvp-1 | 39 | 1.00 | 18（18） | program-order, 16 registers, load reserve 0 | -（W なし） | `sha256:4aac47809422…` |
| `matmul3` | vliw | W-reg | 32 | 1.22 | 18（18） | critical-path, 16 registers, load reserve 0 | 組 3（行をまたぐ組 0）、スカラーのレーン 3: c(0,2)（NoNeighbour）、c(1,2)（NoNeighbour）、c(2,2)（NoNeighbour）、ブロードキャスト 9、集め 3 | `sha256:c2ef493d8230…` |
| `matmul3` | vliw | W-full | 28 | 1.39 | 15（15） | program-order, no register limit | 組 3（行をまたぐ組 0）、スカラーのレーン 3: c(0,2)（NoNeighbour）、c(1,2)（NoNeighbour）、c(2,2)（NoNeighbour）、ブロードキャスト 9、集め 0 | `sha256:239f9bf0a8c2…` |
| `matmul3` | vliw | mvp1_rp4 | 33 | 1.00 | 18（18） | critical-path, 16 registers, load reserve 0 | -（W なし） | `sha256:84bcd150eb5f…` |
| `matmul3` | vliw | mvp2_rp4 | 24 | 1.38 | 15（15） | critical-path, 16 registers, load reserve 0 | 組 3（行をまたぐ組 0）、スカラーのレーン 3: c(0,2)（NoNeighbour）、c(1,2)（NoNeighbour）、c(2,2)（NoNeighbour）、ブロードキャスト 9、集め 0 | `sha256:23c79e2f86d7…` |
| `matmul3` | single | mvp-1 | 55 | 1.00 | 18（18） | critical-path, 16 registers, load reserve 0, single issue | -（W なし） | `sha256:8ad2b63557ac…` |
| `matmul3` | single | W-reg | 46 | 1.20 | 18（18） | program-order, no register limit, single issue | 組 3（行をまたぐ組 0）、スカラーのレーン 3: c(0,2)（NoNeighbour）、c(1,2)（NoNeighbour）、c(2,2)（NoNeighbour）、ブロードキャスト 9、集め 3 | `sha256:d62057c2fab7…` |
| `matmul3` | single | W-full | 40 | 1.38 | 15（15） | program-order, no register limit, single issue | 組 3（行をまたぐ組 0）、スカラーのレーン 3: c(0,2)（NoNeighbour）、c(1,2)（NoNeighbour）、c(2,2)（NoNeighbour）、ブロードキャスト 9、集め 0 | `sha256:bd5fa4a8afe8…` |
| `matmul3` | single | mvp1_rp4 | 55 | 1.00 | 18（18） | critical-path, 16 registers, load reserve 0, single issue | -（W なし） | `sha256:8ad2b63557ac…` |
| `matmul3` | single | mvp2_rp4 | 40 | 1.38 | 15（15） | program-order, no register limit, single issue | 組 3（行をまたぐ組 0）、スカラーのレーン 3: c(0,2)（NoNeighbour）、c(1,2)（NoNeighbour）、c(2,2)（NoNeighbour）、ブロードキャスト 9、集め 0 | `sha256:bd5fa4a8afe8…` |
| `matmul4` | vliw | mvp-1 | 77 | 1.00 | 55（32） | program-order, 16 registers, load reserve 0 | -（W なし） | `sha256:815e5fee72c2…` |
| `matmul4` | vliw | W-reg | 不成立 | - | -（32） | - | 不成立（20 の試行がすべて失敗: TooManyLive 2、Stuck 18）。組 8（行をまたぐ組 0）、スカラーのレーン 0、ブロードキャスト 16、集め 8 | - |
| `matmul4` | vliw | W-full | 58 | 1.33 | 43（24） | program-order, 16 registers, load reserve 1 | 組 8（行をまたぐ組 0）、スカラーのレーン 0、ブロードキャスト 16、集め 0 | `sha256:9b5645fbce67…` |
| `matmul4` | vliw | mvp1_rp4 | 76 | 1.00 | 53（32） | program-order, 16 registers, load reserve 0 | -（W なし） | `sha256:4a273d2436f4…` |
| `matmul4` | vliw | mvp2_rp4 | 60 | 1.27 | 46（24） | program-order, 16 registers, load reserve 2 | 組 8（行をまたぐ組 0）、スカラーのレーン 0、ブロードキャスト 16、集め 0 | `sha256:ddaa556fb0ac…` |
| `matmul4` | single | mvp-1 | 128 | 1.00 | 45（32） | program-order, 16 registers, load reserve 0, single issue | -（W なし） | `sha256:ae9127c22b9c…` |
| `matmul4` | single | W-reg | 不成立 | - | -（32） | - | 不成立（20 の試行がすべて失敗: TooManyLive 2、Stuck 18）。組 8（行をまたぐ組 0）、スカラーのレーン 0、ブロードキャスト 16、集め 8 | - |
| `matmul4` | single | W-full | 82 | 1.56 | 39（24） | program-order, 16 registers, load reserve 2, single issue | 組 8（行をまたぐ組 0）、スカラーのレーン 0、ブロードキャスト 16、集め 0 | `sha256:dc5373946e48…` |
| `matmul4` | single | mvp1_rp4 | 128 | 1.00 | 45（32） | program-order, 16 registers, load reserve 0, single issue | -（W なし） | `sha256:ae9127c22b9c…` |
| `matmul4` | single | mvp2_rp4 | 82 | 1.56 | 39（24） | program-order, 16 registers, load reserve 2, single issue | 組 8（行をまたぐ組 0）、スカラーのレーン 0、ブロードキャスト 16、集め 0 | `sha256:dc5373946e48…` |

LD の欄は LD と LD.W の数で、括弧の中はスケジュールした DAG（rematerialization の前）の数である。W の詰め方は `Pack` の結果で、どの試行にも共通である。スカラーのレーンの理由は、[docs/COMPILER.md](../docs/COMPILER.md)「ISA mvp-2（W）」の名前である。

### 操作の数、レジスタの語の数、転送の語の数

| カーネル | 方式 | 構成 | 操作 | 操作（mnemonic） | スカラー / ベクトルの演算 | 書いたレジスタの語（Compute + Memory） | 読んだレジスタの語 | 転送の語（読む + 書く） | 転送の語（DAG） |
|---|---|---|---|---|---|---|---|---|---|
| `saxpy8` | vliw | mvp-1 | 33（Compute 8、Memory 25） | MAC 8、LD 17、ST 8 | 8 / 0 | 8 + 17（commit 8 + 17） | 32（欄 32） | 17 + 8（操作 17 + 8） | 17 + 8 |
| `saxpy8` | vliw | W-reg | 29（Compute 4、Memory 25） | VMAC 4、LD 17、ST 8 | 0 / 4 | 8 + 17（commit 4 + 17） | 28（欄 20） | 17 + 8（操作 17 + 8） | 17 + 8 |
| `saxpy8` | vliw | W-full | 17（Compute 4、Memory 13） | VMAC 4、LD 1、LD.W 8、ST.W 4 | 0 / 4 | 8 + 17（commit 4 + 9） | 28（欄 16） | 17 + 8（操作 9 + 4） | 17 + 8 |
| `saxpy8` | vliw | mvp1_rp4 | 33（Compute 8、Memory 25） | MAC 8、LD 17、ST 8 | 8 / 0 | 8 + 17（commit 8 + 17） | 32（欄 32） | 17 + 8（操作 17 + 8） | 17 + 8 |
| `saxpy8` | vliw | mvp2_rp4 | 17（Compute 4、Memory 13） | VMAC 4、LD 1、LD.W 8、ST.W 4 | 0 / 4 | 8 + 17（commit 4 + 9） | 28（欄 16） | 17 + 8（操作 9 + 4） | 17 + 8 |
| `saxpy8` | single | mvp-1 | 33（Compute 8、Memory 25） | MAC 8、LD 17、ST 8 | 8 / 0 | 8 + 17（commit 8 + 17） | 32（欄 32） | 17 + 8（操作 17 + 8） | 17 + 8 |
| `saxpy8` | single | W-reg | 29（Compute 4、Memory 25） | VMAC 4、LD 17、ST 8 | 0 / 4 | 8 + 17（commit 4 + 17） | 28（欄 20） | 17 + 8（操作 17 + 8） | 17 + 8 |
| `saxpy8` | single | W-full | 17（Compute 4、Memory 13） | VMAC 4、LD 1、LD.W 8、ST.W 4 | 0 / 4 | 8 + 17（commit 4 + 9） | 28（欄 16） | 17 + 8（操作 9 + 4） | 17 + 8 |
| `saxpy8` | single | mvp1_rp4 | 33（Compute 8、Memory 25） | MAC 8、LD 17、ST 8 | 8 / 0 | 8 + 17（commit 8 + 17） | 32（欄 32） | 17 + 8（操作 17 + 8） | 17 + 8 |
| `saxpy8` | single | mvp2_rp4 | 17（Compute 4、Memory 13） | VMAC 4、LD 1、LD.W 8、ST.W 4 | 0 / 4 | 8 + 17（commit 4 + 9） | 28（欄 16） | 17 + 8（操作 9 + 4） | 17 + 8 |
| `fir4x8` | vliw | mvp-1 | 55（Compute 32、Memory 23） | MUL 8、MAC 24、LD 15、ST 8 | 32 / 0 | 32 + 15（commit 32 + 15） | 96（欄 96） | 15 + 8（操作 15 + 8） | 15 + 8 |
| `fir4x8` | vliw | W-reg | 48（Compute 16、Memory 32） | VMUL 4、VMAC 12、LD 24、ST 8 | 0 / 16 | 32 + 24（commit 16 + 24） | 80（欄 52） | 24 + 8（操作 24 + 8） | 24 + 8 |
| `fir4x8` | vliw | W-full | 38（Compute 16、Memory 22） | VMUL 4、VMAC 12、LD 4、LD.W 14、ST.W 4 | 0 / 16 | 32 + 32（commit 16 + 18） | 80（欄 48） | 32 + 8（操作 18 + 4） | 24 + 8 |
| `fir4x8` | vliw | mvp1_rp4 | 67（Compute 32、Memory 35） | MUL 8、MAC 24、LD 27、ST 8 | 32 / 0 | 32 + 27（commit 32 + 27） | 96（欄 96） | 27 + 8（操作 27 + 8） | 15 + 8 |
| `fir4x8` | vliw | mvp2_rp4 | 38（Compute 16、Memory 22） | VMUL 4、VMAC 12、LD 4、LD.W 14、ST.W 4 | 0 / 16 | 32 + 32（commit 16 + 18） | 80（欄 48） | 32 + 8（操作 18 + 4） | 24 + 8 |
| `fir4x8` | single | mvp-1 | 55（Compute 32、Memory 23） | MUL 8、MAC 24、LD 15、ST 8 | 32 / 0 | 32 + 15（commit 32 + 15） | 96（欄 96） | 15 + 8（操作 15 + 8） | 15 + 8 |
| `fir4x8` | single | W-reg | 48（Compute 16、Memory 32） | VMUL 4、VMAC 12、LD 24、ST 8 | 0 / 16 | 32 + 24（commit 16 + 24） | 80（欄 52） | 24 + 8（操作 24 + 8） | 24 + 8 |
| `fir4x8` | single | W-full | 34（Compute 16、Memory 18） | VMUL 4、VMAC 12、LD 4、LD.W 10、ST.W 4 | 0 / 16 | 32 + 24（commit 16 + 14） | 80（欄 48） | 24 + 8（操作 14 + 4） | 24 + 8 |
| `fir4x8` | single | mvp1_rp4 | 55（Compute 32、Memory 23） | MUL 8、MAC 24、LD 15、ST 8 | 32 / 0 | 32 + 15（commit 32 + 15） | 96（欄 96） | 15 + 8（操作 15 + 8） | 15 + 8 |
| `fir4x8` | single | mvp2_rp4 | 34（Compute 16、Memory 18） | VMUL 4、VMAC 12、LD 4、LD.W 10、ST.W 4 | 0 / 16 | 32 + 24（commit 16 + 14） | 80（欄 48） | 24 + 8（操作 14 + 4） | 24 + 8 |
| `matmul3` | vliw | mvp-1 | 54（Compute 27、Memory 27） | MUL 9、MAC 18、LD 18、ST 9 | 27 / 0 | 27 + 18（commit 27 + 18） | 81（欄 81） | 18 + 9（操作 18 + 9） | 18 + 9 |
| `matmul3` | vliw | W-reg | 45（Compute 18、Memory 27） | MUL 3、MAC 6、VMUL 3、VMAC 6、LD 18、ST 9 | 9 / 9 | 27 + 18（commit 18 + 18） | 72（欄 57） | 18 + 9（操作 18 + 9） | 18 + 9 |
| `matmul3` | vliw | W-full | 39（Compute 18、Memory 21） | MUL 3、MAC 6、VMUL 3、VMAC 6、LD 12、ST 3、LD.W 3、ST.W 3 | 9 / 9 | 27 + 18（commit 18 + 15） | 72（欄 54） | 18 + 9（操作 15 + 6） | 18 + 9 |
| `matmul3` | vliw | mvp1_rp4 | 54（Compute 27、Memory 27） | MUL 9、MAC 18、LD 18、ST 9 | 27 / 0 | 27 + 18（commit 27 + 18） | 81（欄 81） | 18 + 9（操作 18 + 9） | 18 + 9 |
| `matmul3` | vliw | mvp2_rp4 | 39（Compute 18、Memory 21） | MUL 3、MAC 6、VMUL 3、VMAC 6、LD 12、ST 3、LD.W 3、ST.W 3 | 9 / 9 | 27 + 18（commit 18 + 15） | 72（欄 54） | 18 + 9（操作 15 + 6） | 18 + 9 |
| `matmul3` | single | mvp-1 | 54（Compute 27、Memory 27） | MUL 9、MAC 18、LD 18、ST 9 | 27 / 0 | 27 + 18（commit 27 + 18） | 81（欄 81） | 18 + 9（操作 18 + 9） | 18 + 9 |
| `matmul3` | single | W-reg | 45（Compute 18、Memory 27） | MUL 3、MAC 6、VMUL 3、VMAC 6、LD 18、ST 9 | 9 / 9 | 27 + 18（commit 18 + 18） | 72（欄 57） | 18 + 9（操作 18 + 9） | 18 + 9 |
| `matmul3` | single | W-full | 39（Compute 18、Memory 21） | MUL 3、MAC 6、VMUL 3、VMAC 6、LD 12、ST 3、LD.W 3、ST.W 3 | 9 / 9 | 27 + 18（commit 18 + 15） | 72（欄 54） | 18 + 9（操作 15 + 6） | 18 + 9 |
| `matmul3` | single | mvp1_rp4 | 54（Compute 27、Memory 27） | MUL 9、MAC 18、LD 18、ST 9 | 27 / 0 | 27 + 18（commit 27 + 18） | 81（欄 81） | 18 + 9（操作 18 + 9） | 18 + 9 |
| `matmul3` | single | mvp2_rp4 | 39（Compute 18、Memory 21） | MUL 3、MAC 6、VMUL 3、VMAC 6、LD 12、ST 3、LD.W 3、ST.W 3 | 9 / 9 | 27 + 18（commit 18 + 15） | 72（欄 54） | 18 + 9（操作 15 + 6） | 18 + 9 |
| `matmul4` | vliw | mvp-1 | 135（Compute 64、Memory 71） | MUL 16、MAC 48、LD 55、ST 16 | 64 / 0 | 64 + 55（commit 64 + 55） | 192（欄 192） | 55 + 16（操作 55 + 16） | 32 + 16 |
| `matmul4` | vliw | W-reg | 不成立 | 詰めた DAG: VMUL 8、VMAC 24、LD 32、ST 16 | - | - | - | - | 32 + 16 |
| `matmul4` | vliw | W-full | 83（Compute 32、Memory 51） | VMUL 8、VMAC 24、LD 16、LD.W 27、ST.W 8 | 0 / 32 | 64 + 70（commit 32 + 43） | 160（欄 96） | 70 + 16（操作 43 + 8） | 32 + 16 |
| `matmul4` | vliw | mvp1_rp4 | 133（Compute 64、Memory 69） | MUL 16、MAC 48、LD 53、ST 16 | 64 / 0 | 64 + 53（commit 64 + 53） | 192（欄 192） | 53 + 16（操作 53 + 16） | 32 + 16 |
| `matmul4` | vliw | mvp2_rp4 | 86（Compute 32、Memory 54） | VMUL 8、VMAC 24、LD 17、LD.W 29、ST.W 8 | 0 / 32 | 64 + 75（commit 32 + 46） | 160（欄 96） | 75 + 16（操作 46 + 8） | 32 + 16 |
| `matmul4` | single | mvp-1 | 125（Compute 64、Memory 61） | MUL 16、MAC 48、LD 45、ST 16 | 64 / 0 | 64 + 45（commit 64 + 45） | 192（欄 192） | 45 + 16（操作 45 + 16） | 32 + 16 |
| `matmul4` | single | W-reg | 不成立 | 詰めた DAG: VMUL 8、VMAC 24、LD 32、ST 16 | - | - | - | - | 32 + 16 |
| `matmul4` | single | W-full | 79（Compute 32、Memory 47） | VMUL 8、VMAC 24、LD 16、LD.W 23、ST.W 8 | 0 / 32 | 64 + 62（commit 32 + 39） | 160（欄 96） | 62 + 16（操作 39 + 8） | 32 + 16 |
| `matmul4` | single | mvp1_rp4 | 125（Compute 64、Memory 61） | MUL 16、MAC 48、LD 45、ST 16 | 64 / 0 | 64 + 45（commit 64 + 45） | 192（欄 192） | 45 + 16（操作 45 + 16） | 32 + 16 |
| `matmul4` | single | mvp2_rp4 | 79（Compute 32、Memory 47） | VMUL 8、VMAC 24、LD 16、LD.W 23、ST.W 8 | 0 / 32 | 64 + 62（commit 32 + 39） | 160（欄 96） | 62 + 16（操作 39 + 8） | 32 + 16 |

- 操作は発行した命令の数で、VMAC・VMUL・LD.W・ST.W・レーンの ST・スカラーの操作をそれぞれ1と数える。集めは2つの LD で、移動の命令は無い。HALT は数えない（`issue.<slot>.operations`、`issue.by_operation`）
- 書いたレジスタの語は、組を2、スカラーを1と数える（`writeback.<port>.registers`）。commit は書戻しポートを使った操作の数である（`writeback.<port>.commits`）
- 読んだレジスタの語は、組の欄を2、ブロードキャストとスカラーを1と数える（`issue.register_reads.registers`）。欄は L11 の数え方（`issue.register_reads.reads`）である
- 転送の語は Scratchpad の語の数で、LD.W・ST.W を2と数える（`scratchpad.reads` / `writes`）。操作は LD + LD.W と ST + ST.W の数（`read_operations` / `write_operations`）。DAG の欄は、スケジュールした DAG の LD・LD.W・ST・ST.W・レーンの ST から数えた語である
- 基準（`vliw-metrics/2`）では、どの操作も1語を動かすので、語の数は操作の数である
- カーネルの DAG（スケジュールする DAG、rematerialization の前）の転送の語は、`saxpy8`、`matmul3`、`matmul4` では、3つの W の構成で基準と同じである。違うのは `fir4x8` の W-reg（読む語 24、書く語 8。mvp-1 は 15、8）、`fir4x8` の W-full（読む語 24、書く語 8。mvp-1 は 15、8）、`fir4x8` の mvp2_rp4（読む語 24、書く語 8。mvp1_rp4 は 15、8）である。
- 命令列の転送の語（rematerialization の複製を含む。LD.W の複製は2語を読む）が基準と同じなのは、記録のある 22 のうち 12 で、違うのは `fir4x8`（vliw）の W-reg（読む語 24、mvp-1 は 15）、`fir4x8`（vliw）の W-full（読む語 32、mvp-1 は 15）、`fir4x8`（vliw）の mvp2_rp4（読む語 32、mvp1_rp4 は 27）、`fir4x8`（single）の W-reg（読む語 24、mvp-1 は 15）、`fir4x8`（single）の W-full（読む語 24、mvp-1 は 15）、`fir4x8`（single）の mvp2_rp4（読む語 24、mvp1_rp4 は 15）、`matmul4`（vliw）の W-full（読む語 70、mvp-1 は 55）、`matmul4`（vliw）の mvp2_rp4（読む語 75、mvp1_rp4 は 53）、`matmul4`（single）の W-full（読む語 62、mvp-1 は 45）、`matmul4`（single）の mvp2_rp4（読む語 62、mvp1_rp4 は 45）である。

### スロットとポート

| カーネル | 方式 | 構成 | Compute NOP率 | Memory NOP率 | 書戻し利用率（Compute / Memory） | `heldByWritePort` | `heldByReadPorts` | (MAC か VMAC) と (ST か ST.W) の bundle | 読出しの欄の数ごとの bundle（0 / 1 / …） | Scratchpad の bank ごとの語（0 / 1） | RF の bank ごとに読んだレジスタ（0 / 1） |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `saxpy8` | vliw | mvp-1 | 0.667 | 0.074 | 0.296 / 0.630 | 0 | 7 | 0 | 11 / 8 / 0 / 8 | 13 / 12 | - |
| `saxpy8` | vliw | W-reg | 0.815 | 0.074 | 0.148 / 0.630 | 0 | 3 | 0 | 15 / 8 / 0 / 4 | 13 / 12 | 16 / 12 |
| `saxpy8` | vliw | W-full | 0.667 | 0.133 | 0.267 / 0.600 | 0 | 3 | 0 | 7 / 4 / 0 / 4 | 13 / 12 | 16 / 12 |
| `saxpy8` | vliw | mvp1_rp4 | 0.654 | 0.038 | 0.308 / 0.654 | 0 | 0 | 1 | 11 / 7 / 0 / 7 / 1 | 13 / 12 | - |
| `saxpy8` | vliw | mvp2_rp4 | 0.643 | 0.071 | 0.286 / 0.643 | 0 | 0 | 1 | 7 / 3 / 0 / 3 / 1 | 13 / 12 | 16 / 12 |
| `saxpy8` | single | mvp-1 | 0.735 | 0.265 | 0.235 / 0.500 | 0 | 0 | 0 | 18 / 8 / 0 / 8 | 13 / 12 | - |
| `saxpy8` | single | W-reg | 0.833 | 0.167 | 0.133 / 0.567 | 0 | 0 | 0 | 18 / 8 / 0 / 4 | 13 / 12 | 16 / 12 |
| `saxpy8` | single | W-full | 0.722 | 0.278 | 0.222 / 0.500 | 0 | 0 | 0 | 10 / 4 / 0 / 4 | 13 / 12 | 16 / 12 |
| `saxpy8` | single | mvp1_rp4 | 0.735 | 0.265 | 0.235 / 0.500 | 0 | 0 | 0 | 18 / 8 / 0 / 8 / 0 | 13 / 12 | - |
| `saxpy8` | single | mvp2_rp4 | 0.722 | 0.278 | 0.222 / 0.500 | 0 | 0 | 0 | 10 / 4 / 0 / 4 / 0 | 13 / 12 | 16 / 12 |
| `fir4x8` | vliw | mvp-1 | 0.175 | 0.425 | 0.800 / 0.375 | 0 | 0 | 0 | 6 / 2 / 2 / 30 | 12 / 11 | - |
| `fir4x8` | vliw | W-reg | 0.541 | 0.135 | 0.432 / 0.649 | 0 | 6 | 0 | 15 / 6 / 2 / 14 | 16 / 16 | 40 / 40 |
| `fir4x8` | vliw | W-full | 0.320 | 0.120 | 0.640 / 0.720 | 0 | 2 | 0 | 5 / 4 / 4 / 12 | 20 / 20 | 40 / 40 |
| `fir4x8` | vliw | mvp1_rp4 | 0.132 | 0.079 | 0.842 / 0.711 | 0 | 0 | 6 | 4 / 2 / 8 / 18 / 6 | 18 / 17 | - |
| `fir4x8` | vliw | mvp2_rp4 | 0.261 | 0.043 | 0.696 / 0.783 | 0 | 0 | 2 | 5 / 2 / 4 / 10 / 2 | 20 / 20 | 40 / 40 |
| `fir4x8` | single | mvp-1 | 0.431 | 0.603 | 0.552 / 0.259 | 0 | 0 | 0 | 18 / 8 / 8 / 24 | 12 / 11 | - |
| `fir4x8` | single | W-reg | 0.667 | 0.373 | 0.314 / 0.471 | 0 | 0 | 0 | 27 / 8 / 4 / 12 | 16 / 16 | 40 / 40 |
| `fir4x8` | single | W-full | 0.541 | 0.514 | 0.432 / 0.378 | 0 | 0 | 0 | 17 / 4 / 4 / 12 | 16 / 16 | 40 / 40 |
| `fir4x8` | single | mvp1_rp4 | 0.431 | 0.603 | 0.552 / 0.259 | 0 | 0 | 0 | 18 / 8 / 8 / 24 / 0 | 12 / 11 | - |
| `fir4x8` | single | mvp2_rp4 | 0.541 | 0.514 | 0.432 / 0.378 | 0 | 0 | 0 | 17 / 4 / 4 / 12 / 0 | 16 / 16 | 40 / 40 |
| `matmul3` | vliw | mvp-1 | 0.282 | 0.308 | 0.692 / 0.462 | 0 | 0 | 0 | 8 / 4 / 4 / 23 | 14 / 13 | - |
| `matmul3` | vliw | W-reg | 0.406 | 0.156 | 0.563 / 0.563 | 0 | 4 | 0 | 5 / 9 / 6 / 12 | 14 / 13 | 36 / 36 |
| `matmul3` | vliw | W-full | 0.321 | 0.250 | 0.643 / 0.536 | 0 | 0 | 0 | 8 / 2 / 2 / 16 | 14 / 13 | 39 / 33 |
| `matmul3` | vliw | mvp1_rp4 | 0.152 | 0.182 | 0.818 / 0.545 | 0 | 0 | 7 | 4 / 2 / 9 / 11 / 7 | 14 / 13 | - |
| `matmul3` | vliw | mvp2_rp4 | 0.208 | 0.125 | 0.750 / 0.625 | 0 | 0 | 4 | 4 / 2 / 6 / 8 / 4 | 14 / 13 | 35 / 37 |
| `matmul3` | single | mvp-1 | 0.491 | 0.509 | 0.491 / 0.327 | 0 | 0 | 0 | 19 / 9 / 9 / 18 | 14 / 13 | - |
| `matmul3` | single | W-reg | 0.587 | 0.413 | 0.391 / 0.391 | 0 | 0 | 0 | 19 / 9 / 6 / 12 | 14 / 13 | 40 / 32 |
| `matmul3` | single | W-full | 0.525 | 0.475 | 0.450 / 0.375 | 0 | 0 | 0 | 16 / 6 / 6 / 12 | 14 / 13 | 40 / 32 |
| `matmul3` | single | mvp1_rp4 | 0.491 | 0.509 | 0.491 / 0.327 | 0 | 0 | 0 | 19 / 9 / 9 / 18 / 0 | 14 / 13 | - |
| `matmul3` | single | mvp2_rp4 | 0.525 | 0.475 | 0.450 / 0.375 | 0 | 0 | 0 | 16 / 6 / 6 / 12 / 0 | 14 / 13 | 40 / 32 |
| `matmul4` | vliw | mvp-1 | 0.156 | 0.078 | 0.831 / 0.714 | 0 | 17 | 0 | 8 / 5 / 5 / 59 | 38 / 33 | - |
| `matmul4` | vliw | W-reg | - | - | - | - | - | - | - | - | - |
| `matmul4` | vliw | W-full | 0.431 | 0.121 | 0.552 / 0.741 | 0 | 1 | 0 | 19 / 7 / 7 / 25 | 43 / 43 | 82 / 78 |
| `matmul4` | vliw | mvp1_rp4 | 0.145 | 0.092 | 0.842 / 0.697 | 0 | 0 | 1 | 8 / 4 / 5 / 58 / 1 | 34 / 35 | - |
| `matmul4` | vliw | mvp2_rp4 | 0.450 | 0.100 | 0.533 / 0.767 | 0 | 0 | 0 | 23 / 5 / 5 / 27 / 0 | 46 / 45 | 83 / 77 |
| `matmul4` | single | mvp-1 | 0.492 | 0.523 | 0.500 / 0.352 | 0 | 0 | 0 | 48 / 16 / 16 / 48 | 34 / 27 | - |
| `matmul4` | single | W-reg | - | - | - | - | - | - | - | - | - |
| `matmul4` | single | W-full | 0.598 | 0.427 | 0.390 / 0.476 | 0 | 0 | 0 | 42 / 8 / 8 / 24 | 39 / 39 | 80 / 80 |
| `matmul4` | single | mvp1_rp4 | 0.492 | 0.523 | 0.500 / 0.352 | 0 | 0 | 0 | 48 / 16 / 16 / 48 / 0 | 34 / 27 | - |
| `matmul4` | single | mvp2_rp4 | 0.598 | 0.427 | 0.390 / 0.476 | 0 | 0 | 0 | 42 / 8 / 8 / 24 / 0 | 39 / 39 | 80 / 80 |

- `heldByWritePort` と `heldByReadPorts` の定義は [MAC.md](MAC.md) と同じで、W では `heldByReadPorts` が L11 だけで待った ST と ST.W を数える（ADR-0023）
- (MAC か VMAC) と (ST か ST.W) の bundle は、W では `issue.rs3_with_store`、基準では `issue.mac_with_st` である。`read_ports` 3 では常に0
- Scratchpad の bank ごとの語は、各 bank で読み書きした語の数である（DMA との bank のサイクルの共有は #111 で、ここでは測っていない）。RF の bank ごとのレジスタは `vliw-metrics/3` だけにある

### 資源ごとの下限

#109 の最初の完了条件と ADR-0023「記録」の、資源ごとの下限である。スケジュールした DAG（rematerialization の前。不成立の W-reg の行は詰めた DAG）から数え、HALT の1を足した cycles で書く。最大の値と、それに当たる資源（律速）を最後に書く。

| カーネル | 構成 | cycles（vliw） | cycles（single） | dep | cs | ms | wbC | wbM | rp | MUL | MAC | VMUL | VMAC | LD | LD.W | bank 0 | bank 1 | 最大（律速） | single の下限（演算 + HALT） |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `saxpy8` | mvp-1 | 27 | 34 | 6 | 9 | 26 | 9 | 18 | 12 | - | 9 | - | - | 18 | - | 14 | 13 | 26（ms） | 34 |
| `saxpy8` | W-reg | 27 | 30 | 6 | 5 | 26 | 5 | 18 | 8 | - | - | - | 5 | 18 | - | 14 | 13 | 26（ms） | 30 |
| `saxpy8` | W-full | 15 | 18 | 6 | 5 | 14 | 5 | 10 | 7 | - | - | - | 5 | 2 | 9 | 14 | 13 | 14（ms、bank 0） | 18 |
| `saxpy8` | mvp1_rp4 | 26 | 34 | 6 | 9 | 26 | 9 | 18 | 9 | - | 9 | - | - | 18 | - | 14 | 13 | 26（ms） | 34 |
| `saxpy8` | mvp2_rp4 | 14 | 18 | 6 | 5 | 14 | 5 | 10 | 5 | - | - | - | 5 | 2 | 9 | 14 | 13 | 14（ms、bank 0） | 18 |
| `fir4x8` | mvp-1 | 40 | 58 | 12 | 33 | 24 | 33 | 16 | 33 | 9 | 25 | - | - | 16 | - | 13 | 12 | 33（cs、wbC、rp） | 56 |
| `fir4x8` | W-reg | 37 | 51 | 12 | 17 | 33 | 17 | 25 | 19 | - | - | 5 | 13 | 25 | - | 17 | 17 | 33（ms） | 49 |
| `fir4x8` | W-full | 25 | 37 | 12 | 17 | 19 | 17 | 15 | 17 | - | - | 5 | 13 | 5 | 11 | 17 | 17 | 19（ms） | 35 |
| `fir4x8` | mvp1_rp4 | 38 | 58 | 12 | 33 | 24 | 33 | 16 | 25 | 9 | 25 | - | - | 16 | - | 13 | 12 | 33（cs、wbC） | 56 |
| `fir4x8` | mvp2_rp4 | 23 | 37 | 12 | 17 | 19 | 17 | 15 | 13 | - | - | 5 | 13 | 5 | 11 | 17 | 17 | 19（ms） | 35 |
| `matmul3` | mvp-1 | 39 | 55 | 10 | 28 | 28 | 28 | 19 | 28 | 10 | 19 | - | - | 19 | - | 15 | 14 | 28（cs、ms、wbC、rp） | 55 |
| `matmul3` | W-reg | 32 | 46 | 10 | 19 | 28 | 19 | 19 | 20 | 4 | 7 | 4 | 7 | 19 | - | 15 | 14 | 28（ms） | 46 |
| `matmul3` | W-full | 28 | 40 | 10 | 19 | 22 | 19 | 16 | 19 | 4 | 7 | 4 | 7 | 13 | 4 | 15 | 14 | 22（ms） | 40 |
| `matmul3` | mvp1_rp4 | 33 | 55 | 10 | 28 | 28 | 28 | 19 | 22 | 10 | 19 | - | - | 19 | - | 15 | 14 | 28（cs、ms、wbC） | 55 |
| `matmul3` | mvp2_rp4 | 24 | 40 | 10 | 19 | 22 | 19 | 16 | 15 | 4 | 7 | 4 | 7 | 13 | 4 | 15 | 14 | 22（ms） | 40 |
| `matmul4` | mvp-1 | 77 | 128 | 12 | 65 | 49 | 65 | 33 | 65 | 17 | 49 | - | - | 33 | - | 25 | 25 | 65（cs、wbC、rp） | 113 |
| `matmul4` | W-reg | 不成立 | 不成立 | 12 | 33 | 49 | 33 | 33 | 36 | - | - | 9 | 25 | 33 | - | 25 | 25 | 49（ms） | 81 |
| `matmul4` | W-full | 58 | 82 | 12 | 33 | 33 | 33 | 25 | 33 | - | - | 9 | 25 | 17 | 9 | 25 | 25 | 33（cs、ms、wbC、rp） | 65 |
| `matmul4` | mvp1_rp4 | 76 | 128 | 12 | 65 | 49 | 65 | 33 | 49 | 17 | 49 | - | - | 33 | - | 25 | 25 | 65（cs、wbC） | 113 |
| `matmul4` | mvp2_rp4 | 60 | 82 | 12 | 33 | 33 | 33 | 25 | 25 | - | - | 9 | 25 | 17 | 9 | 25 | 25 | 33（cs、ms、wbC） | 65 |

- `dep`: 依存（`Dag.cyclesLowerBound`、クリティカルパス + 1）
- `cs` / `ms`: Compute / Memory の操作の数 + 1（1 bundle に1つずつ）
- `wbC` / `wbM`: Compute の書戻しポートを使う演算 / LD と LD.W の数 + 1（L5、境界ごとに1つ）
- `rp`: ⌈読出しの欄の合計 / `read_ports`⌉ + 1（L11。3本目の読出しポートを欄で数える）
- `MUL`〜`LD.W`: 演算器ごとの数 × II + 1（L9）。「-」はその演算器を使わない
- `bank 0` / `bank 1`: Scratchpad の bank ごとの語の数 + 1（1RW、1サイクルに1語。LD.W・ST.W は2つの bank を1語ずつ使う）。`ms` 以下である
- single の下限は、1サイクルに1操作なので、DAG の操作の数 + HALT である

各記録の全項目は `metrics/<kernel>_<mode><構成>.json`、命令列と入力・期待値は `cases/` の同じ名前にある。構成の接尾辞は W-reg が `_wreg`、W-full が `_w`、`mvp2_rp4` が `_w_rp4` で、W-reg の不成立のカーネルにはファイルが無い。mvp-1 と `mvp1_rp4` の `saxpy8`・`fir4x8`・`matmul3` は MAC.md の記録（`_mac`・`_mac_rp4`）で、`matmul4` の `_mac`・`_mac_rp4` はこのファイルの基準である（`_mac3` は無い）。

## list scheduling: 戦略を固定した系列

各行は1つの試行（優先度、レジスタ上限、load reserve）で、5つの構成に同じ組を与えた cycles を並べる。どの構成もレジスタは 16 本なので、行は同じである。行の順はコンパイラが試す順（`RegisterAllocator.attempts`）である。W の構成の各行は、その試行で詰めた DAG をスケジュールした結果で、詰め込みは戦略の次元ではない（どの行も同じ詰めた DAG。ADR-0023）。**太字**は、その構成の「方式ごとに cycles 最小の系列」で選ばれた試行、「（LD n）」は、その試行が LD か LD.W を複製し、DAG より多い n 個を発行することを示す。「不成立」の行は別の試行で置き換えず、スカラーの値も書かない。理由は [RESULTS.md](RESULTS.md) の2つと、W の「cycle c で整列した組が空かない」（`NoAlignedPair`、断片化）である。比は、両方の行が成立するときだけ書く。片方だけが LD を複製する行の比は、複製の違いの効果も含む。どの行の命令列も verifier が合法と判定し、参照モデルで期待した出力と、スケジューラの予測どおりの cycles になることを `BenchmarkSpec` が確かめる。

カーネルと方式ごとに表は2つある。2つ目の表は、1つ目と同じ行を同じ順に並べ、ADR-0023「記録」の行ごとの値（Compute の NOP 率、書戻しポートの使用率、L11 だけで待った ST・ST.W）と、書戻しの衝突、(MAC か VMAC) と (ST か ST.W) の bundle、読出しの欄の数ごとの bundle（HALT の bundle を含み、合計は cycles）を書く。どの値も、その行の命令列を参照モデルで実行した記録（基準は `vliw-metrics/2`、W は `/3`）とスケジュールの値で、行が成立しない構成は「-」である。読出しの欄の合計は、その構成の DAG で決まり、行によらない。

### `saxpy8`（vliw）

| 優先度 | レジスタ上限 | load reserve | mvp-1 | W-reg | W-full | mvp1_rp4 | mvp2_rp4 | mvp-1 / W-reg | W-reg / W-full | mvp-1 / W-full | mvp1_rp4 / mvp2_rp4 | W-full / mvp2_rp4 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| critical-path | なし | - | **27** | **27** | **15** | **26** | **14** | 1.00 | 1.80 | 1.80 | 1.86 | 1.07 |
| program-order | なし | - | 28 | 29 | 17 | 28 | 16 | 0.97 | 1.71 | 1.65 | 1.75 | 1.06 |
| critical-path | 16 | 0 | 27 | 27 | 15 | 26 | 14 | 1.00 | 1.80 | 1.80 | 1.86 | 1.07 |
| critical-path | 16 | 1 | 27 | 27 | 15 | 26 | 14 | 1.00 | 1.80 | 1.80 | 1.86 | 1.07 |
| critical-path | 16 | 2 | 27 | 27 | 15 | 26 | 14 | 1.00 | 1.80 | 1.80 | 1.86 | 1.07 |
| critical-path | 16 | 3 | 27 | 27 | 15 | 26 | 14 | 1.00 | 1.80 | 1.80 | 1.86 | 1.07 |
| critical-path | 16 | 4 | 27 | 27 | 15 | 26 | 14 | 1.00 | 1.80 | 1.80 | 1.86 | 1.07 |
| critical-path | 16 | 5 | 27 | 27 | 15 | 26 | 14 | 1.00 | 1.80 | 1.80 | 1.86 | 1.07 |
| critical-path | 16 | 6 | 27 | 27 | 15 | 26 | 15 | 1.00 | 1.80 | 1.80 | 1.73 | 1.00 |
| critical-path | 16 | 7 | 27 | 27 | 15 | 26 | 15 | 1.00 | 1.80 | 1.80 | 1.73 | 1.00 |
| critical-path | 16 | 8 | 27 | 27 | 17 | 26 | 17 | 1.00 | 1.59 | 1.59 | 1.53 | 1.00 |
| program-order | 16 | 0 | 28 | 29 | 17 | 28 | 16 | 0.97 | 1.71 | 1.65 | 1.75 | 1.06 |
| program-order | 16 | 1 | 28 | 29 | 17 | 28 | 16 | 0.97 | 1.71 | 1.65 | 1.75 | 1.06 |
| program-order | 16 | 2 | 28 | 29 | 17 | 28 | 16 | 0.97 | 1.71 | 1.65 | 1.75 | 1.06 |
| program-order | 16 | 3 | 28 | 29 | 17 | 28 | 16 | 0.97 | 1.71 | 1.65 | 1.75 | 1.06 |
| program-order | 16 | 4 | 28 | 29 | 17 | 28 | 16 | 0.97 | 1.71 | 1.65 | 1.75 | 1.06 |
| program-order | 16 | 5 | 28 | 29 | 17 | 28 | 16 | 0.97 | 1.71 | 1.65 | 1.75 | 1.06 |
| program-order | 16 | 6 | 28 | 29 | 17 | 28 | 16 | 0.97 | 1.71 | 1.65 | 1.75 | 1.06 |
| program-order | 16 | 7 | 28 | 29 | 17 | 28 | 16 | 0.97 | 1.71 | 1.65 | 1.75 | 1.06 |
| program-order | 16 | 8 | 28 | 29 | 17 | 28 | 17 | 0.97 | 1.71 | 1.65 | 1.65 | 1.00 |

- mvp-1 / W-reg: 両方で成立する行 20（W-reg の cycles が少ない 0、同じ 10、多い 10。比 0.97〜1.00）、mvp-1 だけで成立 0、W-reg だけで成立 0、どちらも不成立 0
- W-reg / W-full: 両方で成立する行 20（W-full の cycles が少ない 20、同じ 0、多い 0。比 1.59〜1.80）、W-reg だけで成立 0、W-full だけで成立 0、どちらも不成立 0
- mvp-1 / W-full: 両方で成立する行 20（W-full の cycles が少ない 20、同じ 0、多い 0。比 1.59〜1.80）、mvp-1 だけで成立 0、W-full だけで成立 0、どちらも不成立 0
- mvp1_rp4 / mvp2_rp4: 両方で成立する行 20（mvp2_rp4 の cycles が少ない 20、同じ 0、多い 0。比 1.53〜1.86）、mvp1_rp4 だけで成立 0、mvp2_rp4 だけで成立 0、どちらも不成立 0
- W-full / mvp2_rp4: 両方で成立する行 20（mvp2_rp4 の cycles が少ない 16、同じ 4、多い 0。比 1.00〜1.07）、W-full だけで成立 0、mvp2_rp4 だけで成立 0、どちらも不成立 0
- 不成立の行は mvp-1 0、W-reg 0、W-full 0、mvp1_rp4 0、mvp2_rp4 0。LD を複製した行は mvp-1 0、W-reg 0、W-full 0、mvp1_rp4 0、mvp2_rp4 0

同じ行のスロットとポート（構成の順は mvp-1 / W-reg / W-full / mvp1_rp4 / mvp2_rp4）:

| 優先度 | レジスタ上限 | load reserve | Compute NOP率 | 書戻し Compute 利用率 | 書戻し Memory 利用率 | `heldByWritePort` | `heldByReadPorts` | (MAC か VMAC) と (ST か ST.W) の bundle | 読出しの欄の数ごとの bundle（mvp-1） | 読出しの欄の数ごとの bundle（W-reg） | 読出しの欄の数ごとの bundle（W-full） | 読出しの欄の数ごとの bundle（mvp1_rp4） | 読出しの欄の数ごとの bundle（mvp2_rp4） |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| critical-path | なし | - | 0.667 / 0.815 / 0.667 / 0.654 / 0.643 | 0.296 / 0.148 / 0.267 / 0.308 / 0.286 | 0.630 / 0.630 / 0.600 / 0.654 / 0.643 | 0 / 0 / 0 / 0 / 0 | 7 / 3 / 3 / 0 / 0 | 0 / 0 / 0 / 1 / 1 | 11 / 8 / 0 / 8 | 15 / 8 / 0 / 4 | 7 / 4 / 0 / 4 | 11 / 7 / 0 / 7 / 1 | 7 / 3 / 0 / 3 / 1 |
| program-order | なし | - | 0.679 / 0.828 / 0.706 / 0.679 / 0.688 | 0.286 / 0.138 / 0.235 / 0.286 / 0.250 | 0.607 / 0.586 / 0.529 / 0.607 / 0.563 | 0 / 0 / 0 / 0 / 0 | 5 / 0 / 2 / 0 / 0 | 0 / 0 / 0 / 1 / 1 | 12 / 8 / 0 / 8 | 17 / 8 / 0 / 4 | 9 / 4 / 0 / 4 | 13 / 7 / 0 / 7 / 1 | 9 / 3 / 0 / 3 / 1 |
| critical-path | 16 | 0 | 0.667 / 0.815 / 0.667 / 0.654 / 0.643 | 0.296 / 0.148 / 0.267 / 0.308 / 0.286 | 0.630 / 0.630 / 0.600 / 0.654 / 0.643 | 0 / 0 / 0 / 0 / 0 | 7 / 3 / 3 / 0 / 0 | 0 / 0 / 0 / 1 / 1 | 11 / 8 / 0 / 8 | 15 / 8 / 0 / 4 | 7 / 4 / 0 / 4 | 11 / 7 / 0 / 7 / 1 | 7 / 3 / 0 / 3 / 1 |
| critical-path | 16 | 1 | 0.667 / 0.815 / 0.667 / 0.654 / 0.643 | 0.296 / 0.148 / 0.267 / 0.308 / 0.286 | 0.630 / 0.630 / 0.600 / 0.654 / 0.643 | 0 / 0 / 0 / 0 / 0 | 7 / 3 / 3 / 0 / 0 | 0 / 0 / 0 / 1 / 1 | 11 / 8 / 0 / 8 | 15 / 8 / 0 / 4 | 7 / 4 / 0 / 4 | 11 / 7 / 0 / 7 / 1 | 7 / 3 / 0 / 3 / 1 |
| critical-path | 16 | 2 | 0.667 / 0.815 / 0.667 / 0.654 / 0.643 | 0.296 / 0.148 / 0.267 / 0.308 / 0.286 | 0.630 / 0.630 / 0.600 / 0.654 / 0.643 | 0 / 0 / 0 / 0 / 0 | 7 / 3 / 3 / 0 / 0 | 0 / 0 / 0 / 1 / 1 | 11 / 8 / 0 / 8 | 15 / 8 / 0 / 4 | 7 / 4 / 0 / 4 | 11 / 7 / 0 / 7 / 1 | 7 / 3 / 0 / 3 / 1 |
| critical-path | 16 | 3 | 0.667 / 0.815 / 0.667 / 0.654 / 0.643 | 0.296 / 0.148 / 0.267 / 0.308 / 0.286 | 0.630 / 0.630 / 0.600 / 0.654 / 0.643 | 0 / 0 / 0 / 0 / 0 | 7 / 3 / 3 / 0 / 0 | 0 / 0 / 0 / 1 / 1 | 11 / 8 / 0 / 8 | 15 / 8 / 0 / 4 | 7 / 4 / 0 / 4 | 11 / 7 / 0 / 7 / 1 | 7 / 3 / 0 / 3 / 1 |
| critical-path | 16 | 4 | 0.667 / 0.815 / 0.667 / 0.654 / 0.643 | 0.296 / 0.148 / 0.267 / 0.308 / 0.286 | 0.630 / 0.630 / 0.600 / 0.654 / 0.643 | 0 / 0 / 0 / 0 / 0 | 7 / 3 / 3 / 0 / 0 | 0 / 0 / 0 / 1 / 1 | 11 / 8 / 0 / 8 | 15 / 8 / 0 / 4 | 7 / 4 / 0 / 4 | 11 / 7 / 0 / 7 / 1 | 7 / 3 / 0 / 3 / 1 |
| critical-path | 16 | 5 | 0.667 / 0.815 / 0.667 / 0.654 / 0.643 | 0.296 / 0.148 / 0.267 / 0.308 / 0.286 | 0.630 / 0.630 / 0.600 / 0.654 / 0.643 | 0 / 0 / 0 / 0 / 0 | 7 / 3 / 3 / 0 / 0 | 0 / 0 / 0 / 1 / 1 | 11 / 8 / 0 / 8 | 15 / 8 / 0 / 4 | 7 / 4 / 0 / 4 | 11 / 7 / 0 / 7 / 1 | 7 / 3 / 0 / 3 / 1 |
| critical-path | 16 | 6 | 0.667 / 0.815 / 0.667 / 0.654 / 0.667 | 0.296 / 0.148 / 0.267 / 0.308 / 0.267 | 0.630 / 0.630 / 0.600 / 0.654 / 0.600 | 0 / 0 / 0 / 0 / 0 | 7 / 3 / 3 / 0 / 0 | 0 / 0 / 0 / 1 / 1 | 11 / 8 / 0 / 8 | 15 / 8 / 0 / 4 | 7 / 4 / 0 / 4 | 11 / 7 / 0 / 7 / 1 | 8 / 3 / 0 / 3 / 1 |
| critical-path | 16 | 7 | 0.667 / 0.815 / 0.667 / 0.654 / 0.667 | 0.296 / 0.148 / 0.267 / 0.308 / 0.267 | 0.630 / 0.630 / 0.600 / 0.654 / 0.600 | 0 / 0 / 0 / 0 / 0 | 7 / 3 / 3 / 0 / 0 | 0 / 0 / 0 / 1 / 1 | 11 / 8 / 0 / 8 | 15 / 8 / 0 / 4 | 7 / 4 / 0 / 4 | 11 / 7 / 0 / 7 / 1 | 8 / 3 / 0 / 3 / 1 |
| critical-path | 16 | 8 | 0.667 / 0.815 / 0.706 / 0.654 / 0.706 | 0.296 / 0.148 / 0.235 / 0.308 / 0.235 | 0.630 / 0.630 / 0.529 / 0.654 / 0.529 | 0 / 0 / 0 / 0 / 0 | 7 / 3 / 1 / 0 / 0 | 0 / 0 / 0 / 1 / 0 | 11 / 8 / 0 / 8 | 15 / 8 / 0 / 4 | 9 / 4 / 0 / 4 | 11 / 7 / 0 / 7 / 1 | 9 / 4 / 0 / 4 / 0 |
| program-order | 16 | 0 | 0.679 / 0.828 / 0.706 / 0.679 / 0.688 | 0.286 / 0.138 / 0.235 / 0.286 / 0.250 | 0.607 / 0.586 / 0.529 / 0.607 / 0.563 | 0 / 0 / 0 / 0 / 0 | 5 / 0 / 2 / 0 / 0 | 0 / 0 / 0 / 1 / 1 | 12 / 8 / 0 / 8 | 17 / 8 / 0 / 4 | 9 / 4 / 0 / 4 | 13 / 7 / 0 / 7 / 1 | 9 / 3 / 0 / 3 / 1 |
| program-order | 16 | 1 | 0.679 / 0.828 / 0.706 / 0.679 / 0.688 | 0.286 / 0.138 / 0.235 / 0.286 / 0.250 | 0.607 / 0.586 / 0.529 / 0.607 / 0.563 | 0 / 0 / 0 / 0 / 0 | 5 / 0 / 2 / 0 / 0 | 0 / 0 / 0 / 1 / 1 | 12 / 8 / 0 / 8 | 17 / 8 / 0 / 4 | 9 / 4 / 0 / 4 | 13 / 7 / 0 / 7 / 1 | 9 / 3 / 0 / 3 / 1 |
| program-order | 16 | 2 | 0.679 / 0.828 / 0.706 / 0.679 / 0.688 | 0.286 / 0.138 / 0.235 / 0.286 / 0.250 | 0.607 / 0.586 / 0.529 / 0.607 / 0.563 | 0 / 0 / 0 / 0 / 0 | 5 / 0 / 2 / 0 / 0 | 0 / 0 / 0 / 1 / 1 | 12 / 8 / 0 / 8 | 17 / 8 / 0 / 4 | 9 / 4 / 0 / 4 | 13 / 7 / 0 / 7 / 1 | 9 / 3 / 0 / 3 / 1 |
| program-order | 16 | 3 | 0.679 / 0.828 / 0.706 / 0.679 / 0.688 | 0.286 / 0.138 / 0.235 / 0.286 / 0.250 | 0.607 / 0.586 / 0.529 / 0.607 / 0.563 | 0 / 0 / 0 / 0 / 0 | 5 / 0 / 2 / 0 / 0 | 0 / 0 / 0 / 1 / 1 | 12 / 8 / 0 / 8 | 17 / 8 / 0 / 4 | 9 / 4 / 0 / 4 | 13 / 7 / 0 / 7 / 1 | 9 / 3 / 0 / 3 / 1 |
| program-order | 16 | 4 | 0.679 / 0.828 / 0.706 / 0.679 / 0.688 | 0.286 / 0.138 / 0.235 / 0.286 / 0.250 | 0.607 / 0.586 / 0.529 / 0.607 / 0.563 | 0 / 0 / 0 / 0 / 0 | 5 / 0 / 2 / 0 / 0 | 0 / 0 / 0 / 1 / 1 | 12 / 8 / 0 / 8 | 17 / 8 / 0 / 4 | 9 / 4 / 0 / 4 | 13 / 7 / 0 / 7 / 1 | 9 / 3 / 0 / 3 / 1 |
| program-order | 16 | 5 | 0.679 / 0.828 / 0.706 / 0.679 / 0.688 | 0.286 / 0.138 / 0.235 / 0.286 / 0.250 | 0.607 / 0.586 / 0.529 / 0.607 / 0.563 | 0 / 0 / 0 / 0 / 0 | 5 / 0 / 2 / 0 / 0 | 0 / 0 / 0 / 1 / 1 | 12 / 8 / 0 / 8 | 17 / 8 / 0 / 4 | 9 / 4 / 0 / 4 | 13 / 7 / 0 / 7 / 1 | 9 / 3 / 0 / 3 / 1 |
| program-order | 16 | 6 | 0.679 / 0.828 / 0.706 / 0.679 / 0.688 | 0.286 / 0.138 / 0.235 / 0.286 / 0.250 | 0.607 / 0.586 / 0.529 / 0.607 / 0.563 | 0 / 0 / 0 / 0 / 0 | 5 / 0 / 2 / 0 / 0 | 0 / 0 / 0 / 1 / 1 | 12 / 8 / 0 / 8 | 17 / 8 / 0 / 4 | 9 / 4 / 0 / 4 | 13 / 7 / 0 / 7 / 1 | 9 / 3 / 0 / 3 / 1 |
| program-order | 16 | 7 | 0.679 / 0.828 / 0.706 / 0.679 / 0.688 | 0.286 / 0.138 / 0.235 / 0.286 / 0.250 | 0.607 / 0.586 / 0.529 / 0.607 / 0.563 | 0 / 0 / 0 / 0 / 0 | 5 / 0 / 2 / 0 / 0 | 0 / 0 / 0 / 1 / 1 | 12 / 8 / 0 / 8 | 17 / 8 / 0 / 4 | 9 / 4 / 0 / 4 | 13 / 7 / 0 / 7 / 1 | 9 / 3 / 0 / 3 / 1 |
| program-order | 16 | 8 | 0.679 / 0.828 / 0.706 / 0.679 / 0.706 | 0.286 / 0.138 / 0.235 / 0.286 / 0.235 | 0.607 / 0.586 / 0.529 / 0.607 / 0.529 | 0 / 0 / 0 / 0 / 0 | 5 / 0 / 2 / 0 / 0 | 0 / 0 / 0 / 1 / 2 | 12 / 8 / 0 / 8 | 17 / 8 / 0 / 4 | 9 / 4 / 0 / 4 | 13 / 7 / 0 / 7 / 1 | 11 / 2 / 0 / 2 / 2 |

- `heldByWritePort` が0でない行: mvp-1 0（合計 0）、W-reg 0（合計 0）、W-full 0（合計 0）、mvp1_rp4 0（合計 0）、mvp2_rp4 0（合計 0）
- `heldByReadPorts` が0でない行: mvp-1 20（合計 120）、W-reg 10（合計 30）、W-full 20（合計 48）、mvp1_rp4 0（合計 0）、mvp2_rp4 0（合計 0）。(MAC か VMAC) と (ST か ST.W) の bundle がある行: mvp-1 0（合計 0）、W-reg 0（合計 0）、W-full 0（合計 0）、mvp1_rp4 20（合計 20）、mvp2_rp4 19（合計 20）

### `saxpy8`（single）

| 優先度 | レジスタ上限 | load reserve | mvp-1 | W-reg | W-full | mvp1_rp4 | mvp2_rp4 | mvp-1 / W-reg | W-reg / W-full | mvp-1 / W-full | mvp1_rp4 / mvp2_rp4 | W-full / mvp2_rp4 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| critical-path | なし | - | 不成立: 同時に生きる値 17 > 16 | 不成立: 同時に生きる値 17 > 16 | 不成立: 同時に生きる値 17 > 16 | 不成立: 同時に生きる値 17 > 16 | 不成立: 同時に生きる値 17 > 16 | - | - | - | - | - |
| program-order | なし | - | 35 | 32 | 19 | 35 | 19 | 1.09 | 1.68 | 1.84 | 1.84 | 1.00 |
| critical-path | 16 | 0 | **34** | **30** | **18** | **34** | **18** | 1.13 | 1.67 | 1.89 | 1.89 | 1.00 |
| critical-path | 16 | 1 | 34 | 30 | 18 | 34 | 18 | 1.13 | 1.67 | 1.89 | 1.89 | 1.00 |
| critical-path | 16 | 2 | 34 | 30 | 18 | 34 | 18 | 1.13 | 1.67 | 1.89 | 1.89 | 1.00 |
| critical-path | 16 | 3 | 34 | 30 | 18 | 34 | 18 | 1.13 | 1.67 | 1.89 | 1.89 | 1.00 |
| critical-path | 16 | 4 | 34 | 30 | 18 | 34 | 18 | 1.13 | 1.67 | 1.89 | 1.89 | 1.00 |
| critical-path | 16 | 5 | 34 | 30 | 18 | 34 | 18 | 1.13 | 1.67 | 1.89 | 1.89 | 1.00 |
| critical-path | 16 | 6 | 34 | 30 | 18 | 34 | 18 | 1.13 | 1.67 | 1.89 | 1.89 | 1.00 |
| critical-path | 16 | 7 | 34 | 30 | 18 | 34 | 18 | 1.13 | 1.67 | 1.89 | 1.89 | 1.00 |
| critical-path | 16 | 8 | 34 | 30 | 19 | 34 | 19 | 1.13 | 1.58 | 1.79 | 1.79 | 1.00 |
| program-order | 16 | 0 | 35 | 32 | 19 | 35 | 19 | 1.09 | 1.68 | 1.84 | 1.84 | 1.00 |
| program-order | 16 | 1 | 35 | 32 | 19 | 35 | 19 | 1.09 | 1.68 | 1.84 | 1.84 | 1.00 |
| program-order | 16 | 2 | 35 | 32 | 19 | 35 | 19 | 1.09 | 1.68 | 1.84 | 1.84 | 1.00 |
| program-order | 16 | 3 | 35 | 32 | 19 | 35 | 19 | 1.09 | 1.68 | 1.84 | 1.84 | 1.00 |
| program-order | 16 | 4 | 35 | 32 | 19 | 35 | 19 | 1.09 | 1.68 | 1.84 | 1.84 | 1.00 |
| program-order | 16 | 5 | 35 | 32 | 19 | 35 | 19 | 1.09 | 1.68 | 1.84 | 1.84 | 1.00 |
| program-order | 16 | 6 | 35 | 32 | 19 | 35 | 19 | 1.09 | 1.68 | 1.84 | 1.84 | 1.00 |
| program-order | 16 | 7 | 35 | 32 | 19 | 35 | 19 | 1.09 | 1.68 | 1.84 | 1.84 | 1.00 |
| program-order | 16 | 8 | 35 | 32 | 19 | 35 | 19 | 1.09 | 1.68 | 1.84 | 1.84 | 1.00 |

- mvp-1 / W-reg: 両方で成立する行 19（W-reg の cycles が少ない 19、同じ 0、多い 0。比 1.09〜1.13）、mvp-1 だけで成立 0、W-reg だけで成立 0、どちらも不成立 1
- W-reg / W-full: 両方で成立する行 19（W-full の cycles が少ない 19、同じ 0、多い 0。比 1.58〜1.68）、W-reg だけで成立 0、W-full だけで成立 0、どちらも不成立 1
- mvp-1 / W-full: 両方で成立する行 19（W-full の cycles が少ない 19、同じ 0、多い 0。比 1.79〜1.89）、mvp-1 だけで成立 0、W-full だけで成立 0、どちらも不成立 1
- mvp1_rp4 / mvp2_rp4: 両方で成立する行 19（mvp2_rp4 の cycles が少ない 19、同じ 0、多い 0。比 1.79〜1.89）、mvp1_rp4 だけで成立 0、mvp2_rp4 だけで成立 0、どちらも不成立 1
- W-full / mvp2_rp4: 両方で成立する行 19（mvp2_rp4 の cycles が少ない 0、同じ 19、多い 0。比 1.00〜1.00）、W-full だけで成立 0、mvp2_rp4 だけで成立 0、どちらも不成立 1
- 不成立の行は mvp-1 1（TooManyLive 1）、W-reg 1（TooManyLive 1）、W-full 1（TooManyLive 1）、mvp1_rp4 1（TooManyLive 1）、mvp2_rp4 1（TooManyLive 1）。LD を複製した行は mvp-1 0、W-reg 0、W-full 0、mvp1_rp4 0、mvp2_rp4 0

同じ行のスロットとポート（構成の順は mvp-1 / W-reg / W-full / mvp1_rp4 / mvp2_rp4）:

| 優先度 | レジスタ上限 | load reserve | Compute NOP率 | 書戻し Compute 利用率 | 書戻し Memory 利用率 | `heldByWritePort` | `heldByReadPorts` | (MAC か VMAC) と (ST か ST.W) の bundle | 読出しの欄の数ごとの bundle（mvp-1） | 読出しの欄の数ごとの bundle（W-reg） | 読出しの欄の数ごとの bundle（W-full） | 読出しの欄の数ごとの bundle（mvp1_rp4） | 読出しの欄の数ごとの bundle（mvp2_rp4） |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| critical-path | なし | - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| program-order | なし | - | 0.743 / 0.844 / 0.737 / 0.743 / 0.737 | 0.229 / 0.125 / 0.211 / 0.229 / 0.211 | 0.486 / 0.531 / 0.474 / 0.486 / 0.474 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 19 / 8 / 0 / 8 | 20 / 8 / 0 / 4 | 11 / 4 / 0 / 4 | 19 / 8 / 0 / 8 / 0 | 11 / 4 / 0 / 4 / 0 |
| critical-path | 16 | 0 | 0.735 / 0.833 / 0.722 / 0.735 / 0.722 | 0.235 / 0.133 / 0.222 / 0.235 / 0.222 | 0.500 / 0.567 / 0.500 / 0.500 / 0.500 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 18 / 8 / 0 / 8 | 18 / 8 / 0 / 4 | 10 / 4 / 0 / 4 | 18 / 8 / 0 / 8 / 0 | 10 / 4 / 0 / 4 / 0 |
| critical-path | 16 | 1 | 0.735 / 0.833 / 0.722 / 0.735 / 0.722 | 0.235 / 0.133 / 0.222 / 0.235 / 0.222 | 0.500 / 0.567 / 0.500 / 0.500 / 0.500 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 18 / 8 / 0 / 8 | 18 / 8 / 0 / 4 | 10 / 4 / 0 / 4 | 18 / 8 / 0 / 8 / 0 | 10 / 4 / 0 / 4 / 0 |
| critical-path | 16 | 2 | 0.735 / 0.833 / 0.722 / 0.735 / 0.722 | 0.235 / 0.133 / 0.222 / 0.235 / 0.222 | 0.500 / 0.567 / 0.500 / 0.500 / 0.500 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 18 / 8 / 0 / 8 | 18 / 8 / 0 / 4 | 10 / 4 / 0 / 4 | 18 / 8 / 0 / 8 / 0 | 10 / 4 / 0 / 4 / 0 |
| critical-path | 16 | 3 | 0.735 / 0.833 / 0.722 / 0.735 / 0.722 | 0.235 / 0.133 / 0.222 / 0.235 / 0.222 | 0.500 / 0.567 / 0.500 / 0.500 / 0.500 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 18 / 8 / 0 / 8 | 18 / 8 / 0 / 4 | 10 / 4 / 0 / 4 | 18 / 8 / 0 / 8 / 0 | 10 / 4 / 0 / 4 / 0 |
| critical-path | 16 | 4 | 0.735 / 0.833 / 0.722 / 0.735 / 0.722 | 0.235 / 0.133 / 0.222 / 0.235 / 0.222 | 0.500 / 0.567 / 0.500 / 0.500 / 0.500 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 18 / 8 / 0 / 8 | 18 / 8 / 0 / 4 | 10 / 4 / 0 / 4 | 18 / 8 / 0 / 8 / 0 | 10 / 4 / 0 / 4 / 0 |
| critical-path | 16 | 5 | 0.735 / 0.833 / 0.722 / 0.735 / 0.722 | 0.235 / 0.133 / 0.222 / 0.235 / 0.222 | 0.500 / 0.567 / 0.500 / 0.500 / 0.500 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 18 / 8 / 0 / 8 | 18 / 8 / 0 / 4 | 10 / 4 / 0 / 4 | 18 / 8 / 0 / 8 / 0 | 10 / 4 / 0 / 4 / 0 |
| critical-path | 16 | 6 | 0.735 / 0.833 / 0.722 / 0.735 / 0.722 | 0.235 / 0.133 / 0.222 / 0.235 / 0.222 | 0.500 / 0.567 / 0.500 / 0.500 / 0.500 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 18 / 8 / 0 / 8 | 18 / 8 / 0 / 4 | 10 / 4 / 0 / 4 | 18 / 8 / 0 / 8 / 0 | 10 / 4 / 0 / 4 / 0 |
| critical-path | 16 | 7 | 0.735 / 0.833 / 0.722 / 0.735 / 0.722 | 0.235 / 0.133 / 0.222 / 0.235 / 0.222 | 0.500 / 0.567 / 0.500 / 0.500 / 0.500 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 18 / 8 / 0 / 8 | 18 / 8 / 0 / 4 | 10 / 4 / 0 / 4 | 18 / 8 / 0 / 8 / 0 | 10 / 4 / 0 / 4 / 0 |
| critical-path | 16 | 8 | 0.735 / 0.833 / 0.737 / 0.735 / 0.737 | 0.235 / 0.133 / 0.211 / 0.235 / 0.211 | 0.500 / 0.567 / 0.474 / 0.500 / 0.474 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 18 / 8 / 0 / 8 | 18 / 8 / 0 / 4 | 11 / 4 / 0 / 4 | 18 / 8 / 0 / 8 / 0 | 11 / 4 / 0 / 4 / 0 |
| program-order | 16 | 0 | 0.743 / 0.844 / 0.737 / 0.743 / 0.737 | 0.229 / 0.125 / 0.211 / 0.229 / 0.211 | 0.486 / 0.531 / 0.474 / 0.486 / 0.474 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 19 / 8 / 0 / 8 | 20 / 8 / 0 / 4 | 11 / 4 / 0 / 4 | 19 / 8 / 0 / 8 / 0 | 11 / 4 / 0 / 4 / 0 |
| program-order | 16 | 1 | 0.743 / 0.844 / 0.737 / 0.743 / 0.737 | 0.229 / 0.125 / 0.211 / 0.229 / 0.211 | 0.486 / 0.531 / 0.474 / 0.486 / 0.474 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 19 / 8 / 0 / 8 | 20 / 8 / 0 / 4 | 11 / 4 / 0 / 4 | 19 / 8 / 0 / 8 / 0 | 11 / 4 / 0 / 4 / 0 |
| program-order | 16 | 2 | 0.743 / 0.844 / 0.737 / 0.743 / 0.737 | 0.229 / 0.125 / 0.211 / 0.229 / 0.211 | 0.486 / 0.531 / 0.474 / 0.486 / 0.474 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 19 / 8 / 0 / 8 | 20 / 8 / 0 / 4 | 11 / 4 / 0 / 4 | 19 / 8 / 0 / 8 / 0 | 11 / 4 / 0 / 4 / 0 |
| program-order | 16 | 3 | 0.743 / 0.844 / 0.737 / 0.743 / 0.737 | 0.229 / 0.125 / 0.211 / 0.229 / 0.211 | 0.486 / 0.531 / 0.474 / 0.486 / 0.474 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 19 / 8 / 0 / 8 | 20 / 8 / 0 / 4 | 11 / 4 / 0 / 4 | 19 / 8 / 0 / 8 / 0 | 11 / 4 / 0 / 4 / 0 |
| program-order | 16 | 4 | 0.743 / 0.844 / 0.737 / 0.743 / 0.737 | 0.229 / 0.125 / 0.211 / 0.229 / 0.211 | 0.486 / 0.531 / 0.474 / 0.486 / 0.474 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 19 / 8 / 0 / 8 | 20 / 8 / 0 / 4 | 11 / 4 / 0 / 4 | 19 / 8 / 0 / 8 / 0 | 11 / 4 / 0 / 4 / 0 |
| program-order | 16 | 5 | 0.743 / 0.844 / 0.737 / 0.743 / 0.737 | 0.229 / 0.125 / 0.211 / 0.229 / 0.211 | 0.486 / 0.531 / 0.474 / 0.486 / 0.474 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 19 / 8 / 0 / 8 | 20 / 8 / 0 / 4 | 11 / 4 / 0 / 4 | 19 / 8 / 0 / 8 / 0 | 11 / 4 / 0 / 4 / 0 |
| program-order | 16 | 6 | 0.743 / 0.844 / 0.737 / 0.743 / 0.737 | 0.229 / 0.125 / 0.211 / 0.229 / 0.211 | 0.486 / 0.531 / 0.474 / 0.486 / 0.474 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 19 / 8 / 0 / 8 | 20 / 8 / 0 / 4 | 11 / 4 / 0 / 4 | 19 / 8 / 0 / 8 / 0 | 11 / 4 / 0 / 4 / 0 |
| program-order | 16 | 7 | 0.743 / 0.844 / 0.737 / 0.743 / 0.737 | 0.229 / 0.125 / 0.211 / 0.229 / 0.211 | 0.486 / 0.531 / 0.474 / 0.486 / 0.474 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 19 / 8 / 0 / 8 | 20 / 8 / 0 / 4 | 11 / 4 / 0 / 4 | 19 / 8 / 0 / 8 / 0 | 11 / 4 / 0 / 4 / 0 |
| program-order | 16 | 8 | 0.743 / 0.844 / 0.737 / 0.743 / 0.737 | 0.229 / 0.125 / 0.211 / 0.229 / 0.211 | 0.486 / 0.531 / 0.474 / 0.486 / 0.474 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 19 / 8 / 0 / 8 | 20 / 8 / 0 / 4 | 11 / 4 / 0 / 4 | 19 / 8 / 0 / 8 / 0 | 11 / 4 / 0 / 4 / 0 |

- `heldByWritePort` が0でない行: mvp-1 0（合計 0）、W-reg 0（合計 0）、W-full 0（合計 0）、mvp1_rp4 0（合計 0）、mvp2_rp4 0（合計 0）
- `heldByReadPorts` が0でない行: mvp-1 0（合計 0）、W-reg 0（合計 0）、W-full 0（合計 0）、mvp1_rp4 0（合計 0）、mvp2_rp4 0（合計 0）。(MAC か VMAC) と (ST か ST.W) の bundle がある行: mvp-1 0（合計 0）、W-reg 0（合計 0）、W-full 0（合計 0）、mvp1_rp4 0（合計 0）、mvp2_rp4 0（合計 0）

### `fir4x8`（vliw）

| 優先度 | レジスタ上限 | load reserve | mvp-1 | W-reg | W-full | mvp1_rp4 | mvp2_rp4 | mvp-1 / W-reg | W-reg / W-full | mvp-1 / W-full | mvp1_rp4 / mvp2_rp4 | W-full / mvp2_rp4 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| critical-path | なし | - | 不成立: 同時に生きる値 20 > 16 | 不成立: 同時に生きる値 23 > 16 | 不成立: 同時に生きる値 23 > 16 | 不成立: 同時に生きる値 20 > 16 | 不成立: 同時に生きる値 23 > 16 | - | - | - | - | - |
| program-order | なし | - | **40** | **37** | 不成立: 同時に生きる値 18 > 16 | 40 | 不成立: 同時に生きる値 18 > 16 | 1.08 | - | - | - | - |
| critical-path | 16 | 0 | 46 | 不成立: 上限の下で行き詰まる | **25**（LD 18） | 40 | **23**（LD 18） | - | - | 1.84 | 1.74 | 1.09 |
| critical-path | 16 | 1 | 44（LD 27） | 不成立: 上限の下で行き詰まる | 25（LD 18） | **38**（LD 27） | 23（LD 18） | - | - | 1.76 | 1.65 | 1.09 |
| critical-path | 16 | 2 | 44（LD 30） | 不成立: 上限の下で行き詰まる | 27（LD 20） | 39（LD 30） | 25（LD 20） | - | - | 1.63 | 1.56 | 1.08 |
| critical-path | 16 | 3 | 44（LD 31） | 不成立: 上限の下で行き詰まる | 27（LD 20） | 40（LD 31） | 25（LD 20） | - | - | 1.63 | 1.60 | 1.08 |
| critical-path | 16 | 4 | 44（LD 32） | 不成立: 上限の下で行き詰まる | 34（LD 27） | 41（LD 32） | 34（LD 27） | - | - | 1.29 | 1.21 | 1.00 |
| critical-path | 16 | 5 | 44（LD 33） | 不成立: 上限の下で行き詰まる | 43（LD 27） | 42（LD 33） | 42（LD 27） | - | - | 1.02 | 1.00 | 1.02 |
| critical-path | 16 | 6 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - | - |
| critical-path | 16 | 7 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - | - |
| critical-path | 16 | 8 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - | - |
| program-order | 16 | 0 | 40 | 37 | 29 | 40 | 29 | 1.08 | 1.28 | 1.38 | 1.38 | 1.00 |
| program-order | 16 | 1 | 40 | 37 | 27 | 40 | 27 | 1.08 | 1.37 | 1.48 | 1.48 | 1.00 |
| program-order | 16 | 2 | 40 | 37 | 27 | 40 | 27 | 1.08 | 1.37 | 1.48 | 1.48 | 1.00 |
| program-order | 16 | 3 | 40 | 37 | 27 | 40 | 27 | 1.08 | 1.37 | 1.48 | 1.48 | 1.00 |
| program-order | 16 | 4 | 40 | 37 | 27 | 40 | 27 | 1.08 | 1.37 | 1.48 | 1.48 | 1.00 |
| program-order | 16 | 5 | 40 | 42 | 29 | 40 | 29 | 0.95 | 1.45 | 1.38 | 1.38 | 1.00 |
| program-order | 16 | 6 | 40 | 46 | 29 | 40 | 29 | 0.87 | 1.59 | 1.38 | 1.38 | 1.00 |
| program-order | 16 | 7 | 40 | 49（LD 34） | 35（LD 26） | 40 | 35（LD 26） | 0.82 | 1.40 | 1.14 | 1.14 | 1.00 |
| program-order | 16 | 8 | 49 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 45 | 不成立: 上限の下で行き詰まる | - | - | - | - | - |

- mvp-1 / W-reg: 両方で成立する行 9（W-reg の cycles が少ない 6、同じ 0、多い 3。比 0.82〜1.08）、mvp-1 だけで成立 7、W-reg だけで成立 0、どちらも不成立 4
- W-reg / W-full: 両方で成立する行 8（W-full の cycles が少ない 8、同じ 0、多い 0。比 1.28〜1.59）、W-reg だけで成立 1、W-full だけで成立 6、どちらも不成立 5
- mvp-1 / W-full: 両方で成立する行 14（W-full の cycles が少ない 14、同じ 0、多い 0。比 1.02〜1.84）、mvp-1 だけで成立 2、W-full だけで成立 0、どちらも不成立 4
- mvp1_rp4 / mvp2_rp4: 両方で成立する行 14（mvp2_rp4 の cycles が少ない 13、同じ 1、多い 0。比 1.00〜1.74）、mvp1_rp4 だけで成立 2、mvp2_rp4 だけで成立 0、どちらも不成立 4
- W-full / mvp2_rp4: 両方で成立する行 14（mvp2_rp4 の cycles が少ない 5、同じ 9、多い 0。比 1.00〜1.09）、W-full だけで成立 0、mvp2_rp4 だけで成立 0、どちらも不成立 6
- 不成立の行は mvp-1 4（TooManyLive 1、Stuck 3）、W-reg 11（TooManyLive 1、Stuck 10）、W-full 6（TooManyLive 2、Stuck 4）、mvp1_rp4 4（TooManyLive 1、Stuck 3）、mvp2_rp4 6（TooManyLive 2、Stuck 4）。LD を複製した行は mvp-1 5、W-reg 1、W-full 7、mvp1_rp4 5、mvp2_rp4 7

同じ行のスロットとポート（構成の順は mvp-1 / W-reg / W-full / mvp1_rp4 / mvp2_rp4）:

| 優先度 | レジスタ上限 | load reserve | Compute NOP率 | 書戻し Compute 利用率 | 書戻し Memory 利用率 | `heldByWritePort` | `heldByReadPorts` | (MAC か VMAC) と (ST か ST.W) の bundle | 読出しの欄の数ごとの bundle（mvp-1） | 読出しの欄の数ごとの bundle（W-reg） | 読出しの欄の数ごとの bundle（W-full） | 読出しの欄の数ごとの bundle（mvp1_rp4） | 読出しの欄の数ごとの bundle（mvp2_rp4） |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| critical-path | なし | - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| program-order | なし | - | 0.175 / 0.541 / - / 0.175 / - | 0.800 / 0.432 / - / 0.800 / - | 0.375 / 0.649 / - / 0.375 / - | 0 / 0 / - / 0 / - | 0 / 6 / - / 0 / - | 0 / 0 / - / 0 / - | 6 / 2 / 2 / 30 | 15 / 6 / 2 / 14 | - | 6 / 2 / 2 / 30 / 0 | - |
| critical-path | 16 | 0 | 0.283 / - / 0.320 / 0.175 / 0.261 | 0.696 / - / 0.640 / 0.800 / 0.696 | 0.326 / - / 0.720 / 0.375 / 0.783 | 0 / - / 0 / 0 / 0 | 6 / - / 2 / 0 / 0 | 0 / - / 0 / 6 / 2 | 6 / 8 / 8 / 24 | - | 5 / 4 / 4 / 12 | 6 / 2 / 8 / 18 / 6 | 5 / 2 / 4 / 10 / 2 |
| critical-path | 16 | 1 | 0.250 / - / 0.320 / 0.132 / 0.261 | 0.727 / - / 0.640 / 0.842 / 0.696 | 0.614 / - / 0.720 / 0.711 / 0.783 | 0 / - / 0 / 0 / 0 | 9 / - / 2 / 0 / 0 | 0 / - / 0 / 6 / 2 | 4 / 8 / 8 / 24 | - | 5 / 4 / 4 / 12 | 4 / 2 / 8 / 18 / 6 | 5 / 2 / 4 / 10 / 2 |
| critical-path | 16 | 2 | 0.250 / - / 0.370 / 0.154 / 0.320 | 0.727 / - / 0.593 / 0.821 / 0.640 | 0.682 / - / 0.741 / 0.769 / 0.800 | 0 / - / 0 / 0 / 0 | 6 / - / 2 / 0 / 0 | 0 / - / 0 / 5 / 2 | 4 / 8 / 8 / 24 | - | 7 / 4 / 4 / 12 | 4 / 3 / 8 / 19 / 5 | 7 / 2 / 4 / 10 / 2 |
| critical-path | 16 | 3 | 0.250 / - / 0.370 / 0.175 / 0.320 | 0.727 / - / 0.593 / 0.800 / 0.640 | 0.705 / - / 0.741 / 0.775 / 0.800 | 0 / - / 0 / 0 / 0 | 6 / - / 2 / 0 / 0 | 0 / - / 0 / 4 / 2 | 4 / 8 / 8 / 24 | - | 7 / 4 / 4 / 12 | 4 / 4 / 8 / 20 / 4 | 7 / 2 / 4 / 10 / 2 |
| critical-path | 16 | 4 | 0.250 / - / 0.500 / 0.195 / 0.500 | 0.727 / - / 0.471 / 0.780 / 0.471 | 0.727 / - / 0.794 / 0.780 / 0.794 | 0 / - / 0 / 0 / 0 | 6 / - / 3 / 0 / 0 | 0 / - / 0 / 3 / 1 | 4 / 8 / 8 / 24 | - | 14 / 4 / 4 / 12 | 4 / 5 / 8 / 21 / 3 | 15 / 3 / 4 / 11 / 1 |
| critical-path | 16 | 5 | 0.250 / - / 0.605 / 0.214 / 0.595 | 0.727 / - / 0.372 / 0.762 / 0.381 | 0.750 / - / 0.628 / 0.786 / 0.643 | 0 / - / 0 / 0 / 0 | 6 / - / 2 / 0 / 0 | 0 / - / 0 / 2 / 1 | 4 / 8 / 8 / 24 | - | 23 / 4 / 4 / 12 | 4 / 6 / 8 / 22 / 2 | 23 / 3 / 4 / 11 / 1 |
| critical-path | 16 | 6 | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| critical-path | 16 | 7 | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| critical-path | 16 | 8 | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| program-order | 16 | 0 | 0.175 / 0.541 / 0.414 / 0.175 / 0.414 | 0.800 / 0.432 / 0.552 / 0.800 / 0.552 | 0.375 / 0.649 / 0.483 / 0.375 / 0.483 | 0 / 0 / 0 / 0 / 0 | 0 / 6 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 6 / 2 / 2 / 30 | 15 / 6 / 2 / 14 | 9 / 4 / 4 / 12 | 6 / 2 / 2 / 30 / 0 | 9 / 4 / 4 / 12 / 0 |
| program-order | 16 | 1 | 0.175 / 0.541 / 0.370 / 0.175 / 0.370 | 0.800 / 0.432 / 0.593 / 0.800 / 0.593 | 0.375 / 0.649 / 0.519 / 0.375 / 0.519 | 0 / 0 / 0 / 0 / 0 | 0 / 6 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 6 / 2 / 2 / 30 | 15 / 6 / 2 / 14 | 9 / 2 / 2 / 14 | 6 / 2 / 2 / 30 / 0 | 9 / 2 / 2 / 14 / 0 |
| program-order | 16 | 2 | 0.175 / 0.541 / 0.370 / 0.175 / 0.370 | 0.800 / 0.432 / 0.593 / 0.800 / 0.593 | 0.375 / 0.649 / 0.519 / 0.375 / 0.519 | 0 / 0 / 0 / 0 / 0 | 0 / 6 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 6 / 2 / 2 / 30 | 15 / 6 / 2 / 14 | 9 / 2 / 2 / 14 | 6 / 2 / 2 / 30 / 0 | 9 / 2 / 2 / 14 / 0 |
| program-order | 16 | 3 | 0.175 / 0.541 / 0.370 / 0.175 / 0.370 | 0.800 / 0.432 / 0.593 / 0.800 / 0.593 | 0.375 / 0.649 / 0.519 / 0.375 / 0.519 | 0 / 0 / 0 / 0 / 0 | 0 / 2 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 6 / 2 / 2 / 30 | 14 / 7 / 3 / 13 | 9 / 2 / 2 / 14 | 6 / 2 / 2 / 30 / 0 | 9 / 2 / 2 / 14 / 0 |
| program-order | 16 | 4 | 0.175 / 0.541 / 0.370 / 0.175 / 0.370 | 0.800 / 0.432 / 0.593 / 0.800 / 0.593 | 0.375 / 0.649 / 0.519 / 0.375 / 0.519 | 0 / 0 / 0 / 0 / 0 | 0 / 2 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 6 / 2 / 2 / 30 | 14 / 7 / 3 / 13 | 9 / 2 / 2 / 14 | 6 / 2 / 2 / 30 / 0 | 9 / 2 / 2 / 14 / 0 |
| program-order | 16 | 5 | 0.175 / 0.595 / 0.414 / 0.175 / 0.414 | 0.800 / 0.381 / 0.552 / 0.800 / 0.552 | 0.375 / 0.571 / 0.483 / 0.375 / 0.483 | 0 / 0 / 0 / 0 / 0 | 0 / 1 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 6 / 2 / 2 / 30 | 18 / 8 / 4 / 12 | 9 / 4 / 4 / 12 | 6 / 2 / 2 / 30 / 0 | 9 / 4 / 4 / 12 / 0 |
| program-order | 16 | 6 | 0.175 / 0.630 / 0.414 / 0.175 / 0.414 | 0.800 / 0.348 / 0.552 / 0.800 / 0.552 | 0.375 / 0.522 / 0.483 / 0.375 / 0.483 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 6 / 2 / 2 / 30 | 22 / 8 / 4 / 12 | 9 / 4 / 4 / 12 | 6 / 2 / 2 / 30 / 0 | 9 / 4 / 4 / 12 / 0 |
| program-order | 16 | 7 | 0.175 / 0.653 / 0.514 / 0.175 / 0.514 | 0.800 / 0.327 / 0.457 / 0.800 / 0.457 | 0.375 / 0.694 / 0.743 / 0.375 / 0.743 | 0 / 0 / 0 / 0 / 0 | 0 / 1 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 6 / 2 / 2 / 30 | 27 / 6 / 2 / 14 | 16 / 3 / 3 / 13 | 6 / 2 / 2 / 30 / 0 | 16 / 3 / 3 / 13 / 0 |
| program-order | 16 | 8 | 0.327 / - / - / 0.267 / - | 0.653 / - / - / 0.711 / - | 0.306 / - / - / 0.333 / - | 0 / - / - / 0 / - | 8 / - / - / 0 / - | 0 / - / - / 5 / - | 14 / 3 / 3 / 29 | - | - | 11 / 2 / 7 / 20 / 5 | - |

- `heldByWritePort` が0でない行: mvp-1 0（合計 0）、W-reg 0（合計 0）、W-full 0（合計 0）、mvp1_rp4 0（合計 0）、mvp2_rp4 0（合計 0）
- `heldByReadPorts` が0でない行: mvp-1 7（合計 47）、W-reg 8（合計 30）、W-full 6（合計 13）、mvp1_rp4 0（合計 0）、mvp2_rp4 0（合計 0）。(MAC か VMAC) と (ST か ST.W) の bundle がある行: mvp-1 0（合計 0）、W-reg 0（合計 0）、W-full 0（合計 0）、mvp1_rp4 7（合計 31）、mvp2_rp4 6（合計 10）

### `fir4x8`（single）

| 優先度 | レジスタ上限 | load reserve | mvp-1 | W-reg | W-full | mvp1_rp4 | mvp2_rp4 | mvp-1 / W-reg | W-reg / W-full | mvp-1 / W-full | mvp1_rp4 / mvp2_rp4 | W-full / mvp2_rp4 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| critical-path | なし | - | 不成立: 同時に生きる値 18 > 16 | 不成立: 同時に生きる値 24 > 16 | 不成立: 同時に生きる値 24 > 16 | 不成立: 同時に生きる値 18 > 16 | 不成立: 同時に生きる値 24 > 16 | - | - | - | - | - |
| program-order | なし | - | **58** | 52 | **37** | **58** | **37** | 1.12 | 1.41 | 1.57 | 1.57 | 1.00 |
| critical-path | 16 | 0 | 59 | 不成立: 上限の下で行き詰まる | 38（LD 17） | 59 | 38（LD 17） | - | - | 1.55 | 1.55 | 1.00 |
| critical-path | 16 | 1 | 60（LD 19） | 不成立: 上限の下で行き詰まる | 39（LD 18） | 60（LD 19） | 39（LD 18） | - | - | 1.54 | 1.54 | 1.00 |
| critical-path | 16 | 2 | 62（LD 21） | 不成立: 上限の下で行き詰まる | 41（LD 20） | 62（LD 21） | 41（LD 20） | - | - | 1.51 | 1.51 | 1.00 |
| critical-path | 16 | 3 | 64（LD 23） | 不成立: 上限の下で行き詰まる | 41（LD 20） | 64（LD 23） | 41（LD 20） | - | - | 1.56 | 1.56 | 1.00 |
| critical-path | 16 | 4 | 66（LD 25） | 不成立: 上限の下で行き詰まる | 48（LD 26） | 66（LD 25） | 48（LD 26） | - | - | 1.38 | 1.38 | 1.00 |
| critical-path | 16 | 5 | 68（LD 27） | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 68（LD 27） | 不成立: 上限の下で行き詰まる | - | - | - | - | - |
| critical-path | 16 | 6 | 88（LD 37） | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 88（LD 37） | 不成立: 上限の下で行き詰まる | - | - | - | - | - |
| critical-path | 16 | 7 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - | - |
| critical-path | 16 | 8 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - | - |
| program-order | 16 | 0 | 58 | 52 | 37 | 58 | 37 | 1.12 | 1.41 | 1.57 | 1.57 | 1.00 |
| program-order | 16 | 1 | 58 | 52 | 37 | 58 | 37 | 1.12 | 1.41 | 1.57 | 1.57 | 1.00 |
| program-order | 16 | 2 | 58 | 52 | 37 | 58 | 37 | 1.12 | 1.41 | 1.57 | 1.57 | 1.00 |
| program-order | 16 | 3 | 58 | **51** | 37 | 58 | 37 | 1.14 | 1.38 | 1.57 | 1.57 | 1.00 |
| program-order | 16 | 4 | 58 | 51 | 37 | 58 | 37 | 1.14 | 1.38 | 1.57 | 1.57 | 1.00 |
| program-order | 16 | 5 | 58 | 53 | 39 | 58 | 39 | 1.09 | 1.36 | 1.49 | 1.49 | 1.00 |
| program-order | 16 | 6 | 58 | 53 | 39 | 58 | 39 | 1.09 | 1.36 | 1.49 | 1.49 | 1.00 |
| program-order | 16 | 7 | 58 | 55（LD 28） | 42（LD 19） | 58 | 42（LD 19） | 1.05 | 1.31 | 1.38 | 1.38 | 1.00 |
| program-order | 16 | 8 | 58 | 不成立: 上限の下で行き詰まる | 49（LD 25） | 58 | 49（LD 25） | - | - | 1.18 | 1.18 | 1.00 |

- mvp-1 / W-reg: 両方で成立する行 9（W-reg の cycles が少ない 9、同じ 0、多い 0。比 1.05〜1.14）、mvp-1 だけで成立 8、W-reg だけで成立 0、どちらも不成立 3
- W-reg / W-full: 両方で成立する行 9（W-full の cycles が少ない 9、同じ 0、多い 0。比 1.31〜1.41）、W-reg だけで成立 0、W-full だけで成立 6、どちらも不成立 5
- mvp-1 / W-full: 両方で成立する行 15（W-full の cycles が少ない 15、同じ 0、多い 0。比 1.18〜1.57）、mvp-1 だけで成立 2、W-full だけで成立 0、どちらも不成立 3
- mvp1_rp4 / mvp2_rp4: 両方で成立する行 15（mvp2_rp4 の cycles が少ない 15、同じ 0、多い 0。比 1.18〜1.57）、mvp1_rp4 だけで成立 2、mvp2_rp4 だけで成立 0、どちらも不成立 3
- W-full / mvp2_rp4: 両方で成立する行 15（mvp2_rp4 の cycles が少ない 0、同じ 15、多い 0。比 1.00〜1.00）、W-full だけで成立 0、mvp2_rp4 だけで成立 0、どちらも不成立 5
- 不成立の行は mvp-1 3（TooManyLive 1、Stuck 2）、W-reg 11（TooManyLive 1、Stuck 10）、W-full 5（TooManyLive 1、Stuck 4）、mvp1_rp4 3（TooManyLive 1、Stuck 2）、mvp2_rp4 5（TooManyLive 1、Stuck 4）。LD を複製した行は mvp-1 6、W-reg 1、W-full 7、mvp1_rp4 6、mvp2_rp4 7

同じ行のスロットとポート（構成の順は mvp-1 / W-reg / W-full / mvp1_rp4 / mvp2_rp4）:

| 優先度 | レジスタ上限 | load reserve | Compute NOP率 | 書戻し Compute 利用率 | 書戻し Memory 利用率 | `heldByWritePort` | `heldByReadPorts` | (MAC か VMAC) と (ST か ST.W) の bundle | 読出しの欄の数ごとの bundle（mvp-1） | 読出しの欄の数ごとの bundle（W-reg） | 読出しの欄の数ごとの bundle（W-full） | 読出しの欄の数ごとの bundle（mvp1_rp4） | 読出しの欄の数ごとの bundle（mvp2_rp4） |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| critical-path | なし | - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| program-order | なし | - | 0.431 / 0.673 / 0.541 / 0.431 / 0.541 | 0.552 / 0.308 / 0.432 / 0.552 / 0.432 | 0.259 / 0.462 / 0.378 / 0.259 / 0.378 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 18 / 8 / 8 / 24 | 28 / 8 / 4 / 12 | 17 / 4 / 4 / 12 | 18 / 8 / 8 / 24 / 0 | 17 / 4 / 4 / 12 / 0 |
| critical-path | 16 | 0 | 0.441 / - / 0.553 / 0.441 / 0.553 | 0.542 / - / 0.421 / 0.542 / 0.421 | 0.254 / - / 0.447 / 0.254 / 0.447 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 19 / 8 / 8 / 24 | - | 18 / 4 / 4 / 12 | 19 / 8 / 8 / 24 / 0 | 18 / 4 / 4 / 12 / 0 |
| critical-path | 16 | 1 | 0.450 / - / 0.564 / 0.450 / 0.564 | 0.533 / - / 0.410 / 0.533 / 0.410 | 0.317 / - / 0.462 / 0.317 / 0.462 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 20 / 8 / 8 / 24 | - | 19 / 4 / 4 / 12 | 20 / 8 / 8 / 24 / 0 | 19 / 4 / 4 / 12 / 0 |
| critical-path | 16 | 2 | 0.468 / - / 0.585 / 0.468 / 0.585 | 0.516 / - / 0.390 / 0.516 / 0.390 | 0.339 / - / 0.488 / 0.339 / 0.488 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 22 / 8 / 8 / 24 | - | 21 / 4 / 4 / 12 | 22 / 8 / 8 / 24 / 0 | 21 / 4 / 4 / 12 / 0 |
| critical-path | 16 | 3 | 0.484 / - / 0.585 / 0.484 / 0.585 | 0.500 / - / 0.390 / 0.500 / 0.390 | 0.359 / - / 0.488 / 0.359 / 0.488 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 24 / 8 / 8 / 24 | - | 21 / 4 / 4 / 12 | 24 / 8 / 8 / 24 / 0 | 21 / 4 / 4 / 12 / 0 |
| critical-path | 16 | 4 | 0.500 / - / 0.646 / 0.500 / 0.646 | 0.485 / - / 0.333 / 0.485 / 0.333 | 0.379 / - / 0.542 / 0.379 / 0.542 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 26 / 8 / 8 / 24 | - | 28 / 4 / 4 / 12 | 26 / 8 / 8 / 24 / 0 | 28 / 4 / 4 / 12 / 0 |
| critical-path | 16 | 5 | 0.515 / - / - / 0.515 / - | 0.471 / - / - / 0.471 / - | 0.397 / - / - / 0.397 / - | 0 / - / - / 0 / - | 0 / - / - / 0 / - | 0 / - / - / 0 / - | 28 / 8 / 8 / 24 | - | - | 28 / 8 / 8 / 24 / 0 | - |
| critical-path | 16 | 6 | 0.625 / - / - / 0.625 / - | 0.364 / - / - / 0.364 / - | 0.420 / - / - / 0.420 / - | 0 / - / - / 0 / - | 0 / - / - / 0 / - | 0 / - / - / 0 / - | 48 / 8 / 8 / 24 | - | - | 48 / 8 / 8 / 24 / 0 | - |
| critical-path | 16 | 7 | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| critical-path | 16 | 8 | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| program-order | 16 | 0 | 0.431 / 0.673 / 0.541 / 0.431 / 0.541 | 0.552 / 0.308 / 0.432 / 0.552 / 0.432 | 0.259 / 0.462 / 0.378 / 0.259 / 0.378 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 18 / 8 / 8 / 24 | 28 / 8 / 4 / 12 | 17 / 4 / 4 / 12 | 18 / 8 / 8 / 24 / 0 | 17 / 4 / 4 / 12 / 0 |
| program-order | 16 | 1 | 0.431 / 0.673 / 0.541 / 0.431 / 0.541 | 0.552 / 0.308 / 0.432 / 0.552 / 0.432 | 0.259 / 0.462 / 0.378 / 0.259 / 0.378 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 18 / 8 / 8 / 24 | 28 / 8 / 4 / 12 | 17 / 4 / 4 / 12 | 18 / 8 / 8 / 24 / 0 | 17 / 4 / 4 / 12 / 0 |
| program-order | 16 | 2 | 0.431 / 0.673 / 0.541 / 0.431 / 0.541 | 0.552 / 0.308 / 0.432 / 0.552 / 0.432 | 0.259 / 0.462 / 0.378 / 0.259 / 0.378 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 18 / 8 / 8 / 24 | 28 / 8 / 4 / 12 | 17 / 4 / 4 / 12 | 18 / 8 / 8 / 24 / 0 | 17 / 4 / 4 / 12 / 0 |
| program-order | 16 | 3 | 0.431 / 0.667 / 0.541 / 0.431 / 0.541 | 0.552 / 0.314 / 0.432 / 0.552 / 0.432 | 0.259 / 0.471 / 0.378 / 0.259 / 0.378 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 18 / 8 / 8 / 24 | 27 / 8 / 4 / 12 | 17 / 4 / 4 / 12 | 18 / 8 / 8 / 24 / 0 | 17 / 4 / 4 / 12 / 0 |
| program-order | 16 | 4 | 0.431 / 0.667 / 0.541 / 0.431 / 0.541 | 0.552 / 0.314 / 0.432 / 0.552 / 0.432 | 0.259 / 0.471 / 0.378 / 0.259 / 0.378 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 18 / 8 / 8 / 24 | 27 / 8 / 4 / 12 | 17 / 4 / 4 / 12 | 18 / 8 / 8 / 24 / 0 | 17 / 4 / 4 / 12 / 0 |
| program-order | 16 | 5 | 0.431 / 0.679 / 0.564 / 0.431 / 0.564 | 0.552 / 0.302 / 0.410 / 0.552 / 0.410 | 0.259 / 0.453 / 0.359 / 0.259 / 0.359 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 18 / 8 / 8 / 24 | 29 / 8 / 4 / 12 | 19 / 4 / 4 / 12 | 18 / 8 / 8 / 24 / 0 | 19 / 4 / 4 / 12 / 0 |
| program-order | 16 | 6 | 0.431 / 0.679 / 0.564 / 0.431 / 0.564 | 0.552 / 0.302 / 0.410 / 0.552 / 0.410 | 0.259 / 0.453 / 0.359 / 0.259 / 0.359 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 18 / 8 / 8 / 24 | 29 / 8 / 4 / 12 | 19 / 4 / 4 / 12 | 18 / 8 / 8 / 24 / 0 | 19 / 4 / 4 / 12 / 0 |
| program-order | 16 | 7 | 0.431 / 0.691 / 0.595 / 0.431 / 0.595 | 0.552 / 0.291 / 0.381 / 0.552 / 0.381 | 0.259 / 0.509 / 0.452 / 0.259 / 0.452 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 18 / 8 / 8 / 24 | 31 / 8 / 4 / 12 | 22 / 4 / 4 / 12 | 18 / 8 / 8 / 24 / 0 | 22 / 4 / 4 / 12 / 0 |
| program-order | 16 | 8 | 0.431 / - / 0.653 / 0.431 / 0.653 | 0.552 / - / 0.327 / 0.552 / 0.327 | 0.259 / - / 0.510 / 0.259 / 0.510 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 18 / 8 / 8 / 24 | - | 29 / 4 / 4 / 12 | 18 / 8 / 8 / 24 / 0 | 29 / 4 / 4 / 12 / 0 |

- `heldByWritePort` が0でない行: mvp-1 0（合計 0）、W-reg 0（合計 0）、W-full 0（合計 0）、mvp1_rp4 0（合計 0）、mvp2_rp4 0（合計 0）
- `heldByReadPorts` が0でない行: mvp-1 0（合計 0）、W-reg 0（合計 0）、W-full 0（合計 0）、mvp1_rp4 0（合計 0）、mvp2_rp4 0（合計 0）。(MAC か VMAC) と (ST か ST.W) の bundle がある行: mvp-1 0（合計 0）、W-reg 0（合計 0）、W-full 0（合計 0）、mvp1_rp4 0（合計 0）、mvp2_rp4 0（合計 0）

### `matmul3`（vliw）

| 優先度 | レジスタ上限 | load reserve | mvp-1 | W-reg | W-full | mvp1_rp4 | mvp2_rp4 | mvp-1 / W-reg | W-reg / W-full | mvp-1 / W-full | mvp1_rp4 / mvp2_rp4 | W-full / mvp2_rp4 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| critical-path | なし | - | 不成立: 同時に生きる値 19 > 16 | 不成立: cycle 13 で整列した組が空かない（生きているレジスタ 16 ≤ 16） | 不成立: 同時に生きる値 17 > 16 | 不成立: 同時に生きる値 19 > 16 | 不成立: 同時に生きる値 17 > 16 | - | - | - | - | - |
| program-order | なし | - | 不成立: 同時に生きる値 17 > 16 | 不成立: 同時に生きる値 18 > 16 | **28** | 不成立: 同時に生きる値 17 > 16 | 28 | - | - | - | - | 1.00 |
| critical-path | 16 | 0 | 40 | **32** | 28 | **33** | **24** | 1.25 | 1.14 | 1.43 | 1.38 | 1.17 |
| critical-path | 16 | 1 | 40 | 32 | 28 | 33 | 24 | 1.25 | 1.14 | 1.43 | 1.38 | 1.17 |
| critical-path | 16 | 2 | 40 | 33 | 28 | 34 | 24 | 1.21 | 1.18 | 1.43 | 1.42 | 1.17 |
| critical-path | 16 | 3 | 42 | 36 | 31 | 37 | 28 | 1.17 | 1.16 | 1.35 | 1.32 | 1.11 |
| critical-path | 16 | 4 | 45（LD 32） | 不成立: 上限の下で行き詰まる | 39（LD 25） | 39（LD 29） | 36（LD 24） | - | - | 1.15 | 1.08 | 1.08 |
| critical-path | 16 | 5 | 62（LD 39） | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 57（LD 37） | 不成立: 上限の下で行き詰まる | - | - | - | - | - |
| critical-path | 16 | 6 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - | - |
| critical-path | 16 | 7 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - | - |
| critical-path | 16 | 8 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - | - |
| program-order | 16 | 0 | **39** | 33 | 28 | 39 | 27 | 1.18 | 1.18 | 1.39 | 1.44 | 1.04 |
| program-order | 16 | 1 | 39 | 34 | 28 | 39 | 27 | 1.15 | 1.21 | 1.39 | 1.44 | 1.04 |
| program-order | 16 | 2 | 39 | 33 | 28 | 39 | 28 | 1.18 | 1.18 | 1.39 | 1.39 | 1.00 |
| program-order | 16 | 3 | 43 | 38 | 32 | 43 | 32 | 1.13 | 1.19 | 1.34 | 1.34 | 1.00 |
| program-order | 16 | 4 | 39（LD 24） | 42（LD 26） | 32（LD 22） | 38（LD 24） | 32（LD 22） | 0.93 | 1.31 | 1.22 | 1.19 | 1.00 |
| program-order | 16 | 5 | 39（LD 25） | 45（LD 29） | 34（LD 22） | 39（LD 25） | 34（LD 22） | 0.87 | 1.32 | 1.15 | 1.15 | 1.00 |
| program-order | 16 | 6 | 43（LD 27） | 49（LD 30） | 36（LD 25） | 43（LD 27） | 36（LD 25） | 0.88 | 1.36 | 1.19 | 1.19 | 1.00 |
| program-order | 16 | 7 | 47（LD 30） | 55（LD 30） | 37（LD 26） | 47（LD 30） | 37（LD 26） | 0.85 | 1.49 | 1.27 | 1.27 | 1.00 |
| program-order | 16 | 8 | 49（LD 32） | 不成立: 上限の下で行き詰まる | 40（LD 27） | 49（LD 32） | 40（LD 27） | - | - | 1.23 | 1.23 | 1.00 |

- mvp-1 / W-reg: 両方で成立する行 12（W-reg の cycles が少ない 8、同じ 0、多い 4。比 0.85〜1.25）、mvp-1 だけで成立 3、W-reg だけで成立 0、どちらも不成立 5
- W-reg / W-full: 両方で成立する行 12（W-full の cycles が少ない 12、同じ 0、多い 0。比 1.14〜1.49）、W-reg だけで成立 0、W-full だけで成立 3、どちらも不成立 5
- mvp-1 / W-full: 両方で成立する行 14（W-full の cycles が少ない 14、同じ 0、多い 0。比 1.15〜1.43）、mvp-1 だけで成立 1、W-full だけで成立 1、どちらも不成立 4
- mvp1_rp4 / mvp2_rp4: 両方で成立する行 14（mvp2_rp4 の cycles が少ない 14、同じ 0、多い 0。比 1.08〜1.44）、mvp1_rp4 だけで成立 1、mvp2_rp4 だけで成立 1、どちらも不成立 4
- W-full / mvp2_rp4: 両方で成立する行 15（mvp2_rp4 の cycles が少ない 7、同じ 8、多い 0。比 1.00〜1.17）、W-full だけで成立 0、mvp2_rp4 だけで成立 0、どちらも不成立 5
- 不成立の行は mvp-1 5（TooManyLive 2、Stuck 3）、W-reg 8（TooManyLive 1、NoAlignedPair 1、Stuck 6）、W-full 5（TooManyLive 1、Stuck 4）、mvp1_rp4 5（TooManyLive 2、Stuck 3）、mvp2_rp4 5（TooManyLive 1、Stuck 4）。LD を複製した行は mvp-1 7、W-reg 4、W-full 6、mvp1_rp4 7、mvp2_rp4 6

同じ行のスロットとポート（構成の順は mvp-1 / W-reg / W-full / mvp1_rp4 / mvp2_rp4）:

| 優先度 | レジスタ上限 | load reserve | Compute NOP率 | 書戻し Compute 利用率 | 書戻し Memory 利用率 | `heldByWritePort` | `heldByReadPorts` | (MAC か VMAC) と (ST か ST.W) の bundle | 読出しの欄の数ごとの bundle（mvp-1） | 読出しの欄の数ごとの bundle（W-reg） | 読出しの欄の数ごとの bundle（W-full） | 読出しの欄の数ごとの bundle（mvp1_rp4） | 読出しの欄の数ごとの bundle（mvp2_rp4） |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| critical-path | なし | - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| program-order | なし | - | - / - / 0.321 / - / 0.321 | - / - / 0.643 / - / 0.643 | - / - / 0.536 / - / 0.536 | - / - / 0 / - / 0 | - / - / 0 / - / 0 | - / - / 0 / - / 0 | - | - | 8 / 2 / 2 / 16 | - | 8 / 2 / 2 / 16 / 0 |
| critical-path | 16 | 0 | 0.300 / 0.406 / 0.321 / 0.152 / 0.208 | 0.675 / 0.563 / 0.643 / 0.818 / 0.750 | 0.450 / 0.563 / 0.536 / 0.545 / 0.625 | 0 / 0 / 0 / 0 / 0 | 7 / 4 / 4 / 0 / 0 | 0 / 0 / 0 / 7 / 4 | 4 / 9 / 9 / 18 | 5 / 9 / 6 / 12 | 4 / 6 / 6 / 12 | 4 / 2 / 9 / 11 / 7 | 4 / 2 / 6 / 8 / 4 |
| critical-path | 16 | 1 | 0.300 / 0.406 / 0.321 / 0.152 / 0.208 | 0.675 / 0.563 / 0.643 / 0.818 / 0.750 | 0.450 / 0.563 / 0.536 / 0.545 / 0.625 | 0 / 0 / 0 / 0 / 0 | 7 / 4 / 4 / 0 / 0 | 0 / 0 / 0 / 7 / 4 | 4 / 9 / 9 / 18 | 5 / 9 / 6 / 12 | 4 / 6 / 6 / 12 | 4 / 2 / 9 / 11 / 7 | 4 / 2 / 6 / 8 / 4 |
| critical-path | 16 | 2 | 0.300 / 0.424 / 0.321 / 0.176 / 0.208 | 0.675 / 0.545 / 0.643 / 0.794 / 0.750 | 0.450 / 0.545 / 0.536 / 0.529 / 0.625 | 0 / 0 / 0 / 0 / 0 | 7 / 4 / 4 / 0 / 0 | 0 / 0 / 0 / 6 / 4 | 4 / 9 / 9 / 18 | 6 / 9 / 6 / 12 | 4 / 6 / 6 / 12 | 4 / 3 / 9 / 12 / 6 | 4 / 2 / 6 / 8 / 4 |
| critical-path | 16 | 3 | 0.333 / 0.472 / 0.387 / 0.243 / 0.321 | 0.643 / 0.500 / 0.581 / 0.730 / 0.643 | 0.429 / 0.500 / 0.484 / 0.486 / 0.536 | 0 / 0 / 0 / 0 / 0 | 7 / 4 / 4 / 0 / 0 | 0 / 0 / 0 / 5 / 3 | 6 / 9 / 9 / 18 | 9 / 9 / 6 / 12 | 7 / 6 / 6 / 12 | 6 / 4 / 9 / 13 / 5 | 7 / 3 / 6 / 9 / 3 |
| critical-path | 16 | 4 | 0.378 / - / 0.513 / 0.282 / 0.472 | 0.600 / - / 0.462 / 0.692 / 0.500 | 0.711 / - / 0.641 / 0.744 / 0.667 | 0 / - / 0 / 0 / 0 | 8 / - / 5 / 0 / 0 | 0 / - / 0 / 6 / 3 | 9 / 9 / 9 / 18 | - | 15 / 6 / 6 / 12 | 9 / 3 / 9 / 12 / 6 | 15 / 3 / 6 / 9 / 3 |
| critical-path | 16 | 5 | 0.548 / - / - / 0.509 / - | 0.435 / - / - / 0.474 / - | 0.629 / - / - / 0.649 / - | 0 / - / - / 0 / - | 7 / - / - / 0 / - | 0 / - / - / 4 / - | 26 / 9 / 9 / 18 | - | - | 25 / 5 / 9 / 14 / 4 | - |
| critical-path | 16 | 6 | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| critical-path | 16 | 7 | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| critical-path | 16 | 8 | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| program-order | 16 | 0 | 0.282 / 0.424 / 0.321 / 0.282 / 0.296 | 0.692 / 0.545 / 0.643 / 0.692 / 0.667 | 0.462 / 0.545 / 0.536 / 0.462 / 0.556 | 0 / 0 / 0 / 0 / 0 | 0 / 5 / 8 / 0 / 0 | 0 / 0 / 0 / 0 / 1 | 8 / 4 / 4 / 23 | 10 / 5 / 2 / 16 | 7 / 3 / 3 / 15 | 8 / 4 / 4 / 23 / 0 | 7 / 2 / 3 / 14 / 1 |
| program-order | 16 | 1 | 0.282 / 0.441 / 0.321 / 0.282 / 0.296 | 0.692 / 0.529 / 0.643 / 0.692 / 0.667 | 0.462 / 0.529 / 0.536 / 0.462 / 0.556 | 0 / 0 / 0 / 0 / 0 | 8 / 6 / 8 / 0 / 0 | 0 / 0 / 0 / 2 / 1 | 7 / 5 / 5 / 22 | 11 / 5 / 2 / 16 | 7 / 3 / 3 / 15 | 8 / 4 / 6 / 19 / 2 | 7 / 2 / 3 / 14 / 1 |
| program-order | 16 | 2 | 0.282 / 0.424 / 0.321 / 0.282 / 0.321 | 0.692 / 0.545 / 0.643 / 0.692 / 0.643 | 0.462 / 0.545 / 0.536 / 0.462 / 0.536 | 0 / 0 / 0 / 0 / 0 | 0 / 5 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 8 / 4 / 4 / 23 | 10 / 5 / 2 / 16 | 7 / 3 / 3 / 15 | 8 / 4 / 4 / 23 / 0 | 7 / 3 / 3 / 15 / 0 |
| program-order | 16 | 3 | 0.349 / 0.500 / 0.406 / 0.349 / 0.406 | 0.628 / 0.474 / 0.563 / 0.628 / 0.563 | 0.419 / 0.474 / 0.469 / 0.419 / 0.469 | 0 / 0 / 0 / 0 / 0 | 0 / 1 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 11 / 5 / 5 / 22 | 14 / 6 / 3 / 15 | 10 / 4 / 4 / 14 | 11 / 5 / 5 / 22 / 0 | 10 / 4 / 4 / 14 / 0 |
| program-order | 16 | 4 | 0.282 / 0.548 / 0.406 / 0.263 / 0.406 | 0.692 / 0.429 / 0.563 / 0.711 / 0.563 | 0.615 / 0.619 / 0.688 / 0.632 / 0.688 | 0 / 0 / 0 / 0 / 0 | 4 / 3 / 0 / 0 / 0 | 0 / 0 / 0 / 1 / 0 | 7 / 5 / 5 / 22 | 17 / 7 / 4 / 14 | 10 / 4 / 4 / 14 | 7 / 4 / 5 / 21 / 1 | 10 / 4 / 4 / 14 / 0 |
| program-order | 16 | 5 | 0.282 / 0.578 / 0.441 / 0.282 / 0.441 | 0.692 / 0.400 / 0.529 / 0.692 / 0.529 | 0.641 / 0.644 / 0.647 / 0.641 / 0.647 | 0 / 0 / 0 / 0 / 0 | 6 / 3 / 0 / 0 / 0 | 0 / 0 / 0 / 2 / 0 | 7 / 5 / 5 / 22 | 18 / 9 / 6 / 12 | 11 / 5 / 5 / 13 | 8 / 4 / 6 / 19 / 2 | 11 / 5 / 5 / 13 / 0 |
| program-order | 16 | 6 | 0.349 / 0.612 / 0.472 / 0.349 / 0.472 | 0.628 / 0.367 / 0.500 / 0.628 / 0.500 | 0.628 / 0.612 / 0.694 / 0.628 / 0.694 | 0 / 0 / 0 / 0 / 0 | 0 / 1 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 8 / 8 / 8 / 19 | 24 / 7 / 4 / 14 | 12 / 6 / 6 / 12 | 8 / 8 / 8 / 19 / 0 | 12 / 6 / 6 / 12 / 0 |
| program-order | 16 | 7 | 0.404 / 0.655 / 0.486 / 0.404 / 0.486 | 0.574 / 0.327 / 0.486 / 0.574 / 0.486 | 0.638 / 0.545 / 0.703 / 0.638 / 0.703 | 0 / 0 / 0 / 0 / 0 | 0 / 1 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 12 / 8 / 8 / 19 | 30 / 7 / 4 / 14 | 14 / 5 / 5 / 13 | 12 / 8 / 8 / 19 / 0 | 14 / 5 / 5 / 13 / 0 |
| program-order | 16 | 8 | 0.429 / - / 0.525 / 0.429 / 0.525 | 0.551 / - / 0.450 / 0.551 / 0.450 | 0.653 / - / 0.675 / 0.653 / 0.675 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 15 / 7 / 7 / 20 | - | 16 / 6 / 6 / 12 | 15 / 7 / 7 / 20 / 0 | 16 / 6 / 6 / 12 / 0 |

- `heldByWritePort` が0でない行: mvp-1 0（合計 0）、W-reg 0（合計 0）、W-full 0（合計 0）、mvp1_rp4 0（合計 0）、mvp2_rp4 0（合計 0）
- `heldByReadPorts` が0でない行: mvp-1 9（合計 61）、W-reg 12（合計 41）、W-full 7（合計 37）、mvp1_rp4 0（合計 0）、mvp2_rp4 0（合計 0）。(MAC か VMAC) と (ST か ST.W) の bundle がある行: mvp-1 0（合計 0）、W-reg 0（合計 0）、W-full 0（合計 0）、mvp1_rp4 9（合計 40）、mvp2_rp4 7（合計 20）

### `matmul3`（single）

| 優先度 | レジスタ上限 | load reserve | mvp-1 | W-reg | W-full | mvp1_rp4 | mvp2_rp4 | mvp-1 / W-reg | W-reg / W-full | mvp-1 / W-full | mvp1_rp4 / mvp2_rp4 | W-full / mvp2_rp4 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| critical-path | なし | - | 不成立: 同時に生きる値 19 > 16 | 不成立: 同時に生きる値 19 > 16 | 不成立: 同時に生きる値 19 > 16 | 不成立: 同時に生きる値 19 > 16 | 不成立: 同時に生きる値 19 > 16 | - | - | - | - | - |
| program-order | なし | - | 57 | **46** | **40** | 57 | **40** | 1.24 | 1.15 | 1.43 | 1.43 | 1.00 |
| critical-path | 16 | 0 | **55** | 46 | 40 | **55** | 40 | 1.20 | 1.15 | 1.38 | 1.38 | 1.00 |
| critical-path | 16 | 1 | 55 | 46 | 40 | 55 | 40 | 1.20 | 1.15 | 1.38 | 1.38 | 1.00 |
| critical-path | 16 | 2 | 55 | 46 | 40 | 55 | 40 | 1.20 | 1.15 | 1.38 | 1.38 | 1.00 |
| critical-path | 16 | 3 | 57 | 47 | 41 | 57 | 41 | 1.21 | 1.15 | 1.39 | 1.39 | 1.00 |
| critical-path | 16 | 4 | 59（LD 22） | 不成立: 上限の下で行き詰まる | 49（LD 20） | 59（LD 22） | 49（LD 20） | - | - | 1.20 | 1.20 | 1.00 |
| critical-path | 16 | 5 | 76（LD 31） | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 76（LD 31） | 不成立: 上限の下で行き詰まる | - | - | - | - | - |
| critical-path | 16 | 6 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - | - |
| critical-path | 16 | 7 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - | - |
| critical-path | 16 | 8 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - | - |
| program-order | 16 | 0 | 57 | 46 | 40 | 57 | 40 | 1.24 | 1.15 | 1.43 | 1.43 | 1.00 |
| program-order | 16 | 1 | 57 | 46 | 40 | 57 | 40 | 1.24 | 1.15 | 1.43 | 1.43 | 1.00 |
| program-order | 16 | 2 | 57 | 46 | 40 | 57 | 40 | 1.24 | 1.15 | 1.43 | 1.43 | 1.00 |
| program-order | 16 | 3 | 57 | 46 | 40 | 57 | 40 | 1.24 | 1.15 | 1.43 | 1.43 | 1.00 |
| program-order | 16 | 4 | 59（LD 20） | 46 | 40 | 59（LD 20） | 40 | 1.28 | 1.15 | 1.48 | 1.48 | 1.00 |
| program-order | 16 | 5 | 59（LD 22） | 56（LD 26） | 45（LD 19） | 59（LD 22） | 45（LD 19） | 1.05 | 1.24 | 1.31 | 1.31 | 1.00 |
| program-order | 16 | 6 | 61（LD 23） | 61（LD 30） | 50（LD 23） | 61（LD 23） | 50（LD 23） | 1.00 | 1.22 | 1.22 | 1.22 | 1.00 |
| program-order | 16 | 7 | 64（LD 25） | 69（LD 31） | 53（LD 24） | 64（LD 25） | 53（LD 24） | 0.93 | 1.30 | 1.21 | 1.21 | 1.00 |
| program-order | 16 | 8 | 67（LD 29） | 不成立: 上限の下で行き詰まる | 54（LD 27） | 67（LD 29） | 54（LD 27） | - | - | 1.24 | 1.24 | 1.00 |

- mvp-1 / W-reg: 両方で成立する行 13（W-reg の cycles が少ない 11、同じ 1、多い 1。比 0.93〜1.28）、mvp-1 だけで成立 3、W-reg だけで成立 0、どちらも不成立 4
- W-reg / W-full: 両方で成立する行 13（W-full の cycles が少ない 13、同じ 0、多い 0。比 1.15〜1.30）、W-reg だけで成立 0、W-full だけで成立 2、どちらも不成立 5
- mvp-1 / W-full: 両方で成立する行 15（W-full の cycles が少ない 15、同じ 0、多い 0。比 1.20〜1.48）、mvp-1 だけで成立 1、W-full だけで成立 0、どちらも不成立 4
- mvp1_rp4 / mvp2_rp4: 両方で成立する行 15（mvp2_rp4 の cycles が少ない 15、同じ 0、多い 0。比 1.20〜1.48）、mvp1_rp4 だけで成立 1、mvp2_rp4 だけで成立 0、どちらも不成立 4
- W-full / mvp2_rp4: 両方で成立する行 15（mvp2_rp4 の cycles が少ない 0、同じ 15、多い 0。比 1.00〜1.00）、W-full だけで成立 0、mvp2_rp4 だけで成立 0、どちらも不成立 5
- 不成立の行は mvp-1 4（TooManyLive 1、Stuck 3）、W-reg 7（TooManyLive 1、Stuck 6）、W-full 5（TooManyLive 1、Stuck 4）、mvp1_rp4 4（TooManyLive 1、Stuck 3）、mvp2_rp4 5（TooManyLive 1、Stuck 4）。LD を複製した行は mvp-1 7、W-reg 3、W-full 5、mvp1_rp4 7、mvp2_rp4 5

同じ行のスロットとポート（構成の順は mvp-1 / W-reg / W-full / mvp1_rp4 / mvp2_rp4）:

| 優先度 | レジスタ上限 | load reserve | Compute NOP率 | 書戻し Compute 利用率 | 書戻し Memory 利用率 | `heldByWritePort` | `heldByReadPorts` | (MAC か VMAC) と (ST か ST.W) の bundle | 読出しの欄の数ごとの bundle（mvp-1） | 読出しの欄の数ごとの bundle（W-reg） | 読出しの欄の数ごとの bundle（W-full） | 読出しの欄の数ごとの bundle（mvp1_rp4） | 読出しの欄の数ごとの bundle（mvp2_rp4） |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| critical-path | なし | - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| program-order | なし | - | 0.509 / 0.587 / 0.525 / 0.509 / 0.525 | 0.474 / 0.391 / 0.450 / 0.474 / 0.450 | 0.316 / 0.391 / 0.375 / 0.316 / 0.375 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 21 / 9 / 9 / 18 | 19 / 9 / 6 / 12 | 16 / 6 / 6 / 12 | 21 / 9 / 9 / 18 / 0 | 16 / 6 / 6 / 12 / 0 |
| critical-path | 16 | 0 | 0.491 / 0.587 / 0.525 / 0.491 / 0.525 | 0.491 / 0.391 / 0.450 / 0.491 / 0.450 | 0.327 / 0.391 / 0.375 / 0.327 / 0.375 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 19 / 9 / 9 / 18 | 19 / 9 / 6 / 12 | 16 / 6 / 6 / 12 | 19 / 9 / 9 / 18 / 0 | 16 / 6 / 6 / 12 / 0 |
| critical-path | 16 | 1 | 0.491 / 0.587 / 0.525 / 0.491 / 0.525 | 0.491 / 0.391 / 0.450 / 0.491 / 0.450 | 0.327 / 0.391 / 0.375 / 0.327 / 0.375 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 19 / 9 / 9 / 18 | 19 / 9 / 6 / 12 | 16 / 6 / 6 / 12 | 19 / 9 / 9 / 18 / 0 | 16 / 6 / 6 / 12 / 0 |
| critical-path | 16 | 2 | 0.491 / 0.587 / 0.525 / 0.491 / 0.525 | 0.491 / 0.391 / 0.450 / 0.491 / 0.450 | 0.327 / 0.391 / 0.375 / 0.327 / 0.375 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 19 / 9 / 9 / 18 | 19 / 9 / 6 / 12 | 16 / 6 / 6 / 12 | 19 / 9 / 9 / 18 / 0 | 16 / 6 / 6 / 12 / 0 |
| critical-path | 16 | 3 | 0.509 / 0.596 / 0.537 / 0.509 / 0.537 | 0.474 / 0.383 / 0.439 / 0.474 / 0.439 | 0.316 / 0.383 / 0.366 / 0.316 / 0.366 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 21 / 9 / 9 / 18 | 20 / 9 / 6 / 12 | 17 / 6 / 6 / 12 | 21 / 9 / 9 / 18 / 0 | 17 / 6 / 6 / 12 / 0 |
| critical-path | 16 | 4 | 0.525 / - / 0.612 / 0.525 / 0.612 | 0.458 / - / 0.367 / 0.458 / 0.367 | 0.373 / - / 0.408 / 0.373 / 0.408 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 23 / 9 / 9 / 18 | - | 25 / 6 / 6 / 12 | 23 / 9 / 9 / 18 / 0 | 25 / 6 / 6 / 12 / 0 |
| critical-path | 16 | 5 | 0.632 / - / - / 0.632 / - | 0.355 / - / - / 0.355 / - | 0.408 / - / - / 0.408 / - | 0 / - / - / 0 / - | 0 / - / - / 0 / - | 0 / - / - / 0 / - | 40 / 9 / 9 / 18 | - | - | 40 / 9 / 9 / 18 / 0 | - |
| critical-path | 16 | 6 | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| critical-path | 16 | 7 | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| critical-path | 16 | 8 | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| program-order | 16 | 0 | 0.509 / 0.587 / 0.525 / 0.509 / 0.525 | 0.474 / 0.391 / 0.450 / 0.474 / 0.450 | 0.316 / 0.391 / 0.375 / 0.316 / 0.375 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 21 / 9 / 9 / 18 | 19 / 9 / 6 / 12 | 16 / 6 / 6 / 12 | 21 / 9 / 9 / 18 / 0 | 16 / 6 / 6 / 12 / 0 |
| program-order | 16 | 1 | 0.509 / 0.587 / 0.525 / 0.509 / 0.525 | 0.474 / 0.391 / 0.450 / 0.474 / 0.450 | 0.316 / 0.391 / 0.375 / 0.316 / 0.375 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 21 / 9 / 9 / 18 | 19 / 9 / 6 / 12 | 16 / 6 / 6 / 12 | 21 / 9 / 9 / 18 / 0 | 16 / 6 / 6 / 12 / 0 |
| program-order | 16 | 2 | 0.509 / 0.587 / 0.525 / 0.509 / 0.525 | 0.474 / 0.391 / 0.450 / 0.474 / 0.450 | 0.316 / 0.391 / 0.375 / 0.316 / 0.375 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 21 / 9 / 9 / 18 | 19 / 9 / 6 / 12 | 16 / 6 / 6 / 12 | 21 / 9 / 9 / 18 / 0 | 16 / 6 / 6 / 12 / 0 |
| program-order | 16 | 3 | 0.509 / 0.587 / 0.525 / 0.509 / 0.525 | 0.474 / 0.391 / 0.450 / 0.474 / 0.450 | 0.316 / 0.391 / 0.375 / 0.316 / 0.375 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 21 / 9 / 9 / 18 | 19 / 9 / 6 / 12 | 16 / 6 / 6 / 12 | 21 / 9 / 9 / 18 / 0 | 16 / 6 / 6 / 12 / 0 |
| program-order | 16 | 4 | 0.525 / 0.587 / 0.525 / 0.525 / 0.525 | 0.458 / 0.391 / 0.450 / 0.458 / 0.450 | 0.339 / 0.391 / 0.375 / 0.339 / 0.375 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 23 / 9 / 9 / 18 | 19 / 9 / 6 / 12 | 16 / 6 / 6 / 12 | 23 / 9 / 9 / 18 / 0 | 16 / 6 / 6 / 12 / 0 |
| program-order | 16 | 5 | 0.525 / 0.661 / 0.578 / 0.525 / 0.578 | 0.458 / 0.321 / 0.400 / 0.458 / 0.400 | 0.373 / 0.464 / 0.422 / 0.373 / 0.422 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 23 / 9 / 9 / 18 | 29 / 9 / 6 / 12 | 21 / 6 / 6 / 12 | 23 / 9 / 9 / 18 / 0 | 21 / 6 / 6 / 12 / 0 |
| program-order | 16 | 6 | 0.541 / 0.689 / 0.620 / 0.541 / 0.620 | 0.443 / 0.295 / 0.360 / 0.443 / 0.360 | 0.377 / 0.492 / 0.460 / 0.377 / 0.460 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 25 / 9 / 9 / 18 | 34 / 9 / 6 / 12 | 26 / 6 / 6 / 12 | 25 / 9 / 9 / 18 / 0 | 26 / 6 / 6 / 12 / 0 |
| program-order | 16 | 7 | 0.563 / 0.725 / 0.642 / 0.563 / 0.642 | 0.422 / 0.261 / 0.340 / 0.422 / 0.340 | 0.391 / 0.449 / 0.453 / 0.391 / 0.453 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 | 28 / 9 / 9 / 18 | 42 / 9 / 6 / 12 | 29 / 6 / 6 / 12 | 28 / 9 / 9 / 18 / 0 | 29 / 6 / 6 / 12 / 0 |
| program-order | 16 | 8 | 0.582 / - / 0.648 / 0.582 / 0.648 | 0.403 / - / 0.333 / 0.403 / 0.333 | 0.433 / - / 0.500 / 0.433 / 0.500 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 31 / 9 / 9 / 18 | - | 30 / 6 / 6 / 12 | 31 / 9 / 9 / 18 / 0 | 30 / 6 / 6 / 12 / 0 |

- `heldByWritePort` が0でない行: mvp-1 0（合計 0）、W-reg 0（合計 0）、W-full 0（合計 0）、mvp1_rp4 0（合計 0）、mvp2_rp4 0（合計 0）
- `heldByReadPorts` が0でない行: mvp-1 0（合計 0）、W-reg 0（合計 0）、W-full 0（合計 0）、mvp1_rp4 0（合計 0）、mvp2_rp4 0（合計 0）。(MAC か VMAC) と (ST か ST.W) の bundle がある行: mvp-1 0（合計 0）、W-reg 0（合計 0）、W-full 0（合計 0）、mvp1_rp4 0（合計 0）、mvp2_rp4 0（合計 0）

### `matmul4`（vliw）

| 優先度 | レジスタ上限 | load reserve | mvp-1 | W-reg | W-full | mvp1_rp4 | mvp2_rp4 | mvp-1 / W-reg | W-reg / W-full | mvp-1 / W-full | mvp1_rp4 / mvp2_rp4 | W-full / mvp2_rp4 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| critical-path | なし | - | 不成立: 同時に生きる値 36 > 16 | 不成立: 同時に生きる値 25 > 16 | 不成立: 同時に生きる値 30 > 16 | 不成立: 同時に生きる値 36 > 16 | 不成立: 同時に生きる値 30 > 16 | - | - | - | - | - |
| program-order | なし | - | 不成立: 同時に生きる値 29 > 16 | 不成立: 同時に生きる値 30 > 16 | 不成立: 同時に生きる値 28 > 16 | 不成立: 同時に生きる値 29 > 16 | 不成立: 同時に生きる値 28 > 16 | - | - | - | - | - |
| critical-path | 16 | 0 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - | - |
| critical-path | 16 | 1 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - | - |
| critical-path | 16 | 2 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - | - |
| critical-path | 16 | 3 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - | - |
| critical-path | 16 | 4 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - | - |
| critical-path | 16 | 5 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - | - |
| critical-path | 16 | 6 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - | - |
| critical-path | 16 | 7 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - | - |
| critical-path | 16 | 8 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - | - |
| program-order | 16 | 0 | **77**（LD 55） | 不成立: 上限の下で行き詰まる | 不成立: cycle 22 で整列した組が空かない（生きているレジスタ 16 ≤ 16） | **76**（LD 53） | 不成立: cycle 22 で整列した組が空かない（生きているレジスタ 16 ≤ 16） | - | - | - | - | - |
| program-order | 16 | 1 | 83（LD 59） | 不成立: 上限の下で行き詰まる | **58**（LD 43） | 83（LD 58） | 不成立: cycle 48 で整列した組が空かない（生きているレジスタ 15 ≤ 16） | - | - | 1.43 | - | - |
| program-order | 16 | 2 | 85（LD 60） | 不成立: 上限の下で行き詰まる | 60（LD 46） | 84（LD 60） | **60**（LD 46） | - | - | 1.42 | 1.40 | 1.00 |
| program-order | 16 | 3 | 88（LD 64） | 不成立: 上限の下で行き詰まる | 63（LD 47） | 88（LD 64） | 63（LD 47） | - | - | 1.40 | 1.40 | 1.00 |
| program-order | 16 | 4 | 91（LD 68） | 不成立: 上限の下で行き詰まる | 63（LD 50） | 91（LD 68） | 63（LD 50） | - | - | 1.44 | 1.44 | 1.00 |
| program-order | 16 | 5 | 93（LD 71） | 不成立: 上限の下で行き詰まる | 66（LD 52） | 93（LD 71） | 67（LD 53） | - | - | 1.41 | 1.39 | 0.99 |
| program-order | 16 | 6 | 96（LD 75） | 不成立: 上限の下で行き詰まる | 71（LD 58） | 96（LD 75） | 71（LD 58） | - | - | 1.35 | 1.35 | 1.00 |
| program-order | 16 | 7 | 102（LD 80） | 不成立: 上限の下で行き詰まる | 77（LD 60） | 102（LD 80） | 77（LD 60） | - | - | 1.32 | 1.32 | 1.00 |
| program-order | 16 | 8 | 107（LD 84） | 不成立: 上限の下で行き詰まる | 79（LD 64） | 107（LD 84） | 79（LD 64） | - | - | 1.35 | 1.35 | 1.00 |

- mvp-1 / W-reg: 両方で成立する行 0（W-reg の cycles が少ない 0、同じ 0、多い 0）、mvp-1 だけで成立 9、W-reg だけで成立 0、どちらも不成立 11
- W-reg / W-full: 両方で成立する行 0（W-full の cycles が少ない 0、同じ 0、多い 0）、W-reg だけで成立 0、W-full だけで成立 8、どちらも不成立 12
- mvp-1 / W-full: 両方で成立する行 8（W-full の cycles が少ない 8、同じ 0、多い 0。比 1.32〜1.44）、mvp-1 だけで成立 1、W-full だけで成立 0、どちらも不成立 11
- mvp1_rp4 / mvp2_rp4: 両方で成立する行 7（mvp2_rp4 の cycles が少ない 7、同じ 0、多い 0。比 1.32〜1.44）、mvp1_rp4 だけで成立 2、mvp2_rp4 だけで成立 0、どちらも不成立 11
- W-full / mvp2_rp4: 両方で成立する行 7（mvp2_rp4 の cycles が少ない 0、同じ 6、多い 1。比 0.99〜1.00）、W-full だけで成立 1、mvp2_rp4 だけで成立 0、どちらも不成立 12
- 不成立の行は mvp-1 11（TooManyLive 2、Stuck 9）、W-reg 20（TooManyLive 2、Stuck 18）、W-full 12（TooManyLive 2、NoAlignedPair 1、Stuck 9）、mvp1_rp4 11（TooManyLive 2、Stuck 9）、mvp2_rp4 13（TooManyLive 2、NoAlignedPair 2、Stuck 9）。LD を複製した行は mvp-1 9、W-reg 0、W-full 8、mvp1_rp4 9、mvp2_rp4 7

同じ行のスロットとポート（構成の順は mvp-1 / W-reg / W-full / mvp1_rp4 / mvp2_rp4）:

| 優先度 | レジスタ上限 | load reserve | Compute NOP率 | 書戻し Compute 利用率 | 書戻し Memory 利用率 | `heldByWritePort` | `heldByReadPorts` | (MAC か VMAC) と (ST か ST.W) の bundle | 読出しの欄の数ごとの bundle（mvp-1） | 読出しの欄の数ごとの bundle（W-reg） | 読出しの欄の数ごとの bundle（W-full） | 読出しの欄の数ごとの bundle（mvp1_rp4） | 読出しの欄の数ごとの bundle（mvp2_rp4） |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| critical-path | なし | - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| program-order | なし | - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| critical-path | 16 | 0 | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| critical-path | 16 | 1 | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| critical-path | 16 | 2 | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| critical-path | 16 | 3 | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| critical-path | 16 | 4 | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| critical-path | 16 | 5 | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| critical-path | 16 | 6 | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| critical-path | 16 | 7 | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| critical-path | 16 | 8 | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| program-order | 16 | 0 | 0.156 / - / - / 0.145 / - | 0.831 / - / - / 0.842 / - | 0.714 / - / - / 0.697 / - | 0 / - / - / 0 / - | 17 / - / - / 0 / - | 0 / - / - / 1 / - | 8 / 5 / 5 / 59 | - | - | 8 / 4 / 5 / 58 / 1 | - |
| program-order | 16 | 1 | 0.217 / - / 0.431 / 0.217 / - | 0.771 / - / 0.552 / 0.771 / - | 0.711 / - / 0.741 / 0.699 / - | 0 / - / 0 / 0 / - | 4 / - / 1 / 0 / - | 0 / - / 0 / 1 / - | 11 / 8 / 8 / 56 | - | 19 / 7 / 7 / 25 | 12 / 7 / 8 / 55 / 1 | - |
| program-order | 16 | 2 | 0.235 / - / 0.450 / 0.226 / 0.450 | 0.753 / - / 0.533 / 0.762 / 0.533 | 0.706 / - / 0.767 / 0.714 / 0.767 | 0 / - / 0 / 0 / 0 | 5 / - / 0 / 0 / 0 | 0 / - / 0 / 1 / 0 | 11 / 10 / 10 / 54 | - | 23 / 5 / 5 / 27 | 11 / 9 / 10 / 53 / 1 | 23 / 5 / 5 / 27 / 0 |
| program-order | 16 | 3 | 0.261 / - / 0.476 / 0.261 / 0.476 | 0.727 / - / 0.508 / 0.727 / 0.508 | 0.727 / - / 0.746 / 0.727 / 0.746 | 0 / - / 0 / 0 / 0 | 6 / - / 2 / 0 / 0 | 0 / - / 0 / 1 / 2 | 13 / 11 / 11 / 53 | - | 26 / 5 / 5 / 27 | 14 / 10 / 11 / 52 / 1 | 28 / 3 / 5 / 25 / 2 |
| program-order | 16 | 4 | 0.286 / - / 0.476 / 0.286 / 0.476 | 0.703 / - / 0.508 / 0.703 / 0.508 | 0.747 / - / 0.794 / 0.747 / 0.794 | 0 / - / 0 / 0 / 0 | 6 / - / 0 / 0 / 0 | 0 / - / 0 / 1 / 0 | 16 / 11 / 11 / 53 | - | 23 / 8 / 8 / 24 | 17 / 10 / 11 / 52 / 1 | 23 / 8 / 8 / 24 / 0 |
| program-order | 16 | 5 | 0.301 / - / 0.500 / 0.301 / 0.507 | 0.688 / - / 0.485 / 0.688 / 0.478 | 0.763 / - / 0.788 / 0.763 / 0.791 | 0 / - / 0 / 0 / 0 | 7 / - / 1 / 0 / 0 | 0 / - / 0 / 2 / 1 | 17 / 12 / 12 / 52 | - | 29 / 5 / 5 / 27 | 19 / 10 / 12 / 50 / 2 | 31 / 4 / 5 / 26 / 1 |
| program-order | 16 | 6 | 0.323 / - / 0.535 / 0.323 / 0.535 | 0.667 / - / 0.451 / 0.667 / 0.451 | 0.781 / - / 0.817 / 0.781 / 0.817 | 0 / - / 0 / 0 / 0 | 6 / - / 0 / 0 / 0 | 0 / - / 0 / 1 / 0 | 20 / 12 / 12 / 52 | - | 31 / 8 / 8 / 24 | 21 / 11 / 12 / 51 / 1 | 31 / 8 / 8 / 24 / 0 |
| program-order | 16 | 7 | 0.363 / - / 0.571 / 0.363 / 0.571 | 0.627 / - / 0.416 / 0.627 / 0.416 | 0.784 / - / 0.779 / 0.784 / 0.779 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 22 / 16 / 16 / 48 | - | 37 / 8 / 8 / 24 | 22 / 16 / 16 / 48 / 0 | 37 / 8 / 8 / 24 / 0 |
| program-order | 16 | 8 | 0.393 / - / 0.582 / 0.393 / 0.582 | 0.598 / - / 0.405 / 0.598 / 0.405 | 0.785 / - / 0.810 / 0.785 / 0.810 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 27 / 16 / 16 / 48 | - | 43 / 4 / 4 / 28 | 27 / 16 / 16 / 48 / 0 | 43 / 4 / 4 / 28 / 0 |

- `heldByWritePort` が0でない行: mvp-1 0（合計 0）、W-reg 0（合計 0）、W-full 0（合計 0）、mvp1_rp4 0（合計 0）、mvp2_rp4 0（合計 0）
- `heldByReadPorts` が0でない行: mvp-1 7（合計 51）、W-reg 0（合計 0）、W-full 3（合計 4）、mvp1_rp4 0（合計 0）、mvp2_rp4 0（合計 0）。(MAC か VMAC) と (ST か ST.W) の bundle がある行: mvp-1 0（合計 0）、W-reg 0（合計 0）、W-full 0（合計 0）、mvp1_rp4 7（合計 8）、mvp2_rp4 2（合計 3）

### `matmul4`（single）

| 優先度 | レジスタ上限 | load reserve | mvp-1 | W-reg | W-full | mvp1_rp4 | mvp2_rp4 | mvp-1 / W-reg | W-reg / W-full | mvp-1 / W-full | mvp1_rp4 / mvp2_rp4 | W-full / mvp2_rp4 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| critical-path | なし | - | 不成立: 同時に生きる値 29 > 16 | 不成立: 同時に生きる値 29 > 16 | 不成立: 同時に生きる値 29 > 16 | 不成立: 同時に生きる値 29 > 16 | 不成立: 同時に生きる値 29 > 16 | - | - | - | - | - |
| program-order | なし | - | 不成立: 同時に生きる値 22 > 16 | 不成立: 同時に生きる値 23 > 16 | 不成立: 同時に生きる値 23 > 16 | 不成立: 同時に生きる値 22 > 16 | 不成立: 同時に生きる値 23 > 16 | - | - | - | - | - |
| critical-path | 16 | 0 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - | - |
| critical-path | 16 | 1 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - | - |
| critical-path | 16 | 2 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - | - |
| critical-path | 16 | 3 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - | - |
| critical-path | 16 | 4 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - | - |
| critical-path | 16 | 5 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - | - |
| critical-path | 16 | 6 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - | - |
| critical-path | 16 | 7 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - | - |
| critical-path | 16 | 8 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - | - |
| program-order | 16 | 0 | **128**（LD 45） | 不成立: 上限の下で行き詰まる | 不成立: cycle 52 で整列した組が空かない（生きているレジスタ 16 ≤ 16） | **128**（LD 45） | 不成立: cycle 52 で整列した組が空かない（生きているレジスタ 16 ≤ 16） | - | - | - | - | - |
| program-order | 16 | 1 | 131（LD 48） | 不成立: 上限の下で行き詰まる | 不成立: cycle 25 で整列した組が空かない（生きているレジスタ 16 ≤ 16） | 131（LD 48） | 不成立: cycle 25 で整列した組が空かない（生きているレジスタ 16 ≤ 16） | - | - | - | - | - |
| program-order | 16 | 2 | 134（LD 51） | 不成立: 上限の下で行き詰まる | **82**（LD 39） | 134（LD 51） | **82**（LD 39） | - | - | 1.63 | 1.63 | 1.00 |
| program-order | 16 | 3 | 137（LD 54） | 不成立: 上限の下で行き詰まる | 86（LD 42） | 137（LD 54） | 86（LD 42） | - | - | 1.59 | 1.59 | 1.00 |
| program-order | 16 | 4 | 140（LD 57） | 不成立: 上限の下で行き詰まる | 87（LD 44） | 140（LD 57） | 87（LD 44） | - | - | 1.61 | 1.61 | 1.00 |
| program-order | 16 | 5 | 143（LD 60） | 不成立: 上限の下で行き詰まる | 91（LD 48） | 143（LD 60） | 91（LD 48） | - | - | 1.57 | 1.57 | 1.00 |
| program-order | 16 | 6 | 147（LD 64） | 不成立: 上限の下で行き詰まる | 94（LD 51） | 147（LD 64） | 94（LD 51） | - | - | 1.56 | 1.56 | 1.00 |
| program-order | 16 | 7 | 153（LD 70） | 不成立: 上限の下で行き詰まる | 101（LD 56） | 153（LD 70） | 101（LD 56） | - | - | 1.51 | 1.51 | 1.00 |
| program-order | 16 | 8 | 159（LD 76） | 不成立: 上限の下で行き詰まる | 104（LD 61） | 159（LD 76） | 104（LD 61） | - | - | 1.53 | 1.53 | 1.00 |

- mvp-1 / W-reg: 両方で成立する行 0（W-reg の cycles が少ない 0、同じ 0、多い 0）、mvp-1 だけで成立 9、W-reg だけで成立 0、どちらも不成立 11
- W-reg / W-full: 両方で成立する行 0（W-full の cycles が少ない 0、同じ 0、多い 0）、W-reg だけで成立 0、W-full だけで成立 7、どちらも不成立 13
- mvp-1 / W-full: 両方で成立する行 7（W-full の cycles が少ない 7、同じ 0、多い 0。比 1.51〜1.63）、mvp-1 だけで成立 2、W-full だけで成立 0、どちらも不成立 11
- mvp1_rp4 / mvp2_rp4: 両方で成立する行 7（mvp2_rp4 の cycles が少ない 7、同じ 0、多い 0。比 1.51〜1.63）、mvp1_rp4 だけで成立 2、mvp2_rp4 だけで成立 0、どちらも不成立 11
- W-full / mvp2_rp4: 両方で成立する行 7（mvp2_rp4 の cycles が少ない 0、同じ 7、多い 0。比 1.00〜1.00）、W-full だけで成立 0、mvp2_rp4 だけで成立 0、どちらも不成立 13
- 不成立の行は mvp-1 11（TooManyLive 2、Stuck 9）、W-reg 20（TooManyLive 2、Stuck 18）、W-full 13（TooManyLive 2、NoAlignedPair 2、Stuck 9）、mvp1_rp4 11（TooManyLive 2、Stuck 9）、mvp2_rp4 13（TooManyLive 2、NoAlignedPair 2、Stuck 9）。LD を複製した行は mvp-1 9、W-reg 0、W-full 7、mvp1_rp4 9、mvp2_rp4 7

同じ行のスロットとポート（構成の順は mvp-1 / W-reg / W-full / mvp1_rp4 / mvp2_rp4）:

| 優先度 | レジスタ上限 | load reserve | Compute NOP率 | 書戻し Compute 利用率 | 書戻し Memory 利用率 | `heldByWritePort` | `heldByReadPorts` | (MAC か VMAC) と (ST か ST.W) の bundle | 読出しの欄の数ごとの bundle（mvp-1） | 読出しの欄の数ごとの bundle（W-reg） | 読出しの欄の数ごとの bundle（W-full） | 読出しの欄の数ごとの bundle（mvp1_rp4） | 読出しの欄の数ごとの bundle（mvp2_rp4） |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| critical-path | なし | - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| program-order | なし | - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| critical-path | 16 | 0 | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| critical-path | 16 | 1 | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| critical-path | 16 | 2 | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| critical-path | 16 | 3 | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| critical-path | 16 | 4 | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| critical-path | 16 | 5 | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| critical-path | 16 | 6 | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| critical-path | 16 | 7 | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| critical-path | 16 | 8 | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - / - / - / - / - | - | - | - | - | - |
| program-order | 16 | 0 | 0.492 / - / - / 0.492 / - | 0.500 / - / - / 0.500 / - | 0.352 / - / - / 0.352 / - | 0 / - / - / 0 / - | 0 / - / - / 0 / - | 0 / - / - / 0 / - | 48 / 16 / 16 / 48 | - | - | 48 / 16 / 16 / 48 / 0 | - |
| program-order | 16 | 1 | 0.504 / - / - / 0.504 / - | 0.489 / - / - / 0.489 / - | 0.366 / - / - / 0.366 / - | 0 / - / - / 0 / - | 0 / - / - / 0 / - | 0 / - / - / 0 / - | 51 / 16 / 16 / 48 | - | - | 51 / 16 / 16 / 48 / 0 | - |
| program-order | 16 | 2 | 0.515 / - / 0.598 / 0.515 / 0.598 | 0.478 / - / 0.390 / 0.478 / 0.390 | 0.381 / - / 0.476 / 0.381 / 0.476 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 54 / 16 / 16 / 48 | - | 42 / 8 / 8 / 24 | 54 / 16 / 16 / 48 / 0 | 42 / 8 / 8 / 24 / 0 |
| program-order | 16 | 3 | 0.526 / - / 0.616 / 0.526 / 0.616 | 0.467 / - / 0.372 / 0.467 / 0.372 | 0.394 / - / 0.488 / 0.394 / 0.488 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 57 / 16 / 16 / 48 | - | 46 / 8 / 8 / 24 | 57 / 16 / 16 / 48 / 0 | 46 / 8 / 8 / 24 / 0 |
| program-order | 16 | 4 | 0.536 / - / 0.621 / 0.536 / 0.621 | 0.457 / - / 0.368 / 0.457 / 0.368 | 0.407 / - / 0.506 / 0.407 / 0.506 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 60 / 16 / 16 / 48 | - | 47 / 8 / 8 / 24 | 60 / 16 / 16 / 48 / 0 | 47 / 8 / 8 / 24 / 0 |
| program-order | 16 | 5 | 0.545 / - / 0.637 / 0.545 / 0.637 | 0.448 / - / 0.352 / 0.448 / 0.352 | 0.420 / - / 0.527 / 0.420 / 0.527 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 63 / 16 / 16 / 48 | - | 51 / 8 / 8 / 24 | 63 / 16 / 16 / 48 / 0 | 51 / 8 / 8 / 24 / 0 |
| program-order | 16 | 6 | 0.558 / - / 0.649 / 0.558 / 0.649 | 0.435 / - / 0.340 / 0.435 / 0.340 | 0.435 / - / 0.543 / 0.435 / 0.543 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 67 / 16 / 16 / 48 | - | 54 / 8 / 8 / 24 | 67 / 16 / 16 / 48 / 0 | 54 / 8 / 8 / 24 / 0 |
| program-order | 16 | 7 | 0.575 / - / 0.673 / 0.575 / 0.673 | 0.418 / - / 0.317 / 0.418 / 0.317 | 0.458 / - / 0.554 / 0.458 / 0.554 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 73 / 16 / 16 / 48 | - | 61 / 8 / 8 / 24 | 73 / 16 / 16 / 48 / 0 | 61 / 8 / 8 / 24 / 0 |
| program-order | 16 | 8 | 0.591 / - / 0.683 / 0.591 / 0.683 | 0.403 / - / 0.308 / 0.403 / 0.308 | 0.478 / - / 0.587 / 0.478 / 0.587 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 0 / - / 0 / 0 / 0 | 79 / 16 / 16 / 48 | - | 64 / 8 / 8 / 24 | 79 / 16 / 16 / 48 / 0 | 64 / 8 / 8 / 24 / 0 |

- `heldByWritePort` が0でない行: mvp-1 0（合計 0）、W-reg 0（合計 0）、W-full 0（合計 0）、mvp1_rp4 0（合計 0）、mvp2_rp4 0（合計 0）
- `heldByReadPorts` が0でない行: mvp-1 0（合計 0）、W-reg 0（合計 0）、W-full 0（合計 0）、mvp1_rp4 0（合計 0）、mvp2_rp4 0（合計 0）。(MAC か VMAC) と (ST か ST.W) の bundle がある行: mvp-1 0（合計 0）、W-reg 0（合計 0）、W-full 0（合計 0）、mvp1_rp4 0（合計 0）、mvp2_rp4 0（合計 0）

## 読出しポートの制約（W-full と `mvp2_rp4`）

W-full と `mvp2_rp4` は `read_ports` だけが違い、同じカーネル・入力・配置・詰めた DAG で比べる（詰め方は読出しポートによらない）。戦略を固定した系列は同じ試行どうしの比較だが、差は L11 の制約（(MAC か VMAC) と (ST か ST.W) を同じ bundle に置けない）の損失だけではなく、それによるスケジュールと割り当ての違い（`NoAlignedPair` を含む）も含み、`mvp2_rp4` の方が遅いこともある。`mvp2_rp4` の命令列を W-full の machine で verifier にかけると、(MAC か VMAC) と (ST か ST.W) の bundle ごとにちょうど1つ L11 の違反になり、ほかの違反は無い（`BenchmarkSpec` が全ての `mvp2_rp4` の命令列で確かめる）。mvp-1 と `mvp1_rp4` の比較は [MAC.md](MAC.md) にある。

### 方式ごとに cycles 最小の系列

| カーネル | 方式 | W-full | `mvp2_rp4` | 差（W-full − `mvp2_rp4`） | `heldByReadPorts`（W-full） | (MAC か VMAC) と (ST か ST.W) の bundle（`mvp2_rp4`） | W-full で検査した違反（L11 / ほか） | 試行（W-full / `mvp2_rp4`） |
|---|---|---|---|---|---|---|---|---|
| `saxpy8` | vliw | 15 | 14 | 1 | 3 | 1 | 1 / 0 | critical-path, no register limit / critical-path, no register limit |
| `saxpy8` | single | 18 | 18 | 0 | 0 | 0 | 0 / 0 | critical-path, 16 registers, load reserve 0, single issue / critical-path, 16 registers, load reserve 0, single issue |
| `fir4x8` | vliw | 25（LD 18） | 23（LD 18） | 2 | 2 | 2 | 2 / 0 | critical-path, 16 registers, load reserve 0 / critical-path, 16 registers, load reserve 0 |
| `fir4x8` | single | 37 | 37 | 0 | 0 | 0 | 0 / 0 | program-order, no register limit, single issue / program-order, no register limit, single issue |
| `matmul3` | vliw | 28 | 24 | 4 | 0 | 4 | 4 / 0 | program-order, no register limit / critical-path, 16 registers, load reserve 0 |
| `matmul3` | single | 40 | 40 | 0 | 0 | 0 | 0 / 0 | program-order, no register limit, single issue / program-order, no register limit, single issue |
| `matmul4` | vliw | 58（LD 43） | 60（LD 46） | -2 | 1 | 0 | 0 / 0 | program-order, 16 registers, load reserve 1 / program-order, 16 registers, load reserve 2 |
| `matmul4` | single | 82（LD 39） | 82（LD 39） | 0 | 0 | 0 | 0 / 0 | program-order, 16 registers, load reserve 2, single issue / program-order, 16 registers, load reserve 2, single issue |

方式ごとに cycles 最小で `mvp2_rp4` の方が遅いのは `matmul4`（vliw。58 → 60。`mvp2_rp4` の命令列の (MAC か VMAC) と (ST か ST.W) の bundle 0）である。読出しポートの制約の無い方が遅いので、読出しポートの効果ではなく、選ばれた試行とスケジュールと割り当ての違いの効果である（同じ試行どうしは下の戦略を固定した系列にある）。

### 戦略を固定した系列

#### `saxpy8`（vliw）

| 優先度 | レジスタ上限 | load reserve | W-full | `mvp2_rp4` | 差（W-full − `mvp2_rp4`） | `heldByReadPorts`（W-full） | (MAC か VMAC) と (ST か ST.W) の bundle（`mvp2_rp4`） | L11 の違反（`mvp2_rp4` の命令列を W-full で検査） |
|---|---|---|---|---|---|---|---|---|
| critical-path | なし | - | 15 | 14 | 1 | 3 | 1 | 1 |
| program-order | なし | - | 17 | 16 | 1 | 2 | 1 | 1 |
| critical-path | 16 | 0 | 15 | 14 | 1 | 3 | 1 | 1 |
| critical-path | 16 | 1 | 15 | 14 | 1 | 3 | 1 | 1 |
| critical-path | 16 | 2 | 15 | 14 | 1 | 3 | 1 | 1 |
| critical-path | 16 | 3 | 15 | 14 | 1 | 3 | 1 | 1 |
| critical-path | 16 | 4 | 15 | 14 | 1 | 3 | 1 | 1 |
| critical-path | 16 | 5 | 15 | 14 | 1 | 3 | 1 | 1 |
| critical-path | 16 | 6 | 15 | 15 | 0 | 3 | 1 | 1 |
| critical-path | 16 | 7 | 15 | 15 | 0 | 3 | 1 | 1 |
| critical-path | 16 | 8 | 17 | 17 | 0 | 1 | 0 | 0 |
| program-order | 16 | 0 | 17 | 16 | 1 | 2 | 1 | 1 |
| program-order | 16 | 1 | 17 | 16 | 1 | 2 | 1 | 1 |
| program-order | 16 | 2 | 17 | 16 | 1 | 2 | 1 | 1 |
| program-order | 16 | 3 | 17 | 16 | 1 | 2 | 1 | 1 |
| program-order | 16 | 4 | 17 | 16 | 1 | 2 | 1 | 1 |
| program-order | 16 | 5 | 17 | 16 | 1 | 2 | 1 | 1 |
| program-order | 16 | 6 | 17 | 16 | 1 | 2 | 1 | 1 |
| program-order | 16 | 7 | 17 | 16 | 1 | 2 | 1 | 1 |
| program-order | 16 | 8 | 17 | 17 | 0 | 2 | 2 | 2 |

- 両方で成立する行 20: W-full の cycles が多い 16、同じ 4、少ない 0。片方だけで成立する行 0
- `mvp2_rp4` の方が遅いか、`mvp2_rp4` だけ不成立の行は無い

#### `fir4x8`（vliw）

| 優先度 | レジスタ上限 | load reserve | W-full | `mvp2_rp4` | 差（W-full − `mvp2_rp4`） | `heldByReadPorts`（W-full） | (MAC か VMAC) と (ST か ST.W) の bundle（`mvp2_rp4`） | L11 の違反（`mvp2_rp4` の命令列を W-full で検査） |
|---|---|---|---|---|---|---|---|---|
| critical-path | なし | - | 不成立: 同時に生きる値 23 > 16 | 不成立: 同時に生きる値 23 > 16 | - | - | - | - |
| program-order | なし | - | 不成立: 同時に生きる値 18 > 16 | 不成立: 同時に生きる値 18 > 16 | - | - | - | - |
| critical-path | 16 | 0 | 25（LD 18） | 23（LD 18） | 2 | 2 | 2 | 2 |
| critical-path | 16 | 1 | 25（LD 18） | 23（LD 18） | 2 | 2 | 2 | 2 |
| critical-path | 16 | 2 | 27（LD 20） | 25（LD 20） | 2 | 2 | 2 | 2 |
| critical-path | 16 | 3 | 27（LD 20） | 25（LD 20） | 2 | 2 | 2 | 2 |
| critical-path | 16 | 4 | 34（LD 27） | 34（LD 27） | 0 | 3 | 1 | 1 |
| critical-path | 16 | 5 | 43（LD 27） | 42（LD 27） | 1 | 2 | 1 | 1 |
| critical-path | 16 | 6 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - |
| critical-path | 16 | 7 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - |
| critical-path | 16 | 8 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - |
| program-order | 16 | 0 | 29 | 29 | 0 | 0 | 0 | 0 |
| program-order | 16 | 1 | 27 | 27 | 0 | 0 | 0 | 0 |
| program-order | 16 | 2 | 27 | 27 | 0 | 0 | 0 | 0 |
| program-order | 16 | 3 | 27 | 27 | 0 | 0 | 0 | 0 |
| program-order | 16 | 4 | 27 | 27 | 0 | 0 | 0 | 0 |
| program-order | 16 | 5 | 29 | 29 | 0 | 0 | 0 | 0 |
| program-order | 16 | 6 | 29 | 29 | 0 | 0 | 0 | 0 |
| program-order | 16 | 7 | 35（LD 26） | 35（LD 26） | 0 | 0 | 0 | 0 |
| program-order | 16 | 8 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - |

- 両方で成立する行 14: W-full の cycles が多い 5、同じ 9、少ない 0。片方だけで成立する行 0
- `mvp2_rp4` の方が遅いか、`mvp2_rp4` だけ不成立の行は無い

#### `matmul3`（vliw）

| 優先度 | レジスタ上限 | load reserve | W-full | `mvp2_rp4` | 差（W-full − `mvp2_rp4`） | `heldByReadPorts`（W-full） | (MAC か VMAC) と (ST か ST.W) の bundle（`mvp2_rp4`） | L11 の違反（`mvp2_rp4` の命令列を W-full で検査） |
|---|---|---|---|---|---|---|---|---|
| critical-path | なし | - | 不成立: 同時に生きる値 17 > 16 | 不成立: 同時に生きる値 17 > 16 | - | - | - | - |
| program-order | なし | - | 28 | 28 | 0 | 0 | 0 | 0 |
| critical-path | 16 | 0 | 28 | 24 | 4 | 4 | 4 | 4 |
| critical-path | 16 | 1 | 28 | 24 | 4 | 4 | 4 | 4 |
| critical-path | 16 | 2 | 28 | 24 | 4 | 4 | 4 | 4 |
| critical-path | 16 | 3 | 31 | 28 | 3 | 4 | 3 | 3 |
| critical-path | 16 | 4 | 39（LD 25） | 36（LD 24） | 3 | 5 | 3 | 3 |
| critical-path | 16 | 5 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - |
| critical-path | 16 | 6 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - |
| critical-path | 16 | 7 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - |
| critical-path | 16 | 8 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - |
| program-order | 16 | 0 | 28 | 27 | 1 | 8 | 1 | 1 |
| program-order | 16 | 1 | 28 | 27 | 1 | 8 | 1 | 1 |
| program-order | 16 | 2 | 28 | 28 | 0 | 0 | 0 | 0 |
| program-order | 16 | 3 | 32 | 32 | 0 | 0 | 0 | 0 |
| program-order | 16 | 4 | 32（LD 22） | 32（LD 22） | 0 | 0 | 0 | 0 |
| program-order | 16 | 5 | 34（LD 22） | 34（LD 22） | 0 | 0 | 0 | 0 |
| program-order | 16 | 6 | 36（LD 25） | 36（LD 25） | 0 | 0 | 0 | 0 |
| program-order | 16 | 7 | 37（LD 26） | 37（LD 26） | 0 | 0 | 0 | 0 |
| program-order | 16 | 8 | 40（LD 27） | 40（LD 27） | 0 | 0 | 0 | 0 |

- 両方で成立する行 15: W-full の cycles が多い 7、同じ 8、少ない 0。片方だけで成立する行 0
- `mvp2_rp4` の方が遅いか、`mvp2_rp4` だけ不成立の行は無い

#### `matmul4`（vliw）

| 優先度 | レジスタ上限 | load reserve | W-full | `mvp2_rp4` | 差（W-full − `mvp2_rp4`） | `heldByReadPorts`（W-full） | (MAC か VMAC) と (ST か ST.W) の bundle（`mvp2_rp4`） | L11 の違反（`mvp2_rp4` の命令列を W-full で検査） |
|---|---|---|---|---|---|---|---|---|
| critical-path | なし | - | 不成立: 同時に生きる値 30 > 16 | 不成立: 同時に生きる値 30 > 16 | - | - | - | - |
| program-order | なし | - | 不成立: 同時に生きる値 28 > 16 | 不成立: 同時に生きる値 28 > 16 | - | - | - | - |
| critical-path | 16 | 0 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - |
| critical-path | 16 | 1 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - |
| critical-path | 16 | 2 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - |
| critical-path | 16 | 3 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - |
| critical-path | 16 | 4 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - |
| critical-path | 16 | 5 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - |
| critical-path | 16 | 6 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - |
| critical-path | 16 | 7 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - |
| critical-path | 16 | 8 | 不成立: 上限の下で行き詰まる | 不成立: 上限の下で行き詰まる | - | - | - | - |
| program-order | 16 | 0 | 不成立: cycle 22 で整列した組が空かない（生きているレジスタ 16 ≤ 16） | 不成立: cycle 22 で整列した組が空かない（生きているレジスタ 16 ≤ 16） | - | - | - | - |
| program-order | 16 | 1 | 58（LD 43） | 不成立: cycle 48 で整列した組が空かない（生きているレジスタ 15 ≤ 16） | - | 1 | - | - |
| program-order | 16 | 2 | 60（LD 46） | 60（LD 46） | 0 | 0 | 0 | 0 |
| program-order | 16 | 3 | 63（LD 47） | 63（LD 47） | 0 | 2 | 2 | 2 |
| program-order | 16 | 4 | 63（LD 50） | 63（LD 50） | 0 | 0 | 0 | 0 |
| program-order | 16 | 5 | 66（LD 52） | 67（LD 53） | -1 | 1 | 1 | 1 |
| program-order | 16 | 6 | 71（LD 58） | 71（LD 58） | 0 | 0 | 0 | 0 |
| program-order | 16 | 7 | 77（LD 60） | 77（LD 60） | 0 | 0 | 0 | 0 |
| program-order | 16 | 8 | 79（LD 64） | 79（LD 64） | 0 | 0 | 0 | 0 |

- 両方で成立する行 7: W-full の cycles が多い 0、同じ 6、少ない 1。片方だけで成立する行 1
- W-full で成立し、`mvp2_rp4` の方が遅いか不成立の行: program-order・load reserve 1 は W-full で 58 cycles、`mvp2_rp4` で不成立: cycle 48 で整列した組が空かない（生きているレジスタ 15 ≤ 16）、program-order・load reserve 5 は `mvp2_rp4` の方が遅い（66 → 67。`mvp2_rp4` の命令列の (MAC か VMAC) と (ST か ST.W) の bundle 1、W-full で検査した L11 の違反 1）。読出しポートの制約が無い方が遅いので、この差はスケジュールと割り当ての違いの効果である

単一発行では、1 bundle に1操作なので読出しの欄は高々3で、制約は当たらない。戦略を固定した系列で、W-full と `mvp2_rp4` の命令列（不成立ならその理由）が同じ行は、`saxpy8` 20 行のうち 20 行、`fir4x8` 20 行のうち 20 行、`matmul3` 20 行のうち 20 行、`matmul4` 20 行のうち 20 行である。

### ループ

| ループ | II（W-full / `mvp2_rp4`） | cycles（W-full / `mvp2_rp4`） | 差（W-full − `mvp2_rp4`） | (MAC か VMAC) と (ST か ST.W) の bundle（`mvp2_rp4`） | W-full で検査した違反（L11 / ほか） |
|---|---|---|---|---|---|
| `saxpy8`（i） | 3 / 3 | 17 / 17 | 0 | 0 | 0 / 0 |
| `fir4x8`（i） | 5 / 4 | 29 / 28 | 1 | 2 | 2 / 0 |
| `matmul3`（i） | 6 / 6 | 28 / 28 | 0 | 0 | 0 / 0 |
| `matmul3`（m） | 6 / 6 | 28 / 24 | 4 | 4 | 4 / 0 |
| `product8`（i） | 2 / 2 | 19 / 19 | 0 | 0 | 0 / 0 |
| `dot8`（i） | 2 / 2 | 21 / 21 | 0 | 0 | 0 / 0 |
| `matmul4`（i） | 不成立（MII 20（元の1反復 10）、II 20〜31 のどれにも収まらない） / 不成立（MII 16（元の1反復 8）、II 16〜29 のどれにも収まらない） | - / - | - | - | - |
| `matmul4`（m） | 不成立（MII 8（元の1反復 8）、II 8〜12 のどれにも収まらない） / 不成立（MII 8（元の1反復 8）、II 8〜12 のどれにも収まらない） | - / - | - | - | - |

不成立のループは、命令列が無いので cycles と違反を書かない。すべての II の結果は「Modulo Scheduling」にある。

## Modulo Scheduling

`LoopBenchmarks.all` の6つのループと、`matmul4` の2つのループ（`matmulRows(4)`、`matmulAcc(4)`。#110 第6段の Q1。オーナーが異を唱えなければ既定とする推奨で、決定ではない）を、5つの構成で。配置と入力は list scheduling と同じである（`BenchmarkSpec` が確かめる）。戦略は1つで、II = MII から順に試し、最初に命令列ができた II を使う（ADR-0015）。ここでの II はループの II で、`metrics` の `ii` に、トレースで確かめた値（`crosscheck/ModuloTrace`）を記録する。`vliw-metrics/3` の `ii` は、スケジュールしたループの反復の開始間隔で、2倍にしたループではベクトルの1反復（元の2反復）の間隔である（[docs/METRICS.md](../docs/METRICS.md)）。比べるときは、元の1反復当たりの II（2倍にしたループでは `ii` / 2、本体を詰めたループとスカラーのループでは `ii`）を使い、`ii` だけを比べない。W-reg の Modulo Scheduling は「測っていない（#78 の質問8 の (c)）」で、値を書かない（`LoopCandidate.NotMeasured`）。

### ループのベクトル化の判定（W-full と `mvp2_rp4`）

| ループ | 構成 | 形 | 判定（`LoopDecision`） | W の候補 |
|---|---|---|---|---|
| `saxpy8`（i） | W-full | 2倍 | `doubled (MII 3 vs 2 × 3)` | 命令列あり |
| `saxpy8`（i） | mvp2_rp4 | 2倍 | `doubled (MII 3 vs 2 × 3)` | 命令列あり |
| `fir4x8`（i） | W-full | 2倍 | `doubled (MII 4 vs 2 × 4)` | 命令列あり |
| `fir4x8`（i） | mvp2_rp4 | 2倍 | `doubled (MII 4 vs 2 × 4)` | 命令列あり |
| `matmul3`（i） | W-full | 本体の詰め込み | `not doubled: OddTripCount; body: 1 pair (each 18, stride 3), 1 scalar lane (NoNeighbour)` | 命令列あり |
| `matmul3`（i） | mvp2_rp4 | 本体の詰め込み | `not doubled: OddTripCount; body: 1 pair (each 18, stride 3), 1 scalar lane (NoNeighbour)` | 命令列あり |
| `matmul3`（m） | W-full | 本体の詰め込み | `not doubled: AssociationOrder; body: 3 pairs (after 18, 21, 24), 3 scalar lanes (NoNeighbour), 3 vector states` | 命令列あり |
| `matmul3`（m） | mvp2_rp4 | 本体の詰め込み | `not doubled: AssociationOrder; body: 3 pairs (after 18, 21, 24), 3 scalar lanes (NoNeighbour), 3 vector states` | 命令列あり |
| `product8`（i） | W-full | スカラー（`NothingToPack`） | `not doubled: OddTripCount, AssociationOrder; NothingToPack` | 命令列あり |
| `product8`（i） | mvp2_rp4 | スカラー（`NothingToPack`） | `not doubled: OddTripCount, AssociationOrder; NothingToPack` | 命令列あり |
| `dot8`（i） | W-full | スカラー（`NothingToPack`） | `not doubled: OddTripCount, AssociationOrder; NothingToPack` | 命令列あり |
| `dot8`（i） | mvp2_rp4 | スカラー（`NothingToPack`） | `not doubled: OddTripCount, AssociationOrder; NothingToPack` | 命令列あり |
| `matmul4`（i） | W-full | 2倍 | `doubled (MII 16 vs 2 × 16)` | 不成立: II 20〜31 のどれにも収まらない |
| `matmul4`（i） | mvp2_rp4 | 2倍 | `doubled (MII 16 vs 2 × 16)` | 不成立: II 16〜29 のどれにも収まらない |
| `matmul4`（m） | W-full | 本体の詰め込み | `not doubled: OddTripCount, AssociationOrder; body: 8 pairs (after 32, 34, 36, 38, 40, 42, 44, 46), 0 scalar lanes, 8 vector states` | 不成立: II 8〜12 のどれにも収まらない |
| `matmul4`（m） | mvp2_rp4 | 本体の詰め込み | `not doubled: OddTripCount, AssociationOrder; body: 8 pairs (after 32, 34, 36, 38, 40, 42, 44, 46), 0 scalar lanes, 8 vector states` | 不成立: II 8〜12 のどれにも収まらない |

判定の MII（G と、作ったときは2倍にしたグラフ）は、3本目の読出しポートを除いて比べる（`Mii.withoutReadPort`。[ADR-0023](../docs/DECISIONS.md)、[docs/COMPILER.md](../docs/COMPILER.md)）。そのため `read_ports` 3 と 4 で同じ判定になり、下の「ループごとの結果」の MII（3本目の読出しポートを含む）と違うことがある。除いた値は「資源ごとの ResMII」にある。

### ループごとの結果

| ループ | 構成 | 形 | ResMII（律速の資源） | RecMII | MII（元の1反復） | `ii` | 元の1反復当たりの II | 元の1反復当たりの II の比（基準 / この構成） | MII を超えた II の理由 | cycles | cycles の比（基準 / この構成） | MinAvg（MII、ASAP） | MinAvg（II） | MaxLive | 割り当て |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `saxpy8`（i） | mvp-1 | スカラー（W なし） | 3（`memory slot`） | 0 | 3（3） | 3 | 3 | 1.00 | - | 29 | 1.00 | 3 | 4 | 4 | 成功 |
| `saxpy8`（i） | W-reg | 測っていない（#78 の質問8 の (c)） | - | - | - | - | - | - | - | - | - | - | - | - | - |
| `saxpy8`（i） | W-full | 2倍 | 3（`memory slot`） | 0 | 3（1.5） | 3 | 1.5 | 2.00 | - | 17 | 1.71 | 5 | 6 | 7 | 成功 |
| `saxpy8`（i） | mvp1_rp4 | スカラー（W なし） | 3（`memory slot`） | 0 | 3（3） | 3 | 3 | 1.00 | - | 29 | 1.00 | 3 | 4 | 4 | 成功 |
| `saxpy8`（i） | mvp2_rp4 | 2倍 | 3（`memory slot`） | 0 | 3（1.5） | 3 | 1.5 | 2.00 | - | 17 | 1.71 | 5 | 6 | 7 | 成功 |
| `fir4x8`（i） | mvp-1 | スカラー（W なし） | 4（`compute slot`、`compute write-back port`、`third read port`） | 0 | 4（4） | 4 | 4 | 1.00 | - | 46 | 1.00 | 9 | 11 | 11 | 成功 |
| `fir4x8`（i） | W-reg | 測っていない（#78 の質問8 の (c)） | - | - | - | - | - | - | - | - | - | - | - | - | - |
| `fir4x8`（i） | W-full | 2倍 | 4（`compute slot`、`compute write-back port`、`third read port`） | 0 | 4（2） | 5 | 2.5 | 1.60 | II 4: (b) レジスタ（同時に生きる値 18 > 16） | 29 | 1.59 | 13 | 12 | 14 | II 4 は `TooManyLive` 18、II 5 で成功 |
| `fir4x8`（i） | mvp1_rp4 | スカラー（W なし） | 4（`compute slot`、`compute write-back port`） | 0 | 4（4） | 4 | 4 | 1.00 | - | 43 | 1.00 | 9 | 10 | 10 | 成功 |
| `fir4x8`（i） | mvp2_rp4 | 2倍 | 4（`compute slot`、`compute write-back port`） | 0 | 4（2） | 4 | 2 | 2.00 | - | 28 | 1.54 | 13 | 15 | 16 | 成功 |
| `matmul3`（i） | mvp-1 | スカラー（W なし） | 9（`compute slot`、`compute write-back port`、`third read port`） | 0 | 9（9） | 9 | 9 | 1.00 | - | 38 | 1.00 | 13 | 14 | 14 | 成功 |
| `matmul3`（i） | W-reg | 測っていない（#78 の質問8 の (c)） | - | - | - | - | - | - | - | - | - | - | - | - | - |
| `matmul3`（i） | W-full | 本体の詰め込み | 6（`compute slot`、`compute write-back port`、`third read port`） | 0 | 6（6） | 6 | 6 | 1.50 | - | 28 | 1.36 | 14 | 15 | 16 | 成功 |
| `matmul3`（i） | mvp1_rp4 | スカラー（W なし） | 9（`compute slot`、`compute write-back port`） | 0 | 9（9） | 9 | 9 | 1.00 | - | 45 | 1.00 | 13 | 16 | 16 | 成功 |
| `matmul3`（i） | mvp2_rp4 | 本体の詰め込み | 6（`compute slot`、`compute write-back port`） | 0 | 6（6） | 6 | 6 | 1.50 | - | 28 | 1.61 | 14 | 15 | 16 | 成功 |
| `matmul3`（m） | mvp-1 | スカラー（W なし） | 9（`compute slot`、`compute write-back port`、`third read port`、`MAC unit`） | 2 | 9（9） | 9 | 9 | 1.00 | - | 40 | 1.00 | 11 | 14 | 14 | 成功 |
| `matmul3`（m） | W-reg | 測っていない（#78 の質問8 の (c)） | - | - | - | - | - | - | - | - | - | - | - | - | - |
| `matmul3`（m） | W-full | 本体の詰め込み | 6（`compute slot`、`compute write-back port`、`third read port`） | 2 | 6（6） | 6 | 6 | 1.50 | - | 28 | 1.43 | 11 | 14 | 15 | 成功 |
| `matmul3`（m） | mvp1_rp4 | スカラー（W なし） | 9（`compute slot`、`compute write-back port`、`MAC unit`） | 2 | 9（9） | 9 | 9 | 1.00 | - | 34 | 1.00 | 11 | 14 | 14 | 成功 |
| `matmul3`（m） | mvp2_rp4 | 本体の詰め込み | 6（`compute slot`、`compute write-back port`） | 2 | 6（6） | 6 | 6 | 1.50 | - | 24 | 1.42 | 11 | 14 | 15 | 成功 |
| `product8`（i） | mvp-1 | スカラー（W なし） | 1（`compute slot`、`memory slot`、`compute write-back port`、`memory write-back port`、`LD unit`、`MUL unit`） | 2 | 2（2） | 2 | 2 | 1.00 | - | 19 | 1.00 | 2 | 2 | 2 | 成功 |
| `product8`（i） | W-reg | 測っていない（#78 の質問8 の (c)） | - | - | - | - | - | - | - | - | - | - | - | - | - |
| `product8`（i） | W-full | スカラー（`NothingToPack`） | 1（`compute slot`、`memory slot`、`compute write-back port`、`memory write-back port`、`LD unit`、`MUL unit`） | 2 | 2（2） | 2 | 2 | 1.00 | - | 19 | 1.00 | 2 | 2 | 2 | 成功 |
| `product8`（i） | mvp1_rp4 | スカラー（W なし） | 1（`compute slot`、`memory slot`、`compute write-back port`、`memory write-back port`、`LD unit`、`MUL unit`） | 2 | 2（2） | 2 | 2 | 1.00 | - | 19 | 1.00 | 2 | 2 | 2 | 成功 |
| `product8`（i） | mvp2_rp4 | スカラー（`NothingToPack`） | 1（`compute slot`、`memory slot`、`compute write-back port`、`memory write-back port`、`LD unit`、`MUL unit`） | 2 | 2（2） | 2 | 2 | 1.00 | - | 19 | 1.00 | 2 | 2 | 2 | 成功 |
| `dot8`（i） | mvp-1 | スカラー（W なし） | 2（`memory slot`、`memory write-back port`、`LD unit`） | 2 | 2（2） | 2 | 2 | 1.00 | - | 21 | 1.00 | 3 | 4 | 4 | 成功 |
| `dot8`（i） | W-reg | 測っていない（#78 の質問8 の (c)） | - | - | - | - | - | - | - | - | - | - | - | - | - |
| `dot8`（i） | W-full | スカラー（`NothingToPack`） | 2（`memory slot`、`memory write-back port`、`LD unit`） | 2 | 2（2） | 2 | 2 | 1.00 | - | 21 | 1.00 | 3 | 4 | 4 | 成功 |
| `dot8`（i） | mvp1_rp4 | スカラー（W なし） | 2（`memory slot`、`memory write-back port`、`LD unit`） | 2 | 2（2） | 2 | 2 | 1.00 | - | 21 | 1.00 | 3 | 4 | 4 | 成功 |
| `dot8`（i） | mvp2_rp4 | スカラー（`NothingToPack`） | 2（`memory slot`、`memory write-back port`、`LD unit`） | 2 | 2（2） | 2 | 2 | 1.00 | - | 21 | 1.00 | 3 | 4 | 4 | 成功 |
| `matmul4`（i） | mvp-1 | スカラー（W なし） | 16（`compute slot`、`compute write-back port`、`third read port`） | 0 | 16（16） | - | - | - | 不成立（命令列なし）: II 16〜20: (b) レジスタ（同時に生きる値 22、25、26、23、22 > 16）（II 16〜20 のどれにも収まらない） | - | - | 20 | - | - | どの II でも割り当てられない |
| `matmul4`（i） | W-reg | 測っていない（#78 の質問8 の (c)） | - | - | - | - | - | - | - | - | - | - | - | - | - |
| `matmul4`（i） | W-full | 2倍 | 20（`third read port`） | 0 | 20（10） | - | - | - | 不成立（`LoopCandidate.Infeasible`）: II 20〜31: (b) レジスタ（同時に生きる値 31、30、29、28、28、28、28、28、28、26、26、26 > 16）（II 20〜31 のどれにも収まらない） | - | - | 22 | - | - | どの II でも割り当てられない |
| `matmul4`（i） | mvp1_rp4 | スカラー（W なし） | 16（`compute slot`、`compute write-back port`） | 0 | 16（16） | - | - | - | 不成立（命令列なし）: II 16〜20: (b) レジスタ（同時に生きる値 24、24、24、23、22 > 16）（II 16〜20 のどれにも収まらない） | - | - | 20 | - | - | どの II でも割り当てられない |
| `matmul4`（i） | mvp2_rp4 | 2倍 | 16（`compute slot`、`memory slot`、`compute write-back port`） | 0 | 16（8） | - | - | - | 不成立（`LoopCandidate.Infeasible`）: II 16〜29: (b) レジスタ（同時に生きる値 34、34、32、30、30、30、29、28、28、28、28、28、28、28 > 16）（II 16〜29 のどれにも収まらない） | - | - | 23 | - | - | どの II でも割り当てられない |
| `matmul4`（m） | mvp-1 | スカラー（W なし） | 16（`compute slot`、`compute write-back port`、`third read port`、`MAC unit`） | 2 | 16（16） | - | - | - | 不成立（命令列なし）: II 16〜20: (b) レジスタ（同時に生きる値 23、23、23、23、23 > 16）（II 16〜20 のどれにも収まらない） | - | - | 17 | - | - | どの II でも割り当てられない |
| `matmul4`（m） | W-reg | 測っていない（#78 の質問8 の (c)） | - | - | - | - | - | - | - | - | - | - | - | - | - |
| `matmul4`（m） | W-full | 本体の詰め込み | 8（`compute slot`、`compute write-back port`、`third read port`、`VMAC unit`） | 2 | 8（8） | - | - | - | 不成立（`LoopCandidate.Infeasible`）: II 8〜12: (b) レジスタ（同時に生きる値 23、23、23、23、23 > 16）（II 8〜12 のどれにも収まらない） | - | - | 18 | - | - | どの II でも割り当てられない |
| `matmul4`（m） | mvp1_rp4 | スカラー（W なし） | 16（`compute slot`、`compute write-back port`、`MAC unit`） | 2 | 16（16） | - | - | - | 不成立（命令列なし）: II 16〜20: (b) レジスタ（同時に生きる値 23、23、23、23、23 > 16）（II 16〜20 のどれにも収まらない） | - | - | 17 | - | - | どの II でも割り当てられない |
| `matmul4`（m） | mvp2_rp4 | 本体の詰め込み | 8（`compute slot`、`compute write-back port`、`VMAC unit`） | 2 | 8（8） | - | - | - | 不成立（`LoopCandidate.Infeasible`）: II 8〜12: (b) レジスタ（同時に生きる値 23、23、23、23、23 > 16）（II 8〜12 のどれにも収まらない） | - | - | 18 | - | - | どの II でも割り当てられない |

- 基準は、同じ読出しポート数の基準の構成（W-full は mvp-1、`mvp2_rp4` は `mvp1_rp4`）である。mvp-1 と `mvp1_rp4` の6つのループは [MAC.md](MAC.md) の記録そのもので、この表で MaxLive（同時に生きる値、#110）を足した
- MinAvg と MaxLive は重みで数える（組は2。[docs/COMPILER.md](../docs/COMPILER.md)「記録の定義」）。割り当ては、レジスタ（(b) と (b′)）で II を上げたときの、その II の結果である
- 理由の (a)・(b)・(b′) は [MAC.md](MAC.md) と [docs/COMPILER.md](../docs/COMPILER.md) と同じ。「不成立」の行は、すべての II の結果を書き、cycles を書かない（W の値をスカラーの値で置き換えない）

### 資源ごとの ResMII

スケジュールしたグラフ（W-full と `mvp2_rp4` では W のグラフ、`NothingToPack` ではスカラーのループ）の1反復が、資源ごとに使う回数である（[docs/COMPILER.md](../docs/COMPILER.md)「Modulo Scheduling」。どの資源も1サイクルに1回）。2倍にしたループでは、1反復は元の2反復である。「-」はその資源を使わないか、その構成に無い（3本目の読出しポートは、MAC があり `read_ports` 3 の構成だけの資源である）。演算器は、その演算の数 × II である。W-reg は測っていない（#78 の質問8 の (c)）ので、この表に無い。

| ループ | 構成 | 形 | Compute のスロット | Memory のスロット | Compute の書戻しポート | Memory の書戻しポート | 3本目の読出しポート | 演算器 | ResMII（律速の資源） | ResMII（3本目の読出しポートを除く） | MII（3本目の読出しポートを除く。元の1反復） |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `saxpy8`（i） | mvp-1 | スカラー（W なし） | 1 | 3 | 1 | 2 | 2 | LD 2、MAC 1、ST 1 | 3（`memory slot`） | 3（`memory slot`） | 3（3） |
| `saxpy8`（i） | W-full | 2倍 | 1 | 3 | 1 | 2 | 2 | LD.W 2、ST.W 1、VMAC 1 | 3（`memory slot`） | 3（`memory slot`） | 3（1.5） |
| `saxpy8`（i） | mvp1_rp4 | スカラー（W なし） | 1 | 3 | 1 | 2 | - | LD 2、MAC 1、ST 1 | 3（`memory slot`） | 3（`memory slot`） | 3（3） |
| `saxpy8`（i） | mvp2_rp4 | 2倍 | 1 | 3 | 1 | 2 | - | LD.W 2、ST.W 1、VMAC 1 | 3（`memory slot`） | 3（`memory slot`） | 3（1.5） |
| `fir4x8`（i） | mvp-1 | スカラー（W なし） | 4 | 2 | 4 | 1 | 4 | LD 1、MAC 3、MUL 1、ST 1 | 4（`compute slot`、`compute write-back port`、`third read port`） | 4（`compute slot`、`compute write-back port`） | 4（4） |
| `fir4x8`（i） | W-full | 2倍 | 4 | 3 | 4 | 2 | 4 | LD.W 2、ST.W 1、VMAC 3、VMUL 1 | 4（`compute slot`、`compute write-back port`、`third read port`） | 4（`compute slot`、`compute write-back port`） | 4（2） |
| `fir4x8`（i） | mvp1_rp4 | スカラー（W なし） | 4 | 2 | 4 | 1 | - | LD 1、MAC 3、MUL 1、ST 1 | 4（`compute slot`、`compute write-back port`） | 4（`compute slot`、`compute write-back port`） | 4（4） |
| `fir4x8`（i） | mvp2_rp4 | 2倍 | 4 | 3 | 4 | 2 | - | LD.W 2、ST.W 1、VMAC 3、VMUL 1 | 4（`compute slot`、`compute write-back port`） | 4（`compute slot`、`compute write-back port`） | 4（2） |
| `matmul3`（i） | mvp-1 | スカラー（W なし） | 9 | 6 | 9 | 3 | 9 | LD 3、MAC 6、MUL 3、ST 3 | 9（`compute slot`、`compute write-back port`、`third read port`） | 9（`compute slot`、`compute write-back port`） | 9（9） |
| `matmul3`（i） | W-full | 本体の詰め込み | 6 | 5 | 6 | 3 | 6 | LD 3、MAC 2、MUL 1、ST 1、ST.W 1、VMAC 2、VMUL 1 | 6（`compute slot`、`compute write-back port`、`third read port`） | 6（`compute slot`、`compute write-back port`） | 6（6） |
| `matmul3`（i） | mvp1_rp4 | スカラー（W なし） | 9 | 6 | 9 | 3 | - | LD 3、MAC 6、MUL 3、ST 3 | 9（`compute slot`、`compute write-back port`） | 9（`compute slot`、`compute write-back port`） | 9（9） |
| `matmul3`（i） | mvp2_rp4 | 本体の詰め込み | 6 | 5 | 6 | 3 | - | LD 3、MAC 2、MUL 1、ST 1、ST.W 1、VMAC 2、VMUL 1 | 6（`compute slot`、`compute write-back port`） | 6（`compute slot`、`compute write-back port`） | 6（6） |
| `matmul3`（m） | mvp-1 | スカラー（W なし） | 9 | 6 | 9 | 6 | 9 | LD 6、MAC 9 | 9（`compute slot`、`compute write-back port`、`third read port`、`MAC unit`） | 9（`compute slot`、`compute write-back port`、`MAC unit`） | 9（9） |
| `matmul3`（m） | W-full | 本体の詰め込み | 6 | 5 | 6 | 5 | 6 | LD 4、LD.W 1、MAC 3、VMAC 3 | 6（`compute slot`、`compute write-back port`、`third read port`） | 6（`compute slot`、`compute write-back port`） | 6（6） |
| `matmul3`（m） | mvp1_rp4 | スカラー（W なし） | 9 | 6 | 9 | 6 | - | LD 6、MAC 9 | 9（`compute slot`、`compute write-back port`、`MAC unit`） | 9（`compute slot`、`compute write-back port`、`MAC unit`） | 9（9） |
| `matmul3`（m） | mvp2_rp4 | 本体の詰め込み | 6 | 5 | 6 | 5 | - | LD 4、LD.W 1、MAC 3、VMAC 3 | 6（`compute slot`、`compute write-back port`） | 6（`compute slot`、`compute write-back port`） | 6（6） |
| `product8`（i） | mvp-1 | スカラー（W なし） | 1 | 1 | 1 | 1 | - | LD 1、MUL 1 | 1（`compute slot`、`memory slot`、`compute write-back port`、`memory write-back port`、`LD unit`、`MUL unit`） | 1（`compute slot`、`memory slot`、`compute write-back port`、`memory write-back port`、`LD unit`、`MUL unit`） | 2（2） |
| `product8`（i） | W-full | スカラー（`NothingToPack`） | 1 | 1 | 1 | 1 | - | LD 1、MUL 1 | 1（`compute slot`、`memory slot`、`compute write-back port`、`memory write-back port`、`LD unit`、`MUL unit`） | 1（`compute slot`、`memory slot`、`compute write-back port`、`memory write-back port`、`LD unit`、`MUL unit`） | 2（2） |
| `product8`（i） | mvp1_rp4 | スカラー（W なし） | 1 | 1 | 1 | 1 | - | LD 1、MUL 1 | 1（`compute slot`、`memory slot`、`compute write-back port`、`memory write-back port`、`LD unit`、`MUL unit`） | 1（`compute slot`、`memory slot`、`compute write-back port`、`memory write-back port`、`LD unit`、`MUL unit`） | 2（2） |
| `product8`（i） | mvp2_rp4 | スカラー（`NothingToPack`） | 1 | 1 | 1 | 1 | - | LD 1、MUL 1 | 1（`compute slot`、`memory slot`、`compute write-back port`、`memory write-back port`、`LD unit`、`MUL unit`） | 1（`compute slot`、`memory slot`、`compute write-back port`、`memory write-back port`、`LD unit`、`MUL unit`） | 2（2） |
| `dot8`（i） | mvp-1 | スカラー（W なし） | 1 | 2 | 1 | 2 | 1 | LD 2、MAC 1 | 2（`memory slot`、`memory write-back port`、`LD unit`） | 2（`memory slot`、`memory write-back port`、`LD unit`） | 2（2） |
| `dot8`（i） | W-full | スカラー（`NothingToPack`） | 1 | 2 | 1 | 2 | 1 | LD 2、MAC 1 | 2（`memory slot`、`memory write-back port`、`LD unit`） | 2（`memory slot`、`memory write-back port`、`LD unit`） | 2（2） |
| `dot8`（i） | mvp1_rp4 | スカラー（W なし） | 1 | 2 | 1 | 2 | - | LD 2、MAC 1 | 2（`memory slot`、`memory write-back port`、`LD unit`） | 2（`memory slot`、`memory write-back port`、`LD unit`） | 2（2） |
| `dot8`（i） | mvp2_rp4 | スカラー（`NothingToPack`） | 1 | 2 | 1 | 2 | - | LD 2、MAC 1 | 2（`memory slot`、`memory write-back port`、`LD unit`） | 2（`memory slot`、`memory write-back port`、`LD unit`） | 2（2） |
| `matmul4`（i） | mvp-1 | スカラー（W なし） | 16 | 8 | 16 | 4 | 16 | LD 4、MAC 12、MUL 4、ST 4 | 16（`compute slot`、`compute write-back port`、`third read port`） | 16（`compute slot`、`compute write-back port`） | 16（16） |
| `matmul4`（i） | W-full | 2倍 | 16 | 16 | 16 | 8 | 20 | LD 8、ST 8、VMAC 12、VMUL 4 | 20（`third read port`） | 16（`compute slot`、`memory slot`、`compute write-back port`） | 16（8） |
| `matmul4`（i） | mvp1_rp4 | スカラー（W なし） | 16 | 8 | 16 | 4 | - | LD 4、MAC 12、MUL 4、ST 4 | 16（`compute slot`、`compute write-back port`） | 16（`compute slot`、`compute write-back port`） | 16（16） |
| `matmul4`（i） | mvp2_rp4 | 2倍 | 16 | 16 | 16 | 8 | - | LD 8、ST 8、VMAC 12、VMUL 4 | 16（`compute slot`、`memory slot`、`compute write-back port`） | 16（`compute slot`、`memory slot`、`compute write-back port`） | 16（8） |
| `matmul4`（m） | mvp-1 | スカラー（W なし） | 16 | 8 | 16 | 8 | 16 | LD 8、MAC 16 | 16（`compute slot`、`compute write-back port`、`third read port`、`MAC unit`） | 16（`compute slot`、`compute write-back port`、`MAC unit`） | 16（16） |
| `matmul4`（m） | W-full | 本体の詰め込み | 8 | 6 | 8 | 6 | 8 | LD 4、LD.W 2、VMAC 8 | 8（`compute slot`、`compute write-back port`、`third read port`、`VMAC unit`） | 8（`compute slot`、`compute write-back port`、`VMAC unit`） | 8（8） |
| `matmul4`（m） | mvp1_rp4 | スカラー（W なし） | 16 | 8 | 16 | 8 | - | LD 8、MAC 16 | 16（`compute slot`、`compute write-back port`、`MAC unit`） | 16（`compute slot`、`compute write-back port`、`MAC unit`） | 16（16） |
| `matmul4`（m） | mvp2_rp4 | 本体の詰め込み | 8 | 6 | 8 | 6 | - | LD 4、LD.W 2、VMAC 8 | 8（`compute slot`、`compute write-back port`、`VMAC unit`） | 8（`compute slot`、`compute write-back port`、`VMAC unit`） | 8（8） |

### `matmul4` のループ

レジスタ 16 本では、測った構成（mvp-1、W-full、mvp1_rp4、mvp2_rp4）のどれでも、2つのループの命令列ができない（W-reg は測っていない。#78 の質問8 の (c)）。理由は構造的で、スケジュールしたグラフから数えた、ループの間ずっと生きる値の重み（不変の値はループの前に LD してループの間レジスタに置く（ADR-0014）。Modulo Scheduling は LD を複製しない（ADR-0016））と、レジスタ（(b)）で不成立の II の同時に生きる値を書く。

| ループ | 構成 | 形 | 不変の値の重み | 反復をまたいで渡す値の重み | レジスタ（(b)）で不成立の II の、同時に生きる値の最小 | 通常の利用のスカラーのループ G |
|---|---|---|---|---|---|---|
| `matmul4`（i） | mvp-1 | スカラー（W なし） | 16 | 0 | 22 | - |
| `matmul4`（i） | W-reg | 測っていない（#78 の質問8 の (c)） | - | - | - | 収まらない（W の値ではない） |
| `matmul4`（i） | W-full | 2倍 | 16 | 0 | 26 | 収まらない |
| `matmul4`（i） | mvp1_rp4 | スカラー（W なし） | 16 | 0 | 22 | - |
| `matmul4`（i） | mvp2_rp4 | 2倍 | 16 | 0 | 28 | 収まらない |
| `matmul4`（m） | mvp-1 | スカラー（W なし） | 0 | 16 | 23 | - |
| `matmul4`（m） | W-reg | 測っていない（#78 の質問8 の (c)） | - | - | - | 収まらない（W の値ではない） |
| `matmul4`（m） | W-full | 本体の詰め込み | 0 | 16 | 23 | 収まらない |
| `matmul4`（m） | mvp1_rp4 | スカラー（W なし） | 0 | 16 | 23 | - |
| `matmul4`（m） | mvp2_rp4 | 本体の詰め込み | 0 | 16 | 23 | 収まらない |

- 不変の値は、反復が読むループの前の値（`matmul4`（i）では b の16語）で、ループの間ずっと生きる。反復をまたいで渡す値は、状態（`matmul4`（m）では c の16の累算。本体を詰めたループでは8つのベクトルの状態）と、後の反復が読む LD の値で、渡す反復の数を掛けて数える。重みは組を2と数える
- 通常の利用は、W のグラフが収まらなければスカラーのループ G に戻る（`NoIiFits`）。G も収まらなければ命令列は無い。この欄は情報で、W の値ではない。W-reg は W のグラフを作らず、G だけを通常の利用で試す

ループの記録の全項目は `metrics/<kernel>_<loop>_modulo<構成>.json`（`ii` はトレースで確かめた値）、命令列と入力・期待値は `cases/` の同じ名前にある。W-full が `_w`、`mvp2_rp4` が `_w_rp4` で、`NothingToPack` のループはスカラーの命令列（mvp-1 と同じ）、不成立と測っていないループにはファイルが無い。

## #78 の見積りと測定

見積りは、#78 の提案（2026-10-20 のコメント）のときに、使い捨ての実装（今のコンパイラを移したもの、rematerialization なし、16 レジスタ）で求めた値である。情報で、目標ではない（ADR-0023）。S2・S2a・WFa・P16 は実装しないので見積りだけで（ADR-0022）、P16 は 16bit の結果（mod 2^16）なので、i32 の基準と比べられない（同じ算術の基準が無い。#78）。W は i32 を保つので、同じ算術の基準を作らずに mvp-1 と比べられる。`matmul4` は #78 の後に足したので、見積りが無い。

list scheduling、方式ごとに cycles 最小（vliw）。見積りの括弧は #78 の表の下限と律速の資源で、測定の下限は上の「資源ごとの下限」の最大である。見積りと比べるのは、LD を複製しない行の最小である（MAC.md と同じ）。

| カーネル | 構成 | 見積り（下限 律速） | 測定（方式ごとに cycles 最小） | LD を複製しない行の最小（試行） | 測定の下限（律速） |
|---|---|---|---|---|---|
| `saxpy8` | M1（mvp-1） | 27（26 Memory） | 27 | 27（critical-path, no register limit） | 26（Memory） |
| `saxpy8` | M1rp4（mvp1_rp4） | 26 | 26 | 26（critical-path, no register limit） | 26（Memory） |
| `saxpy8` | S2 | 26（26 Memory） | 見積りだけ（実装しない。ADR-0022） | - | - |
| `saxpy8` | WR（W-reg） | 27（26 Memory） | 27 | 27（critical-path, no register limit） | 26（Memory） |
| `saxpy8` | WF（W-full） | 15（14 Memory） | 15 | 15（critical-path, no register limit） | 14（Memory） |
| `saxpy8` | WFa | 19（18 Memory） | 見積りだけ（実装しない。ADR-0022） | - | - |
| `saxpy8` | WF4（mvp2_rp4） | 14（14 Memory） | 14 | 14（critical-path, no register limit） | 14（Memory） |
| `saxpy8` | P16 | 15（14 Memory） | 見積りだけ（実装しない。ADR-0022）。16bit の結果で、同じ算術の基準が無い | - | - |
| `fir4x8` | M1（mvp-1） | 40（33 Compute・WB・rp） | 40 | 40（program-order, no register limit） | 33（Compute・WB・rp） |
| `fir4x8` | M1rp4（mvp1_rp4） | 40 | 38（LD 27） | 40（program-order, no register limit） | 33（Compute・WB） |
| `fir4x8` | S2 | 28（24 Memory） | 見積りだけ（実装しない。ADR-0022） | - | - |
| `fir4x8` | WR（W-reg） | 35（33 Memory） | 37 | 37（program-order, no register limit） | 33（Memory） |
| `fir4x8` | WF（W-full） | 26（19 Memory） | 25（LD 18） | 27（program-order, 16 registers, load reserve 1） | 19（Memory） |
| `fir4x8` | WFa | 30（28 Memory） | 見積りだけ（実装しない。ADR-0022） | - | - |
| `fir4x8` | WF4（mvp2_rp4） | 26（19 Memory） | 23（LD 18） | 27（program-order, 16 registers, load reserve 1） | 19（Memory） |
| `fir4x8` | P16 | 29（22 Compute・WB） | 見積りだけ（実装しない。ADR-0022）。16bit の結果で、同じ算術の基準が無い | - | - |
| `matmul3` | M1（mvp-1） | 39（28 Compute・Memory・WB・rp） | 39 | 39（program-order, 16 registers, load reserve 0） | 28（Compute・Memory・WB・rp） |
| `matmul3` | M1rp4（mvp1_rp4） | 33 | 33 | 33（critical-path, 16 registers, load reserve 0） | 28（Compute・Memory・WB） |
| `matmul3` | S2 | 28（28 Memory） | 見積りだけ（実装しない。ADR-0022） | - | - |
| `matmul3` | WR（W-reg） | 32（28 Memory） | 32 | 32（critical-path, 16 registers, load reserve 0） | 28（Memory） |
| `matmul3` | WF（W-full） | 28（22 Memory） | 28 | 28（program-order, no register limit） | 22（Memory） |
| `matmul3` | WFa | 30（25 Memory） | 見積りだけ（実装しない。ADR-0022） | - | - |
| `matmul3` | WF4（mvp2_rp4） | 24（22 Memory） | 24 | 24（critical-path, 16 registers, load reserve 0） | 22（Memory） |
| `matmul3` | P16 | 28（19 Compute・Memory・WB・rp） | 見積りだけ（実装しない。ADR-0022）。16bit の結果で、同じ算術の基準が無い | - | - |

見積りと違う値（理由は [docs/COMPILER.md](../docs/COMPILER.md)「ISA mvp-2（W）」と ADR-0023 の第4段の後半の補足。どれもコンパイラの誤りではない）:

- `fir4x8` の mvp1_rp4: 見積り 40、測定 38（LD 27）、LD を複製しない行の最小 40。見積りは rematerialization を入れていない（LD の複製）
- `fir4x8` の W-reg: 見積り 35、測定 37、LD を複製しない行の最小 37。詰めた DAG のノードの番号の付け方（既定）と見積りの番号の違い（program-order の試行）
- `fir4x8` の W-full: 見積り 26、測定 25（LD 18）、LD を複製しない行の最小 27。見積りが数えていない LD.W の rematerialization と、詰めた DAG のノードの番号の付け方（既定）
- `fir4x8` の mvp2_rp4: 見積り 26、測定 23（LD 18）、LD を複製しない行の最小 27。見積りが数えていない LD.W の rematerialization と、詰めた DAG のノードの番号の付け方（既定）

`matmul4`（見積りなし）: mvp-1 77（下限 65、cs・wbC・rp）、W-reg 不成立（TooManyLive 2、Stuck 18。下限 49、ms）、W-full 58（下限 33、cs・ms・wbC・rp）、mvp1_rp4 76（下限 65、cs・wbC）、mvp2_rp4 60（下限 33、cs・ms・wbC）。

ループの MII（元の1単位当たり。`saxpy8` は要素、`fir4x8` は出力、`matmul3`（i）は行、（m）は段）と、W-full の MinAvg（MII、ASAP）:

| ループ | 見積り | 測定 | MinAvg（見積り / 測定、W-full） |
|---|---|---|---|
| `saxpy8`（i） | M1 3、S2 3、S2a 3、WR 3、WF 1.5、WFa 2、WF4 1.5、P16 1.5 | M1 3、WR 測っていない（#78 の質問8 の (c)）、WF 1.5、WF4 1.5 | 5 / 5 |
| `fir4x8`（i） | M1 4、S2 2、S2a 4、WR 3、WF 2、WFa 2.5、WF4 2、P16 2.5 | M1 4、WR 測っていない（#78 の質問8 の (c)）、WF 2、WF4 2 | 13 / 13 |
| `matmul3`（i） | M1 9、S2 6、S2a 9、WR 7、WF 6、WFa 7、WF4 6、P16 6 | M1 9、WR 測っていない（#78 の質問8 の (c)）、WF 6、WF4 6 | 14 / 14 |
| `matmul3`（m） | M1 9、S2 6、S2a 9、WR 6、WF 6、WFa 6、WF4 6、P16 6 | M1 9、WR 測っていない（#78 の質問8 の (c)）、WF 6、WF4 6 | 11 / 11 |

## ADR-0022「引き受ける危険」の確認

- 16本のレジスタは8組しかない・割り当てが断片化する: 戦略を固定した系列で `NoAlignedPair` の行は、W-reg は `matmul3`（vliw）1。W-full は `matmul4`（vliw）1、`matmul4`（single）2。mvp2_rp4 は `matmul4`（vliw）2、`matmul4`（single）2である（不成立の行の数と理由は上の各表）。記録した W のループ（W-full と mvp2_rp4 で 16）には、II を (b′) で上げたものも、(b′) で不成立の II も無い（ほかのループについては [ADR-0023](../docs/DECISIONS.md) の第5段の後半の補足）
- W-reg では集めが要る: W-reg の不成立の行（vliw / single）は `saxpy8` 0 / 1、`fir4x8` 11 / 11、`matmul3` 8 / 7、`matmul4` 20 / 20。方式ごとに cycles 最小で不成立なのは `matmul4`（vliw。TooManyLive 2、Stuck 18）、`matmul4`（single。TooManyLive 2、Stuck 18）（集めのレーンの LD は複製しない。#110 第4段の Q5 の既定）。集め全体の複製を足さずに不成立と記録するのは #110 第6段の Q2 で、オーナーが異を唱えなければ既定とする推奨で、決定ではない。W-reg の Modulo Scheduling は測っていない（#78 の質問8 の (c)）
- 2つ目の乗算器と RF の配線の費用: 測っていない（#57）。上の「構成ごとに足すもの」は書いた値である
- DMA（#111）との bank のサイクル: Scratchpad の bank ごとの語を「スロットとポート」に記録した。DMA との共有は測っていない（#111）
- 命令領域（#81）: 記録した命令列の bundle は、W の構成では最大 82（`matmul4_single_w`）、基準（mvp-1 と `mvp1_rp4`）では最大 128（`matmul4_single_mac`）である（命令領域 256 bundle）
- `matmul3` の3列目はスカラーのまま残る: W-full の `matmul3` の詰め方は 組 3（行をまたぐ組 0）、スカラーのレーン 3: c(0,2)（NoNeighbour）、c(1,2)（NoNeighbour）、c(2,2)（NoNeighbour）、ブロードキャスト 9、集め 0 で、3列目の語 c(0,2)、c(1,2)、c(2,2) はどれもスカラーのレーンである（危険のとおり）。`matmul4` は 組 8（行をまたぐ組 0）、スカラーのレーン 0、ブロードキャスト 16、集め 0 である（この文の詰め方は `Pack` の結果から作る）。規則1は2段の規則（#110 第4段の Q1 の (b)。オーナーの答えを待つ推奨で、決定ではない）で、list scheduling とループで同じである（`Pack.storePairs` と `LoopPack.storePairs` が `matmul3` とその（m）の候補を試す順から作る）
- `dot8`・`product8` は速くならない: `product8`（i）（W-full）19 cycles、mvp-1 19 cycles、`product8`（i）（mvp2_rp4）19 cycles、mvp1_rp4 19 cycles、`dot8`（i）（W-full）21 cycles、mvp-1 21 cycles、`dot8`（i）（mvp2_rp4）21 cycles、mvp1_rp4 21 cycles（`NothingToPack`。スカラーの命令列で、mvp-1 と同じ cycles）

## 測っていないもの

- Fmax・面積・電力（#57）。cycle の比を実時間の速度比と読まない。この判断は #57 まで暫定である（#78 の質問14）
- `read_ports` 4 の RTL（`mvp1_rp4`・`mvp2_rp4`。#57）。この2つの値は参照モデルと verifier だけで確かめている
- 新しい演算の latency と II を変えた構成（`mvp2_vlat`・`mvp2_vii`）は、テストだけで確かめ、ベンチマークの系列にしない（#78 の質問3。数は [docs/COMPILER.md](../docs/COMPILER.md)）
- レジスタ 8・12 本（比較は #78 の表と同じ16本。mvp-1 の 8・12 本の系列も無い）
- W-reg の Modulo Scheduling（#78 の質問8 の (c)）
- P16・S2・S2a・WFa（実装しない。ADR-0022。見積りだけ）
- 固定パイプライン（W を使わない。ADR-0022）
