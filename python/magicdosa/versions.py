"""Versions checked against agent-core. Keep in step with magicdosa.protocol.Versions and AgentCore.ENCODER_VERSION."""

PROTOCOL_VERSION = 18
# Older protocols whose shards have the same tensors, read without being asked for: 15 (protocol 16 only added
# LEAF_EVALUATION_NETWORK, a new value of the search_leaf_evaluation column, 2026-11-28) and 16 (protocol 17 added the
# per-candidate search statistics; read_shard fills their columns for older shards).
COMPATIBLE_PROTOCOLS = (15, 16, 17)
# Shard layout (schema metadata shard_schema_version; agent-core ShardWriter.SHARD_SCHEMA_VERSION). A shard without it
# is 1. 2 (protocol 17): search_status, search_root_value, cand_visits, cand_q, cand_prior, search_tree_reuse,
# search_inherited_visits, kill_check (0 not checked, 1 none, 2 confirmed kill, 3 confirmed death), kill_check_approximate.
# 3 (protocol 18, Gumbel search): search_algorithm (0 no search, 1 UCB1, 2 Gumbel), search_gumbel_noise. Older shards
# read as UCB1 where search ran, no noise. Protocol 17 shards are read without being asked (the same tensors).
SHARD_SCHEMA_VERSION = 3
ENCODER_VERSION = 5
VOCAB_VERSION = 3
# Play-network structure (inputs, layers, heads, outputs). Raise it whenever a checkpoint of the old structure can no
# longer be loaded or the ONNX contract changes. 1: generation-0 placeholder network (2026-11-26).
MODEL_VERSION = 1
