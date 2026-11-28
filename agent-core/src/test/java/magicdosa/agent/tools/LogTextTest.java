package magicdosa.agent.tools;

import magicdosa.protocol.v1.CardFaceKey;
import magicdosa.protocol.v1.CastSpell;
import magicdosa.protocol.v1.CastSpellCandidate;
import magicdosa.protocol.v1.Candidate;
import magicdosa.protocol.v1.DamageDealt;
import magicdosa.protocol.v1.Decider;
import magicdosa.protocol.v1.DecisionRecord;
import magicdosa.protocol.v1.DecisionRequest;
import magicdosa.protocol.v1.DecisionResponse;
import magicdosa.protocol.v1.DecisionStep;
import magicdosa.protocol.v1.DecisionType;
import magicdosa.protocol.v1.Event;
import magicdosa.protocol.v1.GameEndReason;
import magicdosa.protocol.v1.GameGlobal;
import magicdosa.protocol.v1.GameOver;
import magicdosa.protocol.v1.HiddenIdentity;
import magicdosa.protocol.v1.LifeChanged;
import magicdosa.protocol.v1.LogHeader;
import magicdosa.protocol.v1.LogRecord;
import magicdosa.protocol.v1.MatchPlayer;
import magicdosa.protocol.v1.Method;
import magicdosa.protocol.v1.ObjectRef;
import magicdosa.protocol.v1.Observation;
import magicdosa.protocol.v1.PassCandidate;
import magicdosa.protocol.v1.Privileged;
import magicdosa.protocol.v1.PrivilegedRecord;
import magicdosa.protocol.v1.SearchRun;
import magicdosa.protocol.v1.SpellKey;
import magicdosa.protocol.v1.Step;
import magicdosa.protocol.v1.StepCandidate;
import magicdosa.protocol.v1.Zone;
import magicdosa.protocol.v1.ZoneMove;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogTextTest {
    private static final String BOLT = "4457ed35-7c10-48c8-9776-456485fdf070";
    private static final String MOUNTAIN = "a3fb7228-e76b-4e96-a40e-20b5fed75685";

    @TempDir
    Path dir;

    /** A two-seat game cut to one turn: draws, a searched decision, the cast it led to, a hidden draw, game over. */
    private List<LogRecord> fixture() {
        LogRecord header = LogRecord.newBuilder().setHeader(LogHeader.newBuilder().setProtocolVersion(16)
            .setEncoderVersion(5).setMatchId("m1").setGameId("m1-g1").setGameNumber(1).setGameSeed(42)
            .addPlayers(MatchPlayer.newBuilder().setSeat(0).setDeckName("Izzet Prowess"))
            .addPlayers(MatchPlayer.newBuilder().setSeat(1).setDeckName("Esper Blink"))
            .addPlayerKinds("agent:net").addPlayerKinds("forge-ai").addAgentSeats(0)).build();
        Observation obs = Observation.newBuilder().setViewerSeat(0)
            .setGame(GameGlobal.newBuilder().setTurn(1).setStep(Step.STEP_MAIN1).setActiveSeat(0)).build();
        DecisionRecord decision = DecisionRecord.newBuilder()
            .setRequest(DecisionRequest.newBuilder().setDecisionId(7).setSeat(0).setAppliedSeq(2)
                .setType(DecisionType.DECISION_TYPE_PRIORITY).setObservation(obs)
                .addCandidates(Candidate.newBuilder().setPass(PassCandidate.getDefaultInstance()))
                .addCandidates(Candidate.newBuilder().setCastSpell(CastSpellCandidate.newBuilder().setCardId(10)
                    .setSpell(SpellKey.newBuilder().setCard(CardFaceKey.newBuilder().setOracleId(BOLT))))))
            .setResponse(DecisionResponse.newBuilder().setDecisionId(7).addChosen(1).addSteps(DecisionStep.newBuilder()
                .addCandidates(StepCandidate.newBuilder().setIndex(0)).addCandidates(StepCandidate.newBuilder().setIndex(1))
                .setChosen(1).addVisitRatio(0.25f).addVisitRatio(0.75f).setDecider(Decider.DECIDER_MODEL)
                .setMethod(Method.newBuilder().setRan(SearchRun.newBuilder().setBudget(24).setSimulations(24)
                    .setRootValue(0.625f)))))
            .setDecider(Decider.DECIDER_MODEL).addApplied(1).build();
        return List.of(
            header,
            event(1, Step.STEP_DRAW, 1, Event.newBuilder().setZoneMove(ZoneMove.newBuilder().setId(10).setPrevId(3)
                .setFrom(Zone.ZONE_LIBRARY).setTo(Zone.ZONE_HAND).setOwnerSeat(0).setOracleId(BOLT))),
            event(1, Step.STEP_MAIN1, 2, Event.newBuilder().setZoneMove(ZoneMove.newBuilder().setId(11).setPrevId(4)
                .setFrom(Zone.ZONE_LIBRARY).setTo(Zone.ZONE_HAND).setOwnerSeat(1))),
            // the cast is logged before the decision record that caused it
            event(1, Step.STEP_MAIN1, 3, Event.newBuilder().setCastSpell(CastSpell.newBuilder().setSeat(0).setCardId(10)
                .setSpellId(12).setSpell(SpellKey.newBuilder().setCard(CardFaceKey.newBuilder().setOracleId(BOLT)))
                .addTargets(ObjectRef.newBuilder().setPlayerSeat(1)))),
            event(1, Step.STEP_MAIN1, 4, Event.newBuilder().setDamage(DamageDealt.newBuilder().setSourceId(12)
                .setTarget(ObjectRef.newBuilder().setPlayerSeat(1)).setAmount(3))),
            event(1, Step.STEP_MAIN1, 5, Event.newBuilder().setLife(LifeChanged.newBuilder().setSeat(1).setDelta(-3)
                .setLife(17))),
            LogRecord.newBuilder().setPrivileged(PrivilegedRecord.newBuilder().setSeat(0).setDecisionId(7)
                .setPrivileged(Privileged.newBuilder().addIdentities(HiddenIdentity.newBuilder().setId(11)
                    .setOracleId(MOUNTAIN)))).build(),
            LogRecord.newBuilder().setDecision(decision).build(),
            LogRecord.newBuilder().setGameOver(GameOver.newBuilder().setGameId("m1-g1").setWinnerSeat(0)
                .setReason(GameEndReason.GAME_END_REASON_LIFE).setTurns(1)).build());
    }

    private static LogRecord event(int turn, Step step, long seq, Event.Builder body) {
        return LogRecord.newBuilder().setEvent(body.setGameId("m1-g1").setViewerSeat(0).setSeq(seq).setTurn(turn)
            .setStep(step)).build();
    }

    private Path write(List<LogRecord> records) throws Exception {
        Path file = dir.resolve("g.pb.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            for (LogRecord r : records) {
                r.writeDelimitedTo(out);
            }
        }
        return file;
    }

    @Test
    void rendersOneEntryPerActionInGameOrder() throws Exception {
        List<String> lines = LogText.render(LogText.read(write(fixture())), new LogText.Options(-1, false));
        List<String> expected = List.of(
            "match m1  game m1-g1 (#1)  seed 42",
            "protocol 16  encoder 5  model -  adapter -  engine -  mode unspecified",
            "P0: Izzet Prowess  agent:net  [agent]",
            "P1: Esper Blink  forge-ai",
            "result: P0 wins (life) after 1 turns",
            "events as seen by P0; each decision shows the deciding seat's own view",
            "",
            "=== Turn 1 (P0) ===",
            "  -- draw --",
            "    P0 draws Lightning Bolt#10",
            "  -- main1 --",
            "    P1 draws card#11",
            "    > D7 P0 priority  [model]",
            "      search root_value=0.625 budget=24 simulations=24",
            "        0 pass                    visits 0.250",
            "      * 1 cast Lightning Bolt#10  visits 0.750",
            "    P0 casts Lightning Bolt#12 -> P1",
            "    Lightning Bolt#12 deals 3 damage to P1",
            "    P1 life -3 -> 17",
            "",
            "=== Game over: P0 wins (life) after 1 turns ===",
            "decisions: 1, model 1, searched 1");
        assertEquals(expected, lines);
    }

    @Test
    void privilegedOnlyOnRequest() throws Exception {
        List<LogRecord> records = LogText.read(write(fixture()));
        List<String> plain = LogText.render(records, new LogText.Options(-1, false));
        assertFalse(plain.stream().anyMatch(l -> l.contains("privileged") || l.contains("Mountain")));
        List<String> shown = LogText.render(records, new LogText.Options(-1, true));
        assertTrue(shown.contains("    [privileged P0 @D7] hidden identities 1"), String.join("\n", shown));
    }
}
