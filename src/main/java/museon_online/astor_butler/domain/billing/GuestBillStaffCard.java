package museon_online.astor_butler.domain.billing;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.booking.TableReservationCommand;
import museon_online.astor_butler.domain.booking.TableReservationNotificationService;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.journey.talk.TableRequestRouter;
import museon_online.astor_butler.telegram.utils.HostessTopicRouter;
import museon_online.astor_butler.telegram.utils.TelegramBot;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What the staff group hears about a guest's bill (live test at AERIS, 16.10): the card «Счёт · Стол 5 · Анна · гость
 * Яна» when the guest asks for it — in the bot, through the Butler's agent or through the Concierge — and one line
 * «Стол 5: счёт отменён · Анна» when the bill is cancelled.
 *
 * <p>The card goes to the hostess chat without a topic of its own, so {@link HostessTopicRouter} puts it in the bookings
 * topic ({@code TELEGRAM_HOSTESS_THREAD_ID}), where table requests go as well. It carries the hostess «Счёт» button (the
 * review at the bill, {@link HostessBillRequestService}) and «Отменить счёт» while the bill is live. The cancellation
 * line goes to the wishes topic ({@code TELEGRAM_WISHES_THREAD_ID}); without that topic it goes where the card went.
 * The waiter is the one assigned to the table for the day ({@code table_waiter_assignments}), when there is one.
 */
@Component
@Slf4j
public class GuestBillStaffCard {

    public static final String CANCEL_CALLBACK_PREFIX = "table_booking:bill-cancel:";

    private final ObjectProvider<TelegramBot> telegramBotProvider;
    private final boolean enabled;
    private final String hostessChatId;
    private final Integer wishesThreadId;

    /** The table's waiter for the day: optional so the card still goes out without the roster. */
    private TableRequestRouter waiters;

    @Autowired
    public GuestBillStaffCard(ObjectProvider<TelegramBot> telegramBotProvider,
                              @Value("${telegram.bot.enabled:false}") boolean telegramEnabled,
                              @Value("${telegram.booking.notifications-enabled:true}") boolean notificationsEnabled,
                              @Value("${telegram.booking.hostess-chat-id:}") String hostessChatId,
                              @Value("${telegram.booking.wishes-thread-id:}") String wishesThreadId) {
        this.telegramBotProvider = telegramBotProvider;
        this.hostessChatId = hostessChatId == null ? "" : hostessChatId.trim();
        this.wishesThreadId = HostessTopicRouter.threadId(wishesThreadId, "TELEGRAM_WISHES_THREAD_ID");
        this.enabled = telegramEnabled && notificationsEnabled && !this.hostessChatId.isEmpty();
    }

    @Autowired(required = false)
    public void setWaiters(TableRequestRouter waiters) {
        this.waiters = waiters;
    }

    /** The «стол N просит счёт» callback of the «Отменить счёт» button for a reservation. */
    public static String cancelCallbackData(long reservationId) {
        return CANCEL_CALLBACK_PREFIX + reservationId;
    }

    /**
     * Posts the card that the guest of the reservation asked for the bill. Never throws.
     *
     * @param bill the reservation's bill when Butler has one; its venue amount goes on the card, and the buttons only
     *             while it is live
     * @return whether the card left Butler
     */
    public boolean billAsked(TableReservationOrder order, GuestBill bill) {
        if (order == null || order.id() == null) {
            return false;
        }
        String chat = chatOf(order);
        if (!enabled || chat.isEmpty()) {
            log.info("Bill card for reservation {} not sent: the staff chat is not configured or Telegram is off", order.id());
            return false;
        }
        Optional<Integer> sent = send(chat, null, billAskedText(order, bill, waiterOf(order)), buttons(order, bill));
        sent.ifPresent(id -> log.info("Bill card for reservation {} sent to the staff chat: message {}", order.id(), id));
        return sent.isPresent();
    }

