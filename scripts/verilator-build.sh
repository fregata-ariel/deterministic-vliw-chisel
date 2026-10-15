#!/usr/bin/env bash
# Builds one Verilator version from its git tag and installs it under PREFIX (#85).
# Usage: scripts/verilator-build.sh VERSION PREFIX [INFO_FILE]
#   VERSION    e.g. 5.030 (the tag v5.030 of https://github.com/verilator/verilator)
#   PREFIX     the install prefix, e.g. /opt/verilator/5.030 (its bin/ goes first on PATH later)
#   INFO_FILE  where to write the result, the time and the number of compiler warnings
#              (default: PREFIX.build-info.txt)
# Needs git, autoconf, make, g++, flex, bison, help2man and python3; on Ubuntu:
#   sudo apt-get install -y git autoconf make g++ flex bison libfl-dev help2man perl python3
# Builds without tcmalloc (and jemalloc, an option since 5.048), like Debian's verilator package,
# so that the versions differ only in their own sources.
# Writes the build log next to INFO_FILE (.log).
set -euo pipefail

die() { printf 'verilator-build: %s\n' "$*" >&2; exit 2; }

version="${1:-}"
prefix="${2:-}"
[[ "$version" =~ ^[0-9]+\.[0-9]+$ && -n "$prefix" ]] ||
  die 'usage: scripts/verilator-build.sh VERSION PREFIX [INFO_FILE]'
info="${3:-$prefix.build-info.txt}"
log="${info%.txt}.log"
mkdir -p "$(dirname "$info")"
src="$(mktemp -d)"
trap 'rm -rf -- "$src"' EXIT

start=$SECONDS
status=0
{
  git clone -q --depth 1 --branch "v$version" https://github.com/verilator/verilator "$src/verilator" &&
    cd "$src/verilator" &&
    autoconf &&
    configure_args=(--prefix="$prefix" --disable-tcmalloc) &&
    if ./configure --help | grep -q -- --enable-jemalloc; then configure_args+=(--disable-jemalloc); fi &&
    ./configure "${configure_args[@]}" &&
    make -j "$(nproc)" &&
    make install
} >"$log" 2>&1 || status=$?
seconds=$((SECONDS - start))
{
  printf 'version: %s\n' "$version"
  printf 'result: %s\n' "$( ((status == 0)) && echo success || echo "failed ($status)")"
  printf 'seconds: %d\n' "$seconds"
  printf 'cpus: %s\n' "$(nproc)"
  printf 'g++: %s\n' "$(g++ --version | head -n 1)"
  printf 'compiler warnings: %s\n' "$(grep -c 'warning:' "$log" || true)"
  printf 'configure: %s\n' "$(grep -m 1 -E '^ *Verilator [0-9]' "$log" 2>/dev/null || true)"
} >"$info"
cat "$info"
((status == 0)) || { tail -n 40 "$log" >&2; exit 1; }
"$prefix/bin/verilator" --version
