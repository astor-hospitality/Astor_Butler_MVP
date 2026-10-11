package museon_online.astor_butler.domain.billing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks the guest how the visit went, once per bill, and keeps the answer: stars, a thumb, and whether they asked
 * to leave a tip. The question goes out when the venue closes the order, or after the visit ends by the clock.
 * Words from the guest are not collected here: the optional comment after the stars goes through the feedback
 * scenario, and {@link #attachComment} ties it back to the rated visit.
 */
@Service
@Slf4j
public class VisitReviewService {

    static final int BATCH = 50;
    static final String TIP_PHRASE = "чаевые";
    /** The words of the feedback button: the comment button leads the guest into the feedback scenario. */
    static final String COMMENT_PHRASE = "Оставить отзыв";
    /** A comment counts for the visit only if the guest asked to write it this recently. */
    static final Duration COMMENT_WINDOW = Duration.ofHours(3);
    private static final String COMMENT_MARK = "comment=";
    private static final Pattern CALLBACK = Pattern.compile("^visit_review:(\\d{1,18}):(stars:([1-5])|thumb:(UP|DOWN)|tip|comment)$");

    private final BillingProperties properties;
    private final GuestBillService bills;
    private final VisitReviewRepository reviews;
    private final GuestBillNotifier notifier;
    private final Clock clock;

    @Autowired
    public VisitReviewService(BillingProperties properties, GuestBillService bills, VisitReviewRepository reviews, GuestBillNotifier notifier) {
        this(properties, bills, reviews, notifier, Clock.systemUTC());
    }

    VisitReviewService(BillingProperties properties, GuestBillService bills, VisitReviewRepository reviews, GuestBillNotifier notifier, Clock clock) {
        this.properties = properties;
        this.bills = bills;
        this.reviews = reviews;
        this.notifier = notifier;
        this.clock = clock;
    }

    /**
     * @param guestText what the guest would have typed, for the bot to answer as if they had; null when nothing follows
     */
    public record CallbackResult(boolean handled, String answerText, String guestText) {
        static final CallbackResult NOT_HANDLED = new CallbackResult(false, null, null);
    }

    /** The visit a guest's comment belongs to, for the team's card. */
    public record CommentLink(Long billId, Long tableReservationId, Integer stars) {
    }

    /** Asks the guest, unless they were asked for this bill already or the bill is cancelled. */
    public synchronized Optional<VisitReview> prompt(GuestBill bill) {
        if (bill == null || bill.chatId() == null || bill.status() == GuestBillStatus.CANCELLED) {
            return Optional.empty();
        }
        Optional<VisitReview> existing = reviews.findByBill(bill.id());
        if (existing.isPresent()) {
            return existing;
        }
        if (!notifier.reviewPrompt(bill)) {
            return Optional.empty();
        }
        VisitReview review = reviews.open(bill.id(), bill.chatId());
        bills.reviewNoted(bill.id(), BillOperationType.REVIEW_PROMPTED, null);
        return Optional.of(review);
    }

    /** Visits that ended long enough ago and were not asked about yet. */
    @Scheduled(
            fixedDelayString = "${astor.billing.payment-prompt-delay-ms:30000}",
            initialDelayString = "${astor.booking.external-sync.initial-delay-ms:90000}"
    )
    public void scheduled() {
        int prompted = promptDueVisits();
        if (prompted > 0) {
            log.info("Visit review prompts sent: {}", prompted);
        }
    }

    public int promptDueVisits() {
        if (!properties.isEnabled()) {
            return 0;
        }
        Instant now = clock.instant();
        Instant from = now.minus(Duration.ofHours(Math.max(1, properties.getReviewLookbackHours())));
        Instant to = now.minus(Duration.ofMinutes(Math.max(0, properties.getReviewDelayMinutes())));
        if (!from.isBefore(to)) {
            return 0;
        }
        int prompted = 0;
        for (GuestBill bill : bills.visitsEndedWithoutReview(from, to, BATCH)) {
            try {
                if (prompt(bill).isPresent()) {
                    prompted++;
                }
            } catch (RuntimeException e) {
                log.warn("Visit review prompt failed for bill {}: {}", bill.id(), e.toString());
            }
        }
        return prompted;
    }

    /** A press on one of the review buttons. Only the chat the question went to can answer it. */
    public CallbackResult handleCallback(String callbackData, Long chatId) {
        if (callbackData == null || chatId == null) {
            return CallbackResult.NOT_HANDLED;
        }
        Matcher matcher = CALLBACK.matcher(callbackData.trim());
        if (!matcher.matches()) {
            return CallbackResult.NOT_HANDLED;
        }
        long billId = Long.parseLong(matcher.group(1));
        Optional<VisitReview> found = reviews.findByBill(billId);
        if (found.isEmpty() || !chatId.equals(found.get().chatId())) {
            return new CallbackResult(true, "Эта оценка уже не актуальна.", null);
        }
        GuestBill bill = bills.find(billId).orElse(null);
        if (bill == null) {
            return new CallbackResult(true, "Эта оценка уже не актуальна.", null);
        }
        if (matcher.group(3) != null) {
            VisitReview review = reviews.rate(billId, Integer.parseInt(matcher.group(3)));
            bills.reviewNoted(billId, BillOperationType.REVIEW_RECEIVED, "stars=" + review.stars());
            notifier.reviewThanks(bill, review, !commentRequested(billId));
            return new CallbackResult(true, "Спасибо за оценку!", null);
        }
        if (matcher.group(4) != null) {
            VisitReview review = reviews.thumb(billId, VisitReview.Thumb.valueOf(matcher.group(4)));
            bills.reviewNoted(billId, BillOperationType.REVIEW_RECEIVED, "thumb=" + review.thumb());
            notifier.reviewThanks(bill, review, !commentRequested(billId));
            return new CallbackResult(true, "Спасибо!", null);
        }
        if ("comment".equals(matcher.group(2))) {
            bills.reviewNoted(billId, BillOperationType.COMMENT_REQUESTED, null);
            return new CallbackResult(true, "Напишите пару слов — передадим команде.", COMMENT_PHRASE);
        }
        reviews.tipRequested(billId);
        bills.reviewNoted(billId, BillOperationType.TIP_REQUESTED, null);
        return new CallbackResult(true, "Сейчас предложу, как оставить чаевые.", TIP_PHRASE);
    }

    /**
     * Ties feedback the guest has just written to the visit they rated, when they pressed "Добавить комментарий"
     * for it within {@link #COMMENT_WINDOW} and that comment is not tied yet. Notes the link in the bill's journal.
     *
     * @return the visit, or empty for feedback written for any other reason
     */
    public Optional<CommentLink> attachComment(Long chatId, Long feedbackId) {
        if (chatId == null || feedbackId == null) {
            return Optional.empty();
        }
        Instant since = clock.instant().minus(COMMENT_WINDOW);
        for (VisitReview review : reviews.findByChatId(chatId, 5)) {
            List<BillOperation> history = bills.historyOf(review.billId());
            Optional<BillOperation> asked = history.stream()
                    .filter(operation -> operation.type() == BillOperationType.COMMENT_REQUESTED)
                    .reduce((first, second) -> second);
            if (asked.isEmpty() || asked.get().occurredAt() == null || asked.get().occurredAt().isBefore(since)) {
                continue;
            }
            long askedId = asked.get().id();
            boolean answered = history.stream().anyMatch(operation -> operation.id() > askedId
                    && operation.type() == BillOperationType.REVIEW_RECEIVED
                    && operation.details() != null
                    && operation.details().startsWith(COMMENT_MARK));
            if (answered) {
                continue;
            }
            bills.reviewNoted(review.billId(), BillOperationType.REVIEW_RECEIVED, COMMENT_MARK + "feedback#" + feedbackId);
            Long reservationId = bills.find(review.billId()).map(GuestBill::tableReservationId).orElse(null);
            return Optional.of(new CommentLink(review.billId(), reservationId, review.stars()));
        }
        return Optional.empty();
    }

    private boolean commentRequested(long billId) {
        return bills.historyOf(billId).stream().anyMatch(operation -> operation.type() == BillOperationType.COMMENT_REQUESTED);
    }

    public Optional<VisitReview> reviewOf(Long billId) {
        return billId == null ? Optional.empty() : reviews.findByBill(billId);
    }

    public List<VisitReview> reviewsOf(Long chatId, int limit) {
        return chatId == null ? List.of() : reviews.findByChatId(chatId, Math.max(1, Math.min(limit, 50)));
    }
}
