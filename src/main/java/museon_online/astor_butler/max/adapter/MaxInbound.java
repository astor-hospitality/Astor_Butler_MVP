package museon_online.astor_butler.max.adapter;

import java.util.Map;

/**
 * A MAX update reduced to what the guest pipeline needs, before the chat is bound to an internal chat id.
 *
 * @param eventKey     stable id of this event for the idempotency check (message mid, callback id, ...)
 * @param chatType     {@code dialog} for a one-to-one chat with the bot, {@code chat} for a group
 * @param text         what the FSM reads: the message text, {@code /start [payload]} for bot_started, or the
 *                     callback payload's text
 * @param callbackId   set for {@code message_callback}: the callback must be answered
 * @param payload      extra keys for {@code IncomingMessage.payload} (MAX ids, media kind, start payload)
 */
public record MaxInbound(
        String updateType,
        String eventKey,
        long chatId,
        Long userId,
        String chatType,
        String text,
        String contactPhone,
        String firstName,
        String lastName,
        String username,
        String locale,
        boolean fromBot,
        String callbackId,
        Map<String, Object> payload
) {
    public boolean dialog() {
        return "dialog".equalsIgnoreCase(chatType);
    }
}
