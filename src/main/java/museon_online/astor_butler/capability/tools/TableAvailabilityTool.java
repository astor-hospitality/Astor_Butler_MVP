package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.capability.CapabilityArguments;
import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityProperties;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.capability.CapabilityTool;
import museon_online.astor_butler.capability.GuestAgreement;
import museon_online.astor_butler.domain.booking.TableAvailability;
import museon_online.astor_butler.domain.booking.TableReservationService;
import museon_online.astor_butler.domain.booking.VenueOpeningHours;
import museon_online.astor_butler.domain.booking.VenueTable;
import museon_online.astor_butler.domain.journey.GuestJourneyProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code table_availability{date, time, partySize}}: free tables for a 90-minute slot in venue time and, only when none
 * is free, up to three later times the same day with a free table. Reads only: nothing is held or booked. A slot
 * that has begun by the venue's time is {@code NOT_ALLOWED} with {@code reason = already_passed}, as in
 * {@code reservation_create}; the date comes with its weekday ({@code dateLabel}, «сб 17.10») for the model to copy.
 */
@Component
public class TableAvailabilityTool implements CapabilityTool {

    /** No booking length is configured in the service; a table is asked for 90 minutes. */
    static final Duration SLOT = Duration.ofMinutes(90);
    static final Duration STEP = Duration.ofMinutes(15);
    static final int MAX_ALTERNATIVES = 3;
    /**
     * Each probe asks the restaurant system too (a Saby round trip), so the search looks at most 90 minutes ahead:
     * the whole turn must fit the Concierge's wait.
     */
    static final int MAX_PROBES = 6;
    static final int MAX_PARTY = 50;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final TableReservationService reservations;
    private final VenueOpeningHours openingHours;
    private final CapabilityProperties properties;
    private final GuestJourneyProperties journey;
    private final Clock clock;

    @Autowired
    public TableAvailabilityTool(TableReservationService reservations, VenueOpeningHours openingHours,
                                 CapabilityProperties properties, GuestJourneyProperties journey) {
        this(reservations, openingHours, properties, journey, Clock.systemUTC());
    }

    public TableAvailabilityTool(TableReservationService reservations, VenueOpeningHours openingHours,
                                 CapabilityProperties properties, GuestJourneyProperties journey, Clock clock) {
        this.reservations = reservations;
        this.openingHours = openingHours;
        this.properties = properties;
        this.journey = journey;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "table_availability";
    }

    @Override
    public String description() {
        return "Свободные столы на дату и время (время ресторана, слот 90 минут) для заданного числа гостей. "
                + "dateLabel — день недели и дата («сб 17.10»): называй день так, сам не вычисляй. "
                + "free — свободные столы; если free пуст, alternatives — до трёх более поздних времён (в пределах полутора часов) "
                + "в тот же день со свободным столом, "
                + "open=false — ресторан в это время закрыт. Время уже наступило — NOT_ALLOWED, reason=already_passed: "
                + "не предлагай его. Стол гостю называй только из free или alternatives этого ответа. Ничего не бронирует.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "date", Map.of("type", "string", "description", "Дата в формате YYYY-MM-DD"),
                        "time", Map.of("type", "string", "description", "Время начала в формате HH:mm"),
                        "partySize", Map.of("type", "integer", "description", "Сколько гостей", "minimum", 1)),
                "required", List.of("date", "time", "partySize"));
    }

    @Override
    public boolean commitment() {
        return false;
    }

    @Override
    public CapabilityResult invoke(CapabilityContext ctx, Map<String, Object> args) {
        LocalDate date;
        LocalTime time;
        Integer partySize;
        try {
            date = CapabilityArguments.date(args, "date");
            time = CapabilityArguments.time(args, "time");
            partySize = CapabilityArguments.integer(args, "partySize");
        } catch (CapabilityArguments.BadArgument e) {
            return CapabilityResult.error(CapabilityResult.BAD_ARGUMENT);
        }
        if (date == null || time == null || partySize == null || partySize < 1 || partySize > MAX_PARTY) {
            return CapabilityResult.error(CapabilityResult.BAD_ARGUMENT);
        }
        ZoneId zone = ZoneId.of(journey.getZoneId());
        if (date.atTime(time).atZone(zone).toInstant().isBefore(clock.instant())) {
            // A reason the agent can say, not a bad argument it would retry: «вчера в 19:00» is never offered.
            Map<String, Object> passed = new LinkedHashMap<>();
            passed.put("reason", ReservationCreateTool.ALREADY_PASSED);
            passed.put("date", date.toString());
            passed.put("dateLabel", GuestAgreement.dateLabel(date));
            passed.put("time", time.format(TIME));
            return new CapabilityResult(false, passed, CapabilityResult.NOT_ALLOWED);
        }
        boolean open = openingHours.isOpen(date, time);
        List<Map<String, Object>> free = new ArrayList<>();
        if (open) {
            for (VenueTable table : freeTables(date, time, zone, partySize)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("tableCode", table.tableCode());
                entry.put("name", table.displayName());
                entry.put("capacity", table.capacityMax());
                free.add(entry);
            }
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("date", date.toString());
        facts.put("dateLabel", GuestAgreement.dateLabel(date));
        facts.put("time", time.format(TIME));
        facts.put("partySize", partySize);
        facts.put("open", open);
        facts.put("free", free);
        // Alternatives only for a full slot: with a free table they would only cost more calls to the restaurant system.
        facts.put("alternatives", free.isEmpty() ? alternatives(date, time, zone, partySize) : List.of());
        return CapabilityResult.ok(facts);
    }

    private List<Map<String, Object>> alternatives(LocalDate date, LocalTime time, ZoneId zone, int partySize) {
        List<Map<String, Object>> found = new ArrayList<>();
        LocalTime probe = time;
        for (int step = 0; step < MAX_PROBES && found.size() < MAX_ALTERNATIVES; step++) {
            LocalTime next = probe.plus(STEP);
            if (!next.isAfter(probe)) {
                break; // past midnight: another day
            }
            probe = next;
            if (!openingHours.isOpen(date, probe)) {
                continue;
            }
            List<VenueTable> tables = freeTables(date, probe, zone, partySize);
            if (!tables.isEmpty()) {
                found.add(Map.of("time", probe.format(TIME), "tableCode", tables.getFirst().tableCode()));
            }
        }
        return found;
    }

    private List<VenueTable> freeTables(LocalDate date, LocalTime time, ZoneId zone, int partySize) {
        Instant start = date.atTime(time).atZone(zone).toInstant();
        List<TableAvailability> availability = reservations.availability(properties.getVenueCode(), start,
                start.plus(SLOT), partySize);
        if (availability == null) {
            return List.of();
        }
        return availability.stream().filter(TableAvailability::available).map(TableAvailability::table).toList();
    }
}
