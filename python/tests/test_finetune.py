"""Generation-1 fine-tuning on search shards: split by match across seats, accepted protocols, targets, AUC."""

from pathlib import Path

import numpy as np
import pyarrow as pa
import pyarrow.ipc as ipc
import pytest

from magicdosa import finetune, shards, versions

from conftest import SAMPLE


def write(table: pa.Table, path: Path, **meta) -> Path:
    m = dict(table.schema.metadata)
    m.update({k.encode(): v.encode() for k, v in meta.items()})
    path.parent.mkdir(parents=True, exist_ok=True)
    with pa.OSFile(str(path), "wb") as sink:
        with ipc.new_file(sink, table.schema.with_metadata(m)) as w:
            w.write_table(table)
    return path


@pytest.fixture()
def run(table, tmp_path):
    """A fake head-to-head run: 10 matches, 2 games each, both seats' shards; plus a repro copy."""
    d = tmp_path / "gate9"
    for m in range(10):
        for g in (1, 2):
            for seat in ("shards-x1", "shards-x4"):
                write(table, d / "n0" / seat / f"n0{m}-g{g}.arrow")
    write(table, d / "repro" / "shards" / "n00-g1.arrow")
    return d


def test_split_keeps_matches_and_seats_together(run):
    s = finetune.split_runs([run], val=0.3, test=0.3)
    seen = {}
    for part, paths in s.items():
        for p in paths:
            assert "repro" not in p.parts
            seen.setdefault(finetune.match_key(p, run), set()).add(part)
    assert all(len(parts) == 1 for parts in seen.values())
    assert sum(len(v) for v in s.values()) == 40
    assert s == finetune.split_runs([run], val=0.3, test=0.3)


def test_older_protocol_only_when_listed(table, tmp_path):
    old = write(table, tmp_path / "old.arrow", protocol_version=str(versions.PROTOCOL_VERSION - 1))
    with pytest.raises(shards.VersionMismatch):
        shards.read_shard(old)
    assert shards.read_shard(old, [versions.PROTOCOL_VERSION - 1]).num_rows == table.num_rows
    other_encoder = write(table, tmp_path / "enc.arrow", protocol_version=str(versions.PROTOCOL_VERSION - 1),
                          encoder_version=str(versions.ENCODER_VERSION - 1))
    with pytest.raises(shards.VersionMismatch):
        shards.read_shard(other_encoder, [versions.PROTOCOL_VERSION - 1])


def test_load_drops_columns_only_some_shards_carry(table, tmp_path):
    a = write(table, tmp_path / "a.arrow")
    b = write(table.drop_columns(["search_rollout"]), tmp_path / "b.arrow")
    t = finetune.load([a, b], [])
    assert t.num_rows == 2 * table.num_rows and "search_rollout" not in t.column_names


def test_batch_targets(table):
    rows = np.arange(table.num_rows)
    b = finetune.batch(table, rows)
    y = b["targets"]
    assert y["visit_ratio"].shape == b["inputs"]["cand_mask"].shape
    assert (y["policy_mask"] == shards.policy_target_rows(table)).all()
    assert not finetune.batch(table, rows, value_only=True)["targets"]["policy_mask"].any()
    assert (y["turn"] >= 0).all()


def test_auc():
    assert finetune.auc(np.array([0.1, 0.4, 0.35, 0.8]), np.array([0, 0, 1, 1])) == 0.75
    assert finetune.auc(np.array([0.5, 0.5]), np.array([0, 1])) == 0.5
    assert np.isnan(finetune.auc(np.array([0.5]), np.array([1])))
