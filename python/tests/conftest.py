"""Shared fixtures: the sample shard, agent-core's vocabulary and the placeholder model's sizes."""

from pathlib import Path

import pytest

from magicdosa import shards

DATA = Path(__file__).parent / "data"
SAMPLE = DATA / "sample-shard.arrow"
VOCAB = (Path(__file__).resolve().parents[2] / "agent-core" / "src" / "main" / "resources" / "magicdosa" / "agent"
         / "encoder" / "vocab.tsv")


@pytest.fixture(scope="session")
def table():
    return shards.read_shard(SAMPLE)


@pytest.fixture(scope="session")
def config(table):
    torch = pytest.importorskip("torch")
    from magicdosa.model import ModelConfig
    torch.manual_seed(0)
    return ModelConfig(vocab=shards.vocab_sizes(VOCAB),
                       global_width=len(shards.feature_names(table, "global")),
                       token_width=len(shards.feature_names(table, "token")),
                       candidate_width=len(shards.feature_names(table, "candidate")))
