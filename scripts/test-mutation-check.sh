#!/usr/bin/env bash
# Self-test of scripts/mutation-check.sh with a fake sbt, run by `scripts/check.sh static` (#74).
# The fake sbt replays the runs in scripts/testdata/mutation-classify (its JUnit reports, sbt log and
# exit code) in the order given by FAKE_PLAN, so the retries, the baseline and the summary are
# checked without Scala or Verilator.
set -euo pipefail

die() { printf 'test-mutation-check: %s\n' "$*" >&2; exit 1; }

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT

# A repository with the scripts and the pinned toolchain, one file to mutate and three mutants (one
# is stale).
mkdir -p "$work/repo/scripts" "$work/repo/src" "$work/repo/project" "$work/bin"
cp "$repo_root/scripts/mutation-check.sh" "$repo_root/scripts/mutation-classify.py" \
  "$repo_root/scripts/toolchain.sh" "$work/repo/scripts/"
cp "$repo_root/project/toolchain.properties" "$work/repo/project/"
printf 'x = 1\ny = 2\n' >"$work/repo/src/a.txt"
{
  printf '# id\tfile\toriginal\tmutated\twhat\n'
  printf 'm-first\tsrc/a.txt\tx = 1\tx = 0\tfirst\n'
  printf 'm-second\tsrc/a.txt\ty = 2\ty = 0\tsecond\n'
  printf 'm-stale\tsrc/a.txt\tz = 3\tz = 0\tstale\n'
} >"$work/repo/scripts/mutants.tsv"
git -C "$work/repo" init -q
git -C "$work/repo" add -A
git -C "$work/repo" -c user.name=test -c user.email=test@invalid commit -qm init

# FAKE_PLAN: one word per sbt run, mapped to a fixture.
cat >"$work/bin/sbt" <<EOF
#!/usr/bin/env bash
set -euo pipefail
n=\$((\$(cat "\$FAKE_STATE" 2>/dev/null || echo 0) + 1))
echo "\$n" >"\$FAKE_STATE"
read -r -a plan <<<"\$FAKE_PLAN"
case "\${plan[\$((n - 1))]:-missing}" in
  pass) fixture=passed ;;
  kill) fixture=assertion ;;
  env) fixture=sim-build-thread-pool ;;
  *) echo "fake sbt: no plan for run \$n" >&2; exit 99 ;;
esac
src="$repo_root/scripts/testdata/mutation-classify/\$fixture"
for d in "\$src"/reports/*/; do
  p="\$(basename "\$d")"
  mkdir -p "\$p/target/test-reports"
  cp "\$d"*.xml "\$p/target/test-reports/"
done
cat "\$src/sbt.log"
exit "\$(cat "\$src/exit")"
EOF
# The fake verilator reports the pinned version, or FAKE_VERILATOR.
pinned="$("$repo_root/scripts/toolchain.sh" get verilator.version)"
cat >"$work/bin/verilator" <<EOF
#!/bin/sh
echo "Verilator \${FAKE_VERILATOR:-$pinned} (fake)"
EOF
chmod +x "$work/bin/sbt" "$work/bin/verilator"

# Runs mutation-check.sh with the plan $1 and mutants $3...; log directory $2. Sets out and status.
# The expected Verilator is the pinned one, whatever the environment of the self-test.
run() {
  local plan="$1" logs="$2"
  shift 2
  rm -f "$work/state"
  status=0
  out="$(cd "$work/repo" && env -u VLIW_VERILATOR_EXPECTED PATH="$work/bin:$PATH" SBT=sbt \
    FAKE_PLAN="$plan" FAKE_STATE="$work/state" MUTATION_LOG_DIR="$logs" scripts/mutation-check.sh "$@" 2>&1)" ||
    status=$?
  git -C "$work/repo" diff --quiet || die "the mutated file was not restored (plan: $plan)"
}
expect() { # expect STATUS PATTERN...: the exit status and lines that the output must contain
  local want="$1" pattern
  shift
  [[ "$status" == "$want" ]] || die "exit status $status, expected $want; output:"$'\n'"$out"
  for pattern in "$@"; do
    grep -qE -- "$pattern" <<<"$out" || die "missing /$pattern/ in the output:"$'\n'"$out"
  done
}

# The baseline passes on its retry; an ERROR that stays after its retry is reported and the run goes
# on to the next mutant; the stale mutant is reported.
run 'env pass env env kill' "$work/logs1" m-first m-second m-stale
expect 1 '^baseline: passed on a retry' '^m-first +ERROR +sim-build' '^m-second +killed' '^m-stale +STALE' \
  '^killed: 1, survived: 0, error: 1, stale: 1'
[[ -d "$work/logs1/m-first.retry1" ]] || die 'the retry of m-first has no directory'

# With MUTATION_RETRIES=0 the first ERROR is final, and the run still goes on.
MUTATION_RETRIES=0 run 'pass env kill' "$work/logs2" m-first m-second
expect 1 '^m-first +ERROR' '^m-second +killed' '^killed: 1, survived: 0, error: 1, stale: 0'

# A baseline that fails twice stops the check with its reason.
run 'env env' "$work/logs3" m-first
expect 2 'the unmutated tests do not pass \(ERROR sim-build'

# A reused log directory: the reports of the earlier kill must not count in the later run.
run 'pass kill' "$work/logs4" m-first
expect 0 '^m-first +killed' '^killed: 1, survived: 0, error: 0, stale: 0'
run 'pass pass' "$work/logs4" m-first
expect 1 '^m-first +SURVIVED' '^killed: 0, survived: 1, error: 0, stale: 0'

# MUTATION_SHARD k/n runs the mutant rows k, k+n, ...; with mutant ids, both selections apply.
MUTATION_SHARD=1/2 run 'pass kill' "$work/logs5"
expect 1 '^m-first +killed' '^m-stale +STALE' '^killed: 1, survived: 0, error: 0, stale: 1'
grep -q m-second <<<"$out" && die "shard 1/2 ran m-second:"$'\n'"$out"
MUTATION_SHARD=2/2 run 'pass kill' "$work/logs6"
expect 0 '^m-second +killed' '^killed: 1, survived: 0, error: 0, stale: 0'
MUTATION_SHARD=3/3 run 'pass' "$work/logs7" m-first m-second
expect 0 '^killed: 0, survived: 0, error: 0, stale: 0'
for bad in 0/2 3/2 1/0 x 1/2/3; do
  MUTATION_SHARD="$bad" run '' "$work/logs8"
  expect 2 'MUTATION_SHARD must be k/n'
done

# A Verilator other than the expected one stops the check before sbt, also without the baseline
# (#121); env.txt records the pinned and the expected version of a run.
FAKE_VERILATOR=5.999 MUTATION_BASELINE=0 run 'kill' "$work/logs9" m-first
expect 2 'the Verilator on the PATH is 5\.999, expected '"$pinned"' \(pinned\)' 'toolchain\.sh check failed'
[[ ! -e "$work/state" ]] || die 'sbt ran although the Verilator is not the expected one'
grep -q "^expected: verilator $pinned (pinned)\$" "$work/logs4/env.txt" || die 'env.txt has no expected version'
grep -q "^pinned: verilator $pinned " "$work/logs4/env.txt" || die 'env.txt has no pinned version'

echo 'mutation-check.sh self-test passed'
