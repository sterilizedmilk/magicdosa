package magicdosa.runner.forge.harness;

import magicdosa.protocol.Versions;
import magicdosa.protocol.v1.Event;
import magicdosa.protocol.v1.GameObject;
import magicdosa.protocol.v1.HiddenIdentity;
import magicdosa.protocol.v1.LogRecord;
import magicdosa.protocol.v1.Observation;
import magicdosa.protocol.v1.Privileged;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Reads game logs back and checks their shape (design: 학습 데이터 경로 "프로토콜 로그가 원본"): header first with the
 * current protocol version, gapless event sequence per seat, a request in every agent decision record, game over last.
 * Per seat it also checks the event order rules: an action comes before the zone move it causes (CastSpell before the
 * spell's move to the stack, PlayLand before the land's move), and a triggered ability is announced by AbilityTriggered
 * before it is put on the stack or removed (design: 엔티티 ID "유발된 순간"), and a reveal names the card only in the
 * streams of seats that saw it (Q10). Step bundles (Q18): each bundle's steps are numbered from 1 without gaps, the first
 * step's decision ID names it, and a BundleEnd with the step count closes it before the game ends (bundles may nest:
 * a mana ability activated while paying can ask its own payment).
 */
public final class LogCheck {
    private LogCheck() {}

