"""Versions checked against agent-core. Keep in step with magicdosa.protocol.Versions and AgentCore.ENCODER_VERSION."""

PROTOCOL_VERSION = 15
ENCODER_VERSION = 5
VOCAB_VERSION = 3
# Play-network structure (inputs, layers, heads, outputs). Raise it whenever a checkpoint of the old structure can no
# longer be loaded or the ONNX contract changes. 1: generation-0 placeholder network (2026-11-26).
MODEL_VERSION = 1
