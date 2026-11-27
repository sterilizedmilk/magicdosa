package magicdosa.agent.shadow;

import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.player.RegisteredPlayer;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A copy leaves out the sideboard cards that do nothing from the sideboard (copy cost: each card is rebuilt from its
 * script), keeps the ones that do (devoid's colour works in every zone: Thief of Existence), and keeps every sideboard
 * card when a card in the game can reach the sideboard (a wish).
 */
class GameCopierSideboardTest {

    @Test
    void sideboardCardsThatDoNothingThereAreLeftOut() throws Exception {
        run(() -> {
            Game game = game("humanhand=Lightning Bolt");
            GameCopier copier = new GameCopier(game);
            Game copy = copier.makeCopy();
            List<String> names = names(copy.getPlayers().get(0).getCardsIn(ZoneType.Sideboard));
            assertEquals(List.of("Thief of Existence"), names, "only the card that acts from the sideboard is copied");
            assertEquals(2, copier.sideboardLeftOut());
            Card thief = copy.getPlayers().get(0).getCardsIn(ZoneType.Sideboard).get(0);
            assertTrue(thief.getColor().isColorless(), "devoid applies to the copied sideboard card");
        });
    }

    @Test
    void aWishKeepsTheWholeSideboard() throws Exception {
        run(() -> {
            Game game = game("humanhand=Burning Wish");
            GameCopier copier = new GameCopier(game);
            Game copy = copier.makeCopy();
            assertEquals(3, copy.getPlayers().get(0).getCardsIn(ZoneType.Sideboard).size());
            assertEquals(0, copier.sideboardLeftOut());
        });
    }

    private static List<String> names(Iterable<Card> cards) {
        List<String> out = new ArrayList<>();
        cards.forEach(c -> out.add(c.getName()));
        return out;
    }

    private static Game game(String hand) throws Exception {
        Deck deck = DeckFiles.load(new File(System.getProperty("magicdosa.decks", "../../decks"), "IzzetProwess.dck"));
        List<RegisteredPlayer> ps = new ArrayList<>();
        RegisteredPlayer a = new RegisteredPlayer(new Deck(deck));
        a.setPlayer(GamePlayerUtil.createAiPlayer("A", 0));
        RegisteredPlayer b = new RegisteredPlayer(new Deck(deck));
        b.setPlayer(GamePlayerUtil.createAiPlayer("B", 1));
        ps.add(a);
        ps.add(b);
        Game game = GameCreation.createGame(new Match(new GameRules(GameType.Constructed), ps, "copier-sideboard-test"));
        game.setNoGUIUser();
        forge.game.GameState st = new forge.game.GameState() { };
        st.parse(Arrays.asList("turn=3", "activeplayer=human", "activephase=MAIN1", "humanlife=20", "ailife=20",
            "humanlibrary=Island;Island", "ailibrary=Island;Island", hand, "humanbattlefield=Island",
            "humansideboard=Consign to Memory;Thief of Existence;Spell Pierce", "aisideboard="));
        st.applyToGame(game);
        return game;
    }

    private static void run(ThrowingRunnable body) throws Exception {
        Scenario.keys(); // Forge, once per JVM
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable e) {
                failed.set(e);
            }
        }, "Game-copier-sideboard-test"); // a "Game" thread: GameAction.invoke runs inline
        t.start();
        t.join();
        if (failed.get() != null) {
            throw new AssertionError(failed.get());
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
