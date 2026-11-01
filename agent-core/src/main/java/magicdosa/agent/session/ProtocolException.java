package magicdosa.agent.session;

/** The adapter broke the protocol; the game's stream is closed with this message. */
public final class ProtocolException extends Exception {
    public ProtocolException(String message) {
        super(message);
    }
}
