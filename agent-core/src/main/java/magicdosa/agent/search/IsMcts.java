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

    public SearchResult search(SearchGame game, long seed) {
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
        for (long it = 0; it < config.budget(); it++) {
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
            Walk walk = new Walk(root, rootActions, simRng, nodes);
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
        return out.build(visits, root.visits == 0 ? Double.NaN : reward / root.visits, steps(root));
    }

    /**
     * The root decision's steps as the search answers them: at each step the most visited action (the first of
     * equals), then the most visited continuation node under it, until "done" or no further step was searched.
     */
    private static List<SearchResult.Step> steps(Node root) {
        List<SearchResult.Step> out = new ArrayList<>();
        Node node = root;
        while (node != null && node.visits > 0) {
            List<String> actions = new ArrayList<>();
            long[] v = new long[node.edges.size()];
            int best = -1;
            int i = 0;
            for (Edge e : node.edges.values()) {
                actions.add(e.action);
                v[i] = e.visits;
                if (e.visits > 0 && (best < 0 || e.visits > v[best])) {
                    best = i;
                }
                i++;
            }
            if (best < 0) {
                break;
            }
            out.add(new SearchResult.Step(actions, v, best));
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

        Walk(Node root, List<String> rootActions, SplittableRandom rng, long[] nodes) {
            this.root = root;
            this.rootActions = rootActions;
            this.rng = rng;
            this.nodes = nodes;
        }

        @Override
        public int choose(SearchGame.Decision d) {
            Node node;
            if (!started) {
                started = true;
                if (d.seat() != root.seat || !d.actions().equals(rootActions)) {
                    throw new RootMismatch("root decision differs: seat " + d.seat() + " actions " + d.actions().size()
                        + " (searched " + rootActions.size() + ")");
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
            int pick = select(node, d.actions());
            path.add(node.edges.get(d.actions().get(pick)));
            pathNodes.add(node);
            return pick;
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
            int best = -1;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int i : allowed) {
                Edge e = node.edges.get(actions.get(i));
                double score = e.reward / e.visits + config.exploration() * Math.sqrt(Math.log(e.available) / e.visits);
                if (score > bestScore) { // ties: the first in candidate order
                    bestScore = score;
                    best = i;
                }
            }
            return best;
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
