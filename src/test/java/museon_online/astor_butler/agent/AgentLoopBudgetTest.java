package museon_online.astor_butler.agent;

import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityRegistry;
import museon_online.astor_butler.model.ModelChatMessage;
import museon_online.astor_butler.model.ModelChatResponse;
import museon_online.astor_butler.model.ModelUnavailableException;
import museon_online.astor_butler.model.ToolCallingModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The loop with the real budget on H2: what a model call costs by how it ended, and that no database connection is
 * held while the model thinks (the 16.10 load exhausted a 10-connection pool).
 */
class AgentLoopBudgetTest {

    private static final CapabilityContext CTX = new CapabilityContext(CapabilityContext.CONCIERGE, null, "c-1", null,
            "c-1:1", "Мария", null);

    private final AtomicInteger openConnections = new AtomicInteger();
    private Connection schema;
    private JdbcTemplate jdbc;
    private ToolCallingModel model;
    private AgentProperties properties;
    private AgentBudget budget;
    private AgentLoop loop;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource h2 = new DriverManagerDataSource(
                "jdbc:h2:mem:agent-loop-budget-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        schema = h2.getConnection();
        ScriptUtils.executeSqlScript(schema, new ClassPathResource("db/changelog/2026-10-16-agent-budget.sql"));
        jdbc = new JdbcTemplate(new CountingDataSource(h2, openConnections));
        budget = new AgentBudget(new BigDecimal("100"), new BigDecimal("3"), ZoneId.of("Asia/Yekaterinburg"), Clock.systemUTC(), jdbc);
        model = mock(ToolCallingModel.class);
        properties = new AgentProperties();
        loop = new AgentLoop(model, mock(CapabilityRegistry.class), properties);
        loop.setBudget(budget);
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Connection connection = schema; var statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        }
    }

    private static List<ModelChatMessage> history() {
        return List.of(ModelChatMessage.system("Ты Батлер"), ModelChatMessage.user("Стол на двоих в 13:00"));
    }

    private static ModelChatResponse answer(double costRub) {
        return new ModelChatResponse("Есть стол в 13:00.", Optional.empty(), "openai-compatible", "gigachat-3-pro",
                Duration.ZERO, Map.of("costRub", costRub));
    }

    private double reserved() {
        return jdbc.queryForObject("SELECT reserved_rub FROM agent_budget_days", BigDecimal.class).doubleValue();
    }

    @Test
    void noDatabaseConnectionIsHeldWhileTheModelThinks() {
        List<Integer> seen = new CopyOnWriteArrayList<>();
        when(model.chat(any())).thenAnswer(invocation -> {
            seen.add(openConnections.get());
            return answer(0.12);
        });

        AgentOutcome outcome = loop.run(history(), List.of(), CTX);

        assertThat(outcome.fallback()).isFalse();
        assertThat(seen).containsExactly(0);
        assertThat(openConnections.get()).isZero();
        assertThat(budget.spentToday()).isEqualTo(0.12, within(1e-9));
    }

    @Test
    void anHttpErrorWithoutAPriceGivesTheReservationBackAtOnce() {
        when(model.chat(any())).thenThrow(new ModelUnavailableException("HTTP 503",
                HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "busy", new HttpHeaders(), new byte[0], null)));

        AgentOutcome outcome = loop.run(history(), List.of(), CTX);

        assertThat(outcome.fallback()).isTrue();
        assertThat(budget.spentToday()).isZero();
    }

    @Test
    void aCallCutOffByTheTimeoutIsChargedWhatItsLateAnswerCost() throws Exception {
        properties.setModelTimeoutMs(50);
        when(model.chat(any())).thenAnswer(invocation -> {
            Thread.sleep(400);
            return answer(0.2);
        });

        AgentOutcome outcome = loop.run(history(), List.of(), CTX);

        assertThat(outcome.fallback()).isTrue();
        assertThat(reserved()).as("held while the call still runs").isEqualTo(3.0, within(1e-9));
        awaitSettled();
        assertThat(budget.spentToday()).isEqualTo(0.2, within(1e-9));
    }

    @Test
    void aCallCutOffByTheTimeoutThatThenTimesOutInTheGatewayIsReleased() throws Exception {
        properties.setModelTimeoutMs(50);
        when(model.chat(any())).thenAnswer(invocation -> {
            Thread.sleep(400);
            throw new ModelUnavailableException("did not answer in time",
                    new ResourceAccessException("read timed out", new SocketTimeoutException("Read timed out")));
        });

        AgentOutcome outcome = loop.run(history(), List.of(), CTX);

        assertThat(outcome.fallback()).isTrue();
        awaitSettled();
        assertThat(budget.spentToday()).isZero();
    }

    private void awaitSettled() throws InterruptedException {
        for (int i = 0; i < 100 && reserved() > 0; i++) {
            Thread.sleep(30);
        }
        assertThat(reserved()).as("the late call settled its reservation").isZero();
    }

    /** Counts the connections taken from the pool and not yet given back. */
    private static final class CountingDataSource extends DelegatingDataSource {
        private final AtomicInteger open;

        CountingDataSource(javax.sql.DataSource target, AtomicInteger open) {
            super(target);
            this.open = open;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = super.getConnection();
            open.incrementAndGet();
            AtomicInteger closed = new AtomicInteger();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if ("close".equals(method.getName()) && closed.getAndIncrement() == 0) {
                            open.decrementAndGet();
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }
}
