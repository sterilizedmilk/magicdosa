"""Play-module network skeleton (design 관측·행동 "행동 스키마", 학습·평가 "결정 모듈 구조", "학습 전략과 보상").

What the design fixes and this module follows:
- a shared encoder over the entity tokens ("공통 인코더(엔티티 Transformer)");
- one pointer head per decision type over the engine-given candidates ("결정 유형 + 후보 목록 → 포인터 선택",
  "결정 유형별 헤드"); multi-select is one step per pick, so a step is one softmax over its candidates;
- an observation-only value head (win probability; used for search leaves and mulligan / play-draw tables);
- a training-only critic that alone sees the hidden cards, on its own path, and is dropped at inference
  ("critic 격리 규칙");
- card embeddings by vocabulary index (Scryfall oracle_id), shared between object, deck and candidate tokens.

Everything else here (widths, depths, how relations and deck tokens enter, pooling, the critic's inputs) is a
PLACEHOLDER chosen only to make the pipeline run end to end; see docs/python-training.md "Placeholders".
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, Mapping

import torch
from torch import Tensor, nn

from .shards import MODEL_INPUTS

N_DECISION_TYPES = 15        # DecisionType 0..14 (proto common.proto)
N_TOKEN_KINDS = 5            # 0 padding, 1 player, 2 object, 3 stack item, 4 effect
N_REL_KINDS = 7              # 0 padding, 1..6 attached, attacks, blocks, targets, source, affects
N_DECK_GROUPS = 8            # 0 padding, 1..7 (Encoder MY_CURRENT .. OPP_REMAINING)
N_CAND_KINDS = 32            # Candidate field numbers; 31 = "done"
NEG = -1.0e9                 # logit of a padded candidate (finite, so ONNX Runtime and softmax stay NaN-free)


@dataclass
class ModelConfig:
    """Sizes. `vocab` comes from agent-core's vocab.tsv (shards.vocab_sizes); widths come from the shard metadata."""
    vocab: Dict[str, int]
    global_width: int
    token_width: int
    candidate_width: int
    # PLACEHOLDER sizes: not decided by the design.
    d_model: int = 64
    n_heads: int = 4
    n_layers: int = 2
    d_ff: int = 128
    vocab_headroom: int = 64          # spare rows so an appended vocabulary entry does not need a resize right away
    extra: Dict[str, int] = field(default_factory=dict)

    def rows(self, kind: str) -> int:
        return self.vocab[kind] + self.vocab_headroom


class Block(nn.Module):
    """Pre-norm self-attention block with an additive per-head attention bias (relations) and a key padding mask."""

    def __init__(self, c: ModelConfig):
        super().__init__()
        self.h = c.n_heads
        self.dk = c.d_model // c.n_heads
        self.norm1 = nn.LayerNorm(c.d_model)
        self.qkv = nn.Linear(c.d_model, 3 * c.d_model)
        self.out = nn.Linear(c.d_model, c.d_model)
        self.norm2 = nn.LayerNorm(c.d_model)
        self.ff = nn.Sequential(nn.Linear(c.d_model, c.d_ff), nn.GELU(), nn.Linear(c.d_ff, c.d_model))

    def forward(self, x: Tensor, bias: Tensor, key_mask: Tensor) -> Tensor:
        b, n, d = x.shape
        q, k, v = self.qkv(self.norm1(x)).reshape(b, n, 3, self.h, self.dk).permute(2, 0, 3, 1, 4).unbind(0)
        att = q @ k.transpose(-1, -2) / (self.dk ** 0.5) + bias
        att = att.masked_fill(~key_mask[:, None, None, :], NEG).softmax(-1)
        x = x + self.out((att @ v).transpose(1, 2).reshape(b, n, d))
        return x + self.ff(self.norm2(x))


def _one_hot(ref: Tensor, n: int) -> Tensor:
    """[..., n] float one-hot of positions; -1 (none) and padding give an all-zero row (no gather with -1)."""
    return (ref.unsqueeze(-1) == torch.arange(n, device=ref.device)).float()


class Encoder(nn.Module):
    """Shared observation encoder: [global token] + entity tokens + deck tokens -> contextual embeddings."""

    def __init__(self, c: ModelConfig):
        super().__init__()
        d = c.d_model
        self.card = nn.Embedding(c.rows("card"), d, padding_idx=0)     # shared with deck and candidate tokens
        self.key = nn.Embedding(c.rows("ability"), d, padding_idx=0)   # ability keys / cast methods
        self.token_kind = nn.Embedding(N_TOKEN_KINDS, d)
        self.token_feat = nn.Linear(c.token_width, d)
        self.global_in = nn.Linear(c.global_width, d)
        self.deck_group = nn.Embedding(N_DECK_GROUPS, d)
        self.deck_count = nn.Linear(1, d)                              # PLACEHOLDER: raw count as one input
        self.segment = nn.Embedding(3, d)                              # global / entity / deck
        self.rel_bias = nn.Embedding(N_REL_KINDS, c.n_heads)           # PLACEHOLDER: relations as attention bias
        self.blocks = nn.ModuleList(Block(c) for _ in range(c.n_layers))
        self.norm = nn.LayerNorm(d)

    def forward(self, x: Mapping[str, Tensor]):
        tok = (self.card(x["token_card"]) + self.key(x["token_key"]) + self.token_kind(x["token_kind"])
               + self.token_feat(x["token_feat"]) + self.segment.weight[1])
        glob = (self.global_in(x["global"]) + self.segment.weight[0]).unsqueeze(1)
        deck = (self.card(x["deck_card"]) + self.deck_group(x["deck_group"])
                + self.deck_count(x["deck_count"].float().unsqueeze(-1)) + self.segment.weight[2])
        h = torch.cat([glob, tok, deck], dim=1)
        n_tok = tok.shape[1]
        n = h.shape[1]
        ones = torch.ones_like(x["token_mask"][:, :1])
        key_mask = torch.cat([ones, x["token_mask"], x["deck_mask"]], dim=1)
        # relation bias between entity tokens: sum over relations of w[kind] at (src, dst); token i is position i+1
        src = _one_hot(x["rel_src"] + 1, n) * x["rel_mask"].unsqueeze(-1).float()
        dst = _one_hot(torch.where(x["rel_dst"] >= 0, x["rel_dst"] + 1, x["rel_dst"]), n)
        w = self.rel_bias(x["rel_kind"].clamp(min=0)) * x["rel_mask"].unsqueeze(-1).float()
        bias = torch.einsum("bri,brj,brh->bhij", src, dst, w)
        for blk in self.blocks:
            h = blk(h, bias, key_mask)
        h = self.norm(h)
        return h[:, 0], h[:, 1:1 + n_tok]                              # pooled (global slot), entity tokens


