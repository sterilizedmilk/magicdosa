package magicdosa.agent.shadow;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import forge.game.Game;
import forge.game.phase.Phase;
import forge.game.player.Player;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * State a {@code GameMemento} capture leaves out (docs/upstream-analysis.md, candidate 4: Guava multimaps built with
 * {@code MultimapBuilder} are treated as leaves and not copied): each phase's "until" command maps and each zone's
 * cards added this turn and last turn. Captured with the turn-start memento and put back after a restore (read-only
 * reflection over Forge's fields; nothing in Forge is replaced). A workaround, like ShadowGame.repairHosts.
 */
final class MementoGaps {
    private static final String[] PHASE_MAPS = {"untilMap", "untilEndMap", "registerMap"};
    private static final String[] ZONE_MAPS = {"cardsAddedThisTurn", "cardsAddedLastTurn"};

    private MementoGaps() {
    }

    /** A copy of the maps now; running it puts them back. */
    @SuppressWarnings("unchecked")
    static Runnable capture(Game game) {
        List<Runnable> restore = new ArrayList<>();
        List<Phase> phases = List.of(game.getUntap(), game.getUpkeep(), game.getBeginOfCombat(), game.getEndOfCombat(),
            game.getEndOfTurn(), game.getCleanup());
        for (Phase ph : phases) {
            for (String name : PHASE_MAPS) {
                keep(field(Phase.class, name), ph, restore);
            }
        }
        List<Zone> zones = new ArrayList<>();
        for (Player p : game.getRegisteredPlayers()) {
            for (ZoneType z : ZoneType.values()) {
                Zone zone = p.getZone(z);
                if (zone != null) {
                    zones.add(zone);
                }
            }
        }
        zones.add(game.getStackZone());
        for (Zone zone : zones) {
            for (String name : ZONE_MAPS) {
                keep(field(Zone.class, name), zone, restore);
            }
        }
        return () -> restore.forEach(Runnable::run);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void keep(Field f, Object owner, List<Runnable> restore) {
        if (f == null) {
            return;
        }
        try {
            Multimap live = (Multimap) f.get(owner);
            if (live == null) {
                return;
            }
            Multimap copy = ArrayListMultimap.create(live);
            restore.add(() -> {
                live.clear();
                live.putAll(copy);
            });
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Field field(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                // superclass
            }
        }
        return null;
    }
}
