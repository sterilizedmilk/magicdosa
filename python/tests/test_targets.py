"""Completed-Q policy targets, and the fixture file shared with the Java search (Gumbel v2)."""

import json
from pathlib import Path

import numpy as np

from magicdosa import targets

FIXTURES = Path(__file__).parent / "data" / "completed_q_fixtures.json"


def test_visit_tie_with_value_difference_favours_higher_q():
    pi = targets.completed_q(n=[[6, 6]], q=[[0.4, 0.6]], root_value=[0.5], mask=[[True, True]])
    assert pi[0, 1] > pi[0, 0] and abs(pi.sum() - 1) < 1e-6


def test_uniform_prior_and_equal_q_gives_uniform():
    for norm in (True, False):
        pi = targets.completed_q(n=[[3, 9, 1, 0]], q=[[0.5, 0.5, 0.5, np.nan]], root_value=[0.5],
                                 mask=[[True, True, True, False]], q_normalize=norm)
        assert np.allclose(pi[0, :3], 1 / 3) and pi[0, 3] == 0


def test_normalization():
    m = [[True, True, True]]
    assert np.allclose(targets.normalized_q([[0.50, 0.51, 0.52]], m), [[0, 0.5, 1]], atol=1e-6)
    assert np.allclose(targets.normalized_q([[0.4, 0.4, 0.4]], m), 0)


def test_unvisited_candidate_takes_v_mix():
    # v = 0.3, visited Q = 0.7 with N = 10: v_mix = (0.3 + 10 * 0.7) / 11
    v_mix = (0.3 + 10 * 0.7) / 11
    pi = targets.completed_q(n=[[10, 0]], q=[[0.7, np.nan]], root_value=[0.3], mask=[[True, True]],
                             q_normalize=False)
    assert np.isclose(np.log(pi[0, 0] / pi[0, 1]), (50 + 10) * 0.1 * (0.7 - v_mix), rtol=1e-5)
    # missing root value: v = the visit-weighted mean Q, so v_mix equals it here
    pi = targets.completed_q(n=[[10, 0]], q=[[0.7, np.nan]], root_value=[np.nan], mask=[[True, True]])
    assert np.allclose(pi[0], 0.5)


def test_q_bar_is_prior_weighted():
    # visited 0.2 (prior 3/4) and 0.6 (prior 1/4): Q_bar = 0.3; N = 1 each; v = 0.5 -> v_mix = (0.5 + 2*0.3) / 3
    logits = [[np.log(3.0), 0.0, 0.0]]
    v_mix = (0.5 + 2 * 0.3) / 3
    pi = targets.completed_q(n=[[1, 1, 0]], q=[[0.2, 0.6, np.nan]], root_value=[0.5], mask=[[True] * 3],
                             logits=logits, q_normalize=False)
    # unvisited candidate (logit 0) vs visited 0.6 (logit 0): ratio = exp(51 * 0.1 * (v_mix - 0.6))
    assert np.isclose(np.log(pi[0, 2] / pi[0, 1]), 5.1 * (v_mix - 0.6), rtol=1e-5)


def test_scale_and_prior_logits():
    pi = targets.completed_q(n=[[0, 0]], q=[[np.nan, np.nan]], root_value=[0.5], mask=[[True, True]],
                             logits=[[np.log(3.0), 0.0]])
    assert np.allclose(pi[0], [0.75, 0.25])
    pi = targets.completed_q(n=[[0, 50]], q=[[0.0, 1.0]], root_value=[0.0], mask=[[True, True]])
    assert np.isclose(pi[0, 1] / pi[0, 0], np.exp(10.0), rtol=1e-4)     # (50 + 50) * 0.1 * (1 - 0)


def test_shared_fixtures():
    """The cases in completed_q_fixtures.json (also read by the Java search's tests) give the stored numbers."""
    cases = json.loads(FIXTURES.read_text())["cases"]
    for c in cases:
        nan = lambda v: [np.nan if x is None else x for x in v]
        pi = targets.completed_q([c["n"]], [nan(c["q"])], [np.nan if c["root_value"] is None else c["root_value"]],
                                 [[True] * len(c["n"])], logits=None if c["prior_logits"] is None
                                 else [nan(c["prior_logits"])], c_visit=c["c_visit"], c_scale=c["c_scale"],
                                 q_normalize=c["q_normalize"])
        assert np.allclose(pi[0], c["target"], atol=1e-6), c["name"]
