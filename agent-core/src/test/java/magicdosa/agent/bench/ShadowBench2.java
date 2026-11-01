package magicdosa.agent.bench;
import magicdosa.agent.shadow.GameCopier;

import forge.ai.simulation.GameSimulator;
import forge.deck.Deck;
import magicdosa.agent.DeckFiles;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** (a) cost of copies with partial libraries, (b) concurrent copies of one shadow, (c) one applied action per sim. */
public final class ShadowBench2 {
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
        forge.gui.GuiBase.setInterface(new forge.GuiDesktop());
        forge.model.FModel.initialize(null, null);
        Deck deckA = DeckFiles.load(new File(args[0], "IzzetProwess.dck"));
        Deck deckB = DeckFiles.load(new File(args[0], "EsperBlink.dck"));
        Thread t = new Thread(() -> {
            try { run(deckA, deckB); } catch (Throwable e) { e.printStackTrace(System.out); }
        }, "Game-bench");
        t.start();
        t.join();
        System.exit(0);
    }

    static double timeCopies(Game g, int n) {
        for (int i = 0; i < 100; i++) new GameCopier(g).makeCopy();
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) new GameCopier(g).makeCopy();
        return (System.nanoTime() - t0) / 1e6 / n;
    }

    static void run(Deck a, Deck b) throws Exception {
        Game g = ShadowBench.board(a, b);
        String fp0 = ShadowBench.fingerprint(g);

        // (a) partial libraries
        for (int lim : new int[] { -1, 10, 0 }) {
            GameCopier.LIBRARY_LIMIT = lim;
            double ms = timeCopies(g, 400);
            Game c = new GameCopier(g).makeCopy();
            System.out.printf("LIBRARY_LIMIT=%3d : %.2f ms/copy, cards copied=%d%n", lim, ms, c.getCardsInGame().size());
        }
        GameCopier.LIBRARY_LIMIT = -1;

        // (b) 4 threads copying the same (idle) shadow at once
        int threads = 4, per = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger bad = new AtomicInteger(), errs = new AtomicInteger();
        long t0 = System.nanoTime();
        List<Future<?>> fs = new ArrayList<>();
        for (int k = 0; k < threads; k++) {
            fs.add(pool.submit(() -> {
                for (int i = 0; i < per; i++) {
                    try {
                        Game c = new GameCopier(g).makeCopy();
                        if (!ShadowBench.fingerprint(c).equals(fp0)) bad.incrementAndGet();
                    } catch (Throwable e) { errs.incrementAndGet(); }
                }
            }));
        }
        for (Future<?> f : fs) f.get();
        double total = (System.nanoTime() - t0) / 1e6;
        pool.shutdown();
        System.out.printf("CONCURRENT %d threads x %d copies: %.2f ms wall (%.2f ms/copy effective), mismatches=%d, errors=%d, original unchanged=%s%n",
                threads, per, total, total / (threads * per), bad.get(), errs.get(), ShadowBench.fingerprint(g).equals(fp0));

        // (c) one action per sim: copy + cast Lightning Bolt at the opponent + resolve (what a value-net leaf would cost)
        Player me = g.getPhaseHandler().getPlayerTurn();
        Card bolt = null;
        for (Card c : me.getCardsIn(ZoneType.Hand)) if (c.getName().equals("Lightning Bolt")) bolt = c;
        SpellAbility sa = bolt.getFirstSpellAbility();
        sa.setActivatingPlayer(me);
        sa.resetTargets();
        sa.getTargets().add(me.getWeakestOpponent());
        int n = 300, ok = 0;
        for (int i = 0; i < 50; i++) new GameSimulator(g, me).applySpellAbility(sa);
        long s = System.nanoTime();
        int oppLife = -1;
        for (int i = 0; i < n; i++) {
            GameSimulator sim = new GameSimulator(g, me);
            sim.applySpellAbility(sa);
            Game sg = sim.getSimulatedGameState();
            oppLife = sg.getPlayer(me.getWeakestOpponent().getId()).getLife();
            if (oppLife == me.getWeakestOpponent().getLife() - 3) ok++;
        }
        System.out.printf("COPY+APPLY one spell : %.2f ms per sim, bolt resolved correctly %d/%d (opp life %d -> %d)%n",
                (System.nanoTime() - s) / 1e6 / n, ok, n, me.getWeakestOpponent().getLife(), oppLife);
    }
}
