package museon_online.astor_butler.telegram.utils;

import org.telegram.telegrambots.meta.api.methods.CopyMessage;
import org.telegram.telegrambots.meta.api.methods.ForwardMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendAnimation;
import org.telegram.telegrambots.meta.api.methods.send.SendContact;
import org.telegram.telegrambots.meta.api.methods.send.SendDice;
import org.telegram.telegrambots.meta.api.methods.send.SendLocation;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendVenue;

/**
 * The hostess group is a Telegram forum: without a message_thread_id every card lands in «General». When
 * telegram.booking.hostess-thread-id is a positive integer, a new message to the hostess chat that carries no thread
 * of its own is sent into that topic. Edits, callback answers and other chats are never touched; an empty setting
 * changes nothing.
 */
public final class HostessThreadRouter {

    private final String hostessChatId;
    private final Integer threadId;

    public HostessThreadRouter(String hostessChatId, String threadId) {
        this.hostessChatId = hostessChatId == null ? "" : hostessChatId.trim();
        this.threadId = parsePositive(threadId);
    }

    public void apply(Object method) {
        if (threadId == null || hostessChatId.isEmpty() || method == null) {
            return;
        }
        switch (method) {
            case SendMessage m when targets(m.getChatId(), m.getMessageThreadId()) -> m.setMessageThreadId(threadId);
            case SendLocation m when targets(m.getChatId(), m.getMessageThreadId()) -> m.setMessageThreadId(threadId);
            case SendVenue m when targets(m.getChatId(), m.getMessageThreadId()) -> m.setMessageThreadId(threadId);
            case SendContact m when targets(m.getChatId(), m.getMessageThreadId()) -> m.setMessageThreadId(threadId);
            case SendDice m when targets(m.getChatId(), m.getMessageThreadId()) -> m.setMessageThreadId(threadId);
            case SendMediaGroup m when targets(m.getChatId(), m.getMessageThreadId()) -> m.setMessageThreadId(threadId);
            case SendAnimation m when targets(m.getChatId(), m.getMessageThreadId()) -> m.setMessageThreadId(threadId);
            case CopyMessage m when targets(m.getChatId(), m.getMessageThreadId()) -> m.setMessageThreadId(threadId);
            case ForwardMessage m when targets(m.getChatId(), m.getMessageThreadId()) -> m.setMessageThreadId(threadId);
            default -> { }
        }
    }

    private boolean targets(String chatId, Integer currentThread) {
        return currentThread == null && chatId != null && hostessChatId.equals(chatId.trim());
    }

    private static Integer parsePositive(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
