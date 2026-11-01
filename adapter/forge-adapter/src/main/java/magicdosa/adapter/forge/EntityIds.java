package magicdosa.adapter.forge;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
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
    private final Map<Long, Long> prevOf = new HashMap<>();

    long issue() {
        return next++;
    }

    /** Current entity ID of a card (0 when it has never been seen; call {@link #sync} first). */
    long idOf(Card c) {
        if (c == null) {
            return 0;
        }
        Tracked t = cards.get(c.getId());
        return t == null ? 0 : t.id();
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
            if (!present.contains(e.getKey())) {
                Tracked t = e.getValue();
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

    /** ID of an ability instance on the stack; issued the first time it is seen. */
    long abilityId(SpellAbilityStackInstance si) {
        return stackInstances.computeIfAbsent(si.getId(), k -> issue());
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
