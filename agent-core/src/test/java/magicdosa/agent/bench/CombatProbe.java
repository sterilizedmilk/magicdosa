package magicdosa.agent.bench;

import forge.ai.PlayerControllerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.GameState;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.GuiDesktop;
import magicdosa.adapter.forge.GameCreation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Measurement only (phase-2 open risk, Broodscale m9-g2): how long Forge AI's attack and block declarations take as the
 * board grows. Both seats get {@code n} creatures of the given name; the active seat declares attackers, then the other
 * seat blocks. Wall-clock time is printed here and is never part of a decision.
 *
 * <pre>CombatProbe T:c_1_1_eldrazi_scion_sac 6 10 14 18</pre> (a card name or a {@code T:} token script)
 */
public final class CombatProbe {
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        String creature = args[0];
        Thread t = new Thread(() -> {
            for (int i = 1; i < args.length; i++) {
                int n = Integer.parseInt(args[i]);
                for (int rep = 0; rep < 2; rep++) {
                    probe(creature, n);
                }
            }
        }, "Game-combat-probe");
        t.start();
        t.join();
        System.exit(0);
    }

    static void probe(String creature, int n) {
        Deck d = new Deck();
        GameRules rules = new GameRules(GameType.Constructed);
        List<RegisteredPlayer> ps = new ArrayList<>();
        RegisteredPlayer p1 = new RegisteredPlayer(d);
        p1.setPlayer(GamePlayerUtil.createAiPlayer("A", 0));
        RegisteredPlayer p2 = new RegisteredPlayer(d);
        p2.setPlayer(GamePlayerUtil.createAiPlayer("B", 1));
        ps.add(p1);
        ps.add(p2);
        Game g0 = GameCreation.createGame(new Match(rules, ps, "combat-probe"));
        Game g = g0;
        g.setNoGUIUser();
        g.AI_TIMEOUT = 600;
        // the defending seat: a share of the attacker's creatures (-Dprobe.defenders, default 1) and its life
        // (-Dprobe.life, default 20)
        int defenders = (int) Math.round(n * Double.parseDouble(System.getProperty("probe.defenders", "1")));
        String life = System.getProperty("probe.life", "20");
        StringBuilder board = new StringBuilder();
        for (int i = 0; i < n; i++) {
            board.append(creature).append(';');
        }
        StringBuilder defBoard = new StringBuilder();
        for (int i = 0; i < defenders; i++) {
            defBoard.append(creature).append(';');
        }
        StringBuilder lib = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            lib.append("Forest;");
        }
        GameState st = new GameState() {};
        st.parse(Arrays.asList("turn=9", "activeplayer=human", "activephase=COMBAT_DECLARE_ATTACKERS",
            "humanlife=20", "ailife=" + life, "humanlibrary=" + lib, "ailibrary=" + lib,
            "humanbattlefield=" + board + "Forest;Forest;Forest", "aibattlefield=" + defBoard + "Forest;Forest;Forest"));
        st.applyToGame(g);
        // on a copy, as search and the shadow's delegations meet it
        magicdosa.agent.shadow.GameCopier copier = new magicdosa.agent.shadow.GameCopier(g);
        g = copier.makeCopy();
        g.AI_TIMEOUT = 600;
        Player attacker = g.getPhaseHandler().getPlayerTurn();
        Player defender = attacker.getWeakestOpponent();
        Combat combat = new Combat(attacker);
        g.getPhaseHandler().setCombat(combat);
        long a0 = System.nanoTime();
        ((PlayerControllerAi) attacker.getController()).declareAttackers(attacker, combat);
        long a1 = System.nanoTime();
        int attackers = combat.getAttackers().size();
        ((PlayerControllerAi) defender.getController()).declareBlockers(defender, combat);
        long b1 = System.nanoTime();
        int blockers = combat.getAllBlockers().size();
        System.out.printf("COMBAT %s n=%d defenders=%d life=%s attack %.1f ms (%d attackers) block %.1f ms (%d blockers)%n", creature, n, defenders, life,
            (a1 - a0) / 1e6, attackers, (b1 - a1) / 1e6, blockers);
    }
}
