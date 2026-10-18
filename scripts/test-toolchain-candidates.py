#!/usr/bin/env python3
"""Self-test of scripts/toolchain-candidates.py (#121), run by `scripts/check.sh static`.

Checks with fakes (no network, no sbt, no Verilator): the knobs of the plan (the matrices it writes,
and every malformed knob), the classification of check.sh's output and of a test job, the summary
of a test job, the order and environment of the timing runs, and the report with its records,
including mutation logs and records committed from an earlier run. The fakes pin 5.020 with apt in
a file of their own, so that changing project/toolchain.properties stays a one-line change.
"""

import datetime
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "toolchain-candidates.py")
spec = importlib.util.spec_from_file_location("toolchain_candidates", SCRIPT)
tc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tc)

COMMIT_032 = "8ff77e9d47351b0a59114929880687839a51840b"
COMMIT_052 = "ea338be98e1e838d3518809ce8899f85a009963c"
TAG_052 = "efa4927be48e75c3cd08fc848b198d1d9d237f00"
PINNED = {"verilator.version": "5.020", "verilator.apt": "5.020-1", "verilator.commit": "", "jdk.major": "21"}
NOW = datetime.datetime(2026, 10, 18, tzinfo=datetime.timezone.utc)
failures = []


def check(condition, what):
    if not condition:
        failures.append(what)
        print(f"FAIL: {what}", file=sys.stderr)


def knobs(**overrides):
    env = dict(tc.DEFAULTS)
    env.update(overrides)
    return env


def ls_remote(refs):
    tags = {"refs/tags/v5.032^{}": COMMIT_032, "refs/tags/v5.032": "1" * 40,
            "refs/tags/v5.052^{}": COMMIT_052, "refs/tags/v5.052": TAG_052, "refs/tags/v5.999": "2" * 40}
    return {r: tags[r] for r in refs if r in tags}


def tag_date(tag):
    return {TAG_052: "2026-10-10T08:00:00Z", "1" * 40: "2025-01-01T00:00:00Z"}.get(tag)


def plan_of(**overrides):
    return tc.make_plan(knobs(**overrides), PINNED, ls_remote, tag_date, NOW)


def plan_error(**overrides):
    try:
        plan_of(**overrides)
    except tc.Failure as e:
        return str(e)
    return ""


def test_plan():
    plan = plan_of()
    versions = [(e["version"], e["kind"], e["verilator"], e["commit"]) for e in plan["versions"]]
    check(versions == [("5.020", "pinned", "", ""), ("5.032", "candidate", "5.032", COMMIT_032),
                       ("5.052", "candidate", "5.052", COMMIT_052)], f"default versions: {versions}")
    check(plan["parts"] == {"test": True, "timing": False, "mutation": False}, f"default parts: {plan['parts']}")
    check(plan["seed_runs"] == 1 and plan["timing"] == [] and plan["mutation"] == [], "default runs and parts")
    check(not plan["warnings"][1:] and any("2026-10-10, 7 days ago" in w for w in plan["warnings"]),
          f"a tag younger than two weeks warns: {plan['warnings']}")
    check(plan["versions"][1]["tag_date"] == "2025-01-01", "the tag date of 5.032")
    out = tc.plan_outputs(plan)
    check(json.loads(out["versions"])[0] == {"version": "5.020", "kind": "pinned", "method": "apt", "verilator": "",
                                             "commit": ""}, f"the pinned matrix entry: {out['versions']}")
    check(out["runs"] == "[1]" and out["test"] == "true" and out["timing"] == "false" and out["mutation"] == "[]",
          f"default outputs: {out}")
    check(all("\n" not in v for v in out.values()), "outputs on one line")

    # The decision run of #121.
    plan = plan_of(MUTATION="5.020 5.052", SEED_RUNS="3", TIMING="true")
    out = tc.plan_outputs(plan)
    check(plan["timing"] == ["5.020", "5.032", "5.052"] and out["timing_count"] == "3", f"timing: {plan['timing']}")
    check([e["version"] for e in json.loads(out["mutation"])] == ["5.020", "5.052"], f"mutation: {out['mutation']}")
    check(out["runs"] == "[1,2,3]", f"runs: {out['runs']}")
    check(any("not the defaults" in w for w in plan["warnings"]), "non-default knobs warn")

    # Duplicates run once; the pinned version is the control and never a candidate; apt candidates.
    plan = plan_of(CANDIDATES=" 5.052  5.052 5.020 apt:5.020-1 apt:5.032-1 ")
    check([e["version"] for e in plan["versions"]] == ["5.020", "5.052", "5.032"], f"dedup: {plan['versions']}")
    check(plan["versions"][2]["verilator"] == "apt:5.032-1" and plan["versions"][2]["commit"] == "", "apt candidate")
    check(len([n for n in plan["notes"] if "pinned version" in n]) == 2
          and any("more than once" in n for n in plan["notes"]),
          f"notes: {plan['notes']}")
    plan = plan_of(CANDIDATES="", PARTS="test")
    check([e["version"] for e in plan["versions"]] == ["5.020"], "no candidate: the control alone")
    # A re-run of one part.
    plan = plan_of(MUTATION="5.052", TIMING="true", PARTS="mutation")
    check(plan["parts"] == {"test": False, "timing": False, "mutation": True}, f"PARTS=mutation: {plan['parts']}")
    plan = plan_of(TIMING="true", PARTS="timing test")
    check(plan["parts"] == {"test": True, "timing": True, "mutation": False}, f"PARTS=timing test: {plan['parts']}")
    # Timing leaves out apt candidates, and needs a source candidate.
    plan = plan_of(CANDIDATES="5.052 apt:5.032-1", TIMING="true")
    check(plan["timing"] == ["5.020", "5.052"] and any("leaves out apt:5.032-1" in n for n in plan["notes"]),
          f"timing without apt: {plan['timing']}")

    # Every malformed knob fails, with all errors at once.
    cases = [
        ({"CANDIDATES": "5.52"}, "CANDIDATES: '5.52' is neither"),
        ({"CANDIDATES": "v5.052"}, "CANDIDATES: 'v5.052' is neither"),
        ({"CANDIDATES": "apt:5.032"}, "CANDIDATES: 'apt:5.032' is neither"),
        ({"CANDIDATES": "5.052\t5.032"}, "CANDIDATES='5.052\\t5.032': only"),
        ({"CANDIDATES": "5.052;x"}, "only letters"),
        ({"CANDIDATES": "5.032 apt:5.032-1"}, "are both Verilator 5.032"),
        ({"CANDIDATES": "5.032 5.052 5.054 5.056"}, "at most 3"),
        ({"CANDIDATES": "5.999"}, "has no annotated tag v5.999"),
        ({"CANDIDATES": "5.998"}, "has no annotated tag v5.998"),
        ({"MUTATION": "true"}, "MUTATION: 'true' is not a version"),
        ({"MUTATION": "5.040"}, "MUTATION: 5.040 is neither the pinned version 5.020 nor a candidate (5.032 5.052)"),
        ({"MUTATION": "5.052 5.052"}, "MUTATION lists 5.052 twice"),
        ({"MUTATION": "apt:5.020-1"}, "MUTATION: 'apt:5.020-1' is not a version"),
        ({"SEED_RUNS": "0"}, "SEED_RUNS='0'"),
        ({"SEED_RUNS": "11"}, "SEED_RUNS='11'"),
        ({"SEED_RUNS": "03"}, "SEED_RUNS='03'"),
        ({"SEED_RUNS": ""}, "SEED_RUNS=''"),
        ({"TIMING": "yes"}, "TIMING='yes'"),
        ({"TIMING": "True"}, "TIMING='True'"),
        ({"PARTS": "build"}, "PARTS: 'build' is not a part"),
        ({"PARTS": "test test"}, "PARTS lists test twice"),
        ({"PARTS": "timing"}, "PARTS names timing, but TIMING is not true"),
        ({"PARTS": "mutation"}, "PARTS names mutation, but MUTATION names no version"),
        ({"CANDIDATES": "apt:5.032-1", "TIMING": "true"}, "TIMING=true needs a candidate built from the source"),
    ]
    for overrides, message in cases:
        error = plan_error(**overrides)
        check(message in error, f"{overrides}: expected '{message}', got '{error}'")
    env = knobs()
    del env["PARTS"]
    try:
        tc.make_plan(env, PINNED, ls_remote, tag_date, NOW)
        check(False, "a missing knob passed")
    except tc.Failure as e:
        check("PARTS is not set" in str(e), f"a missing knob: {e}")
    error = plan_error(SEED_RUNS="x", TIMING="x")
    check("SEED_RUNS" in error and "TIMING" in error, f"all errors at once: {error}")


