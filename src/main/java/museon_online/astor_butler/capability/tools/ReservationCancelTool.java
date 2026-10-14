package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.api.common.ApiException;
import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.capability.CapabilityTool;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code reservation_cancel{reservationId}}: the guest cancels their own reservation; cancelling twice is no error. */
@Component
public class ReservationCancelTool implements CapabilityTool {

    private final TableReservationService reservations;
    private final ReservationAccess access;

    public ReservationCancelTool(TableReservationService reservations, ReservationAccess access) {
        this.reservations = reservations;
        this.access = access;
    }

    @Override
    public String name() {
        return "reservation_cancel";
    }

    @Override
    public String description() {
        return "Отменить бронь гостя по его просьбе. Возвращает reservationId и status (CANCELLED).";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object",
                "properties", Map.of("reservationId", Map.of("type", "integer", "description", "Номер брони гостя")),
                "required", List.of("reservationId"));
    }

    @Override
    public boolean commitment() {
        return true;
    }

    @Override
    public CapabilityResult invoke(CapabilityContext ctx, Map<String, Object> args) {
        ReservationAccess.Checked checked = access.check(ctx, args);
        if (!checked.allowed()) {
            return checked.error();
        }
        TableReservationOrder cancelled;
        try {
            cancelled = reservations.cancelByGuest(checked.order().id());
        } catch (ApiException e) {
            return ReservationAccess.refusal(e, "not_active");
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("reservationId", cancelled.id());
        facts.put("status", ReservationAccess.status(cancelled.status()));
        return CapabilityResult.ok(facts);
    }
}
