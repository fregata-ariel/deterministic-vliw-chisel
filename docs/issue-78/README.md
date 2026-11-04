# Issue #78の図解資料

[index.qmd](index.qmd)は、Issue #78の本文と全2コメント（2026-10-20取得）を整理した独立したQuarto資料です。既決の作業順、判断待ちの提案、見積り、14のオーナー判断項目を区別しています。ISA・ADRの採用や実装は行いません。

図はQuarto標準の`{mermaid}`セル10個、HTMLでの比較は標準タブ2組です。PDF・DOCXには全タブを順に出力します。独自HTML・JavaScriptはありません。前の[マニュアル](../manual/README.md)と同じ、SVG表示補正の2ルールだけを共有します。

## 生成

必要なものはQuarto **1.8.27**、Noto Sans CJK JP、Chrome / Chromiumです。[環境の導入方法](../manual/README.md#必要なもの)はマニュアルと共通です。PDFはQuarto同梱Typstを使います。

リポジトリルートで実行します。

```sh
quarto render docs/issue-78 --to all
quarto preview docs/issue-78 --to html
```

個別出力は`--to html`、`--to typst`（PDF）、`--to docx`。出力は`docs/issue-78/_output/index.html`・`index.pdf`・`index.docx`です。

## 根拠と検証

[source-manifest.json](source-manifest.json)にIssue・コメントのURL、取得日、本文ハッシュ、提案の数値表から転記したcycles・MIIを記録しています。提案にある使い捨て実装の再実行や、新方式の性能測定は、この資料の作成には含みません。

専用CIは次を確認します。

- 数値表と棒グラフが出典スナップショットに一致すること
- Quarto標準でHTML・PDF・DOCXを生成でき、10図と全タブの内容が印刷出力に含まれること
- HTMLで全タブを選択でき、非表示だった図のラベルも表示されること
- オフライン表示、390px幅での表示、図の印刷高さ

```sh
python3 docs/issue-78/tests/check_source.py
```

CIの`quarto-issue-78` artifactに3形式と表示確認用画像を保存します。議論が更新されたら、元のIssueとコメントを再取得し、本文とスナップショットを一緒に更新してください。
