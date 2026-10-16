package museon_online.astor_butler.domain.journey.talk;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.booking.TableReservationNotificationService;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.ledger.PhoneMask;
import museon_online.astor_butler.telegram.utils.HostessTopicRouter;
import museon_online.astor_butler.telegram.utils.TelegramBot;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.util.Locale;

/**
 * A guest's table request (water, bread, the waiter, packing to take away) as a card in the wishes topic of the staff
 * group ({@code telegram.booking.wishes-thread-id}, AERIS «Пожелания»), addressed in its first line:
 * «Стол 5 · Даниил · воду». Nobody to address: «Стол 5 · команде · воду» and «нет официанта в зоне WINDOW — кто
 * возьмёт?». Like the wishes notes, never the guest's name or phone. Without the topic, the hostess chat or Telegram,
 * or when the send fails, the card goes the old way: the hostess chat's bookings topic.
 */
@Slf4j
public class TableRequestCards implements TableRequestRouter.StaffChat {

    private final ObjectProvider<TelegramBot> telegramBotProvider;
    private final TableReservationNotificationService fallback;
    private final boolean enabled;
    private final String hostessChatId;
    private final Integer threadId;

    public TableRequestCards(ObjectProvider<TelegramBot> telegramBotProvider, TableReservationNotificationService fallback,
                             boolean telegramEnabled, boolean notificationsEnabled, String hostessChatId, String wishesThreadId) {
        this.telegramBotProvider = telegramBotProvider;
        this.fallback = fallback;
        this.hostessChatId = hostessChatId == null ? "" : hostessChatId.trim();
        this.threadId = HostessTopicRouter.threadId(wishesThreadId, "TELEGRAM_WISHES_THREAD_ID");
        this.enabled = telegramEnabled && notificationsEnabled && !this.hostessChatId.isEmpty() && threadId != null;
    }

    @Override
    public void send(TableReservationOrder order, TableRequest request, String guestText, TableRequestRouter.Addressee to) {
        TableRequestRouter.Addressee addressee = to == null ? TableRequestRouter.Addressee.team(null) : to;
        if (toWishesTopic(order, request, guestText, addressee)) {
            return;
        }
        fallback.notifyStaffGuestRequest(order, request, guestText,
                addressee.known() ? addressee.waiterName() : nobody(addressee.zone()));
    }

    private boolean toWishesTopic(TableReservationOrder order, TableRequest request, String guestText,
                                  TableRequestRouter.Addressee to) {
        if (!enabled) {
            return false;
        }
        try {
            TelegramBot bot = telegramBotProvider.getIfAvailable();
            if (bot == null) {
                return false;
            }
            bot.execute(SendMessage.builder()
                    .chatId(hostessChatId)
                    .messageThreadId(threadId)
                    .text(text(order, request, guestText, to))
                    .parseMode("HTML")
                    .disableWebPagePreview(true)
                    .build());
            return true;
        } catch (Exception e) {
            log.warn("Table request card not posted to the wishes topic, sent to the bookings topic: booking={}, reason={}",
                    order == null ? null : order.id(), e.getClass().getSimpleName());
            return false;
        }
    }

    /** «🙋 Стол 5 · Даниил · воду», what the guest wrote when it says more than the request, the booking number. */
    static String text(TableReservationOrder order, TableRequest request, String guestText, TableRequestRouter.Addressee to) {
        StringBuilder text = new StringBuilder("🙋 <b>")
                .append(escape(table(order))).append(" · ")
                .append(escape(to.known() ? to.waiterName() : "команде")).append(" · ")
                .append(escape(request.what())).append("</b>");
        if (!to.known()) {
            text.append("\n⚠️ ").append(escape(nobody(to.zone())));
        }
        String said = guestText == null ? "" : guestText.strip();
        if (!said.isEmpty() && !said.equalsIgnoreCase(request.what())) {
            text.append("\nГость написал: «").append(escape(PhoneMask.mask(said))).append("»");
        }
        if (order.id() != null) {
            text.append("\nБронь #").append(order.id());
        }
        return text.toString();
    }

    /** «нет официанта в зоне WINDOW — кто возьмёт?»; without a zone, «официант не назначен — кто возьмёт?». */
    static String nobody(String zone) {
        return zone == null || zone.isBlank()
                ? "официант не назначен — кто возьмёт?"
                : "нет официанта в зоне " + zone + " — кто возьмёт?";
    }

    /** «Стол 5», «Барная стойка» for the counter, the booking's own table name when there is no code. */
    static String table(TableReservationOrder order) {
        String code = order.tableCode() == null ? "" : order.tableCode().strip();
        if (code.isEmpty()) {
            return "Стол " + TableReservationNotificationService.tableName(order);
        }
        return "BAR".equals(code.toUpperCase(Locale.ROOT)) ? "Барная стойка" : "Стол " + code;
    }

    private static String escape(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
