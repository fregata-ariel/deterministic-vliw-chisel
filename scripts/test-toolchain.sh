#!/usr/bin/env bash
# Self-test of scripts/toolchain.sh with a fake verilator, run by `scripts/check.sh static` (#121).
# Checks what `check` prints and when it fails, and where VLIW_VERILATOR_EXPECTED may override the
# pinned version. hw's ToolchainSpec checks that toolchain.sh and vliw.hw.Toolchain accept and refuse
# the same files.
set -euo pipefail

die() { printf 'test-toolchain: %s\n' "$*" >&2; exit 1; }

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
mkdir -p "$work/bin"
pinned="$("$repo_root/scripts/toolchain.sh" get verilator.version)" ||
  die 'scripts/toolchain.sh cannot read project/toolchain.properties'

# FAKE_VERILATOR: the version that the fake reports (default: the pinned one); "fail" exits with 1.
cat >"$work/bin/verilator" <<EOF
#!/bin/sh
[ "\${FAKE_VERILATOR:-}" = fail ] && exit 1
echo "Verilator \${FAKE_VERILATOR:-$pinned} (fake)"
EOF
printf 'not a real verilator_bin\n' >"$work/bin/verilator_bin"
chmod +x "$work/bin/verilator" "$work/bin/verilator_bin"
sum="$(if command -v sha256sum >/dev/null 2>&1; then sha256sum "$work/bin/verilator_bin"; else
  shasum -a 256 "$work/bin/verilator_bin"; fi | cut -d' ' -f1)"

# run [NAME=VALUE...] ARG...: runs toolchain.sh ARG... with the fake verilator first on the PATH,
# outside GitHub Actions and without VLIW_VERILATOR_EXPECTED unless given; sets out and status.
run() {
  local vars=()
  while [[ $# -gt 0 && "$1" =~ ^[A-Z_]+= ]]; do
    vars+=("$1")
    shift
  done
  status=0
  out="$(env -u VLIW_VERILATOR_EXPECTED -u GITHUB_ACTIONS -u GITHUB_WORKFLOW_REF PATH="$work/bin:$PATH" \
    ${vars[@]+"${vars[@]}"} "$repo_root/scripts/toolchain.sh" "$@" 2>&1)" || status=$?
}
expect() { # expect STATUS PATTERN...: the exit status and lines that the output must contain
  local want="$1" pattern
  shift
  [[ "$status" == "$want" ]] || die "exit status $status, expected $want; output:"$'\n'"$out"
  for pattern in "$@"; do
    grep -qE -- "$pattern" <<<"$out" || die "missing /$pattern/ in the output:"$'\n'"$out"
  done
}
ci='GITHUB_WORKFLOW_REF=o/r/.github/workflows/ci.yml@refs/pull/1/merge'
candidates='GITHUB_WORKFLOW_REF=o/r/.github/workflows/toolchain-candidates.yml@refs/pull/1/merge'

# The pinned version: the fingerprint of the verilator on the PATH, also on GitHub Actions.
run check
expect 0 "^pinned: verilator $pinned \\((apt|commit) [^)]*\\), jdk [0-9]+ \\(project/toolchain\\.properties\\)$" \
  "^expected: verilator $pinned \\(pinned\\)$" "^verilator: $work/bin/verilator$" \
  "^verilator --version: Verilator $pinned \\(fake\\)$" "^verilator_bin: $work/bin/verilator_bin$" \
  "^verilator_bin sha256: $sum$"
run GITHUB_ACTIONS=true "$ci" check
expect 0 "^expected: verilator $pinned \\(pinned\\)$"

# Another version fails with how to fix it.
run FAKE_VERILATOR=5.999 check
expect 1 "^verilator --version: Verilator 5\\.999 \\(fake\\)$" \
  "^toolchain: the Verilator on the PATH is 5\\.999, expected $pinned \\(pinned\\)$" \
  '^toolchain: to fix: .* first on the PATH' '^toolchain: to try another version locally: .*VLIW_VERILATOR_EXPECTED'
run FAKE_VERILATOR=fail check
expect 1 "^toolchain: no Verilator runs from the PATH, expected $pinned"
run FAKE_VERILATOR=5.0200 check
expect 1 "^toolchain: verilator --version does not start with 'Verilator <version>', expected $pinned"

# VLIW_VERILATOR_EXPECTED, locally and in the candidates workflow, and --expect.
run FAKE_VERILATOR=5.999 VLIW_VERILATOR_EXPECTED=5.999 check
expect 0 '^expected: verilator 5\.999 \(env\)$'
run FAKE_VERILATOR=5.999 VLIW_VERILATOR_EXPECTED=5.999 GITHUB_ACTIONS= check
expect 0 '^expected: verilator 5\.999 \(env\)$'
run FAKE_VERILATOR=5.999 VLIW_VERILATOR_EXPECTED=5.999 GITHUB_ACTIONS=true "$candidates" check
expect 0 '^expected: verilator 5\.999 \(env\)$'
run VLIW_VERILATOR_EXPECTED=5.999 check
expect 1 "^toolchain: the Verilator on the PATH is $pinned, expected 5\\.999 \\(env\\)$" \
  '^toolchain: to fix: .*unset VLIW_VERILATOR_EXPECTED'
run FAKE_VERILATOR=5.999 check --expect 5.999
expect 0 '^expected: verilator 5\.999 \(--expect\)$'

# VLIW_VERILATOR_EXPECTED anywhere else on GitHub Actions, empty, or not a version is refused.
for vars in "GITHUB_ACTIONS=true $ci" GITHUB_ACTIONS=true \
  "GITHUB_ACTIONS=true GITHUB_WORKFLOW_REF=o/r/.github/workflows/my-toolchain-candidates.yml@refs/heads/x"; do
  read -r -a vars <<<"$vars"
  run FAKE_VERILATOR=5.999 VLIW_VERILATOR_EXPECTED=5.999 "${vars[@]}" check
  expect 1 '^toolchain: VLIW_VERILATOR_EXPECTED=5\.999 is set on GitHub Actions outside'
done
run VLIW_VERILATOR_EXPECTED= check
expect 1 '^toolchain: VLIW_VERILATOR_EXPECTED is empty'
run VLIW_VERILATOR_EXPECTED=5.99 check
expect 1 '^toolchain: VLIW_VERILATOR_EXPECTED=5\.99: expected digits, a dot and three digits$'
for args in '--expect' '--expect 5.99' '--expect 5.999 x' '-x'; do
  read -r -a args <<<"$args"
  run check "${args[@]}"
  expect 2 '^usage: '
done

# get, and a file that breaks the format.
run get verilator.version
expect 0 "^$pinned$"
run get jdk.major
expect 0 '^[0-9]+$'
run get verilator
expect 2 '^toolchain: unknown key: verilator'
printf 'verilator.version=%s\nverilator.apt=%s-1\nverilator.commit=\njdk.major=21\njdk.major=21\n' "$pinned" "$pinned" \
  >"$work/dup.properties"
run --file "$work/dup.properties" check
expect 1 '^toolchain: .*/dup\.properties: line 5: duplicate key jdk\.major \(also line 4\)$'
[[ "$out" != *expected:* ]] || die 'check went on after a broken file'
run --file "$work/missing.properties" get jdk.major
expect 1 '^toolchain: cannot read '

echo 'toolchain.sh self-test passed'
