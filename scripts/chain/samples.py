"""Picks LogText sample games from a run folder (units n*/): wins and losses of side B mixed, deterministic (sorted by
game name). Reads side B's seat and result from its shards (n*/shards-<label>/<game>.arrow). Prints one line per pick:
<log path>\t<output file name>. Usage: samples.py <run dir> <side B label> [count=3]"""
import glob
import os
import sys

import pyarrow as pa
import pyarrow.ipc as ipc

root, label = sys.argv[1], sys.argv[2]
count = int(sys.argv[3]) if len(sys.argv) > 3 else 3
wins, losses = [], []
files = sorted(glob.glob(os.path.join(root, "n*", "shards-" + label, "*.arrow")))
if not files:  # a one-agent run (RandomGames) writes n*/shards
    files = sorted(glob.glob(os.path.join(root, "n*", "shards", "*.arrow")))
for f in files:
    stem = os.path.basename(f)[:-len(".arrow")]
    log = os.path.join(os.path.dirname(os.path.dirname(f)), stem + ".pb.gz")
    if not os.path.exists(log):
        continue
    with open(f, "rb") as fh:
        t = ipc.open_file(pa.BufferReader(fh.read())).read_all()
    if t.num_rows == 0:
        continue
    seat = {k.decode(): v.decode() for k, v in t.schema.metadata.items()}["agent_seat"]
    won = t.column("result")[0].as_py() > 0.5
    (wins if won else losses).append((log, f"{stem}_{label}-P{seat}-{'wins' if won else 'loses'}.txt"))
picks = []
while len(picks) < count and (wins or losses):
    for pool in (wins, losses):  # alternate, from the middle of each pool for variety
        if pool and len(picks) < count:
            picks.append(pool.pop(len(pool) // 2))
for log, name in picks:
    print(f"{log}\t{name}")
