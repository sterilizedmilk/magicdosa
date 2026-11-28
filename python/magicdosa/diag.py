"""Policy-head diagnosis (coordinator 2026-11-28, cleanup item 0).

    python -m magicdosa.diag agreement --checkpoint <pt> --split <split.json> --imitation-root <dir> --out <json>
    python -m magicdosa.diag overfit --init <pt> --games <shard>... --rows 1000 --out <json> [--steps 600]
    python -m magicdosa.diag cosine --checkpoint <pt> --games <shard>... --out <json>

agreement: a model's argmax against Forge AI's choice on imitation games, by decision type, and on PRIORITY by the
  kind of Forge AI's choice (pass / land / cast / ability); on rows where Forge AI did not pass: the model's
  probability on pass, and top-1 among the non-pass candidates against chance (mean 1/k over non-pass candidates).
overfit: about N decisions whose recorded choice is not a pass (one-hot targets), the policy head trained alone with
  the encoder frozen and with it trainable (value head never trained); train-set top-1 over the steps.
invariance: policy and value under a shuffle of the candidate order and of the entity-token order (references
  remapped): share of argmax changes by action identity, mean total variation, max |dp|, max |d value|.
cosine: cosine similarity of the candidate encodings (PlayNet.candidates, before the heads) of different candidates
  of the same decision, by pair of candidate kinds.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Dict, List, Sequence

import numpy as np

KIND_NAMES = {1: "land", 2: "cast", 3: "ability", 4: "pass", 5: "target", 6: "attack", 7: "block", 8: "option",
              9: "card", 10: "mana_source", 11: "pool_mana", 12: "trigger", 13: "keep", 14: "play", 15: "swap",
              16: "pay_life", 17: "name", 18: "name_type", 31: "done"}
DECISION_PRIORITY = 1
CAND_PASS = 4

def policy_head_parameters(net):
    from .model import POLICY_HEAD
    return [p for name in POLICY_HEAD for p in getattr(net, name).parameters()]


def _batches(tables, size=512):
    from . import shards
    for t in tables:
        for j in range(0, t.num_rows, size):
            rows = np.arange(j, min(j + size, t.num_rows))
            yield t, rows, shards.training_batch(t, rows)["inputs"]


def agreement(net, tables) -> Dict[str, object]:
    import torch
    from . import shards
    from .model import to_tensors
    net.eval()
    by_type, by_kind = {}, {}
    pass_prob, nonpass_hit, chance, n_nonpass = [], 0, 0.0, 0
    with torch.no_grad():
        for t, rows, x in _batches(tables):
            logits, _, _ = net(to_tensors(x))
            prob = torch.softmax(logits, -1).numpy()
            chosen = t.column("chosen").to_numpy()[rows]
            kind, mask, dtype = x["cand_kind"], x["cand_mask"], x["decision_type"]
            n = mask.sum(-1)
            ok = (prob.argmax(-1) == chosen) & (n >= 2) & ~np.isin(dtype, shards.NOT_PLAY_POLICY)
            for d in np.unique(dtype):
                s = (dtype == d) & (n >= 2)
                if s.any():
                    a = by_type.setdefault(int(d), [0, 0])
                    a[0] += int(s.sum())
                    a[1] += int(ok[s].sum())
            pri = (dtype == DECISION_PRIORITY) & (n >= 2)
            ck = kind[np.arange(len(rows)), np.clip(chosen, 0, kind.shape[1] - 1)]
            for k in np.unique(ck[pri]):
                s = pri & (ck == k)
                a = by_kind.setdefault(KIND_NAMES.get(int(k), str(k)), [0, 0])
                a[0] += int(s.sum())
                a[1] += int(ok[s].sum())
            is_pass = (kind == CAND_PASS) & mask
            np_rows = pri & (ck != CAND_PASS)
            if np_rows.any():
                pp = (prob * is_pass).sum(-1)[np_rows]
                pass_prob.append(pp)
                other = np.where(is_pass | ~mask, -np.inf, logits.numpy())[np_rows]
                nonpass_hit += int((other.argmax(-1) == chosen[np_rows]).sum())
                chance += float((1.0 / (mask & ~is_pass).sum(-1)[np_rows]).sum())
                n_nonpass += int(np_rows.sum())
    pp = np.concatenate(pass_prob) if pass_prob else np.zeros(0)
    return {"top1_by_type": {k: {"rows": a, "top1": h / a} for k, (a, h) in sorted(by_type.items())},
            "priority_top1_by_forge_choice": {k: {"rows": a, "top1": h / a} for k, (a, h) in sorted(by_kind.items())},
            "priority_forge_not_pass": {"rows": n_nonpass, "model_pass_prob_mean": float(pp.mean()) if len(pp) else None,
                                        "model_pass_prob_median": float(np.median(pp)) if len(pp) else None,
                                        "top1_among_non_pass": nonpass_hit / max(n_nonpass, 1),
                                        "chance_among_non_pass": chance / max(n_nonpass, 1)}}


def overfit_rows(tables, count: int, rng) -> List[tuple]:
    """(table index, row) of decisions of a policy type with 2+ candidates whose recorded choice is not a pass."""
    from . import shards
    picks = []
    for i, t in enumerate(tables):
        dtype = t.column("decision_type").to_numpy()
        chosen = t.column("chosen").to_numpy()
        n = shards.list_lengths(t, "cand_kind")
        kinds = t.column("cand_kind").combine_chunks()
        off = kinds.offsets.to_numpy()
        vals = kinds.values.to_numpy()
        ck = np.where(chosen < n, vals[np.minimum(off[:-1] + chosen, len(vals) - 1)], -1)
        ok = (n >= 2) & ~np.isin(dtype, shards.NOT_PLAY_POLICY) & (ck != CAND_PASS) & (chosen < n)
        picks += [(i, int(r)) for r in np.where(ok)[0]]
    order = rng.permutation(len(picks))[:count]
    return [picks[j] for j in sorted(order)]


def overfit(init: str, tables, count: int, steps: int, freeze_encoder: bool, lr: float, seed: int):
    import torch
    import torch.nn.functional as F
    from . import finetune, imitation, shards
    from .model import to_tensors
    rng = np.random.default_rng(seed)
    picks = overfit_rows(tables, count, rng)
    groups: Dict[int, List[int]] = {}
    for i, r in picks:
        groups.setdefault(i, []).append(r)
    data = []
    for i, rows in groups.items():
        rows = np.array(rows)
        x = shards.training_batch(tables[i], rows)["inputs"]
        data.append((x, tables[i].column("chosen").to_numpy()[rows].astype(np.int64)))
    # one padded batch of all rows (count is small)
    from .shards import MODEL_INPUTS
    keys = MODEL_INPUTS
    width = {k: max(d[0][k].shape[1] for d in data) for k in keys if data[0][0][k].ndim >= 2}
    fill = {"rel_src": -1, "rel_dst": -1, "cand_ref0": -1, "cand_ref1": -1}
    xs = {}
    for k in keys:
        parts = []
        for x, _ in data:
            a = x[k]
            if a.ndim >= 2 and a.shape[1] < width[k]:
                pad = [(0, 0), (0, width[k] - a.shape[1])] + [(0, 0)] * (a.ndim - 2)
                a = np.pad(a, pad, constant_values=fill.get(k, 0))
            parts.append(a)
        xs[k] = np.concatenate(parts)
    y = np.concatenate([c for _, c in data])
    dtype = xs["decision_type"]
    torch.manual_seed(seed)
    net, _ = finetune.load_net(init)
    params = policy_head_parameters(net) if freeze_encoder else \
        [p for name, m in net.named_children() if name != "value" for p in m.parameters()]
    for p in net.parameters():
        p.requires_grad_(False)
    for p in params:
        p.requires_grad_(True)
    opt = torch.optim.Adam(params, lr=lr)
    tx = to_tensors(xs)
    target = torch.from_numpy(y)
    curve = []
    for step in range(1, steps + 1):
        net.train()
        logits, _, _ = net(tx)
        loss = F.cross_entropy(logits, target)
        opt.zero_grad()
        loss.backward()
        opt.step()
        if step % 50 == 0 or step == 1:
            net.eval()
            with torch.no_grad():
                pred = net(tx)[0].argmax(-1).numpy()
            hit = pred == y
            curve.append({"step": step, "loss": float(loss.detach()), "top1": float(hit.mean()),
                          "top1_priority": float(hit[dtype == DECISION_PRIORITY].mean())
                          if (dtype == DECISION_PRIORITY).any() else None,
                          "predicted_pass_share": float((xs["cand_kind"][np.arange(len(y)), pred] == CAND_PASS).mean())})
    types = {int(t): int((dtype == t).sum()) for t in np.unique(dtype)}
    return {"rows": int(len(y)), "rows_by_type": types, "freeze_encoder": freeze_encoder,
            "trained_parameters": int(sum(p.numel() for p in params)), "curve": curve}


def cosine(net, tables) -> Dict[str, Dict[str, float]]:
    import torch
    from .model import to_tensors
    net.eval()
    acc: Dict[str, List[float]] = {}
    with torch.no_grad():
        for t, rows, x in _batches(tables):
            tx = to_tensors(x)
            _, tokens = net.encoder(tx)
            cand = net.candidates(tx, tokens).numpy()
            unit = cand / np.maximum(np.linalg.norm(cand, axis=-1, keepdims=True), 1e-9)
            kind, mask = x["cand_kind"], x["cand_mask"]
            for b in range(len(rows)):
                idx = np.where(mask[b])[0]
                if len(idx) < 2:
                    continue
                sim = unit[b, idx] @ unit[b, idx].T
                for i in range(len(idx)):
                    for j in range(i + 1, len(idx)):
                        a, c = sorted((KIND_NAMES.get(int(kind[b, idx[i]]), "?"), KIND_NAMES.get(int(kind[b, idx[j]]), "?")))
                        acc.setdefault(f"{a}-{c}", []).append(float(sim[i, j]))
    return {k: {"pairs": len(v), "mean": float(np.mean(v)), "p90": float(np.quantile(v, 0.9)),
                "share_above_0.99": float(np.mean(np.array(v) > 0.99))}
            for k, v in sorted(acc.items(), key=lambda kv: -len(kv[1]))}


CAND_KEYS = ("cand_kind", "cand_ref0", "cand_ref1", "cand_card", "cand_key", "cand_string", "cand_feat", "cand_mask")
TOKEN_KEYS = ("token_kind", "token_card", "token_key", "token_feat", "token_mask")


def permute_candidates(x: Dict[str, np.ndarray], rng) -> tuple:
    """A copy of the batch with each row's real candidates in a random order; perm[b, new] = old index."""
    y = {k: v.copy() for k, v in x.items()}
    B, C = x["cand_mask"].shape
    perm = np.tile(np.arange(C), (B, 1))
    for b in range(B):
        n = int(x["cand_mask"][b].sum())
        perm[b, :n] = rng.permutation(n)
    for k in CAND_KEYS:
        y[k] = np.take_along_axis(x[k], perm.reshape(B, C, *([1] * (x[k].ndim - 2))), axis=1)
    return y, perm


