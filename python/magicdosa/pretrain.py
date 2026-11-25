"""Generation-0 pretraining on Forge AI imitation shards (CPU).

    python -m magicdosa.pretrain --runs <run dir>... --out <dir> [--threads 4] [--epochs 1] [--max-steps N]

Writes to --out: `log.jsonl` (training and evaluation lines), `checkpoint.pt` (latest), `metrics.json` (final
held-out evaluation), `split.json` (which games went where).
Losses: cross-entropy of the policy against Forge AI's chosen candidate (policy rows) + binary cross-entropy of the
value against the game result (value rows), weights 1/1 (placeholder).
"""

from __future__ import annotations

import argparse
import json
import os
import time
from pathlib import Path
from typing import Dict, Iterable, List

import numpy as np


def _setup_threads(n: int) -> None:
    for var in ("OMP_NUM_THREADS", "MKL_NUM_THREADS"):
        os.environ.setdefault(var, str(n))
    import torch
    torch.set_num_threads(n)
    torch.set_num_interop_threads(1)


def losses(net, b) -> Dict[str, "object"]:
    import torch
    import torch.nn.functional as F
    from .model import to_tensors
    x = to_tensors(b["inputs"])
    y = b["targets"]
    logits, value, _ = net(x)
    pm = torch.from_numpy(y["policy_mask"])
    vm = torch.from_numpy(y["value_mask"])
    chosen = torch.from_numpy(y["chosen"])
    ce = F.cross_entropy(logits, chosen, reduction="none")
    policy = (ce * pm).sum() / pm.sum().clamp(min=1)
    bce = F.binary_cross_entropy(value, torch.from_numpy(y["result"]), reduction="none")
    value_loss = (bce * vm).sum() / vm.sum().clamp(min=1)
    return {"policy": policy, "value": value_loss, "logits": logits, "value_out": value}


def evaluate(net, paths: List[Path], batch_size: int = 512) -> Dict[str, object]:
    """Top-1 agreement with Forge AI's choice (policy rows), overall, on steps with 2+ candidates, and per decision
    type; value Brier score and accuracy (P(win) > 0.5 vs result) on value rows."""
    import torch
    from . import imitation
    net.eval()
    hit, n_type, hit_type, multi_hit, multi_n = 0, {}, {}, 0, 0
    sq, acc, nv, wins = 0.0, 0, 0, 0.0
    n = 0
    rng = np.random.default_rng(0)
    with torch.no_grad():
        for b in imitation.stream(paths, batch_size, rng, shuffle=False):
            out = losses(net, b)
            y = b["targets"]
            pred = out["logits"].argmax(-1).numpy()
            ok = (pred == y["chosen"]) & y["policy_mask"]
            n += int(y["policy_mask"].sum())
            hit += int(ok.sum())
            multi = y["policy_mask"] & (y["n_candidates"] >= 2)
            multi_n += int(multi.sum())
            multi_hit += int((ok & multi).sum())
            dtypes = b["inputs"]["decision_type"]
            for t in np.unique(dtypes[y["policy_mask"]]):
                sel = (dtypes == t) & y["policy_mask"]
                n_type[int(t)] = n_type.get(int(t), 0) + int(sel.sum())
                hit_type[int(t)] = hit_type.get(int(t), 0) + int((ok & sel).sum())
            v = out["value_out"].numpy()[y["value_mask"]]
            r = y["result"][y["value_mask"]]
            sq += float(((v - r) ** 2).sum())
            acc += int(((v > 0.5) == (r > 0.5)).sum())
            nv += len(r)
            wins += float(r.sum())
    base = wins / max(nv, 1)
    return {
        "policy_rows": n, "top1": hit / max(n, 1),
        "multi_rows": multi_n, "top1_multi": multi_hit / max(multi_n, 1),
        "per_type": {t: {"rows": n_type[t], "top1": hit_type[t] / n_type[t]} for t in sorted(n_type)},
        "value_rows": nv, "brier": sq / max(nv, 1), "value_accuracy": acc / max(nv, 1),
        "brier_base_rate": base * (1 - base),
    }


