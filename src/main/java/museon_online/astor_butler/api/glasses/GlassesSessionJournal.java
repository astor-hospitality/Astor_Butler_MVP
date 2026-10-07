package museon_online.astor_butler.api.glasses;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * What happened during one glasses session, for a report people read afterwards. Answers, stage codes,
 * latencies and client-reported events only: no media, transcripts, tasks, ACKs or guest data.
 */
@Component
public class GlassesSessionJournal implements AutoCloseable {
    static final int MAX_SESSIONS = 32;
    static final int MAX_ENTRIES = 500;
    static final Duration TTL = Duration.ofHours(24);
    static final Set<String> CLIENT_EVENTS = Set.of("STEP_STARTED", "STEP_DONE", "CALL_STARTED", "CALL_ENDED",
            "SESSION_FINISHED", "CLIENT_ERROR");

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(String at, String type, String stageCode, String requestId, String kind, String text,
                        Long latencyMs, String error, Boolean archived, String note) { }

    /** The durable shape, also written to S3 as journal.json. */
    public record Session(String sessionId, String scenarioCode, String startedAt, String updatedAt,
                          List<Entry> entries, Map<String, String> batches) { }

    public record Summary(String sessionId, String scenarioCode, String startedAt, String updatedAt,
                          int requests, int errors, int clientEvents) { }

    public record ClientEvent(String at, String type, String stageCode, String note) { }

    private final GlassesS3Storage storage;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Map<String, LinkedHashMap<String, State>> scopes = new HashMap<>();
    private final ScheduledExecutorService flusher;

    private record State(GlassesAccess.Scope scope, Session session, boolean dirty) { }

    @Autowired
    public GlassesSessionJournal(GlassesS3Storage storage) {
        this(storage, Clock.systemUTC(), true);
    }

