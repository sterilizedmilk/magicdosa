package magicdosa.adapter.forge;

import forge.LobbyPlayer;
import forge.ai.ComputerUtilAbility;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;
import magicdosa.protocol.v1.ActivateAbility;
import magicdosa.protocol.v1.Attack;
import magicdosa.protocol.v1.Block;
import magicdosa.protocol.v1.CastSpell;
import magicdosa.protocol.v1.Event;
import magicdosa.protocol.v1.ObjectRef;
import magicdosa.protocol.v1.PassPriority;
import magicdosa.protocol.v1.PlayLand;
import magicdosa.protocol.v1.ResolutionChoice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shadow game (phase 2): a Forge AI seat replayed from what its events recorded. At each priority stop it plays the
 * recorded action (the spell or ability with the recorded targets, modes and X) or passes; attackers, blockers,
 * trigger order and every public choice come from the record. What the record leaves to the engine (paying mana,
 * the cards a cost uses) is left to Forge AI's own play path, as in the real game: in the same state it chooses the
 * same. A question the record does not answer is reported as a mismatch and Forge AI answers it.
 */
public class ReplayAiController extends ObservedAiController {
    private final ReplaySource source;
    private final int seat;

    public ReplayAiController(Game game, Player player, LobbyPlayer lobbyPlayer, ForgeGameSession session, ReplaySource source) {
        super(game, player, lobbyPlayer, session, false);
        this.source = source;
        this.seat = session.seat(player);
    }

    // ---- the record ----

    /** The next recorded event of the seat if it is of the wanted kind (consumed), else null (left in place). */
    private Event take(Event.BodyCase kind) {
        Event e = source.peek(seat);
        if (e == null || e.getBodyCase() != kind) {
            return null;
        }
        source.consume(seat);
        return e;
    }

    /** The next recorded choice made by {@code method}, or null (a mismatch, reported). */
    private ResolutionChoice choice(String method) {
        if (answering > 1) {
            return null; // asked while answering another choice: Forge AI's own way of answering, never recorded
        }
        Event e = source.takeChoice(seat, method);
        if (e != null) {
            return e.getResolutionChoice();
        }
        source.mismatch(seat, method + " asked; next recorded: " + describe(source.peek(seat)));
        return null;
    }

    private static String describe(Event e) {
        if (e == null) {
            return "nothing";
        }
        if (e.getBodyCase() == Event.BodyCase.RESOLUTION_CHOICE) {
            return "choice " + e.getResolutionChoice().getEnginePrompt() + " (seq " + e.getSeq() + ")";
        }
        return e.getBodyCase() + " (seq " + e.getSeq() + ")";
    }

    private Card card(long id) {
        return session.ids().cardOf(id);
    }

    private GameEntity entity(ObjectRef r) {
        if (r.hasPlayerSeat()) {
            return session.player(r.getPlayerSeat());
        }
        return card(r.getEntityId());
    }

    // ---- priority ----

