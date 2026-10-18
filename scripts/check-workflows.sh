#!/usr/bin/env bash
# Checks the workflows against ADR-0020 (#121); run by `scripts/check.sh static`:
#   - no workflow under .github/workflows installs Verilator with apt, uses actions/setup-java or
#     has a java-version key: .github/actions/setup-toolchain installs both, with the versions of
#     project/toolchain.properties
#   - ci.yml (the required checks) uses that action, and with no `with:`, since its inputs choose a
#     candidate version
# The checks read the text and do not parse YAML: comment lines are skipped, and a line continued
# with a backslash counts as one line with the next.
# Usage: scripts/check-workflows.sh [ROOT]   (default: this checkout; the self-test passes a copy)
# Like the other scripts, this runs on bash 3.2 (macOS).
set -euo pipefail
# Byte-wise matching, as in scripts/toolchain.sh.
export LC_ALL=C

die() { printf 'check-workflows: %s\n' "$*" >&2; exit 1; }

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
root="${1:-$repo_root}"
dir="$root/.github/workflows"
[[ -d "$dir" ]] || die "no directory $dir"
action=.github/actions/setup-toolchain

status=0
for file in "$dir"/*.yml "$dir"/*.yaml; do
  [[ -f "$file" ]] || continue
  awk -v name="${file#"$root"/}" -v action="$action" '
    function report(what) {
      printf "%s:%d: %s; %s installs Verilator and the JDK (ADR-0020): %s\n", name, start, what, action, logical
      bad = 1
    }
    {
      if (cont) { logical = logical " " $0 } else { logical = $0; start = FNR }
      cont = 0
      if (logical ~ /\\$/) { sub(/\\$/, "", logical); cont = 1; next }
      if (logical ~ /^[ \t]*#/) next
      line = tolower(logical)
      if (line ~ /apt(-get)?[ \t].*install.*verilator/) report("installs Verilator with apt")
      if (line ~ /uses:.*actions\/setup-java@/) report("uses actions/setup-java")
      if (line ~ /(^|[ \t{,"])java-version(-file)?"?[ \t]*:/) report("sets java-version")
    }
    END { exit bad }
  ' "$file" || status=1
done

# Each step of ci.yml that uses the action: a list item ("- " at column dash) whose keys (at column
# keys) include `uses: ./.github/actions/setup-toolchain` must have no key `with`.
ci="$dir/ci.yml"
[[ -f "$ci" ]] || die "no ${ci#"$root"/}"
awk -v name="${ci#"$root"/}" -v action="$action" -v q="'" '
  BEGIN {
    path = action
    gsub(/[.]/, "[.]", path)
    uses_re = "^uses:[ \t]*[\"" q "]?[.]/" path "/?[\"" q "]?[ \t]*(#.*)?$"
  }
  function finish() {
    if (inside && uses) {
      found++
      if (with) {
        printf "%s:%d: the %s step has `with:`; the required checks install the pinned toolchain, and the inputs choose a candidate (ADR-0020)\n", name, with_line, action
        bad = 1
      }
    }
    inside = 0; uses = 0; with = 0
  }
  /^[ \t]*(#.*)?$/ { next }
  {
    match($0, /^ */); indent = RLENGTH
    if (inside && indent <= dash) finish()
    key = ""
    if (!inside && match($0, /^ *- +/)) {
      inside = 1; dash = indent; keys = RLENGTH; key = substr($0, keys + 1)
    } else if (inside && indent == keys) {
      key = substr($0, keys + 1)
    }
    if (key ~ uses_re) uses = 1
    if (key ~ /^with[ \t]*:/) { with = 1; with_line = FNR }
  }
  END {
    finish()
    if (!found) { printf "%s: no step uses ./%s (ADR-0020)\n", name, action; bad = 1 }
    exit bad
  }
' "$ci" || status=1
exit "$status"