    /** Problems found in every {@code *.pb.gz} under {@code dir}; empty when all logs are well formed. */
    public static List<String> check(Path dir) throws IOException {
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".pb.gz")).sorted().toList()) {
                problems.addAll(checkFile(f));
            }
        }
        return problems;
    }

    static List<String> checkFile(Path f) throws IOException {
        List<String> problems = new ArrayList<>();
        Map<Integer, Long> seq = new HashMap<>();
        Map<Integer, Order> order = new HashMap<>();
        Map<String, Privileged> privileged = new HashMap<>();
        Map<Long, Integer> openBundles = new HashMap<>();
        Set<Long> closedBundles = new HashSet<>();
        int n = 0;
        LogRecord last = null;
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(f)))) {
            LogRecord r;
            while ((r = LogRecord.parseDelimitedFrom(in)) != null) {
                if (n == 0 && (!r.hasHeader() || r.getHeader().getProtocolVersion() != Versions.PROTOCOL)) {
                    problems.add(f.getFileName() + ": first record is not a current header");
                }
                if (r.hasEvent()) {
                    long expected = seq.getOrDefault(r.getEvent().getViewerSeat(), 0L) + 1;
                    if (r.getEvent().getSeq() != expected) {
                        problems.add(f.getFileName() + ": seat " + r.getEvent().getViewerSeat() + " seq " + r.getEvent().getSeq()
                            + " expected " + expected);
                    }
                    seq.put(r.getEvent().getViewerSeat(), r.getEvent().getSeq());
                    String p = order.computeIfAbsent(r.getEvent().getViewerSeat(), k -> new Order()).check(r.getEvent());
                    if (p != null) {
                        problems.add(f.getFileName() + ": seat " + r.getEvent().getViewerSeat() + " seq " + r.getEvent().getSeq() + ": " + p);
                    }
                }
                if (r.hasPrivileged()) {
                    privileged.put(r.getPrivileged().getDecisionId() + "/" + r.getPrivileged().getSeat(), r.getPrivileged().getPrivileged());
                }
                if (r.hasDecision() && r.getDecision().hasRequest()) {
                    var q = r.getDecision().getRequest();
                    Privileged truth = privileged.get(q.getDecisionId() + "/" + q.getSeat());
                    if (truth != null) {
                        String p = libraryPositions(q.getObservation(), truth);
                        if (p != null) {
                            problems.add(f.getFileName() + ": decision " + q.getDecisionId() + ": " + p);
                        }
                    }
                }
                if (r.hasDecision() && r.getDecision().hasRequest() && r.getDecision().getRequest().hasBundle()) {
                    var q = r.getDecision().getRequest();
                    var b = q.getBundle();
                    Integer prev = openBundles.get(b.getId());
                    boolean ok = b.getStep() == 1
                        ? b.getId() == q.getDecisionId() && prev == null && !closedBundles.contains(b.getId())
                        : prev != null && prev == b.getStep() - 1;
                    if (!ok) {
                        problems.add(f.getFileName() + ": decision " + q.getDecisionId() + ": bundle " + b.getId() + " step "
                            + b.getStep() + " after " + prev);
                    }
                    openBundles.put(b.getId(), b.getStep());
                }
                if (r.hasBundleEnd()) {
                    var e = r.getBundleEnd();
                    Integer prev = openBundles.remove(e.getId());
                    if (prev == null || prev != e.getSteps()) {
                        problems.add(f.getFileName() + ": bundle " + e.getId() + " ends with " + e.getSteps() + " steps after step " + prev);
                    }
                    closedBundles.add(e.getId());
                }
                if (r.hasDecision() && r.getDecision().getEngineDecision().isEmpty()) {
                    problems.add(f.getFileName() + ": decision record without engine decision");
                }
                last = r;
                n++;
            }
        }
        if (last == null || !last.hasGameOver()) {
            problems.add(f.getFileName() + ": last record is not game over");
        }
        if (!openBundles.isEmpty()) {
            problems.add(f.getFileName() + ": bundles never ended: " + openBundles.keySet());
        }
        return problems;
    }

    /**
     * E3: every library position the observation states agrees with the real library order of the hidden state
     * recorded with the request, and a card named there is the card that is there. Bottom groups are not checked.
     */
    static String libraryPositions(Observation o, Privileged truth) {
        Map<Long, String> identity = new HashMap<>();
        for (HiddenIdentity h : truth.getIdentitiesList()) {
            identity.put(h.getId(), h.getOracleId());
        }
        for (GameObject g : o.getObjectsList()) {
            if (!g.hasLibraryPosition()) {
                continue;
            }
            List<Long> lib = truth.getLibraries(g.getOwnerSeat()).getIdsList();
            int idx = lib.indexOf(g.getId());
            var pos = g.getLibraryPosition();
            if (pos.hasFromTop() && pos.getFromTop() != idx) {
                return "object " + g.getId() + " said " + pos.getFromTop() + " from the top, is at " + idx;
            }
            if (pos.hasFromBottom() && pos.getFromBottom() != lib.size() - 1 - idx) {
                return "object " + g.getId() + " said " + pos.getFromBottom() + " from the bottom, is at " + idx + " of " + lib.size();
            }
            String real = identity.get(g.getId());
            if (!g.getOracleId().isEmpty() && real != null && !real.equals(g.getOracleId())) {
                return "object " + g.getId() + " named wrongly in the library";
            }
        }
        return null;
    }

    /** Event order rules of one seat's stream. */
    private static final class Order {
        private final Set<Long> movedTo = new HashSet<>();     // IDs a ZoneMove / ObjectCreated gave
        private final Set<Long> movedFrom = new HashSet<>();   // IDs a ZoneMove left
        private final Set<Long> triggered = new HashSet<>();
        private final Set<Long> settled = new HashSet<>();     // triggered abilities stacked or removed

        String check(Event e) {
            switch (e.getBodyCase()) {
                case ZONE_MOVE:
                    movedTo.add(e.getZoneMove().getId());
                    movedFrom.add(e.getZoneMove().getPrevId());
                    return null;
                case OBJECT_CREATED:
                    movedTo.add(e.getObjectCreated().getId());
                    return null;
                case CAST_SPELL:
                    return movedTo.contains(e.getCastSpell().getSpellId())
                        ? "spell " + e.getCastSpell().getSpellId() + " moved to the stack before CastSpell" : null;
                case PLAY_LAND:
                    return movedFrom.contains(e.getPlayLand().getCardId())
                        ? "land " + e.getPlayLand().getCardId() + " moved before PlayLand" : null;
                case ABILITY_TRIGGERED:
                    return triggered.add(e.getAbilityTriggered().getAbilityId())
                        ? null : "ability " + e.getAbilityTriggered().getAbilityId() + " triggered twice";
                case TRIGGER_STACKED:
                    return settle(e.getTriggerStacked().getAbilityId(), "put on the stack");
                case TRIGGER_REMOVED:
                    return settle(e.getTriggerRemoved().getAbilityId(), "removed");
                case CARD_REVEALED:
                    // Q10: a stream names a revealed card only when its seat is among those who saw it
                    return !e.getCardRevealed().getOracleId().isEmpty()
                        && !e.getCardRevealed().getRevealedToSeatsList().contains(e.getViewerSeat())
                        ? "card " + e.getCardRevealed().getId() + " named to a seat that did not see it" : null;
                default:
                    return null;
            }
        }

        private String settle(long id, String what) {
            if (!triggered.contains(id)) {
                return "triggered ability " + id + " " + what + " without AbilityTriggered";
            }
            return settled.add(id) ? null : "triggered ability " + id + " " + what + " twice";
        }
    }
}
