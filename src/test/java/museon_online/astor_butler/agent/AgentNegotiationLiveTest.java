package museon_online.astor_butler.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityProperties;
import museon_online.astor_butler.capability.CapabilityRegistry;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.capability.CapabilityTool;
import museon_online.astor_butler.capability.IdempotencyStore;
import museon_online.astor_butler.capability.tools.BillRequestTool;
import museon_online.astor_butler.capability.tools.CapabilityFixtures;
import museon_online.astor_butler.capability.tools.LunchMenuTool;
import museon_online.astor_butler.capability.tools.LunchOrderPlaceTool;
import museon_online.astor_butler.capability.tools.ReservationAccess;
import museon_online.astor_butler.capability.tools.ReservationCancelTool;
import museon_online.astor_butler.capability.tools.ReservationChangeTool;
import museon_online.astor_butler.capability.tools.ReservationCreateTool;
import museon_online.astor_butler.capability.tools.ReviewSubmitTool;
import museon_online.astor_butler.capability.tools.TableAvailabilityTool;
import museon_online.astor_butler.capability.tools.TableRequestTool;
import museon_online.astor_butler.capability.tools.TipCreateTool;
import museon_online.astor_butler.capability.tools.VenueInfoTool;
import museon_online.astor_butler.capability.tools.VisitStatusTool;
import museon_online.astor_butler.domain.journey.GuestJourneyProperties;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.journey.talk.VenueFactsCatalog;
import museon_online.astor_butler.domain.ledger.LedgerWriter;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
import museon_online.astor_butler.domain.summary.AgentLessonRepository;
import museon_online.astor_butler.domain.summary.GuestMemory;
import museon_online.astor_butler.model.OpenAiCompatibleModelGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAdder;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A LIMITED live check of the Butler's negotiation over the real OpenAI-compatible API; off unless {@code -Dastor.live=true}
 * and {@code OPENAI_COMPATIBLE_BASE_URL} / {@code OPENAI_COMPATIBLE_API_KEY} are in the environment (never printed):
 * <pre>
 * mvn -o test -Dtest=AgentNegotiationLiveTest -Dastor.live=true -Dastor.live.model=gigachat-3-pro -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * The prompt is the production one (ButlerPrompt with the AERIS facts) and the model sees every production tool schema,
 * but each tool answers with synthetic facts: nothing is booked, ordered or paid, and no guest data is sent. Only
 * {@code reservation_create} is the real tool over a mock booking service, so its guard (the guest's yes to the table
 * offered just before) decides as in production. Three synthetic negotiation turns, at most six. The report (tokens, latency, the cost the service charged per call from its
 * {@code x-litellm-response-cost} header) goes to stdout and target/load/.
 */
@EnabledIfSystemProperty(named = "astor.live", matches = "true")
@EnabledIfEnvironmentVariable(named = "OPENAI_COMPATIBLE_API_KEY", matches = ".+")
class AgentNegotiationLiveTest {

    private static final Map<String, Map<String, Object>> FACTS = Map.of(
            "venue_info", Map.of("name", "AERIS", "parking", "городская платная парковка рядом"));

