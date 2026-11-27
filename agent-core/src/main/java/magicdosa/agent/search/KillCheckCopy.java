package magicdosa.agent.search;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.AttackingBand;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.game.phase.PhaseType;
import magicdosa.agent.shadow.GameCopier;

import java.util.List;
import java.util.Map;

/**
 * The confirmed-kill checker's numbers checked on a copy (measurement only, 3단계 completion item B, 2026-11-27): the
 * attack and the blocks KillCheck reasons about are set up in a copy of the game and Forge deals the combat damage
 * (first strike, then regular, state-based actions between), and the defender's life loss is compared with the
 * checker's. The difference is the checker's own error on that position (approximate cases included). Never changes a
 * decision. Must run on a "Game" thread (Forge's actions run inline there), as the shadow's does.
 */
public final class KillCheckCopy {
    private KillCheckCopy() {
    }

    /**
     * Life the defender loses when {@code attackers} attack it and {@code blockOf} blocks them (attacker -> its blocker;
     * the others unblocked), dealt by Forge on a copy of {@code game}; null when the copy cannot be set up.
     */
    public static Integer damageOnCopy(Game game, List<Card> attackers, Player defender, Map<Card, Card> blockOf) {
        if (attackers.isEmpty()) {
            return 0;
        }
        GameCopier copier = new GameCopier(game);
        Game copy = copier.makeCopy();
        Player attacking = copier.find(attackers.get(0).getController());
        Player defending = copier.find(defender);
        Combat combat = new Combat(attacking);
        for (Card a : attackers) {
            combat.addAttacker(copier.find(a), defending);
        }
        for (Map.Entry<Card, Card> e : blockOf.entrySet()) {
            if (attackers.contains(e.getKey())) {
                combat.addBlocker(copier.find(e.getKey()), copier.find(e.getValue()));
            }
        }
        for (Card a : attackers) {
            AttackingBand band = combat.getBandOfAttacker(copier.find(a));
            if (band != null) {
                band.setBlocked(blockOf.containsKey(a));
            }
        }
        copy.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, attacking, copy.getPhaseHandler().getTurn());
        copy.getPhaseHandler().setCombat(combat);
        combat.orderBlockersForDamageAssignment();
        int before = defending.getLife();
        if (combat.assignCombatDamage(true)) {
            combat.dealAssignedDamage();
            copy.getAction().checkStateEffects(true);
            combat.removeAbsentCombatants();
        }
        if (combat.assignCombatDamage(false)) {
            combat.dealAssignedDamage();
        }
        return before - defending.getLife();
    }
}
