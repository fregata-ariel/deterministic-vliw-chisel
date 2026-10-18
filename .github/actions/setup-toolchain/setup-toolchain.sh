#!/usr/bin/env bash
# The shell steps of the setup-toolchain action (action.yml next to this, ADR-0020, #121).
# Usage: setup-toolchain.sh [--file PATH] plan
#          chooses what to install: the pinned toolchain of project/toolchain.properties, or the
#          candidate Verilator of the inputs INPUT_VERILATOR (a version, built from the source at
#          INPUT_COMMIT, or apt:<package version>), which only
#          .github/workflows/toolchain-candidates.yml may give (GITHUB_WORKFLOW_REF, as for
#          VLIW_VERILATOR_EXPECTED in scripts/toolchain.sh). Writes the choice to GITHUB_OUTPUT, and
#          VLIW_VERILATOR_EXPECTED to GITHUB_ENV when the version is not the pinned one, so that the
#          later steps expect it.
#        setup-toolchain.sh [--file PATH] verify
#          checks what was installed against the choice (the outputs of plan, given as METHOD,
#          VERSION, APT, COMMIT, PREFIX, KEY and JDK; CACHE_HIT from the cache step): the
#          scripts/toolchain.sh check of VERSION, the verilator on the PATH, the JDK's major
#          version and VLIW_VERILATOR_EXPECTED. Writes the fingerprint to the job log and
#          GITHUB_STEP_SUMMARY, and then fails if a check failed.
# --file reads another file in the format of project/toolchain.properties (the self-test,
# scripts/test-setup-toolchain.sh).
# Like the other scripts, this runs on bash 3.2 (macOS).
set -euo pipefail
export LC_ALL=C

die() {
  [[ -z "${GITHUB_ACTIONS:-}" ]] || printf '::error title=setup-toolchain::%s\n' "$*"
  printf 'setup-toolchain: %s\n' "$*" >&2
  exit 1
}

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd -P)"
toolchain=("$repo_root/scripts/toolchain.sh")
candidates_workflow='/.github/workflows/toolchain-candidates.yml@'

# Prints the value of KEY $1 of the properties file (scripts/toolchain.sh checks the whole file).
get() { "${toolchain[@]}" get "$1"; }

plan() {
  : "${GITHUB_OUTPUT:?}" "${GITHUB_ENV:?}"
  local pinned pinned_apt pinned_commit jdk
  pinned="$(get verilator.version)"
  pinned_apt="$(get verilator.apt)"
  pinned_commit="$(get verilator.commit)"
  jdk="$(get jdk.major)"
  local verilator="${INPUT_VERILATOR:-}" commit="${INPUT_COMMIT:-}" ref="${GITHUB_WORKFLOW_REF:-}"
  if [[ -n "$verilator$commit" && "$ref" != *"$candidates_workflow"* ]]; then
    die "the inputs verilator ('$verilator') and commit ('$commit') are accepted only in" \
      ".github/workflows/toolchain-candidates.yml (GITHUB_WORKFLOW_REF=$ref); the other workflows" \
      "install the pinned toolchain of project/toolchain.properties (ADR-0020)"
  fi
  local method version apt='' source=pinned
  if [[ -z "$verilator" ]]; then
    [[ -z "$commit" ]] || die "the input commit ($commit) needs the input verilator (the version)"
    version="$pinned" apt="$pinned_apt" commit="$pinned_commit"
    if [[ -n "$apt" ]]; then method=apt; else method=source; fi
  elif [[ "$verilator" == apt:* ]]; then
    apt="${verilator#apt:}" method=apt source=input
    [[ "$apt" =~ ^([0-9]+\.[0-9]{3})-[0-9A-Za-z.+~]+$ ]] ||
      die "verilator=$verilator: expected apt:<version>-<revision>, e.g. apt:5.020-1"
    version="${BASH_REMATCH[1]}"
    [[ -z "$commit" ]] || die "verilator=$verilator installs a package: leave out the input commit ($commit)"
  else
    [[ "$verilator" =~ ^[0-9]+\.[0-9]{3}$ ]] ||
      die "verilator=$verilator: expected a version (digits, a dot and three digits) or apt:<package version>"
    [[ "$commit" =~ ^[0-9a-f]{40}$ ]] ||
      die "verilator=$verilator is built from the source: the input commit ('$commit') must be the" \
        "commit of the tag v$verilator, 40 lowercase hexadecimal digits"
    version="$verilator" method=source source=input
  fi

  local id prefix='' key=''
  if [[ "$method" == apt ]]; then
    id="$version-apt-$apt" commit=''
  else
    id="$version-${commit:0:12}"
    [[ -n "${ImageOS:-}" ]] || die 'ImageOS is not set (a GitHub-hosted runner sets it); it is part of the cache key'
    prefix="$HOME/.cache/vliw-toolchain/verilator-$id"
    # A build is reused only for the same version, commit, runner image and build script.
    key="verilator-$id-$ImageOS-$(sha256 "$repo_root/scripts/verilator-build.sh")"
  fi
  {
    printf 'method=%s\nversion=%s\napt=%s\ncommit=%s\n' "$method" "$version" "$apt" "$commit"
    printf 'prefix=%s\nkey=%s\njdk=%s\nid=%s\npinned=%s\n' "$prefix" "$key" "$jdk" "$id" "$pinned"
  } >>"$GITHUB_OUTPUT"
  if [[ "$version" != "$pinned" ]]; then
    printf 'VLIW_VERILATOR_EXPECTED=%s\n' "$version" >>"$GITHUB_ENV"
  fi
  printf 'setup-toolchain: Verilator %s from %s (%s; pinned %s), JDK %s\n' "$version" \
    "$(describe "$method" "$apt" "$commit")" "$source" "$pinned" "$jdk"
  [[ "$version" == "$pinned" ]] || printf 'setup-toolchain: VLIW_VERILATOR_EXPECTED=%s for the later steps\n' "$version"
}

