#!/usr/bin/env python3
"""The steps of .github/workflows/toolchain-candidates.yml (#121's PR 3, ADR-0020).

The workflow tries candidate Verilator versions with the pinned one as the control in the same
run. The agent's session that drives it can neither dispatch workflows, re-run jobs nor download
artifacts, but it can read job logs: the knobs are the workflow's top-level env, and every job
prints what it found to its log. The report job prints the comparison and, last, the records to
commit (docs/toolchain/<version>/report.md and mutation.tsv), each between
'===BEGIN docs/toolchain/<version>/<file>===' and '===END==='.

Usage (python3, standard library only):
  toolchain-candidates.py plan [--file PROPERTIES]
      Checks the knobs CANDIDATES, MUTATION, SEED_RUNS, TIMING and PARTS (environment), puts the
      pinned version first as the control, resolves the tag commit of each candidate built from
      the source (git ls-remote), and writes the matrices to GITHUB_OUTPUT. Exits 1 on any
      malformed knob.
  toolchain-candidates.py run DIR -- COMMAND...
      Runs COMMAND with its output also in DIR/output.log, then writes DIR/exit and DIR/seconds
      (wall time) and exits with COMMAND's status. A step stopped by its timeout leaves no exit.
  toolchain-candidates.py summary build|test|timing [options]
      Classifies the part that the job ran and prints 'toolchain-candidates summary: <JSON>' to
      the log; --out also writes the JSON (an artifact for the report job). For a failed test run,
      it first prints the failure texts of the failing tests. Exits 0 only for success.
  toolchain-candidates.py timing --versions JSON --installed TSV --dir DIR
      Compiles once, then runs 'scripts/check.sh sbt test' with each version in the order
      A B C C B A in this job, and prints each version's time against the pinned one's.
  toolchain-candidates.py report --summaries DIR [--mutation DIR]
      Reads the plan (PLAN), the results of the jobs (NEEDS: toJSON(needs)), the summaries and the
      logs of the mutation shards, and prints the comparison, the failure texts of the mutation
      shards, and then the records.

The classes of a part: success and test-failure are results; setup (the action, the cache, the
build, scripts/toolchain.sh check, or a [toolchain] or [sim-seed] line other than the run's),
timeout, cancelled, skipped, compile (scalac or scalafmt) and other are inconclusive and call for
a re-run. A candidate is compared with the control test by test ('<suite> / <test>'; the suite
alone where no JUnit report names a test). A failure of a candidate alone is a regression only
when a re-run of the same part fails the same way: the report compares with the records committed
from earlier runs.

Failure texts: the files of a failed run that a fixture in scripts/testdata/mutation-classify/
needs (exit, the verdict of scripts/mutation-classify.py, classification.tsv, the failing test
cases of the JUnit reports with their bodies, and the ends of sbt.log and of the ChiselSim logs),
each between '===BEGIN failure-text <label>/<file>===' and '===END===' (#121 M8: the failures of
Verilator 5.040 and later).
"""

import argparse
import datetime
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UPSTREAM = "https://github.com/verilator/verilator"
KNOBS = ("CANDIDATES", "MUTATION", "SEED_RUNS", "TIMING", "PARTS")
# The values on main (.github/workflows/toolchain-candidates.yml): restore them before merging.
DEFAULTS = {"CANDIDATES": "5.032 5.052", "MUTATION": "", "SEED_RUNS": "1", "TIMING": "false", "PARTS": ""}
PART_NAMES = ("test", "timing", "mutation")
# The timing job has one setup step for the pinned version and one for each candidate.
MAX_CANDIDATES = 3
MAX_SEED_RUNS = 10
# ADR-0004 adopts only versions released at least two weeks ago.
ADOPT_DAYS = 14
# #121 §3: the slowest of the 40 recent successful `check.sh scala` steps of ci.yml, in seconds.
RECENT_MAX_SECONDS = 555
RESULTS = ("success", "test-failure")
SUMMARY_PREFIX = "toolchain-candidates summary: "
COUNTS = ("succeeded", "failed", "canceled", "ignored", "pending")
# The failing tests that a summary keeps for the comparison ([suite, test, kind]), and those shown
# with their detail.
FAILED_TESTS_KEPT = 500
FAILURES_SHOWN = 20
# Failure texts: the characters of a body kept before the lines that are not stack frames, the
# lines of the end of a log, the failing test cases per run, and the runs per mutation version.
BODY_KEPT = 12000
TAIL_LINES = 60
CASES_SHOWN = 4
SAMPLES_PER_VERSION = 8

VERSION = re.compile(r"[0-9]+\.[0-9]{3}")
APT = re.compile(r"apt:([0-9]+\.[0-9]{3})-[0-9A-Za-z.+~]+")
SHA = re.compile(r"[0-9a-f]{40}")
KNOB_CHARS = re.compile(r"[A-Za-z0-9.:+~ -]*")

# sbt colours its output on CI (it sees the CI variable) even into a pipe.
ANSI = re.compile(r"\x1b\[[0-9;?]*[A-Za-z]")
TESTS = re.compile(r"Tests: succeeded (\d+), failed (\d+), canceled (\d+), ignored (\d+), pending (\d+)")
SUITES = re.compile(r"Suites: completed (\d+), aborted (\d+)")
SCALAC = re.compile(r"^\[error\] \((\S+) / (Compile|Test) / compileIncremental\) Compilation failed", re.M)
SCALAFMT = re.compile(r"^\[error\] \(((?:[^()]*/ )?scalafmt\w*)\)", re.M)
SUITE_LIST = re.compile(r"^\[error\] (?:Failed tests|Error during tests):\s*$")
SUITE_NAME = re.compile(r"^\[error\]\s+([A-Za-z_$][\w$]*(?:\.[\w$]+)*)\s*$")
TOOLCHAIN_LINE = re.compile(r"^\[toolchain\] (.*?)\s*$", re.M)
SEED_LINE = re.compile(r"^\[sim-seed\] (.*?)\s*$", re.M)
# vliw.hw.SimSeed.describe: VLIW_SIM_SEED=<n> (<default|random|env>) verilator=<version>.
SEED_SOURCE = re.compile(r"^VLIW_SIM_SEED=\S+ \((\w+)\)")
FORK_DIED = re.compile(r"^Warning: Unable to read from client", re.M)
# scripts/check.sh's own messages.
CHECK_STOPPED = "check: sbt stopped after CHECK_TIMEOUT="
TOOLCHAIN_FAILED = "check: scripts/toolchain.sh check failed"
WARNING = re.compile(r"%Warning-([A-Za-z0-9_]+):")
# The stack frames of a JUnit body (the classifier reads only the first after each exception line).
FRAME_LINE = re.compile(r"^\s+at [\w$.]+\(")
MORE_LINE = re.compile(r"^\s+\.\.\. \d+ more\s*$")
# One line per run section of a committed report.md.
RECORD_LINE = re.compile(r"^<!-- toolchain-candidates: (\{.*\}) -->$", re.M)
MUTATION_ARTIFACT = re.compile(r"^mutation-logs-verilator-(.+)-shard(\d+)-attempt(\d+)$")


class Failure(Exception):
    """An error that ends the step with a message."""


def annotate(level, message):
    """A workflow annotation on GitHub Actions; a plain line elsewhere."""
    message = message.replace("\n", " ")
    print(f"::{level}::{message}" if os.environ.get("GITHUB_ACTIONS") else f"{level}: {message}")


def print_texts(lines, out=print):
    """Prints LINES (failure texts, which quote logs); on GitHub Actions with the workflow commands
    stopped, so that no line of a log is read as one."""
    token = f"toolchain-candidates-{os.getpid()}-{time.time_ns()}" if os.environ.get("GITHUB_ACTIONS") else ""
    if token:
        out(f"::stop-commands::{token}")
    for line in lines:
        out(line)
    if token:
        out(f"::{token}::")


def read_text(path, default=None):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError:
        return default


def write_text(path, text):
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def dumps(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def unique(items):
    result = []
    for item in items:
        if item not in result:
            result.append(item)
    return result


def capture(cmd, timeout=180):
    """(exit status or None, stdout and stderr) of CMD."""
    try:
        p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, universal_newlines=True,
                           timeout=timeout)
        return p.returncode, p.stdout
    except (OSError, subprocess.TimeoutExpired) as e:
        return None, f"{cmd[0]}: {e}"


def set_outputs(values):
    """Appends name=value lines to GITHUB_OUTPUT."""
    path = os.environ.get("GITHUB_OUTPUT")
    if not path:
        raise Failure("GITHUB_OUTPUT is not set")
    with open(path, "a", encoding="utf-8") as f:
        for name, value in values.items():
            if "\n" in value:
                raise Failure(f"the output {name} has a line break")
            f.write(f"{name}={value}\n")


# --- plan ----------------------------------------------------------------------------------------
def read_pinned(file=None):
    """The values of project/toolchain.properties (or FILE), as scripts/toolchain.sh reads them."""
    cmd = [os.path.join(REPO_ROOT, "scripts", "toolchain.sh")] + (["--file", file] if file else [])
    values = {}
    for key in ("verilator.version", "verilator.apt", "verilator.commit", "jdk.major"):
        p = subprocess.run(cmd + ["get", key], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                           universal_newlines=True)
        if p.returncode != 0:
            raise Failure(f"scripts/toolchain.sh get {key} failed: {p.stderr.strip()}")
        values[key] = p.stdout.rstrip("\n")
    return values


def git_ls_remote(refs):
    """{ref: sha} of those REFS that the upstream repository has (git ls-remote)."""
    code, out = capture(["git", "ls-remote", UPSTREAM] + list(refs))
    if code != 0:
        raise Failure(f"git ls-remote {UPSTREAM} failed (exit {code}): {out.strip()[:300]}")
    found = {}
    for line in out.splitlines():
        sha, _, ref = line.partition("\t")
        if ref:
            found[ref] = sha
    return found


def gh_tag_date(tag):
    """The tagger date of the annotated tag object TAG (GitHub's API through gh), or None."""
    if not SHA.fullmatch(tag or ""):
        return None
    code, out = capture(["gh", "api", f"repos/verilator/verilator/git/tags/{tag}", "--jq", ".tagger.date"], 60)
    if code != 0:
        return None
    return out.strip() or None


def parse_time(text):
    """An ISO 8601 time such as 2026-09-05T12:00:00Z, in UTC, or None."""
    try:
        t = datetime.datetime.fromisoformat((text or "").strip().replace("Z", "+00:00"))
    except ValueError:
        return None
    if t.tzinfo is None:
        t = t.replace(tzinfo=datetime.timezone.utc)
    return t.astimezone(datetime.timezone.utc)


def describe_install(entry):
    if entry["method"] == "apt":
        return f"apt {entry['apt']}"
    return f"source {entry['source_commit'] or '(no commit)'}"


