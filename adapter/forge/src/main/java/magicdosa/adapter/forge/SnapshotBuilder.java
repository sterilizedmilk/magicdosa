package magicdosa.adapter.forge;

import forge.StaticData;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameState;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.card.CounterType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import magicdosa.protocol.v1.CardCount;
import magicdosa.protocol.v1.Counter;
import magicdosa.protocol.v1.GameObject;
import magicdosa.protocol.v1.HiddenIdentity;
import magicdosa.protocol.v1.LibraryOrder;
import magicdosa.protocol.v1.MatchPlayer;
import magicdosa.protocol.v1.Observation;
import magicdosa.protocol.v1.PlayerState;
import magicdosa.protocol.v1.PoolMana;
import magicdosa.protocol.v1.Privileged;
import magicdosa.protocol.v1.TurnStartSnapshot;
import magicdosa.protocol.v1.Zone;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Rebuilds a Forge game from a turn-start snapshot (design: 그림자·탐색 "턴 시작 저장과 재생성"): the public state as
 * the snapshot shows it, the hidden cards from {@code hidden} (the real ones in verification ①, a belief sample in
 * ②). The game is left at the end of the previous turn's cleanup, at a loop boundary: the next steps of Forge's own
 * loop begin the snapshot's turn (its untap step). Built with Forge's {@link GameState}; what the snapshot holds but
 * this builder cannot express is listed in {@link Result#gaps()} (design: the builder need not be complete).
 *
 * Must run on a thread whose name starts with "Game" ({@code GameState.applyToGame}).
 */
public final class SnapshotBuilder {
    private static final Field ID_TO_CARD;

    static {
        try {
            ID_TO_CARD = GameState.class.getDeclaredField("idToCard");
            ID_TO_CARD.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** The rebuilt game, its objects by the real entity IDs, the next ID the real game will issue, and the gaps. */
    public record Result(Game game, Match match, Map<Card, Long> ids, Map<Object, Long> effectIds, long nextId, List<String> gaps) {}

    private final CardKeys keys;
    private final Map<String, String> counterKinds = new HashMap<>(); // protocol counter kind -> Forge counter name

    public SnapshotBuilder(CardKeys keys) {
        this.keys = keys;
        for (CounterEnumType t : CounterEnumType.values()) {
            counterKinds.put(ObservationBuilder.counterName(t), t.name());
        }
    }

    /**
     * @param hidden identities of the objects the snapshot's seat cannot see, every library's order, and the current
     *               60 of every seat
     * @param nextId one more than the largest entity ID the stream has named so far
     */
    public Result build(TurnStartSnapshot snapshot, Privileged hidden, long nextId) {
        return build(snapshot, hidden, nextId, id -> id);
    }

    /**
     * @param firstId the first entity ID an object had in this game (its ID chain followed back through prev_id):
     *                Forge keeps a card's own ID when it changes zone, so the real game's card IDs grow with it
     */
    public Result build(TurnStartSnapshot snapshot, Privileged hidden, long nextId, java.util.function.LongUnaryOperator firstId) {
        Observation o = snapshot.getObservation();
        List<String> gaps = new ArrayList<>();
        Map<Long, HiddenIdentity> identities = new HashMap<>();
        hidden.getIdentitiesList().forEach(h -> identities.put(h.getId(), h));

        Match match = match(o, hidden);
        Game game = GameCreation.createGame(match);
        game.setNoGUIUser();

        // objects by zone and owner; GameState places a card in its owner's zone and sets the controller afterwards
        Map<Long, GameObject> byId = new LinkedHashMap<>();
        o.getObjectsList().forEach(g -> byId.put(g.getId(), g));
        int seats = o.getPlayersCount();
        List<StringBuilder> lines = new ArrayList<>();
        Map<Integer, Long> tagToId = new HashMap<>();
        int[] tag = {1};
        Map<Long, Integer> idToTag = new HashMap<>();
        for (GameObject g : byId.values()) {
            idToTag.put(g.getId(), tag[0]++);
        }
        for (LibraryOrder lib : hidden.getLibrariesList()) {
            for (long id : lib.getIdsList()) {
                idToTag.computeIfAbsent(id, k -> tag[0]++);
            }
        }
        idToTag.forEach((id, t) -> tagToId.put(t, id));

        StringBuilder gs = new StringBuilder();
        boolean gameStart = o.getGame().getTurn() == 0; // the game-start snapshot: before opening hands, no turn yet
        int active = o.getGame().getActiveSeat();
        int previous = (active + 1) % seats; // no extra turns: the other seat had the turn before
        if (gameStart) {
            gs.append("turn=0\nactiveplayer=p").append(o.getMatch().getStartingSeat()).append("\nactivephase=NONE\n");
        } else {
            gs.append("turn=").append(o.getGame().getTurn() - 1).append('\n');
            gs.append("activeplayer=p").append(previous).append('\n');
            gs.append("activephase=CLEANUP\n");
        }
        for (PlayerState ps : o.getPlayersList()) {
            int seat = ps.getSeat();
            String p = "p" + seat;
            gs.append(p).append("life=").append(ps.getLife()).append('\n');
            if (ps.getCountersCount() > 0) {
                gs.append(p).append("counters=").append(counters(ps.getCountersList(), gaps)).append('\n');
            }
            gs.append(p).append("landsplayed=0\n");
            if (ps.getDesignationsCount() > 0) {
                gaps.add("designations of seat " + seat + ": " + ps.getDesignationsList());
            }
            String pool = pool(ps.getManaPoolList(), false, gaps);
            if (!pool.isEmpty()) {
                gs.append(p).append("manapool=").append(pool).append('\n');
            }
            String persistent = pool(ps.getManaPoolList(), true, gaps);
            if (!persistent.isEmpty()) {
                gs.append(p).append("persistentmana=").append(persistent).append('\n');
            }
            // Every card of the seat is created in one pass, in the order of the first entity ID it had in this game:
            // Forge breaks ties between equal cards by its own card ID, which in the real game follows the deck
            // order (that first ID) and stays with the card through zone changes. They are created in the library
            // and then put in their zones (see place).
            List<long[]> mine = new ArrayList<>(); // {first ID, entity ID}
            for (GameObject g : byId.values()) {
                if (g.getOwnerSeat() == seat && g.getZone() != Zone.ZONE_LIBRARY) {
                    mine.add(new long[] {firstId.applyAsLong(g.getId()), g.getId()});
                }
            }
            for (LibraryOrder lib : hidden.getLibrariesList()) {
                if (lib.getSeat() == seat) {
                    for (long id : lib.getIdsList()) {
                        mine.add(new long[] {firstId.applyAsLong(id), id});
                    }
                }
            }
            mine.sort(Comparator.comparingLong((long[] a) -> a[0]).thenComparingLong(a -> a[1]));
            List<String> cards = new ArrayList<>();
            for (long[] m : mine) {
                GameObject g = byId.get(m[1]);
                String spec;
                if (g != null && g.getZone() != Zone.ZONE_LIBRARY) {
                    spec = spec(g, identities, idToTag, gaps);
                } else {
                    HiddenIdentity h = identities.get(m[1]);
                    String oracle = h != null ? h.getOracleId() : g != null ? g.getOracleId() : "";
                    String name = keys.forgeName(oracle);
                    if (name == null) {
                        gaps.add("library card " + m[1] + " unknown (" + oracle + ")");
                        continue;
                    }
                    spec = name + "|Id:" + idToTag.get(m[1]);
                }
                if (spec != null) {
                    cards.add(spec);
                }
            }
            gs.append(p).append("library=").append(String.join(";", cards)).append('\n');
            gs.append(p).append("sideboard=").append(String.join(";", sideboard(o.getMatch().getPlayers(seat), hidden, seat)))
                .append('\n');
        }
        if (o.getStackCount() > 0) {
            gaps.add("stack not empty at turn start: " + o.getStackCount() + " items");
        }

        if (o.getGame().getDayNight() != magicdosa.protocol.v1.DayNight.DAY_NIGHT_NONE) {
            gaps.add("day/night " + o.getGame().getDayNight());
        }

        // the game is in play (Forge runs triggers and state-based actions only then) and its first player is known;
        // at the game start it is still before the mulligans
        if (!gameStart) {
            game.setAge(forge.game.GameStage.Play);
            if (o.getMatch().hasStartingSeat()) {
                game.setStartingPlayer(game.getPlayers().get(o.getMatch().getStartingSeat()));
            }
        }
        GameState state = new GameState();
        state.parse(List.of(gs.toString().split("\n")));
        state.applyToGame(game);

        Map<Integer, Card> tagged;
        try {
            @SuppressWarnings("unchecked")
            Map<Integer, Card> m = (Map<Integer, Card>) ID_TO_CARD.get(state);
            tagged = m;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        Map<Card, Long> ids = new HashMap<>();
        tagged.forEach((t, c) -> {
            Long id = tagToId.get(t);
            if (id != null) {
                ids.put(game.getCardState(c), id);
            }
        });
        place(game, o, ids, gaps);
        // controllers other than the owner, and the state GameState does not take from its text
        for (Map.Entry<Card, Long> e : ids.entrySet()) {
            GameObject g = byId.get(e.getValue());
            Card c = e.getKey();
            if (g == null) {
                continue;
            }
            if (g.getControllerSeat() != g.getOwnerSeat()) {
                Player controller = game.getPlayers().get(g.getControllerSeat());
                c.setController(controller, game.getNextTimestamp());
            }
        }
        restoreZoneOrder(game, o, hidden, ids);
        Map<Object, Long> effectIds = effects(game, o, ids, gaps, keys);
        game.getAction().checkStaticAbilities();
        // the game starts its turn from the cleanup step of the turn before, with nobody holding priority; before the
        // game's first turn nobody has a turn or priority (GameState always names an active player: cleared here)
        if (!gameStart) {
            game.getPhaseHandler().setPriority(game.getPlayers().get(previous));
        } else {
            for (String f : List.of("playerTurn", "pPlayerPriority", "pFirstPriority")) {
                try {
                    Field field = forge.game.phase.PhaseHandler.class.getDeclaredField(f);
                    field.setAccessible(true);
                    field.set(game.getPhaseHandler(), null);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return new Result(game, match, ids, effectIds, Math.max(nextId, maxId(ids) + 1), gaps);
    }

    /**
     * Moves the cards created in the libraries to their zones, as GameState itself puts permanents onto the
     * battlefield (from hand, with triggers suppressed; tapped, sickness and counters kept across the move), then
     * attaches and marks damage. Replacement effects of entering the battlefield still run (a land that asks for life):
     * life is set again from the snapshot afterwards.
     */
    private static void place(Game game, Observation o, Map<Card, Long> ids, List<String> gaps) {
        Map<Long, Card> byId = new HashMap<>();
        ids.forEach((c, id) -> byId.put(id, c));
        game.getTriggerHandler().setSuppressAllTriggers(true);
        game.getStack().setResolving(true);
        try {
            for (GameObject g : o.getObjectsList()) {
                Card c = byId.get(g.getId());
                if (c == null || g.getZone() == Zone.ZONE_LIBRARY) {
                    continue;
                }
                Player owner = c.getOwner();
                owner.getZone(ZoneType.Library).remove(c);
                switch (g.getZone()) {
                    case ZONE_HAND -> owner.getZone(ZoneType.Hand).add(c);
                    case ZONE_GRAVEYARD -> owner.getZone(ZoneType.Graveyard).add(c);
                    case ZONE_EXILE -> owner.getZone(ZoneType.Exile).add(c);
                    case ZONE_COMMAND -> owner.getZone(ZoneType.Command).add(c);
                    case ZONE_BATTLEFIELD -> {
                        boolean tapped = c.isTapped();
                        boolean sick = c.hasSickness();
                        var counters = c.getCounters();
                        c.setCounters(com.google.common.collect.HashMultiset.create());
                        owner.getZone(ZoneType.Hand).add(c);
                        game.getAction().moveToPlay(c, null, null);
                        Card now = game.getCardState(c);
                        now.setTapped(tapped);
                        now.setSickness(sick);
                        now.setCounters(counters);
                    }
                    default -> gaps.add("object " + g.getId() + " in " + g.getZone() + " not placed");
                }
            }
            for (GameObject g : o.getObjectsList()) {
                if (g.getZone() != Zone.ZONE_BATTLEFIELD) {
                    continue;
                }
                Card c = byId.get(g.getId());
                if (c == null) {
                    continue;
                }
                c = game.getCardState(c);
                if (g.getAttachedToId() != 0) {
                    Card host = byId.get(g.getAttachedToId());
                    if (host != null) {
                        c.attachToEntity(game.getCardState(host), null, true);
                    }
                }
                if (g.getDamage() > 0) {
                    c.setDamage(g.getDamage());
                }
            }
        } finally {
            game.getTriggerHandler().setSuppressAllTriggers(false);
            game.getStack().setResolving(false);
            game.getStack().unfreezeStack();
        }
        for (PlayerState ps : o.getPlayersList()) {
            game.getPlayers().get(ps.getSeat()).setLife(ps.getLife(), null);
        }
    }

    /**
     * Puts every zone in the real game's order: libraries as the hidden state lists them (top first), the other zones
     * as the snapshot lists their objects (an observation lists each zone in the engine's order).
     */
    private static void restoreZoneOrder(Game game, Observation o, Privileged hidden, Map<Card, Long> ids) {
        Map<Long, Card> byId = new HashMap<>();
        ids.forEach((c, id) -> byId.put(id, c));
        for (Player p : game.getPlayers()) {
            int seat = game.getPlayers().indexOf(p);
            for (LibraryOrder lib : hidden.getLibrariesList()) {
                if (lib.getSeat() == seat) {
                    setOrder(p, ZoneType.Library, lib.getIdsList(), byId);
                }
            }
            for (ZoneType z : List.of(ZoneType.Hand, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Battlefield, ZoneType.Command)) {
                List<Long> order = new ArrayList<>();
                for (GameObject g : o.getObjectsList()) {
                    Card c = byId.get(g.getId());
                    if (c != null && c.getZone() != null && c.getZone().is(z) && c.getZone().getPlayer() == p) {
                        order.add(g.getId());
                    }
                }
                setOrder(p, z, order, byId);
            }
        }
    }

    /** Reorders the zone's cards named in {@code order}; cards it does not name keep their places after them. */
    private static void setOrder(Player p, ZoneType z, List<Long> order, Map<Long, Card> byId) {
        List<Card> now = new ArrayList<>();
        p.getZone(z).getCards().forEach(now::add);
        List<Card> out = new ArrayList<>();
        for (long id : order) {
            Card c = byId.get(id);
            if (c != null) {
                Card current = p.getGame().getCardState(c);
                if (now.contains(current) && !out.contains(current)) {
                    out.add(current);
                }
            }
        }
        for (Card c : now) {
            if (!out.contains(c)) {
                out.add(c);
            }
        }
        p.getZone(z).setCards(out);
    }

    /**
     * The effects of the snapshot this builder can make (with the real IDs):
     * <ul>
     * <li>a companion's effect: from the companion card, in the command zone or wherever it went since (Forge keeps
     * the effect after the companion was put into hand);</li>
     * <li>a lasting effect an ability of a card still in the game created (Teferi's +1): the effect-creating part of
     * that ability (Forge's Effect API) is resolved again for the effect's controller, which also registers its
     * duration from the rebuilt turn.</li>
     * </ul>
     * Every other effect is a gap.
     */
    private static Map<Object, Long> effects(Game game, Observation o, Map<Card, Long> ids, List<String> gaps, CardKeys keys) {
        Map<Long, Card> byId = new HashMap<>();
        ids.forEach((c, id) -> byId.put(id, c));
        Map<Object, Long> out = new HashMap<>();
        for (var e : o.getEffectsList()) {
            Card source = byId.get(e.getSourceId());
            Player controller = e.getControllerSeat() < game.getPlayers().size() ? game.getPlayers().get(e.getControllerSeat()) : null;
            Card companion = source != null && source.hasKeyword(forge.game.keyword.Keyword.COMPANION) ? source
                : source == null && controller != null && e.getDescription().endsWith("Companion Effect") ? companionOf(controller) : null;
            if (companion != null && e.getKind() == magicdosa.protocol.v1.EffectKind.EFFECT_KIND_CONTINUOUS) {
                Card effect = Player.createCompanionEffect(game.getCardState(companion));
                companion.getOwner().getZone(ZoneType.Command).add(effect);
                out.put(effect, e.getId());
                continue;
            }
            if (source != null && controller != null && e.getKind() == magicdosa.protocol.v1.EffectKind.EFFECT_KIND_CONTINUOUS
                    && e.hasAbility()) {
                Card made = resolveEffect(game, source, controller, e.getAbility(), keys);
                if (made != null) {
                    out.put(made, e.getId());
                    continue;
                }
            }
            gaps.add("effect " + e.getKind() + " " + e.getDescription() + (source == null ? " (source unknown)"
                : " (source in " + source.getZone() + ", companion " + source.hasKeyword(forge.game.keyword.Keyword.COMPANION) + ")"));
        }
        return out;
    }

    /** The seat's companion (a card with the companion keyword among all its cards), or null. */
    private static Card companionOf(Player p) {
        for (Card c : p.getAllCards()) {
            if (c.hasKeyword(forge.game.keyword.Keyword.COMPANION)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Resolves the Effect-API part of {@code source}'s ability keyed {@code key} for {@code controller} (no costs, no
     * targets) and returns the effect card it made, or null when the ability has no such part or made no card.
     */
    private static Card resolveEffect(Game game, Card source, Player controller, magicdosa.protocol.v1.AbilityKey key, CardKeys keys) {
        for (forge.game.spellability.SpellAbility sa : source.getAllSpellAbilities()) {
            if (!key.equals(keys.abilityKey(sa))) {
                continue;
            }
            for (forge.game.spellability.SpellAbility part = sa; part != null; part = part.getSubAbility()) {
                if (part.getApi() != forge.game.ability.ApiType.Effect || part.usesTargeting()) {
                    continue;
                }
                List<Card> before = new ArrayList<>(controller.getZone(ZoneType.Command).getCards());
                part.setActivatingPlayer(controller);
                part.getApi().getSpellEffect().resolve(part);
                for (Card c : controller.getZone(ZoneType.Command).getCards()) {
                    if (!before.contains(c)) {
                        return c;
                    }
                }
            }
        }
        return null;
    }

    private static long maxId(Map<Card, Long> ids) {
        long max = 0;
        for (long id : ids.values()) {
            max = Math.max(max, id);
        }
        return max;
    }

    /** A match with each seat's current 60 and the rest of its 75 as sideboard; controllers are set by the caller. */
    private Match match(Observation o, Privileged hidden) {
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setGamesPerMatch(3);
        List<RegisteredPlayer> players = new ArrayList<>();
        for (MatchPlayer mp : o.getMatch().getPlayersList()) {
            Deck deck = new Deck(mp.getDeckName());
            for (Map.Entry<String, Integer> e : current(mp, hidden, mp.getSeat()).entrySet()) {
                PaperCard pc = paper(e.getKey());
                if (pc != null) {
                    deck.getMain().add(pc, e.getValue());
                }
            }
            for (String name : sideboard(mp, hidden, mp.getSeat())) {
                PaperCard pc = StaticData.instance().getCommonCards().getCard(name);
                if (pc != null) {
                    deck.getOrCreate(DeckSection.Sideboard).add(pc);
                }
            }
            RegisteredPlayer rp = new RegisteredPlayer(deck);
            rp.setPlayer(new LobbyPlayerAi("seat" + mp.getSeat(), Set.of())); // named as the runner names seats
            players.add(rp);
        }
        return new Match(rules, players, o.getMatch().getMatchId() + "-shadow");
    }

    /** The seat's current 60 by oracle_id: from the hidden state, else the match context (the viewer's own). */
    private static Map<String, Integer> current(MatchPlayer mp, Privileged hidden, int seat) {
        Map<String, Integer> out = new TreeMap<>();
        for (var d : hidden.getCurrentDecksList()) {
            if (d.getSeat() == seat) {
                d.getCardsList().forEach(c -> out.merge(c.getOracleId(), c.getCount(), Integer::sum));
                return out;
            }
        }
        List<CardCount> cards = mp.getCurrentDeckCount() > 0 ? mp.getCurrentDeckList() : mp.getMainList();
        cards.forEach(c -> out.merge(c.getOracleId(), c.getCount(), Integer::sum));
        return out;
    }

    /** The cards of the 75 not in the current 60, by Forge name. */
    private List<String> sideboard(MatchPlayer mp, Privileged hidden, int seat) {
        Map<String, Integer> all = new TreeMap<>();
        mp.getMainList().forEach(c -> all.merge(c.getOracleId(), c.getCount(), Integer::sum));
        mp.getSideboardList().forEach(c -> all.merge(c.getOracleId(), c.getCount(), Integer::sum));
        current(mp, hidden, seat).forEach((oracle, n) -> all.merge(oracle, -n, Integer::sum));
        List<String> out = new ArrayList<>();
        all.forEach((oracle, n) -> {
            String name = keys.forgeName(oracle);
            for (int i = 0; i < n && name != null; i++) {
                out.add(name);
            }
        });
        return out;
    }

    private PaperCard paper(String oracle) {
        String name = keys.forgeName(oracle);
        return name == null ? null : StaticData.instance().getCommonCards().getCard(name);
    }

    private static String zoneKey(Zone z) {
        return switch (z) {
            case ZONE_BATTLEFIELD -> "battlefield";
            case ZONE_HAND -> "hand";
            case ZONE_GRAVEYARD -> "graveyard";
            case ZONE_EXILE -> "exile";
            case ZONE_COMMAND -> "command";
            default -> throw new IllegalArgumentException(z.toString());
        };
    }

    /** GameState text of one object, or null when it cannot be expressed (recorded as a gap). */
    private String spec(GameObject g, Map<Long, HiddenIdentity> identities, Map<Long, Integer> idToTag, List<String> gaps) {
        String oracle = g.getOracleId();
        int face = g.getFace();
        if (oracle.isEmpty()) {
            HiddenIdentity h = identities.get(g.getId());
            if (h == null) {
                gaps.add("hidden object " + g.getId() + " in " + g.getZone() + " has no identity");
                return null;
            }
            oracle = h.getOracleId();
            face = h.getFace();
        }
        StringBuilder b = new StringBuilder();
        if (g.getToken()) {
            String script = keys.tokenScriptFor(oracle);
            if (script == null) {
                gaps.add("token " + oracle + " has no Forge script");
                return null;
            }
            b.append("T:").append(script); // a token script name (GameState's "t:" takes an encoded token string)
        } else {
            String name = keys.forgeName(oracle);
            if (name == null) {
                gaps.add("card " + oracle + " has no Forge name");
                return null;
            }
            b.append(name);
        }
        b.append("|Id:").append(idToTag.get(g.getId()));
        if (g.getCopy()) {
            gaps.add("copy " + g.getId() + " (" + oracle + ") rebuilt as the original card");
        }
        if (g.getFaceDown()) {
            b.append("|FaceDown");
        } else if (face == 2 && g.getZone() == Zone.ZONE_BATTLEFIELD) {
            b.append("|Transformed");
        }
        if (g.getZone() == Zone.ZONE_BATTLEFIELD) {
            if (g.getTapped()) {
                b.append("|Tapped");
            }
            if (g.getSummoningSick()) {
                b.append("|SummonSick");
            }
            if (g.getCountersCount() > 0) {
                b.append("|Counters:").append(counters(g.getCountersList(), gaps));
            }
            if (g.getAttachedToId() != 0 && !idToTag.containsKey(g.getAttachedToId())) {
                gaps.add("attached to unknown object " + g.getAttachedToId());
            }
            if (g.getGrantedAbilitiesCount() > 0) {
                gaps.add("granted abilities on " + g.getId() + " (" + oracle + ")");
            }
        }
        if (g.getZone() == Zone.ZONE_EXILE && !g.getFaceDown()) {
            // exiled with a card, on an adventure, with a cast permission: not in the snapshot's fields
            gaps.add("exiled " + g.getId() + " (" + oracle + ") links unknown");
        }
        return b.toString();
    }

    private String counters(List<Counter> counters, List<String> gaps) {
        List<String> out = new ArrayList<>();
        for (Counter c : counters) {
            String forge = counterKinds.get(c.getKind());
            if (forge == null) {
                gaps.add("counter kind " + c.getKind());
                continue;
            }
            out.add(forge + "=" + c.getCount());
        }
        return String.join(",", out);
    }

    /** Mana in a pool as GameState text ("R R G"); restricted mana and mana tied to a source are gaps. */
    private static String pool(List<PoolMana> pool, boolean persistent, List<String> gaps) {
        List<String> out = new ArrayList<>();
        for (PoolMana m : pool) {
            if (m.getPersists() != persistent) {
                continue;
            }
            if (!m.getRestriction().isEmpty()) {
                gaps.add("restricted mana " + m.getColor() + " (" + m.getRestriction() + ")");
            }
            for (int i = 0; i < m.getAmount(); i++) {
                out.add(m.getColor());
            }
        }
        return String.join(" ", out);
    }
}
