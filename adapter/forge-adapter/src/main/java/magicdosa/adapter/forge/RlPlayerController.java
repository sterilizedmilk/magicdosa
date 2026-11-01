package magicdosa.adapter.forge;

import forge.LobbyPlayer;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCost;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.combat.AttackRequirement;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityCantAttackBlock;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetRestrictions;
import forge.game.zone.ZoneType;
import magicdosa.protocol.v1.ActivateAbilityCandidate;
import magicdosa.protocol.v1.Attack;
import magicdosa.protocol.v1.Block;
import magicdosa.protocol.v1.Candidate;
import magicdosa.protocol.v1.CastSpellCandidate;
import magicdosa.protocol.v1.DecisionContext;
import magicdosa.protocol.v1.DecisionType;
import magicdosa.protocol.v1.FallbackReason;
import magicdosa.protocol.v1.PairLimit;
import magicdosa.protocol.v1.PassCandidate;
import magicdosa.protocol.v1.PassPriority;
import magicdosa.protocol.v1.PlayLandCandidate;
import magicdosa.protocol.v1.Rejection;
import magicdosa.protocol.v1.SelectionConstraints;

import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Forge controller for a seat agent-core decides for (design: forge-adapter 설계 RlPlayerController).
 *
 * Decisions the protocol defines and this slice handles are sent to agent-core: priority actions, targets of cast
 * spells and activated abilities, attackers, blockers, mulligan, cards to bottom, play/draw. Everything else is left to
 * Forge AI as an intended delegation and recorded by {@link ObservedAiController}. When agent-core gives no usable
 * answer, Forge AI decides and the decision is recorded as an unintended fallback; nothing falls back silently.
 */
public class RlPlayerController extends ObservedAiController {
    private static final SelectionConstraints ONE = SelectionConstraints.newBuilder().setMin(1).setMax(1).build();

    public RlPlayerController(Game game, Player player, LobbyPlayer lobbyPlayer, ForgeGameSession session) {
        super(game, player, lobbyPlayer, session);
    }

    private static Candidate pass() {
        return Candidate.newBuilder().setPass(PassCandidate.getDefaultInstance()).build();
    }

    // ---- priority (design: 설계 검토 2, Q14) ----

    /** Legal non-mana spells, abilities and land plays now, from Forge's own legality checks. */
    private List<SpellAbility> legalActions() {
        Game game = player.getGame();
        CardCollection cards = ComputerUtilAbility.getAvailableCards(game, player);
        List<SpellAbility> all = ComputerUtilAbility.getSpellAbilities(cards, player);
        List<SpellAbility> out = new ArrayList<>();
        for (SpellAbility sa : ComputerUtilAbility.getOriginalAndAltCostAbilities(all, player)) {
            if (sa.isManaAbility()) {
                continue; // mana abilities are not stop points (Q14); payment is delegated in this slice
            }
            sa.setActivatingPlayer(player);
            if (!sa.checkRestrictions(sa.getHostCard(), player)) {
                continue;
            }
            if (sa.isLandAbility()) {
                if (sa.canPlay()) {
                    out.add(sa);
                }
                continue;
            }
            if (sa.isLegalAfterStack() && sa.canPlay() && ComputerUtilCost.canPayCost(sa, player, false)
                    && ComputerUtilAbility.isFullyTargetable(sa) && !getAi().hasFailedToCast(sa)) {
                out.add(sa);
            }
        }
        return out;
    }

