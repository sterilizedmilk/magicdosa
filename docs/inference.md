# Network inference in agent-core (phase 3, 2026-11-26)

agent-core runs the play network with ONNX Runtime on the CPU (design: 학습·평가 "추론 위치"). The ONNX contract
(inputs, padding, outputs, metadata) is Python's, in `python/magicdosa/export.py` and docs/python-training.md
"ONNX contract".

## Pieces (`magicdosa.agent.model`)

- `Evaluator`: one decision step in, policy priors over the step's candidates (softmax of `policy_logits`) and the
  deciding seat's value (P(win)) out. A step is the request's candidates still open (indices, -1 for "done") and the
  candidates chosen in earlier steps.
- `OnnxEvaluator`: the network on ONNX Runtime, batch 1, one intra-op and one inter-op thread (parallelism is by
  process, and one thread keeps answers fixed). Tensors are built from `Encoder.encode` and `Encoder.step`, the same
  step builder the shards use (`ShardWriter` now calls it; shards written before and after are byte-identical on
  three fin3-layer games). Padding as `shards.make_batch`: an empty list is one masked slot, references -1,
  vocabulary indices 0, masks false.
- Versions: the model's `metadata_props` must carry agent-core's `model_version` (`AgentCore.MODEL_VERSION` = 1,
  Python's `versions.MODEL_VERSION`), `encoder_version`, `vocab_version` and input order, and the encoder's feature
  names when present. Anything else is refused (`ModelMismatch`). `protocol_version` is not checked: the protocol
  does not shape the network's inputs (gen-0 was exported at protocol 13 and runs under protocol 14).
- `LeafNetwork`: the search's optional leaf network, `-Dmagicdosa.search.leafModel=<model.onnx>`, loaded once per
  JVM. Unset (the default) the search plays its simulations out as before.

## ONNX Runtime version

The dependency is 1.20.0, not the Python side's 1.31.1. ONNX Runtime 1.21 and later need a newer `msvcp140.dll`
than the one the JDK loads at startup (Corretto 17 ships 14.16, the jdk20 here 14.31; the system has 14.51):
1.31.1 crashes inside `msvcp140.dll` when the environment is created, 1.21.1 and 1.22.0 exit at load. 1.17.3 cannot
read the model (IR version 10, it reads up to 9). A JDK that ships a 14.40+ runtime would allow a newer release.

## Search with a leaf network (an option; the phase-3 gate stays network-free)

`ForgeSearchGame.withNetworkLeaf`: a simulation stops at the decision where the walk leaves the tree (the expanded
node), and the network values that request; the value is turned to the root seat's (1 - v for the other seat). A
simulation that ends or reaches the horizon while still in the tree is valued as before. The priors are not used by
the search yet (it keeps the uniform prior). Since protocol 15 `SearchRun` records the leaf network by the model's
file name and the first 16 hex digits of its SHA-256 (`leaf_model`, `leaf_model_sha256`; empty without one), next to
`horizon` and `leaf_evaluation`, which value the simulations that end in the tree.

## Checks with the gen-0 model (`E:/magicdosa-runs/gen0/run1/gen0-run1.onnx`)

- `NetworkCheck` (agent-core test sources): loads the model, evaluates every request of three fin3-layer games
  (2437), evaluates them again: all answers equal. Values from 0.04 to 0.97, mean 0.58.
- Against Python's ONNX Runtime 1.31.1 on the shards of the same three games (step 0 rows): values differ by at most
  1.0e-6, priors by at most 1.3e-6 (2408 steps with the same candidates; 29 first steps of the shards also offer
  "done", which `Evaluator.evaluate(request)` does not).
- Refusal: `OnnxEvaluatorTest` (model, encoder, vocabulary version, input order, feature names).
- Search with the leaf network on R30 (`SearchReplay`, 12 per candidate x1): two runs print the same 37 searches.
  Without a model the searches equal the plain rollout's (47 of 47).

## Cost (CPU shared with other sessions' games, load near 100%: compare within a run)

| | ms |
| --- | --- |
| Encoding one request (`Encoder.encode`) | 0.09 |
| Encode + inference, batch 1, one thread (`NetworkCheck`) | 2.7-3.5 |
| Network leaf inside search (`search.leafNetwork`, 1257 leaves) | 1.9 per leaf |
| Rollout per simulation, plain Forge AI (`search.simulation` minus leaf value) | about 68 |
| Forge's `GameStateEvaluator` at the leaf (two copies, two combat simulations) | 3.7 |

On the 24 roots both searched, a simulation costs 84.4 ms with the plain rollout and 30.5 ms with the network leaf
(-64%). What is left is mostly the copy (about 12 ms per simulation) and the in-tree decisions up to the leaf.
Python measured 0.7 ms per row on one thread for the same model; the gap to the in-search 1.9 ms is not measured
apart (ONNX Runtime 1.20 against 1.31, a loaded CPU, Java arrays copied into tensors per call).
