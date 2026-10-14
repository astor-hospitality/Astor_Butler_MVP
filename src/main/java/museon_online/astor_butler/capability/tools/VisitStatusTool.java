package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.capability.CapabilityTool;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.journey.GuestJourneyProperties;
import museon_online.astor_butler.domain.journey.GuestVisit;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code visit_status{}}: the stage of the guest's visit, found by the context's visit id or else by the visit whose
 * window holds now for the chat. No visit is a fact too: {@code stage = null}. A visit of another chat is not shown.
 */
@Component
public class VisitStatusTool implements CapabilityTool {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final GuestVisitRepository visits;
    private final TableReservationRepository orders;
    private final GuestJourneyProperties journey;
    private final Clock clock;

    @Autowired
    public VisitStatusTool(GuestVisitRepository visits, TableReservationRepository orders, GuestJourneyProperties journey) {
        this(visits, orders, journey, Clock.systemUTC());
    }

    public VisitStatusTool(GuestVisitRepository visits, TableReservationRepository orders, GuestJourneyProperties journey,
                           Clock clock) {
        this.visits = visits;
        this.orders = orders;
        this.journey = journey;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "visit_status";
    }

    @Override
    public String description() {
        return "Состояние визита гостя: этап (stage), номер брони (reservationId), время визита по ресторану (visitDay) "
                + "и стол (table). stage=null — активного визита нет.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    @Override
    public boolean commitment() {
        return false;
    }

    @Override
    public CapabilityResult invoke(CapabilityContext ctx, Map<String, Object> args) {
        Optional<GuestVisit> found = Optional.empty();
        if (ctx != null && ctx.visitId() != null) {
            found = visits.findById(ctx.visitId());
        }
        if (found.isEmpty() && ctx != null && ctx.chatId() != null) {
            found = visits.activeByChat(ctx.chatId(), clock.instant());
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        if (found.isEmpty()) {
            facts.put("stage", null);
            return CapabilityResult.ok(facts);
        }
        GuestVisit visit = found.get();
        if (ctx.chatId() != null && visit.chatId() != null && !Objects.equals(ctx.chatId(), visit.chatId())) {
            return CapabilityResult.error(CapabilityResult.NOT_ALLOWED);
        }
        facts.put("stage", visit.stage() == null ? null : visit.stage().name());
        facts.put("reservationId", visit.reservationId());
        facts.put("visitDay", visit.visitDay() == null ? null
                : visit.visitDay().atZone(ZoneId.of(journey.getZoneId())).format(DAY));
        facts.put("table", table(visit.reservationId()));
        return CapabilityResult.ok(facts);
    }

    private String table(Long reservationId) {
        if (reservationId == null) {
            return null;
        }
        Optional<TableReservationOrder> order = orders.findOrder(reservationId);
        if (order.isEmpty()) {
            return null;
        }
        String name = order.get().tableDisplayName();
        return name == null || name.isBlank() ? order.get().tableCode() : name;
    }
}