    private Candidate candidate(SpellAbility sa) {
        Card host = sa.getHostCard();
        long id = session.ids().idOf(host);
        if (sa.isLandAbility()) {
            return Candidate.newBuilder().setPlayLand(PlayLandCandidate.newBuilder().setCardId(id)
                .setLand(session.keys().faceKey(host, sa.getCardState() != null ? sa.getCardState() : host.getCurrentState()))).build();
        }
        if (sa.isSpell()) {
            return Candidate.newBuilder().setCastSpell(CastSpellCandidate.newBuilder().setCardId(id)
                .setSpell(session.keys().spellKey(sa))).build();
        }
        ActivateAbilityCandidate.Builder a = ActivateAbilityCandidate.newBuilder().setSourceId(id);
        var key = session.keys().abilityKey(sa);
        if (key != null) {
            a.setAbility(key);
        }
        return Candidate.newBuilder().setActivateAbility(a).build();
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        session.syncZones();
        List<SpellAbility> actions = legalActions();
        if (actions.isEmpty()) {
            session.stats().autoPass();
            session.action(player, true, b -> b.setPassPriority(PassPriority.newBuilder().setSeat(session.seat(player))));
            return null;
        }
        List<Candidate> candidates = new ArrayList<>();
        candidates.add(pass());
        for (SpellAbility sa : actions) {
            candidates.add(candidate(sa));
        }
        ForgeGameSession.Asked asked = session.ask(player, DecisionType.DECISION_TYPE_PRIORITY, candidates, ONE, null);
        if (!asked.ok()) {
            session.record(asked, List.of(), "chooseSpellAbilityToPlay", true, asked.failure());
            return super.chooseSpellAbilityToPlay();
        }
        int chosen = asked.response().getChosen(0);
        session.record(asked, List.of(chosen), "chooseSpellAbilityToPlay", false, null);
        if (chosen == 0) {
            session.action(player, false, b -> b.setPassPriority(PassPriority.newBuilder().setSeat(session.seat(player))));
            return null;
        }
        List<SpellAbility> out = new ArrayList<>();
        out.add(actions.get(chosen - 1));
        return out;
    }

    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) {
        if (sa.isLandAbility()) {
            return super.playChosenSpellAbility(sa);
        }
        if (sa.getPayCosts() != null && sa.getPayCosts().hasManaCost()) {
            session.delegated(player, "payManaCost", DecisionType.DECISION_TYPE_MANA_PAYMENT, null);
        }
        boolean played = playWithRollback(sa, this::chooseTargetsByAgent);
        if (!played) {
            session.stats().fallback("playChosenSpellAbility: cast failed and was taken back: " + sa);
        }
        return true;
    }

    // ---- targets ----

    /** Chooses the targets of every targeting part of {@code sa} through agent-core. */
    private void chooseTargetsByAgent(SpellAbility sa) {
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            if (s.usesTargeting()) {
                chooseTargetsOf(s);
            }
        }
    }

    private boolean chooseTargetsOf(SpellAbility s) {
        TargetRestrictions tgt = s.getTargetRestrictions();
        s.resetTargets(); // Forge may ask again for the same ability (e.g. after a cost changed it)
        if (s.isDividedAsYouChoose()) {
            // dividing damage is an option choice the protocol has no candidates for yet: Forge AI targets it
            session.delegated(player, "chooseTargetsFor(divided)", DecisionType.DECISION_TYPE_TARGET, null);
            return getAi().doTrigger(s, true);
        }
        List<GameObject> options = new ArrayList<>(tgt.getAllCandidates(s));
        if (tgt.getZone().contains(ZoneType.Stack)) {
            // spells and abilities on the stack are targeted as SpellAbility objects, not as cards
            options.removeIf(o -> o instanceof Card c && c.getZone() != null && c.getZone().is(ZoneType.Stack));
            for (SpellAbilityStackInstance si : player.getGame().getStack()) {
                if (s.canTarget(si.getSpellAbility())) {
                    options.add(si.getSpellAbility());
                }
            }
        }
        options.removeIf(o -> o == s.getRootAbility() || o == s);
        int min = tgt.getMinTargets(s.getHostCard(), s);
        int max = Math.min(tgt.getMaxTargets(s.getHostCard(), s), options.size());
        if (options.isEmpty() || max == 0) {
            return min == 0;
        }
        List<Candidate> candidates = new ArrayList<>();
        for (GameObject o : options) {
            candidates.add(Candidate.newBuilder().setTarget(session.ref(o)).build());
        }
        SelectionConstraints c = SelectionConstraints.newBuilder().setMin(Math.min(min, max)).setMax(max).build();
        DecisionContext ctx = DecisionContext.newBuilder().setSourceId(session.ids().idOf(s.getHostCard())).build();
        ForgeGameSession.Asked asked = session.ask(player, DecisionType.DECISION_TYPE_TARGET, candidates, c, ctx);
        if (!asked.ok()) {
            session.record(asked, List.of(), "chooseTargetsFor", true, asked.failure());
            return getAi().doTrigger(s, true);
        }
        session.record(asked, asked.response().getChosenList(), "chooseTargetsFor", false, null);
        for (int i : asked.response().getChosenList()) {
            s.getTargets().add(options.get(i));
        }
        return true;
    }

    @Override
    public boolean chooseTargetsFor(SpellAbility currentAbility) {
        boolean ok = true;
        for (SpellAbility s = currentAbility; s != null; s = s.getSubAbility()) {
            if (s.usesTargeting()) {
                ok &= chooseTargetsOf(s);
            }
        }
        return ok;
    }

    // ---- combat ----

    /** Engine refusals of one decision before it counts as an unintended fallback (Q15). */
    private static final int MAX_REJECTIONS = 3;

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        session.syncZones();
        List<Card> creatures = new ArrayList<>();
        List<GameEntity> defenders = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        for (Card c : attacker.getCreaturesInPlay()) {
            if (!CombatUtil.canAttack(c)) {
                continue;
            }
            for (GameEntity d : combat.getDefenders()) {
                if (CombatUtil.canAttack(c, d)) {
                    creatures.add(c);
                    defenders.add(d);
                    candidates.add(Candidate.newBuilder().setAttack(Attack.newBuilder()
                        .setAttackerId(session.ids().idOf(c)).setDefender(session.ref(d))).build());
                }
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        // Q15: the combination rules single pairs cannot show
        SelectionConstraints.Builder c = SelectionConstraints.newBuilder().setMin(0).setMax(candidates.size());
        Map<Card, AttackRequirement> requirements = combat.getAttackConstraints().getRequirements();
        for (Card a : new LinkedHashSet<>(creatures)) {
            long id = session.ids().idOf(a);
            AttackRequirement req = requirements.get(a);
            if (req != null && req.hasRequirement()) {
                c.addRequiredIds(id);
            }
            c.addPairLimits(PairLimit.newBuilder().setId(id).setMax(1));
        }
        List<Rejection> rejections = new ArrayList<>();
        while (true) {
            ForgeGameSession.Asked asked = session.ask(player, DecisionType.DECISION_TYPE_DECLARE_ATTACKERS, candidates,
                c.build(), null, rejections);
            if (!asked.ok()) {
                session.record(asked, List.of(), "declareAttackers", true, asked.failure());
                break;
            }
            List<Integer> chosen = asked.response().getChosenList();
            String refused = null;
            Set<Card> used = new HashSet<>();
            for (int i : chosen) {
                if (!used.add(creatures.get(i))) {
                    refused = creatures.get(i) + " attacks twice";
                    break;
                }
                combat.addAttacker(creatures.get(i), defenders.get(i));
            }
            if (refused == null && !CombatUtil.validateAttackers(combat)) {
                refused = "attack requirements not met";
            }
            if (refused == null) {
                session.record(asked, chosen, "declareAttackers", false, null);
                return;
            }
            for (Card a : new ArrayList<>(combat.getAttackers())) {
                combat.removeFromCombat(a);
            }
            if (!rejected(asked, "declareAttackers", refused, rejections)) {
                break;
            }
        }
        super.declareAttackers(attacker, combat);
    }

    /**
     * Records a refused answer (Q15) and adds it to {@code rejections}; false when the refusals are used up, after
     * recording the unintended fallback.
     */
    private boolean rejected(ForgeGameSession.Asked asked, String method, String reason, List<Rejection> rejections) {
        session.stats().note(method + " refused by Forge: "
            + reason.replaceAll(" \\(\\d+\\)", "").replaceAll("\\d+ creatures", "N creatures"));
        if (rejections.size() + 1 >= MAX_REJECTIONS) {
            session.record(asked, List.of(), method, true, FallbackReason.FALLBACK_REASON_ILLEGAL_ACTION);
            return false;
        }
        session.recordRejected(asked, method, reason);
        rejections.add(Rejection.newBuilder().setDecisionId(asked.request().getDecisionId())
            .addAllChosen(asked.response().getChosenList()).setReason(reason).build());
        return true;
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        session.syncZones();
        List<Card> blockers = new ArrayList<>();
        List<Card> attackers = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        for (Card b : defender.getCreaturesInPlay()) {
            if (!CombatUtil.canBlock(b, combat)) {
                continue;
            }
            for (Card a : combat.getAttackers()) {
                if (CombatUtil.canBlock(a, b, combat)) {
                    blockers.add(b);
                    attackers.add(a);
                    candidates.add(Candidate.newBuilder().setBlock(Block.newBuilder()
                        .setBlockerId(session.ids().idOf(b)).setAttackerId(session.ids().idOf(a))).build());
                }
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        // Q15: blocks per blocker and blockers per attacker (menace and the like)
        SelectionConstraints.Builder c = SelectionConstraints.newBuilder().setMin(0).setMax(candidates.size());
        for (Card b : new LinkedHashSet<>(blockers)) {
            c.addPairLimits(PairLimit.newBuilder().setId(session.ids().idOf(b))
                .setMax(b.canBlockAny() ? 0 : 1 + b.canBlockAdditional()));
        }
        for (Card a : new LinkedHashSet<>(attackers)) {
            Pair<Integer, Integer> minMax = StaticAbilityCantAttackBlock.getMinMaxBlocker(a, defender);
            int min = minMax.getLeft() > 1 ? minMax.getLeft() : 0;
            int max = minMax.getRight() == Integer.MAX_VALUE ? 0 : minMax.getRight();
            if (min > 0 || max > 0) {
                c.addPairLimits(PairLimit.newBuilder().setId(session.ids().idOf(a)).setMinIfAny(min).setMax(max));
            }
        }
        List<Rejection> rejections = new ArrayList<>();
        while (true) {
            ForgeGameSession.Asked asked = session.ask(player, DecisionType.DECISION_TYPE_DECLARE_BLOCKERS, candidates,
                c.build(), null, rejections);
            if (!asked.ok()) {
                session.record(asked, List.of(), "declareBlockers", true, asked.failure());
                break;
            }
            List<Integer> chosen = asked.response().getChosenList();
            for (int i : chosen) {
                combat.addBlocker(attackers.get(i), blockers.get(i));
            }
            String invalid = CombatUtil.validateBlocks(combat, defender);
            if (invalid == null) {
                session.record(asked, chosen, "declareBlockers", false, null);
                return;
            }
            for (int i : chosen) {
                combat.removeBlockAssignment(attackers.get(i), blockers.get(i));
            }
            if (!rejected(asked, "declareBlockers", invalid, rejections)) {
                break;
            }
        }
        super.declareBlockers(defender, combat);
    }

    // ---- game start ----

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        List<Candidate> candidates = List.of(
            Candidate.newBuilder().setKeep(true).build(), Candidate.newBuilder().setKeep(false).build());
        ForgeGameSession.Asked asked = session.ask(player, DecisionType.DECISION_TYPE_MULLIGAN, candidates, ONE, null);
        boolean keep;
        if (asked.ok()) {
            keep = asked.response().getChosen(0) == 0;
            session.record(asked, List.of(asked.response().getChosen(0)), "mulliganKeepHand", false, null);
        } else {
            session.record(asked, List.of(), "mulliganKeepHand", true, asked.failure());
            keep = forgeAiMulliganKeepHand(firstPlayer, cardsToReturn);
        }
        reportMulligan(keep);
        return keep;
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        List<Card> cards = toList(hand);
        List<Candidate> candidates = new ArrayList<>();
        for (Card c : cards) {
            candidates.add(Candidate.newBuilder().setCardId(session.ids().idOf(c)).build());
        }
        SelectionConstraints c = SelectionConstraints.newBuilder().setMin(cardsToReturn).setMax(cardsToReturn).setOrdered(true).build();
        ForgeGameSession.Asked asked = session.ask(player, DecisionType.DECISION_TYPE_BOTTOM_CARDS, candidates, c, null);
        CardCollection chosen = new CardCollection();
        if (asked.ok()) {
            for (int i : asked.response().getChosenList()) {
                chosen.add(cards.get(i));
            }
            session.record(asked, asked.response().getChosenList(), "tuckCardsViaMulligan", false, null);
        } else {
            session.record(asked, List.of(), "tuckCardsViaMulligan", true, asked.failure());
            chosen.addAll(forgeAiTuckCards(hand, cardsToReturn));
        }
        reportBottom(chosen);
        return chosen;
    }

    private static List<Card> toList(CardCollectionView view) {
        List<Card> out = new ArrayList<>();
        view.forEach(out::add);
        return out;
    }

    @Override
    public Player chooseStartingPlayer(boolean isFirstGame) {
        List<Candidate> candidates = List.of(
            Candidate.newBuilder().setPlay(true).build(), Candidate.newBuilder().setPlay(false).build());
        ForgeGameSession.Asked asked = session.ask(player, DecisionType.DECISION_TYPE_PLAY_OR_DRAW, candidates, ONE, null);
        boolean play;
        if (asked.ok()) {
            play = asked.response().getChosen(0) == 0;
            session.record(asked, List.of(asked.response().getChosen(0)), "chooseStartingPlayer", false, null);
        } else {
            session.record(asked, List.of(), "chooseStartingPlayer", true, asked.failure());
            play = forgeAiStartingPlayer(isFirstGame) == player;
        }
        reportPlayOrDraw(play);
        if (play) {
            return player;
        }
        for (Player p : player.getGame().getPlayers()) {
            if (p != player) {
                return p;
            }
        }
        return player;
    }
}
