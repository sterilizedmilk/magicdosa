package magicdosa.agent.shadow;

import magicdosa.agent.Timing;
import magicdosa.agent.policy.RandomPolicy;
import magicdosa.agent.search.SearchConfig;
import magicdosa.protocol.v1.DecisionResponse;
import magicdosa.protocol.v1.Hello;
import magicdosa.protocol.v1.LogHeader;
import magicdosa.protocol.v1.LogRecord;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Search cost bench on a fixed set of root states (measurement only): plays recorded game logs into a {@link LiveShadow}
 * with the search policy, as {@code LiveReplay} does for the shadow Forge AI policy, so every recorded agent-seat
 * request is searched again from the same shadow state. The shadow follows the recorded events whatever the search
 * answers, so the same code, logs and budget give the same searches: with {@code -Dmagicdosa.search.debug=true} every
 * search prints its visits and root value, the digest to compare before and after a change that must not change them.
 * <p>
 * {@code SearchReplay <perCandidate> <multiplier> <maxDecisionsPerLog> <log.pb.gz>...} (0 for no cap); run with
 * forge-gui as the working directory and {@code -Dmagicdosa.timing=true} for the cost split. With {@code -Dfollow=true}
 * the shadow plays the recorded answer after each search, so settings that answer differently still search every
 * root of the recorded game (compare the searches' answers in the debug lines; the shadow's answers then equal the log).
 */
public final class SearchReplay {
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
        int code = 0;
        try {
            forge.gui.GuiBase.setInterface(new forge.GuiDesktop());
            forge.model.FModel.initialize(null, null);
            magicdosa.adapter.forge.CardKeys keys = magicdosa.adapter.forge.CardKeys.load();
            SearchConfig config = SearchConfig.fromSystemProperties(1)
                .perCandidate(Integer.parseInt(args[0]), Integer.parseInt(args[1]));
            int cap = Integer.parseInt(args[2]);
            long t0 = System.nanoTime();
            long gc0 = gcMillis();
            for (int i = 3; i < args.length; i++) {
                replay(Path.of(args[i]), keys, config, cap);
            }
            System.out.printf("total %.1f s, gc %d ms%n", (System.nanoTime() - t0) / 1e9, gcMillis() - gc0);
            System.out.println(LiveShadow.totals());
            System.out.println(Timing.line());
            System.out.println(magicdosa.agent.search.ForgeSearchGame.boundaryDiff());
        } catch (Throwable t) {
            t.printStackTrace();
            code = 1;
        }
        System.exit(code);
    }

    static long gcMillis() {
        long ms = 0;
        for (var gc : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            ms += Math.max(0, gc.getCollectionTime());
        }
        return ms;
    }

    private static void replay(Path file, magicdosa.adapter.forge.CardKeys keys, SearchConfig config, int cap)
            throws Exception {
        List<LogRecord> records = GameRecord.read(file);
        LogHeader h = records.get(0).getHeader();
        // one seat's agent-core, as it was told at the game start (a head-to-head log has both seats as agents, each
        // played by its own agent-core: -Dmagicdosa.replaySeat picks one, default the first agent seat)
        int seat = Integer.getInteger("magicdosa.replaySeat", h.getAgentSeats(0));
        List<Integer> seats = List.of(seat);
        Hello hello = Hello.newBuilder().setGameId(h.getGameId()).setMatchId(h.getMatchId())
            .setGameNumber(h.getGameNumber()).setMode(h.getMode()).addAllAgentSeats(seats).build();
        LiveShadow shadow = new LiveShadow(hello, hello.getAgentSeats(0), keys, new RandomPolicy(), config);
        // the order agent-core received the game in (as LiveReplay)
        List<Object[]> stream = new ArrayList<>();
        for (LogRecord r : records) {
            switch (r.getRecordCase()) {
                case EVENT -> {
                    if (seats.contains(r.getEvent().getViewerSeat())) {
                        stream.add(new Object[] {r.getEvent().getSeq(), 0, 0L, r});
                    }
                }
                case SNAPSHOT -> {
                    if (seats.contains(r.getSnapshot().getSeat())) {
                        stream.add(new Object[] {r.getSnapshot().getSeq(), 1, 0L, r});
                    }
                }
                case DECISION -> {
                    if (r.getDecision().hasRequest() && r.getDecision().hasResponse()
                            && seats.contains(r.getDecision().getRequest().getSeat())) {
                        stream.add(new Object[] {r.getDecision().getRequest().getAppliedSeq(), 2,
                            r.getDecision().getRequest().getDecisionId(), r});
                    }
                }
                default -> { }
            }
        }
        stream.sort(Comparator.<Object[]>comparingLong(o -> (Long) o[0]).thenComparingInt(o -> (Integer) o[1])
            .thenComparingLong(o -> (Long) o[2]));
        int decisions = 0;
        int different = 0;
        long t0 = System.nanoTime();
        for (Object[] o : stream) {
            LogRecord r = (LogRecord) o[3];
            switch (r.getRecordCase()) {
                case EVENT -> shadow.event(r.getEvent());
                case SNAPSHOT -> shadow.snapshot(r.getSnapshot());
                case DECISION -> {
                    if (cap > 0 && decisions >= cap) {
                        break;
                    }
                    decisions++;
                    if (Boolean.getBoolean("follow")) {
                        ShadowGame.FOLLOW = r.getDecision().getResponse();
                    }
                    DecisionResponse mine = shadow.decide(r.getDecision().getRequest());
                    if (!mine.getChosenList().equals(r.getDecision().getResponse().getChosenList())) {
                        different++;
                    }
                }
                default -> { }
            }
            if (cap > 0 && decisions >= cap && r.getRecordCase() == LogRecord.RecordCase.DECISION) {
                break;
            }
        }
        var result = shadow.stopAndResult();
        System.out.printf("%s: decisions %d, answers different from the log %d, %.1f s%n", file.getFileName(), decisions,
            different, (System.nanoTime() - t0) / 1e9);
        if (result != null) {
            System.out.println("  shadow: decisions " + result.decisions + " matched " + result.decisionsMatched
                + " after recovery " + result.decisionsMatchedAfterRecovery + " searchSkipped " + result.searchSkipped
                + " recoveries " + result.recoveries1 + "/" + result.recoveries2 + "/" + result.recoveries3);
            if (Boolean.getBoolean("magicdosa.search.debug")) {
                result.replayMismatches.forEach(m -> System.out.println("  replay mismatch: " + m));
                result.samples.forEach(m -> System.out.println("  sample: " + m));
            }
        }
        // what the searches counted (depth stats with -Dmagicdosa.search.depthStats, root kinds, leaf ends), for
        // summing over logs
        shadow.stats().search.forEach((k, v) -> System.out.println("  count " + k + " = " + v));
    }
}
