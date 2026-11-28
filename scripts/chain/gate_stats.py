"""Search statistics of one side of a gate run, from its shards (n*/shards-<label>/*.arrow): searched decisions, root
top-visit share (most visits / all root visits, mean over searched first steps), and ms per searched decision from the
unit logs' agent-core timing line (search.decision=<ms>ms/<n>) of that side. Usage: gate_stats.py <run dir> <label>"""
import glob
import os
import re
import sys

import pyarrow as pa
import pyarrow.ipc as ipc

root, label = sys.argv[1], sys.argv[2]
n = 0
share = 0.0
for f in glob.glob(os.path.join(root, "n*", "shards-" + label, "*.arrow")):
    with open(f, "rb") as fh:
        t = ipc.open_file(pa.BufferReader(fh.read())).read_all()
    for r in t.select(["step", "search_ran", "cand_visits"]).to_pylist():
        v = r["cand_visits"] or []
        if r["step"] != 0 or not r["search_ran"] or sum(v) <= 0:
            continue
        n += 1
        share += max(v) / sum(v)
ms = dec = 0
for lg in glob.glob(os.path.join(root, "n*.log")):
    for line in open(lg, encoding="utf-8", errors="replace"):
        if line.startswith("[agent-core " + label + "]") and "search.decision=" in line:
            m = re.search(r"search\.decision=(\d+)ms/(\d+)", line)
            if m:
                ms += int(m.group(1)); dec += int(m.group(2))
print(f"{label}: searched first steps {n}, root top-visit share {share / max(1, n):.3f}; "
      f"ms per searched decision {ms / max(1, dec):.0f} ({dec} decisions)")
