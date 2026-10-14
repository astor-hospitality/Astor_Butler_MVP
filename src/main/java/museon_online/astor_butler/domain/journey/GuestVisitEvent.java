package museon_online.astor_butler.domain.journey;

import java.time.Instant;

/** One event of a visit; mirroredAt is null until the event reached the Saby CRM mirror. */
public record GuestVisitEvent(
        Long id,
        Long visitId,
        VisitEventKind kind,
        JourneyAxis axis,
        String source,
        String note,
        Instant occurredAt,
        Instant mirroredAt
) {
}
