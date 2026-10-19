#!/usr/bin/env python3
"""Self-test of scripts/toolchain-candidates.py (#121), run by `scripts/check.sh static`.

Checks with fakes (no network, no sbt, no Verilator): the knobs of the plan (the matrices it writes,
every malformed knob, and that the workflow's knobs are the defaults), the classification of
check.sh's output and of a test job, the summaries of the build and test jobs (with their JUnit
reports and failure texts), the order and environment of the timing runs, and the report with its
records and failure texts, including mutation logs, the comparison with the control test by test,
and records committed from an earlier run. The fakes pin 5.020 with apt in a file of their own, and
the cases set knobs of their own, so that changing project/toolchain.properties stays a one-line
change and changing the defaults touches only the workflow and DEFAULTS; one case checks that the
defaults are valid with the real pin and try a candidate besides it.
"""

import datetime
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "toolchain-candidates.py")
WORKFLOW = os.path.join(os.path.dirname(HERE), ".github", "workflows", "toolchain-candidates.yml")
CLASSIFY = os.path.join(HERE, "mutation-classify.py")
spec = importlib.util.spec_from_file_location("toolchain_candidates", SCRIPT)
tc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tc)

COMMIT_032 = "8ff77e9d47351b0a59114929880687839a51840b"
COMMIT_052 = "ea338be98e1e838d3518809ce8899f85a009963c"
TAG_052 = "efa4927be48e75c3cd08fc848b198d1d9d237f00"
PINNED = {"verilator.version": "5.020", "verilator.apt": "5.020-1", "verilator.commit": "", "jdk.major": "21"}
# A pin built from the source at a commit (as after #121's PR A).
PINNED_SOURCE = {"verilator.version": "5.052", "verilator.apt": "", "verilator.commit": COMMIT_052, "jdk.major": "21"}
# The knobs of the cases (the knobs of #121's PR 3), not DEFAULTS: against DEFAULTS, the plan warns.
CASE_KNOBS = {"CANDIDATES": "5.032 5.052", "MUTATION": "", "SEED_RUNS": "1", "TIMING": "false", "PARTS": ""}
NOW = datetime.datetime(2026, 10, 18, tzinfo=datetime.timezone.utc)
failures = []


def check(condition, what):
    if not condition:
        failures.append(what)
        print(f"FAIL: {what}", file=sys.stderr)


def knobs(**overrides):
    env = dict(CASE_KNOBS)
    env.update(overrides)
    return env


def ls_remote(refs):
    tags = {"refs/tags/v5.032^{}": COMMIT_032, "refs/tags/v5.032": "1" * 40,
            "refs/tags/v5.052^{}": COMMIT_052, "refs/tags/v5.052": TAG_052, "refs/tags/v5.999": "2" * 40}
    return {r: tags[r] for r in refs if r in tags}


def tag_date(tag):
    return {TAG_052: "2026-10-10T08:00:00Z", "1" * 40: "2025-01-01T00:00:00Z"}.get(tag)


def plan_of(pinned=PINNED, **overrides):
    return tc.make_plan(knobs(**overrides), pinned, ls_remote, tag_date, NOW)


def plan_error(pinned=PINNED, **overrides):
    try:
        plan_of(pinned, **overrides)
    except tc.Failure as e:
        return str(e)
    return ""


def not_defaults(plan):
    return [w for w in plan["warnings"] if "not the defaults" in w]


