"""One loss computation and optimizer step on the sample shard (no real training)."""

import pytest

torch = pytest.importorskip("torch")

from magicdosa import shards  # noqa: E402
from magicdosa.model import Critic, PlayNet  # noqa: E402
from magicdosa.train import losses, train_step  # noqa: E402

from test_training_batch import with_search  # noqa: E402


def test_one_step_updates_and_losses_are_finite(table, config):
    batch = shards.training_batch(with_search(table))   # SYNTHETIC visit ratios, see with_search
    assert batch["targets"]["policy_mask"].any()
    net, critic = PlayNet(config), Critic(config)
    opt = torch.optim.Adam(list(net.parameters()) + list(critic.parameters()), lr=1e-3)
    before = [p.detach().clone() for p in net.parameters()]
    out = train_step(net, critic, opt, batch)
    assert all(torch.isfinite(torch.tensor(v)) for v in out.values())
    assert out["policy"] > 0 and out["value"] > 0 and out["critic"] > 0
    assert any(not torch.equal(a, p) for a, p in zip(before, net.parameters()))


def test_policy_loss_is_zero_without_search_targets(table, config):
    batch = shards.training_batch(table)                 # phase-1 random rows: no visit ratios
    terms = losses(PlayNet(config), Critic(config), batch)
    assert float(terms["policy"].detach()) == 0.0
    assert float(terms["value"].detach()) > 0
