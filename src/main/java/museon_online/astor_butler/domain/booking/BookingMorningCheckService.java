package museon_online.astor_butler.domain.booking;

import museon_online.astor_butler.domain.shift.ButlerPauseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The morning reconfirmation on the day of the visit (#149): instead of the hostess calling every guest at the start
 * of the shift, Butler asks «Всё в силе?» with the buttons «Всё в силе» / «Перенести» / «Отменить».
 *
 * {@link #run()} is called by the clock. Once the venue time has passed the configured send time, every reservation
 * of today that is CONFIRMED or awaiting the hostess, belongs to a Telegram dialog, starts no sooner than the lead
 * time and was booked before the send time gets the question once; the row in table_reservation_morning_checks is
 * written before the message leaves, so a restart, a second instance or a slow Telegram cannot ask twice. A delivery
 * that failed is recorded and not repeated: the hostess still sees the booking exactly as before.
 *
 * {@link #handleCallback(String, Long)} takes the button. «Всё в силе» stamps guest_reconfirmed_at, thanks the guest
 * and tells the staff chat in one line. «Перенести» and «Отменить» hand over to the existing change-and-cancel
 * dialogue by replaying the words the guest could have typed, so the moving and the cancelling (with its own explicit
 * «Отменить стол» step) stay in one place.
 */
@Service
@Slf4j
public class BookingMorningCheckService {

    static final int LIMIT = 500;
    /** Internal chat ids of the MAX (8e12..) and web (9e12..) channels; there is no Telegram delivery for them yet. */
    static final long TELEGRAM_CHAT_ID_LIMIT = 7_000_000_000_000L;
    static final String MOVE_PHRASE = "Перенести бронь";
    static final String CANCEL_PHRASE = "Отменить бронь";
    private static final Pattern CALLBACK = Pattern.compile("^morning_check:(\\d{1,12}):(keep|move|cancel)$");

    private final BookingMorningCheckProperties properties;
    private final TableReservationRepository reservations;
    private final BookingMorningCheckRepository checks;
    private final BookingMorningCheckNotifier notifier;
    private final Clock clock;

    /** The shift manager's kill switch (#134): optional so the constructor and its tests stay as they are. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ButlerPauseService butlerPause;

    public record Report(int checked, int sent, int skipped, int failed) {
        static final Report NOTHING = new Report(0, 0, 0, 0);
    }

    public record CallbackResult(boolean handled, String answerText, String guestText) {
        static final CallbackResult NOT_HANDLED = new CallbackResult(false, null, null);
    }

    /** The guest journey ledger (spec 2026-10-13): optional so the constructor and its tests stay as they are. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private museon_online.astor_butler.domain.journey.JourneyEvents journeyEvents;

    public void setJourneyEvents(museon_online.astor_butler.domain.journey.JourneyEvents journeyEvents) {
        this.journeyEvents = journeyEvents;
    }

    /** The Butler → Concierge webhook (negotiation plan, task 9): optional so the constructor and its tests stay as they are. */
    private museon_online.astor_butler.integration.concierge.ConciergeWebhook conciergeWebhook;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setConciergeWebhook(museon_online.astor_butler.integration.concierge.ConciergeWebhook conciergeWebhook) {
        this.conciergeWebhook = conciergeWebhook;
    }

    @Autowired
    public BookingMorningCheckService(BookingMorningCheckProperties properties, TableReservationRepository reservations,
                                      BookingMorningCheckRepository checks, BookingMorningCheckNotifier notifier) {
        this(properties, reservations, checks, notifier, Clock.systemUTC());
    }

    BookingMorningCheckService(BookingMorningCheckProperties properties, TableReservationRepository reservations,
                               BookingMorningCheckRepository checks, BookingMorningCheckNotifier notifier, Clock clock) {
        this.properties = properties;
        this.reservations = reservations;
        this.checks = checks;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** Asks today's guests once the venue clock has passed the send time; safe to call as often as the clock ticks. */
    public synchronized Report run() {
        if (!properties.isEnabled()) {
            return Report.NOTHING;
        }
        if (butlerPause != null && butlerPause.paused()) {
            log.debug("Morning booking checks skipped: Butler is paused by the shift manager");
            return Report.NOTHING;
        }
        ZoneId zone = properties.zone();
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, zone);
        Instant sendAt = today.atTime(properties.sendTime()).atZone(zone).toInstant();
        if (now.isBefore(sendAt)) {
            return Report.NOTHING;
        }
        Instant from = today.atStartOfDay(zone).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(zone).toInstant();
        List<TableReservationOrder> orders = reservations.findOrdersStartingBetween(properties.venueCode(), from, to, LIMIT);
        Set<Long> alreadyChecked = checks.findByVisitDate(today).stream()
                .map(BookingMorningCheck::reservationId)
                .collect(Collectors.toSet());
        int sent = 0;
        int skipped = 0;
        int failed = 0;
        for (TableReservationOrder order : orders) {
            if (alreadyChecked.contains(order.id())) {
                skipped++;
                continue;
            }
            Optional<String> reason = whyNotAsked(order, now, sendAt);
            if (reason.isPresent()) {
                log.debug("Morning check skipped: reservation={}, reason={}", order.id(), reason.get());
                skipped++;
                continue;
            }
            // A Concierge booking has no chat here (claimed with chat 0, which no button can answer): asked through the webhook.
            boolean viaConcierge = order.chatId() == null;
            if (!checks.claim(order.id(), viaConcierge ? 0L : order.chatId(), today)) {
                skipped++;
                continue;
            }
            try {
                boolean asked = viaConcierge
                        ? conciergeWebhook.announce(museon_online.astor_butler.integration.concierge.ConciergeEvent.MORNING, order)
                        : notifier.ask(order, startTime(order, zone));
                if (asked) {
                    checks.markSent(order.id(), now);
                    sent++;
                } else {
                    checks.markFailed(order.id(), "NOT_DELIVERED");
                    failed++;
                }
            } catch (RuntimeException e) {
                checks.markFailed(order.id(), e.getClass().getSimpleName());
                failed++;
                log.warn("Morning check for reservation {} was not sent: {}", order.id(), e.toString());
            }
        }
        return new Report(orders.size(), sent, skipped, failed);
    }

    private Optional<String> whyNotAsked(TableReservationOrder order, Instant now, Instant sendAt) {
        if (!active(order)) {
            return Optional.of("status " + order.status());
        }
        if (order.chatId() == null && !reachesConcierge(order)) {
            return Optional.of("no guest dialog");
        }
        if (order.chatId() != null && order.chatId() <= 0) {
            return Optional.of("no guest dialog");
        }
        if (order.chatId() != null && order.chatId() >= TELEGRAM_CHAT_ID_LIMIT) {
            return Optional.of("web/MAX chat without Telegram delivery");
        }
        if (order.requestedStartAt() == null) {
            return Optional.of("no start time");
        }
        if (order.requestedStartAt().isBefore(now.plus(properties.minLead()))) {
            return Optional.of("starts sooner than the lead time");
        }
        if (order.createdAt() != null && order.createdAt().isAfter(sendAt)) {
            return Optional.of("booked after the morning check time");
        }
        return Optional.empty();
    }

    private boolean reachesConcierge(TableReservationOrder order) {
        return conciergeWebhook != null && order.id() != null && conciergeWebhook.reaches(order.id());
    }

    /** The button the guest pressed under the morning question. Only the guest who was asked can answer. */
    public CallbackResult handleCallback(String callbackData, Long chatId) {
        if (callbackData == null || chatId == null) {
            return CallbackResult.NOT_HANDLED;
        }
        Matcher matcher = CALLBACK.matcher(callbackData);
        if (!matcher.matches()) {
            return CallbackResult.NOT_HANDLED;
        }
        long reservationId = Long.parseLong(matcher.group(1));
        String action = matcher.group(2);
        Optional<BookingMorningCheck> check = checks.find(reservationId);
        if (check.isEmpty() || !chatId.equals(check.get().chatId())) {
            return new CallbackResult(true, "Это сообщение уже не актуально.", null);
        }
        Optional<TableReservationOrder> order = reservations.findOrder(reservationId);
        if (order.isEmpty() || !active(order.get())) {
            return new CallbackResult(true, "Эта бронь уже не активна.", null);
        }
        Instant now = clock.instant();
        LocalTime startTime = startTime(order.get(), properties.zone());
        return switch (action) {
            case "keep" -> {
                if (check.get().reconfirmed()) {
                    yield new CallbackResult(true, "Спасибо, уже отметили. Ждём вас!", null);
                }
                checks.answer(reservationId, BookingMorningCheck.ANSWER_KEEP, now, true);
                if (journeyEvents != null) {
                    journeyEvents.onReservation(order.get(), museon_online.astor_butler.domain.journey.VisitEventKind.MORNING_CONFIRMED, museon_online.astor_butler.domain.journey.JourneyEvents.GUEST);
                }
                notifier.thanks(order.get(), startTime);
                notifier.hostessReconfirmed(order.get(), startTime);
                yield new CallbackResult(true, "Спасибо, ждём вас!", null);
            }
            case "move" -> {
                checks.answer(reservationId, BookingMorningCheck.ANSWER_MOVE, now, false);
                yield new CallbackResult(true, "Сейчас предложу, как перенести.", MOVE_PHRASE);
            }
            default -> {
                checks.answer(reservationId, BookingMorningCheck.ANSWER_CANCEL, now, false);
                yield new CallbackResult(true, "Сейчас уточню отмену.", CANCEL_PHRASE);
            }
        };
    }

    private static boolean active(TableReservationOrder order) {
        return order.status() == TableReservationStatus.CONFIRMED
                || order.status() == TableReservationStatus.AWAITING_MANAGER_CONFIRMATION;
    }

    private static LocalTime startTime(TableReservationOrder order, ZoneId zone) {
        return order.requestedStartAt() == null ? LocalTime.MIDNIGHT : LocalTime.ofInstant(order.requestedStartAt(), zone);
    }

    void setButlerPause(ButlerPauseService butlerPause) {
        this.butlerPause = butlerPause;
    }
}
