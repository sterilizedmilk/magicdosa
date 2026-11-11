package magicdosa.agent.shadow;

import magicdosa.protocol.v1.CardCount;
import magicdosa.protocol.v1.DeckList;
import magicdosa.protocol.v1.GameObject;
import magicdosa.protocol.v1.HiddenIdentity;
import magicdosa.protocol.v1.LibraryOrder;
import magicdosa.protocol.v1.MatchPlayer;
import magicdosa.protocol.v1.Observation;
import magicdosa.protocol.v1.Privileged;
import magicdosa.protocol.v1.Zone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Phase-2 belief sample (verification ②, design: 그림자·탐색 "숨은 정보 맞추기"): the hidden part of a turn-start state
 * drawn uniformly from what the viewer's observation allows. Each seat's current 60 is known for the viewer and for
 * the opponent in game 1 (the registered main deck); for the opponent in games 2 and 3 it is drawn from the 75 with at
 * least the copies seen in this game and at most the copies owned. Every object whose identity the viewer cannot see
 * gets an identity from what is left of its owner's 60; library order is random apart from the positions the viewer
 * knows. The result has the shape of the real {@link Privileged}, so the snapshot builder takes it unchanged.
 */
final class BeliefSampler {
    private BeliefSampler() {}

    /** Why the last attempt on this thread found no sample (diagnostic). */
    static final ThreadLocal<String> lastFailure = ThreadLocal.withInitial(() -> "");

    /**
     * A sample, or null when no 60 fits the observation within {@code tries} attempts. {@code outside}: per seat, the
     * oracle IDs of cards in the game that are not part of its 60 (a companion, from outside the game).
     */
    static Privileged sample(Observation o, Map<Integer, java.util.Set<String>> outside, Map<Long, String> known, Random rnd,
                             int tries) {
        for (int t = 0; t < tries; t++) {
            Privileged p = attempt(o, outside, known, rnd);
            if (p != null) {
                return p;
            }
        }
        return null;
    }

    private static Privileged attempt(Observation o, Map<Integer, java.util.Set<String>> outside, Map<Long, String> known,
                                      Random rnd) {
        Privileged.Builder out = Privileged.newBuilder();
        for (MatchPlayer mp : o.getMatch().getPlayersList()) {
            int seat = mp.getSeat();
            // the identities the viewer sees of this seat's cards (tokens are not part of the 60)
            Map<String, Integer> seen = new TreeMap<>();
            List<GameObject> hidden = new ArrayList<>();
            List<GameObject> library = new ArrayList<>();
            java.util.Set<String> notInSixty = outside.getOrDefault(seat, java.util.Set.of());
            for (GameObject g : o.getObjectsList()) {
                if (g.getOwnerSeat() != seat || g.getToken() || g.getZone() == Zone.ZONE_COMMAND
                        || notInSixty.contains(g.getOracleId())) {
                    continue;
                }
                if (g.getZone() == Zone.ZONE_LIBRARY) {
                    library.add(g);
                }
                if (g.getOracleId().isEmpty() && known.containsKey(g.getId())) {
                    // hidden in this zone but known from a public move (a companion put into hand, a creature
                    // returned to hand): its identity is known; a companion is not of the 60
                    String k = known.get(g.getId());
                    out.addIdentities(HiddenIdentity.newBuilder().setId(g.getId()).setOracleId(k).setFace(1));
                    if (!notInSixty.contains(k)) {
                        seen.merge(k, 1, Integer::sum);
                    }
                    continue;
                }
                if (g.getOracleId().isEmpty()) {
                    hidden.add(g);
                } else {
                    seen.merge(g.getOracleId(), 1, Integer::sum);
                }
            }
            // game 1: the registered main deck; games 2 and 3: the opponent's 60 is drawn from the 75
            Map<String, Integer> deck = mp.getCurrentDeckCount() > 0 ? counts(mp.getCurrentDeckList())
                : o.getMatch().getGameNumber() <= 1 ? counts(mp.getMainList()) : drawSixty(mp, seen, rnd);
            if (deck == null) {
                lastFailure.set("seat " + seat + ": no 60 holds what was seen " + seen);
                return null;
            }
            List<String> pool = new ArrayList<>();
            for (Map.Entry<String, Integer> e : deck.entrySet()) {
                int left = e.getValue() - seen.getOrDefault(e.getKey(), 0);
                for (int i = 0; i < left; i++) {
                    pool.add(e.getKey());
                }
            }
            if (pool.size() < hidden.size()) {
                Map<String, Integer> over = new TreeMap<>();
                seen.forEach((k, v) -> {
                    if (v > deck.getOrDefault(k, 0)) {
                        over.put(k, v - deck.getOrDefault(k, 0));
                    }
                });
                Map<String, Integer> byZone = new TreeMap<>();
                hidden.forEach(h -> byZone.merge(h.getZone().name(), 1, Integer::sum));
                int seenTotal = seen.values().stream().mapToInt(Integer::intValue).sum();
                lastFailure.set("seat " + seat + ": " + hidden.size() + " hidden " + byZone + ", " + seenTotal + " seen, "
                    + pool.size() + " left of the 60; seen beyond it " + over);
                return null; // the observation shows more of a card than this 60 has
            }
            Collections.shuffle(pool, rnd);
            for (int i = 0; i < hidden.size(); i++) {
                out.addIdentities(HiddenIdentity.newBuilder().setId(hidden.get(i).getId()).setOracleId(pool.get(i)).setFace(1));
            }
            out.addLibraries(LibraryOrder.newBuilder().setSeat(seat).addAllIds(order(library, rnd)));
            out.addCurrentDecks(DeckList.newBuilder().setSeat(seat).addAllCards(cardCounts(deck)));
        }
        return out.build();
    }