def test_plan():
    plan = plan_of()
    versions = [(e["version"], e["kind"], e["verilator"], e["commit"]) for e in plan["versions"]]
    check(versions == [("5.020", "pinned", "", ""), ("5.032", "candidate", "5.032", COMMIT_032),
                       ("5.052", "candidate", "5.052", COMMIT_052)], f"default versions: {versions}")
    check(plan["parts"] == {"test": True, "timing": False, "mutation": False}, f"default parts: {plan['parts']}")
    check(plan["seed_runs"] == 1 and plan["timing"] == [] and plan["mutation"] == [], "default runs and parts")
    own = [w for w in plan["warnings"] if w not in not_defaults(plan)]
    check(len(own) == 1 and "2026-10-10, 7 days ago" in own[0], f"a tag younger than two weeks warns: {plan['warnings']}")
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
    plan = plan_of(MUTATION="5.020 5.052", TIMING="true", PARTS="mutation")
    check(plan["parts"] == {"test": False, "timing": False, "mutation": True}, f"PARTS=mutation: {plan['parts']}")
    # The control's mutation check runs too, and first; alone, it needs no candidate.
    check(plan_of(MUTATION="5.052 5.020")["mutation"] == ["5.020", "5.052"], "MUTATION: the control first")
    check(plan_of(MUTATION="5.020")["mutation"] == ["5.020"], "MUTATION: the control alone")
    # A tag that has moved since a committed record warns.
    records = {"5.052": [{"run_id": 900, "commit": "3" * 40}], "5.032": [{"run_id": 901, "commit": COMMIT_032}]}
    plan = tc.make_plan(knobs(), PINNED, ls_remote, tag_date, NOW, lambda v: records.get(v, []))
    moved = [w for w in plan["warnings"] if "has moved" in w]
    check(len(moved) == 1 and f"the tag v5.052 is now at {COMMIT_052}, but run 900 built {'3' * 40}" in moved[0],
          f"a moved tag: {plan['warnings']}")
    plan = plan_of(TIMING="true", PARTS="timing test")
    check(plan["parts"] == {"test": True, "timing": True, "mutation": False}, f"PARTS=timing test: {plan['parts']}")
    # Timing leaves out apt candidates, and needs a source candidate.
    plan = plan_of(CANDIDATES="5.052 apt:5.032-1", TIMING="true")
    check(plan["timing"] == ["5.020", "5.052"] and any("leaves out apt:5.032-1" in n for n in plan["notes"]),
          f"timing without apt: {plan['timing']}")

    # A pin built from the source, with the version to roll back to (apt's 5.020-1) as a candidate.
    plan = plan_of(PINNED_SOURCE, CANDIDATES="apt:5.020-1")
    out = tc.plan_outputs(plan)
    check(json.loads(out["versions"]) == [
        {"version": "5.052", "kind": "pinned", "method": "source", "verilator": "", "commit": ""},
        {"version": "5.020", "kind": "candidate", "method": "apt", "verilator": "apt:5.020-1", "commit": ""}]
          and plan["pinned"]["source_commit"] == COMMIT_052 and not plan["notes"],
          f"a source pin with an apt candidate: {out['versions']} {plan['notes']}")
    check(plan_of(PINNED_SOURCE, CANDIDATES="apt:5.020-1", MUTATION="5.020 5.052")["mutation"] == ["5.052", "5.020"],
          "MUTATION: an apt candidate after the source pin")
    plan = plan_of(PINNED_SOURCE, CANDIDATES="apt:5.020-1 5.032", TIMING="true")
    check(plan["timing"] == ["5.052", "5.032"] and any("leaves out apt:5.020-1" in n for n in plan["notes"]),
          f"timing with a source pin leaves out apt: {plan['timing']} {plan['notes']}")
    check("TIMING=true needs a candidate built from the source"
          in plan_error(PINNED_SOURCE, CANDIDATES="apt:5.020-1", TIMING="true"), "TIMING with an apt candidate alone")
    # The defaults are valid with the pin of project/toolchain.properties, and try a candidate besides
    # it: the version to roll back to (#121 §5). The upstream has every tag.
    try:
        plan = tc.make_plan(dict(tc.DEFAULTS), tc.read_pinned(), lambda refs: {r: "4" * 40 for r in refs},
                            lambda tag: "2020-01-01T00:00:00Z", NOW)
        check(len(plan["versions"]) > 1, f"the defaults with the real pin try no candidate: {plan['notes']}")
    except tc.Failure as e:
        check(False, f"the defaults with the real pin: {e}")

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
        ({"MUTATION": "5.052"}, "MUTATION names 5.052 but not the pinned version 5.020: a candidate's mutation check"
                                " is compared with the control's in the same run, so write MUTATION=\"5.020 5.052\""),
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


def test_defaults():
    """The knobs of the workflow are DEFAULTS, against which the plan warns: a branch whose knobs are
    not restored fails here, in the required static check, and not only with the plan's warning."""
    text = open(WORKFLOW, encoding="utf-8").read()
    block = re.search(r"^env:\n((?:(?:  .*)?\n)*)", text, re.M)
    found = dict(re.findall(r'^  ([A-Z_]+): "([^"]*)"$', block.group(1), re.M)) if block else {}
    check(found == tc.DEFAULTS,
          f"the knobs of .github/workflows/toolchain-candidates.yml ({found}) are not DEFAULTS of"
          f" scripts/toolchain-candidates.py ({tc.DEFAULTS}): restore the knobs before merging (the branch of a"
          " decision run fails this check until then), or change both")


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
    # check.sh prints its message for 124 only; timeout's KILL after --kill-after gives 137.
    killed = tc.parse_output("[toolchain] verilator=5.052 expected=5.052 (env)\ncheck: sbt failed (exit 137); logs in x\n")
    got = tc.classify_output(137, killed, expected, None, 811.0, 780)
    check(got == ("timeout", "sbt killed after CHECK_TIMEOUT=780 s (exit 137 after 811 s)"), f"137 at the limit: {got}")
    got = tc.classify_output(137, killed, expected, None, 300.0, 780)
    check(got == ("other", "sbt exit 137 with no failed test"), f"137 before the limit: {got}")
    # Every [sim-seed] line names the seed of the run: (default) in run 1, (random) later.
    check(tc.classify_output(0, tc.parse_output(SBT_PASSED), expected, "default")[0] == "success", "the default seed")
    got = tc.classify_output(0, tc.parse_output(SBT_PASSED), expected, "random")
    check(got[0] == "setup" and "not a seed from (random)" in got[1], f"VLIW_SIM_SEED=random lost: {got}")
    no_seed = "\n".join(line for line in SBT_PASSED.splitlines() if "[sim-seed]" not in line)
    got = tc.classify_output(0, tc.parse_output(no_seed), expected, "default")
    check(got[0] == "setup" and "no [sim-seed] line" in got[1], f"no [sim-seed] line: {got}")
    check(tc.classify_output(1, tc.parse_output(SBT_FAILED), expected, "random")[0] == "test-failure", "a random seed")
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


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def junit(suite, cases):
    """A JUnit report of sbt with the test cases [(name, kind ('failure' or 'error'), body)]."""
    rows = "".join(f'  <testcase classname="{suite}" name="{name}" time="0.1">\n'
                   + (f'    <{kind} message="m" type="sbt.ForkMain$ForkError">{body}</{kind}>\n' if kind else "")
                   + "  </testcase>\n" for name, kind, body in cases)
    return (f'<?xml version="1.0" encoding="UTF-8"?>\n<testsuite hostname="vm" name="{suite}" tests="{len(cases)}"'
            f' errors="0" failures="0" skipped="0" time="0.1" timestamp="2026-10-18T00:00:00">\n{rows}'
            "  <system-out><![CDATA[]]></system-out>\n</testsuite>\n")


