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
import forge.game.zone.ZoneType;
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
    /** {@code magicdosa.copyCheck}: every N-th priority decision, copy the whole game and map every card (0: off). */
    static final int COPY_EVERY = Integer.getInteger("magicdosa.copyCheck", 0);

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
            stats.copies++;
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
                stats.copyFailures++;
                stats.note("candidate check could not copy: " + describe(e));
            }
        }
    }

    /**
     * Copies the game as a shadow game will (agent-core GameCopier) and maps every card of every zone and every player
     * back; a failure is counted with its cause.
     */
    static void copyCheck(Game game, DecisionStats stats) {
        stats.copies++;
        try {
            GameCopier copier = new GameCopier(game);
            copier.makeCopy();
            for (Player p : game.getPlayers()) {
                copier.find(p);
                for (ZoneType z : ZoneType.values()) {
                    for (Card c : p.getCardsIn(z)) {
                        copier.find(c);
                    }
                }
            }
            for (Card c : game.getStackZone().getCards()) {
                copier.find(c);
            }
        } catch (RuntimeException e) {
            stats.copyFailures++;
            stats.note("game copy failed: " + describe(e));
        }
    }

    /** The exception without object hashes, and where it was thrown. */
    static String describe(RuntimeException e) {
        StringBuilder b = new StringBuilder(e.getClass().getSimpleName()).append(": ")
            .append(String.valueOf(e.getMessage()).replaceAll("/\\d+", "").replaceAll(" \\(\\d+\\)", ""));
        int n = 0;
        for (StackTraceElement f : e.getStackTrace()) {
            if (n++ == 4) {
                break;
            }
            b.append(" < ").append(f.getClassName().replaceAll(".*\\.", "")).append('.').append(f.getMethodName())
                .append(':').append(f.getLineNumber());
        }
        return b.toString();
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
