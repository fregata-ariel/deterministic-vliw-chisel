#!/usr/bin/env bash
# Single entry point for local checks and CI (.github/workflows/ci.yml).
# Usage: scripts/check.sh [static|scala|all]   (default: all)
#        scripts/check.sh sbt COMMAND...       (any sbt commands, with the same logs as scala)
# Set SBT to override the sbt command, e.g. SBT=/path/to/sbt scripts/check.sh scala
# sbt runs keep their output and diagnostics in $CHECK_LOG_DIR
# (default: build/check-logs/<UTC time>/), so that an intermittent failure can be traced (#74).
# CHECK_TIMEOUT (seconds) stops sbt after that time and still records the failure; CI sets it below
# the step's timeout, which would stop only the outer shell.
# Before sbt, scripts/toolchain.sh check must find the expected Verilator on the PATH (ADR-0020).
set -euo pipefail

die() { printf 'check: %s\n' "$*" >&2; exit 1; }
step() { printf '\n==> %s\n' "$*"; }

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$repo_root"
check_args="$*"

# Settings that decide which test JVMs and suites may run at the same time. They are printed
# into sbt.log before the tests run. `Test/testGrouping` is left out: it compiles the tests first,
# which would change the order of compilation and tests that it is meant to record. Print it after
# compiling, e.g. scripts/check.sh sbt Test/compile 'show hw/Test/testGrouping'.
sbt_settings=(
  'show Global/concurrentRestrictions'
  'show hw/Test/fork' 'show hw/Test/testForkedParallel'
  'show crosscheck/Test/fork' 'show crosscheck/Test/testForkedParallel'
)

