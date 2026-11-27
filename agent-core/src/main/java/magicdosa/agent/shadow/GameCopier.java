package magicdosa.agent.shadow;

import magicdosa.adapter.forge.GameCreation;
import forge.ai.simulation.*;

import com.google.common.collect.*;
import forge.LobbyPlayer;
import forge.ai.AIOption;
import forge.ai.LobbyPlayerAi;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.CardType;
import forge.card.CardStateName;
import forge.game.*;
import forge.game.ability.effects.DetachedCardEffect;
import forge.game.card.Card;
import forge.game.card.CardCloneStates;
import forge.game.card.CardCopyService;
import forge.game.card.CardFactory;
import forge.game.card.CounterType;
import forge.game.card.token.TokenInfo;
import forge.game.combat.Combat;
import forge.game.mana.Mana;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.TriggerType;
import forge.game.zone.PlayerZoneBattlefield;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class GameCopier {
    /** Experiment knob: -1 copies whole libraries, n >= 0 only the top n cards of each. */
    public static int LIBRARY_LIMIT = -1;
    private static final ZoneType[] ZONES = new ZoneType[] {
        ZoneType.Battlefield,
        ZoneType.Hand,
        ZoneType.Graveyard,
        ZoneType.Library,
        ZoneType.Exile,
        ZoneType.Stack,
        ZoneType.Command,
        // Static abilities that work in every zone (devoid: Thief of Existence, Sowing Mycospawn) are registered as
        // static effects of sideboard cards too; without their source copied, mapping the effects fails ("Couldn't
        // map", 2026-11-10). Forge's own GameCopier (forge-ai simulation) has the same gap: upstream PR candidate.
        ZoneType.Sideboard,
    };

    private Game origGame;
    private BiMap<Player, Player> playerMap = HashBiMap.create();
    private BiMap<Card, Card> cardMap = HashBiMap.create();
    private CopiedGameObjectMap gameObjectMap;
    private GameSnapshot snapshot = null;
    /** Copy the spells on the stack too (see {@link GameSimulator#COPY_STACK}); decided per copier. */
    private boolean copyStack = GameSimulator.COPY_STACK;
    private final java.util.Map<SpellAbility, SpellAbility> stackMap = new java.util.HashMap<>();

    public GameCopier(Game origGame) {
        this.origGame = origGame;
        if (origGame.EXPERIMENTAL_RESTORE_SNAPSHOT) {
            this.snapshot = new GameSnapshot(origGame);
        }
    }

    /** Makes {@link #makeCopy} copy the spells on the stack, so that a decision with a spell on the stack can be simulated. */
    public GameCopier withStack(final boolean copy) {
        this.copyStack = copy;
        return this;
    }

    public Game getOriginalGame() {
        return origGame;
    }

    public Game getCopiedGame() {
        return gameObjectMap.getGame();
    }

    public Game makeCopy() {
        return makeCopy(null, null);
    }
    public Game makeCopy(PhaseType advanceToPhase, Player aiPlayer) {
        if (origGame.EXPERIMENTAL_RESTORE_SNAPSHOT) {
            // How do we advance to phase when using restores?
            return snapshot.makeCopy();
        }

        List<RegisteredPlayer> origPlayers = origGame.getMatch().getPlayers();
        List<RegisteredPlayer> newPlayers = new ArrayList<>();
        for (RegisteredPlayer p : origPlayers) {
            newPlayers.add(clonePlayer(p));
        }

        GameRules currentRules = origGame.getRules();
        Match newMatch = new Match(currentRules, newPlayers, origGame.getView().getTitle());
        Game newGame = GameCreation.newGame(newPlayers, currentRules, newMatch);
        newGame.markAsSimulationCopy();
        newGame.setNoGUIUser();
        newGame.dangerouslySetTimestamp(origGame.getTimestamp());

        for (int i = 0; i < origGame.getPlayers().size(); i++) {
            Player origPlayer = origGame.getPlayers().get(i);
            Player newPlayer = newGame.getPlayer(origPlayer.getId());
            newPlayer.setTeam(origPlayer.getTeam());
            newPlayer.setLife(origPlayer.getLife(), null);
            newPlayer.setLifeLostLastTurn(origPlayer.getLifeLostLastTurn());
            newPlayer.setLifeLostThisTurn(origPlayer.getLifeLostThisTurn());
            newPlayer.setLifeGainedThisTurn(origPlayer.getLifeGainedThisTurn());
            newPlayer.setCommitedCrimeThisTurn(origPlayer.getCommittedCrimeThisTurn());
            newPlayer.setDilemmasFacedThisTurn(origPlayer.getDilemmasFacedThisTurn());
            newPlayer.setLifeStartedThisTurnWith(origPlayer.getLifeStartedThisTurnWith());
            newPlayer.setDamageReceivedThisTurn(origPlayer.getDamageReceivedThisTurn());
            newPlayer.setLandsPlayedThisTurn(origPlayer.getLandsPlayedThisTurn());
            newPlayer.setCounters(HashMultiset.create(origPlayer.getCounters()));
            newPlayer.setSpeed(origPlayer.getSpeed());
            // Blessing state travels with the copied command-zone effect card
            // (wired via copyEffectCardsToSnapshot below); calling setBlessing
            // here would create a second effect card the zone copy duplicates.
            newPlayer.setDescended(origPlayer.getDescended());
            newPlayer.setLibrarySearched(origPlayer.getLibrarySearched());
            newPlayer.setSpellsCastLastTurn(origPlayer.getSpellsCastLastTurn());
            for (int j = 0; j < origPlayer.getSpellsCastThisTurn(); j++) {
                newPlayer.addSpellCastThisTurn();
            }
            newPlayer.setMaxHandSize(origPlayer.getMaxHandSize());
            newPlayer.setUnlimitedHandSize(origPlayer.isUnlimitedHandSize());
            newPlayer.setCrankCounter(origPlayer.getCrankCounter());
            // TODO creatureAttackedThisTurn
            // the pool is copied once the cards exist (copyManaPools): its mana must belong to the copy
            playerMap.put(origPlayer, newPlayer);
        }

        PhaseHandler origPhaseHandler = origGame.getPhaseHandler();
        Player newPlayerTurn = playerMap.get(origPhaseHandler.getPlayerTurn());
        newGame.getPhaseHandler().devModeSet(origPhaseHandler.getPhase(), newPlayerTurn, origPhaseHandler.getTurn());
        newGame.getTriggerHandler().suppressMode(TriggerType.ChangesZone);
        for (Player p : newGame.getPlayers()) {
            ((PlayerZoneBattlefield) p.getZone(ZoneType.Battlefield)).setTriggers(false);
        }

        copyGameState(newGame, aiPlayer);
        copyManaPools(newGame);

        for (Player origPlayer : playerMap.keySet()) {
            Player newPlayer = playerMap.get(origPlayer);
            origPlayer.copyCommandersToSnapshot(newPlayer, gameObjectMap::map);
            origPlayer.copyEffectCardsToSnapshot(newPlayer, gameObjectMap::map);
            ((PlayerZoneBattlefield) newPlayer.getZone(ZoneType.Battlefield)).setTriggers(true);
        }
        newGame.getTriggerHandler().clearSuppression(TriggerType.ChangesZone);

        for (Card c : newGame.getCardsInGame()) {
            Card origCard = (Card) reverseFind(c);
            if (origCard.hasRemembered()) {
                for (Object o : origCard.getRemembered()) {
                    if (o instanceof GameObject) {
                        // Sometimes, a spell can "remember" a token card that's not in any zone
                        // (and thus wouldn't have been copied) - for example Swords to Plowshares
                        // remembering its target for LKI. Skip these to not crash in find().
                        if (o instanceof Card && ((Card)o).getZone() == null) {
                           continue;
                        }
                        // The same for an ability (a spell remembering itself or the ability that created it): the copy of
                        // the stack is only made below, so there is nothing to map it to yet.
                        if (o instanceof SpellAbility) {
                            continue;
                        }
                        c.addRemembered(find((GameObject) o));
                    } else {
                        System.err.println(c + " Remembered: " + o + "/" + o.getClass());
                        c.addRemembered(o);
                    }
                }
            }
            for (SpellAbility sa : c.getSpellAbilities()) {
                Player activatingPlayer = sa.getActivatingPlayer();
                if (activatingPlayer != null && activatingPlayer.getGame() != newGame) {
                    sa.setActivatingPlayer(gameObjectMap.map(activatingPlayer));
                }
            }
        }

        // Undo effects first before calculating them below, to avoid them applying twice.
        for (StaticEffect effect : origGame.getStaticEffects().getEffects()) {
            effect.removeMapped(gameObjectMap);
        }

        if (origPhaseHandler.getCombat() != null) {
            newGame.getPhaseHandler().setCombat(new Combat(origPhaseHandler.getCombat(), new CombatObjectMap(newGame)));
        }

        newGame.getAction().checkStateEffects(true); //ensure state based effects and triggers are updated
        newGame.getTriggerHandler().resetActiveTriggers();

        if (copyStack) {
            // putting a spell back on the stack is no cast: MagicStack.add would run the cast triggers (prowess) again
            newGame.getTriggerHandler().setSuppressAllTriggers(true);
            try {
                copyStack(origGame, newGame, gameObjectMap);
            } finally {
                newGame.getTriggerHandler().setSuppressAllTriggers(false);
            }
        }

        copyDelayedTriggers(newGame);
        copyPhaseCommands(newGame);
        copyCastLists(newGame);

        if (advanceToPhase != null) {
            newGame.getPhaseHandler().devAdvanceToPhase(advanceToPhase, () -> GameSimulator.resolveStack(newGame, aiPlayer.getWeakestOpponent()));
        }

        return newGame;
    }

    private void copyStack(Game origGame, Game newGame, IEntityMap map) {
        // The stack is listed from the top; copy it from the bottom up, because a spell that targets another spell
        // (a counterspell) can only be pointed at the copy of that spell once the copy exists.
        final List<SpellAbilityStackInstance> entries = new ArrayList<>();
        for (SpellAbilityStackInstance origEntry : origGame.getStack()) {
            entries.add(origEntry);
        }
        Collections.reverse(entries);
        for (SpellAbilityStackInstance origEntry : entries) {
            SpellAbility origSa = origEntry.getSpellAbility();
            Card origHostCard = origSa.getHostCard();
            Card newCard = map.map(origHostCard);
            SpellAbility newSa = null;
            if (origSa.isSpell() || (origSa.isActivatedAbility() && !origSa.isTrigger() && !origSa.isCopied())) {
                // an activated ability on the stack (Mishra's Bauble's, its source sacrificed as a cost) is found on
                // the copied source by its text, as a spell is; triggered abilities are not copied (stackCopyable)
                newSa = findSAInCard(origSa, newCard);
            }
            if (newSa != null) {
                newSa.setActivatingPlayer(map.map(origSa.getActivatingPlayer()));
                if (origSa.usesTargeting()) {
                    try {
                        for (GameObject o : origSa.getTargets()) {
                            newSa.getTargets().add(map.map(o));
                        }
                    } catch (final RuntimeException e) {
                        // a target that is not in the copy (it left the game meanwhile): leave this entry out rather
                        // than lose the whole copy
                        newSa.resetTargets();
                        continue;
                    }
                }
                newGame.getStack().add(newSa);
                stackMap.put(origSa, newSa);
            }
        } 
    }

    private RegisteredPlayer clonePlayer(RegisteredPlayer p) {
        RegisteredPlayer clone = new RegisteredPlayer(p.getDeck());
        LobbyPlayer lp = p.getPlayer();
        if (!(lp instanceof LobbyPlayerAi)) {
            // TODO should probably also override them if they're normal AI
            lp = new LobbyPlayerAi(p.getPlayer().getName(), Sets.newHashSet(AIOption.USE_FULL_SIMULATION));
        }
        clone.setPlayer(lp);
        return clone;
    }

    /**
     * Each player's mana pool, in order, as new mana of the copy: its player is the copied player, its source the copied
     * card (else the original's last known state) and its mana ability the copied card's matching one. Forge's copier
     * puts the original Mana objects into the copy's pool; spending them there counts the mana on the original player
     * (Player.addExpentThisTurn queues a ManaExpend trigger in the original game, which then holds the copy until it runs
     * its waiting triggers: phase-3 search ran out of heap). Upstream candidate.
     */
    private void copyManaPools(Game newGame) {
        for (Map.Entry<Player, Player> e : playerMap.entrySet()) {
            Player origPlayer = e.getKey();
            Player newPlayer = e.getValue();
            for (Mana m : origPlayer.getManaPool()) {
                Card src = m.getSourceCard();
                Card copySrc = src == null ? null : newGame.findById(src.getId());
                forge.game.spellability.AbilityManaPart part = m.getManaAbility();
                if (copySrc != null && part != null && part.getSourceSA() != null) {
                    SpellAbility found = findSAInCard(part.getSourceSA(), copySrc);
                    if (found != null && found.getManaPart() != null) {
                        part = found.getManaPart();
                    }
                }
                Card source = copySrc != null ? copySrc : src;
                if (source == null) {
                    newPlayer.getManaPool().addManaNoEvent(m); // no source to rebuild it from: as Forge's copier does
                    continue;
                }
                newPlayer.getManaPool().addManaNoEvent(new Mana(m.getColor(), source, part, newPlayer));
            }
        }
    }

    private void copyGameState(Game newGame, Player aiPlayer) {
        // Copied cards keep their original ids (id order is AI-visible via
        // Card.compareTo and id-keyed collections; renumbering makes forked
        // games deterministically diverge from the mainline). Sync the fresh-id
        // counters first so ids created during or after the copy cannot collide.
        newGame.dangerouslySyncCardIdCounters(origGame);
        newGame.EXPERIMENTAL_RESTORE_SNAPSHOT = origGame.EXPERIMENTAL_RESTORE_SNAPSHOT;
        newGame.AI_TIMEOUT = origGame.AI_TIMEOUT;
        newGame.setAge(origGame.getAge());

        // TODO countersAddedThisTurn

        if (origGame.getStartingPlayer() != null) {
            newGame.setStartingPlayer(playerMap.get(origGame.getStartingPlayer()));
        }
        if (origGame.getMonarch() != null) {
            newGame.setMonarch(playerMap.get(origGame.getMonarch()));
        }
        if (origGame.getMonarchBeginTurn() != null) {
            newGame.setMonarchBeginTurn(playerMap.get(origGame.getMonarchBeginTurn()));
        }
        if (origGame.getHasInitiative() != null) {
            newGame.setHasInitiative(playerMap.get(origGame.getHasInitiative()));
        }
        if (origGame.getDayTime() != null) {
            newGame.setDayTime(origGame.getDayTime());
        }

        for (ZoneType zone : ZONES) {
            if (zone == ZoneType.Library && LIBRARY_LIMIT >= 0) {
                // experiment: copy only the top LIBRARY_LIMIT cards of each library
                for (Player op : origGame.getPlayers()) {
                    List<Card> lib = new ArrayList<>(op.getCardsIn(ZoneType.Library));
                    for (int i = 0; i < Math.min(LIBRARY_LIMIT, lib.size()); i++) {
                        addCard(newGame, zone, lib.get(i), aiPlayer);
                    }
                }
                continue;
            }
            if (zone == ZoneType.Library) {
                // a library is filled in one go: Zone.add updates the owner's view of the whole zone (and its flashback
                // view) after each card, which made copying a library quadratic in its size
                for (Player op : origGame.getPlayers()) {
                    Player np = playerMap.get(op);
                    List<Card> cards = new ArrayList<>();
                    for (Card card : op.getCardsIn(ZoneType.Library)) {
                        Card newCard = createCardCopy(newGame, playerMap.get(card.getOwner()), card, aiPlayer);
                        cardMap.put(card, newCard);
                        newCard.setTurnInController(np); // as Zone.add does for a card from outside the zone
                        newCard.setTapped(false);
                        cards.add(newCard);
                    }
                    np.getZone(ZoneType.Library).setCards(cards);
                    for (Card card : op.getCardsIn(ZoneType.Library)) {
                        Card newCard = cardMap.get(card);
                        newCard.setTurnInZone(card.getTurnInZone());
                        if (card.isPlotted()) {
                            setPlotted(newCard);
                        }
                    }
                }
                continue;
            }
            for (Card card : origGame.getCardsIn(zone)) {
                addCard(newGame, zone, card, aiPlayer);
            }
            // TODO CardsAddedThisTurn is now messed up
        }
        gameObjectMap = new CopiedGameObjectMap(newGame);

        for (Card card : origGame.getCardsIn(ZoneType.Battlefield)) {
            Card otherCard = cardMap.get(card);
            otherCard.setGameTimestamp(card.getGameTimestamp());
            otherCard.setLayerTimestamp(card.getLayerTimestamp());
            otherCard.setSickness(card.hasSickness());
            otherCard.setState(card.getCurrentStateName(), false);
            if (card.isAttachedToEntity()) {
                GameEntity ge = gameObjectMap.map(card.getEntityAttachedTo());
                otherCard.setEntityAttachedTo(ge);
                ge.addAttachedCard(otherCard);
            }
            if (card.getCrewedByThisTurn() != null) {
                otherCard.setCrewedByThisTurn(card.getCrewedByThisTurn());
            }
            if (card.getCloneOrigin() != null) {
                otherCard.setCloneOrigin(cardMap.get(card.getCloneOrigin()));
            }
            if (card.getHaunting() != null) {
                otherCard.setHaunting(cardMap.get(card.getHaunting()));
            }
            if (card.getSaddledByThisTurn() != null) {
                otherCard.setSaddledByThisTurn(card.getSaddledByThisTurn());
            }
            if (card.isPaired()) {
                otherCard.setPairedWith(cardMap.get(card.getPairedWith()));
            }
            if (card.getCopiedPermanent() != null) {
                // TODO would it be safe to simply reuse the prototype?
                otherCard.setCopiedPermanent(new CardCopyService(card.getCopiedPermanent()).copyCard(false));
            }
            // TODO: Verify that the above relationships are preserved bi-directionally or not.
        }

        for (Card card : origGame.getCardsIn(ZoneType.Command)) {
            Card otherCard = cardMap.get(card);
            if (card.getEffectSource() != null) {
                // this matters for correct MayPlay matching
                otherCard.setEffectSource(cardMap.get(card.getEffectSource()));
            }
        }

        copyLeftThisTurn(newGame, aiPlayer);
    }

    /**
     * What left the battlefield and the graveyard this turn: revolt (Fatal Push, Narnam Renegade), morbid and the like
     * read it. Without it a play that depends on it is judged in a game where nothing has happened this turn.
     */
    private void copyLeftThisTurn(Game newGame, Player aiPlayer) {
        for (int pass = 0; pass < 2; pass++) {
            final List<Card> origList = pass == 0 ? origGame.getLeftBattlefieldThisTurn() : origGame.getLeftGraveyardThisTurn();
            for (Card lki : new ArrayList<>(origList)) {
                try {
                    final Card now = origGame.findById(lki.getId());
                    Card base = now == null ? null : cardMap.get(now);
                    if (base == null) {
                        // a token (it ceased to exist): rebuild it from the last known information
                        final Player owner = playerMap.get(lki.getOwner());
                        if (owner == null) {
                            continue;
                        }
                        base = createCardCopy(newGame, owner, lki, aiPlayer);
                    }
                    final Card copy = CardCopyService.getLKICopy(base);
                    final Player controller = lki.getController() == null ? null : playerMap.get(lki.getController());
                    if (controller != null && !controller.equals(copy.getController())) {
                        copy.setController(controller, copy.getGameTimestamp());
                    }
                    if (pass == 0) {
                        newGame.addLeftBattlefieldThisTurn(copy);
                    } else {
                        newGame.addLeftGraveyardThisTurn(copy);
                    }
                } catch (final RuntimeException e) {
                    // history is a refinement: a card that cannot be rebuilt is left out
                }
            }
        }
    }

    private static PaperCard hidden_info_card = new PaperCard(CardRules.fromScript(Lists.newArrayList("Name:hidden", "Types:Artifact", "Oracle:")), "", CardRarity.Common);
    private static final boolean PRUNE_HIDDEN_INFO = false;
    private static final boolean USE_FROM_PAPER_CARD = true;
    private Card createCardCopy(Game newGame, Player newOwner, Card c, Player aiPlayer) {
        if (c.isToken() && !c.isImmutable()) {
            Card result = new TokenInfo(c).makeOneToken(newOwner, c.getId());
            new CardCopyService(c).copyCopiableCharacteristics(result, null, null);
            // a token copy of a transforming (or otherwise multi-faced) permanent has its other faces too, and may be
            // showing one of them: without them it cannot be put back in the state it is in
            for (final CardStateName state : c.getStates()) {
                if (!result.hasState(state)) {
                    result.addAlternateState(state, false);
                    result.getState(state).copyFrom(c.getState(state), false);
                }
            }
            result.setBackSide(c.isBackSide());
            return result;
        }
        if (USE_FROM_PAPER_CARD && !c.isImmutable() && c.getPaperCard() != null) {
            Card newCard;
            if (PRUNE_HIDDEN_INFO && aiPlayer != null && !c.getView().canBeShownTo(aiPlayer.getView())) {
                // TODO also check REVEALED_CARDS memory
                newCard = new Card(c.getId(), hidden_info_card, newGame);
                newCard.setOwner(newOwner);
            } else {
                newCard = CardFactory.getCard(c.getPaperCard(), newOwner, c.getId(), newGame);
            }
            newCard.setCommander(c.isCommander());
            return newCard;
        }

        // TODO: The above is very expensive and accounts for the vast majority of GameCopier execution time.
        // The issue is that it requires parsing the original card from scratch from the paper card. We should
        // improve the copier to accurately copy the card from its actual state, so that the paper card shouldn't
        // be needed. Once the below code accurately copies the card, remove the USE_FROM_PAPER_CARD code path.
        Card newCard;
        if (c instanceof DetachedCardEffect)
            newCard = new DetachedCardEffect((DetachedCardEffect) c, newGame, false);
        else
            newCard = new Card(c.getId(), c.getPaperCard(), newGame);
        newCard.setGamePieceType(c.getGamePieceType());
        newCard.setOwner(newOwner);
        newCard.setName(c.getName());
        newCard.setCommander(c.isCommander());
        newCard.setType(new CardType(c.getType()));
        for (StaticAbility stAb : c.getStaticAbilities()) {
            newCard.addStaticAbility(stAb.copy(newCard, true));
        }
        for (SpellAbility sa : c.getSpellAbilities()) {
            SpellAbility saCopy = sa.copy(newCard, true);
            if (saCopy != null) {
                newCard.addSpellAbility(saCopy);
            } else {
                System.err.println(sa.toString());
            }
        }

        return newCard;
    }

    private void addCard(Game newGame, ZoneType zone, Card c, Player aiPlayer) {
        final Player owner = playerMap.get(c.getOwner());
        final Card newCard = createCardCopy(newGame, owner, c, aiPlayer);
        cardMap.put(c, newCard);

        // TODO ExiledWith

        Player zoneOwner = owner;
        // everything the CreatureEvaluator checks must be set here
        if (zone == ZoneType.Battlefield) {
            // TODO: Controllers' list with timestamps should be copied.
            zoneOwner = playerMap.get(c.getController());
            newCard.setController(zoneOwner, 0);

            if (c.isBattle()) {
                newCard.setProtectingPlayer(playerMap.get(c.getProtectingPlayer()));
            }

            newCard.setCameUnderControlSinceLastUpkeep(c.cameUnderControlSinceLastUpkeep());

            newCard.setPTTable(c.getSetPTTable());
            newCard.setPTCharacterDefiningTable(c.getSetPTCharacterDefiningTable());

            newCard.setPTBoost(c.getPTBoostTable());
            // TODO copy by map
            newCard.setDamage(c.getDamage());
            newCard.setDamageReceivedThisTurn(c.getDamageReceivedThisTurn());

            newCard.copyFrom(c);
            newCard.updateKeywordsCache();

            if (c.isTapped()) {
                newCard.setTapped(true);
            }
            if (c.isFaceDown()) {
                newCard.turnFaceDown(true);
                if (c.isManifested()) {
                    newCard.setManifested(c.getManifestedSA());
                }
                if (c.isCloaked()) {
                    newCard.setCloaked(c.getCloakedSA());
                }
            }
            if (c.isMonstrous()) {
                newCard.setMonstrous(true);
            }
            if (c.isRenowned()) {
                newCard.setRenowned(true);
            }
            if (c.isSolved()) {
                newCard.setSolved(true);
            }
            if (c.isSaddled()) {
                newCard.setSaddled(true);
            }
            if (c.isSuspected()) {
                newCard.setSuspected(true);
            }
            if (c.isPlaneswalker()) {
                for (SpellAbility sa : c.getAllSpellAbilities()) {
                    int active = sa.getActivationsThisTurn();
                    if (sa.isPwAbility() && active > 0) {
                        SpellAbility newSa = findSAInCard(sa, newCard);
                        if (newSa != null) {
                            for (int i = 0; i < active; i++) {
                                newCard.addAbilityActivated(newSa);
                            }
                        }
                    }
                }
            }

            newCard.setFlipped(c.isFlipped());
            for (Map.Entry<Long, CardCloneStates> e : c.getCloneStates().entrySet()) {
                newCard.addCloneState(e.getValue().copy(newCard, true), e.getKey());
            }

            Multiset<CounterType> counters = c.getCounters();
            if (!counters.isEmpty()) {
                newCard.setCounters(HashMultiset.create(counters));
            }
            if (c.hasChosenPlayer()) {
                newCard.setChosenPlayer(playerMap.get(c.getChosenPlayer()));
            }
            if (c.hasChosenType()) {
                newCard.setChosenType(c.getChosenType());
            }
            if (c.hasChosenType2()) {
                newCard.setChosenType2(c.getChosenType2());
            }
            if (c.hasChosenColor()) {
                newCard.setChosenColors(Lists.newArrayList(c.getChosenColors()));
            }
            if (c.hasNamedCard()) {
                newCard.setNamedCards(Lists.newArrayList(c.getNamedCards()));
            }

            newCard.setSprocket(c.getSprocket());

            newCard.setSVars(c.getSVars());
            newCard.copyChangedSVarsFrom(c);
        }

        if (zone == ZoneType.Exile && c.isFaceDown()) {
            // Face-down exile state (foretell, "exile face down" effects) must
            // survive the copy: cards rebuilt from their paper card default to
            // face up, leaking hidden information into the copied game.
            newCard.turnFaceDownNoUpdate();
            if (c.isForetold()) {
                newCard.setForetold(true);
                if (c.isForetoldCostByEffect()) {
                    newCard.setForetoldCostByEffect(true);
                }
            }
        }

        if (zone == ZoneType.Stack) {
            newGame.getStackZone().add(newCard);
        } else {
            zoneOwner.getZone(zone).add(newCard);
        }
        // adding to a zone stamps the copy's current turn: keep the original's ("entered this turn" decides, e.g., whether
        // a plotted card may be cast)
        newCard.setTurnInZone(c.getTurnInZone());
        if (c.isPlotted()) {
            // the field itself: setPlotted(true) runs a BecomesPlotted trigger
            setPlotted(newCard);
        }
    }

    private static final java.lang.reflect.Field DELAYED = declared(forge.game.trigger.TriggerHandler.class, "delayedTriggers");
    private static final java.lang.reflect.Field THIS_TURN_DELAYED = declared(forge.game.trigger.TriggerHandler.class, "thisTurnDelayedTriggers");
    private static final java.lang.reflect.Field PLAYER_DEFINED_DELAYED = declared(forge.game.trigger.TriggerHandler.class, "playerDefinedDelayedTriggers");
    private static final java.lang.reflect.Field PHASE_UNTIL = declared(forge.game.phase.Phase.class, "until");

    private static java.lang.reflect.Field declared(Class<?> c, String name) {
        try {
            java.lang.reflect.Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Delayed triggers the copy could not take over (their source has no copy); diagnostics. */
    private int delayedTriggersLeftOut;

    public int delayedTriggersLeftOut() {
        return delayedTriggersLeftOut;
    }

    /**
     * Registered delayed triggers (this turn's, later turns', a player's next turn), which Forge's copier leaves out
     * (upstream candidate), each bound to the copy's source card and players. The ones still queued as a phase command
     * (Mishra's Bauble's "next turn") are carried by {@link #copyPhaseCommands}.
     */
    @SuppressWarnings("unchecked")
    private void copyDelayedTriggers(Game newGame) {
        forge.game.trigger.TriggerHandler from = origGame.getTriggerHandler();
        forge.game.trigger.TriggerHandler to = newGame.getTriggerHandler();
        try {
            List<forge.game.trigger.Trigger> thisTurn = (List<forge.game.trigger.Trigger>) THIS_TURN_DELAYED.get(from);
            for (forge.game.trigger.Trigger t : new ArrayList<>((List<forge.game.trigger.Trigger>) DELAYED.get(from))) {
                forge.game.trigger.Trigger c = copyTrigger(t);
                if (c == null) {
                    continue;
                }
                if (thisTurn.stream().anyMatch(x -> x == t)) {
                    to.registerThisTurnDelayedTrigger(c);
                } else {
                    to.registerDelayedTrigger(c);
                }
            }
            Multimap<Player, forge.game.trigger.Trigger> byPlayer =
                (Multimap<Player, forge.game.trigger.Trigger>) PLAYER_DEFINED_DELAYED.get(from);
            for (Map.Entry<Player, forge.game.trigger.Trigger> e : new ArrayList<>(byPlayer.entries())) {
                forge.game.trigger.Trigger c = copyTrigger(e.getValue());
                Player p = playerMap.get(e.getKey());
                if (c != null && p != null) {
                    to.registerPlayerDefinedDelayedTrigger(p, c);
                }
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Cast and activation history the copy could not take over (its card has no copy); diagnostics. */
    private int castHistoryLeftOut;

    public int castHistoryLeftOut() {
        return castHistoryLeftOut;
    }

    /**
     * The spells cast this turn and last turn and the abilities activated this turn (storm counts, "your second spell
     * each turn" such as Cori-Steel Cutter's trigger, cost changes), which Forge's copier leaves out ("TODO update
     * thisTurnCast"): each entry is put on the copy's stack history with a last-known copy of the copy's card and the
     * copy's player, as MagicStack records them.
     */
    private void copyCastLists(Game newGame) {
        forge.game.zone.MagicStack from = origGame.getStack();
        forge.game.zone.MagicStack to = newGame.getStack();
        for (SpellAbility sa : from.getSpellsCastThisTurn()) {
            SpellAbility c = copyHistory(sa);
            if (c != null) {
                to.getSpellsCastThisTurn().add(c);
            }
        }
        for (Card c : from.getSpellsCastLastTurn()) {
            Card mapped = currentCopy(c);
            if (mapped == null) {
                castHistoryLeftOut++;
            } else {
                to.getSpellsCastLastTurn().add(CardCopyService.getLKICopy(mapped));
            }
        }
        for (SpellAbility sa : from.getAbilityActivatedThisTurn()) {
            SpellAbility c = copyHistory(sa);
            if (c != null) {
                to.getAbilityActivatedThisTurn().add(c);
            }
        }
    }

    private SpellAbility copyHistory(SpellAbility sa) {
        Card mapped = currentCopy(sa.getHostCard());
        if (mapped == null) {
            castHistoryLeftOut++;
            return null;
        }
        Card lki = CardCopyService.getLKICopy(mapped);
        Player activator = sa.getActivatingPlayer() == null ? null : playerMap.get(sa.getActivatingPlayer());
        return activator == null ? sa.copy(lki, true) : sa.copy(lki, activator, true);
    }

    /** The copy's card for {@code c} (an object of the original game or a last-known copy of one), or null. */
    private Card currentCopy(Card c) {
        if (c == null) {
            return null;
        }
        Card mapped = cardMap.get(c);
        if (mapped == null) {
            Card current = origGame.getCardState(c, null);
            mapped = current == null ? null : cardMap.get(current);
        }
        return mapped;
    }

    private static final java.lang.reflect.Field PHASE_AT = declared(forge.game.phase.Phase.class, "at");
    private static final java.lang.reflect.Field[] PHASE_MAPS = {declared(forge.game.phase.Phase.class, "untilMap"),
        declared(forge.game.phase.Phase.class, "untilEndMap"), declared(forge.game.phase.Phase.class, "registerMap")};

    /** Phase commands the copy could not take over, by command class and the value it could not map (diagnostics). */
    private final Map<String, Integer> phaseCommandsLeftOut = new java.util.TreeMap<>();

    public Map<String, Integer> phaseCommandsLeftOut() {
        return phaseCommandsLeftOut;
    }

    /**
     * The commands queued on the phases ("until end of turn": a pump's revert; a delayed trigger registered at the
     * cleanup; "until your next turn"), which Forge's copier leaves out, so a copied creature kept an end-of-turn pump
     * into the next turn. A command is an opaque object (a lambda or an anonymous class): a new one of the same class is
     * made with each captured value mapped to the copy ({@link #mapCaptured}); a command holding something the copy
     * cannot map is left out and counted. Read-only reflection over Forge's fields; nothing in Forge is replaced.
     */
    @SuppressWarnings("unchecked")
    private void copyPhaseCommands(Game newGame) {
        java.util.function.Function<Game, List<forge.game.phase.Phase>> phases = g -> List.of(g.getUntap(), g.getUpkeep(),
            g.getBeginOfCombat(), g.getEndOfCombat(), g.getEndOfTurn(), g.getCleanup());
        List<forge.game.phase.Phase> from = phases.apply(origGame);
        List<forge.game.phase.Phase> to = phases.apply(newGame);
        try {
            for (int i = 0; i < from.size(); i++) {
                for (java.lang.reflect.Field list : new java.lang.reflect.Field[] {PHASE_UNTIL, PHASE_AT}) {
                    List<forge.GameCommand> src = (List<forge.GameCommand>) list.get(from.get(i));
                    List<forge.GameCommand> dst = (List<forge.GameCommand>) list.get(to.get(i));
                    for (forge.GameCommand cmd : new ArrayList<>(src)) {
                        forge.GameCommand c = remapCommand(cmd, newGame);
                        if (c != null) {
                            dst.add(c);
                        }
                    }
                }
                for (java.lang.reflect.Field map : PHASE_MAPS) {
                    Multimap<Player, forge.GameCommand> src = (Multimap<Player, forge.GameCommand>) map.get(from.get(i));
                    Multimap<Player, forge.GameCommand> dst = (Multimap<Player, forge.GameCommand>) map.get(to.get(i));
                    for (Map.Entry<Player, forge.GameCommand> e : new ArrayList<>(src.entries())) {
                        Player p = playerMap.get(e.getKey());
                        forge.GameCommand c = p == null ? null : remapCommand(e.getValue(), newGame);
                        if (c != null) {
                            dst.put(p, c);
                        }
                    }
                }
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A value with no counterpart in the copy. */
    private static final Object UNMAPPED = new Object();

    /** A command of the same class with its captured values mapped to the copy; null (counted) when one cannot be. */
    private forge.GameCommand remapCommand(forge.GameCommand cmd, Game newGame) {
        Class<?> k = cmd.getClass();
        List<java.lang.reflect.Field> fields = new ArrayList<>();
        for (java.lang.reflect.Field f : k.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                fields.add(f);
            }
        }
        Object[] values = new Object[fields.size()];
        try {
            for (int i = 0; i < fields.size(); i++) {
                fields.get(i).setAccessible(true);
                Object v = mapCaptured(fields.get(i).get(cmd), newGame);
                if (v == UNMAPPED) {
                    leftOut(k, fields.get(i).getType().getSimpleName());
                    return null;
                }
                values[i] = v;
            }
            for (java.lang.reflect.Constructor<?> c : k.getDeclaredConstructors()) {
                if (fits(c.getParameterTypes(), values)) {
                    c.setAccessible(true);
                    return (forge.GameCommand) c.newInstance(values);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            leftOut(k, e.getClass().getSimpleName());
            return null;
        }
        leftOut(k, "no constructor");
        return null;
    }

    private void leftOut(Class<?> k, String why) {
        String name = k.getName();
        int lambda = name.indexOf("$$Lambda");
        if (lambda >= 0) {
            name = name.substring(0, lambda) + "$$Lambda";
        }
        phaseCommandsLeftOut.merge(name + " (" + why + ")", 1, Integer::sum);
    }

    private static boolean fits(Class<?>[] types, Object[] values) {
        if (types.length != values.length) {
            return false;
        }
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (values[i] == null) {
                if (t.isPrimitive()) {
                    return false;
                }
                continue;
            }
            if (t.isPrimitive()) {
                t = java.lang.invoke.MethodType.methodType(t).wrap().returnType();
            }
            if (!t.isAssignableFrom(values[i].getClass())) {
                return false;
            }
        }
        return true;
    }

    /**
     * A command's captured value in the copy: cards and players to their copies, the game and its trigger handler to the
     * copy's, a delayed trigger to its copy, collections element by element, commands recursively, plain values (numbers,
     * text, enums, effect singletons) as they are; anything else is {@link #UNMAPPED}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object mapCaptured(Object v, Game newGame) {
        if (v == null || v instanceof Number || v instanceof String || v instanceof Boolean || v instanceof Character
                || v instanceof Enum) {
            return v;
        }
        if (v instanceof Card c) {
            Card m = currentCopy(c);
            return m == null ? UNMAPPED : m;
        }
        if (v instanceof Player p) {
            Player m = playerMap.get(p);
            return m == null ? UNMAPPED : m;
        }
        if (v instanceof Game) {
            return v == origGame ? newGame : UNMAPPED;
        }
        if (v instanceof forge.game.trigger.TriggerHandler) {
            return v == origGame.getTriggerHandler() ? newGame.getTriggerHandler() : UNMAPPED;
        }
        if (v instanceof forge.game.trigger.Trigger t) {
            forge.game.trigger.Trigger m = copyTrigger(t);
            return m == null ? UNMAPPED : m;
        }
        if (v instanceof forge.GameCommand g) {
            forge.GameCommand m = remapCommand(g, newGame);
            return m == null ? UNMAPPED : m;
        }
        if (v instanceof forge.game.ability.SpellAbilityEffect) {
            return v; // effect classes hold no game state
        }
        if (v instanceof java.util.Collection<?> col) {
            java.util.Collection out;
            if (v instanceof forge.game.card.CardCollection) {
                out = new forge.game.card.CardCollection();
            } else if (v instanceof java.util.Set) {
                out = new java.util.LinkedHashSet<>();
            } else {
                out = new ArrayList<>();
            }
            for (Object o : col) {
                Object m = mapCaptured(o, newGame);
                if (m == UNMAPPED) {
                    return UNMAPPED;
                }
                out.add(m);
            }
            return out;
        }
        return UNMAPPED;
    }

    /** A copy of a delayed trigger bound to the copy's source card and players; null when its source has no copy. */
    private forge.game.trigger.Trigger copyTrigger(forge.game.trigger.Trigger t) {
        Card host = t.getHostCard();
        Card newHost = host == null ? null : cardMap.get(host);
        if (newHost == null && host != null) {
            Card current = origGame.getCardState(host, null);
            newHost = current == null ? null : cardMap.get(current);
        }
        if (newHost == null) {
            delayedTriggersLeftOut++;
            return null;
        }
        SpellAbility over = t.getOverridingAbility();
        SpellAbility newOver = null;
        if (over != null) {
            Player activator = over.getActivatingPlayer() == null ? null : playerMap.get(over.getActivatingPlayer());
            newOver = activator == null ? over.copy(newHost, true) : over.copy(newHost, activator, true);
        }
        forge.game.trigger.Trigger c = t.copy(newHost, true, false, newOver);
        if (t.getSpawningAbility() != null) {
            SpellAbility spawn = t.getSpawningAbility().copy(newHost, true);
            Player activator = spawn.getActivatingPlayer() == null ? null : playerMap.get(spawn.getActivatingPlayer());
            if (activator != null) {
                spawn.setActivatingPlayer(activator);
            }
            c.setSpawningAbility(spawn);
        }
        c.setActiveZone(null);
        return c;
    }

    private static final java.lang.reflect.Field PLOTTED;

    static {
        try {
            PLOTTED = Card.class.getDeclaredField("plotted");
            PLOTTED.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void setPlotted(Card c) {
        try {
            PLOTTED.setBoolean(c, true);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Whether {@link #withStack} copies every item of this game's stack: spells and activated abilities, not
     * triggered abilities, triggers waiting to be put on it, or copies of spells and abilities (search does not start
     * from a stack it cannot hold).
     */
    public static boolean stackCopyable(Game game) {
        if (game.getStack().hasSimultaneousStackEntries()) {
            return false; // triggers waiting to be put on the stack (the copy would ask their order first)
        }
        for (SpellAbilityStackInstance si : game.getStack()) {
            SpellAbility sa = si.getSpellAbility();
            if (sa.isTrigger() || sa.isCopied() || !(sa.isSpell() || sa.isActivatedAbility())) {
                return false;
            }
        }
        return true;
    }

    private static SpellAbility findSAInCard(SpellAbility sa, Card c) {
        String saDesc = sa.getDescription();
        for (SpellAbility cardSa : c.getAllSpellAbilities()) {
            if (saDesc.equals(cardSa.getDescription())) {
                return cardSa;
            }
        }
        return null;
    }

    /**
     * For copying combat: a planeswalker or battle that left the game while attacked (a token that died, rule 506.4c)
     * stays among Combat's attackable defenders, where Forge's Combat copy maps it and fails ("Couldn't map Jace
     * Token", 2026-11-10; it already uses a stand-in for the attacked-by entries). Such a card gets the same stand-in
     * here: id -1, its name and controller, in no zone. Forge issue: upstream PR candidate.
     */
    private class CombatObjectMap implements IEntityMap {
        private final Game copiedGame;
        private final Map<Card, Card> standIns = new java.util.IdentityHashMap<>();

        CombatObjectMap(Game copiedGame) {
            this.copiedGame = copiedGame;
        }

        @Override
        public Game getGame() {
            return copiedGame;
        }

        @Override
        public GameObject map(GameObject o) {
            if (o instanceof Card c && !cardMap.containsKey(c) && origGame.getCardState(c, null) == null) {
                return standIns.computeIfAbsent(c, k -> {
                    Card fake = new Card(-1, copiedGame);
                    fake.setName(k.getName());
                    if (k.getController() != null) {
                        fake.setController(playerMap.get(k.getController()), 0);
                    }
                    return fake;
                });
            }
            return find(o);
        }
    }

    private class CopiedGameObjectMap implements IEntityMap {
        private final Game copiedGame;

        public CopiedGameObjectMap(Game copiedGame) {
            this.copiedGame = copiedGame;
        }

        @Override
        public Game getGame() {
            return copiedGame;
        }

        @Override
        public GameObject map(GameObject o) {
            return find(o);
        }
    }

    public <T extends GameObject> T find(T o) {
        if (origGame.EXPERIMENTAL_RESTORE_SNAPSHOT) {
            return (T) snapshot.find(o);
        }

        T result = null;
        if (o instanceof Card) {
            result = (T) cardMap.get(o);
            if (result != null) {
                return result;
            } else {
                System.out.println("Couldn't map " + o + "/" + System.identityHashCode(o));
            }
        } else if (o instanceof Player) {
            result = (T) playerMap.get(o);
            if (result != null)
                return result;
        } else if (o instanceof SpellAbility) {
            // a spell on the stack (the target of a counterspell), only known when the stack was copied
            result = (T) stackMap.get(o);
            if (result != null)
                return result;
        }
        if (o != null)
            throw new RuntimeException("Couldn't map " + o + "/" + System.identityHashCode(o) + describeUnmapped(o));
        return result;
    }
    /** Where an object that has no copy is (diagnostics for "Couldn't map"). */
    private String describeUnmapped(GameObject o) {
        if (!(o instanceof Card c)) {
            return " (" + o.getClass().getSimpleName() + ")";
        }
        Card current = origGame.getCardState(c, null);
        return " zone=" + (c.getZone() == null ? "none" : c.getZone().getZoneType())
            + " lki=" + c.isLKI() + " current=" + (current == null ? "none" : current == c ? "same"
                : "other in " + (current.getZone() == null ? "none" : current.getZone().getZoneType()));
    }

    public GameObject reverseFind(GameObject o) {
        if (origGame.EXPERIMENTAL_RESTORE_SNAPSHOT) {
            return snapshot.reverseFind(o);
        }

        GameObject result = cardMap.inverse().get(o);
        if (result != null)
            return result;
        // TODO: Have only one GameObject map?
        return playerMap.inverse().get(o);
    }
}
