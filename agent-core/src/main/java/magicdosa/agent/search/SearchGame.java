package magicdosa.agent.search;

import java.util.List;
import java.util.SplittableRandom;

/**
 * What {@link IsMcts} searches: the root decision and a way to play one simulation from it in a fresh
 * determinization (the hidden cards sampled anew, SO-ISMCTS: one tree, a new sample every simulation).
 *
 * <p>A simulation is driven by the game, not by the search: the engine asks for each decision deep in its own call
 * stack (Forge's controller calls), so the search answers through a {@link Chooser} (the tree-lookup controller of the
 * design, 그림자·탐색 탭 "탐색 분기 단위"). Out of the tree the chooser answers {@link Chooser#ROLLOUT} and the game
 * answers with its rollout policy.
 */
public interface SearchGame {

    /** The seat the search decides for. */
    int rootSeat();

    /** Keys of the root decision's candidates, in candidate order (the real request's candidates). */
    List<String> rootActions();

    /**
     * Prepares one simulation in a new determinization drawn with {@code rng}; null when no determinization fits
     * (counted, not searched). Must be a pure function of the game state and {@code rng}.
     */
    Simulation determinize(SplittableRandom rng);

    /** One determinized copy, played once. */
    interface Simulation {
        /**
         * Plays from the root decision (asked first, of {@link #rootSeat()}, with {@link #rootActions()}) until the game
         * ends or the rollout horizon, asking {@code chooser} at every decision of either seat; returns the value for the
         * root seat in [0, 1] (1 a win).
         */
        double play(Chooser chooser);
    }

    /**
     * One decision met in a simulation. {@code infoKey} identifies the deciding seat's information set (what that seat
     * can see: public state plus its own hidden cards), so two determinizations that the seat cannot tell apart reach
     * the same tree node; {@code actions} are its legal actions as keys stable across determinizations.
     */
    record Decision(int seat, String infoKey, List<String> actions) {
    }

    /** Answers the decisions of a simulation. */
    interface Chooser {
        /** Answer for "let the rollout policy decide". */
        int ROLLOUT = -1;

        /** An index into {@code d.actions()}, or {@link #ROLLOUT}. */
        int choose(Decision d);
    }
}
