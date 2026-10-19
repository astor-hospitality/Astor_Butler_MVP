package museon_online.astor_butler.domain.lunch;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.fsm.scenario.BookingTimeProvider;
import museon_online.astor_butler.telegram.utils.HostessTopicRouter;
import museon_online.astor_butler.telegram.utils.TelegramBot;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The one card the staff group gets when a business lunch did not reach the venue's till (16.10, AERIS: Saby answered the
 * dishes' update with 500, the guest heard «Заказ оформлен», the kitchen got nothing): «Внести в кассу вручную · бронь #N ·
 * стол T · гость» with every dish and its quantity and the total, in the bookings topic
 * ({@code telegram.booking.hostess-thread-id}). Never on success. One card per order: the same dishes for the same
 * booking are not announced twice, however often the order is placed again; a card that could not be sent may be
 * tried again. No phone on the card. Telegram off or no staff chat: nothing is sent.
 */
@Service
@Slf4j
public class LunchManualEntryNotifier {

    public enum Outcome { SENT, ALREADY_SENT, OFF, FAILED }

    static final int REMEMBERED_ORDERS = 500;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM 'в' HH:mm").withZone(BookingTimeProvider.VENUE_ZONE);

    private final ObjectProvider<TelegramBot> telegramBotProvider;
    private final boolean enabled;
    private final String hostessChatId;
    private final Integer threadId;
    private final Map<String, Boolean> announced = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > REMEMBERED_ORDERS;
        }
    });

    @Autowired
    public LunchManualEntryNotifier(ObjectProvider<TelegramBot> telegramBotProvider,
                                    @Value("${telegram.bot.enabled:false}") boolean telegramEnabled,
                                    @Value("${telegram.booking.notifications-enabled:true}") boolean notificationsEnabled,
                                    @Value("${telegram.booking.hostess-chat-id:}") String hostessChatId,
                                    @Value("${telegram.booking.hostess-thread-id:}") String hostessThreadId) {
        this.telegramBotProvider = telegramBotProvider;
        this.hostessChatId = hostessChatId == null ? "" : hostessChatId.trim();
        this.threadId = HostessTopicRouter.threadId(hostessThreadId, "TELEGRAM_HOSTESS_THREAD_ID");
        this.enabled = telegramEnabled && notificationsEnabled && !this.hostessChatId.isEmpty();
    }

    /** Sends the card unless this very order was already announced. Never throws. */
    public Outcome manualEntry(BusinessLunchOrder order, ExternalLunchOrderProvider.Result external) {
        if (!enabled || order == null || order.dishes() == null || order.dishes().isEmpty()) {
            return Outcome.OFF;
        }
        String key = key(order);
        if (announced.putIfAbsent(key, Boolean.TRUE) != null) {
            log.info("Lunch manual-entry card already sent: reservation={}", order.tableReservationId());
            return Outcome.ALREADY_SENT;
        }
        try {
            TelegramBot bot = telegramBotProvider.getIfAvailable();
            if (bot == null) {
                announced.remove(key);
                return Outcome.OFF;
            }
            bot.execute(SendMessage.builder()
                    .chatId(hostessChatId)
                    .messageThreadId(threadId)
                    .text(text(order, external))
                    .parseMode("HTML")
                    .disableWebPagePreview(true)
                    .build());
            log.info("Lunch manual-entry card sent: reservation={}, status={}", order.tableReservationId(),
                    external == null ? null : external.status());
            return Outcome.SENT;
        } catch (Exception e) {
            announced.remove(key);
            log.warn("Lunch manual-entry card was not sent: reservation={}, reason={}", order.tableReservationId(), e.getClass().getSimpleName());
            return Outcome.FAILED;
        }
    }

    /** The order as the kitchen sees it: the booking, the set and every dish with its quantity. */
    static String key(BusinessLunchOrder order) {
        String dishes = order.dishes().stream()
                .map(item -> item.dishCode() + "×" + item.quantity())
                .sorted()
                .collect(Collectors.joining(","));
        return order.tableReservationId() + "|" + order.setCode() + "|" + order.guests() + "|" + dishes;
    }

    static String text(BusinessLunchOrder order, ExternalLunchOrderProvider.Result external) {
        StringBuilder text = new StringBuilder("✍️ <b>Внести в кассу вручную · бронь #").append(order.tableReservationId())
                .append(" · стол ").append(order.tableCode() == null || order.tableCode().isBlank() ? "не выбран" : escape(order.tableCode()))
                .append(" · ").append(order.guestName() == null || order.guestName().isBlank() ? "гость" : escape(order.guestName().trim()))
                .append("</b>\n");
        text.append("Бизнес-ланч");
        if (order.startAt() != null) {
            text.append(' ').append(DATE_TIME.format(order.startAt()));
        }
        text.append(" · гостей ").append(order.guests()).append('\n');
        if (order.setTitle() != null) {
            text.append("Комбо: ").append(order.guests()).append(" × «").append(escape(order.setTitle())).append('»');
            if (order.setPriceRub() != null) {
                text.append(", ").append(order.setPriceRub()).append(" ₽");
            }
            text.append('\n');
        }
        for (BusinessLunchOrder.Item item : order.dishes()) {
            text.append("• ").append(escape(item.dishTitle())).append(" × ").append(item.quantity()).append('\n');
        }
        text.append("<b>Итого:</b> ").append(order.totalRub() == null ? "по меню кассы" : order.totalRub() + " ₽").append('\n');
        if (order.wishes() != null && !order.wishes().isBlank()) {
            text.append(escape(BusinessLunchService.wishLine(order.wishes()))).append('\n');
        }
        text.append(reason(external));
        return text.toString();
    }

    private static String reason(ExternalLunchOrderProvider.Result external) {
        if (external == null || !external.attempted()) {
            return "Касса не подключена — кухня блюда не получила.";
        }
        String status = escape(external.status());
        if ("PROVIDER_RESULT_UNKNOWN".equals(external.status())) {
            return "Saby не подтвердил заказ (" + status + ") — проверьте бронь в Presto и внесите блюда, если их там нет.";
        }
        return "Saby не принял заказ (" + status + ") — кухня блюда не получила.";
    }

    private static String escape(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
