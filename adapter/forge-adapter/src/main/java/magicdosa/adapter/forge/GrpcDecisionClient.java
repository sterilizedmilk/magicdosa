package magicdosa.adapter.forge;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import magicdosa.protocol.v1.AdapterMessage;
import magicdosa.protocol.v1.AgentCoreGrpc;
import magicdosa.protocol.v1.AgentMessage;
import magicdosa.protocol.v1.DecisionRequest;
import magicdosa.protocol.v1.DecisionResponse;
import magicdosa.protocol.v1.Hello;
import magicdosa.protocol.v1.HelloReply;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * {@link DecisionClient} over gRPC (design: 설계에 미치는 영향 통신; Q12): Hello as a unary call, then one bidirectional
 * stream per game, opened by the game's first message and closed after its game over. A decision waits for agent-core
 * without a time limit (Q8); a closed or failed stream makes every later call of that game throw, which the session
 * records as an unintended fallback.
 */
public final class GrpcDecisionClient implements DecisionClient, AutoCloseable {
    private final ManagedChannel channel;
    private final AgentCoreGrpc.AgentCoreBlockingStub blocking;
    private final AgentCoreGrpc.AgentCoreStub async;
    private final Map<String, GameStream> streams = new ConcurrentHashMap<>();

    public GrpcDecisionClient(String host, int port) {
        channel = NettyChannelBuilder.forAddress(host, port).usePlaintext().build();
        blocking = AgentCoreGrpc.newBlockingStub(channel);
        async = AgentCoreGrpc.newStub(channel);
    }

    @Override
    public HelloReply hello(Hello hello) {
        return blocking.handshake(hello);
    }

    @Override
    public void send(AdapterMessage message) throws Exception {
        String gameId = gameId(message);
        GameStream s = streams.computeIfAbsent(gameId, id -> new GameStream());
        s.send(message);
        if (message.hasGameOver()) {
            streams.remove(gameId);
            s.finish();
        }
    }

    @Override
    public DecisionResponse decide(DecisionRequest request) throws Exception {
        GameStream s = streams.computeIfAbsent(request.getGameId(), id -> new GameStream());
        s.send(AdapterMessage.newBuilder().setDecisionRequest(request).build());
        return s.response();
    }

    @Override
    public void close() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    private static String gameId(AdapterMessage m) {
        switch (m.getMessageCase()) {
            case EVENT:
                return m.getEvent().getGameId();
            case SNAPSHOT:
                return m.getSnapshot().getGameId();
            case DECISION_REQUEST:
                return m.getDecisionRequest().getGameId();
            case GAME_OVER:
                return m.getGameOver().getGameId();
            default:
                throw new IllegalArgumentException("empty adapter message");
        }
    }

    /** One game's stream. Calls come from the game's thread (or the AI threads it waits on), one at a time. */
    private final class GameStream implements StreamObserver<AgentMessage> {
        private static final Object CLOSED = new Object();
        private final StreamObserver<AdapterMessage> out = async.play(this);
        private final LinkedBlockingQueue<Object> responses = new LinkedBlockingQueue<>();
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile Throwable error;
        private volatile boolean completed;

        synchronized void send(AdapterMessage m) {
            check();
            out.onNext(m);
        }

        DecisionResponse response() throws InterruptedException {
            Object r = responses.take();
            if (r == CLOSED) {
                responses.add(CLOSED); // later calls fail too
                check();
                throw new IllegalStateException("agent-core closed the stream before answering");
            }
            return (DecisionResponse) r;
        }

        /** After the game over: agent-core closes the stream; any error it reports fails the game. */
        void finish() throws InterruptedException {
            synchronized (this) {
                out.onCompleted();
            }
            done.await();
            if (error != null) {
                throw new IllegalStateException("agent-core stream failed: " + error.getMessage(), error);
            }
        }

        private void check() {
            if (error != null) {
                throw new IllegalStateException("agent-core stream failed: " + error.getMessage(), error);
            }
            if (completed) {
                throw new IllegalStateException("agent-core stream already closed");
            }
        }

        @Override
        public void onNext(AgentMessage m) {
            if (m.hasDecisionResponse()) {
                responses.add(m.getDecisionResponse());
            } else {
                // Order is guaranteed within one stream, so a resend request (Q12) means something is wrong here.
                error = new IllegalStateException("unexpected " + m.getMessageCase() + " from agent-core");
                responses.add(CLOSED);
            }
        }

        @Override
        public void onError(Throwable t) {
            error = t;
            responses.add(CLOSED);
            done.countDown();
        }

        @Override
        public void onCompleted() {
            completed = true;
            responses.add(CLOSED);
            done.countDown();
        }
    }
}