run_static() {
  command -v jq >/dev/null 2>&1 || die 'jq is required'
  command -v shellcheck >/dev/null 2>&1 || die 'shellcheck is required'

  step 'JSON syntax (config/*.json)'
  local file
  for file in config/*.json; do
    jq empty "$file" || die "invalid JSON: $file"
  done

  step 'shellcheck (scripts/*.sh)'
  shellcheck scripts/*.sh

  step 'whitespace errors in tracked files'
  # Compare the empty tree with the working tree so every tracked file is checked.
  git diff --check "$(git hash-object -t tree /dev/null)" --

  step 'ChiselSim specs mix in VliwChiselSim (#74)'
  if git grep -nE '(with|extends) +([a-z0-9]+\.)*ChiselSim\b|EphemeralSimulator' -- '*.scala' \
    ':!hw/src/test/scala/vliw/hw/VliwChiselSim.scala'; then
    die 'mix in vliw.hw.VliwChiselSim instead of ChiselSim (seed and Verilator jobs, #74)'
  fi

  step 'Python syntax (scripts/*.py)'
  command -v python3 >/dev/null 2>&1 || die 'python3 is required'
  for file in scripts/*.py; do
    python3 -I -c 'import ast, sys; ast.parse(open(sys.argv[1], encoding="utf-8").read(), sys.argv[1])' "$file" ||
      die "invalid Python: $file"
  done

  step 'mutation classifier (scripts/testdata/mutation-classify)'
  local dir tmp expected got
  tmp="$(mktemp -d)"
  for dir in scripts/testdata/mutation-classify/*/; do
    dir="${dir%/}"
    # The classifier writes classification.tsv into the run directory, so run it on a copy.
    cp -R "$dir" "$tmp/"
    expected="$(cat "$dir/expected")"
    got="$(python3 -I scripts/mutation-classify.py "$tmp/${dir##*/}")" ||
      { rm -rf -- "$tmp"; die "${dir##*/}: mutation-classify.py failed"; }
    got="$(printf '%s\n' "$got" | cut -f1,2)"
    [[ "$got" == "$expected" ]] || { rm -rf -- "$tmp"; die "${dir##*/}: expected '$expected', got '$got'"; }
  done
  rm -rf -- "$tmp"

  step 'toolchain.sh with a fake verilator (scripts/test-toolchain.sh)'
  scripts/test-toolchain.sh

  step 'mutation-check.sh with a fake sbt (scripts/test-mutation-check.sh)'
  scripts/test-mutation-check.sh

  step 'repeat-tests.sh with a fake sbt (scripts/test-repeat-tests.sh)'
  scripts/test-repeat-tests.sh
}

# Lines of `ps` for the processes the tests start (sbt and test JVMs, Verilator, the C++ build,
# the simulations).
test_processes() {
  ps -eo pid,ppid,etime,pcpu,rss,command |
    awk 'NR == 1 || /[j]ava|[v]erilator|[m]ake|[c]c1plus|[f]irtool|[s]imulation/' | cut -c1-300
}

# Arguments: the sbt command and the output of scripts/toolchain.sh check (pinned, expected and
# installed Verilator).
record_env() {
  printf 'date: %s\nhead: %s\nchanged files: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$(git rev-parse HEAD)" \
    "$(git status --porcelain | wc -l | tr -d ' ')"
  uname -a
  printf 'cpus: %s\n' "$(nproc 2>/dev/null || sysctl -n hw.ncpu)"
  free -m 2>/dev/null || sysctl hw.memsize
  df -h .
  ulimit -a
  java -version 2>&1
  printf 'sbt command: %s; %s\n' "$(command -v "$1")" "$(grep sbt.version project/build.properties)"
  printf '%s\n' "$2"
  make --version | head -n 1
  "${CXX:-g++}" --version | head -n 1
  printf 'JAVA_OPTS=%s\nSBT_OPTS=%s\n' "${JAVA_OPTS:-}" "${SBT_OPTS:-}"
  printf 'VLIW_SIM_SEED=%s (unset: the default of vliw.hw.SimSeed)\n' "${VLIW_SIM_SEED:-}"
  printf 'command: scripts/check.sh %s\n' "$check_args"
  printf '\nprocesses at start (another sbt on this checkout shares build/chiselsim):\n'
  test_processes
}

# Appends a snapshot of memory and of the test processes every 5 s, until killed.
sample_processes() {
  while :; do
    {
      printf '=== %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      free -m 2>/dev/null | sed -n 2p || true
      test_processes
    } >>"$1" 2>&1
    sleep 5
  done
}

record_failure() {
  {
    printf 'failed at %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    free -m 2>/dev/null || true
    df -h . || true
    printf '\nprocesses by memory:\n'
    ps -eo pid,ppid,etime,pcpu,rss,command --sort=-rss 2>/dev/null | head -n 30 | cut -c1-300 || true
    printf '\nkernel log (OOM killer?):\n'
    { dmesg -T 2>/dev/null || sudo -n dmesg -T 2>/dev/null || true; } | tail -n 100
  } >"$1/failure.txt" 2>&1
  # A later run of the same test overwrites these, so keep the ones this run wrote.
  find build/chiselsim ./*/target/test-reports -newer "$1/env.txt" -type f \
    \( -name compilation-log.txt -o -name simulation-log.txt -o -name execution-script.txt \
    -o -name '*.xml' \) -print0 2>/dev/null |
    tar -czf "$1/run-files.tgz" --null -T - 2>/dev/null || true
}

# Runs sbt with the given commands; output goes to the terminal and to $log_dir/sbt.log.
run_sbt() {
  local sbt_cmd="${SBT:-sbt}"
  command -v "$sbt_cmd" >/dev/null 2>&1 || die "sbt is required (or set SBT): $sbt_cmd"
  command -v verilator >/dev/null 2>&1 || die 'verilator is required by the hw tests (ChiselSim)'

  local log_dir
  log_dir="${CHECK_LOG_DIR:-build/check-logs/$(date -u +%Y%m%dT%H%M%SZ)}"
  mkdir -p "$log_dir"
  # Another Verilator than the expected one (project/toolchain.properties, ADR-0020) would fail
  # BenchmarkSpec only after minutes of tests, so it stops the run before sbt (#121).
  local toolchain toolchain_status=0
  toolchain="$(scripts/toolchain.sh check 2>&1)" || toolchain_status=$?
  record_env "$sbt_cmd" "$toolchain" >"$log_dir/env.txt" 2>&1 || true
  if ((toolchain_status != 0)); then
    printf '%s\n' "$toolchain" >&2
    die "scripts/toolchain.sh check failed (above), so sbt did not run; logs in $log_dir"
  fi
  printf '%s\n' "$toolchain"
  sample_processes "$log_dir/ps.log" >/dev/null 2>&1 &
  local sampler=$!
  # shellcheck disable=SC2064 # expand now: $sampler is local
  trap "pkill -P $sampler 2>/dev/null || true; kill $sampler 2>/dev/null || true" EXIT

  local status=0 limit=()
  if [[ -n "${CHECK_TIMEOUT:-}" ]]; then
    [[ "$CHECK_TIMEOUT" =~ ^[1-9][0-9]*$ ]] || die "CHECK_TIMEOUT must be a number of seconds: $CHECK_TIMEOUT"
    command -v timeout >/dev/null 2>&1 || die 'timeout (coreutils) is required for CHECK_TIMEOUT'
    limit=(timeout --kill-after=30 "$CHECK_TIMEOUT")
  fi
  ${limit[@]+"${limit[@]}"} "$sbt_cmd" "$@" </dev/null 2>&1 | tee "$log_dir/sbt.log" || status=$?
  ((status != 124)) || printf 'check: sbt stopped after CHECK_TIMEOUT=%s s\n' "$CHECK_TIMEOUT" | tee -a "$log_dir/sbt.log" >&2
  # The sampler's `sleep` too, so that no process outlives the run.
  pkill -P "$sampler" 2>/dev/null || true
  kill "$sampler" 2>/dev/null || true
  if ((status != 0)); then
    record_failure "$log_dir"
    printf 'check: sbt failed (exit %d); logs in %s\n' "$status" "$log_dir" >&2
  fi
  return "$status"
}

run_scala() {
  step 'sbt: scalafmt check, compile (-Werror), test'
  run_sbt scalafmtSbtCheck scalafmtCheckAll "${sbt_settings[@]}" test
}

case "${1:-all}" in
  static) run_static ;;
  scala) run_scala ;;
  all) run_static; run_scala ;;
  sbt)
    shift
    [[ $# -gt 0 ]] || die 'sbt needs at least one command'
    # Leading `set ...` commands come before the settings are printed, so the log shows their effect.
    pre=()
    while [[ $# -gt 0 && "$1" == set\ * ]]; do
      pre+=("$1")
      shift
    done
    run_sbt ${pre[@]+"${pre[@]}"} "${sbt_settings[@]}" "$@"
    ;;
  *) die "unknown target: $1 (expected static, scala, all or sbt)" ;;
esac
