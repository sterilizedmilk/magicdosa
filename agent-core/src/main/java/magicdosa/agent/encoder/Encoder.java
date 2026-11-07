package magicdosa.agent.encoder;

import magicdosa.protocol.v1.Candidate;
import magicdosa.protocol.v1.CardCount;
import magicdosa.protocol.v1.Counter;
import magicdosa.protocol.v1.DecisionRequest;
import magicdosa.protocol.v1.Effect;
import magicdosa.protocol.v1.GameObject;
import magicdosa.protocol.v1.MatchPlayer;
import magicdosa.protocol.v1.ObjectRef;
import magicdosa.protocol.v1.Observation;
import magicdosa.protocol.v1.PlayerState;
import magicdosa.protocol.v1.PoolMana;
import magicdosa.protocol.v1.StackItem;
import magicdosa.protocol.v1.StackItemKind;
import magicdosa.protocol.v1.Zone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Observation and candidates of one decision request as tensors (encoder E1-E7; design: 관측 스키마, 행동 스키마). All
 * values are from the deciding seat's point of view: players are "mine" or not, entity IDs become token positions and
 * are never inputs themselves. Variable-length groups are flat lists; the feature names (and so the widths) are part of
 * the shard metadata, so Python batches the tensors without knowing how they were made.
 *
 * Not in this version (E4, E7 and the 2026-11-06 plan): event history, derived features, belief, strategy labels, the
 * number of refusals, granted ability lists. Optional fields have a "known" column; masking them for training is done
 * in Python.
 */
public final class Encoder {
    // ---- feature layouts (names in order; Python reads them from the shard metadata) ----

    public static final List<String> GLOBAL = names(List.of("turn", "my_turn", "my_priority", "nobody_priority", "day",
        "night", "sel_min", "sel_max", "ordered", "game_number", "my_games_won", "opp_games_won", "on_the_play",
        "start_known", "acting_for_other", "effects_provided"), "step_", 15, "type_", 15);

    public static final List<String> TOKEN = names(List.of(
        "mine", "life", "poison", "hand", "library", "lands_played", "mulligans", "pool_w", "pool_u", "pool_b", "pool_r",
        "pool_g", "pool_c", "designations", "player_counters", "spells_cast", "spells_cast_known", "controlled_by_other",
        "face", "token", "copy", "face_down", "owner_mine", "position", "tapped", "sick", "power", "toughness",
        "power_known", "damage", "counter_p1p1", "counter_loyalty", "counter_other", "lib_from_top", "lib_from_bottom",
        "lib_group", "lib_known", "can_attack", "can_attack_known", "attached", "attacking", "blocking", "selected",
        "stack_pos", "sk_spell", "sk_activated", "sk_triggered", "x", "x_set", "modes", "ek_delayed", "ek_continuous",
        "ek_shield"), "zone_", 10, "duration_", 7);

    public static final List<String> CANDIDATE = List.of("x", "x_set", "option_index", "mana_ability", "keep", "play",
        "pay_life", "pool_w", "pool_u", "pool_b", "pool_r", "pool_g", "pool_c", "pool_amount", "done");

    /** Token kinds. */
    public static final int PLAYER = 1, OBJECT = 2, STACK = 3, EFFECT = 4;
    /** Relation kinds (E2). */
    public static final int ATTACHED = 1, ATTACKS = 2, BLOCKS = 3, TARGETS = 4, SOURCE = 5, AFFECTS = 6;
    /** Deck token groups (E3). */
    public static final int MY_CURRENT = 1, MY_SIDEBOARD = 2, OPP_MAIN = 3, OPP_SIDEBOARD = 4, OPP_SEEN_EARLIER = 5,
        MY_REMAINING = 6, OPP_REMAINING = 7;
    /** Candidate kind of the "done" step candidate (Q9); others are Candidate.ActionCase numbers. */
    public static final int DONE = 31;

    private static List<String> names(List<String> base, String p1, int n1, String p2, int n2) {
        List<String> out = new ArrayList<>(base);
        for (int i = 0; i < n1; i++) {
            out.add(p1 + i);
        }
        for (int i = 0; i < n2; i++) {
            out.add(p2 + i);
        }
        return List.copyOf(out);
    }

