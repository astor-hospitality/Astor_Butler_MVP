package museon_online.astor_butler.domain.billing;

import java.time.Instant;

/** One journal line. Lines are only ever added; {@code details} carries no guest data. */
public record BillOperation(
        Long id,
        Long billId,
        BillOperationType type,
        boolean ok,
        BillActor actor,
        String errorCode,
        String details,
        Instant occurredAt
) {
}
