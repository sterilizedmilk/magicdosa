package magicdosa.adapter.forge;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.cost.CostAdjustment;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostPayment;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import magicdosa.agent.shadow.GameCopier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Sample check of the priority candidates (2026-11-06, deck item 2: "후보 누락" sampling): for spells and abilities
 * the pool-only castability check left out while the pool holds mana, Forge's own pool payment is tried on a copy of
 * the game. A spell Forge can pay for that was not offered is a candidate omission. Off unless the system property
 * {@code magicdosa.candidateCheck} gives a sampling interval (every N-th priority decision).
 */
final class CandidateCheck {
    static final int EVERY = Integer.getInteger("magicdosa.candidateCheck", 0);

    private CandidateCheck() {}

    /** Records omissions in {@code stats}; {@code offered} are the actions that were offered. */
    static void run(Player player, Set<SpellAbility> offered, List<SpellAbility> considered, DecisionStats stats) {
        if (player.getManaPool().isEmpty()) {
            return;
        }
        List<SpellAbility> left = new ArrayList<>();
        for (SpellAbility sa : considered) {
            if (!offered.contains(sa) && !sa.isLandAbility() && sa.getHostCard().getSpellAbilities().contains(sa)) {
                left.add(sa);
            }
        }
        if (left.isEmpty()) {
            return;
        }
        stats.candidateChecks++;
        Game game = player.getGame();
        for (SpellAbility sa : left) {
            try {
                GameCopier copier = new GameCopier(game);
                copier.makeCopy();
                Card host = copier.find(sa.getHostCard());
                Player p = copier.find(player);
                int index = sa.getHostCard().getSpellAbilities().indexOf(sa);
                SpellAbility csa = host.getSpellAbilities().get(index);
                csa.setActivatingPlayer(p);
                if (forgePays(p, csa)) {
                    stats.note("candidate omitted (Forge pays it from the pool): " + sa.getHostCard().getName());
                    stats.candidateOmissions++;
                }
            } catch (RuntimeException e) {
                stats.note("candidate check could not copy: " + e.getClass().getSimpleName());
            }
        }
    }

    /** Forge's own check on the copy: non-mana costs payable, mana cost payable from the pool. */
    private static boolean forgePays(Player p, SpellAbility sa) {
        if (!sa.canPlay()) {
            return false;
        }
        Cost cost = sa.getPayCosts();
        if (cost == null) {
            return true;
        }
        Cost adjusted = CostAdjustment.adjust(cost, sa, false);
        if (!CostPayment.canPayAdditionalCosts(adjusted, sa, false)) {
            return false;
        }
        CostPartMana part = adjusted.getCostMana();
        if (part == null) {
            return true;
        }
        ManaCostBeingPaid m = new ManaCostBeingPaid(part.getManaCostFor(sa));
        if (m.getXcounter() > 0) {
            m.setXManaCostPaid(0, sa.getXColor());
        }
        CostAdjustment.adjust(m, sa, p, null, true, false);
        return p.getManaPool().payManaCostFromPool(m, sa, false, new ArrayList<>());
    }
}
