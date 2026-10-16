package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.capability.CapabilityTool;
import museon_online.astor_butler.domain.billing.BillRequestOutcome;
import museon_online.astor_butler.domain.billing.GuestBillRequestService;
import museon_online.astor_butler.domain.journey.JourneyEvents;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code bill_request{reservationId}}: the guest asks for the bill. The team gets the card once per repeat window;
 * the answer carries the bill Butler knows of and the payment link only when the venue's payment is on.
 */
@Component
public class BillRequestTool implements CapabilityTool {

    private final GuestBillRequestService bills;
    private final ReservationAccess access;

    public BillRequestTool(GuestBillRequestService bills, ReservationAccess access) {
        this.bills = bills;
        this.access = access;
    }

    @Override
    public String name() {
        return "bill_request";
    }

    @Override
    public String description() {
        return "Попросить счёт для гостя за столом: команда получает карточку. Возвращает номер счёта (billId) и сумму "
                + "в рублях (totalRub), если счёт есть у Батлера, и ссылку на оплату (paymentUrl) — только если оплата "
                + "включена; нет поля — нет ссылки.";
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
        BillRequestOutcome outcome = bills.requestByReservation(checked.order().id(), JourneyEvents.GUEST);
        switch (outcome.status()) {
            case NO_VISIT -> {
                return ReservationAccess.withReason(CapabilityResult.NOT_ALLOWED, "no_visit_today");
            }
            case OFF, PAUSED, STAFF_NOT_REACHED -> {
                return ReservationAccess.withReason(CapabilityResult.UNAVAILABLE, outcome.status().name().toLowerCase());
            }
            default -> {
            }
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("billId", outcome.billId());
        facts.put("totalRub", outcome.totalRub());
        if (outcome.paymentUrl() != null) {
            facts.put("paymentUrl", outcome.paymentUrl());
        }
        facts.put("alreadyRequested", outcome.status() == BillRequestOutcome.Status.ALREADY_SENT);
        return CapabilityResult.ok(facts);
    }
}
