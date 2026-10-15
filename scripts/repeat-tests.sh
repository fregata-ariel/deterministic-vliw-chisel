#!/usr/bin/env bash
# Repeats test runs to measure how often they fail intermittently (#74).
# Used by .github/workflows/repeat-tests.yml; also runs locally.
# Usage: scripts/repeat-tests.sh MODE RUNS [OUT_DIR]   (default OUT_DIR: build/repeat/MODE)
#   hw                  sbt hw/test
#   crosscheck          sbt crosscheck/test
#   hw-then-crosscheck  sbt hw/test crosscheck/test   (one sbt process, one after the other)
#   full                sbt test                      (as scripts/check.sh scala, without scalafmt)
#   write-reservations  sbt "hw/testOnly vliw.hw.WriteReservationsSpec"
#   forked-parallel     sbt test, suites of a project in parallel inside its test JVM
#   fork-group-2        sbt test, up to two test JVMs (projects) at the same time
#   serial              sbt test, one task at a time (no compilation next to the tests)
#   hw-concurrent       two sbt hw/test processes at once on this checkout. They share
#                       build/chiselsim/<Suite>/<test>; a failure here shows only that such sharing
#                       breaks the tests, not that it caused a failure seen elsewhere.
# REPEAT_SEED selects VLIW_SIM_SEED for each run: unset (the default seed), "run" (the run number),
# "random", or a fixed number from 1 to 2147483647. REPEAT_RUN_TIMEOUT (seconds, default 1800) stops
# a run that hangs; it counts as failed (124) and the next run starts. Every run is a fresh sbt
# process through scripts/check.sh, which keeps its logs, the seed and the effective sbt settings in
# OUT_DIR/run-N/. Writes OUT_DIR/summary.tsv (earlier runs in OUT_DIR are removed first); exits 1 if
# any run failed.
set -euo pipefail

die() { printf 'repeat: %s\n' "$*" >&2; exit 2; }

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$repo_root"

mode="${1:-}"
runs="${2:-}"
out="${3:-build/repeat/$mode}"
seed_mode="${REPEAT_SEED:-}"
fork_group_2='set Global / concurrentRestrictions := Seq(Tags.limitAll(java.lang.Runtime.getRuntime.availableProcessors), Tags.limit(Tags.ForkedTestGroup, 2), Tags.exclusiveGroup(Tags.Clean))'
case "$mode" in
  hw | hw-concurrent) cmds=(hw/test) ;;
  crosscheck) cmds=(crosscheck/test) ;;
  hw-then-crosscheck) cmds=(hw/test crosscheck/test) ;;
  full) cmds=(test) ;;
  write-reservations) cmds=('hw/testOnly vliw.hw.WriteReservationsSpec') ;;
  forked-parallel) cmds=('set ThisBuild / Test / testForkedParallel := true' test) ;;
  fork-group-2) cmds=("$fork_group_2" test) ;;
  serial) cmds=('set Global / concurrentRestrictions := Seq(Tags.limitAll(1))' test) ;;
  *) die "unknown mode: '$mode' (see the usage at the top of this script)" ;;
esac
[[ "$runs" =~ ^[1-9][0-9]*$ ]] || die "RUNS must be a positive integer: '$runs'"
if [[ -n "$seed_mode" && "$seed_mode" != run && "$seed_mode" != random ]]; then
  if ! [[ "$seed_mode" =~ ^[1-9][0-9]{0,9}$ ]] || ((seed_mode > 2147483647)); then
    die "REPEAT_SEED must be empty, run, random or 1 to 2147483647: '$seed_mode'"
  fi
fi
if [[ "$seed_mode" == run ]] && ((runs > 2147483647)); then die 'too many runs for REPEAT_SEED=run'; fi
export CHECK_TIMEOUT="${REPEAT_RUN_TIMEOUT:-1800}"
mkdir -p "$out"
rm -rf -- "$out"/run-* "$out/summary.tsv"

