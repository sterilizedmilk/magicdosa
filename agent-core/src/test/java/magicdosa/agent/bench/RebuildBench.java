package magicdosa.agent.bench;
import magicdosa.agent.shadow.GameCopier;

import forge.deck.Deck;
import magicdosa.agent.DeckFiles;
import forge.game.Game;
import java.io.File;

/** Cost of building a fresh Forge game from a text snapshot (new Match + GameState.applyToGame), vs copying. */
public final class RebuildBench {
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
        forge.gui.GuiBase.setInterface(new forge.GuiDesktop());
        forge.model.FModel.initialize(null, null);
        Deck a = DeckFiles.load(new File(args[0], "IzzetProwess.dck"));
        Deck b = DeckFiles.load(new File(args[0], "EsperBlink.dck"));
        int n = Integer.parseInt(args[1]);
        Thread t = new Thread(() -> {
            for (int i = 0; i < 50; i++) ShadowBench.board(a, b);
            long t0 = System.nanoTime();
            Game g = null;
            for (int i = 0; i < n; i++) g = ShadowBench.board(a, b);
            System.out.printf("REBUILD (new game + applyToGame, %d cards): %.2f ms%n",
                    g.getCardsInGame().size(), (System.nanoTime() - t0) / 1e6 / n);
            for (int i = 0; i < 100; i++) new GameCopier(g).makeCopy();
            t0 = System.nanoTime();
            for (int i = 0; i < n; i++) new GameCopier(g).makeCopy();
            System.out.printf("COPY (external copier): %.2f ms%n", (System.nanoTime() - t0) / 1e6 / n);
        }, "Game-bench");
        t.start(); t.join(); System.exit(0);
    }
}
