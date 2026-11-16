package magicdosa.agent.shadow;

import forge.game.Game;
import forge.game.player.Player;
import magicdosa.adapter.forge.AgentPlayerController;
import magicdosa.adapter.forge.CardKeys;
import magicdosa.adapter.forge.DecisionClient;
import magicdosa.adapter.forge.ForgeGameSession;
import magicdosa.adapter.forge.ForgeRandom;
import magicdosa.adapter.forge.LogSink;
import magicdosa.adapter.forge.ObservedAiController;
import magicdosa.adapter.forge.SnapshotBuilder;
import magicdosa.agent.policy.RandomPolicy;
import magicdosa.protocol.v1.AdapterMessage;
import magicdosa.protocol.v1.CardCount;
import magicdosa.protocol.v1.DecisionRequest;
import magicdosa.protocol.v1.DecisionResponse;
import magicdosa.protocol.v1.DeckList;
import magicdosa.protocol.v1.GameGlobal;
import magicdosa.protocol.v1.GameObject;
import magicdosa.protocol.v1.Hello;
import magicdosa.protocol.v1.HelloReply;
import magicdosa.protocol.v1.HiddenIdentity;
import magicdosa.protocol.v1.LibraryOrder;
import magicdosa.protocol.v1.MatchContext;
import magicdosa.protocol.v1.MatchPlayer;
import magicdosa.protocol.v1.Mode;
import magicdosa.protocol.v1.Observation;
import magicdosa.protocol.v1.PlayerState;
import magicdosa.protocol.v1.Privileged;
import magicdosa.protocol.v1.Step;
import magicdosa.protocol.v1.TurnStartSnapshot;
import magicdosa.protocol.v1.Zone;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A shadow scenario: a mid-game state written by hand (each seat's 60, library top first, hand, battlefield,
 * graveyard), built into a real Forge game by the rebuild path ({@link SnapshotBuilder}), played by Forge from the
 * turn start with seat 0 answered by a live shadow in this JVM (verification 2: the shadow samples what seat 0 cannot
 * see) and seat 1 by Forge AI. The shadow starts from the real game's own turn-start snapshot, by the same rebuild as
 * recovery 2, and its result is what a regression test asserts.
 */
final class Scenario {
    private static CardKeys keys;

    /** Forge and the card keys, once per JVM (Forge reads res/ from forge-gui, the tests' working directory). */
    static synchronized CardKeys keys() {
        if (keys == null) {
            System.setProperty("java.awt.headless", "true");
            System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
            forge.gui.GuiBase.setInterface(new forge.GuiDesktop());
            forge.model.FModel.initialize(null, null);
            keys = CardKeys.load();
        }
        return keys;
    }

    static final class Seat {
        final Map<String, Integer> deck = new LinkedHashMap<>();
        final List<String> library = new ArrayList<>(); // top first
        final List<String> hand = new ArrayList<>();
        final List<String> battlefield = new ArrayList<>();
        final List<String> tapped = new ArrayList<>();
        final List<String> graveyard = new ArrayList<>();
        /** Counters on battlefield cards: card name -> (counter kind -> count), for every copy of that name. */
        final Map<String, Map<String, Integer>> counters = new LinkedHashMap<>();
        int life = 20;

        /** On the battlefield with {@code count} {@code kind} counters (e.g. "lore"). */
        Seat battlefieldWith(String name, String kind, int count) {
            battlefield.add(name);
            counters.computeIfAbsent(name, k -> new LinkedHashMap<>()).put(kind, count);
            return this;
        }

        Seat deck(String name, int count) {
            deck.merge(name, count, Integer::sum);
            return this;
        }

        Seat library(String... names) {
            library.addAll(List.of(names));
            return this;
        }

        Seat hand(String... names) {
            hand.addAll(List.of(names));
            return this;
        }

        Seat battlefield(String... names) {
            battlefield.addAll(List.of(names));
            return this;
        }

        /** On the battlefield, tapped. */
        Seat tapped(String... names) {
            tapped.addAll(List.of(names));
            return this;
        }

        Seat graveyard(String... names) {
            graveyard.addAll(List.of(names));
            return this;
        }
    }

    final String name;
    final int turn;
    final int active;
    final Seat[] seats = {new Seat(), new Seat()};

    /** {@code turn}: the turn that starts when the game is played ({@code active} has it). */
    Scenario(String name, int turn, int active) {
        this.name = name;
        this.turn = turn;
        this.active = active;
    }

    Seat seat(int s) {
        return seats[s];
    }

    private long nextId = 1;

