package magicdosa.agent.tools;

import magicdosa.protocol.v1.Candidate;
import magicdosa.protocol.v1.DecisionRecord;
import magicdosa.protocol.v1.DecisionRequest;
import magicdosa.protocol.v1.DecisionStep;
import magicdosa.protocol.v1.DecisionType;
import magicdosa.protocol.v1.GameObject;
import magicdosa.protocol.v1.GameOver;
import magicdosa.protocol.v1.KillCheckOutcome;
import magicdosa.protocol.v1.LogHeader;
import magicdosa.protocol.v1.LogRecord;
import magicdosa.protocol.v1.SearchSkipReason;
import magicdosa.protocol.v1.Zone;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Behaviour metrics over game logs (2026-11-28 user directive: monitoring). <b>Observation only: never a training
 * signal or reward.</b> Every metric is judged on information the deciding seat had (its own request: public state
 * plus its own hand), never on privileged records. All definitions live here and in docs/behavior-metrics.md.
 *
 * <pre>BehaviorMetrics --label NAME [--cards DIR] [--out table.md] &lt;log dir or file&gt;...</pre>
 *
 * Per agent seat and game, grouped by matchup (own deck vs opponent deck) and seat mode:
 * <ol>
 *   <li>Confirmed kill not closed: turns in which the seat's attack declaration got the kill checker's KILL verdict
 *       ({@code DecisionResponse.kill_check}, protocol 17+) and the seat did not win that turn. Older logs: n/a.</li>
 *   <li>Free damage missed: the seat's attack declarations with at least one attack candidate while the opponent had no
 *       untapped creature on the battlefield (a potential blocker), where the seat declared no attacker.</li>
 *   <li>Land not played: the seat's own turns in which a PRIORITY request offered a land play and the seat played no
 *       land that turn (the game's last turn left out: it did not end).</li>
 *   <li>Pass share: PRIORITY requests with a real alternative (a candidate other than pass and mana abilities) answered
 *       with pass.</li>
 *   <li>Game length in turns; and, in games where the seat's value reached 0.9 (search root value, or the value head
 *       for a network-alone seat when logged), the turns from the first such decision to the game's end.</li>
 * </ol>
 * Seat mode: "search" if any of the seat's steps ran search, else "network" if a step was decided by the network
 * alone, else "delegated".
 */
public final class BehaviorMetrics {
    public static final double CONFIDENT = 0.9;

    /** Whether a battlefield object is a creature. */
    public interface Types {
        boolean isCreature(GameObject o);
    }

    /** One group's counts (or one seat's in one game). */
    public static final class Tally {
        public int games;
        public int wins;
        public int killKnownGames;      // games whose log carries kill_check (protocol 17+)
        public int killTurns;
        public int killTurnsApproximate;
        public int killNotClosed;
        public int freeCombats;
        public int freeMissed;
        public int landTurns;
        public int landMissed;
        public int priorityChoices;
        public int passed;
        public long turns;
        public int confidentGames;
        public long confidentTurnsToEnd;
        public int confidentWins;

        void add(Tally o) {
            games += o.games;
            wins += o.wins;
            killKnownGames += o.killKnownGames;
            killTurns += o.killTurns;
            killTurnsApproximate += o.killTurnsApproximate;
            killNotClosed += o.killNotClosed;
            freeCombats += o.freeCombats;
            freeMissed += o.freeMissed;
            landTurns += o.landTurns;
            landMissed += o.landMissed;
            priorityChoices += o.priorityChoices;
            passed += o.passed;
            turns += o.turns;
            confidentGames += o.confidentGames;
            confidentTurnsToEnd += o.confidentTurnsToEnd;
            confidentWins += o.confidentWins;
        }
    }

    /** One agent seat's result in one game. */
    public record SeatResult(int seat, String matchup, String mode, Tally tally) {}