def fake_bin(work, name, text):
    path = os.path.join(work, "bin", name)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)
    os.chmod(path, 0o755)
    return path


def properties(work):
    path = os.path.join(work, "toolchain.properties")
    with open(path, "w") as f:
        f.write("verilator.version=5.020\nverilator.apt=5.020-1\nverilator.commit=\njdk.major=21\n")
    return path


def run(args, env=None, cwd=None):
    full = dict(os.environ)
    for name in tc.KNOBS + ("GITHUB_ACTIONS", "VLIW_VERILATOR_EXPECTED", "VLIW_SIM_SEED", "PLAN", "NEEDS"):
        full.pop(name, None)
    full.update(env or {})
    p = subprocess.run([sys.executable, "-I", SCRIPT] + args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                       universal_newlines=True, env=full, cwd=cwd)
    return p.returncode, p.stdout


def test_plan_command(work):
    # git ls-remote and gh, faked: the arguments go to a log.
    log = os.path.join(work, "fake.log")
    fake_bin(work, "git", f"""#!/bin/sh
echo "git $*" >>"{log}"
[ "$1" = ls-remote ] || exit 99
for ref; do
  case "$ref" in
    'refs/tags/v5.052^{{}}') printf '{COMMIT_052}\\t%s\\n' "$ref" ;;
    refs/tags/v5.052) printf '{TAG_052}\\t%s\\n' "$ref" ;;
  esac
done
""")
    fake_bin(work, "gh", f"""#!/bin/sh
echo "gh $*" >>"{log}"
echo 2026-09-05T10:00:00Z
""")
    output = os.path.join(work, "github-output")
    open(output, "w").close()
    env = knobs(CANDIDATES="5.052", PATH=os.path.join(work, "bin") + os.pathsep + os.environ["PATH"],
                GITHUB_OUTPUT=output)
    code, out = run(["plan", "--file", properties(work), "--now", "2026-10-18T00:00:00Z"], env)
    check(code == 0, f"plan exit {code}: {out}")
    check("candidate 5.052  source " + COMMIT_052 + ", tag v5.052 of 2026-09-05" in out, f"plan output: {out}")
    lines = dict(line.split("=", 1) for line in open(output).read().splitlines())
    check(set(lines) == {"plan", "versions", "runs", "test", "timing", "timing_versions", "timing_count", "mutation"},
          f"outputs: {sorted(lines)}")
    check(json.loads(lines["versions"])[1]["commit"] == COMMIT_052, f"versions: {lines['versions']}")
    calls = open(log).read()
    check("git ls-remote https://github.com/verilator/verilator refs/tags/v5.052 refs/tags/v5.052^{}" in calls,
          f"ls-remote arguments: {calls}")
    check(f"gh api repos/verilator/verilator/git/tags/{TAG_052} --jq .tagger.date" in calls, f"gh: {calls}")
    # A missing tag fails the plan with an annotation, and writes no output.
    open(output, "w").close()
    env.update(CANDIDATES="5.032", GITHUB_ACTIONS="true")
    code, out = run(["plan", "--file", properties(work)], env)
    check(code == 1 and "::error::CANDIDATES: 5.032: https://github.com/verilator/verilator has no annotated tag" in out,
          f"missing tag: {code} {out}")
    check(open(output).read() == "", "outputs written for a failed plan")


