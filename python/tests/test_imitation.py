"""Generation-0 imitation loader: source tag, targets, split by match, streaming batches."""

from pathlib import Path

import numpy as np
import pyarrow as pa
import pyarrow.ipc as ipc
import pytest

from magicdosa import imitation, shards

from conftest import SAMPLE


def write(table: pa.Table, path: Path, **meta) -> Path:
    m = dict(table.schema.metadata)
    m.update({k.encode(): v.encode() for k, v in meta.items()})
    with pa.OSFile(str(path), "wb") as sink:
        with ipc.new_file(sink, table.schema.with_metadata(m)) as w:
            w.write_table(table)
    return path


@pytest.fixture()
def run(table, tmp_path):
    """A fake run: 3 matches of 2 games each, all copies of the sample shard tagged as imitation data."""
    d = tmp_path / "fin9-layer"
    d.mkdir()
    for m in range(3):
        for g in (1, 2):
            write(table, d / f"IzzetProwess-vs-EsperBlink-m{m}-g{g}.arrow", source="imitation:forge-ai")
    return d


def test_refuses_untagged_shards():
    with pytest.raises(imitation.NotImitation):
        imitation.read_imitation_shard(SAMPLE)


def test_targets_are_chosen_candidates(table):
    rows = np.arange(table.num_rows)
    y = imitation.imitation_targets(table, rows)
    dtype = table.column("decision_type").to_numpy()
    assert (y["chosen"] == table.column("chosen").to_numpy()).all()
    assert (y["policy_mask"] == ~np.isin(dtype, shards.NOT_PLAY_POLICY)).all()
    assert (y["chosen"] < y["n_candidates"]).all()
    # imitation rows never become search policy targets (their decider is not the model)
    assert not shards.policy_target_rows(table).any()


def test_split_keeps_matches_together(run):
    s = imitation.split_games([run], fraction=0.5)
    for part in (s.train, s.held_out):
        matches = {imitation.match_key(p) for p in part}
        for m in matches:
            assert sum(imitation.match_key(p) == m for p in s.train + s.held_out) == 2
            assert all(imitation.match_key(p) != m for p in (s.held_out if part is s.train else s.train))
    assert s == imitation.split_games([run], fraction=0.5)          # deterministic
    p = imitation.split_games([run], 0.05, "fin9-layer", "IzzetProwess-vs-EsperBlink")
    assert len(p.held_out_pairing) == 6 and not p.train and not p.held_out


@pytest.mark.parametrize("shuffle", [True, False])
def test_stream_covers_every_row_once(run, table, shuffle):
    paths = sorted(run.glob("*.arrow"))
    seen = 0
    for b in imitation.stream(paths, 3, np.random.default_rng(0), games_per_chunk=4, shuffle=shuffle):
        assert set(b["inputs"]) == set(shards.MODEL_INPUTS)
        assert not any(k.startswith("priv_") for k in b["inputs"])
        seen += len(b["targets"]["chosen"])
    assert seen == len(paths) * table.num_rows
