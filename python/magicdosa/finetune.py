"""Generation-1 training on search shards: fine-tune a checkpoint on search visit ratios and game results (CPU).

    python -m magicdosa.finetune --runs <run dir>... --init <checkpoint.pt> --out <dir> [--threads 4] [--epochs 8]

Targets (design 학습·평가 "학습·보상", planning decision 2026-11-25 decision 3):
- policy: the search visit ratios of rows the model decided with a search (`shards.policy_target_rows`). Rows without
  a search are no policy samples: intended delegations (Forge AI chose), shadow-recovery failures and failed searches
  (the model chose with a uniform prior, so the choice carries no signal). Their chosen action is never a target.
- value: the deciding seat's game result on every row (`shards.value_target_rows`: no sideboard rows, no draws).
Optionally imitation games (`--imitation`) add value rows only (their policy is Forge AI's, never a gen-1 target).

Games are split by match (crc32 of `<run>/<match>`: both seats' shards and every game of a match stay together) into
train, validation (picks the epoch) and test (reported). `repro` folders are skipped (exact re-runs of games that
are already there). All shards must carry the current encoder and vocabulary versions; `--protocols` lists the
older protocol versions accepted (their tensors are the same when the encoder version is).

Writes to --out: `log.jsonl`, `best.pt` (lowest validation loss), `last.pt`, `split.json`, `metrics.json` (the init
checkpoint, any `--compare` checkpoints and the best one on validation and test). `--eval-only` evaluates the init
and `--compare` checkpoints on the same split (it depends only on the runs and fractions) without training.
"""

from __future__ import annotations

import argparse
import json
import time
import zlib
from pathlib import Path
from typing import Dict, Iterable, List, Sequence

import numpy as np


def match_key(path: Path, run: Path) -> str:
    """`<run>/<match>`: the file name without its `-g<n>` suffix (`n00-g1` → `n00`), so both seats of a head-to-head
    game (`shards-x1/n00-g1`, `shards-x4/n00-g1`) and both games of a match share a key."""
    return f"{run.name}/{path.stem.rsplit('-g', 1)[0]}"


def split_runs(runs: Sequence[Path | str], val: float, test: float) -> Dict[str, List[Path]]:
    split = {"train": [], "val": [], "test": []}
    for run in map(Path, runs):
        for path in sorted(run.rglob("*.arrow")):
            if "repro" in path.relative_to(run).parts:
                continue
            h = zlib.crc32(match_key(path, run).encode()) % 10000
            part = "test" if h < test * 10000 else "val" if h < (test + val) * 10000 else "train"
            split[part].append(path)
    return split


def data_mix(tables) -> Dict[str, int]:
    """Games per `protocol <n> / leaf <values>`: the shard's protocol version and the search leaf evaluations its
    searched rows used (`search_leaf_evaluation`, protocol 15 on; 0 = no search on that row, left out)."""
    from . import shards
    mix: Dict[str, int] = {}
    for t in tables:
        leaf = "-"
        if "search_leaf_evaluation" in t.column_names:
            values = np.unique(t.column("search_leaf_evaluation").to_numpy())
            leaf = "+".join(str(v) for v in values if v != 0) or "-"
        key = f"protocol {shards.metadata(t).get('protocol_version')} / leaf {leaf}"
        mix[key] = mix.get(key, 0) + 1
    return dict(sorted(mix.items()))


def load(paths: Sequence[Path], protocols: Sequence[int], mix: Dict[str, int] | None = None):
    """One table of the given games. Columns that only some shards carry (protocol 15 added the search's horizon and
    leaf evaluation, which training does not read) are dropped; the model's input and target columns are in all."""
    from . import imitation, shards
    tables = [shards.read_shard(p, protocols) for p in paths]
    if mix is not None:
        for k, v in data_mix(tables).items():
            mix[k] = mix.get(k, 0) + v
    common = [c for c in tables[0].column_names if all(c in t.column_names for t in tables)]
    return imitation.concat([t.select(common) for t in tables])