def make_plan(env, pinned, ls_remote, tag_date, now, records=lambda version: []):
    """Checks the knobs in ENV, with PINNED (the values of the properties file); returns the plan,
    or raises Failure with every error. ls_remote(refs) -> {ref: sha}; tag_date(tag object) -> an
    ISO time or None; NOW: a UTC datetime; records(version) -> the machine lines committed in
    docs/toolchain/<version>/report.md (a tag that has moved since then warns)."""
    errors, notes, warnings = [], [], []
    knobs = {}
    for name in KNOBS:
        value = env.get(name)
        if value is None:
            errors.append(f"{name} is not set (the workflow's env sets every knob)")
            value = ""
        elif not KNOB_CHARS.fullmatch(value):
            errors.append(f"{name}={value!r}: only letters, digits, '.', ':', '+', '~', '-' and spaces")
            value = ""
        knobs[name] = value

    version = pinned["verilator.version"]
    control = {"version": version, "kind": "pinned", "verilator": "", "commit": "",
               "method": "apt" if pinned["verilator.apt"] else "source", "apt": pinned["verilator.apt"],
               "source_commit": pinned["verilator.commit"], "tag_date": ""}
    candidates, seen = [], set()
    for token in knobs["CANDIDATES"].split():
        if token in seen:
            notes.append(f"CANDIDATES lists {token} more than once; it runs once")
            continue
        seen.add(token)
        apt = APT.fullmatch(token)
        if VERSION.fullmatch(token):
            entry = {"version": token, "method": "source", "verilator": token, "apt": ""}
        elif apt:
            entry = {"version": apt.group(1), "method": "apt", "verilator": token, "apt": token[len("apt:"):]}
        else:
            errors.append(f"CANDIDATES: '{token}' is neither a version such as 5.052 nor apt:<package version>"
                          " such as apt:5.020-1")
            continue
        if entry["version"] == version:
            notes.append(f"CANDIDATES: {token} is the pinned version {version}, which every run tries as the"
                         " control; left out")
            continue
        same = [c for c in candidates if c["version"] == entry["version"]]
        if same:
            errors.append(f"CANDIDATES: {same[0]['verilator']} and {token} are both Verilator {entry['version']};"
                          " the records are per version (docs/toolchain/<version>/), so give one of them")
            continue
        entry.update(kind="candidate", commit="", source_commit="", tag_date="")
        candidates.append(entry)
    if len(candidates) > MAX_CANDIDATES:
        errors.append(f"CANDIDATES: {len(candidates)} candidates; at most {MAX_CANDIDATES}")

    sources = [c for c in candidates if c["method"] == "source"]
    if sources and not errors:
        refs = []
        for c in sources:
            refs += [f"refs/tags/v{c['version']}", f"refs/tags/v{c['version']}^{{}}"]
        found = ls_remote(refs)
        for c in sources:
            commit = found.get(f"refs/tags/v{c['version']}^{{}}", "")
            if not SHA.fullmatch(commit):
                errors.append(f"CANDIDATES: {c['version']}: {UPSTREAM} has no annotated tag v{c['version']}"
                              f" (git ls-remote printed no refs/tags/v{c['version']}^{{}})")
                continue
            c["commit"] = c["source_commit"] = commit
            # scripts/verilator-build.sh catches a tag moved during this run; the records, one moved since.
            moved = [r for r in records(c["version"])
                     if SHA.fullmatch(str(r.get("commit") or "")) and r["commit"] != commit]
            if moved:
                warnings.append(f"the tag v{c['version']} is now at {commit}, but run {moved[-1].get('run_id')} built"
                                f" {moved[-1]['commit']} (docs/toolchain/{c['version']}/report.md): the tag has moved,"
                                " so this run does not test what the records say")
            when = parse_time(tag_date(found.get(f"refs/tags/v{c['version']}", "")))
            if when is None:
                notes.append(f"the date of the tag v{c['version']} is unknown (GitHub's API did not answer)")
                continue
            c["tag_date"] = when.strftime("%Y-%m-%d")
            age = (now - when).days
            if age < ADOPT_DAYS:
                warnings.append(f"v{c['version']} was tagged on {c['tag_date']}, {age} days ago: ADR-0004 adopts only"
                                f" versions released at least {ADOPT_DAYS} days ago (trying it is fine)")

    versions = [control] + candidates
    by_version = {e["version"]: e for e in versions}
    mutation = []
    for token in knobs["MUTATION"].split():
        if not VERSION.fullmatch(token):
            errors.append(f"MUTATION: '{token}' is not a version such as 5.052 (candidates go by their version)")
        elif token not in by_version:
            errors.append(f"MUTATION: {token} is neither the pinned version {version} nor a candidate"
                          f" ({' '.join(c['version'] for c in candidates) or 'none'})")
        elif token in [e["version"] for e in mutation]:
            errors.append(f"MUTATION lists {token} twice")
        else:
            mutation.append(by_version[token])
    named = [e["version"] for e in mutation if e["kind"] == "candidate"]
    if named and version not in [e["version"] for e in mutation]:
        errors.append(f"MUTATION names {' '.join(named)} but not the pinned version {version}: a candidate's mutation"
                      f" check is compared with the control's in the same run, so write"
                      f" MUTATION=\"{' '.join([version] + named)}\"")
    # The control first, as in the other parts.
    mutation.sort(key=lambda e: e["kind"] != "pinned")

    seed_runs = 0
    if re.fullmatch(r"[1-9][0-9]*", knobs["SEED_RUNS"]) and int(knobs["SEED_RUNS"]) <= MAX_SEED_RUNS:
        seed_runs = int(knobs["SEED_RUNS"])
    else:
        errors.append(f"SEED_RUNS={knobs['SEED_RUNS']!r}: expected a number from 1 to {MAX_SEED_RUNS}")
    if knobs["TIMING"] not in ("true", "false"):
        errors.append(f"TIMING={knobs['TIMING']!r}: expected true or false")
    timing = knobs["TIMING"] == "true"
    parts = []
    for token in knobs["PARTS"].split():
        if token not in PART_NAMES:
            errors.append(f"PARTS: '{token}' is not a part ({', '.join(PART_NAMES)})")
        elif token in parts:
            errors.append(f"PARTS lists {token} twice")
        else:
            parts.append(token)
    if "timing" in parts and not timing:
        errors.append("PARTS names timing, but TIMING is not true")
    if "mutation" in parts and not mutation:
        errors.append("PARTS names mutation, but MUTATION names no version")

    run_test = not parts or "test" in parts
    run_timing = timing and (not parts or "timing" in parts)
    run_mutation = bool(mutation) and (not parts or "mutation" in parts)
    # One job installs every version that it times: the pinned one first, then the candidates built
    # from the source, each in a prefix of its own. A package of another version would replace the
    # pinned package, or come after the prefixes on the PATH.
    timed = [control] + [c for c in candidates if c["method"] == "source"]
    if run_timing:
        for c in candidates:
            if c["method"] == "apt":
                notes.append(f"timing leaves out {c['verilator']}: one job cannot hold a package of another"
                             " version next to the pinned version")
        if len(timed) < 2:
            errors.append("TIMING=true needs a candidate built from the source; CANDIDATES names none")

    changed = [f"{k}={knobs[k]!r}" for k in KNOBS if knobs[k] != DEFAULTS[k]]
    if changed:
        warnings.append("the knobs are not the defaults (" + ", ".join(changed) + "): restore them in"
                        " .github/workflows/toolchain-candidates.yml before merging")
    if errors:
        raise Failure("\n".join(errors))
    return {
        "schema": 1, "knobs": knobs, "pinned": control, "versions": versions, "seed_runs": seed_runs,
        "parts": {"test": run_test, "timing": run_timing, "mutation": run_mutation},
        "timing": [e["version"] for e in timed] if run_timing else [],
        "mutation": [e["version"] for e in mutation] if run_mutation else [],
        "notes": notes, "warnings": warnings,
    }


def matrix_entry(entry):
    """What a job needs of a version; verilator and commit are the action's inputs (empty for the
    pinned version)."""
    return {k: entry[k] for k in ("version", "kind", "method", "verilator", "commit")}


def plan_outputs(plan):
    by_version = {e["version"]: e for e in plan["versions"]}
    timed = [matrix_entry(by_version[v]) for v in plan["timing"]]
    return {
        "plan": dumps(plan),
        "versions": dumps([matrix_entry(e) for e in plan["versions"]]),
        "runs": dumps(list(range(1, plan["seed_runs"] + 1))),
        "test": "true" if plan["parts"]["test"] else "false",
        "timing": "true" if plan["parts"]["timing"] else "false",
        "timing_versions": dumps(timed),
        "timing_count": str(len(timed)),
        "mutation": dumps([matrix_entry(by_version[v]) for v in plan["mutation"]]),
    }


def print_plan(plan):
    print("toolchain-candidates plan")
    print("knobs: " + " ".join(f'{k}="{plan["knobs"][k]}"' for k in KNOBS))
    for e in plan["versions"]:
        tag = f", tag v{e['version']} of {e['tag_date']}" if e["tag_date"] else ""
        role = "control" if e["kind"] == "pinned" else "candidate"
        print(f"  {role:9} {e['version']}  {describe_install(e)}{tag}")
    parts = []
    if plan["parts"]["test"]:
        parts.append(f"test ({plan['seed_runs']} run(s) per version; run 1 with the default seed, any later run"
                     " with VLIW_SIM_SEED=random)")
    if plan["parts"]["timing"]:
        parts.append("timing (" + " ".join(plan["timing"] + plan["timing"][::-1]) + " in one job)")
    if plan["parts"]["mutation"]:
        parts.append("mutation (" + " ".join(plan["mutation"]) + ")")
    print("parts: " + ("; ".join(parts) or "none"))
    for note in plan["notes"]:
        annotate("notice", note)
    for warning in plan["warnings"]:
        annotate("warning", warning)


def cmd_plan(args):
    now = parse_time(args.now) if args.now else datetime.datetime.now(datetime.timezone.utc)
    records_dir = args.records or os.path.join(REPO_ROOT, "docs", "toolchain")
    try:
        plan = make_plan(os.environ, read_pinned(args.file), git_ls_remote, gh_tag_date, now,
                         lambda version: earlier_records(records_dir, version))
    except Failure as e:
        for line in str(e).splitlines():
            annotate("error", line)
        return 1
    print_plan(plan)
    set_outputs(plan_outputs(plan))
    return 0


# --- run -----------------------------------------------------------------------------------------
def run_command(directory, cmd, env=None):
    """Runs CMD with its output to this process's stdout and to DIRECTORY/output.log; writes
    DIRECTORY/start, exit and seconds. Returns the exit status (128 + the signal for a signal)."""
    os.makedirs(directory, exist_ok=True)
    for name in ("exit", "seconds"):
        if os.path.exists(os.path.join(directory, name)):
            os.remove(os.path.join(directory, name))
    write_text(os.path.join(directory, "start"), f"{time.time():.3f}\n")
    start = time.monotonic()
    out = sys.stdout.buffer
    sys.stdout.flush()
    with open(os.path.join(directory, "output.log"), "wb") as log:
        p = subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                             env=env)
        for chunk in iter(p.stdout.readline, b""):
            out.write(chunk)
            out.flush()
            log.write(chunk)
        code = p.wait()
    if code < 0:
        code = 128 - code
    write_text(os.path.join(directory, "exit"), f"{code}\n")
    write_text(os.path.join(directory, "seconds"), f"{time.monotonic() - start:.1f}\n")
    return code


def cmd_run(args):
    cmd = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not cmd:
        raise Failure("run needs a command after --")
    return run_command(args.dir, cmd)


# --- classification ------------------------------------------------------------------------------
def parse_output(text):
    """What the console output of scripts/check.sh (sbt's included) shows."""
    log = ANSI.sub("", text or "").replace("\r", "")
    facts = {"tests": None, "suites": None}
    for m in TESTS.finditer(log):
        facts["tests"] = facts["tests"] or dict.fromkeys(COUNTS, 0)
        for key, value in zip(COUNTS, m.groups()):
            facts["tests"][key] += int(value)
    for m in SUITES.finditer(log):
        facts["suites"] = facts["suites"] or {"completed": 0, "aborted": 0}
        facts["suites"]["completed"] += int(m.group(1))
        facts["suites"]["aborted"] += int(m.group(2))
    # sbt lists the suites after "Failed tests:" and "Error during tests:", one per line.
    suites, listing = [], False
    for line in log.splitlines():
        if SUITE_LIST.match(line):
            listing = True
            continue
        m = SUITE_NAME.match(line) if listing else None
        listing = bool(m)
        if m and m.group(1) not in suites:
            suites.append(m.group(1))
    facts.update({
        "failed_suites": suites,
        "toolchain": unique(TOOLCHAIN_LINE.findall(log)),
        "seeds": unique(SEED_LINE.findall(log)),
        "scalac": unique(f"{p}/{c}" for p, c in SCALAC.findall(log)),
        "scalafmt": unique(SCALAFMT.findall(log)),
        "check_stopped": CHECK_STOPPED in log,
        "toolchain_failed": TOOLCHAIN_FAILED in log,
        "fork_died": bool(FORK_DIED.search(log)),
    })
    return facts


