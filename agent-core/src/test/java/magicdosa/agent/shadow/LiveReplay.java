package magicdosa.agent.shadow;

import magicdosa.agent.policy.RandomPolicy;
import magicdosa.protocol.v1.DecisionResponse;
import magicdosa.protocol.v1.Hello;
import magicdosa.protocol.v1.LogHeader;
import magicdosa.protocol.v1.LogRecord;

import java.nio.file.Path;
import java.util.List;

/**
 * Plays a game log of the shadow Forge AI policy into a {@link LiveShadow} in the order agent-core received it
 * (events, snapshots, requests, the game end), as the live gate did, without the real game: the live shadow's
 * mismatches can then be studied from existing logs. The shadow's answers are compared with the recorded ones (the
 * real game applied them); a different answer means the replay no longer follows the live run.
 * <p>
 * {@code LiveReplay <log.pb.gz>...}; run with forge-gui as the working directory, -Dmagicdosa.shadowStats=<dir> for the
 * per-game stats file the live gate writes.
 */
public final class LiveReplay {
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
        int code = 0;
        try {
            forge.gui.GuiBase.setInterface(new forge.GuiDesktop());
            forge.model.FModel.initialize(null, null);
            magicdosa.adapter.forge.CardKeys keys = magicdosa.adapter.forge.CardKeys.load();
            RandomPolicy random = new RandomPolicy();
            for (String a : args) {
                replay(Path.of(a), keys, random);
            }
        } catch (Throwable t) {
            t.printStackTrace();
            code = 1;
        }
        System.exit(code);
    }

    private static void replay(Path file, magicdosa.adapter.forge.CardKeys keys, RandomPolicy random) throws Exception {
        List<LogRecord> records = GameRecord.read(file);
        LogHeader h = records.get(0).getHeader();
        Hello hello = Hello.newBuilder().setGameId(h.getGameId()).setMatchId(h.getMatchId())
            .setGameNumber(h.getGameNumber()).setMode(h.getMode()).addAllAgentSeats(h.getAgentSeatsList()).build();
        LiveShadow shadow = new LiveShadow(hello, hello.getAgentSeats(0), keys, new magicdosa.agent.policy.Policy() {
            @Override
            public String modelVersion() {
                return "shadow-forge-ai";
            }

            @Override
            public DecisionResponse decide(magicdosa.protocol.v1.DecisionRequest request) {
                return random.decide(request);
            }
        });
        int decisions = 0;
        int different = 0;
        // the order agent-core received the game in: the agent seats' events by sequence number, a snapshot after the
        // event it was taken at, a request after the event its applied_seq names (by decision ID: the log writes a
        // decision once answered, so one asked while another was played comes first there)
        List<Object[]> stream = new java.util.ArrayList<>(); // {seq, order, id, record}
        magicdosa.protocol.v1.GameOver over = null;
        for (LogRecord r : records) {
            switch (r.getRecordCase()) {
                case EVENT -> {
                    if (h.getAgentSeatsList().contains(r.getEvent().getViewerSeat())) {
                        stream.add(new Object[] {r.getEvent().getSeq(), 0, 0L, r});
                    }
                }
                case SNAPSHOT -> {
                    if (h.getAgentSeatsList().contains(r.getSnapshot().getSeat())) {
                        stream.add(new Object[] {r.getSnapshot().getSeq(), 1, 0L, r});
                    }
                }
                case DECISION -> {
                    // agent-core gets only the agent seats' requests (the log also has the Forge AI seat's)
                    if (r.getDecision().hasRequest() && r.getDecision().hasResponse()
                            && h.getAgentSeatsList().contains(r.getDecision().getRequest().getSeat())) {
                        stream.add(new Object[] {r.getDecision().getRequest().getAppliedSeq(), 2,
                            r.getDecision().getRequest().getDecisionId(), r});
                    }
                }
                case GAME_OVER -> over = r.getGameOver();
                default -> { }
            }
        }
        stream.sort(java.util.Comparator.<Object[]>comparingLong(o -> (Long) o[0]).thenComparingInt(o -> (Integer) o[1])
            .thenComparingLong(o -> (Long) o[2]));
        for (Object[] o : stream) {
            LogRecord r = (LogRecord) o[3];
            switch (r.getRecordCase()) {
                case EVENT -> shadow.event(r.getEvent());
                case SNAPSHOT -> shadow.snapshot(r.getSnapshot());
                case DECISION -> {
                    decisions++;
                    DecisionResponse mine = shadow.decide(r.getDecision().getRequest());
                    if (!mine.getChosenList().equals(r.getDecision().getResponse().getChosenList()) && different++ < 3) {
                        System.out.println("  answer differs at decision " + r.getDecision().getRequest().getDecisionId()
                            + ": recorded " + r.getDecision().getResponse().getChosenList() + ", replay " + mine.getChosenList());
                    }
                }
                default -> { }
            }
        }
        if (over != null) {
            shadow.gameOver(over);
        }
        ShadowGame.Result result = shadow.stopAndResult();
        if (result != null) {
            result.replayMismatches.forEach(m -> System.out.println("  replay: " + m));
        }
        System.out.println(file.getFileName() + ": decisions " + decisions + ", answers different " + different);
    }
}
