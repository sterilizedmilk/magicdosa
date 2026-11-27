package magicdosa.adapter.forge;

import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerHandler;
import magicdosa.protocol.v1.Effect;
import magicdosa.protocol.v1.ObjectRef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Delayed triggers of a rebuilt game (SnapshotBuilder): the source card's DelayedTrigger part whose trigger reads as
 * the observed one is set up again as Forge's DelayedTriggerEffect sets it up (Forge 87cb532), with the observed
 * remembered objects. A trigger made for "the next turn" during the turn before is waiting for this turn at a turn
 * start, so it is registered for this turn (Forge registers it in that turn's cleanup).
 */
final class DelayedTriggers {
    private DelayedTriggers() {
    }

    /** The rebuilt delayed trigger, or null when no part of the source reads as {@code e}. */
    static Trigger rebuild(Game game, Card source, Player controller, Effect e, Map<Long, Card> byId) {
        String want = plain(e.getDescription());
        for (SpellAbility root : abilities(source)) {
            for (SpellAbility part = root; part != null; part = part.getSubAbility()) {
                if (part.getApi() != ApiType.DelayedTrigger) {
                    continue;
                }
                Map<String, String> params = new HashMap<>(part.getMapParams());
                if (params.containsKey("SpellDescription") && !params.containsKey("TriggerDescription")) {
                    params.put("TriggerDescription", params.get("SpellDescription"));
                }
                params.remove("SpellDescription");
                params.remove("Cost");
                Trigger t = TriggerHandler.parseTrigger(params, source, part.isIntrinsic(), null);
                if (!plain(t.toString()).equals(want)) {
                    continue;
                }
                if (controller != null) {
                    part.setActivatingPlayer(controller);
                }
                t.setSpawningAbility(part.copy(source, true));
                t.setActiveZone(null);
                for (ObjectRef r : e.getAffectedList()) {
                    if (r.hasPlayerSeat() && r.getPlayerSeat() < game.getPlayers().size()) {
                        t.addRemembered(game.getPlayers().get(r.getPlayerSeat()));
                    } else if (r.hasEntityId() && byId.get(r.getEntityId()) != null) {
                        t.addRemembered(game.getCardState(byId.get(r.getEntityId())));
                    }
                }
                if (part.hasAdditionalAbility("Execute")) {
                    SpellAbility over = part.getAdditionalAbility("Execute").copy(source, part.getActivatingPlayer(), false);
                    if (over instanceof AbilitySub sub) {
                        sub.setParent(null);
                    }
                    t.setOverridingAbility(over);
                }
                TriggerHandler h = game.getTriggerHandler();
                if ((params.containsKey("ThisTurn") || params.containsKey("NextTurn"))
                        && game.getPhaseHandler().is(forge.game.phase.PhaseType.CLEANUP)) {
                    // a rebuilt turn start sits in the cleanup before the turn change, whose first act clears this
                    // turn's delayed triggers (PhaseHandler.handleNextTurn): registered for "this turn" now, the trigger
                    // was gone before the turn it belongs to (every Mishra's Bauble draw after a rebuild). It is
                    // registered as a plain delayed trigger, so it lives through that change, and joins this turn's
                    // list by a cleanup command, which runs right after the change (as Forge's own NextTurn
                    // registration does), so it still ends with the turn
                    h.registerDelayedTrigger(t);
                    game.getCleanup().addUntil(() -> thisTurnTriggers(h).add(t));
                } else if (params.containsKey("ThisTurn") || params.containsKey("NextTurn")) {
                    h.registerThisTurnDelayedTrigger(t);
                } else {
                    h.registerDelayedTrigger(t);
                }
                return t;
            }
        }
        return null;
    }

    private static final java.lang.reflect.Field THIS_TURN;

    static {
        try {
            THIS_TURN = TriggerHandler.class.getDeclaredField("thisTurnDelayedTriggers");
            THIS_TURN.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** The handler's list of this turn's delayed triggers (cleared at the next turn change). */
    @SuppressWarnings("unchecked")
    private static List<Trigger> thisTurnTriggers(TriggerHandler h) {
        try {
            return (List<Trigger>) THIS_TURN.get(h);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Spell and activated abilities, and the abilities of the card's replacement effects and triggers (rebound). */
    private static List<SpellAbility> abilities(Card c) {
        List<SpellAbility> out = new ArrayList<>(c.getAllSpellAbilities());
        for (ReplacementEffect re : c.getReplacementEffects()) {
            if (re.getOverridingAbility() != null) {
                out.add(re.getOverridingAbility());
            }
        }
        for (Trigger t : c.getTriggers()) {
            if (t.getOverridingAbility() != null) {
                out.add(t.getOverridingAbility());
            }
        }
        return out;
    }

    /** Engine text without object numbers. */
    private static String plain(String s) {
        return s.replaceAll("\\s*\\(\\d+\\)", "").trim();
    }
}