SBT_PASSED = """\x1b[0m[\x1b[0m\x1b[0minfo\x1b[0m] \x1b[0m\x1b[0mTests: succeeded 59, failed 0, canceled 0, \
ignored 0, pending 0\x1b[0m
[info] Suites: completed 8, aborted 0
[info] All tests passed.
[sim-seed] VLIW_SIM_SEED=1 (default) verilator=5.052
[toolchain] verilator=5.052 expected=5.052 (env)
[sim-seed] VLIW_SIM_SEED=1 (default) verilator=5.052
[toolchain] verilator=5.052 expected=5.052 (env)
[info] Tests: succeeded 321, failed 0, canceled 0, ignored 14, pending 0
[info] Suites: completed 37, aborted 0
[success] Total time: 500 s
"""
SBT_FAILED = """[sim-seed] VLIW_SIM_SEED=1234 (random) verilator=5.052
[toolchain] verilator=5.052 expected=5.052 (env)
[info] Tests: succeeded 300, failed 2, canceled 0, ignored 14, pending 0
[info] Suites: completed 36, aborted 1
[error] Failed tests:
[error] \tvliw.hw.CaseSpec
[error] \tvliw.crosscheck.RtlDifferentialSpec
[error] Error during tests:
[error] \tvliw.hw.CoreSpec
[error] (hw / Test / test) sbt.TestsFailedException: Tests unsuccessful
"""


def test_classify():
    expected = tc.expected_toolchain("5.052", "candidate")
    check(expected == "verilator=5.052 expected=5.052 (env)", expected)
    check(tc.expected_toolchain("5.020", "pinned") == "verilator=5.020 expected=5.020 (pinned)", "pinned line")
    facts = tc.parse_output(SBT_PASSED)
    check(facts["tests"] == {"succeeded": 380, "failed": 0, "canceled": 0, "ignored": 14, "pending": 0},
          f"counts (with sbt's colours): {facts['tests']}")
    check(facts["suites"] == {"completed": 45, "aborted": 0}, f"suites: {facts['suites']}")
    check(facts["seeds"] == ["VLIW_SIM_SEED=1 (default) verilator=5.052"], f"seeds: {facts['seeds']}")
    check(tc.classify_output(0, facts, expected) == ("success", "380 tests passed"), "success")
    cls, detail = tc.classify_output(0, facts, tc.expected_toolchain("5.052", "pinned"))
    check(cls == "setup" and "(env)" in detail, f"a candidate's line for the control: {cls} {detail}")
    other = SBT_PASSED.replace("verilator=5.052 expected", "verilator=5.020 expected")
    cls, detail = tc.classify_output(0, tc.parse_output(other), expected)
    check(cls == "setup", f"another Verilator on the PATH: {cls} {detail}")
    no_lines = "\n".join(line for line in SBT_PASSED.splitlines() if "[toolchain]" not in line)
    check(tc.classify_output(0, tc.parse_output(no_lines), expected)[0] == "setup", "no [toolchain] line")
    facts = tc.parse_output(SBT_FAILED)
    check(facts["failed_suites"] == ["vliw.hw.CaseSpec", "vliw.crosscheck.RtlDifferentialSpec", "vliw.hw.CoreSpec"],
          f"failed suites: {facts['failed_suites']}")
    check(tc.classify_output(1, facts, expected) ==
          ("test-failure", "failed: vliw.hw.CaseSpec, vliw.crosscheck.RtlDifferentialSpec, vliw.hw.CoreSpec"),
          f"test-failure: {tc.classify_output(1, facts, expected)}")
    cases = [
        (1, "[error] (compiler / Compile / compileIncremental) Compilation failed\n", "compile"),
        (1, "[error] (crosscheck / Test / scalafmtCheck) scalafmt: 1 files must be formatted (/x)\n", "compile"),
        (1, "[error] (scalafmtSbtCheck) scalafmt: 1 files must be formatted\n", "compile"),
        (124, SBT_FAILED + "check: sbt stopped after CHECK_TIMEOUT=780 s\n", "timeout"),
        (137, "check: sbt stopped after CHECK_TIMEOUT=780 s\n", "timeout"),
        (1, "toolchain: the Verilator on the PATH is 5.020, expected 5.052 (env)\n"
            "check: scripts/toolchain.sh check failed (above), so sbt did not run; logs in x\n", "setup"),
        (1, "[toolchain] verilator=5.052 expected=5.052 (env)\nWarning: Unable to read from client, please\n",
         "other"),
        (1, "[toolchain] verilator=5.052 expected=5.052 (env)\n[error] java.lang.OutOfMemoryError\n", "other"),
        (0, "[toolchain] verilator=5.052 expected=5.052 (env)\n", "other"),
        (1, "[toolchain] verilator=5.052 expected=5.052 (env)\n[info] Tests: succeeded 1, failed 1, canceled 0, ignored 0,"
            " pending 0\n", "test-failure"),
    ]
    for code, text, want in cases:
        got = tc.classify_output(code, tc.parse_output(text), expected)
        check(got[0] == want, f"exit {code} {text[:60]!r}: expected {want}, got {got}")
    facts = tc.parse_output(SBT_PASSED)
    steps = [
        (("failure", "skipped", None), "setup"), (("cancelled", "skipped", None), "cancelled"),
        (("success", "cancelled", None), "cancelled"), (("success", "skipped", None), "skipped"),
        (("success", "", None), "skipped"), (("success", "failure", None), "timeout"),
        (("success", "success", None), "other"), (("", "", None), "setup"), (("success", "success", 0), "success"),
    ]
    for (setup, step, code), want in steps:
        got = tc.classify_step(setup, step, code, facts, expected)
        check(got[0] == want, f"setup {setup!r} step {step!r} exit {code}: expected {want}, got {got}")


