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
 * {@code bill_request{reservationId}}: the guest asks for the bill. The team gets the card «Счёт · стол · официант ·
 * гость» once per repeat window ({@code astor.billing.guest-bill-request.repeat-window-minutes}, 10 by default); a
 * repeat inside it sends nothing and answers {@code alreadyRequested=true}.
 *
 * <p>The sum is the venue's (Saby) only: Butler's own estimate is never told to the guest as the bill (16.10: «2230 ₽»
 * said, 2510 ₽ in Saby). Without the venue's sum the answer has no {@code totalRub} and {@code guestLine} says
 * «Официант несёт счёт». The payment link is there only when the venue's payment is on; {@code paymentLinkSent} says
 * it already went to the guest with an «Оплатить» button, so the agent does not repeat it.
 */
@Component
public class BillRequestTool implements CapabilityTool {

    /** The default repeat window ({@code GuestBillRequestProperties#repeatWindowMinutes}). */
    static final int REPEAT_WINDOW_MINUTES = 10;
    static final String BRINGING = "Официант несёт счёт.";

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
        return "Попросить счёт для гостя за столом: команда получает карточку, официант несёт счёт. Повторный вызов "
                + "в течение " + REPEAT_WINDOW_MINUTES + " минут карточку не дублирует (alreadyRequested=true). "
                + "Сумма (totalRub) — только из кассы ресторана; нет totalRub — сумму не называй и не считай сам, "
                + "скажи гостю guestLine («Официант несёт счёт»). Ссылка на оплату (paymentUrl) — только если оплата "
                + "включена; paymentLinkSent=true — ссылка с кнопкой «Оплатить» уже ушла гостю, не повторяй её.";
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
        if (outcome.totalRub() != null) {
            facts.put("totalRub", outcome.totalRub());
        }
        facts.put("amountKnown", outcome.totalRub() != null);
        if (outcome.paymentUrl() != null) {
            facts.put("paymentUrl", outcome.paymentUrl());
        }
        facts.put("paymentLinkSent", outcome.paymentLinkSent());
        facts.put("alreadyRequested", outcome.status() == BillRequestOutcome.Status.ALREADY_SENT);
        facts.put("guestLine", guestLine(outcome));
        return CapabilityResult.ok(facts);
    }

    /** What the guest may be told: the venue's sum when known, otherwise that the waiter is bringing the bill. */
    static String guestLine(BillRequestOutcome outcome) {
        String line = outcome.totalRub() == null ? BRINGING : "Счёт — " + outcome.totalRub() + " ₽, официант несёт его.";
        return outcome.paymentLinkSent() ? line + " Ссылка на оплату — в сообщении с кнопкой «Оплатить»." : line;
    }
}