class PlayNet(nn.Module):
    """Policy (per-decision-type pointer heads over candidates) + observation-only value head. This is what is
    exported to ONNX; it takes no privileged input."""

    def __init__(self, c: ModelConfig):
        super().__init__()
        d = c.d_model
        self.config = c
        self.encoder = Encoder(c)
        self.cand_kind = nn.Embedding(N_CAND_KINDS, d)
        # cand_string holds STRING indices, and card indices for a sideboard swap's card taken out, so its table
        # covers both index spaces (PLACEHOLDER)
        self.cand_string = nn.Embedding(max(c.rows("string"), c.rows("card")), d, padding_idx=0)
        self.cand_feat = nn.Linear(c.candidate_width, d)
        self.ref0 = nn.Linear(d, d)
        self.ref1 = nn.Linear(d, d)
        self.type_emb = nn.Embedding(N_DECISION_TYPES, d)
        # one pointer head per decision type: score(candidate, context)
        self.heads = nn.ModuleList(nn.Sequential(nn.Linear(2 * d, d), nn.GELU(), nn.Linear(d, 1))
                                   for _ in range(N_DECISION_TYPES))
        self.value = nn.Sequential(nn.Linear(d, d), nn.GELU(), nn.Linear(d, 1))

    def candidates(self, x: Mapping[str, Tensor], tokens: Tensor) -> Tensor:
        e = self.encoder
        n_tok = tokens.shape[1]
        c = (self.cand_kind(x["cand_kind"]) + e.card(x["cand_card"]) + e.key(x["cand_key"])
             + self.cand_string(x["cand_string"]) + self.cand_feat(x["cand_feat"])
             + self.ref0(_one_hot(x["cand_ref0"], n_tok) @ tokens)
             + self.ref1(_one_hot(x["cand_ref1"], n_tok) @ tokens))
        return c

    def forward(self, x: Mapping[str, Tensor]):
        pooled, tokens = self.encoder(x)
        cand = self.candidates(x, tokens)
        ctx = (pooled + self.type_emb(x["decision_type"])).unsqueeze(1).expand_as(cand)
        z = torch.cat([cand, ctx], dim=-1)
        all_heads = torch.cat([h(z) for h in self.heads], dim=-1)       # [B, C, types]
        pick = _one_hot(x["decision_type"], N_DECISION_TYPES).unsqueeze(1)
        logits = (all_heads * pick).sum(-1)
        logits = logits.masked_fill(~x["cand_mask"], NEG)
        value = torch.sigmoid(self.value(pooled)).squeeze(-1)          # P(win) of the deciding seat
        return logits, value, pooled


class Critic(nn.Module):
    """Training-only asymmetric value (PerfectDou PTIE). Reads the hidden cards on its own path; never exported.
    PLACEHOLDER inputs: the shared pooled observation, detached so no hidden-information gradient reaches the shared
    encoder, plus mean-pooled card embeddings of the opponent's hand and both libraries. Library order and the
    observation history the design mentions are not used yet (open questions)."""

    def __init__(self, c: ModelConfig):
        super().__init__()
        d = c.d_model
        self.card = nn.Embedding(c.rows("card"), d, padding_idx=0)     # its own table, not the shared one
        self.mix = nn.Sequential(nn.Linear(4 * d, d), nn.GELU(), nn.Linear(d, 1))

    def _bag(self, cards: Tensor, mask: Tensor) -> Tensor:
        m = mask.unsqueeze(-1).float()
        return (self.card(cards) * m).sum(1) / m.sum(1).clamp(min=1.0)

    def forward(self, pooled: Tensor, priv: Mapping[str, Tensor]) -> Tensor:
        z = torch.cat([pooled.detach(),
                       self._bag(priv["priv_opp_hand"], priv["priv_opp_hand_mask"]),
                       self._bag(priv["priv_my_library"], priv["priv_my_library_mask"]),
                       self._bag(priv["priv_opp_library"], priv["priv_opp_library_mask"])], dim=-1)
        return torch.sigmoid(self.mix(z)).squeeze(-1)


class ExportWrapper(nn.Module):
    """Positional-argument view of PlayNet for ONNX export (input order = shards.MODEL_INPUTS)."""

    def __init__(self, net: PlayNet):
        super().__init__()
        self.net = net

    def forward(self, *args: Tensor):
        logits, value, _ = self.net(dict(zip(MODEL_INPUTS, args)))
        return logits, value


def to_tensors(arrays: Mapping[str, "object"]) -> Dict[str, Tensor]:
    return {k: torch.from_numpy(v) for k, v in arrays.items()}
