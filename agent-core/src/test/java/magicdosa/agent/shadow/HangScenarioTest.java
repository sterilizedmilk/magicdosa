package magicdosa.agent.shadow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hang safeguard (2026-11-24 directive): the shadow thread is made to spin at a live decision; the safeguard
 * answers that decision as search skipped (cause shadow stop), takes a thread dump, ends the stuck thread and starts
 * the shadow again from the record, which then follows the rest of the game.
 * <p>
 * The safeguard's wall-clock limit is set to {@link #HANG_MS}: long enough that an ordinary decision cannot reach it
 * on a loaded machine (the restarted shadow replays the record up to its next decision; with 15 JVMs running, a 3 s
 * limit was crossed there once, 2026-11-28), short enough that the forced hang ends the test in reasonable time. The
 * forced hang spins forever, so any limit catches it.
 */
class HangScenarioTest {
    private static final String HANG_MS = "20000";

    @Test
    void aHungShadowIsEndedAndStartedAgain() throws Exception {
        Scenario s = new Scenario("hang-0", 5, 0);
        s.seat(0).deck("Mountain", 30).deck("Lightning Bolt", 15).deck("Monastery Swiftspear", 15)
            .library("Mountain", "Lightning Bolt", "Mountain", "Monastery Swiftspear", "Mountain", "Mountain")
            .hand("Lightning Bolt", "Monastery Swiftspear", "Mountain")
            .battlefield("Mountain", "Mountain");
        s.seat(1).deck("Forest", 40).deck("Ancient Stirrings", 20)
            .library("Forest", "Forest", "Forest", "Forest")
            .hand("Ancient Stirrings", "Forest")
            .battlefield("Forest", "Forest");
        String before = System.getProperty("magicdosa.shadowHangMs");
        long at = ShadowGame.HANG_TEST_AT;
        System.setProperty("magicdosa.shadowHangMs", HANG_MS);
        ShadowGame.HANG_TEST_AT = 4;
        ShadowGame.HANG_TEST_FIRED.set(false);
        ShadowGame.Result r;
        try {
            r = s.play(2);
        } finally {
            ShadowGame.HANG_TEST_AT = at;
            if (before == null) {
                System.clearProperty("magicdosa.shadowHangMs");
            } else {
                System.setProperty("magicdosa.shadowHangMs", before);
            }
        }
        LiveShadow.Stats st = s.stats;
        String why = st.line() + " samples: " + (r == null ? "none" : r.samples);
        System.out.println("hang scenario: " + why);
        assertTrue(ShadowGame.HANG_TEST_FIRED.get(), why);
        assertEquals(1, st.hangs, why);
        assertTrue(why.contains("hang at decision 4"), "the one hang is the forced one: " + why);
        assertEquals(1, st.hangRestarts, why);
        assertEquals(0, st.hangTainted, why);
        assertFalse(LiveShadow.tainted(), why);
        assertTrue(st.withoutShadow.getOrDefault("search skipped: shadow stop: hung", 0) == 1, why);
        // the restarted shadow follows the rest of the game
        assertTrue(r != null && r.decisions > 0, why);
        assertEquals(r.decisions, r.decisionsMatched, why);
    }
}
