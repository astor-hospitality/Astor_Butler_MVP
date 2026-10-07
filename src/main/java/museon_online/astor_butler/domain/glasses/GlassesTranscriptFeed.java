package museon_online.astor_butler.domain.glasses;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * What was said through the glasses, as the staff portal shows it: the staff member's question, Astor's
 * answer, and whether a photo came with it. The system chat is the durable record; this is the page
 * people open during the shift.
 *
 * Bounded on purpose: the last 200 exchanges of the last 24 hours, in memory. Photos are not kept here —
 * only the fact that one existed and its request id, so the private archive stays the single place a
 * frame lives.
 */
@Component
public class GlassesTranscriptFeed {
    static final int LIMIT = 200;
    static final Duration TTL = Duration.ofHours(24);

    public record Entry(String requestId, String at, String kind, String staff, String venue,
                        String sessionId, String stageCode, String question, String answer, boolean photo) { }

    private final Deque<Entry> entries = new ArrayDeque<>();

    public synchronized void add(Entry entry) {
        expire();
        entries.addFirst(entry);
        while (entries.size() > LIMIT) entries.removeLast();
    }

    /** Newest first. */
    public synchronized List<Entry> recent(int limit) {
        expire();
        return entries.stream().limit(Math.max(1, Math.min(limit, LIMIT))).toList();
    }

    private void expire() {
        Instant cutoff = Instant.now().minus(TTL);
        entries.removeIf(entry -> {
            try {
                return Instant.parse(entry.at()).isBefore(cutoff);
            } catch (RuntimeException e) {
                return true;
            }
        });
    }
}
