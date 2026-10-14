#!/usr/bin/env bash
# Mutation check: every mutant in scripts/mutants.tsv must make the test suite fail.
# A mutant that survives means the tests cannot detect that kind of error.
# Usage: scripts/mutation-check.sh [mutant-id...]   (default: all mutants)
# Set SBT to override the sbt command. Target files must have no uncommitted changes.
set -euo pipefail

die() { printf 'mutation: %s\n' "$*" >&2; exit 2; }

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$repo_root"
sbt_cmd="${SBT:-sbt}"
command -v "$sbt_cmd" >/dev/null 2>&1 || die "sbt is required (or set SBT): $sbt_cmd"
command -v python3 >/dev/null 2>&1 || die 'python3 is required'

log_dir="$(mktemp -d)"
current=''
restore() { if [[ -n "$current" ]]; then git checkout -- "$current"; current=''; fi; }
trap restore EXIT

survived=()
stale=()
killed=0
while IFS=$'\t' read -r id file original mutated what; do
  [[ -z "$id" || "$id" == \#* ]] && continue
  if [[ $# -gt 0 ]] && [[ ! " $* " == *" $id "* ]]; then continue; fi
  git diff --quiet -- "$file" || die "$file has uncommitted changes"

  # Replace the original text, which must occur exactly once.
  if ! python3 -I - "$file" "$original" "$mutated" <<'PY'
import sys
path, old, new = sys.argv[1:4]
text = open(path, encoding="utf-8").read()
if text.count(old) != 1:
    sys.exit(1)
open(path, "w", encoding="utf-8").write(text.replace(old, new))
PY
  then
    stale+=("$id")
    printf '%-28s STALE (original text not found exactly once in %s)\n' "$id" "$file"
    continue
  fi
  current="$file"

  if "$sbt_cmd" test >"$log_dir/$id.log" 2>&1; then
    survived+=("$id")
    printf '%-28s SURVIVED  %s\n' "$id" "$what"
  else
    killed=$((killed + 1))
    printf '%-28s killed    %s\n' "$id" "$what"
  fi
  restore
done < scripts/mutants.tsv

printf '\nkilled: %d, survived: %d, stale: %d (logs: %s)\n' "$killed" "${#survived[@]}" "${#stale[@]}" "$log_dir"
[[ ${#survived[@]} -eq 0 && ${#stale[@]} -eq 0 ]]
