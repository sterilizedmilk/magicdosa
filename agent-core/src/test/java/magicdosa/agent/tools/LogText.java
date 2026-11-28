package magicdosa.agent.tools;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import com.google.protobuf.ProtocolMessageEnum;
import magicdosa.adapter.forge.CardKeys;
import magicdosa.protocol.v1.AbilityKey;
import magicdosa.protocol.v1.Attack;
import magicdosa.protocol.v1.Block;
import magicdosa.protocol.v1.Candidate;
import magicdosa.protocol.v1.CardFaceKey;
import magicdosa.protocol.v1.ChoiceValue;
import magicdosa.protocol.v1.Counter;
import magicdosa.protocol.v1.Decider;
import magicdosa.protocol.v1.DecisionContext;
import magicdosa.protocol.v1.DecisionRecord;
import magicdosa.protocol.v1.DecisionRequest;
import magicdosa.protocol.v1.DecisionStep;
import magicdosa.protocol.v1.Event;
import magicdosa.protocol.v1.GameObject;
import magicdosa.protocol.v1.GameOver;
import magicdosa.protocol.v1.LibraryOrder;
import magicdosa.protocol.v1.LogHeader;
import magicdosa.protocol.v1.LogRecord;
import magicdosa.protocol.v1.ManaPayment;
import magicdosa.protocol.v1.ManaUnit;
import magicdosa.protocol.v1.MatchPlayer;
import magicdosa.protocol.v1.Method;
import magicdosa.protocol.v1.ObjectRef;
import magicdosa.protocol.v1.Observation;
import magicdosa.protocol.v1.PlayerState;
import magicdosa.protocol.v1.PoolMana;
import magicdosa.protocol.v1.PrivilegedRecord;
import magicdosa.protocol.v1.HiddenIdentity;
import magicdosa.protocol.v1.SpellKey;
import magicdosa.protocol.v1.StackItem;
import magicdosa.protocol.v1.Step;
import magicdosa.protocol.v1.StepCandidate;
import magicdosa.protocol.v1.TurnStartSnapshot;
import magicdosa.protocol.v1.Zone;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/**
 * Turns one game log (the {@code .pb.gz} runner/forge writes per game) into plain text a person can read, one entry
 * per action in game order:
 *
 * <pre>LogText &lt;log.pb.gz&gt; [out.txt] [--viewer SEAT] [--privileged]</pre>
 *
 * Events come from one seat's stream (the first agent seat unless {@code --viewer} names another), so hidden cards
 * appear as that seat saw them. A decision lists its candidates as the deciding seat saw them (its own request). The
 * {@code privileged} records (training only) are left out unless {@code --privileged} is given; with it, hidden
 * identities also name cards in the event lines. Card names come from the adapter's oracle-id table
 * ({@code oracle-ids.tsv}). Search statistics are read by field reflection as well, so fields added by later
 * protocol versions (per-candidate statistics) are printed without changing this tool. Needs no Forge.
 */
public final class LogText {
    private static final Map<String, List<String>> CARD_NAMES = loadCardNames();

    /** {@code viewer} -1 picks the first agent seat (seat 0 when the header names none). */
    public record Options(int viewer, boolean privileged) {}

    private final Options options;
    private final List<String> out = new ArrayList<>();
    // entity id -> name, as a seat knows it (from its own event stream and requests); `names` is the viewer's except
    // while another seat's events are read (quietly, only to learn the names its decisions show)
    private final Map<Integer, Map<Long, String>> seatNames = new HashMap<>();
    private Map<Long, String> names = new HashMap<>();
    private boolean quiet;
    private final Map<Long, String> abilityNames = new HashMap<>();   // ability id (triggered / on the stack) -> text
    private final Map<Integer, Integer> activeByTurn = new HashMap<>();
    private int viewer;
    private int turn = -1;
    private Step step = Step.STEP_UNSPECIFIED;
    private final int[] deciders = new int[Decider.values().length];
    private int searched;
    private int decisions;

    private LogText(Options options) {
        this.options = options;
    }

