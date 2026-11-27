package magicdosa.agent.shadow;

import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.player.GamePlayerUtil;
import magicdosa.adapter.forge.GameCreation;
import magicdosa.agent.DeckFiles;
import magicdosa.agent.search.KillCheck;
import magicdosa.agent.search.KillCheckCopy;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The confirmed-kill checker's least damage, with the blocks it found, dealt by Forge on a copy: unblocked attackers,
 * a chump block, trample over a small blocker, a double striker. The copy and the checker agree on these exact cases,
 * and the copy leaves the original game untouched.
 */
class KillCheckCopyTest {

    @Test
    void checkerAndCopyAgreeOnExactBoards() throws Exception {
        Scenario.keys(); // Forge, once per JVM
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                check();
            } catch (Throwable e) {
                failed.set(e);
            }
        }, "Game-killcheck-copy-test"); // a "Game" thread: GameAction.invoke runs inline
        t.start();
        t.join();
        if (failed.get() != null) {
            throw new AssertionError(failed.get());
        }
    }

    private static void check() throws Exception {
        // no blockers: every point goes through
        assertBoard("Craw Wurm;Grizzly Bears", "", 8, 0);
        // one blocker: it takes the biggest attacker
        assertBoard("Craw Wurm;Grizzly Bears", "Llanowar Elves", 2, 1);
        // trample over a 1/1: the excess goes through
        assertBoard("Colossal Dreadmaw", "Llanowar Elves", 5, 1);
        // double strike, unblocked: twice its power
        assertBoard("Fencing Ace", "", 2, 0);
    }

    private static void assertBoard(String attackers, String blockers, int expected, int expectedBlocks) throws Exception {
        Game game = newGame(attackers, blockers);
        Player human = game.getPlayers().get(0);
        Player ai = game.getPlayers().get(1);
        List<Card> atk = new ArrayList<>(human.getCardsIn(ZoneType.Battlefield));
        atk.removeIf(c -> !c.isCreature());
        KillCheck.Outcome o = KillCheck.evaluate(atk, ai);
        assertEquals(expected, o.minDamage(), "checker on " + attackers + " vs " + blockers);
        assertEquals(expectedBlocks, o.bestBlocks().size(), "blocks the checker found");
        int lifeBefore = ai.getLife();
        Integer copy = KillCheckCopy.damageOnCopy(game, atk, ai, o.bestBlocks());
        assertEquals(expected, copy, "Forge on a copy, " + attackers + " vs " + blockers);
        assertEquals(lifeBefore, ai.getLife(), "the original is untouched");
        Map<Card, Card> none = Map.of();
        assertEquals(o.maxDamage(), KillCheckCopy.damageOnCopy(game, atk, ai, none), "no blocks: the most damage");
    }

    private static Game newGame(String attackers, String blockers) throws Exception {
        Deck deck = DeckFiles.load(new File(System.getProperty("magicdosa.decks", "../../decks"), "IzzetProwess.dck"));
        List<RegisteredPlayer> ps = new ArrayList<>();
        RegisteredPlayer a = new RegisteredPlayer(new Deck(deck));
        a.setPlayer(GamePlayerUtil.createAiPlayer("A", 0));
        RegisteredPlayer b = new RegisteredPlayer(new Deck(deck));
        b.setPlayer(GamePlayerUtil.createAiPlayer("B", 1));
        ps.add(a);
        ps.add(b);
        Game game = GameCreation.createGame(new Match(new GameRules(GameType.Constructed), ps, "killcheck-copy-test"));
        game.setNoGUIUser();
        forge.game.GameState st = new forge.game.GameState() { };
        st.parse(Arrays.asList("turn=3", "activeplayer=human", "activephase=COMBAT_DECLARE_ATTACKERS", "humanlife=20",
            "ailife=20", "humanlibrary=Island;Island;Island", "ailibrary=Island;Island;Island",
            "humanbattlefield=" + attackers, "aibattlefield=" + (blockers.isEmpty() ? "Island" : blockers)));
        st.applyToGame(game);
        return game;
    }
}