    /** Posts «Стол N: счёт отменён · waiter» to the wishes topic. Never throws; returns whether it left Butler. */
    public boolean billCancelled(TableReservationOrder order) {
        if (order == null || order.id() == null) {
            return false;
        }
        String chat = chatOf(order);
        if (!enabled || chat.isEmpty()) {
            return false;
        }
        Integer thread = chat.equals(hostessChatId) ? wishesThreadId : null;
        return send(chat, thread, billCancelledText(order, waiterOf(order)), null).isPresent();
    }

    static String billAskedText(TableReservationOrder order, GuestBill bill, String waiter) {
        StringBuilder text = new StringBuilder();
        text.append("💳 <b>Счёт · ").append(escape(table(order))).append(" · ")
                .append(escape(waiter == null || waiter.isBlank() ? "официант не назначен" : waiter))
                .append(" · гость ").append(escape(blank(order.guestName(), "без имени"))).append("</b>\n\n");
        text.append("Бронь #").append(order.id()).append(" · ").append(where(order)).append('\n');
        if (bill != null && bill.venueAmountMinor() != null) {
            text.append("В Saby: ").append(GuestBillNotifier.rubles(bill.venueAmountMinor())).append('\n');
        } else {
            text.append("Сумму гостю не называли: она в Saby.\n");
        }
        text.append("\nПринесите счёт к столу.");
        return text.toString();
    }

    static String billCancelledText(TableReservationOrder order, String waiter) {
        String line = table(order) + ": счёт отменён";
        return escape(waiter == null || waiter.isBlank() ? line : line + " · " + waiter);
    }

    private static String where(TableReservationOrder order) {
        if (TableReservationCommand.CHANNEL_CONCIERGE.equals(order.source()) || order.chatId() == null) {
            return "гость попросил в Консьерже";
        }
        return "гость попросил в боте";
    }

    private static InlineKeyboardMarkup buttons(TableReservationOrder order, GuestBill bill) {
        if (bill == null || bill.status() == GuestBillStatus.CANCELLED) {
            return null;
        }
        List<InlineKeyboardButton> row = new ArrayList<>();
        row.add(InlineKeyboardButton.builder().text("Счёт").callbackData(HostessBillRequestService.callbackData(order.id())).build());
        if (bill.status() != GuestBillStatus.PAID) {
            row.add(InlineKeyboardButton.builder().text("Отменить счёт").callbackData(cancelCallbackData(order.id())).build());
        }
        return InlineKeyboardMarkup.builder().keyboard(List.of(row)).build();
    }

    private String waiterOf(TableReservationOrder order) {
        if (waiters == null) {
            return null;
        }
        try {
            return waiters.waiterName(order).orElse(null);
        } catch (RuntimeException e) {
            log.warn("Waiter of reservation {} not looked up: {}", order.id(), e.getClass().getSimpleName());
            return null;
        }
    }

    private String chatOf(TableReservationOrder order) {
        return order.hostessChatId() == null || order.hostessChatId().isBlank() ? hostessChatId : order.hostessChatId().trim();
    }

    private Optional<Integer> send(String chat, Integer thread, String text, InlineKeyboardMarkup keyboard) {
        try {
            TelegramBot bot = telegramBotProvider.getIfAvailable();
            if (bot == null) {
                return Optional.empty();
            }
            SendMessage.SendMessageBuilder builder = SendMessage.builder()
                    .chatId(chat)
                    .text(text)
                    .parseMode("HTML")
                    .disableWebPagePreview(true)
                    .replyMarkup(keyboard);
            if (thread != null) {
                builder.messageThreadId(thread);
            }
            Message sent = bot.execute(builder.build());
            return Optional.of(sent == null || sent.getMessageId() == null ? 0 : sent.getMessageId());
        } catch (Exception e) {
            log.warn("Bill note for the staff chat was not sent: reason={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static String table(TableReservationOrder order) {
        return order.tableCode() == null ? "стол не выбран" : TableReservationNotificationService.tableName(order);
    }

    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
