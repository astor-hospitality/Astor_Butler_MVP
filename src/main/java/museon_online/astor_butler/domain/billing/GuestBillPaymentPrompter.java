package museon_online.astor_butler.domain.billing;

import museon_online.astor_butler.domain.shift.ButlerPauseService;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hands the guest the venue's payment page once their booking is confirmed: the bill is issued, the venue holds the
 * order, the table is theirs. Runs on a schedule, sends each link once, and leaves a bill alone for a while after
 * the venue could not give a link, so that a venue without acquiring does not fill the journal.
 * With {@code astor.review.moment=on-bill} the link is the bill moment: the guest is first asked how it went, then
 * handed the link; the question is asked once per visit, so the close of the order asks nothing more.
 */
@Service
@Slf4j
public class GuestBillPaymentPrompter {

    static final int BATCH = 50;
    static final Duration RETRY_AFTER = Duration.ofMinutes(10);
    static final String REVIEW_TRIGGER = "trigger=payment-link";

    private final BillingProperties properties;
    private final ReviewProperties reviewProperties;
    private final GuestBillService bills;
    private final TableReservationRepository reservations;
    private final List<ExternalPaymentProvider> providers;
    private final GuestBillNotifier notifier;
    private final VisitReviewService reviews;
    private final Clock clock;
    private final Map<Long, Instant> retryAfter = new ConcurrentHashMap<>();
    private long lastCheckedId;

    /** The shift manager's kill switch (#134): optional so the constructor and its tests stay as they are. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ButlerPauseService butlerPause;

    public record Report(int checked, int sent, int skipped, int failed) {
        static final Report NOTHING = new Report(0, 0, 0, 0);
    }

    @Autowired
    public GuestBillPaymentPrompter(BillingProperties properties, ReviewProperties reviewProperties, GuestBillService bills,
                                    TableReservationRepository reservations, List<ExternalPaymentProvider> providers,
                                    GuestBillNotifier notifier, VisitReviewService reviews) {
        this(properties, reviewProperties, bills, reservations, providers, notifier, reviews, Clock.systemUTC());
    }

    GuestBillPaymentPrompter(BillingProperties properties, ReviewProperties reviewProperties, GuestBillService bills,
                             TableReservationRepository reservations, List<ExternalPaymentProvider> providers,
                             GuestBillNotifier notifier, VisitReviewService reviews, Clock clock) {
        this.properties = properties;
        this.reviewProperties = reviewProperties;
        this.bills = bills;
        this.reservations = reservations;
        this.providers = providers;
        this.notifier = notifier;
        this.reviews = reviews;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${astor.billing.payment-prompt-delay-ms:30000}",
            initialDelayString = "${astor.booking.external-sync.initial-delay-ms:90000}"
    )
    public void scheduled() {
        Report report = run();
        if (report.checked() > 0) {
            log.info("Guest bill payment links: checked={}, sent={}, skipped={}, failed={}",
                    report.checked(), report.sent(), report.skipped(), report.failed());
        }
    }

    public synchronized Report run() {
        if (!properties.isEnabled()) {
            return Report.NOTHING;
        }
        if (butlerPause != null && butlerPause.paused()) {
            log.debug("Guest bill payment links skipped: Butler is paused by the shift manager");
            return Report.NOTHING;
        }
        Optional<ExternalPaymentProvider> provider = providers.stream().filter(ExternalPaymentProvider::enabled).findFirst();
        if (provider.isEmpty()) {
            return Report.NOTHING;
        }
        Instant now = clock.instant();
        int sent = 0;
        int skipped = 0;
        int failed = 0;
        List<GuestBill> waiting = bills.awaitingPaymentLinkAfter(lastCheckedId, BATCH);
        lastCheckedId = waiting.size() == BATCH ? waiting.getLast().id() : 0L;
        for (GuestBill bill : waiting) {
            // A Concierge guest's bill has no Butler chat to send the link to: the agent asks for it through
            // bill_request, so the venue is not asked for a link every ten minutes in vain — unless the booking
            // belongs to a Concierge session the webhook reaches (PAYMENT_LINK).
            if ((GuestBillNotifier.concierge(bill) && !notifier.reachesConcierge(bill)) || !provider.get().providerId().equals(bill.externalProvider()) || !confirmed(bill)
                    || retryAfter.getOrDefault(bill.id(), Instant.MIN).isAfter(now)) {
                skipped++;
                continue;
            }
            try {
                Optional<ExternalPaymentProvider.PaymentLink> link = provider.get().paymentLink(bill.externalOrderId());
                if (link.isEmpty()) {
                    bills.paymentLinkFailed(bill.id(), provider.get().providerId(), "NO_LINK");
                    retryAfter.put(bill.id(), now.plus(RETRY_AFTER));
                    failed++;
                    continue;
                }
                askHowItWentAtTheBill(bill);
                if (notifier.paymentLink(bill, link.get().url())) {
                    bills.paymentLinkSent(bill.id(), provider.get().providerId());
                    retryAfter.remove(bill.id());
                    sent++;
                } else {
                    // Telegram is off or the guest cannot be reached right now: the link is the venue's, ask again later.
                    retryAfter.put(bill.id(), now.plus(RETRY_AFTER));
                    skipped++;
                }
            } catch (RuntimeException e) {
                bills.paymentLinkFailed(bill.id(), provider.get().providerId(), e.getClass().getSimpleName());
                retryAfter.put(bill.id(), now.plus(RETRY_AFTER));
                failed++;
                // The class only: an answer's message may carry the link or the venue's token.
                log.warn("Payment link for bill {} was not had: {}", bill.id(), e.getClass().getSimpleName());
            }
        }
        return new Report(waiting.size(), sent, skipped, failed);
    }

    /**
     * The bill moment in the automatic path: stars first, the link right after. A question that could not be sent is
     * not retried here; the close of the order or the clock asks it later, and the link goes out regardless.
     */
    private void askHowItWentAtTheBill(GuestBill bill) {
        if (!reviewProperties.onBill()) {
            return;
        }
        try {
            reviews.prompt(bill, REVIEW_TRIGGER);
        } catch (RuntimeException e) {
            log.warn("Review at the bill was not asked for bill {}: {}", bill.id(), e.toString());
        }
    }

    /** A bill without a reservation is not waiting for anyone; one with a reservation waits for its confirmation. */
    private boolean confirmed(GuestBill bill) {
        if (bill.tableReservationId() == null) {
            return true;
        }
        return reservations.findOrder(bill.tableReservationId())
                .map(TableReservationOrder::status)
                .filter(status -> status == TableReservationStatus.CONFIRMED || status == TableReservationStatus.SEATED)
                .isPresent();
    }

    void setButlerPause(ButlerPauseService butlerPause) {
        this.butlerPause = butlerPause;
    }
}
