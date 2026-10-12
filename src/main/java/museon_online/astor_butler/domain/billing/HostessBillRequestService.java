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
 * the confirmation buttons, and the question goes out once per visit whoever triggers it.
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
        if (!properties.isEnabled()) {
            return CallbackResult.handled("Счета выключены (ASTOR_BILLING_ENABLED), вопрос гостю не отправлен");
        }
        Optional<GuestBill> found = bills.findByReservation(orderId);
        if (found.isEmpty()) {
            return CallbackResult.handled("По брони #" + orderId + " счёта нет: заказ ланча через бота не оформлялся");
        }
        GuestBill bill = found.get();
        if (bill.status() == GuestBillStatus.CANCELLED) {
            return CallbackResult.handled("Счёт по брони #" + orderId + " отменён, вопрос гостю не отправлен");
        }
        if (reviews.reviewOf(bill.id()).isPresent()) {
            return CallbackResult.handled("Гостю по брони #" + orderId + " вопрос об оценке уже задан");
        }
        if (reviews.prompt(bill, TRIGGER).isPresent()) {
            return CallbackResult.handled("Гостю по брони #" + orderId + " отправлен вопрос об оценке");
        }
        log.warn("Hostess asked for the review of bill {} (reservation {}), but the guest could not be reached", bill.id(), orderId);
        return CallbackResult.handled("Гостю по брони #" + orderId + " не удалось отправить вопрос: Telegram недоступен, попробуйте ещё раз");
    }

    void setHostessChatId(String hostessChatId) {
        this.hostessChatId = hostessChatId;
    }

    private boolean isHostessChat(Long chatId) {
        return chatId != null && hostessChatId != null && !hostessChatId.isBlank() && hostessChatId.equals(chatId.toString());
    }
}
