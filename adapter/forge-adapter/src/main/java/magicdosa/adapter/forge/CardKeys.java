package magicdosa.adapter.forge;

import forge.card.CardStateName;
import forge.card.MagicColor;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostParser;
import forge.game.CardTraitBase;
import forge.game.card.Card;
import forge.game.card.CardState;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordInterface;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.Trigger;
import forge.item.IPaperCard;
import forge.item.PaperToken;
import magicdosa.protocol.v1.AbilityKey;
import magicdosa.protocol.v1.CardFaceKey;
import magicdosa.protocol.v1.KeywordAbility;
import magicdosa.protocol.v1.PrintedAbility;
import magicdosa.protocol.v1.SpellKey;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Engine-neutral names for Forge cards and abilities (design: protocol 카드 식별자 / 능력 키; Q1-Q3).
 *
 * Cards and tokens map to Scryfall oracle_ids through {@code oracle-ids.tsv} (built by OracleTableBuilder). Printed
 * abilities are matched to the oracle-text paragraph they come from by text (design: "비용·문구로 자동 매칭"); abilities
 * that cannot be matched are collected in {@link #unmapped()} for review and get no key. Thread-safe; one instance per
 * adapter JVM.
 */
public final class CardKeys {
    /** Similarity below which a printed ability is reported as unmapped instead of guessed. */
    private static final double MIN_SIMILARITY = 0.35;

    private final Map<String, Row> cards = new HashMap<>();
    private final Map<String, Row> tokens = new HashMap<>();
    private final Map<String, AbilityKey> traitCache = new ConcurrentHashMap<>();
    private final Set<String> unmapped = ConcurrentHashMap.newKeySet();
    private final Map<String, String> overrides = new HashMap<>();

    private record Row(String oracleId, List<String> faces) {}

    private CardKeys() {}

    /** Loads the table bundled with the adapter. */
    public static CardKeys load() {
        try (InputStream in = CardKeys.class.getResourceAsStream("oracle-ids.tsv")) {
            if (in == null) {
                throw new IllegalStateException("oracle-ids.tsv missing from the classpath");
            }
            CardKeys keys = new CardKeys();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\t");
                Row row = new Row(f[2], Arrays.asList(f[3].split("\\|")));
                (f[0].equals("token") ? keys.tokens : keys.cards).put(f[1], row);
            }
            try (InputStream o = CardKeys.class.getResourceAsStream("ability-overrides.tsv")) {
                if (o != null) {
                    BufferedReader or = new BufferedReader(new InputStreamReader(o, StandardCharsets.UTF_8));
                    while ((line = or.readLine()) != null) {
                        if (!line.isBlank() && !line.startsWith("#")) {
                            String[] f = line.split("\t", -1);
                            keys.overrides.put(f[0] + "\u0000" + f[1], f[2]);
                        }
                    }
                }
            }
            return keys;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private final Set<String> tokenFallbacks = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Things that could not be named; empty in a correctly configured adapter. Sorted. */
    public List<String> unmapped() {
        return new ArrayList<>(new TreeSet<>(unmapped));
    }

    // ---- cards ----

    /** Forge's database name of the card (the printed card, not a copy effect's). */
    private static String paperName(Card c) {
        IPaperCard paper;
        try {
            paper = c.getPaperCard();
        } catch (RuntimeException e) {
            paper = null; // an object with no printed card (an effect, an unnamed copy)
        }
        return paper == null ? c.getName() : paper.getName();
    }

    /** Token script name of a Forge token ("w_1_1_monk_prowess"), or null. */
    private static String tokenScript(Card c) {
        if (c.getPaperCard() instanceof PaperToken t) {
            String image = t.getImageFilename(1);
            int bar = image.indexOf('|');
            return bar < 0 ? image : image.substring(0, bar);
        }
        return null;
    }

    /** Scryfall oracle_id of a deck-list card name, or "" when unknown. */
    public String oracleIdByName(String forgeName) {
        Row row = cards.get(forgeName);
        if (row == null) {
            unmapped.add("card " + forgeName);
            return "";
        }
        return row.oracleId();
    }

    /** Scryfall oracle_id of a card or token as it is now (copies: the copied card), or "" when unknown. */
    public String oracleId(Card c) {
        Row row = row(c);
        if (row == null) {
            if (c.isToken()) {
                tokenFallbacks.add(tokenScript(c) + " / " + c.getName()); // Q3: identified by the ability that made it
            } else {
                unmapped.add("card " + c.getName());
            }
            return "";
        }
        return row.oracleId();
    }

    /** Token script name (Forge), or null. */
    String tokenScriptOf(Card c) {
        return tokenScript(c);
    }

    /** Tokens without a Scryfall token oracle_id, identified by their creating ability instead (Q3). Sorted. */
    public List<String> tokenFallbacks() {
        return new ArrayList<>(new TreeSet<>(tokenFallbacks));
    }

    private Row row(Card c) {
        if (c.isToken()) {
            String script = tokenScript(c);
            Row t = script == null ? null : tokens.get(script);
            if (t != null) {
                return t;
            }
            return cards.get(c.getName()); // copy token: named after the copied card
        }
        if (c.isCloned()) {
            Row copied = cards.get(c.getName());
            if (copied != null) {
                return copied;
            }
        }
        return cards.get(paperName(c));
    }

    /** Face number (1-based) of the state the card shows now; 0 when face-down or unknown. */
    public int face(Card c) {
        return face(c, c.getCurrentState());
    }

    public int face(Card c, CardState state) {
        if (state.getStateName() == CardStateName.FaceDown) {
            return 0;
        }
        Row row = row(c);
        if (row == null) {
            return 0;
        }
        int i = row.faces().indexOf(state.getName());
        if (i >= 0) {
            return i + 1;
        }
        return row.faces().size() == 1 ? 1 : 0;
    }

    public CardFaceKey faceKey(Card c, CardState state) {
        return CardFaceKey.newBuilder().setOracleId(oracleId(c)).setFace(face(c, state)).build();
    }

    // ---- spells and abilities ----

    /** Key of a spell being cast with {@code sa}: card face + cast method (Q2). */
    public SpellKey spellKey(SpellAbility sa) {
        Card host = sa.getHostCard();
        CardState state = sa.getCardState() != null ? sa.getCardState() : host.getCurrentState();
        SpellKey.Builder b = SpellKey.newBuilder().setCard(faceKey(host, state));
        AbilityKey method = castMethod(sa);
        if (method != null) {
            b.setCastMethod(method);
        }
        return b.build();
    }

    /** What permitted a non-normal cast: the keyword (flashback, evoke, ...) or the static ability allowing it. */
    private AbilityKey castMethod(SpellAbility sa) {
        if (sa.getKeyword() != null) {
            return keywordKey(sa.getKeyword());
        }
        if (sa.getMayPlay() != null) {
            return abilityKey(sa.getMayPlay());
        }
        if (sa.getAlternativeCost() != null) {
            AbilityKey kw = keywordOf(sa.getHostCard(), sa.getAlternativeCost().name());
            if (kw != null) {
                return kw;
            }
            // printed alternative costs (Force of Negation) are static abilities of the card
            for (StaticAbility st : sa.getHostCard().getStaticAbilities()) {
                if (st.isIntrinsic() && "AlternativeCost".equals(st.getParam("Mode"))) {
                    return abilityKey(st);
                }
            }
            unmapped.add("cast method " + sa.getAlternativeCost() + " of " + sa.getHostCard().getName());
        }
        return null;
    }

    /** Key of an activated / triggered / static ability or replacement effect, or null (recorded as unmapped). */
    /**
     * Like {@link #abilityKey} but a trait without text (a game rule's own ability, e.g. the adventure exile effect)
     * is not counted as unmapped: it has no ability to name.
     */
    AbilityKey abilityKeyIfNamed(CardTraitBase trait) {
        if (trait instanceof SpellAbility sa && description(sa).isBlank()) {
            return null;
        }
        return abilityKey(trait);
    }

    public AbilityKey abilityKey(CardTraitBase trait) {
        if (trait instanceof SpellAbility sa && sa.isTrigger() && sa.getTrigger() != null) {
            return abilityKey(sa.getTrigger());
        }
        if (trait.getKeyword() != null) {
            return keywordKey(trait.getKeyword());
        }
        if (trait instanceof SpellAbility sa && sa.getGrantorStatic() != null) {
            return abilityKey(sa.getGrantorStatic());
        }
        if (trait instanceof SpellAbility sa && isBasicLandTypeMana(sa)) {
            // Q16: CR 305.6 mana abilities have no oracle paragraph; keyed like a keyword
            return AbilityKey.newBuilder().setKeyword(KeywordAbility.newBuilder().setName("basic_land_mana")
                .addParams(printedCost(sa.getManaPart().getOrigProduced()))).build();
        }
        Card source = sourceCard(trait.getHostCard());
        String text = description(trait);
        AbilityKey overridden = override(source, text);
        if (overridden != null) {
            return overridden;
        }
        String cacheKey = source.getName() + "\u0000" + text;
        AbilityKey cached = traitCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        AbilityKey key = matchPrinted(source, text);
        if (key == null) {
            key = matchCopiedState(trait, text);
        }
        if (key == null) {
            unmapped.add("ability of " + (source.getName().isEmpty() ? "unnamed " + source + " (" + trait.getClass().getSimpleName() + ")" : source.getName()) + ": " + text);
            return null;
        }
        traitCache.put(cacheKey, key);
        return key;
    }

    private static boolean isBasicLandTypeMana(SpellAbility sa) {
        if (!sa.isManaAbility() || sa.getCardState() == null) {
            return false;
        }
        for (MagicColor.Color c : MagicColor.Color.values()) {
            if (sa.getCardState().getLandManaForColor(c) == sa) {
                return true;
            }
        }
        return false;
    }

    /**
     * Manual mappings (design: "안 되는 것만 수동으로 지정"), {@code ability-overrides.tsv}: card name, start of Forge's
     * description, and {@code keyword:<Name>} (that keyword of the card), {@code printed:<face>:<index>}, or
     * {@code rule:<name>} for a permission a game rule grants (keyword key with that name and no parameters).
     */
    private AbilityKey override(Card card, String text) {
        for (Map.Entry<String, String> e : overrides.entrySet()) {
            String[] k = e.getKey().split("\u0000", -1);
            if (!k[0].equals(card.getName()) || !text.startsWith(k[1])) {
                continue;
            }
            String[] spec = e.getValue().split(":");
            if (spec[0].equals("keyword")) {
                AbilityKey kw = keywordOf(card, spec[1]);
                if (kw != null) {
                    return kw;
                }
            } else if (spec[0].equals("rule")) {
                // TODO(design) Q17: a permission a game rule grants (adventure, 715.4) has no ability; keyed like Q16
                return AbilityKey.newBuilder().setKeyword(KeywordAbility.newBuilder().setName(spec[1])).build();
            } else if (spec[0].equals("printed")) {
                Row row = row(card);
                if (row != null) {
                    return AbilityKey.newBuilder().setPrinted(PrintedAbility.newBuilder().setOracleId(row.oracleId())
                        .setFace(Integer.parseInt(spec[1])).setIndex(Integer.parseInt(spec[2]))).build();
                }
            }
        }
        return null;
    }

    /** Key of the card's keyword whose name starts {@code name} ("Plot" for "Plotted"), or null. */
    private static AbilityKey keywordOf(Card card, String name) {
        for (KeywordInterface kw : card.getKeywords()) {
            String n = kw.getKeyword() == null ? "" : kw.getKeyword().toString();
            if (!n.isEmpty() && (name.equalsIgnoreCase(n) || name.toLowerCase(Locale.ROOT).startsWith(n.toLowerCase(Locale.ROOT)))) {
                return keywordKey(kw);
            }
        }
        return null;
    }

    /** Effect cards (from resolved spells) name the card that created them. */
    private static Card sourceCard(Card host) {
        Card c = host;
        while (c.getEffectSource() != null && c.getEffectSource() != c) {
            c = c.getEffectSource();
        }
        return c;
    }

    private static String description(CardTraitBase trait) {
        if (trait instanceof Trigger t) {
            return t.toString();
        }
        if (trait instanceof SpellAbility sa) {
            return sa.getDescription();
        }
        return trait.hasParam("Description") ? trait.getParam("Description") : trait.toString();
    }

    /** Keyword key: lower-case name and printed parameters ({@code Flashback:R} -> flashback ["{R}"]). */
    public static AbilityKey keywordKey(KeywordInterface kw) {
        String original = kw.getOriginal();
        String[] parts = original.split(":");
        Keyword k = kw.getKeyword();
        String name = k != null && k != Keyword.UNDEFINED ? k.toString() : parts[0];
        KeywordAbility.Builder b = KeywordAbility.newBuilder().setName(name.toLowerCase(Locale.ROOT));
        for (int i = 1; i < parts.length; i++) {
            b.addParams(printedCost(parts[i]));
        }
        return AbilityKey.newBuilder().setKeyword(b).build();
    }

    /** Mana-only Forge costs in printed form ("1 R" -> "{1}{R}"); other costs as Forge writes them. */
    static String printedCost(String forgeCost) {
        String s = forgeCost.trim();
        if (s.matches("[0-9WUBRGCXSP/ ]+")) {
            try {
                return new ManaCost(new ManaCostParser(s)).getSimpleString();
            } catch (RuntimeException e) {
                return s;
            }
        }
        return s;
    }

    private AbilityKey matchPrinted(Card card, String traitText) {
        Row row = row(card);
        if (row == null) {
            return null;
        }
        String norm = normalize(traitText, card.getName());
        AbilityKey best = null;
        double bestScore = 0;
        int faceNo = 0;
        for (CardStateName stateName : card.getStates()) {
            CardState state = card.getState(stateName);
            int face = row.faces().indexOf(state.getName()) + 1;
            if (face == 0 && card.isToken() && row.faces().size() == 1) {
                face = 1; // Forge names some tokens "<type> Token" (Empower's "Jace Token")
            }
            if (face == 0) {
                continue;
            }
            faceNo++;
            List<String> paragraphs = abilityParagraphs(state.getOracleText());
            for (int i = 0; i < paragraphs.size(); i++) {
                double score = similarity(norm, normalize(paragraphs.get(i), card.getName()));
                if (score > bestScore) {
                    bestScore = score;
                    best = AbilityKey.newBuilder().setPrinted(PrintedAbility.newBuilder()
                        .setOracleId(row.oracleId()).setFace(face).setIndex(i + 1)).build();
                }
            }
        }
        return faceNo > 0 && bestScore >= MIN_SIMILARITY ? best : null;
    }

    /**
     * An ability that came with a copy effect (Shifting Woodland as a copy of Urza's Saga) belongs to the copied
     * card's state; it is matched against that card's oracle text even after the copy effect ended.
     */
    private AbilityKey matchCopiedState(CardTraitBase trait, String text) {
        CardState own = trait.getCardState();
        if (own == null) {
            return null;
        }
        Row row = cards.get(own.getName());
        if (row == null) {
            return null;
        }
        int face = Math.max(1, row.faces().indexOf(own.getName()) + 1);
        String norm = normalize(text, own.getName());
        List<String> paragraphs = abilityParagraphs(own.getOracleText());
        double bestScore = 0;
        int best = -1;
        for (int i = 0; i < paragraphs.size(); i++) {
            double score = similarity(norm, normalize(paragraphs.get(i), own.getName()));
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best >= 0 && bestScore >= MIN_SIMILARITY ? AbilityKey.newBuilder().setPrinted(PrintedAbility.newBuilder()
            .setOracleId(row.oracleId()).setFace(face).setIndex(best + 1)).build() : null;
    }

    /** Non-keyword ability paragraphs of one face's oracle text, reminder text removed (Q1). */
    public static List<String> abilityParagraphs(String oracle) {
        List<String> out = new ArrayList<>();
        if (oracle == null) {
            return out;
        }
        for (String p : oracle.replace("\\n", "\n").split("\r?\n")) {
            String t = p.replaceAll("\\([^)]*\\)", "").trim();
            if (!t.isEmpty() && !isKeywordLine(t)) {
                out.add(t);
            }
        }
        return out;
    }

    private static boolean isKeywordLine(String paragraph) {
        for (String part : paragraph.split("[,;]")) {
            if (!isKeyword(part.trim())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isKeyword(String part) {
        String lower = part.toLowerCase(Locale.ROOT);
        for (Keyword k : Keyword.values()) {
            String name = k.toString().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            if (lower.equals(name) || lower.startsWith(name + " ") || lower.startsWith(name + "—")
                    || lower.startsWith(name + "{")) {
                // "Flashback {R}", "Evoke—Exile a white card...", not "Flash of insight" style prose
                String rest = lower.substring(name.length()).trim();
                if (rest.isEmpty() || rest.startsWith("{") || rest.startsWith("—") || rest.matches("^[0-9x].*")
                        || rest.startsWith("—")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String normalize(String text, String cardName) {
        String t = text.replace(cardName, "~").replace("CARDNAME", "~").replace("NICKNAME", "~");
        int comma = cardName.indexOf(',');
        if (comma > 0) {
            t = t.replace(cardName.substring(0, comma), "~");
        }
        return t.toLowerCase(Locale.ROOT).replaceAll("\\([^)]*\\)", " ").replaceAll("[^a-z0-9{}~+/-]+", " ").trim();
    }

    private static double similarity(String a, String b) {
        Set<String> wa = new HashSet<>(Arrays.asList(a.split(" ")));
        Set<String> wb = new HashSet<>(Arrays.asList(b.split(" ")));
        Set<String> inter = new HashSet<>(wa);
        inter.retainAll(wb);
        Set<String> union = new HashSet<>(wa);
        union.addAll(wb);
        return union.isEmpty() ? 0 : (double) inter.size() / union.size();
    }
}
