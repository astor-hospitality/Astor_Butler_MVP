package museon_online.astor_butler.i18n;

import museon_online.astor_butler.service.message.IncomingMessage;

/**
 * Identifies one guest conversation for language preferences: the channel plus the chat. Kept apart from the
 * Telegram user id so that web sessions and other messengers fit the same table.
 *
 * @param channel {@code TELEGRAM}, {@code WEB}, ...
 * @param chatId  the chat the bot answers into
 */
public record GuestLanguageKey(String channel, Long chatId) {

    public static GuestLanguageKey of(IncomingMessage incoming) {
        if (incoming == null) {
            return new GuestLanguageKey("", null);
        }
        return new GuestLanguageKey(incoming.channel() == null ? "" : incoming.channel().name(), incoming.chatId());
    }

    public boolean known() {
        return chatId != null && channel != null && !channel.isBlank();
    }
}
