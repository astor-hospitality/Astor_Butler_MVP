package museon_online.astor_butler.integration.concierge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import museon_online.astor_butler.domain.booking.TableReservationCommand;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.journey.GuestVisit;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.ledger.LedgerWriter;
import museon_online.astor_butler.domain.ledger.TurnChannel;
import museon_online.astor_butler.domain.ledger.TurnKind;
import museon_online.astor_butler.domain.ledger.TurnSide;
import museon_online.astor_butler.domain.ledger.VisitTurnDraft;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tells the Concierge what happened to a booking made in its chat (negotiation plan, task 9): a signed POST to
 * {@code {url}/api/concierge/v1/events} instead of a Telegram message, since such a booking has no chat of the Butler's
 * bot. Only a booking whose visit is linked to a Concierge session is announced; others are left as they are.
 *
 * <p>{@link #announce} delivers on its own executor (after the surrounding transaction commits), never on the guest's
 * thread; the queue holds at most {@value #QUEUE_CAPACITY} events and a rejected one is recorded. {@link #announceNow}
 * delivers on the calling (scheduler) thread, so its result is the delivery itself and "sent" marks stay truthful.
 * Up to 3 attempts (at once, after 1 s, after 4 s), repeated only on a 5xx answer or a network error; a 4xx answer or
 * any other failure is final. Every outcome is one ledger row (BUTLER / INTERNAL / MESSAGE). The signature is {@code sha256=} plus the lowercase
 * hex HMAC-SHA256 of the exact UTF-8 bytes sent, keyed with {@code ASTOR_WEBHOOK_SECRET}.
 *
 * <p>The Concierge drops a repeated {@code type:reservationId} for 24 h, so a second move of the same booking would be
 * swallowed there; MOVED is therefore sent at most once per distinct start time (deduplicated here, in memory).
 *
 * <p>STAFF_COMING stays in the contract but is not sent: a Concierge booking makes a table request only through the
 * capability tool, whose result already carries «Передал. … скоро подойдёт.», so a webhook would say it twice.
 */
@Component
public class ConciergeWebhook {

    public static final String PATH = "/api/concierge/v1/events";
    public static final String SIGNATURE_HEADER = "X-Astor-Signature";
    static final long[] PAUSES_MS = {0L, 1000L, 4000L};
    static final int QUEUE_CAPACITY = 256;

    private static final Logger log = LoggerFactory.getLogger(ConciergeWebhook.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Yekaterinburg");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZONE);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZONE);

    /** A pause between attempts; a test passes one that only remembers. */
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final ConciergeWebhookProperties properties;
    private final RestTemplate rest;
    private final ObjectMapper mapper;
    private final LedgerWriter ledger;
    private final VisitTurnRepository turns;
    private final GuestVisitRepository visits;
    private final Executor executor;
    private final Sleeper sleeper;
    private final Map<Long, String> movedSent = new ConcurrentHashMap<>();

    @Autowired
    public ConciergeWebhook(ConciergeWebhookProperties properties, RestTemplateBuilder builder, ObjectMapper mapper,
                            LedgerWriter ledger, VisitTurnRepository turns, GuestVisitRepository visits) {
        this(properties, builder.connectTimeout(Duration.ofMillis(Math.max(1, properties.getTimeoutMs())))
                        .readTimeout(Duration.ofMillis(Math.max(1, properties.getTimeoutMs()))).build(),
                mapper, ledger, turns, visits, boundedExecutor(), Thread::sleep);
    }

    ConciergeWebhook(ConciergeWebhookProperties properties, RestTemplate rest, ObjectMapper mapper, LedgerWriter ledger,
                     VisitTurnRepository turns, GuestVisitRepository visits, Executor executor, Sleeper sleeper) {
        this.properties = properties;
        this.rest = rest;
        this.mapper = mapper;
        this.ledger = ledger;
        this.turns = turns;
        this.visits = visits;
        this.executor = executor;
        this.sleeper = sleeper;
    }

    @PostConstruct
    void warnAboutAWeakSecret() {
        if (properties.isEnabled() && !properties.secretUsable()) {
            log.warn("Concierge webhook is on but ASTOR_WEBHOOK_SECRET is shorter than {} characters: nothing will be sent",
                    ConciergeWebhookProperties.MIN_SECRET);
        }
    }

    @PreDestroy
    void stop() {
        if (executor instanceof ExecutorService service) {
            service.shutdown();
        }
    }

    /** On, with a secret long enough to sign. */
    public boolean ready() {
        return properties.isEnabled() && properties.secretUsable();
    }

    /** {@link #announce(String, TableReservationOrder, Map)} with the booking's own facts only. */
    public boolean announce(String type, TableReservationOrder order) {
        return announce(type, order, Map.of());
    }

    /**
     * Announces the event when the booking was made in the Concierge's chat (no Butler chat, channel CONCIERGE) and its
     * visit is linked to a session. Returns whether the event was handed over for delivery; never throws.
     */
    public boolean announce(String type, TableReservationOrder order, Map<String, Object> facts) {
        if (order == null || order.id() == null || order.chatId() != null
                || !TableReservationCommand.CHANNEL_CONCIERGE.equals(order.source())) {
            return false;
        }
        return announce(type, order.id(), order, facts);
    }

    /** The same for a reservation known by id; {@code order}, when given, adds its time, table and party to the facts. */
    public boolean announce(String type, long reservationId, TableReservationOrder order, Map<String, Object> facts) {
        return event(type, reservationId, order, facts).map(this::send).orElse(false);
    }

    /** {@link #announceNow(String, long, TableReservationOrder, Map)} for a Concierge booking (no Butler chat). */
    public boolean announceNow(String type, TableReservationOrder order) {
        if (order == null || order.id() == null || order.chatId() != null
                || !TableReservationCommand.CHANNEL_CONCIERGE.equals(order.source())) {
            return false;
        }
        return announceNow(type, order.id(), order, Map.of());
    }

    /**
     * The same, delivered on the calling thread: true only when the Concierge accepted the event, so the caller may mark
     * it sent. For scheduler threads only (the morning check, the payment prompter, the review clock) and never under a
     * lock others wait on: a Telegram update or request thread uses {@link #announce}, since this may take seconds.
     */
    public boolean announceNow(String type, long reservationId, TableReservationOrder order, Map<String, Object> facts) {
        return event(type, reservationId, order, facts).map(this::deliver).orElse(false);
    }

    private Optional<ConciergeEvent> event(String type, long reservationId, TableReservationOrder order, Map<String, Object> facts) {
        if (!ready()) {
            return Optional.empty();
        }
        try {
            Optional<GuestVisit> visit = visits.findByReservation(reservationId);
            if (visit.isEmpty()) {
                return Optional.empty();
            }
            Optional<String> session = turns.sessionOf(visit.get().id());
            if (session.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> all = new LinkedHashMap<>(bookingFacts(order));
            if (facts != null) {
                all.putAll(facts);
            }
            if (ConciergeEvent.MOVED.equals(type) && !firstMoveTo(reservationId, order)) {
                log.debug("Concierge MOVED already sent for this time: reservation={}", reservationId);
                return Optional.empty();
            }
            return Optional.of(ConciergeEvent.of(type, reservationId, visit.get().id(), session.get(), all));
        } catch (RuntimeException e) {
            log.warn("Concierge event not announced: type={}, reservation={}, reason={}", type, reservationId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Whether an event about the reservation would be sent now: on, and its visit is linked to a Concierge session. */
    public boolean reaches(long reservationId) {
        if (!ready()) {
            return false;
        }
        try {
            return visits.findByReservation(reservationId).flatMap(visit -> turns.sessionOf(visit.id())).isPresent();
        } catch (RuntimeException e) {
            log.warn("Concierge session not looked up: reservation={}, reason={}", reservationId, e.getClass().getSimpleName());
            return false;
        }
    }

    /** Hands the event to the webhook's own thread, after the current transaction commits; false while off. */
    public boolean send(ConciergeEvent event) {
        if (!ready() || event == null) {
            return false;
        }
        Runnable delivery = () -> deliver(event);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(delivery, event);
                }
            });
            return true;
        }
        return dispatch(delivery, event);
    }

    private boolean dispatch(Runnable delivery, ConciergeEvent event) {
        try {
            executor.execute(delivery);
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("Concierge event not queued, the queue is full: type={}, reservation={}", event.type(), event.reservationId());
            record(event, 0, "QUEUE_FULL");
            return false;
        } catch (RuntimeException e) {
            log.warn("Concierge event not queued: type={}, reservation={}, reason={}", event.type(), event.reservationId(),
                    e.getClass().getSimpleName());
            record(event, 0, "NOT_QUEUED");
            return false;
        }
    }

    /** Posts the event now, on the calling thread, with retries; writes one ledger row; returns whether it was accepted. */
    boolean deliver(ConciergeEvent event) {
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(body(event));
        } catch (JsonProcessingException e) {
            log.warn("Concierge event not serialisable: type={}, reservation={}", event.type(), event.reservationId());
            record(event, 0, "UNSERIALISABLE");
            return false;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(SIGNATURE_HEADER, "sha256=" + sign(properties.getSecret(), body));
        HttpEntity<byte[]> request = new HttpEntity<>(body, headers);
        String url = endpoint();
        int attempts = 0;
        String status = "NOT_SENT";
        boolean delivered = false;
        for (long pause : PAUSES_MS) {
            if (pause > 0 && !pause(pause)) {
                break;
            }
            attempts++;
            try {
                ResponseEntity<Void> response = rest.exchange(url, HttpMethod.POST, request, Void.class);
                status = Integer.toString(response.getStatusCode().value());
                delivered = response.getStatusCode().is2xxSuccessful();
                break;
            } catch (RestClientResponseException e) {
                status = Integer.toString(e.getStatusCode().value());
                if (!e.getStatusCode().is5xxServerError()) {
                    break;
                }
            } catch (ResourceAccessException e) {
                status = "NETWORK";
            } catch (RuntimeException e) {
                status = "ERROR";
                break;
            }
        }
        if (delivered) {
            log.info("Concierge event delivered: type={}, reservation={}, attempts={}", event.type(), event.reservationId(), attempts);
        } else {
            log.warn("Concierge event not delivered: type={}, reservation={}, attempts={}, status={}", event.type(),
                    event.reservationId(), attempts, status);
        }
        record(event, attempts, status);
        return delivered;
    }

    /** Lowercase hex HMAC-SHA256 of the body; the Concierge compares it case-sensitively. */
    static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    private boolean pause(long millis) {
        try {
            sleeper.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String endpoint() {
        String base = Objects.requireNonNullElse(properties.getUrl(), "").trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + PATH;
    }

    private boolean firstMoveTo(long reservationId, TableReservationOrder order) {
        String startAt = order == null || order.requestedStartAt() == null ? "" : order.requestedStartAt().toString();
        return !startAt.equals(movedSent.put(reservationId, startAt));
    }

    private void record(ConciergeEvent event, int attempts, String status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", event.type());
        payload.put("reservationId", event.reservationId());
        payload.put("attempts", attempts);
        payload.put("status", status);
        ledger.write(new VisitTurnDraft(event.visitId(), event.sessionId(), TurnSide.BUTLER, TurnChannel.INTERNAL, TurnKind.MESSAGE,
                event.butlerText(), payload, null));
    }

    private static Map<String, Object> body(ConciergeEvent event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", event.type());
        body.put("reservationId", event.reservationId());
        body.put("visitId", event.visitId());
        body.put("sessionId", event.sessionId());
        body.put("butlerText", event.butlerText());
        body.put("facts", event.facts());
        return body;
    }

    /** Time, date, table and party of the booking: what the Butler's lines are made of. No phone, no name. */
    private static Map<String, Object> bookingFacts(TableReservationOrder order) {
        Map<String, Object> facts = new LinkedHashMap<>();
        if (order == null) {
            return facts;
        }
        if (order.requestedStartAt() != null) {
            facts.put("startAt", order.requestedStartAt().toString());
            facts.put("date", DATE.format(order.requestedStartAt()));
            facts.put("time", TIME.format(order.requestedStartAt()));
        }
        String table = table(order);
        if (table != null) {
            facts.put("table", table);
        }
        if (order.partySize() != null) {
            facts.put("partySize", order.partySize());
        }
        if (order.status() != null) {
            facts.put("status", order.status().name());
        }
        return facts;
    }

    private static String table(TableReservationOrder order) {
        if (order.tableDisplayName() != null && !order.tableDisplayName().isBlank()) {
            return order.tableDisplayName();
        }
        return order.tableCode() == null || order.tableCode().isBlank() ? null : "стол " + order.tableCode();
    }

    /** Two daemon threads and a queue of {@value #QUEUE_CAPACITY}; a full queue rejects the event instead of growing. */
    static ThreadPoolExecutor boundedExecutor() {
        AtomicInteger count = new AtomicInteger();
        return new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
            Thread thread = new Thread(runnable, "concierge-webhook-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
}
