package magicdosa.adapter.forge;

import forge.card.MagicColor;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.item.PaperCard;
import forge.game.mana.ManaPool;
import forge.game.mana.Mana;
import magicdosa.protocol.v1.Attack;
import magicdosa.protocol.v1.Block;
import magicdosa.protocol.v1.CardCount;
import magicdosa.protocol.v1.Characteristics;
import magicdosa.protocol.v1.CombatState;
import magicdosa.protocol.v1.Counter;
import magicdosa.protocol.v1.DayNight;
import magicdosa.protocol.v1.Designation;
import magicdosa.protocol.v1.GameGlobal;
import magicdosa.protocol.v1.HiddenIdentity;
import magicdosa.protocol.v1.LibraryOrder;
import magicdosa.protocol.v1.MatchContext;
import magicdosa.protocol.v1.MatchPlayer;
import magicdosa.protocol.v1.ObjectRef;
import magicdosa.protocol.v1.Observation;
import magicdosa.protocol.v1.PlayerState;
import magicdosa.protocol.v1.PoolMana;
import magicdosa.protocol.v1.Privileged;
import magicdosa.protocol.v1.DeckList;
import magicdosa.protocol.v1.StackItem;
import magicdosa.protocol.v1.StackItemKind;
import magicdosa.protocol.v1.Step;
import magicdosa.protocol.v1.Zone;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Builds one seat's {@link Observation} and the {@link Privileged} block from a Forge game (design: 관측 스키마). */
final class ObservationBuilder {
    private final ForgeGameSession session;

    ObservationBuilder(ForgeGameSession session) {
        this.session = session;
    }

    Observation build(int viewerSeat) {
        Game game = session.game();
        Player viewer = session.player(viewerSeat);
        Observation.Builder b = Observation.newBuilder()
            .setViewerSeat(viewerSeat)
            .setMatch(matchContext(viewerSeat))
            .setGame(global(game));
        for (Player p : game.getRegisteredPlayers()) {
            b.addPlayers(playerState(p));
        }
        for (Card c : EntityIds.allCards(game)) {
            if (c.getZone() != null && c.getZone().is(ZoneType.Stack) && c.isCopiedSpell()) {
                continue; // spell copies are stack items only
            }
            b.addObjects(object(c, viewer));
        }
        for (SpellAbilityStackInstance si : game.getStack()) {
            b.addStack(stackItem(si));
        }
        Combat combat = game.getPhaseHandler().getCombat();
        if (combat != null) {
            b.setCombat(combat(combat));
        }
        // Q5: effects are optional; this adapter does not list them yet
        return b.setEffectsProvided(false).build();
    }

    MatchContext matchContext(int viewerSeat) {
        MatchContext.Builder b = MatchContext.newBuilder()
            .setMatchId(session.matchId())
            .setGameNumber(session.gameNumber());
        for (int seat = 0; seat < session.seatCount(); seat++) {
            Deck registered = session.registeredDeck(seat);
            MatchPlayer.Builder mp = MatchPlayer.newBuilder()
                .setSeat(seat)
                .setDeckName(registered.getName())
                .addAllMain(counts(registered.getMain().toFlatList()))
                .addAllSideboard(registered.has(DeckSection.Sideboard)
                    ? counts(registered.get(DeckSection.Sideboard).toFlatList()) : List.of())
                .setGamesWon(session.gamesWon(seat));
            if (seat == viewerSeat) {
                mp.addAllCurrentDeck(counts(session.player(seat).getRegisteredPlayer().getDeck().getMain().toFlatList()));
            }
            b.addPlayers(mp);
        }
        Player starting = session.game().getStartingPlayer();
        if (starting != null) {
            b.setStartingSeat(session.seat(starting));
        }
        return b.build();
    }

    private List<CardCount> counts(List<PaperCard> cards) {
        Map<String, Integer> byOracle = new TreeMap<>();
        for (PaperCard pc : cards) {
            byOracle.merge(session.keys().oracleIdByName(pc.getName()), 1, Integer::sum);
        }
        return byOracle.entrySet().stream()
            .map(e -> CardCount.newBuilder().setOracleId(e.getKey()).setCount(e.getValue()).build()).toList();
    }

    private GameGlobal global(Game game) {
        GameGlobal.Builder b = GameGlobal.newBuilder()
            .setTurn(game.getPhaseHandler().getTurn())
            .setStep(step(game))
            .setDayNight(game.isDay() ? DayNight.DAY_NIGHT_DAY : game.isNight() ? DayNight.DAY_NIGHT_NIGHT : DayNight.DAY_NIGHT_NONE);
        Player active = game.getPhaseHandler().getPlayerTurn();
        if (active != null) {
            b.setActiveSeat(session.seat(active));
        }
        Player prio = game.getPhaseHandler().getPriorityPlayer();
        if (prio != null) {
            b.setPrioritySeat(session.seat(prio));
        }
        return b.build();
    }

