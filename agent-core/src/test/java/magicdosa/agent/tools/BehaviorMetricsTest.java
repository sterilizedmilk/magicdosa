package magicdosa.agent.tools;

import magicdosa.agent.tools.BehaviorMetrics.SeatResult;
import magicdosa.agent.tools.BehaviorMetrics.Tally;
import magicdosa.protocol.v1.ActivateAbilityCandidate;
import magicdosa.protocol.v1.Attack;
import magicdosa.protocol.v1.Candidate;
import magicdosa.protocol.v1.Characteristics;
import magicdosa.protocol.v1.DecisionRecord;
import magicdosa.protocol.v1.DecisionRequest;
import magicdosa.protocol.v1.DecisionResponse;
import magicdosa.protocol.v1.DecisionStep;
import magicdosa.protocol.v1.DecisionType;
import magicdosa.protocol.v1.Event;
import magicdosa.protocol.v1.GameEndReason;
import magicdosa.protocol.v1.GameGlobal;
import magicdosa.protocol.v1.GameObject;
import magicdosa.protocol.v1.GameOver;
import magicdosa.protocol.v1.KillCheckOutcome;
import magicdosa.protocol.v1.LogHeader;
import magicdosa.protocol.v1.LogRecord;
import magicdosa.protocol.v1.MatchPlayer;
import magicdosa.protocol.v1.Method;
import magicdosa.protocol.v1.ObjectRef;
import magicdosa.protocol.v1.Observation;
import magicdosa.protocol.v1.PassCandidate;
import magicdosa.protocol.v1.PlayLand;
import magicdosa.protocol.v1.PlayLandCandidate;
import magicdosa.protocol.v1.SearchRun;
import magicdosa.protocol.v1.SearchSkipReason;
import magicdosa.protocol.v1.Zone;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BehaviorMetricsTest {
    private static final BehaviorMetrics.Types TYPES = o -> o.getCharacteristics().getTypesList().contains("Creature");
    private static final Candidate PASS = Candidate.newBuilder().setPass(PassCandidate.getDefaultInstance()).build();
    private static final Candidate LAND = Candidate.newBuilder().setPlayLand(PlayLandCandidate.newBuilder().setCardId(5)).build();
    private static final Candidate MANA = Candidate.newBuilder()
        .setActivateAbility(ActivateAbilityCandidate.newBuilder().setSourceId(6).setManaAbility(true)).build();
    private static final Candidate ATTACK = Candidate.newBuilder().setAttack(Attack.newBuilder().setAttackerId(7)
        .setDefender(ObjectRef.newBuilder().setPlayerSeat(1))).build();

    private long id = 1;

    private LogRecord decision(int turn, int active, DecisionType type, List<GameObject> objects, List<Candidate> cands,
                               List<Integer> applied, DecisionStep step, KillCheckOutcome kill) {
        Observation obs = Observation.newBuilder().setGame(GameGlobal.newBuilder().setTurn(turn).setActiveSeat(active))
            .addAllObjects(objects).build();
        return LogRecord.newBuilder().setDecision(DecisionRecord.newBuilder()
            .setRequest(DecisionRequest.newBuilder().setDecisionId(id++).setSeat(0).setType(type).setObservation(obs)
                .addAllCandidates(cands))
            .setResponse(DecisionResponse.newBuilder().addSteps(step).setKillCheck(kill))
            .addAllApplied(applied)).build();
    }

    private static DecisionStep search(float root) {
        return DecisionStep.newBuilder().setMethod(Method.newBuilder().setRan(SearchRun.newBuilder().setRootValue(root))).build();
    }

    private static GameObject creature(int controller, boolean tapped) {
        return GameObject.newBuilder().setId(90 + controller).setZone(Zone.ZONE_BATTLEFIELD).setControllerSeat(controller)
            .setTapped(tapped).setCharacteristics(Characteristics.newBuilder().addTypes("Creature")).build();
    }

    private List<LogRecord> game(int protocol) {
        DecisionStep net = DecisionStep.newBuilder().setMethod(Method.newBuilder()
            .setSkipped(SearchSkipReason.SEARCH_SKIP_REASON_NOT_AVAILABLE)).build();
        KillCheckOutcome none = KillCheckOutcome.KILL_CHECK_OUTCOME_NONE;
        KillCheckOutcome kill = KillCheckOutcome.KILL_CHECK_OUTCOME_KILL;
        List<LogRecord> r = new ArrayList<>();
        r.add(LogRecord.newBuilder().setHeader(LogHeader.newBuilder().setProtocolVersion(protocol).addAgentSeats(0)
            .addPlayers(MatchPlayer.newBuilder().setSeat(0).setDeckName("A"))
            .addPlayers(MatchPlayer.newBuilder().setSeat(1).setDeckName("B"))).build());
        // turn 1: land offered and played; a real choice answered with pass; a mana-only choice does not count
        r.add(decision(1, 0, DecisionType.DECISION_TYPE_PRIORITY, List.of(), List.of(PASS, LAND), List.of(1), net, none));
        r.add(LogRecord.newBuilder().setEvent(Event.newBuilder().setTurn(1).setPlayLand(PlayLand.newBuilder().setSeat(0))).build());
        r.add(decision(1, 0, DecisionType.DECISION_TYPE_PRIORITY, List.of(), List.of(PASS, LAND), List.of(0), net, none));
        r.add(decision(1, 0, DecisionType.DECISION_TYPE_PRIORITY, List.of(), List.of(PASS, MANA), List.of(0), net, none));
        // turn 3: land offered, never played -> missed; opponent's creature is tapped -> free combat, no attack -> missed
        r.add(decision(3, 0, DecisionType.DECISION_TYPE_PRIORITY, List.of(), List.of(PASS, LAND), List.of(0), search(0.95f), none));
        r.add(decision(3, 0, DecisionType.DECISION_TYPE_DECLARE_ATTACKERS, List.of(creature(1, true), creature(0, false)),
            List.of(ATTACK), List.of(), search(0.5f), kill));
        // turn 5: an untapped blocker -> not free; kill verdict, attack, game won this turn -> closed
        r.add(decision(5, 0, DecisionType.DECISION_TYPE_DECLARE_ATTACKERS, List.of(creature(1, false)),
            List.of(ATTACK), List.of(0), search(0.99f), kill));
        // turn 5 is the last turn: a land left in hand then does not count
        r.add(decision(5, 0, DecisionType.DECISION_TYPE_PRIORITY, List.of(), List.of(PASS, LAND), List.of(0), search(0.99f), none));
        r.add(LogRecord.newBuilder().setGameOver(GameOver.newBuilder().setWinnerSeat(0).setTurns(5)
            .setReason(GameEndReason.GAME_END_REASON_LIFE)).build());
        return r;
    }

    @Test
    void definitions() {
        List<SeatResult> seats = BehaviorMetrics.game(game(17), TYPES);
        assertEquals(1, seats.size());
        SeatResult s = seats.get(0);
        assertEquals("A vs B", s.matchup());
        assertEquals("search", s.mode());
        Tally t = s.tally();
        assertEquals(1, t.wins);
        assertEquals(2, t.killTurns);          // turns 3 and 5
        assertEquals(1, t.killNotClosed);      // turn 3
        assertEquals(1, t.freeCombats);
        assertEquals(1, t.freeMissed);
        assertEquals(2, t.landTurns);          // turns 1 and 3 (5 is the last turn)
        assertEquals(1, t.landMissed);         // turn 3
        assertEquals(4, t.priorityChoices);    // the mana-only one left out
        assertEquals(3, t.passed);
        assertEquals(5, t.turns);
        assertEquals(1, t.confidentGames);
        assertEquals(2, t.confidentTurnsToEnd); // first root value >= 0.9 on turn 3, game ends turn 5
        assertEquals("| A vs B | search | 1 | 100.0 | 50.0% (1/2) | 100.0% (1/1) | 50.0% (1/2) | 75.0% (3/4) | 5.0 | 2.0 (1, 100%) |",
            BehaviorMetrics.row("A vs B", "search", t));
    }

    @Test
    void killCheckNotAvailableBeforeProtocol17() {
        Tally t = BehaviorMetrics.game(game(16), TYPES).get(0).tally();
        assertEquals("| x | search | 1 | 100.0 | n/a (protocol < 17) | 100.0% (1/1) | 50.0% (1/2) | 75.0% (3/4) | 5.0 | 2.0 (1, 100%) |",
            BehaviorMetrics.row("x", "search", t));
    }
}
