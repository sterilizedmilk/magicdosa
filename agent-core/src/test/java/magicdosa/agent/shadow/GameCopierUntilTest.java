package magicdosa.agent.shadow;

import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.AbilityUtils;
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

/**
 * "Until end of turn" effects end in a search copy too: Forge reverts a pump with a command queued on the end-of-turn
 * phase, which its copier leaves out, so a copied creature kept a pump past the turn (a rollout to the next turn
 * attacked with a Swiftspear still +2/+2). The copy now carries the command, bound to its own card.
 */
class GameCopierUntilTest {

    @Test
    void pumpUntilEndOfTurnEndsInTheCopy() throws Exception {
        Scenario.keys(); // Forge, once per JVM
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                check();
            } catch (Throwable e) {
                failed.set(e);
            }
        }, "Game-copier-until-test"); // a "Game" thread: GameAction.invoke runs inline
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
        Game game = GameCreation.createGame(new Match(new GameRules(GameType.Constructed), ps, "copier-until-test"));
        game.setNoGUIUser();
        forge.game.GameState st = new forge.game.GameState() { };
        st.parse(Arrays.asList("turn=3", "activeplayer=human", "activephase=MAIN1", "humanlife=20", "ailife=20",
            "humanlibrary=Island;Island;Island;Island", "ailibrary=Island;Island;Island;Island",
            "humanhand=Mutagenic Growth", "humanbattlefield=Monastery Swiftspear;Island", "aibattlefield=Island"));
        st.applyToGame(game);
        Player human = game.getPlayers().get(0);
        Card spear = human.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.getName().equals("Monastery Swiftspear"))
            .findFirst().orElseThrow();
        Card growth = human.getCardsIn(ZoneType.Hand).get(0);
        SpellAbility pump = growth.getFirstSpellAbility();
        pump.setActivatingPlayer(human);
        pump.getTargets().add(spear);
        AbilityUtils.resolve(pump);
        assertEquals(3, spear.getNetPower(), "the pump applies");

        Game copy = new GameCopier(game).makeCopy();
        Card copied = copy.getPlayers().get(0).getCardsIn(ZoneType.Battlefield).stream()
            .filter(c -> c.getName().equals("Monastery Swiftspear")).findFirst().orElseThrow();
        assertEquals(3, copied.getNetPower(), "the copy has the pump");
        copy.getEndOfTurn().executeUntil();
        assertEquals(1, copied.getNetPower(), "the pump ends at the copy's end of turn");
        assertEquals(3, spear.getNetPower(), "the original keeps its pump");
    }
}
