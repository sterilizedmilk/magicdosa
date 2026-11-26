"""Reads agent-core's training shards and batches them (encoder E5-E7).

A shard is one game's Arrow IPC file written by agent-core: one row per decision step, variable-length values as flat
lists, feature names and versions in the schema metadata. This module only pads, reshapes and masks; it never
computes features (the encoder lives in agent-core alone).
"""

from __future__ import annotations

from pathlib import Path
from typing import Dict, List, Optional, Sequence

import numpy as np
import pyarrow as pa
import pyarrow.ipc as ipc

from . import versions

# Decider and Prior values (proto decision.proto)
DECIDER_MODEL = 1
PRIOR_UNIFORM = 2

INT_LISTS = ("token_kind", "token_card", "token_key", "rel_src", "rel_dst", "rel_kind", "deck_card", "deck_count",
             "deck_group", "cand_kind", "cand_ref0", "cand_ref1", "cand_card", "cand_key", "cand_string",
             "priv_opp_hand", "priv_my_library", "priv_opp_library")


class VersionMismatch(ValueError):
    """The shard was written with other protocol, encoder or vocabulary versions than this package expects."""


def metadata(table: pa.Table) -> Dict[str, str]:
    raw = table.schema.metadata or {}
    return {k.decode(): v.decode() for k, v in raw.items()}


def read_shard(path: Path | str, protocols: Optional[Sequence[int]] = None) -> pa.Table:
    """One game's samples; refuses a shard whose versions differ from versions.py (design: 버전 검증).
    `protocols` lists further accepted protocol versions, for training on older shards whose tensors are the same:
    the encoder and vocabulary versions must still match exactly (the encoder version is what fixes the tensors)."""
    with open(str(path), "rb") as f:          # read fully: a memory map keeps the file locked on Windows
        table = ipc.open_file(pa.BufferReader(f.read())).read_all()
    meta = metadata(table)
    accepted = {str(versions.PROTOCOL_VERSION)} | {str(p) for p in (protocols or ())}
    if meta.get("protocol_version") not in accepted:
        raise VersionMismatch(f"{path}: protocol_version {meta.get('protocol_version')}, expected one of "
                              f"{sorted(accepted)}")
    expected = {"encoder_version": versions.ENCODER_VERSION, "vocab_version": versions.VOCAB_VERSION}
    for key, value in expected.items():
        if meta.get(key) != str(value):
            raise VersionMismatch(f"{path}: {key} {meta.get(key)}, expected {value}")
    return table


def feature_names(table: pa.Table, group: str) -> List[str]:
    """Feature names of 'global', 'token' or 'candidate' rows, in column order."""
    return metadata(table)[group + "_features"].split(",")


def policy_rows(table: pa.Table) -> np.ndarray:
    """Rows usable as policy labels: decided by the model with a learned prior or a search (not uniform random)."""
    decider = table.column("decider").to_numpy()
    prior = table.column("prior").to_numpy()
    searched = table.column("search_ran").to_numpy(zero_copy_only=False)
    return (decider == DECIDER_MODEL) & ((prior != PRIOR_UNIFORM) | searched)


def list_lengths(table: pa.Table, name: str) -> np.ndarray:
    """Number of values of a list column in every row."""
    offsets = table.column(name).combine_chunks().offsets.to_numpy()
    return np.diff(offsets)


def _padded(table: pa.Table, name: str, rows: np.ndarray, width: int, fill, dtype) -> tuple:
    """[B, N] (width 1) or [B, N, width] padded array of a list column and its [B, N] mask, N >= 1. Vectorized over
    the column's offsets (no per-row Python loop)."""
    column = table.column(name).combine_chunks()
    offsets = column.offsets.to_numpy()
    values = column.values.to_numpy(zero_copy_only=False)
    starts = offsets[rows]
    counts = (offsets[rows + 1] - starts) // width
    n = max(int(counts.max()) if len(rows) else 0, 1)
    pos = np.arange(n)
    mask = pos[None, :] < counts[:, None]
    idx = starts[:, None] + pos[None, :] * width                               # [B, N] first element of each item
    if width > 1:
        idx = idx[:, :, None] + np.arange(width)[None, None, :]
        item_mask = mask[:, :, None]
    else:
        item_mask = mask
    taken = values[np.where(item_mask, idx, 0)] if len(values) else np.zeros(idx.shape, dtype=values.dtype)
    return np.where(item_mask, taken, fill).astype(dtype), mask


