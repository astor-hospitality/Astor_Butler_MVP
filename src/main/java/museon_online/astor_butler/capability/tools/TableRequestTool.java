package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.capability.CapabilityArguments;
import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.capability.CapabilityTool;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.journey.GuestVisit;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.journey.JourneyStage;
import museon_online.astor_butler.domain.journey.talk.TableRequest;
import museon_online.astor_butler.domain.journey.talk.TableRequestRouter;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@code table_request{reservationId, request}}: bread, water, the waiter, a faster kitchen or cutlery for a guest
 * at the table. Only while the guest is seated; the card goes to the table's waiter or the team.
 */
@Component
public class TableRequestTool implements CapabilityTool {

    private final TableRequestRouter router;
    private final ReservationAccess access;
    private final GuestVisitRepository visits;

    public TableRequestTool(TableRequestRouter router, ReservationAccess access, GuestVisitRepository visits) {
        this.router = router;
        this.access = access;
        this.visits = visits;
    }

    @Override
    public String name() {
        return "table_request";
    }

    @Override
    public String description() {
        return "Передать просьбу гостя за столом команде: BREAD — хлеб, WATER — вода, WAITER — позвать официанта, "
                + "SPEED_UP — ускорить подачу, CUTLERY — приборы. Только когда гость уже за столом. "
                + "Возвращает routed и имя официанта (waiter), если он известен.";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("reservationId", Map.of("type", "integer", "description", "Номер брони гостя"));
        props.put("request", Map.of("type", "string", "enum", Arrays.stream(TableRequest.values()).map(Enum::name).toList(),
                "description", "Что нужно гостю"));
        return Map.of("type", "object", "properties", props, "required", List.of("reservationId", "request"));
    }

    @Override
    public boolean commitment() {
        return true;
    }

    @Override
    public CapabilityResult invoke(CapabilityContext ctx, Map<String, Object> args) {
        Optional<TableRequest> request = request(CapabilityArguments.text(args, "request"));
        if (request.isEmpty()) {
            return CapabilityResult.error(CapabilityResult.BAD_ARGUMENT);
        }
        ReservationAccess.Checked checked = access.checkOrOnly(ctx, args);
        if (!checked.allowed()) {
            return checked.error();
        }
        TableReservationOrder order = checked.order();
        if (!seated(order)) {
            return ReservationAccess.withReason(CapabilityResult.NOT_ALLOWED, "not_seated");
        }
        // The waiter the card went to: the day's assignment or the one picked by zone and shift for this very request.
        TableRequestRouter.Routed routed = router.deliver(order.id(), request.get(), request.get().what());
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("routed", routed.delivered());
        if (routed.delivered() && routed.waiterName() != null) {
            facts.put("waiter", routed.waiterName());
        }
        return CapabilityResult.ok(facts);
    }

    /** The visit's stage when the journey knows the visit; the reservation's own status otherwise. */
    private boolean seated(TableReservationOrder order) {
        Optional<GuestVisit> visit = visits.findByReservation(order.id());
        if (visit.isPresent()) {
            return visit.get().stage() == JourneyStage.SEATED;
        }
        return order.status() == TableReservationStatus.SEATED;
    }

    private static Optional<TableRequest> request(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(TableRequest.valueOf(value.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
