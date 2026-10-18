#!/usr/bin/env bash
# Self-test of scripts/repeat-tests.sh with a fake sbt, run by `scripts/check.sh static` (#74).
# Checks the inputs in the form the Repeat tests workflow passes them (numbers as "10.0"), the
# summary of passing, failing (with sbt's colours, as on CI) and hanging runs, and the seeds.
set -euo pipefail

die() { printf 'test-repeat-tests: %s\n' "$*" >&2; exit 1; }

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
mkdir -p "$work/bin"

# FAKE_PLAN: one word per sbt run (pass, fail or hang). Every run prints the seed line of the tests.
cat >"$work/bin/sbt" <<'EOF'
#!/usr/bin/env bash
n=$(($(cat "$FAKE_STATE" 2>/dev/null || echo 0) + 1))
echo "$n" >"$FAKE_STATE"
read -r -a plan <<<"$FAKE_PLAN"
echo "[sim-seed] VLIW_SIM_SEED=${VLIW_SIM_SEED:-1} (fake)"
case "${plan[$((n - 1))]:-missing}" in
  pass) exit 0 ;;
  fail)
    e=$'\e'
    printf '%s[0m[%s[0m%s[31merror%s[0m] %s[0m%s[0mFailed tests:%s[0m\n' "$e" "$e" "$e" "$e" "$e" "$e" "$e"
    printf '%s[0m[%s[0m%s[31merror%s[0m] %s[0m%s[0m\tvliw.hw.CaseSpec%s[0m\n' "$e" "$e" "$e" "$e" "$e" "$e" "$e"
    echo 'make: *** [Makefile:12: simulation] Error 255'
    exit 1
    ;;
  hang) sleep 60 ;;
  *) echo "fake sbt: no plan for run $n" >&2; exit 99 ;;
esac
EOF
# The fake verilator reports the pinned version, or FAKE_VERILATOR.
pinned="$("$repo_root/scripts/toolchain.sh" get verilator.version)"
cat >"$work/bin/verilator" <<EOF
#!/bin/sh
echo "Verilator \${FAKE_VERILATOR:-$pinned} (fake)"
EOF
chmod +x "$work/bin/sbt" "$work/bin/verilator"

# Runs repeat-tests.sh MODE RUNS with the plan $1; sets out (its output) and status. The expected
# Verilator is the pinned one, whatever the environment of the self-test.
run() {
  local plan="$1"
  shift
  rm -f "$work/state"
  status=0
  out="$(env -u VLIW_VERILATOR_EXPECTED PATH="$work/bin:$PATH" SBT=sbt FAKE_PLAN="$plan" \
    FAKE_STATE="$work/state" "$repo_root/scripts/repeat-tests.sh" "$@" 2>&1)" || status=$?
}
expect() { # expect STATUS PATTERN...: the exit status and lines that the output must contain
  local want="$1" pattern
  shift
  [[ "$status" == "$want" ]] || die "exit status $status, expected $want; output:"$'\n'"$out"
  for pattern in "$@"; do
    grep -qE -- "$pattern" <<<"$out" || die "missing /$pattern/ in the output:"$'\n'"$out"
  done
}

# The workflow's number inputs, a passing and a failing run; the seed of each run is its number.
REPEAT_SEED=run REPEAT_RUN_TIMEOUT=1800.0 run 'pass fail' full 2.0 "$work/out1"
expect 1 $'^full 1\t[^\t]+\tpass\t[0-9]+\t1\t-\t-$' \
  $'^full 2\t[^\t]+\tfail\\(1\\)\t[0-9]+\t2\tvliw\\.hw\\.CaseSpec\t1 make: \\*\\*\\* \\[Makefile:12: simulation\\] Error 255$' \
  '^full: 1 of 2 runs failed'
[[ "$(wc -l <"$work/out1/summary.tsv")" -eq 3 ]] || die 'summary.tsv should have a header and 2 runs'
[[ -s "$work/out1/run-2/failure.txt" ]] || die 'the failed run has no failure.txt'

# A hanging run is stopped by REPEAT_RUN_TIMEOUT, counted as failed, and the next run starts.
REPEAT_RUN_TIMEOUT=2 run 'hang pass' hw 2 "$work/out2"
expect 1 $'^hw 1\t[^\t]+\tfail\\(124\\)' $'^hw 2\t[^\t]+\tpass\t' '^hw: 1 of 2 runs failed'

# Inputs that are not whole numbers are refused before any run.
run 'pass' full 1.5 "$work/out3"
expect 2 "RUNS must be a positive whole number: '1.5'"
REPEAT_RUN_TIMEOUT=0 run 'pass' full 1 "$work/out3"
expect 2 'REPEAT_RUN_TIMEOUT must be a positive whole number'
[[ ! -e "$work/state" ]] || die 'sbt ran although the inputs were refused'

# A Verilator other than the expected one is refused before any run (#121).
FAKE_VERILATOR=5.999 run 'pass' full 1 "$work/out4"
expect 2 'the Verilator on the PATH is 5\.999, expected '"$pinned"' \(pinned\)' 'toolchain\.sh check failed'
[[ ! -e "$work/state" ]] || die 'sbt ran although the Verilator is not the expected one'

echo 'repeat-tests.sh self-test passed'
