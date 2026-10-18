#!/usr/bin/env bash
# Self-test of the installation in one place (#121), run by `scripts/check.sh static`:
# scripts/check-workflows.sh on copies of .github with a line added, the plan and verify steps of
# .github/actions/setup-toolchain (setup-toolchain.sh) with fake tools, and scripts/verilator-build.sh's
# arguments and commit check with a fake git (no network, no build).
set -euo pipefail

die() { printf 'test-setup-toolchain: %s\n' "$*" >&2; exit 1; }

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
step="$repo_root/.github/actions/setup-toolchain/setup-toolchain.sh"
pinned="$("$repo_root/scripts/toolchain.sh" get verilator.version)" ||
  die 'scripts/toolchain.sh cannot read project/toolchain.properties'
pinned_apt="$("$repo_root/scripts/toolchain.sh" get verilator.apt)"
jdk="$("$repo_root/scripts/toolchain.sh" get jdk.major)"

expect() { # expect STATUS PATTERN...: the exit status and lines that the output must contain
  local want="$1" pattern
  shift
  [[ "$status" == "$want" ]] || die "exit status $status, expected $want; output:"$'\n'"$out"
  for pattern in "$@"; do
    grep -qE -- "$pattern" <<<"$out" || die "missing /$pattern/ in the output:"$'\n'"$out"
  done
}

