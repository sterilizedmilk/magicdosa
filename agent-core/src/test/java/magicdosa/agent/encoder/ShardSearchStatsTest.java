package magicdosa.agent.encoder;

import magicdosa.protocol.v1.Candidate;
import magicdosa.protocol.v1.CandidateStats;
import magicdosa.protocol.v1.DecisionRequest;
import magicdosa.protocol.v1.DecisionResponse;
import magicdosa.protocol.v1.DecisionStep;
import magicdosa.protocol.v1.DecisionType;
import magicdosa.protocol.v1.Decider;
import magicdosa.protocol.v1.GameGlobal;
import magicdosa.protocol.v1.GameOver;
import magicdosa.protocol.v1.Hello;
import magicdosa.protocol.v1.MatchContext;
import magicdosa.protocol.v1.Method;
import magicdosa.protocol.v1.Observation;
import magicdosa.protocol.v1.PassCandidate;
import magicdosa.protocol.v1.PlayerState;
import magicdosa.protocol.v1.SearchRun;
import magicdosa.protocol.v1.SearchSkipReason;
import magicdosa.protocol.v1.SelectionConstraints;
import magicdosa.protocol.v1.StepCandidate;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.ipc.ArrowFileReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shard schema 2 (protocol 17): a searched step's per-candidate N, Q (NaN when N = 0) and prior (NaN: uniform), the root
 * estimate, and each step's search status reach the shard.
 */
class ShardSearchStatsTest {

    private static DecisionRequest request(long id) {
        Observation o = Observation.newBuilder().setViewerSeat(0).setMatch(MatchContext.newBuilder().setGameNumber(1))
            .setGame(GameGlobal.newBuilder().setTurn(2).setActiveSeat(0).setPrioritySeat(0))
            .addPlayers(PlayerState.newBuilder().setSeat(0).setLife(20))
            .addPlayers(PlayerState.newBuilder().setSeat(1).setLife(20)).build();
        return DecisionRequest.newBuilder().setDecisionId(id).setSeat(0).setType(DecisionType.DECISION_TYPE_PRIORITY)
            .setObservation(o).setConstraints(SelectionConstraints.newBuilder().setMin(1).setMax(1))
            .addCandidates(Candidate.newBuilder().setPass(PassCandidate.getDefaultInstance()))
            .addCandidates(Candidate.newBuilder().setPass(PassCandidate.getDefaultInstance())).build();
    }

    private static DecisionResponse answer(long id, Method method, List<CandidateStats> stats) {
        DecisionStep.Builder s = DecisionStep.newBuilder().setDecider(Decider.DECIDER_MODEL).setMethod(method).setChosen(1)
            .addCandidates(StepCandidate.newBuilder().setIndex(0)).addCandidates(StepCandidate.newBuilder().setIndex(1))
            .addAllCandidateStats(stats);
        if (!stats.isEmpty()) {
            s.addVisitRatio(0f).addVisitRatio(1f);
        }
        return DecisionResponse.newBuilder().setDecisionId(id).addChosen(1).addSteps(s)
            .setKillCheck(stats.isEmpty() ? magicdosa.protocol.v1.KillCheckOutcome.KILL_CHECK_OUTCOME_DEATH
                : magicdosa.protocol.v1.KillCheckOutcome.KILL_CHECK_OUTCOME_NONE).build();
    }

    @Test
    void searchStatisticsAndStatusAreWritten(@TempDir Path dir) throws Exception {
        Vocab v = Vocab.read(new ByteArrayInputStream("#version 1\ncard\tbolt\n".getBytes(StandardCharsets.UTF_8)));
        ShardWriter w = new ShardWriter(new Encoder(v), dir, ShardWriter.NO_MODEL, "search");
        Method ran = Method.newBuilder().setRan(SearchRun.newBuilder().setBudget(5).setRootEstimate(0.625f)).build();
        Method stopped = Method.newBuilder().setSkipped(SearchSkipReason.SEARCH_SKIP_REASON_SHADOW_STOPPED).build();
        Path file = w.write(Hello.newBuilder().setGameId("g1").setMatchId("m1").setGameNumber(1).build(), List.of(
            new ShardWriter.Decided(request(1), answer(1, ran, List.of(CandidateStats.newBuilder().setVisits(0).build(),
                CandidateStats.newBuilder().setVisits(5).setMeanValue(0.75f).build()))),
            new ShardWriter.Decided(request(2), answer(2, stopped, List.of()))),
            GameOver.newBuilder().setWinnerSeat(0).build());
        try (RootAllocator alloc = new RootAllocator();
             ArrowFileReader r = new ArrowFileReader(FileChannel.open(file), alloc, ZstdCodec.factory(3))) {
            assertEquals(String.valueOf(ShardWriter.SHARD_SCHEMA_VERSION), r.getVectorSchemaRoot().getSchema()
                .getCustomMetadata().get("shard_schema_version"));
            r.loadNextBatch();
            VectorSchemaRoot root = r.getVectorSchemaRoot();
            assertEquals(2, root.getRowCount());
            IntVector status = (IntVector) root.getVector("search_status");
            assertEquals(ShardWriter.STATUS_COMPLETED, status.get(0));
            assertEquals(ShardWriter.STATUS_ABORTED, status.get(1));
            Float4Vector rootValue = (Float4Vector) root.getVector("search_root_value");
            assertEquals(0.625f, rootValue.get(0), 1e-6);
            assertTrue(Float.isNaN(rootValue.get(1)));
            assertEquals(List.of(0, 5), ((ListVector) root.getVector("cand_visits")).getObject(0));
            List<?> q = ((ListVector) root.getVector("cand_q")).getObject(0);
            assertTrue(Float.isNaN((Float) q.get(0)));
            assertEquals(0.75f, (Float) q.get(1), 1e-6);
            assertTrue(Float.isNaN((Float) ((ListVector) root.getVector("cand_prior")).getObject(0).get(1)));
            assertEquals(List.of(), ((ListVector) root.getVector("cand_visits")).getObject(1));
            IntVector kill = (IntVector) root.getVector("kill_check");
            assertEquals(magicdosa.protocol.v1.KillCheckOutcome.KILL_CHECK_OUTCOME_NONE_VALUE, kill.get(0));
            assertEquals(magicdosa.protocol.v1.KillCheckOutcome.KILL_CHECK_OUTCOME_DEATH_VALUE, kill.get(1));
        }
    }
}
