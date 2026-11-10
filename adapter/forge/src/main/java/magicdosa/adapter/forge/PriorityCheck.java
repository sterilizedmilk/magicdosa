package magicdosa.adapter.forge;

import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.List;

/**
 * A check run on the priority candidates an agent seat is offered (the runner's sampled candidate and copy checks).
 * It must not change the game. Called with the count of priority candidate lists built so far in this controller.
 */
public interface PriorityCheck {
    PriorityCheck NONE = (player, asks, offered, considered, stats) -> { };

    void check(Player player, int asks, List<SpellAbility> offered, List<SpellAbility> considered, DecisionStats stats);
}