# As 5.040 and later end an RTL assertion: exit(1) (#121 §5, M8), with a body longer than BODY_KEPT
# whose cause comes after many frames.
SIM_EXIT_BODY = ("sbt.ForkMain$ForkError: java.lang.Exception: Nonzero exit status: 1\n"
                 + "\tat svsim.Simulation.run(Simulation.scala:1)\n" * 400
                 + "Caused by: sbt.ForkMain$ForkError: chisel3.simulator.Exceptions$AssertionFailed: assertions failed\n"
                 + "  0  [30] %Error: Core_Verification_Assert.sv:25: Assertion failed in TOP.svsimTestbench.dut: L3\n"
                 + "\tat chisel3.simulator.Simulator.run(Simulator.scala:2)\n\tat a.b.C(D.scala:3)\n\t... 12 more\n")
ASSERTION_BODY = ("sbt.ForkMain$ForkError: org.scalatest.exceptions.TestFailedException: 1 did not equal 2\n"
                  "\tat org.scalatest.Assertions.newAssertionFailedException(Assertions.scala:472)\n")


def texts_of(out):
    """{name: text} of the failure-text blocks in OUT."""
    files, name, lines = {}, None, []
    for line in out.splitlines(keepends=True):
        if line.startswith("===BEGIN failure-text ") and line.rstrip().endswith("==="):
            name, lines = line.rstrip()[len("===BEGIN failure-text "):-3], []
        elif line.rstrip("\n") == "===END===" and name:
            files[name] = "".join(lines)
            name = None
        elif name:
            lines.append(line)
    return files


def classify_texts(work, files, label):
    """scripts/mutation-classify.py on a fixture directory made from the failure texts of LABEL:
    (its verdict line, the kinds of its classification.tsv)."""
    d = os.path.join(work, "fixture-" + label.replace("/", "-"))
    for name, text in files.items():
        if name.startswith(label + "/"):
            write(os.path.join(d, name[len(label) + 1:]), text)
    p = subprocess.run([sys.executable, "-I", CLASSIFY, d], stdout=subprocess.PIPE, universal_newlines=True)
    kinds = [line.split("\t")[2] for line in open(os.path.join(d, "classification.tsv")).read().splitlines()]
    return "\t".join(p.stdout.strip().split("\t")[:2]), kinds


