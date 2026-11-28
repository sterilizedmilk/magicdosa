package magicdosa.agent.search;

import magicdosa.protocol.v1.Method;
import magicdosa.protocol.v1.Prior;
import magicdosa.protocol.v1.SearchRun;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The outcome of one search: visits per root candidate (the ExIt policy target, logged from the start because it exists
 * only at play time) and what the search spent.
 */
public final class SearchResult {
    public final SearchConfig config;
    public final long seed;
    /** Simulations this root got (fixed, or its actions x per candidate x multiplier). */
    public final long budget;
    /** Simulations started (= the budget). */
    public final long iterations;
    /** Simulations that were played and backed up. */
    public final long determinizations;
    public final long noDeterminization;
    public final long rootMismatches;
    public final long failures;
    /** Tree nodes, the root included. */
    public final long nodes;
    /** Tree reuse: root visits and nodes taken over from the previous search's tree (0 for a fresh tree). */
    public final long inheritedVisits;
    public final long inheritedNodes;
    /** Tree reuse was on for this search (its root may still be fresh: nothing to inherit). */
    public final boolean treeReuse;
    /** Value of the root seat's position, the visit-weighted mean reward; NaN when nothing was backed up. */
    public final double rootValue;
    private final long[] visits;
    /** First few reasons by kind (diagnostics). */
    public final Map<String, Integer> problems;
    /** The root decision's steps, the first one over the root candidates (one step for a single pick). */
    public final List<Step> steps;

    /** How the chosen action was told apart from others with as many visits. */
    public enum Tie {
        /** The chosen action has the most visits alone. */
        NONE,
        /** Equal visits; the higher mean value decided. */
        MEAN_VALUE,
        /** Equal visits and equal mean value; a pick drawn from the search's seed decided. */
        SEED,
        /**
         * Data runs only ({@code -Dmagicdosa.search.sampleTurns}): the played action was drawn in proportion to the visits
         * from the search's seed; the visit ratios are the search's as always.
         */
        SAMPLED
    }

    /** An action chosen at a step and how a tie was broken. */
    public record Choice(int index, Tie tie) {
    }

    /**
     * One step of the answer: the actions seen at the step's node, their visits and mean values, the chosen one (most
     * visits, then the higher mean value, then candidate order) and how a tie was broken.
     */
    public record Step(List<String> actions, long[] visits, double[] mean, int chosen, Tie tie) {
        public List<Float> visitRatios() {
            long total = 0;
            for (long v : visits) {
                total += v;
            }
            List<Float> out = new ArrayList<>(visits.length);
            for (long v : visits) {
                out.add(total == 0 ? 0f : (float) ((double) v / total));
            }
            return out;
        }
    }

    private SearchResult(Builder b, long[] visits, double rootValue, List<Step> steps) {
        this.steps = List.copyOf(steps);
        this.config = b.config;
        this.budget = b.budget;
        this.seed = b.seed;
        this.iterations = b.iterations;
        this.determinizations = b.determinizations;
        this.noDeterminization = b.noDeterminization;
        this.rootMismatches = b.rootMismatches;
        this.failures = b.failures;
        this.nodes = b.nodes;
        this.inheritedVisits = b.inheritedVisits;
        this.inheritedNodes = b.inheritedNodes;
        this.treeReuse = TreeReuse.MODE == TreeReuse.Mode.ON;
        this.rootValue = rootValue;
        this.visits = visits.clone();
        this.problems = Collections.unmodifiableMap(new TreeMap<>(b.problems));
    }

    public int candidates() {
        return visits.length;
    }

    public long visits(int candidate) {
        return visits[candidate];
    }

    /** The root position's value estimate for the SearchRun record; NaN: the root mean ({@link #rootValue}). */
    private double rootEstimate = Double.NaN;

    /** Records the leaf network's value of the root request as the run's root estimate (protocol 17). */
    public SearchResult withRootEstimate(double v) {
        this.rootEstimate = v;
        return this;
    }

    /** The root estimate recorded: the network's value of the root when set, else the root mean (0 when NaN). */
    public double rootEstimate() {
        double v = Double.isNaN(rootEstimate) ? rootValue : rootEstimate;
        return Double.isNaN(v) ? 0 : v;
    }

