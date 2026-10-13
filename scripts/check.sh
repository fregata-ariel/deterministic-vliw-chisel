#!/usr/bin/env bash
# Single entry point for local checks and CI (.github/workflows/ci.yml).
# Usage: scripts/check.sh [static|scala|all]   (default: all)
# Set SBT to override the sbt command, e.g. SBT=/path/to/sbt scripts/check.sh scala
set -euo pipefail

die() { printf 'check: %s\n' "$*" >&2; exit 1; }
step() { printf '\n==> %s\n' "$*"; }

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$repo_root"

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
}

run_scala() {
  local sbt_cmd="${SBT:-sbt}"
  command -v "$sbt_cmd" >/dev/null 2>&1 || die "sbt is required (or set SBT): $sbt_cmd"

  step 'sbt: scalafmt check, compile (-Werror), test'
  "$sbt_cmd" scalafmtSbtCheck scalafmtCheckAll test
}

case "${1:-all}" in
  static) run_static ;;
  scala) run_scala ;;
  all) run_static; run_scala ;;
  *) die "unknown target: $1 (expected static, scala or all)" ;;
esac
