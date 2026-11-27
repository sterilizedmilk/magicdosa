package magicdosa.agent.shadow;

import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerHandler;
import forge.game.zone.ZoneType;
import forge.player.GamePlayerUtil;
import magicdosa.adapter.forge.GameCreation;
import magicdosa.agent.DeckFiles;
import magicdosa.protocol.v1.Effect;
import magicdosa.protocol.v1.EffectKind;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A "next turn" delayed trigger (Mishra's Bauble's draw) in a rebuilt turn start survives the turn change: the rebuilt
 * game sits in the cleanup before it, and the change first clears this turn's delayed triggers, so registered for
 * "this turn" the trigger was gone before its turn (every Bauble draw after a rebuild: forced-regeneration ShadowCheck of
 * the regenerated w14 match, 3 of 3 games). It now lives through the change and joins that turn's list right after it.
 */
class RebuiltDelayedTriggerTest {

    @Test
    void nextTurnTriggerOfARebuiltTurnStartSurvivesTheTurnChange() throws Exception {
        Scenario.keys(); // Forge, once per JVM
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                check();
            } catch (Throwable e) {
                failed.set(e);
            }
        }, "Game-rebuilt-delayed-test"); // a "Game" thread: GameAction.invoke runs inline
        t.start();
        t.join();
        if (failed.get() != null) {
            throw new AssertionError(failed.get());
        }
    }

    @SuppressWarnings("unchecked")
    private static void check() throws Exception {
        Deck deck = DeckFiles.load(new File(System.getProperty("magicdosa.decks", "../../decks"), "IzzetProwess.dck"));
        List<RegisteredPlayer> ps = new ArrayList<>();
        RegisteredPlayer a = new RegisteredPlayer(new Deck(deck));
        a.setPlayer(GamePlayerUtil.createAiPlayer("A", 0));
        RegisteredPlayer b = new RegisteredPlayer(new Deck(deck));
        b.setPlayer(GamePlayerUtil.createAiPlayer("B", 1));
        ps.add(a);
        ps.add(b);
        Game game = GameCreation.createGame(new Match(new GameRules(GameType.Constructed), ps, "rebuilt-delayed-test"));
        game.setNoGUIUser();
        forge.game.GameState st = new forge.game.GameState() { };
        // as SnapshotBuilder sets up a turn start: the previous turn's cleanup
        st.parse(Arrays.asList("turn=2", "activeplayer=human", "activephase=CLEANUP", "humanlife=20", "ailife=20",
            "humanlibrary=Island;Island;Island;Island", "ailibrary=Island;Island;Island;Island",
            "humangraveyard=Mishra's Bauble", "humanbattlefield=Island", "aibattlefield=Island"));
        st.applyToGame(game);
        Player human = game.getPlayers().get(0);
        Card bauble = human.getCardsIn(ZoneType.Graveyard).get(0);
        Effect e = Effect.newBuilder().setId(209).setKind(EffectKind.EFFECT_KIND_DELAYED_TRIGGER).setSourceId(207)
            .setControllerSeat(0).setDescription("Draw a card at the beginning of the next turn's upkeep.").build();

        Class<?> dt = Class.forName("magicdosa.adapter.forge.DelayedTriggers");
        Method rebuild = dt.getDeclaredMethod("rebuild", Game.class, Card.class, Player.class, Effect.class, Map.class);
        rebuild.setAccessible(true);
        Trigger t = (Trigger) rebuild.invoke(null, game, bauble, human, e, Map.of());
        assertTrue(t != null, "Mishra's Bauble's delayed trigger is rebuilt");

        TriggerHandler h = game.getTriggerHandler();
        h.clearThisTurnDelayedTrigger(); // the turn change's first act (PhaseHandler.handleNextTurn)
        assertTrue(list(h, "delayedTriggers").contains(t), "the trigger lives through the turn change");
        game.getCleanup().executeUntil(); // the cleanup commands, right after the change
        assertTrue(list(h, "thisTurnDelayedTriggers").contains(t), "it belongs to the new turn, and ends with it");
    }

    @SuppressWarnings("unchecked")
    private static List<Trigger> list(TriggerHandler h, String name) throws Exception {
        Field f = TriggerHandler.class.getDeclaredField(name);
        f.setAccessible(true);
        return (List<Trigger>) f.get(h);
    }
}
