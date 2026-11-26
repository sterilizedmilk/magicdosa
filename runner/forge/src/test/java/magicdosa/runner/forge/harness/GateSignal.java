package magicdosa.runner.forge.harness;

import magicdosa.protocol.v1.Candidate;
import magicdosa.protocol.v1.Decider;
import magicdosa.protocol.v1.DecisionRecord;
import magicdosa.protocol.v1.DecisionRequest;
import magicdosa.protocol.v1.DecisionResponse;
import magicdosa.protocol.v1.DecisionType;
import magicdosa.protocol.v1.GameOver;
import magicdosa.protocol.v1.LogHeader;
import magicdosa.protocol.v1.LogRecord;
import magicdosa.protocol.v1.SearchRun;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/**
 * Gate 1 step 1 read-out (2026-11-26 planning order; measurement only, offline): from search game logs, the evaluation
 * signal (does the root value the search logged, the root seat's expected win rate, call the game's real winner, and
 * how by turn), the agent's results, and what the searched decisions chose (priority pass share, deciders).
 *
 * <pre>GateSignal &lt;log root&gt; [csv out]</pre>
 *
 * The csv has one row per searched agent decision: game, match, game number, seat, turn, type, root value, simulations,
 * candidates, chosen pass, agent won.
 */
public final class GateSignal {
    private GateSignal() {
    }

    private record Row(String game, String match, int gameNumber, int seat, int turn, DecisionType type, double value,
                       long simulations, int candidates, boolean pass, boolean actionOffered) {
    }

    private record Game(String id, String match, int seat, Integer winner, int turns, List<Row> rows,
                        Map<String, Integer> deciders) {
        boolean won() {
            return winner != null && winner == seat;
        }
    }

    public static void main(String[] args) throws Exception {
        List<Path> files;
        try (var s = Files.walk(Path.of(args[0]))) {
            files = s.filter(p -> p.toString().endsWith(".pb.gz")).sorted().collect(Collectors.toList());
        }
        List<Game> games = new ArrayList<>();
        for (Path f : files) {
            try {
                games.add(read(f));
            } catch (IllegalStateException | java.io.EOFException e) {
                System.out.println("left out (unfinished game, a stopped run): " + f.getFileName());
            }
        }
        report(games);
        if (args.length > 1) {
            List<String> lines = new ArrayList<>();
            lines.add("game,match,game_number,seat,turn,type,root_value,simulations,candidates,chose_pass,action_offered,agent_won,draw");
            for (Game g : games) {
                for (Row r : g.rows) {
                    lines.add(String.join(",", r.game, r.match, String.valueOf(r.gameNumber), String.valueOf(r.seat),
                        String.valueOf(r.turn), r.type.name().replace("DECISION_TYPE_", ""), String.valueOf(r.value),
                        String.valueOf(r.simulations), String.valueOf(r.candidates), String.valueOf(r.pass),
                        String.valueOf(r.actionOffered), String.valueOf(g.won()), String.valueOf(g.winner == null)));
                }
            }
            Files.write(Path.of(args[1]), lines);
        }
    }

    /**
     * Head-to-head logs: {@code -DgateSignal.side=4} reads the side that searched with 4 times the base budget per
     * candidate ({@code -DgateSignal.basePerCandidate}, default 12) as the agent; unset, the header's first agent seat.
     */
    private static final int SIDE_MULTIPLIER = Integer.getInteger("gateSignal.side", 0);
    private static final int BASE_PER_CANDIDATE = Integer.getInteger("gateSignal.basePerCandidate", 12);

