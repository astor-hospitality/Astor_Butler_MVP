package museon_online.astor_butler.domain.billing;

import museon_online.astor_butler.domain.booking.TableReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The guest's «отмените счёт» or the hostess «Отменить счёт»: an unpaid bill is cancelled, a paid one never. */
class GuestBillCancelServiceTest {

    private static final long CHAT = 1773317437L;
    private static final long HOSTESS_CHAT = -1001234567890L;
    private static final Instant NOW = Instant.parse("2026-10-16T08:30:00Z");

    private final BillingProperties properties = new BillingProperties();
    private final TableReservationRepository reservations = mock(TableReservationRepository.class);
    private final GuestBillStaffCard staffCard = mock(GuestBillStaffCard.class);
    private GuestBillService bills;
    private GuestBillCancelService service;
    private HostessBillRequestService hostess;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = BillingTestDatabase.create();
        bills = new GuestBillService(new GuestBillRepository(jdbc), new BillOperationRepository(jdbc));
        service = new GuestBillCancelService(properties, bills, reservations);
        service.setStaffCard(staffCard);
        VisitReviewService reviews = new VisitReviewService(properties, bills, new VisitReviewRepository(jdbc), mock(GuestBillNotifier.class),
                new GuestBillPaymentPrompterTest.MutableClock(NOW));
        hostess = new HostessBillRequestService(properties, bills, reviews);
        hostess.setHostessChatId(Long.toString(HOSTESS_CHAT));
        hostess.setCancellations(service);
        properties.setEnabled(true);
        when(reservations.findOrder(77L)).thenReturn(Optional.of(GuestBillStaffCardTest.order(77L, CHAT)));
    }

    @Test
    void anUnpaidBillIsCancelledOnceAndTheWishesTopicHearsIt() {
        GuestBill bill = bill(77L);

        GuestBillCancelService.Result first = service.cancelByReservation(77L, BillActor.GUEST, "trigger=guest");
        GuestBillCancelService.Result second = service.cancelByReservation(77L, BillActor.GUEST, "trigger=guest");

        assertThat(first).isEqualTo(new GuestBillCancelService.Result(GuestBillCancelService.Outcome.CANCELLED, bill.id()));
        assertThat(second.outcome()).isEqualTo(GuestBillCancelService.Outcome.ALREADY_CANCELLED);
        assertThat(bills.find(bill.id()).orElseThrow().status()).isEqualTo(GuestBillStatus.CANCELLED);
        assertThat(bills.historyOf(bill.id())).extracting(BillOperation::type).contains(BillOperationType.CANCELLED);
        verify(staffCard, times(1)).billCancelled(any());
    }

    @Test
    void aPaidBillIsNeverCancelled() {
        GuestBill bill = bill(77L);
        bills.issued(bill.id(), "SABY", "order-77");
        bills.venueReported("SABY", "order-77", BillPayState.PAID, 54000L);

        assertThat(service.cancelByReservation(77L, BillActor.GUEST, "trigger=guest").outcome()).isEqualTo(GuestBillCancelService.Outcome.PAID);
        assertThat(bills.find(bill.id()).orElseThrow().status()).isEqualTo(GuestBillStatus.PAID);
        verify(staffCard, never()).billCancelled(any());
    }

    @Test
    void noBillAndBillingOffAreSaidSo() {
        assertThat(service.cancelByReservation(78L, BillActor.GUEST, null).outcome()).isEqualTo(GuestBillCancelService.Outcome.NO_BILL);
        properties.setEnabled(false);
        assertThat(service.cancelByReservation(77L, BillActor.GUEST, null).outcome()).isEqualTo(GuestBillCancelService.Outcome.BILLING_OFF);
    }

    @Test
    void theHostessButtonCancelsFromTheHostessChatOnly() {
        GuestBill bill = bill(77L);

        assertThat(hostess.handleCallback("table_booking:bill-cancel:77", CHAT).handled()).isFalse();
        HostessBillRequestService.CallbackResult pressed = hostess.handleCallback("table_booking:bill-cancel:77", HOSTESS_CHAT);

        assertThat(pressed.handled()).isTrue();
        assertThat(pressed.answerText()).contains("#77").contains("отменён");
        assertThat(bills.find(bill.id()).orElseThrow().status()).isEqualTo(GuestBillStatus.CANCELLED);
        assertThat(bills.historyOf(bill.id())).filteredOn(op -> op.type() == BillOperationType.CANCELLED)
                .extracting(BillOperation::actor).containsExactly(BillActor.STAFF);
        assertThat(hostess.handleCallback("table_booking:bill-cancel:77", HOSTESS_CHAT).answerText()).contains("уже отменён");
    }

    @Test
    void theHostessIsToldAPaidBillGoesBackAtTheTill() {
        GuestBill bill = bill(77L);
        bills.issued(bill.id(), "SABY", "order-77");
        bills.venueReported("SABY", "order-77", BillPayState.PAID, 54000L);

        assertThat(hostess.handleCallback("table_booking:bill-cancel:77", HOSTESS_CHAT).answerText()).contains("оплачен").contains("касс");
    }

    private GuestBill bill(long reservationId) {
        return bills.open(new BillDraft(CHAT, CHAT, "AERIS", GuestBillKind.BUSINESS_LUNCH, "DIRECT", reservationId, "test",
                OrderEstimate.of(List.of(OrderEstimate.Line.priced("BORSCHT", "Борщ", 2, 270))), NOW.plus(Duration.ofHours(1))));
    }
}