def batch(table, rows: np.ndarray, value_only: bool = False) -> Dict[str, Dict[str, np.ndarray]]:
    """Model inputs and targets; the visit ratios are padded to the candidate width. Nothing privileged."""
    from . import shards
    b = shards.training_batch(table, rows)
    y = b["targets"]
    width = b["inputs"]["cand_mask"].shape[1]
    vr = y["visit_ratio"]
    if vr.shape[1] < width:
        vr = np.pad(vr, ((0, 0), (0, width - vr.shape[1])))
    turn = np.rint(b["inputs"]["global"][:, 0] * 20).astype(np.int64)
    pm = np.zeros(len(rows), bool) if value_only else y["policy_mask"]
    return {"inputs": b["inputs"], "targets": {"visit_ratio": vr[:, :width], "policy_mask": pm,
                                                "result": y["result"], "value_mask": y["value_mask"], "turn": turn}}


def batches(table, rows: np.ndarray, size: int, rng, shuffle: bool, value_only: bool = False):
    from . import shards
    if not shuffle:
        for j in range(0, len(rows), size):
            yield batch(table, rows[j:j + size], value_only)
        return
    length = shards.list_lengths(table, "token_kind")[rows] + rng.uniform(0, 4, len(rows))
    order = rows[np.argsort(length, kind="stable")]
    starts = list(range(0, len(order), size))
    rng.shuffle(starts)
    for j in starts:
        yield batch(table, np.sort(order[j:j + size]), value_only)


def losses(net, b):
    import torch
    import torch.nn.functional as F
    from .model import to_tensors
    logits, value, _ = net(to_tensors(b["inputs"]))
    y = b["targets"]
    pm = torch.from_numpy(y["policy_mask"])
    vm = torch.from_numpy(y["value_mask"])
    target = torch.from_numpy(y["visit_ratio"])
    ce = -(target * F.log_softmax(logits, -1).clamp(min=-1e4)).sum(-1)
    policy = (ce * pm).sum() / pm.sum().clamp(min=1)
    bce = F.binary_cross_entropy(value, torch.from_numpy(y["result"]), reduction="none")
    value_loss = (bce * vm).sum() / vm.sum().clamp(min=1)
    return {"policy": policy, "value": value_loss, "logits": logits, "value_out": value,
            "n_policy": int(pm.sum()), "n_value": int(vm.sum())}


def auc(score: np.ndarray, label: np.ndarray) -> float:
    """Area under the ROC curve (Mann-Whitney, ties counted half); nan with one class only."""
    pos, neg = label > 0.5, label <= 0.5
    if not pos.any() or not neg.any():
        return float("nan")
    order = np.argsort(score, kind="stable")
    ranks = np.empty(len(score))
    s = score[order]
    i = 0
    while i < len(s):                         # average ranks over ties
        j = i
        while j + 1 < len(s) and s[j + 1] == s[i]:
            j += 1
        ranks[order[i:j + 1]] = (i + j) / 2 + 1
        i = j + 1
    return float((ranks[pos].sum() - pos.sum() * (pos.sum() + 1) / 2) / (pos.sum() * neg.sum()))


DECISION_PRIORITY = 1
CAND_PASS = 4      # cand_kind of a pass candidate (Candidate.ActionCase PASS; Encoder.java)
TURN_BUCKETS =((1, 2), (3, 4), (5, 6), (7, 8), (9, 10), (11, 14), (15, 999))


