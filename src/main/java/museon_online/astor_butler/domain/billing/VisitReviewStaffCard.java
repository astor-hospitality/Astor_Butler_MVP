package museon_online.astor_butler.domain.billing;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.booking.TableReservationNotificationService;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.ledger.PhoneMask;
import museon_online.astor_butler.telegram.utils.HostessTopicRouter;
import museon_online.astor_butler.telegram.utils.TelegramBot;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The guest's review of the visit, for the team: a card in the reviews topic of the staff group
 * ({@code telegram.booking.feedback-thread-id}, AERIS «Обратная связь»). Stars, the thumb, the guest's words when an
 * agent passed them, the order, the table and the day of the visit; never the guest's phone, and the name as the
 * hostess card shows it. The thumb comes after the stars: it updates the same card instead of posting a second one.
 * Blank topic, blank hostess chat or Telegram off: nothing is published.
 */
@Service
@Slf4j
public class VisitReviewStaffCard {

    private static final ZoneId VENUE_ZONE = ZoneId.of("Asia/Yekaterinburg");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(VENUE_ZONE);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(VENUE_ZONE);
    /** Cards kept for the thumb that follows the stars; an older one (or one lost to a restart) is posted anew. */
    static final int REMEMBERED_CARDS = 500;

    private final ObjectProvider<TelegramBot> telegramBotProvider;
    private final TableReservationRepository reservations;
    private final boolean enabled;
    private final String hostessChatId;
    private final Integer threadId;
    private final Map<Long, Card> cards = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Card> eldest) {
            return size() > REMEMBERED_CARDS;
        }
    });

    @Autowired
    public VisitReviewStaffCard(ObjectProvider<TelegramBot> telegramBotProvider,
                                TableReservationRepository reservations,
                                @Value("${telegram.bot.enabled:false}") boolean telegramEnabled,
                                @Value("${telegram.booking.notifications-enabled:true}") boolean notificationsEnabled,
                                @Value("${telegram.booking.hostess-chat-id:}") String hostessChatId,
                                @Value("${telegram.booking.feedback-thread-id:}") String feedbackThreadId) {
        this.telegramBotProvider = telegramBotProvider;
        this.reservations = reservations;
        this.hostessChatId = hostessChatId == null ? "" : hostessChatId.trim();
        this.threadId = HostessTopicRouter.threadId(feedbackThreadId, "TELEGRAM_FEEDBACK_THREAD_ID");
        this.enabled = telegramEnabled && notificationsEnabled && !this.hostessChatId.isEmpty() && threadId != null;
    }

    private record Card(Integer messageId, String comment) {
    }

    /**
     * Posts the card for this review, or updates the one posted for the same bill. Never throws: the guest's answer is
     * recorded whatever happens to the card.
     *
     * @param comment the guest's words when an agent passed them; null keeps the words of an earlier card of the bill
     * @return whether the card reached Telegram
     */
    public boolean publish(GuestBill bill, VisitReview review, String comment) {
        if (!enabled || bill == null || review == null || bill.id() == null) {
            return false;
        }
        try {
            TelegramBot bot = telegramBotProvider.getIfAvailable();
            if (bot == null) {
                return false;
            }
            Card earlier = cards.get(bill.id());
            String words = comment != null && !comment.isBlank() ? comment.strip() : earlier == null ? null : earlier.comment();
            String text = text(bill, review, reservation(bill), words);
            if (earlier != null && earlier.messageId() != null && edited(bot, earlier.messageId(), text)) {
                cards.put(bill.id(), new Card(earlier.messageId(), words));
                return true;
            }
            Message sent = bot.execute(SendMessage.builder()
                    .chatId(hostessChatId)
                    .messageThreadId(threadId)
                    .text(text)
                    .parseMode("HTML")
                    .disableWebPagePreview(true)
                    .build());
            cards.put(bill.id(), new Card(sent == null ? null : sent.getMessageId(), words));
            return true;
        } catch (Exception e) {
            log.warn("Visit review card was not published: bill={}, reason={}", bill.id(), e.getClass().getSimpleName());
            return false;
        }
    }

    private boolean edited(TelegramBot bot, Integer messageId, String text) {
        try {
            bot.execute(EditMessageText.builder()
                    .chatId(hostessChatId)
                    .messageId(messageId)
                    .text(text)
                    .parseMode("HTML")
                    .disableWebPagePreview(true)
                    .build());
            return true;
        } catch (Exception e) {
            log.debug("Visit review card {} was not updated, posting anew: {}", messageId, e.getClass().getSimpleName());
            return false;
        }
    }

    private TableReservationOrder reservation(GuestBill bill) {
        if (bill.tableReservationId() == null || reservations == null) {
            return null;
        }
        try {
            return reservations.findOrder(bill.tableReservationId()).orElse(null);
        } catch (RuntimeException e) {
            log.debug("Visit review card without the booking {}: {}", bill.tableReservationId(), e.getClass().getSimpleName());
            return null;
        }
    }

    static String text(GuestBill bill, VisitReview review, TableReservationOrder order, String comment) {
        StringBuilder text = new StringBuilder("⭐ <b>Отзыв гостя</b>\n\n");
        text.append("<b>Оценка:</b> ").append(stars(review.stars())).append('\n');
        text.append("<b>Понравилось:</b> ").append(thumb(review.thumb())).append('\n');
        if (comment != null && !comment.isBlank()) {
            text.append("<b>Комментарий:</b>\n<blockquote>").append(escape(PhoneMask.mask(comment.strip()))).append("</blockquote>\n");
        }
        text.append('\n');
        text.append(bill.tableReservationId() != null
                ? "<b>Бронь:</b> #" + bill.tableReservationId()
                : "<b>Заказ:</b> #B" + bill.id()).append('\n');
        if (order != null) {
            text.append("<b>Гость:</b> ").append(escape(PhoneMask.mask(blank(order.guestName(), "гость из Telegram")))).append('\n');
            text.append("<b>Стол:</b> ").append(escape(TableReservationNotificationService.tableName(order))).append('\n');
        }
        text.append("<b>Дата визита:</b> ").append(visitDay(bill, order));
        return text.toString();
    }

    private static String stars(Integer stars) {
        if (stars == null || stars < 1 || stars > 5) {
            return "—";
        }
        return "★".repeat(stars) + "☆".repeat(5 - stars) + " " + stars + "/5";
    }

    private static String thumb(VisitReview.Thumb thumb) {
        if (thumb == null) {
            return "—";
        }
        return thumb == VisitReview.Thumb.UP ? "👍 да" : "👎 нет";
    }

    private static String visitDay(GuestBill bill, TableReservationOrder order) {
        if (order != null && order.requestedStartAt() != null) {
            return DATE_TIME.format(order.requestedStartAt());
        }
        Instant day = Optional.ofNullable(bill.visitEndsAt()).orElse(bill.createdAt());
        return day == null ? "не указана" : DATE.format(day);
    }

    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String escape(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
