package museon_online.astor_butler.domain.tips;

import java.time.Instant;
import java.time.LocalDate;

/** Who serves a table on a shift day: one row per tenant, table and day, the latest assignment wins. */
public record TableWaiterAssignment(
        String tenant,
        String tableCode,
        LocalDate shiftDate,
        String staffId,
        String displayName,
        String assignedBy,
        Instant assignedAt
) {
}
