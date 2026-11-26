package magicdosa.runner.forge.harness;

import magicdosa.adapter.forge.DecisionClient;
import magicdosa.runner.forge.GrpcDecisionClient;
import magicdosa.runner.forge.LocalDecisionClient;
import magicdosa.agent.policy.RandomPolicy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
final class AgentConnection implements AutoCloseable, RandomGames.Agents {
    private static final Pattern LISTENING = Pattern.compile("agent-core listening on port (\\d+)");
    private static final Pattern ERRORS = Pattern.compile("protocolErrors=(\\d+)");
    private static final Pattern SHARDS = Pattern.compile("shards=(\\d+) shardErrors=(\\d+) oov=(\\d+)(.*)");

    private DecisionClient client;
    private Process process;
    private Thread output;
    private volatile String summary = "";
    /** Summaries of agent-core processes stopped by a restart (their counts add up with the last one's). */
    private final List<String> earlier = new java.util.ArrayList<>();
    private int restarts;
    /** What the runner holds: forwards to the current client, so a restart needs no new runner. */
    private final DecisionClient forwarding = new DecisionClient() {
        @Override
        public magicdosa.protocol.v1.HelloReply hello(magicdosa.protocol.v1.Hello hello) throws Exception {
            return client.hello(hello);
        }

        @Override
        public void send(magicdosa.protocol.v1.AdapterMessage message) throws Exception {
            client.send(message);
        }

        @Override
        public magicdosa.protocol.v1.DecisionResponse decide(magicdosa.protocol.v1.DecisionRequest request) throws Exception {
            return client.decide(request);
        }
    };

    /** Settings that replace the run's system properties for this agent-core (two agent-cores with their own budgets). */
    private Map<String, String> overrides = Map.of();

    private AgentConnection(DecisionClient client, Process process) {
        this.client = client;
        this.process = process;
    }

    private static String prop(Map<String, String> overrides, String key) {
        return overrides.containsKey(key) ? overrides.get(key) : System.getProperty(key);
    }

