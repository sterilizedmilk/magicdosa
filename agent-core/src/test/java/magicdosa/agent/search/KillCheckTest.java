package magicdosa.agent.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The confirmed-kill checker's optimisation: the defender's best blocks as a maximum-weight matching. */
class KillCheckTest {

    @Test
    void bestBlocksKeepTheMostDamageOff() {
        // blockers (rows) x attackers (cols): what each block keeps off the player (0: cannot block)
        double[][] w = {
            {3, 2, 0},
            {3, 0, 0},
        };
        // blocker 1 can only stop the 3-power attacker, so blocker 0 takes the 2-power one: 3 + 2 kept
        assertEquals(5, KillCheck.maxWeightMatching(w, 2, 3), 1e-9);
    }

    @Test
    void moreBlockersThanAttackersAndNone() {
        double[][] w = {{1}, {4}, {2}};
        assertEquals(4, KillCheck.maxWeightMatching(w, 3, 1), 1e-9);
        assertEquals(0, KillCheck.maxWeightMatching(new double[0][0], 0, 0), 1e-9);
    }

    @Test
    void largerBoardsAgreeWithBruteForce() {
        java.util.SplittableRandom r = new java.util.SplittableRandom(3);
        for (int t = 0; t < 200; t++) {
            int rows = 1 + r.nextInt(6);
            int cols = 1 + r.nextInt(6);
            double[][] w = new double[rows][cols];
            for (int i = 0; i < rows; i++) {
                for (int j = 0; j < cols; j++) {
                    w[i][j] = r.nextInt(4) == 0 ? 0 : r.nextInt(7);
                }
            }
            assertEquals(brute(w, 0, new boolean[cols]), KillCheck.maxWeightMatching(w, rows, cols), 1e-9);
        }
    }

    private static double brute(double[][] w, int row, boolean[] used) {
        if (row == w.length) {
            return 0;
        }
        double best = brute(w, row + 1, used); // this blocker does not block
        for (int j = 0; j < used.length; j++) {
            if (!used[j] && w[row][j] > 0) {
                used[j] = true;
                best = Math.max(best, w[row][j] + brute(w, row + 1, used));
                used[j] = false;
            }
        }
        return best;
    }
}
