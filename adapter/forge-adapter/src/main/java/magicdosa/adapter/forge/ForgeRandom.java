package magicdosa.adapter.forge;

import forge.util.MyRandom;

import java.util.Random;

/**
 * Seeds Forge's global random per match thread (CLAUDE.md 재현: "스레드별 난수는 InheritableThreadLocal").
 *
 * {@link MyRandom} is one static Random shared by every game in the JVM. This replaces it with a Random that draws
 * from the calling thread's own stream; inheritable because Forge AI evaluates on threads it spawns per decision.
 * Sequential runs with the same seed then replay the same games (parallel runs are not required to).
 */
public final class ForgeRandom {
    private static final InheritableThreadLocal<Random> CURRENT = new InheritableThreadLocal<>() {
        @Override
        protected Random initialValue() {
            return new Random();
        }
    };

    private static final class PerThread extends Random {
        PerThread() {
            super(0);
        }

        @Override
        protected int next(int bits) {
            return CURRENT.get().nextInt() >>> (32 - bits);
        }

        @Override
        public int nextInt() {
            return CURRENT.get().nextInt();
        }

        @Override
        public void setSeed(long seed) {
            // seeds are set per thread through seed()
        }
    }

    private static volatile boolean installed;

    private ForgeRandom() {}

    /** Installs the per-thread Random into Forge (once per JVM). */
    public static synchronized void install() {
        if (!installed) {
            MyRandom.setRandom(new PerThread());
            installed = true;
        }
    }

    /** Seeds the calling thread's stream (and the threads it spawns from now on). */
    public static void seed(long seed) {
        CURRENT.set(new Random(seed));
    }
}