def make_batch(table: pa.Table, rows: Optional[Sequence[int]] = None) -> Dict[str, np.ndarray]:
    """Padded arrays for the given rows (all rows by default). Token and candidate references are positions in the
    row's token list (-1 = none); padding is 0 for vocabulary indices and -1 for references."""
    if rows is None:
        rows = range(table.num_rows)
    rows = np.asarray(list(rows), dtype=np.int64)
    t_width = len(feature_names(table, "token"))
    c_width = len(feature_names(table, "candidate"))
    batch: Dict[str, np.ndarray] = {}
    batch["global"], _ = _padded(table, "global", rows, len(feature_names(table, "global")), 0.0, np.float32)
    batch["global"] = batch["global"][:, 0]
    batch["token_feat"], batch["token_mask"] = _padded(table, "token_feat", rows, t_width, 0.0, np.float32)
    for name in ("token_kind", "token_card", "token_key"):
        batch[name], _ = _padded(table, name, rows, 1, 0, np.int64)
    batch["rel_src"], batch["rel_mask"] = _padded(table, "rel_src", rows, 1, -1, np.int64)
    for name in ("rel_dst", "rel_kind"):
        batch[name], _ = _padded(table, name, rows, 1, -1 if name == "rel_dst" else 0, np.int64)
    batch["deck_card"], batch["deck_mask"] = _padded(table, "deck_card", rows, 1, 0, np.int64)
    for name in ("deck_count", "deck_group"):
        batch[name], _ = _padded(table, name, rows, 1, 0, np.int64)
    batch["cand_feat"], batch["cand_mask"] = _padded(table, "cand_feat", rows, c_width, 0.0, np.float32)
    for name in ("cand_kind", "cand_card", "cand_key", "cand_string"):
        batch[name], _ = _padded(table, name, rows, 1, 0, np.int64)
    for name in ("cand_ref0", "cand_ref1"):
        batch[name], _ = _padded(table, name, rows, 1, -1, np.int64)
    batch["visit_ratio"], batch["has_visits"] = _padded(table, "visit_ratio", rows, 1, 0.0, np.float32)
    batch["has_visits"] = batch["has_visits"].any(axis=1)
    for name in ("chosen", "decision_type", "seat", "decider", "prior"):
        batch[name] = table.column(name).to_numpy()[rows]
    batch["result"] = table.column("result").to_numpy()[rows]
    batch["is_draw"] = table.column("is_draw").to_numpy(zero_copy_only=False)[rows]
    for name in ("priv_opp_hand", "priv_my_library", "priv_opp_library"):
        batch[name], batch[name + "_mask"] = _padded(table, name, rows, 1, 0, np.int64)
    return batch


# ---------------------------------------------------------------------------------------------------------------------
# Training view of a batch: model inputs, labels and privileged (critic / belief) labels kept apart.
# ---------------------------------------------------------------------------------------------------------------------

# DecisionType values (proto common.proto) the play module's policy does not learn (design 학습·평가 "결정 모듈 구조",
# 관측·행동 "행동 스키마"): mulligan, bottom cards and play/draw are computed from the observation-only value net;
# sideboarding belongs to the separate match module.
DECISION_MULLIGAN = 9
DECISION_BOTTOM_CARDS = 10
DECISION_PLAY_OR_DRAW = 11
DECISION_SIDEBOARD = 12
NOT_PLAY_POLICY = (DECISION_MULLIGAN, DECISION_BOTTOM_CARDS, DECISION_PLAY_OR_DRAW, DECISION_SIDEBOARD)
# Rewarded by the match result, not the game result (design: 보상 분리), so not a play-module value sample.
NOT_PLAY_VALUE = (DECISION_SIDEBOARD,)

