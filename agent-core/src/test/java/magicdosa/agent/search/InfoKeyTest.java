package magicdosa.agent.search;

import magicdosa.protocol.v1.DecisionRequest;
import magicdosa.protocol.v1.GameObject;
import magicdosa.protocol.v1.Observation;
import magicdosa.protocol.v1.Zone;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The tree's information key does not depend on which hidden card a determinization dealt where (2026-11-27 depth
 * measurement: it did, and the tree never grew below the root's children), but still on what the viewer sees.
 */
class InfoKeyTest {

    private static GameObject hidden(long id, Zone zone) {
        return GameObject.newBuilder().setId(id).setZone(zone).setOwnerSeat(1).build();
    }

    private static GameObject seen(long id, Zone zone, String oracle) {
        return GameObject.newBuilder().setId(id).setZone(zone).setOwnerSeat(0).setOracleId(oracle).setFace(1).build();
    }

    private static DecisionRequest request(GameObject... objects) {
        return DecisionRequest.newBuilder().setSeat(0).setSeed(7)
            .setObservation(Observation.newBuilder().addAllObjects(java.util.List.of(objects))).build();
    }

    @Test
    void hiddenCardsDealtElsewhereGiveTheSameKey() {
        // the same information: two hidden cards in the opponent's hand, one in its library; which IDs differs
        String a = ForgeSearchGame.infoKey(request(seen(1, Zone.ZONE_HAND, "bolt"), hidden(10, Zone.ZONE_HAND),
            hidden(11, Zone.ZONE_HAND), hidden(12, Zone.ZONE_LIBRARY)));
        String b = ForgeSearchGame.infoKey(request(seen(1, Zone.ZONE_HAND, "bolt"), hidden(12, Zone.ZONE_HAND),
            hidden(10, Zone.ZONE_HAND), hidden(11, Zone.ZONE_LIBRARY)));
        assertEquals(a, b);
    }

    @Test
    void whatTheViewerSeesStillCounts() {
        String a = ForgeSearchGame.infoKey(request(seen(1, Zone.ZONE_HAND, "bolt"), hidden(10, Zone.ZONE_HAND)));
        String b = ForgeSearchGame.infoKey(request(seen(1, Zone.ZONE_BATTLEFIELD, "bolt"), hidden(10, Zone.ZONE_HAND)));
        String c = ForgeSearchGame.infoKey(request(seen(1, Zone.ZONE_HAND, "shock"), hidden(10, Zone.ZONE_HAND)));
        assertNotEquals(a, b);
        assertNotEquals(a, c);
    }
}
