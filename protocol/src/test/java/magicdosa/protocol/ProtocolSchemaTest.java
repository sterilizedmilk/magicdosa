package magicdosa.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import magicdosa.protocol.v1.AdapterMessage;
import magicdosa.protocol.v1.AgentMessage;
import magicdosa.protocol.v1.CardFaceKey;
import magicdosa.protocol.v1.CastSpell;
import magicdosa.protocol.v1.CastSpellCandidate;
import magicdosa.protocol.v1.Candidate;
import magicdosa.protocol.v1.DecisionRequest;
import magicdosa.protocol.v1.DecisionResponse;
import magicdosa.protocol.v1.DecisionStep;
import magicdosa.protocol.v1.DecisionType;
import magicdosa.protocol.v1.Decider;
import magicdosa.protocol.v1.Event;
import magicdosa.protocol.v1.GameEndReason;
import magicdosa.protocol.v1.GameGlobal;
import magicdosa.protocol.v1.GameObject;
import magicdosa.protocol.v1.GameOver;
import magicdosa.protocol.v1.LibraryOrder;
import magicdosa.protocol.v1.LogRecord;
import magicdosa.protocol.v1.ManaAbilityUse;
import magicdosa.protocol.v1.ManaPayment;
import magicdosa.protocol.v1.ManaUnit;
import magicdosa.protocol.v1.Method;
import magicdosa.protocol.v1.ObjectRef;
import magicdosa.protocol.v1.PassCandidate;
import magicdosa.protocol.v1.PlayerState;
import magicdosa.protocol.v1.Prior;
import magicdosa.protocol.v1.Privileged;
import magicdosa.protocol.v1.PrivilegedRecord;
import magicdosa.protocol.v1.SearchRun;
import magicdosa.protocol.v1.SelectionConstraints;
import magicdosa.protocol.v1.SpellKey;
import magicdosa.protocol.v1.StepCandidate;
import magicdosa.protocol.v1.Step;
import magicdosa.protocol.v1.Zone;
import org.junit.jupiter.api.Test;

/** Schema checks and binary / JSON round trips for proto/magicdosa/v1. */
class ProtocolSchemaTest {
    private static final String BOLT = "4457ed35-7c10-48c8-9776-456485fdf070";
    private static final String MOUNTAIN = "a3fb7228-e76b-4e96-a40e-20b5fed75685";

    /** Every file of the package, reached through LogRecord (log.proto imports the rest, session.proto imports handshake). */
    private static Set<FileDescriptor> allFiles() {
        Set<FileDescriptor> files = new LinkedHashSet<>();
        List<FileDescriptor> todo = new ArrayList<>(List.of(LogRecord.getDescriptor().getFile()));
        while (!todo.isEmpty()) {
            FileDescriptor f = todo.remove(todo.size() - 1);
            if (files.add(f)) {
                todo.addAll(f.getDependencies());
            }
        }
        return files;
    }

    private static List<Descriptor> allMessages() {
        List<Descriptor> out = new ArrayList<>();
        List<Descriptor> todo = new ArrayList<>();
        allFiles().forEach(f -> todo.addAll(f.getMessageTypes()));
        while (!todo.isEmpty()) {
            Descriptor d = todo.remove(todo.size() - 1);
            out.add(d);
            todo.addAll(d.getNestedTypes());
        }
        return out;
    }

    @Test
    void allProtoFilesAreReachable() {
        Set<String> names = new LinkedHashSet<>();
        allFiles().forEach(f -> names.add(f.getName()));
        for (String file : List.of("common", "observation", "events", "snapshot", "decision", "session", "log", "handshake")) {
            assertTrue(names.contains("magicdosa/v1/" + file + ".proto"), file + " not reachable: " + names);
        }
    }

    /** Q4: entity IDs are per-game uint64 counters; only game, match and card identifiers are strings. */
    @Test
    void entityIdFieldsAreUint64() {
        List<String> wrong = new ArrayList<>();
        for (Descriptor d : allMessages()) {
            for (FieldDescriptor f : d.getFields()) {
                String n = f.getName();
                boolean idLike = n.equals("id") || n.endsWith("_id") || n.endsWith("_ids");
                boolean stringId = n.equals("game_id") || n.equals("match_id") || n.endsWith("oracle_id");
                if (idLike && !stringId && f.getType() != FieldDescriptor.Type.UINT64) {
                    wrong.add(d.getFullName() + "." + n + " is " + f.getType());
                }
            }
        }
        assertEquals(List.of(), wrong);
    }

    /** proto3 enums used in JSON need an explicit zero value. */
    @Test
    void enumsHaveZeroValue() {
        allFiles().forEach(f -> f.getEnumTypes().forEach(e -> assertEquals(0, e.getValues().get(0).getNumber(), e.getFullName())));
    }

    @Test
    void decisionRoundTripsInBinaryAndJson() throws Exception {
        DecisionRequest request = sampleRequest();
        DecisionResponse response = sampleResponse();
        assertRoundTrips(AdapterMessage.newBuilder().setDecisionRequest(request).build());
        assertRoundTrips(AgentMessage.newBuilder().setDecisionResponse(response).build());
        assertRoundTrips(AdapterMessage.newBuilder().setEvent(sampleCastEvent()).build());
        assertRoundTrips(AdapterMessage.newBuilder().setGameOver(sampleGameOver()).build());
        assertRoundTrips(LogRecord.newBuilder().setGameOver(sampleGameOver()).build());
    }

