#!/usr/bin/env bash
# Self-test of the installation in one place (#121), run by `scripts/check.sh static`:
# scripts/check-workflows.sh on copies of .github with a line or a file added, the plan and verify
# steps of .github/actions/setup-toolchain (setup-toolchain.sh) with fake tools, and
# scripts/verilator-build.sh's arguments, commit check and install with fake git, autoconf,
# configure and make (no network, no build).
# The plan and verify cases read files of their own, pinned to 5.020 with apt or with a commit, so
# that changing the pin of project/toolchain.properties stays a one-line change (ADR-0020); one case
# checks that the plan of the real file is consistent with it.
set -euo pipefail

die() { printf 'test-setup-toolchain: %s\n' "$*" >&2; exit 1; }

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
# Physical, as the paths that scripts/toolchain.sh prints (readlink -f) are.
work="$(cd "$(mktemp -d)" && pwd -P)"
trap 'rm -rf -- "$work"' EXIT
step="$repo_root/.github/actions/setup-toolchain/setup-toolchain.sh"
real_version="$("$repo_root/scripts/toolchain.sh" get verilator.version)" ||
  die 'scripts/toolchain.sh cannot read project/toolchain.properties'
real_apt="$("$repo_root/scripts/toolchain.sh" get verilator.apt)"
real_commit="$("$repo_root/scripts/toolchain.sh" get verilator.commit)"
real_jdk="$("$repo_root/scripts/toolchain.sh" get jdk.major)"
# The pins of the test's own files.
pinned=5.020 pinned_apt=5.020-1 jdk=21
pinned_commit=5c5314b39cd888f427807d626e1502cbf222c292
apt_file="$work/apt.properties" source_file="$work/source.properties"
printf 'verilator.version=%s\nverilator.apt=%s\nverilator.commit=\njdk.major=%s\n' "$pinned" "$pinned_apt" "$jdk" \
  >"$apt_file"
printf 'verilator.version=%s\nverilator.apt=\nverilator.commit=%s\njdk.major=%s\n' "$pinned" "$pinned_commit" "$jdk" \
  >"$source_file"

expect() { # expect STATUS PATTERN...: the exit status and lines that the output must contain
  local want="$1" pattern
  shift
  [[ "$status" == "$want" ]] || die "exit status $status, expected $want; output:"$'\n'"$out"
  for pattern in "$@"; do
    grep -qE -- "$pattern" <<<"$out" || die "missing /$pattern/ in the output:"$'\n'"$out"
  done
}
lacks() { # lacks PATTERN...: lines that the output must not contain
  local pattern
  for pattern in "$@"; do
    ! grep -qE -- "$pattern" <<<"$out" || die "unexpected /$pattern/ in the output:"$'\n'"$out"
  done
}

