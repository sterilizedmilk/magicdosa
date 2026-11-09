package magicdosa.adapter.forge.harness;

import magicdosa.adapter.forge.DecisionClient;
import magicdosa.adapter.forge.GrpcDecisionClient;
import magicdosa.adapter.forge.LocalDecisionClient;
import magicdosa.agent.policy.RandomPolicy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How a harness reaches agent-core:
 * <ul>
 *   <li>{@code spawn}: starts agent-core as a separate JVM (design: 두 JVM) on a free localhost port and talks gRPC;
 *   on close it stops agent-core and reads its summary (protocol errors it saw).</li>
 *   <li>{@code host:port}: an agent-core that is already running, over gRPC.</li>
 *   <li>{@code local}: agent-core's game handling called in-process (one JVM, no gRPC).</li>
 * </ul>
 */
final class AgentConnection implements AutoCloseable {
    private static final Pattern LISTENING = Pattern.compile("agent-core listening on port (\\d+)");
    private static final Pattern ERRORS = Pattern.compile("protocolErrors=(\\d+)");
    private static final Pattern SHARDS = Pattern.compile("shards=(\\d+) shardErrors=(\\d+) oov=(\\d+)(.*)");

    private final DecisionClient client;
    private final Process process;
    private Thread output;
    private volatile String summary = "";

    private AgentConnection(DecisionClient client, Process process) {
        this.client = client;
        this.process = process;
    }

    static AgentConnection open(String spec) throws IOException {
        if (spec.equals("local")) {
            return new AgentConnection(new LocalDecisionClient(new RandomPolicy()), null);
        }
        if (spec.equals("spawn")) {
            return spawn();
        }
        int colon = spec.lastIndexOf(':');
        return new AgentConnection(new GrpcDecisionClient(spec.substring(0, colon), Integer.parseInt(spec.substring(colon + 1))), null);
    }

    private static AgentConnection spawn() throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> cmd = new ArrayList<>(List.of(java, "-Xmx" + System.getProperty("magicdosa.agentXmx", "1g"), "--add-opens=java.base/java.nio=ALL-UNNAMED",
            "-cp", System.getProperty("java.class.path"), "magicdosa.agent.AgentCore", "--port", "0", "--until-stdin-closes"));
        String shards = System.getProperty("magicdosa.shards");
        if (shards != null) {
            cmd.addAll(List.of("--shards", shards)); // training samples (Arrow) of every finished game
        }
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        String line;
        int port = -1;
        while (port < 0 && (line = in.readLine()) != null) {
            System.out.println("[agent-core] " + line);
            Matcher m = LISTENING.matcher(line);
            if (m.find()) {
                port = Integer.parseInt(m.group(1));
            }
        }
        if (port < 0) {
            p.destroyForcibly();
            throw new IOException("agent-core did not start");
        }
        AgentConnection c = new AgentConnection(new GrpcDecisionClient("localhost", port), p);
        c.output = new Thread(() -> {
            try {
                String l;
                while ((l = in.readLine()) != null) {
                    System.out.println("[agent-core] " + l);
                    if (l.startsWith("agent-core: games finished=")) {
                        c.summary = l;
                    }
                }
            } catch (IOException ignored) {
                // process gone
            }
        }, "agent-core output");
        c.output.setDaemon(true);
        c.output.start();
        return c;
    }

    DecisionClient client() {
        return client;
    }

    /**
     * Protocol errors agent-core reported. In-process they are listed; from a spawned agent-core only the count in its
     * summary is known (each also failed its game's stream). Call after {@link #close()}.
     */
    int protocolErrorCount() {
        if (client instanceof LocalDecisionClient) {
            return ((LocalDecisionClient) client).agent().protocolErrors().size();
        }
        if (process == null) {
            return 0; // a running agent-core keeps its own count
        }
        Matcher m = ERRORS.matcher(summary);
        return m.find() ? Integer.parseInt(m.group(1)) : -1; // -1: agent-core did not report
    }

    /** With shards on (-Dmagicdosa.shards): "written, errors, vocabulary misses [values]", else null. Call after close. */
    String shardReport() {
        Matcher m = SHARDS.matcher(summary);
        return m.find() ? m.group(1) + " written, " + m.group(2) + " errors, oov " + m.group(3) + m.group(4) : null;
    }

    boolean shardsClean() {
        Matcher m = SHARDS.matcher(summary);
        return !m.find() || (m.group(2).equals("0") && m.group(3).equals("0"));
    }

    List<String> protocolErrors() {
        if (client instanceof LocalDecisionClient) {
            return ((LocalDecisionClient) client).agent().protocolErrors();
        }
        return List.of(); // spawned or running: see the agent-core output
    }

    @Override
    public void close() throws Exception {
        if (client instanceof GrpcDecisionClient) {
            ((GrpcDecisionClient) client).close();
        }
        if (process != null) {
            process.getOutputStream().close(); // agent-core prints its summary and exits
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            output.join(5000);
        }
    }
}
