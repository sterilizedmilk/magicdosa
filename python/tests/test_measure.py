"""Holdout measurement helpers."""

import numpy as np

from magicdosa import measure


def test_value_errors():
    p = np.array([0.05, 0.15, 0.95, 0.85])
    y = np.array([0.0, 0.0, 1.0, 1.0])
    e = measure.value_errors(p, y)
    assert e["rows"] == 4 and np.isclose(e["brier"], np.mean((p - y) ** 2))
    assert np.isclose(e["ece"], 0.25 * (0.05 + 0.15 + 0.05 + 0.15))     # each row in its own bin
    assert measure.value_errors(np.array([]), np.array([]))["rows"] == 0


def test_buckets():
    p, y = np.full(6, 0.5), np.array([0, 1, 0, 1, 0, 1.0])
    rem = np.array([0, 0, 1, 3, 4, 9])
    b = measure.by_bucket(p, y, rem, measure.REMAINING_BUCKETS)
    assert [b[k]["rows"] for k in ("0", "1", "2", "3-4", "5+")] == [2, 1, 0, 2, 1]
