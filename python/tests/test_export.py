"""ONNX export: the contract (names, dtypes, dynamic axes, metadata, no privileged input) and a round trip through
ONNX Runtime compared with PyTorch on the sample shard."""

import numpy as np
import pytest

torch = pytest.importorskip("torch")
onnx = pytest.importorskip("onnx")
ort = pytest.importorskip("onnxruntime")

from magicdosa import shards, versions  # noqa: E402
from magicdosa.export import OUTPUTS, export_onnx  # noqa: E402
from magicdosa.model import PlayNet, to_tensors  # noqa: E402


@pytest.fixture(scope="module")
def exported(table, config, tmp_path_factory):
    net = PlayNet(config).eval()
    example = shards.training_batch(table, [0, 1])["inputs"]
    names = {g: shards.feature_names(table, g) for g in ("global", "token", "candidate")}
    path = export_onnx(net, example, tmp_path_factory.mktemp("onnx") / "play.onnx", "test-0", names)
    return net, path


def test_contract(exported):
    _, path = exported
    model = onnx.load(str(path))
    inputs = [i.name for i in model.graph.input]
    assert inputs == list(shards.MODEL_INPUTS)
    assert not any(n.startswith("priv_") for n in inputs)
    assert [o.name for o in model.graph.output] == list(OUTPUTS)
    meta = {p.key: p.value for p in model.metadata_props}
    assert meta["encoder_version"] == str(versions.ENCODER_VERSION)
    assert meta["protocol_version"] == str(versions.PROTOCOL_VERSION)
    assert meta["vocab_version"] == str(versions.VOCAB_VERSION)
    assert meta["model_version"] == "test-0"
    dims = {i.name: [d.dim_param or d.dim_value for d in i.type.tensor_type.shape.dim] for i in model.graph.input}
    assert dims["token_feat"][:2] == ["batch", "tokens"]
    assert dims["cand_mask"] == ["batch", "candidates"]
    assert dims["rel_kind"] == ["batch", "relations"] and dims["deck_card"] == ["batch", "deck"]
    assert dims["global"][0] == "batch" and isinstance(dims["global"][1], int)
    out = {o.name: [d.dim_param or d.dim_value for d in o.type.tensor_type.shape.dim] for o in model.graph.output}
    assert out == {"policy_logits": ["batch", "candidates"], "value": ["batch"]}
    types = {i.name: i.type.tensor_type.elem_type for i in model.graph.input}
    assert types["token_card"] == onnx.TensorProto.INT64 and types["token_feat"] == onnx.TensorProto.FLOAT
    assert types["cand_mask"] == onnx.TensorProto.BOOL


def test_round_trip_matches_pytorch(exported, table):
    net, path = exported
    sess = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    # all rows: another batch size and other padded widths than the export example
    x = shards.training_batch(table)["inputs"]
    # SYNTHETIC relation (the sample has none): token 0 targets token 1, so the relation bias path is compared too
    x["rel_src"][0, 0], x["rel_dst"][0, 0], x["rel_kind"][0, 0], x["rel_mask"][0, 0] = 0, 1, 4, True
    logits, value = sess.run(list(OUTPUTS), {k: x[k] for k in shards.MODEL_INPUTS})
    with torch.no_grad():
        tl, tv, _ = net(to_tensors(x))
    mask = x["cand_mask"]
    np.testing.assert_allclose(logits[mask], tl.numpy()[mask], rtol=1e-4, atol=1e-4)
    np.testing.assert_allclose(value, tv.numpy(), rtol=1e-4, atol=1e-5)
    assert logits.dtype == np.float32 and value.dtype == np.float32


def test_single_row(exported, table):
    """agent-core runs one decision at a time: batch 1, one relation slot."""
    net, path = exported
    sess = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    x = shards.training_batch(table, [3])["inputs"]
    logits, value = sess.run(list(OUTPUTS), {k: x[k] for k in shards.MODEL_INPUTS})
    with torch.no_grad():
        tl, tv, _ = net(to_tensors(x))
    np.testing.assert_allclose(logits, tl.numpy(), rtol=1e-4, atol=1e-4)
    np.testing.assert_allclose(value, tv.numpy(), rtol=1e-4, atol=1e-5)
