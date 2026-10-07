package museon_online.astor_butler.domain.billing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.booking.external.ExternalBookingState;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * What the booking sync tells billing after each read of the venue's system: the order's pay state goes into the
 * bill, a payment the venue confirmed is told to the guest, and a closed order starts the question how it went.
 * Adds no calls to the venue; never throws into the sync.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BillingSync {

    static final String PAY_STATE_KEY = "billPayState";

    private final BillingProperties properties;
    private final GuestBillService bills;
    private final GuestBillNotifier notifier;
    private final VisitReviewService reviews;

    public void onVenueSnapshot(String providerId, String externalOrderId, ExternalBookingState state, Map<String, Object> metadata) {
        if (!properties.isEnabled() || providerId == null || externalOrderId == null) {
            return;
        }
        try {
            Object reported = metadata == null ? null : metadata.get(PAY_STATE_KEY);
            if (reported instanceof BillPayState payState) {
                bills.venueReported(providerId, externalOrderId, payState, null);
            }
            Optional<GuestBill> found = bills.findByExternalOrder(providerId, externalOrderId);
            if (found.isEmpty()) {
                return;
            }
            GuestBill bill = found.get();
            if (bill.status() == GuestBillStatus.PAID && bill.paidNotifiedAt() == null && notifier.paid(bill)) {
                bill = bills.paidNotified(bill.id());
            }
            if (state == ExternalBookingState.COMPLETED) {
                reviews.prompt(bill);
            }
        } catch (RuntimeException e) {
            log.warn("Billing did not follow the venue's answer for an order: {}", e.toString());
        }
    }
}
