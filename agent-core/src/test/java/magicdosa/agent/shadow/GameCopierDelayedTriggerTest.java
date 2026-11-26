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
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerHandler;
import forge.game.zone.ZoneType;
import forge.player.GamePlayerUtil;
import magicdosa.adapter.forge.GameCreation;
import magicdosa.agent.DeckFiles;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mishra's Bauble's "draw a card at the beginning of the next turn's upkeep" (gate 1 step 1, 2026-11-26): Forge queues
 * the delayed trigger as a cleanup command and registers it at the cleanup. A search copy made before the cleanup must
 * carry the command, and one made after it the registered trigger, bound to the copy's cards and players (before the
 * fix the copy had neither, so a simulation never drew the card).
 */
class GameCopierDelayedTriggerTest {

    @Test
    void copyKeepsQueuedAndRegisteredDelayedTriggers() throws Exception {
        Scenario.keys(); // Forge, once per JVM
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                check();
            } catch (Throwable e) {
                failed.set(e);
            }
        }, "Game-copier-test"); // a "Game" thread: GameAction.invoke runs inline
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
        Game game = GameCreation.createGame(new Match(new GameRules(GameType.Constructed), ps, "copier-test"));
        game.setNoGUIUser();
        forge.game.GameState st = new forge.game.GameState() { };
        st.parse(Arrays.asList("turn=3", "activeplayer=human", "activephase=MAIN2", "humanlife=20", "ailife=20",
            "humanlibrary=Island;Island;Island;Island", "ailibrary=Island;Island;Island;Island",
            "humangraveyard=Mishra's Bauble", "humanbattlefield=Island", "aibattlefield=Island"));
        st.applyToGame(game);
        Player human = game.getPlayers().get(0);
        Card bauble = human.getCardsIn(ZoneType.Graveyard).get(0);

        // the resolution's delayed-trigger part: what the activation leaves behind once it resolved
        SpellAbility delayed = null;
        for (SpellAbility sa : bauble.getAllSpellAbilities()) {
            for (SpellAbility sub = sa.getSubAbility(); sub != null && delayed == null; sub = sub.getSubAbility()) {
                if (sub.getApi() == forge.game.ability.ApiType.DelayedTrigger) {
                    delayed = sub;
                }
            }
        }
        assertTrue(delayed != null, "Mishra's Bauble has its delayed-trigger part");
        delayed.setActivatingPlayer(human);
        AbilityUtils.resolve(delayed);
        assertEquals(1, until(game).size(), "Forge queued the delayed trigger at the cleanup");

        // copied before the cleanup: the command is carried, and registers the copy's trigger at the copy's cleanup
        Game copy = new GameCopier(game).makeCopy();
        assertEquals(1, until(copy).size(), "the copy carries the queued command");
        copy.getCleanup().executeUntil();
        List<Trigger> registered = delayed(copy);
        assertEquals(1, registered.size());
        assertBound(registered.get(0), copy);

        // copied after the cleanup: the registered trigger is carried
        game.getCleanup().executeUntil();
        assertEquals(1, delayed(game).size());
        Game later = new GameCopier(game).makeCopy();
        List<Trigger> carried = delayed(later);
        assertEquals(1, carried.size(), "the copy carries the registered trigger");
        assertBound(carried.get(0), later);
        assertTrue(thisTurn(later).contains(carried.get(0)), "a next-turn trigger lasts for this turn only");
    }

    private static void assertBound(Trigger t, Game copy) {
        assertSame(copy, t.getHostCard().getGame(), "the trigger's source is the copy's card");
        assertSame(copy, t.getOverridingAbility().getActivatingPlayer().getGame(), "its draw is for the copy's player");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> until(Game g) throws Exception {
        Field f = forge.game.phase.Phase.class.getDeclaredField("until");
        f.setAccessible(true);
        return (List<Object>) f.get(g.getCleanup());
    }

    @SuppressWarnings("unchecked")
    private static List<Trigger> delayed(Game g) throws Exception {
        Field f = TriggerHandler.class.getDeclaredField("delayedTriggers");
        f.setAccessible(true);
        return (List<Trigger>) f.get(g.getTriggerHandler());
    }

    @SuppressWarnings("unchecked")
    private static List<Trigger> thisTurn(Game g) throws Exception {
        Field f = TriggerHandler.class.getDeclaredField("thisTurnDelayedTriggers");
        f.setAccessible(true);
        return (List<Trigger>) f.get(g.getTriggerHandler());
    }
}
