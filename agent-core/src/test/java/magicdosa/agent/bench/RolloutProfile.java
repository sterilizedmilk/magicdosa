package magicdosa.agent.bench;
import magicdosa.agent.shadow.GameCopier;

import forge.ai.PlayerControllerAi;
import forge.ai.simulation.GameSimulator;
import forge.deck.Deck;
import magicdosa.agent.DeckFiles;
import forge.game.Game;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.io.File;
import java.util.List;

/** Splits the Flat-MC style rollout of ShadowBench into its parts and times each one. */
public final class RolloutProfile {
    static long tChoose, tPlay, tResolve, tAdvance, nChoose, nPlay;

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
        forge.gui.GuiBase.setInterface(new forge.GuiDesktop());
        forge.model.FModel.initialize(null, null);
        Deck deckA = DeckFiles.load(new File(args[0], "IzzetProwess.dck"));
        Deck deckB = DeckFiles.load(new File(args[0], "EsperBlink.dck"));
        int n = Integer.parseInt(args[1]);
        Thread t = new Thread(() -> {
            try { run(deckA, deckB, n); } catch (Throwable e) { e.printStackTrace(System.out); }
        }, "Game-bench");
        t.start();
        t.join();
        System.exit(0);
    }

    static void rollout(Game g) {
        Player active = g.getPhaseHandler().getPlayerTurn();
        Player opp = active.getWeakestOpponent();
        PlayerControllerAi pc = (PlayerControllerAi) active.getController();
        for (int i = 0; i < 4 && !g.isGameOver(); i++) {
            long a = System.nanoTime();
            List<SpellAbility> next = pc.chooseSpellAbilityToPlay();
            long b = System.nanoTime();
            tChoose += b - a; nChoose++;
            if (next == null || next.isEmpty()) break;
            pc.playChosenSpellAbility(next.get(0));
            long c = System.nanoTime();
            tPlay += c - b; nPlay++;
            GameSimulator.resolveStack(g, opp);
            tResolve += System.nanoTime() - c;
        }
        long d = System.nanoTime();
        if (!g.isGameOver() && g.getPhaseHandler().getPhase().isBefore(PhaseType.MAIN2)) {
            g.getPhaseHandler().devAdvanceToPhase(PhaseType.MAIN2, () -> GameSimulator.resolveStack(g, opp));
        }
        tAdvance += System.nanoTime() - d;
    }

    static void run(Deck a, Deck b, int n) {
        Game g = ShadowBench.board(a, b);
        GameCopier.LIBRARY_LIMIT = Integer.getInteger("libLimit", -1);
        forge.game.spellability.Spell.setPerformanceMode(Boolean.getBoolean("perfMode"));
        System.out.println("performance mode = " + forge.game.spellability.Spell.isPerformanceMode());
        for (int i = 0; i < 60; i++) rollout(new GameCopier(g).makeCopy());
        tChoose = tPlay = tResolve = tAdvance = nChoose = nPlay = 0;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) rollout(new GameCopier(g).makeCopy());
        double total = (System.nanoTime() - t0) / 1e6 / n;
        System.out.printf("PER ROLLOUT (libLimit=%d) total %.2f ms (incl. copy)%n", GameCopier.LIBRARY_LIMIT, total);
        System.out.printf("  chooseSpellAbilityToPlay : %.2f ms per rollout (%.2f calls, %.2f ms per call)%n",
                tChoose / 1e6 / n, (double) nChoose / n, tChoose / 1e6 / Math.max(1, nChoose));
        System.out.printf("  playChosenSpellAbility   : %.2f ms per rollout (%.2f calls)%n", tPlay / 1e6 / n, (double) nPlay / n);
        System.out.printf("  resolveStack             : %.2f ms per rollout%n", tResolve / 1e6 / n);
        System.out.printf("  devAdvanceToPhase(MAIN2) : %.2f ms per rollout (combat: attack + block decisions)%n", tAdvance / 1e6 / n);
    }
}
