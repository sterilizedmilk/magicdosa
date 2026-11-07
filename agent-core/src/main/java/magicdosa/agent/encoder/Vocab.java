package magicdosa.agent.encoder;

import magicdosa.protocol.v1.AbilityKey;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Indices of cards, ability keys, keyword names and other strings for the embedding tables (encoder E1): a fixed,
 * append-only file ({@code vocab.tsv}, one {@code kind<TAB>value} per line, indices in file order per kind), so that
 * indices of a trained model stay valid when entries are added. Reserved indices in every kind: 0 padding, 1 a hidden
 * card, 2 not in the vocabulary (counted: zero is expected for the target decks). The vocabulary has its own version
 * ({@code #version N} header), separate from the encoder version.
 */
public final class Vocab {
    public static final int PAD = 0;
    public static final int HIDDEN = 1;
    public static final int OOV = 2;
    private static final int FIRST = 3;

    /** Kinds of vocabulary entries. */
    public enum Kind { CARD, ABILITY, KEYWORD, STRING }

    private final int version;
    private final Map<Kind, Map<String, Integer>> index = new HashMap<>();
    private final AtomicLong oov = new AtomicLong();
    private final Map<String, Long> oovValues = new java.util.concurrent.ConcurrentHashMap<>();

    private Vocab(int version) {
        this.version = version;
        for (Kind k : Kind.values()) {
            index.put(k, new LinkedHashMap<>());
        }
    }

    /** The vocabulary shipped with agent-core. */
    public static Vocab load() throws IOException {
        try (InputStream in = Vocab.class.getResourceAsStream("vocab.tsv")) {
            if (in == null) {
                throw new IOException("vocab.tsv missing from agent-core's resources");
            }
            return read(in);
        }
    }

    public static Vocab read(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        Vocab v = null;
        String line;
        while ((line = r.readLine()) != null) {
            if (line.startsWith("#version ")) {
                v = new Vocab(Integer.parseInt(line.substring(9).trim()));
                continue;
            }
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            if (v == null) {
                throw new IOException("vocab.tsv: #version header missing");
            }
            int tab = line.indexOf('\t');
            Kind kind = Kind.valueOf(line.substring(0, tab).toUpperCase(java.util.Locale.ROOT));
            Map<String, Integer> m = v.index.get(kind);
            m.putIfAbsent(line.substring(tab + 1), FIRST + m.size());
        }
        if (v == null) {
            throw new IOException("vocab.tsv is empty");
        }
        return v;
    }

    public int version() {
        return version;
    }

    public int size(Kind kind) {
        return FIRST + index.get(kind).size();
    }

    /** Index of {@code value}; {@link #PAD} for an empty value, {@link #OOV} (counted) when not in the vocabulary. */
    public int of(Kind kind, String value) {
        if (value == null || value.isEmpty()) {
            return PAD;
        }
        Integer i = index.get(kind).get(value);
        if (i == null) {
            oov.incrementAndGet();
            oovValues.merge(kind + " " + value, 1L, Long::sum);
            return OOV;
        }
        return i;
    }

    /** A card by oracle_id; {@link #HIDDEN} when the identity is unknown to the viewer. */
    public int card(String oracleId) {
        return oracleId == null || oracleId.isEmpty() ? HIDDEN : of(Kind.CARD, oracleId);
    }

    public int ability(AbilityKey key) {
        return key == null || key.getKeyCase() == AbilityKey.KeyCase.KEY_NOT_SET ? PAD : of(Kind.ABILITY, abilityName(key));
    }

    /** The vocabulary name of an ability key: {@code p:<oracle_id>:<face>:<index>} or {@code k:<name>:<params|...>}. */
    public static String abilityName(AbilityKey key) {
        if (key.hasPrinted()) {
            return "p:" + key.getPrinted().getOracleId() + ":" + key.getPrinted().getFace() + ":" + key.getPrinted().getIndex();
        }
        return "k:" + key.getKeyword().getName() + ":" + String.join("|", key.getKeyword().getParamsList());
    }

    /** Lookups that missed since agent-core started (the target decks expect none). */
    public long oovCount() {
        return oov.get();
    }

    public Map<String, Long> oovValues() {
        return Map.copyOf(oovValues);
    }
}
