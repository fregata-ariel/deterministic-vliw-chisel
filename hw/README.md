# Hardware

Phase 1のChisel実装先。現時点ではRTL未実装。

Machine Descriptionの読み込み、RF、Compute slot、Memory slot、Instruction ROM、PC、開始・終了制御を実装する。ChiselSimで`sim/`の参照トレースと結果・commit時刻・doneを比較する。

公式chisel-templateの確認済み構成を参考にビルドを導入し、依存バージョンを固定する。SyncReadMemの出力捕捉とRF格納を含むLD遅延を検証する。
