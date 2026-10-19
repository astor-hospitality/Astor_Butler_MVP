package museon_online.astor_butler.domain.billing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.integration.concierge.ConciergeEvent;
import museon_online.astor_butler.integration.concierge.ConciergeWebhook;
import museon_online.astor_butler.telegram.utils.TelegramBot;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the guest hears about their bill, in the calm voice of the rest of the bot: the venue's payment page,
 * the venue's word that it was paid, and the question how the visit went. Sends nothing when Telegram is off.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GuestBillNotifier {

    static final String CALLBACK_PREFIX = "visit_review:";
    /** The bill source of an order placed through the Concierge. */
    static final String CONCIERGE_SOURCE = "CONCIERGE";

    private final ObjectProvider<TelegramBot> telegramBotProvider;

    @Value("${telegram.bot.enabled:false}")
    private boolean telegramEnabled;

    @Value("${telegram.booking.notifications-enabled:true}")
    private boolean notificationsEnabled;

    /** The usefulness filter for bot-first messages (spec 2026-10-13): optional, allows everything while off. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private museon_online.astor_butler.domain.journey.UsefulnessPolicy usefulnessPolicy;

    public void setUsefulnessPolicy(museon_online.astor_butler.domain.journey.UsefulnessPolicy usefulnessPolicy) {
        this.usefulnessPolicy = usefulnessPolicy;
    }

    /** The Butler → Concierge webhook (negotiation plan, task 9): optional so the constructor and its tests stay as they are. */
    private ConciergeWebhook conciergeWebhook;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setConciergeWebhook(ConciergeWebhook conciergeWebhook) {
        this.conciergeWebhook = conciergeWebhook;
    }

    /**
     * A Concierge guest's bill: no chat of the Butler's bot, or ordered through the Concierge. Its payment link and review
     * question go only through the Concierge (webhook), never to a Butler bot chat the guest has not opened (16.10).
     */
    public static boolean concierge(GuestBill bill) {
        return bill != null && (bill.chatId() == null || CONCIERGE_SOURCE.equals(bill.source()));
    }

    /** A Concierge guest's bill whose booking belongs to a Concierge session the webhook reaches. */
    public boolean reachesConcierge(GuestBill bill) {
        return conciergeWebhook != null && concierge(bill) && bill.tableReservationId() != null
                && conciergeWebhook.reaches(bill.tableReservationId());
    }

    /**
     * The venue's payment page for the guest, on a scheduler thread: a Concierge guest gets PAYMENT_LINK through the
     * Concierge (delivered here, so the link is marked sent only once the Concierge took it), a bot guest a message with
     * «Оплатить».
     */
    public boolean paymentLink(GuestBill bill, String url) {
        if (concierge(bill)) {
            return conciergeWebhook != null && bill.tableReservationId() != null
                    && conciergeWebhook.announceNow(ConciergeEvent.PAYMENT_LINK, bill.tableReservationId(), null, billFacts(bill, url));
        }
        if (usefulnessPolicy != null && bill != null && !usefulnessPolicy.permits(bill.tableReservationId(), bill.chatId(), museon_online.astor_butler.domain.journey.ProactiveMessage.BILL_READY)) {
            return false;
        }
        return sendPaymentLink(bill, url);
    }

    /**
     * The same on a Telegram update or request thread, when the guest asked for the bill: the Concierge's PAYMENT_LINK
     * is only queued (delivered on the webhook's own thread); a bot guest asked for it, so it goes out at once.
     */
    public boolean paymentLinkQueued(GuestBill bill, String url) {
        if (bill == null || url == null || url.isBlank()) {
            return false;
        }
        if (concierge(bill)) {
            return conciergeWebhook != null && bill.tableReservationId() != null
                    && conciergeWebhook.announce(ConciergeEvent.PAYMENT_LINK, bill.tableReservationId(), null, billFacts(bill, url));
        }
        return sendPaymentLink(bill, url);
    }

    private boolean sendPaymentLink(GuestBill bill, String url) {
        // Only the venue's own sum (16.10: the guest was told Butler's estimate, 2230 ₽, while Saby had 2510 ₽).
        String amount = bill.venueAmountMinor() != null
                ? "<b>К оплате:</b> " + rubles(bill.venueAmountMinor())
                : "Сумму покажет страница оплаты ресторана.";
        String text = """
                <b>Счёт по вашему заказу</b>

                <b>Заказ:</b> #%s
                %s

                Оплатить можно заранее по ссылке ниже. Платёж принимает ресторан, мы только передаём ссылку.
                Если удобнее рассчитаться на месте, просто оставьте это сообщение без внимания.
                """.formatted(reference(bill), amount).strip();
        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboard(List.of(List.of(InlineKeyboardButton.builder().text("Оплатить").url(url).build())))
                .build();
        return send(bill.chatId(), text, keyboard, "guest-bill-payment-link");
    }

    public boolean paid(GuestBill bill) {
        if (concierge(bill)) {
            return false;
        }
        String text = """
                <b>Оплата получена</b>

                Ресторан подтвердил оплату заказа #%s. Спасибо, ждём вас.
                """.formatted(reference(bill)).strip();
        return send(bill.chatId(), text, null, "guest-bill-paid");
    }

    /**
     * The bot's answer to the guest's own «счёт» (#153 A2), sent here rather than through the FSM reply so that it
     * lands before the review question when the review is asked at the bill. Plain text, keyboards untouched.
     */
    public boolean billRequestReply(Long chatId, String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return send(chatId, text, null, "guest-bill-request-reply");
    }

    public boolean reviewPrompt(GuestBill bill) {
        if (concierge(bill)) {
            // A Concierge booking: the question goes only through the Concierge (REVIEW), answered with one digit there.
            if (conciergeWebhook == null || bill.tableReservationId() == null) {
                return false;
            }
            return conciergeWebhook.announceNow(ConciergeEvent.REVIEW, bill.tableReservationId(), null, reviewFacts(bill));
        }
        String text = """
                <b>Как прошёл обед?</b>

                Будем благодарны за оценку — это одно нажатие, и команда %s её увидит.
                """.formatted(bill.venueCode() == null ? "ресторана" : bill.venueCode()).strip();
        List<InlineKeyboardButton> stars = new ArrayList<>();
        for (int value = 1; value <= 5; value++) {
            stars.add(InlineKeyboardButton.builder().text("★".repeat(value)).callbackData(CALLBACK_PREFIX + bill.id() + ":stars:" + value).build());
        }
        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder().keyboard(List.of(stars)).build();
        return send(bill.chatId(), text, keyboard, "guest-visit-review-prompt");
    }

    public boolean reviewThanks(GuestBill bill, VisitReview review) {
        if (concierge(bill)) {
            return false;
        }
        boolean askThumb = review.thumb() == null;
        boolean offerTip = review.tipRequestedAt() == null;
        String text = askThumb
                ? "Спасибо! Одним словом: понравилось?"
                : "Спасибо, передали команде.";
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (askThumb) {
            rows.add(List.of(
                    InlineKeyboardButton.builder().text("👍").callbackData(CALLBACK_PREFIX + bill.id() + ":thumb:UP").build(),
                    InlineKeyboardButton.builder().text("👎").callbackData(CALLBACK_PREFIX + bill.id() + ":thumb:DOWN").build()));
        }
        if (offerTip) {
            rows.add(List.of(InlineKeyboardButton.builder().text("Оставить чаевые").callbackData(CALLBACK_PREFIX + bill.id() + ":tip").build()));
        }
        InlineKeyboardMarkup keyboard = rows.isEmpty() ? null : InlineKeyboardMarkup.builder().keyboard(rows).build();
        return send(bill.chatId(), text, keyboard, "guest-visit-review-thanks");
    }

    /**
     * The review question for a Telegram update or request thread: a Concierge bill's REVIEW is only queued (delivered on
     * the webhook's own thread); a Telegram bill is asked as by {@link #reviewPrompt}.
     */
    public boolean reviewPromptQueued(GuestBill bill) {
        if (concierge(bill)) {
            return conciergeWebhook != null && bill.tableReservationId() != null
                    && conciergeWebhook.announce(ConciergeEvent.REVIEW, bill.tableReservationId(), null, reviewFacts(bill));
        }
        return reviewPrompt(bill);
    }

    private static Map<String, Object> reviewFacts(GuestBill bill) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("billId", bill.id());
        facts.put("reservationId", bill.tableReservationId());
        return facts;
    }

    static Map<String, Object> billFacts(GuestBill bill, String url) {
        Map<String, Object> facts = new LinkedHashMap<>();
        // Only the venue's sum: Butler's estimate is never told to the guest as the bill.
        Long minor = bill.venueAmountMinor();
        if (minor != null) {
            facts.put("totalRub", minor % 100 == 0 ? (Object) (minor / 100) : BigDecimal.valueOf(minor, 2));
        }
        if (url != null && !url.isBlank()) {
            facts.put("paymentUrl", url);
        }
        facts.put("billId", bill.id());
        return facts;
    }

    static String rubles(long minor) {
        return minor / 100 + (minor % 100 == 0 ? "" : "," + String.format("%02d", minor % 100)) + " ₽";
    }

    private static String reference(GuestBill bill) {
        return bill.tableReservationId() != null ? bill.tableReservationId().toString() : "B" + bill.id();
    }

    private boolean send(Long chatId, String text, ReplyKeyboard keyboard, String target) {
        if (!telegramEnabled || !notificationsEnabled || chatId == null) {
            log.debug("Guest bill notification skipped: target={}, botEnabled={}, notificationsEnabled={}", target, telegramEnabled, notificationsEnabled);
            return false;
        }
        try {
            TelegramBot telegramBot = telegramBotProvider.getIfAvailable();
            if (telegramBot == null) {
                log.debug("Guest bill notification skipped: bot bean is not available");
                return false;
            }
            telegramBot.execute(SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(text)
                    .parseMode("HTML")
                    .replyMarkup(keyboard)
                    .build());
            return true;
        } catch (Exception e) {
            log.warn("Guest bill notification failed: target={}, reason={}", target, e.getMessage());
            return false;
        }
    }
}
