"""Placeholder play network on the sample shard: shapes, masking, critic isolation."""

import pytest

torch = pytest.importorskip("torch")

from magicdosa import shards  # noqa: E402
from magicdosa.model import NEG, Critic, PlayNet, to_tensors  # noqa: E402


def test_forward_shapes_and_mask(table, config):
    b = shards.training_batch(table)
    net = PlayNet(config).eval()
    with torch.no_grad():
        logits, value, pooled = net(to_tensors(b["inputs"]))
    n, c = b["inputs"]["cand_mask"].shape
    assert logits.shape == (n, c) and value.shape == (n,) and pooled.shape == (n, config.d_model)
    assert (logits[~torch.from_numpy(b["inputs"]["cand_mask"])] == NEG).all()
    assert torch.isfinite(logits).all() and ((value > 0) & (value < 1)).all()
    assert torch.allclose(logits.softmax(-1).sum(-1), torch.ones(n))


def test_padding_does_not_change_outputs(table, config):
    """A row's outputs do not depend on the rest of the batch (padding is masked)."""
    net = PlayNet(config).eval()
    full = shards.training_batch(table)
    one = shards.training_batch(table, [0])
    with torch.no_grad():
        lf, vf, _ = net(to_tensors(full["inputs"]))
        lo, vo, _ = net(to_tensors(one["inputs"]))
    c = lo.shape[1]
    assert torch.allclose(lf[0, :c], lo[0], atol=1e-4)
    assert torch.allclose(vf[0], vo[0], atol=1e-5)


def test_critic_gradient_stays_off_the_shared_encoder(table, config):
    b = shards.training_batch(table)
    net, critic = PlayNet(config), Critic(config)
    _, _, pooled = net(to_tensors(b["inputs"]))
    critic(pooled, to_tensors(b["privileged"])).sum().backward()
    assert all(p.grad is None for p in net.parameters())
    assert any(p.grad is not None for p in critic.parameters())
