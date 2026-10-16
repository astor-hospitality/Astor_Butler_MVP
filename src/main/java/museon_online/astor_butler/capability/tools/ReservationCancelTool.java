package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.api.common.ApiException;
import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.capability.CapabilityTool;
import museon_online.astor_butler.capability.GuestAgreement;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code reservation_cancel{reservationId}}: the guest cancels their own reservation; cancelling twice is no error. Only
 * on the guest's direct request: a «нет» to an order summary is not one (run 16.10: «нет, это тест» went looking for a
 * booking to cancel).
 *
 * <p>Enforced on the guest's word, whatever the model decides: the guest's own words this turn ask to cancel, or say
 * yes to cancelling this reservation, offered in the venue's previous answer ({@link GuestAgreement#allowsCancel}).
 * Otherwise nothing is cancelled and the answer is {@code NOT_ALLOWED} with {@code reason = needs_guest_yes}, the
 * reservation and {@code action = cancel}. A REST call carries no agreement and is always refused.
 */
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
        return "Отменить уже созданную бронь гостя — только когда гость прямо просит её отменить («отмените бронь»). "
                + "«Нет» на перечень заказа или на предложение стола — не просьба об отмене: тогда не вызывай, а спроси, "
                + "что поменять. Без просьбы гостя брони не отменят: NOT_ALLOWED, reason=needs_guest_yes. "
                + "Возвращает reservationId и status (CANCELLED).";
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
        long reservationId = checked.order().id();
        if (ctx.agreement() == null || !ctx.agreement().allowsCancel(reservationId)) {
            return GuestAgreement.refusal(reservationId, GuestAgreement.CANCEL, null);
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
