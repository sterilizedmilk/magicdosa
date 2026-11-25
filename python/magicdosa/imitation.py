"""Generation-0 imitation data: agent-core shards of Forge AI decisions (planning decision 2026-11-25, decision 3).

Generation 0's encoder, policy and value nets are pretrained on Forge AI's choices. The policy target is the chosen
candidate of each decision step (one-hot), not visit ratios; the value target is the game result of the deciding
seat. From generation 1 on, imitation rows never enter the replay buffer: this module is the only reader of shards
tagged `source=imitation:*`, and `shards.policy_target_rows` keeps giving 0 for them (their decider is a delegation).

Games are streamed from disk (about 11 KB per decision step decompressed, far more than fits in memory) and split by
match, never by decision step.
"""

from __future__ import annotations

import zlib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterator, List, Optional, Sequence, Tuple

import numpy as np
import pyarrow as pa

from . import shards

IMITATION_PREFIX = "imitation:"


class NotImitation(ValueError):
    """A shard without the imitation source tag was given to the imitation loader."""


def read_imitation_shard(path: Path | str) -> pa.Table:
    table = shards.read_shard(path)
    source = shards.metadata(table).get("source", "")
    if not source.startswith(IMITATION_PREFIX):
        raise NotImitation(f"{path}: source {source!r}")
    return table


def match_key(path: Path) -> str:
    """`<run>/<match>`: the file name without its `-g<n>` game suffix, prefixed by the run directory."""
    stem = path.stem
    match = stem.rsplit("-g", 1)[0]
    return f"{path.parent.name}/{match}"


def pairing(path: Path) -> str:
    """`<our deck>-vs-<opponent deck>` from the file name."""
    return path.stem.rsplit("-m", 1)[0]


@dataclass
class Split:
    train: List[Path]
    held_out: List[Path]                       # about `fraction` of the matches of every run
    held_out_pairing: List[Path] = field(default_factory=list)   # every game of one run's pairing

    def summary(self) -> Dict[str, int]:
        return {"train": len(self.train), "held_out": len(self.held_out),
                "held_out_pairing": len(self.held_out_pairing)}


def split_games(runs: Sequence[Path | str], fraction: float = 0.05,
                pairing_run: Optional[str] = None, pairing_name: Optional[str] = None) -> Split:
    """Deterministic split by match (crc32 of `<run>/<match>`), so the games of a match stay together. With
    `pairing_run` and `pairing_name`, all games of that pairing in that run are held out on their own."""
    split = Split([], [], [])
    for run in runs:
        for path in sorted(Path(run).glob("*.arrow")):
            if pairing_run and path.parent.name == pairing_run and pairing(path) == pairing_name:
                split.held_out_pairing.append(path)
            elif zlib.crc32(match_key(path).encode()) % 10000 < fraction * 10000:
                split.held_out.append(path)
            else:
                split.train.append(path)
    return split


def concat(tables: Sequence[pa.Table]) -> pa.Table:
    """Games with the same feature names as one single-chunk table carrying the first game's metadata."""
    meta = tables[0].schema.metadata
    names = [shards.feature_names(tables[0], g) for g in ("global", "token", "candidate")]
    for t in tables[1:]:
        if [shards.feature_names(t, g) for g in ("global", "token", "candidate")] != names:
            raise shards.VersionMismatch("feature names differ between imitation shards")
    plain = [t.replace_schema_metadata(None) for t in tables]
    return pa.concat_tables(plain).combine_chunks().replace_schema_metadata(meta)


def imitation_targets(table: pa.Table, rows: np.ndarray) -> Dict[str, np.ndarray]:
    """Policy target = chosen candidate of the step, for the decision types the play module's policy learns
    (not mulligan, bottom cards, play/draw, sideboard: those come from the value net or the match module). Value
    target = game result of the deciding seat (shards.value_target_rows)."""
    dtype = table.column("decision_type").to_numpy()[rows]
    return {
        "chosen": table.column("chosen").to_numpy()[rows].astype(np.int64),
        "policy_mask": ~np.isin(dtype, shards.NOT_PLAY_POLICY),
        "result": table.column("result").to_numpy()[rows].astype(np.float32),
        "value_mask": shards.value_target_rows(table)[rows],
        "n_candidates": shards.list_lengths(table, "cand_kind")[rows],
    }


def batch(table: pa.Table, rows: np.ndarray) -> Dict[str, Dict[str, np.ndarray]]:
    """{'inputs', 'targets'} for the given rows. Privileged columns are not read: generation 0 trains no critic."""
    b = shards.make_batch(table, rows)
    inputs = {k: b[k].astype(np.int64) if b[k].dtype.kind == "i" else b[k] for k in shards.MODEL_INPUTS}
    return {"inputs": inputs, "targets": imitation_targets(table, rows)}


def stream(paths: Sequence[Path], batch_size: int, rng: np.random.Generator, games_per_chunk: int = 48,
           shuffle: bool = True) -> Iterator[Dict[str, Dict[str, np.ndarray]]]:
    """Batches over the given games: chunks of games read together. When shuffling, rows of a chunk are bucketed by
    token count (sorted with a little noise, cut into batches, batch order shuffled) so that a batch pads little."""
    order = list(paths)
    if shuffle:
        rng.shuffle(order)
    for i in range(0, len(order), games_per_chunk):
        table = concat([read_imitation_shard(p) for p in order[i:i + games_per_chunk]])
        if not shuffle:
            for j in range(0, table.num_rows, batch_size):
                yield batch(table, np.arange(j, min(j + batch_size, table.num_rows)))
            continue
        length = shards.list_lengths(table, "token_kind") + rng.uniform(0, 4, table.num_rows)
        rows = np.argsort(length, kind="stable")
        starts = list(range(0, len(rows), batch_size))
        rng.shuffle(starts)
        for j in starts:
            yield batch(table, np.sort(rows[j:j + batch_size]))


def count_rows(paths: Sequence[Path]) -> int:
    import pyarrow.ipc as ipc
    total = 0
    for p in paths:
        with pa.memory_map(str(p)) as src:
            reader = ipc.open_file(src)
            total += sum(reader.get_batch(i).num_rows for i in range(reader.num_record_batches))
    return total
