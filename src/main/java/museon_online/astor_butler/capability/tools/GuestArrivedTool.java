package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.api.common.ApiException;
import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.capability.CapabilityTool;
import museon_online.astor_butler.capability.GuestAgreement;
import museon_online.astor_butler.domain.booking.HostessSeatedService;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code guest_arrived{reservationId?}}: the guest says they are at the venue («мы пришли», «мы на месте»). A confirmed
 * booking of this guest becomes SEATED, as on the hostess button «🪑 Гость сел», and the hostess chat hears «Гости
 * брони #N на месте, стол T» — but only from {@link HostessSeatedService#ARRIVAL_EARLIEST} before to
 * {@link HostessSeatedService#ARRIVAL_LATEST} after the booking time; outside that the answer is {@code NOT_ALLOWED},
 * {@code reason = outside_window}, with the booking's date and time. Idempotent: an already seated booking answers
 * {@code alreadySeated = true} with the first time, whoever set it.
 *
 * <p>The booking is found like the other reservation tools: a missing or invented id means the Concierge session's
 * only active booking ({@link ReservationAccess#checkOrOnly}), or for the Butler's bot the chat's only active booking.
 * Not a commitment: nothing is promised to the guest, and a repeat changes nothing.
 */
@Component
public class GuestArrivedTool implements CapabilityTool {

    private static final ZoneId VENUE_ZONE = ZoneId.of("Asia/Yekaterinburg");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final HostessSeatedService seating;
    private final TableReservationService reservations;
    private final ReservationAccess access;
    private final Clock clock;

    @Autowired
    public GuestArrivedTool(HostessSeatedService seating, TableReservationService reservations, ReservationAccess access) {
        this(seating, reservations, access, Clock.systemUTC());
    }

    public GuestArrivedTool(HostessSeatedService seating, TableReservationService reservations, ReservationAccess access,
                            Clock clock) {
        this.seating = seating;
        this.reservations = reservations;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "guest_arrived";
    }

    @Override
    public String description() {
        return "Гость сообщает, что уже пришёл в ресторан («мы пришли», «мы на месте», «уже сидим»): отмечает посадку "
                + "по его брони, хостес получает «Гости брони #N на месте». Только на такие слова гостя — не на вопросы "
                + "«как пройти», «во сколько бронь». Работает в день брони с 60 минут до и до 90 минут после её времени; "
                + "иначе NOT_ALLOWED, reason=outside_window с датой и временем брони. reservationId можно не передавать, "
                + "если бронь у гостя одна. Возвращает reservationId, status (SEATED), table, seatedAt, alreadySeated.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object",
                "properties", Map.of("reservationId", Map.of("type", "integer",
                        "description", "Номер брони гостя; можно не передавать, если бронь одна")),
                "required", List.of());
    }

    @Override
    public boolean commitment() {
        return false;
    }

    @Override
    public CapabilityResult invoke(CapabilityContext ctx, Map<String, Object> args) {
        ReservationAccess.Checked checked = access.checkOrOnly(ctx, args == null ? Map.of() : args);
        if (!checked.allowed()) {
            TableReservationOrder only = onlyBookingOfTheChat(ctx);
            if (only == null) {
                return checked.error();
            }
            checked = access.check(ctx, only.id());
            if (!checked.allowed()) {
                return checked.error();
            }
        }
        HostessSeatedService.Arrival arrival;
        try {
            arrival = seating.guestArrived(checked.order().id(), clock.instant());
        } catch (ApiException e) {
            return ReservationAccess.refusal(e, "not_confirmed");
        }
        TableReservationOrder order = arrival.order();
        return switch (arrival.outcome()) {
            case SEATED -> CapabilityResult.ok(facts(order, false));
            case ALREADY_SEATED -> CapabilityResult.ok(facts(order, true));
            case NOT_CONFIRMED -> refusal("not_confirmed", order, arrival);
            case OUTSIDE_WINDOW -> refusal("outside_window", order, arrival);
            case OFF -> refusal("seated_off", order, arrival);
        };
    }

    /** The Butler's bot: a missing or wrong id means the chat's only active booking, as the Concierge's session gets. */
    private TableReservationOrder onlyBookingOfTheChat(CapabilityContext ctx) {
        if (ctx == null || !ctx.butlerAgent() || ctx.chatId() == null) {
            return null;
        }
        List<TableReservationOrder> active;
        try {
            active = reservations.listActiveReservationsByChatId(ctx.chatId());
        } catch (ApiException e) {
            return null;
        }
        return active != null && active.size() == 1 ? active.get(0) : null;
    }

    private static Map<String, Object> facts(TableReservationOrder order, boolean alreadySeated) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("reservationId", order.id());
        facts.put("status", ReservationAccess.status(order.status()));
        facts.put("table", order.tableCode());
        facts.put("seatedAt", order.seatedAt() == null ? null : localTime(order.seatedAt()));
        facts.put("alreadySeated", alreadySeated);
        return facts;
    }

    private static CapabilityResult refusal(String reason, TableReservationOrder order, HostessSeatedService.Arrival arrival) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("reason", reason);
        facts.put("reservationId", order.id());
        facts.put("status", ReservationAccess.status(order.status()));
        if (order.requestedStartAt() != null) {
            ZonedDateTime start = order.requestedStartAt().atZone(VENUE_ZONE);
            facts.put("date", start.toLocalDate().toString());
            facts.put("dateLabel", GuestAgreement.dateLabel(start.toLocalDate()));
            facts.put("time", TIME.format(start));
        }
        if (arrival.windowFrom() != null && arrival.windowTo() != null) {
            facts.put("arrivalFrom", localTime(arrival.windowFrom()));
            facts.put("arrivalTo", localTime(arrival.windowTo()));
        }
        return new CapabilityResult(false, facts, CapabilityResult.NOT_ALLOWED);
    }

    private static String localTime(Instant instant) {
        return TIME.format(instant.atZone(VENUE_ZONE));
    }
}
