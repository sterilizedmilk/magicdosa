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

    /**
     * A target chosen while casting is searched at the root from the anchor (the copy made at the priority decision
     * that cast the spell), replaying the cast's earlier answers. Lightning Bolt wins at once: the other seat has
     * 3 life; waiting loses: seat 0 has 2 life, the other seat's two attackers come next turn, and its Teferi, Time
     * Raveler lets seat 0 cast only at sorcery speed.
     */
    @Test
    void targetsInsideACastAreSearchedFromTheAnchor() throws Exception {
        Scenario s = new Scenario("search-target-0", 5, 0);
        s.seat(0).deck("Mountain", 30).deck("Lightning Bolt", 30)
            .library("Mountain", "Mountain", "Mountain", "Mountain")
            .hand("Lightning Bolt")
            .battlefield("Mountain");
        s.seat(0).life = 2;
        s.seat(1).deck("Plains", 28).deck("Monastery Swiftspear", 28).deck("Teferi, Time Raveler", 4)
            .library("Plains", "Plains", "Plains", "Plains")
            .hand("Plains")
            .battlefield("Plains", "Monastery Swiftspear", "Monastery Swiftspear")
            .battlefieldWith("Teferi, Time Raveler", "loyalty", 4);
        s.seat(1).life = 3;
        s.search = SearchConfig.of(40);
        ShadowGame.Result r = s.play(1);
        LiveShadow.Stats st = s.stats;
        String why = st.line() + " samples: " + r.samples;
        System.out.println("search target scenario: " + why);
        assertTrue(st.search.getOrDefault("root TARGET", 0) > 0, why);
        assertTrue(st.backedUp * 2 > st.simulations, why);
        assertEquals(r.decisions, r.decisionsMatched, why);
    }

    /** The confirmed-kill checker sees an available kill: three attackers, one blocker, the other seat at 2 life. */
    @Test
    void theKillCheckerSeesAnAvailableKill() throws Exception {
        Scenario s = new Scenario("search-kill-0", 5, 0);
        s.seat(0).deck("Mountain", 30).deck("Monastery Swiftspear", 30)
            .library("Mountain", "Mountain", "Mountain", "Mountain")
            .hand("Mountain")
            .battlefield("Mountain", "Monastery Swiftspear", "Monastery Swiftspear", "Monastery Swiftspear");
        s.seat(1).deck("Forest", 30).deck("Monastery Swiftspear", 30)
            .library("Forest", "Forest", "Forest", "Forest")
            .hand("Forest")
            .battlefield("Forest", "Monastery Swiftspear");
        s.seat(1).life = 2;
        s.search = SearchConfig.of(6);
        ShadowGame.Result r = s.play(1);
        LiveShadow.Stats st = s.stats;
        String why = st.line();
        System.out.println("search kill scenario: " + why);
        assertTrue(st.search.getOrDefault("kill: available", 0) > 0, why);
        assertEquals(r.decisions, r.decisionsMatched, why);
    }

    /**
     * A scry inside Preordain is searched from the anchor: the shadow put the shown cards on top when the real game showed
     * them, after the anchor was copied, so each copy deals them where the shadow has them at the root (before: every
     * simulation of such a root missed it, the copies showing other cards).
     */
    @Test
    void aScryIsSearchedWithTheCardsItShows() throws Exception {
        Scenario s = new Scenario("search-scry-0", 5, 0);
        s.seat(0).deck("Island", 30).deck("Preordain", 30)
            .library("Island", "Preordain", "Island", "Preordain", "Island", "Island")
            .hand("Preordain", "Preordain", "Preordain")
            .battlefield("Island", "Island");
        s.seat(1).deck("Forest", 60)
            .library("Forest", "Forest", "Forest", "Forest")
            .hand("Forest")
            .battlefield("Forest");
        s.search = SearchConfig.of(24);
        ShadowGame.Result r = s.play(4);
        LiveShadow.Stats st = s.stats;
        String why = st.line();
        System.out.println("search scry scenario: " + why);
        assertTrue(st.search.getOrDefault("root CHOOSE_CARDS", 0) > 0, why);
        assertEquals(st.simulations, st.backedUp, why);
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
