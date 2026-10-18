#!/usr/bin/env bash
# The toolchain versions pinned in project/toolchain.properties (ADR-0020, #121), and a check of
# the Verilator on the PATH against them. vliw.hw.Toolchain (hw tests) reads the same file in the
# same strict format; ToolchainSpec checks that both readers accept the same files and read the
# same values.
# Usage: scripts/toolchain.sh [--file PATH] get KEY
#          prints the value of KEY: verilator.version, verilator.apt, verilator.commit or jdk.major
#        scripts/toolchain.sh [--file PATH] check [--expect VERSION]
#          prints the pinned and the expected version, the verilator on the PATH, its version and
#          the sha256 of its verilator_bin; fails with how to fix it unless the version is the
#          expected one
# The expected version is --expect, else VLIW_VERILATOR_EXPECTED, else verilator.version. Like the
# tests, this accepts VLIW_VERILATOR_EXPECTED only outside GitHub Actions (GITHUB_ACTIONS empty) or
# in .github/workflows/toolchain-candidates.yml, and refuses it set anywhere else or empty, so that
# the required checks run with the pinned version only.
# --file reads another file in the same format instead of project/toolchain.properties.
set -euo pipefail
# Byte-wise matching, so that [a-z] takes in no other letters of the locale.
export LC_ALL=C

die() { printf 'toolchain: %s\n' "$*" >&2; exit 1; }
usage() {
  printf 'toolchain: %s\nusage: scripts/toolchain.sh [--file PATH] get KEY | check [--expect VERSION]\n' "$*" >&2
  exit 2
}

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
file="$repo_root/project/toolchain.properties"
keys=(verilator.version verilator.apt verilator.commit jdk.major)
line_re='^([a-z][a-z0-9.]*)=([A-Za-z0-9.+-]*)$'
version_re='^[0-9]+\.[0-9]{3}$'
candidates_workflow='/.github/workflows/toolchain-candidates.yml@'
version_form='digits, a dot and three digits'
# The first line of `verilator --version`, read as vliw.hw.Toolchain.versionOf reads it.
installed_re='^Verilator ([0-9]+\.[0-9]{3})( |$)'

