package magicdosa.adapter.forge;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Entity IDs of one Forge game (design: protocol 엔티티 ID; Q4). One counter per game starting at 1.
 *
 * Forge keeps a card's id across zone changes, so every zone entry gets a new entity ID here (CR 400.7). Moves are
 * found by comparing each card's zone with the last one seen ({@link #sync}), which also catches moves Forge makes
 * without a zone event (opening hands). Moves found together are issued in ascending order of their previous IDs.
 * Game-thread only.
 */
final class EntityIds {
    private static final ZoneType[] PLAYER_ZONES = {
        ZoneType.Library, ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command,
    };

    /** A card's zone change. {@code prevId} is 0 for a new object (token), {@code to} null for an object that ceased. */
    record Move(Card card, long prevId, long newId, ZoneType from, ZoneType to) {}

    private record Tracked(long id, ZoneType zone, Card card) {}

    private long next = 1;
    private final Map<Integer, Tracked> cards = new HashMap<>();
    private final Map<Integer, Long> stackInstances = new HashMap<>();
    private final Map<Integer, Long> stackSources = new HashMap<>();
    /** Triggered abilities by Forge SpellAbility id: the ID issued when they triggered (design: 유발된 순간). */
    private final Map<Integer, Long> triggers = new HashMap<>();
    private final Map<Long, Long> prevOf = new HashMap<>();

    long issue() {
        return next++;
    }

    /**
     * Shadow game: {@code c} is the object the real game named {@code id} (a game rebuilt from a turn-start snapshot).
     * Call before the first {@link #sync}.
     */
    void adopt(Card c, long id) {
        cards.put(c.getId(), new Tracked(id, c.getZone() == null ? null : c.getZone().getZoneType(), c));
        next = Math.max(next, id + 1);
    }

    /**
     * Shadow game with sampled hidden cards: {@code a} and {@code b} trade entity IDs (they also trade places, see
     * {@link ForgeGameSession#swapIdentity}).
     */
    void swap(Card a, Card b) {
        Tracked ta = cards.get(a.getId());
        Tracked tb = cards.get(b.getId());
        if (ta == null || tb == null) {
            return;
        }
        cards.put(a.getId(), new Tracked(tb.id(), tb.zone(), a));
        cards.put(b.getId(), new Tracked(ta.id(), ta.zone(), b));
    }

    /** Shadow game with sampled hidden cards: {@code b} (not tracked: from the sideboard) takes {@code a}'s ID. */
    void replace(Card a, Card b) {
        Tracked ta = cards.remove(a.getId());
        if (ta != null) {
            cards.put(b.getId(), new Tracked(ta.id(), ta.zone(), b));
        }
    }

    /** Shadow game: the next ID the real game will issue. */
    void continueFrom(long nextId) {
        next = Math.max(next, nextId);
    }

    long nextId() {
        return next;
    }

    /** Current entity ID of a card (0 when it has never been seen; call {@link #sync} first). */
    long idOf(Card c) {
        if (c == null) {
            return 0;
        }
        Tracked t = cards.get(c.getId());
        return t == null ? 0 : t.id();
    }

    /** The card that has entity ID {@code id} now, or null. */
    Card cardOf(long id) {
        for (Tracked t : cards.values()) {
            if (t.id() == id) {
                return t.card().getGame().getCardState(t.card());
            }
        }
        return null;
    }

    /** The ID the object had in its previous zone, 0 if none. */
    long prevIdOf(long id) {
        return prevOf.getOrDefault(id, 0L);
    }

    /** Every card currently in the game, in a fixed order (players, zones, zone order, then the stack). */
    static List<Card> allCards(Game game) {
        List<Card> out = new ArrayList<>();
        for (Player p : game.getRegisteredPlayers()) {
            for (ZoneType z : PLAYER_ZONES) {
                for (Card c : p.getCardsIn(z)) {
                    // Forge's effect cards ("... Effect" in the command zone) implement effects; they are not objects
                    if (z != ZoneType.Command || !c.isImmutable() || c.isEmblem()) {
                        out.add(c);
                    }
                }
            }
        }
        game.getStackZone().getCards().forEach(out::add);
        return out;
    }

    /** Finds every zone change since the last call and issues new IDs for them. */
    List<Move> sync(Game game) {
        record Pending(Card card, long prevId, ZoneType from, ZoneType to, int forgeId) {}
        List<Pending> pending = new ArrayList<>();
        Set<Integer> present = new HashSet<>();
        for (Card c : allCards(game)) {
            present.add(c.getId());
            ZoneType zone = c.getZone() == null ? null : c.getZone().getZoneType();
            Tracked t = cards.get(c.getId());
            if (t == null) {
                pending.add(new Pending(c, 0, null, zone, c.getId()));
            } else if (t.zone() != zone) {
                pending.add(new Pending(c, t.id(), t.zone(), zone, c.getId()));
            } else if (t.card() != c) {
                cards.put(c.getId(), new Tracked(t.id(), zone, c)); // Forge replaced the object in place (LKI copy)
            }
        }
        for (Map.Entry<Integer, Tracked> e : new ArrayList<>(cards.entrySet())) {
            Tracked t = e.getValue();
            // A card found in no zone is between zones (Forge fires events mid-move); only tokens cease to exist.
            if (!present.contains(e.getKey()) && t.card().isToken()) {
                pending.add(new Pending(t.card(), t.id(), t.zone(), null, e.getKey()));
            }
        }
        // Q4: moves found together are issued by ascending previous ID; new objects by Forge creation order
        pending.sort(Comparator.comparingLong((Pending p) -> p.prevId() == 0 ? Long.MAX_VALUE : p.prevId())
            .thenComparingInt(Pending::forgeId));
        List<Move> moves = new ArrayList<>();
        for (Pending p : pending) {
            if (p.to() == null) {
                cards.remove(p.forgeId());
                moves.add(new Move(p.card(), p.prevId(), 0, p.from(), null));
                continue;
            }
            long id = issue();
            cards.put(p.forgeId(), new Tracked(id, p.to(), p.card()));
            if (p.prevId() != 0) {
                prevOf.put(id, p.prevId());
            }
            moves.add(new Move(p.card(), p.prevId(), id, p.from(), p.to()));
        }
        return moves;
    }

    /**
     * ID of an ability instance on the stack: a triggered ability keeps the ID it got when it triggered, other
     * abilities get one the first time they are seen.
     */
    long abilityId(SpellAbilityStackInstance si) {
        return stackInstances.computeIfAbsent(si.getId(), k -> {
            Long triggered = triggers.get(si.getSpellAbility().getId());
            return triggered != null ? triggered : issue();
        });
    }

    /** Issues the ID of a triggered ability that just triggered. */
    long trigger(SpellAbility sa) {
        return triggers.computeIfAbsent(sa.getId(), k -> issue());
    }

    boolean hasTriggerId(SpellAbility sa) {
        return triggers.containsKey(sa.getId());
    }

    long triggerId(SpellAbility sa) {
        return triggers.getOrDefault(sa.getId(), 0L);
    }

    boolean hasAbilityId(SpellAbilityStackInstance si) {
        return stackInstances.containsKey(si.getId());
    }

    /** Remembers the source ID an ability was put on the stack with (it keeps it after the source moves). */
    void setSource(SpellAbilityStackInstance si, long sourceId) {
        stackSources.putIfAbsent(si.getId(), sourceId);
    }

    long sourceOf(SpellAbilityStackInstance si, long fallback) {
        return stackSources.getOrDefault(si.getId(), fallback);
    }
}