def test_summary(work):
    """A test job's summary from the run wrapper's directory, with a fake toolchain."""
    root = os.path.join(work, "checkout")
    os.makedirs(os.path.join(root, "run"))
    with open(os.path.join(root, "run", "output.log"), "w") as f:
        f.write(SBT_FAILED)
    for name, value in (("exit", "1"), ("seconds", "512.4"), ("start", "100")):
        with open(os.path.join(root, "run", name), "w") as f:
            f.write(value + "\n")
    logs = os.path.join(root, "build", "chiselsim", "CaseSpec", "t", "workdir-verilator")
    os.makedirs(logs)
    with open(os.path.join(logs, "compilation-log.txt"), "w") as f:
        f.write("%Warning-WIDTH: a.sv:1: x\n%Warning-UNOPTFLAT: b.sv:2: y\n%Warning-WIDTH: c.sv:3: z\n")
    bins = os.path.join(work, "summary-bin")
    os.makedirs(os.path.join(work, "prefix", "bin"))
    for name, text in (("verilator", "#!/bin/sh\necho 'Verilator 5.052 2026-09-05 rev v5.052 (fake)'\n"),
                       ("verilator_bin", "not a real verilator_bin\n")):
        path = os.path.join(work, "prefix", "bin", name)
        with open(path, "w") as f:
            f.write(text)
        os.chmod(path, 0o755)
    with open(os.path.join(work, "prefix", "build-info.txt"), "w") as f:
        f.write("version: 5.052\nresult: success\n")
    os.makedirs(bins)
    with open(os.path.join(bins, "java"), "w") as f:
        f.write("#!/bin/sh\necho 'openjdk version \"21.0.8\" 2025-07-15 LTS' >&2\n")
    os.chmod(os.path.join(bins, "java"), 0o755)
    start = os.path.join(work, "job-start")
    with open(start, "w") as f:
        f.write(f"{os.path.getmtime(os.path.join(work, 'prefix', 'build-info.txt')) + 10:.0f}\n")
    entry = {"version": "5.052", "kind": "candidate", "method": "source", "verilator": "5.052", "commit": COMMIT_052}
    out_file = os.path.join(work, "summary", "test.json")
    env = {"PATH": os.pathsep.join([os.path.join(work, "prefix", "bin"), bins, os.environ["PATH"]]),
           "VLIW_SIM_SEED": "random", "CHECK_TIMEOUT": "780", "GITHUB_RUN_ATTEMPT": "2", "ImageOS": "ubuntu24",
           "ImageVersion": "20261012.1", "VLIW_VERILATOR_EXPECTED": "5.052"}
    code, out = run(["summary", "test", "--entry", json.dumps(entry), "--run", "2", "--setup", "success", "--step",
                     "failure", "--dir", "run", "--out", out_file, "--start", start,
                     "--toolchain-file", properties(work)], env, cwd=root)
    check(code == 1, f"summary of a failed run exits 1, got {code}: {out}")
    line = [x for x in out.splitlines() if x.startswith(tc.SUMMARY_PREFIX)]
    check(len(line) == 1, f"one summary line: {out}")
    s = json.loads(line[0][len(tc.SUMMARY_PREFIX):]) if line else {}
    check(s == json.load(open(out_file)), "the summary line and the file agree")
    check(s.get("class") == "test-failure" and s.get("run") == 2 and s.get("attempt") == 2, f"summary: {s}")
    check(s.get("failed_suites") == ["vliw.hw.CaseSpec", "vliw.crosscheck.RtlDifferentialSpec", "vliw.hw.CoreSpec"],
          f"suites: {s.get('failed_suites')}")
    check(s.get("warnings") == {"UNOPTFLAT": 1, "WIDTH": 2} and s.get("builds") == 1, f"warnings: {s.get('warnings')}")
    check(s.get("seeds") == ["VLIW_SIM_SEED=1234 (random) verilator=5.052"] and s.get("seed_env") == "random", "seeds")
    check(s.get("exit") == 1 and s.get("seconds") == 512.4 and s.get("check_timeout") == "780", "exit and seconds")
    check(s.get("image") == "ubuntu24 20261012.1" and s.get("nproc", 0) >= 1 and s.get("cpu"), f"runner: {s}")
    prints = s.get("fingerprint") or []
    check(f"verilator: {os.path.join(work, 'prefix', 'bin', 'verilator')}" in prints, f"fingerprint: {prints}")
    check("build-info: version: 5.052" in prints, f"build-info: {prints}")
    check(any(p.startswith("cache: restored (") for p in prints), f"cache: {prints}")
    check('java -version: openjdk version "21.0.8" 2025-07-15 LTS' in prints, f"java: {prints}")
    check(any(p.startswith("verilator_bin sha256: ") for p in prints), f"sha256: {prints}")