# --- scripts/check-workflows.sh -----------------------------------------------------------------
# fresh: a fresh copy of .github in $work/root. check_root: runs check-workflows.sh on it, in a git
# work tree of its own with every file added (it greps the tracked files); sets out and status.
fresh() {
  rm -rf -- "$work/root"
  mkdir -p "$work/root"
  cp -R "$repo_root/.github" "$work/root/"
}
check_root() {
  git -C "$work/root" init -q
  git -C "$work/root" add -A
  status=0
  out="$("$repo_root/scripts/check-workflows.sh" "$work/root" 2>&1)" || status=$?
}
# workflows [FILE AWK-PROGRAM]: checks a fresh copy, with FILE (under .github/workflows) rewritten
# by the awk program.
workflows() {
  fresh
  if [[ $# -gt 0 ]]; then
    awk "$2" "$work/root/.github/workflows/$1" >"$work/edited"
    cmp -s "$work/edited" "$work/root/.github/workflows/$1" && die "the edit of $1 changed nothing: $2"
    mv "$work/edited" "$work/root/.github/workflows/$1"
  fi
  check_root
}
# with_file PATH LINE...: checks a fresh copy with the file PATH (from the root) holding the lines.
with_file() {
  fresh
  mkdir -p "$work/root/$(dirname "$1")"
  printf '%s\n' "${@:2}" >"$work/root/$1"
  check_root
}
uses='/^ *- uses: \.\/\.github\/actions\/setup-toolchain$/'
apt_msg=': installs Verilator with apt; '
workflows
expect 0
# Installing Verilator with apt, in one line, continued with a backslash or folded by YAML, and
# setting up a JDK, in any workflow.
workflows repeat-tests.yml '{ print } END { print "      - run: sudo apt-get update && sudo apt-get install -y verilator" }'
expect 1 "^\\.github/workflows/repeat-tests\\.yml:[0-9]+$apt_msg"
workflows mutation.yml '{ print } END { print "      - run: |"; print "          sudo apt-get install -y \\"; print "            verilator=5.020-1" }'
expect 1 "^\\.github/workflows/mutation\\.yml:[0-9]+$apt_msg"
workflows ci.yml '{ print } END { print "      - run: sudo apt -y install Verilator" }'
expect 1 "^\\.github/workflows/ci\\.yml:[0-9]+$apt_msg"
workflows repeat-tests.yml '{ print } END { print "      - run: >-"; print "          sudo apt-get update"; print ""; print "          sudo apt-get install -y"; print "          git"; print "          verilator" }'
expect 1 "^\\.github/workflows/repeat-tests\\.yml:[0-9]+$apt_msg"
# shellcheck disable=SC2016 # $APT is for the workflow's shell
workflows repeat-tests.yml '{ print } END { print "  more:"; print "    steps:"; print "      - run: >"; print "          sudo apt-get install -y"; print "          \"verilator=$APT\""; print "        env: { APT: 5.020-1 }" }'
expect 1 "^\\.github/workflows/repeat-tests\\.yml:[0-9]+$apt_msg"
# A comment ends at its line break, also after a backslash.
workflows repeat-tests.yml '{ print } END { print "      # the simulator \\"; print "      - run: sudo apt-get install -y verilator" }'
expect 1 "^\\.github/workflows/repeat-tests\\.yml:[0-9]+$apt_msg"
workflows mutation.yml '{ print } END { print "      - uses: actions/setup-java@v5"; print "        with:"; print "          java-version: \"21\"" }'
expect 1 '^\.github/workflows/mutation\.yml:[0-9]+: uses actions/setup-java; ' \
  '^\.github/workflows/mutation\.yml:[0-9]+: sets java-version; '
workflows mutation.yml "{ print } END { print \"      - 'uses': 'actions/setup-java@v5'\"; print \"        with: { 'java-version': '17' }\" }"
expect 1 '^\.github/workflows/mutation\.yml:[0-9]+: uses actions/setup-java; ' \
  '^\.github/workflows/mutation\.yml:[0-9]+: sets java-version; '
workflows repeat-tests.yml '{ print } /^    strategy:$/ { print "      matrix: { java-version: [21] }" }'
expect 1 '^\.github/workflows/repeat-tests\.yml:[0-9]+: sets java-version; '
workflows repeat-tests.yml '{ print } END { print "      - uses: some/action@v1"; print "        with:"; print "          java-version-file: .java-version" }'
expect 1 '^\.github/workflows/repeat-tests\.yml:[0-9]+: sets java-version; '
# Comments, other packages, and running verilator after installing another package are not
# installs of Verilator, nor is a folded block of other commands.
workflows ci.yml '{ print } END { print "      # not: sudo apt-get install -y verilator"; print "      - run: sudo apt-get install -y jq"; print "      - run: sudo apt-get install -y jq && verilator --version"; print "      - run: >-"; print "          sudo apt-get install -y help2man"; print "      - run: scripts/verilator-build.sh 5.020 x y" }'
expect 0
# ci.yml's setup-toolchain step: no `with:` before or after `uses:`, also with quoted keys, a "-"
# alone on its line or a space before the colon; no step in flow style; the step must be there.
with_msg='^\.github/workflows/ci\.yml:[0-9]+: the \.github/actions/setup-toolchain step has .with:.'
workflows ci.yml "{ print } $uses { print \"        with:\"; print \"          verilator: 5.052\" }"
expect 1 "$with_msg"
workflows ci.yml "$uses { print \"      - name: Toolchain\"; print \"        with: { verilator: apt:5.020-1 }\"; print \"        uses: \\\"./.github/actions/setup-toolchain/\\\" # candidate\"; next } { print }"
expect 1 "$with_msg"
for with in '\"with\":' "'with':"; do
  workflows ci.yml "{ print } $uses { print \"        $with\"; print \"          verilator: 5.052\" }"
  expect 1 "$with_msg"
done
workflows ci.yml "{ print } $uses { print \"      -\"; print \"        uses : ./.github/actions/setup-toolchain\"; print \"        with:\"; print \"          verilator: 5.052\" }"
expect 1 "$with_msg"
workflows ci.yml "{ print } $uses { print \"      - uses: ./.github/actions/../actions/setup-toolchain\"; print \"        with: { verilator: 5.052 }\" }"
expect 1 "$with_msg"
workflows ci.yml "{ print } $uses { print \"      - { uses: ./.github/actions/setup-toolchain, with: { verilator: 5.052 } }\" }"
expect 1 '^\.github/workflows/ci\.yml:[0-9]+: a step in flow style names \.github/actions/setup-toolchain'
workflows ci.yml "$uses { next } { print }"
expect 1 '^\.github/workflows/ci\.yml: no step uses \./\.github/actions/setup-toolchain'
# A `with:` of the next step, or nested deeper in this one, is not this step's.
workflows ci.yml "{ print } $uses { print \"        env:\"; print \"          with: x\"; print \"      - uses: actions/cache@v5\"; print \"        with:\"; print \"          path: x\" }"
expect 0
# The variables that override the pinned toolchain, anywhere but in the action,
# toolchain-candidates.yml, mutation.yml, the readers, the self-tests and the documentation.
env_msg=' names VLIW_VERILATOR_(EXPECTED|JOBS) outside the action, toolchain-candidates\.yml, mutation\.yml and the readers'
workflows repeat-tests.yml '{ print } /^    runs-on:/ { print "    env:"; print "      VLIW_VERILATOR_EXPECTED: \"5.052\"" }'
expect 1 "^\\.github/workflows/repeat-tests\\.yml:[0-9]+$env_msg"
workflows ci.yml '{ print } /^  scala:$/ { print "    env: { VLIW_VERILATOR_JOBS: svsim-default }" }'
expect 1 "^\\.github/workflows/ci\\.yml:[0-9]+$env_msg"
with_file scripts/repeat.sh '#!/bin/sh' 'export VLIW_VERILATOR_EXPECTED=5.052'
expect 1 "^scripts/repeat\\.sh:2$env_msg"
with_file hw/src/test/scala/vliw/hw/Sim.scala '  // VLIW_VERILATOR_JOBS is read by Toolchain' \
  '  sys.env.get("VLIW_VERILATOR_JOBS")'
expect 1 "^hw/src/test/scala/vliw/hw/Sim\\.scala:2$env_msg"
lacks 'Sim\.scala:1 '
# Allowed: comments, the candidates' workflows, the action, the readers, the self-tests, documents.
workflows mutation.yml '{ print } /^    runs-on:/ { print "    env:"; print "      VLIW_VERILATOR_JOBS: verilate-1"; print "      # VLIW_VERILATOR_EXPECTED" }'
expect 0
with_file .github/workflows/toolchain-candidates.yml 'env:' '  VLIW_VERILATOR_EXPECTED: "5.052"'
expect 0
for path in .github/actions/setup-toolchain/x.sh scripts/toolchain.sh hw/src/test/scala/vliw/hw/Toolchain.scala \
  scripts/test-x.sh docs/x/y.txt hw/README.md; do
  with_file "$path" 'VLIW_VERILATOR_EXPECTED=5.052'
  expect 0
done
# shellcheck disable=SC2016 # backquotes of Scaladoc
with_file crosscheck/src/test/scala/X.scala '/** The pinned one, also with' '  * `VLIW_VERILATOR_EXPECTED`. */' \
  '  // VLIW_VERILATOR_EXPECTED' '# VLIW_VERILATOR_JOBS'
expect 0

# --- setup-toolchain.sh plan ----------------------------------------------------------------------
ci='GITHUB_WORKFLOW_REF=o/r/.github/workflows/ci.yml@refs/pull/1/merge'
candidates='GITHUB_WORKFLOW_REF=o/r/.github/workflows/toolchain-candidates.yml@refs/pull/1/merge'
commit=ea338be98e1e838d3518809ce8899f85a009963c
build_sha="$(sha256sum "$repo_root/scripts/verilator-build.sh" 2>/dev/null || shasum -a 256 "$repo_root/scripts/verilator-build.sh")"
build_sha="${build_sha%% *}"
# plan [--file PATH|--real] [NAME=VALUE...]: runs the plan step on a file (default: the test's file
# pinned with apt; --real: project/toolchain.properties); sets out, status, outputs (GITHUB_OUTPUT)
# and exported (GITHUB_ENV).
plan() {
  local file=(--file "$apt_file")
  case "${1:-}" in
    --file) file=(--file "$2"); shift 2 ;;
    --real) file=(); shift ;;
  esac
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
plan --file "$source_file"
expect 0 "^setup-toolchain: Verilator $pinned from source $pinned_commit \\(pinned"
has outputs method=source "commit=$pinned_commit" "prefix=$work/home/.cache/vliw-toolchain/verilator-$pinned-5c5314b39cd8" \
  "key=verilator-$pinned-5c5314b39cd8-ubuntu24-$build_sha" "id=$pinned-5c5314b39cd8"
[[ -z "$exported" ]] || die "exported for the pinned toolchain: $exported"
plan --file "$source_file" ImageOS=
expect 1 '^setup-toolchain: ImageOS is not set'
# project/toolchain.properties, whatever it pins: apt exactly when verilator.apt is set, and
# nothing exported.
plan --real
expect 0 "^setup-toolchain: Verilator $real_version from (apt|source) .* \\(pinned; pinned $real_version\\), JDK $real_jdk$"
if [[ -n "$real_apt" ]]; then
  has outputs method=apt "apt=$real_apt" commit=
else
  has outputs method=source apt= "commit=$real_commit"
fi
has outputs "version=$real_version" "jdk=$real_jdk"
[[ -z "$exported" ]] || die "exported for the pinned toolchain of project/toolchain.properties: $exported"
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
# A candidate in toolchain-candidates.yml: from the source at the commit, or from apt; a version
# other than the pinned one is exported, whether the pin is apt or a commit.
plan "$candidates" INPUT_VERILATOR=5.052 INPUT_COMMIT="$commit"
expect 0 '^setup-toolchain: Verilator 5\.052 from source ea338be98e1e838d3518809ce8899f85a009963c \(input; pinned' \
  '^setup-toolchain: VLIW_VERILATOR_EXPECTED=5\.052 for the later steps$'
has outputs method=source version=5.052 "key=verilator-5.052-ea338be98e1e-ubuntu24-$build_sha" id=5.052-ea338be98e1e
has exported VLIW_VERILATOR_EXPECTED=5.052
for file in "$apt_file" "$source_file"; do
  plan --file "$file" "$candidates" INPUT_VERILATOR=apt:5.032-1
  expect 0
  has outputs method=apt version=5.032 apt=5.032-1 commit= prefix= key= id=5.032-apt-5.032-1
  has exported VLIW_VERILATOR_EXPECTED=5.032
done
# The pinned version as a candidate: another way to install it, nothing exported.
plan "$candidates" INPUT_VERILATOR=5.020 INPUT_COMMIT="$pinned_commit"
expect 0
has outputs method=source "version=$pinned"
[[ -z "$exported" ]] || die "exported for the pinned version from the input: $exported"
plan --file "$source_file" "$candidates" INPUT_VERILATOR="apt:$pinned_apt"
expect 0
has outputs method=apt "version=$pinned" "apt=$pinned_apt"
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
# A fake Verilator in a prefix (FAKE_VERILATOR: its version), another one elsewhere, a fake java
# (FAKE_JAVA: its version) and a fake dpkg-query (FAKE_DPKG: the package version, empty for none).
mkdir -p "$work/prefix/bin" "$work/other/bin" "$work/java/bin" "$work/dpkg/bin"
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
cat >"$work/dpkg/bin/dpkg-query" <<'EOF'
#!/bin/sh
[ -n "${FAKE_DPKG:-}" ] || { echo 'dpkg-query: no packages found matching verilator' >&2; exit 1; }
printf '%s' "$FAKE_DPKG"
EOF
chmod +x "$work"/*/bin/*
# verify [--file PATH] [NAME=VALUE...]: runs the verify step for the prefix's Verilator built from
# the source, pinned by the test's file pinned to a commit (default); sets out and status.
verify() {
  local file="$source_file"
  if [[ "${1:-}" == --file ]]; then
    file="$2"
    shift 2
  fi
  : >"$work/summary"
  status=0
  out="$(env -u VLIW_VERILATOR_EXPECTED -u VERILATOR_ROOT -u GITHUB_ACTIONS -u GITHUB_WORKFLOW_REF \
    PATH="$work/prefix/bin:$work/java/bin:$PATH" METHOD=source VERSION="$pinned" APT= COMMIT="$pinned_commit" \
    PREFIX="$work/prefix" KEY=verilator-key JDK="$jdk" CACHE_HIT=true FAKE_JAVA="$jdk.0.8" \
    GITHUB_STEP_SUMMARY="$work/summary" "$@" "$step" --file "$file" verify 2>&1)" || status=$?
}
verify
expect 0 "^setup-toolchain: Verilator $pinned from source $pinned_commit, JDK $jdk$" \
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
# The installed verilator would run another verilator_bin: the one under VERILATOR_ROOT, or one on
# the PATH when there is none next to it.
verify VERILATOR_ROOT="$work/other"
expect 1 "^verilator_bin: $work/other/bin/verilator_bin$" \
  "^setup-toolchain: VERILATOR_ROOT is set \\($work/other\\): $work/prefix/bin/verilator would run the verilator_bin under it" \
  "^setup-toolchain: the verilator_bin that $work/prefix/bin/verilator runs is '$work/other/bin/verilator_bin', not $work/prefix/bin/verilator_bin$"
mv "$work/prefix/bin/verilator_bin" "$work/prefix/verilator_bin"
verify PATH="$work/prefix/bin:$work/java/bin:$work/other/bin:$PATH"
expect 1 "^setup-toolchain: the verilator_bin that $work/prefix/bin/verilator runs is '$work/other/bin/verilator_bin', not $work/prefix/bin/verilator_bin$"
mv "$work/prefix/verilator_bin" "$work/prefix/bin/verilator_bin"
# apt: /usr/bin/verilator (not the fake), and the package of the pinned version.
verify --file "$apt_file" METHOD=apt APT="$pinned_apt" PATH="$work/dpkg/bin:$work/prefix/bin:$work/java/bin:$PATH" \
  FAKE_DPKG=5.020-2
expect 1 "^setup-toolchain: the verilator on the PATH is '$work/prefix/bin/verilator', not /usr/bin/verilator$" \
  "^setup-toolchain: the verilator package is '5\\.020-2', not $pinned_apt$" '^dpkg-query verilator: 5\.020-2$'
verify --file "$apt_file" METHOD=apt APT="$pinned_apt" PATH="$work/dpkg/bin:$work/prefix/bin:$work/java/bin:$PATH" \
  FAKE_DPKG=
expect 1 "^setup-toolchain: the verilator package is 'not installed', not $pinned_apt$"
verify --file "$apt_file" METHOD=apt APT="$pinned_apt" PATH="$work/dpkg/bin:$work/prefix/bin:$work/java/bin:$PATH" \
  FAKE_DPKG="$pinned_apt"
expect 1 "^setup-toolchain: the verilator on the PATH is '$work/prefix/bin/verilator', not /usr/bin/verilator$" \
  "^dpkg-query verilator: $pinned_apt$"
lacks '^setup-toolchain: the verilator package is '
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
# Fakes, which log their arguments to FAKE_LOG: git (clone creates the directory with a configure,
# and rev-parse prints FAKE_HEAD), autoconf, configure (--help lists --enable-jemalloc when
# FAKE_JEMALLOC is set) and make (install puts a verilator printing FAKE_BUILT, or 5.999 when
# VERILATOR_ROOT is set, as the real script then runs another verilator_bin, a verilator_bin, a
# verilator_bin_dbg and the examples into the configured prefix).
mkdir -p "$work/build/bin"
cat >"$work/build/bin/git" <<'EOF'
#!/bin/sh
echo "git $*" >>"$FAKE_LOG"
case " $* " in
  *" clone "*) for last; do :; done; mkdir -p "$last"; cp "$FAKE_CONFIGURE" "$last/configure" ;;
  *" rev-parse HEAD "*) echo "$FAKE_HEAD" ;;
  *) echo "fake git: $*" >&2; exit 99 ;;
esac
EOF
cat >"$work/build/configure" <<'EOF'
#!/bin/sh
echo "configure $*" >>"$FAKE_LOG"
if [ "$1" = --help ]; then
  echo '  --disable-tcmalloc'
  [ -z "${FAKE_JEMALLOC:-}" ] || echo '  --enable-jemalloc'
  exit 0
fi
for arg; do
  case "$arg" in --prefix=*) printf '%s\n' "${arg#--prefix=}" >configured-prefix ;; esac
done
EOF
cat >"$work/build/bin/autoconf" <<'EOF'
#!/bin/sh
echo "autoconf $*" >>"$FAKE_LOG"
EOF
cat >"$work/build/bin/make" <<'EOF'
#!/bin/sh
echo "make $*" >>"$FAKE_LOG"
[ "$1" = install ] || exit 0
prefix="$(cat configured-prefix)"
mkdir -p "$prefix/bin" "$prefix/share/verilator/examples"
cat >"$prefix/bin/verilator" <<'END'
#!/bin/sh
if [ -n "${VERILATOR_ROOT:-}" ]; then echo 'Verilator 5.999 (the verilator_bin of VERILATOR_ROOT)'; exit 0; fi
echo "Verilator ${FAKE_BUILT:-5.020} 2024-01-01 rev v5.020 (fake)"
END
: >"$prefix/bin/verilator_bin"
: >"$prefix/bin/verilator_bin_dbg"
: >"$prefix/share/verilator/examples/x.v"
chmod +x "$prefix/bin/verilator" "$prefix/bin/verilator_bin"
EOF
chmod +x "$work/build/bin"/* "$work/build/configure"
# build_with NAME=VALUE... ARG...: runs verilator-build.sh with the fakes; sets out and status.
build_with() {
  local vars=()
  while [[ $# -gt 0 && "$1" == *=* ]]; do
    vars+=("$1")
    shift
  done
  : >"$work/build/log"
  status=0
  out="$(env -u FAKE_JEMALLOC -u FAKE_BUILT -u VERILATOR_ROOT PATH="$work/build/bin:$PATH" FAKE_LOG="$work/build/log" \
    FAKE_CONFIGURE="$work/build/configure" FAKE_HEAD="$pinned_commit" ${vars[@]+"${vars[@]}"} \
    "${build[@]}" "$@" 2>&1)" || status=$?
}
# A tag at another commit: nothing configured or installed.
build_with FAKE_HEAD="$commit" 5.020 "$pinned_commit" "$work/vb/prefix"
expect 1 "^result: failed \\(the tag v5\\.020 is at commit $commit, not $pinned_commit: was the tag moved\\?\\)$" \
  "^tag commit: $commit$" '^verilator: n/a$'
[[ ! -e "$work/vb/prefix" ]] || die 'verilator-build.sh installed something after a commit mismatch'
! grep -q '^configure' "$work/build/log" || die "configure ran after a commit mismatch:"$'\n'"$(cat "$work/build/log")"
grep -qx "commit: $pinned_commit" "$work/vb/prefix.build-info.txt" || die 'no build-info.txt next to PREFIX'
# The tag at the commit: configured without tcmalloc (and jemalloc, when configure has it), built,
# installed without verilator_bin_dbg and the examples, and checked with VERILATOR_ROOT unset.
build_with VERILATOR_ROOT="$work/other" 5.020 "$pinned_commit" "$work/vb/ok" "$work/vb/ok-info.txt"
expect 0 '^result: success$' "^tag commit: $pinned_commit$" '^verilator: Verilator 5\.020 2024-01-01 rev v5\.020 \(fake\)$'
grep -qx "configure --prefix=$work/vb/ok --disable-tcmalloc" "$work/build/log" ||
  die "configure without --prefix and --disable-tcmalloc alone:"$'\n'"$(cat "$work/build/log")"
grep -qx 'make install' "$work/build/log" || die "no make install:"$'\n'"$(cat "$work/build/log")"
[[ -x "$work/vb/ok/bin/verilator" && -f "$work/vb/ok/bin/verilator_bin" ]] || die 'verilator-build.sh installed no verilator'
[[ ! -e "$work/vb/ok/bin/verilator_bin_dbg" && ! -e "$work/vb/ok/share/verilator/examples" ]] ||
  die 'verilator-build.sh left verilator_bin_dbg or the examples in PREFIX'
grep -qx 'result: success' "$work/vb/ok-info.txt" || die "no result in INFO_FILE:"$'\n'"$(cat "$work/vb/ok-info.txt")"
[[ -f "$work/vb/ok-info.log" ]] || die 'no build log next to INFO_FILE'
build_with FAKE_JEMALLOC=1 5.020 "$pinned_commit" "$work/vb/jemalloc"
expect 0 '^result: success$'
grep -qx "configure --prefix=$work/vb/jemalloc --disable-tcmalloc --disable-jemalloc" "$work/build/log" ||
  die "configure without --disable-jemalloc:"$'\n'"$(cat "$work/build/log")"
# The installed verilator of another version.
build_with FAKE_BUILT=5.032 5.020 "$pinned_commit" "$work/vb/other"
expect 1 '^result: failed \(the installed verilator is not 5\.020\)$' '^verilator: Verilator 5\.032 '

echo 'setup-toolchain self-test passed'