def expected_toolchain(version, kind):
    """The [toolchain] line of a test JVM that runs with this version (vliw.hw.Toolchain.describe)."""
    return f"verilator={version} expected={version} ({'pinned' if kind == 'pinned' else 'env'})"


def classify_output(code, facts, expected, seed=None, seconds=None, limit=None):
    """(class, detail) of one run of scripts/check.sh that exited with CODE; EXPECTED is the
    [toolchain] line that every test JVM must print, or None to skip that check. SEED ('default'
    or 'random'): the source that every [sim-seed] line must name once a test JVM has run. SECONDS
    and LIMIT (CHECK_TIMEOUT): exit 137 at LIMIT or later is a timeout, since check.sh prints its
    message only for 124 (timeout's KILL after --kill-after gives 137)."""
    if facts["toolchain_failed"]:
        return "setup", "scripts/toolchain.sh check failed, so sbt did not run"
    if facts["check_stopped"] or code == 124:
        return "timeout", f"sbt stopped after CHECK_TIMEOUT (exit {code})"
    if code == 137 and seconds is not None and limit and seconds >= limit:
        return "timeout", f"sbt killed after CHECK_TIMEOUT={limit} s (exit 137 after {seconds:.0f} s)"
    if facts["scalac"]:
        return "compile", "scalac: " + ", ".join(facts["scalac"])
    if facts["scalafmt"]:
        return "compile", "scalafmt: " + ", ".join(facts["scalafmt"])
    wrong = [line for line in facts["toolchain"] if expected is not None and line != expected]
    if wrong:
        return "setup", f"a test JVM printed '[toolchain] {wrong[0]}', not '[toolchain] {expected}'"
    # Every test JVM that simulates prints both lines (vliw.hw.VliwChiselSim).
    if seed and facts["toolchain"]:
        if not facts["seeds"]:
            return "setup", f"no [sim-seed] line in the sbt log: no test JVM named its seed (expected ({seed}))"
        wrong = [s for s in facts["seeds"] if (SEED_SOURCE.match(s) or [None, None])[1] != seed]
        if wrong:
            return "setup", f"a test JVM printed '[sim-seed] {wrong[0]}', not a seed from ({seed})"
    tests, suites = facts["tests"], facts["suites"]
    failed = bool(facts["failed_suites"]) or bool(tests and tests["failed"]) or bool(suites and suites["aborted"])
    if code == 0:
        if expected is not None and not facts["toolchain"]:
            return "setup", "no [toolchain] line in the sbt log: no test JVM named the Verilator that it ran"
        if expected is not None and (tests is None or failed):
            return "other", "sbt exit 0 without a passing ScalaTest summary"
        return "success", f"{tests['succeeded']} tests passed" if tests else "sbt exit 0"
    if failed:
        if facts["failed_suites"]:
            return "test-failure", "failed: " + ", ".join(facts["failed_suites"])
        return "test-failure", f"{tests['failed'] if tests else '?'} tests failed (sbt named no suite)"
    if facts["fork_died"]:
        return "other", f"a forked test JVM died (sbt exit {code})"
    return "other", f"sbt exit {code} with no failed test"


def classify_step(setup, step, code, facts, expected, seed=None, seconds=None, limit=None):
    """(class, detail) of a test job, from the outcomes of its setup-toolchain and test steps and the
    exit status that the run wrapper recorded (None: the step was stopped before the command ended);
    the rest as in classify_output."""
    if "cancelled" in (setup, step):
        return "cancelled", f"cancelled (setup {setup or '-'}, test {step or '-'})"
    if setup != "success":
        return "setup", f"the setup-toolchain action ended with {setup or 'no outcome'}"
    if step in ("", "skipped"):
        return "skipped", "the test step did not run"
    if code is None:
        if step == "failure":
            return "timeout", "the step was stopped before scripts/check.sh ended (its timeout-minutes)"
        return "other", f"no exit status recorded (the step's outcome is {step})"
    return classify_output(code, facts, expected, seed, seconds, limit)


def classify_timing(versions, setups, step, compiled, runs):
    """(class, detail, blocked) of the timing job; blocked: no version has its own result."""
    for i, e in enumerate(versions):
        outcome = setups[i] if i < len(setups) else ""
        if outcome == "cancelled":
            return "cancelled", f"the setup-toolchain step of {e['version']} was cancelled", True
        if outcome != "success":
            return "setup", f"the setup-toolchain step of {e['version']} ended with {outcome or 'no outcome'}", True
    if step in ("", "skipped"):
        return "skipped", "the timing step did not run", True
    if compiled is None:
        cls = "cancelled" if step == "cancelled" else "timeout" if step == "failure" else "other"
        return cls, "the timing step ended before Test/compile", True
    if compiled["class"] != "success":
        return compiled["class"], "Test/compile: " + compiled["detail"], True
    if len(runs) < 2 * len(versions):
        cls = "cancelled" if step == "cancelled" else "timeout" if step == "failure" else "other"
        return cls, f"{len(runs)} of {2 * len(versions)} runs ended (the step was stopped)", False
    bad = [r for r in runs if r["class"] != "success"]
    if not bad:
        return "success", "every run passed", False
    if any(r["class"] == "test-failure" for r in bad):
        return "test-failure", f"{len(bad)} of {len(runs)} runs failed", False
    return bad[0]["class"], f"{len(bad)} of {len(runs)} runs did not pass", False


def ratio_of(times, base):
    if not times or not base or None in times or None in base or sum(base) <= 0:
        return None
    return round((sum(times) / len(times)) / (sum(base) / len(base)), 3)


def timing_versions(summary):
    """{version: {class, detail, seconds, failed_suites, failed_tests, failed_tests_cut, ratio}} of a
    timing summary; ratio is the mean time against the pinned version's (the first), when both
    passed."""
    runs, versions, result = summary.get("runs") or [], summary.get("versions") or [], {}
    for e in versions:
        mine = [r for r in runs if r["version"] == e["version"]]
        bad = [r for r in mine if r["class"] != "success"]
        failures = [r for r in bad if r["class"] == "test-failure"]
        if summary.get("blocked"):
            cls, detail = summary["class"], summary["detail"]
        elif failures:
            cls = "test-failure"
            detail = "; ".join(f"position {r['position']}: {r['detail']}" for r in failures)
        elif bad:
            cls = bad[0]["class"]
            detail = "; ".join(f"position {r['position']}: {r['class']} ({r['detail']})" for r in bad)
        elif len(mine) < 2:
            cls = summary["class"] if summary["class"] not in RESULTS else "other"
            detail = f"{len(mine)} of 2 runs ended ({summary['detail']})"
        else:
            cls, detail = "success", "both runs passed"
        result[e["version"]] = {"class": cls, "detail": detail, "seconds": [r["seconds"] for r in mine],
                                "failed_suites": unique(s for r in failures for s in r.get("failed_suites") or []),
                                "failed_tests": unique(t for r in failures for t in r.get("failed_tests") or []),
                                "failed_tests_cut": any(r.get("failed_tests_cut") for r in failures)}
    if versions:
        base = result[versions[0]["version"]]
        for info in result.values():
            both = info["class"] == "success" and base["class"] == "success"
            info["ratio"] = ratio_of(info["seconds"], base["seconds"]) if both else None
    return result


# --- summaries -----------------------------------------------------------------------------------
def read_number(path, kind):
    text = read_text(path)
    try:
        return kind(text.strip()) if text is not None else None
    except ValueError:
        return None


def runner_facts():
    cpu = ""
    for line in (read_text("/proc/cpuinfo", "") or "").splitlines():
        if line.startswith("model name"):
            cpu = line.split(":", 1)[1].strip()
            break
    if not cpu:
        code, out = capture(["sysctl", "-n", "machdep.cpu.brand_string"])
        cpu = out.strip() if code == 0 else ""
    try:
        nproc = len(os.sched_getaffinity(0))
    except AttributeError:
        nproc = os.cpu_count() or 0
    image = " ".join(x for x in (os.environ.get("ImageOS", ""), os.environ.get("ImageVersion", "")) if x)
    return {"cpu": cpu or "unknown", "nproc": nproc, "image": image or "unknown"}


