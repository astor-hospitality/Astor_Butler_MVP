package museon_online.astor_butler.agent;

import museon_online.astor_butler.capability.GuestAgreement;
import museon_online.astor_butler.model.ModelChatMessage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The agent's memory of a conversation (a chat of the bot or a Concierge session), in process only. It keeps the last
 * turns within {@code historyChars}; older ones collapse into one «Ранее: …» line built from their facts, without a
 * model call. It also remembers the visit a session was linked to, so one session never opens a second visit, and the
 * tables the last answered turn put to the guest: the only ones the guest's next yes can book, and the reservations it
 * offered to cancel: the only ones the guest's next yes can cancel.
 */
class AgentHistory {

    static final int MAX_CONVERSATIONS = 1_000;
    private static final int DIGEST_TEXT = 60;

    /** One finished turn: what was said to the agent, what it answered, and a one-line digest of its facts. */
    record Exchange(String said, String answered, String digest) {
        int length() {
            return said.length() + answered.length();
        }
    }

    private static final class Conversation {
        final Deque<Exchange> exchanges = new ArrayDeque<>();
        final Deque<String> earlier = new ArrayDeque<>();
        int turns;
        List<GuestAgreement.Table> proposed = List.of();
        List<Long> cancellations = List.of();
        volatile Long visitId;
        volatile long touched = System.nanoTime();
    }

    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();
    private final AgentProperties properties;

    AgentHistory(AgentProperties properties) {
        this.properties = properties;
    }

    /** The earlier line (when any) and the kept turns, oldest first. */
    List<ModelChatMessage> messages(String key) {
        Conversation conversation = conversations.get(key);
        if (conversation == null) {
            return List.of();
        }
        synchronized (conversation) {
            List<ModelChatMessage> messages = new ArrayList<>();
            if (!conversation.earlier.isEmpty()) {
                messages.add(ModelChatMessage.user("Ранее: " + String.join("; ", conversation.earlier)));
            }
            for (Exchange exchange : conversation.exchanges) {
                messages.add(ModelChatMessage.user(exchange.said()));
                messages.add(ModelChatMessage.assistant(exchange.answered()));
            }
            return messages;
        }
    }

    /** The number of the coming turn, 1 for a new conversation; a turn that fell back keeps its number. */
    int nextTurn(String key) {
        Conversation conversation = conversations.get(key);
        if (conversation == null) {
            return 1;
        }
        synchronized (conversation) {
            return conversation.turns + 1;
        }
    }

    Long visit(String key) {
        Conversation conversation = conversations.get(key);
        return conversation == null ? null : conversation.visitId;
    }

    void rememberVisit(String key, Long visitId) {
        if (visitId != null) {
            conversation(key).visitId = visitId;
        }
    }

    /**
     * Moves past a turn that fell back. Its commitments keep their keys, so the next, different turn must not reuse
     * them (it would replay a booking or hit another tool's key).
     */
    void skipTurn(String key) {
        Conversation conversation = conversation(key);
        synchronized (conversation) {
            conversation.turns++;
        }
    }

    /** The tables the last answered turn put to the guest; none after a turn that fell back. */
    List<GuestAgreement.Table> proposed(String key) {
        Conversation conversation = conversations.get(key);
        if (conversation == null) {
            return List.of();
        }
        synchronized (conversation) {
            return conversation.proposed;
        }
    }

    /** A turn that fell back: the guest never heard its answer, so the next yes answers no offer. */
    void withdrawProposals(String key) {
        Conversation conversation = conversation(key);
        synchronized (conversation) {
            conversation.proposed = List.of();
            conversation.cancellations = List.of();
        }
    }

    /** The reservations the last answered turn offered the guest to cancel; none after a turn that fell back. */
    List<Long> cancellations(String key) {
        Conversation conversation = conversations.get(key);
        if (conversation == null) {
            return List.of();
        }
        synchronized (conversation) {
            return conversation.cancellations;
        }
    }

    /**
     * @param proposed the tables this turn put to the guest; they replace the previous turn's
     * @param cancellations the reservations this turn offered to cancel; likewise
     */
    void add(String key, String said, String answered, String digest, List<GuestAgreement.Table> proposed,
             List<Long> cancellations) {
        Conversation conversation = conversation(key);
        synchronized (conversation) {
            conversation.turns++;
            conversation.proposed = proposed == null ? List.of() : List.copyOf(proposed);
            conversation.cancellations = cancellations == null ? List.of() : List.copyOf(cancellations);
            conversation.exchanges.addLast(new Exchange(said == null ? "" : said, answered == null ? "" : answered,
                    digest == null || digest.isBlank() ? shorten("гость: " + said) : digest));
            int earlierLimit = Math.max(60, Math.min(600, properties.getHistoryChars() / 4));
            while (conversation.exchanges.size() > 1 && size(conversation) > properties.getHistoryChars()) {
                conversation.earlier.addLast(conversation.exchanges.removeFirst().digest());
                while (conversation.earlier.size() > 1 && String.join("; ", conversation.earlier).length() > earlierLimit) {
                    conversation.earlier.removeFirst();
                }
            }
        }
    }

    private static int size(Conversation conversation) {
        int chars = conversation.exchanges.stream().mapToInt(Exchange::length).sum();
        return conversation.earlier.isEmpty() ? chars : chars + String.join("; ", conversation.earlier).length() + 7;
    }

    static String shorten(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return flat.length() <= DIGEST_TEXT ? flat : flat.substring(0, DIGEST_TEXT) + "…";
    }

    private Conversation conversation(String key) {
        Conversation conversation = conversations.computeIfAbsent(key, ignored -> new Conversation());
        conversation.touched = System.nanoTime();
        if (conversations.size() > MAX_CONVERSATIONS) {
            conversations.entrySet().stream()
                    .filter(entry -> entry.getValue() != conversation)
                    .min(Comparator.comparingLong(entry -> entry.getValue().touched))
                    .ifPresent(entry -> conversations.remove(entry.getKey(), entry.getValue()));
        }
        return conversation;
    }
}
