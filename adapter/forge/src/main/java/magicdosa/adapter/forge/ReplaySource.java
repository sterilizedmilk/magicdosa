package magicdosa.adapter.forge;

import magicdosa.protocol.v1.Event;

import java.util.List;

/**
 * What a replayed seat did in the real game, as its events show it (shadow game, phase 2): its actions (priority
 * passes, casts, activations, land plays, attacks, blocks, trigger order) and its public choices
 * ({@code ResolutionChoice}), in stream order. Live, {@link #peek} waits until the next one has arrived.
 */
public interface ReplaySource {
    /** The seat's next recorded action or choice, not consumed; null when the record has no more. */
    Event peek(int seat);

    /** Consumes what {@link #peek} returned. */
    void consume(int seat);

    /**
     * The seat's next recorded choice made by {@code method}, consumed; null if there is none before the seat's next
     * action. A choice Forge asks while answering another (paying a cost inside "pay or counter") is recorded before
     * the outer one, so choices are found by method, not taken in strict order.
     */
    Event takeChoice(int seat, String method);

    /**
     * The seat's library as it really was right after the event {@code seq} (top first, entity IDs), when the source
     * knows hidden cards (verification 1: from the privileged records); empty otherwise.
     */
    default List<Long> libraryAfter(int seat, long seq) {
        return List.of();
    }

    /**
     * Whether a choice the record does not hold means "the seat chose nothing" where nothing may be chosen: true where
     * the replayed seat's hidden cards are a sample (verification 2), so Forge AI's own answer would weigh the sample
     * (it may see a card the real game did not have, as in "look at the top five, you may take a historic card").
     */
    default boolean noRecordMeansNone() {
        return false;
    }

    /** The shadow game asked something the record does not answer, or the record holds something it did not ask. */
    void mismatch(int seat, String what);
}
