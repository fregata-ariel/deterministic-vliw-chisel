#!/usr/bin/env bash
# Checks the workflows against ADR-0020 (#121); run by `scripts/check.sh static`:
#   - no workflow under .github/workflows installs Verilator with apt, uses actions/setup-java or
#     has a java-version key: .github/actions/setup-toolchain installs both, with the versions of
#     project/toolchain.properties
#   - ci.yml (the required checks) uses that action, and with no `with:`, since its inputs choose a
#     candidate version
#   - VLIW_VERILATOR_EXPECTED and VLIW_VERILATOR_JOBS, which override the pinned toolchain, appear in
#     the tracked files only in the action, toolchain-candidates.yml and its script
#     (scripts/toolchain-candidates.py, whose timing step sets the expected version per run),
#     mutation.yml, the readers (scripts/toolchain.sh, vliw.hw.Toolchain), the self-tests and the
#     documentation
# The checks read the text and do not parse YAML. Comment lines are skipped (#, and // or * in
# Scala). A line continued with a backslash counts as one line with the next, and so do the lines of
# a folded block scalar (`run: >-`), which YAML joins with spaces.
# Usage: scripts/check-workflows.sh [ROOT]   (default: this checkout; the self-test passes a copy,
#        in a git work tree of its own)
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
  awk -v name="${file#"$root"/}" -v action="$action" -v q="'" '
    BEGIN {
      # apt or apt-get, then install, then the package verilator (or verilator=<version>), in one
      # command: not across ;, && or |.
      apt_re = "apt(-get)?[ \t]+([^;&|]*[ \t])?install[ \t]([^;&|]*[ \t\"" q "])?verilator([=: \t\"" q "]|$)"
      java_re = "(^|[ \t{,\"" q "])java-version(-file)?[\"" q "]?[ \t]*:"
    }
    function report(what) {
      printf "%s:%d: %s; %s installs Verilator and the JDK (ADR-0020): %s\n", name, start, what, action, logical
      bad = 1
    }
    function check(line) {
      line = tolower(logical)
      if (line ~ apt_re) report("installs Verilator with apt")
      if (line ~ /actions\/setup-java@/) report("uses actions/setup-java")
      if (line ~ java_re) report("sets java-version")
    }
    {
      # The lines of a folded block scalar, up to the first one indented at or below its key, are
      # one line; a blank line in it is a line break.
      if (fold) {
        if ($0 ~ /^[ \t]*$/) {
          if (logical != "") check()
          logical = ""
          next
        }
        match($0, /^ */)
        if (RLENGTH > fold_col) {
          if (logical == "") { logical = $0; start = FNR } else logical = logical " " substr($0, RLENGTH + 1)
          next
        }
        if (logical != "") check()
        fold = 0
      }
      # A comment line ends at its line break, also if it ends with a backslash.
      if (!cont && $0 ~ /^[ \t]*#/) next
      if (cont) { logical = logical " " $0 } else { logical = $0; start = FNR }
      cont = 0
      if (logical ~ /\\$/) { sub(/\\$/, "", logical); cont = 1; next }
      check()
      # A key whose value is a folded block scalar (>, >-, >+, with an indentation indicator).
      if ($0 ~ /:[ \t]+>[-+0-9]*[ \t]*(#.*)?$/) {
        match($0, /^ *(-[ \t]+)*/)
        fold = 1; fold_col = RLENGTH
        logical = ""
      }
    }
    END {
      if ((cont || fold) && logical != "") check()
      exit bad
    }
  ' "$file" || status=1
done

# Each step of ci.yml that uses the action: a list item ("-" at column dash) whose keys (at column
# keys: after "- ", or on the next lines when the "-" is alone) include `uses:` with the action
# must have no key `with`. Keys may be quoted. A step in flow style ({...}) that names the action is
# refused, since this does not read it.
ci="$dir/ci.yml"
[[ -f "$ci" ]] || die "no ${ci#"$root"/}"
awk -v name="${ci#"$root"/}" -v action="$action" -v q="'" '
  BEGIN {
    path = action
    gsub(/[.]/, "[.]", path)
    uses_re = "^uses[ \t]*:[ \t]*[\"" q "]?[.]/" path "/?[\"" q "]?[ \t]*(#.*)?$"
  }
  function finish() {
    if (inside && uses) {
      if (exact) found++
      if (with) {
        printf "%s:%d: the %s step has `with:`; the required checks install the pinned toolchain, and the inputs choose a candidate (ADR-0020)\n", name, with_line, action
        bad = 1
      }
    }
    inside = 0; uses = 0; exact = 0; with = 0
  }
  /^[ \t]*(#.*)?$/ { next }
  {
    match($0, /^ */); indent = RLENGTH
    if (inside && indent <= dash) finish()
    key = ""
    if (!inside && match($0, /^ *-([ \t]+|$)/)) {
      inside = 1; dash = indent
      if (RLENGTH == length($0)) keys = -1
      else { keys = RLENGTH; key = substr($0, keys + 1) }
    } else if (inside && keys < 0) {
      keys = indent; key = substr($0, keys + 1)
    } else if (inside && indent == keys) {
      key = substr($0, keys + 1)
    }
    # A quoted key: drop its two quotes.
    if (key ~ "^[\"" q "]") { key = substr(key, 2); sub("[\"" q "]", "", key) }
    if (key ~ /^\{/ && key ~ /setup-toolchain/) {
      printf "%s:%d: a step in flow style names %s; write it in block style, with no `with:` (ADR-0020)\n", name, FNR, action
      bad = 1
    }
    if (key ~ /^uses[ \t]*:/ && key ~ /setup-toolchain/) uses = 1
    if (key ~ uses_re) exact = 1
    if (key ~ /^with[ \t]*:/) { with = 1; with_line = FNR }
  }
  END {
    finish()
    if (!found) { printf "%s: no step uses ./%s (ADR-0020)\n", name, action; bad = 1 }
    exit bad
  }
' "$ci" || status=1

# The variables that override the pinned toolchain: only the action exports them (for a candidate),
# only toolchain-candidates.yml (and its script, which sets them for each timed run) and
# mutation.yml (which it calls) may run with them, and only the readers read them. Elsewhere, a
# required check could pass with another toolchain.
top="$(git -C "$root" rev-parse --show-toplevel 2>/dev/null)" || die "$root is not a git work tree"
[[ "$(cd "$top" && pwd -P)" == "$(cd "$root" && pwd -P)" ]] || die "$root is not the top of its git work tree"
allowed=(
  ':(exclude,glob).github/actions/setup-toolchain/**'
  ':(exclude).github/workflows/toolchain-candidates.yml'
  ':(exclude)scripts/toolchain-candidates.py'
  ':(exclude).github/workflows/mutation.yml'
  ':(exclude)scripts/toolchain.sh'
  ':(exclude)hw/src/test/scala/vliw/hw/Toolchain.scala'
  ':(exclude)hw/src/test/scala/vliw/hw/ToolchainSpec.scala'
  ':(exclude)scripts/check-workflows.sh'
  ':(exclude,glob)scripts/test-*'
  ':(exclude,glob)docs/**'
  ':(exclude,glob)**/*.md'
)
rc=0
lines="$(git -C "$root" grep -nI -E 'VLIW_VERILATOR_(EXPECTED|JOBS)' -- . "${allowed[@]}")" || rc=$?
((rc <= 1)) || die "git grep failed (exit $rc)"
if [[ -n "$lines" ]]; then
  awk '
    match($0, /^[^:]*:[0-9]+:/) {
      text = substr($0, RLENGTH + 1)
      if (text ~ /^[ \t]*(#|\/\/|\/?\*)/) next
      printf "%s names %s outside the action, toolchain-candidates.yml and its script, mutation.yml and the readers: only a run of the candidates may override the pinned toolchain (ADR-0020): %s\n", substr($0, 1, RLENGTH - 1), (text ~ /VLIW_VERILATOR_JOBS/ ? "VLIW_VERILATOR_JOBS" : "VLIW_VERILATOR_EXPECTED"), text
      bad = 1
    }
    END { exit bad }
  ' <<<"$lines" || status=1
fi
exit "$status"
