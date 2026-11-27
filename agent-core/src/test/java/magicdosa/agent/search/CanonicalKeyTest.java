package magicdosa.agent.search;

import magicdosa.protocol.v1.Candidate;
import magicdosa.protocol.v1.CastSpellCandidate;
import magicdosa.protocol.v1.DecisionRequest;
import magicdosa.protocol.v1.GameObject;
import magicdosa.protocol.v1.Observation;
import magicdosa.protocol.v1.PlayerState;
import magicdosa.protocol.v1.Zone;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Tree reuse matches the real game's request with a search copy's: the same information set numbered by another
 * session (the real one counts IDs over the whole game, a copy's from 1 at the root) gives the same key and the same
 * candidate names; another information set does not; what only the real session knows (mulligans, known library
 * cards) is left out.
 */
class CanonicalKeyTest {

    private static GameObject card(long id, Zone zone, String oracle) {
        return GameObject.newBuilder().setId(id).setZone(zone).setOwnerSeat(0).setOracleId(oracle).setFace(1).build();
    }

    private static DecisionRequest request(int mulligans, long bolt, long spear, long mountain, GameObject... extra) {
        Observation.Builder o = Observation.newBuilder()
            .addPlayers(PlayerState.newBuilder().setLife(20).setMulligans(mulligans))
            .addPlayers(PlayerState.newBuilder().setLife(17))
            .addObjects(card(bolt, Zone.ZONE_HAND, "bolt"))
            .addObjects(card(spear, Zone.ZONE_BATTLEFIELD, "spear"))
            .addObjects(card(mountain, Zone.ZONE_BATTLEFIELD, "mountain"));
        for (GameObject g : extra) {
            o.addObjects(g);
        }
        return DecisionRequest.newBuilder().setSeat(0).setSeed(7).setDecisionId(mulligans + 40).setObservation(o)
            .addCandidates(Candidate.newBuilder().setCastSpell(CastSpellCandidate.newBuilder().setCardId(bolt)))
            .addCandidates(Candidate.newBuilder().setPass(magicdosa.protocol.v1.PassCandidate.getDefaultInstance()))
            .build();
    }

    @Test
    void anotherSessionsNumberingGivesTheSameKeyAndNames() {
        CanonicalKey.Canon real = CanonicalKey.of(request(0, 512, 90, 300));
        CanonicalKey.Canon copy = CanonicalKey.of(request(0, 3, 17, 5));
        assertEquals(real.key(), copy.key());
        assertEquals(real.candidates(), copy.candidates());
    }

    @Test
    void anotherInformationSetGivesAnotherKey() {
        DecisionRequest a = request(0, 3, 17, 5);
        DecisionRequest b = a.toBuilder().setObservation(a.getObservation().toBuilder()
            .setPlayers(1, PlayerState.newBuilder().setLife(14))).build();
        assertNotEquals(CanonicalKey.of(a).key(), CanonicalKey.of(b).key());
    }

    @Test
    void mulligansAndKnownLibraryCardsAreLeftOut() {
        CanonicalKey.Canon real = CanonicalKey.of(request(2, 512, 90, 300, card(77, Zone.ZONE_LIBRARY, "bolt")));
        CanonicalKey.Canon copy = CanonicalKey.of(request(0, 3, 17, 5));
        assertEquals(real.key(), copy.key());
        assertEquals(real.candidates(), copy.candidates());
    }
}
