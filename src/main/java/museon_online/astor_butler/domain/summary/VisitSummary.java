package museon_online.astor_butler.domain.summary;

import java.time.Instant;
import java.util.List;

/**
 * What a finished visit came to: the guest's wish, the venue's offer, the outcome, where the talk got stuck, notes about
 * the guest for next time and lessons for the agents ({@code CONCIERGE: …} / {@code BUTLER: …}).
 */
public record VisitSummary(long visitId, String guestWanted, String venueOffered, String outcome, List<String> stuck,
                           List<String> nextTimeGuest, List<String> nextTimeAgents, Instant createdAt) {

    public VisitSummary {
        stuck = stuck == null ? List.of() : List.copyOf(stuck);
        nextTimeGuest = nextTimeGuest == null ? List.of() : List.copyOf(nextTimeGuest);
        nextTimeAgents = nextTimeAgents == null ? List.of() : List.copyOf(nextTimeAgents);
    }

    /** A summary with nothing in it yet, for a manager's note on a visit the model has not summarized. */
    public static VisitSummary empty(long visitId) {
        return new VisitSummary(visitId, null, null, null, List.of(), List.of(), List.of(), null);
    }
}