def main(argv: Iterable[str] | None = None) -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--runs", nargs="+", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--vocab", default=str(Path(__file__).resolve().parents[2] / "agent-core" / "src" / "main"
                                          / "resources" / "magicdosa" / "agent" / "encoder" / "vocab.tsv"))
    p.add_argument("--threads", type=int, default=4)
    p.add_argument("--epochs", type=float, default=1.0)
    p.add_argument("--max-steps", type=int, default=0)
    p.add_argument("--batch", type=int, default=256)
    p.add_argument("--lr", type=float, default=1e-3)
    p.add_argument("--d-model", type=int, default=64)
    p.add_argument("--layers", type=int, default=2)
    p.add_argument("--heads", type=int, default=4)
    p.add_argument("--holdout", type=float, default=0.05)
    p.add_argument("--holdout-pairing", default="fin4-layer:IzzetProwess-vs-EsperBlink")
    p.add_argument("--eval-limit", type=int, default=0, help="games per held-out set (0 = all)")
    p.add_argument("--log-every", type=int, default=100)
    p.add_argument("--checkpoint-every", type=int, default=1000)
    p.add_argument("--seed", type=int, default=1)
    a = p.parse_args(argv)
    _setup_threads(a.threads)

    import torch
    from . import imitation, shards, versions
    from .model import ModelConfig, PlayNet

    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    run, name = a.holdout_pairing.split(":") if a.holdout_pairing else (None, None)
    split = imitation.split_games(a.runs, a.holdout, run, name)
    (out / "split.json").write_text(json.dumps({k: [str(x) for x in v] for k, v in vars(split).items()}, indent=0))
    first = imitation.read_imitation_shard(split.train[0])
    config = ModelConfig(vocab=shards.vocab_sizes(a.vocab),
                         global_width=len(shards.feature_names(first, "global")),
                         token_width=len(shards.feature_names(first, "token")),
                         candidate_width=len(shards.feature_names(first, "candidate")),
                         d_model=a.d_model, n_layers=a.layers, n_heads=a.heads, d_ff=2 * a.d_model)
    torch.manual_seed(a.seed)
    net = PlayNet(config)
    opt = torch.optim.AdamW(net.parameters(), lr=a.lr, weight_decay=1e-4)
    rows = imitation.count_rows(split.train)
    total = a.max_steps or int(a.epochs * rows / a.batch)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=a.lr, total_steps=max(total, 1), pct_start=0.05)
    log = open(out / "log.jsonl", "a", encoding="utf-8")

    def write(entry):
        log.write(json.dumps(entry) + "\n")
        log.flush()
        print(json.dumps(entry), flush=True)

    write({"event": "start", "split": split.summary(), "train_rows": rows, "steps": total, "threads": a.threads,
           "params": sum(p.numel() for p in net.parameters()), "args": vars(a)})
    rng = np.random.default_rng(a.seed)
    step, t0, acc = 0, time.time(), {"policy": 0.0, "value": 0.0, "top1": 0.0, "n": 0}

    def save():
        torch.save({"config": config.__dict__, "state": net.state_dict(), "step": step,
                    "model_version": versions.MODEL_VERSION, "encoder_version": versions.ENCODER_VERSION,
                    "vocab_version": versions.VOCAB_VERSION}, out / "checkpoint.pt")

    while step < total:
        for b in imitation.stream(split.train, a.batch, rng):
            net.train()
            terms = losses(net, b)
            loss = terms["policy"] + terms["value"]
            opt.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(net.parameters(), 1.0)
            opt.step()
            sched.step()
            step += 1
            y = b["targets"]
            pred = terms["logits"].detach().argmax(-1).numpy()
            pm = y["policy_mask"]
            acc["policy"] += float(terms["policy"].detach())
            acc["value"] += float(terms["value"].detach())
            acc["top1"] += float(((pred == y["chosen"]) & pm).sum() / max(pm.sum(), 1))
            acc["n"] += 1
            if step % a.log_every == 0:
                k = acc["n"]
                write({"event": "train", "step": step, "of": total, "policy": acc["policy"] / k,
                       "value": acc["value"] / k, "top1": acc["top1"] / k, "lr": sched.get_last_lr()[0],
                       "elapsed_s": round(time.time() - t0, 1)})
                acc = {"policy": 0.0, "value": 0.0, "top1": 0.0, "n": 0}
            if step % a.checkpoint_every == 0:
                save()
            if step >= total:
                break
    save()
    train_s = time.time() - t0
    metrics = {"train_seconds": round(train_s, 1), "steps": step, "threads": a.threads}
    for key, games in (("held_out", split.held_out), ("held_out_pairing", split.held_out_pairing)):
        if games:
            games = games[:a.eval_limit] if a.eval_limit else games
            t1 = time.time()
            metrics[key] = evaluate(net, games)
            metrics[key]["games"] = len(games)
            metrics[key]["eval_seconds"] = round(time.time() - t1, 1)
    (out / "metrics.json").write_text(json.dumps(metrics, indent=1))
    write({"event": "done", **metrics})


if __name__ == "__main__":
    main()
