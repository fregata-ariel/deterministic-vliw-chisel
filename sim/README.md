# Reference Simulator

Phase 0のISA/サイクル参照モデル実装先。sbtプロジェクト `sim`、パッケージ `vliw.sim`。依存先は `isa` のみで、`compiler`（verifier）には依存しない（[ADR-0002](../docs/DECISIONS.md)）。現時点では未実装。

各cycleのPC、issue、RF commit、Memory commit、pending operation、doneを記録する。`docs/ISA.md`の例はmemory[1]=28、done=7cyclesとなる。

エンコード済み命令を入力とし、算術結果と予定完了時刻を独立して計算する。RTLとの比較に加え、異なる入力値でもcyclesが固定であることを検証する。
