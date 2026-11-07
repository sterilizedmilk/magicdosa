package magicdosa.agent.server;

import io.grpc.BindableService;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import magicdosa.agent.session.Agent;
import magicdosa.agent.session.GameSession;
import magicdosa.agent.session.ProtocolException;
import magicdosa.protocol.v1.AdapterMessage;
import magicdosa.protocol.v1.AgentCoreGrpc;
import magicdosa.protocol.v1.AgentMessage;
import magicdosa.protocol.v1.DecisionResponse;
import magicdosa.protocol.v1.Hello;
import magicdosa.protocol.v1.HelloReply;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * gRPC transport of agent-core (design: 설계에 미치는 영향 통신; Q12): Hello as a unary call, then one bidirectional
 * stream per game. A protocol error closes that game's stream with FAILED_PRECONDITION; the adapter then records an
 * unintended fallback, never a silent continuation. agent-core closes the stream after the game over.
 */
public final class AgentCoreServer {
    /** Largest message agent-core accepts. */
    static final int MAX_MESSAGE_BYTES = 256 << 20;

    private final Server server;

    private AgentCoreServer(Server server) {
        this.server = server;
    }

    /** Starts listening on localhost; port 0 picks a free port. */
    public static AgentCoreServer start(int port, Agent agent) throws IOException {
        Server server = NettyServerBuilder.forAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
            InsecureServerCredentials.create())
            // a game over carries the hidden state recorded at every decision of the game (Q11): long games exceed 4 MB
            .maxInboundMessageSize(MAX_MESSAGE_BYTES)
            .addService(service(agent)).build().start();
        return new AgentCoreServer(server);
    }

    /** The service alone, for in-process servers in tests. */
    static BindableService service(Agent agent) {
        return new Service(agent);
    }

    public int port() {
        return server.getPort();
    }

    public void awaitTermination() throws InterruptedException {
        server.awaitTermination();
    }

    public void shutdown() throws InterruptedException {
        server.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    private static final class Service extends AgentCoreGrpc.AgentCoreImplBase {
        private final Agent agent;

        Service(Agent agent) {
            this.agent = agent;
        }

        @Override
        public void handshake(Hello request, StreamObserver<HelloReply> out) {
            out.onNext(agent.hello(request));
            out.onCompleted();
        }

        @Override
        public StreamObserver<AdapterMessage> play(StreamObserver<AgentMessage> out) {
            return new StreamObserver<>() {
                private GameSession session;
                private boolean closed;

                @Override
                public void onNext(AdapterMessage m) {
                    if (closed) {
                        return;
                    }
                    try {
                        if (session == null) {
                            session = agent.open(gameId(m));
                        }
                        DecisionResponse r = session.accept(m);
                        if (r != null) {
                            out.onNext(AgentMessage.newBuilder().setDecisionResponse(r).build());
                        }
                        if (session.over()) {
                            closed = true;
                            out.onCompleted();
                        }
                    } catch (ProtocolException e) {
                        fail(Status.FAILED_PRECONDITION.withDescription(e.getMessage()), m);
                    } catch (RuntimeException e) {
                        fail(Status.INTERNAL.withDescription(e.toString()).withCause(e), m);
                    }
                }

                private void fail(Status status, AdapterMessage m) {
                    System.err.println("agent-core: closing the stream of " + gameId(m) + ": " + status.getDescription());
                    closed = true;
                    agent.abandoned(gameId(m));
                    out.onError(status.asRuntimeException());
                }

                @Override
                public void onError(Throwable t) {
                    if (session != null && !session.over()) {
                        agent.abandoned(session.gameId());
                    }
                }

                @Override
                public void onCompleted() {
                    if (closed) {
                        return;
                    }
                    if (session != null && !session.over()) {
                        agent.abandoned(session.gameId());
                    }
                    closed = true;
                    out.onCompleted();
                }
            };
        }
    }

    /** Every adapter message names its game. */
    static String gameId(AdapterMessage m) {
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
                return "";
        }
    }
}
