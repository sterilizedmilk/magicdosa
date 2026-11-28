"""Policy-head diagnosis helpers: shuffles keep the observation, overfit row picking."""

import numpy as np
import pytest

from magicdosa import diag, shards

torch = pytest.importorskip("torch")


def test_shuffles_leave_policy_and_value_unchanged(table, config):
    from magicdosa.model import PlayNet, to_tensors
    torch.manual_seed(0)
    net = PlayNet(config).eval()
    x = shards.training_batch(table)["inputs"]
    with torch.no_grad():
        lg, v, _ = net(to_tensors(x))
        rng = np.random.default_rng(3)
        y, perm = diag.permute_candidates(x, rng)
        lg2, v2, _ = net(to_tensors(y))
        back = np.zeros_like(lg2.numpy())
        np.put_along_axis(back, perm, lg2.numpy(), axis=1)
        m = x["cand_mask"]
        assert np.allclose(np.where(m, back, 0), np.where(m, lg.numpy(), 0), atol=1e-4)
        lg3, v3, _ = net(to_tensors(diag.permute_tokens(x, rng)))
        assert np.allclose(np.where(m, lg3.numpy(), 0), np.where(m, lg.numpy(), 0), atol=1e-4)
        assert np.allclose(v3.numpy(), v.numpy(), atol=1e-5) and np.allclose(v2.numpy(), v.numpy(), atol=1e-5)


def test_overfit_rows_are_non_pass_play_decisions(table):
    picks = diag.overfit_rows([table], 50, np.random.default_rng(0))
    dtype = table.column("decision_type").to_numpy()
    chosen = table.column("chosen").to_numpy()
    kinds = table.column("cand_kind").to_pylist()
    for _, r in picks:
        assert dtype[r] not in shards.NOT_PLAY_POLICY and len(kinds[r]) >= 2
        assert kinds[r][chosen[r]] != diag.CAND_PASS


def test_target_entropy(table):
    from test_finetune import _searched
    t, _, _ = _searched(table, np.full(table.num_rows, shards.STATUS_COMPLETED))
    r = diag.target_entropy([t])
    assert r
    for v in r.values():
        assert v["rows"] > 0 and 0 <= v["on_entropy"] <= v["uniform"] + 1e-6 and v["off_entropy"] <= v["uniform"] + 1e-6
