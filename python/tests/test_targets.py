"""Completed-Q policy targets."""

import numpy as np

from magicdosa import targets


def test_visit_tie_with_value_difference_favours_higher_q():
    pi = targets.completed_q(n=[[6, 6]], q=[[0.4, 0.6]], root_value=[0.5], mask=[[True, True]])
    assert pi[0, 1] > pi[0, 0] and abs(pi.sum() - 1) < 1e-6


def test_uniform_prior_and_equal_q_gives_uniform():
    pi = targets.completed_q(n=[[3, 9, 1, 0]], q=[[0.5, 0.5, 0.5, np.nan]], root_value=[0.5],
                             mask=[[True, True, True, False]])
    assert np.allclose(pi[0, :3], 1 / 3) and pi[0, 3] == 0


def test_unvisited_candidate_takes_root_value():
    pi = targets.completed_q(n=[[10, 0]], q=[[0.7, np.nan]], root_value=[0.7], mask=[[True, True]])
    assert np.allclose(pi[0], 0.5)
    pi = targets.completed_q(n=[[10, 0]], q=[[0.7, np.nan]], root_value=[0.3], mask=[[True, True]])
    assert pi[0, 0] > pi[0, 1]


def test_scale_and_prior_logits():
    # sigma = (c_visit + max N) * c_scale * q: with c_visit 50, max N 0 -> 5 * q
    pi = targets.completed_q(n=[[0, 0]], q=[[np.nan, np.nan]], root_value=[0.5], mask=[[True, True]],
                             logits=[[np.log(3.0), 0.0]])
    assert np.allclose(pi[0], [0.75, 0.25])
    pi = targets.completed_q(n=[[0, 50]], q=[[0.0, 1.0]], root_value=[0.0], mask=[[True, True]])
    assert np.isclose(pi[0, 1] / pi[0, 0], np.exp(10.0), rtol=1e-4)     # (50 + 50) * 0.1 * (1 - 0)