def permute_tokens(x: Dict[str, np.ndarray], rng) -> Dict[str, np.ndarray]:
    """A copy of the batch with each row's real entity tokens in a random order and every token reference (relation
    ends, candidate refs) remapped, so the observation is the same set of objects in another order."""
    y = {k: v.copy() for k, v in x.items()}
    B, T = x["token_mask"].shape
    for b in range(B):
        n = int(x["token_mask"][b].sum())
        order = rng.permutation(n)                      # new position i holds old token order[i]
        new_of_old = np.empty(n, np.int64)
        new_of_old[order] = np.arange(n)
        for k in TOKEN_KEYS:
            y[k][b, :n] = x[k][b, order]
        for k in ("rel_src", "rel_dst", "cand_ref0", "cand_ref1"):
            v = x[k][b]
            ok = (v >= 0) & (v < n)
            y[k][b] = np.where(ok, new_of_old[np.clip(v, 0, n - 1)], v)
    return y


def invariance(net, tables, seed: int = 0) -> Dict[str, Dict[str, float]]:
    """Policy and value under (a) a candidate-order shuffle and (b) an entity-token-order shuffle, compared per
    action (by identity): share of decisions whose argmax action changes, mean total-variation distance, max |dp|
    per action, max |d value|. Rows with 2+ candidates."""
    import torch
    from .model import to_tensors
    net.eval()
    rng = np.random.default_rng(seed)
    out = {}
    for mode in ("candidates", "tokens"):
        changed, tv, n, max_dp, max_dv, ties = 0, 0.0, 0, 0.0, 0.0, 0
        with torch.no_grad():
            for _, rows, x in _batches(tables):
                multi = x["cand_mask"].sum(-1) >= 2
                lg, v, _ = net(to_tensors(x))
                p = torch.softmax(lg, -1).numpy()
                if mode == "candidates":
                    y, perm = permute_candidates(x, rng)
                    lg2, v2, _ = net(to_tensors(y))
                    p2s = torch.softmax(lg2, -1).numpy()
                    p2 = np.zeros_like(p2s)
                    np.put_along_axis(p2, perm, p2s, axis=1)  # back to the original candidate order
                else:
                    lg2, v2, _ = net(to_tensors(permute_tokens(x, rng)))
                    p2 = torch.softmax(lg2, -1).numpy()
                m = x["cand_mask"]
                p, p2 = np.where(m, p, 0), np.where(m, p2, 0)
                top = p.max(-1, keepdims=True)
                ties += int((((p >= top - 1e-6) & m).sum(-1) > 1)[multi].sum())
                changed += int((p.argmax(-1) != p2.argmax(-1))[multi].sum())
                tv += float((0.5 * np.abs(p - p2).sum(-1))[multi].sum())
                max_dp = max(max_dp, float(np.abs(p - p2)[multi].max()) if multi.any() else 0.0)
                max_dv = max(max_dv, float(np.abs(v.numpy() - v2.numpy()).max()))
                n += int(multi.sum())
        out[mode] = {"rows": n, "argmax_changed": changed / max(n, 1), "mean_tv": tv / max(n, 1),
                     "max_abs_dp": max_dp, "max_abs_dvalue": max_dv, "rows_with_tied_top": ties / max(n, 1)}
    return out