    GlassesSessionJournal(GlassesS3Storage storage, Clock clock, boolean background) {
        this.storage = storage;
        this.clock = clock;
        if (background) {
            flusher = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "glasses-journal");
                t.setDaemon(true);
                return t;
            });
            flusher.scheduleWithFixedDelay(this::flush, 2, 2, TimeUnit.SECONDS);
        } else {
            flusher = null;
        }
    }

    synchronized void recordAssist(GlassesAccess.Scope scope, GlassesPhotoContext context, String requestId, String kind,
                                   String answer, String errorCode, long latencyMs, boolean archived) {
        if (context == null) return;
        State state = open(scope, context.sessionId(), context.scenarioCode());
        append(state, new Entry(now(), "ASSIST", context.stageCode(), requestId, kind, answer, latencyMs, errorCode,
                errorCode == null ? archived : null, null));
    }

    /** Idempotent by batch requestId: the same batch again is accepted silently, a different one with that id is 409. */
    synchronized int recordEvents(GlassesAccess.Scope scope, String batchId, String sessionId, String scenarioCode,
                                  List<ClientEvent> events) {
        String digest = digest(sessionId, scenarioCode, events);
        State state = open(scope, sessionId, scenarioCode);
        String known = state.session.batches.get(batchId);
        if (known != null) {
            if (!known.equals(digest)) throw new GlassesFailure(409, "REQUEST_ID_CONFLICT", "requestId reused for a different batch");
            return events.size();
        }
        if (!state.session.scenarioCode.equals(scenarioCode)) {
            throw new GlassesFailure(409, "SESSION_CONFLICT", "Session belongs to another scenario");
        }
        if (state.session.entries.size() + events.size() > MAX_ENTRIES) {
            throw new GlassesFailure(409, "SESSION_FULL", "Session journal is full");
        }
        for (ClientEvent event : events) {
            append(state, new Entry(event.at(), event.type(), event.stageCode(), null, null, null, null, null, null, event.note()));
        }
        state.session.batches.put(batchId, digest);
        return events.size();
    }

    synchronized List<Summary> sessions(GlassesAccess.Scope scope) {
        expire();
        var list = new ArrayList<Summary>();
        for (State state : scopes.getOrDefault(GlassesS3Storage.scopeKey(scope), new LinkedHashMap<>()).values()) {
            list.add(summary(state.session));
        }
        list.sort(Comparator.comparing(Summary::updatedAt).reversed());
        return list;
    }

    /** From memory, or from the private archive when this process has not seen the session. */
    synchronized Session session(GlassesAccess.Scope scope, String sessionId) {
        expire();
        State state = scopes.getOrDefault(GlassesS3Storage.scopeKey(scope), new LinkedHashMap<>()).get(sessionId);
        if (state != null) return copy(state.session);
        byte[] bytes = storage.readJournal(scope, sessionId);
        if (bytes == null) return null;
        try {
            Session stored = mapper.readValue(bytes, Session.class);
            if (stored == null || !sessionId.equals(stored.sessionId) || stored.entries == null) return null;
            return stored;
        } catch (Exception e) {
            return null;
        }
    }

    static Summary summary(Session session) {
        int requests = 0, errors = 0, events = 0;
        for (Entry entry : session.entries) {
            if ("ASSIST".equals(entry.type())) {
                requests++;
                if (entry.error() != null) errors++;
            } else {
                events++;
            }
        }
        return new Summary(session.sessionId, session.scenarioCode, session.startedAt, session.updatedAt, requests, errors, events);
    }

    /** Writes changed sessions to S3; a storage failure keeps the session dirty for the next pass. */
    void flush() {
        List<State> dirty;
        synchronized (this) {
            dirty = new ArrayList<>();
            for (var sessions : scopes.values()) for (State state : sessions.values()) if (state.dirty) dirty.add(state);
        }
        for (State state : dirty) {
            byte[] bytes;
            Session snapshot;
            synchronized (this) { snapshot = copy(state.session); }
            try {
                bytes = mapper.writeValueAsBytes(snapshot);
            } catch (Exception e) {
                continue;
            }
            boolean written = storage.writeJournal(state.scope, snapshot.sessionId, bytes);
            if (written) {
                synchronized (this) {
                    var sessions = scopes.get(GlassesS3Storage.scopeKey(state.scope));
                    State current = sessions == null ? null : sessions.get(snapshot.sessionId);
                    if (current != null && current.session.updatedAt.equals(snapshot.updatedAt)) {
                        sessions.put(snapshot.sessionId, new State(current.scope, current.session, false));
                    }
                }
            }
        }
    }

    private State open(GlassesAccess.Scope scope, String sessionId, String scenarioCode) {
        expire();
        var sessions = scopes.computeIfAbsent(GlassesS3Storage.scopeKey(scope), k -> new LinkedHashMap<>());
        State state = sessions.get(sessionId);
        if (state == null) {
            while (sessions.size() >= MAX_SESSIONS) {
                String oldest = null;
                String oldestAt = null;
                for (var e : sessions.entrySet()) {
                    if (oldestAt == null || e.getValue().session.updatedAt.compareTo(oldestAt) < 0) {
                        oldest = e.getKey();
                        oldestAt = e.getValue().session.updatedAt;
                    }
                }
                sessions.remove(oldest);
            }
            String at = now();
            state = new State(scope, new Session(sessionId, scenarioCode, at, at, new ArrayList<>(), new LinkedHashMap<>()), true);
            sessions.put(sessionId, state);
        }
        return state;
    }

    /** A full session drops further entries: the report must never make a request fail after the model answered. */
    private boolean append(State state, Entry entry) {
        if (state.session.entries.size() >= MAX_ENTRIES) return false;
        state.session.entries.add(entry);
        var sessions = scopes.get(GlassesS3Storage.scopeKey(state.scope));
        sessions.put(state.session.sessionId, new State(state.scope, new Session(state.session.sessionId,
                state.session.scenarioCode, state.session.startedAt, now(), state.session.entries, state.session.batches), true));
        return true;
    }

    private void expire() {
        Instant limit = clock.instant().minus(TTL);
        for (var sessions : scopes.values()) {
            sessions.values().removeIf(state -> Instant.parse(state.session.updatedAt).isBefore(limit));
        }
    }

    private String now() { return clock.instant().toString(); }

    private static Session copy(Session session) {
        return new Session(session.sessionId, session.scenarioCode, session.startedAt, session.updatedAt,
                List.copyOf(session.entries), Map.copyOf(session.batches));
    }

    private static String digest(String sessionId, String scenarioCode, List<ClientEvent> events) {
        var text = new StringBuilder(sessionId).append('\n').append(scenarioCode).append('\n');
        for (ClientEvent event : events) {
            text.append(event.at()).append('|').append(event.type()).append('|').append(event.stageCode())
                    .append('|').append(event.note()).append('\n');
        }
        return GlassesReplyCache.digest(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    @PreDestroy
    @Override
    public void close() {
        if (flusher != null) flusher.shutdownNow();
    }
}