    /** The metrics of every agent seat in one game; empty when the game has no game-over record. */
    public static List<SeatResult> game(List<LogRecord> records, Types types) {
        LogHeader h = records.stream().filter(LogRecord::hasHeader).map(LogRecord::getHeader).findFirst()
            .orElse(LogHeader.getDefaultInstance());
        GameOver over = records.stream().filter(LogRecord::hasGameOver).map(LogRecord::getGameOver).findFirst()
            .orElse(null);
        if (over == null) {
            return List.of();
        }
        Map<Integer, Set<Integer>> landPlays = new HashMap<>();   // seat -> turns with a land played
        for (LogRecord r : records) {
            if (r.hasEvent() && r.getEvent().hasPlayLand()) {
                landPlays.computeIfAbsent(r.getEvent().getPlayLand().getSeat(), k -> new HashSet<>())
                    .add(r.getEvent().getTurn());
            }
        }
        List<SeatResult> out = new ArrayList<>();
        for (int seat : h.getAgentSeatsList()) {
            Tally t = new Tally();
            t.games = 1;
            boolean won = over.hasWinnerSeat() && over.getWinnerSeat() == seat;
            t.wins = won ? 1 : 0;
            t.turns = over.getTurns();
            t.killKnownGames = h.getProtocolVersion() >= 17 ? 1 : 0;
            boolean searched = false;
            boolean netAlone = false;
            Set<Integer> killTurns = new HashSet<>();
            Set<Integer> killExact = new HashSet<>();
            Set<Integer> landOffered = new HashSet<>();
            int confidentTurn = -1;
            for (LogRecord r : records) {
                if (!r.hasDecision() || r.getDecision().getRequest().getSeat() != seat
                    || r.getDecision().getRequest().getDecisionId() == 0) {
                    continue;
                }
                DecisionRecord d = r.getDecision();
                DecisionRequest q = d.getRequest();
                int turn = q.getObservation().getGame().getTurn();
                for (DecisionStep st : d.getResponse().getStepsList()) {
                    searched |= st.getMethod().hasRan();
                    netAlone |= st.getMethod().hasSkipped()
                        && st.getMethod().getSkipped() != SearchSkipReason.SEARCH_SKIP_REASON_UNSPECIFIED;
                    if (confidentTurn < 0 && (st.getMethod().hasRan() ? st.getMethod().getRan().getRootValue() >= CONFIDENT
                        : st.getDiagnostics().hasValue() && st.getDiagnostics().getValue() >= CONFIDENT)) {
                        confidentTurn = turn;
                    }
                }
                switch (q.getType()) {
                    case DECISION_TYPE_DECLARE_ATTACKERS -> {
                        if (d.getResponse().getKillCheck() == KillCheckOutcome.KILL_CHECK_OUTCOME_KILL) {
                            killTurns.add(turn);
                            if (!d.getResponse().getKillCheckApproximate()) {
                                killExact.add(turn);
                            }
                        }
                        boolean canAttack = q.getCandidatesList().stream().anyMatch(Candidate::hasAttack);
                        boolean blocker = q.getObservation().getObjectsList().stream().anyMatch(o ->
                            o.getZone() == Zone.ZONE_BATTLEFIELD && o.getControllerSeat() != seat && !o.getTapped()
                                && types.isCreature(o));
                        if (canAttack && !blocker) {
                            t.freeCombats++;
                            boolean attacked = d.getAppliedList().stream()
                                .anyMatch(i -> i < q.getCandidatesCount() && q.getCandidates(i).hasAttack());
                            if (!attacked) {
                                t.freeMissed++;
                            }
                        }
                    }
                    case DECISION_TYPE_PRIORITY -> {
                        if (q.getObservation().getGame().getActiveSeat() == seat
                            && q.getCandidatesList().stream().anyMatch(Candidate::hasPlayLand)) {
                            landOffered.add(turn);
                        }
                        boolean alternative = q.getCandidatesList().stream().anyMatch(c -> !c.hasPass()
                            && !(c.hasActivateAbility() && c.getActivateAbility().getManaAbility()));
                        if (alternative) {
                            t.priorityChoices++;
                            boolean pass = !d.getAppliedList().isEmpty() && d.getAppliedList().stream()
                                .allMatch(i -> i < q.getCandidatesCount() && q.getCandidates(i).hasPass());
                            if (pass) {
                                t.passed++;
                            }
                        }
                    }
                    default -> { }
                }
            }
            for (int turn : killTurns) {
                t.killTurns++;
                if (!(won && over.getTurns() == turn)) {
                    t.killNotClosed++;
                }
            }
            t.killTurnsApproximate = killTurns.size() - killExact.size();
            Set<Integer> played = landPlays.getOrDefault(seat, Set.of());
            for (int turn : landOffered) {
                if (turn != over.getTurns()) {
                    t.landTurns++;
                    if (!played.contains(turn)) {
                        t.landMissed++;
                    }
                }
            }
            if (confidentTurn >= 0) {
                t.confidentGames = 1;
                t.confidentTurnsToEnd = over.getTurns() - confidentTurn;
                t.confidentWins = won ? 1 : 0;
            }
            String own = deck(h, seat);
            String opp = h.getPlayersList().stream().filter(p -> p.getSeat() != seat).map(p -> p.getDeckName())
                .findFirst().orElse("?");
            out.add(new SeatResult(seat, own + " vs " + opp, searched ? "search" : netAlone ? "network" : "delegated", t));
        }
        return out;
    }

