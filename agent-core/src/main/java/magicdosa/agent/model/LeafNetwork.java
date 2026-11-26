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
    /** The loaded model's file name and the first 16 hex digits of its SHA-256 (recorded in each SearchRun). */
    private static String fileName = "";
    private static String sha256 = "";

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
                    fileName = Path.of(path).getFileName().toString();
                    sha256 = sha256(Path.of(path)).substring(0, 16);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return loaded;
    }

    /** The loaded model's file name, empty when none is loaded. */
    public static synchronized String fileName() {
        return fileName;
    }

    /** The first 16 hex digits of the loaded model's SHA-256, empty when none is loaded. */
    public static synchronized String sha256() {
        return sha256;
    }

    private static String sha256(Path file) throws IOException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(md.digest(java.nio.file.Files.readAllBytes(file)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
