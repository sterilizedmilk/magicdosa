package magicdosa.agent;

import magicdosa.agent.policy.RandomPolicy;
import magicdosa.agent.server.AgentCoreServer;
import magicdosa.agent.session.Agent;
import magicdosa.protocol.Versions;

/**
 * agent-core entry point: serves adapters over gRPC. Phase 1 answers every decision with the uniform random policy.
 *
 * <pre>java magicdosa.agent.AgentCore [--port N] [--until-stdin-closes]</pre>
 *
 * The port defaults to 50051; 0 picks a free port. Prints {@code agent-core listening on port N} once it accepts
 * connections. With {@code --until-stdin-closes} (a harness that started it as a child process) it prints its summary
 * and exits when its standard input closes.
 */
public final class AgentCore {
    /** Layout of the tensors the encoder produces. A model records the encoder version it was trained with. */
    public static final int ENCODER_VERSION = 1;

    public static final int DEFAULT_PORT = 50051;

    private AgentCore() {
    }

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        boolean untilStdinCloses = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--port") && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if (args[i].equals("--until-stdin-closes")) {
                untilStdinCloses = true;
            } else {
                throw new IllegalArgumentException("unknown argument " + args[i]);
            }
        }
        RandomPolicy policy = new RandomPolicy();
        Agent agent = new Agent(policy);
        AgentCoreServer server = AgentCoreServer.start(port, agent);
        System.out.println("agent-core: protocol " + Versions.PROTOCOL + ", encoder " + ENCODER_VERSION + ", model "
            + policy.modelVersion());
        System.out.println("agent-core listening on port " + server.port());
        System.out.flush();
        if (untilStdinCloses) {
            while (System.in.read() >= 0) {
                // wait for the harness to close the pipe
            }
            server.shutdown();
            System.out.println("agent-core: " + agent.summary());
            System.exit(0);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("agent-core: " + agent.summary())));
        server.awaitTermination();
    }
}
