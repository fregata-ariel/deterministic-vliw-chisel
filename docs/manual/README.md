# Quarto標準の図で読むマニュアル

[index.qmd](index.qmd)を同じソースからHTML、PDF、DOCXへ出力します。図はQuarto標準の`{mermaid}`コードセル、比較と実行段階の切替は標準の`.panel-tabset`です。独自HTML、JavaScript、CSS、Quarto拡張、事前のデータ生成スクリプトはありません。

| 出力 | 図の扱い |
|---|---|
| HTML | ネイティブMermaid。組み込みの矢印アニメーションとQuartoのタブ切替 |
| PDF | QuartoがMermaidを静止画像に変換。全タブの内容を順に掲載 |
| DOCX | QuartoがMermaidを静止画像に変換。全タブの内容を順に掲載 |

矢印の動きはデータ経路の説明です。CPUの動作をブラウザでシミュレーションするものではありません。値と時刻は本文・各境界の表に明示します。

## 必要なもの

- Quarto CLI **1.8.27**（Typstを同梱）
- 日本語フォント **Noto Sans CJK JP**（Ubuntuでは`fonts-noto-cjk`）
- CIで検証したブラウザはChromium 141（Playwright 1.56.1同梱）です。PDF・DOCXへの図の変換にChrome / Chromium。Quartoが自動検出します。未導入なら`quarto install chromium`で導入できます。実行ファイルを明示する場合は`QUARTO_CHROMIUM`を指定します。

PDFにはQuarto標準のTypst形式を使います。`--to typst`の出力は`.pdf`です。LaTeX環境は不要です。本文と図の生成にPython、R、Jupyter、Node.jsのインストールは不要です。

## 生成

リポジトリルートで実行します。

```sh
quarto render docs/manual --to html
quarto render docs/manual --to typst  # docs/manual/_output/index.pdf
quarto render docs/manual --to docx
quarto render docs/manual --to all   # 3形式すべて
quarto preview docs/manual --to html
```

出力先は`docs/manual/_output/`です。HTMLは`embed-resources: true`でQuartoの標準リソースを埋め込みます。PDF・DOCXにも図、段階ごとの説明、値の表を含めます。印刷形式ではアニメーションとタブ操作は静止図と連続する見出しになります。

## 構成と根拠

| ソース | 内容 |
|---|---|
| `index.qmd` | アーキテクチャ、ISA、実行意味論、合法性、コンパイラ、対応範囲、参照リンク |
| `_architecture.qmd` | VLIW Core / FixedPipelineのMermaid図 |
| `_decoder.qmd` | 64bit bundleの分解例とMACのISA依存 |
| `_replay.qmd` | E0、E2、E4、E5、E7の経路と、全8境界の状態表 |
| `_hazards.qmd` | L3、L5、L6、L11の衝突と修正案 |
| `_compiler.qmd` | スケジュール、符号化、独立verifierの経路 |

説明とソースリンクはcommit `787ae6d53953bf87202640dcbc181a88156a12a7`を基準にしています。実行図と状態表は`tests/cases/isa_example.json`および`tests/golden/isa_example.trace.jsonl`と照合します。ハザード例は既存の不正ケースに対応します。機械設定やfixtureを変えたときは本文・図・表も更新してください。

## 検証

専用CI `.github/workflows/manual.yml`が次を確認します。

1. raw HTMLや独自ブラウザ資産がなく、状態表が既存goldenと一致すること
2. `quarto render docs/manual --to all`が成功すること
3. HTMLで14個のMermaid図が描画され、標準タブを切り替えられること
4. PDFとDOCXに14個の図と、印刷向けの全段階が含まれること

```sh
# ソースとgoldenの照合。ビルド自体には不要
python3 docs/manual/tests/check_source.py
```

ブラウザ検証にはPlaywright、PDF検査にはPopplerを使います。テスト専用依存はマニュアルの出力には含まれません。CIの`quarto-manual` artifactにはHTML・PDF・DOCXと、表示確認用スクリーンショットを保存します。Webサイトへの公開は行いません。

[Quartoの図表機能](https://quarto.org/docs/authoring/diagrams.html)はHTMLにネイティブMermaid、PDF・DOCXに画像を出す仕組みです。[標準タブセット](https://quarto.org/docs/output-formats/html-basics.html#tabsets)と[Typst出力](https://quarto.org/docs/output-formats/typst.html)もQuarto標準の機能です。
