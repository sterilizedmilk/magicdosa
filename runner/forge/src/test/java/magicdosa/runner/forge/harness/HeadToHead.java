package magicdosa.runner.forge.harness;

import magicdosa.adapter.forge.CardKeys;
import magicdosa.adapter.forge.DecisionClient;
import magicdosa.protocol.v1.AdapterMessage;
import magicdosa.protocol.v1.DecisionRequest;
import magicdosa.protocol.v1.DecisionResponse;
import magicdosa.protocol.v1.GameOver;
import magicdosa.protocol.v1.Hello;
import magicdosa.protocol.v1.HelloReply;
import magicdosa.protocol.v1.PrivilegedRecord;
import magicdosa.runner.forge.ForgeMatchRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Two agent-cores against each other (gate 1 step 3, 2026-11-26: search at 1x against search at 4x): each seat's
 * requests and stream go to its own agent-core, which is told it plays that seat alone, so each keeps its own shadow
 * game and writes its own shards ({@code <logs>/shards-<label>}). Agent-core A takes seat 0 in even matches and seat 1 in
 * odd ones, so neither side always sits in the same seat (the first game's starting player comes from the seed, later
 * games from Forge's loser-chooses rule). Settings of A and B: the run's system properties, with
 * {@code -Dmagicdosa.agentBudgetMultiplierA/B} and {@code -Dmagicdosa.agentPolicyA/B} replacing the shared ones.
 *
 * <pre>HeadToHead &lt;abs decks dir&gt; deck0 deck1 matches &lt;abs log dir&gt; [match prefix]</pre>
 */
public final class HeadToHead {
    private HeadToHead() {
    }

    /** Routes each seat's messages to the agent-core playing it. */
    static final class SeatRouter implements DecisionClient {
        private final DecisionClient[] bySeat = new DecisionClient[2];

        void assign(DecisionClient seat0, DecisionClient seat1) {
            bySeat[0] = seat0;
            bySeat[1] = seat1;
        }

        @Override
        public HelloReply hello(Hello hello) throws Exception {
            HelloReply last = null;
            for (int seat : hello.getAgentSeatsList()) {
                last = bySeat[seat].hello(hello.toBuilder().clearAgentSeats().addAgentSeats(seat).build());
                if (!last.getAccepted()) {
                    return last;
                }
            }
            return last;
        }

        @Override
        public void send(AdapterMessage m) throws Exception {
            switch (m.getMessageCase()) {
                case EVENT -> bySeat[m.getEvent().getViewerSeat()].send(m);
                case SNAPSHOT -> bySeat[m.getSnapshot().getSeat()].send(m);
                case DECISION_REQUEST -> bySeat[m.getDecisionRequest().getSeat()].send(m);
                case GAME_OVER -> {
                    for (int seat = 0; seat < 2; seat++) {
                        GameOver o = m.getGameOver();
                        List<PrivilegedRecord> own = new ArrayList<>();
                        for (PrivilegedRecord r : o.getPrivilegedList()) {
                            if (r.getSeat() == seat) {
                                own.add(r);
                            }
                        }
                        bySeat[seat].send(m.toBuilder().setGameOver(o.toBuilder().clearPrivileged().addAllPrivileged(own)).build());
                    }
                }
                default -> throw new IllegalArgumentException("unknown message " + m.getMessageCase());
            }
        }

        @Override
        public DecisionResponse decide(DecisionRequest request) throws Exception {
            return bySeat[request.getSeat()].decide(request);
        }
    }

    public static void main(String[] args) throws Exception {
        String deckDir = args[0];
        int matches = Integer.parseInt(args[3]);
        Path logDir = Path.of(args[4]);
        String prefix = args.length > 5 ? args[5] : "h";
        String labelA = label("A", "1");
        String labelB = label("B", "4");
        if (labelA.equals(labelB)) {
            // self-play (1x vs 1x): each side keeps its own shards folder and its own name in the report
            labelA += "-A";
            labelB += "-B";
        }
        RandomGames.initForge();
        CardKeys keys = CardKeys.load();
        AgentConnection a = AgentConnection.spawn(settings(logDir, "A", labelA));
        AgentConnection b = AgentConnection.spawn(settings(logDir, "B", labelB));
        SeatRouter router = new SeatRouter();
        int[] gamesWon = new int[2]; // A, B
        int[] matchesWon = new int[2];
        int[] gamesAsSeat0 = new int[2];
        int[] wonAsSeat0 = new int[2];
        List<String> perMatch = new ArrayList<>();
        RandomGames.Agents both = new RandomGames.Agents() {
            @Override
            public DecisionClient client() {
                return router;
            }

            @Override
            public boolean restartable() {
                return true;
            }

            @Override
            public void restart() throws Exception {
                a.restart();
                b.restart();
            }

            @Override
            public int restarts() {
                return a.restarts();
            }
        };
        RandomGames.Seating seating = new RandomGames.Seating() {
            @Override
            public Set<Integer> seats(int match) {
                return Set.of(0, 1);
            }

            @Override
            public void beforeMatch(int match) {
                if (match % 2 == 0) {
                    router.assign(a.client(), b.client());
                } else {
                    router.assign(b.client(), a.client());
                }
            }

            @Override
            public void afterMatch(int match, ForgeMatchRunner.Result r) {
                int seatOfA = match % 2;
                int wa = r.gamesWon()[seatOfA];
                int wb = r.gamesWon()[1 - seatOfA];
                gamesWon[0] += wa;
                gamesWon[1] += wb;
                if (wa != wb) {
                    matchesWon[wa > wb ? 0 : 1]++;
                }
                gamesAsSeat0[seatOfA == 0 ? 0 : 1] += r.games();
                wonAsSeat0[seatOfA == 0 ? 0 : 1] += r.gamesWon()[0];
                perMatch.add(String.format("match %s%d: %s in seat 0; games won %s %d, %s %d", prefix, match,
                    seatOfA == 0 ? labelA : labelB, labelA, wa, labelB, wb));
            }
        };
        RandomGames.Summary s = RandomGames.run(deckDir, args[1], args[2], matches, logDir, both, keys, prefix, seating);
        a.close();
        b.close();
        List<String> lines = new ArrayList<>(perMatch);
        lines.add(String.format("HEAD TO HEAD %s (A) vs %s (B): games won %d / %d, matches won %d / %d", labelA, labelB,
            gamesWon[0], gamesWon[1], matchesWon[0], matchesWon[1]));
        double[] ci = wilson(gamesWon[1], gamesWon[0] + gamesWon[1]);
        lines.add(String.format("%s game win rate %.1f%% (95%% Wilson %.1f-%.1f%%)", labelB,
            100.0 * gamesWon[1] / Math.max(1, gamesWon[0] + gamesWon[1]), 100 * ci[0], 100 * ci[1]));
        lines.add(String.format("seat 0 won %d of %d games with %s in it, %d of %d with %s in it", wonAsSeat0[0],
            gamesAsSeat0[0], labelA, wonAsSeat0[1], gamesAsSeat0[1], labelB));
        lines.addAll(s.lines());
        boolean pass = s.pass();
        for (AgentConnection c : List.of(a, b)) {
            int errors = c.protocolErrorCount();
            lines.add("protocol errors seen by agent-core " + (c == a ? labelA : labelB) + ": "
                + (errors < 0 ? "not reported" : errors));
            if (c.shardReport() != null) {
                lines.add("training shards " + (c == a ? labelA : labelB) + ": " + c.shardReport());
            }
            pass &= errors == 0 && c.shardsClean();
        }
        lines.add("RESULT " + (pass ? "PASS" : "FAIL"));
        lines.forEach(System.out::println);
        Files.write(logDir.resolve("summary.txt"), lines);
        System.exit(pass ? 0 : 1);
    }

    /** "random" for a side on the random policy (gate 2), else its budget multiplier ("x4"). */
    private static String label(String side, String multiplier) {
        String policy = System.getProperty("magicdosa.agentPolicy" + side, System.getProperty("magicdosa.agentPolicy", "random"));
        return policy.equals("random") ? "random"
            : "x" + System.getProperty("magicdosa.agentBudgetMultiplier" + side, multiplier);
    }

    private static Map<String, String> settings(Path logDir, String side, String label) throws Exception {
        Map<String, String> m = new java.util.HashMap<>();
        m.put("magicdosa.agentLabel", label);
        String multiplier = System.getProperty("magicdosa.agentBudgetMultiplier" + side);
        if (multiplier != null) {
            m.put("magicdosa.agentBudgetMultiplier", multiplier);
        }
        String policy = System.getProperty("magicdosa.agentPolicy" + side);
        if (policy != null) {
            m.put("magicdosa.agentPolicy", policy);
        }
        if (!Boolean.getBoolean("magicdosa.noShards")) {
            Path dir = logDir.resolve("shards-" + label);
            Files.createDirectories(dir);
            m.put("magicdosa.shards", dir.toAbsolutePath().toString());
        }
        return m;
    }

    private static double[] wilson(int k, int n) {
        if (n == 0) {
            return new double[] {0, 0};
        }
        double z = 1.96, p = (double) k / n, d = 1 + z * z / n;
        double c = (p + z * z / (2 * n)) / d, h = z * Math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / d;
        return new double[] {c - h, c + h};
    }
}
