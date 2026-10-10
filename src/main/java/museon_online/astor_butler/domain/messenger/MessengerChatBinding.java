package museon_online.astor_butler.domain.messenger;

import museon_online.astor_butler.service.message.MessageChannel;

/**
 * One chat of a non-Telegram messenger as the rest of the system sees it.
 *
 * @param externalChatId the messenger's own chat id (MAX {@code chat_id}); used to send replies
 * @param externalUserId the messenger's user id of the guest in a dialog; null for group chats
 * @param internalChatId the FSM key: positive for a dialog, negative for a group chat
 */
public record MessengerChatBinding(
        MessageChannel channel,
        long externalChatId,
        Long externalUserId,
        String chatType,
        long internalChatId,
        String contactPhone
) {
}
