"""completed_q gives the numbers of agent-core's GumbelMath on the shared fixture (agent-core test resource
completed_q_fixture.txt, also read by GumbelMathTest)."""

from pathlib import Path

import numpy as np

from magicdosa.targets import C_SCALE, C_VISIT, completed_q

FIXTURE = Path(__file__).resolve().parents[2] / "agent-core" / "src" / "test" / "resources" / "completed_q_fixture.txt"


def floats(s):
    return np.array([float("nan") if x.strip() == "nan" else float(x) for x in s.split(",")])


def test_completed_q_matches_the_shared_fixture():
    assert (C_VISIT, C_SCALE) == (50.0, 0.1)
    rows = [l for l in FIXTURE.read_text().splitlines() if l.strip() and not l.startswith("#")]
    assert len(rows) >= 5
    for row in rows:
        n, q, v, mask, logits, expected = [f.strip() for f in row.split("|")]
        got = completed_q(floats(n)[None], floats(q)[None], np.array([float(v)]), floats(mask)[None].astype(bool),
                          None if logits == "none" else floats(logits)[None])[0]
        np.testing.assert_allclose(got, floats(expected), atol=2e-6, err_msg=row)