def test_summary(work):
    """A test job's summary from the run wrapper's directory, with a fake toolchain and JUnit reports;
    the build job's summary."""
    root = os.path.join(work, "checkout")
    os.makedirs(os.path.join(root, "run"))
    with open(os.path.join(root, "run", "output.log"), "w") as f:
        f.write(SBT_FAILED)
    for name, value in (("exit", "1"), ("seconds", "512.4"), ("start", f"{time.time() - 60:.3f}")):
        with open(os.path.join(root, "run", name), "w") as f:
            f.write(value + "\n")
    # The reports of this run, and an older one that must not count.
    reports = os.path.join(root, "hw", "target", "test-reports")
    write(os.path.join(reports, "TEST-vliw.hw.CaseSpec.xml"),
          junit("vliw.hw.CaseSpec", [("case a", "", ""), ("case b", "failure", SIM_EXIT_BODY)]))
    write(os.path.join(reports, "TEST-vliw.hw.CoreSpec.xml"),
          junit("vliw.hw.CoreSpec", [("t1", "failure", ASSERTION_BODY), ("t2", "failure", ASSERTION_BODY)]))
    write(os.path.join(reports, "TEST-vliw.hw.OldSpec.xml"), junit("vliw.hw.OldSpec", [("old", "failure", "x")]))
    os.utime(os.path.join(reports, "TEST-vliw.hw.OldSpec.xml"), (time.time() - 3600,) * 2)
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
    # The failing tests of this run's reports, as scripts/mutation-classify.py classifies them.
    check(s.get("failed_tests") == [["vliw.hw.CaseSpec", "case b", "env:sim-exit"],
                                    ["vliw.hw.CoreSpec", "t1", "assertion"], ["vliw.hw.CoreSpec", "t2", "assertion"]]
          and s.get("failed_tests_cut") is False, f"failed tests: {s.get('failed_tests')}")
    check(s.get("failures", [[]])[0] == ["vliw.hw.CaseSpec", "case b", "env:sim-exit", "simulation exited with 1"]
          and s.get("junit", "").startswith("KILLED\tassertion\t2 failed"), f"failures: {s.get('failures')} {s.get('junit')}")
    # The failure texts: the first failing test of each kind, its long body cut so that the classifier
    # still reads it the same way.
    files = texts_of(out)
    label = "5.052/test-run2"
    names = ("classification.tsv", "exit", "reports/hw/TEST-vliw.hw.CaseSpec.xml", "reports/hw/TEST-vliw.hw.CoreSpec.xml",
             "sbt.log", "verdict")
    check(sorted(files) == [f"{label}/{n}" for n in names], f"failure texts: {sorted(files)}")
    case = files.get(f"{label}/reports/hw/TEST-vliw.hw.CaseSpec.xml", "")
    check('name="case b"' in case and 'name="case a"' not in case and len(case) < len(SIM_EXIT_BODY)
          and "toolchain-candidates.py cut" in case
          and "Caused by: sbt.ForkMain$ForkError: chisel3.simulator.Exceptions$AssertionFailed" in case
          and "\tat chisel3.simulator.Simulator.run(Simulator.scala:2)\n" in case and "a.b.C" not in case,
          f"the cut body: {case[-800:]}")
    core = files.get(f"{label}/reports/hw/TEST-vliw.hw.CoreSpec.xml", "")
    check('name="t1"' in core and 'name="t2"' not in core, f"one test of each kind: {core}")
    check(files.get(f"{label}/exit") == "1\n" and "[error] \tvliw.hw.CoreSpec\n" in files.get(f"{label}/sbt.log", ""),
          "exit and sbt.log")
    check(classify_texts(work, files, label) == ("KILLED\tassertion", ["env:sim-exit", "assertion"]),
          f"the failure texts as a fixture: {classify_texts(work, files, label)}")
    check(out.index("===BEGIN failure-text ") < out.index(tc.SUMMARY_PREFIX), "the failure texts before the summary")

    # A failure that names no suite and no test cannot be compared.
    shutil.rmtree(os.path.join(root, "hw", "target"))
    with open(os.path.join(root, "run", "output.log"), "w") as f:
        f.write(SBT_FAILED.split("[error] Failed tests:")[0])
    code, out = run(["summary", "test", "--entry", json.dumps(entry), "--run", "2", "--setup", "success", "--step",
                     "failure", "--dir", "run", "--start", start, "--toolchain-file", properties(work)], env, cwd=root)
    s = json.loads(out.split(tc.SUMMARY_PREFIX)[1])
    check(s["class"] == "other" and "no JUnit report names a failing test" in s["detail"] and "failure-text" not in out,
          f"an unnamed failure: {s['class']} {s['detail']}")

    # summary build: a source build of this job, the apt package of the pinned version, a failed setup.
    early = os.path.join(work, "early-start")
    write(early, f"{os.path.getmtime(os.path.join(work, 'prefix', 'build-info.txt')) - 10:.0f}\n")
    code, out = run(["summary", "build", "--entry", json.dumps(entry), "--setup", "success", "--start", early,
                     "--toolchain-file", properties(work)], env, cwd=root)
    s = json.loads(out.split(tc.SUMMARY_PREFIX)[1])
    check(code == 0 and s["class"] == "success" and s["part"] == "build"
          and any(p.startswith("cache: built in this job (") for p in s["fingerprint"]), f"build: {code} {s}")
    pinned_bin = os.path.join(work, "pinned", "bin")
    for name, text in (("verilator", "#!/bin/sh\necho 'Verilator 5.020 2023-12-08 rev (Debian 5.020-1)'\n"),
                       ("dpkg-query", "#!/bin/sh\nprintf '5.020-1'\n")):
        write(os.path.join(pinned_bin, name), text)
        os.chmod(os.path.join(pinned_bin, name), 0o755)
    pinned = {"version": "5.020", "kind": "pinned", "method": "apt", "verilator": "", "commit": ""}
    apt_env = dict(env, PATH=os.pathsep.join([pinned_bin, bins, os.environ["PATH"]]))
    del apt_env["VLIW_VERILATOR_EXPECTED"]
    out_file = os.path.join(work, "summary", "build.json")
    code, out = run(["summary", "build", "--entry", json.dumps(pinned), "--setup", "success", "--out", out_file,
                     "--toolchain-file", properties(work)], apt_env, cwd=root)
    s = json.load(open(out_file))
    check(code == 0 and s["class"] == "success" and s["version"] == "5.020"
          and "dpkg-query verilator: 5.020-1" in s["fingerprint"]
          and "fingerprint: expected: verilator 5.020 (--expect)" in out
          and "fingerprint: VLIW_VERILATOR_EXPECTED: unset" in out, f"build of the pinned version: {code} {s} {out}")
    code, out = run(["summary", "build", "--entry", json.dumps(pinned), "--setup", "failure",
                     "--toolchain-file", properties(work)], apt_env, cwd=root)
    s = json.loads(out.split(tc.SUMMARY_PREFIX)[1])
    check(code == 1 and s["class"] == "setup" and s["detail"] == "the setup-toolchain action ended with failure",
          f"a failed setup: {code} {s}")


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
    checkout = os.path.join(work, "timing-checkout")
    reports = os.path.join(checkout, "hw", "target", "test-reports")
    write(os.path.join(work, "case-b.xml"), junit("vliw.hw.CaseSpec", [("case b", "failure", ASSERTION_BODY)]))
    # A fake check.sh: logs its arguments, the first prefix of the PATH and the expected version, and
    # prints sbt's summary with the [toolchain] and [sim-seed] lines of that version (5.052 fails its
    # second run, whose report names the test). The first run leaves a failed report that the later
    # runs must not count.
    check_sh = fake_bin(work, "check.sh", f"""#!/bin/sh
first="${{PATH%%:*}}"
case "$first" in */verilator-5.0*) v="${{first%/bin}}"; v="${{v##*verilator-}}" ;; *) v=5.020 ;; esac
echo "$* | $v | ${{VLIW_VERILATOR_EXPECTED:-unset}} | ${{VLIW_SIM_SEED:-unset}} | $CHECK_LOG_DIR" >>"{record}"
[ "$1 $2" = "sbt Test/compile" ] && exit 0
sleep 0.2
src=pinned; [ "$v" = 5.020 ] || src=env
echo "[sim-seed] VLIW_SIM_SEED=1 (default) verilator=$v"
echo "[toolchain] verilator=$v expected=$v ($src)"
n=$(grep -c "sbt test | $v " "{record}")
mkdir -p "{reports}"
if [ ! -f "{reports}/TEST-vliw.hw.OldSpec.xml" ]; then
  sed 's/CaseSpec/OldSpec/g' "{work}/case-b.xml" >"{reports}/TEST-vliw.hw.OldSpec.xml"
  touch -t 200001010000 "{reports}/TEST-vliw.hw.OldSpec.xml"
fi
if [ "$v" = 5.052 ] && [ "$n" = 2 ]; then
  cp "{work}/case-b.xml" "{reports}/TEST-vliw.hw.CaseSpec.xml"
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
    os.makedirs(checkout)
    code, out = run(["timing", "--versions", json.dumps(versions), "--installed", installed, "--dir", directory,
                     "--check", check_sh], {"PATH": path, "VLIW_VERILATOR_EXPECTED": "5.052", "VLIW_SIM_SEED": "random"},
                    cwd=checkout)
    check(code == 1, f"timing with a failed run exits 1, got {code}: {out}")
    files = texts_of(out)
    check(sorted(n for n in files if "/reports/" in n) == ["5.052/timing-position4/reports/hw/TEST-vliw.hw.CaseSpec.xml"],
          f"the failure texts of the failed run, and its reports only: {sorted(files)}")
    calls = [line.split(" | ") for line in open(record).read().splitlines()]
    check([c[0] for c in calls] == ["sbt Test/compile"] + ["sbt test"] * 6, f"commands: {calls}")
    check([c[1] for c in calls] == ["5.020", "5.020", "5.032", "5.052", "5.052", "5.032", "5.020"], f"order: {calls}")
    check([c[2] for c in calls] == ["unset", "unset", "5.032", "5.052", "5.052", "5.032", "unset"], f"expected: {calls}")
    check(all(c[3] == "unset" for c in calls), "the default seed in every run")
    check(calls[3][4] == os.path.join(directory, "runs", "3-5.052", "check-logs"), f"CHECK_LOG_DIR: {calls[3]}")
    doc = json.load(open(os.path.join(directory, "runs.json")))
    check([r["class"] for r in doc["runs"]] == ["success"] * 3 + ["test-failure"] + ["success"] * 2,
          f"classes: {[r['class'] for r in doc['runs']]}")
    check(doc["runs"][3]["failed_suites"] == ["vliw.hw.CaseSpec"]
          and doc["runs"][3]["failed_tests"] == [["vliw.hw.CaseSpec", "case b", "assertion"]],
          f"failed suite and test: {doc['runs'][3]}")
    # The summary step, from runs.json.
    out_file = os.path.join(work, "summary", "timing.json")
    code, out = run(["summary", "timing", "--versions", json.dumps(versions), "--setup",
                     "success success success skipped", "--step", "failure", "--dir", directory, "--out", out_file])
    s = json.load(open(out_file))
    check(code == 1 and s["class"] == "test-failure", f"timing summary: {code} {s.get('class')} {out}")
    per = s["per_version"]
    check(per["5.032"]["class"] == "success" and per["5.032"]["ratio"] is not None, f"5.032: {per['5.032']}")
    check(per["5.052"]["class"] == "test-failure" and per["5.052"]["ratio"] is None, f"5.052: {per['5.052']}")
    check(per["5.052"]["failed_suites"] == ["vliw.hw.CaseSpec"]
          and per["5.052"]["failed_tests"] == [["vliw.hw.CaseSpec", "case b", "assertion"]], f"5.052 tests: {per['5.052']}")
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


def mutation_logs(root, name, shard, n, verdicts, baseline=("SURVIVED",), version="5.020", cases=None):
    """A shard's logs as mutation-check.sh leaves them: env.txt, results.tsv, and a directory per run
    (_baseline, _baseline.retryN, <mutant>) with verdict, exit, sbt.log and, for a failed run,
    classification.tsv (vliw.hw.CoreSpec / t1, or from CASES {run: (suite, test, body)} with a JUnit
    report of that test)."""
    d = os.path.join(root, name) if name else root
    os.makedirs(d, exist_ok=True)
    write(os.path.join(d, "env.txt"), f"expected: verilator {version} (pinned)\nshard: {shard}/{n}\n")
    runs = [("_baseline" + (f".retry{i}" if i else ""), v, {"SURVIVED": "passed", "KILLED": "assertion"}.get(v, "sim-build"))
            for i, v in enumerate(baseline)]
    if verdicts is not None:
        write(os.path.join(d, "results.tsv"), "id\tverdict\tcategory\tdetail\tlog\n"
              + "".join(f"{m}\t{v}\t{c}\tdetail\t{d}/{m}\n" for m, v, c in verdicts))
        runs += list(verdicts)
    for run_name, verdict, category in runs:
        r = os.path.join(d, run_name)
        write(os.path.join(r, "verdict"), f"{verdict}\t{category}\tx\n")
        write(os.path.join(r, "exit"), "0\n" if verdict == "SURVIVED" else "1\n")
        write(os.path.join(r, "sbt.log"), "[info] done\n" if verdict == "SURVIVED" else
              "[error] Failed tests:\n[error] \tvliw.hw.CoreSpec\n[error] (hw / Test / test) sbt.TestsFailedException\n")
        if verdict == "SURVIVED":
            continue
        suite, test, body = (cases or {}).get(run_name, ("vliw.hw.CoreSpec", "t1", None))
        kind = category if verdict == "KILLED" else f"env:{category}"
        write(os.path.join(r, "classification.tsv"), f"{suite}\t{test}\t{kind}\tdetail of {run_name}\n")
        if body:
            write(os.path.join(r, "reports", "hw", f"TEST-{suite}.xml"), junit(suite, [(test, "failure", body)]))


NEEDS_FAILED = json.dumps({"plan": {"result": "success"}, "build": {"result": "success"}, "test": {"result": "failure"},
                           "timing": {"result": "success"}, "mutation": {"result": "failure"}})


def report_env(plan, run_id="1000", **extra):
    env = {"PLAN": json.dumps(plan), "GITHUB_RUN_ID": run_id, "GITHUB_RUN_ATTEMPT": "1", "GITHUB_REPOSITORY": "o/r",
           "GITHUB_EVENT_NAME": "pull_request", "GITHUB_SHA": "m" * 40, "PR_NUMBER": "130", "PR_HEAD_SHA": "h" * 40,
           "MAIN_SHA": "b" * 40, "PR_BASE_SHA": "c" * 40, "REPORT_DATE": "2026-10-18", "NEEDS": NEEDS_FAILED}
    env.update(extra)
    return env


def write_summaries(directory, items):
    shutil.rmtree(directory, ignore_errors=True)
    os.makedirs(directory)
    for i, s in enumerate(items):
        with open(os.path.join(directory, f"{i}.json"), "w") as f:
            json.dump(s, f)


def machine_records(md):
    return [json.loads(line[len("<!-- toolchain-candidates: "):-len(" -->")]) for line in md.splitlines()
            if line.startswith("<!-- toolchain-candidates: ")]


def test_report(work):
    mutants = os.path.join(work, "mutants.tsv")
    with open(mutants, "w") as f:
        f.write("# id\tfile\n" + "".join(f"m{i}\tf\to\tm\twhat\n" for i in range(1, 5)))
    plan = plan_of(MUTATION="5.020 5.052", SEED_RUNS="2", TIMING="true")
    summaries = os.path.join(work, "summaries")
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
                failed_tests=[["vliw.hw.CaseSpec", "a test", "env:sim-exit"]], warnings={"WIDTH": 3}),
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
    write_summaries(summaries, items)
    mutation = os.path.join(work, "mutation")
    for k in (1, 2):
        mine = [(m, "KILLED", "assertion") for m in tc.shard_mutants(mutants, k, 2)]
        mutation_logs(mutation, f"mutation-logs-verilator-5.020-apt-5.020-1-shard{k}-attempt1", k, 2, mine,
                      baseline=("ERROR", "SURVIVED") if k == 2 else ("SURVIVED",))
        theirs = [(m, "ERROR" if m == "m3" else "KILLED", "sim-exit" if m == "m3" else "assertion") for m in
                  tc.shard_mutants(mutants, k, 2)]
        # m3 as 5.040 and later end an RTL assertion (exit(1)): scripts/mutation-classify.py sees sim-exit.
        mutation_logs(mutation, f"mutation-logs-verilator-5.052-ea338be98e1e-shard{k}-attempt1", k, 2, theirs,
                      version="5.052", cases={"m3": ("vliw.hw.CaseSpec", "case b", SIM_EXIT_BODY)})
    records = os.path.join(work, "records")
    env = report_env(plan)
    args = ["report", "--summaries", summaries, "--mutation", mutation, "--records", records, "--mutants", mutants]
    code, out = run(args, env)
    check(code == 0, f"report exit {code}: {out}")
    check(f"==> toolchain-candidates report: run 1000 attempt 1 (pull_request #130, main {'b' * 40}, head {'h' * 40}"
          in out, "header")
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
    check("- 5.052 test: candidate-only failure: vliw.hw.CaseSpec / a test failed on 5.052 and not on the control" in out
          and "commit these records, then re-run it with PARTS=\"test\"" in out, "5.052 candidate-only failure")
    check("- 5.052 mutation: inconclusive (re-run with PARTS=\"mutation\"): 5.052: ERROR sim-exit x1" in out,
          "5.052 mutation inconclusive")
    check("shard 2: baseline: passed on a retry; the first run ended with ERROR sim-build" in out, "baseline retry")
    check("#121 §5: 555 s x 1.300 x 1.1 = 794 s, OVER CHECK_TIMEOUT 780 s" in out, "timing criterion")
    check("    vliw.hw.CaseSpec / a test: env:sim-exit: simulation exited with 1" in out
          and "    failure texts: in the log of the job 'test 5.052 run 1'" in out, "junit failure kinds")
    check("cannot be judged" not in out, f"the control's results can be judged: {out}")
    # The failure texts of the mutation shards come before the records: the unmutated run that failed
    # first, and the first mutant of each verdict other than KILLED and SURVIVED.
    texts = texts_of(out)
    check("failure text: 5.020 mutation shard 2 _baseline: the unmutated run (attempt 1): ERROR sim-build" in out
          and "failure text: 5.052 mutation shard 1 m3: ERROR sim-exit (the control: KILLED assertion)" in out
          and out.count("failure text: ") == 2, f"failure text headers: {out}")
    check(sorted(n for n in texts if n.startswith("5.052/")) == [
        f"5.052/mutation/m3/{n}" for n in ("classification.tsv", "exit", "reports/hw/TEST-vliw.hw.CaseSpec.xml",
                                           "sbt.log", "verdict")], f"failure texts: {sorted(texts)}")
    check(classify_texts(work, texts, "5.052/mutation/m3") == ("ERROR\tsim-exit", ["env:sim-exit"]),
          f"m3 as a fixture: {classify_texts(work, texts, '5.052/mutation/m3')}")
    check(out.index("===BEGIN failure-text ") < out.index("records to commit"), "the failure texts before the records")
    files = records_of(out)
    check(sorted(files) == ["docs/toolchain/5.020/mutation.tsv", "docs/toolchain/5.020/report.md",
                            "docs/toolchain/5.032/report.md", "docs/toolchain/5.052/report.md"], f"records: {sorted(files)}")
    check(files.get("docs/toolchain/5.020/mutation.tsv") ==
          "mutant\tverdict\tcategory\n" + "".join(f"m{i}\tKILLED\tassertion\n" for i in range(1, 5)),
          f"mutation.tsv: {files.get('docs/toolchain/5.020/mutation.tsv')!r}")
    check(out.rstrip().endswith("===END===") and out.rindex("===BEGIN failure-text ") < out.index("===BEGIN docs/"),
          "the records come last")
    md52 = files.get("docs/toolchain/5.052/report.md", "")
    check(md52.startswith("# Verilator 5.052（候補）の記録\n"), "report.md title")
    check(f"commit `{COMMIT_052}`" in md52 and "[1000](https://github.com/o/r/actions/runs/1000)" in md52, "report.md run")
    check(f"pull_request #130（main `{'b' * 40}`、head `{'h' * 40}`、merge commit `{'m' * 40}`）" in md52,
          "report.md trigger")
    check("| 1 | — | test-failure（failed: vliw.hw.CaseSpec） |" in md52 and "WIDTH 3" in md52, "report.md test row")
    check("mutation.tsv は出していない" in md52, "no mutation.tsv for an inconclusive mutation part")
    md20 = files.get("docs/toolchain/5.020/report.md", "")
    check("verilator_bin sha256: aaa" in md20 and "test job の `verilator_bin` の sha256: すべて build job と同じ。" in md20,
          "report.md fingerprint")
    record = machine_records(md52)
    check(len(record) == 1
          and record[0]["parts"]["test"] == {"class": "test-failure", "candidate_only": ["vliw.hw.CaseSpec / a test"]}
          and record[0]["main"] == "b" * 40 and record[0]["commit"] == COMMIT_052, f"machine record: {record}")
    check("commit" not in machine_records(md20)[0], "no commit for the apt package")

    # Committed from that run, the records make a re-run of the same part (PARTS=test) that fails the
    # same way a regression.
    os.makedirs(os.path.join(records, "5.052"))
    with open(os.path.join(records, "5.052", "report.md"), "w") as f:
        f.write(md52)
    rerun = plan_of(PARTS="test", SEED_RUNS="2")
    env.update(PLAN=json.dumps(rerun), GITHUB_RUN_ID="1001")
    code, out = run(args, env)
    check(code == 0 and "- 5.052 test: REGRESSION of 5.052: vliw.hw.CaseSpec / a test failed on 5.052 and not on the"
          " control in run 1000 and again in this run" in out, f"regression: {out}")
    check("PARTS: this run re-runs test only" in out, "PARTS noted")
    # Another test of the same suite on the re-run is not the same failure.
    items[7]["failed_tests"] = [["vliw.hw.CaseSpec", "another test", "assertion"]]
    write_summaries(summaries, items)
    code, out = run(args, env)
    check("REGRESSION" not in out and "- 5.052 test: candidate-only failure: vliw.hw.CaseSpec / another test" in out
          and "(no record committed from an earlier run has the same failure)" in out, f"another test: {out}")
    # Without the failure in the re-run, there is no regression.
    del items[7]
    write_summaries(summaries, items)
    code, out = run(args, env)
    check("- 5.052 test: success" not in out and "REGRESSION" not in out, f"no regression without the failure: {out}")
    check('- 5.052 test: inconclusive (re-run with PARTS="test"): 5.052: run 1: other (no summary from the test job'
          in out, f"a missing summary is inconclusive: {out}")
    # A failed plan: the report says so and fails.
    env.pop("PLAN")
    code, out = run(args, env)
    check(code == 1 and "the plan job ended with success" in out, f"no plan: {code} {out}")


def test_report_compare(work):
    """The candidates against a control that failed: test by test, and the suite alone where no
    report names a test; a control that failed only in the environment; a failure that names
    nothing; mutation verdicts that differ."""
    mutants = os.path.join(work, "mutants.tsv")
    plan = plan_of(MUTATION="5.020 5.052")
    summaries = os.path.join(work, "summaries-compare")
    builds = [summary("build", v, "success") for v in ("5.020", "5.032", "5.052")]

    def failed(version, *tests, suites=()):
        return summary("test", version, "test-failure", "failed: " + ", ".join(sorted({t[0] for t in tests} | set(suites))),
                       run=1, failed_suites=sorted({t[0] for t in tests} | set(suites)), failed_tests=[list(t) for t in tests])

    def report(items, mutation=None, **env):
        write_summaries(summaries, builds + items)
        args = ["report", "--summaries", summaries, "--records", os.path.join(work, "no-records"), "--mutants", mutants]
        return run(args + (["--mutation", mutation] if mutation else []), report_env(env.pop("plan", plan), **env))

    mutation = os.path.join(work, "mutation-compare")
    for k in (1, 2):
        ids = tc.shard_mutants(mutants, k, 2)
        mutation_logs(mutation, f"mutation-logs-verilator-5.020-apt-5.020-1-shard{k}-attempt1", k, 2,
                      [(m, "KILLED", "assertion") for m in ids])
        mutation_logs(mutation, f"mutation-logs-verilator-5.052-ea338be98e1e-shard{k}-attempt1", k, 2,
                      [(m, "KILLED", "exception" if m == "m2" else "assertion") for m in ids], version="5.052")
    case_a, case_b = ("vliw.hw.CaseSpec", "case a", "assertion"), ("vliw.hw.CaseSpec", "case b", "assertion")
    rtl = ("vliw.crosscheck.RtlDifferentialSpec", "x", "assertion")
    # The control fails case a (and a test of RtlDifferentialSpec); 5.032 fails the same, RtlDifferentialSpec
    # named by sbt alone; 5.052 fails case b of the same suite.
    code, out = report([failed("5.020", case_a, rtl), failed("5.032", case_a, suites=[rtl[0]]), failed("5.052", case_b)],
                       mutation)
    check(code == 0 and "- 5.020 test: test-failure: run 1: test-failure (failed: vliw.crosscheck.RtlDifferentialSpec,"
          " vliw.hw.CaseSpec) (the"
          " control: the candidates are compared with these failures)" in out and "cannot be judged" not in out,
          f"the control's failure: {out}")
    check("- 5.032 test: fails as the control does (vliw.crosscheck.RtlDifferentialSpec, vliw.hw.CaseSpec / case a):"
          " not a regression of 5.032" in out, f"the same failure: {out}")
    check("- 5.052 test: candidate-only failure: vliw.hw.CaseSpec / case b failed on 5.052 and not on the control" in out,
          f"another test of the same suite: {out}")
    check("- 5.052 mutation: verdicts differ from the control's: m2 KILLED/exception (control KILLED/assertion)" in out
          and "failure text: 5.052 mutation shard 2 m2: KILLED exception, the control KILLED assertion" in out
          and out.count("failure text: ") == 1, f"mutation verdicts differ: {out}")
    md = records_of(out).get("docs/toolchain/5.052/report.md", "")
    check(machine_records(md)[0]["parts"]["test"]["candidate_only"] == ["vliw.hw.CaseSpec / case b"], f"record: {md}")
    # The control failed only in the environment: no comparison.
    code, out = report([failed("5.020", ("vliw.hw.CaseSpec", "case a", "env:sim-build")), summary("test", "5.032", "success", run=1),
                        failed("5.052", case_b)])
    check('- 5.020 test: inconclusive: run 1: other (only environment errors on the control (env:sim-build)' in out
          and "cannot be judged" in out, f"the control's environment: {out}")
    check('- 5.032 test: inconclusive (re-run with PARTS="test"): the control 5.020: run 1: other (only environment'
          in out and '- 5.052 test: inconclusive (re-run with PARTS="test"): 5.052 failed (vliw.hw.CaseSpec / case b),'
          ' but there is nothing to compare it with' in out, f"no comparison: {out}")
    # The control fails and 5.032 passes; 5.052 fails without naming anything.
    code, out = report([failed("5.020", case_a), summary("test", "5.032", "success", run=1),
                        summary("test", "5.052", "test-failure", "2 tests failed (sbt named no suite)", run=1)])
    check("- 5.032 test: the control failed (run 1: test-failure (failed: vliw.hw.CaseSpec)); 5.032 passed" in out,
          f"the control failed: {out}")
    check('- 5.052 test: inconclusive (re-run with PARTS="test"): 5.052: run 1: other (2 tests failed (sbt named no'
          ' suite); it names no failing suite or test)' in out, f"an unnamed failure: {out}")


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
    check(m["class"] == "test-failure" and "the unmutated tests failed in shard 1 (vliw.hw.CoreSpec / t1)" in m["detail"]
          and m["failed"] == {"baseline: vliw.hw.CoreSpec / t1"} and "shard 3: no logs" in m["detail"]
          and m["verdicts"] is None, f"failed baseline: {m}")
    # A failed baseline that names no test cannot be compared.
    baseline = os.path.join(many, "mutation-logs-verilator-5.052-x-shard1-attempt1", "_baseline")
    os.remove(os.path.join(baseline, "classification.tsv"))
    m = tc.mutation_part(tc.load_mutation(many)[0]["5.052"], mutants)
    check(m["class"] == "other" and m["failed"] == set()
          and "shard 1: the unmutated tests failed, but its classification.tsv names no test" in m["detail"],
          f"an unnamed baseline failure: {m}")
    shutil.rmtree(os.path.join(many, "mutation-logs-verilator-5.052-x-shard1-attempt1"))
    m = tc.mutation_part(tc.load_mutation(many)[0]["5.052"], mutants)
    check(m["class"] == "other" and "shard 1: no logs" in m["detail"], f"missing shard: {m}")
    check(tc.shard_mutants(mutants, 2, 3) == ["b"] and tc.shard_mutants(mutants, 1, 1) == ["a", "b"], "shard mutants")


def records_of(out):
    """{path: text} of the records in OUT (the blocks of docs/toolchain/<version>/<file>)."""
    files, name, lines = {}, None, []
    for line in out.splitlines(keepends=True):
        if line.startswith("===BEGIN docs/") and line.rstrip().endswith("==="):
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
        test_defaults()
        test_classify()
        test_summary(work)
        test_timing(work)
        test_report(work)
        test_report_compare(work)
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
