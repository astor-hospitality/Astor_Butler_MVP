package museon_online.astor_butler.telegram.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.OutgoingMessage;
import museon_online.astor_butler.telegram.utils.TelegramBot;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramSystemNotifier {

    private final ObjectProvider<TelegramBot> telegramBotProvider;

    @Value("${telegram.bot.enabled:false}")
    private boolean telegramEnabled;

    @Value("${telegram.system.chat-id:}")
    private String systemChatId;

    @Value("${telegram.system.notifications-enabled:false}")
    private boolean notificationsEnabled;

    public void sendTransition(IncomingMessage incoming, BotState previousState, OutgoingMessage outgoing, boolean kafkaOutboxQueued) {
        if (!telegramEnabled || !notificationsEnabled || systemChatId == null || systemChatId.isBlank()) {
            return;
        }

        TelegramBot telegramBot = telegramBotProvider.getIfAvailable();
        if (telegramBot == null) {
            return;
        }

        try {
            telegramBot.execute(SendMessage.builder()
                    .chatId(systemChatId)
                    .text(systemText(incoming, previousState, outgoing, kafkaOutboxQueued))
                    .parseMode("HTML")
                    .build());
        } catch (Exception e) {
            log.warn("Telegram system notification failed: {}", e.getMessage());
        }
    }

    /**
     * What was said through the glasses, in the system chat like any other message: the staff member's
     * question, Astor's answer, and the photo when there was one. Guest-facing identifiers are not
     * invented here, and the caller is responsible for what it passes.
     */
    public boolean sendGlassesExchange(String staff, String stageCode, String question, String answer,
                                       byte[] photo, String photoName) {
        if (!telegramEnabled || !notificationsEnabled || systemChatId == null || systemChatId.isBlank()) {
            return false;
        }
        TelegramBot telegramBot = telegramBotProvider.getIfAvailable();
        if (telegramBot == null) {
            return false;
        }
        String caption = """
                <b>Astor Glass</b> · %s%s

                <b>Сотрудник</b>
                <blockquote>%s</blockquote>

                <b>Астор</b>
                <blockquote>%s</blockquote>
                """.formatted(
                html(blank(staff)),
                stageCode == null || stageCode.isBlank() ? "" : " · " + html(stageCode),
                html(blank(question)),
                html(blank(answer)));
        try {
            if (photo != null && photo.length > 0) {
                // Telegram captions are limited; a long answer goes as its own message after the photo.
                String short_ = caption.length() > 1000 ? caption.substring(0, 1000) : caption;
                telegramBot.execute(org.telegram.telegrambots.meta.api.methods.send.SendPhoto.builder()
                        .chatId(systemChatId)
                        .photo(new org.telegram.telegrambots.meta.api.objects.InputFile(
                                new java.io.ByteArrayInputStream(photo), photoName == null ? "astor-glass.jpg" : photoName))
                        .caption(short_)
                        .parseMode("HTML")
                        .build());
                if (caption.length() > 1000) {
                    telegramBot.execute(SendMessage.builder().chatId(systemChatId).text(caption).parseMode("HTML").build());
                }
            } else {
                telegramBot.execute(SendMessage.builder().chatId(systemChatId).text(caption).parseMode("HTML").build());
            }
            return true;
        } catch (Exception e) {
            log.warn("Telegram glasses exchange failed: {}", e.getMessage());
            return false;
        }
    }

    private String systemText(IncomingMessage incoming, BotState previousState, OutgoingMessage outgoing, boolean kafkaOutboxQueued) {
        return """
                <b>Astor Butler / system trace</b>
                %s

                <b>Диалог</b>
                %s
                chat %s / user %s%s

                <b>FSM</b>
                %s -> %s

                <b>Actions / Kafka</b>
                %s
                Kafka outbox: %s

                <b>Guest input</b>
                <blockquote>%s</blockquote>

                <b>App reply</b>
                <blockquote>%s</blockquote>

                <b>Correlation</b>
                %s
                """.formatted(
                html(displayName(incoming)),
                html(dialogTag(incoming)),
                html(text(incoming == null ? null : incoming.chatId())),
                html(text(incoming == null ? null : incoming.telegramUserId())),
                incoming == null || incoming.username() == null || incoming.username().isBlank()
                        ? ""
                        : " / @" + html(incoming.username()),
                html(text(previousState)),
                html(outgoing == null ? "" : outgoing.nextState()),
                html(outgoing == null || outgoing.actions() == null ? "" : String.join(", ", outgoing.actions())),
                kafkaOutboxQueued ? "queued USER_MESSAGE_RECEIVED" : "not queued / disabled / failed",
                html(blank(incoming == null ? null : incoming.text())),
                html(blank(outgoing == null ? null : outgoing.text())),
                html(blank(incoming == null ? null : incoming.correlationId()))
        );
    }

    private String displayName(IncomingMessage incoming) {
        if (incoming == null) {
            return "unknown guest";
        }
        String firstName = incoming.firstName() == null ? "" : incoming.firstName().trim();
        String lastName = incoming.lastName() == null ? "" : incoming.lastName().trim();
        String fullName = (firstName + " " + lastName).trim();
        if (!fullName.isBlank()) {
            return fullName;
        }
        if (incoming.username() != null && !incoming.username().isBlank()) {
            return "@" + incoming.username();
        }
        return "unknown guest";
    }

    private String dialogTag(IncomingMessage incoming) {
        if (incoming == null || incoming.channel() == null || incoming.chatId() == null) {
            return "#dialog_unknown";
        }
        String chat = incoming.chatId().toString().replace("-", "m");
        return "#dialog_" + incoming.channel().name().toLowerCase() + "_" + chat;
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? "(empty)" : value;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String html(String value) {
        return text(value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