    /**
     * Whether agent-core refused a game because it must be restarted (its hang safeguard could not end a stuck Forge
     * thread: one Forge thread per JVM no longer holds there, 2026-11-24 directive).
     */
    static boolean needsRestart(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getMessage() != null && c.getMessage().contains("agent-core must be restarted")) {
                return true;
            }
        }
        return false;
    }

    /** A spawned agent-core can be started again; others cannot. */
    @Override
    public boolean restartable() {
        return process != null;
    }

    /** Stops this agent-core and starts a new one with the same settings. */
    @Override
    public void restart() throws Exception {
        if (!restartable()) {
            throw new IllegalStateException("only a spawned agent-core can be restarted");
        }
        close();
        earlier.add(currentSummary());
        AgentConnection fresh = spawn(overrides);
        client = fresh.client;
        process = fresh.process;
        output = fresh.output;
        summary = "";
        restarts++;
        // the new process's output thread writes into the fresh connection's summary: read it from there
        summarySource = fresh;
    }

    /** Where the current process's summary is read (itself, or the connection a restart made). */
    private AgentConnection summarySource = this;

    @Override
    public int restarts() {
        return restarts;
    }

    private String currentSummary() {
        return summarySource == this ? summary : summarySource.summary;
    }

    /**
     * Every gate writes shards (2026-11-25 planning decision): unless {@code -Dmagicdosa.shards} names a folder or
     * {@code -Dmagicdosa.noShards=true}, an agent-core this run spawns writes them to {@code <logs>/shards}.
     */
    static void defaultShards(Path logs) throws IOException {
        if (System.getProperty("magicdosa.shards") == null && !Boolean.getBoolean("magicdosa.noShards")) {
            Path dir = logs.resolve("shards");
            java.nio.file.Files.createDirectories(dir);
            System.setProperty("magicdosa.shards", dir.toAbsolutePath().toString());
        }
    }

    static AgentConnection open(String spec) throws IOException {
        if (spec.equals("local")) {
            return new AgentConnection(new LocalDecisionClient(new RandomPolicy()), null);
        }
        if (spec.equals("forge-ai")) {
            return new AgentConnection(new ForgeAiClient(), null); // baseline: the real game's Forge AI decides
        }
        if (spec.equals("spawn")) {
            return spawn(Map.of());
        }
        int colon = spec.lastIndexOf(':');
        return new AgentConnection(new GrpcDecisionClient(spec.substring(0, colon), Integer.parseInt(spec.substring(colon + 1))), null);
    }

    /**
     * A spawned agent-core whose {@code overrides} (e.g. {@code magicdosa.agentBudgetMultiplier},
     * {@code magicdosa.shards}) replace the run's system properties of the same name.
     */
    static AgentConnection spawn(Map<String, String> overrides) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> cmd = new ArrayList<>(List.of(java, "-Xmx" + Objects.requireNonNullElse(prop(overrides, "magicdosa.agentXmx"), "1g"), "--add-opens=java.base/java.nio=ALL-UNNAMED",
            "-cp", System.getProperty("java.class.path"), "magicdosa.agent.AgentCore", "--port", "0", "--until-stdin-closes"));
        String jvmArgs = prop(overrides, "magicdosa.agentJvmArgs");
        if (jvmArgs != null && !jvmArgs.isBlank()) {
            // e.g. "-XX:ParallelGCThreads=2 -XX:ConcGCThreads=1": several agent-cores on one machine
            cmd.addAll(cmd.indexOf("-cp"), List.of(jvmArgs.trim().split("\\s+")));
        }
        String policy = prop(overrides, "magicdosa.agentPolicy");
        if (policy != null) {
            // e.g. shadow-forge-ai: the shadow game's Forge AI decides (Forge runs in agent-core too: the same working
            // directory, forge-gui)
            cmd.addAll(List.of("--policy", policy));
        }
        String budget = prop(overrides, "magicdosa.agentBudget");
        if (budget != null) {
            cmd.addAll(List.of("--budget", budget)); // --policy search: a fixed number of simulations per decision
        }
        String perCandidate = prop(overrides, "magicdosa.agentBudgetPerCandidate");
        if (perCandidate != null) {
            // --policy search: simulations per root action (1x = 12), times the multiplier (2x, 4x)
            cmd.addAll(List.of("--budget-per-candidate", perCandidate, "--budget-multiplier",
                Objects.requireNonNullElse(prop(overrides, "magicdosa.agentBudgetMultiplier"), "1")));
        }
        for (String k : System.getProperties().stringPropertyNames()) {
            if (k.startsWith("magicdosa.shadow") || k.startsWith("magicdosa.search") || k.equals("magicdosa.timing")) {
                cmd.add(cmd.indexOf("-cp"), "-D" + k + "=" + System.getProperty(k)); // shadowStats, shadowDump, ...
            }
        }
        if (policy != null) {
            cmd.add(cmd.indexOf("-cp"), "-Dfile.encoding=UTF-8");
        }
        String shards = prop(overrides, "magicdosa.shards");
        if (shards != null) {
            cmd.addAll(List.of("--shards", shards)); // training samples (Arrow) of every finished game
        }
        String label = prop(overrides, "magicdosa.agentLabel");
        String tag = label == null ? "[agent-core] " : "[agent-core " + label + "] ";
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        String line;
        int port = -1;
        while (port < 0 && (line = in.readLine()) != null) {
            System.out.println(tag + line);
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
        c.overrides = Map.copyOf(overrides);
        c.output = new Thread(() -> {
            try {
                String l;
                while ((l = in.readLine()) != null) {
                    System.out.println(tag + l);
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

    @Override
    public DecisionClient client() {
        return forwarding;
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
        int total = 0;
        List<String> all = new java.util.ArrayList<>(earlier);
        all.add(currentSummary());
        for (String s : all) {
            Matcher m = ERRORS.matcher(s);
            if (!m.find()) {
                return -1; // -1: agent-core did not report
            }
            total += Integer.parseInt(m.group(1));
        }
        return total;
    }

    /** With shards on (-Dmagicdosa.shards): "written, errors, vocabulary misses [values]", else null. Call after close. */
    String shardReport() {
        Matcher m = SHARDS.matcher(currentSummary());
        return m.find() ? m.group(1) + " written, " + m.group(2) + " errors, oov " + m.group(3) + m.group(4) : null;
    }

    boolean shardsClean() {
        List<String> all = new java.util.ArrayList<>(earlier);
        all.add(currentSummary());
        for (String s : all) {
            Matcher m = SHARDS.matcher(s);
            if (m.find() && !(m.group(2).equals("0") && m.group(3).equals("0"))) {
                return false;
            }
        }
        return true;
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