def test_timing(work):
    """The timing runs: the order A B C C B A, each version's prefix alone on the PATH, the expected
    version of each, and the ratios."""
    prefixes = {}
    for v in ("5.032", "5.052"):
        prefixes[v] = os.path.join(work, f"verilator-{v}", "bin")
        os.makedirs(prefixes[v])
    installed = os.path.join(work, "installed.tsv")
    with open(installed, "w") as f:
        f.write("5.020\t/usr/bin/verilator\n")
        for v, d in prefixes.items():
            f.write(f"{v}\t{d}/verilator\n")
    record = os.path.join(work, "timing-calls.log")
    # A fake check.sh: logs its arguments, the first prefix of the PATH and the expected version, and
    # prints sbt's summary with the [toolchain] line of that version (5.052 fails its second run).
    check_sh = fake_bin(work, "check.sh", f"""#!/bin/sh
first="${{PATH%%:*}}"
case "$first" in */verilator-5.0*) v="${{first%/bin}}"; v="${{v##*verilator-}}" ;; *) v=5.020 ;; esac
echo "$* | $v | ${{VLIW_VERILATOR_EXPECTED:-unset}} | ${{VLIW_SIM_SEED:-unset}} | $CHECK_LOG_DIR" >>"{record}"
[ "$1 $2" = "sbt Test/compile" ] && exit 0
sleep 0.2
src=pinned; [ "$v" = 5.020 ] || src=env
echo "[toolchain] verilator=$v expected=$v ($src)"
n=$(grep -c "sbt test | $v " "{record}")
if [ "$v" = 5.052 ] && [ "$n" = 2 ]; then
  echo '[info] Tests: succeeded 1, failed 1, canceled 0, ignored 0, pending 0'
  printf '[error] Failed tests:\\n[error] \\tvliw.hw.CaseSpec\\n'
  exit 1
fi
echo '[info] Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0'
""")
    versions = [{"version": "5.020", "kind": "pinned", "method": "apt", "verilator": "", "commit": ""},
                {"version": "5.032", "kind": "candidate", "method": "source", "verilator": "5.032", "commit": COMMIT_032},
                {"version": "5.052", "kind": "candidate", "method": "source", "verilator": "5.052", "commit": COMMIT_052}]
    path = os.pathsep.join([prefixes["5.052"], prefixes["5.032"], "/usr/bin", os.environ["PATH"]])
    directory = os.path.join(work, "timing")
    code, out = run(["timing", "--versions", json.dumps(versions), "--installed", installed, "--dir", directory,
                     "--check", check_sh], {"PATH": path, "VLIW_VERILATOR_EXPECTED": "5.052", "VLIW_SIM_SEED": "random"})
    check(code == 1, f"timing with a failed run exits 1, got {code}: {out}")
    calls = [line.split(" | ") for line in open(record).read().splitlines()]
    check([c[0] for c in calls] == ["sbt Test/compile"] + ["sbt test"] * 6, f"commands: {calls}")
    check([c[1] for c in calls] == ["5.020", "5.020", "5.032", "5.052", "5.052", "5.032", "5.020"], f"order: {calls}")
    check([c[2] for c in calls] == ["unset", "unset", "5.032", "5.052", "5.052", "5.032", "unset"], f"expected: {calls}")
    check(all(c[3] == "unset" for c in calls), "the default seed in every run")
    check(calls[3][4] == os.path.join(directory, "runs", "3-5.052", "check-logs"), f"CHECK_LOG_DIR: {calls[3]}")
    doc = json.load(open(os.path.join(directory, "runs.json")))
    check([r["class"] for r in doc["runs"]] == ["success"] * 3 + ["test-failure"] + ["success"] * 2,
          f"classes: {[r['class'] for r in doc['runs']]}")
    check(doc["runs"][3]["failed_suites"] == ["vliw.hw.CaseSpec"], f"failed suite: {doc['runs'][3]}")
    # The summary step, from runs.json.
    out_file = os.path.join(work, "summary", "timing.json")
    code, out = run(["summary", "timing", "--versions", json.dumps(versions), "--setup",
                     "success success success skipped", "--step", "failure", "--dir", directory, "--out", out_file])
    s = json.load(open(out_file))
    check(code == 1 and s["class"] == "test-failure", f"timing summary: {code} {s.get('class')} {out}")
    per = s["per_version"]
    check(per["5.032"]["class"] == "success" and per["5.032"]["ratio"] is not None, f"5.032: {per['5.032']}")
    check(per["5.052"]["class"] == "test-failure" and per["5.052"]["ratio"] is None, f"5.052: {per['5.052']}")
    check(per["5.052"]["failed_suites"] == ["vliw.hw.CaseSpec"], f"5.052 suites: {per['5.052']}")
    # A failed setup step stops every version; a stopped step leaves the versions without two runs.
    s = tc.summarize_timing(Args(versions=json.dumps(versions), setup="success failure skipped", step="skipped",
                                 dir=directory))
    check(s["class"] == "setup" and s["blocked"] and all(v["class"] == "setup" for v in s["per_version"].values()),
          f"setup failure: {s['class']} {s['per_version']}")
    doc["runs"] = doc["runs"][:2]
    with open(os.path.join(directory, "runs.json"), "w") as f:
        json.dump(doc, f)
    s = tc.summarize_timing(Args(versions=json.dumps(versions), setup="success success success", step="failure",
                                 dir=directory))
    check(s["class"] == "timeout" and s["per_version"]["5.020"]["class"] == "timeout"
          and s["per_version"]["5.032"]["class"] == "timeout", f"stopped: {s['per_version']}")
    times = {"runs": [{"position": i + 1, "version": v, "seconds": t, "class": "success", "detail": ""}
                      for i, (v, t) in enumerate((("5.020", 100.0), ("5.052", 130.0), ("5.052", 110.0), ("5.020", 100.0)))],
             "versions": versions[:1] + versions[2:], "class": "success", "detail": "", "blocked": False}
    per = tc.timing_versions(times)
    check(per["5.052"]["ratio"] == 1.2 and per["5.020"]["ratio"] == 1.0, f"ratios: {per}")


