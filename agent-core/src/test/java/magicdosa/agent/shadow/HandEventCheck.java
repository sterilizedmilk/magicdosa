package magicdosa.agent.shadow;

import magicdosa.protocol.v1.Event;
import magicdosa.protocol.v1.GameObject;
import magicdosa.protocol.v1.LogRecord;
import magicdosa.protocol.v1.Observation;
import magicdosa.protocol.v1.Zone;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Phase-2 check (design: 개요 2단계 "사건 로그에 손패 출입 사건(턴, 경로, 공개된 카드)이 빠짐없이 남는지 확인"): for
 * every agent seat's stream, each hand's contents are rebuilt from the game-start snapshot and the zone-move events
 * alone, and compared with the hand in every later observation (decision requests and turn-start snapshots): the same
 * object IDs. Every move into or out of a hand must also say its turn and step (after the game start), its zones, and
 * the card when the seat could see it (its own hand, or a public zone on the other side). No Forge needed.
 *
 * <pre>mvn -pl agent-core exec:exec "-Dexec.mainClass=magicdosa.agent.shadow.HandEventCheck" "-Dexec.args=&lt;abs log dir&gt;"</pre>
 */
public final class HandEventCheck {
    private HandEventCheck() {}

    public static void main(String[] args) throws Exception {
        List<Path> logs;
        try (Stream<Path> s = Files.walk(Path.of(args[0]))) {
            logs = s.filter(p -> p.toString().endsWith(".pb.gz")).sorted().toList();
        }
        long observations = 0, mismatched = 0, moves = 0, badMoves = 0, streams = 0, pending = 0;
        Map<String, Integer> problems = new TreeMap<>();
        List<String> samples = new ArrayList<>();
        for (Path log : logs) {
            List<LogRecord> records = GameRecord.read(log);
            for (int seat : records.get(0).getHeader().getAgentSeatsList()) {
                streams++;
                GameRecord rec = GameRecord.of(log, records, seat);
                // the game-start snapshot (the last of turn 0) and the hands in it
                GameRecord.Snapshot start = null;
                for (GameRecord.Snapshot s : rec.snapshots) {
                    if (s.snapshot().getObservation().getGame().getTurn() == 0) {
                        start = s;
                    }
                }
                if (start == null) {
                    continue;
                }
                long from = start.snapshot().getSeq();
                Map<Integer, Set<Long>> hands = hands(start.snapshot().getObservation());
                // observations after the start, by the sequence number they were made at
                TreeMap<Long, List<Observation>> at = new TreeMap<>();
                for (GameRecord.Decision d : rec.decisions) {
                    var r = d.record().getRequest();
                    if (r.getAppliedSeq() > from) {
                        at.computeIfAbsent(r.getAppliedSeq(), k -> new ArrayList<>()).add(r.getObservation());
                    }
                }
                for (GameRecord.Snapshot s : rec.snapshots) {
                    if (s.snapshot().getSeq() > from) {
                        at.computeIfAbsent(s.snapshot().getSeq(), k -> new ArrayList<>()).add(s.snapshot().getObservation());
                    }
                }
                var events = rec.events.tailMap(from, false);
                var it = events.entrySet().iterator();
                Map.Entry<Long, Event> next = it.hasNext() ? it.next() : null;
                for (Map.Entry<Long, List<Observation>> point : at.entrySet()) {
                    while (next != null && next.getKey() <= point.getKey()) {
                        Event e = next.getValue();
                        if (e.getBodyCase() == Event.BodyCase.ZONE_MOVE) {
                            var m = e.getZoneMove();
                            boolean out = m.getFrom() == Zone.ZONE_HAND;
                            boolean in = m.getTo() == Zone.ZONE_HAND;
                            if (out || in) {
                                moves++;
                                String bad = null;
                                if (e.getTurn() == 0 && e.getStep() != magicdosa.protocol.v1.Step.STEP_PREGAME) {
                                    bad = "no turn or step";
                                } else if (m.getOwnerSeat() == seat && m.getOracleId().isEmpty()) {
                                    bad = "own card without identity";
                                } else if (in && m.getOwnerSeat() != seat && publicZone(m.getFrom()) && m.getOracleId().isEmpty()) {
                                    bad = "public card into the other hand without identity";
                                } else if (out && publicZone(m.getTo()) && m.getOracleId().isEmpty() && !m.getFaceDown()) {
                                    bad = "hand card into a public zone without identity";
                                }
                                if (bad != null) {
                                    badMoves++;
                                    problems.merge(bad, 1, Integer::sum);
                                    if (samples.size() < 20) {
                                        samples.add(rec.header.getGameId() + "/seat" + seat + " seq " + e.getSeq() + ": " + bad);
                                    }
                                }
                            }
                            if (out) {
                                hands.computeIfAbsent(m.getOwnerSeat(), k -> new HashSet<>()).remove(m.getPrevId());
                            }
                            if (in) {
                                hands.computeIfAbsent(m.getOwnerSeat(), k -> new HashSet<>()).add(m.getId());
                            }
                        }
                        next = it.hasNext() ? it.next() : null;
                    }
                    for (Observation o : point.getValue()) {
                        observations++;
                        Map<Integer, Set<Long>> real = hands(o);
                        for (int s : new HashSet<>(List.of(0, 1))) {
                            Set<Long> a = real.getOrDefault(s, Set.of());
                            Set<Long> b = hands.getOrDefault(s, Set.of());
                            if (!a.equals(b) && explainedLater(events, point.getKey(), a, b)) {
                                pending++; // asked during an action: its moves come with the action, right after
                                continue;
                            }
                            if (!a.equals(b)) {
                                mismatched++;
                                problems.merge("hand of seat " + (s == seat ? "self" : "opponent") + " differs from the events", 1,
                                    Integer::sum);
                                if (samples.size() < 20) {
                                    samples.add(rec.header.getGameId() + "/seat" + seat + " seq " + point.getKey() + ": hand of seat "
                                        + s + " observed " + a + ", from events " + b);
                                }
                                hands.put(s, new HashSet<>(a)); // go on from the observed hand
                            }
                        }
                    }
                }
            }
        }
        System.out.println("hand events (design: 손패 출입 사건): " + streams + " streams, " + moves + " moves into or out of a"
            + " hand, " + badMoves + " incomplete; " + observations + " observations, " + mismatched
            + " hands that differ from the events (" + pending + " more were asked during an action whose moves the stream"
            + " reports with the action, right after the request)");
        problems.forEach((k, v) -> System.out.println("  " + v + "  " + k));
        samples.forEach(x -> System.out.println("  " + x));
        System.out.println("RESULT " + (badMoves == 0 && mismatched == 0 ? "PASS" : "FAIL"));
        System.exit(badMoves == 0 && mismatched == 0 ? 0 : 1);
    }

