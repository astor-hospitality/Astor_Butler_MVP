package museon_online.astor_butler.domain.billing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The hostess presses «Счёт» on the reservation card: the guest is asked how it went, once, from the hostess chat only. */
class HostessBillRequestServiceTest {

    private static final long CHAT = 1773317437L;
    private static final long HOSTESS_CHAT = -1001234567890L;
    private static final Instant NOW = Instant.parse("2026-10-13T08:30:00Z");

    private final BillingProperties properties = new BillingProperties();
    private final GuestBillNotifier notifier = mock(GuestBillNotifier.class);
    private GuestBillService bills;
    private VisitReviewService reviews;
    private HostessBillRequestService service;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = BillingTestDatabase.create();
        bills = new GuestBillService(new GuestBillRepository(jdbc), new BillOperationRepository(jdbc));
        reviews = new VisitReviewService(properties, bills, new VisitReviewRepository(jdbc), notifier,
                new GuestBillPaymentPrompterTest.MutableClock(NOW));
        service = new HostessBillRequestService(properties, bills, reviews);
        service.setHostessChatId(Long.toString(HOSTESS_CHAT));
        properties.setEnabled(true);
        when(notifier.reviewPrompt(any())).thenReturn(true);
    }

    @Test
    void theButtonAsksTheGuestOnceAndSaysSoTheSecondTime() {
        GuestBill bill = bill(77L);

        HostessBillRequestService.CallbackResult first = service.handleCallback("table_booking:bill:77", HOSTESS_CHAT);
        HostessBillRequestService.CallbackResult second = service.handleCallback("table_booking:bill:77", HOSTESS_CHAT);

        assertThat(first.handled()).isTrue();
        assertThat(first.answerText()).contains("#77").contains("отправлен");
        assertThat(second.handled()).isTrue();
        assertThat(second.answerText()).contains("уже");
        verify(notifier, times(1)).reviewPrompt(any());
        assertThat(reviews.reviewOf(bill.id())).isPresent();
        assertThat(bills.historyOf(bill.id())).extracting(BillOperation::type, BillOperation::details).containsExactly(
                tuple(BillOperationType.ESTIMATE_CREATED, "estimate=54000"),
                tuple(BillOperationType.REVIEW_PROMPTED, "trigger=hostess"));
    }

    @Test
    void theQuestionIsNotAskedAgainByTheClockOrAtTheCloseOnceTheHostessAskedIt() {
        GuestBill bill = bill(77L);
        service.handleCallback("table_booking:bill:77", HOSTESS_CHAT);

        assertThat(reviews.promptDueVisits()).isZero();
        assertThat(reviews.prompt(bill)).isPresent();
        verify(notifier, times(1)).reviewPrompt(any());
    }

    @Test
    void worksOnlyFromTheHostessChatAndOnlyForItsOwnButton() {
        bill(77L);

        assertThat(service.handleCallback("table_booking:bill:77", CHAT).handled()).isFalse();
        assertThat(service.handleCallback("table_booking:bill:77", null).handled()).isFalse();
        assertThat(service.handleCallback("table_booking:confirm:77", HOSTESS_CHAT).handled()).isFalse();
        assertThat(service.handleCallback("visit_review:1:stars:5", HOSTESS_CHAT).handled()).isFalse();
        assertThat(service.handleCallback(null, HOSTESS_CHAT).handled()).isFalse();
        service.setHostessChatId("");
        assertThat(service.handleCallback("table_booking:bill:77", HOSTESS_CHAT).handled()).isFalse();
        verify(notifier, never()).reviewPrompt(any());
    }

    @Test
    void tellsTheHostessWhenThereIsNoBillOrItIsCancelledOrBillingIsOff() {
        GuestBill cancelled = bills.cancel(bill(78L).id(), BillActor.GUEST, "no lunch");

        assertThat(service.handleCallback("table_booking:bill:99", HOSTESS_CHAT).answerText()).contains("#99").contains("счёта нет");
        assertThat(service.handleCallback("table_booking:bill:78", HOSTESS_CHAT).answerText()).contains("#78").contains("отменён");
        assertThat(reviews.reviewOf(cancelled.id())).isEmpty();

        properties.setEnabled(false);
        bill(77L);
        assertThat(service.handleCallback("table_booking:bill:77", HOSTESS_CHAT).answerText()).contains("выключен");
        verify(notifier, never()).reviewPrompt(any());
    }

    @Test
    void aQuestionTelegramDidNotDeliverIsReportedAndCanBePressedAgain() {
        GuestBill bill = bill(77L);
        when(notifier.reviewPrompt(any())).thenReturn(false, true);

        HostessBillRequestService.CallbackResult failed = service.handleCallback("table_booking:bill:77", HOSTESS_CHAT);
        HostessBillRequestService.CallbackResult retried = service.handleCallback("table_booking:bill:77", HOSTESS_CHAT);

        assertThat(failed.handled()).isTrue();
        assertThat(failed.answerText()).contains("не удалось");
        assertThat(retried.answerText()).contains("отправлен");
        assertThat(reviews.reviewOf(bill.id())).isPresent();
        verify(notifier, times(2)).reviewPrompt(any());
    }

    private GuestBill bill(long reservationId) {
        return bills.open(new BillDraft(CHAT, CHAT, "AERIS", GuestBillKind.BUSINESS_LUNCH, "DIRECT", reservationId, "test",
                OrderEstimate.of(List.of(OrderEstimate.Line.priced("BORSCHT", "Борщ", 2, 270))), NOW.plus(Duration.ofHours(1))));
    }
}
