# Python training skeleton

Implementation notes for `python/magicdosa` (2026-11-13). It is an early skeleton: it reads agent-core's shards,
defines a placeholder play network with a training-only critic, exports the network to ONNX, and takes one optimizer
step in a test. It does not run training. Everything the design leaves open is a placeholder, listed below, and
the matching design questions are at the end.

Design sources: 학습·평가 tab ("결정 모듈 구조", "학습 전략과 보상", "폴백 검증"), 관측·행동 tab ("관측 스키마",
"행동 스키마"), and `docs/encoder.md` for the shard layout.

## Pieces

| File | Role |
| --- | --- |
| `magicdosa/shards.py` | Existing `read_shard`, `make_batch`, `policy_rows`. New: `read_shards` (several games, same feature names), `training_batch` (inputs / targets / privileged kept apart), `policy_target_rows`, `value_target_rows`, `vocab_sizes` (embedding sizes from agent-core's `vocab.tsv`, version-checked), `MODEL_INPUTS`, `PRIVILEGED` |
| `magicdosa/model.py` | `ModelConfig`, `PlayNet` (shared encoder + one pointer head per decision type + observation-only value head), `Critic` (training only), `ExportWrapper` |
| `magicdosa/export.py` | `export_onnx`: the ONNX contract below |
| `magicdosa/train.py` | `losses`, `train_step`: ExIt policy loss, value loss, critic loss |
| `tests/` | `test_training_batch.py`, `test_model.py`, `test_export.py` (ONNX Runtime round trip), `test_train.py`; fixtures in `conftest.py` |

Python never computes features. It reads agent-core's tensors and pads, masks and splits them. It reads `vocab.tsv`
only to count entries per kind, so that the embedding tables are big enough.

## Environment

- Dependencies: `train` extra = `torch`, `onnx`, `onnxscript` (the `torch.export` ONNX exporter), `onnxruntime`
  (round-trip test). `onnx` was already listed. The other three are new.
- onnxruntime has no wheel for Python 3.15, the only Python this PC had. Python 3.14 was installed with the Python
  install manager (`py install 3.14`) and the training venv is `python/.venv-train` (ignored by `python/.gitignore`):

  ```powershell
  cd python
  py -V:3.14 -m venv .venv-train
  .venv-train\Scripts\python -m pip install torch --index-url https://download.pytorch.org/whl/cpu
  .venv-train\Scripts\python -m pip install -e ".[train,dev]"
  .venv-train\Scripts\python -m pytest -q      # 23 tests, about 20 s on CPU (the ONNX export is most of it)
  ```

- Without torch (for example the system Python 3.15), `python -m pytest -q` runs the shard tests and skips the
  model, export and training tests.
- Versions used: torch 2.15.0+cpu, onnx 1.23.2, onnxscript 0.7.2, onnxruntime 1.31.1, pyarrow 26.0.1, numpy 2.5.4.

## Shard schema relied on

Written by `agent-core/.../encoder/ShardWriter` (encoder 4, vocabulary 3, protocol 10). One row per decision step.

| Column(s) | Type | Use here |
| --- | --- | --- |
| metadata `protocol_version`, `encoder_version`, `vocab_version` | string | must equal `versions.py`, else `VersionMismatch` |
| metadata `global_features`, `token_features`, `candidate_features` | comma list | input widths; `read_shards` refuses mixed names |
| `global` | list<f32> | `[B, G]` |
| `token_kind`, `token_card`, `token_key` / `token_feat` | list<i32> / list<f32> | `[B, T]` / `[B, T, F_tok]`, mask `token_mask` |
| `rel_src`, `rel_dst`, `rel_kind` | list<i32> | `[B, R]`, positions in the row's token list, mask `rel_mask` |
| `deck_card`, `deck_count`, `deck_group` | list<i32> | `[B, D]`, mask `deck_mask` |
| `cand_kind`, `cand_ref0`, `cand_ref1`, `cand_card`, `cand_key`, `cand_string` / `cand_feat` | list<i32> / list<f32> | `[B, C]` / `[B, C, F_cand]`, mask `cand_mask`; refs are token positions, -1 none |
| `decision_type` | i32 | selects the pointer head; filters policy and value rows |
| `chosen` | i32 | index into the step's candidates (kept as a target, not used by a loss yet) |
| `visit_ratio` | list<f32> | ExIt policy target, parallel to the step's candidates; empty when no search ran |
| `decider` | i32 | only `DECIDER_MODEL` (1) rows are policy samples |
| `result`, `is_draw` | f32, bool | value / critic target (1 win, 0 loss or draw) |
| `priv_opp_hand`, `priv_my_library`, `priv_opp_library` | list<i32> | card indices of hidden cards; critic input only (belief labels later) |

Padding (from `make_batch`): vocabulary indices 0, references -1, features 0, masks False. Every padded axis is at
least 1 wide, so a row with no relations still has one masked relation slot.

`training_batch` returns `inputs` (exactly `MODEL_INPUTS`, int64 / float32 / bool), `targets` (`chosen`,
`visit_ratio`, `policy_mask`, `result`, `value_mask`) and `privileged` (the `priv_*` lists and their masks). Only
`inputs` reaches `PlayNet`.

Row filters:

- policy sample: `decider == MODEL`, `visit_ratio` not empty, and the decision type is not mulligan (9), bottom
  cards (10), play/draw (11) or sideboard (12). The first three are computed from the value net, the last belongs to
  the match module (학습·평가 "결정 모듈 구조"). Delegated and fallback rows are never policy samples
  (폴백 검증 "학습 데이터").
- value sample: every row except sideboard rows (match result, not game result: "보상 분리"), and except draws
  (placeholder).

The phase-1 sample shard has no search, so it has no policy samples. Tests that need them add synthetic uniform
visit ratios (`with_search` in `tests/test_training_batch.py`). The sample has no relations either, so the export
round trip adds one synthetic relation.

## Model

- **Encoder:** one sequence = global token + entity tokens, a pre-norm Transformer. An entity token is card
  embedding + ability-key embedding + token-kind embedding + linear(features). Deck tokens (card embedding + group
  embedding + linear(count)) are mean-pooled, passed through an MLP and added to the global token. They are not
  attended over: there are about 100 of them per row, which made the sequence 3 to 4 times longer on CPU. Relations
  are an additive per-head attention bias from source to destination by relation kind. The pooled observation is
  the global token's output. Training uses fused attention (`scaled_dot_product_attention`); during ONNX export the
  same math is written out, because the exporter cannot decompose the fused kernel with dynamic shapes.
- **Policy:** a candidate is kind + card + key + string embeddings + linear(features) + projections of the
  referenced tokens' encodings. Each of the 15 decision types has its own scoring MLP over
  [candidate, pooled + decision-type embedding]. The row's decision type picks the head. Padded candidates get logit
  -1e9.
- **Value:** MLP on the pooled observation, sigmoid = P(win) of the deciding seat. Observation only.
- **Critic (training only, never exported):** its own card embedding table over the opponent's hand and both
  libraries (mean-pooled), plus the pooled observation with the gradient stopped (`detach`). So no hidden-information
  gradient reaches the shared encoder. A test checks this.

## Losses (`train.py`)

- policy: cross-entropy of the visit ratios against `log_softmax(policy_logits)`, averaged over policy samples;
- value: binary cross-entropy of `value` against `result`, over value samples;
- critic: the same against the critic's output;
- total = 1 x policy + 1 x value + 1 x critic.

## ONNX contract

`export_onnx(net, example_inputs, path, model_name, feature_names)` uses the `torch.export` exporter at opset 18.
The graph contains `PlayNet` only. It has no privileged input, so an inference-mode model cannot take hidden
information.

Inputs, in this order (`shards.MODEL_INPUTS`):

| Name | dtype | Shape |
| --- | --- | --- |
| `global` | float32 | [batch, G] |
| `token_kind`, `token_card`, `token_key` | int64 | [batch, tokens] |
| `token_feat` | float32 | [batch, tokens, F_tok] |
| `token_mask` | bool | [batch, tokens] |
| `rel_src`, `rel_dst`, `rel_kind` | int64 | [batch, relations] |
| `rel_mask` | bool | [batch, relations] |
| `deck_card`, `deck_count`, `deck_group` | int64 | [batch, deck] |
| `deck_mask` | bool | [batch, deck] |
| `decision_type` | int64 | [batch] |
| `cand_kind`, `cand_ref0`, `cand_ref1`, `cand_card`, `cand_key`, `cand_string` | int64 | [batch, candidates] |
| `cand_feat` | float32 | [batch, candidates, F_cand] |
| `cand_mask` | bool | [batch, candidates] |

G, F_tok and F_cand are fixed by the encoder version (46, 70 and 15 at encoder 5). `batch`, `tokens`, `relations`,
`deck` and `candidates` are dynamic, minimum 1. A row with none of something passes one masked slot, as
`make_batch` does. agent-core has to build the same padding: references -1, vocabulary indices 0, masks False.

Outputs:

| Name | dtype | Shape | Meaning |
| --- | --- | --- | --- |
| `policy_logits` | float32 | [batch, candidates] | softmax over a row = policy over the step's candidates (padding -1e9) |
| `value` | float32 | [batch] | P(win) of the deciding seat, observation only |

`metadata_props`: `protocol_version`, `encoder_version`, `vocab_version`, `model_version` (from `versions.py`;
`model_version` is the network structure, raised when a checkpoint of the old structure no longer loads or the ONNX
contract changes), `model_name` (the trained instance, e.g. `gen0-run1`), `inputs` (input order), `global_features` / `token_features` / `candidate_features` (when
given). agent-core can compare these with its own versions before it loads a model.

Checked by `tests/test_export.py`: names, order, dtypes, dynamic axis names, metadata, and ONNX Runtime output equal
to PyTorch (rtol 1e-4) on the whole sample shard (another batch size than the export example) and on one row.

Export detail: `torch.export` fixes an axis whose example size is 0 or 1. So `export_onnx` widens the example to at
least 2 rows and 2 slots per variable axis, using masked padding (`_widen`).

## Generation 0: Forge AI imitation pretraining

Planning decision 2026-11-25 (decision 3): generation 0's encoder, policy and value nets are pretrained on Forge AI
imitation data. From generation 1 on, policy targets are search visit ratios only and value targets game results
only; imitation rows never enter the replay buffer.

- Data: `E:\magicdosa-runs\imitation-p14\fin3-layer` and `fin4-layer` (protocol 14; runs 1 and 2 read the
  protocol-13 conversion in `imitation\`), 2273 games each (decision-layer runs, encoder 5,
  zstd Arrow, metadata `source=imitation:forge-ai`, the agent's seat only). About 11 KB per decision step
  decompressed, so games are streamed from disk (`imitation.stream`), 48 games per chunk, rows bucketed by token
  count so batches pad little.
- `magicdosa/imitation.py`: refuses shards without the `imitation:` source tag. Policy target = the step's chosen
  candidate (one-hot), on decision types the play policy learns (not mulligan, bottom cards, play/draw, sideboard).
  Value target = game result of the deciding seat (`shards.value_target_rows`: no sideboard rows, no draws).
  `shards.policy_target_rows` stays 0 for these rows (their decider is a delegation).
- Split by match (crc32 of `<run>/<match>`): 5% of matches held out (201 games), plus every game of
  `fin4-layer` IzzetProwess-vs-EsperBlink (151 games) as a second held-out set. 4194 training games, 1.35M rows.
- `python -m magicdosa.pretrain --runs <dirs> --out <dir> --threads 4 --epochs N [--d-model --layers]`: AdamW,
  one-cycle LR (max 1e-3), batch 256, gradient clip 1, losses policy CE + value BCE (1/1). Writes `log.jsonl`,
  `checkpoint.pt`, `split.json`, `metrics.json`. Held-out evaluation: top-1 agreement with Forge AI (overall, on
  steps with 2+ candidates, per decision type), value Brier score and accuracy, and the base-rate Brier score.
- `python -m magicdosa.export --checkpoint <pt> --shard <any encoder-5 shard> --out <onnx> --name <model name>`.

Run 1 (2026-11-26, `E:\magicdosa-runs\gen0\run1`): d_model 64, 2 layers, 256k parameters, 1 epoch (5261 steps),
4 threads, 1852 s of training on a CPU shared with game runs.

| Held-out set | Top-1 | Top-1, 2+ candidates | Value Brier (base rate) | Value accuracy |
| --- | --- | --- | --- | --- |
| 5% of matches (201 games, 63.6k policy rows) | 88.3% | 88.2% | 0.151 (0.250) | 78.7% |
| fin4 IzzetProwess-vs-EsperBlink (151 games, 30.1k rows) | 89.4% | 89.2% | 0.201 (0.247) | 67.2% |

Top-1 by decision type, 5% set: priority 91.3% (54.1k rows; always picking the most common candidate kind gives
81.7%), target 92.8%, declare attackers 81.5%, declare blockers 63.1%, choose option 71.3%, choose cards 53.1%,
mana payment 68.9%, trigger order 91.3%, damage assignment 91.3% (23 rows).

Run 2 (2026-11-26, `E:\magicdosa-runs\gen0\run2`): d_model 128, 3 layers, 2 epochs (10523 steps), 4 threads,
6521 s of training (protocol-13 shards, same split).

| Held-out set | Top-1 | Top-1, 2+ candidates | Value Brier (base rate) | Value accuracy |
| --- | --- | --- | --- | --- |
| 5% of matches | 89.6% | 89.4% | 0.133 (0.250) | 81.6% |
| fin4 IzzetProwess-vs-EsperBlink | 89.8% | 89.6% | 0.165 (0.247) | 75.3% |

Top-1 by decision type, 5% set (run 1 in brackets): priority 92.4% (91.3), target 93.7% (92.8), declare attackers
84.1% (81.5), declare blockers 67.3% (63.1), choose option 71.8% (71.3), choose cards 55.8% (53.1), mana payment
72.0% (68.9), trigger order 91.3% (91.3), damage assignment 91.3% (91.3). The bigger net helps the value head most
(the unseen pairing's Brier 0.201 → 0.165); policy agreement moves about a point. Its export (`gen0-run2.onnx`,
4.8 MB, protocol 14 metadata, built against a protocol-14 shard) matches PyTorch to 8e-6 on a held-out game (same
argmax everywhere); one row takes 1.5 ms median on one thread.

Run 3 (2026-11-26, `E:\magicdosa-runs\gen0\run3`): the first run on the protocol-14 conversion, run 2's network,
3 epochs (15785 steps), 4 threads, 12307 s of training (CPU shared with game runs).

| Held-out set | Top-1 | Top-1, 2+ candidates | Value Brier (base rate) | Value accuracy |
| --- | --- | --- | --- | --- |
| 5% of matches | 89.8% | 89.7% | 0.142 (0.250) | 80.0% |
| fin4 IzzetProwess-vs-EsperBlink | 90.3% | 90.1% | 0.176 (0.247) | 73.9% |

Top-1 by decision type, 5% set (run 2 in brackets): priority 92.4% (92.4), target 94.5% (93.7), declare attackers
81.2% (84.1), declare blockers 64.9% (67.3), choose option 78.3% (71.8), choose cards 58.9% (55.8), mana payment
74.1% (72.0), trigger order 91.3% (91.3). A third epoch adds a few tenths of policy agreement and makes the value head
worse on both sets (Brier 0.133 → 0.142, 0.165 → 0.176): the value target is one label per game repeated over
about 300 rows, so it overfits first. Run 2 stays the better generation-0 candidate; `gen0-run3.onnx` matches
PyTorch to 2e-5.

The ONNX export (`gen0-run1.onnx`, 1.5 MB) under ONNX Runtime 1.31 (Python, CPU) matches PyTorch to 5e-6 on a
held-out game (336 rows, same argmax everywhere); one row takes 0.7 ms median on one thread.

agent-core cannot load it yet. Missing on the Java side: an ONNX Runtime Java dependency; a `Policy` that turns
`Encoder.Encoded` into the contract's tensors (int64 indices, float32 features, bool masks, batch 1, one masked slot
for an empty list) and reads `policy_logits` / `value`; a check of the ONNX `metadata_props` (protocol, encoder,
vocabulary and model versions) before a game starts, as `Agent.check` does for the encoder version today.
(Since then agent-core loads it as the search leaf: `docs/inference.md`.)

## Generation 1: search shards

`python -m magicdosa.finetune --runs <dirs> --init <checkpoint> --out <dir> [--protocols 14] [--imitation <dirs>
--imitation-games N --imitation-batch M]`.

- Data (2026-11-26): `gate3-step3-net` (1x vs 4x, network leaf gen0-run2, protocol 15, both seats' shards) and
  `gate3-step1` (1x vs Forge AI, protocol 14; `repro/` skipped, exact re-runs of `w3` games). All encoder 5 with
  identical feature names; protocol 15 only added search metadata columns, which training drops.
- **Gate seeds used for training:** gen1-b (and so every model initialised from it) was trained on `gate3-step3-net`
  (seed bases 8000-8400) and `gate3-step1`. Gate runs on those seeds (e.g. `gate3-step3-gen2`, seeds 8000-8400) are
  not clean evaluations of these models. From gen-3 on, training reads self-play shards only (seeds 2,000,000+),
  never `gate3-*` or `eval-*` folders (evaluation seeds 7,000,000+), so training and evaluation seeds stay apart.
- Policy target: visit ratios of searched model rows (every one has them). Not policy samples: intended delegations
  (5954 rows), shadow-recovery failures and failed searches (188 rows; uniform-prior choices). Their chosen action is
  never used. Value target: the deciding seat's result on every row (no sideboard, no draws).
- Split by match (both seats and both games together): train 86 matches (206 games, 305 shards, 28.7k rows, 23.8k
  policy rows), validation 12 matches (28 games; picks the epoch by policy CE + value BCE), test 12 matches (29 games).
  The holdout sets are small: AUC differences of a few hundredths are noise.

Run A (gate shards only, lr 3e-4, 8 epochs): the value head overfits from epoch 2 (train BCE 0.52 → 0.06,
validation BCE 0.79 → 2.0); the policy settles after one epoch (validation soft CE 1.047).
Run B (`E:\magicdosa-runs\gen1\b`, the one exported): plus 300 imitation-p14 games as value-only rows (128 per batch
next to 128 gate rows), lr 1e-4, 4 epochs, 1532 s on 4 threads. The value still overfits after epoch 1, more slowly
(validation BCE 0.72 → 0.94); epoch 1 is kept.

| Set | Model | Top-1 vs search | Soft CE | Value Brier (base rate) | AUC | AUC turns 1-2 / 3-4 / 5-6 / 7-8 / 9-10 |
| --- | --- | --- | --- | --- | --- | --- |
| validation | gen0-run2 | 65.7% | 2.94 | 0.245 (0.249) | 0.702 | 0.62 / 0.74 / 0.76 / 0.71 / 0.81 |
| validation | gen1-b | 60.2% | 1.08 | 0.238 | 0.716 | 0.56 / 0.74 / 0.83 / 0.75 / 0.73 |
| test | gen0-run2 | 73.7% | 2.78 | 0.272 (0.233) | 0.674 | 0.58 / 0.85 / 0.59 / 0.72 / 0.66 |
| test | gen1-b | 68.4% | 1.03 | 0.197 | 0.765 | 0.60 / 0.84 / 0.76 / 0.88 / 0.92 |

Top-1 against the most-visited candidate drops: the visit ratios of a uniform-prior search at 12 simulations per
candidate are flat, gen1 learns that spread (soft CE 2.9 → 1.0), and gen0's peaked imitation policy lands on the
argmax more often. The searches that use it as a leaf read only the value. `gen1-b.onnx` (4.8 MB, SHA-256 6fe26256…)
matches PyTorch to 9e-6 (logits) and 5e-6 (value) on two gate games.

## Generation 2: first self-play batch

Data (2026-11-27): the 1034 self-play games of `E:\magicdosa-runs\selfplay\b0000`-`b0020` (Izzet mirror, both seats
1x search, protocol 15, encoder 5; b0000 used the gen0-run2 leaf, b0001-b0020 gen1-b). Turns 1-4 were played by
sampling, so the played move is not always the most-visited one; the visit-ratio targets are unaffected. No gate
shards and no imitation rows. Split by match: train 1618 shards (167k rows, 143k policy rows), validation 208,
test 242. Run C (`E:\magicdosa-runs\gen2\c`): init gen1-b, lr 1e-4, 4 epochs, 1993 s on 4 threads. The value
overfits after epoch 1 again (validation BCE 0.60 → 0.76, train 0.47 → 0.27); epoch 1 is kept.

| Set | Model | Top-1 vs search | Soft CE | Value Brier (base rate) | AUC | AUC turns 1-2 / 3-4 / 5-6 / 7-8 / 9-10 / 11-14 |
| --- | --- | --- | --- | --- | --- | --- |
| validation | gen0-run2 | 72.9% | 3.25 | 0.254 (0.250) | 0.683 | 0.52 / 0.61 / 0.68 / 0.74 / 0.80 / 0.83 |
| validation | gen1-b | 66.9% | 1.20 | 0.235 | 0.717 | 0.57 / 0.62 / 0.73 / 0.78 / 0.82 / 0.87 |
| validation | gen2-c | 77.7% | 1.17 | 0.203 | 0.782 | 0.67 / 0.71 / 0.81 / 0.86 / 0.90 / 0.85 |
| test | gen0-run2 | 73.6% | 3.24 | 0.235 (0.250) | 0.720 | 0.56 / 0.67 / 0.75 / 0.78 / 0.77 / 0.80 |
| test | gen1-b | 68.0% | 1.18 | 0.215 | 0.761 | 0.61 / 0.70 / 0.77 / 0.83 / 0.83 / 0.87 |
| test | gen2-c | 78.1% | 1.15 | 0.200 | 0.792 | 0.67 / 0.73 / 0.79 / 0.84 / 0.85 / 0.89 |

`gen2-c.onnx` (SHA-256 669387b3…) matches PyTorch to 3e-6 (logits) and 2e-6 (value) on two self-play games.

## Generation 3: gen2-c-leaf self-play

Data (2026-11-28): the 1011 self-play games of `selfplay\b0021`-`b0040` (gen2-c leaf, seed bases 2,021,000-2,040,000;
builds 93a0a0b b0021, c06a76a b0022, 05d9dee b0023-25, 0a7fbbf b0026-33, a6d3299 b0034-40). Shards: 1326 protocol 15
with Forge-static horizon leaves (leaf evaluation 2), 696 protocol 16 with the value net at the horizon (3). No older
games added. Split by match: train 1668 shards (146k rows), validation 182, test 172. Run D (`E:\magicdosa-runs\gen3\d`):
init gen2-c, lr 1e-4, 4 epochs, 1230 s; the value overfits after epoch 1 (validation BCE 0.455 → 0.499), epoch 1 kept.

| Set | Model | Top-1 vs search | Soft CE | Value Brier (base rate) | AUC | PRIORITY: pass is argmax / mean top prior |
| --- | --- | --- | --- | --- | --- | --- |
| validation | gen0-run2 | 70.1% | 3.28 | 0.200 (0.249) | 0.781 | 54.8% / 0.90 |
| validation | gen1-b | 66.3% | 1.11 | 0.178 | 0.824 | 48.8% / 0.45 |
| validation | gen2-c | 72.4% | 1.077 | 0.156 | 0.856 | 71.8% / 0.41 |
| validation | gen3-d | 74.3% | 1.073 | 0.149 | 0.867 | 65.0% / 0.39 |
| test | gen0-run2 | 69.8% | 3.16 | 0.223 (0.249) | 0.741 | 56.2% / 0.89 |
| test | gen1-b | 67.6% | 1.09 | 0.210 | 0.764 | 48.8% / 0.45 |
| test | gen2-c | 74.7% | 1.061 | 0.164 | 0.842 | 73.4% / 0.42 |
| test | gen3-d | 75.4% | 1.058 | 0.165 | 0.842 | 64.2% / 0.39 |

Test AUC by turn, gen2-c → gen3-d: 1-2 0.73 → 0.78, 3-4 0.81 → 0.84, 5-6 0.87 → 0.88, 7-8 0.90 → 0.90,
9-10 0.89 → 0.87, 11-14 0.87 → 0.87. `gen3-d.onnx` (SHA-256 6ee4fb18…) matches PyTorch to 4e-6 / 2e-6.

Why the policy is flat and passes (PRIORITY targets of this data, validation set; 10.6k rows, 3.45 candidates on
average): the visit ratios are close to uniform (pass 0.371 against a uniform share of 0.357; on one batch the median
spread between the most and least visited candidate is 0.083, one simulation of twelve), the most visited
candidates tie in 38% of rows, and pass is always candidate 0, so pass is among the most visited in 70% of rows but
the unique most visited in only 36%. A uniform prior with 12 simulations per candidate barely separates them, the
soft cross-entropy then teaches a near-uniform policy with a small pass tilt, and an argmax with first-index tie
breaking lands on pass. The sampled early turns do not touch the targets (only the played move). The recipe is
unchanged; changing it (sharper targets, more simulations, tie handling in the search) is a planning decision.

## Placeholders

Everything here exists only to make the pipeline run. None of it is a design decision.

| Placeholder | Where |
| --- | --- |
| Sizes: d_model 64, 4 heads, 2 layers, FF 128 | `ModelConfig` |
| `vocab_headroom` 64 spare embedding rows per kind | `ModelConfig.rows` |
| Global vector as a token; its output is the pooled observation | `Encoder` |
| Segment embeddings (global / entity) | `Encoder` |
| Deck tokens mean-pooled into the global token, not attended over | `Encoder.deck_mix` |
| Relations as a learned per-head attention bias per relation kind | `Encoder.rel_bias` |
| Deck count as one raw scalar into a linear layer | `Encoder.deck_count` |
| Candidate = sum of embeddings + linear projections of the referenced tokens | `PlayNet.candidates` |
| One `cand_string` table spanning both STRING and CARD index spaces (a sideboard swap's card taken out is a card index in that column) | `PlayNet.cand_string` |
| Per-type head = 2-layer MLP on [candidate, pooled + type embedding]; all 15 types get one | `PlayNet.heads` |
| Value head = 2-layer MLP + sigmoid | `PlayNet.value` |
| Critic inputs: detached pooled observation + mean of hidden card embeddings (no library order, no history) | `Critic` |
| Binary cross-entropy for value and critic; loss weights 1/1/1 | `train.py` |
| Draws left out of value targets; rows of every decider kept as value samples | `shards.value_target_rows` |
| Adam, lr 1e-3 (test only) | `tests/test_train.py` |
| Opset 18; padded logit -1e9 | `export.py`, `model.NEG` |

## Not built

- Replay buffer and the ONNX hand-off directory. (Generation 0 has a streaming loader, a training loop and
  checkpoints: see below.)
- Hiding the E7 "known" columns for training (`docs/encoder.md`, Tensors).
- The learned belief correction, the match module (sideboarding), and the mulligan / play-draw tables.
- Using `chosen` in a loss.

## Notes for other sessions

- agent-core has no ONNX Runtime dependency yet. The exported graph needs an ORT that supports opset 18 and int64 /
  bool inputs.
- `versions.py` has `MODEL_VERSION` (1 since 2026-11-26). It is written into checkpoints and ONNX metadata;
  `python -m magicdosa.export` refuses a checkpoint of another model version.
- The shard has no deck identity column. A per-deck (deck-local) split would have to come from `match_id` /
  `game_id` or the deck tokens.
