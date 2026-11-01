package magicdosa.adapter.forge;

import forge.LobbyPlayer;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.player.GamePlayerUtil;
import magicdosa.agent.shadow.GameCreation;
import magicdosa.protocol.v1.Mode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Plays one Bo3 match of real Forge games with agent-core deciding for {@code agentSeats} and Forge AI for the other
 * seats (design: forge-adapter 설계). Forge must be initialized (headless, FModel) and the working directory must be
 * forge/forge-gui.
 */
public final class ForgeMatchRunner {
    /** What a match produced. */
    public record Result(int[] gamesWon, int games, DecisionStats stats, List<String> logDigests) {}

    private final DecisionClient client;
    private final CardKeys keys;
    private final Mode mode;
    private final String engineVersion;

    private final boolean reproducible;

    /**
     * @param reproducible regression runs: Forge AI gets a long wall-clock timeout so that load does not change its
     *                     decisions (the training path keeps Forge's default)
     */
    public ForgeMatchRunner(DecisionClient client, CardKeys keys, Mode mode, String engineVersion, boolean reproducible) {
        this.client = client;
        this.keys = keys;
        this.mode = mode;
        this.engineVersion = engineVersion;
        this.reproducible = reproducible;
        ForgeRandom.install();
    }

    /** Plays the match; one gzipped log per game in {@code logDir} (null: no logs). */
    public Result playMatch(String matchId, Deck deck0, Deck deck1, Set<Integer> agentSeats, long seed, Path logDir) throws Exception {
        ForgeRandom.seed(seed);
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setGamesPerMatch(3);
        List<RegisteredPlayer> players = new ArrayList<>();
        List<Deck> decks = List.of(deck0, deck1);
        for (int seat = 0; seat < 2; seat++) {
            RegisteredPlayer rp = new RegisteredPlayer(decks.get(seat));
            LobbyPlayer lobby = GamePlayerUtil.createAiPlayer("seat" + seat, seat);
            rp.setPlayer(lobby);
            players.add(rp);
        }
        Match match = new Match(rules, players, matchId);
        int[] won = new int[2];
        DecisionStats stats = new DecisionStats();
        List<String> digests = new ArrayList<>();
        int gameNumber = 0;
        while (!match.isMatchOver() && gameNumber < 3) {
            gameNumber++;
            Game game = GameCreation.createGame(match);
            game.setNoGUIUser();
            if (reproducible) {
                game.AI_TIMEOUT = 600;
            }
            try (GameLogWriter log = logDir == null ? GameLogWriter.discard()
                    : GameLogWriter.open(logDir.resolve(matchId + "-g" + gameNumber + ".pb.gz"))) {
                ForgeGameSession session = new ForgeGameSession(game, matchId, gameNumber, decks, won.clone(), agentSeats,
                    client, keys, log, mode, seed * 31 + gameNumber);
                List<String> kinds = new ArrayList<>();
                for (Player p : game.getRegisteredPlayers()) {
                    int seat = session.seat(p);
                    if (agentSeats.contains(seat)) {
                        p.dangerouslySetController(new RlPlayerController(game, p, p.getLobbyPlayer(), session));
                        kinds.add("agent");
                    } else {
                        p.dangerouslySetController(new ObservedAiController(game, p, p.getLobbyPlayer(), session));
                        kinds.add("forge-ai");
                    }
                }
                session.start(engineVersion, kinds, seed);
                match.startGame(game);
                var over = session.finish();
                if (over.hasWinnerSeat()) {
                    won[over.getWinnerSeat()]++;
                }
                stats.add(session.stats());
                digests.add(log.digest());
            }
        }
        return new Result(won, gameNumber, stats, digests);
    }
}
