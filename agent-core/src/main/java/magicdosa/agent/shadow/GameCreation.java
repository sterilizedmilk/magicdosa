package magicdosa.agent.shadow;

import forge.game.Game;
import forge.game.GameRules;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;

import java.util.List;

/**
 * Every Forge game in this JVM is created here.
 *
 * Forge numbers games with an unsynchronized static counter ({@code ++maxId} in {@link Game}), and that id keys
 * {@code AITimeoutTracker}. Two games created at once on different threads can get the same id, so creation is
 * serialized by one JVM-wide lock. Shadow games (via {@link GameCopier}), harnesses, benchmarks and the
 * forge-adapter all go through it.
 */
public final class GameCreation {
    private static final Object LOCK = new Object();

    private GameCreation() {}

    /** The next game of {@code match} ({@link Match#createGame()} under the lock). */
    public static Game createGame(Match match) {
        synchronized (LOCK) {
            return match.createGame();
        }
    }

    /** {@code new Game(players, rules, match)} under the lock. */
    public static Game newGame(List<RegisteredPlayer> players, GameRules rules, Match match) {
        synchronized (LOCK) {
            return new Game(players, rules, match);
        }
    }
}