    /** The turn-start state as seat 0 sees it and the hidden state (every library in order, the hidden hand). */
    private TurnStartSnapshot snapshot;
    private Privileged hidden;

    private void write() {
        CardKeys k = keys();
        Observation.Builder o = Observation.newBuilder().setViewerSeat(0);
        MatchContext.Builder match = MatchContext.newBuilder().setMatchId(name).setGameNumber(1).setStartingSeat(0);
        Privileged.Builder h = Privileged.newBuilder();
        for (int s = 0; s < seats.length; s++) {
            Seat seat = seats[s];
            MatchPlayer.Builder mp = MatchPlayer.newBuilder().setSeat(s).setDeckName(name + "-" + s);
            DeckList.Builder current = DeckList.newBuilder().setSeat(s);
            for (Map.Entry<String, Integer> e : seat.deck.entrySet()) {
                CardCount c = CardCount.newBuilder().setOracleId(oracle(k, e.getKey())).setCount(e.getValue()).build();
                mp.addMain(c);
                current.addCards(c);
            }
            match.addPlayers(mp);
            h.addCurrentDecks(current);
            o.addPlayers(PlayerState.newBuilder().setSeat(s).setLife(seat.life).setHandSize(seat.hand.size())
                .setLibrarySize(seat.library.size()));
            // the library: the cards listed on top, then the rest of the 60 (every card of the deck is in the game, as
            // in a real one: the shadow's sample takes hidden identities from them)
            Map<String, Integer> rest = new LinkedHashMap<>(seat.deck);
            for (List<String> zone : List.of(seat.library, seat.hand, seat.battlefield, seat.tapped, seat.graveyard)) {
                for (String c : zone) {
                    if (rest.merge(c, -1, Integer::sum) < 0) {
                        throw new IllegalArgumentException("seat " + s + ": more " + c + " than its deck has");
                    }
                }
            }
            List<String> library = new ArrayList<>(seat.library);
            rest.forEach((c, n) -> {
                for (int i = 0; i < n; i++) {
                    library.add(c);
                }
            });
            o.getPlayersBuilder(s).setLibrarySize(library.size());
            LibraryOrder.Builder lib = LibraryOrder.newBuilder().setSeat(s);
            for (String c : library) {
                long id = nextId++;
                lib.addIds(id);
                h.addIdentities(HiddenIdentity.newBuilder().setId(id).setOracleId(oracle(k, c)).setFace(1));
            }
            h.addLibraries(lib);
            for (String c : seat.hand) {
                long id = nextId++;
                GameObject.Builder g = object(id, Zone.ZONE_HAND, s);
                if (s == 0) {
                    g.setOracleId(oracle(k, c)).setFace(1);
                } else {
                    h.addIdentities(HiddenIdentity.newBuilder().setId(id).setOracleId(oracle(k, c)).setFace(1));
                }
                o.addObjects(g);
            }
            for (String c : seat.battlefield) {
                GameObject.Builder g = object(nextId++, Zone.ZONE_BATTLEFIELD, s).setOracleId(oracle(k, c)).setFace(1);
                seat.counters.getOrDefault(c, Map.of()).forEach((kind, n) ->
                    g.addCounters(magicdosa.protocol.v1.Counter.newBuilder().setKind(kind).setCount(n)));
                o.addObjects(g);
            }
            for (String c : seat.tapped) {
                o.addObjects(object(nextId++, Zone.ZONE_BATTLEFIELD, s).setOracleId(oracle(k, c)).setFace(1).setTapped(true));
            }
            for (String c : seat.graveyard) {
                o.addObjects(object(nextId++, Zone.ZONE_GRAVEYARD, s).setOracleId(oracle(k, c)).setFace(1));
            }
        }
        o.setMatch(match);
        o.setGame(GameGlobal.newBuilder().setTurn(turn).setStep(Step.STEP_UNTAP).setActiveSeat(active));
        snapshot = TurnStartSnapshot.newBuilder().setGameId(name + "-g1").setSeat(0).setSeq(1).setObservation(o).build();
        hidden = h.build();
    }

    private static GameObject.Builder object(long id, Zone zone, int seat) {
        return GameObject.newBuilder().setId(id).setZone(zone).setOwnerSeat(seat).setControllerSeat(seat);
    }

    private static String oracle(CardKeys k, String name) {
        String o = k.oracleIdByName(name);
        if (o == null) {
            throw new IllegalArgumentException("no card " + name);
        }
        return o;
    }

