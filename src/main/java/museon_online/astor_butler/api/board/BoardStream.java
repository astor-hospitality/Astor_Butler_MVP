package museon_online.astor_butler.api.board;

import jakarta.annotation.PreDestroy;
import museon_online.astor_butler.domain.ledger.VisitTurnAppended;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The board's live stream: event {@code turn} with {@code {"visitId":N}} for every ledger turn of a known visit, and a
 * comment every 25 seconds so proxies keep the connection. The ledger publishes on the writer's thread; this listener
 * only hands the push to its own thread and never touches the database, so a slow browser never slows a guest's turn.
 */
@Component
public class BoardStream {

    static final long TIMEOUT_MS = TimeUnit.MINUTES.toMillis(30);
    static final long HEARTBEAT_SECONDS = 25;
    /** A closed board has a handful of viewers; the cap keeps a leaked token from holding open many connections. */
    static final int MAX_CLIENTS = 32;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService pusher = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "board-stream");
        thread.setDaemon(true);
        return thread;
    });

    public BoardStream() {
        pusher.scheduleAtFixedRate(this::heartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    public SseEmitter open() {
        if (emitters.size() >= MAX_CLIENTS) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "too many board streams");
        }
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(error -> emitters.remove(emitter));
        emitters.add(emitter);
        return emitter;
    }

    @EventListener
    public void onTurn(VisitTurnAppended event) {
        if (event == null || event.visitId() == null || emitters.isEmpty()) {
            return;
        }
        String data = "{\"visitId\":" + event.visitId() + "}";
        try {
            pusher.execute(() -> send(() -> SseEmitter.event().name("turn").data(data)));
        } catch (RejectedExecutionException e) {
            // Shutting down: nobody is listening any more.
        }
    }

    int clients() {
        return emitters.size();
    }

    private void heartbeat() {
        send(() -> SseEmitter.event().comment("ping"));
    }

    /** A builder is single-use, so every emitter gets its own. */
    private void send(Supplier<SseEmitter.SseEventBuilder> event) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(event.get());
            } catch (Exception e) {
                emitters.remove(emitter);
                emitter.completeWithError(e);
            }
        }
    }

    @PreDestroy
    void close() {
        pusher.shutdownNow();
        emitters.forEach(SseEmitter::complete);
        emitters.clear();
    }
}