# Suites that sbt lists under "Failed tests:" or "Error during tests:", and make errors. sbt colours
# its log on CI, so the escape sequences are removed first.
failure_summary() {
  local suites errors
  suites="$(sed 's/\x1b\[[0-9;]*[A-Za-z]//g' "$@" 2>/dev/null |
    awk '/^\[error\] (Failed tests|Error during tests):/ { f = 1; next }
         f && /^\[error\] \t/ { sub(/^\[error\] \t/, ""); print; next }
         { f = 0 }' | sort -u | paste -sd, -)" || true
  errors="$(grep -ohE 'make: \*\*\* \[[^]]*\] Error [0-9]+' "$@" 2>/dev/null |
    sort | uniq -c | sed 's/^ *//' | paste -sd, -)" || true
  printf '%s\t%s' "${suites:--}" "${errors:--}"
}

# Stops a process and all its descendants. sbt runs under `timeout` (CHECK_TIMEOUT), which moves it
# into a process group of its own, so neither Ctrl-C nor a signal to our group would reach it.
kill_tree() {
  local child
  for child in $(pgrep -P "$1" 2>/dev/null); do kill_tree "$child"; done
  kill -TERM "$1" 2>/dev/null || true
}
pids=()
trap 'for p in ${pids[@]+"${pids[@]}"}; do kill_tree "$p"; done; exit 130' INT TERM

# The seed that the test JVMs of a run used, from the sbt logs.
seeds_of() {
  grep -ohE '^\[sim-seed\] VLIW_SIM_SEED=[0-9]+' "$@" 2>/dev/null |
    sed 's/.*=//' | sort -u | paste -sd, - || true
}

printf 'run\tstarted (UTC)\tresult\tseconds\tseed\tfailed suites\tmake errors\n' >"$out/summary.tsv"
failed=0
for ((i = 1; i <= runs; i++)); do
  dir="$out/run-$i"
  case "$seed_mode" in
    '') unset VLIW_SIM_SEED ;;
    run) export VLIW_SIM_SEED="$i" ;;
    *) export VLIW_SIM_SEED="$seed_mode" ;;
  esac
  started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  start=$SECONDS
  status=0
  # Runs go to the background and are waited for, so that the trap runs at once on Ctrl-C.
  if [[ "$mode" == hw-concurrent ]]; then
    CHECK_LOG_DIR="$dir/a" scripts/check.sh sbt "${cmds[@]}" </dev/null >/dev/null 2>&1 &
    pids=($!)
    CHECK_LOG_DIR="$dir/b" scripts/check.sh sbt "${cmds[@]}" </dev/null >/dev/null 2>&1 &
    pids+=($!)
    logs=("$dir/a/sbt.log" "$dir/b/sbt.log")
  else
    CHECK_LOG_DIR="$dir" scripts/check.sh sbt "${cmds[@]}" </dev/null >/dev/null 2>&1 &
    pids=($!)
    logs=("$dir/sbt.log")
  fi
  for p in "${pids[@]}"; do wait "$p" || status=$?; done
  pids=()
  result=pass
  if ((status != 0)); then
    result="fail($status)"
    failed=$((failed + 1))
  fi
  seeds="$(seeds_of "${logs[@]}")"
  line="$(printf '%d\t%s\t%s\t%d\t%s\t' "$i" "$started" "$result" $((SECONDS - start)) "${seeds:--}")"
  line+="$(failure_summary "${logs[@]}")"
  printf '%s\n' "$line" >>"$out/summary.tsv"
  printf '%s %s\n' "$mode" "$line"
done

total="$(printf '%s: %d of %d runs failed (logs: %s)' "$mode" "$failed" "$runs" "$out")"
printf '\n%s\n' "$total"
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  {
    printf '### %s\n\n~~~\n' "$total"
    cat "$out/summary.tsv"
    printf '~~~\n'
  } >>"$GITHUB_STEP_SUMMARY"
fi
((failed == 0))
