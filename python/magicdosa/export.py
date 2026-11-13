"""ONNX export of the play module for agent-core's ONNX Runtime (design 학습·평가 "추론 위치", "학습 데이터 경로").

Contract (also in docs/python-training.md):
- inputs: shards.MODEL_INPUTS, in that order; integer tensors int64, feature tensors float32, masks bool.
  Dynamic axes: batch, tokens, relations, deck, candidates. No privileged input exists in the graph.
- outputs: `policy_logits` float32 [batch, candidates] (padded candidates = -1e9; softmax over a row gives the policy
  over that step's candidates), `value` float32 [batch] (P(win) of the deciding seat, observation only).
- metadata_props: protocol_version, encoder_version, vocab_version (versions.py), model_version (given by the
  caller), feature names of the shard the model was built for, input order.
The training-only critic is never exported.
"""

from __future__ import annotations

import warnings
from pathlib import Path
from typing import Dict, Mapping, Sequence

import numpy as np
import torch

from . import versions
from .model import ExportWrapper, PlayNet
from .shards import MODEL_INPUTS

OUTPUTS = ("policy_logits", "value")

# Variable-length axis of each input (axis 0 is always the batch). Every axis has minimum size 1.
DYNAMIC: Dict[str, Dict[int, str]] = {}
for _name in MODEL_INPUTS:
    _axes = {0: "batch"}
    if _name.startswith("token_"):
        _axes[1] = "tokens"
    elif _name.startswith("rel_"):
        _axes[1] = "relations"
    elif _name.startswith("deck_"):
        _axes[1] = "deck"
    elif _name.startswith("cand_"):
        _axes[1] = "candidates"
    DYNAMIC[_name] = _axes
OUTPUT_AXES = {"policy_logits": {0: "batch", 1: "candidates"}, "value": {0: "batch"}}


def export_onnx(net: PlayNet, example: Mapping[str, np.ndarray], path: Path | str, model_version: str,
                feature_names: Mapping[str, Sequence[str]] | None = None, opset: int = 18) -> Path:
    """Writes `net` as ONNX to `path`, traced on `example` (a shards.training_batch(...)['inputs'])."""
    import onnx

    path = Path(path)
    wrapper = ExportWrapper(net).eval()
    example = _widen(example)
    args = tuple(torch.from_numpy(example[k]) for k in MODEL_INPUTS)
    dims = {name: torch.export.Dim(name, min=1) for name in ("batch", "tokens", "relations", "deck", "candidates")}
    shapes = tuple({axis: dims[name] for axis, name in DYNAMIC[k].items()} for k in MODEL_INPUTS)
    with warnings.catch_warnings():
        # the exporter notes that each shared axis name is already taken by the same axis; harmless
        warnings.filterwarnings("ignore", message=".*will not be used, since it shares the same shape constraints")
        program = torch.onnx.export(wrapper, args, input_names=list(MODEL_INPUTS), output_names=list(OUTPUTS),
                                    dynamic_shapes=(shapes,), opset_version=opset, dynamo=True, verbose=False)
    model = program.model_proto
    meta = {"protocol_version": str(versions.PROTOCOL_VERSION), "encoder_version": str(versions.ENCODER_VERSION),
            "vocab_version": str(versions.VOCAB_VERSION), "model_version": model_version,
            "inputs": ",".join(MODEL_INPUTS)}
    for group, names in (feature_names or {}).items():
        meta[group + "_features"] = ",".join(names)
    for k, v in meta.items():
        p = model.metadata_props.add()
        p.key, p.value = k, v
    onnx.checker.check_model(model)
    onnx.save(model, str(path))
    return path


def _widen(example: Mapping[str, np.ndarray]) -> Dict[str, np.ndarray]:
    """The export example with every variable axis at least 2 wide (and 2 rows), filled with masked padding.
    torch.export fixes an axis whose example size is 0 or 1, and the sample shard has a single (padded) relation
    slot. Padding values follow shards.make_batch: references -1, masks False, everything else 0."""
    out: Dict[str, np.ndarray] = {}
    for k in MODEL_INPUTS:
        v = example[k]
        if v.shape[0] < 2:
            v = np.concatenate([v, v[:1]], axis=0)
        if 1 in DYNAMIC[k] and v.shape[1] < 2:
            fill = -1 if k in ("rel_src", "rel_dst", "cand_ref0", "cand_ref1") else 0
            pad = np.full((v.shape[0], 2 - v.shape[1]) + v.shape[2:], fill, dtype=v.dtype)
            v = np.concatenate([v, pad], axis=1)
        out[k] = v
    return out
