package magicdosa.agent.search;

import forge.ai.ComputerUtilCombat;
import forge.game.card.Card;
import forge.game.combat.CombatUtil;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Confirmed-kill checker (3단계 기획 쟁점 7, 2026-11-16; directive 2026-11-24): measures combat judgement errors, never
 * changes a decision.
 * <ol>
 *   <li>Reads from Forge, without a copy: each attacker's combat damage, which creature can block which attacker
 *       (Forge's own block legality: flying, reach, protection, ...), the damage that kills a blocker (deathtouch
 *       counted), trample, double strike, and the factors the calculation leaves out.</li>
 *   <li>Optimises only: the defender's best blocks are a maximum-weight matching (a blocker stops one attacker; the
 *       weight is the damage it keeps off the player, trample's excess not), solved by the Hungarian method. No rule is
 *       implemented here.</li>
 *   <li>Factors outside the calculation (minimum-blocker rules such as menace, combat triggers, a double striker with
 *       trample) mark the result approximate. Checking those cases on a copy is not done yet.</li>
 * </ol>
 * "Confirmed" is by public information: tricks in hidden hands are not counted. More attackers never lower the least
 * damage the defender can take, so a kill is available exactly when attacking with every possible attacker is lethal.
 */
public final class KillCheck {
    private KillCheck() {
    }

    /** The least and most damage the defender takes from these attackers, and the factors left out. */
    public record Outcome(int minDamage, int maxDamage, Set<String> factors, Map<Card, Card> bestBlocks) {
        public boolean lethal(int life) {
            return minDamage >= life;
        }

        public boolean approximate() {
            return !factors.isEmpty();
        }
    }

    private static final Set<TriggerType> COMBAT_TRIGGERS = Set.of(TriggerType.Attacks, TriggerType.AttackerBlocked,
        TriggerType.AttackerBlockedByCreature, TriggerType.AttackerUnblocked, TriggerType.Blocks, TriggerType.AttackersDeclared,
        TriggerType.BlockersDeclared, TriggerType.DamageDone, TriggerType.DamageDoneOnce);

    /** Combat damage the attacker deals to a player when unblocked (double strike: twice). */
    static int damage(Card attacker) {
        int d = Math.max(0, attacker.getNetCombatDamage());
        return attacker.hasDoubleStrike() ? 2 * d : d;
    }

    /** The defender's creatures that can block now. */
    public static List<Card> blockers(Player defender) {
        List<Card> out = new ArrayList<>();
        for (Card c : defender.getCardsIn(ZoneType.Battlefield)) {
            if (c.isCreature() && CombatUtil.canBlock(c)) {
                out.add(c);
            }
        }
        return out;
    }

    /** The defender's best blocks against {@code attackers}. */
    public static Outcome evaluate(List<Card> attackers, Player defender) {
        return evaluate(attackers, blockers(defender), defender);
    }

    public static Outcome evaluate(List<Card> attackers, List<Card> blockers, Player defender) {
        Set<String> factors = new TreeSet<>();
        int total = 0;
        int n = attackers.size();
        int m = blockers.size();
        double[][] w = new double[m][n];
        for (int a = 0; a < n; a++) {
            Card at = attackers.get(a);
            int d = damage(at);
            total += d;
            if (CombatUtil.getMinNumBlockersForAttacker(at, defender) > 1) {
                factors.add("needs several blockers: " + at.getName()); // counted as one blocker stopping it
            }
            if (at.hasDoubleStrike() && at.hasKeyword(Keyword.TRAMPLE)) {
                factors.add("double strike with trample: " + at.getName());
            }
            combatTriggers(at, factors);
            for (int b = 0; b < m; b++) {
                Card bl = blockers.get(b);
                if (!CombatUtil.canBlock(at, bl)) {
                    continue;
                }
                int kept = d;
                if (at.hasKeyword(Keyword.TRAMPLE)) {
                    kept = Math.min(d, ComputerUtilCombat.getEnoughDamageToKill(bl, d, at, true));
                }
                w[b][a] = kept;
            }
        }
        for (Card bl : blockers) {
            combatTriggers(bl, factors);
        }
        int kept = (int) Math.round(maxWeightMatching(w, m, n));
        // the defender's blocks that keep that damage off (attacker -> blocker), for checking the number on a copy
        Map<Card, Card> best = new java.util.LinkedHashMap<>();
        int[] rowOf = assignment(w, m, n);
        for (int a = 0; a < n; a++) {
            if (rowOf[a] >= 0 && w[rowOf[a]][a] > 0) {
                best.put(attackers.get(a), blockers.get(rowOf[a]));
            }
        }
        return new Outcome(total - kept, total, factors, best);
    }

