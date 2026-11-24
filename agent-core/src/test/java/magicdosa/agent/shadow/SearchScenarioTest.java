package magicdosa.agent.shadow;

import magicdosa.agent.search.SearchConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 wiring: seat 0's priority decisions are searched on copies of the live shadow (a small budget; this checks
 * that simulations reach the root decision and are backed up, not how well the search plays).
 */
class SearchScenarioTest {

    private static Scenario burn(String name) {
        Scenario s = new Scenario(name, 5, 0);
        s.seat(0).deck("Mountain", 30).deck("Lightning Bolt", 15).deck("Monastery Swiftspear", 15)
            .library("Mountain", "Lightning Bolt", "Mountain", "Monastery Swiftspear", "Mountain", "Mountain")
            .hand("Lightning Bolt", "Monastery Swiftspear", "Mountain")
            .battlefield("Mountain", "Mountain");
        s.seat(1).deck("Forest", 40).deck("Ancient Stirrings", 20)
            .library("Forest", "Forest", "Forest", "Forest")
            .hand("Ancient Stirrings", "Forest")
            .battlefield("Forest", "Forest");
        return s;
    }

    @Test
    void prioritySearchBacksUpSimulations() throws Exception {
        Scenario s = burn("search-0");
        s.search = SearchConfig.of(6);
        ShadowGame.Result r = s.play(2);
        LiveShadow.Stats st = s.stats;
        String why = st.line() + " samples: " + r.samples;
        System.out.println("search scenario: " + why);
        assertTrue(st.searches > 0, why);
        assertEquals(st.searches * 6, st.simulations, why);
        assertTrue(st.backedUp * 2 > st.simulations, why); // most simulations reach the root and are backed up
        assertEquals(r.decisions, r.decisionsMatched, why);
    }

    /** Combat declarations are searched at the root: the copy goes back a step and Forge's loop asks again. */
    @Test
    void combatDeclarationsAreSearched() throws Exception {
        Scenario s = new Scenario("search-combat-0", 5, 0);
        s.seat(0).deck("Mountain", 30).deck("Monastery Swiftspear", 30)
            .library("Mountain", "Mountain", "Mountain", "Mountain", "Mountain", "Mountain")
            .hand("Mountain")
            .battlefield("Mountain", "Mountain", "Monastery Swiftspear", "Monastery Swiftspear", "Monastery Swiftspear");
        s.seat(1).deck("Forest", 30).deck("Monastery Swiftspear", 30)
            .library("Forest", "Forest", "Forest", "Forest")
            .hand("Forest")
            .battlefield("Forest", "Forest", "Monastery Swiftspear", "Monastery Swiftspear");
        s.search = SearchConfig.of(6);
        ShadowGame.Result r = s.play(2);
        LiveShadow.Stats st = s.stats;
        String why = st.line() + " samples: " + r.samples;
        System.out.println("search combat scenario: " + why);
        assertTrue(st.search.getOrDefault("root DECLARE_ATTACKERS", 0) > 0, why);
        assertTrue(st.backedUp * 2 > st.simulations, why);
        assertEquals(r.decisions, r.decisionsMatched, why);
    }

    @Test
    void sameSeedSameSearches() throws Exception {
        String[] lines = new String[2];
        for (int i = 0; i < 2; i++) {
            Scenario s = burn("search-1");
            s.search = SearchConfig.of(4);
            s.play(1);
            lines[i] = s.stats.line();
        }
        assertEquals(lines[0], lines[1]);
    }
}
