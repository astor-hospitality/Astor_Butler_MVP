package museon_online.astor_butler.max.adapter;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.max.MaxBotSettings;
import museon_online.astor_butler.max.client.MaxApiException;
import museon_online.astor_butler.max.client.MaxBotApiClient;
import museon_online.astor_butler.max.client.MaxUpdatesPage;
import org.springframework.context.SmartLifecycle;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The MAX long-polling loop: {@code GET /updates} with the last marker, every update handed to {@link MaxRouter}
 * one after another (the same one-at-a-time order the Telegram bot library uses), then the next poll. Runs on one
 * daemon thread started after the context is up and stopped with it. A failed poll waits {@code retryDelay}
 * (doubling up to a minute while failures repeat) and never takes the application down; with no token the loop
 * is not started at all.
 *
 * <p>The marker is kept in memory only: after a restart the first poll goes without a marker and MAX returns what
 * the bot has not received yet. Duplicates that can slip through on a restart are dropped by the router's
 * idempotency check.
 */
@Slf4j
public class MaxLongPollingRunner implements SmartLifecycle {

    private static final Duration MAX_BACKOFF = Duration.ofMinutes(1);

    private final MaxBotApiClient client;
    private final MaxRouter router;
    private final MaxBotSettings settings;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Thread thread;
    private volatile Long marker;

    public MaxLongPollingRunner(MaxBotApiClient client, MaxRouter router, MaxBotSettings settings) {
        this.client = client;
        this.router = router;
        this.settings = settings;
    }

    @Override
    public void start() {
        if (!settings.active()) {
            log.warn("MAX channel is enabled but MAX_BOT_TOKEN is blank: polling not started");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread worker = new Thread(this::loop, "max-long-polling");
        worker.setDaemon(true);
        thread = worker;
        worker.start();
        log.info("MAX long polling started: baseUrl={}", settings.baseUrl());
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Thread worker = thread;
        if (worker != null) {
            worker.interrupt();
        }
        log.info("MAX long polling stopped");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /** One poll and the handling of everything it returned; package-visible for tests. */
    int pollOnce() {
        MaxUpdatesPage page = client.getUpdates(marker, settings.pollTimeout(), settings.pollLimit());
        int handled = 0;
        for (JsonNode update : page.updates()) {
            if (!running.get() && thread != null) {
                break;
            }
            try {
                router.handle(update);
            } catch (RuntimeException e) {
                // One bad update must not stop the channel or be retried forever.
                log.error("MAX update failed and was skipped: type={}, reason={}",
                        update.path("update_type").asText(""), e.toString());
            }
            handled++;
        }
        if (page.marker() != null) {
            marker = page.marker();
        }
        return handled;
    }

    Long marker() {
        return marker;
    }

    /** Logs which bot the token belongs to (the smoke check after switching MAX on); never fatal. */
    void announce() {
        try {
            JsonNode me = client.getMe();
            log.info("MAX bot connected: userId={}, username={}", me.path("user_id").asText(""),
                    me.path("username").asText(""));
        } catch (MaxApiException e) {
            log.warn("MAX GET /me failed (HTTP {}): {}", e.status(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("MAX GET /me failed: {}", e.toString());
        }
    }

    private void loop() {
        announce();
        Duration delay = settings.retryDelay();
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                pollOnce();
                delay = settings.retryDelay();
            } catch (MaxApiException e) {
                if (e.status() == 401 || e.status() == 403) {
                    log.error("MAX rejected the bot token (HTTP {}): check MAX_BOT_TOKEN; retrying in {}s",
                            e.status(), MAX_BACKOFF.toSeconds());
                    delay = MAX_BACKOFF;
                } else {
                    log.warn("MAX poll failed (HTTP {}): {}; retrying in {} ms", e.status(), e.getMessage(), delay.toMillis());
                }
                if (!sleep(delay)) {
                    return;
                }
                delay = next(delay);
            } catch (RuntimeException e) {
                log.warn("MAX poll failed: {}; retrying in {} ms", e.toString(), delay.toMillis());
                if (!sleep(delay)) {
                    return;
                }
                delay = next(delay);
            }
        }
    }

    private Duration next(Duration delay) {
        Duration doubled = delay.multipliedBy(2);
        return doubled.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : doubled;
    }

    private boolean sleep(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