    public static void main(String[] args) throws IOException {
        Path log = null;
        Path outFile = null;
        boolean privileged = false;
        int viewer = -1;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--privileged" -> privileged = true;
                case "--viewer" -> viewer = Integer.parseInt(args[++i]);
                default -> {
                    if (log == null) {
                        log = Path.of(args[i]);
                    } else {
                        outFile = Path.of(args[i]);
                    }
                }
            }
        }
        if (log == null) {
            System.err.println("usage: LogText <log.pb.gz> [out.txt] [--viewer SEAT] [--privileged]");
            System.exit(2);
        }
        List<String> lines = new ArrayList<>();
        lines.add("log: " + log.toAbsolutePath());
        lines.addAll(render(read(log), new Options(viewer, privileged)));
        if (outFile == null) {
            PrintStream ps = new PrintStream(System.out, true, StandardCharsets.UTF_8);
            lines.forEach(ps::println);
        } else {
            Files.write(outFile, lines, StandardCharsets.UTF_8);
            System.err.println("wrote " + lines.size() + " lines to " + outFile.toAbsolutePath());
        }
    }

    /** Reads a length-delimited LogRecord stream, gzipped when the file name ends in {@code .gz}. */
    public static List<LogRecord> read(Path file) throws IOException {
        List<LogRecord> records = new ArrayList<>();
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(file));
             InputStream in = file.toString().endsWith(".gz") ? new GZIPInputStream(raw) : raw) {
            LogRecord r;
            while ((r = LogRecord.parseDelimitedFrom(in)) != null) {
                records.add(r);
            }
        }
        return records;
    }

    public static List<String> render(List<LogRecord> records, Options options) {
        return new LogText(options).run(records);
    }

    // ---------------------------------------------------------------------------------------------------------------

    private List<String> run(List<LogRecord> records) {
        LogHeader header = records.stream().filter(LogRecord::hasHeader).map(LogRecord::getHeader).findFirst()
            .orElse(LogHeader.getDefaultInstance());
        GameOver over = records.stream().filter(LogRecord::hasGameOver).map(LogRecord::getGameOver).findFirst()
            .orElse(null);
        viewer = options.viewer() >= 0 ? options.viewer()
            : header.getAgentSeatsList().stream().mapToInt(Integer::intValue).min().orElse(0);
        for (LogRecord r : records) {
            if (r.hasSnapshot()) {
                noteActive(r.getSnapshot().getObservation());
            } else if (r.hasDecision()) {
                noteActive(r.getDecision().getRequest().getObservation());
            }
        }
        names = names(viewer);
        header(header, over);
        for (LogRecord r : gameOrder(records)) {
            switch (r.getRecordCase()) {
                case EVENT -> {
                    int seat = r.getEvent().getViewerSeat();
                    names = names(seat);
                    quiet = seat != viewer;
                    event(r.getEvent());
                    names = names(viewer);
                    quiet = false;
                }
                case SNAPSHOT -> snapshot(r.getSnapshot());
                case DECISION -> decision(r.getDecision());
                case OPPONENT_DECISION -> {
                    var o = r.getOpponentDecision();
                    line(turn, step, "> P" + o.getSeat() + " (engine) " + o.getEngineDecision()
                        + (o.getDescription().isEmpty() ? "" : ": " + o.getDescription()));
                }
                case PRIVILEGED -> privileged(r.getPrivileged());
                case BUNDLE_END -> {
                    if (!r.getBundleEnd().getCompleted()) {
                        line(turn, step, "  (bundle D" + r.getBundleEnd().getId() + " cut short after "
                            + r.getBundleEnd().getSteps() + " steps)");
                    }
                }
                default -> { }
            }
        }
        out.add("");
        out.add("=== Game over: " + result(over) + " ===");
        StringBuilder s = new StringBuilder("decisions: " + decisions);
        for (Decider d : Decider.values()) {
            if (d != Decider.UNRECOGNIZED && deciders[d.ordinal()] > 0) {
                s.append(", ").append(nice(d)).append(' ').append(deciders[d.ordinal()]);
            }
        }
        out.add(s.append(", searched ").append(searched).toString());
        return out;
    }

    /**
     * The log writes a decision record once the decision is done, after the events it caused. Each decision (and the
     * privileged record of its request) moves back to just after the event its request had seen last (its seat's
     * stream, {@code applied_seq}); decisions there keep their decision id order. Records without an anchor stay.
     */
    static List<LogRecord> gameOrder(List<LogRecord> records) {
        Map<Long, Integer> eventIndex = new HashMap<>();   // (seat, seq) -> record index
        Map<Long, Integer> decisionAnchor = new HashMap<>();
        for (int i = 0; i < records.size(); i++) {
            LogRecord r = records.get(i);
            if (r.hasEvent()) {
                eventIndex.putIfAbsent(seatSeq(r.getEvent().getViewerSeat(), r.getEvent().getSeq()), i);
            }
        }
        record Keyed(int anchor, int sub, long id, int index, LogRecord r) {}
        List<Keyed> keyed = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            LogRecord r = records.get(i);
            if (r.hasDecision() && r.getDecision().getRequest().getDecisionId() != 0) {
                DecisionRequest q = r.getDecision().getRequest();
                Integer a = eventIndex.get(seatSeq(q.getSeat(), q.getAppliedSeq()));
                if (a != null && a < i) {
                    decisionAnchor.put(q.getDecisionId(), a);
                    keyed.add(new Keyed(a, 2, q.getDecisionId(), i, r));
                    continue;
                }
            }
            keyed.add(new Keyed(i, 0, 0, i, r));
        }
        // a request's privileged record goes with its decision, just before it
        for (int k = 0; k < keyed.size(); k++) {
            Keyed e = keyed.get(k);
            if (e.r().hasPrivileged() && decisionAnchor.containsKey(e.r().getPrivileged().getDecisionId())) {
                long id = e.r().getPrivileged().getDecisionId();
                keyed.set(k, new Keyed(decisionAnchor.get(id), 1, id, e.index(), e.r()));
            }
        }
        keyed.sort(java.util.Comparator.comparingInt(Keyed::anchor)
            .thenComparingInt(k -> k.sub() == 0 ? 0 : 1)
            .thenComparingLong(Keyed::id).thenComparingInt(Keyed::sub).thenComparingInt(Keyed::index));
        return keyed.stream().map(Keyed::r).toList();
    }

    private static long seatSeq(int seat, long seq) {
        return ((long) seat << 48) ^ seq;
    }

    private void noteActive(Observation o) {
        if (o.hasGame()) {
            activeByTurn.putIfAbsent(o.getGame().getTurn(), o.getGame().getActiveSeat());
        }
    }

    private void header(LogHeader h, GameOver over) {
        out.add("match " + h.getMatchId() + "  game " + h.getGameId() + " (#" + h.getGameNumber() + ")  seed "
            + h.getGameSeed());
        out.add("protocol " + h.getProtocolVersion() + "  encoder " + h.getEncoderVersion() + "  model "
            + blank(h.getModelVersion()) + "  adapter " + blank(h.getAdapter()) + "  engine "
            + blank(h.getEngineVersion()) + "  mode " + nice(h.getMode()));
        for (MatchPlayer p : h.getPlayersList()) {
            String kind = p.getSeat() < h.getPlayerKindsCount() ? h.getPlayerKinds(p.getSeat()) : "?";
            out.add("P" + p.getSeat() + ": " + p.getDeckName()
                + (p.getDeckVersion().isEmpty() ? "" : " (" + p.getDeckVersion() + ")") + "  " + kind
                + (h.getAgentSeatsList().contains(p.getSeat()) ? "  [agent]" : ""));
        }
        out.add("result: " + result(over));
        out.add("events as seen by P" + viewer + "; each decision shows the deciding seat's own view"
            + (options.privileged() ? "; privileged (hidden) records shown" : ""));
    }

    private static String result(GameOver over) {
        if (over == null) {
            return "no game-over record (game cut short)";
        }
        return (over.hasWinnerSeat() ? "P" + over.getWinnerSeat() + " wins" : "draw") + " (" + nice(over.getReason())
            + ") after " + over.getTurns() + " turns";
    }

    // --- turn and step markers --------------------------------------------------------------------------------------

    private Map<Long, String> names(int seat) {
        return seatNames.computeIfAbsent(seat, k -> new HashMap<>());
    }

    private void line(int t, Step s, String text) {
        if (quiet) {
            return;
        }
        if (t != turn && t >= 0) {
            turn = t;
            step = Step.STEP_UNSPECIFIED;
            out.add("");
            Integer active = activeByTurn.get(t);
            out.add(t == 0 ? "=== Pregame ===" : "=== Turn " + t + (active == null ? "" : " (P" + active + ")") + " ===");
        }
        if (s != step && s != Step.STEP_UNSPECIFIED && s != Step.UNRECOGNIZED) {
            step = s;
            out.add("  -- " + nice(s) + " --");
        }
        out.add("    " + text);
    }

    // --- names ----------------------------------------------------------------------------------------------------

    private static Map<String, List<String>> loadCardNames() {
        Map<String, List<String>> m = new HashMap<>();
        try (InputStream in = CardKeys.class.getResourceAsStream("oracle-ids.tsv")) {
            if (in == null) {
                return m;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String l;
            while ((l = r.readLine()) != null) {
                if (!l.isBlank() && !l.startsWith("#")) {
                    String[] f = l.split("\t");
                    m.putIfAbsent(f[2], Arrays.asList(f[3].split("\\|")));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return m;
    }

    static String cardName(String oracleId, int face) {
        if (oracleId.isEmpty()) {
            return "";
        }
        List<String> faces = CARD_NAMES.get(oracleId);
        if (faces == null) {
            return "oracle:" + oracleId.substring(0, Math.min(8, oracleId.length()));
        }
        return faces.get(face < faces.size() ? face : 0);
    }

    private static String cardName(CardFaceKey k) {
        return cardName(k.getOracleId(), k.getFace());
    }

    /** Names the viewer's observation shows (or, for a decision, the decider's). */
    private void learn(Observation o, Map<Long, String> into) {
        for (GameObject g : o.getObjectsList()) {
            String n = !g.getOracleId().isEmpty() ? cardName(g.getOracleId(), g.getFace())
                : g.hasCharacteristics() && !g.getCharacteristics().getName().isEmpty() ? g.getCharacteristics().getName()
                : g.getFaceDown() ? "face-down card" : "";
            if (!n.isEmpty()) {
                into.put(g.getId(), n);
            }
        }
        for (StackItem s : o.getStackList()) {
            if (s.hasSpell()) {
                into.put(s.getId(), cardName(s.getSpell().getCard()));
            } else if (s.hasAbility()) {
                String src = into.getOrDefault(s.getSourceId(), names.get(s.getSourceId()));
                into.put(s.getId(), (src == null ? "" : src + ": ") + ability(s.getAbility()));
            }
        }
    }

    private String obj(long id, Map<Long, String> local) {
        String n = local != null && local.containsKey(id) ? local.get(id) : names.get(id);
        if (n == null) {
            n = abilityNames.get(id);
        }
        return (n == null ? "card" : n) + "#" + id;
    }

    private String obj(long id) {
        return obj(id, null);
    }

    private String ref(ObjectRef r, Map<Long, String> local) {
        return r.hasPlayerSeat() ? "P" + r.getPlayerSeat() : obj(r.getEntityId(), local);
    }

    private String refs(List<ObjectRef> rs, Map<Long, String> local) {
        return rs.stream().map(r -> ref(r, local)).collect(Collectors.joining(", "));
    }

    private static String ability(AbilityKey k) {
        if (k.hasKeyword()) {
            return k.getKeyword().getName() + (k.getKeyword().getParamsCount() == 0 ? ""
                : " " + String.join(" ", k.getKeyword().getParamsList()));
        }
        if (k.hasPrinted()) {
            return "ability " + k.getPrinted().getIndex();
        }
        return "ability";
    }

    /** An ability with the card it is printed on, when there is no source object to name. */
    private static String abilityWithCard(AbilityKey k) {
        return k.hasPrinted() ? cardName(k.getPrinted().getOracleId(), k.getPrinted().getFace()) + " " + ability(k)
            : ability(k);
    }

    private static String mana(List<ManaUnit> units) {
        return units.stream().map(u -> (u.getAmount() > 1 ? u.getAmount() + "" : "") + u.getColor())
            .collect(Collectors.joining(" "));
    }

    private static String pool(PoolMana p) {
        return (p.getAmount() > 1 ? p.getAmount() + "" : "") + p.getColor() + " from pool";
    }

    private String payments(List<ManaPayment> ps) {
        if (ps.isEmpty()) {
            return "";
        }
        return "  (paid: " + ps.stream().map(p -> p.hasAbility()
                ? obj(p.getAbility().getSourceId()) + " " + mana(p.getAbility().getProducedList())
                : pool(p.getPool()))
            .collect(Collectors.joining(", ")) + ")";
    }

    private String extras(List<ObjectRef> targets, List<Integer> modes, boolean hasX, long x, Map<Long, String> local) {
        StringBuilder s = new StringBuilder();
        if (!targets.isEmpty()) {
            s.append(" -> ").append(refs(targets, local));
        }
        if (!modes.isEmpty()) {
            s.append(" modes ").append(modes);
        }
        if (hasX) {
            s.append(" X=").append(x);
        }
        return s.toString();
    }

    // --- events ---------------------------------------------------------------------------------------------------

    private void event(Event e) {
        int t = e.getTurn();
        Step s = e.getStep();
        switch (e.getBodyCase()) {
            case PLAY_LAND -> {
                var p = e.getPlayLand();
                names.put(p.getCardId(), cardName(p.getLand()));
                playedLands.add(p.getCardId());
                line(t, s, "P" + p.getSeat() + " plays " + obj(p.getCardId()));
            }
            case CAST_SPELL -> {
                var c = e.getCastSpell();
                String n = cardName(c.getSpell().getCard());
                names.put(c.getCardId(), n);
                names.put(c.getSpellId(), n);
                line(t, s, "P" + c.getSeat() + " casts " + obj(c.getSpellId())
                    + (c.getSpell().hasCastMethod() ? " (" + ability(c.getSpell().getCastMethod()) + ")" : "")
                    + extras(c.getTargetsList(), c.getModesList(), c.hasX(), c.getX(), null) + payments(c.getPaymentList()));
            }
            case ACTIVATE_ABILITY -> {
                var a = e.getActivateAbility();
                String text = obj(a.getSourceId()) + ": " + ability(a.getAbility());
                abilityNames.put(a.getAbilityId(), text);
                line(t, s, "P" + a.getSeat() + " activates " + text
                    + extras(a.getTargetsList(), a.getModesList(), a.hasX(), a.getX(), null) + payments(a.getPaymentList()));
            }
            case DECLARE_ATTACKERS -> {
                var d = e.getDeclareAttackers();
                line(t, s, "P" + d.getSeat() + " attacks: " + (d.getAttacksCount() == 0 ? "none"
                    : d.getAttacksList().stream().map(a -> attack(a, null)).collect(Collectors.joining(", "))));
            }
            case DECLARE_BLOCKERS -> {
                var d = e.getDeclareBlockers();
                line(t, s, "P" + d.getSeat() + " blocks: " + (d.getBlocksCount() == 0 ? "none"
                    : d.getBlocksList().stream().map(b -> block(b, null)).collect(Collectors.joining(", "))));
            }
            case MULLIGAN -> line(t, s, "P" + e.getMulligan().getSeat() + (e.getMulligan().getKeep() ? " keeps" : " mulligans"));
            case BOTTOM_CARDS -> line(t, s, "P" + e.getBottomCards().getSeat() + " puts on the bottom: "
                + e.getBottomCards().getCardIdsList().stream().map(this::obj).collect(Collectors.joining(", ")));
            case PLAY_OR_DRAW -> line(t, s, "P" + e.getPlayOrDraw().getSeat() + " chooses to "
                + (e.getPlayOrDraw().getPlay() ? "play first" : "draw first"));
            case RESOLUTION_CHOICE -> {
                var c = e.getResolutionChoice();
                StringBuilder b = new StringBuilder("P" + c.getSeat() + " chooses (" + nice(c.getType()) + ")");
                if (c.getResolvingId() != 0) {
                    b.append(" for ").append(obj(c.getResolvingId()));
                }
                if (c.getChosenCount() > 0) {
                    b.append(": ").append(refs(c.getChosenList(), null));
                }
                if (c.getOptionsCount() > 0) {
                    b.append(" options ").append(c.getOptionsList());
                }
                if (c.hasX()) {
                    b.append(" X=").append(c.getX());
                }
                if (c.getValuesCount() > 0) {
                    b.append(" values ").append(c.getValuesList().stream().map(LogText::value).toList());
                }
                if (c.getChosenCount() == 0 && c.getOptionsCount() == 0 && !c.hasX() && c.getValuesCount() == 0) {
                    b.append(": nothing");
                }
                if (!c.getEnginePrompt().isEmpty()) {
                    b.append("  \"").append(oneLine(c.getEnginePrompt())).append('"');
                }
                line(t, s, b.toString());
            }
            case TRIGGER_ORDER -> line(t, s, "P" + e.getTriggerOrder().getSeat() + " orders triggers: "
                + e.getTriggerOrder().getAbilityIdsList().stream().map(this::obj).collect(Collectors.joining(", ")));
            case TRIGGER_STACKED -> {
                var a = e.getTriggerStacked();
                String x = extras(a.getTargetsList(), a.getModesList(), a.hasX(), a.getX(), null) + payments(a.getPaymentList());
                if (!x.isEmpty()) {
                    line(t, s, "P" + a.getSeat() + " puts " + obj(a.getAbilityId()) + " on the stack" + x);
                }
            }
            case CARD_REVEALED -> {
                var c = e.getCardRevealed();
                if (c.getOracleId().isEmpty()) {
                    break;   // revealed to another seat only
                }
                names.put(c.getId(), cardName(c.getOracleId(), c.getFace()));
                if (c.getReason() != magicdosa.protocol.v1.RevealReason.REVEAL_REASON_OWN_DRAW) {
                    line(t, s, obj(c.getId()) + " revealed (" + nice(c.getReason()) + ", " + nice(c.getZone()) + ")");
                }
            }
            case ZONE_MOVE -> zoneMove(t, s, e.getZoneMove());
            case OBJECT_CREATED -> {
                var c = e.getObjectCreated();
                String n = !c.getOracleId().isEmpty() ? cardName(c.getOracleId(), c.getFace())
                    : c.hasCharacteristics() && !c.getCharacteristics().getName().isEmpty() ? c.getCharacteristics().getName()
                    : c.getCopyOfId() != 0 ? "copy of " + obj(c.getCopyOfId()) : "token";
                names.put(c.getId(), n);
                line(t, s, "P" + c.getControllerSeat() + " creates " + nice(c.getKind()) + " " + obj(c.getId())
                    + " (" + nice(c.getZone()) + ")" + (c.getSourceId() != 0 ? " from " + obj(c.getSourceId()) : ""));
            }
            case ABILITY_TRIGGERED -> {
                var a = e.getAbilityTriggered();
                String text = (a.getSourceId() != 0 ? obj(a.getSourceId()) + ": " + ability(a.getAbility())
                    : abilityWithCard(a.getAbility()));
                abilityNames.put(a.getAbilityId(), text);
                line(t, s, "trigger (P" + a.getControllerSeat() + "): " + text + "  [ability #" + a.getAbilityId() + "]");
            }
            case EFFECT_CREATED -> {
                var f = e.getEffectCreated().getEffect();
                line(t, s, "effect (P" + f.getControllerSeat() + "): " + nice(f.getKind())
                    + (f.getSourceId() != 0 ? " from " + obj(f.getSourceId()) : "")
                    + (f.getDescription().isEmpty() ? "" : " \"" + oneLine(f.getDescription()) + "\"")
                    + (f.getAffectedCount() > 0 ? " on " + refs(f.getAffectedList(), null) : "")
                    + (f.getDuration().getNumber() != 0 ? " (" + nice(f.getDuration()) + ")" : ""));
            }
            case DAMAGE -> {
                var d = e.getDamage();
                line(t, s, obj(d.getSourceId()) + " deals " + d.getAmount() + (d.getCombat() ? " combat" : "")
                    + " damage to " + ref(d.getTarget(), null));
            }
            case LIFE -> {
                var l = e.getLife();
                line(t, s, "P" + l.getSeat() + " life " + (l.getDelta() >= 0 ? "+" : "") + l.getDelta() + " -> " + l.getLife());
            }
            case COUNTERS -> line(t, s, "counters on " + ref(e.getCounters().getTarget(), null) + ": "
                + counters(e.getCounters().getCountersList()));
            case LIBRARY_SHUFFLED -> line(t, s, "P" + e.getLibraryShuffled().getSeat() + " shuffles their library");
            case DESIGNATIONS -> line(t, s, "P" + e.getDesignations().getSeat() + " designations: "
                + e.getDesignations().getDesignationsList().stream()
                .map(d -> d.getName() + (d.getValue() != 0 ? "=" + d.getValue() : "") + (d.getText().isEmpty() ? "" : " " + d.getText()))
                .toList());
            case DAY_NIGHT -> line(t, s, "it becomes " + nice(e.getDayNight().getDayNight()));
            case LIBRARY_ARRANGED -> {
                var a = e.getLibraryArranged();
                line(t, s, "P" + a.getSeat() + " looks at " + a.getLookedAt() + " (top " + a.getToTop() + ", bottom "
                    + a.getToBottom() + ", graveyard " + a.getToGraveyard() + ")"
                    + (a.getCardsCount() > 0 ? ": " + a.getCardsList().stream().map(c -> obj(c.getId())).collect(Collectors.joining(", ")) : ""));
            }
            // Bookkeeping the action lines already show: priority passes, mana abilities and pool changes (the cast
            // line names the payment), taps and untaps, ended effects and removed triggers.
            default -> { }
        }
    }

    private static String value(ChoiceValue v) {
        return switch (v.getValueCase()) {
            case FLAG -> String.valueOf(v.getFlag());
            case NUMBER -> String.valueOf(v.getNumber());
            case TEXT -> v.getText();
            default -> "?";
        };
    }

    private static String counters(List<Counter> cs) {
        return cs.isEmpty() ? "none" : cs.stream().map(c -> c.getKind() + " " + c.getCount()).collect(Collectors.joining(", "));
    }

    private void zoneMove(int t, Step s, magicdosa.protocol.v1.ZoneMove m) {
        String n = cardName(m.getOracleId(), m.getFace());
        if (n.isEmpty() && m.getTo() != Zone.ZONE_LIBRARY && names.containsKey(m.getPrevId())) {
            n = names.get(m.getPrevId());
        }
        if (n.isEmpty() && m.getFaceDown()) {
            n = "face-down card";
        }
        if (!n.isEmpty()) {
            names.put(m.getId(), n);
        }
        Zone from = m.getFrom();
        Zone to = m.getTo();
        // Cast and play-land lines already show cards going onto the stack or played; token creation has its own line.
        if (to == Zone.ZONE_STACK || from == Zone.ZONE_NONE
            || (from == Zone.ZONE_HAND && to == Zone.ZONE_BATTLEFIELD && isLandPlay(m))) {
            return;
        }
        String who = "P" + m.getOwnerSeat();
        String text;
        if (from == Zone.ZONE_LIBRARY && to == Zone.ZONE_HAND) {
            text = who + " draws " + obj(m.getId());
        } else if (to == Zone.ZONE_NONE) {
            text = obj(m.getPrevId()) + " ceases to exist";
        } else {
            text = obj(m.getId()) + " (" + who + "): " + nice(from) + " -> " + nice(to)
                + (m.hasPosition() ? " at " + m.getPosition() : "");
        }
        line(t, s, text);
    }

    private final Set<Long> playedLands = new java.util.HashSet<>();

    private boolean isLandPlay(magicdosa.protocol.v1.ZoneMove m) {
        return playedLands.contains(m.getPrevId());
    }

    private String attack(Attack a, Map<Long, String> local) {
        return obj(a.getAttackerId(), local) + " -> " + ref(a.getDefender(), local);
    }

    private String block(Block b, Map<Long, String> local) {
        return obj(b.getBlockerId(), local) + " blocks " + obj(b.getAttackerId(), local);
    }

    // --- snapshots and privileged ---------------------------------------------------------------------------------

    private void snapshot(TurnStartSnapshot snap) {
        if (snap.getSeat() != viewer) {
            learn(snap.getObservation(), names(snap.getSeat()));
            return;
        }
        Observation o = snap.getObservation();
        learn(o, names);
        int t = o.getGame().getTurn();
        if (t == 0) {
            return;   // before the opening hands
        }
        line(t, o.getGame().getStep(), "state: " + o.getPlayersList().stream().map(LogText::player)
            .collect(Collectors.joining(" | ")));
        for (PlayerState p : o.getPlayersList()) {
            List<String> perms = o.getObjectsList().stream()
                .filter(g -> g.getZone() == Zone.ZONE_BATTLEFIELD && g.getControllerSeat() == p.getSeat())
                .map(g -> obj(g.getId()) + (g.getTapped() ? " (tapped)" : "")
                    + (g.getCountersCount() > 0 ? " [" + counters(g.getCountersList()) + "]" : ""))
                .toList();
            line(t, o.getGame().getStep(), "battlefield P" + p.getSeat() + ": " + (perms.isEmpty() ? "-" : String.join(", ", perms)));
        }
        List<String> hand = o.getObjectsList().stream()
            .filter(g -> g.getZone() == Zone.ZONE_HAND && g.getOwnerSeat() == viewer).map(g -> obj(g.getId())).toList();
        line(t, o.getGame().getStep(), "hand P" + viewer + ": " + (hand.isEmpty() ? "-" : String.join(", ", hand)));
    }

    private static String player(PlayerState p) {
        return "P" + p.getSeat() + " life " + p.getLife() + ", hand " + p.getHandSize() + ", library " + p.getLibrarySize()
            + (p.getCountersCount() > 0 ? ", " + counters(p.getCountersList()) : "");
    }

    private void privileged(PrivilegedRecord p) {
        if (!options.privileged()) {
            return;
        }
        Map<Long, String> hidden = new HashMap<>();
        for (HiddenIdentity h : p.getPrivileged().getIdentitiesList()) {
            String n = cardName(h.getOracleId(), h.getFace());
            hidden.put(h.getId(), n);
            names.putIfAbsent(h.getId(), n);
        }
        StringBuilder b = new StringBuilder("[privileged P" + p.getSeat()
            + (p.getDecisionId() != 0 ? " @D" + p.getDecisionId() : "") + "] hidden identities " + hidden.size());
        for (LibraryOrder l : p.getPrivileged().getLibrariesList()) {
            b.append("; library P").append(l.getSeat()).append(" top: ").append(l.getIdsList().stream().limit(5)
                .map(id -> obj(id, hidden)).collect(Collectors.joining(", ")));
        }
        line(turn, step, b.toString());
    }

    // --- decisions ------------------------------------------------------------------------------------------------

    private void decision(DecisionRecord d) {
        DecisionRequest req = d.getRequest();
        decisions++;
        deciders[d.getDecider().ordinal()]++;
        Map<Long, String> local = names(req.getSeat());   // the decider's view; == names for the viewer
        learn(req.getObservation(), local);
        List<String> labels = new ArrayList<>();
        for (Candidate c : req.getCandidatesList()) {
            labels.add(candidate(c, local));
        }
        int t = req.getObservation().hasGame() ? req.getObservation().getGame().getTurn() : turn;
        Step s = req.getObservation().hasGame() ? req.getObservation().getGame().getStep() : step;
        if (req.getDecisionId() == 0 && req.getCandidatesCount() == 0) {
            // a choice the adapter hands to the engine without a request (e.g. orderCosts): no seat, no candidates
            line(t, s, "> engine " + blank(d.getEngineDecision()) + "  [" + nice(d.getDecider()) + "]");
            return;
        }

        StringBuilder title = new StringBuilder("> D" + req.getDecisionId() + " P" + req.getSeat()
            + (req.hasActingSeat() && req.getActingSeat() != req.getSeat() ? " (acting P" + req.getActingSeat() + ")" : "")
            + " " + nice(req.getType()));
        if (req.hasBundle() && req.getBundle().getStep() > 1) {
            title.append(" (bundle D").append(req.getBundle().getId()).append(" step ").append(req.getBundle().getStep()).append(')');
        }
        title.append(context(req.getContext(), local));
        var con = req.getConstraints();
        if (con.getMax() > 1 || con.getMin() > 1) {
            title.append("  pick ").append(con.getMin()).append("..").append(con.getMax()).append(con.getOrdered() ? " ordered" : "");
        }
        title.append("  [").append(nice(d.getDecider()));
        if (d.getDecider() == Decider.DECIDER_UNINTENDED_FALLBACK) {
            title.append(": ").append(nice(d.getFallbackReason()));
        }
        if (d.getDecider() != Decider.DECIDER_MODEL && !d.getEngineDecision().isEmpty()) {
            title.append(", ").append(d.getEngineDecision());
        }
        title.append(']');
        line(t, s, title.toString());
        for (var rej : req.getRejectionsList()) {
            line(t, s, "  rejected earlier: D" + rej.getDecisionId() + " " + rej.getChosenList() + " " + oneLine(rej.getReason()));
        }
        if (!d.getRejection().isEmpty()) {
            line(t, s, "  engine rejected: " + oneLine(d.getRejection()));
        }

        List<DecisionStep> steps = d.hasResponse() ? d.getResponse().getStepsList() : List.of();
        steps = steps.stream().filter(st -> st.getCandidatesCount() > 0).toList();
        Set<Integer> applied = Set.copyOf(d.getAppliedList());
        if (steps.stream().anyMatch(st -> st.getMethod().hasRan())) {
            searched++;
        }
        // One step that offers every candidate (and maybe "done"): its table is the candidate list.
        boolean single = steps.size() == 1 && offersAll(steps.get(0), labels.size());
        if (!single) {
            for (int i = 0; i < labels.size(); i++) {
                line(t, s, "  " + (applied.contains(i) ? "* " : "  ") + i + " " + labels.get(i));
            }
        }
        for (int k = 0; k < steps.size(); k++) {
            DecisionStep st = steps.get(k);
            List<String> rows = st.getCandidatesList().stream()
                .map(c -> c.hasDone() ? "done" : c.getIndex() < labels.size() ? c.getIndex() + " " + labels.get(c.getIndex()) : "?" + c.getIndex())
                .toList();
            String m = method(st);
            if (single) {
                if (!m.isEmpty()) {
                    line(t, s, "  " + m);
                }
            } else {
                String chosen = st.getChosen() < rows.size() ? rows.get(st.getChosen()) : "";
                line(t, s, "  step " + (k + 1) + (chosen.isEmpty() ? "" : ": " + chosen) + (m.isEmpty() ? "" : "   " + m));
            }
            if (single || !columns(st, rows.size()).isEmpty()) {
                table(t, s, st, rows);
            }
        }
    }

    /** The step offers every request candidate. */
    private static boolean offersAll(DecisionStep st, int n) {
        return n > 0 && st.getCandidatesList().stream().filter(StepCandidate::hasIndex).map(StepCandidate::getIndex)
            .distinct().count() == n;
    }

    private void table(int t, Step s, DecisionStep st, List<String> rows) {
        Map<String, List<Object>> cols = columns(st, rows.size());
        int width = Math.min(60, rows.stream().mapToInt(String::length).max().orElse(0));
        for (int i = 0; i < rows.size(); i++) {
            StringBuilder b = new StringBuilder("  " + (i == st.getChosen() ? "* " : "  ") + pad(rows.get(i), width));
            for (var col : cols.entrySet()) {
                b.append("  ").append(col.getKey()).append(' ').append(num(col.getValue().get(i)));
            }
            line(t, s, b.toString().stripTrailing());
        }
    }

    /**
     * Per-candidate columns of a step: visit ratio, policy probability, and any repeated numeric field (or repeated
     * message's numeric field) of the step, its diagnostics or its search run that has one entry per candidate, so
     * statistics a later protocol adds show up without a change here.
     */
    private static Map<String, List<Object>> columns(DecisionStep st, int n) {
        Map<String, List<Object>> cols = new LinkedHashMap<>();
        if (st.getVisitRatioCount() == n) {
            cols.put("visits", new ArrayList<>(st.getVisitRatioList()));
        }
        if (st.getDiagnostics().getPolicyProbCount() == n) {
            cols.put("policy", new ArrayList<>(st.getDiagnostics().getPolicyProbList()));
        }
        perCandidate(st, n, "", Set.of("visit_ratio", "candidates"), cols);
        perCandidate(st.getDiagnostics(), n, "", Set.of("policy_prob"), cols);
        if (st.getMethod().hasRan()) {
            perCandidate(st.getMethod().getRan(), n, "", Set.of(), cols);
        }
        return cols;
    }

    private static void perCandidate(Message m, int n, String prefix, Set<String> skip, Map<String, List<Object>> cols) {
        for (FieldDescriptor f : m.getDescriptorForType().getFields()) {
            if (!f.isRepeated() || skip.contains(f.getName()) || m.getRepeatedFieldCount(f) != n) {
                continue;
            }
            if (isNumber(f)) {
                List<Object> v = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    v.add(m.getRepeatedField(f, i));
                }
                cols.put(prefix + f.getName(), v);
            } else if (f.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                for (FieldDescriptor sub : f.getMessageType().getFields()) {
                    if (!sub.isRepeated() && isNumber(sub)) {
                        List<Object> v = new ArrayList<>();
                        for (int i = 0; i < n; i++) {
                            v.add(((Message) m.getRepeatedField(f, i)).getField(sub));
                        }
                        cols.put(prefix + sub.getName(), v);
                    }
                }
            }
        }
    }

    private static boolean isNumber(FieldDescriptor f) {
        return switch (f.getJavaType()) {
            case INT, LONG, FLOAT, DOUBLE -> true;
            default -> false;
        };
    }

    /** Who decided and how: search with its settings (every set scalar field), the network alone, or the prior. */
    private static String method(DecisionStep st) {
        List<String> parts = new ArrayList<>();
        if (st.getDecider() != Decider.DECIDER_UNSPECIFIED && st.getDecider() != Decider.DECIDER_MODEL) {
            parts.add(nice(st.getDecider()));
        }
        Method m = st.getMethod();
        if (m.hasRan()) {
            StringBuilder b = new StringBuilder("search");
            Message run = m.getRan();
            for (var e : run.getAllFields().entrySet()) {
                FieldDescriptor f = e.getKey();
                if (f.isRepeated() || f.getName().equals("seed") || f.getName().equals("leaf_model_sha256")) {
                    continue;
                }
                b.append(' ').append(f.getName()).append('=').append(scalar(e.getValue()));
            }
            parts.add(b.toString());
        } else if (m.hasSkipped()) {
            parts.add("net only (" + nice(m.getSkipped()) + ")");
        }
        if (m.getPrior().getNumber() != 0) {
            parts.add("prior " + nice(m.getPrior()));
        }
        if (st.getDiagnostics().hasValue()) {
            parts.add("net value " + num(st.getDiagnostics().getValue()));
        }
        return String.join("; ", parts);
    }

    private static String scalar(Object v) {
        if (v instanceof com.google.protobuf.Descriptors.EnumValueDescriptor e) {
            return niceName(e.getName(), e.getType().getName());
        }
        return num(v);
    }

    private String context(DecisionContext c, Map<Long, String> local) {
        StringBuilder b = new StringBuilder();
        if (c.getSourceId() != 0) {
            b.append(" for ").append(obj(c.getSourceId(), local));
        } else if (c.hasSpell()) {
            b.append(" for ").append(cardName(c.getSpell().getCard()));
        }
        if (c.hasAbility()) {
            b.append(c.getSourceId() != 0 ? " " + ability(c.getAbility()) : " for " + abilityWithCard(c.getAbility()));
        }
        if (!c.getManaCost().isEmpty()) {
            b.append(" cost ").append(c.getManaCost());
        }
        if (c.getPaidCount() > 0) {
            b.append(" paid ").append(mana(c.getPaidList()));
        }
        if (c.getTargetsCount() > 0) {
            b.append(" targets ").append(refs(c.getTargetsList(), local));
        }
        if (c.getModesCount() > 0) {
            b.append(" modes ").append(c.getModesList());
        }
        if (c.hasX()) {
            b.append(" X=").append(c.getX());
        }
        if (c.hasDamage()) {
            var dm = c.getDamage();
            b.append(" damage ").append(dm.getTotal()).append(" (").append(dm.getRemaining()).append(" left")
                .append(dm.getTrample() ? ", trample" : "").append(") to ").append(ref(dm.getRecipient(), local));
        }
        if (c.getNewTargets()) {
            b.append(" (new targets)");
        }
        if (!c.getEnginePrompt().isEmpty()) {
            b.append("  \"").append(oneLine(c.getEnginePrompt())).append('"');
        }
        return b.toString();
    }

    private String candidate(Candidate c, Map<Long, String> local) {
        return switch (c.getActionCase()) {
            case PLAY_LAND -> {
                local.put(c.getPlayLand().getCardId(), cardName(c.getPlayLand().getLand()));
                yield "play " + obj(c.getPlayLand().getCardId(), local);
            }
            case CAST_SPELL -> {
                var cs = c.getCastSpell();
                local.put(cs.getCardId(), cardName(cs.getSpell().getCard()));
                yield "cast " + obj(cs.getCardId(), local) + castMethod(cs.getSpell());
            }
            case ACTIVATE_ABILITY -> {
                var a = c.getActivateAbility();
                yield (a.getManaAbility() ? "mana ability " : "activate ") + obj(a.getSourceId(), local) + ": " + ability(a.getAbility());
            }
            case PASS -> "pass";
            case TARGET -> "target " + ref(c.getTarget(), local);
            case ATTACK -> "attack " + attack(c.getAttack(), local);
            case BLOCK -> "block: " + block(c.getBlock(), local);
            case OPTION -> {
                var o = c.getOption();
                yield "option " + o.getIndex() + (o.hasX() ? " X=" + o.getX() : "")
                    + (o.getLabel().isEmpty() ? "" : " \"" + oneLine(o.getLabel()) + "\"");
            }
            case CARD_ID -> obj(c.getCardId(), local);
            case MANA_SOURCE -> {
                var m = c.getManaSource();
                yield "tap " + m.getSourceIdsList().stream().map(id -> obj(id, local)).collect(Collectors.joining(", "))
                    + " for " + mana(m.getProducedList());
            }
            case POOL_MANA -> "use " + pool(c.getPoolMana());
            case TRIGGERED_ABILITY_ID -> obj(c.getTriggeredAbilityId(), local);
            case KEEP -> c.getKeep() ? "keep" : "mulligan";
            case PLAY -> c.getPlay() ? "play first" : "draw first";
            case SIDEBOARD_SWAP -> "swap in " + cardName(c.getSideboardSwap().getInOracleId(), 0) + ", out "
                + cardName(c.getSideboardSwap().getOutOracleId(), 0);
            case PAY_LIFE -> "pay 2 life (Phyrexian)";
            case NAMED_ORACLE_ID -> "name " + cardName(c.getNamedOracleId(), 0);
            case NAMED_TYPE -> "name type " + c.getNamedType();
            default -> "?";
        };
    }

    private static String castMethod(SpellKey k) {
        return k.hasCastMethod() ? " (" + ability(k.getCastMethod()) + ")" : "";
    }

    // --- formatting -----------------------------------------------------------------------------------------------

    private static String nice(ProtocolMessageEnum e) {
        if (e instanceof Enum<?> en && en.name().equals("UNRECOGNIZED")) {
            return "?";
        }
        var d = e.getValueDescriptor();
        return niceName(d.getName(), d.getType().getName());
    }

    /** {@code ZONE_HAND} of {@code Zone} -> {@code hand}; {@code DECIDER_MODEL} -> {@code model}. */
    static String niceName(String value, String enumType) {
        String prefix = enumType.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT) + "_";
        String v = value.startsWith(prefix) ? value.substring(prefix.length()) : value;
        return v.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String num(Object v) {
        if (v instanceof Float f) {
            return String.format(Locale.ROOT, "%.3f", f);
        }
        if (v instanceof Double d) {
            return String.format(Locale.ROOT, "%.3f", d);
        }
        return String.valueOf(v);
    }

    private static String pad(String s, int w) {
        return s.length() >= w ? s : s + " ".repeat(w - s.length());
    }

    private static String oneLine(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String blank(String s) {
        return s.isEmpty() ? "-" : s;
    }
}
