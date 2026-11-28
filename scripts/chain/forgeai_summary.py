"""Totals of a RandomGames diagnosis run (units n*/summary.txt): Forge AI's game win rate against the agent with its
95% Wilson interval. The agent is always in seat 0. Usage: forgeai_summary.py <run dir>"""
import glob
import math
import os
import re
import sys

agent = forge = magent = mforge = 0
fails = []
for f in sorted(glob.glob(os.path.join(sys.argv[1], "n*", "summary.txt"))):
    t = open(f, encoding="utf-8", errors="replace").read()
    m = re.search(r"match wins agent/forge-ai: (\d+)/(\d+); game wins: (\d+)/(\d+)", t)
    if m:
        magent += int(m.group(1)); mforge += int(m.group(2)); agent += int(m.group(3)); forge += int(m.group(4))
    for k in ("unintended fallbacks", "RESULT"):
        fails += [os.path.basename(os.path.dirname(f)) + " " + l for l in t.splitlines() if l.startswith(k)]
n = agent + forge
z = 1.96
p = forge / max(1, n)
d = 1 + z * z / max(1, n)
c = (p + z * z / (2 * max(1, n))) / d
h = z * math.sqrt(p * (1 - p) / max(1, n) + z * z / (4 * max(1, n) ** 2)) / d
print(f"Forge AI won {forge} of {n} games = {100 * p:.1f}% (95% Wilson {100 * (c - h):.1f}-{100 * (c + h):.1f}%); "
      f"matches {mforge} / {magent + mforge}")
print("by seat: the random agent is always seat 0 (RandomGames), Forge AI seat 1; no seat split")
print("; ".join(fails))