    private static final Map<String, Integer> T = index(TOKEN);
    private static final Map<String, Integer> G = index(GLOBAL);
    private static final Map<String, Integer> C = index(CANDIDATE);

    private static Map<String, Integer> index(List<String> names) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < names.size(); i++) {
            m.put(names.get(i), i);
        }
        return m;
    }

    /** One request, encoded; a training sample adds the step (candidates, chosen, selected tokens) and labels. */
    public record Encoded(float[] global, int[] tokenKind, int[] tokenCard, int[] tokenKey, float[] tokenFeat,
                          int[] relSrc, int[] relDst, int[] relKind, int[] deckCard, int[] deckCount, int[] deckGroup,
                          int[] candKind, int[] candRef0, int[] candRef1, int[] candCard, int[] candKey, int[] candString,
                          float[] candFeat, Map<Long, Integer> tokenOfEntity, Map<Integer, Integer> tokenOfPlayer) {}

    private final Vocab vocab;

    public Encoder(Vocab vocab) {
        this.vocab = vocab;
    }

    public Vocab vocab() {
        return vocab;
    }

    public Encoded encode(DecisionRequest q) {
        Observation o = q.getObservation();
        int me = q.getSeat();
        // tokens
        List<Integer> kind = new ArrayList<>(), card = new ArrayList<>(), key = new ArrayList<>();
        List<float[]> feat = new ArrayList<>();
        Map<Long, Integer> ofEntity = new HashMap<>();
        Map<Integer, Integer> ofPlayer = new HashMap<>();
        for (PlayerState p : o.getPlayersList()) {
            float[] f = new float[TOKEN.size()];
            f[T.get("mine")] = p.getSeat() == me ? 1 : 0;
            f[T.get("life")] = p.getLife() / 20f;
            f[T.get("hand")] = p.getHandSize() / 10f;
            f[T.get("library")] = p.getLibrarySize() / 60f;
            f[T.get("lands_played")] = p.getLandsPlayedThisTurn();
            f[T.get("mulligans")] = p.getMulligans() / 7f;
            for (Counter c : p.getCountersList()) {
                if (c.getKind().equals("poison")) {
                    f[T.get("poison")] = c.getCount() / 10f;
                } else {
                    f[T.get("player_counters")] += c.getCount() / 10f;
                }
            }
            for (PoolMana m : p.getManaPoolList()) {
                Integer i = T.get("pool_" + m.getColor().toLowerCase());
                if (i != null) {
                    f[i] += m.getAmount() / 10f;
                }
            }
            f[T.get("designations")] = p.getDesignationsCount() / 5f;
            f[T.get("spells_cast")] = p.getSpellsCastThisTurn() / 10f;
            f[T.get("spells_cast_known")] = p.hasSpellsCastThisTurn() ? 1 : 0;
            f[T.get("controlled_by_other")] = p.hasControlledBySeat() && p.getControlledBySeat() != p.getSeat() ? 1 : 0;
            ofPlayer.put(p.getSeat(), kind.size());
            add(kind, card, key, feat, PLAYER, Vocab.PAD, Vocab.PAD, f);
            for (var d : p.getDesignationsList()) {
                vocab.of(Vocab.Kind.STRING, "designation:" + d.getName()); // vocabulary coverage
            }
        }
        for (GameObject g : o.getObjectsList()) {
            if (g.getZone() == Zone.ZONE_LIBRARY && !g.hasLibraryPosition() && g.getOracleId().isEmpty()) {
                continue; // E3: an unknown library card is not a token, it is part of the remaining composition
            }
            if (g.getZone() == Zone.ZONE_STACK) {
                continue; // a spell on the stack is its stack item's token
            }
            float[] f = new float[TOKEN.size()];
            f[T.get("mine")] = g.getControllerSeat() == me ? 1 : 0;
            f[T.get("zone_" + Math.min(9, g.getZoneValue()))] = 1;
            f[T.get("face")] = g.getFace() / 2f;
            f[T.get("token")] = g.getToken() ? 1 : 0;
            f[T.get("copy")] = g.getCopy() ? 1 : 0;
            f[T.get("face_down")] = g.getFaceDown() ? 1 : 0;
            f[T.get("owner_mine")] = g.getOwnerSeat() == me ? 1 : 0;
            f[T.get("position")] = g.hasPosition() ? g.getPosition() / 60f : 0;
            f[T.get("tapped")] = g.getTapped() ? 1 : 0;
            f[T.get("sick")] = g.getSummoningSick() ? 1 : 0;
            if (g.hasPower()) {
                f[T.get("power")] = g.getPower() / 10f;
                f[T.get("toughness")] = g.getToughness() / 10f;
                f[T.get("power_known")] = 1;
            }
            f[T.get("damage")] = g.getDamage() / 10f;
            for (Counter c : g.getCountersList()) {
                String k = c.getKind().equals("+1/+1") || c.getKind().equals("p1p1") ? "counter_p1p1"
                    : c.getKind().equals("loyalty") ? "counter_loyalty" : "counter_other";
                f[T.get(k)] += c.getCount() / 10f;
                vocab.of(Vocab.Kind.STRING, "counter:" + c.getKind());
            }
            if (g.hasLibraryPosition()) {
                var lp = g.getLibraryPosition();
                f[T.get("lib_known")] = 1;
                if (lp.hasFromTop()) {
                    f[T.get("lib_from_top")] = lp.getFromTop() / 60f;
                } else if (lp.hasFromBottom()) {
                    f[T.get("lib_from_bottom")] = lp.getFromBottom() / 60f;
                } else {
                    f[T.get("lib_group")] = lp.getBottomGroup().getSize() / 10f;
                }
            }
            if (g.hasCanAttack()) {
                f[T.get("can_attack")] = g.getCanAttack() ? 1 : 0;
                f[T.get("can_attack_known")] = 1;
            }
            f[T.get("attached")] = g.getAttachedToId() != 0 ? 1 : 0;
            ofEntity.put(g.getId(), kind.size());
            add(kind, card, key, feat, OBJECT, objectCard(g), Vocab.PAD, f);
        }
        for (int i = 0; i < o.getStackCount(); i++) {
            StackItem s = o.getStack(i);
            float[] f = new float[TOKEN.size()];
            f[T.get("mine")] = s.getControllerSeat() == me ? 1 : 0;
            f[T.get("zone_" + Zone.ZONE_STACK_VALUE)] = 1;
            f[T.get("stack_pos")] = i / 10f;
            f[T.get(s.getKind() == StackItemKind.STACK_ITEM_KIND_SPELL ? "sk_spell"
                : s.getKind() == StackItemKind.STACK_ITEM_KIND_TRIGGERED ? "sk_triggered" : "sk_activated")] = 1;
            if (s.hasX()) {
                f[T.get("x")] = s.getX() / 10f;
                f[T.get("x_set")] = 1;
            }
            f[T.get("modes")] = s.getModesCount() / 5f;
            f[T.get("copy")] = s.getCopyOfId() != 0 ? 1 : 0;
            int c = s.hasSpell() ? vocab.card(s.getSpell().getCard().getOracleId()) : Vocab.PAD;
            int k = s.hasAbility() ? vocab.ability(s.getAbility())
                : s.hasSpell() && s.getSpell().hasCastMethod() ? vocab.ability(s.getSpell().getCastMethod()) : Vocab.PAD;
            ofEntity.put(s.getId(), kind.size());
            add(kind, card, key, feat, STACK, c, k, f);
        }
        for (Effect e : o.getEffectsList()) {
            float[] f = new float[TOKEN.size()];
            f[T.get("mine")] = e.getControllerSeat() == me ? 1 : 0;
            f[T.get(switch (e.getKind()) {
                case EFFECT_KIND_DELAYED_TRIGGER -> "ek_delayed";
                case EFFECT_KIND_SHIELD -> "ek_shield";
                default -> "ek_continuous";
            })] = 1;
            f[T.get("duration_" + Math.min(6, e.getDurationValue()))] = 1;
            ofEntity.put(e.getId(), kind.size());
            add(kind, card, key, feat, EFFECT, Vocab.PAD, vocab.ability(e.getAbility()), f);
        }
        // relations
        List<int[]> rel = new ArrayList<>();
        for (GameObject g : o.getObjectsList()) {
            relate(rel, ofEntity, ofPlayer, g.getId(), ObjectRef.newBuilder().setEntityId(g.getAttachedToId()).build(), ATTACHED);
        }
        if (o.hasCombat()) {
            for (var a : o.getCombat().getAttacksList()) {
                relate(rel, ofEntity, ofPlayer, a.getAttackerId(), a.getDefender(), ATTACKS);
                mark(feat, ofEntity.get(a.getAttackerId()), "attacking");
            }
            for (var b : o.getCombat().getBlocksList()) {
                relate(rel, ofEntity, ofPlayer, b.getBlockerId(), ObjectRef.newBuilder().setEntityId(b.getAttackerId()).build(), BLOCKS);
                mark(feat, ofEntity.get(b.getBlockerId()), "blocking");
            }
        }
        for (StackItem s : o.getStackList()) {
            for (ObjectRef t : s.getTargetsList()) {
                relate(rel, ofEntity, ofPlayer, s.getId(), t, TARGETS);
            }
            if (s.getSourceId() != s.getId()) {
                relate(rel, ofEntity, ofPlayer, s.getId(), ObjectRef.newBuilder().setEntityId(s.getSourceId()).build(), SOURCE);
            }
        }
        for (Effect e : o.getEffectsList()) {
            for (ObjectRef a : e.getAffectedList()) {
                relate(rel, ofEntity, ofPlayer, e.getId(), a, AFFECTS);
            }
        }
        // deck tokens (E3)
        List<int[]> deck = new ArrayList<>();
        decks(o, me, deck);
        // candidates
        int n = q.getCandidatesCount();
        int[] ck = new int[n], r0 = new int[n], r1 = new int[n], cc = new int[n], ckey = new int[n], cs = new int[n];
        float[] cf = new float[n * CANDIDATE.size()];
        for (int i = 0; i < n; i++) {
            candidate(q.getCandidates(i), i, ofEntity, ofPlayer, ck, r0, r1, cc, ckey, cs, cf);
        }
        return new Encoded(global(q), ints(kind), ints(card), ints(key), floats(feat, TOKEN.size()),
            col(rel, 0), col(rel, 1), col(rel, 2), col(deck, 0), col(deck, 1), col(deck, 2),
            ck, r0, r1, cc, ckey, cs, cf, ofEntity, ofPlayer);
    }

    private int objectCard(GameObject g) {
        if (!g.getOracleId().isEmpty()) {
            return vocab.card(g.getOracleId());
        }
        return Vocab.HIDDEN; // a hidden card, or a token without a Scryfall token (Q3 fallback: its key is in tokenKey)
    }

    private static void add(List<Integer> kind, List<Integer> card, List<Integer> key, List<float[]> feat, int k, int c,
                            int ky, float[] f) {
        kind.add(k);
        card.add(c);
        key.add(ky);
        feat.add(f);
    }

    private static void mark(List<float[]> feat, Integer token, String name) {
        if (token != null) {
            feat.get(token)[T.get(name)] = 1;
        }
    }

    private static void relate(List<int[]> rel, Map<Long, Integer> ofEntity, Map<Integer, Integer> ofPlayer, long from,
                               ObjectRef to, int kind) {
        Integer src = ofEntity.get(from);
        Integer dst = to.hasPlayerSeat() ? ofPlayer.get(to.getPlayerSeat()) : ofEntity.get(to.getEntityId());
        if (src != null && dst != null) {
            rel.add(new int[] {src, dst, kind});
        }
    }

    private Integer ref(ObjectRef r, Map<Long, Integer> ofEntity, Map<Integer, Integer> ofPlayer) {
        return r.hasPlayerSeat() ? ofPlayer.get(r.getPlayerSeat()) : ofEntity.get(r.getEntityId());
    }

    private void decks(Observation o, int me, List<int[]> deck) {
        Map<String, Integer> visibleMine = new TreeMap<>();
        Map<String, Integer> visibleOpp = new TreeMap<>();
        for (GameObject g : o.getObjectsList()) {
            if (!g.getOracleId().isEmpty() && !g.getToken() && !g.getCopy()) {
                (g.getOwnerSeat() == me ? visibleMine : visibleOpp).merge(g.getOracleId(), 1, Integer::sum);
            }
        }
        for (MatchPlayer p : o.getMatch().getPlayersList()) {
            boolean mine = p.getSeat() == me;
            if (mine) {
                cards(deck, p.getCurrentDeckList(), MY_CURRENT);
                cards(deck, p.getSideboardList(), MY_SIDEBOARD);
                cards(deck, remaining(p.getCurrentDeckList(), visibleMine), MY_REMAINING);
            } else {
                cards(deck, p.getMainList(), OPP_MAIN);
                cards(deck, p.getSideboardList(), OPP_SIDEBOARD);
                cards(deck, p.getSeenInEarlierGamesList(), OPP_SEEN_EARLIER);
                // the opponent's 60 is known only in game 1 (its registered main deck)
                if (o.getMatch().getGameNumber() <= 1) {
                    cards(deck, remaining(p.getMainList(), visibleOpp), OPP_REMAINING);
                }
            }
        }
    }

    /** {@code deck} minus the cards whose identity is seen outside the hidden zones (the remaining composition). */
    private static List<CardCount> remaining(List<CardCount> deck, Map<String, Integer> seen) {
        List<CardCount> out = new ArrayList<>();
        for (CardCount c : deck) {
            int left = c.getCount() - seen.getOrDefault(c.getOracleId(), 0);
            if (left > 0) {
                out.add(CardCount.newBuilder().setOracleId(c.getOracleId()).setCount(left).build());
            }
        }
        return out;
    }

    private void cards(List<int[]> deck, List<CardCount> counts, int group) {
        for (CardCount c : counts) {
            deck.add(new int[] {vocab.card(c.getOracleId()), c.getCount(), group});
        }
    }

    private float[] global(DecisionRequest q) {
        Observation o = q.getObservation();
        int me = q.getSeat();
        float[] f = new float[GLOBAL.size()];
        f[G.get("turn")] = Math.min(40, o.getGame().getTurn()) / 40f;
        f[G.get("step_" + Math.min(14, o.getGame().getStepValue()))] = 1;
        f[G.get("my_turn")] = o.getGame().getActiveSeat() == me ? 1 : 0;
        if (o.getGame().hasPrioritySeat()) {
            f[G.get("my_priority")] = o.getGame().getPrioritySeat() == me ? 1 : 0;
        } else {
            f[G.get("nobody_priority")] = 1;
        }
        f[G.get("day")] = o.getGame().getDayNightValue() == 1 ? 1 : 0;
        f[G.get("night")] = o.getGame().getDayNightValue() == 2 ? 1 : 0;
        f[G.get("type_" + Math.min(14, q.getTypeValue()))] = 1;
        f[G.get("sel_min")] = Math.min(10, q.getConstraints().getMin()) / 10f;
        f[G.get("sel_max")] = Math.min(10, q.getConstraints().getMax()) / 10f;
        f[G.get("ordered")] = q.getConstraints().getOrdered() ? 1 : 0;
        f[G.get("game_number")] = o.getMatch().getGameNumber() / 3f;
        for (MatchPlayer p : o.getMatch().getPlayersList()) {
            f[G.get(p.getSeat() == me ? "my_games_won" : "opp_games_won")] = p.getGamesWon() / 2f;
        }
        if (o.getMatch().hasStartingSeat()) {
            f[G.get("start_known")] = 1;
            f[G.get("on_the_play")] = o.getMatch().getStartingSeat() == me ? 1 : 0;
        }
        f[G.get("acting_for_other")] = q.hasActingSeat() && q.getActingSeat() != me ? 1 : 0;
        f[G.get("effects_provided")] = o.getEffectsProvided() ? 1 : 0;
        return f;
    }

    private void candidate(Candidate c, int i, Map<Long, Integer> ofEntity, Map<Integer, Integer> ofPlayer, int[] ck,
                           int[] r0, int[] r1, int[] cc, int[] ckey, int[] cs, float[] cf) {
        int base = i * CANDIDATE.size();
        ck[i] = c.getActionCase().getNumber();
        r0[i] = -1;
        r1[i] = -1;
        switch (c.getActionCase()) {
            case PLAY_LAND -> {
                r0[i] = idx(ofEntity.get(c.getPlayLand().getCardId()));
                cc[i] = vocab.card(c.getPlayLand().getLand().getOracleId());
            }
            case CAST_SPELL -> {
                r0[i] = idx(ofEntity.get(c.getCastSpell().getCardId()));
                cc[i] = vocab.card(c.getCastSpell().getSpell().getCard().getOracleId());
                ckey[i] = c.getCastSpell().getSpell().hasCastMethod() ? vocab.ability(c.getCastSpell().getSpell().getCastMethod()) : Vocab.PAD;
            }
            case ACTIVATE_ABILITY -> {
                r0[i] = idx(ofEntity.get(c.getActivateAbility().getSourceId()));
                ckey[i] = vocab.ability(c.getActivateAbility().getAbility());
                cf[base + C.get("mana_ability")] = c.getActivateAbility().getManaAbility() ? 1 : 0;
            }
            case TARGET -> r0[i] = idx(ref(c.getTarget(), ofEntity, ofPlayer));
            case ATTACK -> {
                r0[i] = idx(ofEntity.get(c.getAttack().getAttackerId()));
                r1[i] = idx(ref(c.getAttack().getDefender(), ofEntity, ofPlayer));
            }
            case BLOCK -> {
                r0[i] = idx(ofEntity.get(c.getBlock().getBlockerId()));
                r1[i] = idx(ofEntity.get(c.getBlock().getAttackerId()));
            }
            case OPTION -> {
                cf[base + C.get("option_index")] = Math.min(30, c.getOption().getIndex()) / 10f;
                if (c.getOption().hasX()) {
                    cf[base + C.get("x")] = c.getOption().getX() / 10f;
                    cf[base + C.get("x_set")] = 1;
                }
            }
            case CARD_ID -> r0[i] = idx(ofEntity.get(c.getCardId()));
            case MANA_SOURCE -> {
                r0[i] = c.getManaSource().getSourceIdsCount() == 0 ? -1 : idx(ofEntity.get(c.getManaSource().getSourceIds(0)));
                ckey[i] = vocab.ability(c.getManaSource().getAbility());
            }
            case POOL_MANA -> {
                r0[i] = idx(ofEntity.get(c.getPoolMana().getSourceId()));
                Integer col = C.get("pool_" + c.getPoolMana().getColor().toLowerCase());
                if (col != null) {
                    cf[base + col] = 1;
                }
                cf[base + C.get("pool_amount")] = c.getPoolMana().getAmount() / 10f;
            }
            case KEEP -> cf[base + C.get("keep")] = c.getKeep() ? 1 : 0;
            case PLAY -> cf[base + C.get("play")] = c.getPlay() ? 1 : 0;
            case PAY_LIFE -> cf[base + C.get("pay_life")] = 1;
            case SIDEBOARD_SWAP -> {
                cc[i] = vocab.card(c.getSideboardSwap().getInOracleId());
                cs[i] = vocab.card(c.getSideboardSwap().getOutOracleId()); // the card taken out, same card index space
            }
            case NAMED_ORACLE_ID -> cc[i] = vocab.card(c.getNamedOracleId());
            case NAMED_TYPE -> cs[i] = vocab.of(Vocab.Kind.STRING, "type:" + c.getNamedType());
            default -> { }
        }
    }

    private static int idx(Integer token) {
        return token == null ? -1 : token;
    }

    private static int[] ints(List<Integer> l) {
        return l.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int[] col(List<int[]> rows, int c) {
        int[] out = new int[rows.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = rows.get(i)[c];
        }
        return out;
    }

    private static float[] floats(List<float[]> rows, int width) {
        float[] out = new float[rows.size() * width];
        for (int i = 0; i < rows.size(); i++) {
            System.arraycopy(rows.get(i), 0, out, i * width, width);
        }
        return out;
    }

    /** Feature column of {@code name} in token rows (for the "selected" flag of multi-select steps). */
    public static int tokenColumn(String name) {
        return T.get(name);
    }

    public static int candidateColumn(String name) {
        return C.get(name);
    }
}
