package museon_online.astor_butler.domain.billing;

import museon_online.astor_butler.domain.booking.external.ExternalBookingState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What one read of the venue's order does to the bill, the guest and the review. */
class BillingSyncTest {

    private static final long CHAT = 1773317437L;
    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    private final BillingProperties properties = new BillingProperties();
    private final GuestBillNotifier notifier = mock(GuestBillNotifier.class);
    private GuestBillService bills;
    private VisitReviewService reviews;
    private BillingSync sync;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = BillingTestDatabase.create();
        bills = new GuestBillService(new GuestBillRepository(jdbc), new BillOperationRepository(jdbc));
        reviews = new VisitReviewService(properties, bills, new VisitReviewRepository(jdbc), notifier, new GuestBillPaymentPrompterTest.MutableClock(NOW));
        sync = new BillingSync(properties, bills, notifier, reviews);
        properties.setEnabled(true);
        when(notifier.paid(any())).thenReturn(true);
    }

    @Test
    void aPaidOrderMakesThePaidBillAndTellsTheGuestOnce() {
        GuestBill bill = issued();

        sync.onVenueSnapshot("SABY", "order-1", ExternalBookingState.CONFIRMED, Map.of(BillingSync.PAY_STATE_KEY, BillPayState.UNPAID));
        assertThat(bills.find(bill.id()).orElseThrow().status()).isEqualTo(GuestBillStatus.ISSUED);
        verify(notifier, never()).paid(any());

        sync.onVenueSnapshot("SABY", "order-1", ExternalBookingState.CONFIRMED, Map.of(BillingSync.PAY_STATE_KEY, BillPayState.PAID));
        sync.onVenueSnapshot("SABY", "order-1", ExternalBookingState.CONFIRMED, Map.of(BillingSync.PAY_STATE_KEY, BillPayState.PAID));

        GuestBill paid = bills.find(bill.id()).orElseThrow();
        assertThat(paid.status()).isEqualTo(GuestBillStatus.PAID);
        assertThat(paid.paidNotifiedAt()).isNotNull();
        verify(notifier, times(1)).paid(any());
        assertThat(bills.historyOf(bill.id())).extracting(BillOperation::type).containsExactly(
                BillOperationType.ESTIMATE_CREATED, BillOperationType.ISSUED_TO_VENUE,
                BillOperationType.PAY_STATE_CHANGED, BillOperationType.PAY_STATE_CHANGED, BillOperationType.PAID_NOTIFIED);
    }

    @Test
    void aClosedOrderAsksTheGuestHowItWent() {
        GuestBill bill = issued();
        when(notifier.reviewPrompt(any())).thenReturn(true);

        sync.onVenueSnapshot("SABY", "order-1", ExternalBookingState.COMPLETED, Map.of(BillingSync.PAY_STATE_KEY, BillPayState.PAID));
        sync.onVenueSnapshot("SABY", "order-1", ExternalBookingState.COMPLETED, Map.of(BillingSync.PAY_STATE_KEY, BillPayState.PAID));

        assertThat(reviews.reviewOf(bill.id())).isPresent();
        verify(notifier, times(1)).reviewPrompt(any());
    }

    @Test
    void anUnknownReadOrAnOrderButlerDoesNotHoldChangesNothing() {
        GuestBill bill = issued();

        sync.onVenueSnapshot("SABY", "order-1", ExternalBookingState.UNKNOWN, Map.of());
        sync.onVenueSnapshot("SABY", "someone-else", ExternalBookingState.COMPLETED, Map.of(BillingSync.PAY_STATE_KEY, BillPayState.PAID));
        sync.onVenueSnapshot("SABY", null, ExternalBookingState.COMPLETED, Map.of(BillingSync.PAY_STATE_KEY, BillPayState.PAID));

        assertThat(bills.find(bill.id()).orElseThrow().payState()).isEqualTo(BillPayState.NOT_REPORTED);
        assertThat(bills.historyOf(bill.id())).hasSize(2);
        verify(notifier, never()).paid(any());
        verify(notifier, never()).reviewPrompt(any());
    }

    @Test
    void staysQuietWhileBillingIsOffAndNeverThrows() {
        issued();
        properties.setEnabled(false);
        sync.onVenueSnapshot("SABY", "order-1", ExternalBookingState.COMPLETED, Map.of(BillingSync.PAY_STATE_KEY, BillPayState.PAID));
        verify(notifier, never()).paid(any());

        properties.setEnabled(true);
        when(notifier.paid(any())).thenThrow(new IllegalStateException("telegram is down"));
        sync.onVenueSnapshot("SABY", "order-1", ExternalBookingState.CONFIRMED, Map.of(BillingSync.PAY_STATE_KEY, BillPayState.PAID));
        assertThat(bills.findByExternalOrder("SABY", "order-1").orElseThrow().paidNotifiedAt()).isNull();
    }

    private GuestBill issued() {
        GuestBill bill = bills.open(new BillDraft(CHAT, CHAT, "AERIS", GuestBillKind.BUSINESS_LUNCH, "DIRECT", 77L, "test",
                OrderEstimate.of(List.of(OrderEstimate.Line.priced("BORSCHT", "Борщ", 2, 270))), NOW.minus(Duration.ofHours(2))));
        return bills.issued(bill.id(), "SABY", "order-1");
    }
}