    /** The scenario's game as the rebuild path builds it (no session, nothing played), with the builder's gaps. */
    SnapshotBuilder.Result build() throws Exception {
        CardKeys k = keys();
        write();
        SnapshotBuilder.Result[] out = {null};
        Throwable[] failure = {null};
        Thread t = new Thread(() -> {
            try {
                out[0] = new SnapshotBuilder(k).build(snapshot, hidden, nextId, id -> id);
            } catch (Throwable e) {
                failure[0] = e;
            }
        }, "Game-scenario-build-" + name);
        t.start();
        t.join();
        if (failure[0] != null) {
            throw new IllegalStateException("scenario " + name + " not built", failure[0]);
        }
        return out[0];
    }

    /** Seat 0's requests go to the live shadow, the game's stream as well (as agent-core gets them). */
    /** The real game's stream as seat 0 got it (a test checks the scenario still does what it is for). */
    final List<magicdosa.protocol.v1.Event> events = java.util.Collections.synchronizedList(new ArrayList<>());

    private final class ShadowClient implements DecisionClient {
        final LiveShadow shadow;

        ShadowClient(LiveShadow shadow) {
            this.shadow = shadow;
        }

        @Override
        public HelloReply hello(Hello hello) {
            return HelloReply.newBuilder().setAccepted(true).build();
        }

        @Override
        public void send(AdapterMessage m) throws Exception {
            if (m.hasEvent()) {
                events.add(m.getEvent());
                shadow.event(m.getEvent());
            } else if (m.hasSnapshot()) {
                shadow.snapshot(m.getSnapshot());
            } else if (m.hasGameOver()) {
                shadow.gameOver(m.getGameOver());
            }
        }

        @Override
        public DecisionResponse decide(DecisionRequest r) throws Exception {
            return shadow.decide(r);
        }
    }

    /**
     * Plays the scenario through {@code turns} turns (or to the game end) and returns the shadow's result. Seeds are
     * fixed: the same scenario plays the same game.
     */
    ShadowGame.Result play(int turns) throws Exception {
        CardKeys k = keys();
        write();
        Hello hello = Hello.newBuilder().setGameId(snapshot.getGameId()).setMatchId(name).setGameNumber(1)
            .setMode(Mode.MODE_TRAINING).addAgentSeats(0).build();
        RandomPolicy random = new RandomPolicy();
        LiveShadow shadow = new LiveShadow(hello, 0, k, new magicdosa.agent.policy.Policy() {
            @Override
            public String modelVersion() {
                return "scenario";
            }

            @Override
            public DecisionResponse decide(DecisionRequest request) {
                return random.decide(request);
            }
        });
        Throwable[] failure = {null};
        Thread t = new Thread(() -> {
            try {
                ForgeRandom.install();
                ForgeRandom.seed(name.hashCode());
                SnapshotBuilder.Result built = new SnapshotBuilder(k).build(snapshot, hidden, nextId, id -> id);
                if (!built.gaps().isEmpty()) {
                    throw new IllegalStateException("scenario not built: " + built.gaps());
                }
                Game game = built.game();
                game.AI_TIMEOUT = 600;
                List<forge.deck.Deck> decks = new ArrayList<>();
                built.match().getPlayers().forEach(rp -> decks.add(rp.getDeck()));
                ForgeGameSession session = new ForgeGameSession(game, name, 1, decks, new int[2], Set.of(0),
                    new ShadowClient(shadow), k, LogSink.DISCARD, Mode.MODE_TRAINING, name.hashCode());
                for (Player p : game.getRegisteredPlayers()) {
                    if (game.getRegisteredPlayers().indexOf(p) == 0) {
                        p.dangerouslySetController(new AgentPlayerController(game, p, p.getLobbyPlayer(), session));
                    } else {
                        p.dangerouslySetController(new ObservedAiController(game, p, p.getLobbyPlayer(), session));
                    }
                }
                Set<Long> seen = new HashSet<>();
                snapshot.getObservation().getObjectsList().forEach(g -> {
                    if (!g.getOracleId().isEmpty()) {
                        seen.add(g.getId());
                    }
                });
                session.resume(snapshot, built.ids(), built.effectIds(), built.nextId(), seen);
                var ph = game.getPhaseHandler();
                while (!game.isGameOver() && ph.getTurn() < turn + turns) {
                    ph.mainLoopStep();
                }
            } catch (Throwable e) {
                failure[0] = e;
            }
        }, "Game-scenario-" + name);
        t.setDaemon(true);
        t.start();
        t.join(300_000);
        ShadowGame.Result r = shadow.stopAndResult();
        if (failure[0] != null) {
            throw new IllegalStateException("scenario " + name + " failed", failure[0]);
        }
        if (t.isAlive()) {
            throw new IllegalStateException("scenario " + name + " did not finish");
        }
        return r;
    }
}
