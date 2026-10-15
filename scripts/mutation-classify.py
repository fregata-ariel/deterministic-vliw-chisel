"""Classify one mutant run of `sbt test` as KILLED, ERROR or SURVIVED.

Usage: python3 -I scripts/mutation-classify.py RUN_DIR
RUN_DIR holds: sbt.log (stdout+stderr of sbt), exit (sbt's exit code), reports/**.xml (the JUnit XML
files that sbt wrote under */target/test-reports during this run, and only those).
Prints one line: VERDICT<TAB>category<TAB>detail, and writes RUN_DIR/classification.tsv with one row per
failing test or aborted suite.
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

# --- sbt console (sbt.log) ---------------------------------------------------------------------------
# scalac error (also -Werror warnings): sbt prints this once per failed compile task.
SCALAC = re.compile(r"^\[error\] \((\S+) / (Compile|Test) / compileIncremental\) Compilation failed", re.M)
SCALAC_FIRST = re.compile(r"^\[error\] (\S+\.scala):(\d+):(\d+): (.*)$", re.M)
# The test task of a project failed (test failures, aborted suites, or a forked JVM that died).
TESTS_FAILED = re.compile(r"^\[error\] \((\S+) / Test / (?:test|testOnly)\) (\S+)", re.M)
# The forked test JVM died (OOM killer, crash, System.exit): sbt prints this and then hangs until killed.
FORK_DIED = re.compile(r"^Warning: Unable to read from client", re.M)
# The sbt launcher JVM itself ran out of memory or the build could not load.
SBT_ENV = re.compile(
    r"^\[error\] java\.lang\.OutOfMemoryError|^java\.lang\.OutOfMemoryError|"
    r"^\[error\] Project loading failed|^\[error\] .*Server is already booting", re.M)

# --- JUnit XML bodies ----------------------------------------------------------------------------------
# Tests are forked (Test / fork := true), so every throwable is wrapped in sbt.ForkMain$ForkError and the
# report's type attribute is always that wrapper. The body is printStackTrace output; the original class
# follows the wrapper's name on the first line and on each "Caused by:" line:
#   sbt.ForkMain$ForkError: <class>: <message>            (the thrown exception)
#   Caused by: sbt.ForkMain$ForkError: <class>: <message> (its causes)
# An exception that escapes ScalaTest (e.g. OutOfMemoryError) is wrapped twice, so the wrapper's name
# can repeat; CHAIN skips every repetition.
CHAIN = re.compile(r"^(?:Caused by: )?(?:sbt\.ForkMain\$ForkError: )*([A-Za-z_][\w.$]*(?:Exception|Error|Failed|Timeout|Throwable)\b[\w$]*): ?(.*)$", re.M)
# svsim Workspace.compile: `make -C <workdir> simulation` exited non-zero -> java.lang.Exception whose
# message is the whole make output (make -C always prints "Entering directory").
SIM_BUILD = re.compile(r"^(?:Caused by: )?(?:sbt\.ForkMain\$ForkError: )*java\.lang\.Exception: make: Entering directory '[^']*/workdir-\w+'", re.M)
MAKE_ERR = re.compile(r"^make: \*\*\* \[Makefile:(\d+): (\w+)\] Error (\d+)", re.M)
# Why the build failed, first match wins (Verilator wrapper messages, C++ compiler, resources).
BUILD_WHY = re.compile(
    r"^(%Error: Verilator (?:threw signal \d+|internal fault|aborted)[^\n]*|"
    r"%Error: [^\n]*|"
    r"[^\n]*(?:Killed signal terminated program|virtual memory exhausted|No space left on device|"
    r"Cannot allocate memory|fatal error:)[^\n]*)$", re.M)
ENV_CLASSES = {
    "java.lang.OutOfMemoryError": "oom",
    "java.lang.StackOverflowError": "jvm",
    "java.lang.InternalError": "jvm",
    "java.io.IOException": "io",
    "java.nio.file.FileSystemException": "io",
    "svsim.Backend$Exceptions$FailedInitialization": "sim-start",
    "circt.stage.phases.Exceptions$FirtoolNonZeroExitCode": "firtool",
    "circt.stage.phases.Exceptions$FirtoolNotFound": "firtool",
    "chisel3.package$ChiselException": "elaboration",
}
SIM_EXIT = re.compile(r"java\.lang\.Exception: Nonzero exit status: (-?\d+)")
# A test that checks a value and finds it wrong: ScalaTest assertions (intercept included), ChiselSim
# `expect`, RTL assertions (`%Error: ... Assertion failed` in the simulation), Scala `assert`, and the
# cycle limit of ChiselSim. Any other exception thrown from the test body also fails the test; it is
# killed too but counted as "exception", so that an unexpected kind of failure is visible.
ASSERTION_CLASSES = {
    "org.scalatest.exceptions.TestFailedException",
    "org.scalatest.exceptions.TestFailedDueToTimeoutException",
    "chisel3.simulator.FailedExpectationException",
    "chisel3.simulator.Exceptions$AssertionFailed",
    "chisel3.simulator.Exceptions$Timeout",
    "java.lang.AssertionError",
}
SUITE_SELECTOR = "(It is not a test it is a sbt.testing.SuiteSelector)"


def first_line(s, n=160):
    s = (s or "").strip().splitlines()
    return (s[0] if s else "")[:n]


def classify_record(body):
    """Return (kind, detail) for one <failure>/<error> body.

    kind is 'env:<category>' for a failure of the environment, else 'assertion' or 'exception'.
    """
    if SIM_BUILD.search(body):
        m = MAKE_ERR.search(body)
        why = BUILD_WHY.search(body)
        code = f"make Error {m.group(3)} (Makefile:{m.group(1)} {m.group(2)})" if m else "make failed"
        reason = re.sub(r"(/[^\s:/']+)+/", "", why.group(1).strip())[:160] if why else ""
        return "env:sim-build", code + (f": {reason}" if reason else "")
    chain = CHAIN.findall(body)
    for cls, msg in chain:
        if cls in ENV_CLASSES:
            return "env:" + ENV_CLASSES[cls], f"{cls}: {msg[:160]}"
    m = SIM_EXIT.search(body)
    if m:
        return "env:sim-exit", f"simulation exited with {m.group(1)}"
    cls, msg = chain[0] if chain else ("?", first_line(body))
    return ("assertion" if cls in ASSERTION_CLASSES else "exception"), f"{cls}: {msg[:160]}"


def main(run_dir):
    log = open(os.path.join(run_dir, "sbt.log"), encoding="utf-8", errors="replace").read()
    code = int(open(os.path.join(run_dir, "exit")).read().strip() or "1")
    rows = []  # (suite, test, kind, detail)
    suites = 0
    for path in sorted(glob.glob(os.path.join(run_dir, "reports", "**", "*.xml"), recursive=True)):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as e:  # a report cut short (e.g. disk full) counts as an environment error
            rows.append((os.path.basename(path), "", "env:report", f"unreadable report: {e}"))
            continue
        suites += 1
        for tc in root.iter("testcase"):
            for el in list(tc.findall("failure")) + list(tc.findall("error")):
                body = el.text or el.get("message") or ""
                kind, detail = classify_record(body)
                name = tc.get("name") or ""
                if name == SUITE_SELECTOR and not kind.startswith("env:"):
                    kind = "aborted"  # the suite failed outside any test (constructor, beforeAll)
                rows.append((tc.get("classname") or root.get("name"), name, kind, detail))

    with open(os.path.join(run_dir, "classification.tsv"), "w", encoding="utf-8") as f:
        for r in rows:
            f.write("\t".join(x.replace("\t", " ").replace("\n", " ") for x in r) + "\n")

    scalac = SCALAC.findall(log)
    verdicts = [r for r in rows if r[2] in ("assertion", "exception")]
    envs = [r for r in rows if r[2].startswith("env:")]
    aborted = [r for r in rows if r[2] == "aborted"]
    if scalac:
        first = SCALAC_FIRST.search(log)
        where = ", ".join(f"{p}/{c}" for p, c in scalac)
        why = f"{os.path.basename(first.group(1))}:{first.group(2)}: {first.group(4)[:120]}" if first else ""
        return "ERROR", "scalac", f"{where}: {why}"
    if FORK_DIED.search(log) and not verdicts:
        return "ERROR", "fork-died", f"forked test JVM died (sbt exit {code}); {suites} reports"
    if code in (124, 137) and not verdicts:
        return "ERROR", "timeout", f"sbt exit {code}"
    if verdicts:
        # Prefer an assertion as the reason; "exception" only when no test failed by an assertion.
        asserts = [r for r in verdicts if r[2] == "assertion"]
        r = (asserts or verdicts)[0]
        extra = f" (+{len(envs) + len(aborted)} errors)" if envs or aborted else ""
        return "KILLED", r[2], f"{len(verdicts)} failed; first {r[0]}: {r[1][:60]}: {r[3]}{extra}"
    if envs:
        r = envs[0]
        return "ERROR", r[2][4:], f"{len(envs)} errors; first {r[0]}: {r[1][:60]}: {r[3]}"
    if aborted:
        r = aborted[0]
        return "ERROR", "aborted", f"{len(aborted)} suites aborted; first {r[0]}: {r[3]}"
    if code != 0:
        m = SBT_ENV.search(log) or TESTS_FAILED.search(log)
        return "ERROR", "sbt", f"sbt exit {code}, no failing test in {suites} reports" + (
            f": {first_line(m.group(0))}" if m else "")
    if suites == 0:
        return "ERROR", "no-reports", "sbt exit 0 but no test reports"
    return "SURVIVED", "passed", f"{suites} suites passed"


if __name__ == "__main__":
    print("\t".join(main(sys.argv[1])))
