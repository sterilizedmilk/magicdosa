package magicdosa.agent.model;

import magicdosa.agent.AgentCore;
import magicdosa.agent.encoder.Encoder;
import magicdosa.agent.encoder.Vocab;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Version checks of a model's metadata (no model file needed). */
class OnnxEvaluatorTest {

    private static Map<String, String> fitting(Vocab vocab) {
        Map<String, String> m = new HashMap<>();
        m.put("model_version", String.valueOf(AgentCore.MODEL_VERSION));
        m.put("encoder_version", String.valueOf(AgentCore.ENCODER_VERSION));
        m.put("vocab_version", String.valueOf(vocab.version()));
        m.put("inputs", String.join(",", OnnxEvaluator.INPUTS));
        m.put("protocol_version", "13"); // not checked: the protocol does not shape the network's inputs
        m.put("global_features", String.join(",", Encoder.GLOBAL));
        return m;
    }

    @Test
    void acceptsItsOwnVersions() throws Exception {
        Vocab vocab = Vocab.load();
        assertDoesNotThrow(() -> OnnxEvaluator.check(fitting(vocab), vocab));
    }

    @Test
    void refusesOtherVersionsOrFeatures() throws Exception {
        Vocab vocab = Vocab.load();
        for (String key : new String[] {"model_version", "encoder_version", "vocab_version", "inputs", "global_features"}) {
            Map<String, String> m = fitting(vocab);
            m.put(key, m.get(key) + "0");
            OnnxEvaluator.ModelMismatch e = assertThrows(OnnxEvaluator.ModelMismatch.class, () -> OnnxEvaluator.check(m, vocab));
            assertTrue(e.getMessage().contains(key), e.getMessage());
        }
        Map<String, String> none = fitting(vocab);
        none.remove("model_version");
        assertThrows(OnnxEvaluator.ModelMismatch.class, () -> OnnxEvaluator.check(none, vocab));
    }

    @Test
    void softmaxOverTheStep() {
        float[] p = OnnxEvaluator.softmax(new float[] {0f, 0f, -1e9f});
        assertArrayEquals(new float[] {0.5f, 0.5f, 0f}, p, 1e-6f);
        assertEquals(1f, OnnxEvaluator.softmax(new float[] {3f})[0], 1e-6f);
    }
}