def _tables(paths: Sequence[str], protocols=(14,)):
    from . import shards
    return [shards.read_shard(p, protocols) for p in paths]


def main(argv=None) -> None:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    g = sub.add_parser("agreement")
    g.add_argument("--checkpoint", required=True)
    g.add_argument("--games", nargs="+", required=True)
    g.add_argument("--out", required=True)
    o = sub.add_parser("overfit")
    o.add_argument("--init", required=True)
    o.add_argument("--games", nargs="+", required=True)
    o.add_argument("--rows", type=int, default=1000)
    o.add_argument("--steps", type=int, default=600)
    o.add_argument("--lr", type=float, default=1e-3)
    o.add_argument("--seed", type=int, default=1)
    o.add_argument("--out", required=True)
    v = sub.add_parser("invariance")
    v.add_argument("--checkpoint", required=True)
    v.add_argument("--games", nargs="+", required=True)
    v.add_argument("--out", required=True)
    c = sub.add_parser("cosine")
    c.add_argument("--checkpoint", required=True)
    c.add_argument("--games", nargs="+", required=True)
    c.add_argument("--out", required=True)
    for s in (g, o, c, v):
        s.add_argument("--threads", type=int, default=4)
    a = ap.parse_args(argv)
    from .pretrain import _setup_threads
    _setup_threads(a.threads)
    from . import finetune
    tables = _tables(a.games)
    if a.cmd == "agreement":
        net, _ = finetune.load_net(a.checkpoint)
        out = agreement(net, tables)
    elif a.cmd == "invariance":
        net, _ = finetune.load_net(a.checkpoint)
        out = invariance(net, tables)
    elif a.cmd == "cosine":
        net, _ = finetune.load_net(a.checkpoint)
        out = cosine(net, tables)
    else:
        out = {mode: overfit(a.init, tables, a.rows, a.steps, mode == "frozen", a.lr, a.seed)
               for mode in ("frozen", "unfrozen")}
    Path(a.out).write_text(json.dumps(out, indent=1))
    print(json.dumps(out)[:3000])


if __name__ == "__main__":
    main()
