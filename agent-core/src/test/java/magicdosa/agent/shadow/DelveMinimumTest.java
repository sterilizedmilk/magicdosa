package magicdosa.agent.shadow;

import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.player.GamePlayerUtil;
import magicdosa.adapter.forge.AgentPlayerController;
import magicdosa.adapter.forge.GameCreation;
import magicdosa.agent.DeckFiles;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Delve offers only exile counts the pool can pay the rest of the cost with (diag-gen2c n0 d3u0-33-g2 D71-D76: Murktide
 * Regent {5}{U}{U}, pool R U U, five cards in the graveyard: 2..5 was offered, Forge refused 2 and 3, and the third
 * refusal was an unintended fallback). The pool's U U pay the colored shards, so its R pays one generic: exile 4 or 5.
 */
class DelveMinimumTest {

    @Test
    void theColoredShardsUseTheirShareOfThePool() throws Exception {
        Scenario.keys(); // Forge, once per JVM
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                check();
            } catch (Throwable e) {
                failed.set(e);
            }
        }, "Game-delve-minimum-test"); // a "Game" thread: GameAction.invoke runs inline
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
        Game game = GameCreation.createGame(new Match(new GameRules(GameType.Constructed), ps, "delve-minimum-test"));
        game.setNoGUIUser();
        forge.game.GameState st = new forge.game.GameState() { };
        st.parse(Arrays.asList("turn=5", "activeplayer=human", "activephase=MAIN1", "humanlife=13", "ailife=19",
            "humanlibrary=Island;Island", "ailibrary=Island;Island", "humanhand=Murktide Regent",
            "humangraveyard=Preordain;Mountain;Mountain;Mutagenic Growth;Bloodstained Mire",
            "humanbattlefield=Steam Vents|Tapped;Thundering Falls|Tapped;Mountain|Tapped", "humanmanapool=R U U"));
        st.applyToGame(game);
        Player human = game.getPlayers().get(0);
        Card murktide = human.getCardsIn(ZoneType.Hand).get(0);
        SpellAbility cast = murktide.getFirstSpellAbility();
        cast.setActivatingPlayer(human);
        assertEquals(3, human.getManaPool().totalMana(), "pool R U U");
        assertEquals(4, AgentPlayerController.delveMinimum(human, cast, 5, 5), "U U pay the colored shards, R one generic");
        // a cost already reduced to {3}{U}{U} (another reduction before delve): R pays one, exile 2
        assertEquals(2, AgentPlayerController.delveMinimum(human, cast, 3, 3));
    }
}