# Observation tensors the play module reads, in ONNX input order. Nothing privileged is here.
MODEL_INPUTS = ("global", "token_kind", "token_card", "token_key", "token_feat", "token_mask", "rel_src", "rel_dst",
                "rel_kind", "rel_mask", "deck_card", "deck_count", "deck_group", "deck_mask", "decision_type",
                "cand_kind", "cand_ref0", "cand_ref1", "cand_card", "cand_key", "cand_string", "cand_feat", "cand_mask")
# Training-only columns (privileged record, Q11): critic and belief labels only.
PRIVILEGED = ("priv_opp_hand", "priv_opp_hand_mask", "priv_my_library", "priv_my_library_mask", "priv_opp_library",
              "priv_opp_library_mask")


def read_shards(paths: Sequence[Path | str]) -> pa.Table:
    """Several games' shards (each version-checked) as one table."""
    tables = [read_shard(p) for p in paths]
    if not tables:
        raise ValueError("no shards")
    names = [feature_names(t, g) for t in tables for g in ("global", "token", "candidate")]
    first = names[:3]
    for i in range(0, len(names), 3):
        if names[i:i + 3] != first:
            raise VersionMismatch(f"{paths[i // 3]}: feature names differ from {paths[0]}")
    return pa.concat_tables(tables).replace_schema_metadata(tables[0].schema.metadata)


def policy_target_rows(table: pa.Table) -> np.ndarray:
    """Rows with an ExIt policy target: decided by the model, with search visit ratios, of a decision type the play
    module's policy learns. Delegated or fallback rows are never policy samples (design: 폴백 검증 "학습 데이터")."""
    decider = table.column("decider").to_numpy()
    has_visits = list_lengths(table, "visit_ratio") > 0
    dtype = table.column("decision_type").to_numpy()
    return (decider == DECIDER_MODEL) & has_visits & ~np.isin(dtype, NOT_PLAY_POLICY)


def value_target_rows(table: pa.Table) -> np.ndarray:
    """Rows with a play-module value target (the game result). PLACEHOLDER: draws are left out until the draw value
    is decided; rows of every decider are kept (the design excludes delegated rows from the policy only)."""
    dtype = table.column("decision_type").to_numpy()
    draw = table.column("is_draw").to_numpy(zero_copy_only=False)
    return ~np.isin(dtype, NOT_PLAY_VALUE) & ~draw


def training_batch(table: pa.Table, rows: Optional[Sequence[int]] = None) -> Dict[str, Dict[str, np.ndarray]]:
    """make_batch split into {'inputs', 'targets', 'privileged'}. Only 'inputs' may reach the policy/value nets;
    'privileged' feeds the training-only critic and belief labels (design: critic 격리 규칙)."""
    if rows is None:
        rows = range(table.num_rows)
    rows = list(rows)
    b = make_batch(table, rows)
    inputs = {k: b[k].astype(np.int64) if b[k].dtype.kind == "i" else b[k] for k in MODEL_INPUTS}
    targets = {
        "chosen": b["chosen"].astype(np.int64),
        "visit_ratio": b["visit_ratio"],
        "policy_mask": policy_target_rows(table)[rows],
        "result": b["result"].astype(np.float32),
        "value_mask": value_target_rows(table)[rows],
    }
    return {"inputs": inputs, "targets": targets, "privileged": {k: b[k] for k in PRIVILEGED}}


def vocab_sizes(path: Path | str) -> Dict[str, int]:
    """Embedding table sizes from agent-core's vocab.tsv (append-only; indices from 3 per kind, 0 padding, 1 hidden,
    2 not in the vocabulary). Checks the vocabulary version against versions.py. Only counts lines; no encoding."""
    counts = {"card": 0, "ability": 0, "keyword": 0, "string": 0}
    version = None
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if line.startswith("#version"):
                version = int(line.split()[1])
            elif line and not line.startswith("#"):
                counts[line.split("\t", 1)[0].lower()] += 1
    if version != versions.VOCAB_VERSION:
        raise VersionMismatch(f"{path}: vocab_version {version}, expected {versions.VOCAB_VERSION}")
    return {k: 3 + v for k, v in counts.items()}
