#!/usr/bin/env bash
# Evaluates one Verilator version for the catalog in docs/VERILATOR_VERSIONS.md (#85).
# Usage: scripts/verilator-catalog.sh VERILATOR_BIN_DIR OUT_DIR [STEP...]
#   VERILATOR_BIN_DIR  the directory of that version's `verilator`, e.g. /opt/verilator/5.030/bin
#                      or /usr/bin (put first on PATH for every step)
#   OUT_DIR            where the results go (earlier results of the steps that run are replaced)
#   STEP               any of the following, in this order by default:
#     env     versions of Verilator, g++, the JDK and sbt; CPUs and memory          -> env.txt
#     sbt     sbt test once, as in CI (items D, G, H, I)                            -> sbt/
#     nofix   sbt test CATALOG_NOFIX_RUNS times (default 2) without ADR-0013's jobs,
#             i.e. with svsim's default -j 0, on a copy of HEAD (item E)            -> nofix/
#     seed    $urandom with and without +verilator+seed+ (item F; seed 0 twice: random since 5.050)
#                                                                           -> seed.tsv
#     race    scripts/verilator-exit-race.py, CATALOG_RACE_RUNS runs (default 3000) per jobs
#             configuration, on CATALOG_RACE_DIR (items B, C)                       -> race.tsv
# CATALOG_RACE_JOBS lists the race's jobs configurations, separated by ';' (default: the race
# script's, "-j 0" and "--build-jobs 0 --verilate-jobs 1").
# CATALOG_RACE_DIR is a ChiselSim directory of the sbt step, relative to build/chiselsim/
# (default: RandomKernelSpec, its first test). The sbt and nofix steps keep scripts/check.sh's logs;
# summary.txt collects one line per finished step. Exits 1 if a step failed; the steps after it
# still run.
set -euo pipefail

die() { printf 'catalog: %s\n' "$*" >&2; exit 2; }

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$repo_root"

bin="${1:-}"
out="${2:-}"
[[ -n "$bin" && -n "$out" ]] || die 'usage: scripts/verilator-catalog.sh VERILATOR_BIN_DIR OUT_DIR [STEP...]'
[[ -x "$bin/verilator" ]] || die "no verilator in $bin"
shift 2
steps=("$@")
((${#steps[@]})) || steps=(env sbt nofix seed race)
for s in "${steps[@]}"; do
  case "$s" in env | sbt | nofix | seed | race) ;; *) die "unknown step: $s" ;; esac
done
nofix_runs="${CATALOG_NOFIX_RUNS:-2}"
race_runs="${CATALOG_RACE_RUNS:-3000}"
race_dir="${CATALOG_RACE_DIR:-}"
race_jobs=()
if [[ -n "${CATALOG_RACE_JOBS:-}" ]]; then IFS=';' read -r -a race_jobs <<<"$CATALOG_RACE_JOBS"; fi
export PATH="$bin:$PATH"
mkdir -p "$out"
out="$(cd "$out" && pwd -P)"
summary="$out/summary.txt"
touch "$summary"
failed=0

note() {
  printf '%s\t%s\n' "$1" "$2" >>"$summary"
  printf 'catalog: %s: %s\n' "$1" "$2"
}

# `%Warning-<CODE>` lines of the Verilator builds under DIR, counted per code.
warnings_of() {
  find "$1" -name compilation-log.txt -print0 2>/dev/null |
    xargs -0 -r grep -ohE '%Warning-[A-Z0-9_]+' 2>/dev/null | sort | uniq -c |
    awk '{ printf "%s%s=%s", sep, $2, $1; sep = "," }'
}

step_env() {
  {
    printf 'date (UTC): %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'commit: %s\n' "$(git rev-parse HEAD)"
    printf 'verilator: %s (%s)\n' "$(verilator --version)" "$(command -v verilator)"
    printf 'verilator_bin: %s\n' "$(ldd "$(dirname "$(command -v verilator)")/verilator_bin" 2>/dev/null |
      awk '/tcmalloc|jemalloc/ { print $1 }' | paste -sd, - | sed 's/^$/no tcmalloc or jemalloc/')"
    printf 'g++: %s\n' "$(g++ --version | head -n 1)"
    printf 'java: %s\n' "$(java -version 2>&1 | grep -m 1 version)"
    printf 'sbt: %s\n' "$(grep -m 1 sbt.version project/build.properties)"
    printf 'kernel: %s\n' "$(uname -sr)"
    printf 'os: %s\n' "$(sed -n 's/^PRETTY_NAME="\(.*\)"$/\1/p' /etc/os-release 2>/dev/null)"
    printf 'cpus: %s\n' "$(nproc)"
    printf 'memory: %s\n' "$(free -h | awk '/^Mem:/ { print $2 }')"
  } >"$out/env.txt"
  cat "$out/env.txt"
  note env "$(verilator --version)"
}

