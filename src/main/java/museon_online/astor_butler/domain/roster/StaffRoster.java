package museon_online.astor_butler.domain.roster;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Who is on shift, by the latest confirmed schedule from the «График» topic: whom to send a task to, whom to mention
 * on a card. Knows nobody while {@code astor.staff.schedule.enabled} is off or no schedule covering the day was
 * confirmed — the callers then do as they did before.
 *
 * <p>Only rows matched to an active member of the directory count: an unknown or unsettled name is never an
 * addressee. A row without hours is on shift the whole of its day.
 */
@Service
public class StaffRoster {

    /** A person on shift on a day; {@code start}/{@code end} null when the schedule gave no hours. */
    public record OnShift(String staffId, String displayName, StaffRole role, LocalDate date, LocalTime start,
                          LocalTime end) {

        /** The shift runs past midnight into the next day. */
        public boolean overnight() {
            return start != null && end != null && !end.isAfter(start);
        }

        boolean coversToday(LocalTime now) {
            if (start == null) {
                return true;
            }
            if (now.isBefore(start)) {
                return false;
            }
            return end == null || overnight() || now.isBefore(end);
        }

        boolean coversFromYesterday(LocalTime now) {
            return overnight() && now.isBefore(end);
        }
    }

    private final StaffScheduleProperties properties;
    private final StaffScheduleRepository repository;
    private final Clock clock;

    @Autowired
    public StaffRoster(StaffScheduleProperties properties, StaffScheduleRepository repository) {
        this(properties, repository, Clock.systemUTC());
    }

    StaffRoster(StaffScheduleProperties properties, StaffScheduleRepository repository, Clock clock) {
        this.properties = properties;
        this.repository = repository;
        this.clock = clock;
    }

    /** Everyone on shift right now, in the venue's time zone. */
    public List<OnShift> onShiftNow() {
        return onShiftAt(ZonedDateTime.now(clock.withZone(properties.zone())));
    }

    /** Those of the role on shift right now: the addressees of a task for that role. */
    public List<OnShift> onShiftNow(StaffRole role) {
        return onShiftNow().stream().filter(shift -> shift.role() == role).toList();
    }

    public List<OnShift> onShiftAt(ZonedDateTime at) {
        if (!properties.isEnabled()) {
            return List.of();
        }
        ZonedDateTime local = at.withZoneSameInstant(properties.zone());
        LocalTime now = local.toLocalTime();
        List<OnShift> result = new ArrayList<>();
        for (OnShift shift : onShift(local.toLocalDate().minusDays(1))) {
            if (shift.coversFromYesterday(now)) {
                result.add(shift);
            }
        }
        for (OnShift shift : onShift(local.toLocalDate())) {
            if (shift.coversToday(now) && result.stream().noneMatch(other -> other.staffId().equals(shift.staffId()))) {
                result.add(shift);
            }
        }
        return List.copyOf(result);
    }

    /** Everyone the confirmed schedule puts on that day, whatever the hours. */
    public List<OnShift> onShift(LocalDate date) {
        if (!properties.isEnabled()) {
            return List.of();
        }
        String tenant = properties.tenantOrDefault();
        return repository.governing(tenant, date)
                .map(schedule -> repository.onShift(tenant, schedule.id(), date))
                .orElse(List.of());
    }

    /** The venue whose directory the schedule's names were matched against. */
    public String tenant() {
        return properties.tenantOrDefault();
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(properties.zone()));
    }

    ZonedDateTime now() {
        return ZonedDateTime.now(clock.withZone(properties.zone()));
    }
}
