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
import forge.game.event.GameEventShuffle;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventTurnBegan;
import forge.game.mana.Mana;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
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
import magicdosa.protocol.v1.ResolutionChoice;
import magicdosa.protocol.v1.RevealReason;
import magicdosa.protocol.v1.SelectionConstraints;
import magicdosa.protocol.v1.TapChanged;
import magicdosa.protocol.v1.TurnStartSnapshot;
import magicdosa.protocol.v1.ZoneMove;
import magicdosa.protocol.v1.Candidate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
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
    record Asked(DecisionRequest request, DecisionResponse response, FallbackReason failure) {
        boolean ok() {
            return failure == null;
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
    private final GameLogWriter log;
    private final Mode mode;
    private final String gameId;
    private final SplittableRandom seeds;

    private final EntityIds ids = new EntityIds();
    private final ObservationBuilder observations = new ObservationBuilder(this);
    private final Map<Integer, Long> seq = new HashMap<>();
    private final Map<Integer, Set<Long>> visibleIds = new HashMap<>();
    private final Map<Integer, Integer> mulligans = new HashMap<>();
    private final List<PrivilegedRecord> privileged = new ArrayList<>();
    private final Set<Integer> movedInLastSync = new HashSet<>();
    private long decisions;
    private int snapshotTurn = -1;
    private final DecisionStats stats = new DecisionStats();

    public ForgeGameSession(Game game, String matchId, int gameNumber, List<Deck> registeredDecks, int[] gamesWon,
                            Set<Integer> agentSeats, DecisionClient client, CardKeys keys, GameLogWriter log, Mode mode,
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

    // ---- accessors ----

    Game game() {
        return game;
    }

    EntityIds ids() {
        return ids;
    }

    CardKeys keys() {
        return keys;
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

    int seat(Player p) {
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

    void tookMulligan(Player p) {
        mulligans.merge(seat(p), 1, Integer::sum);
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
    private void emit(boolean engineHandled, java.util.function.BiConsumer<Integer, Event.Builder> body) {
        for (int viewer : agentSeats) {
            Event.Builder b = Event.newBuilder();
            body.accept(viewer, b);
            if (b.getBodyCase() == Event.BodyCase.BODY_NOT_SET) {
                continue;
            }
            long s = seq.merge(viewer, 1L, Long::sum);
            b.setGameId(gameId).setViewerSeat(viewer).setSeq(s)
                .setTurn(game.getPhaseHandler().getTurn()).setStep(ObservationBuilder.step(game)).setEngineHandled(engineHandled);
            Event e = b.build();
            send(AdapterMessage.newBuilder().setEvent(e).build());
            log.write(LogRecord.newBuilder().setEvent(e).build());
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
            Card c = m.card();
            if (m.to() == null) {
                emit(false, (viewer, b) -> b.setZoneMove(ZoneMove.newBuilder().setPrevId(m.prevId())
                    .setFrom(ObservationBuilder.zone(m.from())).setTo(magicdosa.protocol.v1.Zone.ZONE_NONE)
                    .setOwnerSeat(seat(c.getOwner())).setControllerSeat(seat(c.getController()))));
                continue;
            }
            if (m.prevId() == 0 && m.to() != ZoneType.Library && game.getAge() != forge.game.GameStage.BeforeMulligan
                    && c.isToken()) {
                emit(false, (viewer, b) -> b.setObjectCreated(ObjectCreated.newBuilder().setId(m.newId())
                    .setKind(c.isCloned() ? ObjectCreatedKind.OBJECT_CREATED_KIND_COPY_TOKEN : ObjectCreatedKind.OBJECT_CREATED_KIND_TOKEN)
                    .setZone(ObservationBuilder.zone(m.to())).setOwnerSeat(seat(c.getOwner()))
                    .setControllerSeat(seat(c.getController())).setOracleId(keys.oracleId(c)).setFace(keys.face(c))));
                continue;
            }
            emit(false, (viewer, b) -> {
                boolean vis = visible(c, player(viewer));
                ZoneMove.Builder z = ZoneMove.newBuilder().setId(m.newId()).setPrevId(m.prevId())
                    .setFrom(m.prevId() == 0 ? magicdosa.protocol.v1.Zone.ZONE_NONE : ObservationBuilder.zone(m.from()))
                    .setTo(ObservationBuilder.zone(m.to())).setOwnerSeat(seat(c.getOwner()))
                    .setControllerSeat(seat(c.getController())).setFaceDown(c.isFaceDown());
                if (vis) {
                    z.setOracleId(keys.oracleId(c)).setFace(keys.face(c));
                    if (m.to() == ZoneType.Library) {
                        z.setPosition(c.getZone().getCards().indexOf(c));
                    }
                }
                b.setZoneMove(z);
            });
        }
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
                long prev = ids.prevIdOf(id);
                boolean wasHidden = prev == 0 ? c.getZone().is(ZoneType.Library) || c.getZone().is(ZoneType.Hand)
                    : !seen.contains(prev);
                if (!wasHidden) {
                    continue;
                }
                RevealReason reason = revealReason(c, v, prev);
                long seqNow = seq.merge(viewer, 1L, Long::sum);
                Event e = Event.newBuilder().setGameId(gameId).setViewerSeat(viewer).setSeq(seqNow)
                    .setTurn(game.getPhaseHandler().getTurn()).setStep(ObservationBuilder.step(game))
                    .setCardRevealed(CardRevealed.newBuilder().setId(id).setOracleId(keys.oracleId(c)).setFace(keys.face(c))
                        .setZone(ObservationBuilder.zone(c.getZone().getZoneType())).setReason(reason)
                        .addAllRevealedToSeats(seatsSeeing(c)))
                    .build();
                send(AdapterMessage.newBuilder().setEvent(e).build());
                log.write(LogRecord.newBuilder().setEvent(e).build());
            }
        }
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
        if (event instanceof GameEventGameStarted) {
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
        syncZones();
        if (event instanceof GameEventSpellAbilityCast e) {
            castOrActivate(e);
        } else if (event instanceof GameEventLandPlayed e) {
            Card land = game.findByView(e.land());
            long id = ids.idOf(land);
            Player p = land.getController();
            emitAll(false, b -> b.setPlayLand(PlayLand.newBuilder().setSeat(seat(p)).setCardId(ids.prevIdOf(id))
                .setLand(keys.faceKey(land, land.getCurrentState()))));
        } else if (event instanceof GameEventAttackersDeclared e) {
            Combat combat = game.getPhaseHandler().getCombat();
            if (combat != null) {
                DeclareAttackers.Builder d = DeclareAttackers.newBuilder().setSeat(seat(game.getPhaseHandler().getPlayerTurn()));
                for (Card a : combat.getAttackers()) {
                    d.addAttacks(Attack.newBuilder().setAttackerId(ids.idOf(a)).setDefender(ref(combat.getDefenderByAttacker(a))));
                }
                emitAll(false, b -> b.setDeclareAttackers(d));
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
                emitAll(false, b -> b.setDeclareBlockers(d));
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
            emitAll(false, b -> b.setTap(TapChanged.newBuilder().setId(ids.idOf(c)).setTapped(e.tapped())));
        } else if (event instanceof GameEventShuffle e) {
            Player p = game.getPlayer(e.player());
            emitAll(false, b -> b.setLibraryShuffled(LibraryShuffled.newBuilder().setSeat(seat(p))));
            recordPrivileged(0);
        } else if (event instanceof GameEventDayTimeChanged e) {
            emitAll(false, b -> b.setDayNight(DayNightChanged.newBuilder()
                .setDayNight(e.daytime() ? DayNight.DAY_NIGHT_DAY : DayNight.DAY_NIGHT_NIGHT)));
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
        long sourceId = movedInLastSync.contains(host.getId()) && !sa.isSpell() ? ids.prevIdOf(hostId) : hostId;
        ids.setSource(si, sourceId);
        List<ObjectRef> targets = targets(sa);
        if (sa.isSpell()) {
            CastSpell.Builder c = CastSpell.newBuilder().setSeat(seat).setCardId(ids.prevIdOf(hostId)).setSpellId(hostId)
                .setSpell(keys.spellKey(sa)).addAllTargets(targets).addAllPayment(payment(sa));
            if (sa.getXManaCostPaid() != null) {
                c.setX(sa.getXManaCostPaid());
            }
            emitAll(false, b -> b.setCastSpell(c));
            return;
        }
        long abilityId = ids.abilityId(si);
        AbilityKey key = keys.abilityKey(sa);
        if (sa.isTrigger()) {
            AbilityTriggered.Builder t = AbilityTriggered.newBuilder().setAbilityId(abilityId).setSourceId(sourceId)
                .setControllerSeat(seat);
            if (key != null) {
                t.setAbility(key);
            }
            emitAll(false, b -> b.setAbilityTriggered(t));
            return;
        }
        ActivateAbility.Builder a = ActivateAbility.newBuilder().setSeat(seat).setSourceId(sourceId).setAbilityId(abilityId)
            .addAllTargets(targets).addAllPayment(payment(sa));
        if (key != null) {
            a.setAbility(key);
        }
        if (sa.getXManaCostPaid() != null) {
            a.setX(sa.getXManaCostPaid());
        }
        emitAll(false, b -> b.setActivateAbility(a));
    }

    private List<ManaPayment> payment(SpellAbility sa) {
        List<ManaPayment> out = new ArrayList<>();
        for (Mana m : sa.getPayingMana()) {
            ManaAbilityUse.Builder u = ManaAbilityUse.newBuilder().setSourceId(ids.idOf(m.getSourceCard()))
                .addProduced(ManaUnit.newBuilder().setColor(ObservationBuilder.colorLetter(m.getColor())).setAmount(1));
            if (m.getManaAbility() != null && m.getManaAbility().getSourceSA() != null) {
                AbilityKey k = keys.abilityKey(m.getManaAbility().getSourceSA());
                if (k != null) {
                    u.setAbility(k);
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

    /** A resolution choice made for {@code p}: events for the streams and the delegation / opponent record. */
    void delegated(Player p, String engineDecision, DecisionType type, Collection<? extends GameObject> chosen) {
        syncZones();
        if (chosen != null && !chosen.isEmpty()) {
            ResolutionChoice.Builder r = ResolutionChoice.newBuilder().setSeat(seat(p)).setType(type)
                .setEnginePrompt(engineDecision);
            for (GameObject o : chosen) {
                r.addChosen(ref(o));
            }
            emitAll(false, b -> b.setResolutionChoice(r));
        }
        if (isAgent(p)) {
            stats.delegated(engineDecision);
            log.write(LogRecord.newBuilder().setDecision(DecisionRecord.newBuilder()
                .setDecider(Decider.DECIDER_INTENDED_DELEGATION).setEngineDecision(engineDecision)).build());
        } else {
            log.write(LogRecord.newBuilder().setOpponentDecision(OpponentDecision.newBuilder()
                .setSeat(seat(p)).setEngineDecision(engineDecision)).build());
        }
    }

    // ---- decisions ----

    /** Asks agent-core. The returned response, when present, satisfies the constraints. */
    Asked ask(Player p, DecisionType type, List<Candidate> candidates, SelectionConstraints constraints,
              DecisionContext context) {
        return ask(p, type, candidates, constraints, context, List.of());
    }

    /** Asks again after the engine refused earlier answers to the same decision (Q15). */
    Asked ask(Player p, DecisionType type, List<Candidate> candidates, SelectionConstraints constraints,
              DecisionContext context, List<Rejection> rejections) {
        syncZones();
        if (snapshotTurn < 0) {
            snapshot();
        }
        int seat = seat(p);
        long decisionId = ++decisions;
        DecisionRequest request = DecisionRequest.newBuilder()
            .setProtocolVersion(Versions.PROTOCOL).setGameId(gameId).setDecisionId(decisionId).setSeat(seat)
            .setAppliedSeq(seq.get(seat)).setSeed(seeds.nextLong()).setType(type)
            .setObservation(observations.build(seat)).addAllCandidates(candidates).setConstraints(constraints)
            .setContext(context == null ? DecisionContext.getDefaultInstance() : context)
            .addAllRejections(rejections)
            .build();
        recordPrivileged(decisionId, seat);
        DecisionResponse response;
        try {
            response = client.decide(request);
        } catch (Exception e) {
            return new Asked(request, null, FallbackReason.FALLBACK_REASON_COMMUNICATION_ERROR);
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
        if (req.getType() != DecisionType.DECISION_TYPE_MANA_PAYMENT && (n < c.getMin() || n > c.getMax())) {
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
    void recordRejected(Asked asked, String engineDecision, String reason) {
        stats.rejected(engineDecision);
        log.write(LogRecord.newBuilder().setDecision(DecisionRecord.newBuilder().setRequest(asked.request())
            .setResponse(asked.response()).setDecider(Decider.DECIDER_MODEL).setEngineDecision(engineDecision)
            .setRejection(reason)).build());
    }

    void record(Asked asked, List<Integer> applied, String engineDecision, boolean fellBack, FallbackReason reason) {
        DecisionRecord.Builder r = DecisionRecord.newBuilder().setRequest(asked.request()).setEngineDecision(engineDecision)
            .addAllApplied(applied);
        if (asked.response() != null) {
            r.setResponse(asked.response());
        }
        if (fellBack) {
            r.setDecider(Decider.DECIDER_UNINTENDED_FALLBACK).setFallbackReason(reason);
            stats.fallback(engineDecision + ": " + reason);
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

    /** Sends the game end (with privileged data in training mode) and logs it. Call after the game is over. */
    public GameOver finish() {
        syncZones();
        GameOutcome outcome = game.getOutcome();
        GameOver.Builder g = GameOver.newBuilder().setGameId(gameId).setTurns(game.getPhaseHandler().getTurn())
            .addAllPrivileged(privileged);
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
            g.setReason(GameEndReason.GAME_END_REASON_LIFE);
        } else {
            g.setReason(GameEndReason.GAME_END_REASON_DRAW);
        }
        GameOver over = g.build();
        send(AdapterMessage.newBuilder().setGameOver(over).build());
        log.write(LogRecord.newBuilder().setGameOver(over).build());
        return over;
    }

}
