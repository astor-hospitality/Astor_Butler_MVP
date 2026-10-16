package museon_online.astor_butler.telegram.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.PartialBotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendAnimation;
import org.telegram.telegrambots.meta.api.methods.send.SendAudio;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.methods.send.SendVoice;
import org.telegram.telegrambots.meta.bots.AbsSender;

/**
 * The venue's staff group is a forum: everything the bot sends to the hostess chat without a topic of its own goes to
 * the bookings topic, {@code telegram.booking.hostess-thread-id}. Blank keeps General, as before topics. A message that
 * names its topic keeps it (the guest reviews topic, for one). Edits, pins and callback answers address a message, not a
 * topic, and are left as they are.
 */
@Component
@Slf4j
public class HostessTopicRouter {

    private final String hostessChatId;
    private final Integer threadId;

    public HostessTopicRouter(@Value("${telegram.booking.hostess-chat-id:}") String hostessChatId,
                              @Value("${telegram.booking.hostess-thread-id:}") String threadId) {
        this.hostessChatId = hostessChatId == null ? "" : hostessChatId.trim();
        this.threadId = threadId(threadId, "TELEGRAM_HOSTESS_THREAD_ID");
    }

    /** No topic routing: every message goes where its builder sent it. */
    public static HostessTopicRouter none() {
        return new HostessTopicRouter(null, null);
    }

    /**
     * A forum topic id from the settings: blank or not a positive number is no topic (a bad value is logged, the
     * message then goes to General instead of failing).
     */
    public static Integer threadId(String value, String setting) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int id = Integer.parseInt(value.trim());
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException ignored) {
            // falls through to the warning
        }
        log.warn("{} is not a positive topic id, ignored: '{}'", setting, value);
        return null;
    }

    /** The send with the bookings topic set when it goes to the hostess chat without one; the same object otherwise. */
    public <M extends PartialBotApiMethod<?>> M route(M method) {
        if (threadId == null || method == null) {
            return method;
        }
        if (method instanceof SendMessage send && routes(send.getChatId(), send.getMessageThreadId())) {
            send.setMessageThreadId(threadId);
        } else if (method instanceof SendPhoto send && routes(send.getChatId(), send.getMessageThreadId())) {
            send.setMessageThreadId(threadId);
        } else if (method instanceof SendDocument send && routes(send.getChatId(), send.getMessageThreadId())) {
            send.setMessageThreadId(threadId);
        } else if (method instanceof SendMediaGroup send && routes(send.getChatId(), send.getMessageThreadId())) {
            send.setMessageThreadId(threadId);
        } else if (method instanceof SendVideo send && routes(send.getChatId(), send.getMessageThreadId())) {
            send.setMessageThreadId(threadId);
        } else if (method instanceof SendVoice send && routes(send.getChatId(), send.getMessageThreadId())) {
            send.setMessageThreadId(threadId);
        } else if (method instanceof SendAudio send && routes(send.getChatId(), send.getMessageThreadId())) {
            send.setMessageThreadId(threadId);
        } else if (method instanceof SendAnimation send && routes(send.getChatId(), send.getMessageThreadId())) {
            send.setMessageThreadId(threadId);
        }
        return method;
    }

    /**
     * The same for a send that does not pass through {@link TelegramBot}'s overrides: the library's sync
     * {@code execute(SendPhoto)}, {@code execute(SendDocument)} and the other uploads are final. Routed when the sender
     * is the bot; a test double gets the method untouched.
     */
    public static <M extends PartialBotApiMethod<?>> M route(AbsSender sender, M method) {
        HostessTopicRouter topics = sender instanceof TelegramBot bot ? bot.topics() : null;
        return topics == null ? method : topics.route(method);
    }

    private boolean routes(String chatId, Integer explicitThreadId) {
        return explicitThreadId == null && chatId != null && !hostessChatId.isEmpty() && hostessChatId.equals(chatId.trim());
    }
}
