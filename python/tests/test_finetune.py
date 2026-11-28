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
    older = min(versions.COMPATIBLE_PROTOCOLS + (versions.PROTOCOL_VERSION,)) - 1  # neither current nor compatible
    old = write(table, tmp_path / "old.arrow", protocol_version=str(older))
    with pytest.raises(shards.VersionMismatch):
        shards.read_shard(old)
    assert shards.read_shard(old, [older]).num_rows == table.num_rows
    other_encoder = write(table, tmp_path / "enc.arrow", protocol_version=str(older),
                          encoder_version=str(versions.ENCODER_VERSION - 1))
    with pytest.raises(shards.VersionMismatch):
        shards.read_shard(other_encoder, [older])


def test_compatible_protocols_read_without_asking(table, tmp_path):
    # protocol 15 shards (gen2-c self-play b0021-b0033) train together with protocol 16 ones
    assert 15 in versions.COMPATIBLE_PROTOCOLS
    p15 = write(table, tmp_path / "p15.arrow", protocol_version="15")
    assert shards.read_shard(p15).num_rows == table.num_rows
    assert shards.read_shards([p15]).num_rows == table.num_rows


def test_protocol16_shards_read_with_the_schema2_columns(table, tmp_path):
    # protocol 17 / shard schema 2 added search_status, search_root_value and the per-candidate N, Q and prior
    added = ["search_status", "search_root_value", "cand_visits", "cand_q", "cand_prior"]
    old = table.drop_columns([c for c in added if c in table.column_names])
    p16 = write(old, tmp_path / "p16.arrow", protocol_version="16")
    t = shards.read_shard(p16)
    ran = old.column("search_ran").to_pylist()
    assert t.column("search_status").to_pylist() == [
        shards.STATUS_COMPLETED if r else shards.search_status(old)[i] for i, r in enumerate(ran)]
    assert all(np.isnan(v) for v in t.column("search_root_value").to_pylist())
    assert t.column("cand_visits").to_pylist() == [[]] * table.num_rows
    # mixed with a schema-2 shard whose columns sit where agent-core puts them
    new = write(t.select(list(reversed(t.column_names))), tmp_path / "p17.arrow", protocol_version="17",
                shard_schema_version="2")
    assert shards.read_shards([p16, new]).num_rows == 2 * table.num_rows


def test_a_newer_shard_schema_is_refused(table, tmp_path):
    newer = write(table, tmp_path / "s9.arrow", shard_schema_version=str(versions.SHARD_SCHEMA_VERSION + 1))
    with pytest.raises(shards.VersionMismatch):
        shards.read_shard(newer)


def test_load_drops_columns_only_some_shards_carry(table, tmp_path):
    a = write(table, tmp_path / "a.arrow")
    b = write(table.drop_columns(["search_rollout"]), tmp_path / "b.arrow")
    t = finetune.load([a, b], [])
    assert t.num_rows == 2 * table.num_rows and "search_rollout" not in t.column_names


def test_data_mix_counts_protocol_and_leaf(table):
    plain = table.drop_columns([c for c in ("search_leaf_evaluation",) if c in table.column_names])
    leaf = pa.array(np.where(np.arange(table.num_rows) % 2, 2, 0).astype(np.int32))
    with_leaf = plain.append_column("search_leaf_evaluation", leaf)
    mix = finetune.data_mix([plain, with_leaf, with_leaf])
    p = shards.metadata(table)["protocol_version"]
    assert mix == {f"protocol {p} / leaf -": 1, f"protocol {p} / leaf 2": 2}


def test_batch_targets(table):
    rows = np.arange(table.num_rows)
    b = finetune.batch(table, rows)
    y = b["targets"]
    assert y["visit_ratio"].shape == b["inputs"]["cand_mask"].shape
    assert (y["policy_mask"] == shards.policy_target_rows(table)).all()
    assert not finetune.batch(table, rows, value_only=True)["targets"]["policy_mask"].any()
    g = table.column("global").combine_chunks().values.to_numpy().reshape(table.num_rows, -1)
    assert (y["turn"] == np.rint(g[:, 0] * 40)).all()           # Encoder: min(40, turn) / 40
    assert (y["turn"] >= 0).all() and (y["turn"] <= table.column("turns").to_numpy()).all()


def test_auc():
    assert finetune.auc(np.array([0.1, 0.4, 0.35, 0.8]), np.array([0, 0, 1, 1])) == 0.75
    assert finetune.auc(np.array([0.5, 0.5]), np.array([0, 1])) == 0.5
    assert np.isnan(finetune.auc(np.array([0.5]), np.array([1])))
