package magicdosa.agent;

import magicdosa.protocol.Versions;

/** agent-core entry point. For now it only reports its versions; the gRPC server comes in phase 1. */
public final class AgentCore {
    /** Layout of the tensors the encoder produces. A model records the encoder version it was trained with. */
    public static final int ENCODER_VERSION = 1;

    private AgentCore() {
    }

    public static void main(String[] args) {
        System.out.println("magicdosa agent-core: protocol " + Versions.PROTOCOL + ", encoder " + ENCODER_VERSION);
    }
}
