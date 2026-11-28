package magicdosa.agent.search;

/**
 * The completed-Q formulas of Gumbel search (2026-11-28 user directive "Gumbel v2"; Danihelka et al., "Policy improvement
 * by planning with Gumbel", ICLR 2022), the same definition as python/magicdosa/targets.py {@code completed_q}; the
 * shared fixture {@code completed_q_fixture.txt} (agent-core test resources) checks both sides give the same numbers.
 * <pre>
 *   sigma(q)  = (c_visit + max_b N(b)) * c_scale * q
 *   q_hat(a)  = Q(a) when a was visited, else the value estimate v (completed Q)
 *   pi'(a)    = softmax(logits(a) + sigma(q_hat(a)))   over the candidates in the mask
 * </pre>
 * Q and v are the deciding seat's win probability in [0, 1]; the logits are all 0 without a prior (uniform).
 */
public final class GumbelMath {
    /** Defaults, as targets.py's C_VISIT and C_SCALE. */
    public static final double C_VISIT = 50, C_SCALE = 0.1;

    private GumbelMath() {
    }

    /** sigma(q) for a node whose most visited candidate has {@code maxN} visits. */
    public static double sigma(double q, long maxN, double cVisit, double cScale) {
        return (cVisit + maxN) * cScale * q;
    }

    /** q_hat: Q where visited (N &gt; 0 and Q known), else {@code v}. */
    public static double[] completedQ(long[] n, double[] q, double v) {
        double[] out = new double[n.length];
        for (int i = 0; i < n.length; i++) {
            out[i] = n[i] > 0 && !Double.isNaN(q[i]) ? q[i] : v;
        }
        return out;
    }

    /** The largest N among the candidates in the mask (0 when none). */
    public static long maxVisits(long[] n, boolean[] mask) {
        long m = 0;
        for (int i = 0; i < n.length; i++) {
            if (mask == null || mask[i]) {
                m = Math.max(m, n[i]);
            }
        }
        return m;
    }

    /**
     * pi' over the candidates in {@code mask} (0 elsewhere); {@code logits} null or NaN entries count as 0. The
     * targets.py {@code completed_q} of one row.
     */
    public static double[] improvedPolicy(double[] logits, long[] n, double[] q, double v, boolean[] mask,
                                          double cVisit, double cScale) {
        double[] qHat = completedQ(n, q, v);
        long maxN = maxVisits(n, mask);
        double[] z = new double[n.length];
        double top = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n.length; i++) {
            if (mask != null && !mask[i]) {
                continue;
            }
            double l = logits == null || Double.isNaN(logits[i]) ? 0 : logits[i];
            z[i] = l + sigma(qHat[i], maxN, cVisit, cScale);
            top = Math.max(top, z[i]);
        }
        double sum = 0;
        double[] e = new double[n.length];
        for (int i = 0; i < n.length; i++) {
            if (mask == null || mask[i]) {
                e[i] = Math.exp(z[i] - top);
                sum += e[i];
            }
        }
        for (int i = 0; i < n.length; i++) {
            e[i] = sum > 0 ? e[i] / sum : 0;
        }
        return e;
    }
}