# Runs scripts/repeat-tests.sh full in DIR (a checkout) and notes the result.
sbt_runs() {
  local name="$1" dir="$2" runs="$3" status=0 builds
  rm -rf -- "$dir/build/chiselsim"
  (cd "$dir" && scripts/repeat-tests.sh full "$runs" "$out/$name") || status=$?
  builds="$(find "$dir/build/chiselsim" -name compilation-log.txt 2>/dev/null | wc -l)"
  note "$name" "$(awk -F'\t' 'NR > 1 { n++; if ($3 != "pass") f++; s += $4 }
      END { printf "%d of %d runs failed, %.0f s per run", f, n, s / n }' "$out/$name/summary.tsv"),\
 $builds builds in the last run, warnings: $(warnings="$(warnings_of "$dir/build/chiselsim")"; echo "${warnings:-none}")"
  # The errors of failed runs (Verilator, make, failed tests), for the record.
  grep -HE '%Error|make: \*\*\*|\*\*\* FAILED|\*\*\* ABORTED|Thread Pool' "$out/$name"/run-*/sbt.log 2>/dev/null |
    sed "s|^$out/$name/||" | head -n 200 >"$out/$name/errors.txt" || true
  # The Verilator command line of one build, to show the jobs options that were used.
  local makefile
  makefile="$(find "$dir/build/chiselsim" -path '*workdir-verilator/Makefile' -print -quit 2>/dev/null)"
  [[ -z "$makefile" ]] ||
    awk '/^simulation:/ { f = 1; next } f && !NF { exit } f' "$makefile" >"$out/$name/verilator-command.txt"
  ((status == 0)) || failed=1
}

step_sbt() {
  sbt_runs sbt "$repo_root" 1
}

step_nofix() {
  local copy="$out/nofix-checkout" sim=hw/src/test/scala/vliw/hw/VliwChiselSim.scala
  rm -rf -- "$copy"
  git worktree prune
  git worktree add --detach "$copy" HEAD >/dev/null
  # svsim's default jobs (-j 0): VliwChiselSim keeps the seed but no longer sets the jobs.
  # VliwChiselSimSpec checks those jobs, so it is left out of this copy.
  sed -i 's/verilator\.withParallelism(Some(SimVerilator\.parallelism))/verilator/' "$copy/$sim"
  grep -q 'case verilator: svsim.verilator.Backend.CompilationSettings =>' "$copy/$sim" ||
    die "the jobs override in $sim has changed; update step_nofix"
  if grep -q 'withParallelism' "$copy/$sim"; then die "could not remove the jobs override in $sim"; fi
  rm "$copy/hw/src/test/scala/vliw/hw/VliwChiselSimSpec.scala"
  sbt_runs nofix "$copy" "$nofix_runs"
  rm -rf -- "$copy"
  git worktree prune
}

step_seed() {
  local dir="$out/seed-build" line
  rm -rf -- "$dir"
  mkdir -p "$dir"
  cat >"$dir/urandom.sv" <<'SV'
module urandom;
  initial begin
    $display("%08x %08x %08x %08x", $urandom, $urandom, $urandom, $urandom);
    $finish;
  end
endmodule
SV
  if ! (cd "$dir" && verilator --binary --Mdir obj -o urandom urandom.sv) >"$dir/build.log" 2>&1; then
    note seed "build failed (see $dir/build.log)"
    failed=1
    return
  fi
  printf 'plusargs\trun\turandom x4\n' >"$out/seed.tsv"
  for args in '' '' '' '+verilator+seed+1' '+verilator+seed+1' '+verilator+seed+2' '+verilator+seed+0' '+verilator+seed+0'; do
    # shellcheck disable=SC2086 # the plusargs are split on purpose
    line="$("$dir/obj/urandom" $args 2>&1 | tr '\t' ' ' | grep -m 1 -E '^[0-9a-f]{8} ' ||
      { "$dir/obj/urandom" $args 2>&1 || true; } | tr '\t' ' ' | head -n 1)"
    printf '%s\t%s\t%s\n' "${args:-none}" "$(grep -c "^${args:-none}	" "$out/seed.tsv" | awk '{ print $1 + 1 }')" \
      "$line" >>"$out/seed.tsv"
  done
  cat "$out/seed.tsv"
  local none seed1
  none="$(awk -F'\t' '$1 == "none" { print $3 }' "$out/seed.tsv" | sort -u | wc -l)"
  seed1="$(awk -F'\t' '$1 == "+verilator+seed+1" { print $3 }' "$out/seed.tsv" | sort -u | wc -l)"
  note seed "without a seed: $( ((none == 1)) && echo 'the same values in every run' || echo 'different values per run'),\
 seed 1: $( ((seed1 == 1)) && echo 'the same values in every run' || echo 'different values per run')"
  rm -rf -- "$dir"
}

step_race() {
  local dir status=0
  if [[ -n "$race_dir" ]]; then
    dir="build/chiselsim/$race_dir"
  else
    dir="$(find build/chiselsim/RandomKernelSpec -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort | head -n 1)"
  fi
  [[ -n "$dir" && -f "$dir/workdir-verilator/Makefile" ]] ||
    die "no ChiselSim directory for the race step: '${dir:-build/chiselsim/RandomKernelSpec/*}' (run the sbt step first)"
  printf '%s\n' "$dir" >"$out/race-design.txt"
  VERILATOR_EXIT_RACE_TSV="$out/race.tsv" python3 -I scripts/verilator-exit-race.py "$dir" "$race_runs" \
    ${race_jobs[@]+"${race_jobs[@]}"} \
    >"$out/race.log" 2>&1 || status=$?
  tail -n 4 "$out/race.log"
  note race "$(awk -F'\t' 'NR > 2 { printf "%s[%s]: %s of %s pool aborts, %s other aborts, %s other errors, %s s/run, p=%s",
      sep, $1, $3, $2, $4, $5, $6, $7; sep = "; " }' "$out/race.tsv" 2>/dev/null)"
  ((status == 0)) || failed=1
}

for s in "${steps[@]}"; do
  sed -i "/^$s\t/d" "$summary"
  "step_$s"
done
printf '\n'
cat "$summary"
((failed == 0))