def fingerprint(entry, toolchain_file=None, start=None):
    """Lines that name the installed toolchain, like the action's fingerprint: scripts/toolchain.sh
    check of the version, the package or the build (and whether the cache had it), the JDK."""
    toolchain = [os.path.join(REPO_ROOT, "scripts", "toolchain.sh")]
    toolchain += ["--file", toolchain_file] if toolchain_file else []
    code, out = capture(toolchain + ["check", "--expect", entry["version"]])
    lines = [line for line in out.splitlines() if line]
    if code != 0:
        lines.append(f"scripts/toolchain.sh check --expect {entry['version']}: exit {code}")
    if entry["method"] == "apt":
        code, out = capture(["dpkg-query", "-W", "-f=${Version}", "verilator"])
        lines.append(f"dpkg-query verilator: {out.strip() if code == 0 else 'not installed'}")
    else:
        path = next((line[len("verilator: "):] for line in lines if line.startswith("verilator: /")), "")
        info = os.path.join(os.path.dirname(os.path.dirname(path)), "build-info.txt") if path else ""
        text = read_text(info) if info else None
        if text is None:
            lines.append("build-info: none")
        else:
            lines += [f"build-info: {line}" for line in text.splitlines() if line]
            built = os.path.getmtime(info)
            when = datetime.datetime.fromtimestamp(built, datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
            if start is None:
                lines.append(f"cache: build-info.txt written {when}")
            elif built < start:
                lines.append(f"cache: restored (build-info.txt written {when}, before this job)")
            else:
                lines.append(f"cache: built in this job (build-info.txt written {when})")
    code, out = capture(["java", "-version"])
    java = [line for line in out.splitlines() if re.match(r'^[a-z]+ version "', line)]
    lines.append(f"java -version: {java[0] if java else out.strip()[:200]}")
    lines.append(f"VLIW_VERILATOR_EXPECTED: {os.environ.get('VLIW_VERILATOR_EXPECTED', 'unset')}")
    return lines


def warning_counts(start=None):
    """({code: count} of %Warning-<CODE> in the ChiselSim compilation logs written since START, the
    number of those logs: one per Verilator build)."""
    counts, builds = {}, 0
    for path in sorted(glob.glob(os.path.join("build", "chiselsim", "**", "compilation-log.txt"), recursive=True)):
        if start is not None and os.path.getmtime(path) < start:
            continue
        builds += 1
        for code in WARNING.findall(read_text(path, "")):
            counts[code] = counts.get(code, 0) + 1
    return dict(sorted(counts.items())), builds


def read_rows(run_dir):
    """The rows of RUN_DIR/classification.tsv (scripts/mutation-classify.py): [suite, test, kind, detail]."""
    rows = []
    for line in (read_text(os.path.join(run_dir, "classification.tsv"), "") or "").splitlines():
        fields = line.split("\t")
        if len(fields) >= 4:
            rows.append(fields[:3] + ["\t".join(fields[3:])])
    return rows


def junit_failures(code, output, label, since=None):
    """The failing tests in this checkout's JUnit reports (*/target/test-reports/*.xml written at
    SINCE or later), as scripts/mutation-classify.py classifies them (assertion, exception,
    env:sim-build, env:sim-exit, ...): (its verdict line, rows [suite, test, kind, detail], and the
    failure texts of the run, as LABEL)."""
    reports = [p for p in sorted(glob.glob(os.path.join("*", "target", "test-reports", "*.xml")))
               if since is None or os.path.getmtime(p) >= since]
    if code in (None, 0) or not reports:
        return "", [], []
    tmp = tempfile.mkdtemp()
    try:
        write_text(os.path.join(tmp, "sbt.log"), output)
        write_text(os.path.join(tmp, "exit"), f"{code}\n")
        for path in reports:
            target = os.path.join(tmp, "reports", path.split(os.sep)[0])
            os.makedirs(target, exist_ok=True)
            shutil.copy(path, target)
        _, verdict = capture([sys.executable, "-I", os.path.join(REPO_ROOT, "scripts", "mutation-classify.py"), tmp])
        verdict = verdict.strip()
        # As scripts/mutation-check.sh keeps it, for the failure texts.
        write_text(os.path.join(tmp, "verdict"), verdict + "\n")
        return verdict[:300], read_rows(tmp), failure_texts(label, tmp)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def failure_fields(rows):
    """The fields of a summary for the failing tests ROWS: those shown (cut) and those compared."""
    return {"failures": [[s, t[:120], k, d[:200]] for s, t, k, d in rows[:FAILURES_SHOWN]],
            "failed_tests": [[s, t, k] for s, t, k, _ in rows[:FAILED_TESTS_KEPT]],
            "failed_tests_cut": len(rows) > FAILED_TESTS_KEPT}


def named_failure(cls, detail, suites, rows):
    """A test-failure that names neither a suite (sbt) nor a test (the JUnit reports) cannot be
    compared with another version's failures, nor found again in a re-run: other."""
    if cls == "test-failure" and not suites and not rows:
        return "other", f"{detail}, and no JUnit report names a failing test"
    return cls, detail


# --- failure texts -------------------------------------------------------------------------------
def cut_body(text):
    """TEXT (the body of a JUnit <failure> or <error>) cut at a line break after BODY_KEPT characters,
    followed by the lines of the rest that are not stack frames, each block of them with its first
    frame: what scripts/mutation-classify.py reads (the exception and 'Caused by:' lines, the first
    frame after each, 'Nonzero exit status', make's and Verilator's messages)."""
    if len(text) <= BODY_KEPT:
        return text
    head = text[:BODY_KEPT]
    head = head[:head.rfind("\n") + 1] or head
    kept, frame = [], False
    for line in text[len(head):].splitlines():
        if FRAME_LINE.match(line):
            if frame:
                kept.append(line)
            frame = False
        elif not MORE_LINE.match(line) and len(kept) < 200:
            kept.append(line[:400])
            frame = True
    note = (f"\t... toolchain-candidates.py cut {len(text) - len(head)} characters here; the lines that follow are"
            " those of the rest that are not stack frames, with the first frame after each block")
    return head + "\n".join([note] + kept) + "\n"


def tail(text, lines=TAIL_LINES):
    """The last LINES lines of TEXT, without sbt's colours."""
    rows = ANSI.sub("", text or "").replace("\r", "").splitlines()
    return "".join(line[:400] + "\n" for line in rows[-lines:])


def failing_cases(path, wanted):
    """The JUnit report PATH with only its failing test cases whose (suite, test) is in WANTED, their
    bodies cut (cut_body); None if there is none. An unreadable report comes as it is (cut)."""
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError):
        return (read_text(path, "") or "")[:BODY_KEPT] or None
    suite = ET.Element(root.tag, dict(root.attrib))
    for case in root.iter("testcase"):
        bad = case.findall("failure") + case.findall("error")
        if not bad or (case.get("classname") or root.get("name"), case.get("name") or "") not in wanted:
            continue
        copy = ET.SubElement(suite, "testcase", dict(case.attrib))
        for el in bad:
            attrs = {k: v[:2000] if k == "message" else v for k, v in el.attrib.items()}
            ET.SubElement(copy, el.tag, attrs).text = cut_body(el.text or "")
    if not len(suite):
        return None
    if hasattr(ET, "indent"):
        ET.indent(suite)
    return '<?xml version="1.0" encoding="UTF-8"?>\n' + ET.tostring(suite, encoding="unicode") + "\n"


def failure_texts(label, run_dir):
    """The failure texts of the failed run in RUN_DIR, in the layout of scripts/mutation-check.sh
    (exit, sbt.log, reports/<project>/*.xml, verdict, classification.tsv, mutant.diff and the
    ChiselSim logs under build/chiselsim): lines, each file between '===BEGIN failure-text
    LABEL/<file>===' and '===END==='. Of the failing tests, the first of each kind (CASES_SHOWN
    kinds at most), so that a fixture of scripts/testdata/mutation-classify can be made from them."""
    blocks = []
    for name, keep in (("verdict", None), ("exit", None), ("classification.tsv", 50)):
        text = read_text(os.path.join(run_dir, name))
        if text is not None:
            blocks.append((name, "".join(text.splitlines(True)[:keep]) if keep else text))
    wanted, kinds = set(), []
    for suite, test, kind, _ in read_rows(run_dir):
        if kind not in kinds and len(kinds) < CASES_SHOWN:
            kinds.append(kind)
            wanted.add((suite, test))
    for path in sorted(glob.glob(os.path.join(run_dir, "reports", "**", "*.xml"), recursive=True)):
        text = failing_cases(path, wanted)
        if text:
            blocks.append((os.path.relpath(path, run_dir), text))
    if os.path.exists(os.path.join(run_dir, "sbt.log")):
        blocks.append(("sbt.log", tail(read_text(os.path.join(run_dir, "sbt.log"), ""))))
    for name in ("simulation-log.txt", "compilation-log.txt"):
        for path in sorted(glob.glob(os.path.join(run_dir, "build", "chiselsim", "**", name), recursive=True))[:2]:
            blocks.append((os.path.relpath(path, run_dir), tail(read_text(path, ""), 40)))
    diff = read_text(os.path.join(run_dir, "mutant.diff"))
    if diff:
        blocks.append(("mutant.diff", diff[:3000]))
    lines = []
    for name, text in blocks:
        lines += [f"===BEGIN failure-text {label}/{name}===", text.rstrip("\n"), "===END==="]
    return lines


def summarize_build(args, entry):
    setup = args.setup
    if setup == "success":
        cls, detail = "success", "installed and checked"
    elif setup == "cancelled":
        cls, detail = "cancelled", "the setup-toolchain action was cancelled"
    else:
        cls, detail = "setup", f"the setup-toolchain action ended with {setup or 'no outcome'}"
    return {"part": "build", "class": cls, "detail": detail, "setup": setup,
            "fingerprint": fingerprint(entry, args.toolchain_file, read_number(args.start, float))}


def summarize_test(args, entry):
    """(the summary of a test job, the failure texts of its failing tests)."""
    def number(name, kind):
        return read_number(os.path.join(args.dir, name), kind) if args.dir else None

    output = read_text(os.path.join(args.dir, "output.log"), "") if args.dir else ""
    code, seconds, start = number("exit", int), number("seconds", float), number("start", float)
    limit = os.environ.get("CHECK_TIMEOUT", "")
    facts = parse_output(output)
    expected = expected_toolchain(entry["version"], entry["kind"])
    # Run 1 has the default seed; the workflow sets VLIW_SIM_SEED=random for the later runs.
    seed = "random" if args.run > 1 else "default"
    cls, detail = classify_step(args.setup, args.step, code, facts, expected, seed, seconds,
                                int(limit) if limit.isdigit() else None)
    warnings, builds = warning_counts(start)
    verdict, rows, texts = "", [], []
    if cls == "test-failure":
        verdict, rows, texts = junit_failures(code, output, f"{entry['version']}/test-run{args.run}", start)
        cls, detail = named_failure(cls, detail, facts["failed_suites"], rows)
    summary = {
        "part": "test", "run": args.run, "class": cls, "detail": detail, "setup": args.setup,
        "step": args.step, "exit": code, "seconds": seconds,
        "seed_env": os.environ.get("VLIW_SIM_SEED") or "default", "seed_expected": seed, "seeds": facts["seeds"],
        "toolchain": facts["toolchain"], "expected_toolchain": expected, "tests": facts["tests"],
        "suites": facts["suites"], "failed_suites": facts["failed_suites"], "junit": verdict,
        "warnings": warnings, "builds": builds, "check_timeout": limit,
    }
    summary.update(failure_fields(rows))
    if args.setup == "success":
        summary["fingerprint"] = fingerprint(entry, args.toolchain_file, read_number(args.start, float))
    return summary, texts


def summarize_timing(args):
    versions = json.loads(args.versions or "[]")
    doc = {}
    if args.dir:
        doc = json.loads(read_text(os.path.join(args.dir, "runs.json"), "") or "{}")
    summary = {"part": "timing", "versions": versions, "setup": args.setup.split(), "step": args.step,
               "compile": doc.get("compile"), "runs": doc.get("runs") or []}
    cls, detail, blocked = classify_timing(versions, summary["setup"], args.step, summary["compile"], summary["runs"])
    summary.update({"class": cls, "detail": detail, "blocked": blocked})
    summary["per_version"] = timing_versions(summary)
    return summary


def print_timing(summary, out=print):
    for r in summary.get("runs") or []:
        seconds = "-" if r.get("seconds") is None else f"{r['seconds']:.1f}"
        out(f"position {r['position']}: {r['version']} {seconds} s, exit {r.get('exit')}: {r['class']} ({r['detail']})")
    versions = summary.get("versions") or []
    for v, info in (summary.get("per_version") or {}).items():
        ratio = "no ratio"
        if info.get("ratio") is not None:
            ratio = f"ratio {info['ratio']:.3f} against {versions[0]['version']}"
        out(f"timing {v}: {info['class']} ({info['detail']}); {ratio}")


def cmd_summary(args):
    if args.part == "timing":
        summary = summarize_timing(args)
        print_timing(summary)
    else:
        entry = json.loads(args.entry or "{}")
        if not entry.get("version"):
            raise Failure("summary build and test need --entry with the version's matrix entry")
        summary, texts = (summarize_build(args, entry), []) if args.part == "build" else summarize_test(args, entry)
        summary.update({"version": entry["version"], "kind": entry["kind"], "method": entry["method"]})
        for line in summary.get("fingerprint") or []:
            print(f"fingerprint: {line}")
        if texts:
            print("failure texts of the failing tests (the first of each kind), for the fixtures of"
                  " scripts/testdata/mutation-classify (#121 M8):")
            print_texts(texts)
        run = f" run {args.run}" if args.part == "test" else ""
        print(f"{args.part} {entry['version']}{run}: {summary['class']} ({summary['detail']})")
    summary.update({"schema": 1, "attempt": int(os.environ.get("GITHUB_RUN_ATTEMPT") or 1)})
    summary.update(runner_facts())
    print(SUMMARY_PREFIX + dumps(summary))
    if args.out:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        write_text(args.out, dumps(summary) + "\n")
    return 0 if summary["class"] == "success" else 1