    /** A 60 from the 75: every copy seen in this game, the rest uniformly from the copies left. */
    private static Map<String, Integer> drawSixty(MatchPlayer mp, Map<String, Integer> seen, Random rnd) {
        Map<String, Integer> owned = counts(mp.getMainList());
        counts(mp.getSideboardList()).forEach((k, v) -> owned.merge(k, v, Integer::sum));
        Map<String, Integer> deck = new TreeMap<>();
        int size = 0;
        for (Map.Entry<String, Integer> e : seen.entrySet()) {
            if (owned.getOrDefault(e.getKey(), 0) < e.getValue()) {
                return null; // seen more than owned
            }
            deck.put(e.getKey(), e.getValue());
            size += e.getValue();
        }
        int target = mp.getMainList().stream().mapToInt(CardCount::getCount).sum();
        List<String> rest = new ArrayList<>();
        owned.forEach((k, v) -> {
            for (int i = deck.getOrDefault(k, 0); i < v; i++) {
                rest.add(k);
            }
        });
        Collections.shuffle(rest, rnd);
        for (int i = 0; size < target && i < rest.size(); i++, size++) {
            deck.merge(rest.get(i), 1, Integer::sum);
        }
        return size == target ? deck : null;
    }

    /** Library IDs top first: the positions the viewer knows kept, the rest in random order. */
    private static List<Long> order(List<GameObject> library, Random rnd) {
        int n = library.size();
        Long[] slots = new Long[n];
        List<Long> free = new ArrayList<>();
        for (GameObject g : library) {
            var pos = g.hasLibraryPosition() ? g.getLibraryPosition() : null;
            int at = -1;
            if (pos != null && pos.hasFromTop()) {
                at = pos.getFromTop();
            } else if (pos != null && pos.hasFromBottom()) {
                at = n - 1 - pos.getFromBottom();
            }
            if (at >= 0 && at < n && slots[at] == null) {
                slots[at] = g.getId();
            } else {
                free.add(g.getId());
            }
        }
        Collections.shuffle(free, rnd);
        List<Long> out = new ArrayList<>();
        int next = 0;
        for (int i = 0; i < n; i++) {
            out.add(slots[i] != null ? slots[i] : free.get(next++));
        }
        return out;
    }

    private static Map<String, Integer> counts(List<CardCount> cards) {
        Map<String, Integer> out = new TreeMap<>();
        cards.forEach(c -> out.merge(c.getOracleId(), c.getCount(), Integer::sum));
        return out;
    }

    private static List<CardCount> cardCounts(Map<String, Integer> deck) {
        List<CardCount> out = new ArrayList<>();
        deck.forEach((k, v) -> out.add(CardCount.newBuilder().setOracleId(k).setCount(v).build()));
        return out;
    }
}
