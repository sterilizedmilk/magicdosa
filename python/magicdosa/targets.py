"""Policy targets computed in the loader from the per-candidate search statistics the shards store (N, Q, prior
logit, root value), so the constants can change without regenerating data (coordinator 2026-11-28, item 2).

Completed-Q target (Gumbel MuZero style; the Java search, Gumbel v2, implements the same formula and is checked
against tests/data/completed_q_fixtures.json):
    q_hat(a) = Q(a) for a visited candidate (N > 0 and Q recorded), else v_mix
    v_mix    = (v + sum_b N(b) * Q_bar) / (1 + sum_b N(b))
               v = the network's root value (search_root_value), or the visit-weighted mean Q when it is missing;
               Q_bar = the prior-weighted mean Q over the visited candidates (uniform prior: the plain mean)
    q_tilde  = (q_hat - min) / (max - min + 1e-8) over the row's candidates   (q_normalize, default on)
    pi'      = softmax(logits + sigma(q_tilde)),  sigma(q) = (c_visit + max_b N(b)) * c_scale * q
    logits   = the prior logits, all 0 without a prior (uniform prior)
Q and the root value are the root seat's win probability in [0, 1].
"""

from __future__ import annotations

from typing import Optional

import numpy as np

C_VISIT = 50.0
C_SCALE = 0.1
EPSILON = 1e-8


def completed_q(n: np.ndarray, q: np.ndarray, root_value: np.ndarray, mask: np.ndarray,
                logits: Optional[np.ndarray] = None, c_visit: float = C_VISIT, c_scale: float = C_SCALE,
                q_normalize: bool = True) -> np.ndarray:
    """[B, C] target distribution over the candidates of each row (0 on masked candidates).
    n: visit counts [B, C]; q: mean value [B, C] (nan where missing); root_value [B] (nan where missing);
    mask [B, C] real candidates; logits: prior logits [B, C] (nan or None where there is no prior: treated as 0)."""
    n = np.asarray(n, np.float64)
    q = np.asarray(q, np.float64)
    mask = np.asarray(mask, bool)
    root = np.asarray(root_value, np.float64)
    base = np.zeros(n.shape) if logits is None else np.nan_to_num(np.asarray(logits, np.float64), nan=0.0)
    visited = (n > 0) & ~np.isnan(q) & mask
    qv = np.where(visited, q, 0.0)
    nv = np.where(visited, n, 0.0)
    total_n = nv.sum(-1)
    # prior over the row's candidates (softmax of the logits; uniform when there is none)
    z = np.where(mask, base, -np.inf)
    prior = np.exp(z - z.max(-1, keepdims=True))
    prior = np.where(mask, prior, 0.0)
    pw = np.where(visited, prior, 0.0)
    q_bar = np.where(pw.sum(-1) > 0, (pw * qv).sum(-1) / np.maximum(pw.sum(-1), 1e-300), np.nan)
    mean_q = np.where(total_n > 0, (nv * qv).sum(-1) / np.maximum(total_n, 1), np.nan)
    v = np.where(np.isnan(root), mean_q, root)
    v_mix = np.where(total_n > 0, (v + total_n * np.nan_to_num(q_bar)) / (1 + total_n), v)
    v_mix = np.nan_to_num(v_mix, nan=0.5)                                   # no value at all: neutral
    q_hat = np.where(visited, q, v_mix[:, None])
    if q_normalize:
        lo = np.where(mask, q_hat, np.inf).min(-1, keepdims=True)
        hi = np.where(mask, q_hat, -np.inf).max(-1, keepdims=True)
        q_hat = (q_hat - lo) / (hi - lo + EPSILON)
    max_n = np.where(mask, n, 0).max(-1, keepdims=True)
    sigma = (c_visit + max_n) * c_scale * q_hat
    s = np.where(mask, base + sigma, -np.inf)
    s = s - s.max(-1, keepdims=True)
    e = np.where(mask, np.exp(s), 0.0)
    return (e / e.sum(-1, keepdims=True)).astype(np.float32)


def normalized_q(q_hat: np.ndarray, mask: np.ndarray) -> np.ndarray:
    """The min-max stretch alone, for tests and fixtures."""
    q_hat = np.asarray(q_hat, np.float64)
    lo = np.where(mask, q_hat, np.inf).min(-1, keepdims=True)
    hi = np.where(mask, q_hat, -np.inf).max(-1, keepdims=True)
    return (q_hat - lo) / (hi - lo + EPSILON)
