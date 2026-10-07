package museon_online.astor_butler.domain.billing;

import java.time.Instant;

/** The guest's word after the visit, one per bill: stars, a thumb, and whether tips were asked for. */
public record VisitReview(
        Long id,
        Long billId,
        Long chatId,
        Instant promptedAt,
        Integer stars,
        Thumb thumb,
        Instant tipRequestedAt,
        Instant ratedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public enum Thumb {
        UP,
        DOWN
    }
}
