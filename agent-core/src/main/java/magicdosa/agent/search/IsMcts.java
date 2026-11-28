package magicdosa.agent.search;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Single-tree information-set MCTS (3단계 기획 쟁점 1, 11-15 decision: SO-ISMCTS with a new sample every simulation;
 * the root seat's nodes are its information sets, the other seat's nodes are the other seat's information sets).
 *
 * <ul>
 *   <li>Candidates are every legal action the engine gives; the prior is uniform (phase 3 has no network).</li>
 *   <li>Selection is UCB1 with availability counts (an action available in only some determinizations is judged by
 *       how often it was available, not by its parent's visits).</li>
 *   <li>A simulation that reaches a node the tree does not have adds it and continues with the rollout policy
 *       ({@link SearchGame.Chooser#ROLLOUT}); at most one node per simulation.</li>
 *   <li>Progressive widening for nodes with many actions ({@link SearchConfig#wideningMin()}).</li>
 *   <li>The budget is a number of simulations; nothing reads a clock. With the same game state, seed and settings the
 *       result is the same (single thread, all randomness from the seed).</li>
 * </ul>
 * One instance searches one decision; not thread-safe.
 */
public final class IsMcts {
    private final SearchConfig config;

    public IsMcts(SearchConfig config) {
        this.config = config;
    }

    /** Statistics of one action at a node. */
    static final class Edge {
        final String action;
        final long rank;              // per-node random order for widening (uniform prior)
        long visits;
        long available;
        double reward;                // summed, from the point of view of the node's deciding seat
        final Map<String, Node> next = new HashMap<>();

        Edge(String action, long rank) {
            this.action = action;
            this.rank = rank;
        }
    }

    /** An information set of the deciding seat. */
    static final class Node {
        final int seat;
        final boolean continuation;
        long visits;
        final Map<String, Edge> edges = new LinkedHashMap<>();

        Node(int seat) {
            this(seat, false);
        }

        Node(int seat, boolean continuation) {
            this.seat = seat;
            this.continuation = continuation;
        }
    }

    /** Thrown inside a simulation whose root decision is not the searched one (its candidates differ). */
    private static final class RootMismatch extends RuntimeException {
        RootMismatch(String why) {
            super(why, null, false, false);
        }
    }

    /** A finished search's tree, kept for {@link TreeReuse}. */
    public static final class Tree {
        final Node root;
        final List<String> rootActions;

        Tree(Node root, List<String> rootActions) {
            this.root = root;
            this.rootActions = rootActions;
        }
    }

    private Tree last;

    /** The tree of the last search of this instance. */
    public Tree lastTree() {
        return last;
    }

    public SearchResult search(SearchGame game, long seed) {
        return search(game, seed, null);
    }

    /**
     * {@code inherited}: the root built from the previous search's subtree ({@link TreeReuse#inherit}), its edges named by
     * {@code game.rootActions()}; null for a fresh tree. Inherited visits count toward the budget: the search plays
     * budget - inherited root visits new simulations, at least one per root action.
     */
    public SearchResult search(SearchGame game, long seed, TreeReuse.Inherited inherited) {
        List<String> rootActions = List.copyOf(game.rootActions());
        int n = rootActions.size();
        SearchResult.Builder out = new SearchResult.Builder(config, seed, n);
        if (n == 0) {
            throw new IllegalArgumentException("no root candidates");
        }
        SplittableRandom rng = new SplittableRandom(seed);
        Node root = new Node(game.rootSeat());
        for (String a : rootActions) {
            root.edges.put(a, new Edge(a, rng.nextLong()));
        }
        long[] nodes = {1};
        long budget = config.budgetFor(n);
        out.budget = budget;
        long simulations = budget;
        SearchConfig.Gumbel gumbel = config.gumbel();
        if (gumbel != null) {
            // tree reuse would hand the root's sequential halving visits it did not schedule: Gumbel starts fresh
            inherited = null;
        }
        if (inherited != null) {
            root = inherited.root(root);
            out.inheritedVisits = root.visits;
            out.inheritedNodes = inherited.nodes();
            simulations = Math.max(budget - root.visits, n);
        }
        Halving halving = gumbel == null ? null : new Halving(rootActions, gumbel, budget, seed);
        for (long it = 0; it < simulations; it++) {
            String forced = null;
            if (halving != null) {
                forced = halving.next(root);
                if (forced == null) {
                    break; // the schedule is spent (never more than the budget)
                }
            }
            SplittableRandom simRng = rng.split();
            out.iterations++;
            SearchGame.Simulation sim;
            try {
                sim = game.determinize(simRng.split());
            } catch (RuntimeException e) {
                out.failure("determinize: " + e);
                continue;
            }
            if (sim == null) {
                out.noDeterminization++;
                continue;
            }
            Walk walk = new Walk(root, rootActions, simRng, nodes, forced);
            double v;
            try {
                v = sim.play(walk);
            } catch (RootMismatch e) {
                out.rootMismatch(e.getMessage());
                continue;
            } catch (RuntimeException e) {
                out.failure("simulation: " + e);
                continue;
            }
            if (Double.isNaN(v) || v < 0 || v > 1) {
                out.failure("value out of range: " + v);
                continue;
            }
            if (walk.mismatch != null) {
                out.rootMismatch(walk.mismatch);
                continue;
            }
            if (walk.path.isEmpty()) {
                out.rootMismatch("the simulation never asked the root decision");
                continue;
            }
            out.determinizations++;
            for (int i = 0; i < walk.path.size(); i++) {
                Node at = walk.pathNodes.get(i);
                Edge e = walk.path.get(i);
                at.visits++;
                e.visits++;
                e.reward += at.seat == root.seat ? v : 1 - v;
            }
        }
        long[] visits = new long[n];
        double reward = 0;
        for (int i = 0; i < n; i++) {
            Edge e = root.edges.get(rootActions.get(i));
            visits[i] = e.visits;
            reward += e.reward;
        }
        out.nodes = nodes[0];
        last = new Tree(root, rootActions);
        if (halving != null) {
            out.gumbelK = halving.k;
            return out.build(visits, root.visits == 0 ? Double.NaN : reward / root.visits,
                gumbelSteps(root, halving, new SplittableRandom(seed ^ 0x5DEECE66DL)));
        }
        // ties the visits and the mean values leave are drawn from the seed (its own stream: the simulations' draws
        // above do not depend on it)
        return out.build(visits, root.visits == 0 ? Double.NaN : reward / root.visits,
            steps(root, new SplittableRandom(seed ^ 0x5DEECE66DL),
                SAMPLE_TURNS > 0 && game.rootTurn() > 0 && game.rootTurn() <= SAMPLE_TURNS
                    ? new SplittableRandom(seed ^ 0x9E3779B97F4A7C15L) : null));
    }

    /**
     * The answer among visited actions (2026-11-25 planning decision): the most visits; equal visits, the higher mean
     * value; equal means too, a pick drawn from {@code rng} (the search's seed: the same seed gives the same pick, and no
     * candidate, such as pass at candidate 0, is favoured by its position). Null when nothing was visited.
     */
    static SearchResult.Choice choose(long[] visits, double[] mean, java.util.SplittableRandom rng) {
        long most = 0;
        for (long v : visits) {
            most = Math.max(most, v);
        }
        if (most == 0) {
            return null;
        }
        List<Integer> leaders = new ArrayList<>();
        for (int i = 0; i < visits.length; i++) {
            if (visits[i] == most) {
                leaders.add(i);
            }
        }
        if (leaders.size() == 1) {
            return new SearchResult.Choice(leaders.get(0), SearchResult.Tie.NONE);
        }
        double best = Double.NEGATIVE_INFINITY;
        for (int i : leaders) {
            best = Math.max(best, mean[i]);
        }
        List<Integer> top = new ArrayList<>();
        for (int i : leaders) {
            if (mean[i] == best) {
                top.add(i);
            }
        }
        if (top.size() == 1) {
            return new SearchResult.Choice(top.get(0), SearchResult.Tie.MEAN_VALUE);
        }
        return new SearchResult.Choice(top.get(rng.nextInt(top.size())), SearchResult.Tie.SEED);
    }

    /**
     * The root decision's steps as the search answers them: at each step the action {@link #choose} picks, then the
     * most visited continuation node under it, until "done" or no further step was searched.
     */
    /**
     * Data runs only (2026-11-26 user decision, off by default and in gates): up to this game turn the root's played
     * action is drawn in proportion to its visits from the search's seed (deterministic, no clock), so self-play
     * visits more lines; the visit ratios, the policy target, are unchanged. {@code -Dmagicdosa.search.sampleTurns=N}.
     */
    static final int SAMPLE_TURNS = Integer.getInteger("magicdosa.search.sampleTurns", 0);

    private static List<SearchResult.Step> steps(Node root, SplittableRandom tieRng, SplittableRandom sampleRng) {
        List<SearchResult.Step> out = new ArrayList<>();
        Node node = root;
        while (node != null && node.visits > 0) {
            List<String> actions = new ArrayList<>();
            long[] v = new long[node.edges.size()];
            double[] mean = new double[node.edges.size()];
            int i = 0;
            for (Edge e : node.edges.values()) {
                actions.add(e.action);
                v[i] = e.visits;
                mean[i] = e.visits == 0 ? 0 : e.reward / e.visits;
                i++;
            }
            SearchResult.Choice c = choose(v, mean, tieRng);
            if (c == null) {
                break;
            }
            if (sampleRng != null && node == root) {
                c = sampled(v, sampleRng, c);
            }
            int best = c.index();
            out.add(new SearchResult.Step(actions, v, mean, best, c.tie()));
            Edge chosen = node.edges.get(actions.get(best));
            Node next = null;
            for (Node n : chosen.next.values()) {
                if (n.continuation && n.seat == root.seat && (next == null || n.visits > next.visits)) {
                    next = n;
                }
            }
            node = next;
        }
        return out;
    }

    /**
     * Gumbel sequential halving at the root (2026-11-28 directive; Danihelka et al. 2022). The k = min(maxK, actions)
     * candidates with the highest g + logits enter (g: Gumbel(0, 1) noise from the seed when {@code noise}, else 0;
     * logits all 0, a uniform prior; ties by a per-action rank from the seed). ceil(log2 k) rounds: each gives every
     * remaining candidate budget / (rounds * remaining) simulations (at least one), the last round what is left, then
     * keeps the better half by g + logits + sigma(q_hat). Every started simulation counts, so the total never exceeds
     * the budget. The answer is the best remaining candidate by the same score.
     */
    static final class Halving {
        final List<String> actions;
        final SearchConfig.Gumbel gumbel;
        final long budget;
        final int k;
        final int rounds;
        final java.util.Map<String, Double> g = new java.util.HashMap<>();
        final java.util.Map<String, Double> rank = new java.util.HashMap<>();
        List<String> remaining;
        int round;
        long spent;
        /** This round's simulations still to start, as a queue of actions. */
        final java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();

        Halving(List<String> actions, SearchConfig.Gumbel gumbel, long budget, long seed) {
            this.actions = actions;
            this.gumbel = gumbel;
            this.budget = budget;
            SplittableRandom r = new SplittableRandom(seed ^ 0x47756D62656CL);
            for (String a : actions) {
                double u = r.nextDouble();
                g.put(a, gumbel.noise() ? -Math.log(-Math.log(Math.max(u, Double.MIN_VALUE))) : 0.0);
                rank.put(a, r.nextDouble());
            }
            this.k = Math.min(gumbel.maxK(), actions.size());
            this.rounds = k <= 1 ? 1 : 32 - Integer.numberOfLeadingZeros(k - 1); // ceil(log2 k)
            List<String> byPrior = new ArrayList<>(actions);
            byPrior.sort(order(a -> g.get(a))); // logits are 0
            this.remaining = new ArrayList<>(byPrior.subList(0, k));
        }

        /** Descending by score, ties by the seeded rank (never by candidate order). */
        java.util.Comparator<String> order(java.util.function.ToDoubleFunction<String> score) {
            return java.util.Comparator.<String>comparingDouble(a -> -score.applyAsDouble(a))
                .thenComparingDouble(a -> rank.get(a));
        }

        /** g + logits + sigma(q_hat) of a root action. */
        double score(Node root, String a) {
            long maxN = 0;
            double rewards = 0;
            long visits = 0;
            for (Edge e : root.edges.values()) {
                maxN = Math.max(maxN, e.visits);
                rewards += e.reward;
                visits += e.visits;
            }
            double v = visits == 0 ? 0.5 : rewards / visits; // the root's mean: the completed Q of unvisited ones
            Edge e = root.edges.get(a);
            double q = e.visits > 0 ? e.reward / e.visits : v;
            return g.get(a) + GumbelMath.sigma(q, maxN, gumbel.cVisit(), gumbel.cScale());
        }

        /** The root action of the next simulation; null when the budget is spent. */
        String next(Node root) {
            if (spent >= budget) {
                return null;
            }
            while (queue.isEmpty()) {
                if (round > 0) {
                    if (remaining.size() <= 1 && round >= rounds) {
                        // a single candidate left with budget over (floors): it takes the rest
                        if (remaining.isEmpty()) {
                            return null;
                        }
                    } else if (remaining.size() > 1) {
                        List<String> sorted = new ArrayList<>(remaining);
                        sorted.sort(order(a -> score(root, a)));
                        remaining = new ArrayList<>(sorted.subList(0, (remaining.size() + 1) / 2));
                    }
                }
                long left = budget - spent;
                int m = remaining.size();
                boolean last = round >= rounds - 1 || m <= 1;
                List<String> sorted = new ArrayList<>(remaining);
                sorted.sort(order(a -> score(root, a)));
                if (last) {
                    long each = left / m;
                    long extra = left % m;
                    for (String a : sorted) {
                        for (long i = 0; i < each + (extra > 0 ? 1 : 0); i++) {
                            queue.add(a);
                        }
                        extra--;
                    }
                } else {
                    long each = Math.max(1, budget / ((long) rounds * m));
                    each = Math.min(each, left / m);
                    if (each == 0) {
                        for (int i = 0; i < left; i++) {
                            queue.add(sorted.get(i % m));
                        }
                    } else {
                        for (String a : sorted) {
                            for (long i = 0; i < each; i++) {
                                queue.add(a);
                            }
                        }
                    }
                }
                round++;
                if (queue.isEmpty()) {
                    return null;
                }
            }
            spent++;
            return queue.poll();
        }

        /** The answer: the best remaining candidate by g + logits + sigma(q_hat), ties by the seeded rank. */
        String best(Node root) {
            List<String> sorted = new ArrayList<>(remaining);
            sorted.sort(order(a -> score(root, a)));
            return sorted.get(0);
        }

        /** Whether the best remaining candidate ties another on the score (broken by the seed). */
        boolean tied(Node root) {
            if (remaining.size() < 2) {
                return false;
            }
            List<String> sorted = new ArrayList<>(remaining);
            sorted.sort(order(a -> score(root, a)));
            return score(root, sorted.get(0)) == score(root, sorted.get(1));
        }
    }

    /**
     * The root decision's steps in Gumbel mode: the first by sequential halving's answer, each later step of a
     * multi-step decision by the same score without noise (logits + sigma(q_hat) among its visited actions, the node's
     * mean as the completed Q, ties by the seed).
     */
    private List<SearchResult.Step> gumbelSteps(Node root, Halving halving, SplittableRandom tieRng) {
        List<SearchResult.Step> out = new ArrayList<>();
        Node node = root;
        boolean first = true;
        while (node != null && node.visits > 0) {
            List<String> actions = new ArrayList<>();
            long[] v = new long[node.edges.size()];
            double[] mean = new double[node.edges.size()];
            int i = 0;
            for (Edge e : node.edges.values()) {
                actions.add(e.action);
                v[i] = e.visits;
                mean[i] = e.visits == 0 ? 0 : e.reward / e.visits;
                i++;
            }
            int best;
            SearchResult.Tie tie;
            if (first) {
                best = actions.indexOf(halving.best(root));
                tie = halving.tied(root) ? SearchResult.Tie.SEED : SearchResult.Tie.NONE;
            } else {
                double[] score = completedScores(node, v, mean);
                List<Integer> top = new ArrayList<>();
                double bestScore = Double.NEGATIVE_INFINITY;
                for (int j = 0; j < v.length; j++) {
                    if (v[j] == 0) {
                        continue;
                    }
                    if (score[j] > bestScore) {
                        bestScore = score[j];
                        top.clear();
                        top.add(j);
                    } else if (score[j] == bestScore) {
                        top.add(j);
                    }
                }
                if (top.isEmpty()) {
                    break;
                }
                best = top.size() == 1 ? top.get(0) : top.get(tieRng.nextInt(top.size()));
                tie = top.size() == 1 ? SearchResult.Tie.NONE : SearchResult.Tie.SEED;
            }
            first = false;
            out.add(new SearchResult.Step(actions, v, mean, best, tie));
            Edge chosen = node.edges.get(actions.get(best));
            Node next = null;
            for (Node m : chosen.next.values()) {
                if (m.continuation && m.seat == root.seat && (next == null || m.visits > next.visits)) {
                    next = m;
                }
            }
            node = next;
        }
        return out;
    }

    /** logits + sigma(q_hat) per action of a node (logits 0; the node's mean value completes unvisited ones). */
    private double[] completedScores(Node node, long[] visits, double[] mean) {
        SearchConfig.Gumbel gm = config.gumbel();
        long maxN = 0;
        double rewards = 0;
        long total = 0;
        for (Edge e : node.edges.values()) {
            maxN = Math.max(maxN, e.visits);
            rewards += e.reward;
            total += e.visits;
        }
        double v = total == 0 ? 0.5 : rewards / total;
        double[] out = new double[visits.length];
        for (int j = 0; j < visits.length; j++) {
            out[j] = GumbelMath.sigma(visits[j] > 0 ? mean[j] : v, maxN, gm.cVisit(), gm.cScale());
        }
        return out;
    }

    /** An action drawn in proportion to its visits; {@code fallback} when nothing was visited. */
    static SearchResult.Choice sampled(long[] visits, SplittableRandom rng, SearchResult.Choice fallback) {
        long total = 0;
        for (long x : visits) {
            total += x;
        }
        if (total <= 0) {
            return fallback;
        }
        long r = rng.nextLong(total);
        for (int i = 0; i < visits.length; i++) {
            r -= visits[i];
            if (r < 0) {
                return new SearchResult.Choice(i, SearchResult.Tie.SAMPLED);
            }
        }
        return fallback;
    }

    private static String abbreviate(String s) {
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }

    /** One simulation's walk down the tree: the tree-lookup chooser. */
    private final class Walk implements SearchGame.Chooser {
        private final Node root;
        private final List<String> rootActions;
        private final SplittableRandom rng;
        private final long[] nodes;
        final List<Edge> path = new ArrayList<>();
        final List<Node> pathNodes = new ArrayList<>();
        private boolean started;
        private boolean outOfTree;
        /** Gumbel: the root action sequential halving gave this simulation. */
        private final String forcedRoot;
        /** Why the root decision did not match, also when the engine swallowed the exception. */
        String mismatch;

        Walk(Node root, List<String> rootActions, SplittableRandom rng, long[] nodes, String forcedRoot) {
            this.forcedRoot = forcedRoot;
            this.root = root;
            this.rootActions = rootActions;
            this.rng = rng;
            this.nodes = nodes;
        }

        @Override
        public boolean outOfTree() {
            return outOfTree;
        }

        @Override
        public int choose(SearchGame.Decision d) {
            Node node;
            if (!started) {
                started = true;
                if (d.seat() != root.seat || !d.actions().equals(rootActions)) {
                    String first = "";
                    for (int i = 0; i < Math.max(d.actions().size(), rootActions.size()); i++) {
                        String x = i < d.actions().size() ? d.actions().get(i) : "-";
                        String y = i < rootActions.size() ? rootActions.get(i) : "-";
                        if (!x.equals(y)) {
                            first = "; first difference at " + i + ": " + abbreviate(x) + " vs searched " + abbreviate(y);
                            break;
                        }
                    }
                    // kept on the walk too: the engine may catch the exception inside its own call (Forge AI code)
                    mismatch = "root decision differs: seat " + d.seat() + " actions " + d.actions().size()
                        + " (searched " + rootActions.size() + ")" + first;
                    throw new RootMismatch(mismatch);
                }
                node = root;
            } else {
                if (outOfTree || d.actions().isEmpty()) {
                    return ROLLOUT;
                }
                if (d.seat() != root.seat && !config.opponentInTree()) {
                    return ROLLOUT; // the switch: the other seat is answered by the rollout policy, the tree goes on
                }
                Edge last = path.get(path.size() - 1);
                String key = d.seat() + "|" + d.infoKey();
                node = last.next.get(key);
                if (node == null) {
                    // expansion: one new node per simulation, then the rollout policy plays on
                    last.next.put(key, new Node(d.seat(), d.continuation()));
                    nodes[0]++;
                    outOfTree = true;
                    return ROLLOUT;
                }
            }
            int pick = node == root && forcedRoot != null ? forced(node, d.actions())
                : config.gumbel() != null ? gumbelSelect(node, d.actions()) : select(node, d.actions());
            path.add(node.edges.get(d.actions().get(pick)));
            pathNodes.add(node);
            return pick;
        }

        /** The root action sequential halving scheduled (counted as available, as a selection would). */
        private int forced(Node node, List<String> actions) {
            int i = actions.indexOf(forcedRoot);
            for (String a : new java.util.LinkedHashSet<>(actions)) {
                Edge e = node.edges.computeIfAbsent(a, x -> new Edge(x, rng.nextLong()));
                e.available++;
            }
            return i;
        }

        /**
         * Gumbel below the root (opponent nodes too): argmax over the actions available in this sample of
         * pi'(a) - N(a) / (1 + sum_b N(b)), pi' = softmax(logits + sigma(q_hat)) over them (logits 0, the node's mean value
         * completing unvisited actions); ties by the simulation's seeded random. Availability is counted as with UCB1.
         */
        private int gumbelSelect(Node node, List<String> actions) {
            List<Integer> avail = new ArrayList<>(actions.size());
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int i = 0; i < actions.size(); i++) {
                String a = actions.get(i);
                node.edges.computeIfAbsent(a, x -> new Edge(x, rng.nextLong()));
                if (seen.add(a)) {
                    avail.add(i); // a key offered twice is one action
                }
            }
            int m = avail.size();
            long[] n = new long[m];
            double[] q = new double[m];
            long total = 0;
            double rewards = 0;
            for (int j = 0; j < m; j++) {
                Edge e = node.edges.get(actions.get(avail.get(j)));
                e.available++;
                n[j] = e.visits;
                q[j] = e.visits == 0 ? Double.NaN : e.reward / e.visits;
                total += e.visits;
                rewards += e.reward;
            }
            double v = total == 0 ? 0.5 : rewards / total;
            SearchConfig.Gumbel gm = config.gumbel();
            double[] pi = GumbelMath.improvedPolicy(null, n, q, v, null, gm.cVisit(), gm.cScale());
            List<Integer> top = new ArrayList<>();
            double best = Double.NEGATIVE_INFINITY;
            for (int j = 0; j < m; j++) {
                double score = pi[j] - (double) n[j] / (1 + total);
                if (score > best) {
                    best = score;
                    top.clear();
                    top.add(j);
                } else if (score == best) {
                    top.add(j);
                }
            }
            int j = top.size() == 1 ? top.get(0) : top.get(rng.nextInt(top.size()));
            return avail.get(j);
        }

        /** UCB1 with availability among the available actions (widened), unvisited ones first in random order. */
        private int select(Node node, List<String> actions) {
            List<Integer> avail = new ArrayList<>(actions.size());
            for (int i = 0; i < actions.size(); i++) {
                String a = actions.get(i);
                Edge e = node.edges.get(a);
                if (e == null) {
                    e = new Edge(a, rng.nextLong());
                    node.edges.put(a, e);
                }
                if (avail.stream().noneMatch(j -> actions.get(j).equals(a))) {
                    avail.add(i); // a key offered twice is one action
                }
            }
            for (int i : avail) {
                node.edges.get(actions.get(i)).available++;
            }
            List<Integer> allowed = widened(node, actions, avail);
            List<Integer> unvisited = new ArrayList<>();
            for (int i : allowed) {
                if (node.edges.get(actions.get(i)).visits == 0) {
                    unvisited.add(i);
                }
            }
            if (!unvisited.isEmpty()) {
                return unvisited.get(rng.nextInt(unvisited.size()));
            }
            List<Integer> best = new ArrayList<>();
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int i : allowed) {
                Edge e = node.edges.get(actions.get(i));
                double score = e.reward / e.visits + config.exploration() * Math.sqrt(Math.log(e.available) / e.visits);
                if (score > bestScore) {
                    bestScore = score;
                    best.clear();
                    best.add(i);
                } else if (score == bestScore) {
                    best.add(i);
                }
            }
            // exact ties: a pick drawn from the simulation's seeded random, never the first in candidate order (which
            // favoured candidate 0, pass at priority; 2026-11-28 user directive)
            return best.size() == 1 ? best.get(0) : best.get(rng.nextInt(best.size()));
        }

        private List<Integer> widened(Node node, List<String> actions, List<Integer> avail) {
            int min = config.wideningMin();
            if (min == 0 || avail.size() <= min) {
                return avail;
            }
            long k = Math.max(min, (long) Math.ceil(config.wideningC() * Math.pow(node.visits + 1, config.wideningAlpha())));
            if (k >= avail.size()) {
                return avail;
            }
            List<Integer> byRank = new ArrayList<>(avail);
            byRank.sort((x, y) -> Long.compare(node.edges.get(actions.get(x)).rank, node.edges.get(actions.get(y)).rank));
            List<Integer> out = new ArrayList<>(byRank.subList(0, (int) k));
            out.sort(Integer::compare);
            return out;
        }
    }
}
