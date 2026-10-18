#!/usr/bin/env bash
# Self-test of scripts/toolchain.sh with a fake verilator, run by `scripts/check.sh static` (#121).
# Checks what `check` prints and when it fails, where VLIW_VERILATOR_EXPECTED may override the
# pinned version, and that scripts/check.sh stops before sbt when the check fails. hw's
# ToolchainSpec checks that toolchain.sh and vliw.hw.Toolchain accept and refuse the same files.
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
[ "\${FAKE_VERILATOR:-}" = fail ] && { echo 'fake: cannot run' >&2; exit 1; }
echo "Verilator \${FAKE_VERILATOR:-$pinned} (fake)"
EOF
# FAKE_SBT_STATE: a file that the fake sbt creates when it runs.
cat >"$work/bin/sbt" <<'EOF'
#!/bin/sh
: >"$FAKE_SBT_STATE"
EOF
printf 'not a real verilator_bin\n' >"$work/bin/verilator_bin"
chmod +x "$work/bin/verilator" "$work/bin/verilator_bin" "$work/bin/sbt"
sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1
}
sum="$(sha256 "$work/bin/verilator_bin")"
# Where else the verilator script finds its verilator_bin: under VERILATOR_ROOT, or on the PATH when
# it has none next to it.
mkdir -p "$work/root/bin" "$work/wrapper" "$work/path"
printf 'the verilator_bin of VERILATOR_ROOT\n' >"$work/root/bin/verilator_bin"
cp "$work/bin/verilator" "$work/wrapper/verilator"
printf 'the verilator_bin on the PATH\n' >"$work/path/verilator_bin"
chmod +x "$work/root/bin/verilator_bin" "$work/path/verilator_bin"

