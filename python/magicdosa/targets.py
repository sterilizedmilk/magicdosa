"""Policy targets computed in the loader from the per-candidate search statistics the shards store (N, Q, prior
logit, root value), so the constants can change without regenerating data (coordinator 2026-11-28, item 2).

Completed-Q target (Gumbel MuZero style):
    pi' = softmax(logits + sigma(q_hat)),   sigma(q) = (c_visit + max_b N(b)) * c_scale * q
    q_hat(a) = Q(a) for a visited candidate, the root value estimate for an unvisited one (N = 0 or Q missing)
    logits = the prior logits, all 0 without a prior (uniform prior)
Q and the root value are the root seat's win probability in [0, 1].
"""

from __future__ import annotations

from typing import Optional

import numpy as np

C_VISIT = 50.0
C_SCALE = 0.1


def completed_q(n: np.ndarray, q: np.ndarray, root_value: np.ndarray, mask: np.ndarray,
                logits: Optional[np.ndarray] = None, c_visit: float = C_VISIT,
                c_scale: float = C_SCALE) -> np.ndarray:
    """[B, C] target distribution over the candidates of each row (0 on masked candidates).
    n: visit counts [B, C]; q: mean value [B, C] (nan where missing); root_value [B]; mask [B, C] real candidates;
    logits: prior logits [B, C] (nan or None where there is no prior: treated as 0)."""
    n = np.asarray(n, np.float64)
    q = np.asarray(q, np.float64)
    mask = np.asarray(mask, bool)
    visited = (n > 0) & ~np.isnan(q)
    q_hat = np.where(visited, q, np.asarray(root_value, np.float64)[:, None])
    max_n = np.where(mask, n, 0).max(-1, keepdims=True)
    sigma = (c_visit + max_n) * c_scale * q_hat
    base = np.zeros_like(sigma) if logits is None else np.nan_to_num(np.asarray(logits, np.float64), nan=0.0)
    z = np.where(mask, base + sigma, -np.inf)
    z = z - z.max(-1, keepdims=True)
    e = np.where(mask, np.exp(z), 0.0)
    return (e / e.sum(-1, keepdims=True)).astype(np.float32)