class Args:
    def __init__(self, **kw):
        self.__dict__.update(kw)


def summary(part, version, cls, detail="", run=0, **extra):
    s = {"schema": 1, "part": part, "version": version, "kind": "pinned" if version == "5.020" else "candidate",
         "class": cls, "detail": detail or cls, "attempt": 1, "cpu": "AMD EPYC 7763 64-Core Processor", "nproc": 4,
         "image": "ubuntu24 20261012.1"}
    if run:
        s["run"] = run
    s.update(extra)
    return s


def mutation_logs(root, name, shard, n, verdicts, baseline=("SURVIVED",), version="5.020"):
    """A shard's logs as mutation-check.sh leaves them (env.txt, results.tsv, _baseline*/verdict)."""
    d = os.path.join(root, name) if name else root
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, "env.txt"), "w") as f:
        f.write(f"expected: verilator {version} (pinned)\nshard: {shard}/{n}\n")
    for i, verdict in enumerate(baseline):
        b = os.path.join(d, "_baseline" + (f".retry{i}" if i else ""))
        os.makedirs(b)
        with open(os.path.join(b, "verdict"), "w") as f:
            f.write(f"{verdict}\t{'passed' if verdict == 'SURVIVED' else 'sim-build'}\tx\n")
    if verdicts is not None:
        with open(os.path.join(d, "results.tsv"), "w") as f:
            f.write("id\tverdict\tcategory\tdetail\tlog\n")
            for mutant, verdict, category in verdicts:
                f.write(f"{mutant}\t{verdict}\t{category}\tdetail\t{d}/{mutant}\n")
                os.makedirs(os.path.join(d, mutant))


