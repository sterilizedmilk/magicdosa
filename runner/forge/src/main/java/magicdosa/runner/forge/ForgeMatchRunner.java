package magicdosa.runner.forge;

import magicdosa.adapter.forge.AgentLobbyPlayer;
import magicdosa.adapter.forge.AgentPlayerController;
import magicdosa.adapter.forge.CardKeys;
import magicdosa.adapter.forge.DecisionClient;
import magicdosa.adapter.forge.DecisionStats;
import magicdosa.adapter.forge.ForgeGameSession;
import magicdosa.adapter.forge.ForgeRandom;
import magicdosa.adapter.forge.ObservedAiController;

import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import magicdosa.adapter.forge.GameCreation;
import magicdosa.protocol.v1.GameOver;
import magicdosa.protocol.v1.Mode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plays one Bo3 match of real Forge games with agent-core deciding for {@code agentSeats} and Forge AI for the other
 * seats (design: forge-adapter 설계). Forge must be initialized (headless, FModel) and the working directory must be
 * forge/forge-gui.
 */
public final class ForgeMatchRunner {
    /** What a match produced. */
    public record Result(int[] gamesWon, int games, DecisionStats stats, List<String> logDigests, List<GameOver> overs) {}

    private final DecisionClient client;
    private final CardKeys keys;
    private final Mode mode;
    private final String engineVersion;

    private final boolean reproducible;
    /** Called with each game before it starts (test harnesses). */
    private java.util.function.BiConsumer<Game, ForgeGameSession> onGame = (g, s) -> { };

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

    public ForgeMatchRunner onGame(java.util.function.BiConsumer<Game, ForgeGameSession> hook) {
        this.onGame = hook;
        return this;
    }

    /** Plays the match; one gzipped log per game in {@code logDir} (null: no logs). */
    public Result playMatch(String matchId, Deck deck0, Deck deck1, Set<Integer> agentSeats, long seed, Path logDir) throws Exception {
        ForgeRandom.seed(seed);
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setGamesPerMatch(3);
        List<RegisteredPlayer> players = new ArrayList<>();
        List<AgentLobbyPlayer> lobbies = new ArrayList<>();
        List<Deck> decks = List.of(deck0, deck1);
        for (int seat = 0; seat < 2; seat++) {
            // Forge changes the registered deck in place when sideboarding: each match plays its own copy
            RegisteredPlayer rp = new RegisteredPlayer(new Deck(decks.get(seat)));
            AgentLobbyPlayer lobby = new AgentLobbyPlayer("seat" + seat);
            lobbies.add(lobby);
            rp.setPlayer(lobby);
            players.add(rp);
        }
        Match match = new Match(rules, players, matchId);
        int[] won = new int[2];
        DecisionStats stats = new DecisionStats();
        List<String> digests = new ArrayList<>();
        List<GameOver> overs = new ArrayList<>();
        Map<Integer, Map<String, Integer>> seenEarlier = new HashMap<>();
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
                session.seenInEarlierGames(seenEarlier);
                session.priorityCheck(CandidateCheck.HOOK);
                for (AgentLobbyPlayer l : lobbies) {
                    l.session(session);
                }
                List<String> kinds = new ArrayList<>();
                for (Player p : game.getRegisteredPlayers()) {
                    int seat = session.seat(p);
                    if (agentSeats.contains(seat)) {
                        p.dangerouslySetController(new AgentPlayerController(game, p, p.getLobbyPlayer(), session));
                        kinds.add("agent");
                    } else {
                        p.dangerouslySetController(new ObservedAiController(game, p, p.getLobbyPlayer(), session));
                        kinds.add("forge-ai");
                    }
                }
                session.start(engineVersion, kinds, seed);
                onGame.accept(game, session);
                match.startGame(game);
                var over = session.finish();
                overs.add(over);
                if (over.hasWinnerSeat()) {
                    won[over.getWinnerSeat()]++;
                }
                stats.add(session.stats());
                for (int seat : agentSeats) {
                    session.seenOpponentCards(seat).forEach((oracle, n) ->
                        seenEarlier.computeIfAbsent(seat, k -> new HashMap<>()).merge(oracle, n, Math::max));
                }
                digests.add(log.digest());
            }
        }
        return new Result(won, gameNumber, stats, digests, overs);
    }
}