# Reads $file into `values`, or dies with every error in it. The rules are those of vliw.hw.Toolchain.
declare -A values=()
load() {
  [[ -f "$file" && -r "$file" ]] || die "cannot read $file"
  local line key value n=0 errors=()
  local -A seen=()
  while IFS= read -r line || [[ -n "$line" ]]; do
    n=$((n + 1))
    if [[ "$line" == *$'\r'* ]]; then
      errors+=("line $n: CR")
    elif [[ "$line" == *$'\t'* ]]; then
      errors+=("line $n: tab")
    elif [[ "$line" == *' ' ]]; then
      errors+=("line $n: trailing space")
    elif [[ -z "$line" || "$line" == '#'* ]]; then
      :
    elif [[ "$line" =~ $line_re ]]; then
      key="${BASH_REMATCH[1]}" value="${BASH_REMATCH[2]}"
      if [[ " ${keys[*]} " != *" $key "* ]]; then
        errors+=("line $n: unknown key $key")
      elif [[ -n "${seen[$key]:-}" ]]; then
        errors+=("line $n: duplicate key $key (also line ${seen[$key]})")
      else
        seen[$key]=$n
        values[$key]="$value"
      fi
    else
      errors+=("line $n: expected an empty line, a comment starting with # or key=value")
    fi
  done <"$file"
  for key in "${keys[@]}"; do
    [[ -n "${seen[$key]:-}" ]] || errors+=("missing key $key")
  done
  if ((${#errors[@]} == 0)); then
    local version="${values[verilator.version]}" apt="${values[verilator.apt]}"
    local commit="${values[verilator.commit]}" jdk="${values[jdk.major]}"
    [[ "$version" =~ $version_re ]] || errors+=("verilator.version=$version: expected $version_form")
    if [[ -z "$apt" && -z "$commit" || -n "$apt" && -n "$commit" ]]; then
      errors+=('exactly one of verilator.apt and verilator.commit must be set')
    fi
    if [[ -n "$apt" && "$apt" != "$version"-?* ]]; then
      errors+=("verilator.apt=$apt: expected the package of verilator.version, $version-<revision>")
    fi
    if [[ -n "$commit" && ! "$commit" =~ ^[0-9a-f]{40}$ ]]; then
      errors+=("verilator.commit=$commit: expected 40 lowercase hexadecimal digits")
    fi
    [[ "$jdk" =~ ^[0-9]+$ ]] || errors+=("jdk.major=$jdk: expected digits")
  fi
  ((${#errors[@]} > 0)) || return 0
  for line in "${errors[@]}"; do printf 'toolchain: %s: %s\n' "$file" "$line" >&2; done
  exit 1
}

# The verilator_bin that the verilator script runs: under VERILATOR_ROOT (a build in the source tree),
# else next to the script (a package or `make install`), else on the PATH.
verilator_bin() {
  local root dir candidate
  root="$(verilator --getenv VERILATOR_ROOT 2>/dev/null || true)"
  dir="$(dirname "$(readlink -f "$1" 2>/dev/null || printf '%s' "$1")")"
  for candidate in "$root/bin/verilator_bin" "$dir/verilator_bin"; do
    if [[ "$candidate" == /* && -f "$candidate" && -x "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return
    fi
  done
  command -v verilator_bin || true
}

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1
}

check() {
  local pinned="${values[verilator.version]}" expected source how
  if [[ -n "${expect+set}" ]]; then
    expected="$expect" source='--expect'
  elif [[ -n "${VLIW_VERILATOR_EXPECTED+set}" ]]; then
    [[ -n "$VLIW_VERILATOR_EXPECTED" ]] ||
      die "VLIW_VERILATOR_EXPECTED is empty; unset it to expect the pinned Verilator $pinned"
    if [[ -n "${GITHUB_ACTIONS:-}" && "${GITHUB_WORKFLOW_REF:-}" != *"$candidates_workflow"* ]]; then
      die "VLIW_VERILATOR_EXPECTED=$VLIW_VERILATOR_EXPECTED is set on GitHub Actions outside" \
        ".github/workflows/toolchain-candidates.yml (GITHUB_WORKFLOW_REF=${GITHUB_WORKFLOW_REF:-});" \
        "the required checks run with the pinned Verilator $pinned only (ADR-0020)"
    fi
    [[ "$VLIW_VERILATOR_EXPECTED" =~ $version_re ]] ||
      die "VLIW_VERILATOR_EXPECTED=$VLIW_VERILATOR_EXPECTED: expected $version_form"
    expected="$VLIW_VERILATOR_EXPECTED" source=env
  else
    expected="$pinned" source=pinned
  fi
  how="apt ${values[verilator.apt]}"
  [[ -n "${values[verilator.apt]}" ]] || how="commit ${values[verilator.commit]}"
  printf 'pinned: verilator %s (%s), jdk %s (%s)\n' "$pinned" "$how" "${values[jdk.major]}" \
    "${file#"$repo_root"/}"
  printf 'expected: verilator %s (%s)\n' "$expected" "$source"

  local path out line installed bin sum
  path="$(command -v verilator || true)"
  if [[ -z "$path" ]]; then
    printf 'verilator: not on the PATH\n'
    installed=not-found
  else
    printf 'verilator: %s\n' "$path"
    if out="$(verilator --version 2>/dev/null)"; then
      line="${out%%$'\n'*}"
      printf 'verilator --version: %s\n' "$line"
      if [[ "$line" =~ $installed_re ]]; then
        installed="${BASH_REMATCH[1]}"
      else
        installed=unreadable
      fi
    else
      printf 'verilator --version: failed\n'
      installed=not-found
    fi
    bin="$(verilator_bin "$path")"
    if [[ -n "$bin" ]]; then
      sum="$(sha256 "$bin")" || sum=unknown
      printf 'verilator_bin: %s\nverilator_bin sha256: %s\n' "$bin" "$sum"
    else
      printf 'verilator_bin: not found\n'
    fi
  fi
  [[ "$installed" != "$expected" ]] || return 0

  local found="the Verilator on the PATH is $installed"
  [[ "$installed" != not-found ]] || found='no Verilator runs from the PATH'
  [[ "$installed" != unreadable ]] || found="verilator --version does not start with 'Verilator <version>'"
  {
    printf 'toolchain: %s, expected %s (%s)\n' "$found" "$expected" "$source"
    case "$source" in
      pinned)
        if [[ -n "${values[verilator.apt]}" ]]; then
          how="install it with 'sudo apt-get install verilator=${values[verilator.apt]}' (Ubuntu 24.04)"
        else
          how="build it from commit ${values[verilator.commit]}"
        fi
        printf 'toolchain: to fix: %s and put it first on the PATH (docs/toolchain/README.md).\n' "$how"
        printf 'toolchain: to try another version locally: put it first on the PATH and set %s\n' \
          'VLIW_VERILATOR_EXPECTED to it.'
        ;;
      env)
        printf 'toolchain: to fix: put Verilator %s first on the PATH, or unset %s to expect the pinned %s.\n' \
          "$expected" VLIW_VERILATOR_EXPECTED "$pinned"
        ;;
      *)
        printf 'toolchain: to fix: put Verilator %s first on the PATH, or leave out %s to expect the pinned %s.\n' \
          "$expected" --expect "$pinned"
        ;;
    esac
  } >&2
  return 1
}

if [[ "${1:-}" == --file ]]; then
  [[ $# -ge 2 ]] || usage '--file needs a path'
  file="$2"
  shift 2
fi
case "${1:-}" in
  get)
    [[ $# -eq 2 ]] || usage 'get needs one key'
    [[ " ${keys[*]} " == *" $2 "* ]] || usage "unknown key: $2 (expected ${keys[*]})"
    load
    printf '%s\n' "${values[$2]}"
    ;;
  check)
    shift
    if [[ $# -gt 0 ]]; then
      [[ $# -eq 2 && "$1" == --expect ]] || usage 'check takes only --expect VERSION'
      [[ "$2" =~ $version_re ]] || usage "--expect $2: expected $version_form"
      expect="$2"
    fi
    load
    check
    ;;
  *) usage "unknown command: '${1:-}'" ;;
esac
