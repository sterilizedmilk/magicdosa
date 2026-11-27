package magicdosa.agent.search;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * Tree reuse between the searches of one seat (2026-11-28 directive; {@code -Dmagicdosa.search.treeReuse}, off by
 * default): the next search starts from the subtree of the previous tree that holds the next real decision, found by the
 * real request's canonical information key ({@link CanonicalKey}) under the action the seat played. Modes:
 * <ul>
 *   <li>{@code false} (default): fresh trees, nothing computed for reuse.</li>
 *   <li>{@code measure}: fresh trees keyed as without reuse (the same searches); the canonical keys are kept beside them
 *       to count how often and with how many visits the next decision is in the previous tree.</li>
 *   <li>{@code true}: the tree is keyed canonically and the found subtree becomes the next root. Inherited visits count
 *       toward the budget ({@link IsMcts#search(SearchGame, long, Inherited)}).</li>
 * </ul>
 * The caller falls back to a fresh tree on anything that breaks the chain (no previous search, another turn, a shadow
 * recovery since, an answer of more than one candidate, no matching node).
 */
public final class TreeReuse {
    public enum Mode { OFF, MEASURE, ON }

    public static final Mode MODE = mode(System.getProperty("magicdosa.search.treeReuse", "false"));
    /** Diagnosis only: on a miss, print how the real request differs from the nearest tree node of the seat. */
    static final boolean DEBUG = Boolean.getBoolean("magicdosa.search.reuseDebug");
    private static int debugPrinted;

    /** Diagnosis ({@link #DEBUG}): the fewest field differences between {@code real} and the previous tree's nodes. */
    public static void explainMiss(Previous prev, magicdosa.protocol.v1.DecisionRequest real) {
        if (!DEBUG || debugPrinted >= 12 || !(prev.game() instanceof ForgeSearchGame)) {
            return;
        }
        com.google.protobuf.Message mine = CanonicalKey.message(real);
        java.util.List<magicdosa.agent.shadow.Fingerprint.Diff> best = null;
        for (com.google.protobuf.Message m : ((ForgeSearchGame) prev.game()).canonMessages.values()) {
            var d = magicdosa.agent.shadow.Fingerprint.diff(mine, m);
            if (best == null || d.size() < best.size()) {
                best = d;
            }
        }
        debugPrinted++;
        System.out.println("tree reuse debug: decision " + real.getDecisionId() + " nodes " + ((ForgeSearchGame) prev.game()).canonMessages.size()
            + " nearest " + (best == null ? "none" : best.size() + " diffs " + best.stream().limit(8).toList()));
    }

    static Mode mode(String s) {
        return switch (s) {
            case "false", "off", "" -> Mode.OFF;
            case "measure" -> Mode.MEASURE;
            case "true", "on" -> Mode.ON;
            default -> throw new IllegalArgumentException("magicdosa.search.treeReuse: false, measure or true, not " + s);
        };
    }

    private TreeReuse() {
    }

    /** A search's canonical names of its tree's keys and actions ({@link ForgeSearchGame}). */
    public interface Names {
        /** The canonical key of a tree node's information key; null when unknown. */
        String canonKey(String info);

        /** The canonical name of an action at a tree node; null when unknown. */
        String canonAction(String info, String action);

        /** The canonical name of a root action of this search. */
        String rootCanonAction(String rootAction);
    }

    /** The previous search of a seat: its tree, its names and the root action played. */
    public record Previous(IsMcts.Tree tree, Names game, int seat, String played) {
    }

    /** The node of the next decision in a previous tree: its information key, visits and depth below the played edge. */
    public record Found(IsMcts.Node node, String info, long visits, int depth, int matches) {
    }

    /**
     * The most visited node of {@code seat} below the played root action whose canonical key is {@code canonKey}; null
     * when there is none. A multi-select root answered with one candidate goes on through its "done" steps.
     */
    public static Found find(Previous prev, String canonKey) {
        if (canonKey == null) {
            return null;
        }
        IsMcts.Edge played = prev.tree().root.edges.get(prev.played());
        if (played == null) {
            return null;
        }
        Deque<Object[]> queue = new ArrayDeque<>();
        boolean continued = false;
        for (Map.Entry<String, IsMcts.Node> e : played.next.entrySet()) {
            IsMcts.Node n = e.getValue();
            if (n.continuation && n.seat == prev.seat()) {
                continued = true;
                IsMcts.Edge done = n.edges.get(ForgeSearchGame.DONE);
                if (done != null) {
                    done.next.forEach((k, v) -> queue.add(new Object[] {k, v, 1}));
                }
            }
        }
        if (!continued) {
            played.next.forEach((k, v) -> queue.add(new Object[] {k, v, 1}));
        }
        Found best = null;
        int matches = 0;
        java.util.Set<IsMcts.Node> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        while (!queue.isEmpty()) {
            Object[] q = queue.poll();
            String key = (String) q[0];
            IsMcts.Node n = (IsMcts.Node) q[1];
            int depth = (Integer) q[2];
            if (!seen.add(n)) {
                continue;
            }
            if (!n.continuation && n.seat == prev.seat()) {
                String info = key.substring(key.indexOf('|') + 1);
                if (canonKey.equals(prev.game().canonKey(info))) {
                    matches++;
                    if (best == null || n.visits > best.visits()) {
                        best = new Found(n, info, n.visits, depth, 0);
                    }
                }
            }
            for (IsMcts.Edge e : n.edges.values()) {
                e.next.forEach((k, v) -> queue.add(new Object[] {k, v, depth + 1}));
            }
        }
        return best == null ? null : new Found(best.node(), best.info(), best.visits(), best.depth(), matches);
    }

    /** The found node as the next search's root, its edges renamed to the next root's actions. */
    public static Inherited inherit(Previous prev, Found found, Names next) {
        return new Inherited(prev, found, next);
    }

    /** A root to start from, built when the search knows its fresh root. */
    public static final class Inherited {
        private final Previous prev;
        private final Found found;
        private final Names next;
        private long nodes;
        private int edgesTaken;

        Inherited(Previous prev, Found found, Names next) {
            this.prev = prev;
            this.found = found;
            this.next = next;
        }

        /**
         * {@code fresh}'s edges with the statistics and subtrees of the found node's edges of the same canonical name
         * (continuation nodes, keyed by the old request's candidate indices, are left out); the rest stay fresh.
         */
        IsMcts.Node root(IsMcts.Node fresh) {
            Map<String, IsMcts.Edge> byCanon = new java.util.HashMap<>();
            for (IsMcts.Edge e : found.node().edges.values()) {
                String c = prev.game().canonAction(found.info(), e.action);
                if (c != null) {
                    byCanon.putIfAbsent(c, e);
                }
            }
            IsMcts.Node root = new IsMcts.Node(fresh.seat);
            for (IsMcts.Edge f : fresh.edges.values()) {
                String c = next.rootCanonAction(f.action);
                IsMcts.Edge old = c == null ? null : byCanon.remove(c);
                IsMcts.Edge e = new IsMcts.Edge(f.action, f.rank);
                if (old != null) {
                    edgesTaken++;
                    e.visits = old.visits;
                    e.available = old.available;
                    e.reward = old.reward;
                    for (Map.Entry<String, IsMcts.Node> k : old.next.entrySet()) {
                        if (!k.getValue().continuation) {
                            e.next.put(k.getKey(), k.getValue());
                            nodes += count(k.getValue());
                        }
                    }
                    root.visits += old.visits;
                }
                root.edges.put(f.action, e);
            }
            return root;
        }

        /** Tree nodes taken over below the root. */
        public long nodes() {
            return nodes;
        }

        /** Root edges that took over statistics. */
        public int edgesTaken() {
            return edgesTaken;
        }

        public Found found() {
            return found;
        }

        private static long count(IsMcts.Node n) {
            long c = 1;
            for (IsMcts.Edge e : n.edges.values()) {
                for (IsMcts.Node m : e.next.values()) {
                    c += count(m);
                }
            }
            return c;
        }
    }
}