# --- scripts/check-workflows.sh -----------------------------------------------------------------
# workflows [FILE AWK-PROGRAM]: checks a fresh copy of .github, with FILE (under .github/workflows)
# rewritten by the awk program; sets out and status.
workflows() {
  rm -rf -- "$work/root"
  mkdir -p "$work/root"
  cp -R "$repo_root/.github" "$work/root/"
  if [[ $# -gt 0 ]]; then
    awk "$2" "$work/root/.github/workflows/$1" >"$work/edited"
    cmp -s "$work/edited" "$work/root/.github/workflows/$1" && die "the edit of $1 changed nothing: $2"
    mv "$work/edited" "$work/root/.github/workflows/$1"
  fi
  status=0
  out="$("$repo_root/scripts/check-workflows.sh" "$work/root" 2>&1)" || status=$?
}
uses='/^ *- uses: \.\/\.github\/actions\/setup-toolchain$/'
workflows
expect 0
# Installing Verilator with apt, in one line or continued, and setting up a JDK, in any workflow.
workflows repeat-tests.yml '{ print } END { print "      - run: sudo apt-get update && sudo apt-get install -y verilator" }'
expect 1 '^\.github/workflows/repeat-tests\.yml:[0-9]+: installs Verilator with apt; '
workflows mutation.yml '{ print } END { print "      - run: |"; print "          sudo apt-get install -y \\"; print "            verilator=5.020-1" }'
expect 1 '^\.github/workflows/mutation\.yml:[0-9]+: installs Verilator with apt; '
workflows ci.yml '{ print } END { print "      - run: sudo apt -y install Verilator" }'
expect 1 '^\.github/workflows/ci\.yml:[0-9]+: installs Verilator with apt; '
workflows mutation.yml '{ print } END { print "      - uses: actions/setup-java@v5"; print "        with:"; print "          java-version: \"21\"" }'
expect 1 '^\.github/workflows/mutation\.yml:[0-9]+: uses actions/setup-java; ' \
  '^\.github/workflows/mutation\.yml:[0-9]+: sets java-version; '
workflows repeat-tests.yml '{ print } /^    strategy:$/ { print "      matrix: { java-version: [21] }" }'
expect 1 '^\.github/workflows/repeat-tests\.yml:[0-9]+: sets java-version; '
# Comments and other packages are not installs of Verilator.
workflows ci.yml '{ print } END { print "      # not: sudo apt-get install -y verilator"; print "      - run: sudo apt-get install -y jq" }'
expect 0
# ci.yml's setup-toolchain step: no `with:` before or after `uses:`, and the step must be there.
workflows ci.yml "{ print } $uses { print \"        with:\"; print \"          verilator: 5.052\" }"
expect 1 '^\.github/workflows/ci\.yml:[0-9]+: the \.github/actions/setup-toolchain step has .with:.'
workflows ci.yml "$uses { print \"      - name: Toolchain\"; print \"        with: { verilator: apt:5.020-1 }\"; print \"        uses: \\\"./.github/actions/setup-toolchain/\\\" # candidate\"; next } { print }"
expect 1 '^\.github/workflows/ci\.yml:[0-9]+: the \.github/actions/setup-toolchain step has .with:.'
workflows ci.yml "$uses { next } { print }"
expect 1 '^\.github/workflows/ci\.yml: no step uses \./\.github/actions/setup-toolchain'
# A `with:` of the next step, or nested deeper in this one, is not this step's.
workflows ci.yml "{ print } $uses { print \"        env:\"; print \"          with: x\"; print \"      - uses: actions/cache@v5\"; print \"        with:\"; print \"          path: x\" }"
expect 0

# --- setup-toolchain.sh plan ----------------------------------------------------------------------
ci='GITHUB_WORKFLOW_REF=o/r/.github/workflows/ci.yml@refs/pull/1/merge'
candidates='GITHUB_WORKFLOW_REF=o/r/.github/workflows/toolchain-candidates.yml@refs/pull/1/merge'
commit=ea338be98e1e838d3518809ce8899f85a009963c
build_sha="$(sha256sum "$repo_root/scripts/verilator-build.sh" 2>/dev/null || shasum -a 256 "$repo_root/scripts/verilator-build.sh")"
build_sha="${build_sha%% *}"
# plan [--file PATH] [NAME=VALUE...]: runs the plan step; sets out, status, outputs (GITHUB_OUTPUT)
# and exported (GITHUB_ENV).
plan() {
  local file=()
  if [[ "${1:-}" == --file ]]; then
    file=(--file "$2")
    shift 2
  fi
  : >"$work/output"
  : >"$work/env"
  status=0
  out="$(env -u INPUT_VERILATOR -u INPUT_COMMIT -u GITHUB_ACTIONS -u VLIW_VERILATOR_EXPECTED \
    GITHUB_OUTPUT="$work/output" GITHUB_ENV="$work/env" HOME="$work/home" ImageOS=ubuntu24 "$ci" \
    "$@" "$step" ${file[@]+"${file[@]}"} plan 2>&1)" || status=$?
  outputs="$(cat "$work/output")" exported="$(cat "$work/env")"
}
has() { # has VARIABLE LINE...: the outputs or exported must contain each line
  local name="$1" line
  shift
  for line in "$@"; do
    grep -qxF -- "$line" <<<"${!name}" || die "missing '$line' in $name:"$'\n'"${!name}"$'\n'"output: $out"
  done
}
# The pinned toolchain, in any workflow; nothing exported.
plan
expect 0 "^setup-toolchain: Verilator $pinned from apt $pinned_apt \\(pinned; pinned $pinned\\), JDK $jdk$"
has outputs method=apt "version=$pinned" "apt=$pinned_apt" commit= prefix= key= "jdk=$jdk" \
  "id=$pinned-apt-$pinned_apt"
[[ -z "$exported" ]] || die "exported for the pinned toolchain: $exported"
# Pinned to a commit: built from the source, cached under a key of the version, the commit, the
# image and the build script.
printf 'verilator.version=%s\nverilator.apt=\nverilator.commit=%s\njdk.major=%s\n' "$pinned" "$commit" "$jdk" \
  >"$work/source.properties"
plan --file "$work/source.properties"
expect 0 "^setup-toolchain: Verilator $pinned from source $commit \\(pinned"
has outputs method=source "commit=$commit" "prefix=$work/home/.cache/vliw-toolchain/verilator-$pinned-ea338be98e1e" \
  "key=verilator-$pinned-ea338be98e1e-ubuntu24-$build_sha" "id=$pinned-ea338be98e1e"
plan --file "$work/source.properties" ImageOS=
expect 1 '^setup-toolchain: ImageOS is not set'
# Inputs anywhere but in toolchain-candidates.yml fail, however they are given.
for vars in "INPUT_VERILATOR=5.052 INPUT_COMMIT=$commit" INPUT_VERILATOR=apt:5.020-1 "INPUT_COMMIT=$commit" \
  "INPUT_VERILATOR=5.052 GITHUB_WORKFLOW_REF=o/r/.github/workflows/mutation.yml@refs/pull/1/merge" \
  "INPUT_VERILATOR=5.052 GITHUB_WORKFLOW_REF=o/r/.github/workflows/my-toolchain-candidates.yml@refs/heads/x" \
  "INPUT_VERILATOR=5.052 GITHUB_WORKFLOW_REF="; do
  read -r -a vars <<<"$vars"
  plan "${vars[@]}"
  expect 1 '^setup-toolchain: the inputs verilator .* are accepted only in \.github/workflows/toolchain-candidates\.yml'
  [[ -z "$outputs$exported" ]] || die "wrote outputs for refused inputs: $outputs $exported"
done
# A candidate in toolchain-candidates.yml: from the source at the commit, or from apt.
plan "$candidates" INPUT_VERILATOR=5.052 INPUT_COMMIT="$commit"
expect 0 '^setup-toolchain: Verilator 5\.052 from source ea338be98e1e838d3518809ce8899f85a009963c \(input; pinned' \
  '^setup-toolchain: VLIW_VERILATOR_EXPECTED=5\.052 for the later steps$'
has outputs method=source version=5.052 "key=verilator-5.052-ea338be98e1e-ubuntu24-$build_sha" id=5.052-ea338be98e1e
has exported VLIW_VERILATOR_EXPECTED=5.052
plan "$candidates" INPUT_VERILATOR=apt:5.032-1
expect 0
has outputs method=apt version=5.032 apt=5.032-1 commit= id=5.032-apt-5.032-1
has exported VLIW_VERILATOR_EXPECTED=5.032
plan "$candidates" INPUT_VERILATOR="apt:$pinned_apt"
expect 0
has outputs method=apt "version=$pinned"
[[ -z "$exported" ]] || die "exported for the pinned version from the input: $exported"
plan "$candidates" INPUT_VERILATOR=5.052
expect 1 "^setup-toolchain: verilator=5\\.052 is built from the source: the input commit \\(''\\) must be"
plan "$candidates" INPUT_VERILATOR=5.052 INPUT_COMMIT=EA338BE98E1E838D3518809CE8899F85A009963C
expect 1 '^setup-toolchain: verilator=5\.052 is built from the source: the input commit'
for verilator in 5.52 v5.052 apt:5.020 apt:5.020- 'apt:5.020-1 x' 'apt:5.020-1;x'; do
  plan "$candidates" INPUT_VERILATOR="$verilator"
  expect 1 '^setup-toolchain: verilator=.*: expected '
done
plan "$candidates" INPUT_VERILATOR=apt:5.020-1 INPUT_COMMIT="$commit"
expect 1 '^setup-toolchain: verilator=apt:5\.020-1 installs a package: leave out the input commit'
plan "$candidates" INPUT_COMMIT="$commit"
expect 1 '^setup-toolchain: the input commit .* needs the input verilator'

# --- setup-toolchain.sh verify --------------------------------------------------------------------
# A fake Verilator in a prefix (FAKE_VERILATOR: its version), another one elsewhere, and a fake java
# (FAKE_JAVA: its version).
mkdir -p "$work/prefix/bin" "$work/other/bin" "$work/java/bin"
cat >"$work/prefix/bin/verilator" <<EOF
#!/bin/sh
echo "Verilator \${FAKE_VERILATOR:-$pinned} (fake)"
EOF
printf 'not a real verilator_bin\n' >"$work/prefix/bin/verilator_bin"
cp "$work/prefix/bin/verilator" "$work/prefix/bin/verilator_bin" "$work/other/bin/"
printf 'result: success\n' >"$work/prefix/build-info.txt"
# The JVM prints JAVA_TOOL_OPTIONS before the version when that is set.
cat >"$work/java/bin/java" <<'EOF'
#!/bin/sh
echo 'Picked up JAVA_TOOL_OPTIONS: -Dfake="version "1"' >&2
echo "openjdk version \"${FAKE_JAVA}\" 2025-07-15 LTS" >&2
echo 'OpenJDK Runtime Environment Temurin (fake)' >&2
EOF
chmod +x "$work"/*/bin/*
# verify [NAME=VALUE...]: runs the verify step for the prefix's Verilator built from the source;
# sets out and status.
verify() {
  : >"$work/summary"
  status=0
  out="$(env -u VLIW_VERILATOR_EXPECTED -u VERILATOR_ROOT -u GITHUB_ACTIONS -u GITHUB_WORKFLOW_REF \
    PATH="$work/prefix/bin:$work/java/bin:$PATH" METHOD=source VERSION="$pinned" APT= COMMIT="$commit" \
    PREFIX="$work/prefix" KEY=verilator-key JDK="$jdk" CACHE_HIT=true FAKE_JAVA="$jdk.0.8" \
    GITHUB_STEP_SUMMARY="$work/summary" "$@" "$step" verify 2>&1)" || status=$?
}
verify
expect 0 "^setup-toolchain: Verilator $pinned from source $commit, JDK $jdk$" \
  "^expected: verilator $pinned \\(--expect\\)$" "^verilator: $work/prefix/bin/verilator$" \
  "^verilator_bin: $work/prefix/bin/verilator_bin$" '^cache: restored \(key verilator-key\)$' \
  '^build-info: result: success$' "^java: $work/java/bin/java$" "^java -version: openjdk version \"$jdk\\.0\\.8\""
if ! grep -qx '### Toolchain' "$work/summary" || ! grep -qx "verilator: $work/prefix/bin/verilator" "$work/summary"; then
  die "no fingerprint in GITHUB_STEP_SUMMARY:"$'\n'"$(cat "$work/summary")"
fi
verify CACHE_HIT=
expect 0 '^cache: built here \(key verilator-key\)$'
# The installed Verilator of another version, or not first on the PATH.
verify FAKE_VERILATOR=5.999
expect 1 "^setup-toolchain: scripts/toolchain\\.sh check --expect $pinned failed"
grep -qF -- "- **scripts/toolchain.sh check --expect $pinned failed" "$work/summary" ||
  die "the problem is not in GITHUB_STEP_SUMMARY:"$'\n'"$(cat "$work/summary")"
verify PATH="$work/other/bin:$work/prefix/bin:$work/java/bin:$PATH"
expect 1 "^setup-toolchain: the verilator on the PATH is '$work/other/bin/verilator', not $work/prefix/bin/verilator$"
verify METHOD=apt APT="$pinned_apt"
expect 1 "^setup-toolchain: the verilator on the PATH is '$work/prefix/bin/verilator', not /usr/bin/verilator$"
# Another JDK, an old one ("1.8.0"), or none.
for java in 17.0.2 1.8.0_412; do
  verify FAKE_JAVA="$java"
  expect 1 "^setup-toolchain: java -version is 'openjdk version \"$java\" 2025-07-15 LTS' \\($work/java/bin/java\\), not JDK $jdk$"
done
verify PATH="$work/prefix/bin:/usr/bin:/bin" JAVA_HOME=
[[ -x /usr/bin/java || -x /bin/java ]] || expect 1 '^setup-toolchain: java -version is .*\(no java on the PATH\), not JDK'
# A candidate of another version needs VLIW_VERILATOR_EXPECTED for the later steps; the pinned
# version needs it unset (or the same).
verify FAKE_VERILATOR=5.999 VERSION=5.999
expect 1 "^setup-toolchain: VLIW_VERILATOR_EXPECTED is '', not the installed candidate 5\\.999$"
verify FAKE_VERILATOR=5.999 VERSION=5.999 VLIW_VERILATOR_EXPECTED=5.999
expect 0 '^expected: verilator 5\.999 \(--expect\)$' '^VLIW_VERILATOR_EXPECTED: 5\.999$'
verify VLIW_VERILATOR_EXPECTED=5.999
expect 1 "^setup-toolchain: VLIW_VERILATOR_EXPECTED=5\\.999 is set, but the pinned $pinned was installed$"
verify VLIW_VERILATOR_EXPECTED="$pinned"
expect 0

# --- scripts/verilator-build.sh -------------------------------------------------------------------
build=("$repo_root/scripts/verilator-build.sh")
for args in '5.020 x' "5.02 $commit $work/vb" "5.020 ${commit}0 $work/vb" "5.020 EA338BE98E1E838D3518809CE8899F85A009963C $work/vb" \
  "5.020 $commit $work/vb $work/vb.txt x"; do
  read -r -a args <<<"$args"
  status=0
  out="$("${build[@]}" "${args[@]}" 2>&1)" || status=$?
  expect 2 '^verilator-build: '
done
# A fake git: clone creates the directory, and rev-parse prints FAKE_HEAD.
mkdir -p "$work/git/bin"
cat >"$work/git/bin/git" <<'EOF'
#!/bin/sh
case " $* " in
  *" clone "*) for last; do :; done; mkdir -p "$last" ;;
  *" rev-parse HEAD "*) echo "$FAKE_HEAD" ;;
  *) echo "fake git: $*" >&2; exit 99 ;;
esac
EOF
chmod +x "$work/git/bin/git"
status=0
out="$(PATH="$work/git/bin:$PATH" FAKE_HEAD=8ff77e9d47351b0a59114929880687839a51840b \
  "${build[@]}" 5.020 "$commit" "$work/vb/prefix" 2>&1)" || status=$?
expect 1 "^result: failed \\(the tag v5\\.020 is at commit 8ff77e9d47351b0a59114929880687839a51840b, not $commit: was the tag moved\\?\\)$" \
  '^tag commit: 8ff77e9d47351b0a59114929880687839a51840b$' '^verilator: n/a$'
[[ ! -e "$work/vb/prefix" ]] || die 'verilator-build.sh installed something after a commit mismatch'
grep -qx "commit: $commit" "$work/vb/prefix.build-info.txt" || die 'no build-info.txt next to PREFIX'

echo 'setup-toolchain self-test passed'
