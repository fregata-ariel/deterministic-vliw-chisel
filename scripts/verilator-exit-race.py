"""Measure how often Verilator aborts at exit with "attempted to destroy locked Thread Pool" (#74).

Usage: python3 -I scripts/verilator-exit-race.py TEST_DIR RUNS [JOBS...]
       python3 -I scripts/verilator-exit-race.py --merge OUT_TSV TSV...
  TEST_DIR  a ChiselSim directory, e.g. build/chiselsim/CaseSpec/<test> (run the test once first)
  RUNS      runs per configuration
  JOBS      Verilator job options, one configuration per argument (default: "-j 0" and
            "--build-jobs 0 --verilate-jobs 1", what svsim does by default and what VliwChiselSim uses)

Verilates with the arguments of TEST_DIR's workdir-verilator/Makefile (without --build, which does not
change the exit path) into a fresh directory per run, the configurations taking turns, one run at a
time. The Makefile names its sources by absolute paths, so a copy of TEST_DIR still verilates the
sources at the place where ChiselSim wrote them. Prints the runs and aborts per configuration. Verilator 5.020 with
more than one verilation thread aborts in about 0.1 % of the runs (Verilator issue 4931).

Runs that fail for another reason are counted too (a Verilator abort with another message apart
from other errors), with the first message of each kind; the script then exits 1 at the end. Also
prints the mean seconds per run and, for each configuration, the one-sided Fisher p-value that its
abort rate is lower than the reference ABORTS/RUNS of VERILATOR_EXIT_RACE_REFERENCE (default
8/9663, Verilator 5.020 with more than one thread in #74). VERILATOR_EXIT_RACE_TSV=FILE also
writes the counts as a table (#85). --merge adds up such tables (e.g. of runs split over several
machines) per jobs configuration into OUT_TSV, with the p-values of the totals.
"""
import os
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
from math import comb

POOL = "attempted to destroy locked Thread Pool"
DEFAULT_JOBS = ["-j 0", "--build-jobs 0 --verilate-jobs 1"]
# Messages of the Perl wrapper when verilator_bin dies on a signal or aborts.
ABORTED = ("Verilator aborted", "Verilator threw signal", "internal fault")


def verilator_args(test_dir):
    """The arguments of the `simulation` target, without the build and job options."""
    workdir = os.path.join(test_dir, "workdir-verilator")
    lines = open(os.path.join(workdir, "Makefile"), encoding="utf-8").read().splitlines()
    start = lines.index("simulation: clean") + 1
    recipe = []
    for line in lines[start:]:
        if not line.strip():
            break
        recipe.append(line)
    words = re.findall(r"'([^']*)'", "\n".join(recipe))
    words = [w.replace("$$", "$").replace("$(shell pwd)", workdir) for w in words]
    drop_with_value = {"-o", "--Mdir", "-j", "--build-jobs", "--verilate-jobs"}
    args, skip = [], False
    for w in words:
        if skip:
            skip = False
        elif w in drop_with_value:
            skip = True
        elif w != "--build":
            args.append(w)
    return args


def fisher_lower(k, n, ref_k, ref_n):
    """One-sided Fisher exact p-value that k/n is lower than ref_k/ref_n: P(X <= k), where X is
    the aborts of this configuration among all k + ref_k aborts, drawn without replacement."""
    total = k + ref_k
    denom = comb(n + ref_n, total)
    return sum(comb(n, x) * comb(ref_n, total - x) for x in range(k + 1)) / denom


def write_tsv(path, version, ref, rows):
    with open(path, "w", encoding="utf-8") as f:
        f.write(f"# {version}\n")
        f.write("jobs\truns\tpool aborts\tother aborts\tother errors\tseconds per run\t")
        f.write(f"p (lower than {ref})\n")
        for row in rows:
            f.write("\t".join(str(x) for x in row) + "\n")


