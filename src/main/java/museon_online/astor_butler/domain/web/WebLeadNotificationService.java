package museon_online.astor_butler.domain.web;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.OutgoingMessage;
import museon_online.astor_butler.telegram.adapter.TelegramAdminNotifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Projects website dialogs into the Telegram analytics chat. Delivery is asynchronous on one thread with a bounded
 * queue: {@link TelegramAdminNotifier#sendAnalytics} throttles and retries, so it must never run on a request thread.
 * Overflow drops the card (counted) rather than blocking Tomcat.
 */
@Service
@Slf4j
public class WebLeadNotificationService {

    /** Telegram caps a message at 4096 characters; the card template needs room around the guest text. */
    static final int MAX_GUEST_TEXT_CHARS = 2500;

    private final TelegramAdminNotifier telegramAdminNotifier;
    private final ThreadPoolExecutor executor;
    private final AtomicLong dropped = new AtomicLong();

    @Value("${astor.web.notifications.admin-chat-enabled:true}")
    private boolean adminChatEnabled;

    public WebLeadNotificationService(
            TelegramAdminNotifier telegramAdminNotifier,
            @Value("${astor.web.notifications.queue-capacity:200}") int queueCapacity
    ) {
        this.telegramAdminNotifier = telegramAdminNotifier;
        this.executor = new ThreadPoolExecutor(
                1,
                1,
                30,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
                runnable -> {
                    Thread thread = new Thread(runnable, "web-lead-notify");
                    thread.setDaemon(true);
                    return thread;
                },
                (runnable, pool) -> {
                    long total = dropped.incrementAndGet();
                    log.warn("Web lead notification queue is full, card dropped (total dropped={})", total);
                }
        );
        this.executor.allowCoreThreadTimeOut(true);
    }

    /** Every message of a lead dialog (C3AG site) becomes an operator card. */
    public void project(WebSessionResolution session, IncomingMessage incoming, OutgoingMessage outgoing) {
        if (!adminChatEnabled || session == null || incoming == null) {
            return;
        }
        String text = incoming.text() == null ? "" : incoming.text().trim();
        if (text.isBlank()) {
            return;
        }
        String card = card(session, incoming, outgoing);
        executor.execute(() -> {
            try {
                telegramAdminNotifier.sendAnalytics(card);
            } catch (RuntimeException e) {
                log.warn("Web lead notification failed: {}", e.getMessage());
            }
        });
    }

    /** Guest FSM dialogs (Astor sites) only announce the first message of a session and fallbacks. */
    public void projectGuestFlow(WebSessionResolution session, IncomingMessage incoming, OutgoingMessage outgoing) {
        if (session == null) {
            return;
        }
        boolean fallback = outgoing != null && outgoing.fallback();
        if (session.created() || fallback) {
            project(session, incoming, outgoing);
        }
    }

    public long droppedCount() {
        return dropped.get();
    }

    /** Test hook: returns once the queue is empty and the worker is idle (single FIFO worker). */
    void awaitIdle(java.time.Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!executor.getQueue().isEmpty() || executor.getActiveCount() > 0) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Web lead notification queue did not drain in " + timeout);
            }
            Thread.sleep(10);
        }
    }

    public int queuedCount() {
        return executor.getQueue().size();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private String card(WebSessionResolution session, IncomingMessage incoming, OutgoingMessage outgoing) {
        Map<String, Object> payload = incoming.payload() == null ? Map.of() : incoming.payload();
        return """
                <b>Astor Butler / website lead</b>
                Новое сообщение с сайта

                <b>Dialog</b>
                lead %s
                message %s
                time %s

                <b>Visitor</b>
                session %s
                chat %s / user %s

                <b>Message</b>
                <blockquote>%s</blockquote>

                <b>Context</b>
                Site: %s
                Page: %s
                Referrer: %s
                Selected video: %s
                Consent: %s

                <b>FSM</b>
                Next state: %s
                Fallback: %s
                Actions: %s

                """.formatted(
                html(text(session.id())),
                html(blank(incoming.correlationId())),
                html(text(incoming.receivedAt())),
                html(session.sessionId()),
                html(text(session.chatId())),
                html(blank(session.externalUserId())),
                html(blank(guestText(incoming.text()))),
                html(blank(value(payload, "site"))),
                html(blank(value(payload, "page"))),
                html(blank(value(payload, "referrer"))),
                html(blank(selectedVideo(payload))),
                html(blank(consent(payload))),
                html(outgoing == null ? "" : outgoing.nextState()),
                html(outgoing == null ? "" : text(outgoing.fallback())),
                html(outgoing == null || outgoing.actions() == null ? "" : String.join(", ", outgoing.actions()))
        );
    }

    private String guestText(String text) {
        if (text == null) {
            return null;
        }
        if (text.codePointCount(0, text.length()) <= MAX_GUEST_TEXT_CHARS) {
            return text;
        }
        int end = text.offsetByCodePoints(0, MAX_GUEST_TEXT_CHARS);
        return text.substring(0, end) + "… [обрезано]";
    }

    private String selectedVideo(Map<String, Object> payload) {
        Object selected = payload.get("selectedVideo");
        if (!(selected instanceof Map<?, ?> video)) {
            return "";
        }
        String slug = string(video.get("slug"));
        String title = string(video.get("title"));
        if (!slug.isBlank() && !title.isBlank()) {
            return title + " (" + slug + ")";
        }
        return !slug.isBlank() ? slug : title;
    }

    private String consent(Map<String, Object> payload) {
        Object consent = payload.get("consent");
        if (!(consent instanceof Map<?, ?> consentMap)) {
            return "";
        }
        Object accepted = consentMap.get("privacyAccepted");
        Object version = consentMap.get("policyVersion");
        return "privacyAccepted=%s, policyVersion=%s".formatted(
                string(accepted),
                string(version)
        );
    }

    private String value(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return string(value);
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? "(empty)" : value;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private String html(String value) {
        return text(value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
