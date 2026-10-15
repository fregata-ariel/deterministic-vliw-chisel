#!/usr/bin/env bash
# Mutation check: every mutant in scripts/mutants.tsv must make the test suite fail.
# A mutant that survives means the tests cannot detect that kind of error.
# Each run is classified by scripts/mutation-classify.py from the JUnit XML reports and the sbt log:
#   KILLED   a test failed: by an assertion (ScalaTest, ChiselSim expect, RTL assertion) or, counted
#            separately, by another exception thrown from the test
#   ERROR    scalac or -Werror, Verilator/make build, firtool, elaboration, OOM, I/O, the simulation
#            or the test JVM died, timeout, aborted suite (see tests/cases/README.md)
#   SURVIVED sbt test passed
# The tests run once without a mutant first and must pass. An environment ERROR is retried.
# Usage: scripts/mutation-check.sh [mutant-id...]   (default: all mutants)
# Environment: SBT (sbt command), MUTATION_LOG_DIR (default build/mutation/<UTC time>),
#   MUTATION_TIMEOUT (seconds per sbt run, default 1200), MUTATION_RETRIES (reruns after an
#   environment ERROR, default 1), MUTATION_BASELINE (0 skips the unmutated run, default 1).
# Target files must have no uncommitted changes.
set -euo pipefail

die() { printf 'mutation: %s\n' "$*" >&2; exit 2; }

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$repo_root"
sbt_cmd="${SBT:-sbt}"
command -v "$sbt_cmd" >/dev/null 2>&1 || die "sbt is required (or set SBT): $sbt_cmd"
command -v python3 >/dev/null 2>&1 || die 'python3 is required'
command -v timeout >/dev/null 2>&1 || die 'timeout (coreutils) is required'

log_dir="${MUTATION_LOG_DIR:-$repo_root/build/mutation/$(date -u +%Y%m%dT%H%M%SZ)}"
limit="${MUTATION_TIMEOUT:-1200}"
retries="${MUTATION_RETRIES:-1}"
mkdir -p "$log_dir"
{
  printf 'date: %s\nhead: %s\ncpus: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$(git rev-parse HEAD)" \
    "$(nproc 2>/dev/null || sysctl -n hw.ncpu)"
  free -m 2>/dev/null || true
  java -version 2>&1
  verilator --version
  printf 'VLIW_SIM_SEED=%s (unset: the default of vliw.hw.SimSeed)\n' "${VLIW_SIM_SEED:-}"
} >"$log_dir/env.txt" 2>&1 || true
current=''
restore() { if [[ -n "$current" ]]; then git checkout -- "$current"; current=''; fi; }
# sbt runs under `timeout`, which puts it in its own process group, so Ctrl-C on this script does not
# reach it. Stop it explicitly before restoring the mutated file.
sbt_pid=''
stop_sbt() { if [[ -n "$sbt_pid" ]]; then kill -TERM "$sbt_pid" 2>/dev/null || true; sbt_pid=''; fi; }
trap 'stop_sbt; restore' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Runs `sbt test` once into directory $1 and writes "VERDICT<TAB>category<TAB>detail" to $1/verdict.
# $1 gets: sbt.log, exit, reports/<project>/TEST-*.xml, build/chiselsim/**/{compilation,simulation}-log.txt
# (failed builds and non-empty simulation logs only), classification.tsv (one row per failing test).
run_tests() {
  local dir="$1" code=0 d f
  mkdir -p "$dir"
  rm -rf -- ./*/target/test-reports # sbt never deletes old reports, so they would mix with this run's
  : >"$dir/start"
  timeout --kill-after=60 "$limit" "$sbt_cmd" test </dev/null >"$dir/sbt.log" 2>&1 &
  sbt_pid=$!
  wait "$sbt_pid" || code=$?
  sbt_pid=''
  echo "$code" >"$dir/exit"
  for d in ./*/target/test-reports; do
    [[ -d "$d" ]] || continue
    d="${d#./}"
    mkdir -p "$dir/reports/${d%%/*}"
    cp -p "$d"/*.xml "$dir/reports/${d%%/*}/" 2>/dev/null || true
  done
  # ChiselSim rewrites these logs on the next run of the same test.
  if [[ -d build/chiselsim ]]; then
    while IFS= read -r -d '' f; do
      if [[ "$f" == */simulation-log.txt ]] || grep -q '^make: \*\*\*' "$f"; then
        mkdir -p "$dir/$(dirname "$f")" && cp -p "$f" "$dir/$f"
      fi
    done < <(find build/chiselsim -newer "$dir/start" -type f -size +0 \
      \( -name compilation-log.txt -o -name simulation-log.txt \) -print0)
  fi
  python3 -I scripts/mutation-classify.py "$dir" >"$dir/verdict" ||
    printf 'ERROR\tclassify\tmutation-classify.py failed\n' >"$dir/verdict"
}

