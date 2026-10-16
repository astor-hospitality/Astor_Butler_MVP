package museon_online.astor_butler.domain.roster;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * The waiters' standing zones ({@code waiter_zones}, migration {@code 2026-10-16-waiter-zones}): which zone of the floor
 * a waiter usually works. Only active members of the directory count. Plain JDBC, like the tips directory next to it.
 */
@Repository
@RequiredArgsConstructor
public class WaiterZoneRepository {

    /** A waiter's standing zone, with the name the directory gives. */
    public record WaiterZone(String staffId, String displayName, String zone) {
    }

    private final JdbcTemplate jdbc;

    /** Adds the zone to the waiter's zones; false when it was there already. */
    public boolean add(String tenant, String staffId, String zone, String assignedBy) {
        try {
            return jdbc.update("""
                    INSERT INTO waiter_zones (tenant, staff_id, zone, assigned_by, assigned_at)
                    VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """,
                    tenant,
                    staffId,
                    zone,
                    blankToNull(assignedBy)
            ) > 0;
        } catch (DuplicateKeyException already) {
            return false;
        }
    }

    public boolean remove(String tenant, String staffId, String zone) {
        return jdbc.update("DELETE FROM waiter_zones WHERE tenant = ? AND staff_id = ? AND zone = ?", tenant, staffId, zone) > 0;
    }

    /** Takes the zone from everyone; the number of waiters it was taken from. */
    public int clearZone(String tenant, String zone) {
        return jdbc.update("DELETE FROM waiter_zones WHERE tenant = ? AND zone = ?", tenant, zone);
    }

    /** The active members whose standing zone it is. */
    public List<String> staffIn(String tenant, String zone) {
        return jdbc.queryForList("""
                SELECT z.staff_id
                FROM waiter_zones z
                JOIN astor_staff_members m ON m.tenant = z.tenant AND m.staff_id = z.staff_id
                WHERE z.tenant = ?
                  AND z.zone = ?
                  AND m.active = TRUE
                ORDER BY z.staff_id
                """,
                String.class,
                tenant,
                zone
        );
    }

    /** Every standing zone of the active members, by zone and name. */
    public List<WaiterZone> all(String tenant) {
        return jdbc.query("""
                SELECT z.staff_id, m.display_name, z.zone
                FROM waiter_zones z
                JOIN astor_staff_members m ON m.tenant = z.tenant AND m.staff_id = z.staff_id
                WHERE z.tenant = ?
                  AND m.active = TRUE
                ORDER BY z.zone, m.display_name, z.staff_id
                """,
                (rs, rowNum) -> new WaiterZone(rs.getString("staff_id"), rs.getString("display_name"), rs.getString("zone")),
                tenant
        );
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
