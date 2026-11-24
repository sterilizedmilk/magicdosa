package magicdosa.agent.shadow;

import org.junit.jupiter.api.Test;

import magicdosa.adapter.forge.SnapshotBuilder;

import java.util.List;

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

    /**
     * The other seat activates Geier Reach Sanitarium in its second main phase, draws Teferi, discards another hidden
     * card and then casts the Teferi. Live, its discard choice comes before the record shows what the discarded card is,
     * and the sample may have made that card the Teferi; the Teferi's reveal (taken after our own discard answer, inside
     * the effect) must not trade identities with the card the effect holds, or the effect discards the Teferi under the
     * drawn card's ID and the recorded cast finds no card (before the fix: 1 of 32 decisions did not match).
     */
    @Test
    void castOfACardDrawnInsideAnEffectThatDiscardsAnother() throws Exception {
        for (String name : new String[] {"tefcast-1-0-0", "tefcast-1-0-1"}) { // names seed the games and the sample
            Scenario s = new Scenario(name, 5, 1);
            s.seat(0).deck("Island", 26).deck("Plains", 30).deck("Geier Reach Sanitarium", 4)
                .library("Island", "Plains", "Island", "Plains", "Island", "Plains")
                .hand("Plains")
                .battlefield("Island", "Plains");
            s.seat(1).deck("Island", 14).deck("Plains", 14).deck("Wrath of the Skies", 4).deck("Teferi, Time Raveler", 24)
                .deck("Geier Reach Sanitarium", 4)
                .library("Island", "Teferi, Time Raveler", "Plains")
                .hand("Wrath of the Skies", "Island", "Plains")
                .battlefield("Geier Reach Sanitarium", "Island", "Plains", "Plains", "Island", "Island");
            ShadowGame.Result r = s.play(3);
            assertTrue(s.events.stream().anyMatch(e -> e.hasResolutionChoice() && e.getResolutionChoice().getSeat() == 1
                && e.getResolutionChoice().getEnginePrompt().equals("chooseCardsToDiscardFrom")), name + ": no discard by seat 1");
            assertTrue(casts(s, 1, "Teferi, Time Raveler"), name + ": Teferi not cast");
            clean(r);
        }
    }

    private static boolean casts(Scenario s, int seat, String card) {
        String oracle = Scenario.keys().oracleIdByName(card);
        return s.events.stream().anyMatch(e -> e.hasCastSpell() && e.getCastSpell().getSeat() == seat
            && e.getCastSpell().getSpell().getCard().getOracleId().equals(oracle));
    }

    /**
     * 738b927, 0bd2dc8: seat 0's Forge AI weighs Kozilek's Command ({X}{C}{C}, a Charm) over several priorities and
     * leaves an X on it; the potential-cast check must not count that X, and the X a cast announced must survive Forge
     * AI's target and mode logic (before the fixes 2 of 65 decisions did not match).
     */
    @Test
    void kozileksCommandKeepsItsAnnouncedX() throws Exception {
        for (int v = 0; v < 3; v++) {
            Scenario s = new Scenario("kozilek-" + v, 5, 0);
            s.seat(0).deck("Forest", 26).deck("Eldrazi Temple", 4).deck("Kozilek's Command", 4).deck("Basking Broodscale", 4)
                .deck("Ancient Stirrings", 4).deck("Malevolent Rumble", 4).deck("Sowing Mycospawn", 4).deck("Urza's Saga", 4)
                .deck("Ugin's Labyrinth", 4).deck("Dismember", 2)
                .library("Forest", "Basking Broodscale", "Forest", "Eldrazi Temple", "Forest", "Ancient Stirrings")
                .hand(v == 2 ? new String[] {"Kozilek's Command", "Forest"} : new String[] {"Kozilek's Command", "Kozilek's Command"})
                .battlefield(v == 1 ? new String[] {"Eldrazi Temple", "Eldrazi Temple", "Forest"}
                    : new String[] {"Eldrazi Temple", "Forest", "Forest", "Forest"});
            s.seat(1).deck("Mountain", 20).deck("Monastery Swiftspear", 4).deck("Lightning Bolt", 4).deck("Dragon's Rage Channeler", 4)
                .deck("Slickshot Show-Off", 4).deck("Preordain", 4).deck("Lava Dart", 4).deck("Mishra's Bauble", 4)
                .deck("Expressive Iteration", 4).deck("Cori-Steel Cutter", 4).deck("Steam Vents", 4)
                .library("Mountain", "Lightning Bolt", "Mountain", "Preordain", "Mountain", "Lava Dart")
                .hand("Preordain", "Lava Dart", "Mishra's Bauble")
                .battlefield("Monastery Swiftspear", "Dragon's Rage Channeler", "Mountain", "Steam Vents");
            ShadowGame.Result r = s.play(4);
            assertTrue(casts(s, 0, "Kozilek's Command"), s.name + ": Kozilek's Command not cast");
            clean(r);
        }
    }

    /**
     * Seat 0's Forge AI casts Kozilek's Command (two modes, each targeting a player) with Eldrazi Temple mana during the
     * other seat's attack. Asked for the modes inside CharmEffect.makeChoices, Forge AI's weighing (CharmAi) chains the
     * modes onto the spell, and makeChoices appends them again unless the weighing's chain is undone: the shadow then
     * asks two more targets than the real game (before the fix: a TARGET where the real game asks priority).
     */
    @Test
    void kozileksCommandCarriesItsModesOnce() throws Exception {
        String[][] hands = {{"Kozilek's Command", "Forest"}, {"Kozilek's Command", "Kozilek's Command"}};
        for (int h = 0; h < hands.length; h++) {
            Scenario s = new Scenario("kcatk-" + (h + 1) + "-2-0", 5, 1); // the name seeds the game
            s.seat(0).deck("Forest", 26).deck("Eldrazi Temple", 4).deck("Kozilek's Command", 4).deck("Basking Broodscale", 4)
                .deck("Ancient Stirrings", 4).deck("Malevolent Rumble", 4).deck("Sowing Mycospawn", 4).deck("Urza's Saga", 4)
                .deck("Ugin's Labyrinth", 4).deck("Dismember", 2)
                .library("Forest", "Basking Broodscale", "Forest", "Eldrazi Temple", "Forest", "Ancient Stirrings")
                .hand(hands[h])
                .battlefield("Eldrazi Temple", "Eldrazi Temple", "Forest");
            s.seat(1).deck("Mountain", 20).deck("Monastery Swiftspear", 4).deck("Lightning Bolt", 4).deck("Dragon's Rage Channeler", 4)
                .deck("Slickshot Show-Off", 4).deck("Preordain", 4).deck("Lava Dart", 4).deck("Mishra's Bauble", 4)
                .deck("Expressive Iteration", 4).deck("Cori-Steel Cutter", 4).deck("Steam Vents", 4)
                .library("Mountain", "Mountain", "Mountain", "Preordain", "Mountain", "Lava Dart")
                .hand("Mountain")
                .battlefield("Monastery Swiftspear", "Mountain", "Steam Vents");
            ShadowGame.Result r = s.play(3);
            String oracle = Scenario.keys().oracleIdByName("Kozilek's Command");
            assertTrue(s.events.stream().anyMatch(e -> e.hasCastSpell() && e.getCastSpell().getSeat() == 0
                && e.getStep() == magicdosa.protocol.v1.Step.STEP_DECLARE_ATTACKERS
                && e.getCastSpell().getSpell().getCard().getOracleId().equals(oracle)
                && e.getCastSpell().getTargetsCount() == 2), s.name + ": Kozilek's Command not cast with two targets in the attack");
            clean(r);
        }
    }

    /**
     * f7bcbe3: a rebuilt Urza's Saga with lore counters has the abilities its chapters gave it for good (I: "{T}: Add
     * {C}", II: the Construct ability); a recovery-2 rebuild without them left the mana ability out of the candidates.
     */
    @Test
    void rebuiltSagaKeepsItsChapterAbilities() throws Exception {
        Scenario s = new Scenario("saga", 5, 0);
        s.seat(0).deck("Forest", 52).deck("Urza's Saga", 4).deck("Ancient Stirrings", 4)
            .library("Forest", "Forest", "Forest").hand("Forest")
            .battlefieldWith("Urza's Saga", "lore", 2).battlefield("Forest");
        s.seat(1).deck("Mountain", 56).deck("Lightning Bolt", 4).library("Mountain", "Mountain").hand("Lightning Bolt");
        SnapshotBuilder.Result built = s.build();
        assertEquals(List.of(), built.gaps());
        forge.game.card.Card saga = built.game().getCardsIn(forge.game.zone.ZoneType.Battlefield).stream()
            .filter(c -> c.getName().equals("Urza's Saga")).findFirst().orElseThrow();
        assertEquals(2, saga.getCounters(forge.game.card.CounterEnumType.LORE));
        assertEquals(1, saga.getManaAbilities().size(), "Urza's Saga gains {T}: Add {C}");
        assertTrue(saga.getSpellAbilities().stream().anyMatch(sa -> sa.getApi() == forge.game.ability.ApiType.Token),
            "Urza's Saga gains the Construct ability");
    }
}
