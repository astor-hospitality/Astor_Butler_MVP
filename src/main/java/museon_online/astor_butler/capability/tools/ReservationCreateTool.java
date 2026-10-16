package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.api.common.ApiException;
import museon_online.astor_butler.capability.CapabilityArguments;
import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityProperties;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.capability.CapabilityTool;
import museon_online.astor_butler.capability.GuestAgreement;
import museon_online.astor_butler.domain.booking.TableReservationCommand;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationService;
import museon_online.astor_butler.domain.booking.VenueOpeningHours;
import museon_online.astor_butler.domain.journey.GuestJourneyProperties;
import museon_online.astor_butler.domain.journey.GuestVisit;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.journey.JourneyEvents;
import museon_online.astor_butler.domain.journey.VisitEventKind;
import museon_online.astor_butler.domain.ledger.LedgerWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code reservation_create{date, time, partySize, guestName, guestPhone?, wish?}}: a request for a table that waits for
 * the hostess. It never confirms: the status is always {@code AWAITING_HOSTESS}. A Concierge booking is made in the
 * {@code CONCIERGE} channel without a chat of the Butler's bot, and its session is linked to the new visit.
 *
 * <p>Only after the guest's yes, whatever the model decides: the turn's {@link GuestAgreement} must cover this very
 * day, time and party size, offered to the guest in the venue's previous answer. Otherwise nothing is booked and the
 * answer is {@code NOT_ALLOWED} with {@code reason = needs_guest_yes} and the table to put to the guest. The same holds
 * in the Butler's own bot; a REST call carries no agreement and is always refused.
 *
 * <p>One guest, one table at a time: when the guest already holds a reservation across this visit (the bot's by its
 * chat, the Concierge's by its session and visit), nothing is booked and the answer is {@code NOT_ALLOWED} with
 * {@code reason = overlapping} and that reservation's id, day and time.
 *
 * <p>A slot no guest can have is refused before the yes is asked for: one that has already begun, by the venue's
 * time ({@code reason = already_passed}), or outside {@link VenueOpeningHours} ({@code reason = closed}, with that
 * day's hours). Every date in the facts comes with its weekday ({@code dateLabel}, «сб 17.10») for the model to copy.
 */
@Component
public class ReservationCreateTool implements CapabilityTool {

    static final Duration VISIT_LENGTH = Duration.ofMinutes(90);
    static final String ALREADY_PASSED = "already_passed";
    static final String CLOSED = "closed";
    private static final Logger log = LoggerFactory.getLogger(ReservationCreateTool.class);

    private final TableReservationService reservations;
    private final GuestVisitRepository visits;
    private final JourneyEvents journey;
    private final LedgerWriter ledger;
    private final CapabilityProperties properties;
    private final GuestJourneyProperties journeyProperties;
    private final VenueOpeningHours openingHours;
    private final Clock clock;

    @Autowired
    public ReservationCreateTool(TableReservationService reservations, GuestVisitRepository visits, JourneyEvents journey,
                                 LedgerWriter ledger, CapabilityProperties properties, GuestJourneyProperties journeyProperties,
                                 VenueOpeningHours openingHours) {
        this(reservations, visits, journey, ledger, properties, journeyProperties, openingHours, Clock.systemUTC());
    }

    public ReservationCreateTool(TableReservationService reservations, GuestVisitRepository visits, JourneyEvents journey,
                                 LedgerWriter ledger, CapabilityProperties properties, GuestJourneyProperties journeyProperties,
                                 VenueOpeningHours openingHours, Clock clock) {
        this.reservations = reservations;
        this.visits = visits;
        this.journey = journey;
        this.ledger = ledger;
        this.properties = properties;
        this.journeyProperties = journeyProperties;
        this.openingHours = openingHours;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "reservation_create";
    }

    @Override
    public String description() {
        return "Создать заявку на стол — только после «да» гостя на день, время и число гостей, которые ты назвал ему "
                + "в прошлом ответе. Без этого заявки не будет: NOT_ALLOWED, reason=needs_guest_yes, с днём, временем и числом "
                + "гостей — назови их гостю и спроси, бронировать ли. Если у гостя уже есть бронь на это время — NOT_ALLOWED, "
                + "reason=overlapping, с её номером, днём и временем: второй заявки не будет, предложи изменить ту. Время уже "
                + "наступило — reason=already_passed; ресторан в это время закрыт — reason=closed с часами работы (hours): "
                + "скажи гостю и предложи другое время. Бронь ждёт подтверждения хостес (status=AWAITING_HOSTESS) — "
                + "не обещай гостю, что стол подтверждён. Возвращает номер брони (reservationId), стол (table), дату, "
                + "день недели с датой (dateLabel, например «сб 17.10» — называй его так, сам не вычисляй) и время.";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("date", Map.of("type", "string", "description", "День визита в формате YYYY-MM-DD"));
        props.put("time", Map.of("type", "string", "description", "Время прихода в формате HH:mm, по времени ресторана"));
        props.put("partySize", Map.of("type", "integer", "description", "Сколько гостей, от 1"));
        props.put("guestName", Map.of("type", "string", "description", "Имя гостя для брони"));
        props.put("guestPhone", Map.of("type", "string", "description", "Телефон гостя, если он его назвал"));
        props.put("wish", Map.of("type", "string", "description", "Пожелание к столу своими словами гостя, например «у окна»"));
        return Map.of("type", "object", "properties", props, "required", List.of("date", "time", "partySize", "guestName"));
    }

    @Override
    public boolean commitment() {
        return true;
    }

    @Override
    public CapabilityResult invoke(CapabilityContext ctx, Map<String, Object> args) {
        LocalDate date = CapabilityArguments.date(args, "date");
        LocalTime time = CapabilityArguments.time(args, "time");
        Integer partySize = CapabilityArguments.integer(args, "partySize");
        String guestName = firstOf(CapabilityArguments.text(args, "guestName"), ctx == null ? null : ctx.guestName());
        String guestPhone = firstOf(CapabilityArguments.text(args, "guestPhone"), ctx == null ? null : ctx.guestPhone());
        if (ctx == null || date == null || time == null || partySize == null || partySize < 1 || guestName == null) {
            return CapabilityResult.error(CapabilityResult.BAD_ARGUMENT);
        }
        GuestAgreement.Table table = new GuestAgreement.Table(date, time, partySize);
        Instant start = date.atTime(time).atZone(zone()).toInstant();
        // A slot no guest can have is named before any yes is asked for: the yes could not book it anyway.
        if (start.isBefore(clock.instant())) {
            return slotRefusal(ALREADY_PASSED, table);
        }
        if (openingHours != null && !openingHours.isOpen(date, time)) {
            return slotRefusal(CLOSED, table);
        }
        if (ctx.agreement() == null || !ctx.agreement().covers(table)) {
            return GuestAgreement.refusal(table);
        }
        boolean butler = ctx.butlerAgent();
        Optional<TableReservationOrder> held = heldAtTheTime(ctx, start, start.plus(VISIT_LENGTH));
        if (held.isPresent()) {
            return overlapping(held.get());
        }
        TableReservationCommand command = new TableReservationCommand(
                butler ? ctx.chatId() : null,
                butler ? ctx.chatId() : null,
                null,
                properties.getVenueCode(),
                null,
                null,
                CapabilityArguments.text(args, "wish"),
                start,
                start.plus(VISIT_LENGTH),
                partySize,
                guestName,
                guestPhone,
                null,
                null,
                null,
                butler ? TableReservationCommand.CHANNEL_TELEGRAM : TableReservationCommand.CHANNEL_CONCIERGE);
        TableReservationOrder order;
        try {
            order = reservations.createReservation(command);
        } catch (ApiException e) {
            return ReservationAccess.refusal(e, "table_busy");
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("reservationId", order.id());
        // The tool only ever asks: whatever the order says, the hostess (or Presto) is the one who confirms.
        facts.put("status", "AWAITING_HOSTESS");
        facts.put("table", table(order));
        facts.put("date", date.toString());
        facts.put("dateLabel", GuestAgreement.dateLabel(date));
        facts.put("time", time.toString());
        facts.put("visitId", butler ? null : linkVisit(ctx, order));
        return CapabilityResult.ok(facts);
    }

    /**
     * The guest's own reservation that already holds a table across this window: in the bot by its chat, for the
     * Concierge by its session and visit (a Concierge booking has no chat). One guest never holds two tables at once.
     */
    private Optional<TableReservationOrder> heldAtTheTime(CapabilityContext ctx, Instant start, Instant end) {
        if (ctx.butlerAgent()) {
            return ctx.chatId() == null ? Optional.empty()
                    : reservations.findOverlappingReservation(ctx.chatId(), properties.getVenueCode(), start, end);
        }
        return reservations.findOverlappingSessionReservation(ctx.sessionId(), ctx.visitId(), properties.getVenueCode(), start, end);
    }

    /** {@code NOT_ALLOWED}, {@code reason = overlapping}: the reservation the guest already holds then, in venue time. */
    private CapabilityResult overlapping(TableReservationOrder held) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("reason", "overlapping");
        facts.put("reservationId", held.id());
        ZonedDateTime start = held.requestedStartAt().atZone(zone());
        facts.put("date", start.toLocalDate().toString());
        facts.put("dateLabel", GuestAgreement.dateLabel(start.toLocalDate()));
        facts.put("time", GuestAgreement.factTime(start.toLocalTime()));
        return new CapabilityResult(false, facts, CapabilityResult.NOT_ALLOWED);
    }

    /** {@code NOT_ALLOWED} for a slot that has begun or falls outside the opening hours, with the hours of that day. */
    private CapabilityResult slotRefusal(String reason, GuestAgreement.Table table) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("reason", reason);
        facts.put("date", table.date().toString());
        facts.put("dateLabel", GuestAgreement.dateLabel(table.date()));
        facts.put("time", GuestAgreement.factTime(table.time()));
        if (CLOSED.equals(reason) && openingHours != null) {
            facts.put("hours", openingHours.describe(table.date()).orElse("закрыто"));
        }
        return new CapabilityResult(false, facts, CapabilityResult.NOT_ALLOWED);
    }

    /** The visit of a Concierge booking, with the session's earlier turns moved into it; null while there is none. */
    private Long linkVisit(CapabilityContext ctx, TableReservationOrder order) {
        try {
            Optional<GuestVisit> visit = visits.findByReservation(order.id());
            if (visit.isEmpty() && journey != null) {
                journey.onReservation(order, VisitEventKind.REQUESTED, JourneyEvents.BOT);
                visit = visits.findByReservation(order.id());
            }
            if (visit.isEmpty() || visit.get().id() == null) {
                return null;
            }
            long visitId = visit.get().id();
            if (ctx.sessionId() != null && !ctx.sessionId().isBlank()) {
                ledger.linkSession(ctx.sessionId(), visitId);
            }
            return visitId;
        } catch (RuntimeException e) {
            log.warn("Concierge booking not linked to its visit: reservation={}, reason={}", order.id(), e.getClass().getSimpleName());
            return null;
        }
    }

    static String table(TableReservationOrder order) {
        String name = order.tableDisplayName();
        return name == null || name.isBlank() ? order.tableCode() : name;
    }

    private static String firstOf(String given, String known) {
        return given != null ? given : known == null || known.isBlank() ? null : known.trim();
    }

    private ZoneId zone() {
        return ZoneId.of(journeyProperties.getZoneId());
    }
}
