package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.capability.CapabilityTool;
import museon_online.astor_butler.capability.GuestAgreement;
import museon_online.astor_butler.domain.billing.BillActor;
import museon_online.astor_butler.domain.billing.GuestBillCancelService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code bill_cancel{reservationId}}: the guest cancels the unpaid bill of their reservation. Only on the guest's direct
 * request this turn («отмените счёт», {@link GuestAgreement#asksToCancelBill}), enforced on the guest's words whatever
 * the model decides, like {@code reservation_cancel}; a REST call carries no agreement and is always refused. A paid bill
 * is refused with {@code reason = paid} (a refund is made at the venue's till); cancelling twice is no error. The staff
 * group's wishes topic gets «Стол N: счёт отменён».
 */
@Component
public class BillCancelTool implements CapabilityTool {

    static final String ACTION = "cancel_bill";
    static final String PAID_LINE = "Этот счёт уже оплачен, отменить его нельзя — вернуть оплату можно на кассе ресторана, "
            + "команда поможет.";

    private final GuestBillCancelService cancellations;
    private final ReservationAccess access;

    public BillCancelTool(GuestBillCancelService cancellations, ReservationAccess access) {
        this.cancellations = cancellations;
        this.access = access;
    }

    @Override
    public String name() {
        return "bill_cancel";
    }

    @Override
    public String description() {
        return "Отменить неоплаченный счёт гостя по его брони — только когда гость прямо просит отменить счёт "
                + "(«отмените счёт»). Бронь не отменяет. Без просьбы гостя: NOT_ALLOWED, reason=needs_guest_yes. "
                + "Оплаченный счёт не отменяется: NOT_ALLOWED, reason=paid — скажи гостю guestLine. "
                + "Возвращает reservationId, billId и status (CANCELLED).";
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
        ReservationAccess.Checked checked = access.checkOrOnly(ctx, args);
        if (!checked.allowed()) {
            return checked.error();
        }
        long reservationId = checked.order().id();
        if (ctx.agreement() == null || !ctx.agreement().cancelsBill()) {
            return GuestAgreement.refusal(reservationId, ACTION, null);
        }
        GuestBillCancelService.Result result = cancellations.cancelByReservation(reservationId, BillActor.GUEST, "trigger=guest");
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("reservationId", reservationId);
        facts.put("billId", result.billId());
        switch (result.outcome()) {
            case BILLING_OFF -> {
                return ReservationAccess.withReason(CapabilityResult.UNAVAILABLE, "billing_off");
            }
            case NO_BILL -> {
                return ReservationAccess.withReason(CapabilityResult.NOT_ALLOWED, "no_bill");
            }
            case PAID -> {
                facts.put("reason", "paid");
                facts.put("guestLine", PAID_LINE);
                return new CapabilityResult(false, facts, CapabilityResult.NOT_ALLOWED);
            }
            default -> {
            }
        }
        facts.put("status", "CANCELLED");
        facts.put("alreadyCancelled", result.outcome() == GuestBillCancelService.Outcome.ALREADY_CANCELLED);
        return CapabilityResult.ok(facts);
    }
}
