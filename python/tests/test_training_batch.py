"""The training view of a shard: inputs, targets and privileged labels kept apart; row filters; vocabulary sizes."""

import numpy as np
import pyarrow as pa
import pytest

from magicdosa import shards, versions

from conftest import SAMPLE, VOCAB


def with_search(table: pa.Table) -> pa.Table:
    """SYNTHETIC: the sample shard with made-up visit ratios (uniform over each step's candidates), as if a search had
    run. Only to exercise the ExIt path until a shard with real search results exists."""
    visits = [[1.0 / len(c)] * len(c) for c in table.column("cand_kind").to_pylist()]
    i = table.schema.get_field_index("visit_ratio")
    return table.set_column(i, table.schema.field(i), pa.array(visits, type=table.schema.field(i).type))


def test_split_keeps_privileged_out_of_inputs(table):
    b = shards.training_batch(table)
    assert set(b["inputs"]) == set(shards.MODEL_INPUTS)
    assert not any(k.startswith("priv_") for k in b["inputs"])
    assert set(b["privileged"]) == set(shards.PRIVILEGED)
    for k, v in b["inputs"].items():
        assert v.dtype in (np.int64, np.float32, np.bool_), k
        assert v.shape[0] == table.num_rows


def test_random_rows_have_no_policy_target_but_a_value_target(table):
    b = shards.training_batch(table)
    assert not b["targets"]["policy_mask"].any()          # phase-1 random policy: no search, no ExIt target
    dtype = table.column("decision_type").to_numpy()
    assert (b["targets"]["value_mask"] == (dtype != shards.DECISION_SIDEBOARD)).all()


def test_policy_targets_follow_decider_and_decision_type(table):
    t = with_search(table)
    mask = shards.policy_target_rows(t)
    dtype = t.column("decision_type").to_numpy()
    assert (mask == ~np.isin(dtype, shards.NOT_PLAY_POLICY)).all()   # the mulligan row (9) is not a policy sample
    assert mask.any()
    i = t.schema.get_field_index("decider")
    delegated = t.set_column(i, t.schema.field(i), pa.array(np.full(t.num_rows, 2, dtype=np.int32)))
    assert not shards.policy_target_rows(delegated).any()


def test_read_shards_concatenates(table):
    both = shards.read_shards([SAMPLE, SAMPLE])
    assert both.num_rows == 2 * table.num_rows
    assert shards.metadata(both)["encoder_version"] == str(versions.ENCODER_VERSION)


def test_vocab_sizes(table, tmp_path):
    sizes = shards.vocab_sizes(VOCAB)
    b = shards.make_batch(table)
    assert b["token_card"].max() < sizes["card"] and b["cand_key"].max() < sizes["ability"]
    other = tmp_path / "vocab.tsv"
    other.write_text(VOCAB.read_text(encoding="utf-8").replace("#version", "#version 99 was", 1), encoding="utf-8")
    with pytest.raises(shards.VersionMismatch):
        shards.vocab_sizes(other)