    static Step step(Game game) {
        PhaseType phase = game.getPhaseHandler().getPhase();
        if (phase == null) {
            return Step.STEP_PREGAME;
        }
        return switch (phase) {
            case UNTAP -> Step.STEP_UNTAP;
            case UPKEEP -> Step.STEP_UPKEEP;
            case DRAW -> Step.STEP_DRAW;
            case MAIN1 -> Step.STEP_MAIN1;
            case COMBAT_BEGIN -> Step.STEP_BEGIN_COMBAT;
            case COMBAT_DECLARE_ATTACKERS -> Step.STEP_DECLARE_ATTACKERS;
            case COMBAT_DECLARE_BLOCKERS -> Step.STEP_DECLARE_BLOCKERS;
            case COMBAT_FIRST_STRIKE_DAMAGE -> Step.STEP_FIRST_STRIKE_DAMAGE;
            case COMBAT_DAMAGE -> Step.STEP_COMBAT_DAMAGE;
            case COMBAT_END -> Step.STEP_END_COMBAT;
            case MAIN2 -> Step.STEP_MAIN2;
            case END_OF_TURN -> Step.STEP_END;
            case CLEANUP -> Step.STEP_CLEANUP;
        };
    }

    private PlayerState playerState(Player p) {
        PlayerState.Builder b = PlayerState.newBuilder()
            .setSeat(session.seat(p))
            .setLife(p.getLife())
            .setLandsPlayedThisTurn(p.getLandsPlayedThisTurn())
            .setHandSize(p.getCardsIn(ZoneType.Hand).size())
            .setLibrarySize(p.getCardsIn(ZoneType.Library).size())
            .setMulligans(session.mulligans(session.seat(p)))
            .setSpellsCastThisTurn(p.getSpellsCastThisTurn());
        for (CounterType type : p.getCounters().elementSet()) {
            b.addCounters(Counter.newBuilder().setKind(counterName(type)).setCount(p.getCounters().count(type)));
        }
        ManaPool pool = p.getManaPool();
        for (Mana m : pool) {
            b.addManaPool(poolMana(m));
        }
        Game game = session.game();
        if (game.getMonarch() == p) {
            b.addDesignations(Designation.newBuilder().setName("monarch"));
        }
        if (game.getHasInitiative() == p) {
            b.addDesignations(Designation.newBuilder().setName("initiative"));
        }
        if (p.hasBlessing()) {
            b.addDesignations(Designation.newBuilder().setName("citys_blessing"));
        }
        return b.build();
    }

    PoolMana poolMana(Mana m) {
        return PoolMana.newBuilder()
            .setColor(colorLetter(m.getColor()))
            .setAmount(1)
            .setSourceId(session.ids().idOf(m.getSourceCard()))
            .setPersists(m.isPersistentMana())
            .build();
    }

    static String colorLetter(byte color) {
        return switch (color) {
            case MagicColor.WHITE -> "W";
            case MagicColor.BLUE -> "U";
            case MagicColor.BLACK -> "B";
            case MagicColor.RED -> "R";
            case MagicColor.GREEN -> "G";
            default -> "C";
        };
    }

    static String counterName(CounterType type) {
        return type.getName().toLowerCase(java.util.Locale.ROOT);
    }

    static Zone zone(ZoneType z) {
        if (z == null) {
            return Zone.ZONE_NONE;
        }
        return switch (z) {
            case Library -> Zone.ZONE_LIBRARY;
            case Hand -> Zone.ZONE_HAND;
            case Battlefield -> Zone.ZONE_BATTLEFIELD;
            case Graveyard -> Zone.ZONE_GRAVEYARD;
            case Stack -> Zone.ZONE_STACK;
            case Exile -> Zone.ZONE_EXILE;
            case Command -> Zone.ZONE_COMMAND;
            case Sideboard -> Zone.ZONE_OUTSIDE_GAME;
            default -> Zone.ZONE_NONE;
        };
    }