# run [NAME=VALUE...] ARG...: runs toolchain.sh ARG... with the fake verilator first on the PATH,
# outside GitHub Actions and without VLIW_VERILATOR_EXPECTED, VERILATOR_ROOT or expect unless given;
# sets out and status.
run() {
  local vars=()
  while [[ $# -gt 0 && "$1" =~ ^[A-Za-z_]+= ]]; do
    vars+=("$1")
    shift
  done
  status=0
  out="$(env -u VLIW_VERILATOR_EXPECTED -u GITHUB_ACTIONS -u GITHUB_WORKFLOW_REF -u VERILATOR_ROOT \
    -u expect PATH="$work/bin:$PATH" ${vars[@]+"${vars[@]}"} "$repo_root/scripts/toolchain.sh" "$@" 2>&1)" ||
    status=$?
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
  "^verilator_bin sha256: $sum$" '^VERILATOR_ROOT: unset$'
run GITHUB_ACTIONS=true "$ci" check
expect 0 "^expected: verilator $pinned \\(pinned\\)$"

# The verilator_bin that the verilator script runs: under VERILATOR_ROOT when it is set, else on
# the PATH when there is none next to the script, else none.
run VERILATOR_ROOT="$work/root" check
expect 0 "^VERILATOR_ROOT: $work/root$" "^verilator_bin: $work/root/bin/verilator_bin$" \
  "^verilator_bin sha256: $(sha256 "$work/root/bin/verilator_bin")$"
run PATH="$work/wrapper:$work/path:$PATH" check
expect 0 "^verilator: $work/wrapper/verilator$" "^verilator_bin: $work/path/verilator_bin$" \
  "^verilator_bin sha256: $(sha256 "$work/path/verilator_bin")$"
run PATH="$work/wrapper:/usr/bin:/bin" check
expect 0 "^verilator: $work/wrapper/verilator$"
[[ -x /usr/bin/verilator_bin || -x /bin/verilator_bin ]] || expect 0 '^verilator_bin: not found$'

# Another version fails with how to fix it.
run FAKE_VERILATOR=5.999 check
expect 1 "^verilator --version: Verilator 5\\.999 \\(fake\\)$" \
  "^toolchain: the Verilator on the PATH is 5\\.999, expected $pinned \\(pinned\\)$" \
  '^toolchain: to fix: .* first on the PATH' '^toolchain: to try another version locally: .*VLIW_VERILATOR_EXPECTED'
# Pinned to a commit, the fix is a build with scripts/verilator-build.sh.
commit=5c5314b39cd888f427807d626e1502cbf222c292
printf 'verilator.version=%s\nverilator.apt=\nverilator.commit=%s\njdk.major=21\n' "$pinned" "$commit" \
  >"$work/source.properties"
run FAKE_VERILATOR=5.999 --file "$work/source.properties" check
expect 1 "^pinned: verilator $pinned \\(commit $commit\\), jdk 21 \\(" \
  "^toolchain: to fix: build it with 'scripts/verilator-build\\.sh $pinned $commit PREFIX' and put it first on the PATH"
run FAKE_VERILATOR=fail check
expect 1 '^verilator --version: failed: fake: cannot run$' \
  "^toolchain: no Verilator runs from the PATH, expected $pinned"
run FAKE_VERILATOR=fail VERILATOR_ROOT="$work/root" check
expect 1 "^toolchain: VERILATOR_ROOT=$work/root is set, so the verilator script runs the verilator_bin under"
run FAKE_VERILATOR=5.0200 check
expect 1 "^toolchain: verilator --version does not start with 'Verilator <version>', expected $pinned"
# The first line ends at a CR, as for vliw.hw.Toolchain.
run FAKE_VERILATOR=$'5.999\rx' check
expect 1 '^verilator --version: Verilator 5\.999$' \
  "^toolchain: the Verilator on the PATH is 5\\.999, expected $pinned"

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
# Only the option sets --expect, not a variable of the environment.
run FAKE_VERILATOR=5.999 expect=5.999 check
expect 1 "^expected: verilator $pinned \\(pinned\\)$"

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
run get 'verilator.commit jdk.major'
expect 2 '^toolchain: unknown key: verilator\.commit jdk\.major'
printf 'verilator.version=%s\nverilator.apt=%s-1\nverilator.commit=\njdk.major=21\njdk.major=21\n' "$pinned" "$pinned" \
  >"$work/dup.properties"
run --file "$work/dup.properties" check
expect 1 '^toolchain: .*/dup\.properties: line 5: duplicate key jdk\.major \(also line 4\)$'
[[ "$out" != *expected:* ]] || die 'check went on after a broken file'
run --file "$work/missing.properties" get jdk.major
expect 1 '^toolchain: cannot read '
# bash's read would drop the NUL and read 21.
printf 'verilator.version=%s\nverilator.apt=%s-1\nverilator.commit=\njdk.major=2\0001\n' "$pinned" "$pinned" \
  >"$work/nul.properties"
run --file "$work/nul.properties" get jdk.major
expect 1 '^toolchain: .*/nul\.properties: NUL byte$'

# scripts/check.sh runs the check before sbt, writes it to env.txt and stops when it fails.
check_sh() { # check_sh NAME=VALUE... -- ARG...: runs check.sh ARG... with the fakes; sets out and status
  local vars=()
  while [[ "$1" != -- ]]; do
    vars+=("$1")
    shift
  done
  shift
  rm -rf -- "$work/logs" "$work/sbt-ran"
  status=0
  out="$(env -u VLIW_VERILATOR_EXPECTED -u GITHUB_ACTIONS -u GITHUB_WORKFLOW_REF -u VERILATOR_ROOT \
    PATH="$work/bin:$PATH" SBT=sbt FAKE_SBT_STATE="$work/sbt-ran" CHECK_LOG_DIR="$work/logs" \
    ${vars[@]+"${vars[@]}"} "$repo_root/scripts/check.sh" "$@" 2>&1)" || status=$?
}
for vars in FAKE_VERILATOR=5.999 VLIW_VERILATOR_EXPECTED=5.999; do
  for args in 'sbt test' scala; do
    read -r -a args <<<"$args"
    check_sh "$vars" -- "${args[@]}"
    expect 1 '^check: scripts/toolchain\.sh check failed \(above\), so sbt did not run'
    [[ ! -e "$work/sbt-ran" ]] || die "check.sh ${args[*]} ran sbt with $vars"
    grep -q '^expected: verilator ' "$work/logs/env.txt" || die "env.txt has no check with $vars"
  done
done
check_sh -- sbt test
expect 0 "^expected: verilator $pinned \\(pinned\\)$"
[[ -e "$work/sbt-ran" ]] || die 'check.sh did not run sbt with the pinned Verilator'
grep -q "^expected: verilator $pinned (pinned)$" "$work/logs/env.txt" || die 'env.txt has no check'

echo 'toolchain.sh self-test passed'