    /**
     * An observation made during an action (a cast asks for targets, X, costs) is ahead of the stream: the action's
     * moves follow the request. True when every difference is such a move among the next events.
     */
    private static boolean explainedLater(java.util.NavigableMap<Long, Event> events, long seq, Set<Long> observed,
                                          Set<Long> fromEvents) {
        Set<Long> leaving = new HashSet<>(fromEvents);
        leaving.removeAll(observed);
        Set<Long> coming = new HashSet<>(observed);
        coming.removeAll(fromEvents);
        int n = 0;
        for (Event e : events.tailMap(seq, false).values()) {
            if (++n > 40) {
                break;
            }
            if (e.getBodyCase() == Event.BodyCase.ZONE_MOVE) {
                leaving.remove(e.getZoneMove().getPrevId());
                coming.remove(e.getZoneMove().getId());
            }
        }
        return leaving.isEmpty() && coming.isEmpty();
    }

    private static Map<Integer, Set<Long>> hands(Observation o) {
        Map<Integer, Set<Long>> out = new HashMap<>();
        for (GameObject g : o.getObjectsList()) {
            if (g.getZone() == Zone.ZONE_HAND) {
                out.computeIfAbsent(g.getOwnerSeat(), k -> new HashSet<>()).add(g.getId());
            }
        }
        return out;
    }

    private static boolean publicZone(Zone z) {
        return z == Zone.ZONE_BATTLEFIELD || z == Zone.ZONE_GRAVEYARD || z == Zone.ZONE_EXILE || z == Zone.ZONE_STACK
            || z == Zone.ZONE_COMMAND;
    }
}
