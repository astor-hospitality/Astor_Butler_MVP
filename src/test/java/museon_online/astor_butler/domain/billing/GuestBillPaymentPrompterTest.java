package museon_online.astor_butler.domain.billing;

import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The guest gets the venue's payment page once, after the table is theirs, and never a page Butler could not get. */
class GuestBillPaymentPrompterTest {

    private static final long CHAT = 1773317437L;
    private static final Instant NOW = Instant.parse("2026-10-06T07:00:00Z");

    private final BillingProperties properties = new BillingProperties();
    private final TableReservationRepository reservations = mock(TableReservationRepository.class);
    private final ExternalPaymentProvider provider = mock(ExternalPaymentProvider.class);
    private final GuestBillNotifier notifier = mock(GuestBillNotifier.class);
    private final MutableClock clock = new MutableClock(NOW);
    private GuestBillService bills;
    private GuestBillPaymentPrompter prompter;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = BillingTestDatabase.create();
        bills = new GuestBillService(new GuestBillRepository(jdbc), new BillOperationRepository(jdbc));
        prompter = new GuestBillPaymentPrompter(properties, bills, reservations, List.of(provider), notifier, clock);
        properties.setEnabled(true);
        when(provider.providerId()).thenReturn("SABY");
        when(provider.enabled()).thenReturn(true);
        when(notifier.paymentLink(any(), anyString())).thenReturn(true);
    }

    @Test
    void sendsTheLinkOnceTheBookingIsConfirmed() {
        GuestBill bill = issued(77L);
        reservation(77L, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION);

        assertThat(prompter.run()).isEqualTo(new GuestBillPaymentPrompter.Report(1, 0, 1, 0));
        verify(provider, never()).paymentLink(anyString());

        reservation(77L, TableReservationStatus.CONFIRMED);
        when(provider.paymentLink("order-1")).thenReturn(Optional.of(new ExternalPaymentProvider.PaymentLink("https://pay.test/1", null)));

        assertThat(prompter.run()).isEqualTo(new GuestBillPaymentPrompter.Report(1, 1, 0, 0));
        verify(notifier).paymentLink(any(), eq("https://pay.test/1"));
        assertThat(bills.find(bill.id()).orElseThrow().paymentLinkSentAt()).isNotNull();
        assertThat(bills.historyOf(bill.id()).getLast().type()).isEqualTo(BillOperationType.PAYMENT_LINK_ISSUED);

        assertThat(prompter.run()).isEqualTo(GuestBillPaymentPrompter.Report.NOTHING);
        verify(provider, times(1)).paymentLink(anyString());
    }

    @Test
    void leavesABillAloneForAWhileWhenTheVenueHasNoPage() {
        GuestBill bill = issued(77L);
        reservation(77L, TableReservationStatus.CONFIRMED);
        when(provider.paymentLink("order-1")).thenReturn(Optional.empty());

        assertThat(prompter.run()).isEqualTo(new GuestBillPaymentPrompter.Report(1, 0, 0, 1));
        assertThat(prompter.run()).isEqualTo(new GuestBillPaymentPrompter.Report(1, 0, 1, 0));
        verify(provider, times(1)).paymentLink(anyString());
        assertThat(bills.historyOf(bill.id()).getLast())
                .extracting(BillOperation::type, BillOperation::ok, BillOperation::errorCode)
                .containsExactly(BillOperationType.PAYMENT_LINK_FAILED, false, "NO_LINK");

        clock.advance(GuestBillPaymentPrompter.RETRY_AFTER.plusSeconds(1));
        when(provider.paymentLink("order-1")).thenReturn(Optional.of(new ExternalPaymentProvider.PaymentLink("https://pay.test/1", null)));

        assertThat(prompter.run()).isEqualTo(new GuestBillPaymentPrompter.Report(1, 1, 0, 0));
    }

    @Test
    void aFailedCallIsJournalledAndRetriedLater() {
        GuestBill bill = issued(77L);
        reservation(77L, TableReservationStatus.CONFIRMED);
        when(provider.paymentLink("order-1")).thenThrow(new IllegalStateException("Saby is down"));

        assertThat(prompter.run()).isEqualTo(new GuestBillPaymentPrompter.Report(1, 0, 0, 1));
        assertThat(bills.historyOf(bill.id()).getLast().errorCode()).isEqualTo("IllegalStateException");
        assertThat(bills.find(bill.id()).orElseThrow().paymentLinkSentAt()).isNull();
    }

    @Test
    void doesNotMarkTheLinkSentWhenTheGuestCouldNotBeReached() {
        GuestBill bill = issued(77L);
        reservation(77L, TableReservationStatus.CONFIRMED);
        when(provider.paymentLink("order-1")).thenReturn(Optional.of(new ExternalPaymentProvider.PaymentLink("https://pay.test/1", null)));
        when(notifier.paymentLink(any(), anyString())).thenReturn(false);

        assertThat(prompter.run()).isEqualTo(new GuestBillPaymentPrompter.Report(1, 0, 1, 0));
        assertThat(bills.find(bill.id()).orElseThrow().paymentLinkSentAt()).isNull();
    }

    @Test
    void doesNothingWhileBillingOrTheVenuePaymentIsOff() {
        issued(77L);
        reservation(77L, TableReservationStatus.CONFIRMED);

        properties.setEnabled(false);
        assertThat(prompter.run()).isEqualTo(GuestBillPaymentPrompter.Report.NOTHING);

        properties.setEnabled(true);
        when(provider.enabled()).thenReturn(false);
        assertThat(prompter.run()).isEqualTo(GuestBillPaymentPrompter.Report.NOTHING);
        verify(provider, never()).paymentLink(anyString());
    }

    @Test
    void unconfirmedFirstFiftyBillsDoNotStarveTheNextConfirmedBill() {
        for (long id = 1; id <= 51; id++) {
            GuestBill bill = bills.open(new BillDraft(CHAT, CHAT, "AERIS", GuestBillKind.BUSINESS_LUNCH,
                    "DIRECT", id, "test", OrderEstimate.of(List.of(OrderEstimate.Line.priced("B", "Борщ", 1, 270))), NOW));
            bills.issued(bill.id(), "SABY", "order-" + id);
            reservation(id, id == 51 ? TableReservationStatus.CONFIRMED : TableReservationStatus.AWAITING_MANAGER_CONFIRMATION);
        }
        when(provider.paymentLink("order-51")).thenReturn(Optional.of(new ExternalPaymentProvider.PaymentLink("https://pay.test/51", null)));
        assertThat(prompter.run().sent()).isZero();
        assertThat(prompter.run().sent()).isEqualTo(1);
        verify(provider).paymentLink("order-51");
    }

    private GuestBill issued(long reservationId) {
        GuestBill bill = bills.open(new BillDraft(CHAT, CHAT, "AERIS", GuestBillKind.BUSINESS_LUNCH, "DIRECT", reservationId, "test",
                OrderEstimate.of(List.of(OrderEstimate.Line.priced("BORSCHT", "Борщ", 2, 270))), NOW.plus(Duration.ofHours(2))));
        return bills.issued(bill.id(), "SABY", "order-1");
    }

    private void reservation(long id, TableReservationStatus status) {
        TableReservationOrder order = mock(TableReservationOrder.class);
        when(order.status()).thenReturn(status);
        when(reservations.findOrder(id)).thenReturn(Optional.of(order));
    }

    /** A clock the test moves by hand. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
