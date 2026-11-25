package magicdosa.agent.search;

import forge.ai.simulation.GameStateEvaluator;
import forge.game.Game;
import forge.game.GameOutcome;
import forge.game.card.Card;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.deck.Deck;
import magicdosa.adapter.forge.AgentPlayerController;
import magicdosa.adapter.forge.CardKeys;
import magicdosa.adapter.forge.DelegatingClient;
import magicdosa.adapter.forge.ForgeGameSession;
import magicdosa.adapter.forge.ForgeRandom;
import magicdosa.adapter.forge.LogSink;
import magicdosa.agent.shadow.GameCopier;
import magicdosa.protocol.v1.AdapterMessage;
import magicdosa.protocol.v1.Candidate;
import magicdosa.protocol.v1.DecisionRequest;
import magicdosa.protocol.v1.DecisionResponse;
import magicdosa.protocol.v1.DecisionType;
import magicdosa.protocol.v1.Hello;
import magicdosa.protocol.v1.HelloReply;
import magicdosa.protocol.v1.Mode;
import magicdosa.protocol.v1.PairLimit;
import magicdosa.protocol.v1.SelectionConstraints;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.function.LongFunction;

/**
 * IS-MCTS over a shadow game (phase 3; design: 그림자·탐색 "탐색용 복제기", "탐색 분기 단위"). Every simulation copies
 * the shadow game at the root decision ({@link GameCopier}), deals the cards the root seat cannot see anew (uniform
 * belief, 3단계 기획 쟁점 1), and plays the copy with Forge's own loop. Both seats are our decision layer
 * ({@link AgentPlayerController}) over a session whose client is the search: in the tree it answers from the tree
 * (each step of a multi-select is a node), out of it Forge AI answers (the rollout policy, a delegation inside the
 * copy). The simulation ends at the start of the root seat's next turn or at the game's end; the leaf value is Forge's
 * {@code GameStateEvaluator} (3단계 기획 쟁점 4) or the result.
 *
 * <p>Skeleton limits (phase 3, written while the phase-2 gate ran; not run against games yet):
 * <ul>
 *   <li>Roots are priority decisions only; a root inside an action (targets, modes while casting) needs the copy from
 *       the point before the action with the choices made so far replayed (design), not done yet.</li>
 *   <li>The root seat's library is dealt anew except the cards Forge shows it (no use of the positions the shadow
 *       session knows from scry and the like yet).</li>
 *   <li>A copy shares the shadow's lobby players: a mind-control effect inside a simulation would bind to the shadow
 *       session.</li>
 *   <li>Out of the tree every decision still goes through the decision layer (an observation per decision); swapping
 *       in plain Forge AI controllers for the rollout is a measured optimisation for later.</li>
 * </ul>
 * Decision types delegated inside simulations (decision 2, 11-16): mana payment (pool allocation), damage assignment,
 * trigger order, and the pre-game ones.
 */
