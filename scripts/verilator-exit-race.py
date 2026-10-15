"""Measure how often Verilator aborts at exit with "attempted to destroy locked Thread Pool" (#74).

Usage: python3 -I scripts/verilator-exit-race.py TEST_DIR RUNS [JOBS...]
  TEST_DIR  a ChiselSim directory, e.g. build/chiselsim/CaseSpec/<test> (run the test once first)
  RUNS      runs per configuration
  JOBS      Verilator job options, one configuration per argument (default: "-j 0" and
            "--build-jobs 0 --verilate-jobs 1", what svsim does by default and what VliwChiselSim uses)

Verilates with the arguments of TEST_DIR's workdir-verilator/Makefile (without --build, which does not
change the exit path) into a fresh directory per run, the configurations taking turns, one run at a
time. The Makefile names its sources by absolute paths, so a copy of TEST_DIR still verilates the
sources at the place where ChiselSim wrote them. Prints the runs and aborts per configuration. Verilator 5.020 with
more than one verilation thread aborts in about 0.1 % of the runs (Verilator issue 4931).
"""
import os
import re
import shlex
import shutil
import subprocess
import sys
import tempfile

POOL = "attempted to destroy locked Thread Pool"
DEFAULT_JOBS = ["-j 0", "--build-jobs 0 --verilate-jobs 1"]


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


def main(argv):
    if len(argv) < 2 or not argv[1].isdigit():
        sys.exit(__doc__)
    test_dir, runs = argv[0], int(argv[1])
    configs = argv[2:] or DEFAULT_JOBS
    args = verilator_args(test_dir)
    counts = {c: [0, 0] for c in configs}  # runs, aborts
    scratch = tempfile.mkdtemp(prefix="verilator-exit-race-")
    try:
        for i in range(runs):
            for c in configs:
                # A fresh output directory each time: with an unchanged one, Verilator skips the
                # work (--skip-identical) before it starts its threads.
                mdir = os.path.join(scratch, "obj")
                shutil.rmtree(mdir, ignore_errors=True)
                cmd = ["verilator", "--Mdir", mdir] + shlex.split(c) + args
                r = subprocess.run(cmd, cwd=scratch, capture_output=True, text=True)
                counts[c][0] += 1
                if POOL in r.stderr:
                    counts[c][1] += 1
                    print(f"run {i + 1} [{c}]: exit {r.returncode}: {POOL}", flush=True)
                elif r.returncode != 0:
                    sys.exit(f"[{c}] failed for another reason (exit {r.returncode}):\n{r.stderr}")
    finally:
        shutil.rmtree(scratch, ignore_errors=True)
    print(f"{subprocess.run(['verilator', '--version'], capture_output=True, text=True).stdout.strip()}")
    for c, (n, k) in counts.items():
        print(f"{c:40} {k} of {n} runs aborted")


if __name__ == "__main__":
    main(sys.argv[1:])
