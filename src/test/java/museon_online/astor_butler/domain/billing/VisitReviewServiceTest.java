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
import static org.mockito.Mockito.verifyNoInteractions;

/** The guest is asked once per visit, answers with buttons, and only the guest who was asked can answer. */
class VisitReviewServiceTest {

    private static final long CHAT = 1773317437L;
    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    private final BillingProperties properties = new BillingProperties();
    private final GuestBillNotifier notifier = mock(GuestBillNotifier.class);
    private final GuestBillPaymentPrompterTest.MutableClock clock = new GuestBillPaymentPrompterTest.MutableClock(NOW);
    private GuestBillService bills;
    private VisitReviewService reviews;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = BillingTestDatabase.create();
        bills = new GuestBillService(new GuestBillRepository(jdbc), new BillOperationRepository(jdbc));
        reviews = new VisitReviewService(properties, bills, new VisitReviewRepository(jdbc), notifier, clock);
        properties.setEnabled(true);
        properties.setReviewDelayMinutes(60);
        properties.setReviewLookbackHours(24);
    }

    @Test
    void asksOncePerBillAndNotForACancelledOne() {
        GuestBill bill = bill(77L, NOW.minus(Duration.ofHours(2)));
        GuestBill cancelled = bills.cancel(bill(78L, NOW.minus(Duration.ofHours(2))).id(), BillActor.GUEST, "no lunch");

        assertThat(reviews.prompt(bill)).isPresent();
        assertThat(reviews.prompt(bill)).isPresent();
        assertThat(reviews.prompt(cancelled)).isEmpty();

        verify(notifier, times(1)).reviewPrompt(any());
        assertThat(bills.historyOf(bill.id())).extracting(BillOperation::type).containsExactly(BillOperationType.ESTIMATE_CREATED, BillOperationType.REVIEW_PROMPTED);
        assertThat(reviews.reviewsOf(CHAT, 10)).hasSize(1);
    }

    @Test
    void asksByTheClockOnlyAfterTheDelayAndWithinTheLookback() {
        bill(70L, NOW.minus(Duration.ofMinutes(30)));          // too fresh
        GuestBill due = bill(71L, NOW.minus(Duration.ofHours(3)));
        bill(72L, NOW.minus(Duration.ofHours(30)));            // too old to ask
        bill(73L, null);                                       // no known end: never by the clock

        assertThat(reviews.promptDueVisits()).isEqualTo(1);
        assertThat(reviews.reviewOf(due.id())).isPresent();
        assertThat(reviews.promptDueVisits()).isZero();

        clock.advance(Duration.ofMinutes(45));
        assertThat(reviews.promptDueVisits()).isEqualTo(1);

        properties.setEnabled(false);
        assertThat(reviews.promptDueVisits()).isZero();
    }

    @Test
    void takesStarsThenAThumbAndOffersTips() {
        GuestBill bill = bill(77L, NOW.minus(Duration.ofHours(2)));
        reviews.prompt(bill);

        VisitReviewService.CallbackResult stars = reviews.handleCallback("visit_review:" + bill.id() + ":stars:4", CHAT);
        VisitReviewService.CallbackResult thumb = reviews.handleCallback("visit_review:" + bill.id() + ":thumb:UP", CHAT);
        VisitReviewService.CallbackResult tip = reviews.handleCallback("visit_review:" + bill.id() + ":tip", CHAT);

        assertThat(stars.handled()).isTrue();
        assertThat(stars.guestText()).isNull();
        assertThat(thumb.handled()).isTrue();
        assertThat(tip.handled()).isTrue();
        assertThat(tip.guestText()).isEqualTo("чаевые");
        VisitReview review = reviews.reviewOf(bill.id()).orElseThrow();
        assertThat(review.stars()).isEqualTo(4);
        assertThat(review.thumb()).isEqualTo(VisitReview.Thumb.UP);
        assertThat(review.tipRequestedAt()).isNotNull();
        assertThat(review.ratedAt()).isNotNull();
        verify(notifier, times(2)).reviewThanks(any(), any());
        assertThat(bills.historyOf(bill.id())).extracting(BillOperation::type, BillOperation::details).containsExactly(
                tuple(BillOperationType.ESTIMATE_CREATED, "estimate=54000"),
                tuple(BillOperationType.REVIEW_PROMPTED, null),
                tuple(BillOperationType.REVIEW_RECEIVED, "stars=4"),
                tuple(BillOperationType.REVIEW_RECEIVED, "thumb=UP"),
                tuple(BillOperationType.TIP_REQUESTED, null));
    }

    @Test
    void answersOnlyTheChatThatWasAskedAndIgnoresOtherButtons() {
        GuestBill bill = bill(77L, NOW.minus(Duration.ofHours(2)));
        reviews.prompt(bill);

        VisitReviewService.CallbackResult stranger = reviews.handleCallback("visit_review:" + bill.id() + ":stars:5", CHAT + 1);
        VisitReviewService.CallbackResult unasked = reviews.handleCallback("visit_review:424242:stars:5", CHAT);

        assertThat(stranger.handled()).isTrue();
        assertThat(stranger.answerText()).contains("не актуальна");
        assertThat(unasked.handled()).isTrue();
        assertThat(reviews.reviewOf(bill.id()).orElseThrow().stars()).isNull();
        assertThat(reviews.handleCallback("table_booking:confirm:1", CHAT).handled()).isFalse();
        assertThat(reviews.handleCallback("visit_review:" + bill.id() + ":stars:9", CHAT).handled()).isFalse();
        assertThat(reviews.handleCallback(null, CHAT).handled()).isFalse();
        verify(notifier, never()).reviewThanks(any(), any());
    }

    @Test
    void writesNothingForABillWithoutAChat() {
        verifyNoInteractions(notifier);
        assertThat(reviews.prompt(null)).isEmpty();
    }

    private GuestBill bill(long reservationId, Instant visitEndsAt) {
        return bills.open(new BillDraft(CHAT, CHAT, "AERIS", GuestBillKind.BUSINESS_LUNCH, "DIRECT", reservationId, "test",
                OrderEstimate.of(List.of(OrderEstimate.Line.priced("BORSCHT", "Борщ", 2, 270))), visitEndsAt));
    }
}