def test_report(work):
    mutants = os.path.join(work, "mutants.tsv")
    with open(mutants, "w") as f:
        f.write("# id\tfile\n" + "".join(f"m{i}\tf\to\tm\twhat\n" for i in range(1, 5)))
    plan = plan_of(MUTATION="5.020 5.052", SEED_RUNS="2", TIMING="true")
    summaries = os.path.join(work, "summaries")
    os.makedirs(summaries)
    fp = ["pinned: verilator 5.020 (apt 5.020-1), jdk 21", "verilator_bin sha256: aaa"]
    items = [
        summary("build", "5.020", "success", fingerprint=fp),
        summary("build", "5.032", "success", fingerprint=["verilator_bin sha256: bbb"]),
        summary("build", "5.052", "success", fingerprint=["verilator_bin sha256: ccc"]),
        summary("test", "5.020", "success", run=1, seconds=500.0, exit=0,
                seeds=["VLIW_SIM_SEED=1 (default) verilator=5.020"],
                tests={"succeeded": 380, "failed": 0, "canceled": 0, "ignored": 14, "pending": 0},
                suites={"completed": 45, "aborted": 0}, builds=53, warnings={}, fingerprint=fp, check_timeout="780"),
        summary("test", "5.020", "success", run=2, seconds=510.0, exit=0),
        summary("test", "5.032", "success", run=1, fingerprint=["verilator_bin sha256: bbb"]),
        summary("test", "5.032", "setup", "the setup-toolchain action ended with failure", run=2),
        summary("test", "5.052", "test-failure", "failed: vliw.hw.CaseSpec", run=1, failed_suites=["vliw.hw.CaseSpec"],
                failures=[["vliw.hw.CaseSpec", "a test", "env:sim-exit", "simulation exited with 1"]],
                warnings={"WIDTH": 3}),
        summary("test", "5.052", "success", run=2),
    ]
    timing_doc = {"part": "timing", "class": "success", "detail": "every run passed", "attempt": 1, "blocked": False,
                  "versions": [{"version": v} for v in ("5.020", "5.032", "5.052")], "cpu": "x", "nproc": 4, "image": "y",
                  "runs": [{"position": i + 1, "version": v, "seconds": t, "exit": 0, "class": "success", "detail": ""}
                           for i, (v, t) in enumerate((("5.020", 100.0), ("5.032", 101.0), ("5.052", 120.0),
                                                       ("5.052", 140.0), ("5.032", 99.0), ("5.020", 100.0)))]}
    timing_doc["per_version"] = tc.timing_versions(timing_doc)
    items.append(timing_doc)
    # An earlier attempt is overridden by a later one.
    items.append(dict(summary("test", "5.020", "other", run=2), attempt=0))
    for i, s in enumerate(items):
        with open(os.path.join(summaries, f"{i}.json"), "w") as f:
            json.dump(s, f)
    mutation = os.path.join(work, "mutation")
    for k in (1, 2):
        mine = [(m, "KILLED", "assertion") for m in tc.shard_mutants(mutants, k, 2)]
        mutation_logs(mutation, f"mutation-logs-verilator-5.020-apt-5.020-1-shard{k}-attempt1", k, 2, mine,
                      baseline=("ERROR", "SURVIVED") if k == 2 else ("SURVIVED",))
        theirs = [(m, "ERROR" if m == "m3" else "KILLED", "sim-exit" if m == "m3" else "assertion") for m in
                  tc.shard_mutants(mutants, k, 2)]
        mutation_logs(mutation, f"mutation-logs-verilator-5.052-ea338be98e1e-shard{k}-attempt1", k, 2, theirs,
                      version="5.052")
    records = os.path.join(work, "records")
    env = {"PLAN": json.dumps(plan), "GITHUB_RUN_ID": "1000", "GITHUB_RUN_ATTEMPT": "1", "GITHUB_REPOSITORY": "o/r",
           "GITHUB_EVENT_NAME": "pull_request", "GITHUB_SHA": "m" * 40, "PR_NUMBER": "130", "PR_HEAD_SHA": "h" * 40,
           "REPORT_DATE": "2026-10-18",
           "NEEDS": json.dumps({"plan": {"result": "success"}, "build": {"result": "success"},
                                "test": {"result": "failure"}, "timing": {"result": "success"},
                                "mutation": {"result": "failure"}})}
    args = ["report", "--summaries", summaries, "--mutation", mutation, "--records", records, "--mutants", mutants]
    code, out = run(args, env)
    check(code == 0, f"report exit {code}: {out}")
    check("==> toolchain-candidates report: run 1000 attempt 1 (pull_request #130, head " + "h" * 40 in out, "header")
    table = [line for line in out.splitlines() if line.startswith(("version ", "5.020 ", "5.032 ", "5.052 "))]
    check(len(table) == 4 and table[0].split() == ["version", "role", "build", "test", "timing", "mutation"],
          f"table: {table}")
    check(table[1].split()[:4] == ["5.020", "control", "success", "success"]
          and "[4 KILLED, 0 SURVIVED, 0 other]" in table[1],
          f"control row: {table[1]}")
    check("setup run 2 (inconclusive, re-run)" in table[2] and "r=1.000" in table[2], f"5.032 row: {table[2]}")
    check("test-failure run 1" in table[3] and "r=1.300" in table[3] and "other (inconclusive, re-run)" in table[3],
          f"5.052 row: {table[3]}")
    check("- 5.032 test: inconclusive (re-run with PARTS=\"test\"): 5.032: run 2: setup" in out, f"5.032 verdict: {out}")
    check("- 5.052 test: candidate-only failure: vliw.hw.CaseSpec failed on 5.052 and not on the control" in out
          and "commit these records, then re-run it with PARTS=\"test\"" in out, "5.052 candidate-only failure")
    check("- 5.052 mutation: inconclusive (re-run with PARTS=\"mutation\"): 5.052: ERROR sim-exit x1" in out,
          "5.052 mutation inconclusive")
    check("shard 2: baseline: passed on a retry; the first run ended with ERROR sim-build" in out, "baseline retry")
    check("#121 §5: 555 s x 1.300 x 1.1 = 794 s, OVER CHECK_TIMEOUT 780 s" in out, "timing criterion")
    check("    vliw.hw.CaseSpec / a test: env:sim-exit: simulation exited with 1" in out, "junit failure kinds")
    files = records_of(out)
    check(sorted(files) == ["docs/toolchain/5.020/mutation.tsv", "docs/toolchain/5.020/report.md",
                            "docs/toolchain/5.032/report.md", "docs/toolchain/5.052/report.md"], f"records: {sorted(files)}")
    check(files.get("docs/toolchain/5.020/mutation.tsv") ==
          "mutant\tverdict\tcategory\n" + "".join(f"m{i}\tKILLED\tassertion\n" for i in range(1, 5)),
          f"mutation.tsv: {files.get('docs/toolchain/5.020/mutation.tsv')!r}")
    check(out.rstrip().endswith("===END==="), "the records come last")
    md52 = files.get("docs/toolchain/5.052/report.md", "")
    check(md52.startswith("# Verilator 5.052（候補）の記録\n"), "report.md title")
    check(f"commit `{COMMIT_052}`" in md52 and "[1000](https://github.com/o/r/actions/runs/1000)" in md52, "report.md run")
    check("pull_request #130（head `" + "h" * 40 + "`、merge commit `" + "m" * 40 + "`）" in md52, "report.md trigger")
    check("| 1 | — | test-failure（failed: vliw.hw.CaseSpec） |" in md52 and "WIDTH 3" in md52, "report.md test row")
    check("mutation.tsv は出していない" in md52, "no mutation.tsv for an inconclusive mutation part")
    md20 = files.get("docs/toolchain/5.020/report.md", "")
    check("verilator_bin sha256: aaa" in md20 and "test job の `verilator_bin` の sha256: すべて build job と同じ。" in md20,
          "report.md fingerprint")
    record = [json.loads(line[len("<!-- toolchain-candidates: "):-len(" -->")]) for line in md52.splitlines()
              if line.startswith("<!-- toolchain-candidates: ")]
    check(len(record) == 1
          and record[0]["parts"]["test"] == {"class": "test-failure", "candidate_only": ["vliw.hw.CaseSpec"]},
          f"machine record: {record}")

    # Committed from that run, the records make a re-run of the same part (PARTS=test) that fails the
    # same way a regression.
    os.makedirs(os.path.join(records, "5.052"))
    with open(os.path.join(records, "5.052", "report.md"), "w") as f:
        f.write(md52)
    rerun = plan_of(PARTS="test", SEED_RUNS="2")
    env.update(PLAN=json.dumps(rerun), GITHUB_RUN_ID="1001")
    code, out = run(args, env)
    check(code == 0 and "- 5.052 test: REGRESSION of 5.052: vliw.hw.CaseSpec failed on 5.052 and not on the control in"
          " run 1000 and again in this run" in out, f"regression: {out}")
    check("PARTS: this run re-runs test only" in out, "PARTS noted")
    # Without the failure in the re-run, there is no regression.
    os.remove(os.path.join(summaries, "7.json"))
    code, out = run(args, env)
    check("- 5.052 test: success" not in out and "REGRESSION" not in out, f"no regression without the failure: {out}")
    check('- 5.052 test: inconclusive (re-run with PARTS="test"): 5.052: run 1: other (no summary from the test job'
          in out, f"a missing summary is inconclusive: {out}")
    # A failed plan: the report says so and fails.
    env.pop("PLAN")
    code, out = run(args, env)
    check(code == 1 and "the plan job ended with success" in out, f"no plan: {code} {out}")


