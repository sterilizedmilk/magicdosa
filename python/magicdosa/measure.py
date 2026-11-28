"""Holdout measurements of trained models (coordinator request 2026-11-28, item 5).

    python -m magicdosa.measure --runs <run dir>... --models name=<checkpoint.pt>... --out <report.json>
        [--val 0.1 --test 0.1] [--threads 4]

The holdout is the validation + test part of finetune.split_runs (games no model of the same split trained on).
a. Pass bias by decision type: how often the model's argmax is a pass and how often the move actually played was a
   pass (the search's choice on searched rows; turns 1-4 of self-play were played by sampling, so they are also
   reported without those turns).
b. Value error by game phase: BCE, Brier and ECE (10 equal-width bins) of P(win) against the deciding seat's game
   result, by game turn and by turns remaining (the game's last turn minus the decision's turn), per matchup.
   Value rows and targets are shards.value_target_rows / value_targets (draws 0.5 since 2026-11-28; no draws in the
   self-play holdout so far). The shards carry no KillCheck outcome, so confirmed kills/deaths cannot be separated.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
from typing import Dict, Iterable, List

import numpy as np

TURN_BUCKETS = ((1, 2), (3, 4), (5, 6), (7, 8), (9, 10), (11, 14), (15, 99))
REMAINING_BUCKETS = ((0, 0), (1, 1), (2, 2), (3, 4), (5, 99))
CAND_PASS = 4
TURN_SCALE = 40          # Encoder.java: global "turn" = min(40, game turn) / 40
SAMPLED_TURNS = 4        # self-play turns played by sampling (sampleTurns=4)


def _label(lo: int, hi: int) -> str:
    return f"{lo}" if lo == hi else f"{lo}+" if hi >= 99 else f"{lo}-{hi}"


def value_errors(p: np.ndarray, y: np.ndarray, bins: int = 10) -> Dict[str, float]:
    """BCE, Brier and expected calibration error (equal-width bins, weighted by count) of predictions p for 0/1 y."""
    if len(p) == 0:
        return {"rows": 0}
    q = np.clip(p, 1e-6, 1 - 1e-6)
    bce = float(-(y * np.log(q) + (1 - y) * np.log(1 - q)).mean())
    idx = np.minimum((p * bins).astype(int), bins - 1)
    ece = 0.0
    for b in range(bins):
        sel = idx == b
        if sel.any():
            ece += sel.mean() * abs(float(p[sel].mean()) - float(y[sel].mean()))
    return {"rows": int(len(p)), "bce": bce, "brier": float(((p - y) ** 2).mean()), "ece": float(ece),
            "mean_p": float(p.mean()), "mean_y": float(y.mean())}


def by_bucket(p, y, key, buckets) -> Dict[str, Dict[str, float]]:
    return {_label(lo, hi): value_errors(p[(key >= lo) & (key <= hi)], y[(key >= lo) & (key <= hi)])
            for lo, hi in buckets}


def collect(net, tables) -> Dict[str, np.ndarray]:
    """Per-row model outputs and the row facts the measurements need, over all rows of the given tables."""
    import torch
    from . import shards
    from .model import to_tensors
    out = {k: [] for k in ("value", "result", "value_mask", "turn", "remaining", "matchup", "dtype", "has_pass",
                           "model_pass", "played_pass", "searched")}
    net.eval()
    for t in tables:
        meta = shards.metadata(t)
        matchup = f"{meta.get('our_deck')} vs {meta.get('opponent_deck')}"
        last = int(t.column("turns").to_numpy()[0])
        for j in range(0, t.num_rows, 512):
            rows = np.arange(j, min(j + 512, t.num_rows))
            b = shards.training_batch(t, rows)
            x = b["inputs"]
            with torch.no_grad():
                logits, value, _ = net(to_tensors(x))
            kind, mask = x["cand_kind"], x["cand_mask"]
            is_pass = (kind == CAND_PASS) & mask
            chosen = t.column("chosen").to_numpy()[rows]
            turn = np.rint(x["global"][:, 0] * TURN_SCALE).astype(int)
            out["value"].append(value.numpy())
            out["result"].append(b["targets"]["result"])
            out["value_mask"].append(b["targets"]["value_mask"])
            out["turn"].append(turn)
            out["remaining"].append(last - turn)
            out["matchup"].append(np.array([matchup] * len(rows)))
            out["dtype"].append(x["decision_type"])
            out["has_pass"].append(is_pass.any(-1))
            out["model_pass"].append(is_pass[np.arange(len(rows)), logits.numpy().argmax(-1)])
            out["played_pass"].append(is_pass[np.arange(len(rows)), np.clip(chosen, 0, kind.shape[1] - 1)])
            out["searched"].append(t.column("search_ran").to_numpy(zero_copy_only=False)[rows])
    return {k: np.concatenate(v) for k, v in out.items()}


def pass_bias(c: Dict[str, np.ndarray]) -> Dict[str, Dict[str, float]]:
    """Per decision type with a pass candidate: model argmax = pass vs played move = pass (all rows, searched rows,
    searched rows after the sampled turns)."""
    rep = {}
    for t in np.unique(c["dtype"][c["has_pass"]]):
        sel = (c["dtype"] == t) & c["has_pass"]
        s2 = sel & c["searched"]
        s3 = s2 & (c["turn"] > SAMPLED_TURNS)
        rep[int(t)] = {"rows": int(sel.sum()), "model_argmax_pass": float(c["model_pass"][sel].mean()),
                       "played_pass": float(c["played_pass"][sel].mean()),
                       "searched_rows": int(s2.sum()),
                       "model_argmax_pass_searched": float(c["model_pass"][s2].mean()) if s2.any() else None,
                       "search_played_pass": float(c["played_pass"][s2].mean()) if s2.any() else None,
                       "searched_rows_after_sampling": int(s3.sum()),
                       "model_argmax_pass_after_sampling": float(c["model_pass"][s3].mean()) if s3.any() else None,
                       "search_played_pass_after_sampling": float(c["played_pass"][s3].mean()) if s3.any() else None}
    return rep


def value_report(c: Dict[str, np.ndarray]) -> Dict[str, object]:
    vm = c["value_mask"]
    p, y, turn, rem, mu = c["value"][vm], c["result"][vm], c["turn"][vm], c["remaining"][vm], c["matchup"][vm]
    rep = {"all": value_errors(p, y), "by_turn": by_bucket(p, y, turn, TURN_BUCKETS),
           "by_turns_remaining": by_bucket(p, y, rem, REMAINING_BUCKETS), "by_matchup": {}}
    for m in np.unique(mu):
        s = mu == m
        rep["by_matchup"][str(m)] = {"all": value_errors(p[s], y[s]),
                                     "by_turn": by_bucket(p[s], y[s], turn[s], TURN_BUCKETS),
                                     "by_turns_remaining": by_bucket(p[s], y[s], rem[s], REMAINING_BUCKETS)}
    return rep


def main(argv: Iterable[str] | None = None) -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", nargs="+", required=True)
    ap.add_argument("--models", nargs="+", required=True, help="name=checkpoint.pt")
    ap.add_argument("--out", required=True)
    ap.add_argument("--val", type=float, default=0.1)
    ap.add_argument("--test", type=float, default=0.1)
    ap.add_argument("--threads", type=int, default=4)
    a = ap.parse_args(argv)
    from .pretrain import _setup_threads
    _setup_threads(a.threads)
    from . import finetune, shards
    split = finetune.split_runs(a.runs, a.val, a.test)
    paths: List[Path] = split["val"] + split["test"]
    tables = [shards.read_shard(q) for q in paths]
    report = {"holdout_shards": len(paths), "models": {}}
    for spec in a.models:
        name, ck = spec.split("=", 1)
        net, _ = finetune.load_net(ck)
        c = collect(net, tables)
        report["models"][name] = {"checkpoint": ck, "pass_bias": pass_bias(c), "value": value_report(c)}
        print(name, json.dumps(report["models"][name]["value"]["all"]), flush=True)
    Path(a.out).write_text(json.dumps(report, indent=1))


if __name__ == "__main__":
    main()