# --- timing --------------------------------------------------------------------------------------
def timing_env(base, entry, versions, dirs):
    """The environment of one timed run: of the versions' prefixes only this one's on the PATH (a
    package is in /usr/bin, which stays), VLIW_VERILATOR_EXPECTED for a candidate, the default seed."""
    env = dict(base)
    strip = {dirs[e["version"]] for e in versions if e["method"] == "source"}
    path = [p for p in env.get("PATH", "").split(os.pathsep) if p and p not in strip]
    if entry["method"] == "source":
        path.insert(0, dirs[entry["version"]])
    env["PATH"] = os.pathsep.join(path)
    if entry["kind"] == "pinned":
        env.pop("VLIW_VERILATOR_EXPECTED", None)
    else:
        env["VLIW_VERILATOR_EXPECTED"] = entry["version"]
    env.pop("VLIW_SIM_SEED", None)
    return env


def cmd_timing(args):
    versions = json.loads(args.versions)
    if len(versions) < 2 or versions[0]["kind"] != "pinned":
        raise Failure("timing needs the pinned version first and at least one candidate")
    dirs = {}
    for line in (read_text(args.installed, "") or "").splitlines():
        version, _, path = line.partition("\t")
        if version and path:
            dirs[version] = os.path.dirname(path)
    missing = [e["version"] for e in versions if e["version"] not in dirs]
    if missing:
        raise Failure(f"{args.installed} names no verilator for {' '.join(missing)}")
    check = args.check or os.path.join(REPO_ROOT, "scripts", "check.sh")
    os.makedirs(args.dir, exist_ok=True)
    doc = {"compile": None, "runs": []}

    def save():
        write_text(os.path.join(args.dir, "runs.json"), dumps(doc) + "\n")

    directory = os.path.join(args.dir, "compile")
    env = timing_env(os.environ, versions[0], versions, dirs)
    env["CHECK_LOG_DIR"] = os.path.join(directory, "check-logs")
    print(f"==> timing: compile once with {versions[0]['version']} (not timed)", flush=True)
    code = run_command(directory, [check, "sbt", "Test/compile"], env)
    cls, detail = classify_output(code, parse_output(read_text(os.path.join(directory, "output.log"), "")), None)
    doc["compile"] = {"class": cls, "detail": detail, "exit": code,
                      "seconds": read_number(os.path.join(directory, "seconds"), float)}
    save()
    if cls != "success":
        print(f"timing: Test/compile: {cls} ({detail})")
        return 1
    order = versions + versions[::-1]
    for position, entry in enumerate(order, 1):
        directory = os.path.join(args.dir, "runs", f"{position}-{entry['version']}")
        env = timing_env(os.environ, entry, versions, dirs)
        env["CHECK_LOG_DIR"] = os.path.join(directory, "check-logs")
        env["CHECK_TIMEOUT"] = str(args.run_timeout)
        print(f"==> timing: position {position} of {len(order)}: Verilator {entry['version']}", flush=True)
        code = run_command(directory, [check, "sbt", "test"], env)
        output = read_text(os.path.join(directory, "output.log"), "")
        facts = parse_output(output)
        seconds = read_number(os.path.join(directory, "seconds"), float)
        cls, detail = classify_output(code, facts, expected_toolchain(entry["version"], entry["kind"]), "default",
                                      seconds, args.run_timeout)
        rows = []
        if cls == "test-failure":
            # The reports of this run only: the earlier runs of this job wrote theirs in the same place.
            _, rows, texts = junit_failures(code, output, f"{entry['version']}/timing-position{position}",
                                            read_number(os.path.join(directory, "start"), float))
            cls, detail = named_failure(cls, detail, facts["failed_suites"], rows)
            if texts:
                print(f"timing: failure texts of position {position} (the first failing test of each kind):")
                print_texts(texts)
        doc["runs"].append({"position": position, "version": entry["version"], "exit": code, "seconds": seconds,
                            "class": cls, "detail": detail, "failed_suites": facts["failed_suites"],
                            "seeds": facts["seeds"]})
        doc["runs"][-1].update({k: v for k, v in failure_fields(rows).items() if k != "failures"})
        save()
        print(f"timing: position {position}: {entry['version']} {cls} ({detail}) in {seconds} s", flush=True)
    summary = {"versions": versions, "runs": doc["runs"]}
    summary.update(dict(zip(("class", "detail", "blocked"),
                            classify_timing(versions, ["success"] * len(versions), "success", doc["compile"],
                                            doc["runs"]))))
    summary["per_version"] = timing_versions(summary)
    print_timing(summary)
    return 0 if summary["class"] == "success" else 1


# --- mutation logs -------------------------------------------------------------------------------
def shard_mutants(path, k, n):
    """The mutant ids of shard K of N, picked from mutants.tsv as scripts/mutation-check.sh does."""
    ids, row = [], 0
    for line in (read_text(path, "") or "").splitlines():
        mutant = line.lstrip("\t").split("\t")[0]
        if not mutant or mutant.startswith("#"):
            continue
        row += 1
        if (row - k) % n == 0:
            ids.append(mutant)
    return ids


def parse_shard(path):
    """The logs of one shard of scripts/mutation-check.sh (its MUTATION_LOG_DIR)."""
    env = read_text(os.path.join(path, "env.txt"), "") or ""
    results, text = None, read_text(os.path.join(path, "results.tsv"))
    if text is not None:
        results = []
        # id, verdict, category, detail, log (the directory of the last attempt); a tab in the detail
        # leaves the log last.
        for line in text.splitlines()[1:]:
            fields = line.split("\t")
            if fields[0]:
                fields += [""] * (5 - len(fields))
                results.append({"id": fields[0], "verdict": fields[1], "category": fields[2],
                                "detail": "\t".join(fields[3:-1]), "dir": os.path.basename(fields[-1].rstrip("/"))})
    baseline, retries = [], {}
    for name in sorted(os.listdir(path)):
        m = re.fullmatch(r"(.+)\.retry(\d+)", name)
        mutant, attempt = (m.group(1), int(m.group(2))) if m else (name, 0)
        if mutant == "_baseline":
            fields = ((read_text(os.path.join(path, name, "verdict"), "") or "").rstrip("\n").split("\t") + ["", ""])
            baseline.append({"attempt": attempt, "verdict": fields[0] or "ERROR", "category": fields[1],
                             "detail": fields[2], "dir": name})
        elif m:
            retries[mutant] = retries.get(mutant, 0) + 1
    baseline.sort(key=lambda b: b["attempt"])
    shard = re.search(r"^shard: (\d+)/(\d+)$", env, re.M)
    expected = re.search(r"^expected: verilator (\S+)", env, re.M)
    return {"results": results, "baseline": baseline, "retries": retries, "path": path,
            "shard": int(shard.group(1)) if shard else None, "n": int(shard.group(2)) if shard else None,
            "version": expected.group(1) if expected else None, "attempt": 1}


def load_mutation(root):
    """({version: {shard: logs}}, notes) of the downloaded artifacts of mutation.yml, the latest
    attempt of each shard. download-artifact puts a single artifact straight into ROOT, several into
    ROOT/<name>; the version and the shard come from the name, else from env.txt."""
    found, notes = {}, []
    if not os.path.isdir(root):
        return found, notes
    if any(os.path.exists(os.path.join(root, f)) for f in ("env.txt", "results.tsv", "_baseline")):
        dirs = [(root, "")]
    else:
        dirs = [(os.path.join(root, d), d) for d in sorted(os.listdir(root)) if os.path.isdir(os.path.join(root, d))]
    for path, name in dirs:
        logs = parse_shard(path)
        m = MUTATION_ARTIFACT.match(name)
        if m:
            logs.update(version=m.group(1).split("-")[0], shard=int(m.group(2)), attempt=int(m.group(3)))
        version, shard = logs["version"], logs["shard"]
        if not (version and VERSION.fullmatch(version) and shard):
            notes.append(f"{name or path}: cannot tell the version and the shard of these mutation logs; left out")
            continue
        old = found.setdefault(version, {}).get(shard)
        if old is None or logs["attempt"] >= old["attempt"]:
            found[version][shard] = logs
    return found, notes


def shard_count(shards):
    for s in shards.values():
        if s.get("n"):
            return s["n"]
    text = read_text(os.path.join(REPO_ROOT, ".github", "workflows", "mutation.yml"), "") or ""
    m = re.search(r"MUTATION_SHARD: \$\{\{ matrix\.shard \}\}/(\d+)", text)
    return int(m.group(1)) if m else 8


def mutation_part(shards, mutants_file):
    """The mutation part of one version: class, detail, verdicts {mutant: (verdict, category)} (None
    unless every mutant has one; all_verdicts has those there are), failed (the SURVIVED mutants, and
    'baseline: <suite> / <test>' for each test that failed in an unmutated run), notes, rows."""
    n = shard_count(shards)
    problems, baselines, notes, verdicts, errors = [], {}, [], {}, {}
    complete = True
    for k in range(1, n + 1):
        s = shards.get(k)
        if s is None:
            problems.append(f"shard {k}: no logs")
            complete = False
            continue
        last = s["baseline"][-1] if s["baseline"] else None
        if last and last["verdict"] == "SURVIVED" and len(s["baseline"]) > 1:
            first = s["baseline"][0]
            notes.append(f"shard {k}: baseline: passed on a retry; the first run ended with {first['verdict']}"
                         f" {first['category']}")
        if last and last["verdict"] != "SURVIVED":
            complete = False
            if last["verdict"] == "KILLED":
                # The tests that killed it (scripts/mutation-classify.py), as the test part names them.
                rows = [r for r in read_rows(os.path.join(s["path"], last["dir"]))
                        if r[2] in ("assertion", "exception")]
                notes.append(f"shard {k}: the unmutated tests failed ({last['category']}: {last['detail'][:160]})")
                if rows:
                    baselines[k] = [f"baseline: {label}" for label in failure_labels([], rows)]
                else:
                    problems.append(f"shard {k}: the unmutated tests failed, but its classification.tsv names no test")
            else:
                problems.append(f"shard {k}: the unmutated run ended with {last['verdict']} {last['category']}")
            continue
        if s["results"] is None:
            problems.append(f"shard {k}: no results.tsv")
            complete = False
            continue
        got = {r["id"] for r in s["results"]}
        missing = [m for m in shard_mutants(mutants_file, k, n) if m not in got]
        if missing:
            problems.append(f"shard {k}: {len(missing)} mutants have no verdict (stopped early?)")
            complete = False
        for r in s["results"]:
            verdicts[r["id"]] = (r["verdict"], r["category"])
            if r["verdict"] not in ("KILLED", "SURVIVED"):
                key = f"{r['verdict']} {r['category']}".strip()
                errors[key] = errors.get(key, 0) + 1
        retried = sorted(m for m in s["retries"] if m != "_baseline")
        if retried:
            notes.append(f"shard {k}: retried after an environment ERROR: {', '.join(retried)}")
    survived = sorted(m for m, (v, _) in verdicts.items() if v == "SURVIVED")
    killed = sum(1 for v, _ in verdicts.values() if v == "KILLED")
    inconclusive = problems + [f"{key} x{count}" for key, count in sorted(errors.items())]
    failed_baselines = unique(label for k in sorted(baselines) for label in baselines[k])
    if survived or failed_baselines:
        cls = "test-failure"
        found = ([f"the unmutated tests failed in shard {', '.join(map(str, sorted(baselines)))}"
                  f" ({names_text([label[len('baseline: '):] for label in failed_baselines])})"]
                 if failed_baselines else [])
        found += [f"SURVIVED: {', '.join(survived)}"] if survived else []
        detail = "; ".join(found) + ("; also " + "; ".join(inconclusive) if inconclusive else "")
    elif inconclusive:
        cls, detail = "other", "; ".join(inconclusive)
    else:
        cls, detail = "success", f"every mutant KILLED ({killed})"
    return {"class": cls, "detail": detail, "verdicts": verdicts if complete else None, "all_verdicts": verdicts,
            "notes": notes, "failed": set(survived) | set(failed_baselines),
            "counts": f"{killed} KILLED, {len(survived)} SURVIVED, {sum(errors.values())} other",
            "rows": [(k, shards.get(k)) for k in range(1, n + 1)]}


