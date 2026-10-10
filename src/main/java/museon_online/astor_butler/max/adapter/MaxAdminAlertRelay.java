package museon_online.astor_butler.max.adapter;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.service.message.AdminAlert;
import museon_online.astor_butler.service.message.OutgoingMessage;
import museon_online.astor_butler.telegram.utils.TelegramBot;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

/**
 * Staff and ops stay in Telegram in phase 1: an admin alert raised while answering a MAX guest (fallback, manager
 * help) goes to the same Telegram admin chat as alerts from Telegram guests. The alert text already names the
 * channel (MAX) and the internal chat id. Nothing is sent when the Telegram bot is off or the relay is disabled.
 */
@Slf4j
public class MaxAdminAlertRelay {

    private final ObjectProvider<TelegramBot> telegramBot;
    private final boolean enabled;

    public MaxAdminAlertRelay(ObjectProvider<TelegramBot> telegramBot, boolean telegramEnabled, boolean relayEnabled) {
        this.telegramBot = telegramBot;
        this.enabled = telegramEnabled && relayEnabled;
    }

    public void forward(OutgoingMessage outgoing) {
        AdminAlert alert = outgoing == null ? null : outgoing.adminAlert();
        if (!enabled || alert == null || !alert.required() || alert.chatId() == null || alert.chatId().isBlank()) {
            return;
        }
        TelegramBot bot = telegramBot.getIfAvailable();
        if (bot == null) {
            return;
        }
        try {
            bot.execute(SendMessage.builder()
                    .chatId(alert.chatId())
                    .text(alert.text())
                    .parseMode("HTML")
                    .replyMarkup(keyboard(alert))
                    .build());
        } catch (Exception e) {
            log.warn("Admin alert from a MAX conversation was not delivered to Telegram: {}", e.getMessage());
        }
    }

    private InlineKeyboardMarkup keyboard(AdminAlert alert) {
        if (alert.buttons() == null || alert.buttons().isEmpty()) {
            return null;
        }
        return InlineKeyboardMarkup.builder()
                .keyboard(alert.buttons().stream()
                        .map(row -> row.stream()
                                .map(button -> InlineKeyboardButton.builder()
                                        .text(button.text())
                                        .callbackData(button.callbackData())
                                        .build())
                                .toList())
                        .toList())
                .build();
    }
}
