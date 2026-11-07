package magicdosa.adapter.forge;

import forge.ai.LobbyPlayerAi;
import forge.game.player.Player;
import forge.game.player.PlayerController;

import java.util.Set;

/**
 * The lobby player of every seat in a match the adapter runs. Forge asks the controlling player's lobby player for a
 * controller when one player controls another (Emrakul, the Promised End; 2026-11-06, deck item 4): an agent seat
 * decides the controlled player's choices through agent-core, an opponent seat's Forge AI plays a controlled agent
 * seat without asking agent-core. The session is set for each game.
 */
final class AgentLobbyPlayer extends LobbyPlayerAi {
    private ForgeGameSession session;

    AgentLobbyPlayer(String name) {
        super(name, Set.of());
    }

    void session(ForgeGameSession session) {
        this.session = session;
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        if (session == null) {
            return super.createMindSlaveController(master, slave);
        }
        if (session.isAgent(master)) {
            return new RlPlayerController(slave.getGame(), slave, slave.getLobbyPlayer(), session, master);
        }
        return new ObservedAiController(slave.getGame(), slave, slave.getLobbyPlayer(), session, false);
    }
}
