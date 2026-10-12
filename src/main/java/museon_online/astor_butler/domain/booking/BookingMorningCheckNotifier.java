package museon_online.astor_butler.domain.booking;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.telegram.utils.TelegramBot;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * What the guest and the hostess hear about the morning reconfirmation (#149), in the calm voice of the rest of
 * the bot: the question with three buttons, the thank-you, and one line for the staff chat. No phone numbers.
 * Sends nothing when Telegram is off; the caller decides what to record.
 */
@Service
@Slf4j
public class BookingMorningCheckNotifier {

    static final String CALLBACK_PREFIX = "morning_check:";
    static final String BUTTON_KEEP = "Всё в силе";
    static final String BUTTON_MOVE = "Перенести";
    static final String BUTTON_CANCEL = "Отменить";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final ObjectProvider<TelegramBot> telegramBotProvider;
    private final boolean telegramEnabled;
    private final boolean notificationsEnabled;
    private final String hostessChatId;

    public BookingMorningCheckNotifier(
            ObjectProvider<TelegramBot> telegramBotProvider,
            @Value("${telegram.bot.enabled:false}") boolean telegramEnabled,
            @Value("${telegram.booking.notifications-enabled:true}") boolean notificationsEnabled,
            @Value("${telegram.booking.hostess-chat-id:}") String hostessChatId) {
        this.telegramBotProvider = telegramBotProvider;
        this.telegramEnabled = telegramEnabled;
        this.notificationsEnabled = notificationsEnabled;
        this.hostessChatId = hostessChatId;
    }

    /** «Сегодня ждём вас в 13:00, 2 гостя. Всё в силе?» with the three buttons. @return whether the message left Butler */
    public boolean ask(TableReservationOrder order, LocalTime startTime) {
        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboard(List.of(List.of(
                        button(BUTTON_KEEP, order.id(), "keep"),
                        button(BUTTON_MOVE, order.id(), "move"),
                        button(BUTTON_CANCEL, order.id(), "cancel"))))
                .build();
        return send(chat(order.chatId()), askText(order, startTime), keyboard, "booking-morning-check");
    }

    public boolean thanks(TableReservationOrder order, LocalTime startTime) {
        return send(chat(order.chatId()), thanksText(startTime), null, "booking-morning-check-thanks");
    }

    /** One line for the staff chat, the same chat the hostess cards go to. */
    public boolean hostessReconfirmed(TableReservationOrder order, LocalTime startTime) {
        String chat = order.hostessChatId() == null || order.hostessChatId().isBlank() ? hostessChatId : order.hostessChatId();
        return send(chat, hostessText(order, startTime), null, "booking-morning-check-hostess");
    }

    static String askText(TableReservationOrder order, LocalTime startTime) {
        String guests = order.partySize() == null ? "" : ", " + guests(order.partySize());
        return "Сегодня ждём вас в " + TIME.format(startTime) + guests + ". Всё в силе?";
    }

    static String thanksText(LocalTime startTime) {
        return "Спасибо, ждём вас сегодня в " + TIME.format(startTime) + "! "
                + "Если планы изменятся, нажмите «Изменить / отменить» в главном меню.";
    }

    static String hostessText(TableReservationOrder order, LocalTime startTime) {
        StringBuilder line = new StringBuilder("✅ Гость подтвердил утром бронь #").append(order.id())
                .append(": сегодня ").append(TIME.format(startTime));
        if (order.partySize() != null) {
            line.append(", ").append(guests(order.partySize()));
        }
        line.append(", ").append(tableName(order));
        if (order.guestName() != null && !order.guestName().isBlank()) {
            line.append(" — ").append(order.guestName().trim());
        }
        return line.append('.').toString();
    }

    /** «1 гость», «2 гостя», «5 гостей», «21 гость» — the Russian plural by the last digits. */
    static String guests(int count) {
        int tail = count % 100;
        int digit = count % 10;
        String word;
        if (tail >= 11 && tail <= 14) {
            word = "гостей";
        } else if (digit == 1) {
            word = "гость";
        } else if (digit >= 2 && digit <= 4) {
            word = "гостя";
        } else {
            word = "гостей";
        }
        return count + " " + word;
    }

    private static String tableName(TableReservationOrder order) {
        if (order.tableCode() == null || order.tableCode().isBlank()) {
            return "стол не назначен";
        }
        return "стол " + order.tableCode().trim();
    }

    private static InlineKeyboardButton button(String text, Long reservationId, String action) {
        return InlineKeyboardButton.builder().text(text).callbackData(CALLBACK_PREFIX + reservationId + ":" + action).build();
    }

    private static String chat(Long chatId) {
        return chatId == null ? null : chatId.toString();
    }

    private boolean send(String chatId, String text, ReplyKeyboard keyboard, String target) {
        if (!telegramEnabled || !notificationsEnabled || chatId == null || chatId.isBlank()) {
            log.debug("Morning check notification skipped: target={}, botEnabled={}, notificationsEnabled={}, chatConfigured={}",
                    target, telegramEnabled, notificationsEnabled, chatId != null && !chatId.isBlank());
            return false;
        }
        try {
            TelegramBot telegramBot = telegramBotProvider.getIfAvailable();
            if (telegramBot == null) {
                log.debug("Morning check notification skipped: bot bean is not available");
                return false;
            }
            telegramBot.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .replyMarkup(keyboard)
                    .build());
            return true;
        } catch (Exception e) {
            log.warn("Morning check notification failed: target={}, reason={}", target, e.getMessage());
            return false;
        }
    }
}
