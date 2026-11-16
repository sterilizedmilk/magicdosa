package magicdosa.agent.shadow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression scenarios for shadow classes found in the phase-2 gates: a mid-game state written by hand and played by
 * Forge with a live shadow (verification 2) following seat 0 (see {@link Scenario}). Each scenario played the class
 * before its fix (the decisions it names did not match) and plays clean after it.
 */
class ShadowScenarioTest {

    private static void clean(ShadowGame.Result r) {
        String why = "samples: " + r.samples;
        assertEquals(0, r.identitySwapFailures, why);
        assertEquals(r.decisions, r.decisionsMatched, why);
        assertEquals(r.snapshots, r.snapshotsMatched, why);
        assertEquals(0, r.searchSkipped, why);
    }

    /** Both seats have Geier Reach Sanitarium ({2}, {T}: each player draws a card, then discards a card). */
    private static Scenario sanitarium(String name, int active, String[] hand, String... libraryTop) {
        Scenario s = new Scenario(name, 5, active);
        s.seat(0).deck("Island", 26).deck("Plains", 30).deck("Geier Reach Sanitarium", 4)
            .library("Island", "Plains", "Island", "Plains", "Island", "Plains")
            .hand("Plains")
            .battlefield("Geier Reach Sanitarium", "Island", "Island", "Plains");
        s.seat(1).deck("Island", 10).deck("Plains", 10).deck("Solitude", 4).deck("Prismatic Ending", 4)
            .deck("Wrath of the Skies", 4).deck("Day's Undoing", 4).deck("Orim's Chant", 4).deck("Teferi, Time Raveler", 4)
            .deck("Narset, Parter of Veils", 4).deck("Silence", 4).deck("Erode", 2).deck("Supreme Verdict", 4)
            .deck("Geier Reach Sanitarium", 2)
            .library(libraryTop)
            .hand(hand)
            .battlefield("Geier Reach Sanitarium", "Island", "Plains");
        return s;
    }

    /**
     * a7143fc: the other seat discards a hidden card it chooses inside an effect. Live, its choice is answered before
     * the record shows the card, so the effect holds the shadow's sampled object; the card's reveal must not swap
     * another object under the chosen ID while the effect holds it (before the fix: 1 or 2 decisions of each
     * scenario did not match).
     */
    @Test
    void discardOfAHiddenCardChosenInsideAnEffect() throws Exception {
        Scenario[] scenarios = {
            sanitarium("hold-1-2-0", 1, new String[] {"Silence", "Orim's Chant", "Day's Undoing"}, "Island", "Silence", "Plains"),
            sanitarium("hold-0-1-0", 0, new String[] {"Solitude", "Solitude", "Wrath of the Skies"}, "Island", "Silence", "Plains"),
            sanitarium("hold-1-0-0", 1, new String[] {"Solitude", "Prismatic Ending", "Wrath of the Skies"}, "Island", "Silence", "Plains"),
        };
        for (Scenario s : scenarios) {
            ShadowGame.Result r = s.play(4);
            assertTrue(s.events.stream().anyMatch(e -> e.hasResolutionChoice() && e.getResolutionChoice().getSeat() == 1
                && e.getResolutionChoice().getEnginePrompt().equals("chooseCardsToDiscardFrom")), s.name + ": no discard by seat 1");
            clean(r);
        }
    }
}
