package magicdosa.agent.encoder;

import magicdosa.protocol.v1.Candidate;
import magicdosa.protocol.v1.CardCount;
import magicdosa.protocol.v1.DecisionRequest;
import magicdosa.protocol.v1.DecisionType;
import magicdosa.protocol.v1.GameGlobal;
import magicdosa.protocol.v1.GameObject;
import magicdosa.protocol.v1.LibraryPosition;
import magicdosa.protocol.v1.MatchContext;
import magicdosa.protocol.v1.MatchPlayer;
import magicdosa.protocol.v1.Observation;
import magicdosa.protocol.v1.PlayerState;
import magicdosa.protocol.v1.SelectionConstraints;
import magicdosa.protocol.v1.Zone;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E3: unknown library cards are not tokens; the remaining compositions and known library positions are encoded. */
class EncoderTest {
    private static Vocab vocab() throws Exception {
        String v = "#version 1\ncard\tbolt\ncard\tmountain\ncard\tpush\n";
        return Vocab.read(new ByteArrayInputStream(v.getBytes(StandardCharsets.UTF_8)));
    }

    private static CardCount count(String oracle, int n) {
        return CardCount.newBuilder().setOracleId(oracle).setCount(n).build();
    }

    /** Me (seat 0): 4 bolt + 2 mountain; one bolt in hand, one bolt scried to the top, one mountain unknown in library. */
    private static DecisionRequest request(int gameNumber) {
        Observation o = Observation.newBuilder().setViewerSeat(0)
            .setMatch(MatchContext.newBuilder().setGameNumber(gameNumber)
                .addPlayers(MatchPlayer.newBuilder().setSeat(0).addCurrentDeck(count("bolt", 4)).addCurrentDeck(count("mountain", 2)))
                .addPlayers(MatchPlayer.newBuilder().setSeat(1).addMain(count("push", 3))))
            .setGame(GameGlobal.newBuilder().setTurn(3).setActiveSeat(0).setPrioritySeat(0))
            .addPlayers(PlayerState.newBuilder().setSeat(0).setLife(20))
            .addPlayers(PlayerState.newBuilder().setSeat(1).setLife(18))
            .addObjects(GameObject.newBuilder().setId(10).setZone(Zone.ZONE_HAND).setOracleId("bolt").setFace(1))
            .addObjects(GameObject.newBuilder().setId(11).setZone(Zone.ZONE_LIBRARY).setOracleId("bolt").setFace(1)
                .setLibraryPosition(LibraryPosition.newBuilder().setFromTop(0)))
            .addObjects(GameObject.newBuilder().setId(12).setZone(Zone.ZONE_LIBRARY))   // unknown
            .addObjects(GameObject.newBuilder().setId(20).setZone(Zone.ZONE_BATTLEFIELD).setOwnerSeat(1).setControllerSeat(1)
                .setOracleId("push"))
            .build();
        return DecisionRequest.newBuilder().setSeat(0).setType(DecisionType.DECISION_TYPE_PRIORITY).setObservation(o)
            .addCandidates(Candidate.newBuilder().setCardId(10))
            .setConstraints(SelectionConstraints.newBuilder().setMin(1).setMax(1)).build();
    }

    private static Map<Integer, Integer> group(Encoder.Encoded e, Vocab v, int group, String card) {
        Map<Integer, Integer> out = new HashMap<>();
        for (int i = 0; i < e.deckGroup().length; i++) {
            if (e.deckGroup()[i] == group) {
                out.put(e.deckCard()[i], e.deckCount()[i]);
            }
        }
        return out;
    }

    @Test
    void unknownLibraryCardsAreNotTokens() throws Exception {
        Vocab v = vocab();
        Encoder.Encoded e = new Encoder(v).encode(request(1));
        assertFalse(e.tokenOfEntity().containsKey(12L));
        assertTrue(e.tokenOfEntity().containsKey(11L));
        int known = e.tokenOfEntity().get(11L);
        int width = Encoder.TOKEN.size();
        assertEquals(1f, e.tokenFeat()[known * width + Encoder.tokenColumn("lib_known")]);
        assertEquals(0f, e.tokenFeat()[known * width + Encoder.tokenColumn("lib_from_top")]);
        assertEquals(v.card("bolt"), e.tokenCard()[known]);
        // the candidate points at the hand card's token
        assertEquals(e.tokenOfEntity().get(10L), e.candRef0()[0]);
    }

    @Test
    void remainingCompositions() throws Exception {
        Vocab v = vocab();
        Encoder.Encoded e = new Encoder(v).encode(request(1));
        // mine: 4 bolt + 2 mountain, minus the bolt in hand and the known bolt on top
        Map<Integer, Integer> mine = group(e, v, Encoder.MY_REMAINING, null);
        assertEquals(2, mine.get(v.card("bolt")));
        assertEquals(2, mine.get(v.card("mountain")));
        // the opponent's 60 is known in game 1: 3 push minus the one on the battlefield
        assertEquals(2, group(e, v, Encoder.OPP_REMAINING, null).get(v.card("push")));
        // game 2: the opponent may have sideboarded, so no remaining composition
        assertTrue(group(new Encoder(v).encode(request(2)), v, Encoder.OPP_REMAINING, null).isEmpty());
        assertEquals(0, v.oovCount());
    }
}
