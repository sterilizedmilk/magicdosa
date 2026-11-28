package magicdosa.agent.search;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The completed-Q formulas give the numbers of python/magicdosa/targets.py on the shared fixtures
 * python/tests/data/completed_q_fixtures.json (also checked by python tests/test_targets.py).
 */
class GumbelMathTest {

    private static Path fixtures() {
        return Path.of(System.getProperty("magicdosa.repo", "../.."), "python", "tests", "data", "completed_q_fixtures.json");
    }

    private static double[] doubles(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        JsonArray a = e.getAsJsonArray();
        double[] out = new double[a.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = a.get(i).isJsonNull() ? Double.NaN : a.get(i).getAsDouble();
        }
        return out;
    }

    @Test
    void javaMatchesPythonOnTheSharedFixtures() throws Exception {
        JsonObject root = JsonParser.parseString(Files.readString(fixtures(), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray cases = root.getAsJsonArray("cases");
        assertTrue(cases.size() >= 10, "fixture cases: " + cases.size());
        for (JsonElement c : cases) {
            JsonObject o = c.getAsJsonObject();
            double[] nd = doubles(o.get("n"));
            long[] n = new long[nd.length];
            for (int i = 0; i < n.length; i++) {
                n[i] = (long) nd[i];
            }
            double[] q = doubles(o.get("q"));
            double v = o.get("root_value").isJsonNull() ? Double.NaN : o.get("root_value").getAsDouble();
            double[] logits = doubles(o.get("prior_logits"));
            double[] expected = doubles(o.get("target"));
            double[] got = GumbelMath.improvedPolicy(logits, n, q, v, null, o.get("c_visit").getAsDouble(),
                o.get("c_scale").getAsDouble(), o.get("q_normalize").getAsBoolean());
            for (int i = 0; i < expected.length; i++) {
                assertEquals(expected[i], got[i], 1e-6, o.get("name").getAsString());
            }
        }
    }

    @Test
    void closeValuesAreStretchedAndEqualOnesStayZero() {
        double[] stretched = GumbelMath.normalized(new double[] {0.50, 0.51, 0.52}, null);
        assertEquals(0, stretched[0], 1e-6);
        assertEquals(0.5, stretched[1], 1e-6);
        assertEquals(1, stretched[2], 1e-6);
        double[] equal = GumbelMath.normalized(new double[] {0.5, 0.5, 0.5}, null);
        for (double x : equal) {
            assertEquals(0, x, 0, "no division by zero");
        }
    }

    @Test
    void unvisitedCandidatesTakeVMix() {
        // v 0.3, visited Q 0.7 (N 10) and 0.4 (N 2), uniform prior: Q_bar 0.55, v_mix (0.3 + 12 * 0.55) / 13
        double[] qHat = GumbelMath.completedQ(null, new long[] {10, 0, 2}, new double[] {0.7, Double.NaN, 0.4}, 0.3, null);
        assertEquals((0.3 + 12 * 0.55) / 13, qHat[1], 1e-12);
        assertEquals(0.7, qHat[0], 0);
        // no network value: the visit-weighted mean Q stands in for v
        double[] noNet = GumbelMath.completedQ(null, new long[] {5, 0, 1}, new double[] {0.6, Double.NaN, 0.2}, Double.NaN, null);
        double mean = (5 * 0.6 + 0.2) / 6;
        assertEquals((mean + 6 * 0.4) / 7, noNet[1], 1e-12);
    }
}