def evaluate(net, table, rows: np.ndarray) -> Dict[str, object]:
    """Policy: soft cross-entropy against the visit ratios and top-1 agreement with the search (the net's argmax is
    one of the most-visited candidates), overall and per decision type. Value: loss, Brier score, accuracy and AUC
    against the deciding seat's result, overall and by turn."""
    import torch
    net.eval()
    ce, hit, n, per_type = 0.0, 0, 0, {}
    pri = {k: [] for k in ("top", "pass_prior", "pass_argmax", "pass_vr", "pass_vr_argmax", "pass_vr_unique", "tied",
                           "uniform", "n")}
    v_all, r_all, t_all, bce, nv = [], [], [], 0.0, 0
    with torch.no_grad():
        for b in batches(table, rows, 512, None, shuffle=False):
            out = losses(net, b)
            y = b["targets"]
            pm = y["policy_mask"]
            if pm.any():
                vr = y["visit_ratio"][pm]
                pred = out["logits"].numpy()[pm].argmax(-1)
                ok = vr[np.arange(len(pred)), pred] >= vr.max(-1) - 1e-6
                ce += float(out["policy"]) * int(pm.sum())
                hit += int(ok.sum())
                n += int(pm.sum())
                for t, k in zip(b["inputs"]["decision_type"][pm], ok):
                    d = per_type.setdefault(int(t), [0, 0])
                    d[0] += 1
                    d[1] += int(k)
                sel = pm & (b["inputs"]["decision_type"] == DECISION_PRIORITY)
                if sel.any():
                    prob = torch.softmax(out["logits"], -1).numpy()[sel]
                    is_pass = (b["inputs"]["cand_kind"][sel] == CAND_PASS) & b["inputs"]["cand_mask"][sel]
                    vr = y["visit_ratio"][sel]
                    count = b["inputs"]["cand_mask"][sel].sum(-1)
                    pri["top"].append(prob.max(-1))
                    pri["pass_prior"].append((prob * is_pass).sum(-1))
                    pri["pass_argmax"].append(is_pass[np.arange(len(prob)), prob.argmax(-1)])
                    pri["pass_vr"].append((vr * is_pass).sum(-1))
                    top = (vr >= vr.max(-1, keepdims=True) - 1e-6) & b["inputs"]["cand_mask"][sel]
                    pri["pass_vr_argmax"].append((top & is_pass).any(-1))         # pass among the most visited
                    pri["pass_vr_unique"].append((top & is_pass).any(-1) & (top.sum(-1) == 1))
                    pri["tied"].append(top.sum(-1) > 1)
                    pri["uniform"].append(1.0 / count)
                    pri["n"].append(count)
            vm = y["value_mask"]
            bce += float(out["value"]) * int(vm.sum())
            nv += int(vm.sum())
            v_all.append(out["value_out"].numpy()[vm])
            r_all.append(y["result"][vm])
            t_all.append(y["turn"][vm])
    v, r, t = np.concatenate(v_all), np.concatenate(r_all), np.concatenate(t_all)
    base = float(r.mean())
    by_turn = {}
    for lo, hi in TURN_BUCKETS:
        sel = (t >= lo) & (t <= hi)
        by_turn[f"{lo}-{hi}" if hi < 999 else f"{lo}+"] = {"rows": int(sel.sum()), "auc": auc(v[sel], r[sel]),
                                                          "brier": float(((v[sel] - r[sel]) ** 2).mean())
                                                          if sel.any() else float("nan")}
    priority = {}
    if pri["top"]:
        p = {k: np.concatenate(v) for k, v in pri.items()}
        priority = {"rows": int(len(p["top"])), "candidates_mean": float(p["n"].mean()),
                    "top_prior_mean": float(p["top"].mean()),
                    "top_prior_quantiles": {q: float(np.quantile(p["top"], q)) for q in (0.1, 0.25, 0.5, 0.75, 0.9)},
                    "pass_argmax_share": float(p["pass_argmax"].mean()),
                    "pass_prior_mean": float(p["pass_prior"].mean()),
                    "search_pass_among_most_visited": float(p["pass_vr_argmax"].mean()),
                    "search_pass_unique_most_visited": float(p["pass_vr_unique"].mean()),
                    "search_tied_most_visited": float(p["tied"].mean()),
                    "search_pass_visit_mean": float(p["pass_vr"].mean()),
                    "uniform_share_mean": float(p["uniform"].mean())}
    return {"policy_rows": n, "policy_ce": ce / max(n, 1), "top1_vs_search": hit / max(n, 1), "priority": priority,
            "per_type": {k: {"rows": a, "top1": h / a} for k, (a, h) in sorted(per_type.items())},
            "value_rows": nv, "value_bce": bce / max(nv, 1), "brier": float(((v - r) ** 2).mean()),
            "brier_base_rate": base * (1 - base), "value_accuracy": float(((v > 0.5) == (r > 0.5)).mean()),
            "auc": auc(v, r), "by_turn": by_turn}