def test_mutation_layouts(work):
    """One artifact lands straight in the directory: the version and shard come from env.txt."""
    mutants = os.path.join(work, "mutants-one.tsv")
    with open(mutants, "w") as f:
        f.write("a\tf\to\tm\tw\nb\tf\to\tm\tw\n")
    single = os.path.join(work, "single")
    mutation_logs(single, "", 1, 1, [("a", "KILLED", "assertion"), ("b", "SURVIVED", "passed")], version="5.052")
    found, notes = tc.load_mutation(single)
    check(list(found) == ["5.052"] and list(found["5.052"]) == [1] and not notes, f"single artifact: {found} {notes}")
    m = tc.mutation_part(found["5.052"], mutants)
    check(m["class"] == "test-failure" and m["failed"] == {"b"} and "SURVIVED: b" in m["detail"], f"survived: {m}")
    # A shard without results, a missing shard, a stopped shard, a later attempt, a failed baseline.
    many = os.path.join(work, "many")
    mutation_logs(many, "mutation-logs-verilator-5.052-x-shard1-attempt1", 1, 3, None, baseline=("KILLED",),
                  version="5.052")
    mutation_logs(many, "mutation-logs-verilator-5.052-x-shard2-attempt1", 2, 3, [], version="5.052")
    mutation_logs(many, "mutation-logs-verilator-5.052-x-shard2-attempt2", 2, 3, [("b", "KILLED", "assertion")],
                  version="5.052")
    os.makedirs(os.path.join(many, "unrelated"))
    found, notes = tc.load_mutation(many)
    check(sorted(found["5.052"]) == [1, 2] and found["5.052"][2]["attempt"] == 2, f"attempts: {found}")
    check(len(notes) == 1 and "unrelated" in notes[0], f"notes: {notes}")
    m = tc.mutation_part(found["5.052"], mutants)
    check(m["class"] == "test-failure" and "the unmutated tests failed in shard 1" in m["detail"]
          and "shard 3: no logs" in m["detail"] and m["verdicts"] is None, f"failed baseline: {m}")
    shutil.rmtree(os.path.join(many, "mutation-logs-verilator-5.052-x-shard1-attempt1"))
    m = tc.mutation_part(tc.load_mutation(many)[0]["5.052"], mutants)
    check(m["class"] == "other" and "shard 1: no logs" in m["detail"], f"missing shard: {m}")
    check(tc.shard_mutants(mutants, 2, 3) == ["b"] and tc.shard_mutants(mutants, 1, 1) == ["a", "b"], "shard mutants")


def records_of(out):
    files, name, lines = {}, None, []
    for line in out.splitlines(keepends=True):
        if line.startswith("===BEGIN ") and line.rstrip().endswith("==="):
            name, lines = line.rstrip()[len("===BEGIN "):-3], []
        elif line.rstrip("\n") == "===END===" and name:
            files[name] = "".join(lines)
            name = None
        elif name:
            lines.append(line)
    return files


def test_run(work):
    directory = os.path.join(work, "wrapped")
    code, out = run(["run", directory, "--", "sh", "-c", "echo out; echo err >&2; exit 3"])
    check(code == 3 and "out\n" in out and "err\n" in out, f"run: {code} {out}")
    check(open(os.path.join(directory, "exit")).read() == "3\n", "run: exit")
    check(open(os.path.join(directory, "output.log")).read() == "out\nerr\n", "run: output.log")
    check(float(open(os.path.join(directory, "seconds")).read()) >= 0, "run: seconds")
    code, out = run(["run", directory, "--", "sh", "-c", "kill -9 $$"])
    check(code == 137 and open(os.path.join(directory, "exit")).read() == "137\n", f"run: a signal: {code}")


def main():
    work = tempfile.mkdtemp()
    try:
        test_plan()
        test_plan_command(work)
        test_classify()
        test_summary(work)
        test_timing(work)
        test_report(work)
        test_mutation_layouts(work)
        test_run(work)
    finally:
        shutil.rmtree(work, ignore_errors=True)
    if failures:
        print(f"test-toolchain-candidates: {len(failures)} failures", file=sys.stderr)
        return 1
    print("toolchain-candidates self-test passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
