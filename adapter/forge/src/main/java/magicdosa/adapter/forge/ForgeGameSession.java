package magicdosa.adapter.forge;

import com.google.common.eventbus.Subscribe;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.GameOutcome;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.event.GameEvent;
import forge.game.event.GameEventAttackersDeclared;
import forge.game.event.GameEventBlockersDeclared;
import forge.game.event.GameEventCardCounters;
import forge.game.event.GameEventCardDamaged;
import forge.game.event.GameEventCardTapped;
import forge.game.event.GameEventDayTimeChanged;
import forge.game.event.GameEventGameStarted;
import forge.game.event.GameEventLandPlayed;
import forge.game.event.GameEventPlayerCounters;
import forge.game.event.GameEventPlayerDamaged;
import forge.game.event.GameEventPlayerLivesChanged;
import forge.game.event.GameEventPlayerPoisoned;
import forge.game.event.GameEventManaPool;
import forge.game.event.GameEventScry;
import forge.game.event.GameEventShuffle;
import forge.game.event.GameEventSurveil;
import forge.game.event.EventValueChangeType;
import forge.game.player.GameLossReason;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventTurnBegan;
import forge.game.mana.Mana;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.MagicStack;
import forge.game.zone.ZoneType;
import magicdosa.protocol.Versions;
import magicdosa.protocol.v1.AbilityKey;
import magicdosa.protocol.v1.AbilityTriggered;
import magicdosa.protocol.v1.ActivateAbility;
import magicdosa.protocol.v1.AdapterMessage;
import magicdosa.protocol.v1.Attack;
import magicdosa.protocol.v1.Block;
import magicdosa.protocol.v1.CardRevealed;
import magicdosa.protocol.v1.CastSpell;
import magicdosa.protocol.v1.Counter;
import magicdosa.protocol.v1.CountersChanged;
import magicdosa.protocol.v1.DamageDealt;
import magicdosa.protocol.v1.DayNight;
import magicdosa.protocol.v1.DayNightChanged;
import magicdosa.protocol.v1.Decider;
import magicdosa.protocol.v1.DecisionContext;
import magicdosa.protocol.v1.DecisionRecord;
import magicdosa.protocol.v1.DecisionRequest;
import magicdosa.protocol.v1.DecisionResponse;
import magicdosa.protocol.v1.DecisionStep;
import magicdosa.protocol.v1.DecisionType;
import magicdosa.protocol.v1.DeclareAttackers;
import magicdosa.protocol.v1.DeclareBlockers;
import magicdosa.protocol.v1.Event;
import magicdosa.protocol.v1.FallbackReason;
import magicdosa.protocol.v1.GameEndReason;
import magicdosa.protocol.v1.GameOver;
import magicdosa.protocol.v1.BundleEnd;
import magicdosa.protocol.v1.StepBundle;
import magicdosa.protocol.v1.Hello;
import magicdosa.protocol.v1.HelloReply;
import magicdosa.protocol.v1.LibraryShuffled;
import magicdosa.protocol.v1.LifeChanged;
import magicdosa.protocol.v1.LogHeader;
import magicdosa.protocol.v1.LogRecord;
import magicdosa.protocol.v1.ManaAbilityUse;
import magicdosa.protocol.v1.ManaPayment;
import magicdosa.protocol.v1.ManaUnit;
import magicdosa.protocol.v1.Mode;
import magicdosa.protocol.v1.ObjectCreated;
import magicdosa.protocol.v1.ObjectCreatedKind;
import magicdosa.protocol.v1.ObjectRef;
import magicdosa.protocol.v1.OpponentDecision;
import magicdosa.protocol.v1.PlayLand;
import magicdosa.protocol.v1.PrivilegedRecord;
import magicdosa.protocol.v1.Rejection;
import magicdosa.protocol.v1.ChoiceValue;
import magicdosa.protocol.v1.Observation;
import magicdosa.protocol.v1.ResolutionChoice;
import magicdosa.protocol.v1.RevealReason;
import magicdosa.protocol.v1.SelectionConstraints;
import magicdosa.protocol.v1.TapChanged;
import magicdosa.protocol.v1.ActivateManaAbility;
import magicdosa.protocol.v1.DesignationsChanged;
import magicdosa.protocol.v1.EffectCreated;
import magicdosa.protocol.v1.EffectEnded;
import magicdosa.protocol.v1.KnownLibraryCard;
import magicdosa.protocol.v1.LibraryArranged;
import magicdosa.protocol.v1.LibraryPosition;
import magicdosa.protocol.v1.ManaAdded;
import magicdosa.protocol.v1.ManaPoolEmptied;
import magicdosa.protocol.v1.ManaSpent;
import magicdosa.protocol.v1.PoolMana;
import magicdosa.protocol.v1.TriggeredAbilityRemoved;
import magicdosa.protocol.v1.TriggeredAbilityStacked;
import magicdosa.protocol.v1.TurnStartSnapshot;
import magicdosa.protocol.v1.ZoneMove;
import magicdosa.protocol.v1.Candidate;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * One real Forge game as seen through the protocol: entity IDs, the per-seat event streams, turn-start snapshots,
 * decision requests and the game log (design: forge-adapter 설계; protocol 사건 스트림). Subscribed to the game's event
 * bus; everything runs on the game thread.
 *
 * Streams exist only for agent seats (Q10). Hidden information goes only into the log and, in training mode, into
 * {@link GameOver} at the end (Q11).
 */
public final class ForgeGameSession {
    /** Result of asking agent-core: the response, or why there is none. */
    /**
     * The steps of one choice asked one request at a time because each pick changes the next candidates (Q18: mana
     * payment, sideboarding). The first asked step's decision ID names it; {@link #endBundle} logs its end.
     */
    static final class Bundle {
        long id;
        int steps;
    }

    /** Closes a bundle that asked at least one step. {@code completed}: it ended by its own rule (paid, done, stop). */
    void endBundle(Bundle bundle, boolean completed) {
        if (bundle.steps == 0) {
            return;
        }
        log.write(LogRecord.newBuilder().setBundleEnd(BundleEnd.newBuilder().setId(bundle.id).setSteps(bundle.steps)
            .setCompleted(completed)).build());
        if (!completed) {
            stats.note("bundle cut short: " + bundle.id);
        }
    }

    /** {@code delegate}: the client handed the decision to Forge AI of this game (see {@link DelegatingClient}). */
    record Asked(DecisionRequest request, DecisionResponse response, FallbackReason failure, boolean delegate) {
        Asked(DecisionRequest request, DecisionResponse response, FallbackReason failure) {
            this(request, response, failure, false);
        }

        boolean ok() {
            return failure == null && !delegate;
        }
    }

    private final Game game;
    private final String matchId;
    private final int gameNumber;
    private final List<Deck> registeredDecks;
    private final int[] gamesWon;
    private final Set<Integer> agentSeats;
    private final DecisionClient client;
    private final CardKeys keys;
    private final LogSink log;
    private PriorityCheck priorityCheck = PriorityCheck.NONE;
    private final Mode mode;
    private final String gameId;
    private final SplittableRandom seeds;

    private final EntityIds ids = new EntityIds();
    private final ObservationBuilder observations = new ObservationBuilder(this);
    private final GameFacts facts = new GameFacts(this);
    /** Who has seen each object, by entity ID (Q10: an object seen by a new seat is reported to every stream). */
    private final Map<Long, Set<Integer>> seers = new HashMap<>();
    /** Set by Forge's "mana pool cleared" event: the next pool change is the pool emptying. */
    private boolean poolsCleared;
    /** Per seat, the cards it just arranged (scry, surveil), for the LibraryArranged event of its own stream. */
    private final Map<Integer, List<Card>> arrangedBy = new HashMap<>();
    private final Map<Integer, Long> seq = new HashMap<>();
    private final Map<Integer, Set<Long>> visibleIds = new HashMap<>();
    private final Map<Integer, Integer> mulligans = new HashMap<>();
    private final List<PrivilegedRecord> privileged = new ArrayList<>();
    private final Set<Integer> movedInLastSync = new HashSet<>();
    /** During an action: each moved card's ID from before the action began (Forge card ID -> entity ID). */
    private final Map<Integer, Long> idsAtActionStart = new HashMap<>();
    private long decisions;
    private int snapshotTurn = -1;
    private final DecisionStats stats = new DecisionStats();
    private final Map<Integer, List<magicdosa.protocol.v1.PotentialCast>> potentialCasts = new HashMap<>();
    /** Per agent seat: the opponents' cards (Forge card id -> oracle_id) it saw in this game. */
    private final Map<Integer, Map<Integer, String>> seenOpponentCards = new HashMap<>();
    private Map<Integer, Map<String, Integer>> seenInEarlierGames = Map.of();

    /**
     * Forge fires no event when an ability triggers: triggered abilities wait in the stack's simultaneous-entry list
     * until a player would receive priority. The list is private; it is only read here, never changed.
     */
    private static final Field SIMULTANEOUS_ENTRIES;

