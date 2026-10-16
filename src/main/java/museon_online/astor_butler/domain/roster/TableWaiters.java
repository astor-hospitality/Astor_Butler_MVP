package museon_online.astor_butler.domain.roster;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.tips.TableWaiterAssignment;
import museon_online.astor_butler.domain.tips.TipStaffMember;
import museon_online.astor_butler.domain.tips.TipsPersonalProperties;
import museon_online.astor_butler.domain.tips.TipsPersonalRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The waiter of a table for the day. The day's assignment ({@code /официант}, or an earlier pick of this class) wins;
 * when there is none, a table request picks the waiter on shift now ({@link StaffRoster}, role WAITER) whose standing
 * zone ({@code /зона}) holds the table — of several, the one with fewer tables that day — and writes the pick to
 * {@code table_waiter_assignments} with {@code assigned_by = 'auto'}, so the next request of the day goes to the same
 * person and the manager's {@code /официант} still overrides it. Nobody fits: the team, with the zone named.
 */
@Service
@Slf4j
public class TableWaiters {

    /** {@code assigned_by} of a pick made here. */
    public static final String AUTO = "auto";

    /**
     * Whom a table request goes to.
     *
     * @param name the waiter's name; null when the request goes to the team
     * @param zone the table's zone on the plan; null when the plan does not know the table
     * @param auto the waiter was picked by zone and shift just now (not by the manager, not earlier)
     */
    public record TableWaiter(String staffId, String name, String zone, boolean auto) {

        public static TableWaiter team(String zone) {
            return new TableWaiter(null, null, zone, false);
        }

        public boolean known() {
            return name != null && !name.isBlank();
        }
    }

    private final TipsPersonalRepository directory;
    private final TipsPersonalProperties tips;
    private final WaiterZoneRepository zones;
    private final FloorZones floor;
    private final StaffRoster roster;

    public TableWaiters(TipsPersonalRepository directory, TipsPersonalProperties tips, WaiterZoneRepository zones,
                        FloorZones floor, StaffRoster roster) {
        this.directory = directory;
        this.tips = tips;
        this.zones = zones;
        this.floor = floor;
        this.roster = roster;
    }

    /** The day's assignment only, nothing picked: for the seated hint, the tips and the return loop. */
    public Optional<TipStaffMember> assigned(String tableCode, LocalDate shiftDate) {
        if (tableCode == null || tableCode.isBlank()) {
            return Optional.empty();
        }
        return directory.waiterOfTable(tips.tenantOrDefault(), tableCode, shiftDate);
    }

    /** The waiter a table request goes to; picks and keeps one by zone and shift when nobody is assigned. Never throws. */
    public TableWaiter forRequest(String tableCode, LocalDate shiftDate) {
        if (tableCode == null || tableCode.isBlank()) {
            return TableWaiter.team(null);
        }
        String tenant = tips.tenantOrDefault();
        String zone = null;
        try {
            Optional<TipStaffMember> assigned = directory.waiterOfTable(tenant, tableCode, shiftDate);
            if (assigned.isPresent()) {
                return new TableWaiter(assigned.get().staffId(), assigned.get().displayName(), floor.zoneOfTable(tableCode).orElse(null), false);
            }
            zone = floor.zoneOfTable(tableCode).orElse(null);
            if (zone == null) {
                return TableWaiter.team(null);
            }
            Optional<StaffRoster.OnShift> pick = pick(tenant, zone, shiftDate);
            if (pick.isEmpty()) {
                return TableWaiter.team(zone);
            }
            if (directory.assignIfFree(tenant, tableCode, shiftDate, pick.get().staffId(), AUTO)) {
                log.info("AUDIT Официант {} назначен на стол {} ({}) автоматически: зона {}, на смене",
                        pick.get().staffId(), tableCode, shiftDate, zone);
                return new TableWaiter(pick.get().staffId(), pick.get().displayName(), zone, true);
            }
            // Someone assigned the table meanwhile (the manager, or a parallel request): theirs stands.
            String inZone = zone;
            return directory.waiterOfTable(tenant, tableCode, shiftDate)
                    .map(member -> new TableWaiter(member.staffId(), member.displayName(), inZone, false))
                    .orElse(TableWaiter.team(zone));
        } catch (RuntimeException e) {
            log.warn("Table waiter not resolved, the request goes to the team: table={}, reason={}", tableCode, e.toString());
            return TableWaiter.team(zone);
        }
    }

    /** On shift now, a waiter, the zone among their standing zones; fewer tables of the day first, then by name. */
    private Optional<StaffRoster.OnShift> pick(String tenant, String zone, LocalDate shiftDate) {
        Set<String> standing = new HashSet<>(zones.staffIn(tenant, zone));
        if (standing.isEmpty()) {
            return Optional.empty();
        }
        List<StaffRoster.OnShift> candidates = roster.onShiftNow(StaffRole.WAITER).stream()
                .filter(shift -> standing.contains(shift.staffId()))
                .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Long> tables = directory.assignments(tenant, shiftDate).stream()
                .collect(Collectors.groupingBy(TableWaiterAssignment::staffId, Collectors.counting()));
        Function<StaffRoster.OnShift, Long> load = shift -> tables.getOrDefault(shift.staffId(), 0L);
        return candidates.stream().min(Comparator.comparing(load)
                .thenComparing(shift -> shift.displayName() == null ? "" : shift.displayName())
                .thenComparing(StaffRoster.OnShift::staffId));
    }
}
