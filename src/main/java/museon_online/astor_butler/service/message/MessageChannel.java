package museon_online.astor_butler.service.message;

public enum MessageChannel {
    TELEGRAM,
    WEB,
    INTERNAL,
    /** MAX messenger (VK); chatId is an internal id from messenger_chat_bindings, never the raw MAX chat id. */
    MAX
}
