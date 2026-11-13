package magicdosa.runner.forge.harness;

import magicdosa.adapter.forge.CardKeys;

import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

/**
 * One pairing of a process-parallel gate (2026-11-11 decision: parallel runs are by process, one Forge game at a time
 * per JVM, because Forge games running at the same time in one JVM affect each other). Started by
 * {@link AllPairings} with {@code --workers N}; plays the pairing's matches one after another and writes its
 * {@link Result} to a file. Main program in the test sources:
 *
 * <pre>PairingWorker &lt;decks dir&gt; &lt;deck0&gt; &lt;deck1&gt; &lt;matches&gt; &lt;log dir&gt; &lt;agent spec&gt; &lt;match prefix&gt; &lt;result file&gt;</pre>
 */
public final class PairingWorker {
    private PairingWorker() {}

    /** What the parent needs from one worker: the pairing's summary and the agent-core figures of its connection. */
    record Result(RandomGames.Summary summary, int protocolErrors, boolean shardsClean, String shardReport,
                  Set<String> unmapped, long peakHeapMb, long maxHeapMb) implements Serializable {}

    public static void main(String[] args) {
        int code = 1;
        try {
            RandomGames.initForge();
            CardKeys keys = CardKeys.load();
            Set<String> before = new TreeSet<>(keys.unmapped());
            AgentConnection agent = AgentConnection.open(args[5]);
            RandomGames.Summary s = RandomGames.run(args[0], args[1], args[2], Integer.parseInt(args[3]),
                Path.of(args[4]), agent, keys, args[6]);
            agent.close();
            s.lines().forEach(System.out::println); // the pairing's summary in the worker's output too
            Set<String> unmapped = new TreeSet<>(keys.unmapped());
            unmapped.removeAll(before);
            long peak = 0;
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool.getType() == MemoryType.HEAP && pool.getPeakUsage() != null) {
                    peak += pool.getPeakUsage().getUsed();
                }
            }
            Result r = new Result(s, agent.protocolErrorCount(), agent.shardsClean(), agent.shardReport(), unmapped,
                peak >> 20, Runtime.getRuntime().maxMemory() >> 20);
            try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(Path.of(args[7])))) {
                out.writeObject(r);
            }
            code = 0;
        } catch (Throwable t) {
            t.printStackTrace();
        }
        System.exit(code); // Forge leaves non-daemon threads
    }
}