def load_net(path: str | Path):
    import torch
    from . import versions
    from .model import ModelConfig, PlayNet
    ck = torch.load(str(path), map_location="cpu")
    if ck.get("model_version") != versions.MODEL_VERSION:
        raise ValueError(f"{path}: model_version {ck.get('model_version')}, expected {versions.MODEL_VERSION}")
    net = PlayNet(ModelConfig(**ck["config"]))
    net.load_state_dict(ck["state"])
    return net, ck


def main(argv: Iterable[str] | None = None) -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--runs", nargs="+", required=True)
    p.add_argument("--init", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--protocols", type=int, nargs="*", default=[], help="older protocol versions accepted")
    p.add_argument("--threads", type=int, default=4)
    p.add_argument("--epochs", type=int, default=8)
    p.add_argument("--batch", type=int, default=128)
    p.add_argument("--lr", type=float, default=3e-4)
    p.add_argument("--weight-decay", type=float, default=1e-2)
    p.add_argument("--value-weight", type=float, default=1.0)
    p.add_argument("--val", type=float, default=0.1)
    p.add_argument("--test", type=float, default=0.1)
    p.add_argument("--imitation", nargs="*", default=[], help="imitation run dirs: value rows only")
    p.add_argument("--imitation-games", type=int, default=0, help="imitation games loaded (a fixed random subset)")
    p.add_argument("--imitation-batch", type=int, default=64, help="imitation rows added to every training batch")
    p.add_argument("--compare", nargs="*", default=[], help="further checkpoints evaluated on validation and test")
    p.add_argument("--eval-only", action="store_true", help="evaluate --init and --compare, no training")
    p.add_argument("--seed", type=int, default=1)
    a = p.parse_args(argv)
    from .pretrain import _setup_threads
    _setup_threads(a.threads)

    import torch
    from . import imitation, versions

    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    log = open(out / "log.jsonl", "a", encoding="utf-8")

    def write(entry):
        log.write(json.dumps(entry) + "\n")
        log.flush()
        print(json.dumps(entry), flush=True)

    split = split_runs(a.runs, a.val, a.test)
    rng = np.random.default_rng(a.seed)
    imit_paths: List[Path] = []
    if a.imitation and a.imitation_games:
        pool = [q for d in a.imitation for q in sorted(Path(d).glob("*.arrow"))]
        imit_paths = sorted(rng.choice(pool, size=min(a.imitation_games, len(pool)), replace=False).tolist())
    (out / "split.json").write_text(json.dumps({**{k: [str(x) for x in v] for k, v in split.items()},
                                                "imitation": [str(x) for x in imit_paths]}, indent=0))
    t0 = time.time()
    mix: Dict[str, int] = {}
    tables = {k: load(v, a.protocols, mix) for k, v in split.items()}
    imit = imitation.concat([imitation.read_imitation_shard(q, a.protocols) for q in imit_paths]) if imit_paths \
        else None
    from . import shards
    names = [shards.feature_names(tables["train"], g) for g in ("global", "token", "candidate")]
    for t in list(tables.values()) + ([imit] if imit is not None else []):
        if [shards.feature_names(t, g) for g in ("global", "token", "candidate")] != names:
            raise shards.VersionMismatch("feature names differ between the training sets")
    all_rows = {k: np.arange(t.num_rows) for k, t in tables.items()}
    train_rows = all_rows["train"]
    write({"event": "start", "games": {k: len(v) for k, v in split.items()}, "imitation_games": len(imit_paths),
           "rows": {k: int(t.num_rows) for k, t in tables.items()},
           "policy_rows": {k: int(shards.policy_target_rows(t).sum()) for k, t in tables.items()},
           "imitation_rows": int(imit.num_rows) if imit is not None else 0,
           "shards_by_protocol_and_leaf": mix,
           "load_s": round(time.time() - t0, 1), "args": vars(a)})

    init, ck = load_net(a.init)
    metrics = {"init": a.init, "init_eval": {k: evaluate(init, tables[k], all_rows[k]) for k in ("val", "test")}}
    write({"event": "init", **metrics["init_eval"]["val"], "split": "val"})
    for c in a.compare:
        other, _ = load_net(c)
        metrics.setdefault("compare", {})[c] = {k: evaluate(other, tables[k], all_rows[k]) for k in ("val", "test")}
        write({"event": "compare", "checkpoint": c, **metrics["compare"][c]["val"], "split": "val"})
    if a.eval_only:
        (out / "metrics.json").write_text(json.dumps(metrics, indent=1))
        return

    net, _ = load_net(a.init)
    torch.manual_seed(a.seed)
    opt = torch.optim.AdamW(net.parameters(), lr=a.lr, weight_decay=a.weight_decay)
    steps_per_epoch = (len(train_rows) + a.batch - 1) // a.batch
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=a.lr, total_steps=steps_per_epoch * a.epochs,
                                                pct_start=0.1)

    def save(name, epoch):
        torch.save({"config": ck["config"], "state": net.state_dict(), "epoch": epoch, "init": a.init,
                    "model_version": versions.MODEL_VERSION, "encoder_version": versions.ENCODER_VERSION,
                    "vocab_version": versions.VOCAB_VERSION}, out / name)

    best, best_epoch = float("inf"), -1
    step = 0
    for epoch in range(1, a.epochs + 1):
        net.train()
        sums = {"policy": 0.0, "value": 0.0, "imit_value": 0.0, "n": 0}
        imit_iter = None
        for b in batches(tables["train"], train_rows, a.batch, rng, shuffle=True):
            net.train()
            terms = losses(net, b)
            loss = terms["policy"] + a.value_weight * terms["value"]
            if imit is not None:
                rows = np.sort(rng.choice(imit.num_rows, size=a.imitation_batch, replace=False))
                it = losses(net, batch(imit, rows, value_only=True))
                loss = loss + a.value_weight * it["value"]
                sums["imit_value"] += float(it["value"].detach())
            opt.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(net.parameters(), 1.0)
            opt.step()
            sched.step()
            step += 1
            sums["policy"] += float(terms["policy"].detach())
            sums["value"] += float(terms["value"].detach())
            sums["n"] += 1
        k = max(sums["n"], 1)
        ev = evaluate(net, tables["val"], all_rows["val"])
        score = ev["policy_ce"] + ev["value_bce"]
        write({"event": "epoch", "epoch": epoch, "step": step, "train_policy": sums["policy"] / k,
               "train_value": sums["value"] / k, "train_imit_value": sums["imit_value"] / k,
               "val_policy_ce": ev["policy_ce"], "val_top1": ev["top1_vs_search"], "val_value_bce": ev["value_bce"],
               "val_brier": ev["brier"], "val_auc": ev["auc"], "elapsed_s": round(time.time() - t0, 1)})
        if score < best:
            best, best_epoch = score, epoch
            save("best.pt", epoch)
    save("last.pt", a.epochs)
    best_net, _ = load_net(out / "best.pt")
    metrics.update({"best_epoch": best_epoch, "train_seconds": round(time.time() - t0, 1), "threads": a.threads,
                    "best_eval": {k: evaluate(best_net, tables[k], all_rows[k]) for k in ("val", "test")}})
    (out / "metrics.json").write_text(json.dumps(metrics, indent=1))
    write({"event": "done", "best_epoch": best_epoch, "test": metrics["best_eval"]["test"],
           "test_init": metrics["init_eval"]["test"]})


if __name__ == "__main__":
    main()
