package magicdosa.agent.shadow;

import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
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

/**
 * The spells cast this turn and last turn and the abilities activated this turn (Cori-Steel Cutter's "second spell each
 * turn", storm counts) go into a search copy, bound to the copy's cards and players (before, the copy's history was
 * empty: Forge's copier leaves it out).
 */
class GameCopierHistoryTest {

    @Test
    void copyKeepsCastAndActivationHistory() throws Exception {
        Scenario.keys(); // Forge, once per JVM
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                check();
            } catch (Throwable e) {
                failed.set(e);
            }
        }, "Game-copier-history-test"); // a "Game" thread: GameAction.invoke runs inline
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
        Game game = GameCreation.createGame(new Match(new GameRules(GameType.Constructed), ps, "copier-history-test"));
        game.setNoGUIUser();
        forge.game.GameState st = new forge.game.GameState() { };
        st.parse(Arrays.asList("turn=3", "activeplayer=human", "activephase=MAIN2", "humanlife=20", "ailife=20",
            "humanlibrary=Island;Island;Island;Island", "ailibrary=Island;Island;Island;Island",
            "humangraveyard=Lightning Bolt;Mishra's Bauble", "humanbattlefield=Island", "aibattlefield=Island"));
        st.applyToGame(game);
        Player human = game.getPlayers().get(0);
        Card bolt = null;
        Card bauble = null;
        for (Card c : human.getCardsIn(ZoneType.Graveyard)) {
            if (c.getName().equals("Lightning Bolt")) {
                bolt = c;
            } else {
                bauble = c;
            }
        }
        SpellAbility cast = bolt.getFirstSpellAbility();
        cast.setActivatingPlayer(human);
        game.getStack().getSpellsCastThisTurn().add(cast.copy(CardCopyService.getLKICopy(bolt), true));
        game.getStack().getSpellsCastLastTurn().add(CardCopyService.getLKICopy(bolt));
        SpellAbility activated = bauble.getSpellAbilities().get(0);
        activated.setActivatingPlayer(human);
        game.getStack().getAbilityActivatedThisTurn().add(activated.copy(CardCopyService.getLKICopy(bauble), true));

        Game copy = new GameCopier(game).makeCopy();
        List<SpellAbility> thisTurn = copy.getStack().getSpellsCastThisTurn();
        assertEquals(1, thisTurn.size(), "spells cast this turn");
        assertEquals("Lightning Bolt", thisTurn.get(0).getHostCard().getName());
        assertSame(copy, thisTurn.get(0).getActivatingPlayer().getGame(), "cast by the copy's player");
        assertEquals(1, copy.getStack().getSpellsCastLastTurn().size(), "spells cast last turn");
        List<SpellAbility> act = copy.getStack().getAbilityActivatedThisTurn();
        assertEquals(1, act.size(), "abilities activated this turn");
        assertSame(copy, act.get(0).getActivatingPlayer().getGame(), "activated by the copy's player");
    }
}
