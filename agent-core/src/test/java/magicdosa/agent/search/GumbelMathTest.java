package magicdosa.agent.search;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The completed-Q formulas give the numbers of python/magicdosa/targets.py on the shared fixture
 * (test resource completed_q_fixture.txt, also read by python/tests/test_targets_fixture.py).
 */
class GumbelMathTest {

    private static double[] doubles(String s) {
        String[] p = s.trim().split(",");
        double[] out = new double[p.length];
        for (int i = 0; i < p.length; i++) {
            out[i] = p[i].trim().equals("nan") ? Double.NaN : Double.parseDouble(p[i].trim());
        }
        return out;
    }

    @Test
    void javaMatchesPythonOnTheSharedFixture() throws Exception {
        List<String> rows = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                GumbelMathTest.class.getResourceAsStream("/completed_q_fixture.txt"), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    rows.add(line);
                }
            }
        }
        assertTrue(rows.size() >= 5);
        for (String row : rows) {
            String[] f = row.split("\\|");
            double[] nd = doubles(f[0]);
            long[] n = new long[nd.length];
            for (int i = 0; i < n.length; i++) {
                n[i] = (long) nd[i];
            }
            double[] q = doubles(f[1]);
            double v = Double.parseDouble(f[2].trim());
            double[] md = doubles(f[3]);
            boolean[] mask = new boolean[md.length];
            for (int i = 0; i < mask.length; i++) {
                mask[i] = md[i] != 0;
            }
            double[] logits = f[4].trim().equals("none") ? null : doubles(f[4]);
            double[] expected = doubles(f[5]);
            double[] got = GumbelMath.improvedPolicy(logits, n, q, v, mask, GumbelMath.C_VISIT, GumbelMath.C_SCALE);
            for (int i = 0; i < expected.length; i++) {
                assertEquals(expected[i], got[i], 2e-6, row);
            }
        }
    }

    @Test
    void sigmaScalesWithTheMostVisitedCandidate() {
        assertEquals((50 + 30) * 0.1 * 0.5, GumbelMath.sigma(0.5, 30, 50, 0.1), 1e-12);
        double[] qHat = GumbelMath.completedQ(new long[] {3, 0}, new double[] {0.9, Double.NaN}, 0.4);
        assertEquals(0.9, qHat[0], 0);
        assertEquals(0.4, qHat[1], 0);
    }
}