    /** An external engine writes JSON by hand: field names are lowerCamelCase, uint64 IDs may be strings or numbers. */
    @Test
    void parsesHandWrittenJsonEvent() throws Exception {
        String json = """
            {
              "event": {
                "gameId": "g1", "viewerSeat": 0, "seq": "42", "turn": 3, "step": "STEP_MAIN1",
                "castSpell": {
                  "seat": 0, "cardId": 17, "spellId": "58",
                  "spell": {"card": {"oracleId": "%s", "face": 1}},
                  "targets": [{"playerSeat": 1}],
                  "payment": [{"ability": {"sourceId": 9, "produced": [{"color": "R", "amount": 1}]}}]
                }
              }
            }""".formatted(BOLT);
        AdapterMessage.Builder b = AdapterMessage.newBuilder();
        JsonFormat.parser().merge(json, b);
        Event e = b.getEvent();
        assertEquals(42, e.getSeq());
        assertEquals(Event.BodyCase.CAST_SPELL, e.getBodyCase());
        assertEquals(58, e.getCastSpell().getSpellId());
        assertEquals(BOLT, e.getCastSpell().getSpell().getCard().getOracleId());
        assertEquals(ObjectRef.RefCase.PLAYER_SEAT, e.getCastSpell().getTargets(0).getRefCase());
        assertEquals(9, e.getCastSpell().getPayment(0).getAbility().getSourceId());
        assertEquals(e, sampleCastEvent().toBuilder().clearEngineHandled().build());
    }

    private static void assertRoundTrips(Message m) throws InvalidProtocolBufferException {
        assertEquals(m, m.getParserForType().parseFrom(m.toByteArray()), "binary");
        Message.Builder b = m.newBuilderForType();
        JsonFormat.parser().merge(JsonFormat.printer().print(m), b);
        assertEquals(m, b.build(), "json");
    }

    // ---- samples: seat 0 casts Lightning Bolt at seat 1, paying with a Mountain ----

    private static DecisionRequest sampleRequest() {
        GameObject bolt = GameObject.newBuilder().setId(17).setZone(Zone.ZONE_HAND).setOracleId(BOLT).setFace(1).build();
        GameObject mountain = GameObject.newBuilder().setId(9).setZone(Zone.ZONE_BATTLEFIELD).setOracleId(MOUNTAIN).setFace(1).build();
        GameObject hidden = GameObject.newBuilder().setId(30).setZone(Zone.ZONE_HAND).setOwnerSeat(1).setControllerSeat(1).build();
        return DecisionRequest.newBuilder()
            .setProtocolVersion(Versions.PROTOCOL)
            .setGameId("g1").setDecisionId(7).setSeat(0).setAppliedSeq(41).setSeed(123456789L)
            .setType(DecisionType.DECISION_TYPE_PRIORITY)
            .setObservation(magicdosa.protocol.v1.Observation.newBuilder()
                .setViewerSeat(0)
                .setGame(GameGlobal.newBuilder().setTurn(3).setStep(Step.STEP_MAIN1).setActiveSeat(0).setPrioritySeat(0))
                .addPlayers(PlayerState.newBuilder().setSeat(0).setLife(20).setHandSize(1))
                .addPlayers(PlayerState.newBuilder().setSeat(1).setLife(14).setHandSize(1))
                .addObjects(bolt).addObjects(mountain).addObjects(hidden))
            .addCandidates(Candidate.newBuilder().setPass(PassCandidate.getDefaultInstance()))
            .addCandidates(Candidate.newBuilder().setCastSpell(CastSpellCandidate.newBuilder()
                .setCardId(17).setSpell(SpellKey.newBuilder().setCard(CardFaceKey.newBuilder().setOracleId(BOLT).setFace(1)))))
            .setConstraints(SelectionConstraints.newBuilder().setMin(1).setMax(1))
            .build();
    }

    private static DecisionResponse sampleResponse() {
        return DecisionResponse.newBuilder()
            .setGameId("g1").setDecisionId(7).addChosen(1)
            .addSteps(DecisionStep.newBuilder()
                .addCandidates(StepCandidate.newBuilder().setIndex(0))
                .addCandidates(StepCandidate.newBuilder().setIndex(1))
                .setChosen(1).addVisitRatio(0.25f).addVisitRatio(0.75f)
                .setDecider(Decider.DECIDER_MODEL)
                .setMethod(Method.newBuilder().setPrior(Prior.PRIOR_UNIFORM).setRan(SearchRun.newBuilder().setNodes(800).setDeterminizations(8))))
            .build();
    }

    private static Event sampleCastEvent() {
        return Event.newBuilder()
            .setGameId("g1").setViewerSeat(0).setSeq(42).setTurn(3).setStep(Step.STEP_MAIN1)
            .setCastSpell(CastSpell.newBuilder()
                .setSeat(0).setCardId(17).setSpellId(58)
                .setSpell(SpellKey.newBuilder().setCard(CardFaceKey.newBuilder().setOracleId(BOLT).setFace(1)))
                .addTargets(ObjectRef.newBuilder().setPlayerSeat(1))
                .addPayment(ManaPayment.newBuilder().setAbility(ManaAbilityUse.newBuilder()
                    .setSourceId(9).addProduced(ManaUnit.newBuilder().setColor("R").setAmount(1)))))
            .build();
    }

    private static GameOver sampleGameOver() {
        Privileged hidden = Privileged.newBuilder()
            .addLibraries(LibraryOrder.newBuilder().setSeat(1).addIds(31).addIds(32))
            .build();
        return GameOver.newBuilder()
            .setGameId("g1").setWinnerSeat(0).setReason(GameEndReason.GAME_END_REASON_LIFE).setTurns(5)
            .addPrivileged(PrivilegedRecord.newBuilder().setSeq(41).setSeat(0).setDecisionId(7).setPrivileged(hidden))
            .build();
    }

}
