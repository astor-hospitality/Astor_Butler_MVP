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
 * to leave a tip. The question goes out when the venue closes the order, or after the visit ends by the clock; with
 * {@code astor.review.moment=on-bill} it goes out earlier, at the bill (payment link or the hostess «Счёт» button),
 * and the close stays as the safety net. Words from the guest are not collected here; the feedback scenario does that.
 */
@Service
@Slf4j
public class VisitReviewService {

    static final int BATCH = 50;
    static final String TIP_PHRASE = "чаевые";
    private static final Pattern CALLBACK = Pattern.compile("^visit_review:(\\d{1,18}):(stars:([1-5])|thumb:(UP|DOWN)|tip)$");

    private final BillingProperties properties;
    private final GuestBillService bills;
    private final VisitReviewRepository reviews;
    private final GuestBillNotifier notifier;
    private final Clock clock;

    /** The guest journey ledger (spec 2026-10-13): optional so the constructor and its tests stay as they are. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private museon_online.astor_butler.domain.journey.JourneyEvents journeyEvents;

    public void setJourneyEvents(museon_online.astor_butler.domain.journey.JourneyEvents journeyEvents) {
        this.journeyEvents = journeyEvents;
    }

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

    /** Asks the guest, unless they were asked for this bill already or the bill is cancelled. */
    public Optional<VisitReview> prompt(GuestBill bill) {
        return prompt(bill, null);
    }

    /**
     * The same question, with a note in the journal about what triggered it before the order was closed:
     * the payment link or the hostess. Whoever asks first, the guest is asked once.
     *
     * @param trigger journal detail such as {@code trigger=payment-link}; null for the close and the clock, as before
     */
    public synchronized Optional<VisitReview> prompt(GuestBill bill, String trigger) {
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
        bills.reviewNoted(bill.id(), BillOperationType.REVIEW_PROMPTED, trigger);
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
            if (journeyEvents != null) {
                journeyEvents.onReservationId(bill.tableReservationId(), museon_online.astor_butler.domain.journey.VisitEventKind.REVIEW, museon_online.astor_butler.domain.journey.JourneyEvents.GUEST, "stars=" + review.stars());
            }
            bills.reviewNoted(billId, BillOperationType.REVIEW_RECEIVED, "stars=" + review.stars());
            notifier.reviewThanks(bill, review);
            return new CallbackResult(true, "Спасибо за оценку!", null);
        }
        if (matcher.group(4) != null) {
            VisitReview review = reviews.thumb(billId, VisitReview.Thumb.valueOf(matcher.group(4)));
            if (journeyEvents != null) {
                journeyEvents.onReservationId(bill.tableReservationId(), museon_online.astor_butler.domain.journey.VisitEventKind.REVIEW, museon_online.astor_butler.domain.journey.JourneyEvents.GUEST, "thumb=" + review.thumb());
            }
            bills.reviewNoted(billId, BillOperationType.REVIEW_RECEIVED, "thumb=" + review.thumb());
            notifier.reviewThanks(bill, review);
            return new CallbackResult(true, "Спасибо!", null);
        }
        reviews.tipRequested(billId);
        if (journeyEvents != null) {
            journeyEvents.onReservationId(bill.tableReservationId(), museon_online.astor_butler.domain.journey.VisitEventKind.TIP, museon_online.astor_butler.domain.journey.JourneyEvents.GUEST);
        }
        bills.reviewNoted(billId, BillOperationType.TIP_REQUESTED, null);
        return new CallbackResult(true, "Сейчас предложу, как оставить чаевые.", TIP_PHRASE);
    }

    /**
     * The guest's stars given through an agent instead of the buttons: recorded on the latest bill of the reservation
     * as the stars button records them, journey REVIEW event included. The guest's words are not kept here, as with
     * the buttons. False when the reservation has no bill, the bill is cancelled, or the visit was rated already. Works
     * for a bill without a chat (a Concierge booking); no thanks is sent, the agent answers.
     */
    public synchronized boolean submit(long reservationId, int stars, String text) {
        if (stars < 1 || stars > 5) {
            return false;
        }
        Optional<GuestBill> found = bills.findByReservation(reservationId);
        if (found.isEmpty() || found.get().status() == GuestBillStatus.CANCELLED) {
            return false;
        }
        GuestBill bill = found.get();
        Optional<VisitReview> existing = reviews.findByBill(bill.id());
        if (existing.isPresent() && (existing.get().stars() != null || existing.get().ratedAt() != null)) {
            return false;
        }
        if (existing.isEmpty()) {
            // A bill of a Concierge booking has no chat: the review row has none either.
            reviews.open(bill.id(), bill.chatId());
        }
        VisitReview review = reviews.rate(bill.id(), stars);
        if (journeyEvents != null) {
            journeyEvents.onReservationId(bill.tableReservationId(), museon_online.astor_butler.domain.journey.VisitEventKind.REVIEW, museon_online.astor_butler.domain.journey.JourneyEvents.GUEST, "stars=" + review.stars());
        }
        bills.reviewNoted(bill.id(), BillOperationType.REVIEW_RECEIVED, "stars=" + review.stars());
        return true;
    }

    public Optional<VisitReview> reviewOf(Long billId) {
        return billId == null ? Optional.empty() : reviews.findByBill(billId);
    }

    public List<VisitReview> reviewsOf(Long chatId, int limit) {
        return chatId == null ? List.of() : reviews.findByChatId(chatId, Math.max(1, Math.min(limit, 50)));
    }
}