    static {
        try {
            SIMULTANEOUS_ENTRIES = MagicStack.class.getDeclaredField("simultaneousStackEntryList");
            SIMULTANEOUS_ENTRIES.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Triggered abilities that triggered and are not on the stack yet, by ID. */
    private final Map<Long, SpellAbility> waitingTriggers = new LinkedHashMap<>();
    private final Map<Long, Long> triggerSources = new HashMap<>();
    /** Forge SpellAbility ids a controller is putting on the stack right now (out of the waiting list, not stacked yet). */
    private final Map<Integer, SpellAbility> beingStacked = new LinkedHashMap<>();
    /** While a controller plays something, state changes wait here until its action event is out (see events.proto). */
    private int actionDepth;
    /** An action event was emitted since the outermost action began. */
    private boolean actionEventSeen;
    private final List<Event.Builder> deferred = new ArrayList<>();
    /** The player who goes first, from the game start on (Forge sets its starting player only after mulligans). */
    private Player firstPlayer;

    public ForgeGameSession(Game game, String matchId, int gameNumber, List<Deck> registeredDecks, int[] gamesWon,
                            Set<Integer> agentSeats, DecisionClient client, CardKeys keys, LogSink log, Mode mode,
                            long seed) {
        this.game = game;
        this.matchId = matchId;
        this.gameNumber = gameNumber;
        this.registeredDecks = registeredDecks;
        this.gamesWon = gamesWon;
        this.agentSeats = Set.copyOf(agentSeats);
        this.client = client;
        this.keys = keys;
        this.log = log;
        this.mode = mode;
        this.gameId = matchId + "-g" + gameNumber;
        this.seeds = new SplittableRandom(seed);
        for (int s : agentSeats) {
            seq.put(s, 0L);
            visibleIds.put(s, new HashSet<>());
        }
    }

    /** Handshake and log header; call before the game starts. Throws when agent-core refuses the game. */
    public void start(String engineVersion, List<String> playerKinds, long gameSeed) throws Exception {
        Hello hello = Hello.newBuilder()
            .setProtocolVersion(Versions.PROTOCOL).setAdapter("forge-adapter").setEngineVersion(engineVersion)
            .setGameId(gameId).setMode(mode).addAllAgentSeats(agentSeats).setMatchId(matchId).setGameNumber(gameNumber)
            .build();
        HelloReply reply = client.hello(hello);
        if (!reply.getAccepted()) {
            throw new IllegalStateException("agent-core refused the game: " + reply.getReason());
        }
        if (reply.getProtocolVersion() != Versions.PROTOCOL) {
            // design (버전): a game does not start when a version differs
            throw new IllegalStateException("agent-core has protocol version " + reply.getProtocolVersion()
                + ", the adapter " + Versions.PROTOCOL);
        }
        LogHeader.Builder h = LogHeader.newBuilder()
            .setProtocolVersion(Versions.PROTOCOL).setEncoderVersion(reply.getEncoderVersion())
            .setModelVersion(reply.getModelVersion()).setAdapter("forge-adapter").setEngineVersion(engineVersion)
            .setMode(mode).setMatchId(matchId).setGameId(gameId).setGameNumber(gameNumber).setGameSeed(gameSeed)
            .addAllPlayerKinds(playerKinds).addAllAgentSeats(agentSeats);
        for (int s = 0; s < seatCount(); s++) {
            h.addPlayers(observations.matchContext(s).getPlayers(s));
        }
        log.write(LogRecord.newBuilder().setHeader(h).build());
        game.subscribeToEvents(this);
    }

    /**
     * Shadow game (agent-core, phase 2): the game was rebuilt from {@code snapshot} with {@code idsByCard} naming its
     * objects as the real game does. The stream of the snapshot's seat continues from the snapshot's sequence number,
     * with what the seat knew then (seen objects, library positions, pool, designations, mulligans). No handshake and
     * no log header; the session starts listening to the game's events.
     */
    public void resume(TurnStartSnapshot snapshot, Map<Card, Long> idsByCard, Map<Object, Long> effectIds, long nextId,
                       Set<Long> seenIds) {
        int viewer = snapshot.getSeat();
        Observation o = snapshot.getObservation();
        observations.fixMatchContext(o.getMatch());
        idsByCard.forEach(ids::adopt);
        ids.continueFrom(nextId);
        seq.put(viewer, snapshot.getSeq());
        snapshotTurn = o.getGame().getTurn();
        Map<Long, Card> byId = new HashMap<>();
        idsByCard.forEach((c, id) -> byId.put(id, c));
        Map<Card, magicdosa.protocol.v1.LibraryPosition> known = new HashMap<>();
        for (magicdosa.protocol.v1.GameObject g : o.getObjectsList()) {
            Card c = byId.get(g.getId());
            if (c == null) {
                continue;
            }
            if (!g.getOracleId().isEmpty()) {
                saw(viewer, c);
            }
            if (g.hasLibraryPosition()) {
                known.put(c, g.getLibraryPosition());
            }
        }
        visibleIds.get(viewer).addAll(seenIds);
        for (magicdosa.protocol.v1.PlayerState ps : o.getPlayersList()) {
            mulligans.put(ps.getSeat(), ps.getMulligans());
        }
        magicdosa.protocol.v1.PlayerState me = o.getPlayers(viewer);
        facts.restore(viewer, known, me.getManaPoolList(), me.getDesignationsList());
        for (magicdosa.protocol.v1.Effect e : o.getEffectsList()) {
            effectIds.forEach((obj, id) -> {
                if (id == e.getId()) {
                    facts.adoptEffect(obj, e);
                }
            });
        }
        game.subscribeToEvents(this);
    }

    /**
     * Shadow game: a checkpoint of this session's own state (entity IDs, streams, what each seat has seen, effects,
     * library knowledge). Forge's game state is checkpointed separately (GameMemento); the returned action puts this
     * session back as it was.
     */
    public Runnable checkpoint() {
        List<StateCopy> parts = List.of(StateCopy.of(this, Object.class), StateCopy.of(ids, Object.class),
            StateCopy.of(facts, Object.class), StateCopy.of(observations, Object.class));
        return () -> parts.forEach(StateCopy::restore);
    }

    /** The request with its observation built again from the game as it is now (shadow recovery test). */
    /**
     * Shadow games: puts the seat's mana pool in the order {@code order} gives (the same mana as observed in the real
     * game), without events. A pool's order is public; Forge pays from it in order, so a different order spends other
     * mana later. Returns false (and changes nothing) when the pools do not hold the same mana or the order is the same.
     */
    public boolean orderPool(int seat, List<magicdosa.protocol.v1.PoolMana> order) {
        Player p = null;
        for (Player x : game.getRegisteredPlayers()) {
            if (seat(x) == seat) {
                p = x;
            }
        }
        if (p == null) {
            return false;
        }
        List<forge.game.mana.Mana> now = new ArrayList<>();
        p.getManaPool().forEach(now::add);
        if (now.size() != order.size()) {
            return false;
        }
        // Forge's Mana.equals compares by value (colour and ability kind, not the source card), so mana from two
        // sources is "equal": match, remove and compare by identity
        List<forge.game.mana.Mana> left = new ArrayList<>(now);
        List<forge.game.mana.Mana> out = new ArrayList<>();
        for (magicdosa.protocol.v1.PoolMana want : order) {
            int found = -1;
            for (int i = 0; i < left.size(); i++) {
                if (observations.poolMana(left.get(i)).equals(want)) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                return false;
            }
            out.add(left.remove(found));
        }
        boolean same = true;
        for (int i = 0; i < out.size(); i++) {
            same &= out.get(i) == now.get(i);
        }
        if (same) {
            return false;
        }
        // removeManaNoEvent removes the first value-equal entry; removing as many as there are empties the pool
        for (forge.game.mana.Mana m : now) {
            p.getManaPool().removeManaNoEvent(m);
        }
        for (forge.game.mana.Mana m : out) {
            p.getManaPool().addManaNoEvent(m);
        }
        return true;
    }

    /**
     * Shadow games with sampled hidden cards (verification 2): the object {@code id} turns out to be {@code oracle}.
     * When the shadow's card under that ID is another card, it trades places and entity IDs with a card of
     * {@code oracle} of the same owner from a hand or library slot not in {@code locked} (IDs whose identity the real
     * game shows; design: 공개되면 손패·라이브러리 사이에서 카드 정체를 교환). Returns 0 when nothing had to change, 1 after a swap,
     * -1 when no such card was there (an identity-swap failure).
     */
    public int swapIdentity(long id, String oracle, Set<Long> locked) {
        return swapIdentity(id, oracle, locked, true, false);
    }

    public int swapIdentity(long id, String oracle, Set<Long> locked, boolean sync) {
        return swapIdentity(id, oracle, locked, sync, false);
    }

    /** {@code sync} false: called while the session reports an event (its IDs are in sync already). */
    /**
     * {@code fromSideboard}: when no hand or library card fits, a card of {@code oracle} from the owner's sideboard
     * takes the place (a sampled 60 that left it out: design 공개된 카드가 샘플 구성에 없으면 구성을 다시 샘플링).
     */
    public int swapIdentity(long id, String oracle, Set<Long> locked, boolean sync, boolean fromSideboard) {
        if (sync) {
            syncZones(); // IDs as the shadow game stands now
        }
        Card a = ids.cardOf(id);
        if (a == null || oracle.isEmpty() || oracle.equals(keys.oracleId(a))) {
            return 0;
        }
        Player owner = a.getOwner();
        Card b = null;
        // a card of the same zone first: an effect under way holds the cards it looks at (a surveil), and a card
        // brought over from another zone would be moved by it from there
        List<ZoneType> zones = a.isInZone(ZoneType.Hand) ? List.of(ZoneType.Hand, ZoneType.Library)
            : List.of(ZoneType.Library, ZoneType.Hand);
        for (ZoneType z : zones) {
            for (Card c : owner.getCardsIn(z)) {
                if (c != a && oracle.equals(keys.oracleId(c)) && !locked.contains(ids.idOf(c)) && !knownToAgents(c)) {
                    b = c;
                    break;
                }
            }
            if (b != null) {
                break;
            }
        }
        if (b == null && fromSideboard) {
            for (Card c : owner.getCardsIn(ZoneType.Sideboard)) {
                if (oracle.equals(keys.oracleId(c))) {
                    forge.game.zone.Zone za = a.getZone();
                    forge.game.zone.Zone sb = c.getZone();
                    ids.replace(a, c);
                    List<Card> ca = new ArrayList<>(za.getCards());
                    ca.set(ca.indexOf(a), c);
                    List<Card> cs = new ArrayList<>(sb.getCards());
                    cs.set(cs.indexOf(c), a);
                    za.setCards(ca);
                    sb.setCards(cs);
                    return 2;
                }
            }
        }
        if (b == null) {
            return -1;
        }
        forge.game.zone.Zone za = a.getZone();
        forge.game.zone.Zone zb = b.getZone();
        // IDs first (with the zones they go to): Zone.setCards fires a zone event, and the session's sync must then
        // find every card where its ID says it is
        ids.swap(a, b);
        if (za == zb) {
            List<Card> cs = new ArrayList<>(za.getCards());
            int ia = cs.indexOf(a);
            int ib = cs.indexOf(b);
            cs.set(ia, b);
            cs.set(ib, a);
            za.setCards(cs);
        } else {
            List<Card> ca = new ArrayList<>(za.getCards());
            List<Card> cb = new ArrayList<>(zb.getCards());
            int ia = ca.indexOf(a);
            int ib = cb.indexOf(b);
            ca.set(ia, b);
            cb.set(ib, a);
            za.setCards(ca);
            zb.setCards(cb);
        }
        return 1;
    }

    public DecisionRequest reask(DecisionRequest r) {
        return r.toBuilder().setObservation(observations.build(r.getSeat())).build();
    }

    // ---- accessors ----

    /** The card the stream names {@code id} now, or null (shadow game). */
    public Card cardOf(long id) {
        return ids.cardOf(id);
    }

    /** The entity ID of a card now, 0 if none (shadow game). */
    public long idOf(Card c) {
        return ids.idOf(c);
    }

    /** The game this session translates. */
    public Game shadowGame() {
        return game;
    }

    Player firstPlayer() {
        return firstPlayer;
    }


    Game game() {
        return game;
    }

    GameFacts facts() {
        return facts;
    }

    ObservationBuilder observationBuilder() {
        return observations;
    }

    EntityIds ids() {
        return ids;
    }

    CardKeys keys() {
        return keys;
    }

    /** Sets the check run on every list of agent-seat priority candidates (runner checks). */
    public void priorityCheck(PriorityCheck check) {
        this.priorityCheck = check;
    }

    PriorityCheck priorityCheck() {
        return priorityCheck;
    }

    public DecisionStats stats() {
        return stats;
    }

    String matchId() {
        return matchId;
    }

    int gameNumber() {
        return gameNumber;
    }

    int seatCount() {
        return game.getRegisteredPlayers().size();
    }

    Deck registeredDeck(int seat) {
        return registeredDecks.get(seat);
    }

    int gamesWon(int seat) {
        return gamesWon[seat];
    }

    public int seat(Player p) {
        return game.getRegisteredPlayers().indexOf(p);
    }

    Player player(int seat) {
        return game.getRegisteredPlayers().get(seat);
    }

    boolean isAgent(Player p) {
        return agentSeats.contains(seat(p));
    }

    int mulligans(int seat) {
        return mulligans.getOrDefault(seat, 0);
    }

    /** What the seat could cast with more mana, as its controller last computed it (observation, approximate). */
    void potentialCasts(Player p, List<magicdosa.protocol.v1.PotentialCast> casts) {
        potentialCasts.put(seat(p), casts);
    }

    List<magicdosa.protocol.v1.PotentialCast> potentialCasts(int seat) {
        return potentialCasts.getOrDefault(seat, List.of());
    }

    /** Per agent seat, the opponents' cards it saw in earlier games of the match (observation, match context). */
    public void seenInEarlierGames(Map<Integer, Map<String, Integer>> seen) {
        seenInEarlierGames = seen;
    }

    public Map<String, Integer> seenInEarlierGames(int viewer) {
        return seenInEarlierGames.getOrDefault(viewer, Map.of());
    }

    /** The opponents' cards the seat saw in this game, by oracle_id. */
    public Map<String, Integer> seenOpponentCards(int viewer) {
        Map<String, Integer> out = new java.util.TreeMap<>();
        seenOpponentCards.getOrDefault(viewer, Map.of()).values().forEach(o -> out.merge(o, 1, Integer::sum));
        return out;
    }

    private void saw(int viewer, Card c) {
        if (c.getOwner() != player(viewer) && !c.isToken()) {
            seenOpponentCards.computeIfAbsent(viewer, k -> new HashMap<>()).put(c.getId(), keys.oracleId(c));
        }
    }

    void tookMulligan(Player p) {
        mulligans.merge(seat(p), 1, Integer::sum);
    }

    /** Shadow games: the agent seats have seen the object {@code id} (the real game showed it to them). */
    public void markSeen(long id) {
        for (int s : agentSeats) {
            visibleIds.get(s).add(id);
        }
    }

    /** Whether an agent seat sees the card or has seen it as this object (its identity is not a sample). */
    private boolean knownToAgents(Card c) {
        for (int s : agentSeats) {
            // the agent's own hand; a shadow's own "seen" is no guide here (it saw sampled identities: what the real
            // game showed is in {@code locked})
            if (c.isInZone(ZoneType.Hand) && visible(c, player(s))) {
                return true;
            }
        }
        return false;
    }

    /** Whether the viewer has seen the object that has entity ID {@code id} now (same object: rule 400.7). */
    boolean seen(int viewer, long id) {
        Set<Long> s = visibleIds.get(viewer);
        return s != null && id != 0 && s.contains(id);
    }

    boolean visible(Card c, Player viewer) {
        return c.getView().canBeShownTo(viewer.getView());
    }

    ObjectRef ref(GameEntity e) {
        if (e instanceof Player p) {
            return ObjectRef.newBuilder().setPlayerSeat(seat(p)).build();
        }
        return ObjectRef.newBuilder().setEntityId(ids.idOf((Card) e)).build();
    }

    /** A target: a player, a card, or a spell / ability on the stack. */
    ObjectRef ref(GameObject o) {
        if (o instanceof GameEntity e) {
            return ref(e);
        }
        if (o instanceof SpellAbility sa) {
            for (SpellAbilityStackInstance si : game.getStack()) {
                if (si.getSpellAbility() == sa || si.getSpellAbility().getRootAbility() == sa.getRootAbility()) {
                    return ObjectRef.newBuilder().setEntityId(sa.isSpell() ? ids.idOf(sa.getHostCard()) : ids.abilityId(si)).build();
                }
            }
            return ObjectRef.newBuilder().setEntityId(ids.idOf(sa.getHostCard())).build();
        }
        return ObjectRef.getDefaultInstance();
    }

    List<ObjectRef> targets(SpellAbility sa) {
        List<ObjectRef> out = new ArrayList<>();
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            if (s.usesTargeting()) {
                for (GameObject o : s.getTargets()) {
                    out.add(ref(o));
                }
            }
        }
        return out;
    }

    // ---- event streams ----

    /** Builds an event body per agent seat ({@code body} may leave the builder untouched to skip that seat). */
    private void emit(boolean engineHandled, BiConsumer<Integer, Event.Builder> body) {
        for (int viewer : agentSeats) {
            Event.Builder b = Event.newBuilder();
            body.accept(viewer, b);
            if (b.getBodyCase() == Event.BodyCase.BODY_NOT_SET) {
                continue;
            }
            b.setGameId(gameId).setViewerSeat(viewer).setTurn(game.getPhaseHandler().getTurn())
                .setStep(ObservationBuilder.step(game)).setEngineHandled(engineHandled);
            if (actionDepth > 0) {
                deferred.add(b); // built now, numbered when published
            } else {
                publish(b);
            }
        }
    }

    private void publish(Event.Builder b) {
        Event e = b.setSeq(seq.merge(b.getViewerSeat(), 1L, Long::sum)).build();
        send(AdapterMessage.newBuilder().setEvent(e).build());
        log.write(LogRecord.newBuilder().setEvent(e).build());
    }

    /** An action event: published before the state changes held back since the action began, which follow it. */
    private void emitAction(Consumer<Event.Builder> body) {
        actionEventSeen = true;
        emitNow(body);
        flushDeferred();
    }

    /**
     * Shows {@code cards} to a seat: one about to choose among them (a search, the top of a library), or one Forge
     * reveals them to (Thoughtseize). The seat's stream learns their identities once per object (CardRevealed,
     * LOOKED_AT), and of each look at library cards (where they are); the other streams learn that it saw them, with
     * the identity only where their own seat sees it (Q10).
     */
    void showTo(Player p, Collection<? extends Card> cards) {
        syncZones();
        int viewer = seat(p);
        for (Card c : cards) {
            long id = ids.idOf(c);
            if (id == 0 || c.getZone() == null) {
                continue;
            }
            Set<Integer> who = seers.computeIfAbsent(id, k -> new java.util.TreeSet<>(seatsSeeing(c)));
            boolean newSeer = who.add(viewer);
            for (int stream : agentSeats) {
                boolean itSees = who.contains(stream);
                if (stream == viewer) {
                    boolean first = visibleIds.get(viewer).add(id);
                    if (!first && !c.getZone().is(ZoneType.Library)) {
                        continue; // already seen
                    }
                    // a library card is looked at again where it is now (Narset's top four holding a card seen
                    // before): which objects were looked at tells where they are, so every look is told
                    if (first) {
                        saw(viewer, c);
                    }
                } else if (!newSeer) {
                    continue;
                }
                // the chooser learns the card; other streams learn that it was seen (Q10), named only where seen
                CardRevealed.Builder r = CardRevealed.newBuilder().setId(id)
                    .setZone(ObservationBuilder.zone(c.getZone().getZoneType())).setReason(RevealReason.REVEAL_REASON_LOOKED_AT)
                    .addAllRevealedToSeats(who);
                if (itSees) {
                    r.setOracleId(keys.oracleId(c)).setFace(keys.face(c));
                }
                emit(false, (seat, b) -> {
                    if (seat == stream) {
                        b.setCardRevealed(r);
                    }
                });
            }
        }
    }

    /** An action a controller reports in the middle of playing something (e.g. the order it puts triggers in). */
    void reportAction(Consumer<Event.Builder> body) {
        syncZones();
        emitAction(body);
    }

    long triggerId(SpellAbility sa) {
        return ids.triggerId(sa);
    }

    /** Published at once, ahead of anything held back. */
    private void emitNow(Consumer<Event.Builder> body) {
        int depth = actionDepth;
        actionDepth = 0;
        emitAll(false, body);
        actionDepth = depth;
    }

    private void flushDeferred() {
        List<Event.Builder> held = new ArrayList<>(deferred);
        deferred.clear();
        held.forEach(this::publish);
    }

    /**
     * A controller starts playing something (casting, activating, playing a land, putting triggers on the stack).
     * Until the matching action event, the zone moves and other changes it causes are held back.
     */
    void beginAction() {
        if (actionDepth == 0) {
            syncZones(); // what happened before belongs to no action
            actionEventSeen = false;
        }
        actionDepth++;
    }

    /**
     * A controller played {@code sa} and no action event came of it: an ability that resolves without the stack (a
     * special action, CR 116.2: a companion put into hand). Reported as an activation with no ability ID.
     */
    void playedWithoutStack(Player p, SpellAbility sa) {
        if (actionEventSeen || sa.isSpell() || sa.isManaAbility() || sa.isLandAbility()) {
            return;
        }
        syncZones();
        Card host = sa.getHostCard();
        long sourceId = idsAtActionStart.getOrDefault(host.getId(), ids.idOf(host));
        ActivateAbility.Builder a = ActivateAbility.newBuilder().setSeat(seat(p)).setSourceId(sourceId)
            .addAllTargets(targets(sa)).addAllPayment(payment(sa));
        AbilityKey key = keys.abilityKey(sa);
        if (key != null) {
            a.setAbility(key);
        }
        emitAction(b -> b.setActivateAbility(a));
    }

    /** True while a controller plays something (a cast, an activation, a land, triggers going on the stack). */
    boolean inAction() {
        return actionDepth > 0;
    }

    void endAction() {
        syncZones();
        if (--actionDepth == 0) {
            flushDeferred();
            idsAtActionStart.clear();
        }
    }

    /** Same body for every seat. */
    void emitAll(boolean engineHandled, Consumer<Event.Builder> body) {
        emit(engineHandled, (viewer, b) -> body.accept(b));
    }

    private void send(AdapterMessage m) {
        try {
            client.send(m);
        } catch (Exception e) {
            stats.sendFailures++;
            throw new IllegalStateException("could not send to agent-core", e);
        }
    }

    /** Issues IDs for zone changes since the last call and streams them, with reveals (design: 사건 ② ③). */
    void syncZones() {
        List<EntityIds.Move> moves = ids.sync(game);
        movedInLastSync.clear();
        for (EntityIds.Move m : moves) {
            movedInLastSync.add(m.card().getId());
            if (actionDepth > 0 && m.prevId() != 0) {
                idsAtActionStart.putIfAbsent(m.card().getId(), m.prevId());
            }
            Card c = m.card();
            if (m.to() == null) {
                emit(false, (viewer, b) -> b.setZoneMove(ZoneMove.newBuilder().setPrevId(m.prevId())
                    .setFrom(ObservationBuilder.zone(m.from())).setTo(magicdosa.protocol.v1.Zone.ZONE_NONE)
                    .setOwnerSeat(seat(c.getOwner())).setControllerSeat(seat(c.getController()))));
                continue;
            }
            if (m.prevId() == 0 && m.to() == ZoneType.Stack && c.isCopiedSpell()) {
                // a copy of a spell is created on the stack, not cast
                emit(false, (viewer, b) -> b.setObjectCreated(ObjectCreated.newBuilder().setId(m.newId())
                    .setKind(ObjectCreatedKind.OBJECT_CREATED_KIND_SPELL_COPY).setZone(magicdosa.protocol.v1.Zone.ZONE_STACK)
                    .setOwnerSeat(seat(c.getOwner())).setControllerSeat(seat(c.getController()))
                    .setOracleId(keys.oracleId(c)).setFace(keys.face(c))));
                continue;
            }
            if (m.prevId() == 0 && m.to() != ZoneType.Library && game.getAge() != forge.game.GameStage.BeforeMulligan
                    && c.isToken()) {
                ObjectCreated.Builder oc = ObjectCreated.newBuilder().setId(m.newId())
                    .setKind(c.isCloned() ? ObjectCreatedKind.OBJECT_CREATED_KIND_COPY_TOKEN : ObjectCreatedKind.OBJECT_CREATED_KIND_TOKEN)
                    .setZone(ObservationBuilder.zone(m.to())).setOwnerSeat(seat(c.getOwner()))
                    .setControllerSeat(seat(c.getController())).setOracleId(keys.oracleId(c)).setFace(keys.face(c))
                    .setCharacteristics(observations.characteristicsOf(c));
                var fallback = observations.tokenFallback(c);
                if (fallback != null) {
                    oc.setTokenFallback(fallback); // Q3: no Scryfall token found
                }
                emit(false, (viewer, b) -> b.setObjectCreated(oc));
                continue;
            }
            emit(false, (viewer, b) -> {
                boolean vis = visible(c, player(viewer));
                ZoneMove.Builder z = ZoneMove.newBuilder().setId(m.newId()).setPrevId(m.prevId())
                    .setFrom(m.prevId() == 0 ? magicdosa.protocol.v1.Zone.ZONE_NONE : ObservationBuilder.zone(m.from()))
                    .setTo(ObservationBuilder.zone(m.to())).setOwnerSeat(seat(c.getOwner()))
                    .setControllerSeat(seat(c.getController())).setFaceDown(c.isFaceDown());
                boolean sawIt = m.to() == ZoneType.Library && m.prevId() != 0 && visibleIds.get(viewer).contains(m.prevId());
                // a card the viewer saw that goes into a hand (a companion put into hand, a creature returned to its
                // owner's hand) stays known there: confirmed information (rule 400.7: the same object until it moves)
                boolean intoHand = m.to() == ZoneType.Hand && m.prevId() != 0 && visibleIds.get(viewer).contains(m.prevId());
                if (intoHand) {
                    visibleIds.get(viewer).add(m.newId());
                }
                if (vis || sawIt || intoHand) {
                    z.setOracleId(keys.oracleId(c)).setFace(keys.face(c));
                    if (m.to() == ZoneType.Library) {
                        z.setPosition(c.getZone().getCards().indexOf(c));
                    }
                }
                b.setZoneMove(z);
            });
        }
        // E3: a seat that saw a card before it went into a library knows where it is
        for (int viewer : agentSeats) {
            List<Card> into = new ArrayList<>();
            for (EntityIds.Move m : moves) {
                if (m.to() == ZoneType.Library && m.prevId() != 0 && visibleIds.get(viewer).contains(m.prevId())) {
                    into.add(m.card());
                }
            }
            if (!into.isEmpty()) {
                facts.movedIntoLibrary(viewer, into);
            }
        }
        facts.prune();
        tapBaseline();
        // reveals: objects whose identity the viewer sees now and did not see before
        for (int viewer : agentSeats) {
            Player v = player(viewer);
            Set<Long> seen = visibleIds.get(viewer);
            for (Card c : EntityIds.allCards(game)) {
                long id = ids.idOf(c);
                if (!visible(c, v) || seen.contains(id)) {
                    continue;
                }
                seen.add(id);
                saw(viewer, c);
                long prev = ids.prevIdOf(id);
                boolean wasHidden = prev == 0 ? c.getZone().is(ZoneType.Library) || c.getZone().is(ZoneType.Hand)
                    : !seen.contains(prev);
                if (!wasHidden) {
                    continue;
                }
                RevealReason reason = revealReason(c, v, prev);
                CardRevealed.Builder r = CardRevealed.newBuilder().setId(id).setOracleId(keys.oracleId(c)).setFace(keys.face(c))
                    .setZone(ObservationBuilder.zone(c.getZone().getZoneType())).setReason(reason)
                    .addAllRevealedToSeats(seatsSeeing(c));
                emit(false, (seat, b) -> {
                    if (seat == viewer) {
                        b.setCardRevealed(r);
                    }
                });
            }
        }
        revealFacts();
        syncFacts();
        pollTriggers();
    }

    /**
     * Q10: when a seat starts seeing a hidden object (Thoughtseize looking at a hand), every stream hears of it; streams
     * of seats that do not see it get the fact without the identity.
     */
    private void revealFacts() {
        for (Card c : EntityIds.allCards(game)) {
            long id = ids.idOf(c);
            if (id == 0 || c.getZone() == null) {
                continue;
            }
            Set<Integer> now = new java.util.TreeSet<>(seatsSeeing(c));
            Set<Integer> before = seers.get(id);
            if (before != null) {
                now.addAll(before); // who has seen an object only grows (showTo adds seats Forge does not track)
            }
            seers.put(id, now);
            if (before == null || before.containsAll(now)
                    || !(c.getZone().is(ZoneType.Hand) || c.getZone().is(ZoneType.Library))) {
                continue;
            }
            for (int viewer : agentSeats) {
                if (now.contains(viewer) && !before.contains(viewer)) {
                    continue; // that stream got the reveal itself
                }
                CardRevealed.Builder r = CardRevealed.newBuilder().setId(id).setZone(ObservationBuilder.zone(c.getZone().getZoneType()))
                    .setReason(RevealReason.REVEAL_REASON_LOOKED_AT).addAllRevealedToSeats(now);
                if (now.contains(viewer)) {
                    r.setOracleId(keys.oracleId(c)).setFace(keys.face(c));
                }
                emit(false, (seat, b) -> {
                    if (seat == viewer) {
                        b.setCardRevealed(r);
                    }
                });
            }
        }
    }

    /** Effects (Q5), mana pools (Q6) and designations (Q13) that changed since the last look. */
    private void syncFacts() {
        GameFacts.EffectChanges changes = facts.syncEffects();
        for (var e : changes.created()) {
            emitAll(false, b -> b.setEffectCreated(EffectCreated.newBuilder().setEffect(e)));
        }
        for (long id : changes.ended()) {
            emitAll(false, b -> b.setEffectEnded(EffectEnded.newBuilder().setEffectId(id)));
        }
        boolean cleared = poolsCleared;
        poolsCleared = false;
        for (Player p : game.getRegisteredPlayers()) {
            int s = seat(p);
            List<PoolMana> now = observations.poolList(p);
            GameFacts.PoolChange change = facts.pool(s, now);
            if (!change.removed().isEmpty()) {
                if (cleared && now.isEmpty()) {
                    emitAll(false, b -> b.setManaPoolEmptied(ManaPoolEmptied.newBuilder().setSeat(s)));
                } else {
                    emitAll(false, b -> b.setManaSpent(ManaSpent.newBuilder().setSeat(s).addAllMana(change.removed())));
                }
            }
            if (!change.added().isEmpty()) {
                emitAll(false, b -> b.setManaAdded(ManaAdded.newBuilder().setSeat(s).addAllMana(change.added())));
            }
            var designations = observations.designations(p);
            if (facts.designationsChanged(s, designations)) {
                emitAll(false, b -> b.setDesignations(DesignationsChanged.newBuilder().setSeat(s).addAllDesignations(designations)));
            }
        }
    }

    /** Each battlefield object's tapped state as last reported (TapChanged), by entity ID. */
    private final Map<Long, Boolean> reportedTapped = new HashMap<>();

    /**
     * Permanents whose tapped state changed without Forge's tap event: undoing a mana ability when a payment is not
     * made untaps its source (CostTap.refund sets the state directly) and Forge reports only a battlefield update.
     */
    private void tapsWithoutEvent() {
        for (Player p : game.getRegisteredPlayers()) {
            for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                long id = ids.idOf(c);
                Boolean was = reportedTapped.get(id);
                if (was != null && was != c.isTapped()) {
                    reportedTapped.put(id, c.isTapped());
                    boolean tapped = c.isTapped();
                    emitAll(false, b -> b.setTap(TapChanged.newBuilder().setId(id).setTapped(tapped)));
                }
            }
        }
    }