    private magicdosa.protocol.v1.GameObject object(Card c, Player viewer) {
        ZoneType z = c.getZone() == null ? null : c.getZone().getZoneType();
        boolean visible = session.visible(c, viewer);
        magicdosa.protocol.v1.GameObject.Builder b = magicdosa.protocol.v1.GameObject.newBuilder()
            .setId(session.ids().idOf(c))
            .setZone(zone(z))
            .setOwnerSeat(session.seat(c.getOwner()))
            .setControllerSeat(session.seat(c.getController()))
            .setToken(c.isToken())
            .setFaceDown(c.isFaceDown());
        if (visible) {
            b.setOracleId(session.keys().oracleId(c)).setFace(session.keys().face(c)).setCopy(c.isCloned());
            if (c.isToken()) {
                b.setCharacteristics(characteristics(c));
            }
        }
        if (z == ZoneType.Graveyard || (z == ZoneType.Library && visible)) {
            b.setPosition(c.getZone().getCards().indexOf(c));
        }
        if (z == ZoneType.Battlefield) {
            b.setTapped(c.isTapped()).setSummoningSick(c.isSick()).setDamage(c.getDamage())
                .setAttachedToId(session.ids().idOf(c.getAttachedTo()));
            for (CounterType type : c.getCounters().elementSet()) {
                b.addCounters(Counter.newBuilder().setKind(counterName(type)).setCount(c.getCounters().count(type)));
            }
            if (c.isCreature()) {
                b.setPower(c.getNetPower()).setToughness(c.getNetToughness());
            }
        }
        return b.build();
    }

    private Characteristics characteristics(Card c) {
        Characteristics.Builder b = Characteristics.newBuilder().setName(c.getName());
        for (String t : c.getType().toString().replace("—", " ").split("\\s+")) {
            if (!t.isBlank() && !t.equals("-")) {
                b.addTypes(t);
            }
        }
        for (MagicColor.Color color : c.getColor()) {
            b.addColors(colorLetter(color.getColorMask()));
        }
        if (c.isCreature()) {
            b.setPower(c.getBasePower()).setToughness(c.getBaseToughness());
        }
        return b.build();
    }

    StackItem stackItem(SpellAbilityStackInstance si) {
        SpellAbility sa = si.getSpellAbility();
        StackItem.Builder b = StackItem.newBuilder().setControllerSeat(session.seat(si.getActivatingPlayer()));
        if (sa.isSpell()) {
            long id = session.ids().idOf(sa.getHostCard());
            b.setId(id).setKind(StackItemKind.STACK_ITEM_KIND_SPELL).setSpell(session.keys().spellKey(sa)).setSourceId(id);
        } else {
            b.setId(session.ids().abilityId(si))
                .setKind(sa.isTrigger() ? StackItemKind.STACK_ITEM_KIND_TRIGGERED : StackItemKind.STACK_ITEM_KIND_ACTIVATED)
                .setSourceId(session.ids().sourceOf(si, session.ids().idOf(sa.getHostCard())));
            var key = session.keys().abilityKey(sa);
            if (key != null) {
                b.setAbility(key);
            }
        }
        b.addAllTargets(session.targets(sa));
        if (sa.getXManaCostPaid() != null) {
            b.setX(sa.getXManaCostPaid());
        }
        return b.build();
    }

    private CombatState combat(Combat combat) {
        CombatState.Builder b = CombatState.newBuilder();
        for (Card attacker : combat.getAttackers()) {
            b.addAttacks(Attack.newBuilder().setAttackerId(session.ids().idOf(attacker))
                .setDefender(session.ref(combat.getDefenderByAttacker(attacker))));
            for (Card blocker : combat.getBlockers(attacker)) {
                b.addBlocks(Block.newBuilder().setBlockerId(session.ids().idOf(blocker)).setAttackerId(session.ids().idOf(attacker)));
            }
        }
        return b.build();
    }

    /** Everything hidden from {@code seat}, for critic / belief labels (training only). */
    Privileged privileged(int seat) {
        Player viewer = session.player(seat);
        Privileged.Builder b = Privileged.newBuilder();
        Game game = session.game();
        for (Card c : EntityIds.allCards(game)) {
            if (!session.visible(c, viewer)) {
                b.addIdentities(HiddenIdentity.newBuilder().setId(session.ids().idOf(c))
                    .setOracleId(session.keys().oracleId(c)).setFace(session.keys().face(c)));
            }
        }
        for (Player p : game.getRegisteredPlayers()) {
            LibraryOrder.Builder lib = LibraryOrder.newBuilder().setSeat(session.seat(p));
            for (Card c : p.getCardsIn(ZoneType.Library)) {
                lib.addIds(session.ids().idOf(c));
            }
            b.addLibraries(lib);
            b.addCurrentDecks(DeckList.newBuilder().setSeat(session.seat(p))
                .addAllCards(counts(p.getRegisteredPlayer().getDeck().getMain().toFlatList())));
        }
        return b.build();
    }

    static ObjectRef playerRef(int seat) {
        return ObjectRef.newBuilder().setPlayerSeat(seat).build();
    }

}