describe() { # describe METHOD APT COMMIT: how Verilator is installed
  if [[ "$1" == apt ]]; then printf 'apt %s' "$2"; else printf 'source %s' "$3"; fi
}

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1
}

verify() {
  local method="${METHOD:?}" version="${VERSION:?}" jdk="${JDK:?}" apt="${APT:-}" commit="${COMMIT:-}"
  local prefix="${PREFIX:-}" pinned problems=() check status=0
  pinned="$(get verilator.version)"
  check="$("${toolchain[@]}" check --expect "$version" 2>&1)" || status=$?
  ((status == 0)) || problems+=("scripts/toolchain.sh check --expect $version failed (exit $status)")

  # The Verilator that was installed must be the first on the PATH; prefix/bin is the only directory
  # that the action adds to it (a directory such as /usr/bin first would hide the JDK of setup-java).
  local path want pkg=''
  path="$(command -v verilator || true)"
  if [[ "$method" == apt ]]; then
    want=/usr/bin/verilator
    pkg="$(dpkg-query -W -f='${Version}' verilator 2>/dev/null || true)"
    [[ "$pkg" == "$apt" ]] || problems+=("the verilator package is '${pkg:-not installed}', not $apt")
  else
    want="$prefix/bin/verilator"
  fi
  [[ "$path" == "$want" ]] || problems+=("the verilator on the PATH is '${path:-none}', not $want")

  # The line 'openjdk version "21.0.8" 2025-07-15 LTS', after any "Picked up JAVA_TOOL_OPTIONS: ...";
  # JDK 8 and older are "1.<major>".
  local java_path java_out java_line='' line major='' java_re='^[a-z]+ version "([0-9]+)(\.([0-9]+))?'
  java_path="$(command -v java || true)"
  java_out="$(java -version 2>&1)" || java_out="failed: $java_out"
  while IFS= read -r line; do
    [[ "$line" =~ $java_re ]] || continue
    java_line="$line" major="${BASH_REMATCH[1]}"
    [[ "$major" != 1 ]] || major="${BASH_REMATCH[3]}"
    break
  done <<<"$java_out"
  [[ -n "$java_line" ]] || java_line="${java_out%%$'\n'*}"
  [[ "$major" == "$jdk" ]] || problems+=("java -version is '$java_line' (${java_path:-no java on the PATH}), not JDK $jdk")

  if [[ "$version" != "$pinned" ]]; then
    [[ "${VLIW_VERILATOR_EXPECTED:-}" == "$version" ]] ||
      problems+=("VLIW_VERILATOR_EXPECTED is '${VLIW_VERILATOR_EXPECTED:-}', not the installed candidate $version")
  elif [[ -n "${VLIW_VERILATOR_EXPECTED+set}" && "$VLIW_VERILATOR_EXPECTED" != "$version" ]]; then
    problems+=("VLIW_VERILATOR_EXPECTED=$VLIW_VERILATOR_EXPECTED is set, but the pinned $version was installed")
  fi

  local fingerprint
  fingerprint="$(
    printf 'setup-toolchain: Verilator %s from %s, JDK %s\n' "$version" "$(describe "$method" "$apt" "$commit")" "$jdk"
    printf '%s\n' "$check"
    if [[ "$method" == apt ]]; then
      printf 'dpkg-query verilator: %s\n' "${pkg:-not installed}"
    else
      printf 'prefix: %s\ncache: %s (key %s)\n' "$prefix" \
        "$([[ "${CACHE_HIT:-}" == true ]] && echo restored || echo 'built here')" "${KEY:-}"
      [[ ! -f "$prefix/build-info.txt" ]] || sed 's/^/build-info: /' "$prefix/build-info.txt"
    fi
    printf 'java: %s\n' "${java_path:-not on the PATH}"
    printf 'java -version: %s\n' "$java_line"
    printf 'JAVA_HOME: %s\n' "${JAVA_HOME:-unset}"
    printf 'VLIW_VERILATOR_EXPECTED: %s\n' "${VLIW_VERILATOR_EXPECTED-unset}"
    printf 'image: %s %s\n' "${ImageOS:-?}" "${ImageVersion:-?}"
  )"
  printf '%s\n' "$fingerprint"
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    {
      printf '### Toolchain\n\n~~~\n%s\n~~~\n' "$fingerprint"
      ((${#problems[@]} == 0)) || printf -- '- **%s**\n' "${problems[@]}"
    } >>"$GITHUB_STEP_SUMMARY"
  fi
  ((${#problems[@]} > 0)) || return 0
  local problem
  for problem in "${problems[@]}"; do
    [[ -z "${GITHUB_ACTIONS:-}" ]] || printf '::error title=setup-toolchain::%s\n' "$problem"
    printf 'setup-toolchain: %s\n' "$problem" >&2
  done
  exit 1
}

if [[ "${1:-}" == --file ]]; then
  [[ $# -ge 2 ]] || die '--file needs a path'
  toolchain+=(--file "$2")
  shift 2
fi
case "${1:-}" in
  plan) plan ;;
  verify) verify ;;
  *) die "usage: setup-toolchain.sh [--file PATH] plan|verify (got '${1:-}')" ;;
esac