def mutation_samples(version, shards, control=None):
    """The failed runs of VERSION's mutation shards whose failure texts the report prints: the first
    unmutated run of each (verdict, category) that is not SURVIVED, the first mutant of each verdict
    other than KILLED and SURVIVED, and the first KILLED mutant of each category that differs from
    the control's verdict (CONTROL: {mutant: (verdict, category)}), SAMPLES_PER_VERSION at most:
    [(label, header, directory)]."""
    samples, seen = [], set()

    def add(key, k, s, name, header):
        if key in seen or len(samples) >= SAMPLES_PER_VERSION:
            return
        seen.add(key)
        path = os.path.join(s["path"], name)
        samples.append((f"{version}/mutation/{name}", f"{version} mutation shard {k} {name}: {header}"
                        + ("" if os.path.isdir(path) else " (no logs of it in the artifact)"), path))

    for k in sorted(shards):
        for b in shards[k]["baseline"]:
            if b["verdict"] != "SURVIVED":
                add(("baseline", b["verdict"], b["category"]), k, shards[k], b["dir"],
                    f"the unmutated run (attempt {b['attempt'] + 1}): {b['verdict']} {b['category']}")
    results = sorted(((r["id"], k, r) for k in sorted(shards) for r in shards[k]["results"] or []),
                     key=lambda x: x[:2])
    for mutant, k, r in results:
        name = r["dir"] if r["dir"] and os.path.isdir(os.path.join(shards[k]["path"], r["dir"])) else mutant
        theirs = (control or {}).get(mutant)
        if r["verdict"] not in ("KILLED", "SURVIVED"):
            add(("mutant", r["verdict"], r["category"]), k, shards[k], name, f"{r['verdict']} {r['category']}"
                + (f" (the control: {' '.join(theirs)})" if theirs else ""))
        elif r["verdict"] == "KILLED" and theirs and theirs != (r["verdict"], r["category"]):
            add(("differs", r["category"], theirs), k, shards[k], name,
                f"KILLED {r['category']}, the control {' '.join(theirs)}")
    return samples


# --- report --------------------------------------------------------------------------------------
def load_summaries(directory):
    """{(part, version, run): summary}, the latest attempt of each; the timing summary is under
    ("timing", "", 0)."""
    found = {}
    for path in sorted(glob.glob(os.path.join(directory, "**", "*.json"), recursive=True)):
        try:
            s = json.loads(read_text(path, ""))
        except ValueError:
            continue
        if not isinstance(s, dict) or s.get("part") not in ("build", "test", "timing"):
            continue
        key = (s["part"], s.get("version", ""), s.get("run") or 0)
        if key not in found or s.get("attempt", 1) >= found[key].get("attempt", 1):
            found[key] = s
    return found


def missing(needs, job):
    """(class, detail) of a part whose job left no summary, from the job's result in NEEDS."""
    result = (needs.get(job) or {}).get("result", "")
    if result in ("skipped", "cancelled"):
        return result, f"the {job} job was {result}"
    return "other", f"no summary from the {job} job (its result: {result or 'unknown'}); see its log"


def failure_labels(suites, rows):
    """What failed, to compare with another version and with earlier runs: '<suite> / <test>' for each
    failing test of the JUnit ROWS ([suite, test, kind, ...]), and the suite alone for each of SUITES
    (those that sbt named) that no row names."""
    labels = unique(f"{r[0]} / {r[1]}" if r[1] else r[0] for r in rows)
    named = {r[0] for r in rows}
    return labels + [s for s in unique(suites) if s not in named and s not in labels]


def suite_of(label):
    return label.split(" / ", 1)[0]


def covered(label, others):
    """Whether LABEL failed in OTHERS too: the same test, or a suite named alone on either side."""
    suite = suite_of(label)
    return label in others or suite in others or (label == suite and any(suite_of(o) == suite for o in others))


def env_only(failure):
    """Whether every failing test of FAILURE (a test summary, or a version of the timing summary) is an
    environment error of scripts/mutation-classify.py (env:sim-build, env:sim-exit, ...) and every
    suite that sbt named has such a row: a failure of the runner, not of the tests."""
    rows = failure.get("failed_tests") or []
    return (bool(rows) and not failure.get("failed_tests_cut") and all(r[2].startswith("env:") for r in rows)
            and all(s in {r[0] for r in rows} for s in failure.get("failed_suites") or []))


def names_text(names, shown=10):
    names = list(names)
    return ", ".join(names[:shown]) + (f" and {len(names) - shown} more" if len(names) > shown else "")


def verdict_of(classes):
    """A part's verdict from the classes of its runs: test-failure if any run failed, success if
    every run passed, else inconclusive."""
    if "test-failure" in classes:
        return "test-failure"
    if classes and all(c == "success" for c in classes):
        return "success"
    return "inconclusive"


def earlier_records(records_dir, version):
    """The machine lines of docs/toolchain/<version>/report.md committed from earlier runs."""
    records = []
    for m in RECORD_LINE.finditer(read_text(os.path.join(records_dir, version, "report.md"), "") or ""):
        try:
            records.append(json.loads(m.group(1)))
        except ValueError:
            continue
    return records


