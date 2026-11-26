"""Totals of an evaluation run (HeadToHead units n0..n4 under one folder): game win rate of side B (the trained model)
with its 95% Wilson interval, wins by seat, and the A3 record counts. Usage: python eval_summary.py <eval folder>"""
import glob
import math
import os
import re
import sys


def wilson(k, n, z=1.96):
    if n == 0:
        return (0.0, 0.0)
    p = k / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return (c - h, c + h)


root = sys.argv[1]
a = b = matches_a = matches_b = 0
seat0_a = [0, 0]  # seat 0 wins / games with A in seat 0
seat0_b = [0, 0]
problems = {}
units = sorted(glob.glob(os.path.join(root, "n*", "summary.txt")))
for f in units:
    t = open(f, encoding="utf-8", errors="replace").read()
    m = re.search(r"games won (\d+) / (\d+), matches won (\d+) / (\d+)", t)
    if m:
        a += int(m.group(1)); b += int(m.group(2)); matches_a += int(m.group(3)); matches_b += int(m.group(4))
    m = re.search(r"seat 0 won (\d+) of (\d+) games with \S+ in it, (\d+) of (\d+) with", t)
    if m:
        seat0_a[0] += int(m.group(1)); seat0_a[1] += int(m.group(2))
        seat0_b[0] += int(m.group(3)); seat0_b[1] += int(m.group(4))
    for label, pattern in [("unintended fallbacks", r"unintended fallbacks: (\d+)"),
                           ("constraint refusals", r"refusals of answers that broke a stated constraint \(agent-core bug\): (\d+)"),
                           ("unmapped", r"^unmapped: (\d+)"), ("log problems", r"log problems: (\d+)"),
                           ("protocol errors", r"protocol errors seen by agent-core \S+: (\d+)"),
                           ("shard errors", r"training shards \S+: \d+ written, (\d+) errors"),
                           ("RESULT FAIL", r"RESULT (FAIL)")]:
        for v in re.findall(pattern, t, re.M):
            problems[label] = problems.get(label, 0) + (1 if v == "FAIL" else int(v))
n = a + b
lo, hi = wilson(b, n)
print(f"trained (B) won {b} of {n} games = {100.0 * b / max(1, n):.1f}% (95% Wilson {100 * lo:.1f}-{100 * hi:.1f}%); "
      f"matches {matches_b} / {matches_a + matches_b}; units with a summary {len(units)} of 5")
b_seat0 = seat0_b[0]
b_seat1 = (seat0_a[1] - seat0_a[0])
print(f"by seat: trained won {b_seat0} of {seat0_b[1]} as seat 0, {b_seat1} of {seat0_a[1]} as seat 1; "
      f"seat 0 won {seat0_a[0] + seat0_b[0]} of {seat0_a[1] + seat0_b[1]}")
print("A3: " + ", ".join(f"{k} {v}" for k, v in sorted(problems.items())))