    @Test
    void threeSyntheticNegotiationTurns() throws Exception {
        String model = System.getProperty("astor.live.model", "gigachat-3-pro");
        DoubleAdder cost = new DoubleAdder();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger unpriced = new AtomicInteger();
        ClientHttpRequestInterceptor meter = (request, body, execution) -> {
            var response = execution.execute(request, body);
            calls.incrementAndGet();
            String charged = response.getHeaders().getFirst("x-litellm-response-cost");
            if (charged == null) {
                unpriced.incrementAndGet();
            } else {
                cost.add(Double.parseDouble(charged));
            }
            return response;
        };
        // HttpURLConnection: on a Mac without Netty's native DNS library Reactor Netty asks public DNS over IPv6.
        OpenAiCompatibleModelGateway gateway = new OpenAiCompatibleModelGateway(new RestTemplateBuilder()
                .requestFactory(SimpleClientHttpRequestFactory::new).additionalInterceptors(meter),
                System.getenv("OPENAI_COMPATIBLE_BASE_URL"), System.getenv("OPENAI_COMPATIBLE_API_KEY"), model, "", "", "",
                15_000, 512, 0.1, true, "");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:agent-live-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection schema = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(schema, new ClassPathResource("db/changelog/2026-10-15-capability-calls.sql"));
            CapabilityProperties capabilities = new CapabilityProperties();
            capabilities.setEnabled(true);
            CapabilityRegistry registry = new CapabilityRegistry(productionToolSchemas(capabilities), mock(LedgerWriter.class), capabilities,
                    new IdempotencyStore(new JdbcTemplate(dataSource), new ObjectMapper()));
            AgentProperties properties = new AgentProperties();
            properties.setEnabled(true);
            properties.setModel(model);
            SimpleMeterRegistry meters = new SimpleMeterRegistry();
            AgentLoop loop = new AgentLoop(gateway, registry, properties);
            loop.setDefaultProvider("openai-compatible");
            loop.setMeterRegistry(meters);
            VisitTurnRepository turns = mock(VisitTurnRepository.class);
            when(turns.visitOfSession(anyString())).thenReturn(Optional.empty());
            ButlerAgent agent = new ButlerAgent(loop, registry, properties, mock(AgentLessonRepository.class), mock(GuestMemory.class),
                    new VenueFactsCatalog(new ObjectMapper()), capabilities, mock(LedgerWriter.class), turns, mock(GuestVisitRepository.class));

            // -Dastor.live.repeats=N: the first turn only ("is there a table?"), N times in fresh sessions, to count how
            // often the model books before the guest agreed. At most 6.
            int repeats = Math.min(6, Integer.getInteger("astor.live.repeats", 0));
            if (repeats > 0) {
                int premature = 0;
                int refusedCalls = 0;
                StringBuilder first = new StringBuilder();
                for (int r = 0; r < repeats; r++) {
                    NegotiationReply reply = agent.negotiate(new NegotiationTurn("live-" + UUID.randomUUID(), null,
                            "Хочу пообедать сегодня в 13:00 вдвоём", null, "Анна", null,
                            "Гость хочет пообедать сегодня в 13:00 вдвоём. Есть стол?", "t-1", false));
                    List<String> used = reply.facts().stream().map(fact -> fact.get("tool") + (Boolean.TRUE.equals(fact.get("ok")) ? "" : ":refused")).toList();
                    // Only a request the tool actually created counts; a refused call (a guard) is not a booking.
                    if (used.contains("reservation_create")) {
                        premature++;
                    }
                    if (used.contains("reservation_create:refused")) {
                        refusedCalls++;
                    }
                    first.append(String.format(Locale.ROOT, "| %d | %s | %s |%n", r + 1, reply.fallback(), used));
                }
                String report = "# Butler: первый ход «Есть стол?» ×" + repeats + ", модель " + model + "\n\n"
                        + "| Повтор | Fallback | Инструменты |\n|---:|---|---|\n" + first
                        + String.format(Locale.ROOT, "%nЗаявка создана до согласия гостя: %d из %d; вызов отклонён инструментом: %d. Стоимость %.4f ₽, вызовов %d.%n",
                        premature, repeats, refusedCalls, cost.sum(), calls.get());
                System.out.println(report);
                Files.createDirectories(Path.of("target", "load"));
                Files.writeString(Path.of("target", "load", "butler-live-first-turn-" + model + ".md"), report, StandardCharsets.UTF_8);
                return;
            }
            String session = "live-" + UUID.randomUUID();
            List<String[]> dialog = List.of(
                    new String[]{"Хочу пообедать сегодня в 13:00 вдвоём", "Гость хочет пообедать сегодня в 13:00 вдвоём. Есть стол?"},
                    new String[]{"Да, давайте 13:00, я Анна Тестова", "Гость согласен на 13:00, имя Анна Тестова. Оформляем заявку."},
                    new String[]{"А парковка у вас есть?", "Гость спрашивает про парковку."});
            StringBuilder out = new StringBuilder("# Butler: живая проверка переговоров (синтетика)\n\n")
                    .append("Дата: ").append(Instant.now()).append(". Модель: ").append(model)
                    .append(". Промпт и схемы инструментов — боевые; факты инструментов — синтетические, броней нет.\n\n")
                    .append("| Ход | Fallback | Инструменты | Вызовов модели | Токены вход | Токены выход | Время, с | Стоимость по заголовку, ₽ |\n")
                    .append("|---:|---|---|---:|---:|---:|---:|---:|\n");
            double tokensIn0 = 0;
            double tokensOut0 = 0;
            for (int i = 0; i < Math.min(6, dialog.size()); i++) {
                int calls0 = calls.get();
                double cost0 = cost.sum();
                long started = System.nanoTime();
                NegotiationReply reply = agent.negotiate(new NegotiationTurn(session, null, dialog.get(i)[0], null, "Анна", null,
                        dialog.get(i)[1], "t-" + (i + 1), false));
                List<String> used = reply.facts().stream().map(fact -> fact.get("tool") + (Boolean.TRUE.equals(fact.get("ok")) ? "" : ":refused")).toList();
                double seconds = (System.nanoTime() - started) / 1e9;
                double in = tokens(meters, "in");
                double outTokens = tokens(meters, "out");
                out.append(String.format(Locale.ROOT, "| %d | %s | %s | %d | %.0f | %.0f | %.2f | %.4f |%n", i + 1, reply.fallback(),
                        used, calls.get() - calls0,
                        in - tokensIn0, outTokens - tokensOut0, seconds, cost.sum() - cost0));
                tokensIn0 = in;
                tokensOut0 = outTokens;
            }
            out.append(String.format(Locale.ROOT, "%nИтого: вызовов модели %d (без цены в заголовке: %d), токенов %.0f / %.0f, стоимость %.4f ₽.%n",
                    calls.get(), unpriced.get(), tokensIn0, tokensOut0, cost.sum()));
            System.out.println(out);
            Path dir = Path.of("target", "load");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("butler-live-" + model + ".md"), out, StandardCharsets.UTF_8);
        }
    }

    /** The real tool's shape: the asked slot is free, so it is an offer the guest's next yes can book. */
    private static CapabilityResult freeAsAsked(Map<String, Object> args) {
        Map<String, Object> facts = new java.util.LinkedHashMap<>();
        facts.put("date", args.get("date"));
        facts.put("time", args.get("time"));
        facts.put("partySize", args.get("partySize"));
        facts.put("open", true);
        facts.put("free", List.of(Map.of("tableCode", "T5", "name", "Стол у окна", "capacity", 4)));
        facts.put("alternatives", List.of());
        return CapabilityResult.ok(facts);
    }

    private static double tokens(SimpleMeterRegistry meters, String direction) {
        var counter = meters.find("astor.agent.model.tokens").tag("direction", direction).counter();
        return counter == null ? 0 : counter.count();
    }

    /** Every production tool's name, description and schema; synthetic facts instead of the real services. */
    private static List<CapabilityTool> productionToolSchemas(CapabilityProperties capabilities) {
        GuestJourneyProperties journey = new GuestJourneyProperties();
        ReservationAccess access = mock(ReservationAccess.class);
        List<CapabilityTool> real = List.of(
                new TableAvailabilityTool(mock(museon_online.astor_butler.domain.booking.TableReservationService.class),
                        mock(museon_online.astor_butler.domain.booking.VenueOpeningHours.class), capabilities, journey),
                new ReservationCreateTool(bookingService(), mock(GuestVisitRepository.class),
                        mock(museon_online.astor_butler.domain.journey.JourneyEvents.class), mock(LedgerWriter.class), capabilities, journey),
                new ReservationChangeTool(mock(museon_online.astor_butler.domain.booking.TableReservationService.class), access, journey),
                new ReservationCancelTool(mock(museon_online.astor_butler.domain.booking.TableReservationService.class), access),
                new VenueInfoTool(new VenueFactsCatalog(new ObjectMapper()), capabilities),
                new VisitStatusTool(mock(GuestVisitRepository.class),
                        mock(museon_online.astor_butler.domain.booking.TableReservationRepository.class), journey),
                new LunchMenuTool(mock(museon_online.astor_butler.domain.lunch.BusinessLunchCatalog.class),
                        mock(museon_online.astor_butler.integration.saby.SabyNutritionService.class), capabilities, journey),
                new LunchOrderPlaceTool(mock(museon_online.astor_butler.domain.lunch.BusinessLunchService.class),
                        mock(museon_online.astor_butler.domain.lunch.BusinessLunchCatalog.class), capabilities, access, journey),
                new TableRequestTool(mock(museon_online.astor_butler.domain.journey.talk.TableRequestRouter.class), access, mock(GuestVisitRepository.class)),
                new BillRequestTool(mock(museon_online.astor_butler.domain.billing.GuestBillRequestService.class), access),
                new TipCreateTool(mock(museon_online.astor_butler.domain.tip.TipService.class),
                        mock(museon_online.astor_butler.domain.journey.talk.TableRequestRouter.class), access),
                new ReviewSubmitTool(mock(museon_online.astor_butler.domain.billing.VisitReviewService.class), access));
        return real.stream().<CapabilityTool>map(tool -> tool instanceof ReservationCreateTool ? tool : new CapabilityTool() {
            public String name() { return tool.name(); }
            public String description() { return tool.description(); }
            public Map<String, Object> parameters() { return tool.parameters(); }
            public boolean commitment() { return tool.commitment(); }
            public CapabilityResult invoke(CapabilityContext ctx, Map<String, Object> args) {
                return "table_availability".equals(tool.name()) ? freeAsAsked(args)
                        : CapabilityResult.ok(FACTS.getOrDefault(tool.name(), Map.of("accepted", true)));
            }
        }).toList();
    }

    /** A booking service that books nothing: every request is a synthetic order waiting for the hostess. */
    private static museon_online.astor_butler.domain.booking.TableReservationService bookingService() {
        var service = mock(museon_online.astor_butler.domain.booking.TableReservationService.class);
        when(service.createReservation(org.mockito.ArgumentMatchers.any())).thenReturn(CapabilityFixtures.order(9001L, null,
                museon_online.astor_butler.domain.booking.TableReservationStatus.AWAITING_MANAGER_CONFIRMATION));
        return service;
    }
}
