package magicdosa.agent.shadow;

import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameActionUtil;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.player.GamePlayerUtil;
import magicdosa.adapter.forge.GameCreation;
import magicdosa.agent.DeckFiles;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A spell cast for an alternative cost (Lava Dart's flashback, sacrificing a Mountain) is copied onto a search copy's
 * stack: the copier looked it up among the card's own abilities by its text, which a flashback cast does not match, and
 * left it out ("stack not copied: 0 of 1", about 3,200 failed simulations in gate 1 step 3).
 */
class GameCopierStackTest {

    @Test
    void flashbackSpellOnTheStackIsCopied() throws Exception {
        Scenario.keys(); // Forge, once per JVM
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                check();
            } catch (Throwable e) {
                failed.set(e);
            }
        }, "Game-copier-stack-test"); // a "Game" thread: GameAction.invoke runs inline
        t.start();
        t.join();
        if (failed.get() != null) {
            throw new AssertionError(failed.get());
        }
    }

    private static void check() throws Exception {
        Deck deck = DeckFiles.load(new File(System.getProperty("magicdosa.decks", "../../decks"), "IzzetProwess.dck"));
        List<RegisteredPlayer> ps = new ArrayList<>();
        RegisteredPlayer a = new RegisteredPlayer(new Deck(deck));
        a.setPlayer(GamePlayerUtil.createAiPlayer("A", 0));
        RegisteredPlayer b = new RegisteredPlayer(new Deck(deck));
        b.setPlayer(GamePlayerUtil.createAiPlayer("B", 1));
        ps.add(a);
        ps.add(b);
        Game game = GameCreation.createGame(new Match(new GameRules(GameType.Constructed), ps, "copier-stack-test"));
        game.setNoGUIUser();
        forge.game.GameState st = new forge.game.GameState() { };
        st.parse(Arrays.asList("turn=3", "activeplayer=human", "activephase=MAIN1", "humanlife=20", "ailife=20",
            "humanlibrary=Island;Island;Island;Island", "ailibrary=Island;Island;Island;Island",
            "humangraveyard=Lava Dart", "humanbattlefield=Mountain;Island", "aibattlefield=Island"));
        st.applyToGame(game);
        Player human = game.getPlayers().get(0);
        Player ai = game.getPlayers().get(1);
        Card dart = human.getCardsIn(ZoneType.Graveyard).get(0);
        SpellAbility base = dart.getFirstSpellAbility();
        base.setActivatingPlayer(human);
        SpellAbility flashback = null;
        for (SpellAbility alt : GameActionUtil.getAlternativeCosts(base, human, false)) {
            if (alt.isFlashback()) {
                flashback = alt;
            }
        }
        assertTrue(flashback != null, "Lava Dart has a flashback cast from the graveyard");
        flashback.setActivatingPlayer(human);
        flashback.getTargets().add(ai);
        Card onStack = game.getAction().moveToStack(dart, flashback);
        flashback.setHostCard(onStack);
        game.getStack().addAndUnfreeze(flashback);
        assertEquals(1, game.getStack().size(), "the flashback spell is on the stack");

        Game copy = new GameCopier(game).withStack(true).makeCopy();
        assertEquals(1, copy.getStack().size(), "the flashback spell is on the copy's stack");
        SpellAbility copied = copy.getStack().peekAbility();
        assertTrue(copied.isFlashback(), "still the flashback cast");
        assertSame(copy, copied.getActivatingPlayer().getGame(), "cast by the copy's player");
        assertSame(copy.getPlayers().get(1), copied.getTargets().getFirstTargetedPlayer(), "aimed at the copy's player");
    }
}
