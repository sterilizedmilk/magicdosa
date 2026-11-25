package magicdosa.agent.model;

import magicdosa.agent.encoder.Encoder;
import magicdosa.agent.encoder.Vocab;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * The search's optional leaf network ({@code -Dmagicdosa.search.leafModel=<model.onnx>}), loaded once per JVM. Unset (the
 * default): searches play their simulations out. The phase-3 gate runs without it (2026-11-26).
 */
public final class LeafNetwork {
    private static Evaluator loaded;
    private static boolean tried;

    private LeafNetwork() {
    }

    /** The network named by the property, or null when none is set. A model that does not fit is refused (thrown). */
    public static synchronized Evaluator get() {
        if (!tried) {
            tried = true;
            String path = System.getProperty("magicdosa.search.leafModel");
            if (path != null && !path.isEmpty()) {
                try {
                    loaded = new OnnxEvaluator(Path.of(path), new Encoder(Vocab.load()));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return loaded;
    }
}
