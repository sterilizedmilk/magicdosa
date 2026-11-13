package magicdosa.agent.server;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import magicdosa.agent.policy.RandomPolicy;
import magicdosa.agent.session.Agent;
import magicdosa.protocol.Versions;
import magicdosa.protocol.v1.AdapterMessage;
import magicdosa.protocol.v1.AgentCoreGrpc;
import magicdosa.protocol.v1.AgentMessage;
import magicdosa.protocol.v1.Candidate;
import magicdosa.protocol.v1.DecisionRequest;
import magicdosa.protocol.v1.DecisionType;
import magicdosa.protocol.v1.Event;
import magicdosa.protocol.v1.GameOver;
import magicdosa.protocol.v1.Hello;
import magicdosa.protocol.v1.Mode;
import magicdosa.protocol.v1.PassCandidate;
import magicdosa.protocol.v1.PassPriority;
import magicdosa.protocol.v1.PrivilegedRecord;
import magicdosa.protocol.v1.SearchSkipReason;
import magicdosa.protocol.v1.SelectionConstraints;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The gRPC service over an in-process transport: handshake checks, decisions, protocol errors, game over. */
class AgentCoreServerTest {
    private Agent agent;
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void start() throws Exception {
        agent = new Agent(new RandomPolicy());
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(AgentCoreServer.service(agent)).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void stop() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    private static Hello hello(String gameId, Mode mode) {
        return Hello.newBuilder().setProtocolVersion(Versions.PROTOCOL).setAdapter("test").setGameId(gameId)
            .setMode(mode).addAgentSeats(0).setMatchId("m").setGameNumber(1).build();
    }

    private static AdapterMessage event(String gameId, long seq) {
        return AdapterMessage.newBuilder().setEvent(Event.newBuilder().setGameId(gameId).setViewerSeat(0).setSeq(seq)
            .setPassPriority(PassPriority.getDefaultInstance())).build();
    }

    private static AdapterMessage decision(String gameId, long id, long appliedSeq) {
        Candidate pass = Candidate.newBuilder().setPass(PassCandidate.getDefaultInstance()).build();
        return AdapterMessage.newBuilder().setDecisionRequest(DecisionRequest.newBuilder()
            .setProtocolVersion(Versions.PROTOCOL).setGameId(gameId).setDecisionId(id).setSeat(0).setAppliedSeq(appliedSeq)
            .setSeed(7).setType(DecisionType.DECISION_TYPE_PRIORITY).addCandidates(pass).addCandidates(pass)
            .setConstraints(SelectionConstraints.newBuilder().setMin(1).setMax(1))).build();
    }

    private static AdapterMessage gameOver(String gameId, boolean withPrivileged) {
        GameOver.Builder g = GameOver.newBuilder().setGameId(gameId).setTurns(3);
        if (withPrivileged) {
            g.addPrivileged(PrivilegedRecord.newBuilder().setSeat(0));
        }
        return AdapterMessage.newBuilder().setGameOver(g).build();
    }

    /** Collects what agent-core sends on one stream. */
    private static final class Received implements StreamObserver<AgentMessage> {
        final List<AgentMessage> messages = new ArrayList<>();
        final CountDownLatch closed = new CountDownLatch(1);
        Throwable error;

        @Override
        public void onNext(AgentMessage m) {
            messages.add(m);
        }

        @Override
        public void onError(Throwable t) {
            error = t;
            closed.countDown();
        }

        @Override
        public void onCompleted() {
            closed.countDown();
        }
    }

    private Received play(List<AdapterMessage> messages) throws InterruptedException {
        Received r = new Received();
        StreamObserver<AdapterMessage> out = AgentCoreGrpc.newStub(channel).play(r);
        for (AdapterMessage m : messages) {
            out.onNext(m);
        }
        r.closed.await(5, TimeUnit.SECONDS);
        return r;
    }

    @Test
    void handshakeChecksVersionAndDuplicates() {
        AgentCoreGrpc.AgentCoreBlockingStub stub = AgentCoreGrpc.newBlockingStub(channel);
        assertTrue(stub.handshake(hello("g1", Mode.MODE_TRAINING)).getAccepted());
        assertFalse(stub.handshake(hello("g1", Mode.MODE_TRAINING)).getAccepted());
        assertFalse(stub.handshake(hello("g2", Mode.MODE_TRAINING).toBuilder().setProtocolVersion(1).build()).getAccepted());
        assertFalse(stub.handshake(hello("g3", Mode.MODE_UNSPECIFIED)).getAccepted());
    }

    @Test
    void handshakeRefusesAModelOfAnotherEncoder() {
        String name = InProcessServerBuilder.generateName();
        Agent other = new Agent(new magicdosa.agent.policy.Policy() {
            @Override
            public String modelVersion() {
                return "old-model";
            }

            @Override
            public int encoderVersion() {
                return magicdosa.agent.AgentCore.ENCODER_VERSION - 1;
            }

            @Override
            public magicdosa.protocol.v1.DecisionResponse decide(DecisionRequest request) {
                throw new AssertionError("no decisions without a handshake");
            }
        });
        Server s = null;
        ManagedChannel c = null;
        try {
            s = InProcessServerBuilder.forName(name).directExecutor().addService(AgentCoreServer.service(other)).build().start();
            c = InProcessChannelBuilder.forName(name).directExecutor().build();
            var reply = AgentCoreGrpc.newBlockingStub(c).handshake(hello("g1", Mode.MODE_TRAINING));
            assertFalse(reply.getAccepted());
            assertTrue(reply.getReason().contains("encoder version"), reply.getReason());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        } finally {
            if (c != null) {
                c.shutdownNow();
            }
            if (s != null) {
                s.shutdownNow();
            }
        }
    }

    @Test
    void answersDecisionsAndClosesAfterGameOver() throws Exception {
        AgentCoreGrpc.newBlockingStub(channel).handshake(hello("g1", Mode.MODE_TRAINING));
        Received r = play(List.of(event("g1", 1), event("g1", 2), decision("g1", 1, 2), gameOver("g1", true)));
        assertNull(r.error);
        assertEquals(1, r.messages.size());
        var step = r.messages.get(0).getDecisionResponse().getSteps(0);
        assertEquals(SearchSkipReason.SEARCH_SKIP_REASON_NOT_AVAILABLE, step.getMethod().getSkipped());
        assertTrue(agent.protocolErrors().isEmpty());
        assertTrue(agent.summary().startsWith("games finished=1"));
    }

    @Test
    void sameSeedSameAnswer() throws Exception {
        AgentCoreGrpc.AgentCoreBlockingStub stub = AgentCoreGrpc.newBlockingStub(channel);
        stub.handshake(hello("a", Mode.MODE_TRAINING));
        stub.handshake(hello("b", Mode.MODE_TRAINING));
        Received a = play(List.of(decision("a", 1, 0), gameOver("a", false)));
        Received b = play(List.of(decision("b", 1, 0), gameOver("b", false)));
        assertEquals(a.messages.get(0).getDecisionResponse().getChosenList(),
            b.messages.get(0).getDecisionResponse().getChosenList());
    }

    @Test
    void sequenceGapClosesTheStream() throws Exception {
        AgentCoreGrpc.newBlockingStub(channel).handshake(hello("g1", Mode.MODE_TRAINING));
        Received r = play(List.of(event("g1", 1), event("g1", 3)));
        assertEquals(Status.Code.FAILED_PRECONDITION, Status.fromThrowable(r.error).getCode());
        assertEquals(1, agent.protocolErrors().size());
    }

    @Test
    void staleAppliedSeqClosesTheStream() throws Exception {
        AgentCoreGrpc.newBlockingStub(channel).handshake(hello("g1", Mode.MODE_TRAINING));
        Received r = play(List.of(event("g1", 1), decision("g1", 1, 0)));
        assertEquals(Status.Code.FAILED_PRECONDITION, Status.fromThrowable(r.error).getCode());
    }

    @Test
    void streamWithoutHandshakeIsRefused() throws Exception {
        Received r = play(List.of(event("nope", 1)));
        assertEquals(Status.Code.FAILED_PRECONDITION, Status.fromThrowable(r.error).getCode());
    }

    @Test
    void privilegedInInferenceModeIsRefused() throws Exception {
        AgentCoreGrpc.newBlockingStub(channel).handshake(hello("g1", Mode.MODE_INFERENCE));
        Received r = play(List.of(gameOver("g1", true)));
        assertEquals(Status.Code.FAILED_PRECONDITION, Status.fromThrowable(r.error).getCode());
    }
}
