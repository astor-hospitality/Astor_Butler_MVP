package museon_online.astor_butler.domain.journey;

import java.time.Instant;

/** One guest visit = one reservation. */
public record GuestVisit(
        Long id,
        Long reservationId,
        Long chatId,
        String guestPhone,
        String venueCode,
        JourneyStage stage,
        String sabyLeadId,
        Long returnedVisitId,
        int guestMessages,
        Instant visitDay,
        Instant openedAt,
        Instant closedAt
) {
}