    /** The tapped state of permanents not reported yet (entered the battlefield) as they are now. */
    private void tapBaseline() {
        for (Player p : game.getRegisteredPlayers()) {
            for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                reportedTapped.putIfAbsent(ids.idOf(c), c.isTapped());
            }
        }
    }

    /** A seat arranged the top of a library (scry, surveil): kept for the LibraryArranged event of its stream. */
    void arranged(Player decider, List<Card> top, List<Card> bottom) {
        int s = seat(decider);
        facts.arranged(s, top, bottom);
        List<Card> all = new ArrayList<>(top);
        all.addAll(bottom);
        arrangedBy.put(s, all);
        // where the arrangement puts them: Forge announces a scry (GameEventScry) before it moves the cards, so their
        // places then are still the old ones. The kept cards go on top in this order; the others go to the bottom one
        // after another, the last at the very bottom
        for (int i = 0; i < top.size(); i++) {
            arrangedPositions.put(top.get(i), LibraryPosition.newBuilder().setFromTop(i).build());
        }
        for (int i = 0; i < bottom.size(); i++) {
            arrangedPositions.put(bottom.get(i), LibraryPosition.newBuilder().setFromBottom(bottom.size() - 1 - i).build());
        }
    }

    /** The places an arrangement puts its cards, for its LibraryArranged event. */
    private final Map<Card, LibraryPosition> arrangedPositions = new java.util.IdentityHashMap<>();

    private void libraryArranged(Player owner, int toTop, int toBottom, int toGraveyard) {
        int s = seat(owner);
        emit(false, (viewer, b) -> {
            LibraryArranged.Builder a = LibraryArranged.newBuilder().setSeat(s).setLookedAt(toTop + toBottom + toGraveyard)
                .setToTop(toTop).setToBottom(toBottom).setToGraveyard(toGraveyard);
            for (Card c : arrangedBy.getOrDefault(viewer, List.of())) {
                LibraryPosition pos = arrangedPositions.containsKey(c) ? arrangedPositions.get(c) : facts.position(viewer, c);
                if (pos != null) {
                    a.addCards(KnownLibraryCard.newBuilder().setId(ids.idOf(c)).setPosition(pos));
                }
            }
            b.setLibraryArranged(a);
        });
        arrangedBy.clear();
        arrangedPositions.clear();
    }

    /** A mana ability activated as a priority action or while paying during resolution (Q6), after it resolved. */
    void manaAbilityActivated(Player p, SpellAbility sa) {
        ActivateManaAbility.Builder a = ActivateManaAbility.newBuilder().setSeat(seat(p))
            .setSourceId(ids.idOf(sa.getHostCard()));
        AbilityKey key = keys.abilityKey(sa);
        if (key != null) {
            a.setAbility(key);
        }
        if (sa.getManaPart() != null) {
            for (Mana m : sa.getManaPart().getLastManaProduced()) {
                a.addProduced(ManaUnit.newBuilder().setColor(ObservationBuilder.colorLetter(m.getColor())).setAmount(1));
            }
        }
        reportAction(b -> b.setActivateManaAbility(a));
    }

    /** Chosen modes of a modal spell or ability: 0-based indices in oracle order (Q1). */
    /**
     * The modes each stack item was put on the stack with. Forge AI may reset a charm's chosen list after the cast
     * (CharmAi weighing the same spell again; GameActionUtil's rollback) while the chosen modes stay in its sub-ability
     * chain: the stack item keeps the modes its cast reported.
     */
    private final Map<SpellAbilityStackInstance, List<Integer>> stackModes = new java.util.WeakHashMap<>();

    /** The modes of a stack item: as Forge holds them, else as its cast reported them. */
    List<Integer> stackModes(SpellAbilityStackInstance si, SpellAbility sa) {
        List<Integer> m = modes(sa);
        return m.isEmpty() ? stackModes.getOrDefault(si, m) : m;
    }

    List<Integer> modes(SpellAbility sa) {
        SpellAbility root = sa.getRootAbility();
        List<Integer> out = new ArrayList<>();
        if (root.getApi() == forge.game.ability.ApiType.Charm && root.getSubAbility() != null) {
            // the chosen modes are chained as the charm's sub-abilities on both play paths; the chosen list is kept
            // only by Forge AI, and a cast on the human play path (a replayed cast, an agent's cast) leaves the list
            // Forge AI stored while weighing the card earlier (CharmAi), not the modes cast
            List<forge.game.spellability.AbilitySub> chain = new ArrayList<>();
            for (forge.game.spellability.AbilitySub s = root.getSubAbility(); s != null; s = s.getSubAbility()) {
                chain.add(s);
            }
            return modeIndices(sa, chain);
        }
        if (root.getChosenList() == null) {
            return out;
        }
        return modeIndices(sa, root.getChosenList());
    }

    /** Modes of {@code sa} as 0-based indices in oracle order (Q1), in the given order. */
    List<Integer> modeIndices(SpellAbility sa, List<forge.game.spellability.AbilitySub> modes) {
        List<Integer> out = new ArrayList<>();
        if (modes == null) {
            return out;
        }
        List<forge.game.spellability.AbilitySub> all = sa.getRootAbility().getAdditionalAbilityList("Choices");
        for (var chosen : modes) {
            int i = all.indexOf(chosen);
            if (i < 0) {
                for (int j = 0; j < all.size(); j++) {
                    if (all.get(j).getDescription().equals(chosen.getDescription())) {
                        i = j;
                        break;
                    }
                }
            }
            if (i >= 0) {
                out.add(i);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<SpellAbility> simultaneousEntries() {
        try {
            return new ArrayList<>((List<SpellAbility>) SIMULTANEOUS_ENTRIES.get(game.getStack()));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private Player controllerOf(SpellAbility sa) {
        return sa.getActivatingPlayer() != null ? sa.getActivatingPlayer() : sa.getHostCard().getController();
    }

    private boolean onStack(SpellAbility sa) {
        for (SpellAbilityStackInstance si : game.getStack()) {
            if (si.getSpellAbility().getId() == sa.getId()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Issues IDs to abilities that triggered since the last look (design: 엔티티 ID "유발된 순간"; Q4 order: APNAP, then
     * source ID, then ability key) and reports triggered abilities that left without being put on the stack.
     */
    private void pollTriggers() {
        List<SpellAbility> waiting = simultaneousEntries();
        waiting.addAll(beingStacked.values()); // Forge takes a player's entries out of the list before handing them over
        List<SpellAbility> fresh = new ArrayList<>();
        Set<Integer> waitingIds = new HashSet<>();
        for (SpellAbility sa : waiting) {
            waitingIds.add(sa.getId());
            if (sa.isTrigger() && !ids.hasTriggerId(sa)) {
                fresh.add(sa);
            }
        }
        if (!fresh.isEmpty()) {
            Player turn = game.getPhaseHandler().getPlayerTurn();
            List<Player> apnap = turn == null ? game.getRegisteredPlayers() : game.getPlayersInTurnOrder(turn);
            Map<Integer, Long> sources = new HashMap<>();
            Map<Integer, String> keyNames = new HashMap<>();
            for (SpellAbility sa : fresh) {
                sources.put(sa.getId(), triggerSource(sa));
                AbilityKey k = keys.abilityKey(sa);
                keyNames.put(sa.getId(), k == null ? "" : k.toString());
            }
            fresh.sort(java.util.Comparator.comparingInt((SpellAbility sa) -> apnap.indexOf(controllerOf(sa)))
                .thenComparingLong(sa -> sources.get(sa.getId()))
                .thenComparing(sa -> keyNames.get(sa.getId())));
            for (SpellAbility sa : fresh) {
                announceTrigger(sa, sources.get(sa.getId()));
            }
        }
        for (Map.Entry<Long, SpellAbility> e : new ArrayList<>(waitingTriggers.entrySet())) {
            SpellAbility sa = e.getValue();
            if (!waitingIds.contains(sa.getId()) && !onStack(sa)) {
                waitingTriggers.remove(e.getKey());
                emitAll(false, b -> b.setTriggerRemoved(TriggeredAbilityRemoved.newBuilder().setAbilityId(e.getKey())));
            }
        }
    }

    /** The source as it was when the ability triggered: before a move that made it trigger (dies, leaves). */
    private long triggerSource(SpellAbility sa) {
        Card host = sa.getHostCard();
        long id = ids.idOf(host);
        return movedInLastSync.contains(host.getId()) && ids.prevIdOf(id) != 0 ? ids.prevIdOf(id) : id;
    }

    private void announceTrigger(SpellAbility sa, long sourceId) {
        announceTrigger(sa, sourceId, false);
    }

    private void announceTrigger(SpellAbility sa, long sourceId, boolean now) {
        long id = ids.trigger(sa);
        waitingTriggers.put(id, sa);
        triggerSources.put(id, sourceId);
        long effectId = sa.isTrigger() ? facts.effectIdOf(sa.getTrigger()) : 0;
        AbilityTriggered.Builder t = AbilityTriggered.newBuilder().setAbilityId(id).setSourceId(sourceId).setEffectId(effectId)
            .setControllerSeat(seat(controllerOf(sa)));
        AbilityKey key = keys.abilityKey(sa);
        if (key != null) {
            t.setAbility(key);
        }
        if (now) {
            emitNow(b -> b.setAbilityTriggered(t));
        } else {
            emitAll(false, b -> b.setAbilityTriggered(t));
        }
    }

    /** A controller takes these entries out of the waiting list to put them on the stack (or drop them). */
    void beginStacking(List<SpellAbility> entries) {
        entries.forEach(sa -> beingStacked.put(sa.getId(), sa));
        beginAction(); // announces entries that triggered without a look in between
    }

    void endStacking(List<SpellAbility> entries) {
        entries.forEach(sa -> beingStacked.remove(sa.getId()));
        endAction(); // entries not put on the stack are reported removed
    }

    private RevealReason revealReason(Card c, Player viewer, long prevId) {
        ZoneType now = c.getZone().getZoneType();
        if (now == ZoneType.Hand && c.getOwner() == viewer) {
            return RevealReason.REVEAL_REASON_OWN_DRAW;
        }
        if (now == ZoneType.Stack || now == ZoneType.Battlefield) {
            return RevealReason.REVEAL_REASON_PLAYED_FROM_HIDDEN;
        }
        if (now == ZoneType.Library || now == ZoneType.Hand) {
            return RevealReason.REVEAL_REASON_LOOKED_AT;
        }
        return RevealReason.REVEAL_REASON_LEFT_LIBRARY;
    }

    private List<Integer> seatsSeeing(Card c) {
        List<Integer> out = new ArrayList<>();
        for (Player p : game.getRegisteredPlayers()) {
            if (visible(c, p)) {
                out.add(seat(p));
            }
        }
        return out;
    }

    /** Turn-start snapshot for every agent seat (design: 턴 시작 스냅샷). */
    private void snapshot() {
        syncZones();
        snapshotTurn = game.getPhaseHandler().getTurn();
        for (int viewer : agentSeats) {
            TurnStartSnapshot s = TurnStartSnapshot.newBuilder().setGameId(gameId).setSeat(viewer)
                .setSeq(seq.get(viewer)).setObservation(observations.build(viewer)).build();
            send(AdapterMessage.newBuilder().setSnapshot(s).build());
            log.write(LogRecord.newBuilder().setSnapshot(s).build());
            // hidden state at the turn start too: the shadow game is rebuilt from the snapshot (phase 2)
            recordPrivileged(0, viewer);
        }
    }

    @Subscribe
    public void onEvent(GameEvent event) {
        try {
            translate(event);
        } catch (RuntimeException e) {
            stats.translationErrors++;
            throw e;
        }
    }

    private void translate(GameEvent event) {
        if (event instanceof GameEventGameStarted e) {
            firstPlayer = game.getPlayer(e.firstTurn()); // the play/draw choice is made: the match context shows it
            snapshot();
            return;
        }
        if (event instanceof GameEventTurnBegan) {
            snapshot();
            return;
        }
        if (snapshotTurn < 0) {
            return; // nothing is streamed before the first snapshot
        }
        boolean action = event instanceof GameEventSpellAbilityCast || event instanceof GameEventLandPlayed
            || event instanceof GameEventAttackersDeclared || event instanceof GameEventBlockersDeclared;
        if (action) {
            actionDepth++; // IDs are issued now; the changes that came with the action follow its event
        }
        syncZones();
        if (event instanceof GameEventSpellAbilityCast e) {
            castOrActivate(e);
        } else if (event instanceof GameEventLandPlayed e) {
            Card land = game.findByView(e.land());
            long id = ids.idOf(land);
            Player p = land.getController();
            emitAction(b -> b.setPlayLand(PlayLand.newBuilder().setSeat(seat(p)).setCardId(ids.prevIdOf(id))
                .setLand(keys.faceKey(land, land.getCurrentState()))));
        } else if (event instanceof GameEventAttackersDeclared e) {
            Combat combat = game.getPhaseHandler().getCombat();
            if (combat != null) {
                DeclareAttackers.Builder d = DeclareAttackers.newBuilder().setSeat(seat(game.getPhaseHandler().getPlayerTurn()));
                for (Card a : combat.getAttackers()) {
                    d.addAttacks(Attack.newBuilder().setAttackerId(ids.idOf(a)).setDefender(ref(combat.getDefenderByAttacker(a))));
                }
                emitAction(b -> b.setDeclareAttackers(d));
            }
        } else if (event instanceof GameEventBlockersDeclared e) {
            Combat combat = game.getPhaseHandler().getCombat();
            Player defender = game.getPlayer(e.defendingPlayer());
            if (combat != null) {
                DeclareBlockers.Builder d = DeclareBlockers.newBuilder().setSeat(seat(defender));
                for (Card a : combat.getAttackers()) {
                    for (Card bl : combat.getBlockers(a)) {
                        if (bl.getController() == defender) {
                            d.addBlocks(Block.newBuilder().setBlockerId(ids.idOf(bl)).setAttackerId(ids.idOf(a)));
                        }
                    }
                }
                emitAction(b -> b.setDeclareBlockers(d));
            }
        } else if (event instanceof GameEventCardDamaged e) {
            Card target = game.findByView(e.card());
            Card source = e.source() == null ? null : game.findByView(e.source());
            emitAll(false, b -> b.setDamage(DamageDealt.newBuilder().setSourceId(ids.idOf(source))
                .setTarget(ref(target)).setAmount(e.amount())
                .setCombat(e.type() == GameEventCardDamaged.DamageType.Normal && game.getPhaseHandler().inCombat())));
        } else if (event instanceof GameEventPlayerDamaged e) {
            Player target = game.getPlayer(e.target());
            Card source = e.source() == null ? null : game.findByView(e.source());
            emitAll(false, b -> b.setDamage(DamageDealt.newBuilder().setSourceId(ids.idOf(source))
                .setTarget(ref(target)).setAmount(e.amount()).setCombat(e.combat())));
        } else if (event instanceof GameEventPlayerLivesChanged e) {
            Player p = game.getPlayer(e.player());
            emitAll(false, b -> b.setLife(LifeChanged.newBuilder().setSeat(seat(p))
                .setDelta(e.newLives() - e.oldLives()).setLife(e.newLives())));
        } else if (event instanceof GameEventCardCounters e) {
            Card c = game.findByView(e.card());
            emitAll(false, b -> b.setCounters(CountersChanged.newBuilder().setTarget(ref(c)).addAllCounters(counters(c))));
        } else if (event instanceof GameEventPlayerCounters e) {
            playerCounters(game.getPlayer(e.receiver()));
        } else if (event instanceof GameEventPlayerPoisoned e) {
            playerCounters(game.getPlayer(e.receiver()));
        } else if (event instanceof GameEventCardTapped e) {
            Card c = game.findByView(e.card());
            reportedTapped.put(ids.idOf(c), e.tapped());
            emitAll(false, b -> b.setTap(TapChanged.newBuilder().setId(ids.idOf(c)).setTapped(e.tapped())));
        } else if (event instanceof forge.game.event.GameEventZone e && e.zoneType() == ZoneType.Battlefield
                && e.mode() == EventValueChangeType.ComplexUpdate) {
            tapsWithoutEvent();
        } else if (event instanceof GameEventManaPool e) {
            if (e.mode() == EventValueChangeType.Cleared) {
                poolsCleared = true;
                syncFacts();
            }
        } else if (event instanceof GameEventScry e) {
            libraryArranged(game.getPlayer(e.player()), e.toTop(), e.toBottom(), 0);
        } else if (event instanceof GameEventSurveil e) {
            libraryArranged(game.getPlayer(e.player()), e.toLibrary(), 0, e.toGraveyard());
        } else if (event instanceof GameEventShuffle e) {
            Player p = game.getPlayer(e.player());
            facts.shuffled(p);
            emitAll(false, b -> b.setLibraryShuffled(LibraryShuffled.newBuilder().setSeat(seat(p))));
            recordPrivileged(0);
        } else if (event instanceof GameEventDayTimeChanged e) {
            emitAll(false, b -> b.setDayNight(DayNightChanged.newBuilder()
                .setDayNight(e.daytime() ? DayNight.DAY_NIGHT_DAY : DayNight.DAY_NIGHT_NIGHT)));
        }
        if (action) {
            endAction();
        }
    }

    private List<Counter> counters(GameEntity e) {
        List<Counter> out = new ArrayList<>();
        for (CounterType t : e.getCounters().elementSet()) {
            out.add(Counter.newBuilder().setKind(ObservationBuilder.counterName(t)).setCount(e.getCounters().count(t)).build());
        }
        return out;
    }

    private void playerCounters(Player p) {
        emitAll(false, b -> b.setCounters(CountersChanged.newBuilder().setTarget(ref(p)).addAllCounters(counters(p))));
    }

    /**
     * The optional costs (kicker, buyback, entwine, conspire) a Forge AI seat cast its spell with, as a public choice
     * after the cast. Forge AI does not choose them when it casts: it weighs each combination as an ability of its own
     * and plays one, so no chooseOptionalCosts is asked. Values name the costs (Forge's OptionalCost); none chosen is
     * an empty choice. A cast on the human play path (an agent's, a replayed one) records the choice it is asked, and
     * nothing more is recorded for it.
     */
    private void optionalCosts(Player p, SpellAbility sa) {
        boolean asked = optionalCostsAsked;
        optionalCostsAsked = false;
        if (asked) {
            return;
        }
        Card host = sa.getHostCard();
        boolean chosen = sa.getOptionalCosts().iterator().hasNext();
        boolean offered = chosen || host.hasKeyword(forge.game.keyword.Keyword.KICKER)
            || host.hasKeyword(forge.game.keyword.Keyword.BUYBACK) || host.hasKeyword(forge.game.keyword.Keyword.ENTWINE)
            || host.hasKeyword(forge.game.keyword.Keyword.CONSPIRE);
        if (!offered) {
            return;
        }
        List<ChoiceValue> values = new ArrayList<>();
        for (forge.game.spellability.OptionalCost c : sa.getOptionalCosts()) {
            values.add(ChoiceValue.newBuilder().setText(c.name()).build());
        }
        choice(p, DecisionType.DECISION_TYPE_CHOOSE_OPTION, "chooseOptionalCosts", null, null, values);
        optionalCostsAsked = false; // recorded here for this cast
    }

    /** A chooseOptionalCosts choice was recorded since the last cast (the cast it belongs to records nothing more). */
    private boolean optionalCostsAsked;

    private void castOrActivate(GameEventSpellAbilityCast e) {
        SpellAbilityStackInstance si = null;
        for (SpellAbilityStackInstance s : game.getStack()) {
            if (s.getId() == e.si().getId()) {
                si = s;
            }
        }
        if (si == null) {
            return; // already resolved / countered before the event was handled
        }
        SpellAbility sa = si.getSpellAbility();
        Card host = sa.getHostCard();
        int seat = seat(si.getActivatingPlayer());
        long hostId = ids.idOf(host);
        // the source as it was when activated: before a cost (sacrifice) moved it
        long sourceId = sa.isSpell() ? hostId
            : idsAtActionStart.containsKey(host.getId()) ? idsAtActionStart.get(host.getId())
            : movedInLastSync.contains(host.getId()) ? ids.prevIdOf(hostId) : hostId;
        ids.setSource(si, sa.isTrigger() ? triggerSources.getOrDefault(ids.triggerId(sa), sourceId) : sourceId);
        List<ObjectRef> targets = targets(sa);
        if (sa.isCopied()) {
            // copies are created, not cast: a spell copy was reported as ObjectCreated when it appeared on the stack
            if (!sa.isSpell()) {
                long copyId = ids.abilityId(si);
                emitAll(false, b -> b.setObjectCreated(ObjectCreated.newBuilder().setId(copyId)
                    .setKind(ObjectCreatedKind.OBJECT_CREATED_KIND_ABILITY_COPY).setZone(magicdosa.protocol.v1.Zone.ZONE_STACK)
                    .setOwnerSeat(seat).setControllerSeat(seat).setSourceId(sourceId)));
            }
            return;
        }
        List<Integer> chosenModes = modes(sa);
        if (!chosenModes.isEmpty()) {
            stackModes.put(si, chosenModes);
        }
        if (sa.isSpell()) {
            CastSpell.Builder c = CastSpell.newBuilder().setSeat(seat).setCardId(ids.prevIdOf(hostId)).setSpellId(hostId)
                .setSpell(keys.spellKey(sa)).addAllTargets(targets).addAllPayment(payment(sa)).addAllModes(modes(sa))
                .addAllCostIds(costIds(sa));
            if (sa.getXManaCostPaid() != null) {
                c.setX(sa.getXManaCostPaid());
            }
            emitAction(b -> b.setCastSpell(c));
            optionalCosts(si.getActivatingPlayer(), sa);
            return;
        }
        if (sa.isTrigger()) {
            if (!ids.hasTriggerId(sa)) {
                // not seen while waiting (put on the stack without passing the simultaneous-entry list)
                stats.note("trigger ID issued at stacking: " + host.getName());
                announceTrigger(sa, sourceId, true);
            }
            long abilityId = ids.abilityId(si);
            waitingTriggers.remove(abilityId);
            TriggeredAbilityStacked.Builder t = TriggeredAbilityStacked.newBuilder().setSeat(seat).setAbilityId(abilityId)
                .addAllTargets(targets).addAllPayment(payment(sa)).addAllModes(modes(sa));
            if (sa.getXManaCostPaid() != null) {
                t.setX(sa.getXManaCostPaid());
            }
            emitAction(b -> b.setTriggerStacked(t));
            return;
        }
        long abilityId = ids.abilityId(si);
        AbilityKey key = keys.abilityKey(sa);
        ActivateAbility.Builder a = ActivateAbility.newBuilder().setSeat(seat).setSourceId(sourceId).setAbilityId(abilityId)
            .addAllTargets(targets).addAllPayment(payment(sa)).addAllModes(modes(sa)).addAllCostIds(costIds(sa));
        if (key != null) {
            a.setAbility(key);
        }
        if (sa.getXManaCostPaid() != null) {
            a.setX(sa.getXManaCostPaid());
        }
        emitAction(b -> b.setActivateAbility(a));
    }

    /**
     * The objects a spell's or ability's costs used (sacrificed, discarded, exiled, tapped, returned), by the ID they
     * had when the action began, in Forge's order of cost kinds.
     */
    private List<Long> costIds(SpellAbility sa) {
        List<Long> out = new ArrayList<>();
        for (var cell : sa.getPaidHash().cellSet()) {
            if (cell.getValue() == null || cell.getColumnKey() == null || !cell.getColumnKey()) {
                continue; // only cards paid for the cost itself (intrinsic), not remembered by an effect
            }
            for (Card c : cell.getValue()) {
                long id = idsAtActionStart.getOrDefault(c.getId(), ids.idOf(c));
                if (id != 0 && !out.contains(id)) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    private List<ManaPayment> payment(SpellAbility sa) {
        List<ManaPayment> out = new ArrayList<>();
        java.util.Set<Card> madeTold = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Mana m : sa.getPayingMana()) {
            // the source's ID when the action began: a source sacrificed for its mana (an Eldrazi Spawn) has left
            Card src = m.getSourceCard();
            long srcId = src == null ? 0 : idsAtActionStart.getOrDefault(src.getId(), ids.idOf(src));
            ManaAbilityUse.Builder u = ManaAbilityUse.newBuilder().setSourceId(srcId)
                .addProduced(ManaUnit.newBuilder().setColor(ObservationBuilder.colorLetter(m.getColor())).setAmount(1));
            if (m.getManaAbility() != null && m.getManaAbility().getSourceSA() != null) {
                AbilityKey k = keys.abilityKey(m.getManaAbility().getSourceSA());
                if (k != null) {
                    u.setAbility(k);
                }
            }
            // a source that made more than the payment spent of it (a filter's second unit, floating): all it made,
            // on its first piece; the units it made that are still in the pool are the rest
            if (src != null && madeTold.add(src)) {
                List<ManaUnit> made = new ArrayList<>();
                for (Mana spent : sa.getPayingMana()) {
                    if (spent.getSourceCard() == src) {
                        made.add(ManaUnit.newBuilder().setColor(ObservationBuilder.colorLetter(spent.getColor())).setAmount(1).build());
                    }
                }
                int spentCount = made.size();
                for (Mana floating : src.getController().getManaPool()) {
                    if (floating.getSourceCard() == src) {
                        made.add(ManaUnit.newBuilder().setColor(ObservationBuilder.colorLetter(floating.getColor())).setAmount(1).build());
                    }
                }
                if (made.size() > spentCount) {
                    u.addAllMade(made);
                }
            }
            out.add(ManaPayment.newBuilder().setAbility(u).build());
        }
        return out;
    }

    // ---- actions reported by the controllers ----

    void action(Player p, boolean engineHandled, Consumer<Event.Builder> body) {
        syncZones();
        emitAll(engineHandled, body);
    }

    /**
     * A public choice of {@code p} (protocol 9): one {@link ResolutionChoice} on every stream, the chooser's included,
     * with only what a human opponent could see or hear. Made where the choice is applied, before what it causes.
     */
    void choice(Player p, DecisionType type, String engineDecision, Collection<? extends GameObject> chosen,
                List<Integer> options, List<ChoiceValue> values) {
        syncZones();
        ResolutionChoice.Builder r = ResolutionChoice.newBuilder().setSeat(seat(p)).setType(type)
            .setEnginePrompt(engineDecision);
        r.setResolvingId(resolvingId());
        if (chosen != null) {
            for (GameObject o : chosen) {
                if (o != null) {
                    r.addChosen(ref(o));
                }
            }
        }
        if (options != null) {
            r.addAllOptions(options);
        }
        if (values != null) {
            r.addAllValues(values);
        }
        if (engineDecision.equals("chooseOptionalCosts")) {
            optionalCostsAsked = true;
        }
        emitAll(false, b -> b.setResolutionChoice(r));
    }

    /** Entity ID of the stack item resolving now, 0 when nothing resolves (no ID is issued here). */
    long resolvingId() {
        var stack = game.getStack();
        if (!stack.isResolving() || stack.isEmpty()) {
            return 0;
        }
        SpellAbilityStackInstance si = stack.peek();
        SpellAbility sa = si.getSpellAbility();
        if (sa.isSpell()) {
            return ids.idOf(sa.getHostCard());
        }
        return ids.hasAbilityId(si) ? ids.abilityId(si) : 0;
    }

    /** A record of an opponent's choice, or of an agent seat's intended delegation, for the log (no event). */
    void delegated(Player p, String engineDecision, DecisionType type, Collection<? extends GameObject> chosen) {
        delegated(p, isAgent(p), engineDecision, type, chosen);
    }

    /** {@code asAgent} false: an opponent's choice, also when an opponent controls an agent seat (deck item 4). */
    void delegated(Player p, boolean asAgent, String engineDecision, DecisionType type, Collection<? extends GameObject> chosen) {
        if (asAgent) {
            stats.delegated(engineDecision, type);
            log.write(LogRecord.newBuilder().setDecision(DecisionRecord.newBuilder()
                .setDecider(Decider.DECIDER_INTENDED_DELEGATION).setEngineDecision(engineDecision)).build());
        } else {
            log.write(LogRecord.newBuilder().setOpponentDecision(OpponentDecision.newBuilder()
                .setSeat(seat(p)).setEngineDecision(engineDecision)).build());
        }
    }

    /**
     * A decision method no classification covers yet (Forge AI answered). Counted for agent seats so the gap shows
     * (2026-11-06: no unrecorded decision may remain); logged like an intended delegation.
     */
    void unclassified(Player p, String method) {
        if (!isAgent(p)) {
            return;
        }
        stats.unclassified(method);
        log.write(LogRecord.newBuilder().setDecision(DecisionRecord.newBuilder()
            .setDecider(Decider.DECIDER_INTENDED_DELEGATION).setEngineDecision("unclassified:" + method)).build());
    }

    // ---- decisions ----

    /** Asks agent-core. The returned response, when present, satisfies the constraints. */
    Asked ask(Player p, DecisionType type, List<Candidate> candidates, SelectionConstraints constraints,
              DecisionContext context) {
        return ask(p, p, type, candidates, constraints, context, List.of());
    }

    Asked ask(Player p, DecisionType type, List<Candidate> candidates, SelectionConstraints constraints,
              DecisionContext context, List<Rejection> rejections) {
        return ask(p, p, type, candidates, constraints, context, rejections);
    }

    /** Asks again after the engine refused earlier answers to the same decision (Q15). */
    /** {@code decider} decides for {@code acting}: itself, or a player it controls (deck item 4). */
    Asked ask(Player decider, Player acting, DecisionType type, List<Candidate> candidates, SelectionConstraints constraints,
              DecisionContext context, List<Rejection> rejections) {
        return ask(decider, acting, type, candidates, constraints, context, rejections, null);
    }

    /** {@code bundle}: this request is the next step of a step bundle (Q18), or null. */
    Asked ask(Player decider, Player acting, DecisionType type, List<Candidate> candidates, SelectionConstraints constraints,
              DecisionContext context, List<Rejection> rejections, Bundle bundle) {
        if (forgeAiDepth > 0) {
            // a decision Forge AI asks itself while it computes a delegated answer (PlayerControllerAi calls its own
            // controller methods): part of that answer, Forge AI's too; no request, nothing recorded
            return new Asked(DecisionRequest.newBuilder().setGameId(gameId).setType(type).addAllCandidates(candidates)
                .setConstraints(constraints).build(), null, null, true);
        }
        Player p = decider;
        syncZones();
        if (snapshotTurn < 0) {
            snapshot();
        }
        int seat = seat(p);
        long decisionId = ++decisions;
        DecisionRequest.Builder requestBuilder = DecisionRequest.newBuilder()
            .setProtocolVersion(Versions.PROTOCOL).setGameId(gameId).setDecisionId(decisionId).setSeat(seat)
            .setAppliedSeq(seq.get(seat)).setSeed(seeds.nextLong()).setType(type)
            .setObservation(observations.build(seat)).addAllCandidates(candidates).setConstraints(constraints)
            .setContext(context == null ? DecisionContext.getDefaultInstance() : context)
            .addAllRejections(rejections);
        if (acting != decider) {
            requestBuilder.setActingSeat(seat(acting));
        }
        if (bundle != null) {
            if (bundle.steps == 0) {
                bundle.id = decisionId;
            }
            requestBuilder.setBundle(StepBundle.newBuilder().setId(bundle.id).setStep(++bundle.steps));
        }
        DecisionRequest request = requestBuilder.build();
        recordPrivileged(decisionId, seat);
        if (type == DecisionType.DECISION_TYPE_PRIORITY) {
            stats.priorityAsks++;
            if (candidates.stream().anyMatch(c -> !c.hasPass() && !(c.hasActivateAbility() && c.getActivateAbility().getManaAbility()))) {
                stats.priorityAsksWithAction++;
            }
        }
        DecisionResponse response;
        long waitStart = System.nanoTime(); // measurement only (throughput report), never part of a decision
        try {
            response = client.decide(request);
            stats.decideNanos += System.nanoTime() - waitStart;
        } catch (Exception e) {
            return new Asked(request, null, FallbackReason.FALLBACK_REASON_COMMUNICATION_ERROR);
        }
        if (client instanceof DelegatingClient d && d.delegateLast()) {
            return new Asked(request, null, null, true);
        }
        if (!valid(request, response)) {
            return new Asked(request, response, FallbackReason.FALLBACK_REASON_ILLEGAL_ACTION);
        }
        return new Asked(request, response, null);
    }

    private static boolean valid(DecisionRequest req, DecisionResponse resp) {
        if (resp == null || resp.getDecisionId() != req.getDecisionId()) {
            return false;
        }
        Set<Integer> seen = new HashSet<>();
        for (int i : resp.getChosenList()) {
            if (i < 0 || i >= req.getCandidatesCount() || !seen.add(i)) {
                return false;
            }
        }
        int n = resp.getChosenCount();
        SelectionConstraints c = req.getConstraints();
        // a payment step may stop (min 0: no answer is "done"); a single pick from the pool must name one
        boolean mayStop = req.getType() == DecisionType.DECISION_TYPE_MANA_PAYMENT && c.getMin() == 0;
        if (!mayStop && (n < c.getMin() || n > c.getMax())) {
            return false;
        }
        for (DecisionStep s : resp.getStepsList()) {
            if (s.getDecider() == Decider.DECIDER_UNINTENDED_FALLBACK) {
                return false;
            }
        }
        return true;
    }

    /** Logs what happened for a decision that was asked. */
    /** Logs an answer the engine refused (Q15); the decision is asked again. Not a fallback. */
    void recordRejected(Asked asked, String engineDecision, String reason, DecisionStats.RejectionKind kind) {
        stats.rejected(engineDecision, asked.request().getType(), kind);
        log.write(LogRecord.newBuilder().setDecision(DecisionRecord.newBuilder().setRequest(asked.request())
            .setResponse(asked.response()).setDecider(Decider.DECIDER_MODEL).setEngineDecision(engineDecision)
            .setRejection(kind + ": " + reason)).build());
    }

    /** The seat chose an action past the repeat cap: it passes instead, recorded as a safety cap (not a fallback). */
    void recordSafetyCap(Asked asked, String engineDecision, Candidate chosen) {
        safetyCap(engineDecision + ": " + chosen.getActionCase());
        log.write(LogRecord.newBuilder().setDecision(DecisionRecord.newBuilder().setRequest(asked.request())
            .setResponse(asked.response()).setDecider(Decider.DECIDER_MODEL).addApplied(0)
            .setEngineDecision("safety_cap:" + engineDecision)).build());
    }

    void safetyCap(String what) {
        stats.safetyCaps++;
        stats.note("safety cap: " + what);
    }

    /**
     * A delegated decision (see {@link DelegatingClient}): {@code forgeAi} gives Forge AI's answer as candidate indices
     * of the request. The result is an answer like agent-core's, its step marked as an intended delegation, so the
     * caller goes on as usual (and logs it as a delegation); an answer that maps to no candidate (or breaks the
     * constraints) becomes an unintended fallback, counted by decision type. Not delegated: {@code asked} unchanged.
     */
    Asked resolveDelegation(Asked asked, java.util.function.Supplier<List<Integer>> forgeAi, String engineDecision) {
        if (!asked.delegate()) {
            return asked;
        }
        DecisionRequest req = asked.request();
        boolean nested = forgeAiDepth > 0;
        if (!nested) {
            delegationDetail = null;
        }
        List<Integer> chosen = null;
        if (forgeAi != null) {
            forgeAiDepth++;
            try {
                chosen = forgeAi.get();
            } catch (RuntimeException e) {
                stats.note("Forge AI's answer failed (" + engineDecision + "): " + e);
            } finally {
                forgeAiDepth--;
            }
        }
        DecisionResponse resp = chosen == null ? null : DecisionResponse.newBuilder().setGameId(req.getGameId())
            .setDecisionId(req.getDecisionId()).addAllChosen(chosen)
            .addSteps(DecisionStep.newBuilder().setDecider(Decider.DECIDER_INTENDED_DELEGATION)).build();
        if (nested) {
            return resp == null || !valid(req, resp) ? new Asked(req, null, FallbackReason.FALLBACK_REASON_ILLEGAL_ACTION)
                : new Asked(req, resp, null);
        }
        DelegatingClient d = (DelegatingClient) client;
        if (resp == null || !valid(req, resp)) {
            stats.note("Forge AI's answer matched no candidate: " + req.getType() + " " + engineDecision);
            d.unmapped(req, delegationDetail == null ? engineDecision : engineDecision + ": " + delegationDetail);
            return new Asked(req, null, FallbackReason.FALLBACK_REASON_ILLEGAL_ACTION);
        }
        d.delegated(req, chosen, engineDecision);
        return new Asked(req, resp, null);
    }

    /** What Forge AI answered, for the report of an answer that maps to no candidate. */
    private String delegationDetail;

    void delegationDetail(String detail) {
        if (forgeAiDepth <= 1) {
            delegationDetail = detail;
        }
    }

    /** Depth of Forge AI computing a delegated answer (decisions asked inside it are part of it). */
    private int forgeAiDepth;

    void record(Asked asked, List<Integer> applied, String engineDecision, boolean fellBack, FallbackReason reason) {
        if (asked.request().getDecisionId() == 0) {
            return; // asked by Forge AI inside a delegated answer: not a decision of its own
        }
        DecisionRecord.Builder r = DecisionRecord.newBuilder().setRequest(asked.request()).setEngineDecision(engineDecision)
            .addAllApplied(applied);
        if (asked.response() != null) {
            r.setResponse(asked.response());
        }
        if (fellBack) {
            r.setDecider(Decider.DECIDER_UNINTENDED_FALLBACK).setFallbackReason(reason);
            stats.fallback(engineDecision + ": " + reason);
        } else if (asked.response() != null && asked.response().getStepsCount() > 0
                && asked.response().getSteps(0).getDecider() == Decider.DECIDER_INTENDED_DELEGATION) {
            // agent-core answered with the shadow game's Forge AI (design: 의도된 위임)
            r.setDecider(Decider.DECIDER_INTENDED_DELEGATION);
            stats.delegated(engineDecision + "(shadow Forge AI)", asked.request().getType());
        } else {
            r.setDecider(Decider.DECIDER_MODEL);
            stats.decided(asked.request().getType());
        }
        log.write(LogRecord.newBuilder().setDecision(r).build());
    }

    private void recordPrivileged(long decisionId) {
        for (int seat : agentSeats) {
            recordPrivileged(decisionId, seat);
        }
    }

    private void recordPrivileged(long decisionId, int seat) {
        PrivilegedRecord r = PrivilegedRecord.newBuilder().setSeq(seq.get(seat)).setSeat(seat).setDecisionId(decisionId)
            .setPrivileged(observations.privileged(seat)).build();
        log.write(LogRecord.newBuilder().setPrivileged(r).build());
        if (mode == Mode.MODE_TRAINING) {
            privileged.add(r);
        }
    }

    /** Why the game ended (Q13): the loser's loss reason, or a card that wins the game. */
    private GameEndReason endReason(GameOutcome outcome) {
        if (outcome.getWinCondition() == forge.game.GameEndReason.WinsGameSpellEffect) {
            return GameEndReason.GAME_END_REASON_CARD_EFFECT;
        }
        for (Player p : game.getRegisteredPlayers()) {
            if (p.getOutcome() == null || p.getOutcome().lossState == null) {
                continue;
            }
            GameLossReason r = p.getOutcome().lossState;
            switch (r) {
                case LifeReachedZero:
                    return GameEndReason.GAME_END_REASON_LIFE;
                case Poisoned:
                    return GameEndReason.GAME_END_REASON_POISON;
                case Milled:
                    return GameEndReason.GAME_END_REASON_DECKED;
                case Conceded:
                    return GameEndReason.GAME_END_REASON_CONCEDE;
                case SpellEffect:
                    return GameEndReason.GAME_END_REASON_CARD_EFFECT;
                default:
                    break;
            }
        }
        return GameEndReason.GAME_END_REASON_LIFE;
    }

    /** Sends the game end (with privileged data in training mode) and logs it. Call after the game is over. */
    public GameOver finish() {
        syncZones();
        GameOver over = outcome().toBuilder().addAllPrivileged(privileged).build();
        send(AdapterMessage.newBuilder().setGameOver(over).build());
        log.write(LogRecord.newBuilder().setGameOver(over).build());
        return over;
    }

    /** The game end as {@link #finish()} reports it (winner, reason, turns), without the privileged records; not sent. */
    public GameOver outcome() {
        GameOutcome outcome = game.getOutcome();
        GameOver.Builder g = GameOver.newBuilder().setGameId(gameId).setTurns(game.getPhaseHandler().getTurn());
        if (outcome != null && !outcome.isDraw() && outcome.getWinningPlayer() != null) {
            Player winner = null;
            for (Player p : game.getRegisteredPlayers()) {
                if (p.getLobbyPlayer() == outcome.getWinningLobbyPlayer()) {
                    winner = p;
                }
            }
            if (winner != null) {
                g.setWinnerSeat(seat(winner));
            }
            g.setReason(endReason(outcome));
        } else {
            g.setReason(GameEndReason.GAME_END_REASON_DRAW);
        }
        return g.build();
    }

}