    /** Damage the defender takes with these blocks (attacker -> its blocker; unblocked attackers are absent). */
    public static int damageWith(List<Card> attackers, Map<Card, Card> blockOf) {
        int out = 0;
        for (Card at : attackers) {
            int d = damage(at);
            Card bl = blockOf.get(at);
            if (bl == null) {
                out += d;
            } else if (at.hasKeyword(Keyword.TRAMPLE)) {
                out += d - Math.min(d, ComputerUtilCombat.getEnoughDamageToKill(bl, d, at, true));
            }
        }
        return out;
    }

    private static void combatTriggers(Card c, Set<String> factors) {
        for (Trigger t : c.getTriggers()) {
            if (COMBAT_TRIGGERS.contains(t.getMode())) {
                factors.add("combat trigger: " + c.getName());
                return;
            }
        }
    }

    /**
     * Maximum total weight of a matching between {@code rows} and {@code cols} (Hungarian method on the square matrix
     * padded with zeros; weights are not negative).
     */
    static double maxWeightMatching(double[][] w, int rows, int cols) {
        int[] rowOf = assignment(w, rows, cols);
        double total = 0;
        for (int j = 0; j < cols; j++) {
            if (rowOf[j] >= 0) {
                total += w[rowOf[j]][j];
            }
        }
        return total;
    }

    /** For each column, the row a maximum-weight matching gives it, or -1 (Hungarian method, square padding). */
    static int[] assignment(double[][] w, int rows, int cols) {
        int size = Math.max(rows, cols);
        int[] rowOf = new int[cols];
        java.util.Arrays.fill(rowOf, -1);
        if (size == 0) {
            return rowOf;
        }
        double max = 0;
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                max = Math.max(max, w[i][j]);
            }
        }
        double[][] cost = new double[size + 1][size + 1]; // minimise max - weight
        for (int i = 1; i <= size; i++) {
            for (int j = 1; j <= size; j++) {
                double wij = i <= rows && j <= cols ? w[i - 1][j - 1] : 0;
                cost[i][j] = max - wij;
            }
        }
        double[] u = new double[size + 1];
        double[] v = new double[size + 1];
        int[] p = new int[size + 1];
        int[] way = new int[size + 1];
        for (int i = 1; i <= size; i++) {
            p[0] = i;
            int j0 = 0;
            double[] minv = new double[size + 1];
            boolean[] used = new boolean[size + 1];
            java.util.Arrays.fill(minv, Double.POSITIVE_INFINITY);
            do {
                used[j0] = true;
                int i0 = p[j0];
                double delta = Double.POSITIVE_INFINITY;
                int j1 = 0;
                for (int j = 1; j <= size; j++) {
                    if (!used[j]) {
                        double cur = cost[i0][j] - u[i0] - v[j];
                        if (cur < minv[j]) {
                            minv[j] = cur;
                            way[j] = j0;
                        }
                        if (minv[j] < delta) {
                            delta = minv[j];
                            j1 = j;
                        }
                    }
                }
                for (int j = 0; j <= size; j++) {
                    if (used[j]) {
                        u[p[j]] += delta;
                        v[j] -= delta;
                    } else {
                        minv[j] -= delta;
                    }
                }
                j0 = j1;
            } while (p[j0] != 0);
            do {
                int j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
            } while (j0 != 0);
        }
        for (int j = 1; j <= size; j++) {
            int i = p[j];
            if (i >= 1 && i <= rows && j <= cols) {
                rowOf[j - 1] = i - 1;
            }
        }
        return rowOf;
    }
}
