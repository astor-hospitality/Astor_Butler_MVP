package museon_online.astor_butler.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityProperties;
import museon_online.astor_butler.capability.CapabilityRegistry;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.capability.CapabilityTool;
import museon_online.astor_butler.capability.IdempotencyStore;
import museon_online.astor_butler.capability.tools.CapabilityFixtures;
import museon_online.astor_butler.capability.tools.ReservationCreateToolTest;
import museon_online.astor_butler.domain.booking.TableReservationService;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.journey.talk.VenueFacts;
import museon_online.astor_butler.domain.journey.talk.VenueFactsCatalog;
import museon_online.astor_butler.domain.ledger.LedgerWriter;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
import museon_online.astor_butler.domain.summary.AgentLessonRepository;
import museon_online.astor_butler.domain.summary.GuestMemory;
import museon_online.astor_butler.model.OpenAiCompatibleModelGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Load check of the Butler's negotiation path with a MODEL STUB; off unless {@code -Dastor.load=true}:
 * <pre>
 * mvn -o test -Dtest=AgentNegotiationLoadTest -Dastor.load=true -Dsurefire.failIfNoSpecifiedTests=false \
 *     -Dastor.load.profile=gigachat-3-pro -Dastor.load.steps=1,2,5,10 -Dastor.load.stress=20,40 -Dastor.load.seconds=30
 * </pre>
 * One RPS unit is one negotiation turn ({@link ButlerAgent#negotiate}, what {@code POST /api/negotiation/v1/turn}
 * runs): three model calls (table_availability, reservation_create, answer) through the real OpenAI-compatible gateway
 * over HTTP to a local stub. The booking runs the real tool and idempotency store on H2; the reservation service is a
 * mock. Not measured here: the HTTP controller, PostgreSQL, Telegram. Model latency profiles are the live ones of
 * 2026-10-15 (docs/operations/SBER500_LLM_ACCEPTANCE.md). The report goes to stdout and target/load/.
 */
@EnabledIfSystemProperty(named = "astor.load", matches = "true")
class AgentNegotiationLoadTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String profile = System.getProperty("astor.load.profile", "gigachat-3-pro");
    private final int seconds = Integer.parseInt(System.getProperty("astor.load.seconds", "30"));
    private final AtomicLong modelRequests = new AtomicLong();

    /** Seconds of one model call: log-normal (median, p95) measured live, capped at 30 s; or fixed. */
    private double modelDelay() {
        return switch (profile) {
            case "fast" -> 0.05;
            case "gpt-4o-mini" -> logNormal(1.62, 1.97);
            default -> logNormal(1.16, 8.89);
        };
    }

    private static double logNormal(double median, double p95) {
        double sigma = Math.log(p95 / median) / 1.645;
        return Math.min(30, median * Math.exp(sigma * ThreadLocalRandom.current().nextGaussian()));
    }

    @Test
    void negotiationTurnsAtSteppedRates() throws Exception {
        HttpServer model = modelStub();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:agent-load-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection schema = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(schema, new ClassPathResource("db/changelog/2026-10-15-capability-calls.sql"));
            SimpleMeterRegistry meters = new SimpleMeterRegistry();
            ButlerAgent agent = agent(new IdempotencyStore(new JdbcTemplate(dataSource), new ObjectMapper()),
                    "http://127.0.0.1:" + model.getAddress().getPort() + "/v1", meters);
            List<Row> rows = new ArrayList<>();
            for (int rps : rates("astor.load.steps", "1,2,5,10")) {
                rows.add(step("ступень", rps, agent));
            }
            for (int rps : rates("astor.load.stress", "20,40")) {
                rows.add(step("стресс", rps, agent));
            }
            report(rows);
        } finally {
            model.stop(0);
        }
    }

    private HttpServer modelStub() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 512);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            Map<?, ?> body = JSON.readValue(exchange.getRequestBody(), Map.class);
            long n = modelRequests.incrementAndGet();
            try {
                Thread.sleep((long) (modelDelay() * 1000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            List<?> messages = (List<?>) body.get("messages");
            int lastUser = -1;
            for (int i = 0; i < messages.size(); i++) {
                if ("user".equals(((Map<?, ?>) messages.get(i)).get("role"))) {
                    lastUser = i;
                }
            }
            long done = messages.subList(lastUser + 1, messages.size()).stream()
                    .filter(m -> "tool".equals(((Map<?, ?>) m).get("role"))).count();
            boolean tools = body.get("tools") instanceof List<?> list && !list.isEmpty();
            Map<String, Object> message = tools && done == 0 ? call(n, "table_availability", Map.of("date", "2026-10-20", "time", "19:00", "partySize", 2))
                    : tools && done == 1 ? call(n, "reservation_create", ReservationCreateToolTest.args())
                    : Map.of("role", "assistant", "content", "Стол на 19:00 передан хостес, она подтвердит.");
            byte[] answer = JSON.writeValueAsBytes(Map.of(
                    "choices", List.of(Map.of("finish_reason", message.containsKey("tool_calls") ? "tool_calls" : "stop", "message", message)),
                    "usage", Map.of("prompt_tokens", 0, "completion_tokens", 0)));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static Map<String, Object> call(long n, String tool, Map<String, Object> args) throws IOException {
        return Map.of("role", "assistant", "tool_calls", List.of(Map.of("id", "call%05d".formatted(n % 100_000), "type", "function",
                "function", Map.of("name", tool, "arguments", JSON.writeValueAsString(args)))));
    }

    private ButlerAgent agent(IdempotencyStore store, String baseUrl, SimpleMeterRegistry meters) {
        TableReservationService reservations = mock(TableReservationService.class);
        AtomicLong ids = new AtomicLong(1000);
        when(reservations.createReservation(any())).thenAnswer(invocation -> CapabilityFixtures.order(ids.incrementAndGet(), null,
                TableReservationStatus.AWAITING_MANAGER_CONFIRMATION));
        CapabilityProperties capabilities = new CapabilityProperties();
        capabilities.setEnabled(true);
        CapabilityTool availability = new CapabilityTool() {
            public String name() { return "table_availability"; }
            public String description() { return "Свободные столы на дату и время"; }
            public Map<String, Object> parameters() { return Map.of("type", "object", "properties", Map.of()); }
            public boolean commitment() { return false; }
            public CapabilityResult invoke(CapabilityContext ctx, Map<String, Object> args) {
                return CapabilityResult.ok(Map.of("available", true, "time", "19:00"));
            }
        };
        CapabilityRegistry registry = new CapabilityRegistry(List.of(availability, ReservationCreateToolTest.tool(reservations)),
                mock(LedgerWriter.class), capabilities, store);
        OpenAiCompatibleModelGateway gateway = new OpenAiCompatibleModelGateway(new RestTemplateBuilder(), baseUrl, "stub-key-not-a-secret",
                profile, "", "", "", 15_000, 512, 0.1, true, "");
        AgentProperties properties = new AgentProperties();
        properties.setEnabled(true);
        properties.setModel(profile);
        AgentLoop loop = new AgentLoop(gateway, registry, properties);
        loop.setDefaultProvider("openai-compatible");
        loop.setMeterRegistry(meters);
        VisitTurnRepository turns = mock(VisitTurnRepository.class);
        when(turns.visitOfSession(anyString())).thenReturn(Optional.empty());
        return new ButlerAgent(loop, registry, properties, mock(AgentLessonRepository.class), mock(GuestMemory.class),
                new VenueFactsCatalog(VenueFacts.none()), capabilities, mock(LedgerWriter.class), turns, mock(GuestVisitRepository.class));
    }

    private record Turn(long nanos, boolean fallback, boolean error) {
    }

    private record Row(String kind, int rps, int sent, double achieved, double p50, double p95, double p99, double max,
                       long answered, long fallback, long errors, double modelRps, double cpu) {
    }

    private Row step(String kind, int rps, ButlerAgent agent) throws Exception {
        long models = modelRequests.get();
        double processCpu0 = processCpuNanos();
        long started = System.nanoTime();
        int total = rps * seconds;
        List<CompletableFuture<Turn>> turns = new ArrayList<>(total);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < total; i++) {
                long due = started + (long) (i * 1e9 / rps);
                long wait = due - System.nanoTime();
                if (wait > 0) {
                    Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                }
                String session = "load-" + UUID.randomUUID();
                turns.add(CompletableFuture.supplyAsync(() -> {
                    long t0 = System.nanoTime();
                    try {
                        NegotiationReply reply = agent.negotiate(new NegotiationTurn(session, null, "Стол на двоих сегодня в 19:00",
                                null, "Гость", null, "Бронируем?", "t-1", true));
                        return new Turn(System.nanoTime() - t0, reply.fallback(), false);
                    } catch (RuntimeException e) {
                        return new Turn(System.nanoTime() - t0, false, true);
                    }
                }, executor));
            }
            long sentNanos = System.nanoTime() - started;
            List<Turn> done = turns.stream().map(CompletableFuture::join).toList();
            double wall = (System.nanoTime() - started) / 1e9;
            double[] sorted = done.stream().mapToDouble(t -> t.nanos() / 1e9).sorted().toArray();
            return new Row(kind, rps, done.size(), done.size() > 1 ? (done.size() - 1) / (sentNanos / 1e9) : done.size(),
                    pct(sorted, 0.5), pct(sorted, 0.95), pct(sorted, 0.99), sorted.length == 0 ? 0 : sorted[sorted.length - 1],
                    done.stream().filter(t -> !t.fallback() && !t.error()).count(), done.stream().filter(Turn::fallback).count(),
                    done.stream().filter(Turn::error).count(), (modelRequests.get() - models) / wall,
                    (processCpuNanos() - processCpu0) / (wall * 1e9) * 100);
        }
    }

    private static double processCpuNanos() {
        return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
    }

    private static double pct(double[] sorted, double p) {
        return sorted.length == 0 ? 0 : sorted[Math.min(sorted.length - 1, (int) Math.ceil(p * sorted.length) - 1)];
    }

    private static List<Integer> rates(String property, String fallback) {
        String value = System.getProperty(property, fallback);
        return value.isBlank() ? List.of() : java.util.Arrays.stream(value.split(",")).map(String::trim).map(Integer::parseInt).toList();
    }

    private void report(List<Row> rows) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("# Butler: нагрузка на переговорный путь агента (заглушка модели)\n\n")
                .append("Дата: ").append(Instant.now()).append(". Java ").append(Runtime.version()).append(", ")
                .append(System.getProperty("os.name")).append('/').append(System.getProperty("os.arch"))
                .append(", ядер: ").append(Runtime.getRuntime().availableProcessors()).append(". Профиль задержки модели: ").append(profile)
                .append(". Длительность ступени: ").append(seconds).append(" с. Открытая модель нагрузки.\n\n")
                .append("RPS — переговорных ходов в секунду (ButlerAgent.negotiate); на каждый: 3 вызова модели через HTTP и бронь через ")
                .append("идемпотентный инструмент. Задержки — время хода, секунды.\n\n")
                .append("| Тип | Цель RPS | Отправлено | Факт RPS | p50 | p95 | p99 | max | Ответ | Fallback | Ошибки | Вызовов модели/с | CPU % ядра |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Row r : rows) {
            out.append(String.format(Locale.ROOT, "| %s | %d | %d | %.2f | %.2f | %.2f | %.2f | %.2f | %d | %d | %d | %.1f | %.0f |%n",
                    r.kind(), r.rps(), r.sent(), r.achieved(), r.p50(), r.p95(), r.p99(), r.max(), r.answered(), r.fallback(), r.errors(),
                    r.modelRps(), r.cpu()));
        }
        out.append(String.format(Locale.ROOT, "%nПамять кучи после прогона: %d МБ.%n",
                (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024));
        System.out.println(out);
        Path dir = Path.of("target", "load");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("butler-agent-" + profile + ".md"), out, StandardCharsets.UTF_8);
    }
}