def merge(out, paths):
    """Adds up the tables that VERILATOR_EXIT_RACE_TSV wrote, per jobs configuration."""
    versions, refs, totals = set(), set(), {}
    for path in paths:
        lines = open(path, encoding="utf-8").read().splitlines()
        versions.add(lines[0].removeprefix("# "))
        refs.add(re.search(r"p \(lower than (\d+/\d+)\)", lines[1]).group(1))
        for line in lines[2:]:
            jobs, n, pool, abort, error, secs, _ = line.split("\t")
            t = totals.setdefault(jobs, [0, 0, 0, 0, 0.0])
            for i, x in enumerate((int(n), int(pool), int(abort), int(error))):
                t[i] += x
            t[4] += float(secs) * int(n)
    if len(versions) != 1 or len(refs) != 1:
        sys.exit(f"--merge: the tables differ in version or reference: {versions} {refs}")
    ref = refs.pop()
    ref_k, ref_n = (int(x) for x in ref.split("/"))
    rows = []
    for jobs, (n, pool, abort, error, secs) in totals.items():
        p = fisher_lower(pool + abort, n, ref_k, ref_n)
        rows.append([jobs, n, pool, abort, error, f"{secs / n:.3f}", f"{p:.3g}"])
        print(f"{jobs:40} {pool} of {n} runs aborted ({abort} other aborts, {error} other errors),"
              f" {secs / n:.3f} s per run, p = {p:.3g} against {ref}")
    write_tsv(out, versions.pop(), ref, rows)


def main(argv):
    if len(argv) >= 3 and argv[0] == "--merge":
        merge(argv[1], argv[2:])
        return
    if len(argv) < 2 or not argv[1].isdigit():
        sys.exit(__doc__)
    test_dir, runs = argv[0], int(argv[1])
    configs = argv[2:] or DEFAULT_JOBS
    ref = os.environ.get("VERILATOR_EXIT_RACE_REFERENCE", "8/9663")
    ref_k, ref_n = (int(x) for x in ref.split("/"))
    args = verilator_args(test_dir)
    # runs, pool aborts, other aborts, other errors, seconds
    counts = {c: [0, 0, 0, 0, 0.0] for c in configs}
    first = {}  # (config, kind) -> first message
    scratch = tempfile.mkdtemp(prefix="verilator-exit-race-")
    try:
        for i in range(runs):
            for c in configs:
                # A fresh output directory each time: with an unchanged one, Verilator skips the
                # work (--skip-identical) before it starts its threads.
                mdir = os.path.join(scratch, "obj")
                shutil.rmtree(mdir, ignore_errors=True)
                cmd = ["verilator", "--Mdir", mdir] + shlex.split(c) + args
                start = time.monotonic()
                r = subprocess.run(cmd, cwd=scratch, capture_output=True, text=True)
                counts[c][4] += time.monotonic() - start
                counts[c][0] += 1
                if POOL in r.stderr:
                    kind = 1
                elif r.returncode != 0 and any(a in r.stderr for a in ABORTED):
                    kind = 2
                elif r.returncode != 0:
                    kind = 3
                else:
                    continue
                counts[c][kind] += 1
                name = {1: POOL, 2: "other abort", 3: "other error"}[kind]
                print(f"run {i + 1} [{c}]: exit {r.returncode}: {name}", flush=True)
                first.setdefault((c, name), r.stderr.strip())
    finally:
        shutil.rmtree(scratch, ignore_errors=True)
    version = subprocess.run(["verilator", "--version"], capture_output=True, text=True).stdout.strip()
    print(version)
    rows = []
    for c, (n, pool, abort, error, secs) in counts.items():
        p = fisher_lower(pool + abort, n, ref_k, ref_n)
        rows.append([c, n, pool, abort, error, f"{secs / n:.3f}", f"{p:.3g}"])
        print(
            f"{c:40} {pool} of {n} runs aborted ({abort} other aborts, {error} other errors),"
            f" {secs / n:.3f} s per run, p = {p:.3g} against {ref}"
        )
    for (c, name), msg in first.items():
        print(f"\nfirst {name} [{c}]:\n{msg}")
    tsv = os.environ.get("VERILATOR_EXIT_RACE_TSV")
    if tsv:
        write_tsv(tsv, version, ref, rows)
    if any(n[2] or n[3] for n in counts.values()):
        sys.exit(1)


if __name__ == "__main__":
    main(sys.argv[1:])
