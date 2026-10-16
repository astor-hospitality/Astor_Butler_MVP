package museon_online.astor_butler.domain.booking;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.api.common.ApiException;
import museon_online.astor_butler.telegram.utils.TelegramBot;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * «🪑 Гость сел» on the confirmed hostess card (#151, semi-automatic mode): the hostess marks the moment the guest sat
 * down without leaving the staff chat. The press makes the booking SEATED with its time, the pressed card gets the
 * line «🪑 Гость сел 14:32» and loses the button («Счёт» and «✏️ Изменить» stay), the system chat gets one trace
 * line. A second press — on a stale copy of the card, or after Presto seated the guest first — only repeats the time
 * and cleans the card; nothing is stamped twice. Only the hostess chat may press, like the other buttons of the card;
 * the feature is off by default ({@code astor.booking.seated.enabled}).
 *
 * <p>The guest may mark the arrival too ({@link #guestArrived}, tool {@code guest_arrived}: «мы пришли»): within
 * {@link #ARRIVAL_EARLIEST} before and {@link #ARRIVAL_LATEST} after the booking time the booking becomes SEATED the
 * same way, and the hostess chat (bookings topic) gets «Гости брони #N на месте, стол T». Whoever is first — the
 * hostess, Presto or the guest — sets the time; the others only see it.
 */
@Service
@Slf4j
public class HostessSeatedService {

    public static final String CALLBACK_PREFIX = "table_booking:seated:";
    private static final Pattern CALLBACK = Pattern.compile("^table_booking:seated:(\\d{1,18})$");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Yekaterinburg"));

    private final BookingSeatedProperties properties;
    private final TableReservationService reservations;
    private final TableReservationNotificationService notifications;
    private final ObjectProvider<TelegramBot> telegramBotProvider;

    @Value("${telegram.booking.hostess-chat-id:}")
    private String hostessChatId;

    @Value("${telegram.bot.enabled:false}")
    private boolean telegramEnabled;

    @Value("${telegram.booking.notifications-enabled:true}")
    private boolean notificationsEnabled = true;

    public HostessSeatedService(BookingSeatedProperties properties,
                                TableReservationService reservations,
                                TableReservationNotificationService notifications,
                                ObjectProvider<TelegramBot> telegramBotProvider) {
        this.properties = properties;
        this.reservations = reservations;
        this.notifications = notifications;
        this.telegramBotProvider = telegramBotProvider;
    }

    public record CallbackResult(boolean handled, String answerText) {
        static final CallbackResult NOT_HANDLED = new CallbackResult(false, "");

        static CallbackResult handled(String answerText) {
            return new CallbackResult(true, answerText);
        }
    }

    /** How long before the booking time the guest's own «мы пришли» seats the booking. */
    public static final Duration ARRIVAL_EARLIEST = Duration.ofMinutes(60);
    /** How long after the booking time the guest's own «мы пришли» still seats the booking. */
    public static final Duration ARRIVAL_LATEST = Duration.ofMinutes(90);
    /** Who seated the booking, for the log and the system chat, when the guest said it. */
    public static final String GUEST_SELF = "гость (сам)";

    /** What the guest's «мы пришли» did to the booking. */
    public enum ArrivalOutcome {
        /** Seated now, the hostess was told. */
        SEATED,
        /** Seated before (by the hostess, Presto or an earlier «мы пришли»): the first time stands, nothing is sent. */
        ALREADY_SEATED,
        /** Not a confirmed booking: still awaiting the hostess, cancelled, refused or expired. */
        NOT_CONFIRMED,
        /** Too early or too late for the booking time; see the window. */
        OUTSIDE_WINDOW,
        /** The seated status is switched off ({@code ASTOR_BOOKING_SEATED_ENABLED}). */
        OFF
    }

    /** @param windowFrom, windowTo when «мы пришли» seats this booking; null without a booking time */
    public record Arrival(ArrivalOutcome outcome, TableReservationOrder order, Instant windowFrom, Instant windowTo) {
    }

    /**
     * The guest says they are at the venue: a confirmed booking within its arrival window becomes SEATED, as on
     * «🪑 Гость сел», and the hostess chat gets one line. Idempotent: an already seated booking keeps its time and the
     * hostess hears nothing twice. The caller has already checked that the booking is this guest's.
     */
    public Arrival guestArrived(long orderId, Instant now) {
        TableReservationOrder order = reservations.getReservation(orderId);
        Instant start = order.requestedStartAt();
        Instant from = start == null ? null : start.minus(ARRIVAL_EARLIEST);
        Instant to = start == null ? null : start.plus(ARRIVAL_LATEST);
        if (!properties.isEnabled()) {
            return new Arrival(ArrivalOutcome.OFF, order, from, to);
        }
        if (order.status() == TableReservationStatus.SEATED) {
            return new Arrival(ArrivalOutcome.ALREADY_SEATED, order, from, to);
        }
        if (order.status() != TableReservationStatus.CONFIRMED) {
            return new Arrival(ArrivalOutcome.NOT_CONFIRMED, order, from, to);
        }
        if (start == null || now == null || now.isBefore(from) || now.isAfter(to)) {
            return new Arrival(ArrivalOutcome.OUTSIDE_WINDOW, order, from, to);
        }
        TableReservationOrder seated;
        try {
            seated = reservations.seatByStaff(orderId, GUEST_SELF);
        } catch (ApiException e) {
            // Changed between the read and the write: a seating by someone else stands, anything else is not confirmed.
            TableReservationOrder again = reservations.getReservation(orderId);
            return new Arrival(again.status() == TableReservationStatus.SEATED
                    ? ArrivalOutcome.ALREADY_SEATED : ArrivalOutcome.NOT_CONFIRMED, again, from, to);
        }
        notifications.sendStaffNotice(hostessChat(seated), guestArrivedText(seated));
        log.info("Reservation {} seated on the guest's own word: table {}, seatedAt {}", orderId, seated.tableCode(), seated.seatedAt());
        return new Arrival(ArrivalOutcome.SEATED, seated, from, to);
    }

    /** «🪑 Гости брони #68 на месте, стол 4.», the line the hostess chat gets on the guest's «мы пришли». */
    static String guestArrivedText(TableReservationOrder order) {
        String table = order.tableCode() == null || order.tableCode().isBlank() ? "не выбран" : order.tableCode().trim();
        return "🪑 Гости брони #" + order.id() + " на месте, стол " + escape(table) + ". Отметил гость сам в "
                + time(order) + ".";
    }

    private String hostessChat(TableReservationOrder order) {
        return order.hostessChatId() == null || order.hostessChatId().isBlank() ? hostessChatId : order.hostessChatId();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** The «🪑 Гость сел» button of a reservation, as the card builder puts it on the card. */
    public static String callbackData(Long orderId) {
        return CALLBACK_PREFIX + orderId;
    }

    /**
     * @param pressed  the message the button is on (the confirmed card); null when Telegram did not say
     * @param staffRef who pressed, see {@link HostessBookingEditService#staffRef}
     */
    public CallbackResult handleCallback(String callbackData, Long chatId, Message pressed, String staffRef) {
        if (callbackData == null) {
            return CallbackResult.NOT_HANDLED;
        }
        Matcher matcher = CALLBACK.matcher(callbackData.trim());
        if (!matcher.matches() || !isHostessChat(chatId)) {
            return CallbackResult.NOT_HANDLED;
        }
        long orderId = Long.parseLong(matcher.group(1));
        if (!properties.isEnabled()) {
            return CallbackResult.handled("Отметка «гость сел» выключена (ASTOR_BOOKING_SEATED_ENABLED)");
        }

        TableReservationOrder order;
        try {
            order = reservations.getReservation(orderId);
        } catch (ApiException e) {
            return CallbackResult.handled("Бронь #" + orderId + " не найдена");
        }
        if (order.status() == TableReservationStatus.SEATED) {
            // Already there — by an earlier press or by Presto: the card is brought up to date, nothing is stamped again.
            markCard(pressed, order, null);
            return CallbackResult.handled("Бронь #" + orderId + ": гость уже сел в " + time(order));
        }
        if (order.status() != TableReservationStatus.CONFIRMED) {
            return CallbackResult.handled("Бронь #" + orderId + " " + statusWord(order.status()) + " — отметить посадку нельзя");
        }

        String who = staffRef == null || staffRef.isBlank() ? "неизвестно" : staffRef;
        TableReservationOrder seated;
        try {
            seated = reservations.seatByStaff(orderId, who);
        } catch (ApiException e) {
            log.debug("Seating of reservation {} by {} was refused: {}", orderId, who, e.getMessage());
            return CallbackResult.handled("Бронь #" + orderId + ": отметить посадку не удалось, обновите карточку");
        }
        markCard(pressed, seated, who);
        return CallbackResult.handled("Бронь #" + orderId + ": гость сел в " + time(seated));
    }

    /**
     * The pressed card gets «🪑 Гость сел 14:32 — @dasha» under its text and loses the button. The text is edited with
     * its own entities, so the bold labels stay as they were; a card Telegram no longer lets the bot edit, or one it
     * did not hand over, only loses the button, and even that failure is not the hostess's problem.
     */
    private void markCard(Message pressed, TableReservationOrder order, String who) {
        if (!telegramEnabled || !notificationsEnabled || pressed == null || pressed.getMessageId() == null) {
            return;
        }
        TelegramBot bot = telegramBotProvider.getIfAvailable();
        if (bot == null) {
            return;
        }
        String chatId = pressed.getChat() == null || pressed.getChat().getId() == null
                ? hostessChatId
                : pressed.getChat().getId().toString();
        String line = TableReservationNotificationService.seatedLine(order, who);
        String text = pressed.getText();
        try {
            if (text != null && !text.isBlank() && !text.contains(line)) {
                List<MessageEntity> entities = pressed.getEntities();
                bot.execute(EditMessageText.builder()
                        .chatId(chatId)
                        .messageId(pressed.getMessageId())
                        .text(text + "\n\n" + line)
                        .entities(entities == null ? List.of() : entities)
                        .replyMarkup(notifications.seatedCardKeyboard(order.id()))
                        .build());
                return;
            }
            bot.execute(EditMessageReplyMarkup.builder()
                    .chatId(chatId)
                    .messageId(pressed.getMessageId())
                    .replyMarkup(notifications.seatedCardKeyboard(order.id()))
                    .build());
        } catch (Exception e) {
            log.debug("Hostess card was not marked as seated: chatId={}, messageId={}, reason={}",
                    chatId, pressed.getMessageId(), e.getMessage());
        }
    }

    private static String time(TableReservationOrder order) {
        return order.seatedAt() == null ? "—" : TIME.format(order.seatedAt());
    }

    private static String statusWord(TableReservationStatus status) {
        if (status == null) {
            return "в неизвестном статусе";
        }
        return switch (status) {
            case AWAITING_MANAGER_CONFIRMATION -> "ещё не подтверждена";
            case REJECTED -> "уже отклонена";
            case CANCELLED -> "уже отменена";
            case EXPIRED -> "истекла";
            default -> "в статусе " + status.name();
        };
    }

    private boolean isHostessChat(Long chatId) {
        return chatId != null && hostessChatId != null && !hostessChatId.isBlank() && hostessChatId.equals(chatId.toString());
    }

    void setHostessChatId(String hostessChatId) {
        this.hostessChatId = hostessChatId;
    }

    void setTelegramEnabled(boolean telegramEnabled) {
        this.telegramEnabled = telegramEnabled;
    }
}
