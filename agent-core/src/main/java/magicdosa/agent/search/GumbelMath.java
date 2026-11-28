package magicdosa.agent.search;

/**
 * The completed-Q formulas of Gumbel search (2026-11-28 user directive "Gumbel v2"; Danihelka et al., "Policy improvement
 * by planning with Gumbel", ICLR 2022), the same definition as python/magicdosa/targets.py {@code completed_q}; the
 * shared fixtures python/tests/data/completed_q_fixtures.json are checked on both sides (GumbelMathTest, test_targets.py).
 * <pre>
 *   q_hat(a) = Q(a) for a visited candidate (N &gt; 0 and Q known), else v_mix
 *   v_mix    = (v + sum_b N(b) * Q_bar) / (1 + sum_b N(b))
 *              v = the network's value of the node, or the visit-weighted mean Q when there is none;
 *              Q_bar = the prior-weighted mean Q over the visited candidates (uniform prior: the plain mean)
 *   q_tilde  = (q_hat - min) / (max - min + 1e-8) over the candidates   (normalization, on by default)
 *   pi'      = softmax(logits + sigma(q_tilde)),  sigma(q) = (c_visit + max_b N(b)) * c_scale * q
 * </pre>
 * Q and v are the deciding seat's win probability in [0, 1]; the logits are all 0 without a prior (uniform).
 */
public final class GumbelMath {
    /** Defaults, as targets.py's C_VISIT, C_SCALE and EPSILON. */
    public static final double C_VISIT = 50, C_SCALE = 0.1, EPSILON = 1e-8;

    private GumbelMath() {
    }

    /** sigma(q) for a node whose most visited candidate has {@code maxN} visits. */
    public static double sigma(double q, long maxN, double cVisit, double cScale) {
        return (cVisit + maxN) * cScale * q;
    }

    private static boolean in(boolean[] mask, int i) {
        return mask == null || mask[i];
    }

    /**
     * q_hat for every candidate: Q where visited, else v_mix ({@code v}: the network's value, NaN when there is none).
     * Masked-out entries are set like the others (callers ignore them).
     */
    public static double[] completedQ(double[] logits, long[] n, double[] q, double v, boolean[] mask) {
        int k = n.length;
        double[] prior = prior(logits, mask, k);
        double pw = 0, pq = 0, nq = 0;
        long total = 0;
        boolean[] visited = new boolean[k];
        for (int i = 0; i < k; i++) {
            visited[i] = in(mask, i) && n[i] > 0 && !Double.isNaN(q[i]);
            if (visited[i]) {
                pw += prior[i];
                pq += prior[i] * q[i];
                nq += n[i] * q[i];
                total += n[i];
            }
        }
        double qBar = pw > 0 ? pq / pw : Double.NaN;
        double meanQ = total > 0 ? nq / total : Double.NaN;
        double value = Double.isNaN(v) ? meanQ : v;
        double vMix = total > 0 ? (value + total * (Double.isNaN(qBar) ? 0 : qBar)) / (1 + total) : value;
        if (Double.isNaN(vMix)) {
            vMix = 0.5; // no value at all: neutral
        }
        double[] out = new double[k];
        for (int i = 0; i < k; i++) {
            out[i] = visited[i] ? q[i] : vMix;
        }
        return out;
    }

    /** The min-max stretch of q_hat over the candidates in the mask: 0 for the least, about 1 for the greatest. */
    public static double[] normalized(double[] qHat, boolean[] mask) {
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < qHat.length; i++) {
            if (in(mask, i)) {
                lo = Math.min(lo, qHat[i]);
                hi = Math.max(hi, qHat[i]);
            }
        }
        double[] out = new double[qHat.length];
        for (int i = 0; i < qHat.length; i++) {
            out[i] = (qHat[i] - lo) / (hi - lo + EPSILON);
        }
        return out;
    }

    /** sigma(q_tilde) (or sigma(q_hat) without normalization) for every candidate. */
    public static double[] sigmas(double[] logits, long[] n, double[] q, double v, boolean[] mask, double cVisit,
                                  double cScale, boolean normalize) {
        double[] qHat = completedQ(logits, n, q, v, mask);
        if (normalize) {
            qHat = normalized(qHat, mask);
        }
        long maxN = 0;
        for (int i = 0; i < n.length; i++) {
            if (in(mask, i)) {
                maxN = Math.max(maxN, n[i]);
            }
        }
        double[] out = new double[n.length];
        for (int i = 0; i < n.length; i++) {
            out[i] = sigma(qHat[i], maxN, cVisit, cScale);
        }
        return out;
    }

    /** pi' over the candidates in {@code mask} (0 elsewhere); targets.py {@code completed_q} of one row. */
    public static double[] improvedPolicy(double[] logits, long[] n, double[] q, double v, boolean[] mask,
                                          double cVisit, double cScale, boolean normalize) {
        double[] s = sigmas(logits, n, q, v, mask, cVisit, cScale, normalize);
        double top = Double.NEGATIVE_INFINITY;
        double[] z = new double[n.length];
        for (int i = 0; i < n.length; i++) {
            if (in(mask, i)) {
                z[i] = logit(logits, i) + s[i];
                top = Math.max(top, z[i]);
            }
        }
        double sum = 0;
        double[] e = new double[n.length];
        for (int i = 0; i < n.length; i++) {
            if (in(mask, i)) {
                e[i] = Math.exp(z[i] - top);
                sum += e[i];
            }
        }
        for (int i = 0; i < n.length; i++) {
            e[i] = sum > 0 ? e[i] / sum : 0;
        }
        return e;
    }

    private static double logit(double[] logits, int i) {
        return logits == null || Double.isNaN(logits[i]) ? 0 : logits[i];
    }

    /** The prior up to a constant: exp(logit - max) over the mask (all 1 without logits). */
    private static double[] prior(double[] logits, boolean[] mask, int k) {
        double[] p = new double[k];
        double top = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < k; i++) {
            if (in(mask, i)) {
                top = Math.max(top, logit(logits, i));
            }
        }
        for (int i = 0; i < k; i++) {
            p[i] = in(mask, i) ? Math.exp(logit(logits, i) - top) : 0;
        }
        return p;
    }
}
