package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.api.common.ApiException;
import museon_online.astor_butler.capability.CapabilityArguments;
import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityProperties;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.capability.CapabilityTool;
import museon_online.astor_butler.domain.booking.TableReservationCommand;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationService;
import museon_online.astor_butler.domain.journey.GuestJourneyProperties;
import museon_online.astor_butler.domain.journey.GuestVisit;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.journey.JourneyEvents;
import museon_online.astor_butler.domain.journey.VisitEventKind;
import museon_online.astor_butler.domain.ledger.LedgerWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code reservation_create{date, time, partySize, guestName, guestPhone?, wish?}}: a request for a table that waits for
 * the hostess. It never confirms: the status is always {@code AWAITING_HOSTESS}. A Concierge booking is made in the
 * {@code CONCIERGE} channel without a chat of the Butler's bot, and its session is linked to the new visit.
 */
@Component
public class ReservationCreateTool implements CapabilityTool {

    static final Duration VISIT_LENGTH = Duration.ofMinutes(90);
    private static final Logger log = LoggerFactory.getLogger(ReservationCreateTool.class);

    private final TableReservationService reservations;
    private final GuestVisitRepository visits;
    private final JourneyEvents journey;
    private final LedgerWriter ledger;
    private final CapabilityProperties properties;
    private final GuestJourneyProperties journeyProperties;

    public ReservationCreateTool(TableReservationService reservations, GuestVisitRepository visits, JourneyEvents journey,
                                 LedgerWriter ledger, CapabilityProperties properties, GuestJourneyProperties journeyProperties) {
        this.reservations = reservations;
        this.visits = visits;
        this.journey = journey;
        this.ledger = ledger;
        this.properties = properties;
        this.journeyProperties = journeyProperties;
    }

    @Override
    public String name() {
        return "reservation_create";
    }

    @Override
    public String description() {
        return "Создать заявку на стол. Бронь ждёт подтверждения хостес (status=AWAITING_HOSTESS) — не обещай гостю, "
                + "что стол подтверждён. Возвращает номер брони (reservationId), стол (table), дату и время.";
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
        boolean butler = ctx.butlerAgent();
        Instant start = date.atTime(time).atZone(zone()).toInstant();
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
        facts.put("time", time.toString());
        facts.put("visitId", butler ? null : linkVisit(ctx, order));
        return CapabilityResult.ok(facts);
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
