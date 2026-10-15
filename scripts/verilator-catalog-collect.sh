#!/usr/bin/env bash
# Gathers the results of one variant from the jobs of .github/workflows/verilator-catalog.yml (#85).
# Usage: scripts/verilator-catalog-collect.sh ARTIFACTS_DIR VARIANT DEST_DIR
#   ARTIFACTS_DIR  the downloaded artifacts, one directory per artifact:
#                  catalog-VARIANT-sbt, catalog-VARIANT-nofix, catalog-VARIANT-race-<shard> (each the
#                  OUT_DIR of scripts/verilator-catalog.sh plus runner.txt) and verilator-VERSION
#                  (scripts/verilator-build.sh's build-info.txt), when the variant was built from source
#   VARIANT        e.g. 5.030, or 5.020-apt for Debian's package
#   DEST_DIR       e.g. docs/verilator-versions/5.030 (replaced)
# Writes the small result files (no logs) into DEST_DIR, adds up the race shards with
# scripts/verilator-exit-race.py --merge (also per CPU model of the runners, race-by-cpu.tsv), and
# writes summary.txt with one line per step. CATALOG_PARTS names the parts that the run measured
# (default "sbt nofix race"); a missing one is reported as missing.
set -euo pipefail

die() { printf 'collect: %s\n' "$*" >&2; exit 2; }

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
art="${1:-}"
variant="${2:-}"
dest="${3:-}"
[[ -d "$art" && -n "$variant" && -n "$dest" ]] ||
  die 'usage: scripts/verilator-catalog-collect.sh ARTIFACTS_DIR VARIANT DEST_DIR'
rm -rf -- "$dest"
mkdir -p "$dest"
summary="$dest/summary.txt"
: >"$summary"

# Copies FILE from artifact directory DIR to DEST/NAME, if it exists.
take() {
  [[ -f "$1/$2" ]] && cp "$1/$2" "$dest/$3"
  return 0
}
# The line of STEP in the summary.txt of artifact directory DIR.
line_of() {
  [[ -f "$1/summary.txt" ]] && grep -m 1 "^$2	" "$1/summary.txt"
  return 0
}

parts=" ${CATALOG_PARTS:-sbt nofix race} "
version="${variant%-apt}"
if [[ -d "$art/verilator-$version" && "$variant" != *-apt ]]; then
  take "$art/verilator-$version" build-info.txt build-info.txt
  printf 'build\t%s\n' "$(grep -E '^(result|seconds|compiler warnings):' "$dest/build-info.txt" |
    sed 's/: /=/' | paste -sd, -)" >>"$summary"
fi

sbt="$art/catalog-$variant-sbt"
if [[ -d "$sbt" ]]; then
  take "$sbt" env.txt env.txt
  take "$sbt" runner.txt sbt-runner.txt
  take "$sbt" sbt/summary.tsv sbt-summary.tsv
  take "$sbt" sbt/verilator-command.txt sbt-verilator-command.txt
  take "$sbt" sbt/errors.txt sbt-errors.txt
  take "$sbt" seed.tsv seed.tsv
  {
    line_of "$sbt" env
    line_of "$sbt" sbt
    line_of "$sbt" seed
  } >>"$summary"
elif [[ "$parts" == *" sbt "* ]]; then
  printf 'sbt\tmissing (the job failed before it uploaded its results)\n' >>"$summary"
fi

nofix="$art/catalog-$variant-nofix"
if [[ -d "$nofix" ]]; then
  take "$nofix" runner.txt nofix-runner.txt
  take "$nofix" nofix/summary.tsv nofix-summary.tsv
  take "$nofix" nofix/verilator-command.txt nofix-verilator-command.txt
  take "$nofix" nofix/errors.txt nofix-errors.txt
  line_of "$nofix" nofix >>"$summary"
elif [[ "$parts" == *" nofix "* ]]; then
  printf 'nofix\tmissing (the job failed before it uploaded its results)\n' >>"$summary"
fi

shards=()
for dir in "$art/catalog-$variant-race-"*/; do
  [[ -f "$dir/race.tsv" ]] || continue
  shard="${dir%/}"
  shard="${shard##*-race-}"
  shards+=("$shard")
  mkdir -p "$dest/race-shards"
  cp "$dir/race.tsv" "$dest/race-shards/$shard.tsv"
  take "$dir" runner.txt "race-shards/$shard-runner.txt"
  take "$dir" race-design.txt race-design.txt
  # One line per abort or error, and the first message of each kind.
  grep -E '^run [0-9]+ \[' "$dir/race.log" 2>/dev/null | sed "s/^/shard $shard: /" >>"$dest/race-events.txt" || true
  awk '/^first /{ f = 1 } f' "$dir/race.log" 2>/dev/null | sed "s/^/[shard $shard] /" >>"$dest/race-first-messages.txt" || true
done
if ((${#shards[@]})); then
  python3 -I "$repo_root/scripts/verilator-exit-race.py" --merge "$dest/race.tsv" "$dest"/race-shards/*.tsv >/dev/null
  # Per CPU model of the runners: the abort rate depends on it (#85).
  for shard in "${shards[@]}"; do
    cpu="$(sed -n 's/^cpu: //p' "$dest/race-shards/$shard-runner.txt" 2>/dev/null)"
    awk -F'\t' -v cpu="${cpu:-unknown}" 'NR > 2 { print cpu "\t" $1 "\t" $2 "\t" $3 "\t" $4 "\t" $5 }' \
      "$dest/race-shards/$shard.tsv"
  done | awk -F'\t' '{ k = $1 "\t" $2; s[k]++; n[k] += $3; p[k] += $4; a[k] += $5; e[k] += $6 }
    END {
      print "cpu\tjobs\tshards\truns\tpool aborts\tother aborts\tother errors"
      for (k in s) print k "\t" s[k] "\t" n[k] "\t" p[k] "\t" a[k] "\t" e[k]
    }' | { IFS= read -r header; printf '%s\n' "$header"; sort; } >"$dest/race-by-cpu.tsv"
  printf 'race\t%s shards: %s\n' "${#shards[@]}" "$(awk -F'\t' 'NR > 2 { printf "%s[%s]: %s of %s pool aborts, %s other aborts, %s other errors, %s s/run, p=%s",
      sep, $1, $3, $2, $4, $5, $6, $7; sep = "; " }' "$dest/race.tsv")" >>"$summary"
else
  printf 'race\tmissing (no shard uploaded its results)\n' >>"$summary"
fi
cat "$summary"
