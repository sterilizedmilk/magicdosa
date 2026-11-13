"""One training step of the play module (design 학습·평가 "학습 전략과 보상").

Losses, as far as the design fixes them:
- policy: ExIt target = the search's visit ratios over the step's candidates ("탐색 결과(후보별 방문 비율)는 정책의
  정답"), cross-entropy against the policy softmax, only on shards.policy_target_rows;
- value: game result of the deciding seat ("게임 결과는 가치의 정답", 보상 분리: game result for in-game decisions),
  observation-only head;
- critic: same game-result target, training-only head that sees the hidden cards.
PLACEHOLDERS: binary cross-entropy for the value losses, loss weights 1/1/1, Adam with a fixed learning rate, draws
left out of the value targets. No replay buffer, schedule or data mixing here; nothing in this module runs a real
training job.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, Mapping

import torch
import torch.nn.functional as F
from torch import Tensor

from .model import Critic, PlayNet, to_tensors


@dataclass
class LossWeights:
    policy: float = 1.0   # PLACEHOLDER
    value: float = 1.0    # PLACEHOLDER
    critic: float = 1.0   # PLACEHOLDER


def _masked_mean(x: Tensor, mask: Tensor) -> Tensor:
    m = mask.float()
    return (x * m).sum() / m.sum().clamp(min=1.0)


def losses(net: PlayNet, critic: Critic, batch: Mapping[str, Mapping[str, object]]) -> Dict[str, Tensor]:
    """batch = shards.training_batch(...). Returns the loss terms (scalars)."""
    x = to_tensors(batch["inputs"])
    y = to_tensors(batch["targets"])
    priv = to_tensors(batch["privileged"])
    logits, value, pooled = net(x)
    # visit_ratio is padded to the batch's widest visit list; align it with the candidate axis
    c = logits.shape[1]
    visits = y["visit_ratio"]
    if visits.shape[1] < c:
        visits = F.pad(visits, (0, c - visits.shape[1]))
    visits = visits[:, :c]
    logp = torch.log_softmax(logits, dim=-1)
    policy = _masked_mean(-(visits * logp).sum(-1), y["policy_mask"])
    value_loss = _masked_mean(F.binary_cross_entropy(value, y["result"], reduction="none"), y["value_mask"])
    crit = critic(pooled, priv)
    critic_loss = _masked_mean(F.binary_cross_entropy(crit, y["result"], reduction="none"), y["value_mask"])
    return {"policy": policy, "value": value_loss, "critic": critic_loss}


def train_step(net: PlayNet, critic: Critic, optimizer: torch.optim.Optimizer,
               batch: Mapping[str, Mapping[str, object]], weights: LossWeights = LossWeights()) -> Dict[str, float]:
    net.train()
    critic.train()
    terms = losses(net, critic, batch)
    total = weights.policy * terms["policy"] + weights.value * terms["value"] + weights.critic * terms["critic"]
    optimizer.zero_grad()
    total.backward()
    optimizer.step()
    return {**{k: float(v.detach()) for k, v in terms.items()}, "total": float(total.detach())}