    static Game read(Path f) throws Exception {
        LogHeader header = null;
        GameOver over = null;
        List<Row> rows = new ArrayList<>();
        Map<String, Integer> deciders = new TreeMap<>();
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(f)))) {
            LogRecord r;
            while ((r = LogRecord.parseDelimitedFrom(in)) != null) {
                switch (r.getRecordCase()) {
                    case HEADER -> header = r.getHeader();
                    case GAME_OVER -> over = r.getGameOver();
                    case DECISION -> {
                        DecisionRecord d = r.getDecision();
                        if (!d.hasRequest() || !d.hasResponse()) {
                            continue;
                        }
                        DecisionRequest q = d.getRequest();
                        DecisionResponse resp = d.getResponse();
                        Decider who = d.getDecider();
                        String skip = resp.getStepsCount() > 0 && resp.getSteps(0).getMethod().hasSkipped()
                            ? " (" + resp.getSteps(0).getMethod().getSkipped().name().replace("SEARCH_SKIP_REASON_", "") + ")" : "";
                        deciders.merge(who.name().replace("DECIDER_", "") + skip, 1, Integer::sum);
                        if (resp.getStepsCount() == 0 || !resp.getSteps(0).getMethod().hasRan()) {
                            continue;
                        }
                        SearchRun ran = resp.getSteps(0).getMethod().getRan();
                        if (ran.getSimulations() == 0 || ran.getDeterminizations() == 0) {
                            continue; // every simulation failed: no value
                        }
                        boolean pass = false;
                        boolean action = false;
                        if (q.getType() == DecisionType.DECISION_TYPE_PRIORITY) {
                            for (Candidate c : q.getCandidatesList()) {
                                action |= !c.hasPass() && !(c.hasActivateAbility() && c.getActivateAbility().getManaAbility());
                            }
                            pass = resp.getChosenCount() > 0 && q.getCandidates(resp.getChosen(0)).hasPass();
                        }
                        rows.add(new Row(q.getGameId(), "", 0, q.getSeat(), q.getObservation().getGame().getTurn(),
                            q.getType(), ran.getRootValue(), ran.getSimulations(), q.getCandidatesCount(), pass, action));
                    }
                    default -> { }
                }
            }
        }
        if (header == null || over == null) {
            throw new IllegalStateException(f + ": no header or no game end");
        }
        int seat = header.getAgentSeats(0);
        String match = header.getMatchId();
        int number = header.getGameNumber();
        if (SIDE_MULTIPLIER > 0) {
            // a head-to-head log (HeadToHead: both seats searched, each by its own agent-core): keep the side whose
            // searches ran SIDE_MULTIPLIER times the base budget per candidate; its seat is the agent's in this game
            List<Row> side = new ArrayList<>();
            for (Row r : rows) {
                long perCandidate = r.simulations / Math.max(1, r.candidates);
                if (Math.round((double) perCandidate / BASE_PER_CANDIDATE) == SIDE_MULTIPLIER) {
                    side.add(r);
                }
            }
            rows = side;
            if (!side.isEmpty()) {
                seat = side.get(0).seat;
            }
        }
        List<Row> fixed = new ArrayList<>();
        for (Row r : rows) {
            fixed.add(new Row(r.game, match, number, r.seat, r.turn, r.type, r.value, r.simulations, r.candidates, r.pass,
                r.actionOffered));
        }
        return new Game(header.getGameId(), match, seat, over.hasWinnerSeat() ? over.getWinnerSeat() : null,
            over.getTurns(), fixed, deciders);
    }

    static void report(List<Game> games) {
        int n = 0, wins = 0, draws = 0;
        Map<String, int[]> matches = new TreeMap<>();
        Map<String, Integer> deciders = new TreeMap<>();
        for (Game g : games) {
            g.deciders.forEach((k, v) -> deciders.merge(k, v, Integer::sum));
            if (g.winner == null) {
                draws++;
                continue;
            }
            n++;
            wins += g.won() ? 1 : 0;
            int[] m = matches.computeIfAbsent(g.match, k -> new int[2]);
            m[g.won() ? 0 : 1]++;
        }
        int matchWins = 0;
        for (int[] m : matches.values()) {
            matchWins += m[0] > m[1] ? 1 : 0;
        }
        System.out.printf("games %d (draws %d left out), agent game wins %d = %.1f%% (95%% Wilson %s); matches %d, agent match wins %d%n",
            games.size(), draws, wins, 100.0 * wins / n, wilson(wins, n), matches.size(), matchWins);
        System.out.println("deciders (agent seat records): " + deciders);

        // evaluation signal
        List<Row> all = new ArrayList<>();
        Map<String, Boolean> won = new TreeMap<>();
        for (Game g : games) {
            if (g.winner != null) {
                all.addAll(g.rows);
                won.put(g.id, g.won());
            }
        }
        System.out.println();
        System.out.println("evaluation signal: root value > 0.5 calls the agent's win (searched decisions with a value, draws left out)");
        line("all decisions", all, won);
        Map<String, List<Row>> byTurn = new TreeMap<>();
        for (Row r : all) {
            byTurn.computeIfAbsent(bucket(r.turn), k -> new ArrayList<>()).add(r);
        }
        byTurn.forEach((k, v) -> line("turn " + k, v, won));
        Map<String, List<Row>> byType = new TreeMap<>();
        for (Row r : all) {
            byType.computeIfAbsent(r.type.name().replace("DECISION_TYPE_", ""), k -> new ArrayList<>()).add(r);
        }
        byType.forEach((k, v) -> line("type " + k, v, won));
        // per game: each game weighs the same (mean of its decisions' hits), and its last searched decision
        double perGame = 0;
        int gamesWithRows = 0, lastHits = 0;
        for (Game g : games) {
            if (g.winner == null || g.rows.isEmpty()) {
                continue;
            }
            int hits = 0;
            for (Row r : g.rows) {
                hits += (r.value > 0.5) == g.won() ? 1 : 0;
            }
            perGame += (double) hits / g.rows.size();
            gamesWithRows++;
            Row last = g.rows.get(g.rows.size() - 1);
            lastHits += (last.value > 0.5) == g.won() ? 1 : 0;
        }
        System.out.printf("  per game (each game weighs the same): %.1f%% of decisions called right, %d games; last searched decision right in %d = %.1f%%%n",
            100 * perGame / gamesWithRows, gamesWithRows, lastHits, 100.0 * lastHits / gamesWithRows);
        // calibration: value bins against the share of those decisions' games won
        System.out.println("  calibration (root value bin: decisions, share of them in games the agent won):");
        for (int b = 0; b < 10; b++) {
            int cnt = 0, w = 0;
            for (Row r : all) {
                if (Math.min(9, (int) (r.value * 10)) == b) {
                    cnt++;
                    w += won.get(r.game) ? 1 : 0;
                }
            }
            if (cnt > 0) {
                System.out.printf("    %.1f-%.1f: %6d  %.1f%%%n", b / 10.0, (b + 1) / 10.0, cnt, 100.0 * w / cnt);
            }
        }

        // priority
        int prio = 0, prioPass = 0, prioAction = 0, prioActionPass = 0;
        for (Row r : all) {
            if (r.type == DecisionType.DECISION_TYPE_PRIORITY) {
                prio++;
                prioPass += r.pass ? 1 : 0;
                if (r.actionOffered) {
                    prioAction++;
                    prioActionPass += r.pass ? 1 : 0;
                }
            }
        }
        System.out.println();
        System.out.printf("searched priority decisions %d: pass chosen %.1f%%; with an action other than pass and mana abilities %d: pass chosen %.1f%%%n",
            prio, 100.0 * prioPass / Math.max(1, prio), prioAction, 100.0 * prioActionPass / Math.max(1, prioAction));
    }

    private static void line(String label, List<Row> rows, Map<String, Boolean> won) {
        int hits = 0;
        double brier = 0, wonSum = 0, lostSum = 0;
        int wonN = 0, lostN = 0;
        for (Row r : rows) {
            boolean w = won.get(r.game);
            hits += (r.value > 0.5) == w ? 1 : 0;
            brier += (r.value - (w ? 1 : 0)) * (r.value - (w ? 1 : 0));
            if (w) {
                wonSum += r.value;
                wonN++;
            } else {
                lostSum += r.value;
                lostN++;
            }
        }
        System.out.printf("  %-28s %7d decisions: right %.1f%%, AUC %.3f, Brier %.3f, mean value in won games %.3f (%d), in lost games %.3f (%d)%n",
            label, rows.size(), 100.0 * hits / Math.max(1, rows.size()), auc(rows, won), brier / Math.max(1, rows.size()),
            wonN == 0 ? Double.NaN : wonSum / wonN, wonN, lostN == 0 ? Double.NaN : lostSum / lostN, lostN);
    }

    /** The chance that a decision of a won game has a higher root value than one of a lost game (ties half): no threshold. */
    private static double auc(List<Row> rows, Map<String, Boolean> won) {
        List<Row> sorted = new ArrayList<>(rows);
        sorted.sort(java.util.Comparator.comparingDouble(Row::value));
        double rankSumWon = 0;
        long nWon = 0, nLost = 0;
        int i = 0;
        while (i < sorted.size()) {
            int j = i;
            while (j < sorted.size() && sorted.get(j).value == sorted.get(i).value) {
                j++;
            }
            double rank = (i + 1 + j) / 2.0; // average rank of the tied run
            for (int k = i; k < j; k++) {
                if (won.get(sorted.get(k).game)) {
                    rankSumWon += rank;
                    nWon++;
                } else {
                    nLost++;
                }
            }
            i = j;
        }
        if (nWon == 0 || nLost == 0) {
            return Double.NaN;
        }
        return (rankSumWon - nWon * (nWon + 1) / 2.0) / ((double) nWon * nLost);
    }

    private static String bucket(int turn) {
        if (turn <= 2) {
            return "01-02";
        }
        if (turn <= 4) {
            return "03-04";
        }
        if (turn <= 6) {
            return "05-06";
        }
        if (turn <= 8) {
            return "07-08";
        }
        if (turn <= 10) {
            return "09-10";
        }
        if (turn <= 14) {
            return "11-14";
        }
        return "15+";
    }

    private static String wilson(int k, int n) {
        if (n == 0) {
            return "n/a";
        }
        double z = 1.96, p = (double) k / n;
        double den = 1 + z * z / n;
        double c = (p + z * z / (2 * n)) / den;
        double h = z * Math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / den;
        return String.format("%.1f-%.1f%%", 100 * (c - h), 100 * (c + h));
    }
}