    private static String deck(LogHeader h, int seat) {
        return h.getPlayersList().stream().filter(p -> p.getSeat() == seat).map(p -> p.getDeckName()).findFirst().orElse("?");
    }

    // --- table ----------------------------------------------------------------------------------------------------

    /** Markdown table: one row per (matchup, mode), then an "all" row per mode. */
    public static List<String> table(Map<String, Tally> groups) {
        List<String> out = new ArrayList<>();
        out.add("| matchup | mode | games | win % | ① kill not closed | ② free damage missed | ③ land not played"
            + " | ④ pass share | ⑤ mean turns | ⑤ turns from value ≥ 0.9 to end (games, won %) |");
        out.add("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |");
        groups.forEach((key, t) -> out.add(row(key.split("\t")[0], key.split("\t")[1], t)));
        return out;
    }

    public static String row(String matchup, String mode, Tally t) {
        String kill = t.killKnownGames < t.games ? (t.killKnownGames == 0 ? "n/a (protocol < 17)" : "partial")
            : share(t.killNotClosed, t.killTurns) + (t.killTurnsApproximate > 0 ? " (approx " + t.killTurnsApproximate + ")" : "");
        String confident = t.confidentGames == 0 ? "-" : String.format(Locale.ROOT, "%.1f (%d, %.0f%%)",
            (double) t.confidentTurnsToEnd / t.confidentGames, t.confidentGames, 100.0 * t.confidentWins / t.confidentGames);
        return "| " + matchup + " | " + mode + " | " + t.games + " | "
            + String.format(Locale.ROOT, "%.1f", 100.0 * t.wins / Math.max(1, t.games)) + " | " + kill + " | "
            + share(t.freeMissed, t.freeCombats) + " | " + share(t.landMissed, t.landTurns) + " | "
            + share(t.passed, t.priorityChoices) + " | "
            + String.format(Locale.ROOT, "%.1f", (double) t.turns / Math.max(1, t.games)) + " | " + confident + " |";
    }

    static String share(int k, int n) {
        return n == 0 ? "-" : String.format(Locale.ROOT, "%.1f%% (%d/%d)", 100.0 * k / n, k, n);
    }

    // --- card types from Forge's card scripts -----------------------------------------------------------------------

    /**
     * Creature by type line: tokens by their characteristics; cards by the Forge card script of their oracle id
     * (res/cardsfolder, read as text, no Forge start-up). Face-down objects and cards whose type is unknown count as
     * creatures (a possible blocker), so ② never counts a combat as free on missing data.
     */
    public static final class CardScripts implements Types {
        private final Path dir;
        private final Map<String, String> forgeName = new HashMap<>();          // oracle id -> Forge card name
        private final Map<Character, Map<String, List<String>>> byLetter = new HashMap<>(); // name -> Types lines

