package magicdosa.adapter.forge;

import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerHandler;
import forge.game.zone.ZoneType;
import magicdosa.protocol.v1.AbilityKey;
import magicdosa.protocol.v1.BottomGroup;
import magicdosa.protocol.v1.Designation;
import magicdosa.protocol.v1.Effect;
import magicdosa.protocol.v1.EffectDuration;
import magicdosa.protocol.v1.EffectKind;
import magicdosa.protocol.v1.LibraryPosition;
import magicdosa.protocol.v1.PoolMana;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Game state the event stream reports beyond objects and zones (2026-11-06 plan, section 3): effects with their IDs
 * (Q5), what each agent seat knows about library order (E3), and the last mana pool and designations of each player,
 * so that their changes become events. Game-thread only.
 */
final class GameFacts {
    /** Delayed triggers wait in TriggerHandler's private list; it is only read here. */
    private static final Field DELAYED_TRIGGERS;

    static {
        try {
            DELAYED_TRIGGERS = TriggerHandler.class.getDeclaredField("delayedTriggers");
            DELAYED_TRIGGERS.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final ForgeGameSession session;

    // ---- effects (Q5) ----
    private final Map<Object, Long> effectIds = new IdentityHashMap<>();
    private final Map<Long, Effect> effects = new LinkedHashMap<>();

    // ---- library order (E3): per agent seat, the library cards whose place it knows ----
    private enum Anchor { TOP, BOTTOM, GROUP }

    private record Known(Anchor anchor, int group, int groupSize) {}

    private final Map<Integer, Map<Card, Known>> library = new HashMap<>();
    private final Map<Integer, Integer> groups = new HashMap<>();
    /** Cards a seat chose the order of as they went into a library (orderMoveToZoneList). */
    private final Map<Integer, Set<Card>> orderedBySeat = new HashMap<>();

    // ---- last reported pool and designations, per seat ----
    private final Map<Integer, List<PoolMana>> pools = new HashMap<>();
    private final Map<Integer, List<Designation>> designations = new HashMap<>();

    GameFacts(ForgeGameSession session) {
        this.session = session;
    }

    // ---- effects ----

    record EffectChanges(List<Effect> created, List<Long> ended) {}

    /** Effects now: Forge's effect cards (lasting effects, shields) and registered delayed triggers. */
    EffectChanges syncEffects() {
        Game game = session.game();
        Map<Object, Boolean> now = new IdentityHashMap<>();
        for (Player p : game.getRegisteredPlayers()) {
            for (Card c : p.getCardsIn(ZoneType.Command)) {
                if (c.isImmutable() && !c.isEmblem()) {
                    now.put(c, true);
                }
            }
        }
        for (Trigger t : delayedTriggers(game)) {
            now.put(t, true);
        }
        List<Effect> created = new ArrayList<>();
        for (Object o : now.keySet()) {
            if (!effectIds.containsKey(o)) {
                long id = session.ids().issue();
                effectIds.put(o, id);
                Effect e = describe(o, id);
                effects.put(id, e);
                created.add(e);
            }
        }
        List<Long> ended = new ArrayList<>();
        for (Map.Entry<Object, Long> e : new ArrayList<>(effectIds.entrySet())) {
            if (!now.containsKey(e.getKey())) {
                effectIds.remove(e.getKey());
                effects.remove(e.getValue());
                ended.add(e.getValue());
            }
        }
        created.sort(java.util.Comparator.comparingLong(Effect::getId));
        ended.sort(Long::compare);
        return new EffectChanges(created, ended);
    }

    /** Shadow game: an effect object the rebuild made is the real game's effect {@code e}. */
    void adoptEffect(Object o, Effect e) {
        effectIds.put(o, e.getId());
        effects.put(e.getId(), e);
    }

    Collection<Effect> effects() {
        return effects.values();
    }

    /** ID of the delayed trigger a triggered ability came from (AbilityTriggered.effect_id), 0 if none. */
    long effectIdOf(Trigger t) {
        return t == null ? 0 : effectIds.getOrDefault(t, 0L);
    }

    @SuppressWarnings("unchecked")
    private static List<Trigger> delayedTriggers(Game game) {
        try {
            return new ArrayList<>((List<Trigger>) DELAYED_TRIGGERS.get(game.getTriggerHandler()));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private Effect describe(Object o, long id) {
        Effect.Builder b = Effect.newBuilder().setId(id);
        if (o instanceof Trigger t) {
            Card host = t.getHostCard();
            b.setKind(EffectKind.EFFECT_KIND_DELAYED_TRIGGER).setDuration(EffectDuration.EFFECT_DURATION_UNTIL_USED)
                .setSourceId(session.ids().idOf(host)).setDescription(t.toString());
            if (host != null && host.getController() != null) {
                b.setControllerSeat(session.seat(host.getController()));
            }
            AbilityKey key = session.keys().abilityKey(t);
            if (key != null) {
                b.setAbility(key);
            }
            for (Object r : t.getTriggerRemembered()) {
                if (r instanceof GameEntity g) {
                    b.addAffected(session.ref(g)); // what it will act on (the rebound card, a player to draw)
                }
            }
            return b.build();
        }
        Card eff = (Card) o;
        SpellAbility creator = eff.getEffectSourceAbility();
        b.setKind(eff.getReplacementEffects().isEmpty() ? EffectKind.EFFECT_KIND_CONTINUOUS : EffectKind.EFFECT_KIND_SHIELD)
            .setDuration(duration(creator == null ? null : creator.getParam("Duration")))
            .setSourceId(session.ids().idOf(eff.getEffectSource()))
            .setControllerSeat(session.seat(eff.getController()))
            .setDescription(eff.getName());
        if (creator != null) {
            AbilityKey key = session.keys().abilityKeyIfNamed(creator.getRootAbility());
            if (key != null) {
                b.setAbility(key);
            }
        }
        for (Object r : eff.getRemembered()) {
            if (r instanceof GameEntity g) {
                b.addAffected(session.ref(g));
            }
        }
        return b.build();
    }

    private static EffectDuration duration(String forge) {
        if (forge == null) {
            return EffectDuration.EFFECT_DURATION_UNTIL_END_OF_TURN;
        }
        return switch (forge) {
            case "UntilYourNextTurn", "UntilNextTurn" -> EffectDuration.EFFECT_DURATION_UNTIL_YOUR_NEXT_TURN;
            case "UntilTheEndOfYourNextTurn", "UntilEndOfYourNextTurn" -> EffectDuration.EFFECT_DURATION_UNTIL_END_OF_YOUR_NEXT_TURN;
            case "Permanent" -> EffectDuration.EFFECT_DURATION_INDEFINITE;
            case "UntilHostLeavesPlay", "UntilLoseControlOfHost", "UntilHostLeavesPlayOrEOT", "UntilUntaps",
                 "AsLongAs", "UntilHostLeavesPlayOrEndOfTurn" -> EffectDuration.EFFECT_DURATION_WHILE_CONDITION;
            default -> forge.startsWith("AsLongAs") || forge.startsWith("UntilHost")
                ? EffectDuration.EFFECT_DURATION_WHILE_CONDITION : EffectDuration.EFFECT_DURATION_INDEFINITE;
        };
    }

    // ---- library order (E3) ----

    private Map<Card, Known> known(int seat) {
        return library.computeIfAbsent(seat, k -> new IdentityHashMap<>());
    }

    /** The seat put {@code top} back on top and {@code bottom} on the bottom, in those orders (scry, surveil). */
    void arranged(int seat, List<Card> top, List<Card> bottom) {
        top.forEach(c -> known(seat).put(c, new Known(Anchor.TOP, 0, 0)));
        bottom.forEach(c -> known(seat).put(c, new Known(Anchor.BOTTOM, 0, 0)));
    }

    /** The seat chose the order in which these cards go into a library. */
    void orderedBy(int seat, Collection<Card> cards) {
        orderedBySeat.computeIfAbsent(seat, k -> java.util.Collections.newSetFromMap(new IdentityHashMap<>())).addAll(cards);
    }

    /**
     * Cards that just went into a library and that {@code seat} saw before: it knows where they are, from the end they
     * were put at; several put on the bottom in an order it did not choose are a group.
     */
    void movedIntoLibrary(int seat, List<Card> cards) {
        Set<Card> ordered = orderedBySeat.getOrDefault(seat, Set.of());
        List<Card> bottomUnordered = new ArrayList<>();
        for (Card c : cards) {
            List<Card> lib = libraryOf(c);
            int idx = lib.indexOf(c);
            if (idx < 0) {
                continue;
            }
            boolean top = idx < (lib.size() + 1) / 2;
            if (top || ordered.contains(c) || cards.size() == 1) {
                known(seat).put(c, new Known(top ? Anchor.TOP : Anchor.BOTTOM, 0, 0));
            } else {
                bottomUnordered.add(c);
            }
        }
        if (!bottomUnordered.isEmpty()) {
            int g = groups.merge(seat, 1, Integer::sum);
            bottomUnordered.forEach(c -> known(seat).put(c, new Known(Anchor.GROUP, g, bottomUnordered.size())));
        }
        if (orderedBySeat.containsKey(seat)) {
            orderedBySeat.get(seat).removeAll(cards);
        }
    }

    /** A library was shuffled: nobody knows its order any more. */
    void shuffled(Player owner) {
        for (Map<Card, Known> k : library.values()) {
            k.keySet().removeIf(c -> c.getOwner() == owner);
        }
    }

    /** Drops cards that left their library. */
    void prune() {
        for (Map<Card, Known> k : library.values()) {
            k.keySet().removeIf(c -> c.getZone() == null || !c.getZone().is(ZoneType.Library));
        }
    }

    private static List<Card> libraryOf(Card c) {
        List<Card> out = new ArrayList<>();
        if (c.getZone() != null) {
            c.getZone().getCards().forEach(out::add);
        }
        return out;
    }

    /** Where {@code seat} knows {@code c} is in its library, or null. */
    LibraryPosition position(int seat, Card c) {
        Known k = library.getOrDefault(seat, Map.of()).get(c);
        if (k == null || c.getZone() == null || !c.getZone().is(ZoneType.Library)) {
            return null;
        }
        List<Card> lib = libraryOf(c);
        int idx = lib.indexOf(c);
        // a place counted from the top (bottom) holds while the seat knows every card above (below) it: Forge's Dig
        // puts the cards it does not take on the bottom in a random order inside the library, without a move the
        // session sees, and a card known on top before is then somewhere the seat does not know
        if (k.anchor() == Anchor.TOP && !knowsAll(seat, lib.subList(0, idx))
                || k.anchor() == Anchor.BOTTOM && !knowsAll(seat, lib.subList(idx + 1, lib.size()))) {
            return null;
        }
        return switch (k.anchor()) {
            case TOP -> LibraryPosition.newBuilder().setFromTop(idx).build();
            case BOTTOM -> LibraryPosition.newBuilder().setFromBottom(lib.size() - 1 - idx).build();
            case GROUP -> LibraryPosition.newBuilder().setBottomGroup(BottomGroup.newBuilder().setGroup(k.group())
                .setSize(k.groupSize())).build();
        };
    }

    /** Whether {@code seat} knows each of {@code cards} (where it is, or sees it). */
    private boolean knowsAll(int seat, List<Card> cards) {
        Player p = session.player(seat);
        for (Card x : cards) {
            if (!knows(seat, x) && !session.visible(x, p)) {
                return false;
            }
        }
        return true;
    }

    boolean knows(int seat, Card c) {
        return library.getOrDefault(seat, Map.of()).containsKey(c);
    }

    /**
     * Shadow game rebuilt from a turn-start snapshot: what {@code seat} knew of library order, its pool and its
     * designations as the snapshot showed them.
     */
    void restore(int seat, Map<Card, LibraryPosition> knownPositions, List<PoolMana> pool, List<Designation> designs) {
        int maxGroup = 0;
        for (Map.Entry<Card, LibraryPosition> e : knownPositions.entrySet()) {
            LibraryPosition lp = e.getValue();
            Known k = switch (lp.getPositionCase()) {
                case FROM_BOTTOM -> new Known(Anchor.BOTTOM, 0, 0);
                case BOTTOM_GROUP -> new Known(Anchor.GROUP, lp.getBottomGroup().getGroup(), lp.getBottomGroup().getSize());
                default -> new Known(Anchor.TOP, 0, 0);
            };
            maxGroup = Math.max(maxGroup, k.group());
            known(seat).put(e.getKey(), k);
        }
        groups.merge(seat, maxGroup, Math::max);
        pools.put(seat, List.copyOf(pool));
        designations.put(seat, List.copyOf(designs));
    }

    // ---- pools and designations: changes since the last report ----

    /** The pool now, and what was added and what left it since the last call. */
    record PoolChange(List<PoolMana> added, List<PoolMana> removed) {}

    PoolChange pool(int seat, List<PoolMana> now) {
        List<PoolMana> before = pools.getOrDefault(seat, List.of());
        List<PoolMana> added = new ArrayList<>(now);
        List<PoolMana> removed = new ArrayList<>();
        for (PoolMana m : before) {
            if (!added.remove(m)) {
                removed.add(m);
            }
        }
        pools.put(seat, List.copyOf(now));
        return new PoolChange(added, removed);
    }

    /** True when the designations differ from the last ones reported for the seat. */
    boolean designationsChanged(int seat, List<Designation> now) {
        List<Designation> before = designations.put(seat, List.copyOf(now));
        return !now.equals(before == null ? List.of() : before);
    }
}