    /**
     * Per-candidate statistics of a step (protocol 17): N, Q (the root seat's mean win probability; unset when N = 0) and
     * no prior (the search's prior is uniform).
     */
    public static List<magicdosa.protocol.v1.CandidateStats> candidateStats(Step s) {
        List<magicdosa.protocol.v1.CandidateStats> out = new ArrayList<>();
        for (int i = 0; i < s.visits().length; i++) {
            magicdosa.protocol.v1.CandidateStats.Builder b = magicdosa.protocol.v1.CandidateStats.newBuilder()
                .setVisits(s.visits()[i]);
            if (s.visits()[i] > 0) {
                b.setMeanValue((float) s.mean()[i]);
            }
            out.add(b.build());
        }
        return out;
    }

    /** Whether any simulation was backed up (else the search did not decide anything). */
    public boolean searched() {
        return determinizations > 0;
    }

    /** Visits / all root visits, parallel to the root candidates; all zero when nothing was backed up. */
    public List<Float> visitRatios() {
        long total = 0;
        for (long v : visits) {
            total += v;
        }
        List<Float> out = new ArrayList<>(visits.length);
        for (long v : visits) {
            out.add(total == 0 ? 0f : (float) ((double) v / total));
        }
        return out;
    }

    /**
     * The root's chosen candidate (most visits, then the higher mean value, then candidate order); -1 when nothing was
     * backed up.
     */
    public int chosen() {
        return steps.isEmpty() ? -1 : steps.get(0).chosen();
    }

    /** How the root's choice was told apart from equally visited candidates. */
    public Tie rootTie() {
        return steps.isEmpty() ? Tie.NONE : steps.get(0).tie();
    }

    /** The decision step's method: uniform prior, this search run. */
    public Method method() {
        return Method.newBuilder().setPrior(Prior.PRIOR_UNIFORM).setRan(SearchRun.newBuilder()
            .setNodes(nodes).setDeterminizations((int) Math.min(Integer.MAX_VALUE, determinizations))
            .setRootValue(Double.isNaN(rootValue) ? 0f : (float) rootValue)
            .setBudget(budget).setSeed(seed).setSimulations(iterations)
            .setFailed(noDeterminization + rootMismatches + failures).setRollout(config.rollout())
            .setHorizon(config.horizon()).setLeafEvaluation(recordedLeafEvaluation(config))
            .setLeafModel(magicdosa.agent.model.LeafNetwork.fileName())
            .setLeafModelSha256(magicdosa.agent.model.LeafNetwork.sha256())
            .setRootEstimate((float) rootEstimate())
            .setTreeReuse(treeReuse).setInheritedVisits(inheritedVisits)).build();
    }

    /**
     * How the run's leaves were valued: by the leaf network whenever one is loaded (protocol 16: at the tree exit and at
     * the horizon), else by Forge's score as the settings say.
     */
    public static magicdosa.protocol.v1.LeafEvaluation recordedLeafEvaluation(SearchConfig config) {
        return magicdosa.agent.model.LeafNetwork.fileName().isEmpty() ? config.leafEvaluation()
            : magicdosa.protocol.v1.LeafEvaluation.LEAF_EVALUATION_NETWORK;
    }

    @Override
    public String toString() {
        return "search budget=" + budget + " (" + config.describe() + ") seed=" + seed + " backedUp=" + determinizations + " noSample="
            + noDeterminization + " rootMismatch=" + rootMismatches + " failures=" + failures + " nodes=" + nodes
            + (inheritedVisits > 0 || inheritedNodes > 0 ? " inherited=" + inheritedVisits + "/" + inheritedNodes : "")
            + " value=" + rootValue + " visits=" + java.util.Arrays.toString(visits) + " tie=" + rootTie() + (problems.isEmpty() ? "" : " " + problems);
    }

    static final class Builder {
        final SearchConfig config;
        final long seed;
        long budget, iterations, determinizations, noDeterminization, rootMismatches, failures, nodes;
        long inheritedVisits, inheritedNodes;
        final Map<String, Integer> problems = new TreeMap<>();

        Builder(SearchConfig config, long seed, int candidates) {
            this.config = config;
            this.seed = seed;
        }

        void rootMismatch(String why) {
            rootMismatches++;
            note(why);
        }

        void failure(String why) {
            failures++;
            note(why);
        }

        private void note(String why) {
            if (problems.size() < 8 || problems.containsKey(why)) {
                problems.merge(why, 1, Integer::sum);
            }
        }

        SearchResult build(long[] visits, double rootValue, List<Step> steps) {
            return new SearchResult(this, visits, rootValue, steps);
        }
    }
}
