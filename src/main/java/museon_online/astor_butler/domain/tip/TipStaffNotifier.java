package museon_online.astor_butler.domain.tip;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.telegram.utils.TelegramBot;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.List;

/**
 * The waiters' card for a confirmed tip in the semi-automatic mode: who, which table, how much, and a button to mark
 * it received. It goes to the hostess chat, where the booking cards go, or to the manager's chat when there is none.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TipStaffNotifier {

    static final String CALLBACK_PREFIX = "tip_paid:";

    private final ObjectProvider<TelegramBot> telegramBotProvider;

    @Value("${telegram.bot.enabled:false}")
    private boolean telegramEnabled;

    @Value("${telegram.booking.notifications-enabled:true}")
    private boolean notificationsEnabled;

    @Value("${telegram.booking.hostess-chat-id:}")
    private String hostessChatId;

    @Value("${telegram.booking.manager-chat-id:}")
    private String managerChatId;

    /** @return whether the card left Butler */
    public boolean tipCard(TipOrder order, TipGuest guest, TipVisit visit) {
        String chatId = staffChatId();
        if (!telegramEnabled || !notificationsEnabled || chatId == null || order == null) {
            log.debug("Tip card skipped: botEnabled={}, notificationsEnabled={}, chatConfigured={}", telegramEnabled, notificationsEnabled, chatId != null);
            return false;
        }
        try {
            TelegramBot bot = telegramBotProvider.getIfAvailable();
            if (bot == null) {
                log.debug("Tip card skipped: bot bean is not available");
                return false;
            }
            bot.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(cardText(order, guest, visit))
                    .parseMode("HTML")
                    .replyMarkup(InlineKeyboardMarkup.builder()
                            .keyboard(List.of(List.of(InlineKeyboardButton.builder()
                                    .text("Чаевые получены")
                                    .callbackData(CALLBACK_PREFIX + order.id())
                                    .build())))
                            .build())
                    .build());
            return true;
        } catch (Exception e) {
            log.warn("Tip card for #{} was not sent: {}", order.id(), e.getMessage());
            return false;
        }
    }

    /** Only the chats the card can go to may mark a tip received. */
    public boolean isStaffChat(Long chatId) {
        if (chatId == null) {
            return false;
        }
        String id = chatId.toString();
        return id.equals(blankToNull(hostessChatId)) || id.equals(blankToNull(managerChatId));
    }

    static String cardText(TipOrder order, TipGuest guest, TipVisit visit) {
        StringBuilder text = new StringBuilder("<b>Чаевые от гостя</b>\n\n");
        text.append("<b>Гость:</b> ").append(guestLine(guest)).append("\n");
        if (visit != null && blankToNull(visit.table()) != null) {
            text.append("<b>Стол:</b> ").append(html(visit.table().trim()));
            if (visit.reservationId() != null) {
                text.append(", заявка #").append(visit.reservationId());
            }
            text.append("\n");
        } else if (visit != null && visit.reservationId() != null) {
            text.append("<b>Заявка:</b> #").append(visit.reservationId()).append("\n");
        }
        text.append("<b>Сумма:</b> ")
                .append(order.amountMinor() == null ? "—" : TipMoney.rubles(order.amountMinor()))
                .append(" ₽ — ")
                .append(html(recipient(order)))
                .append("\n\nПодойдите к гостю с QR или терминалом.");
        return text.toString();
    }

    private String staffChatId() {
        String hostess = blankToNull(hostessChatId);
        return hostess != null ? hostess : blankToNull(managerChatId);
    }

    /** The name with the username beside it, so the waiters can find the guest; whichever of them is known. */
    private static String guestLine(TipGuest guest) {
        String name = guest == null ? null : blankToNull(guest.name());
        String username = guest == null ? null : blankToNull(guest.username());
        if (name != null && username != null) {
            return html(name) + " (@" + html(username) + ")";
        }
        if (name != null) {
            return html(name);
        }
        return username != null ? "@" + html(username) : "имя не указано";
    }

    private static String recipient(TipOrder order) {
        String name = blankToNull(order.staffDisplayName());
        return name == null ? "Команда AERIS" : name;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String html(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