# Runs the tests into $log_dir/$1, again into $log_dir/$1.retryN after an environment error (#74);
# scalac, firtool, elaboration and aborted suites are not retried. Every attempt keeps its directory.
# Sets verdict, category, detail and run_dir from the last attempt.
run_with_retries() {
  local attempt=0
  while :; do
    run_dir="$log_dir/$1"
    [[ $attempt -gt 0 ]] && run_dir="$log_dir/$1.retry$attempt"
    mkdir -p "$run_dir"
    [[ -z "$current" ]] || git diff -- "$current" >"$run_dir/mutant.diff"
    run_tests "$run_dir"
    verdict='' category='' detail=''
    IFS=$'\t' read -r verdict category detail <"$run_dir/verdict" || true
    verdict="${verdict:-ERROR}"
    attempt=$((attempt + 1))
    case "$verdict:$category" in
      ERROR:scalac | ERROR:firtool | ERROR:elaboration | ERROR:aborted) return ;;
      ERROR:*) [[ $attempt -le $retries ]] || return ;;
      *) return ;;
    esac
  done
}

if [[ "${MUTATION_BASELINE:-1}" != 0 ]]; then
  run_with_retries _baseline
  [[ "$verdict" == SURVIVED ]] ||
    die "the unmutated tests do not pass ($verdict $category: $detail); see $run_dir"
  [[ "$run_dir" == "$log_dir/_baseline" ]] ||
    printf 'baseline: passed on a retry; the first run failed with %s\n' "$(cut -f1-3 "$log_dir/_baseline/verdict")"
fi

survived=()
stale=()
errors=()
killed=0
printf 'id\tverdict\tcategory\tdetail\tlog\n' >"$log_dir/results.tsv"
while IFS=$'\t' read -r id file original mutated what; do
  [[ -z "$id" || "$id" == \#* ]] && continue
  if [[ $# -gt 0 ]] && [[ ! " $* " == *" $id "* ]]; then continue; fi
  git diff --quiet -- "$file" || die "$file has uncommitted changes"

  # Replace the original text, which must occur exactly once.
  if ! python3 -I - "$file" "$original" "$mutated" <<'PY'
import sys
path, old, new = (a.replace("\\n", "\n") for a in sys.argv[1:4])
text = open(path, encoding="utf-8").read()
if text.count(old) != 1:
    sys.exit(1)
open(path, "w", encoding="utf-8").write(text.replace(old, new))
PY
  then
    stale+=("$id")
    printf '%-28s STALE (original text not found exactly once in %s)\n' "$id" "$file"
    printf '%s\tSTALE\t\t%s\t\n' "$id" "$file" >>"$log_dir/results.tsv"
    continue
  fi
  current="$file"
  run_with_retries "$id"
  printf '%s\t%s\t%s\t%s\t%s\n' "$id" "$verdict" "$category" "$detail" "$run_dir" >>"$log_dir/results.tsv"
  case "$verdict" in
    KILLED)
      killed=$((killed + 1))
      printf '%-28s killed    %s\n' "$id" "$what"
      ;;
    SURVIVED)
      survived+=("$id")
      printf '%-28s SURVIVED  %s\n' "$id" "$what"
      ;;
    *)
      errors+=("$id")
      printf '%-28s ERROR     %s: %s (%s)\n' "$id" "$category" "$detail" "$run_dir"
      ;;
  esac
  restore
done < scripts/mutants.tsv

printf '\nkilled: %d, survived: %d, error: %d, stale: %d (logs: %s)\n' \
  "$killed" "${#survived[@]}" "${#errors[@]}" "${#stale[@]}" "$log_dir"
[[ ${#survived[@]} -eq 0 && ${#errors[@]} -eq 0 && ${#stale[@]} -eq 0 ]]
