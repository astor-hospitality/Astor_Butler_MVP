package museon_online.astor_butler.domain.summary;

import java.time.Instant;

/** A lesson for one agent taken from a visit; it reaches the agent only once a manager accepts it. */
public record AgentLesson(long id, Long visitId, String side, String text, Status status, Instant createdAt, Instant decidedAt) {

    public enum Status { PROPOSED, ACCEPTED, REJECTED }

    /** The agents a lesson can be for. */
    public static boolean isSide(String side) {
        return "CONCIERGE".equals(side) || "BUTLER".equals(side);
    }
}
