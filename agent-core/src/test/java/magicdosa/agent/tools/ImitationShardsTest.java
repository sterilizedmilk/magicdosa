package magicdosa.agent.tools;

import magicdosa.protocol.v1.Candidate;
import magicdosa.protocol.v1.Decider;
import magicdosa.protocol.v1.DecisionRequest;
import magicdosa.protocol.v1.DecisionResponse;
import magicdosa.protocol.v1.DecisionStep;
import magicdosa.protocol.v1.PassCandidate;
import magicdosa.protocol.v1.SelectionConstraints;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The converter rebuilds the steps of an answer recorded as Forge AI's from its chosen candidates. */
class ImitationShardsTest {

    private static DecisionRequest request(int candidates, int min, int max) {
        DecisionRequest.Builder q = DecisionRequest.newBuilder().setDecisionId(7)
            .setConstraints(SelectionConstraints.newBuilder().setMin(min).setMax(max));
        for (int i = 0; i < candidates; i++) {
            q.addCandidates(Candidate.newBuilder().setPass(PassCandidate.getDefaultInstance()));
        }
        return q.build();
    }

    @Test
    void aMultiSelectBecomesOneStepPerPickThenDone() {
        DecisionResponse r = DecisionResponse.newBuilder().addChosen(2).addChosen(0)
            .addSteps(DecisionStep.newBuilder().setDecider(Decider.DECIDER_INTENDED_DELEGATION)).build();
        assertTrue(ImitationShards.needsSteps(r));
        DecisionResponse s = ImitationShards.withSteps(request(3, 0, 3), r, Decider.DECIDER_INTENDED_DELEGATION);
        assertEquals(3, s.getStepsCount());
        // step 1: candidates 0, 1, 2 and done; candidate 2 chosen
        assertEquals(4, s.getSteps(0).getCandidatesCount());
        assertEquals(2, s.getSteps(0).getCandidates(s.getSteps(0).getChosen()).getIndex());
        // step 2: candidates 0, 1 and done; candidate 0 chosen
        assertEquals(0, s.getSteps(1).getCandidates(s.getSteps(1).getChosen()).getIndex());
        // step 3: done
        assertTrue(s.getSteps(2).getCandidates(s.getSteps(2).getChosen()).getDone());
        assertEquals(java.util.List.of(2, 0), s.getChosenList());
        s.getStepsList().forEach(st -> assertEquals(Decider.DECIDER_INTENDED_DELEGATION, st.getDecider()));
        assertTrue(!ImitationShards.needsSteps(s));
    }

    @Test
    void aSinglePickHasOneStepWithoutDone() {
        DecisionResponse r = DecisionResponse.newBuilder().addChosen(1).build();
        DecisionResponse s = ImitationShards.withSteps(request(3, 1, 1), r, Decider.DECIDER_INTENDED_DELEGATION);
        assertEquals(1, s.getStepsCount());
        assertEquals(3, s.getSteps(0).getCandidatesCount());
        assertEquals(1, s.getSteps(0).getChosen());
    }
}
