"""Same-state comparison of two SearchReplay debug outputs (same logs, followed): per searched decision, whether the
answers differ. Lines: 'search debug: decision <id> <TYPE> ... answer=[...]', grouped per log by the
'<log>: decisions' line that follows a log. Usage: same_state.py <a.txt> <b.txt>"""
import re
import sys

PAT = re.compile(r"^search debug: decision (\d+) (\S+) .* answer=(\[[^\]]*\])")
END = re.compile(r"^(\S+\.pb\.gz): decisions")


def read(f):
    out, cur = {}, []
    for line in open(f, encoding="utf-8", errors="replace"):
        m = PAT.match(line)
        if m:
            cur.append((m.group(1), m.group(2), m.group(3)))
            continue
        e = END.match(line)
        if e:
            for d, t, a in cur:
                out[(e.group(1), d)] = (t, a)
            cur = []
    return out


a, b = read(sys.argv[1]), read(sys.argv[2])
both = sorted(set(a) & set(b))
diff = [k for k in both if a[k][1] != b[k][1]]
by_type = {}
for k in both:
    t = a[k][0].replace("DECISION_TYPE_", "")
    n, d = by_type.get(t, (0, 0))
    by_type[t] = (n + 1, d + (a[k][1] != b[k][1]))
print(f"searched in both {len(both)}, answers differ {len(diff)} ({100.0 * len(diff) / max(1, len(both)):.1f}%)")
print("by type: " + ", ".join(f"{t} {d}/{n}" for t, (n, d) in sorted(by_type.items())))
