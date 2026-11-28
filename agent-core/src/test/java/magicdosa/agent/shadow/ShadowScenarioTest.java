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

    /**
     * The other seat's Soul-Guide Lantern enters ("exile target card from a graveyard") with two or three non-basic lands
     * in seat 0's graveyard: Forge AI picks one of them at random (ComputerUtilCard.getBestLandAI), so the replayed seat
     * takes the targets the real stream shows for its trigger (before: about half the seeds targeted another land).
     */
    @Test
    void replayedTriggerTakesItsRecordedTargets() throws Exception {
        String[][] yards = {{"Marsh Flats", "Polluted Delta"}, {"Marsh Flats", "Polluted Delta", "Flooded Strand"},
            {"Watery Grave", "Hallowed Fountain"}};
        String[] names = {"lantern-0-1", "lantern-1-0", "lantern-2-0"}; // names seed the games
        for (int y = 0; y < yards.length; y++) {
            Scenario s = new Scenario(names[y], 5, 1);
            s.seat(0).deck("Island", 26).deck("Plains", 26).deck("Marsh Flats", 2).deck("Polluted Delta", 2).deck("Flooded Strand", 2)
                .deck("Watery Grave", 1).deck("Hallowed Fountain", 1)
                .library("Island", "Plains", "Island", "Plains", "Island", "Plains")
                .hand("Plains")
                .battlefield("Island", "Plains")
                .graveyard(yards[y]);
            s.seat(1).deck("Forest", 52).deck("Soul-Guide Lantern", 4).deck("Ancient Stirrings", 4)
                .library("Forest", "Forest", "Forest")
                .hand("Soul-Guide Lantern", "Forest")
                .battlefield("Forest", "Forest");
            ShadowGame.Result r = s.play(2);
            assertTrue(s.events.stream().anyMatch(e -> e.hasTriggerStacked() && e.getTriggerStacked().getSeat() == 1
                && e.getTriggerStacked().getTargetsCount() == 1), s.name + ": no targeted trigger of seat 1");
            clean(r);
        }
    }

    /**
     * 2026-11-28: library cards a seat is shown for a choice (a fetchland's search, Expressive Iteration's look at the
     * top three) carry their identity in that seat's observation while the choice is open; before, each was an unknown
     * library card (no oracle_id, so no token: the network could not tell the candidates apart). The shadow must still
     * match every decision.
     */
    @Test
    void libraryCardsShownForAChoiceCarryTheirIdentity() throws Exception {
        for (String name : new String[] {"shown-0", "shown-1"}) { // names seed the games
            Scenario s = new Scenario(name, 5, 0);
            s.seat(0).deck("Mountain", 18).deck("Island", 10).deck("Steam Vents", 4).deck("Bloodstained Mire", 4)
                .deck("Lightning Bolt", 4).deck("Preordain", 4).deck("Lava Dart", 4).deck("Expressive Iteration", 4)
                .deck("Monastery Swiftspear", 4).deck("Mishra's Bauble", 4)
                .library("Lightning Bolt", "Preordain", "Lava Dart", "Monastery Swiftspear", "Mountain", "Island", "Steam Vents")
                .hand("Expressive Iteration")
                .battlefield("Bloodstained Mire", "Steam Vents", "Mountain");
            s.seat(1).deck("Forest", 60).library("Forest", "Forest", "Forest").hand("Forest");
            ShadowGame.Result r = s.play(3);
            List<magicdosa.protocol.v1.DecisionRequest> shown = s.requests.stream()
                .filter(q -> q.getType() == magicdosa.protocol.v1.DecisionType.DECISION_TYPE_CHOOSE_CARDS)
                .filter(q -> q.getCandidatesList().stream().anyMatch(c -> zoneOf(q, c) == magicdosa.protocol.v1.Zone.ZONE_LIBRARY))
                .toList();
            String what = name + ": " + shown.stream().map(q -> q.getContext().getEnginePrompt()).toList();
            assertTrue(shown.stream().anyMatch(q -> q.getContext().getEnginePrompt().equals("Select a card from your library")),
                what + ": no fetchland search");
            assertTrue(shown.stream().anyMatch(q -> q.getContext().getEnginePrompt().equals("Choose card(s) to put into hand")),
                what + ": no Expressive Iteration look");
            for (magicdosa.protocol.v1.DecisionRequest q : shown) {
                java.util.Set<Long> ids = new java.util.HashSet<>();
                for (magicdosa.protocol.v1.Candidate c : q.getCandidatesList()) {
                    long id = candidateId(c);
                    assertTrue(ids.add(id), what + ": candidate " + id + " offered twice");
                    String oracle = q.getObservation().getObjectsList().stream().filter(g -> g.getId() == id)
                        .map(magicdosa.protocol.v1.GameObject::getOracleId).findFirst().orElse("");
                    assertTrue(!oracle.isEmpty(), what + ": candidate " + id + " of \"" + q.getContext().getEnginePrompt()
                        + "\" has no identity");
                }
            }
            // a fetch before the look shuffles the library, so the top three may repeat a name
            magicdosa.protocol.v1.DecisionRequest look = shown.stream()
                .filter(q -> q.getContext().getEnginePrompt().equals("Choose card(s) to put into hand")).findFirst().orElseThrow();
            assertTrue(look.getCandidatesCount() >= 2, name + ": the look offers " + look.getCandidatesCount() + " cards");
            // the encoder makes each a token of its own card: every candidate points at a different token with a card
            magicdosa.agent.encoder.Encoder enc = new magicdosa.agent.encoder.Encoder(magicdosa.agent.encoder.Vocab.load());
            for (magicdosa.protocol.v1.DecisionRequest q : shown) {
                magicdosa.agent.encoder.Encoder.Encoded e = enc.encode(q);
                java.util.Set<Integer> tokens = new java.util.HashSet<>();
                for (int i = 0; i < q.getCandidatesCount(); i++) {
                    int t = e.candRef0()[i];
                    assertTrue(t >= 0 && tokens.add(t), what + ": candidate " + i + " has no token of its own (" + t + ")");
                    assertTrue(e.tokenCard()[t] != magicdosa.agent.encoder.Vocab.PAD, what + ": candidate " + i + "'s token has no card");
                }
            }
            clean(r);
        }
    }

    private static long candidateId(magicdosa.protocol.v1.Candidate c) {
        return c.hasTarget() ? c.getTarget().getEntityId() : c.getCardId();
    }

    private static magicdosa.protocol.v1.Zone zoneOf(magicdosa.protocol.v1.DecisionRequest q, magicdosa.protocol.v1.Candidate c) {
        long id = candidateId(c);
        return q.getObservation().getObjectsList().stream().filter(g -> g.getId() == id).map(magicdosa.protocol.v1.GameObject::getZone)
            .findFirst().orElse(magicdosa.protocol.v1.Zone.ZONE_NONE);
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
