package museon_online.astor_butler.domain.billing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.telegram.utils.TelegramBot;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.ArrayList;
import java.util.List;

/**
 * What the guest hears about their bill, in the calm voice of the rest of the bot: the venue's payment page,
 * the venue's word that it was paid, and the question how the visit went. Sends nothing when Telegram is off.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GuestBillNotifier {

    static final String CALLBACK_PREFIX = "visit_review:";

    private final ObjectProvider<TelegramBot> telegramBotProvider;

    @Value("${telegram.bot.enabled:false}")
    private boolean telegramEnabled;

    @Value("${telegram.booking.notifications-enabled:true}")
    private boolean notificationsEnabled;

    /** @return whether the message left Butler */
    public boolean paymentLink(GuestBill bill, String url) {
        String amount = bill.venueAmountMinor() != null
                ? "<b>К оплате:</b> " + rubles(bill.venueAmountMinor())
                : bill.estimateMinor() != null
                ? "<b>Предварительно:</b> " + rubles(bill.estimateMinor()) + "\nИтоговую сумму покажет страница оплаты ресторана."
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
        String text = """
                <b>Оплата получена</b>

                Ресторан подтвердил оплату заказа #%s. Спасибо, ждём вас.
                """.formatted(reference(bill)).strip();
        return send(bill.chatId(), text, null, "guest-bill-paid");
    }

    public boolean reviewPrompt(GuestBill bill) {
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
