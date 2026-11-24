package magicdosa.agent;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Measurement only (phase-2 completion report, planning item 19): wall-clock time spent in named parts of agent-core,
 * summed over the JVM's games. Off unless {@code -Dmagicdosa.timing=true}; when off, {@link #start()} returns 0 and
 * {@link #add} does nothing. Nothing here is read by a decision (CLAUDE.md: no wall-clock logic in the decision path).
 */
public final class Timing {
    public static final boolean ON = Boolean.getBoolean("magicdosa.timing");
    private static final Map<String, LongAdder> NANOS = new ConcurrentHashMap<>();
    private static final Map<String, LongAdder> COUNTS = new ConcurrentHashMap<>();

    private static long startupMs = -1;

    private Timing() {}

    /** The JVM's time from its start until now (call once agent-core listens). */
    public static void started() {
        startupMs = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
    }

    /** A start time for {@link #add}, or 0 when timing is off. */
    public static long start() {
        return ON ? System.nanoTime() : 0;
    }

    /** Adds the time since {@code start} to {@code part}. */
    public static void add(String part, long start) {
        if (ON && start != 0) {
            NANOS.computeIfAbsent(part, k -> new LongAdder()).add(System.nanoTime() - start);
            COUNTS.computeIfAbsent(part, k -> new LongAdder()).increment();
        }
    }

    /** Upper bounds (ms) of the histogram buckets; the last bucket is everything above. */
    private static final long[] BUCKETS_MS = {1, 10, 100, 1_000, 10_000, 60_000};
    private static final Map<String, java.util.concurrent.atomic.AtomicLongArray> HIST = new ConcurrentHashMap<>();
    private static final Map<String, java.util.concurrent.atomic.AtomicLong> MAX = new ConcurrentHashMap<>();

    /** Adds the time since {@code start} to {@code part} and to its histogram (a distribution, not only a sum). */
    public static void addHistogram(String part, long start) {
        if (!ON || start == 0) {
            return;
        }
        long nanos = System.nanoTime() - start;
        NANOS.computeIfAbsent(part, k -> new LongAdder()).add(nanos);
        COUNTS.computeIfAbsent(part, k -> new LongAdder()).increment();
        long ms = nanos / 1_000_000;
        int b = 0;
        while (b < BUCKETS_MS.length && ms >= BUCKETS_MS[b]) {
            b++;
        }
        HIST.computeIfAbsent(part, k -> new java.util.concurrent.atomic.AtomicLongArray(BUCKETS_MS.length + 1)).incrementAndGet(b);
        MAX.computeIfAbsent(part, k -> new java.util.concurrent.atomic.AtomicLong()).accumulateAndGet(ms, Math::max);
    }

    /** "timing part=ms/count ..." for the agent-core summary line; empty when off. */
    public static String line() {
        if (!ON) {
            return "";
        }
        StringBuilder b = new StringBuilder("timing");
        new TreeMap<>(NANOS).forEach((k, v) -> b.append(' ').append(k).append('=').append(v.sum() / 1_000_000)
            .append("ms/").append(COUNTS.get(k).sum()));
        new TreeMap<>(HIST).forEach((k, h) -> {
            b.append(' ').append(k).append(".hist=[");
            for (int i = 0; i < h.length(); i++) {
                b.append(i == 0 ? "" : ",").append(i < BUCKETS_MS.length ? "<" + BUCKETS_MS[i] + "ms:" : ">=60s:").append(h.get(i));
            }
            b.append("] max=").append(MAX.get(k).get()).append("ms");
        });
        long peak = 0;
        for (var pool : java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == java.lang.management.MemoryType.HEAP && pool.getPeakUsage() != null) {
                peak += pool.getPeakUsage().getUsed();
            }
        }
        b.append(" heapPeakMb=").append(peak >> 20).append(" maxHeapMb=").append(Runtime.getRuntime().maxMemory() >> 20)
            .append(" startupMs=").append(startupMs);
        return b.toString();
    }
}
