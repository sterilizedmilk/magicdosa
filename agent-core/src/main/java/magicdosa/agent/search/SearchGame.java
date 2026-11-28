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
     * Gumbel search: the network's value of the root decision for the root seat, NaN when no network values this game's
     * leaves (the root's visit-weighted mean then stands in, as in targets.py).
     */
    default double rootValue() {
        return Double.NaN;
    }

    /** Whether a simulation's value is the network's value of the node its walk expanded (a network leaf). */
    default boolean leafIsNetwork() {
        return false;
    }

    /** The game turn of the root decision (0 when unknown): early-turn sampling of the played move uses it. */
    default int rootTurn() {
        return 0;
    }

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
     * {@code continuation}: the next step of the decision just answered (a multi-select picks one candidate per step,
     * protocol steps[]); the root decision's steps are read back from the tree as the answer's steps.
     */
    record Decision(int seat, String infoKey, List<String> actions, boolean continuation) {
        public Decision(int seat, String infoKey, List<String> actions) {
            this(seat, infoKey, actions, false);
        }
    }

    /** Answers the decisions of a simulation. */
    interface Chooser {
        /** Answer for "let the rollout policy decide". */
        int ROLLOUT = -1;

        /** An index into {@code d.actions()}, or {@link #ROLLOUT}. */
        int choose(Decision d);

        /**
         * Whether every later decision of this simulation is answered {@link #ROLLOUT} whatever it is (the walk left the
         * tree): the game may skip what only a tree lookup reads (information keys, action keys, observations).
         */
        default boolean outOfTree() {
            return false;
        }
    }
}