        public CardScripts(Path cardsfolder) {
            this.dir = cardsfolder;
            try (InputStream in = magicdosa.adapter.forge.CardKeys.class.getResourceAsStream("oracle-ids.tsv")) {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String l;
                while ((l = r.readLine()) != null) {
                    String[] f = l.split("\t");
                    if (!l.startsWith("#") && f.length >= 3 && f[0].equals("card")) {
                        forgeName.putIfAbsent(f[2], f[1]);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public boolean isCreature(GameObject o) {
            if (o.hasCharacteristics() && o.getCharacteristics().getTypesCount() > 0) {
                return o.getCharacteristics().getTypesList().contains("Creature");
            }
            if (o.getFaceDown() || o.getOracleId().isEmpty()) {
                return true;
            }
            String name = forgeName.get(o.getOracleId());
            List<String> lines = name == null ? null : scripts(name).get(name);
            if (lines == null || lines.isEmpty()) {
                return true;
            }
            String typeLine = lines.get(o.getFace() < lines.size() ? o.getFace() : 0);
            return Set.of(typeLine.split(" ")).contains("Creature");
        }

        private Map<String, List<String>> scripts(String name) {
            char letter = Character.toLowerCase(name.charAt(0));
            return byLetter.computeIfAbsent(letter, c -> {
                Map<String, List<String>> m = new HashMap<>();
                Path sub = dir.resolve(String.valueOf(c));
                if (!Files.isDirectory(sub)) {
                    return m;
                }
                try (Stream<Path> files = Files.list(sub)) {
                    for (Path f : (Iterable<Path>) files::iterator) {
                        String n = null;
                        List<String> types = new ArrayList<>();
                        for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                            if (n == null && l.startsWith("Name:")) {
                                n = l.substring(5).trim();
                            } else if (l.startsWith("Types:")) {
                                types.add(l.substring(6).trim());
                            }
                        }
                        if (n != null) {
                            m.putIfAbsent(n, types);
                        }
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                return m;
            });
        }
    }

    // --- main -----------------------------------------------------------------------------------------------------

    public static void main(String[] args) throws IOException {
        String label = "run";
        Path cards = Path.of("res/cardsfolder");
        Path out = null;
        List<Path> inputs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--label" -> label = args[++i];
                case "--cards" -> cards = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                default -> inputs.add(Path.of(args[i]));
            }
        }
        if (inputs.isEmpty() || !Files.isDirectory(cards)) {
            System.err.println("usage: BehaviorMetrics --label NAME [--cards res/cardsfolder] [--out table.md] <log dir or file>...");
            System.exit(2);
        }
        Types types = new CardScripts(cards);
        Map<String, Tally> groups = new TreeMap<>();
        Map<String, Tally> all = new TreeMap<>();
        int logs = 0;
        int skipped = 0;
        for (Path in : inputs) {
            List<Path> files;
            try (Stream<Path> s = Files.isDirectory(in) ? Files.walk(in) : Stream.of(in)) {
                files = s.filter(p -> p.toString().endsWith(".pb.gz")).sorted().toList();
            }
            for (Path f : files) {
                List<SeatResult> seats = game(LogText.read(f), types);
                logs++;
                if (seats.isEmpty()) {
                    skipped++;
                }
                for (SeatResult s : seats) {
                    groups.computeIfAbsent(s.matchup() + "\t" + s.mode(), k -> new Tally()).add(s.tally());
                    all.computeIfAbsent("all\t" + s.mode(), k -> new Tally()).add(s.tally());
                }
            }
        }
        groups.putAll(all);
        List<String> lines = new ArrayList<>();
        lines.add("## " + label);
        lines.add("");
        lines.add("Logs " + logs + " (" + skipped + " without a game-over record, left out); inputs: " + inputs
            + ". Definitions: docs/behavior-metrics.md. Observation only, never a training signal.");
        lines.add("");
        lines.addAll(table(groups));
        if (out == null) {
            lines.forEach(System.out::println);
        } else {
            Files.createDirectories(out.toAbsolutePath().getParent());
            Files.write(out, lines, StandardCharsets.UTF_8);
            lines.forEach(System.out::println);
        }
    }
}
