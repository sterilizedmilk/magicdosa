package magicdosa.agent.bench;
import magicdosa.agent.shadow.GameCopier;
import magicdosa.agent.shadow.GameCreation;

import forge.GuiDesktop;
import forge.ai.PlayerControllerAi;
import forge.ai.simulation.GameSimulator;
import forge.deck.Deck;
import magicdosa.agent.DeckFiles;
import forge.game.*;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.undo.GameMemento;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

import java.io.File;
import java.util.*;

/**
 * Compares two ways of getting a scratch game for search on one mid-game board:
 *  (1) copy:    GameCopier.makeCopy() (Forge's own, and the same class compiled outside Forge),
 *  (2) restore: play a short rollout on the live game, then GameMemento.restore() it back.
 * Checks every result with a state fingerprint against the untouched original.
 */
public final class ShadowBench {
    static final ZoneType[] ZONES = { ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard, ZoneType.Library,
            ZoneType.Exile, ZoneType.Command };

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        Deck deckA = DeckFiles.load(new File(args[0], "IzzetProwess.dck"));
        Deck deckB = DeckFiles.load(new File(args[0], "EsperBlink.dck"));
        int rollouts = args.length > 1 ? Integer.parseInt(args[1]) : 200;
        Thread t = new Thread(() -> {
            try { run(deckA, deckB, rollouts); } catch (Throwable e) { e.printStackTrace(System.out); }
        }, "Game-bench"); // a "Game" thread, so GameAction.invoke runs inline
        t.start();
        t.join();
        System.exit(0);
    }

    static Game board(Deck a, Deck b) {
        GameRules rules = new GameRules(GameType.Constructed);
        List<RegisteredPlayer> ps = new ArrayList<>();
        RegisteredPlayer p1 = new RegisteredPlayer(a); p1.setPlayer(GamePlayerUtil.createAiPlayer("A", 0));
        RegisteredPlayer p2 = new RegisteredPlayer(b); p2.setPlayer(GamePlayerUtil.createAiPlayer("B", 1));
        ps.add(p1); ps.add(p2);
        Match m = new Match(rules, ps, "bench");
        Game g = GameCreation.createGame(m);
        g.setNoGUIUser();
        g.AI_TIMEOUT = 600;
        StringBuilder libA = new StringBuilder(), libB = new StringBuilder();
        String[] la = { "Mountain", "Lightning Bolt", "Lava Spike", "Goblin Guide", "Rift Bolt", "Boros Charm" };
        String[] lb = { "Mountain", "Lightning Bolt", "Fireblast", "Monastery Swiftspear", "Searing Blaze", "Lava Spike" };
        for (int i = 0; i < 40; i++) { libA.append(la[i % la.length]).append(';'); libB.append(lb[(i * 7) % lb.length]).append(';'); }
        GameState st = new GameState() {};
        st.parse(Arrays.asList(
            "turn=6", "activeplayer=human", "activephase=MAIN1",
            "humanlife=11", "ailife=9",
            "humanhand=Lightning Bolt;Lava Spike;Boros Charm;Eidolon of the Great Revel;Mountain",
            "humanlibrary=" + libA,
            "humangraveyard=Rift Bolt;Lightning Helix;Skewer the Critics",
            "humanbattlefield=Mountain;Mountain;Sacred Foundry;Inspiring Vantage;Goblin Guide|SummonSick;Monastery Swiftspear|Counters:P1P1=1;Eidolon of the Great Revel",
            "aihand=Fireblast;Searing Blaze;Mountain",
            "ailibrary=" + libB,
            "aigraveyard=Bloodghast;Lava Spike",
            "aibattlefield=Mountain;Mountain;Mountain;Mountain;Kari Zev, Perimeter Grunt;Goblin Guide|Tapped;Monastery Swiftspear"
        ));
        st.applyToGame(g);
        return g;
    }

    /** Everything a search would care about, in a stable textual form (card ids included). */
    static String fingerprint(Game g) {
        StringBuilder sb = new StringBuilder();
        sb.append("turn=").append(g.getPhaseHandler().getTurn())
          .append(" phase=").append(g.getPhaseHandler().getPhase())
          .append(" active=").append(g.getPhaseHandler().getPlayerTurn().getId())
          .append(" over=").append(g.isGameOver())
          .append(" stack=").append(g.getStack().size()).append('\n');
        for (Player p : g.getPlayers()) {
            sb.append("P").append(p.getId()).append(" life=").append(p.getLife())
              .append(" counters=").append(new TreeMap<>(toMap(p.getCounters().toString())))
              .append(" lands=").append(p.getLandsPlayedThisTurn())
              .append(" spells=").append(p.getSpellsCastThisTurn())
              .append(" lostThisTurn=").append(p.getLifeLostThisTurn())
              .append(" pool=").append(p.getManaPool().totalMana()).append('\n');
            for (ZoneType z : ZONES) {
                sb.append("  ").append(z).append(':');
                for (Card c : p.getCardsIn(z)) {
                    sb.append(' ').append(c.getId()).append(':').append(c.getName());
                    if (z == ZoneType.Battlefield) {
                        sb.append('[').append(c.isTapped() ? "T" : "").append(c.hasSickness() ? "S" : "")
                          .append(c.getNetPower()).append('/').append(c.getNetToughness())
                          .append(" d").append(c.getDamage())
                          .append(" c").append(c.getCounters())
                          .append(" ctl").append(c.getController().getId())
                          .append(c.isAttachedToEntity() ? " att" + c.getEntityAttachedTo().getId() : "")
                          .append(']');
                    }
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    static Map<String, String> toMap(String s) { Map<String, String> m = new HashMap<>(); m.put("v", s); return m; }

    /** A Flat-MC style rollout on game g: the active player plays up to 4 spells, then combat runs to main 2. */
    static void rollout(Game g) {
        Player active = g.getPhaseHandler().getPlayerTurn();
        Player opp = active.getWeakestOpponent();
        PlayerControllerAi pc = (PlayerControllerAi) active.getController();
        for (int i = 0; i < 4 && !g.isGameOver(); i++) {
            List<SpellAbility> next = pc.chooseSpellAbilityToPlay();
            if (next == null || next.isEmpty()) break;
            pc.playChosenSpellAbility(next.get(0));
            GameSimulator.resolveStack(g, opp);
        }
        if (!g.isGameOver() && g.getPhaseHandler().getPhase().isBefore(PhaseType.MAIN2)) {
            g.getPhaseHandler().devAdvanceToPhase(PhaseType.MAIN2, () -> GameSimulator.resolveStack(g, opp));
        }
    }

    static void run(Deck a, Deck b, int rollouts) {
        Game g = board(a, b);
        String fp0 = fingerprint(g);
        System.out.println("cards in game: " + g.getCardsInGame().size());

        // ---- (1) copies
        for (int i = 0; i < 100; i++) { new forge.ai.simulation.GameCopier(g).makeCopy(); new GameCopier(g).makeCopy(); }
        int n = 300;
        long t0 = System.nanoTime();
        Game cIn = null;
        for (int i = 0; i < n; i++) cIn = new forge.ai.simulation.GameCopier(g).makeCopy();
        double msIn = (System.nanoTime() - t0) / 1e6 / n;
        t0 = System.nanoTime();
        Game cExt = null;
        for (int i = 0; i < n; i++) cExt = new GameCopier(g).makeCopy();
        double msExt = (System.nanoTime() - t0) / 1e6 / n;
        System.out.printf("COPY forge GameCopier   : %.2f ms, identical=%s%n", msIn, fingerprint(cIn).equals(fp0));
        System.out.printf("COPY external copier    : %.2f ms, identical=%s%n", msExt, fingerprint(cExt).equals(fp0));

        // rollout cost on copies (copy + rollout), and a sample of where rollouts go
        long tc = 0, tr = 0;
        Set<String> endStates = new HashSet<>();
        int copyErrors = 0;
        for (int i = 0; i < rollouts; i++) {
            long s = System.nanoTime();
            Game c = new forge.ai.simulation.GameCopier(g).makeCopy();
            long m = System.nanoTime();
            try { rollout(c); } catch (RuntimeException e) { copyErrors++; }
            long e = System.nanoTime();
            tc += m - s; tr += e - m;
            endStates.add(lifeLine(c));
        }
        System.out.printf("COPY+ROLLOUT            : copy %.2f ms + rollout %.2f ms per sim, %d distinct end states, errors=%d%n",
                tc / 1e6 / rollouts, tr / 1e6 / rollouts, endStates.size(), copyErrors);

        // ---- (2) in-place rollout + memento restore
        GameMemento.Recorder rec = new GameMemento.Recorder();
        long c0 = System.nanoTime();
        GameMemento root = rec.capture(g);
        double capCold = (System.nanoTime() - c0) / 1e6;
        c0 = System.nanoTime();
        root = rec.capture(g);
        double capWarm = (System.nanoTime() - c0) / 1e6;
        System.out.printf("MEMENTO capture         : cold %.2f ms, warm %.2f ms, objects=%d, problems=%s%n",
                capCold, capWarm, root.objectCount(), root.problems);
        int logSize = g.getGameLog().size();

        long tRoll = 0, tRest = 0;
        int mismatches = 0, errors = 0;
        Set<String> endStates2 = new HashSet<>();
        String firstDiff = null;
        for (int i = 0; i < rollouts; i++) {
            long s = System.nanoTime();
            try { rollout(g); } catch (RuntimeException e) { errors++; if (errors <= 2) e.printStackTrace(System.out); }
            long m = System.nanoTime();
            endStates2.add(lifeLine(g));
            root.restore();
            g.getGameLog().truncateTo(logSize);
            g.getTriggerHandler().resetActiveTriggers();
            long e = System.nanoTime();
            tRoll += m - s; tRest += e - m;
            String fp = fingerprint(g);
            if (!fp.equals(fp0)) {
                mismatches++;
                if (firstDiff == null) firstDiff = diff(fp0, fp);
            }
        }
        System.out.printf("ROLLOUT+RESTORE         : rollout %.2f ms + restore %.2f ms per sim, %d distinct end states, mismatches=%d, errors=%d, problems=%s%n",
                tRoll / 1e6 / rollouts, tRest / 1e6 / rollouts, endStates2.size(), mismatches, errors, root.problems);
        if (firstDiff != null) System.out.println("first mismatch: " + firstDiff);

        // after all those restores, a fresh copy of the live game must still equal the original
        System.out.println("copy of restored game identical=" + fingerprint(new forge.ai.simulation.GameCopier(g).makeCopy()).equals(fp0));
        // and the restored game must still be playable: run one more rollout and report it
        rollout(g);
        System.out.println("after restores, one more rollout -> " + lifeLine(g));
    }

    static String lifeLine(Game g) {
        StringBuilder sb = new StringBuilder();
        for (Player p : g.getPlayers()) sb.append(p.getName()).append('=').append(p.getLife()).append(' ');
        sb.append("bf=").append(g.getCardsIn(ZoneType.Battlefield).size());
        return sb.toString();
    }

    static String diff(String a, String b) {
        String[] x = a.split("\n"), y = b.split("\n");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            if (!x[i].equals(y[i])) return "\n  orig: " + x[i] + "\n  now : " + y[i];
        }
        return "length differs";
    }
}
