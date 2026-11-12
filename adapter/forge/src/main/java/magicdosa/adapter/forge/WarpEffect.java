package magicdosa.adapter.forge;

import forge.game.ability.SpellAbilityEffect;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * The effect a warped card leaves when it is exiled at the end step ("you may cast it from exile on a later turn"),
 * made for a rebuilt game as Forge's ChangeZoneEffect makes it (Forge 87cb532, ChangeZoneEffect "Warped "). A subclass
 * only to reach SpellAbilityEffect's protected helpers; nothing in Forge is replaced.
 */
final class WarpEffect extends SpellAbilityEffect {
    private WarpEffect() {
    }

    @Override
    public void resolve(SpellAbility sa) {
        throw new UnsupportedOperationException();
    }

    /** The warp effect of {@code exiled} (a card in its owner's exile), in its owner's command zone. */
    static Card make(Card exiled) {
        Card eff = createEffect(null, exiled, exiled.getOwner(), "Warped " + exiled, exiled.getImageKey(),
            exiled.getGame().getNextTimestamp());
        eff.addStaticAbility("Mode$ Continuous | MayPlay$ True | EffectZone$ Command | Affected$ Card.IsRemembered+nonLand"
            + "+!ThisTurnEntered | AffectedZone$ Exile | Description$ You may cast the card.");
        eff.addRemembered(exiled);
        addForgetOnMovedTrigger(eff, "Exile");
        addForgetOnCastTrigger(eff, "Card.IsRemembered");
        exiled.getOwner().getZone(ZoneType.Command).add(eff);
        return eff;
    }
}
