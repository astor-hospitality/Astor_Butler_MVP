package museon_online.astor_butler.agent;

import museon_online.astor_butler.domain.calllog.ModelTariff;
import museon_online.astor_butler.model.ModelChatResponse;
import museon_online.astor_butler.model.ModelUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** The budget against the real migration on H2: reservations, settlement, restart, midnight, parallel calls. */
@ExtendWith(OutputCaptureExtension.class)
class AgentBudgetTest {

    private static final ZoneId VENUE = ZoneId.of("Asia/Yekaterinburg");

    private Connection schema;
    private JdbcTemplate jdbc;
    private final MovableClock clock = new MovableClock(Instant.parse("2026-10-15T08:00:00Z"));

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:agent-budget-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        schema = dataSource.getConnection();
        ScriptUtils.executeSqlScript(schema, new ClassPathResource("db/changelog/2026-10-16-agent-budget.sql"));
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Connection connection = schema; var statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        }
    }

    private AgentBudget budget(String limit, String reserve) {
        return new AgentBudget(new BigDecimal(limit), new BigDecimal(reserve), VENUE, clock, jdbc);
    }

    private static ModelChatResponse charged(double rub) {
        return new ModelChatResponse("ok", Optional.empty(), "openai-compatible", "gigachat-3-pro", Duration.ZERO,
                Map.of("costRub", rub));
    }

    private static final class MovableClock extends Clock {
        private volatile Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void withoutALimitNothingIsReservedOrWritten() {
        AgentBudget budget = budget("0", "1");

        AgentBudget.Reservation reservation = budget.reserve();
        budget.settle(reservation, charged(1_000_000));

        assertThat(budget.limited()).isFalse();
        assertThat(reservation).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_budget_days", Integer.class)).isZero();
    }

    @Test
    void aCallReservesFirstAndThenCostsWhatTheServiceCharged() {
        AgentBudget budget = budget("10", "1");

        AgentBudget.Reservation reservation = budget.reserve();
        assertThat(budget.spentToday()).isEqualTo(1.0, within(1e-9));
        budget.settle(reservation, charged(0.25));

        assertThat(budget.spentToday()).isEqualTo(0.25, within(1e-9));
    }

    @Test
    void anUnknownPriceOrAnErrorThatSaysNothingKeepsTheWholeReservation() {
        AgentBudget budget = budget("10", "1");
        ModelChatResponse unpriced = new ModelChatResponse("ok", Optional.empty(), "openai-compatible", "no-tariff-model",
                Duration.ZERO, Map.of("usage", Map.of("prompt_tokens", 1000, "completion_tokens", 10)));

        budget.settle(budget.reserve(), unpriced);
        budget.settle(budget.reserve(), null);
        budget.settleFailure(budget.reserve(), new CompletionException(new IllegalArgumentException("unreadable answer")));

        assertThat(budget.spentToday()).isEqualTo(3.0, within(1e-9));
    }

    @Test
    void aTimeoutAnHttpErrorOrACallNeverSentGivesTheReservationBack() {
        AgentBudget budget = budget("10", "3");

        budget.settleFailure(budget.reserve(), new CompletionException(new TimeoutException()));
        budget.settleFailure(budget.reserve(), new ModelUnavailableException("did not answer in time",
                new ResourceAccessException("read timed out", new SocketTimeoutException("Read timed out"))));
        budget.settleFailure(budget.reserve(), new ModelUnavailableException("could not be reached",
                new ResourceAccessException("refused", new ConnectException("Connection refused"))));
        budget.settleFailure(budget.reserve(), new ModelUnavailableException("HTTP 503",
                HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "busy", new HttpHeaders(), new byte[0], null)));
        budget.settleFailure(budget.reserve(), new IllegalStateException("HTTP 400",
                HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "bad", new HttpHeaders(), new byte[0], null)));
        budget.settleFailure(budget.reserve(), new IllegalStateException(AgentBudget.NOT_SENT));
        budget.release(budget.reserve());

        assertThat(budget.spentToday()).isZero();
        assertThat(jdbc.queryForObject("SELECT reserved_rub FROM agent_budget_days", BigDecimal.class)).isEqualByComparingTo("0");
    }

    @Test
    void anHttpErrorThatTheProviderChargedCostsWhatItSaid() {
        AgentBudget budget = budget("10", "3");
        HttpHeaders headers = new HttpHeaders();
        headers.set(AgentBudget.COST_HEADER, "0.042");

        budget.settleFailure(budget.reserve(), new ModelUnavailableException("HTTP 429",
                HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "slow down", headers, new byte[0], null)));

        assertThat(budget.spentToday()).isEqualTo(0.042, within(1e-9));
    }

    @Test
    void withoutTheServicesChargeTheTariffPricesTheTokens() {
        AgentBudget budget = budget("1000", "1");
        budget.setTariff(new ModelTariff());
        ModelChatResponse gigachat = new ModelChatResponse("ok", Optional.empty(), "gigachat", "GigaChat-2-Pro", Duration.ZERO,
                Map.of("usage", Map.of("prompt_tokens", 1_000, "completion_tokens", 0)));

        budget.settle(budget.reserve(), gigachat);

        // GigaChat-2-Pro: 500 ₽ per 1M tokens.
        assertThat(budget.spentToday()).isEqualTo(0.5, within(1e-9));
    }

    @Test
    void theLimitCountsOpenReservationsSoNoCallStartsBeyondIt(CapturedOutput output) {
        AgentBudget budget = budget("2.5", "1");

        AgentBudget.Reservation first = budget.reserve();
        AgentBudget.Reservation second = budget.reserve();
        AgentBudget.Reservation third = budget.reserve();

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(third).as("2 ₽ held, 1 ₽ more would pass 2.5 ₽").isNull();
        assertThat(output.getAll()).contains("Agent daily model budget is spent");
        budget.settle(first, charged(0.1));
        assertThat(budget.reserve()).as("the settled call freed 0.9 ₽").isNotNull();
    }

    @Test
    void parallelCallsNeverReserveBeyondTheLimit() throws Exception {
        AgentBudget budget = budget("10", "1");
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AgentBudget.Reservation>> calls = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            calls.add(pool.submit(() -> {
                start.await();
                return budget.reserve();
            }));
        }
        start.countDown();
        int granted = 0;
        for (Future<AgentBudget.Reservation> call : calls) {
            if (call.get() != null) {
                granted++;
            }
        }
        pool.shutdown();

        assertThat(granted).isEqualTo(10);
        assertThat(budget.spentToday()).isEqualTo(10.0, within(1e-9));
    }

    @Test
    void aRestartKeepsTheDaysSpendAndItsOpenReservations() {
        AgentBudget before = budget("10", "1");
        before.settle(before.reserve(), charged(4));
        before.reserve(); // in flight when the process died

        AgentBudget after = budget("10", "1");

        assertThat(after.spentToday()).isEqualTo(5.0, within(1e-9));
    }

    @Test
    void aCallStartedBeforeMidnightIsChargedToItsOwnDayAndTheNextDayStartsEmpty() {
        clock.now = Instant.parse("2026-10-15T18:59:30Z"); // 23:59:30 in Yekaterinburg
        AgentBudget budget = budget("10", "1");
        AgentBudget.Reservation late = budget.reserve();

        clock.now = Instant.parse("2026-10-15T19:00:30Z"); // 00:00:30 the next venue day
        budget.settle(late, charged(0.4));

        assertThat(budget.spentToday()).isZero();
        assertThat(jdbc.queryForObject("SELECT spent_rub FROM agent_budget_days WHERE budget_day = DATE '2026-10-15'", BigDecimal.class))
                .isEqualByComparingTo("0.4");
    }

    @Test
    void anUnreadableBudgetGivesNoReservation(CapturedOutput output) {
        jdbc.execute("DROP TABLE agent_budget_days");

        assertThat(budget("10", "1").reserve()).isNull();
        assertThat(output.getAll()).contains("Agent daily model budget cannot be checked");
    }

    @Test
    void aLimitThatIsNotANumberStopsTheStart() {
        assertThatThrownBy(() -> new AgentBudget("тысяча", "1", "Asia/Yekaterinburg", jdbc))
                .hasMessageContaining("ASTOR_AGENT_DAILY_BUDGET_RUB");
    }
}