class Report:
    """The parts of each version of this run, compared with the control's."""

    def __init__(self, plan, needs, summaries, mutation, notes, run, records_dir, mutants_file):
        self.plan, self.needs, self.summaries, self.run = plan, needs, summaries, run
        self.records_dir, self.notes, self.mutation = records_dir, notes, mutation
        self.pinned = plan["pinned"]["version"]
        self.timing = summaries.get(("timing", "", 0))
        self.parts = ["build"] + [p for p in PART_NAMES if plan["parts"][p]]
        self.cells = {e["version"]: self.version_cells(e["version"], mutation.get(e["version"]), mutants_file)
                      for e in plan["versions"]}

    def judged(self, v, failure):
        """FAILURE (a test run's summary, or a version of the timing summary) as the comparison sees
        it: a test-failure with the labels of what failed; other when it names nothing, or when it is
        the control's and only the environment failed (it says nothing about the candidates)."""
        failure = dict(failure)
        if failure["class"] != "test-failure":
            return failure
        failure["labels"] = failure_labels(failure.get("failed_suites") or [], failure.get("failed_tests") or [])
        if not failure["labels"]:
            failure.update({"class": "other", "detail": f"{failure['detail']}; it names no failing suite or test"})
        elif v == self.pinned and env_only(failure):
            kinds = ", ".join(unique(r[2] for r in failure["failed_tests"]))
            failure.update({"class": "other", "detail": f"only environment errors on the control ({kinds}):"
                            f" {failure['detail']}"})
        return failure

    def version_cells(self, v, shards, mutants_file):
        """{part: cell}; a cell has verdict, classes (of its runs), detail and failed (labels)."""
        cells = {}
        s = self.summaries.get(("build", v, 0))
        if s is None:
            cls, detail = missing(self.needs, "build")
            s = {"class": cls, "detail": detail}
        cells["build"] = {"classes": [s["class"]], "detail": s["detail"], "failed": set(), "summary": s}
        if self.plan["parts"]["test"]:
            runs = []
            for r in range(1, self.plan["seed_runs"] + 1):
                t = self.summaries.get(("test", v, r))
                if t is None:
                    cls, detail = missing(self.needs, "test")
                    t = {"class": cls, "detail": detail, "run": r}
                runs.append(self.judged(v, t))
            failed = {n for t in runs if t["class"] == "test-failure" for n in t["labels"]}
            detail = "; ".join(f"run {t['run']}: {t['class']} ({t['detail']})" for t in runs if t["class"] != "success")
            cells["test"] = {"classes": [t["class"] for t in runs], "runs": runs, "failed": failed,
                             "detail": detail or ("every run passed" if len(runs) > 1 else runs[0]["detail"])}
        if self.plan["parts"]["timing"] and v in self.plan["timing"]:
            if self.timing is None:
                cls, detail = missing(self.needs, "timing")
                info = {"class": cls, "detail": detail, "ratio": None, "seconds": []}
            else:
                info = (self.timing.get("per_version") or {}).get(v) or {
                    "class": "other", "detail": "not in the timing summary", "ratio": None, "seconds": []}
            info = self.judged(v, info)
            cells["timing"] = {"classes": [info["class"]], "detail": info["detail"],
                               "failed": set(info.get("labels") or []), "info": info}
        if self.plan["parts"]["mutation"] and v in self.plan["mutation"]:
            if shards:
                m = mutation_part(shards, mutants_file)
            else:
                cls, detail = missing(self.needs, "mutation")
                if cls == "other":
                    detail = ("no mutation logs: the shards ended before scripts/mutation-check.sh wrote any (setup?),"
                              " or the download failed; see the mutation jobs' logs")
                m = {"class": cls, "detail": detail, "verdicts": None, "notes": [], "failed": set(), "counts": "",
                     "rows": []}
            m["classes"] = [m.pop("class")]
            cells["mutation"] = m
        for cell in cells.values():
            cell["verdict"] = verdict_of(cell["classes"])
        return cells

    def kind(self, version):
        return next(e["kind"] for e in self.plan["versions"] if e["version"] == version)

    def overview(self, version, part):
        cell = self.cells[version].get(part)
        if cell is None:
            return "-"
        classes = cell["classes"]
        if cell["verdict"] == "success":
            text = "success" + (f" ({len(classes)}/{len(classes)})" if len(classes) > 1 else "")
        else:
            labels = [f"{c} run {i}" if len(classes) > 1 else c for i, c in enumerate(classes, 1) if c != "success"]
            text = ", ".join(labels) + (" (inconclusive, re-run)" if cell["verdict"] == "inconclusive" else "")
        info = cell.get("info") or {}
        if info.get("ratio") is not None and self.kind(version) != "pinned":
            text += f" r={info['ratio']:.3f}"
        if cell.get("counts"):
            text += f" [{cell['counts']}]"
        return text

    def compare(self, version, part):
        """(verdict line, the names that failed on the candidate alone) of a candidate's part."""
        cand, ctrl = self.cells[version][part], self.cells[self.pinned].get(part)
        rerun = f'PARTS="{part}"'
        if ctrl is None:
            if cand["verdict"] == "inconclusive":
                return f"inconclusive (re-run with {rerun}): {cand['detail']}", []
            return (f"{cand['verdict']}: {cand['detail']} (the control's {part} did not run in this run; compare with"
                    f" docs/toolchain/{self.pinned}/)"), []
        if "inconclusive" in (cand["verdict"], ctrl["verdict"]):
            which = [f"{version}: {cand['detail']}"] if cand["verdict"] == "inconclusive" else []
            which += [f"the control {self.pinned}: {ctrl['detail']}"] if ctrl["verdict"] == "inconclusive" else []
            if cand["verdict"] == "test-failure":
                which.insert(0, f"{version} failed ({names_text(sorted(cand['failed']))}), but there is nothing to"
                                " compare it with")
            return f"inconclusive (re-run with {rerun}): " + "; ".join(which), []
        # Test by test: a failure of the control in the same suite does not cover another test.
        only = sorted(n for n in cand["failed"] if not covered(n, ctrl["failed"]))
        if not only and cand.get("verdicts") and ctrl.get("verdicts"):
            diff = sorted(m for m in set(cand["verdicts"]) | set(ctrl["verdicts"])
                          if cand["verdicts"].get(m) != ctrl["verdicts"].get(m))
            if diff:
                shown = ", ".join(f"{m} {'/'.join(cand['verdicts'].get(m, ('-',)))} (control"
                                  f" {'/'.join(ctrl['verdicts'].get(m, ('-',)))})" for m in diff[:10])
                return f"verdicts differ from the control's: {shown}{' ...' if len(diff) > 10 else ''}", []
        if not only:
            if cand["verdict"] == "success" and ctrl["verdict"] == "success":
                return "same as the control: success", []
            if cand["verdict"] == "success":
                return f"the control failed ({ctrl['detail']}); {version} passed", []
            return (f"fails as the control does ({names_text(sorted(cand['failed']))}): not a regression of"
                    f" {version}"), []
        runs, names = self.reproduced(version, part, set(only))
        if runs:
            return (f"REGRESSION of {version}: {names_text(sorted(names))} failed on {version} and not on the control"
                    f" in run {', '.join(runs)} and again in this run"), only
        if part in self.plan["knobs"]["PARTS"].split():
            hint = "no record committed from an earlier run has the same failure"
        else:
            hint = f"commit these records, then re-run it with {rerun} and the same CANDIDATES"
        return (f"candidate-only failure: {names_text(only)} failed on {version} and not on the control; a"
                f" regression only if a re-run fails the same way ({hint})"), only

    def reproduced(self, version, part, names):
        """(earlier runs, names) where the same tests (labels) failed on this version alone."""
        runs, common = [], set()
        for record in earlier_records(self.records_dir, version):
            if str(record.get("run_id")) == str(self.run["id"]):
                continue
            earlier = set(((record.get("parts") or {}).get(part) or {}).get("candidate_only") or [])
            if earlier & names:
                runs.append(str(record.get("run_id")))
                common |= earlier & names
        return runs, common

    def verdict_line(self, version, part):
        cell = self.cells[version][part]
        if self.kind(version) != "pinned" and part != "build":
            return self.compare(version, part)[0]
        if cell["verdict"] == "success":
            return "success"
        line = f"{cell['verdict']}: {cell['detail']}"
        if part != "build" and cell["verdict"] == "inconclusive":
            line += " (the control: the candidates' results of this part cannot be judged)"
        elif part != "build":
            line += " (the control: the candidates are compared with these failures)"
        return line

    def timing_note(self, version):
        info = self.cells[version]["timing"]["info"]
        if info.get("ratio") is None:
            return f"no ratio ({info['detail']})"
        ratio, limit = info["ratio"], self.check_timeout()
        worst = RECENT_MAX_SECONDS * ratio * 1.1
        return (f"ratio {ratio:.3f}: the mean time of {version} over that of {self.pinned} in one job; #121 §5:"
                f" {RECENT_MAX_SECONDS} s x {ratio:.3f} x 1.1 = {worst:.0f} s,"
                f" {'within' if worst <= limit else 'OVER'} CHECK_TIMEOUT {limit} s")

    def check_timeout(self):
        for (part, _, _), s in self.summaries.items():
            if part == "test" and str(s.get("check_timeout", "")).isdigit():
                return int(s["check_timeout"])
        return 780

    # --- the job log ------------------------------------------------------------------------------
    def print_log(self, out=print):
        plan, run = self.plan, self.run
        trigger = f"{run['event']}{' #' + run['pr'] if run['pr'] else ''}, main {run['main'] or '?'}"
        trigger += f", head {run['head'] or '?'}" if run["event"] == "pull_request" else ""
        trigger += f", merge commit {run['merge']}" if run["merge"] else ""
        out(f"==> toolchain-candidates report: run {run['id']} attempt {run['attempt']} ({trigger})")
        out("knobs: " + " ".join(f'{k}="{plan["knobs"][k]}"' for k in KNOBS))
        for note in plan["notes"] + self.notes:
            out(f"note: {note}")
        for warning in plan["warnings"]:
            out(f"warning: {warning}")
        if plan["knobs"]["PARTS"]:
            out(f"PARTS: this run re-runs {plan['knobs']['PARTS']} only")
        out("")
        rows = [["version", "role"] + self.parts]
        for e in plan["versions"]:
            role = "control" if e["kind"] == "pinned" else "candidate"
            rows.append([e["version"], role] + [self.overview(e["version"], p) for p in self.parts])
        widths = [max(len(r[i]) for r in rows) for i in range(len(rows[0]))]
        for r in rows:
            out("  ".join(cell.ljust(w) for cell, w in zip(r, widths)).rstrip())
        out("")
        out("success and test-failure are results; any other class is inconclusive: re-run that part with PARTS.")
        out("verdicts:")
        for e in plan["versions"]:
            for p in self.parts:
                if p not in self.cells[e["version"]]:
                    continue
                out(f"- {e['version']} {p}: {self.verdict_line(e['version'], p)}")
                if p == "timing" and e["kind"] != "pinned":
                    out(f"  {self.timing_note(e['version'])}")
                for note in self.cells[e["version"]][p].get("notes") or []:
                    out(f"  {note}")
        self.print_details(out)

    def print_details(self, out):
        if self.plan["parts"]["test"]:
            out("")
            out("test runs:")
            for e in self.plan["versions"]:
                for t in self.cells[e["version"]]["test"]["runs"]:
                    out(f"  {e['version']} run {t.get('run')}: {t['class']} ({t['detail']}); exit {t.get('exit')},"
                        f" {t.get('seconds')} s, tests {tests_text(t.get('tests'))},"
                        f" suites {suites_text(t.get('suites'))}, seeds {' | '.join(t.get('seeds') or []) or '-'},"
                        f" %Warning {warnings_text(t.get('warnings'))},"
                        f" builds {t.get('builds', '-')}, cpu {t.get('cpu', '-')}, nproc {t.get('nproc', '-')},"
                        f" image {t.get('image', '-')}")
                    for f in t.get("failures") or []:
                        out(f"    {f[0]} / {f[1]}: {f[2]}: {f[3]}")
                    shown, rows = len(t.get("failures") or []), len(t.get("failed_tests") or [])
                    if rows > shown:
                        out(f"    ... {rows - shown} more failing tests" + (" (and more not kept)"
                                                                          if t.get("failed_tests_cut") else ""))
                    if t.get("failures"):
                        out(f"    failure texts: in the log of the job 'test {e['version']} run {t.get('run')}'")
        if self.timing:
            out("")
            out(f"timing (one job; cpu {self.timing.get('cpu')}, nproc {self.timing.get('nproc')},"
                f" image {self.timing.get('image')}):")
            print_timing(self.timing, lambda line: out("  " + line))
        if self.plan["parts"]["mutation"]:
            out("")
            out("mutation shards:")
            for v in self.plan["mutation"]:
                for k, s in self.cells[v]["mutation"]["rows"]:
                    out(f"  {v} shard {k}: {shard_text(s)}")

    def failure_samples(self):
        """[(label, header, directory)] of the mutation runs whose failure texts the report prints."""
        if not self.plan["parts"]["mutation"]:
            return []
        control = (self.cells[self.pinned].get("mutation") or {}).get("all_verdicts")
        samples = []
        for v in self.plan["mutation"]:
            if self.mutation.get(v):
                samples += mutation_samples(v, self.mutation[v], None if v == self.pinned else control)
        return samples

    def print_failure_texts(self, out=print):
        """The failure texts of the mutation shards (the agent cannot download their logs); those of
        the test and timing parts are in the logs of their jobs."""
        samples = self.failure_samples()
        if not samples:
            return
        out("")
        out("failure texts of the mutation shards, for the fixtures of scripts/testdata/mutation-classify (#121 M8):"
            " each file of a run between '===BEGIN failure-text <version>/mutation/<run>/<file>===' and '===END==='")
        lines = []
        for label, header, path in samples:
            lines.append(f"failure text: {header}")
            lines += failure_texts(label, path) if os.path.isdir(path) else []
        print_texts(lines, out)

    # --- the records ------------------------------------------------------------------------------
    def records(self):
        files = []
        for e in self.plan["versions"]:
            files.append((f"docs/toolchain/{e['version']}/report.md", self.report_md(e)))
            m = self.cells[e["version"]].get("mutation")
            if m and m["verdicts"] and m["verdict"] in RESULTS:
                rows = ["mutant\tverdict\tcategory"] + [f"{k}\t{v}\t{c}" for k, (v, c) in sorted(m["verdicts"].items())]
                files.append((f"docs/toolchain/{e['version']}/mutation.tsv", "\n".join(rows) + "\n"))
        return files

    def machine_record(self, e):
        parts = {}
        for p, cell in self.cells[e["version"]].items():
            record = {"class": cell["verdict"]}
            if e["kind"] != "pinned" and p != "build":
                record["candidate_only"] = self.compare(e["version"], p)[1]
            if (cell.get("info") or {}).get("ratio") is not None:
                record["ratio"] = cell["info"]["ratio"]
            parts[p] = record
        record = {"run_id": self.run["id"], "attempt": self.run["attempt"], "version": e["version"],
                  "kind": e["kind"], "pinned": self.pinned, "main": self.run["main"], "parts": parts}
        # The plan of a later run warns when the tag has moved.
        if e["method"] == "source" and e.get("source_commit"):
            record["commit"] = e["source_commit"]
        return record

    def report_md(self, e):
        v, run, plan, cells = e["version"], self.run, self.plan, self.cells[e["version"]]
        build = cells["build"]["summary"]
        role = "固定の版。この run の対照" if e["kind"] == "pinned" else f"候補。対照は {self.pinned}"
        lines = [
            f"# Verilator {v}（{'固定の版' if e['kind'] == 'pinned' else '候補'}）の記録",
            "",
            "[toolchain-candidates.yml](../../../.github/workflows/toolchain-candidates.yml) の run の記録である"
            "（[#121](https://github.com/fregata-ariel/deterministic-vliw-chisel/issues/121)）。report job が job の"
            "ログに出したものを、そのまま commit した。後の run の記録は、下に `## run` の節として足す。分類の意味は、"
            "[docs/toolchain/README.md](../README.md#候補を試す) にある。",
            "",
            f"## run {run['id']}（attempt {run['attempt']}、{run['date']}）",
            "",
            f"<!-- toolchain-candidates: {dumps(self.machine_record(e))} -->",
            "",
            "| 項目 | 値 |",
            "|---|---|",
            f"| 版 | {v}（{role}） |",
            f"| 入れ方 | {install_md(e)} |",
            f"| run | [{run['id']}]({run['url']})（attempt {run['attempt']}） |",
            f"| 起動 | {trigger_md(run)} |",
            "| knob | " + "、".join(f'`{k}="{plan["knobs"][k]}"`' for k in KNOBS) + " |",
            f"| runner（build job） | {build.get('cpu', '—')}、nproc {build.get('nproc', '—')}、"
            f"image {build.get('image', '—')} |",
            "",
            "### 判定",
            "",
            "success と test-failure だけが結果である。ほかの分類（setup、timeout、cancelled、skipped、compile、other）は"
            "判定不能で、その part を `PARTS` でやり直す。",
            "",
            "| part | 分類 | 詳細 |",
            "|---|---|---|",
        ]
        for p in self.parts:
            if p not in cells:
                continue
            detail = self.verdict_line(v, p) if e["kind"] != "pinned" and p != "build" else cells[p]["detail"]
            if p == "timing" and e["kind"] != "pinned":
                detail += "。" + self.timing_note(v)
            lines.append(f"| {p} | {md(self.overview(v, p))} | {md(detail)} |")
        if "test" in cells:
            lines += self.test_md(cells["test"])
        prints = build.get("fingerprint") or ["（なし: build job の要約がない）"]
        lines += ["", "### fingerprint", "", "build job で入れて確かめたもの:", "", "~~~"] + prints + ["~~~"]
        if "test" in cells:
            mine = {line for line in prints if line.startswith("verilator_bin sha256:")}
            sums = {line for t in cells["test"]["runs"] for line in t.get("fingerprint") or []
                    if line.startswith("verilator_bin sha256:")}
            if sums and mine:
                lines += ["", "test job の `verilator_bin` の sha256: " + (
                    "すべて build job と同じ。" if sums == mine else
                    "build job と違うものがある（" + "、".join(sorted(sums - mine)) + "）。")]
        if "timing" in cells:
            lines += self.timing_md(e)
        if "mutation" in cells:
            lines += self.mutation_md(cells["mutation"])
        return "\n".join(lines) + "\n"

    def test_md(self, cell):
        lines = ["", "### test", "",
                 "run 1 は既定の seed、2 以降は `VLIW_SIM_SEED=random`（JVM ごとに新しい seed）で、ci.yml の `scala`"
                 " ジョブと同じ `scripts/check.sh scala` を走らせた。秒は、その step の壁時計の時間である。",
                 "",
                 "| run | seed | 分類 | exit | 秒 | テスト（成功/失敗/取消/無視/保留） | suite（完了/中断） | 失敗した suite |"
                 " ビルド | %Warning | CPU | nproc | image |",
                 "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
        for t in cell["runs"]:
            seconds = "—" if t.get("seconds") is None else f"{t['seconds']:.0f}"
            row = (str(t.get("run", "")), "<br>".join(f"`{s}`" for s in t.get("seeds") or []) or "—",
                   t["class"] if t["class"] == "success" else f"{t['class']}（{t['detail']}）",
                   str(t.get("exit", "—")), seconds, tests_text(t.get("tests"), "—"),
                   suites_text(t.get("suites"), "—"), ", ".join(t.get("failed_suites") or []) or "—",
                   str(t.get("builds", "—")), warnings_text(t.get("warnings")), t.get("cpu", "—"),
                   str(t.get("nproc", "—")), t.get("image", "—"))
            lines.append("| " + " | ".join(md(x) for x in row) + " |")
        fails = [(t.get("run"), f) for t in cell["runs"] for f in t.get("failures") or []]
        if fails:
            lines += ["", "失敗したテスト（`scripts/mutation-classify.py` の分類）:", ""]
            lines += [f"- run {r}: `{f[0]}` / {md(f[1])}: {f[2]}: {md(f[3])}" for r, f in fails]
        return lines

    def timing_md(self, e):
        timing, info = self.timing or {}, self.cells[e["version"]]["timing"]["info"]
        order = " ".join(self.plan["timing"] + self.plan["timing"][::-1])
        lines = ["", "### timing", "",
                 f"1つの job（CPU {timing.get('cpu', '—')}、nproc {timing.get('nproc', '—')}、image"
                 f" {timing.get('image', '—')}）で、{order} の順に `scripts/check.sh sbt test` を走らせた（コンパイルは先に"
                 "1回だけ行い、時間に入れない）。",
                 "", "| 順 | 版 | 秒 | exit | 分類 |", "|---|---|---|---|---|"]
        for r in timing.get("runs") or []:
            seconds = "—" if r.get("seconds") is None else f"{r['seconds']:.0f}"
            cls = r["class"] if r["class"] == "success" else f"{r['class']}（{r['detail']}）"
            lines.append(f"| {r['position']} | {r['version']} | {seconds} | {r.get('exit')} | {md(cls)} |")
        if e["kind"] != "pinned":
            lines += ["", md(self.timing_note(e["version"]))]
        elif not timing.get("runs"):
            lines += ["", f"run がない（{md(info['detail'])}）。"]
        return lines

    def mutation_md(self, m):
        lines = ["", "### mutation", "",
                 "`mutation.yml` を `workflow_call` で呼んだ。shard ごとの判定は、その job のログの"
                 " `===BEGIN results.tsv ...===` と `===BEGIN baseline ...===` にもある。", "",
                 "| shard | ベースライン | KILLED | SURVIVED | その他 | やり直した mutant |", "|---|---|---|---|---|---|"]
        for k, s in m["rows"]:
            if s is None:
                lines.append(f"| {k} | ログなし | — | — | — | — |")
                continue
            res = s["results"] or []
            base = " → ".join(f"{b['verdict']} {b['category']}".strip() for b in s["baseline"]) or "なし"
            lines.append(f"| {k} | {md(base)} | {sum(r['verdict'] == 'KILLED' for r in res)}"
                         f" | {sum(r['verdict'] == 'SURVIVED' for r in res)}"
                         f" | {sum(r['verdict'] not in ('KILLED', 'SURVIVED') for r in res)}"
                         f" | {', '.join(sorted(x for x in s['retries'] if x != '_baseline')) or '—'} |")
        lines += [""] + [f"- {md(note)}" for note in m["notes"]] if m["notes"] else []
        if m["verdicts"] and m["verdict"] in RESULTS:
            lines += ["", "mutant ごとの判定は [mutation.tsv](mutation.tsv) にある（列は mutant、verdict、category）。"]
        else:
            lines += ["", f"mutation.tsv は出していない（{md(m['detail'])}）。判定がそろったら、mutation の job のログの"
                      " `===BEGIN results.tsv ...===` から作る。"]
        return lines


def tests_text(tests, none="-"):
    return "/".join(str(tests[k]) for k in COUNTS) if tests else none


def suites_text(suites, none="-"):
    return f"{suites['completed']}/{suites['aborted']}" if suites else none


def warnings_text(counts):
    return ", ".join(f"{k} {v}" for k, v in sorted(counts.items())) if counts else "—"


def shard_text(s):
    if s is None:
        return "no logs"
    res = s["results"] or []
    base = " -> ".join(f"{b['verdict']} {b['category']}".strip() for b in s["baseline"]) or "none"
    counts = [sum(r["verdict"] == "KILLED" for r in res), sum(r["verdict"] == "SURVIVED" for r in res),
              sum(r["verdict"] not in ("KILLED", "SURVIVED") for r in res)]
    text = f"attempt {s['attempt']}, baseline {base}, KILLED {counts[0]}, SURVIVED {counts[1]}, other {counts[2]}"
    retried = sorted(m for m in s["retries"] if m != "_baseline")
    text += ", no results.tsv" if s["results"] is None else ""
    return text + (f", retried {', '.join(retried)}" if retried else "")


def md(text):
    return str(text).replace("|", "\\|").replace("\n", " ")


def install_md(e):
    if e["method"] == "apt":
        return f"apt のパッケージ `{e['apt']}`（ubuntu-24.04）"
    date = f"、タグの日付 {e['tag_date']}" if e.get("tag_date") else ""
    return f"ソースからビルド（タグ `v{e['version']}`、commit `{e['source_commit']}`{date}）"


def trigger_md(run):
    if run["event"] == "pull_request":
        return (f"pull_request #{run['pr']}（main `{run['main'] or '?'}`、head `{run['head']}`、"
                f"merge commit `{run['merge']}`）")
    return f"{run['event']}（commit `{run['head']}`、ref `{run['ref']}`）"


def run_info(env):
    """The run, from the environment of the report step. main is the commit of main that the run tested:
    for pull_request the first parent of the merge commit (MAIN_SHA: the step reads it with git, since
    the merge commit is replaced on the next push), else the pull request's base (PR_BASE_SHA); for
    another event the commit of the run."""
    server, repo, run_id = env.get("GITHUB_SERVER_URL", "https://github.com"), env.get("GITHUB_REPOSITORY", ""), \
        env.get("GITHUB_RUN_ID", "")
    event = env.get("GITHUB_EVENT_NAME", "")
    pull = event == "pull_request"
    return {
        "id": run_id, "attempt": int(env.get("GITHUB_RUN_ATTEMPT") or 1),
        "url": f"{server}/{repo}/actions/runs/{run_id}", "event": event, "pr": env.get("PR_NUMBER", ""),
        "head": env.get("PR_HEAD_SHA") or env.get("GITHUB_SHA", ""),
        "merge": env.get("GITHUB_SHA", "") if pull else "", "ref": env.get("GITHUB_REF", ""),
        "main": (env.get("MAIN_SHA") or env.get("PR_BASE_SHA", "")) if pull else env.get("GITHUB_SHA", ""),
        "date": env.get("REPORT_DATE") or datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d"),
    }


def cmd_report(args):
    needs = json.loads(os.environ.get("NEEDS") or "{}")
    if not os.environ.get("PLAN"):
        result = (needs.get("plan") or {}).get("result", "unknown")
        annotate("error", f"the plan job ended with {result}: a knob is malformed or a candidate's tag could not be"
                 " resolved (see the plan job's log); nothing ran")
        return 1
    mutation, notes = load_mutation(args.mutation) if args.mutation else ({}, [])
    report = Report(json.loads(os.environ["PLAN"]), needs, load_summaries(args.summaries), mutation, notes,
                    run_info(os.environ), args.records or os.path.join(REPO_ROOT, "docs", "toolchain"),
                    args.mutants or os.path.join(REPO_ROOT, "scripts", "mutants.tsv"))
    report.print_log()
    report.print_failure_texts()
    print("")
    print("records to commit, each file between '===BEGIN docs/toolchain/<version>/<file>===' and '===END==='; to a"
          " report.md committed from an earlier run, append the lines from '## run' on:")
    for path, text in report.records():
        print(f"===BEGIN {path}===")
        sys.stdout.write(text)
        print("===END===")
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="cmd")
    p = sub.add_parser("plan")
    p.add_argument("--file", help="another file in the format of project/toolchain.properties")
    p.add_argument("--now", help=argparse.SUPPRESS)
    p.add_argument("--records", help=argparse.SUPPRESS)
    p = sub.add_parser("run")
    p.add_argument("dir")
    p.add_argument("command", nargs=argparse.REMAINDER)
    p = sub.add_parser("summary")
    p.add_argument("part", choices=("build", "test", "timing"))
    p.add_argument("--entry", help="build, test: the version's matrix entry (JSON)")
    p.add_argument("--versions", help="timing: the versions timed (JSON)")
    p.add_argument("--run", type=int, default=0, help="test: the run (1 has the default seed)")
    p.add_argument("--setup", default="", help="the outcome of each setup-toolchain step")
    p.add_argument("--step", default="", help="the outcome of the test or timing step")
    p.add_argument("--dir", help="test: the run wrapper's directory; timing: that of the timing runs")
    p.add_argument("--start", default=os.path.join(os.environ.get("RUNNER_TEMP") or "/nonexistent", "job-start"),
                   help="a file with the start of the job (seconds since the epoch)")
    p.add_argument("--out", help="where to write the summary (JSON)")
    p.add_argument("--toolchain-file", help=argparse.SUPPRESS)
    p = sub.add_parser("timing")
    p.add_argument("--versions", required=True)
    p.add_argument("--installed", required=True, help="lines of <version> TAB <path of its verilator>")
    p.add_argument("--dir", required=True)
    p.add_argument("--run-timeout", type=int, default=1500, help="CHECK_TIMEOUT of each run")
    p.add_argument("--check", help=argparse.SUPPRESS)
    p = sub.add_parser("report")
    p.add_argument("--summaries", required=True)
    p.add_argument("--mutation")
    p.add_argument("--records", help=argparse.SUPPRESS)
    p.add_argument("--mutants", help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    commands = {"plan": cmd_plan, "run": cmd_run, "summary": cmd_summary, "timing": cmd_timing, "report": cmd_report}
    if args.cmd not in commands:
        parser.print_usage(sys.stderr)
        return 2
    try:
        return commands[args.cmd](args)
    except Failure as e:
        annotate("error", f"toolchain-candidates {args.cmd}: {e}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
