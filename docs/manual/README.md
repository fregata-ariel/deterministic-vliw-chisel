# 動く図で読むマニュアル（Quarto）

[index.qmd](index.qmd) が日本語マニュアルの本文です。標準のISA mvp-0と、実装済みのmvp-1（MAC）、比較用FixedPipelineを説明します。

- Core / FixedPipelineの切替とデータ経路の段階再生
- ISAのbit分解とlittle-endian byte列。MACのISA依存や予約bit違反も表示
- 既存golden traceに基づくcycle単位の状態再生、タイムライン、RF・メモリ・busy/done
- L3・L5・L6・L11の衝突と修正案の段階表示
- コンパイルから実行・照合までの処理の段階表示

図は手動で再生を開始し、末尾で停止します。前後送りとリセット、実行図には境界のスライダーがあります。OSの`prefers-reduced-motion`を尊重し、非表示になった図・タブの再生は停止します。本文と静的な図・表はJavaScriptなしでも読めます。

## 生成・閲覧

必要: [Quarto CLI](https://quarto.org/docs/get-started/)、Python 3。検証済みQuarto: **1.8.27**。Python標準ライブラリのみを使用し、R・Jupyter・sbtは不要です。

リポジトリルートで:

```sh
quarto render docs/manual
# docs/manual/_output/index.html をブラウザで開く

quarto preview docs/manual
```

CSS、JavaScript、教材データは`embed-resources: true`でHTMLに埋め込まれます。生成後は単一HTMLをコピーしてオフラインで閲覧できます。外部CDN、fetch、Webサーバーは実行時に不要です。`_output/`と`.quarto/`は生成物のためgitignoreしています。印刷は本文と静的な図を使い、動く図はHTMLで利用してください。

## データの由来と更新

本文の設計説明・ソースリンクはcommit `787ae6d53953bf87202640dcbc181a88156a12a7`を基準にしています。ソースが進んだら、基準commit、本文の構成値、静的な図・フォールバック表もあわせて更新してください。

`prepare.py`はQuartoのpre-renderとして次を`assets/data.js`へ取り込みます。fixtureの内容を変換してCPUの期待結果を生成する処理はありません。

| 元データ | 用途 |
|---|---|
| `config/machine.json`、`config/machine-mvp1.json` | opcode、latency、ISA、資源数 |
| `tests/cases/isa_example.json`、`consecutive_mul.json` | 入力値、期待する結果・cycle数 |
| 上のケースが指定する`tests/golden/*.trace.jsonl` | issue、commit、busy/doneの再生 |
| `tests/golden/isa_example.hex` | 手計算の命令語との照合 |
| `tests/cases/raw_mul_next_cycle.json`、`mul_then_add_port.json`、`early_halt_ld.json`、`mac_store_read_ports.json` | ハザード教材と期待する規則番号 |

```sh
python3 docs/manual/prepare.py          # 取り込み直す（通常はrenderが実行）
python3 docs/manual/prepare.py --check  # 未更新なら非ゼロ終了
```

`assets/model.js`はtraceのcommitを入力状態へ重ねる表示用ヘルパーとbit分解だけです。CPUのエミュレータ、スケジューラ、独立verifierの代替ではありません。ISA分解図は単一bundleのL1の欄を確認し、L2〜L11の判定は行いません。ハザード図の修正例は説明用であり、任意プログラムを自動修復しません。

## 検証

Node.js 18以上の標準テストランナーで、fixtureの一致、commit境界、逆方向へのseek、最終状態、LとIIの違い、golden命令語、MAC・不正bitを検査します。

```sh
python3 docs/manual/prepare.py --check
node --test docs/manual/tests/model.test.cjs
```

ブラウザの検証にはPlaywrightとChromiumを使います。Playwrightが既に利用できる環境なら追加インストールは不要です。未導入ならリポジトリ外の作業ディレクトリにインストールできます。

```sh
# 任意: ブラウザ検証用依存を別ディレクトリへ導入
npm install --prefix /tmp/vliw-manual-browser --no-package-lock playwright@1.56.1
/tmp/vliw-manual-browser/node_modules/.bin/playwright install chromium

# render済みのHTMLを直接file://で開き、外部通信なしで検査する
NODE_PATH=/tmp/vliw-manual-browser/node_modules node docs/manual/tests/browser.cjs
```

`browser.cjs`は操作、再生の有限停止、2つのtrace、全境界の表示、bit分解、全ハザード、Core/FixedPipeline切替、キーボード、狭い画面、reduced-motion、JavaScript無効時のフォールバック、内部リンク、ブラウザエラー、外部リクエストを検査します。スクリーンショットは`_output/qa/`へ保存します。`PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH`を指定すると既存のChromiumを使えます。

この変更はドキュメントと表示用コードです。RTL・Scala実装は変更しません。RTLの機能確認は既存の`scripts/check.sh`に従って別途行ってください。

PRでは`.github/workflows/manual.yml`が同じ検証を行い、生成HTMLとレビュー用スクリーンショットを`animated-manual` artifactとして保存します。ホスティングへの公開は行いません。
