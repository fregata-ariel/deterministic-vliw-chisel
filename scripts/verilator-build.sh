#!/usr/bin/env bash
# Builds one Verilator version from its git tag and installs it under PREFIX (#121). Copied from
# scripts/verilator-build.sh of the catalog (claude/verilator-catalog at 025f545, #85), with the
# commit check added: the build stops before configure unless the tag is at COMMIT, so that a moved
# tag is noticed.
# Usage: scripts/verilator-build.sh VERSION COMMIT PREFIX [INFO_FILE]
#   VERSION    e.g. 5.020 (the tag v5.020 of https://github.com/verilator/verilator)
#   COMMIT     the commit of that tag, 40 lowercase hexadecimal digits; this prints it:
#                git ls-remote https://github.com/verilator/verilator 'refs/tags/v5.020^{}'
#   PREFIX     the install prefix, e.g. ~/verilator/5.020 (put PREFIX/bin first on the PATH)
#   INFO_FILE  where to write the result, the commit, the time and the number of compiler warnings
#              (default: PREFIX.build-info.txt)
# Needs git, autoconf, make, g++, flex, bison, help2man and python3; on Ubuntu:
#   sudo apt-get install -y git autoconf make g++ flex bison libfl-dev help2man perl python3
# Builds without tcmalloc (and jemalloc, an option since 5.048), like Debian's verilator package,
# so that the versions differ only in their own sources. Leaves out of PREFIX what the tests never
# use, to keep CI's cache small: verilator_bin_dbg (only `verilator --debug` runs it) and the
# examples.
# Writes the build log next to INFO_FILE (.log).
# Like the other scripts, this runs on bash 3.2 (macOS).
set -euo pipefail

die() { printf 'verilator-build: %s\n' "$*" >&2; exit 2; }
# The verilator script runs the verilator_bin under VERILATOR_ROOT when that is set, and the binary
# that VERILATOR_BIN names: the version check after `make install` must run the one built here.
# Verilator's install guide unsets VERILATOR_ROOT for a build into a prefix, too.
unset VERILATOR_ROOT VERILATOR_BIN

[[ $# -ge 3 && $# -le 4 ]] || die 'usage: scripts/verilator-build.sh VERSION COMMIT PREFIX [INFO_FILE]'
version="$1" commit="$2" prefix="$3"
[[ "$version" =~ ^[0-9]+\.[0-9]{3}$ ]] ||
  die "VERSION $version: expected digits, a dot and three digits, e.g. 5.020"
[[ "$commit" =~ ^[0-9a-f]{40}$ ]] || die "COMMIT $commit: expected 40 lowercase hexadecimal digits"
[[ -n "$prefix" ]] || die 'PREFIX is empty'
# Absolute, since the build runs in another directory.
[[ "$prefix" == /* ]] || prefix="$PWD/$prefix"
info="${4:-$prefix.build-info.txt}"
[[ "$info" == /* ]] || info="$PWD/$info"
log="${info%.txt}.log"
mkdir -p "$(dirname "$info")"
src="$(mktemp -d)"
trap 'rm -rf -- "$src"' EXIT
cpus="$(nproc 2>/dev/null || sysctl -n hw.ncpu)"

start=$SECONDS
head='' result=success
if ! git -c advice.detachedHead=false clone -q --depth 1 --branch "v$version" \
  https://github.com/verilator/verilator "$src/verilator" >"$log" 2>&1; then
  result='failed (git clone)'
elif ! head="$(git -C "$src/verilator" rev-parse HEAD 2>>"$log")"; then
  result='failed (git rev-parse)'
elif [[ "$head" != "$commit" ]]; then
  result="failed (the tag v$version is at commit $head, not $commit: was the tag moved?)"
else
  status=0
  # In a subshell, so that the directory stays. `set -e` does not apply on the left of ||, hence &&.
  (
    cd "$src/verilator" &&
      autoconf &&
      configure_args=(--prefix="$prefix" --disable-tcmalloc) &&
      ./configure --help >"$src/configure-help.txt" &&
      if grep -q -- --enable-jemalloc "$src/configure-help.txt"; then configure_args+=(--disable-jemalloc); fi &&
      ./configure "${configure_args[@]}" &&
      make -j "$cpus" &&
      make install &&
      rm -f -- "$prefix/bin/verilator_bin_dbg" &&
      rm -rf -- "$prefix/share/verilator/examples"
  ) >>"$log" 2>&1 || status=$?
  ((status == 0)) || result="failed ($status)"
fi
seconds=$((SECONDS - start))
installed=n/a
if [[ "$result" == success ]]; then
  installed="$("$prefix/bin/verilator" --version 2>&1)" || result="failed ($prefix/bin/verilator --version)"
  # The first word after "Verilator" is the version, as scripts/toolchain.sh reads it.
  [[ "$result" != success || "$installed" == "Verilator $version "* ]] ||
    result="failed (the installed verilator is not $version)"
fi
{
  printf 'version: %s\n' "$version"
  printf 'commit: %s\n' "$commit"
  printf 'tag commit: %s\n' "${head:-n/a}"
  printf 'result: %s\n' "$result"
  printf 'seconds: %d\n' "$seconds"
  printf 'cpus: %s\n' "$cpus"
  printf 'g++: %s\n' "$("${CXX:-g++}" --version 2>/dev/null | head -n 1)"
  printf 'compiler warnings: %s\n' "$(grep -c 'warning:' "$log" || true)"
  printf 'verilator: %s\n' "$installed"
} >"$info"
cat "$info"
if [[ "$result" != success ]]; then
  tail -n 40 "$log" >&2
  printf 'verilator-build: %s; the log is %s\n' "$result" "$log" >&2
  exit 1
fi
