package magicdosa.agent.bench;

import magicdosa.adapter.forge.GameCreation;

import forge.GuiDesktop;
import forge.ai.simulation.GameCopier;
import forge.deck.Deck;
import magicdosa.agent.DeckFiles;
import forge.game.*;
import forge.game.player.RegisteredPlayer;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

import java.io.File;
import java.util.*;

/** Times GameCopier.makeCopy() on a mid-game board built with GameState. */
public final class CopyBench {
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        Deck deckA = DeckFiles.load(new File(args[0], "IzzetProwess.dck"));
        Deck deckB = DeckFiles.load(new File(args[0], "EsperBlink.dck"));

        Thread t = new Thread(() -> {
            try { run(deckA, deckB); } catch (Exception e) { e.printStackTrace(); }
        }, "Game-bench"); // a "Game" thread, so GameAction.invoke runs inline
        t.start();
        t.join();
        System.exit(0);
    }

    static void run(Deck a, Deck b) {
        GameRules rules = new GameRules(GameType.Constructed);
        List<RegisteredPlayer> ps = new ArrayList<>();
        RegisteredPlayer p1 = new RegisteredPlayer(a); p1.setPlayer(GamePlayerUtil.createAiPlayer("A", 0));
        RegisteredPlayer p2 = new RegisteredPlayer(b); p2.setPlayer(GamePlayerUtil.createAiPlayer("B", 1));
        ps.add(p1); ps.add(p2);
        Match m = new Match(rules, ps, "bench");
        Game g = GameCreation.createGame(m);
        g.setNoGUIUser();

        StringBuilder lib = new StringBuilder();
        for (int i = 0; i < 40; i++) lib.append(i % 3 == 0 ? "Mountain;" : "Lightning Bolt;");
        GameState st = new GameState() {};
        st.parse(Arrays.asList(
            "turn=6", "activeplayer=human", "activephase=MAIN1",
            "humanlife=11", "ailife=7",
            "humanhand=Lightning Bolt;Lava Spike;Boros Charm;Eidolon of the Great Revel",
            "humanlibrary=" + lib,
            "humangraveyard=Rift Bolt;Lightning Helix;Skewer the Critics",
            "humanbattlefield=Mountain;Mountain;Sacred Foundry;Inspiring Vantage;Goblin Guide|SummonSick;Monastery Swiftspear|Counters:P1P1=1;Eidolon of the Great Revel",
            "aihand=Fireblast;Searing Blaze;Mountain",
            "ailibrary=" + lib,
            "aigraveyard=Bloodghast;Lava Spike",
            "aibattlefield=Mountain;Mountain;Mountain;Mountain;Kari Zev, Perimeter Grunt;Goblin Guide|Tapped;Monastery Swiftspear"
        ));
        st.applyToGame(g);
        System.out.println("cards in game: " + g.getCardsInGame().size());

        // warm-up, then timed copies
        for (int i = 0; i < 200; i++) new GameCopier(g).makeCopy();
        int n = 1000;
        Runtime rt = Runtime.getRuntime();
        System.gc();
        long mem0 = rt.totalMemory() - rt.freeMemory();
        long t0 = System.nanoTime();
        List<Game> keep = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Game c = new GameCopier(g).makeCopy();
            if (i < 100) keep.add(c);
        }
        long ns = System.nanoTime() - t0;
        System.gc();
        long mem1 = rt.totalMemory() - rt.freeMemory();
        System.out.printf("makeCopy: %.3f ms/copy (%d copies), ~%.2f MB per retained copy%n",
                ns / 1e6 / n, n, (mem1 - mem0) / 1024.0 / 1024.0 / keep.size());
        Game c = keep.get(0);
        System.out.println("copy life A/B = " + c.getPlayers().get(0).getLife() + "/" + c.getPlayers().get(1).getLife()
                + ", battlefield = " + c.getCardsIn(forge.game.zone.ZoneType.Battlefield).size()
                + " (orig " + g.getCardsIn(forge.game.zone.ZoneType.Battlefield).size() + ")");
    }
}