    /** Recorded casts, activations and land plays; the SpellAbility set up as the record says. */
    private CastSpell pendingCast;
    private ActivateAbility pendingActivation;

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        while (true) {
            Event e = source.peek(seat);
            if (e == null) {
                source.mismatch(seat, "priority asked; the record has no more");
                passed();
                return null;
            }
            switch (e.getBodyCase()) {
                case PASS_PRIORITY -> {
                    source.consume(seat);
                    passed();
                    return null;
                }
                case CAST_SPELL, ACTIVATE_ABILITY, PLAY_LAND, ACTIVATE_MANA_ABILITY -> {
                    // a cost that uses cards (discard, exile, sacrifice) is paid with the recorded cards: Forge AI would
                    // pick its own, which are not public when hidden (from a hand)
                    List<Long> costIds = e.getBodyCase() == Event.BodyCase.CAST_SPELL ? e.getCastSpell().getCostIdsList()
                        : e.getBodyCase() == Event.BodyCase.ACTIVATE_ABILITY ? e.getActivateAbility().getCostIdsList() : List.of();
                    boolean costCards = false;
                    for (long id : costIds) {
                        Card c = card(id);
                        costCards |= c != null && c.isInZone(ZoneType.Hand);
                    }
                    if ((e.getBodyCase() == Event.BodyCase.CAST_SPELL || e.getBodyCase() == Event.BodyCase.ACTIVATE_ABILITY)
                            && !costCards) {
                        // Forge AI first: in the same state it chooses the same action, prepared its own way (costs it
                        // chose while weighing it); otherwise the recorded action is set up here
                        List<SpellAbility> own = forgeAiAction();
                        if (own != null && own.size() == 1 && sameAction(own.get(0), e)) {
                            source.consume(seat);
                            // the modes Forge AI chose while weighing are the recorded ones (sameAction): their
                            // recorded choice belongs to this cast
                            source.takeChoice(seat, "chooseModeForAbility");
                            Event oc = e.getBodyCase() == Event.BodyCase.CAST_SPELL
                                ? source.takeChoice(seat, "chooseOptionalCosts") : null;
                            if (oc != null && !optionalCostNames(own.get(0)).equals(optionalCostNames(oc.getResolutionChoice()))) {
                                // Forge AI weighed another kicker combination: the recorded cast is played the
                                // human way, with the recorded optional costs
                                recordedOptionalCosts = oc.getResolutionChoice();
                                SpellAbility sa = action(e);
                                if (sa == null) {
                                    source.mismatch(seat, "recorded " + describe(e) + " not found among the seat's abilities");
                                    continue;
                                }
                                List<SpellAbility> out = new ArrayList<>();
                                out.add(sa);
                                return out;
                            }
                            aiPrepared = own.get(0);
                            preparedPayment = e.getBodyCase() == Event.BodyCase.CAST_SPELL ? e.getCastSpell().getPaymentList()
                                : e.getActivateAbility().getPaymentList();
                            return own;
                        }
                    }
                    SpellAbility sa = action(e);
                    if (sa == null) {
                        source.mismatch(seat, "recorded " + describe(e) + " not found among the seat's abilities");
                        source.consume(seat);
                        continue;
                    }
                    source.consume(seat);
                    List<SpellAbility> out = new ArrayList<>();
                    out.add(sa);
                    return out;
                }
                default -> {
                    source.mismatch(seat, "priority asked; next recorded: " + describe(e) + " (skipped)");
                    source.consume(seat);
                }
            }
        }
    }

    private void passed() {
        session.action(player, false, b -> b.setPassPriority(PassPriority.newBuilder().setSeat(seat)));
    }

    private SpellAbility action(Event e) {
        Game game = player.getGame();
        long cardId;
        switch (e.getBodyCase()) {
            case CAST_SPELL -> cardId = e.getCastSpell().getCardId();
            case ACTIVATE_ABILITY -> cardId = e.getActivateAbility().getSourceId();
            case PLAY_LAND -> cardId = e.getPlayLand().getCardId();
            default -> cardId = e.getActivateManaAbility().getSourceId();
        }
        Card c = card(cardId);
        if (c == null) {
            return null;
        }
        List<SpellAbility> all = ComputerUtilAbility.getSpellAbilities(new CardCollection(c), player);
        List<SpellAbility> options = ComputerUtilAbility.getOriginalAndAltCostAbilities(all, player);
        // first the ones whose cost uses other cards exactly when the recorded one did (mana cost or alternative cost)
        for (boolean strict : new boolean[] {true, false}) {
            SpellAbility found = action(e, c, options, strict);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private SpellAbility action(Event e, Card c, List<SpellAbility> options, boolean strict) {
        for (SpellAbility sa : options) {
            sa.setActivatingPlayer(player);
            switch (e.getBodyCase()) {
                case PLAY_LAND -> {
                    PlayLand pl = e.getPlayLand();
                    if (sa.isLandAbility() && session.keys().faceKey(c, sa.getCardState() == null ? c.getCurrentState()
                            : sa.getCardState()).equals(pl.getLand())) {
                        return sa;
                    }
                }
                case CAST_SPELL -> {
                    if (sa.isSpell() && session.keys().spellKey(sa).equals(e.getCastSpell().getSpell())
                            && (!strict || usesCards(sa) == e.getCastSpell().getCostIdsCount() > 0)) {
                        pendingCast = e.getCastSpell();
                        pendingActivation = null;
                        if (e.getCastSpell().hasX()) {
                            sa.setXManaCostPaid((int) e.getCastSpell().getX());
                        }
                        return sa;
                    }
                }
                case ACTIVATE_ABILITY -> {
                    if (!sa.isSpell() && !sa.isManaAbility() && !sa.isLandAbility()
                            && e.getActivateAbility().getAbility().equals(session.keys().abilityKey(sa))
                            && (!strict || usesCards(sa) == e.getActivateAbility().getCostIdsCount() > 0)) {
                        pendingActivation = e.getActivateAbility();
                        pendingCast = null;
                        if (e.getActivateAbility().hasX()) {
                            sa.setXManaCostPaid((int) e.getActivateAbility().getX());
                        }
                        return sa;
                    }
                }
                default -> {
                    if (sa.isManaAbility() && e.getActivateManaAbility().getAbility().equals(session.keys().abilityKey(sa))) {
                        expressChoice(sa, e.getActivateManaAbility().getProducedList());
                        return sa;
                    }
                }
            }
        }
        return null;
    }

    /** An action Forge AI chose and prepared itself, the same as the recorded one. */
    private SpellAbility aiPrepared;
    /** The recorded payment of the action Forge AI prepared. */
    private List<magicdosa.protocol.v1.ManaPayment> preparedPayment;

    private boolean sameAction(SpellAbility sa, Event e) {
        Card host = player.getGame().getCardState(sa.getHostCard());
        if (e.getBodyCase() == Event.BodyCase.CAST_SPELL) {
            CastSpell c = e.getCastSpell();
            return sa.isSpell() && host == card(c.getCardId()) && session.keys().spellKey(sa).equals(c.getSpell())
                && session.targets(sa).equals(c.getTargetsList()) && session.modes(sa).equals(c.getModesList())
                && usesCards(sa) == c.getCostIdsCount() > 0;
        }
        ActivateAbility a = e.getActivateAbility();
        return !sa.isSpell() && host == card(a.getSourceId()) && a.getAbility().equals(session.keys().abilityKey(sa))
            && session.targets(sa).equals(a.getTargetsList()) && usesCards(sa) == a.getCostIdsCount() > 0;
    }

    /**
     * Whether the action's cost uses other cards (exile, discard, sacrifice, tap or return them): the same spell can be
     * cast by its mana cost or by an alternative cost that exiles a card (Force of Negation), under one spell key.
     */
    private static boolean usesCards(SpellAbility sa) {
        if (sa.getPayCosts() == null) {
            return false;
        }
        for (forge.game.cost.CostPart part : sa.getPayCosts().getCostParts()) {
            if (part instanceof forge.game.cost.CostPartWithList && !part.payCostFromSource()
                    && !(part instanceof forge.game.cost.CostPartMana)) {
                return true;
            }
        }
        return false;
    }

    /** Casts or activates the recorded action the way Forge AI does, with the recorded targets. */
    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) {
        if (sa == aiPrepared) {
            aiPrepared = null;
            // the recorded mana sources make their mana first; Forge AI pays from the pool before it taps anything
            // itself (otherwise it may tap other sources than the real game did: a Talisman instead of a land)
            List<magicdosa.protocol.v1.ManaPayment> payment = preparedPayment;
            if (preparedPayment != null) {
                mergedUses(preparedPayment).forEach(this::makeMana);
                preparedPayment = null;
            }
            boolean played = super.playChosenSpellAbility(sa); // as in the real game
            if (played && payment != null) {
                settlePayment(sa, payment);
            }
            return played;
        }
        if (sa.isLandAbility() || sa.isManaAbility()) {
            return super.playChosenSpellAbility(sa);
        }
        List<ObjectRef> targets = pendingCast != null ? pendingCast.getTargetsList()
            : pendingActivation != null ? pendingActivation.getTargetsList() : List.of();
        List<magicdosa.protocol.v1.ManaPayment> payment = pendingCast != null ? pendingCast.getPaymentList()
            : pendingActivation != null ? pendingActivation.getPaymentList() : List.of();
        recordedTargets = new ArrayList<>(targets);
        recordedCosts = new ArrayList<>(pendingCast != null ? pendingCast.getCostIdsList()
            : pendingActivation != null ? pendingActivation.getCostIdsList() : List.of());
        recordedModes = pendingCast != null ? pendingCast.getModesList()
            : pendingActivation != null ? pendingActivation.getModesList() : List.of();
        pendingX = pendingCast != null && pendingCast.hasX() ? Long.valueOf(pendingCast.getX())
            : pendingActivation != null && pendingActivation.hasX() ? Long.valueOf(pendingActivation.getX()) : null;
        session.beginAction();
        try {
            recordedPayment = new ArrayList<>(payment);
            // the human play path: Forge asks this controller for modes, X, targets and the cards costs use,
            // and every answer comes from the recorded action (Forge AI pays the mana, from the pool first)
            scripted = true;
            boolean r;
            try {
                r = forge.game.player.PlaySpellAbility.playSpellAbility(this, player, sa);
            } finally {
                scripted = false;
            }
            session.playedWithoutStack(player, sa);
            if (!r) {
                source.mismatch(seat, "recorded " + (pendingCast != null ? "cast" : "activation") + " of " + sa.getHostCard()
                    + " (" + sa + ") was not played; last log: " + lastLog());
            }
            return r;
        } finally {
            session.endAction();
            pendingCast = null;
            pendingActivation = null;
        }
    }

    private String lastLog() {
        var entries = player.getGame().getGameLog().getLogEntries(null);
        return entries.isEmpty() ? "-" : entries.get(0).message();
    }

    /** The recorded colors for a mana ability that lets its controller choose them. */
    private static void expressChoice(SpellAbility m, List<magicdosa.protocol.v1.ManaUnit> produced) {
        if (m.getManaPart() == null || !(m.getManaPart().isAnyMana() || m.getManaPart().isComboMana())) {
            return;
        }
        List<String> units = new ArrayList<>();
        produced.forEach(u -> {
            for (int i = 0; i < Math.max(1, u.getAmount()); i++) {
                units.add(u.getColor());
            }
        });
        m.getManaPart().setExpressChoice(String.join(" ", units));
    }

    /** Whether the mana ability can make each of the colors (letters, "C" colorless). */
    private static boolean makes(SpellAbility m, List<String> colors) {
        if (m.getManaPart() == null) {
            return false;
        }
        for (String c : colors) {
            if (!m.getManaPart().canProduce(c, m)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The recorded payment's mana ability uses, one per source and ability with all the units it made: the record has
     * a piece per unit spent, and a source that makes two units of a choice of colors (Mystic Gate's {W}{U}) is
     * activated once, with both.
     */
    private static List<magicdosa.protocol.v1.ManaAbilityUse> mergedUses(List<magicdosa.protocol.v1.ManaPayment> payment) {
        java.util.LinkedHashMap<String, magicdosa.protocol.v1.ManaAbilityUse.Builder> uses = new java.util.LinkedHashMap<>();
        java.util.Set<String> withMade = new java.util.HashSet<>();
        for (magicdosa.protocol.v1.ManaPayment piece : payment) {
            if (!piece.hasAbility()) {
                continue;
            }
            var u = piece.getAbility();
            String key = u.getSourceId() + " " + u.getAbility();
            if (u.getMadeCount() > 0) {
                // all the activation made (protocol 10): its colours, the units the payment did not spend included
                uses.put(key, u.toBuilder().clearProduced().addAllProduced(u.getMadeList()).clearMade());
                withMade.add(key);
                continue;
            }
            var b = uses.get(key);
            if (b == null) {
                uses.put(key, u.toBuilder());
            } else if (!withMade.contains(key)) {
                b.addAllProduced(u.getProducedList());
            }
        }
        List<magicdosa.protocol.v1.ManaAbilityUse> out = new ArrayList<>();
        uses.values().forEach(b -> out.add(b.build()));
        return out;
    }

    /** Activates the recorded mana ability of a source for the recorded mana (it goes to the pool). */
    private void makeMana(magicdosa.protocol.v1.ManaAbilityUse use) {
        Card c = card(use.getSourceId());
        if (c == null) {
            source.mismatch(seat, "mana source " + use.getSourceId() + " unknown");
            return;
        }
        List<String> units = new ArrayList<>(); // one color letter per unit of mana, as Forge's express choice wants
        use.getProducedList().forEach(m -> {
            for (int i = 0; i < Math.max(1, m.getAmount()); i++) {
                units.add(m.getColor());
            }
        });
        // abilities under the recorded key; one printed "Add {W} or {U}" is one Forge ability per color: the one that
        // makes the recorded colors first
        List<SpellAbility> abilities = new ArrayList<>(c.getManaAbilities());
        abilities.sort(java.util.Comparator.comparing(m -> !makes(m, units)));
        for (SpellAbility m : abilities) {
            m.setActivatingPlayer(player);
            if (!use.getAbility().equals(session.keys().abilityKey(m)) || !m.canPlay()) {
                continue;
            }
            expressChoice(m, use.getProducedList());
            // a filter (Mystic Gate's {W/U}, {T}: Add two) pays its own cost: Forge AI spends each unit as it makes it,
            // so the pool is empty when it activates the filter in the real game, and it pays the cost by tapping a
            // source (that mana goes to the filter, so the record does not name it). The mana made before stays
            // aside meanwhile (the pool's own iterator removes the exact units).
            List<forge.game.mana.Mana> aside = new ArrayList<>();
            if (m.getPayCosts() != null && m.getPayCosts().hasManaCost()) {
                for (java.util.Iterator<forge.game.mana.Mana> it = player.getManaPool().iterator(); it.hasNext(); ) {
                    aside.add(it.next());
                    it.remove();
                }
            }
            boolean made;
            try {
                made = forge.ai.ComputerUtil.playNoStack(player, m, player.getGame(), false);
            } finally {
                aside.forEach(player.getManaPool()::addManaNoEvent);
            }
            if (made) {
                return;
            }
        }
        for (forge.game.mana.Mana m : player.getManaPool()) {
            if (m.getSourceCard() != null && m.getSourceCard().getId() == c.getId()) {
                return; // made earlier (a priority action) and floating: the payment takes it from the pool
            }
        }
        if (c.isTapped()) {
            // already tapped for this payment (Forge AI tapped it while weighing how to pay): the payment goes on with
            // what is there; a payment that ends differently shows in the decisions and the game end
            return;
        }
        source.mismatch(seat, "mana ability " + use.getAbility() + " of " + c + " could not be activated");
    }

    /**
     * A spell cast while something resolves ("you may cast it"): Forge AI casts it as in the real game; its recorded
     * cast is used up here so that the record stays aligned.
     */
    @Override
    public boolean playSaFromPlayEffect(SpellAbility tgtSA) {
        Event e = source.peek(seat);
        if (e != null && e.getBodyCase() == Event.BodyCase.CAST_SPELL) {
            Card recorded = card(e.getCastSpell().getCardId());
            if (recorded != null && recorded == player.getGame().getCardState(tgtSA.getHostCard())) {
                source.consume(seat);
            }
        }
        return super.playSaFromPlayEffect(tgtSA);
    }

    /** While a recorded action is played the human way: answers come from it. */
    private boolean scripted;
    private List<ObjectRef> recordedTargets = List.of();
    private List<Long> recordedCosts = List.of();
    private List<Integer> recordedModes = List.of();
    private Long pendingX;
    private List<magicdosa.protocol.v1.ManaPayment> recordedPayment = List.of();

    /**
     * Paying the mana of a recorded action: the recorded sources make their mana first (where the real game tapped
     * them, after the spell went on the stack), and Forge AI pays from the pool before it taps anything itself.
     */
    @Override
    public boolean payManaCost(forge.card.mana.ManaCost toPay, forge.game.cost.CostPartMana costPartMana, SpellAbility sa,
                               String prompt, forge.game.mana.ManaConversionMatrix matrix, boolean effect) {
        List<magicdosa.protocol.v1.ManaPayment> payment = null;
        if (scripted && !recordedPayment.isEmpty()) {
            payment = recordedPayment;
            recordedPayment = List.of(); // taken first: making the mana pays the mana abilities' own costs here too
            mergedUses(payment).forEach(this::makeMana);
        }
        boolean paid = super.payManaCost(toPay, costPartMana, sa, prompt, matrix, effect);
        if (paid && payment != null) {
            settlePayment(sa, payment);
        }
        return paid;
    }

    /**
     * After Forge AI paid a recorded action: the mana it spent becomes the recorded mana. The recorded sources made all
     * their mana first ({C}{C} from an Eldrazi Temple, of which the real game spent one), so Forge AI may have spent
     * another unit and left a recorded one floating (a {G} instead of the Temple's second {C}). Such units trade places:
     * the recorded unit leaves the pool for the paying mana, the other one goes back to the pool. Units are told apart
     * by color and source (the public view of the pool); Forge's Mana.equals compares by value, so the pool's own
     * iterator removes the exact unit.
     */
    private void settlePayment(SpellAbility sa, List<magicdosa.protocol.v1.ManaPayment> payment) {
        ObservationBuilder observations = session.observationBuilder();
        List<String> recorded = new ArrayList<>();
        for (magicdosa.protocol.v1.ManaPayment piece : payment) {
            // a source gone since (a sacrificed Eldrazi Spawn) shows as no source on its mana
            if (piece.hasAbility()) {
                long id = piece.getAbility().getSourceId();
                for (magicdosa.protocol.v1.ManaUnit u : piece.getAbility().getProducedList()) {
                    for (int i = 0; i < Math.max(1, u.getAmount()); i++) {
                        recorded.add(u.getColor() + "/" + (card(id) == null ? 0 : id));
                    }
                }
            } else {
                long id = piece.getPool().getSourceId();
                recorded.add(piece.getPool().getColor() + "/" + (card(id) == null ? 0 : id));
            }
        }
        List<forge.game.mana.Mana> paying = sa.getPayingMana();
        List<String> missing = new ArrayList<>(recorded); // recorded units Forge AI did not spend
        List<forge.game.mana.Mana> wrong = new ArrayList<>(); // units it spent that the record does not have
        for (forge.game.mana.Mana m : paying) {
            magicdosa.protocol.v1.PoolMana pm = observations.poolMana(m);
            if (!missing.remove(pm.getColor() + "/" + pm.getSourceId())) {
                wrong.add(m);
            }
        }
        for (forge.game.mana.Mana w : wrong) {
            forge.game.mana.Mana swap = null;
            for (forge.game.mana.Mana m : player.getManaPool()) {
                magicdosa.protocol.v1.PoolMana pm = observations.poolMana(m);
                if (missing.contains(pm.getColor() + "/" + pm.getSourceId())) {
                    swap = m;
                    break;
                }
            }
            if (swap == null) {
                List<String> spent = new ArrayList<>();
                paying.forEach(m -> spent.add(observations.poolMana(m).getColor() + "/" + observations.poolMana(m).getSourceId()));
                source.mismatch(seat, "payment of " + sa.getHostCard() + ": recorded " + recorded + ", spent " + spent);
                return;
            }
            magicdosa.protocol.v1.PoolMana pm = observations.poolMana(swap);
            missing.remove(pm.getColor() + "/" + pm.getSourceId());
            for (java.util.Iterator<forge.game.mana.Mana> it = player.getManaPool().iterator(); it.hasNext(); ) {
                if (it.next() == swap) {
                    it.remove();
                    break;
                }
            }
            player.getManaPool().addManaNoEvent(w);
            for (int i = 0; i < paying.size(); i++) {
                if (paying.get(i) == w) {
                    paying.set(i, swap);
                    break;
                }
            }
        }
        if (!missing.isEmpty()) {
            List<String> spent = new ArrayList<>();
            paying.forEach(m -> spent.add(observations.poolMana(m).getColor() + "/" + observations.poolMana(m).getSourceId()));
            source.mismatch(seat, "payment of " + sa.getHostCard() + ": recorded " + recorded + ", spent " + spent);
        }
    }

    @Override
    public boolean chooseTargetsFor(SpellAbility currentAbility) {
        if (!scripted) {
            return super.chooseTargetsFor(currentAbility);
        }
        currentAbility.resetTargets();
        int max = Math.max(1, currentAbility.getMaxTargets());
        while (!recordedTargets.isEmpty() && currentAbility.getTargets().size() < max) {
            GameObject o = targetOf(recordedTargets.get(0));
            if (o == null || (o instanceof GameEntity ge && !currentAbility.canTarget(ge))) {
                break;
            }
            currentAbility.getTargets().add(o);
            recordedTargets.remove(0);
        }
        return currentAbility.isTargetNumberValid();
    }

    @Override
    public forge.game.cost.CostDecisionMakerBase getCostDecisionMaker(Player p, SpellAbility ability, boolean effect, String prompt) {
        if (!scripted || recordedCosts.isEmpty()) {
            return super.getCostDecisionMaker(p, ability, effect, prompt);
        }
        return new RecordedCost(p, ability, effect);
    }

    /** The cards a cost uses, from the recorded action (those valid for this cost), else Forge AI's choice. */
    private final class RecordedCost extends forge.ai.AiCostDecision {
        private final SpellAbility ability;

        RecordedCost(Player p, SpellAbility ability, boolean effect) {
            super(p, ability, effect);
            this.ability = ability;
        }

        private forge.game.cost.PaymentDecision recorded(forge.game.cost.CostPartWithList cost, CardCollectionView valid,
                                                          int amount, java.util.function.Supplier<forge.game.cost.PaymentDecision> ai) {
            CardCollection out = new CardCollection();
            for (long id : new ArrayList<>(recordedCosts)) {
                Card c = card(id);
                if (c != null && valid.contains(c) && out.size() < amount) {
                    out.add(c);
                    recordedCosts.remove(id);
                }
            }
            return out.size() == amount ? forge.game.cost.PaymentDecision.card(out) : ai.get();
        }

        private CardCollectionView valid(forge.game.cost.CostPartWithList cost, Iterable<ZoneType> zones) {
            CardCollection all = new CardCollection();
            for (ZoneType z : zones) {
                all.addAll(player.getCardsIn(z));
            }
            return forge.game.card.CardLists.getValidCards(all, cost.getType().split(";"), player, ability.getHostCard(), ability);
        }

        @Override
        public forge.game.cost.PaymentDecision visit(forge.game.cost.CostExile cost) {
            if (cost.payCostFromSource()) {
                return super.visit(cost);
            }
            return recorded(cost, valid(cost, cost.getFrom()), cost.getAbilityAmount(ability), () -> super.visit(cost));
        }

        @Override
        public forge.game.cost.PaymentDecision visit(forge.game.cost.CostDiscard cost) {
            if (cost.payCostFromSource()) {
                return super.visit(cost);
            }
            return recorded(cost, valid(cost, List.of(ZoneType.Hand)), cost.getAbilityAmount(ability), () -> super.visit(cost));
        }

        @Override
        public forge.game.cost.PaymentDecision visit(forge.game.cost.CostSacrifice cost) {
            if (cost.payCostFromSource()) {
                return super.visit(cost);
            }
            return recorded(cost, valid(cost, List.of(ZoneType.Battlefield)), cost.getAbilityAmount(ability), () -> super.visit(cost));
        }

        @Override
        public forge.game.cost.PaymentDecision visit(forge.game.cost.CostTapType cost) {
            return recorded(cost, valid(cost, List.of(ZoneType.Battlefield)), cost.getAbilityAmount(ability), () -> super.visit(cost));
        }
    }

    /** The recorded targets, in order, over the parts of the ability that target (each up to its maximum). */
    private void setTargets(SpellAbility root, List<ObjectRef> targets) {
        int next = 0;
        for (SpellAbility s = root; s != null; s = s.getSubAbility()) {
            if (!s.usesTargeting()) {
                continue;
            }
            s.resetTargets();
            int max = s.getMaxTargets();
            int taken = 0;
            while (next < targets.size() && taken < Math.max(1, max)) {
                GameObject o = targetOf(targets.get(next));
                if (o == null || (o instanceof GameEntity ge && !s.canTarget(ge))) {
                    break;
                }
                s.getTargets().add(o);
                next++;
                taken++;
            }
        }
        if (next < targets.size()) {
            source.mismatch(seat, "targets " + targets + " not all placed on " + root);
        }
    }

    /** A target: a player, a card, or a spell / ability on the stack by its entity ID. */
    private GameObject targetOf(ObjectRef r) {
        if (r.hasPlayerSeat()) {
            return session.player(r.getPlayerSeat());
        }
        Card c = card(r.getEntityId());
        if (c != null && c.isInZone(ZoneType.Stack)) {
            for (var si : player.getGame().getStack()) {
                if (si.getSpellAbility().isSpell() && si.getSpellAbility().getHostCard() == c) {
                    return si.getSpellAbility();
                }
            }
        }
        if (c != null) {
            return c;
        }
        for (var si : player.getGame().getStack()) {
            if (!si.getSpellAbility().isSpell() && session.ids().hasAbilityId(si) && session.ids().abilityId(si) == r.getEntityId()) {
                return si.getSpellAbility();
            }
        }
        return null;
    }

    // ---- before the game ----

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        Event e = take(Event.BodyCase.MULLIGAN);
        if (e == null) {
            source.mismatch(seat, "mulligan asked; none recorded");
            return super.mulliganKeepHand(firstPlayer, cardsToReturn);
        }
        reportMulligan(e.getMulligan().getKeep());
        return e.getMulligan().getKeep();
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        Event e = take(Event.BodyCase.BOTTOM_CARDS);
        if (e == null) {
            source.mismatch(seat, "cards to bottom asked; none recorded");
            return super.tuckCardsViaMulligan(hand, cardsToReturn);
        }
        CardCollection out = new CardCollection();
        for (long id : e.getBottomCards().getCardIdsList()) {
            Card c = card(id);
            if (c != null) {
                out.add(c);
            }
        }
        reportBottom(out);
        return out;
    }

    // ---- combat ----

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        Event e = take(Event.BodyCase.DECLARE_ATTACKERS);
        if (e == null) {
            return; // no attack recorded: nobody attacks
        }
        for (Attack a : e.getDeclareAttackers().getAttacksList()) {
            Card c = card(a.getAttackerId());
            GameEntity defender = entity(a.getDefender());
            if (c == null || defender == null) {
                source.mismatch(seat, "attack " + a + " names an unknown object");
                continue;
            }
            combat.addAttacker(c, defender);
        }
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        Event e = take(Event.BodyCase.DECLARE_BLOCKERS);
        if (e == null) {
            return;
        }
        for (Block b : e.getDeclareBlockers().getBlocksList()) {
            Card blocker = card(b.getBlockerId());
            Card attacker = card(b.getAttackerId());
            if (blocker == null || attacker == null) {
                source.mismatch(seat, "block " + b + " names an unknown object");
                continue;
            }
            combat.addBlocker(attacker, blocker);
        }
    }

    // ---- trigger order ----

    @Override
    public List<SpellAbility> orderSimultaneousSa(List<SpellAbility> activePlayerSAs) {
        if (activePlayerSAs.size() < 2) {
            return activePlayerSAs;
        }
        Event e = take(Event.BodyCase.TRIGGER_ORDER);
        if (e == null) {
            source.mismatch(seat, "trigger order asked; none recorded");
            return super.orderSimultaneousSa(activePlayerSAs);
        }
        List<SpellAbility> out = new ArrayList<>();
        for (long id : e.getTriggerOrder().getAbilityIdsList()) {
            for (SpellAbility sa : activePlayerSAs) {
                if (!out.contains(sa) && session.triggerId(sa) == id) {
                    out.add(sa);
                }
            }
        }
        for (SpellAbility sa : activePlayerSAs) {
            if (!out.contains(sa)) {
                out.add(sa); // entries without a trigger ID keep Forge's order after the recorded ones
            }
        }
        // the event the real Forge AI made is reproduced by the shadow game's own session
        session.reportAction(b -> b.setTriggerOrder(e.getTriggerOrder()));
        return out;
    }

    // ---- public choices: from the record ----

    private List<Card> cards(ResolutionChoice r) {
        List<Card> out = new ArrayList<>();
        for (ObjectRef o : r.getChosenList()) {
            GameEntity e = entity(o);
            if (e instanceof Card c) {
                out.add(c);
            }
        }
        return out;
    }

    private <T extends GameEntity> List<T> entities(ResolutionChoice r, Iterable<T> offered) {
        List<T> out = new ArrayList<>();
        for (ObjectRef o : r.getChosenList()) {
            GameEntity e = entity(o);
            for (T t : offered) {
                if (t == e) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    private static boolean flag(ResolutionChoice r, boolean otherwise) {
        return r == null || r.getValuesCount() == 0 ? otherwise : r.getValues(0).getFlag();
    }

    private static Integer number(ResolutionChoice r) {
        return r == null || r.getValuesCount() == 0 ? null : (int) r.getValues(0).getNumber();
    }

    private static String text(ResolutionChoice r) {
        return r == null || r.getValuesCount() == 0 ? null : r.getValues(0).getText();
    }

    @Override
    protected CardCollectionView answerChoosePermanentsToSacrifice(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) {
        ResolutionChoice r = choice("choosePermanentsToSacrifice");
        return r == null ? super.answerChoosePermanentsToSacrifice(sa, min, max, validTargets, message) : new CardCollection(cards(r));
    }

    @Override
    protected CardCollectionView answerChoosePermanentsToDestroy(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) {
        ResolutionChoice r = choice("choosePermanentsToDestroy");
        return r == null ? super.answerChoosePermanentsToDestroy(sa, min, max, validTargets, message) : new CardCollection(cards(r));
    }

    @Override
    protected Integer answerAnnounceRequirements(SpellAbility ability, int min, int max, String announce) {
        if (scripted && "X".equals(announce)) {
            Long x = pendingX;
            if (x != null) {
                return (int) (long) x;
            }
        }
        ResolutionChoice r = choice("announceRequirements");
        Integer n = number(r);
        if (n == null && scripted && !"X".equals(announce) && session.inAction()) {
            // another number announced while casting the recorded spell, not recorded: Forge AI sets it on its own play
            // path without asking. The ones our decks have count cards a cost takes from the hand (March of
            // Otherworldly Light: how many white cards to exile): the recorded cost cards still in the hand.
            int inHand = 0;
            for (long id : recordedCosts) {
                Card c = card(id);
                if (c != null && c.isInZone(ZoneType.Hand)) {
                    inHand++;
                }
            }
            if (inHand >= min && inHand <= max) {
                return inHand;
            }
        }
        return n == null ? super.answerAnnounceRequirements(ability, min, max, announce) : n;
    }

    @Override
    protected CardCollectionView answerChooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa, String title, int min, int max,
                                                             boolean isOptional, Map<String, Object> params) {
        ResolutionChoice r = choice("chooseCardsForEffect");
        if (r == null && min == 0 && source.noRecordMeansNone()) {
            return new CardCollection();
        }
        return r == null ? super.answerChooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params) : new CardCollection(cards(r));
    }

    @Override
    protected <T extends GameEntity> T answerChooseSingleEntityForEffect(FCollectionView<T> optionList, DelayedReveal delayedReveal, SpellAbility sa,
                                                                         String title, boolean isOptional, Player relatedPlayer, Map<String, Object> params) {
        ResolutionChoice r = choice("chooseSingleEntityForEffect");
        if (r == null) {
            return super.answerChooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, relatedPlayer, params);
        }
        List<T> chosen = entities(r, optionList);
        return chosen.isEmpty() ? null : chosen.get(0);
    }

    @Override
    protected <T extends GameEntity> List<T> answerChooseEntitiesForEffect(FCollectionView<T> optionList, int min, int max, DelayedReveal delayedReveal,
                                                                           SpellAbility sa, String title, Player relatedPlayer, Map<String, Object> params) {
        ResolutionChoice r = choice("chooseEntitiesForEffect");
        if (r == null && min == 0 && source.noRecordMeansNone()) {
            return new ArrayList<>();
        }
        return r == null ? super.answerChooseEntitiesForEffect(optionList, min, max, delayedReveal, sa, title, relatedPlayer, params)
            : entities(r, optionList);
    }

    @Override
    protected List<SpellAbility> answerChooseSpellAbilitiesForEffect(List<SpellAbility> spells, SpellAbility sa, String title, int num,
                                                                    Map<String, Object> params) {
        ResolutionChoice r = choice("chooseSpellAbilitiesForEffect");
        if (r == null) {
            return super.answerChooseSpellAbilitiesForEffect(spells, sa, title, num, params);
        }
        List<SpellAbility> out = new ArrayList<>();
        r.getOptionsList().forEach(i -> out.add(spells.get(i)));
        return out;
    }

    @Override
    protected SpellAbility answerChooseSingleSpellForEffect(List<SpellAbility> spells, SpellAbility sa, String title, Map<String, Object> params) {
        if (spells.size() <= 1) {
            return super.answerChooseSingleSpellForEffect(spells, sa, title, params);
        }
        ResolutionChoice r = choice("chooseSingleSpellForEffect");
        return r == null || r.getOptionsCount() == 0 ? super.answerChooseSingleSpellForEffect(spells, sa, title, params)
            : spells.get(r.getOptions(0));
    }

    @Override
    protected boolean answerConfirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message, List<String> options, Card cardToShow,
                                          Map<String, Object> params) {
        ResolutionChoice r = choice("confirmAction");
        return r == null ? super.answerConfirmAction(sa, mode, message, options, cardToShow, params) : flag(r, false);
    }

    @Override
    protected boolean answerConfirmReplacementEffect(ReplacementEffect replacementEffect, SpellAbility effectSA, GameEntity affected, String question) {
        ResolutionChoice r = choice("confirmReplacementEffect");
        return r == null ? super.answerConfirmReplacementEffect(replacementEffect, effectSA, affected, question) : flag(r, false);
    }

    @Override
    protected boolean answerConfirmTrigger(WrappedAbility sa) {
        ResolutionChoice r = choice("confirmTrigger");
        return r == null ? super.answerConfirmTrigger(sa) : flag(r, false);
    }

    @Override
    protected boolean answerWillPutCardOnTop(Card c) {
        ResolutionChoice r = choice("willPutCardOnTop");
        return r == null ? super.answerWillPutCardOnTop(c) : flag(r, true);
    }

    @Override
    protected CardCollection answerChooseCardsToDiscardFrom(Player playerDiscard, SpellAbility sa, CardCollection validCards, int min, int max,
                                                            CardCollectionView visibleToChooser) {
        ResolutionChoice r = choice("chooseCardsToDiscardFrom");
        return r == null ? super.answerChooseCardsToDiscardFrom(playerDiscard, sa, validCards, min, max, visibleToChooser) : new CardCollection(cards(r));
    }

    @Override
    protected CardCollectionView answerChooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        ResolutionChoice r = choice("chooseCardsToDiscardToMaximumHandSize");
        return r == null ? super.answerChooseCardsToDiscardToMaximumHandSize(numDiscard) : new CardCollection(cards(r));
    }

    @Override
    protected CardCollectionView answerChooseCardsToDelve(int genericAmount, CardCollection grave) {
        ResolutionChoice r = choice("chooseCardsToDelve");
        return r == null ? super.answerChooseCardsToDelve(genericAmount, grave) : new CardCollection(cards(r));
    }

    @Override
    protected List<AbilitySub> answerChooseModeForAbility(SpellAbility sa, List<AbilitySub> possible, int min, int num, boolean allowRepeat) {
        if (scripted && !recordedModes.isEmpty()) {
            List<AbilitySub> all = sa.getRootAbility().getAdditionalAbilityList("Choices");
            List<AbilitySub> out = new ArrayList<>();
            for (int i : recordedModes) {
                for (AbilitySub p : possible) {
                    if (i < all.size() && (p == all.get(i) || p.getDescription().equals(all.get(i).getDescription()))) {
                        out.add(p);
                        break;
                    }
                }
            }
            return out;
        }
        ResolutionChoice r = choice("chooseModeForAbility");
        if (r == null) {
            return super.answerChooseModeForAbility(sa, possible, min, num, allowRepeat);
        }
        List<AbilitySub> all = sa.getRootAbility().getAdditionalAbilityList("Choices");
        List<AbilitySub> out = new ArrayList<>();
        for (int i : r.getOptionsList()) {
            AbilitySub mode = i < all.size() ? all.get(i) : null;
            for (AbilitySub p : possible) {
                if (mode != null && (p == mode || p.getDescription().equals(mode.getDescription()))) {
                    out.add(p);
                    break;
                }
            }
        }
        return out;
    }

    @Override
    protected int answerChooseNumber(SpellAbility sa, String title, int min, int max) {
        if (max <= min) {
            return super.answerChooseNumber(sa, title, min, max);
        }
        Integer n = number(choice("chooseNumber"));
        return n == null ? super.answerChooseNumber(sa, title, min, max) : n;
    }

    @Override
    protected int answerChooseNumber(SpellAbility sa, String title, List<Integer> values, Player relatedPlayer) {
        if (values.size() <= 1) {
            return super.answerChooseNumber(sa, title, values, relatedPlayer);
        }
        Integer n = number(choice("chooseNumber"));
        return n == null ? super.answerChooseNumber(sa, title, values, relatedPlayer) : n;
    }

    @Override
    protected boolean answerChooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice, Boolean defaultChoice) {
        ResolutionChoice r = choice("chooseBinary");
        return r == null ? super.answerChooseBinary(sa, question, kindOfChoice, defaultChoice) : flag(r, false);
    }

    @Override
    protected byte answerChooseColor(String message, SpellAbility sa, forge.card.ColorSet colors) {
        if (colors.countColors() <= 1) {
            return super.answerChooseColor(message, sa, colors);
        }
        String t = text(choice("chooseColor"));
        if (t == null) {
            return super.answerChooseColor(message, sa, colors);
        }
        return forge.card.MagicColor.fromName(t);
    }

    @Override
    protected CounterType answerChooseCounterType(List<CounterType> options, SpellAbility sa, String prompt, Map<String, Object> params) {
        if (options.size() <= 1) {
            return super.answerChooseCounterType(options, sa, prompt, params);
        }
        String t = text(choice("chooseCounterType"));
        for (CounterType o : options) {
            if (ObservationBuilder.counterName(o).equals(t)) {
                return o;
            }
        }
        return super.answerChooseCounterType(options, sa, prompt, params);
    }

    @Override
    protected CardCollectionView answerChooseCardsForCost(CardCollectionView optionList, SpellAbility sa, forge.game.cost.CostPartWithList cpl,
                                                          int amount, boolean isOptional, String prompt) {
        ResolutionChoice r = choice("chooseCardsForCost");
        return r == null ? super.answerChooseCardsForCost(optionList, sa, cpl, amount, isOptional, prompt) : new CardCollection(cards(r));
    }

    @Override
    protected Card answerChooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList,
                                                       DelayedReveal delayedReveal, String selectPrompt, boolean isOptional, Player decider) {
        ResolutionChoice r = choice("chooseSingleCardForZoneChange");
        if (r == null) {
            return super.answerChooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal, selectPrompt, isOptional, decider);
        }
        List<Card> c = cards(r);
        return c.isEmpty() ? null : c.get(0);
    }

    @Override
    protected List<Card> answerChooseCardsForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList,
                                                        int min, int max, DelayedReveal delayedReveal, String selectPrompt, Player decider) {
        ResolutionChoice r = choice("chooseCardsForZoneChange");
        return r == null ? super.answerChooseCardsForZoneChange(destination, origin, sa, fetchList, min, max, delayedReveal, selectPrompt, decider)
            : cards(r);
    }

    @Override
    protected CardCollection answerOrderBlockers(Card attacker, CardCollection blockers) {
        ResolutionChoice r = choice("orderBlockers");
        return r == null ? super.answerOrderBlockers(attacker, blockers) : new CardCollection(cards(r));
    }

    @Override
    protected ReplacementEffect answerChooseSingleReplacementEffect(List<ReplacementEffect> possibleReplacers) {
        if (possibleReplacers.size() <= 1) {
            return super.answerChooseSingleReplacementEffect(possibleReplacers);
        }
        ResolutionChoice r = choice("chooseSingleReplacementEffect");
        return r == null || r.getOptionsCount() == 0 ? super.answerChooseSingleReplacementEffect(possibleReplacers)
            : possibleReplacers.get(r.getOptions(0));
    }

    @Override
    protected int answerChooseNumberForCostReduction(SpellAbility sa, int min, int max) {
        if (max <= min) {
            return super.answerChooseNumberForCostReduction(sa, min, max);
        }
        Integer n = number(choice("chooseNumberForCostReduction"));
        return n == null ? super.answerChooseNumberForCostReduction(sa, min, max) : n;
    }

    @Override
    protected int answerChooseNumberForKeywordCost(SpellAbility sa, forge.game.cost.Cost cost, forge.game.keyword.KeywordInterface keyword,
                                                   String prompt, int max) {
        if (max <= 0) {
            return super.answerChooseNumberForKeywordCost(sa, cost, keyword, prompt, max);
        }
        Integer n = number(choice("chooseNumberForKeywordCost"));
        return n == null ? super.answerChooseNumberForKeywordCost(sa, cost, keyword, prompt, max) : n;
    }

    @Override
    protected List<forge.game.spellability.OptionalCostValue> answerChooseOptionalCosts(SpellAbility choosen,
                                                                                      List<forge.game.spellability.OptionalCostValue> optionalCostValues) {
        if (!session.inAction() || optionalCostValues.isEmpty()) {
            return super.answerChooseOptionalCosts(choosen, optionalCostValues);
        }
        ResolutionChoice r = recordedOptionalCosts != null ? recordedOptionalCosts : choice("chooseOptionalCosts");
        recordedOptionalCosts = null;
        if (r == null) {
            return super.answerChooseOptionalCosts(choosen, optionalCostValues);
        }
        List<forge.game.spellability.OptionalCostValue> out = new ArrayList<>();
        if (r.getValuesCount() > 0 || r.getOptionsCount() == 0) {
            // a Forge AI seat's cast names its optional costs (ForgeGameSession.optionalCosts)
            // the ability found for the recorded cast may already be one with optional costs (Forge lists each
            // combination as an ability): those are not added again
            List<String> names = optionalCostNames(r);
            names.removeAll(optionalCostNames(choosen));
            for (forge.game.spellability.OptionalCostValue v : optionalCostValues) {
                if (names.contains(v.getType().name())) {
                    out.add(v);
                }
            }
            return out;
        }
        r.getOptionsList().forEach(i -> out.add(optionalCostValues.get(i)));
        return out;
    }

    /** The recorded optional costs of a Forge AI seat's cast, taken with it when Forge AI weighed another combination. */
    private ResolutionChoice recordedOptionalCosts;

    private static List<String> optionalCostNames(ResolutionChoice r) {
        List<String> out = new ArrayList<>();
        r.getValuesList().forEach(v -> out.add(v.getText()));
        java.util.Collections.sort(out);
        return out;
    }

    private static List<String> optionalCostNames(SpellAbility sa) {
        List<String> out = new ArrayList<>();
        sa.getOptionalCosts().forEach(c -> out.add(c.name()));
        java.util.Collections.sort(out);
        return out;
    }

    @Override
    protected List<SpellAbility> answerChooseSaToActivateFromOpeningHand(List<SpellAbility> usableFromOpeningHand) {
        ResolutionChoice r = choice("chooseSaToActivateFromOpeningHand");
        if (r == null && source.noRecordMeansNone()) {
            return new ArrayList<>(); // the real seat revealed nothing from its opening hand (its hand is a sample here)
        }
        if (r == null) {
            return super.answerChooseSaToActivateFromOpeningHand(usableFromOpeningHand);
        }
        List<SpellAbility> out = new ArrayList<>();
        if (r.getChosenCount() > 0) {
            // by the cards the abilities belong to: with a sampled hand (verification 2) the list holds other cards'
            // abilities and the recorded index points at another one
            for (ObjectRef ref : r.getChosenList()) {
                Card host = card(ref.getEntityId());
                for (SpellAbility sa : usableFromOpeningHand) {
                    if (host != null && player.getGame().getCardState(sa.getHostCard()) == host && !out.contains(sa)) {
                        out.add(sa);
                        break;
                    }
                }
            }
            return out;
        }
        r.getOptionsList().forEach(i -> out.add(usableFromOpeningHand.get(i)));
        return out;
    }

    @Override
    protected boolean answerConfirmPayment(forge.game.cost.CostPart costPart, String string, SpellAbility sa) {
        ResolutionChoice r = choice("confirmPayment");
        if (r == null && scripted) {
            // a cost of the recorded action played here: the real game paid it (Forge AI pays without being asked; its
            // own answer here weighs its life, e.g. a fetch land's 1 life at low life, and could cancel the action)
            return true;
        }
        return r == null ? super.answerConfirmPayment(costPart, string, sa) : flag(r, false);
    }

    @Override
    protected boolean answerPayCostToPreventEffect(forge.game.cost.Cost cost, SpellAbility sa, boolean alreadyPaid, FCollectionView<Player> allPayers) {
        ResolutionChoice r = choice("payCostToPreventEffect");
        if (r != null && !flag(r, false)) {
            return false;
        }
        // the payment itself is Forge AI's, as in the real game
        boolean paid = super.answerPayCostToPreventEffect(cost, sa, alreadyPaid, allPayers);
        if (!paid && r != null && flag(r, false) && payLifeOnly(cost, sa)) {
            // the record says it paid; Forge AI declined here (its decision weighs what it holds, which is a sample in
            // verification 2): a cost of life alone (a shock land's 2 life) is paid as recorded
            return true;
        }
        return paid;
    }

    /** Pays {@code cost} when it is only life; false (nothing paid) otherwise. */
    private boolean payLifeOnly(forge.game.cost.Cost cost, SpellAbility sa) {
        int life = 0;
        for (forge.game.cost.CostPart part : cost.getCostParts()) {
            if (!(part instanceof forge.game.cost.CostPayLife pl)) {
                return false;
            }
            life += pl.getAbilityAmount(sa);
        }
        if (life <= 0 || !player.canPayLife(life, false, sa)) {
            return false;
        }
        return player.payLife(life, sa, false);
    }

    @Override
    protected Map<Card, Integer> answerAssignCombatDamage(Card attacker, CardCollectionView blockers, CardCollectionView remaining,
                                                          int damageDealt, GameEntity defender, boolean overrideOrder) {
        ResolutionChoice r = choice("assignCombatDamage");
        if (r == null) {
            return super.answerAssignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder);
        }
        Map<Card, Integer> out = new java.util.LinkedHashMap<>();
        for (int i = 0; i < r.getChosenCount(); i++) {
            GameEntity e = entity(r.getChosen(i));
            int amount = i < r.getValuesCount() ? (int) r.getValues(i).getNumber() : 0;
            out.put(e instanceof Card c ? c : null, amount);
        }
        return out;
    }

    @Override
    protected forge.game.spellability.TargetChoices answerChooseNewTargetsFor(SpellAbility ability, java.util.function.Predicate<GameObject> filter,
                                                                              boolean optional) {
        ResolutionChoice r = choice("chooseNewTargetsFor");
        forge.game.spellability.TargetChoices out = super.answerChooseNewTargetsFor(ability, filter, optional);
        if (r == null || out == null) {
            return out;
        }
        out.clear();
        for (ObjectRef o : r.getChosenList()) {
            GameObject t = targetOf(o);
            if (t != null) {
                out.add(t);
            }
        }
        return out;
    }

    // Library arrangements keep their hidden part out of the stream: where the source knows the real library
    // (verification 1) the arrangement is taken from it, else Forge AI arranges (a belief sample in 2).

    /** Positions of the cards in the real library right after the recorded choice; null when unknown. */
    private java.util.Map<Card, Integer> realPositions(Event choice) {
        if (choice == null) {
            return null;
        }
        List<Long> lib = source.libraryAfter(seat, choice.getSeq());
        if (lib.isEmpty()) {
            return null;
        }
        java.util.Map<Card, Integer> out = new java.util.HashMap<>();
        for (int i = 0; i < lib.size(); i++) {
            Card c = card(lib.get(i));
            if (c != null) {
                out.putIfAbsent(c, i);
            }
        }
        return out;
    }

    private static CardCollection byPosition(Iterable<Card> cards, java.util.Map<Card, Integer> pos) {
        List<Card> out = new ArrayList<>();
        cards.forEach(out::add);
        out.sort(java.util.Comparator.comparingInt(c -> pos.getOrDefault(c, Integer.MAX_VALUE)));
        return new CardCollection(out);
    }

    @Override
    protected org.apache.commons.lang3.tuple.ImmutablePair<CardCollection, CardCollection> answerArrangeForScry(CardCollection topN) {
        Event e = answering > 1 ? null : source.takeChoice(seat, "arrangeForScry");
        java.util.Map<Card, Integer> pos = realPositions(e);
        if (pos == null || e.getResolutionChoice().getValuesCount() < 1) {
            return super.answerArrangeForScry(topN);
        }
        int onTop = Math.min((int) e.getResolutionChoice().getValues(0).getNumber(), topN.size());
        CardCollection ordered = byPosition(topN, pos);
        CardCollection top = new CardCollection(ordered.subList(0, onTop));
        CardCollection bottom = new CardCollection(ordered.subList(onTop, ordered.size()));
        return org.apache.commons.lang3.tuple.ImmutablePair.of(top, bottom);
    }

    @Override
    protected org.apache.commons.lang3.tuple.ImmutablePair<CardCollection, CardCollection> answerArrangeForSurveil(CardCollection topN) {
        Event e = answering > 1 ? null : source.takeChoice(seat, "arrangeForSurveil");
        if (e == null) {
            return super.answerArrangeForSurveil(topN);
        }
        java.util.Map<Card, Integer> pos = realPositions(e);
        CardCollection graveyard = new CardCollection(cards(e.getResolutionChoice()));
        List<Card> rest = new ArrayList<>();
        topN.forEach(rest::add);
        rest.removeAll(graveyard);
        CardCollection top = pos == null ? new CardCollection(rest) : byPosition(rest, pos);
        return org.apache.commons.lang3.tuple.ImmutablePair.of(top, graveyard);
    }

    @Override
    protected CardCollectionView answerOrderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone, SpellAbility sa) {
        Event e = answering > 1 ? null : source.takeChoice(seat, "orderMoveToZoneList");
        if (e != null && destinationZone != ZoneType.Library && e.getResolutionChoice().getChosenCount() == cards.size()) {
            return new CardCollection(cards(e.getResolutionChoice()));
        }
        if (e != null && destinationZone == ZoneType.Library) {
            java.util.Map<Card, Integer> pos = realPositions(e);
            if (pos != null) {
                // the order Forge then puts them in is checked against the real library at the next decision
                return byPosition(cards, pos);
            }
        }
        return super.answerOrderMoveToZoneList(cards, destinationZone, sa);
    }
}
