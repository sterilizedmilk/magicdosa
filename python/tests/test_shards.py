"""Reading agent-core's shards: versions, batch shapes, padding and the policy-row filter.

tests/data/sample-shard.arrow is the first rows of a real shard (IzzetProwess random policy vs EsperBlink Forge AI);
rebuild it from a new run when the protocol, encoder or vocabulary version changes.
"""

from pathlib import Path

import numpy as np
import pyarrow as pa
import pyarrow.ipc as ipc
import pytest

from magicdosa import shards, versions

SAMPLE = Path(__file__).parent / "data" / "sample-shard.arrow"


def test_reads_and_checks_versions():
    table = shards.read_shard(SAMPLE)
    meta = shards.metadata(table)
    assert meta["encoder_version"] == str(versions.ENCODER_VERSION)
    assert table.num_rows > 0
    assert int(meta["oov"]) == 0


def test_refuses_other_versions(tmp_path):
    table = shards.read_shard(SAMPLE)
    meta = dict(table.schema.metadata)
    meta[b"encoder_version"] = b"999"
    other = tmp_path / "other.arrow"
    with pa.OSFile(str(other), "wb") as sink:
        with ipc.new_file(sink, table.schema.with_metadata(meta)) as writer:
            writer.write_table(table)
    with pytest.raises(shards.VersionMismatch):
        shards.read_shard(other)


def test_batch_shapes_and_masks():
    table = shards.read_shard(SAMPLE)
    batch = shards.make_batch(table)
    b = table.num_rows
    t_width = len(shards.feature_names(table, "token"))
    c_width = len(shards.feature_names(table, "candidate"))
    assert batch["global"].shape == (b, len(shards.feature_names(table, "global")))
    assert batch["token_feat"].shape[0] == b and batch["token_feat"].shape[2] == t_width
    assert batch["cand_feat"].shape[2] == c_width
    # every row has tokens (the two players at least) and candidates; the chosen one is a real candidate
    assert batch["token_mask"].sum(axis=1).min() >= 2
    counts = batch["cand_mask"].sum(axis=1)
    assert (counts >= 1).all()
    assert (batch["chosen"] < counts).all()
    # padding: references -1, vocabulary indices 0
    assert (batch["cand_ref0"][~batch["cand_mask"]] == -1).all()
    assert (batch["token_card"][~batch["token_mask"]] == 0).all()
    # references point inside the row's tokens
    refs = batch["cand_ref0"][batch["cand_mask"]]
    assert refs.max() < batch["token_feat"].shape[1]


def test_random_rows_are_not_policy_labels():
    table = shards.read_shard(SAMPLE)
    # phase 1: the random policy has a uniform prior and no search
    assert not shards.policy_rows(table).any()
    assert (table.column("prior").to_numpy() == shards.PRIOR_UNIFORM).all()


def test_privileged_labels_present():
    table = shards.read_shard(SAMPLE)
    batch = shards.make_batch(table)
    # training mode: the library order of both seats is recorded at every decision
    assert batch["priv_my_library_mask"].any()
    assert np.isin(batch["result"], [0.0, 1.0]).all()


def _with(table, **columns):
    for name, values in columns.items():
        i = table.column_names.index(name)
        table = table.set_column(i, name, pa.array(values, type=table.schema.field(name).type))
    return table


def test_value_targets_draws_half_and_fallback_games_dropped():
    table = shards.read_shard(SAMPLE)
    n = table.num_rows
    draw = np.zeros(n, bool)
    draw[:2] = True
    game = np.array(["g1"] * (n // 2) + ["g2"] * (n - n // 2))
    decider = table.column("decider").to_numpy().copy()
    decider[-1] = shards.DECIDER_UNINTENDED_FALLBACK
    t = _with(table, is_draw=draw, game_id=game, decider=decider)
    y = shards.value_targets(t)
    assert (y[:2] == 0.5).all() and np.isin(y[2:], [0.0, 1.0]).all()
    rows = shards.value_target_rows(t)
    assert not rows[game == "g2"].any()                     # the whole fallback game, not only its row
    dtype = t.column("decision_type").to_numpy()
    assert (rows[game == "g1"] == ~np.isin(dtype[game == "g1"], shards.NOT_PLAY_VALUE)).all()
    assert rows[:2].any() or np.isin(dtype[:2], shards.NOT_PLAY_VALUE).all()   # draws are value rows
