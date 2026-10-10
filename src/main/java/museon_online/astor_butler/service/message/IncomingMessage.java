package museon_online.astor_butler.service.message;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public record IncomingMessage(
        MessageChannel channel,
        String externalUserId,
        Long chatId,
        Long telegramUserId,
        Integer telegramMessageId,
        Integer telegramUpdateId,
        String text,
        String contactPhone,
        String firstName,
        String lastName,
        String username,
        String languageCode,
        Boolean bot,
        String correlationId,
        Instant receivedAt,
        Map<String, Object> payload
) {
    public static IncomingMessage telegram(
            Long chatId,
            Long telegramUserId,
            Integer telegramMessageId,
            Integer telegramUpdateId,
            String text,
            String contactPhone,
            String firstName,
            String lastName,
            String username,
            String languageCode,
            Boolean bot,
            String correlationId
    ) {
        return telegram(
                chatId,
                telegramUserId,
                telegramMessageId,
                telegramUpdateId,
                text,
                contactPhone,
                firstName,
                lastName,
                username,
                languageCode,
                bot,
                correlationId,
                Map.of()
        );
    }

    public static IncomingMessage telegram(
            Long chatId,
            Long telegramUserId,
            Integer telegramMessageId,
            Integer telegramUpdateId,
            String text,
            String contactPhone,
            String firstName,
            String lastName,
            String username,
            String languageCode,
            Boolean bot,
            String correlationId,
            Map<String, Object> payload
    ) {
        Map<String, Object> safePayload = payload == null ? Map.of() : Map.copyOf(new HashMap<>(payload));
        return new IncomingMessage(
                MessageChannel.TELEGRAM,
                telegramUserId == null ? null : telegramUserId.toString(),
                chatId,
                telegramUserId,
                telegramMessageId,
                telegramUpdateId,
                text,
                contactPhone,
                firstName,
                lastName,
                username,
                languageCode,
                bot,
                correlationId,
                Instant.now(),
                safePayload
        );
    }

    /**
     * A message from the MAX messenger. {@code internalChatId} comes from messenger_chat_bindings so the FSM key
     * cannot collide with a Telegram or web chat; the raw MAX ids travel as {@code externalUserId} and in the
     * payload ({@code maxChatId}, {@code maxUserId}, {@code maxMessageId}). The Telegram-only fields stay null, so
     * nothing Telegram-specific (profiles, telegram_messages, consent by Telegram id) ever sees a MAX id.
     */
    public static IncomingMessage max(
            Long internalChatId,
            Long maxUserId,
            String text,
            String contactPhone,
            String firstName,
            String lastName,
            String username,
            String languageCode,
            Boolean bot,
            String correlationId,
            Map<String, Object> payload
    ) {
        Map<String, Object> safePayload = payload == null ? Map.of() : Map.copyOf(new HashMap<>(payload));
        return new IncomingMessage(
                MessageChannel.MAX,
                maxUserId == null ? null : maxUserId.toString(),
                internalChatId,
                null,
                null,
                null,
                text,
                contactPhone,
                firstName,
                lastName,
                username,
                languageCode,
                bot,
                correlationId,
                Instant.now(),
                safePayload
        );
    }

    /**
     * True when a real messenger account stands behind the message (a Telegram user id or a MAX user id), as
     * opposed to the web chat or an internal call. The first-touch consent flow keys on this.
     */
    public boolean hasMessengerUser() {
        if (channel == MessageChannel.TELEGRAM) {
            return telegramUserId != null;
        }
        return channel == MessageChannel.MAX && externalUserId != null && !externalUserId.isBlank();
    }

    public IncomingMessage withTextAndPayload(String newText, Map<String, Object> newPayload) {
        return new IncomingMessage(
                channel,
                externalUserId,
                chatId,
                telegramUserId,
                telegramMessageId,
                telegramUpdateId,
                newText,
                contactPhone,
                firstName,
                lastName,
                username,
                languageCode,
                bot,
                correlationId,
                receivedAt,
                newPayload == null ? Map.of() : Map.copyOf(new HashMap<>(newPayload))
        );
    }
}