public final class ForgeSearchGame implements SearchGame {
    /**
     * The game's waiting triggers (a guard: a simulation must never add to the source game's; Forge's copier once let a
     * copy's spent mana queue ManaExpend triggers in the shadow, which then held every copy).
     */
    static int waiting(Game g) {
        try {
            java.lang.reflect.Field f = forge.game.trigger.TriggerHandler.class.getDeclaredField("waitingTriggers");
            f.setAccessible(true);
            return ((List<?>) f.get(g.getTriggerHandler())).size();
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    /** Types the rollout policy answers even in the tree (3단계 기획 쟁점 2: delegated, rates recorded). */
    /**
     * Decisions searched at the root: priority (the shadow waits at a priority point) and the combat declarations (the
     * copy goes back to the step before and Forge's loop asks again). Roots inside an action are not done yet.
     */
    public static final Set<DecisionType> ROOTS = Set.of(DecisionType.DECISION_TYPE_PRIORITY,
        DecisionType.DECISION_TYPE_DECLARE_ATTACKERS, DecisionType.DECISION_TYPE_DECLARE_BLOCKERS);

    /**
     * Decisions inside an action searched at the root (3단계 기획 쟁점 2: targets, modes / X / costs, scry, surveil,
     * cards from the top): each copy starts from the anchor (a copy made at the root seat's last priority decision) and
     * replays the root seat's recorded answers since then, the other seat passing, until the engine asks the root
     * decision again (design: 그림자·탐색 "루트가 하위 결정이면 직전 상위 지점에서 복제한 뒤 이미 내린 선택을 다시 적용").
     */
    public static final Set<DecisionType> INNER_ROOTS = Set.of(DecisionType.DECISION_TYPE_TARGET,
        DecisionType.DECISION_TYPE_CHOOSE_OPTION, DecisionType.DECISION_TYPE_CHOOSE_CARDS);

    /**
     * The root seat's last priority point: a copy of the shadow made there and the root seat's decisions answered
     * since (that priority decision first), as the real game recorded them.
     */
    public record Anchor(Game game, List<magicdosa.protocol.v1.DecisionRecord> replay) {
    }

    private final Anchor anchor;

    /** A copy of the shadow at the root seat's priority decision, to start anchored searches from. */
    public static Game anchorCopy(Game shadow) {
        GameCopier copier = new GameCopier(shadow).withStack(true);
        Game copy = copier.makeCopy();
        copy.AI_TIMEOUT = 600;
        PhaseState.copy(shadow, copy, copier);
        return copy;
    }
    /** Forge card numbers of the cards in hands and libraries the root seat can see at the root (kept when dealing). */
    private final Set<Integer> seenNow = new java.util.HashSet<>();
    /** Per seat, the library positions of the shadow's cards at the root (Forge card number -> index from the top). */
    private final Map<Integer, Map<Integer, Integer>> libraryPositionsNow = new HashMap<>();
    /** Per seat, the positions of the library cards the root seat sees at the root. */
    private final Map<Integer, Map<Integer, Integer>> seenLibraryPositions = new HashMap<>();

    static final Set<DecisionType> DELEGATED = Set.of(DecisionType.DECISION_TYPE_MANA_PAYMENT,
        DecisionType.DECISION_TYPE_DAMAGE_ASSIGNMENT, DecisionType.DECISION_TYPE_TRIGGER_ORDER,
        DecisionType.DECISION_TYPE_MULLIGAN, DecisionType.DECISION_TYPE_BOTTOM_CARDS,
        DecisionType.DECISION_TYPE_PLAY_OR_DRAW, DecisionType.DECISION_TYPE_SIDEBOARD);

    /** Scale of the score difference in the leaf value's logistic (property magicdosa.search.evalScale). */
    static final double EVAL_SCALE = Double.parseDouble(System.getProperty("magicdosa.search.evalScale", "400"));
    /** Safeguard against a copy that never reaches the horizon (loop steps, never time). */
    static final int MAX_LOOP_STEPS = Integer.getInteger("magicdosa.search.maxLoopSteps", 20_000);

    private final Game shadow;
    private final ForgeGameSession shadowSession;
    private final DecisionRequest root;
    private final int rootSeat;
    private final CardKeys keys;
    private final List<Deck> decks;
    private final int[] gamesWon;
    private final String matchId;
    private final int gameNumber;
    private final List<String> rootActions;
    /** The root request's candidates by name, in candidate order. */
    private final List<String> rootNames;

    /** The candidate index an action of the root decision's steps names; -1 for "done". */
    public int rootCandidate(String action) {
        return action.equals(DONE) ? -1 : rootNames.indexOf(action);
    }
    /** What the simulations did, for the search's report. */
    public final Map<String, Integer> counts = new TreeMap<>();

    public ForgeSearchGame(Game shadow, ForgeGameSession shadowSession, DecisionRequest root, CardKeys keys, List<Deck> decks,
                           int[] gamesWon, String matchId, int gameNumber) {
        this(shadow, shadowSession, root, keys, decks, gamesWon, matchId, gameNumber, null);
    }

    /** {@code anchor}: required for {@link #INNER_ROOTS}, ignored otherwise. */
    public ForgeSearchGame(Game shadow, ForgeGameSession shadowSession, DecisionRequest root, CardKeys keys, List<Deck> decks,
                           int[] gamesWon, String matchId, int gameNumber, Anchor anchor) {
        if (!ROOTS.contains(root.getType()) && !(INNER_ROOTS.contains(root.getType()) && anchor != null)) {
            throw new IllegalArgumentException("not a search root: " + root.getType() + (anchor == null ? " (no anchor)" : ""));
        }
        this.anchor = ROOTS.contains(root.getType()) ? null : anchor;
        Player viewerNow = shadow.getRegisteredPlayers().get(root.getSeat());
        for (Player p : shadow.getRegisteredPlayers()) {
            for (ZoneType z : List.of(ZoneType.Hand, ZoneType.Library)) {
                for (Card c : p.getCardsIn(z)) {
                    if (c.getView().canBeShownTo(viewerNow.getView())) {
                        seenNow.add(c.getId());
                    }
                }
            }
        }
        for (Player p : shadow.getRegisteredPlayers()) {
            Map<Integer, Integer> at = new HashMap<>();
            List<Card> lib = new ArrayList<>(p.getCardsIn(ZoneType.Library));
            for (int i = 0; i < lib.size(); i++) {
                at.put(lib.get(i).getId(), i);
            }
            libraryPositionsNow.put(shadow.getRegisteredPlayers().indexOf(p), at);
        }
        // the cards the root decision itself shows (a scry's, the top cards to choose from): Forge does not mark them as
        // visible to the player, yet the root names them, so the copy must deal them where they are
        for (Candidate c : root.getCandidatesList()) {
            if (c.getCardId() != 0) {
                Card card = shadowSession.cardOf(c.getCardId());
                if (card != null) {
                    seenNow.add(card.getId());
                }
            }
        }
        libraryPositionsNow.forEach((seat, at) -> {
            Map<Integer, Integer> seen = new HashMap<>();
            at.forEach((id, i) -> {
                if (seenNow.contains(id)) {
                    seen.put(id, i);
                }
            });
            seenLibraryPositions.put(seat, seen);
        });
        this.shadow = shadow;
        this.shadowSession = shadowSession;
        this.root = root;
        this.rootSeat = root.getSeat();
        this.keys = keys;
        this.decks = decks;
        this.gamesWon = gamesWon;
        this.matchId = matchId;
        this.gameNumber = gameNumber;
        List<String> a = new ArrayList<>();
        for (Candidate c : root.getCandidatesList()) {
            a.add(rootKey(c, id -> forgeIdOf(shadowSession.cardOf(id), id)));
        }
        this.rootNames = List.copyOf(a);
        count("root " + root.getType().name().replace("DECISION_TYPE_", ""));
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < root.getCandidatesCount(); i++) {
            all.add(i);
        }
        // the root decision's first step: its fitting candidates and, where it may stop at once, "done"
        this.rootActions = List.copyOf(stepActions(rootNames, fitting(root, all, List.of()), doneAllowed(root, List.of())));
    }

    @Override
    public int rootSeat() {
        return rootSeat;
    }

    @Override
    public List<String> rootActions() {
        return rootActions;
    }

    /** A root candidate named by Forge card numbers, which a copy keeps (the copy's entity IDs are its own). */
    static String rootKey(Candidate c, LongFunction<String> name) {
        return IdNames.text(c, name);
    }

    private static String forgeIdOf(Card c, long entityId) {
        return c == null ? "e" + entityId : "c" + c.getId();
    }

    private void count(String what) {
        counts.merge(what, 1, Integer::sum);
    }

    @Override
    public Simulation determinize(SplittableRandom rng) {
        long t0 = magicdosa.agent.Timing.start(); // measurement only
        Game source = anchor == null ? shadow : anchor.game();
        int w0 = waiting(source);
        GameCopier copier = new GameCopier(source).withStack(true);
        Game copy = copier.makeCopy();
        if (waiting(source) != w0) {
            count("problem: copying changed the source's waiting triggers");
        }
        copy.AI_TIMEOUT = 600; // Forge AI's wall-clock limit as a safeguard only (as in the shadow), never a budget
        PhaseState.copy(source, copy, copier);
        if (copy.getStack().size() != source.getStack().size()) {
            throw new IllegalStateException("stack not copied: " + copy.getStack().size() + " of " + source.getStack().size());
        }
        if (root.getType() == DecisionType.DECISION_TYPE_DECLARE_ATTACKERS
                || root.getType() == DecisionType.DECISION_TYPE_DECLARE_BLOCKERS) {
            PhaseState.rewindToDeclaration(copy);
        }
        deal(source, copier, rng);
        magicdosa.agent.Timing.add("search.copy", t0);
        return chooser -> {
            long t1 = magicdosa.agent.Timing.start();
            int p0 = waiting(source);
            try {
                return play(copy, chooser, rng);
            } finally {
                if (waiting(source) != p0) {
                    count("problem: a simulation changed the source's waiting triggers");
                }
                magicdosa.agent.Timing.addHistogram("search.simulation", t1);
            }
        };
    }

    /**
     * Deals anew, among themselves, the cards the root seat cannot see: the other seat's hand and library together,
     * the root seat's library. The copy holds the shadow's sample; a permutation of the unseen cards is a fresh uniform
     * sample of the same 60 (game 1; the 60 of games 2 and 3 stays the shadow's sample).
     */
    private void deal(Game source, GameCopier copier, SplittableRandom rng) {
        Player viewer = source.getRegisteredPlayers().get(rootSeat);
        for (Player po : source.getRegisteredPlayers()) {
            Player pc = (Player) copier.find(po);
            int seat = source.getRegisteredPlayers().indexOf(po);
            Map<Integer, Integer> libraryAt = seenLibraryPositions.getOrDefault(seat, Map.of());
            List<ZoneType> zones = po == viewer ? List.of(ZoneType.Library) : List.of(ZoneType.Hand, ZoneType.Library);
            Map<ZoneType, List<Card>> lists = new EnumMap<>(ZoneType.class);
            List<ZoneType> slotZone = new ArrayList<>();
            List<Integer> slotIndex = new ArrayList<>();
            List<Card> pool = new ArrayList<>();
            for (ZoneType z : zones) {
                List<Card> orig = new ArrayList<>(po.getCardsIn(z));
                List<Card> copied = new ArrayList<>(pc.getCardsIn(z));
                if (orig.size() != copied.size()) {
                    throw new IllegalStateException("copy of " + z + " has " + copied.size() + " cards, the shadow " + orig.size());
                }
                lists.put(z, copied);
                for (int i = 0; i < orig.size(); i++) {
                    Card o = orig.get(i);
                    boolean placedByShadow = libraryAt.containsKey(o.getId());
                    boolean seenHere = seenNow.contains(o.getId()) || o.getView().canBeShownTo(viewer.getView());
                    if (placedByShadow || !seenHere) {
                        // dealt: either to where the shadow has it now (a card seen in a library: the scry's), or at random
                        slotZone.add(z);
                        slotIndex.add(i);
                        pool.add(copied.get(i));
                    }
                }
            }
            // cards seen in a library now go where the shadow has them now (an anchor is older: the shadow put the cards a
            // scry shows on top when they were shown), the rest of the slots are dealt at random
            List<Card> library = lists.get(ZoneType.Library);
            Map<Integer, Card> fixedAt = new java.util.TreeMap<>();
            for (Card c : pool) {
                Integer at = libraryAt.get(c.getId());
                if (at != null && at < library.size() && !fixedAt.containsKey(at)) {
                    fixedAt.put(at, c);
                }
            }
            pool.removeAll(fixedAt.values());
            List<ZoneType> freeZone = new ArrayList<>();
            List<Integer> freeIndex = new ArrayList<>();
            for (int k = 0; k < slotZone.size(); k++) {
                boolean taken = slotZone.get(k) == ZoneType.Library && fixedAt.containsKey(slotIndex.get(k));
                if (!taken) {
                    freeZone.add(slotZone.get(k));
                    freeIndex.add(slotIndex.get(k));
                }
            }
            fixedAt.forEach(library::set);
            // a fixed position that was not a dealt slot (a seen card was there already) leaves one more card than slots
            while (pool.size() > freeZone.size()) {
                pool.remove(pool.size() - 1);
            }
            for (int i = pool.size() - 1; i > 0; i--) {
                int j = rng.nextInt(i + 1);
                Card t = pool.get(i);
                pool.set(i, pool.get(j));
                pool.set(j, t);
            }
            for (int k = 0; k < pool.size(); k++) {
                lists.get(freeZone.get(k)).set(freeIndex.get(k), pool.get(k));
            }
            for (ZoneType z : zones) {
                pc.getZone(z).setCards(lists.get(z));
            }
        }
    }

    private double play(Game copy, Chooser chooser, SplittableRandom rng) {
        Client client = new Client(chooser, rng.split());
        ForgeGameSession s = new ForgeGameSession(copy, matchId, gameNumber, decks, gamesWon.clone(), Set.of(0, 1), client,
            keys, LogSink.DISCARD, Mode.MODE_TRAINING, rng.nextLong());
        client.session = s;
        s.attachToCopy();
        for (Player p : copy.getRegisteredPlayers()) {
            p.dangerouslySetController(new AgentPlayerController(copy, p, p.getLobbyPlayer(), s));
        }
        java.util.Random saved = ForgeRandom.current();
        ForgeRandom.seed(rng.nextLong());
        try {
            PhaseHandler ph = copy.getPhaseHandler();
            Player me = copy.getRegisteredPlayers().get(rootSeat);
            int rootTurn = ph.getTurn();
            int steps = 0;
            while (!copy.isGameOver()) {
                ph.mainLoopStep();
                if (ph.getTurn() > rootTurn && ph.getPlayerTurn() == me) {
                    count("horizon");
                    break; // the start of the root seat's next turn (3단계 기획 쟁점 4)
                }
                if (++steps > MAX_LOOP_STEPS) {
                    throw new IllegalStateException("no horizon after " + MAX_LOOP_STEPS + " loop steps");
                }
            }
            if (!client.askedRoot) {
                count("no root: " + root.getType().name().replace("DECISION_TYPE_", "") + " at "
                    + root.getObservation().getGame().getStep() + (copy.isGameOver() ? " (game over)" : " (horizon)")
                    + (root.getObservation().getStackCount() > 0 ? " stack" : ""));
                return 0.5; // the walk reports it: the root decision was never asked
            }
            long tv = magicdosa.agent.Timing.start();
            double v = value(copy, me);
            magicdosa.agent.Timing.add("search.leafValue", tv);
            return v;
        } finally {
            ForgeRandom.restore(saved);
        }
    }

    /** 1 a win, 0 a loss, 0.5 a draw; else the logistic of the score difference of Forge's state evaluator. */
    private double value(Game copy, Player me) {
        if (copy.isGameOver()) {
            count("game over");
            GameOutcome o = copy.getOutcome();
            if (o == null || o.isDraw() || o.getWinningLobbyPlayer() == null) {
                return 0.5;
            }
            return o.getWinningLobbyPlayer() == me.getLobbyPlayer() ? 1 : 0;
        }
        Player opp = me.getWeakestOpponent();
        GameStateEvaluator ev = new GameStateEvaluator();
        double mine = ev.getScoreForGameState(copy, me).value;
        double theirs = ev.getScoreForGameState(copy, opp).value;
        if (GameStateEvaluator.isWinning((int) mine)) {
            return 1;
        }
        if (GameStateEvaluator.isWinning((int) theirs)) {
            return 0;
        }
        return 1 / (1 + Math.exp(-(mine - theirs) / EVAL_SCALE));
    }

    /**
     * The copy's decision client: the tree-lookup controller. A request becomes one tree decision per step (the step's
     * remaining candidates plus "done" where the selection may stop); {@link Chooser#ROLLOUT} hands the request to
     * Forge AI (a delegation in the copy), or, in the middle of a multi-select, finishes it at random.
     */
    private final class Client implements DelegatingClient {
        private final Chooser chooser;
        private final SplittableRandom rng;
        ForgeGameSession session;
        boolean askedRoot;
        private boolean delegate;

        Client(Chooser chooser, SplittableRandom rng) {
            this.chooser = chooser;
            this.rng = rng;
        }

        @Override
        public HelloReply hello(Hello hello) {
            return HelloReply.newBuilder().setAccepted(true).build();
        }

        @Override
        public void send(AdapterMessage message) {
            // events, snapshots and the game end of a copy are not kept
        }

        @Override
        public boolean delegateLast() {
            return delegate;
        }

        private int replayed;
        private boolean rootChosen;

        /**
         * Before the root of an anchored search: the root seat's recorded answers in order (by candidate index: the
         * same decision layer asks the same request), the other seat passing. Null when the root is next.
         */
        private DecisionResponse replay(DecisionRequest r, DecisionResponse.Builder out) {
            if (r.getSeat() != rootSeat) {
                if (r.getType() == DecisionType.DECISION_TYPE_PRIORITY) {
                    for (int i = 0; i < r.getCandidatesCount(); i++) {
                        if (r.getCandidates(i).hasPass()) {
                            return out.addChosen(i).build();
                        }
                    }
                }
                throw new IllegalStateException("replay: the other seat asked " + r.getType());
            }
            if (replayed >= anchor.replay().size()) {
                return null;
            }
            magicdosa.protocol.v1.DecisionRecord rec = anchor.replay().get(replayed++);
            if (rec.getRequest().getType() != r.getType() || rec.getRequest().getCandidatesCount() != r.getCandidatesCount()
                    || !rec.hasResponse()) {
                throw new IllegalStateException("replay differs at step " + replayed + ": recorded "
                    + rec.getRequest().getType() + "/" + rec.getRequest().getCandidatesCount() + ", asked " + r.getType()
                    + "/" + r.getCandidatesCount());
            }
            return out.addAllChosen(rec.getResponse().getChosenList()).build();
        }

        /** When the last delegated request went to Forge AI (measurement only: rollout decision times). */
        private long delegatedAt;

        @Override
        public void delegated(DecisionRequest request, List<Integer> chosen, String engineDecision) {
            count("rollout " + request.getType().name().replace("DECISION_TYPE_", ""));
            magicdosa.agent.Timing.addHistogram("search.forgeAi." + request.getType().name().replace("DECISION_TYPE_", ""),
                delegatedAt);
        }

        @Override
        public void unmapped(DecisionRequest request, String engineDecision) {
            count("rollout answer matched no candidate: " + request.getType().name().replace("DECISION_TYPE_", ""));
        }

        @Override
        public DecisionResponse decide(DecisionRequest r) {
            delegate = false;
            DecisionResponse.Builder out = DecisionResponse.newBuilder().setGameId(r.getGameId()).setDecisionId(r.getDecisionId());
            if (anchor != null && !askedRoot) {
                DecisionResponse replayed = replay(r, out);
                if (replayed != null) {
                    return replayed;
                }
            }
            if (!askedRoot && r.getSeat() != rootSeat) {
                // the other seat is asked before the root (its triggers the copy puts on the stack first): Forge AI
                count("before the root: " + r.getType().name().replace("DECISION_TYPE_", ""));
                delegate = true;
                delegatedAt = magicdosa.agent.Timing.start();
                return out.build();
            }
            boolean isRoot = !askedRoot;
            askedRoot = true;
            if (!isRoot && (DELEGATED.contains(r.getType()) || r.getCandidatesCount() == 0)) {
                count("delegated " + r.getType().name().replace("DECISION_TYPE_", ""));
                delegate = true;
                delegatedAt = magicdosa.agent.Timing.start();
                return out.build();
            }
            // the root decision's candidates are named by Forge card numbers (they must equal the real request's);
            // elsewhere by the copy's own entity IDs
            List<String> names = new ArrayList<>();
            for (Candidate k : r.getCandidatesList()) {
                names.add(isRoot ? rootKey(k, id -> forgeIdOf(session.cardOf(id), id))
                    : com.google.protobuf.TextFormat.shortDebugString(k));
            }
            String info = isRoot ? "root" : magicdosa.agent.shadow.Fingerprint.hash(magicdosa.agent.shadow.Fingerprint.normalise(
                r.toBuilder().clearCandidates().clearConstraints().build()));
            SelectionConstraints c = r.getConstraints();
            List<Integer> remaining = new ArrayList<>();
            for (int i = 0; i < r.getCandidatesCount(); i++) {
                remaining.add(i);
            }
            List<Integer> chosen = new ArrayList<>();
            boolean inTree = true;
            while (chosen.size() < c.getMax()) {
                List<Integer> fit = fitting(r, remaining, chosen);
                boolean doneAllowed = doneAllowed(r, chosen);
                if (fit.isEmpty() && !doneAllowed) {
                    throw new IllegalStateException(r.getType() + ": no legal way to complete the selection");
                }
                if (fit.isEmpty()) {
                    break;
                }
                int pick = Chooser.ROLLOUT;
                if (inTree) {
                    List<String> actions = stepActions(names, fit, doneAllowed);
                    // a step's information set: the decision's plus what is chosen so far, in order-free form
                    List<Integer> sorted = new ArrayList<>(chosen);
                    sorted.sort(Integer::compare);
                    pick = chooser.choose(new Decision(r.getSeat(), chosen.isEmpty() ? info : info + "|" + sorted, actions,
                        !chosen.isEmpty()));
                    rootChosen |= isRoot;
                    if (pick == Chooser.ROLLOUT) {
                        if (chosen.isEmpty()) {
                            delegate = true; // Forge AI answers the whole request
                            delegatedAt = magicdosa.agent.Timing.start();
                            return out.build();
                        }
                        inTree = false;
                    }
                }
                if (!inTree) {
                    pick = rng.nextInt(fit.size() + (doneAllowed ? 1 : 0)); // rest of a step selection: at random
                }
                if (pick == fit.size()) {
                    break; // done
                }
                int idx = fit.get(pick);
                chosen.add(idx);
                remaining.remove(Integer.valueOf(idx));
            }
            if (isRoot && !rootChosen) {
                count("root request without a tree decision: " + r.getType().name().replace("DECISION_TYPE_", "") + " seat "
                    + r.getSeat() + " min " + c.getMin() + " max " + c.getMax() + " candidates " + r.getCandidatesCount());
            }
            return out.addAllChosen(chosen).build();
        }
    }

    /** A step's actions: the fitting candidates' names, then "done" where the selection may stop. */
    static List<String> stepActions(List<String> names, List<Integer> fit, boolean doneAllowed) {
        List<String> actions = new ArrayList<>();
        for (int i : fit) {
            actions.add(names.get(i));
        }
        if (doneAllowed) {
            actions.add(DONE);
        }
        return actions;
    }

    /** The "done" action of a multi-select step. */
    public static final String DONE = "done";

    /** Whether a selection with these constraints may stop with "done" after {@code chosen} picks. */
    static boolean doneAllowed(DecisionRequest r, List<Integer> chosen) {
        SelectionConstraints c = r.getConstraints();
        return chosen.size() >= c.getMin() && complete(r, chosen) && !(c.getMin() == 1 && c.getMax() == 1);
    }

    /** {@link #doneAllowed}, for the answer's own steps. */
    public static boolean mayStop(DecisionRequest r, List<Integer> chosen) {
        return chosen.size() >= r.getConstraints().getMin() && complete(r, chosen);
    }

    /** {@link #fitting}, for the answer's own steps. */
    public static List<Integer> fits(DecisionRequest r, List<Integer> remaining, List<Integer> chosen) {
        return fitting(r, remaining, chosen);
    }

    /** Pair candidates (attack, block) still within their objects' limits; every other candidate. */
    static List<Integer> fitting(DecisionRequest r, List<Integer> remaining, List<Integer> chosen) {
        Map<Long, Integer> max = new HashMap<>();
        for (PairLimit l : r.getConstraints().getPairLimitsList()) {
            if (l.getMax() > 0) {
                max.put(l.getId(), l.getMax());
            }
        }
        if (max.isEmpty()) {
            return new ArrayList<>(remaining);
        }
        Map<Long, Integer> uses = uses(r, chosen);
        List<Integer> out = new ArrayList<>();
        for (int i : remaining) {
            boolean ok = true;
            for (long id : pairIds(r.getCandidates(i))) {
                Integer m = max.get(id);
                ok &= m == null || uses.getOrDefault(id, 0) < m;
            }
            if (ok) {
                out.add(i);
            }
        }
        return out;
    }

    /** Whether a selection may stop here: every required object is in a pair, every used object has its minimum. */
    static boolean complete(DecisionRequest r, List<Integer> chosen) {
        Map<Long, Integer> uses = uses(r, chosen);
        for (long id : r.getConstraints().getRequiredIdsList()) {
            if (uses.getOrDefault(id, 0) == 0) {
                return false;
            }
        }
        for (PairLimit l : r.getConstraints().getPairLimitsList()) {
            int u = uses.getOrDefault(l.getId(), 0);
            if (u > 0 && u < l.getMinIfAny()) {
                return false;
            }
        }
        return true;
    }

    private static Map<Long, Integer> uses(DecisionRequest r, List<Integer> chosen) {
        Map<Long, Integer> uses = new HashMap<>();
        for (int i : chosen) {
            for (long id : pairIds(r.getCandidates(i))) {
                uses.merge(id, 1, Integer::sum);
            }
        }
        return uses;
    }

    private static List<Long> pairIds(Candidate c) {
        if (c.hasAttack()) {
            List<Long> out = new ArrayList<>(List.of(c.getAttack().getAttackerId()));
            if (c.getAttack().getDefender().hasEntityId()) {
                out.add(c.getAttack().getDefender().getEntityId());
            }
            return out;
        }
        if (c.hasBlock()) {
            return List.of(c.getBlock().getBlockerId(), c.getBlock().getAttackerId());
        }
        return List.of();
    }

    /**
     * The phase handler's priority state, which {@code devModeSet} in the copier resets to the active player: the copy
     * must give priority to whoever has it in the shadow (the root seat), with the same first-priority player.
     */
    static final class PhaseState {
        private static final Field PRIORITY = field("pPlayerPriority");
        private static final Field FIRST = field("pFirstPriority");
        private static final Field GIVE = field("givePriorityToPlayer");

        private static Field field(String name) {
            try {
                Field f = PhaseHandler.class.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        /**
         * A combat declaration root: the shadow waits inside the declaration (a turn-based action at the start of the
         * step), which a copy cannot resume. The copy goes back to the step before, with first priority on the other
         * seat and none given, so Forge's loop moves on at once and starts the step again (the combat itself is copied:
         * no attackers yet for an attack root, the attackers for a block root).
         */
        static void rewindToDeclaration(Game copy) {
            PhaseHandler ph = copy.getPhaseHandler();
            forge.game.phase.PhaseType step = ph.getPhase();
            forge.game.phase.PhaseType before = step == forge.game.phase.PhaseType.COMBAT_DECLARE_ATTACKERS
                ? forge.game.phase.PhaseType.COMBAT_BEGIN : forge.game.phase.PhaseType.COMBAT_DECLARE_ATTACKERS;
            Player active = ph.getPlayerTurn();
            ph.devModeSet(before, active, false, ph.getTurn());
            try {
                PRIORITY.set(ph, active);
                FIRST.set(ph, active.getWeakestOpponent());
                GIVE.setBoolean(ph, false);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        static void copy(Game from, Game to, GameCopier copier) {
            PhaseHandler a = from.getPhaseHandler();
            PhaseHandler b = to.getPhaseHandler();
            try {
                PRIORITY.set(b, copier.find((Player) PRIORITY.get(a)));
                FIRST.set(b, copier.find((Player) FIRST.get(a)));
                GIVE.setBoolean(b, GIVE.getBoolean(a));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
