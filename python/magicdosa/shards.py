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


def read_shard(path: Path | str) -> pa.Table:
    """One game's samples; refuses a shard whose versions differ from versions.py (design: 버전 검증)."""
    with pa.memory_map(str(path)) as source:
        table = ipc.open_file(source).read_all()
    meta = metadata(table)
    expected = {"protocol_version": versions.PROTOCOL_VERSION, "encoder_version": versions.ENCODER_VERSION,
                "vocab_version": versions.VOCAB_VERSION}
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


def _lists(table: pa.Table, name: str, rows: Sequence[int]) -> List[np.ndarray]:
    column = table.column(name).combine_chunks()
    return [np.asarray(column[i].values, dtype=np.float32 if name not in INT_LISTS else np.int64) for i in rows]


def _pad(values: List[np.ndarray], width: int, fill: float, dtype) -> tuple:
    """[B, N] (width 1) or [B, N, width] padded arrays and a [B, N] mask."""
    counts = [len(v) // width for v in values]
    n = max(counts + [1])
    shape = (len(values), n) if width == 1 else (len(values), n, width)
    out = np.full(shape, fill, dtype=dtype)
    mask = np.zeros((len(values), n), dtype=bool)
    for b, (v, c) in enumerate(zip(values, counts)):
        if width == 1:
            out[b, :c] = v
        else:
            out[b, :c] = v.reshape(c, width)
        mask[b, :c] = True
    return out, mask


def make_batch(table: pa.Table, rows: Optional[Sequence[int]] = None) -> Dict[str, np.ndarray]:
    """Padded arrays for the given rows (all rows by default). Token and candidate references are positions in the
    row's token list (-1 = none); padding is 0 for vocabulary indices and -1 for references."""
    if rows is None:
        rows = range(table.num_rows)
    rows = list(rows)
    t_width = len(feature_names(table, "token"))
    c_width = len(feature_names(table, "candidate"))
    batch: Dict[str, np.ndarray] = {}
    batch["global"] = np.stack(_lists(table, "global", rows))
    batch["token_feat"], batch["token_mask"] = _pad(_lists(table, "token_feat", rows), t_width, 0.0, np.float32)
    for name in ("token_kind", "token_card", "token_key"):
        batch[name], _ = _pad(_lists(table, name, rows), 1, 0, np.int64)
    batch["rel_src"], batch["rel_mask"] = _pad(_lists(table, "rel_src", rows), 1, -1, np.int64)
    for name in ("rel_dst", "rel_kind"):
        batch[name], _ = _pad(_lists(table, name, rows), 1, -1 if name == "rel_dst" else 0, np.int64)
    batch["deck_card"], batch["deck_mask"] = _pad(_lists(table, "deck_card", rows), 1, 0, np.int64)
    for name in ("deck_count", "deck_group"):
        batch[name], _ = _pad(_lists(table, name, rows), 1, 0, np.int64)
    batch["cand_feat"], batch["cand_mask"] = _pad(_lists(table, "cand_feat", rows), c_width, 0.0, np.float32)
    for name in ("cand_kind", "cand_card", "cand_key", "cand_string"):
        batch[name], _ = _pad(_lists(table, name, rows), 1, 0, np.int64)
    for name in ("cand_ref0", "cand_ref1"):
        batch[name], _ = _pad(_lists(table, name, rows), 1, -1, np.int64)
    visits = _lists(table, "visit_ratio", rows)
    batch["visit_ratio"], batch["has_visits"] = _pad(visits, 1, 0.0, np.float32)
    batch["has_visits"] = batch["has_visits"].any(axis=1)
    for name in ("chosen", "decision_type", "seat", "decider", "prior"):
        batch[name] = table.column(name).to_numpy()[rows]
    batch["result"] = table.column("result").to_numpy()[rows]
    batch["is_draw"] = table.column("is_draw").to_numpy(zero_copy_only=False)[rows]
    for name in ("priv_opp_hand", "priv_my_library", "priv_opp_library"):
        batch[name], batch[name + "_mask"] = _pad(_lists(table, name, rows), 1, 0, np.int64)
    return batch
