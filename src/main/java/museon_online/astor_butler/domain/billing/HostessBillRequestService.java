package museon_online.astor_butler.domain.billing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The hostess presses «Счёт» on the reservation card when the guest asks for the bill: the guest is asked how the
 * visit went right there, at the table, instead of after leaving. The button works only from the hostess chat, like
 * the confirmation buttons, and the question goes out once per visit whoever triggers it. The same step, without
 * the button, serves the guest's own «счёт» in the bot (#153 A2, {@link GuestBillRequestService}): {@link #askReview}.
 */
@Service
@Slf4j
public class HostessBillRequestService {

    public static final String CALLBACK_PREFIX = "table_booking:bill:";
    static final String TRIGGER = "trigger=hostess";
    private static final Pattern CALLBACK = Pattern.compile("^table_booking:bill:(\\d{1,18})$");

    private final BillingProperties properties;
    private final GuestBillService bills;
    private final VisitReviewService reviews;

    @Value("${telegram.booking.hostess-chat-id:}")
    private String hostessChatId;

    public HostessBillRequestService(BillingProperties properties, GuestBillService bills, VisitReviewService reviews) {
        this.properties = properties;
        this.bills = bills;
        this.reviews = reviews;
    }

    public record CallbackResult(boolean handled, String answerText) {
        static final CallbackResult NOT_HANDLED = new CallbackResult(false, "");

        static CallbackResult handled(String answerText) {
            return new CallbackResult(true, answerText);
        }
    }

    /** What came of asking the guest of a reservation how it went; the caller words it for whoever asked. */
    public enum ReviewAsk {
        /** {@code ASTOR_BILLING_ENABLED} is off: there are no bills to hang a review on. */
        BILLING_OFF,
        /** No bill for the reservation: the lunch was not ordered through the bot. */
        NO_BILL,
        /** The bill is cancelled. */
        CANCELLED,
        /** The guest was asked for this bill already, by whoever was first. */
        ALREADY_ASKED,
        /** The question went out now. */
        SENT,
        /** Telegram did not take the question; nothing is recorded, so it can be tried again. */
        NOT_DELIVERED;

        public boolean asked() {
            return this == SENT || this == ALREADY_ASKED;
        }
    }

    /** The «Счёт» button for a reservation, as the card builder puts it on the card. */
    public static String callbackData(Long orderId) {
        return CALLBACK_PREFIX + orderId;
    }

    public CallbackResult handleCallback(String callbackData, Long chatId) {
        if (callbackData == null || !isHostessChat(chatId)) {
            return CallbackResult.NOT_HANDLED;
        }
        Matcher matcher = CALLBACK.matcher(callbackData.trim());
        if (!matcher.matches()) {
            return CallbackResult.NOT_HANDLED;
        }
        long orderId = Long.parseLong(matcher.group(1));
        return CallbackResult.handled(switch (askReview(orderId, TRIGGER)) {
            case BILLING_OFF -> "Счета выключены (ASTOR_BILLING_ENABLED), вопрос гостю не отправлен";
            case NO_BILL -> "По брони #" + orderId + " счёта нет: заказ ланча через бота не оформлялся";
            case CANCELLED -> "Счёт по брони #" + orderId + " отменён, вопрос гостю не отправлен";
            case ALREADY_ASKED -> "Гостю по брони #" + orderId + " вопрос об оценке уже задан";
            case SENT -> "Гостю по брони #" + orderId + " отправлен вопрос об оценке";
            case NOT_DELIVERED -> "Гостю по брони #" + orderId + " не удалось отправить вопрос: Telegram недоступен, попробуйте ещё раз";
        });
    }

    /**
     * Asks the guest of a reservation how it went, exactly as the hostess «Счёт» button does: only with billing on,
     * only for a live bill, and once per visit whoever asks first. Shared by the button and the guest's own «счёт».
     *
     * @param trigger the journal note, such as {@code trigger=hostess} or {@code trigger=guest}
     */
    public ReviewAsk askReview(long orderId, String trigger) {
        if (!properties.isEnabled()) {
            return ReviewAsk.BILLING_OFF;
        }
        Optional<GuestBill> found = bills.findByReservation(orderId);
        if (found.isEmpty()) {
            return ReviewAsk.NO_BILL;
        }
        GuestBill bill = found.get();
        if (bill.status() == GuestBillStatus.CANCELLED) {
            return ReviewAsk.CANCELLED;
        }
        if (reviews.reviewOf(bill.id()).isPresent()) {
            return ReviewAsk.ALREADY_ASKED;
        }
        if (reviews.prompt(bill, trigger).isPresent()) {
            return ReviewAsk.SENT;
        }
        log.warn("Review of bill {} (reservation {}) was asked for ({}), but the guest could not be reached", bill.id(), orderId, trigger);
        return ReviewAsk.NOT_DELIVERED;
    }

    /** The newest bill of the reservation, whatever its state. */
    public Optional<GuestBill> billOf(long orderId) {
        return bills.findByReservation(orderId);
    }

    void setHostessChatId(String hostessChatId) {
        this.hostessChatId = hostessChatId;
    }

    private boolean isHostessChat(Long chatId) {
        return chatId != null && hostessChatId != null && !hostessChatId.isBlank() && hostessChatId.equals(chatId.toString());
    }
}
