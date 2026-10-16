package museon_online.astor_butler.agent;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import museon_online.astor_butler.domain.calllog.ModelTariff;
import museon_online.astor_butler.model.ModelChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * The agent's daily model budget ({@code ASTOR_AGENT_DAILY_BUDGET_RUB}; blank or 0 — no limit) in
 * {@code agent_budget_days}, per venue day ({@code astor.guest-journey.zone-id}).
 *
 * <p>Every model call first {@link #reserve reserves} its worst-case price ({@code ASTOR_AGENT_CALL_RESERVE_RUB}, 1 ₽):
 * one conditional UPDATE, under a lock within this process, so parallel calls never pass the limit together. The answer
 * then {@link #settle settles} the reservation: what the service charged ({@code costRub} from
 * {@code x-litellm-response-cost}), else the call log tariff for the reported tokens, else — an answer of unknown
 * price — the whole reservation. A call that ended without an answer is {@link #settleFailure settled by its error}:
 * an HTTP error is charged what its {@code x-litellm-response-cost} says (nothing without it), a call that was never
 * sent, could not connect or timed out is released; only an error that says nothing of the outcome keeps the whole
 * reservation. Counts survive restarts; a reservation left by a crash stays counted until the day ends. If the table cannot be read or written, no reservation is given: the agent falls back (fails closed).
 * A reached limit means no model calls until the next venue day; turns fall back to the scenarios, never to another model.
 */
@Component
public class AgentBudget {

    static final String SCOPE = "butler-agent";
    /** The provider's charge for a call, also on its error answers. */
    public static final String COST_HEADER = "x-litellm-response-cost";
    /** The message of the error AgentLoop raises when no time is left for a call it then never sends. */
    static final String NOT_SENT = "turn_timeout";
    private static final Logger log = LoggerFactory.getLogger(AgentBudget.class);
    private static final Reservation UNLIMITED = new Reservation(null, BigDecimal.ZERO);

    /** A held reservation; {@code day} is null without a limit. */
    public record Reservation(LocalDate day, BigDecimal amount) {
    }

    private final BigDecimal limit;
    private final BigDecimal reserve;
    private final ZoneId zone;
    private final Clock clock;
    private final JdbcTemplate jdbc;
    private ModelTariff tariff;
    private LocalDate warnedNearLimit;
    private LocalDate warnedExhausted;

    @Autowired
    public AgentBudget(@Value("${astor.agent.daily-budget-rub:}") String limitRub,
                       @Value("${astor.agent.call-reserve-rub:1}") String reserveRub,
                       @Value("${astor.guest-journey.zone-id:Asia/Yekaterinburg}") String zone,
                       JdbcTemplate jdbc) {
        this(parse(limitRub, "ASTOR_AGENT_DAILY_BUDGET_RUB"), parse(reserveRub, "ASTOR_AGENT_CALL_RESERVE_RUB"), ZoneId.of(zone),
                Clock.systemUTC(), jdbc);
    }

    AgentBudget(BigDecimal limit, BigDecimal reserve, ZoneId zone, Clock clock, JdbcTemplate jdbc) {
        this.limit = limit.max(BigDecimal.ZERO);
        this.reserve = reserve.signum() > 0 ? reserve : BigDecimal.ONE;
        this.zone = zone;
        this.clock = clock;
        this.jdbc = jdbc;
        if (limited()) {
            log.info("Agent daily model budget: {} RUB per venue day, {} RUB reserved per call", this.limit, this.reserve);
        }
    }

    @Autowired(required = false)
    public void setTariff(ModelTariff tariff) {
        this.tariff = tariff;
    }

    @Autowired(required = false)
    public void setMeterRegistry(MeterRegistry meters) {
        Gauge.builder("astor.agent.budget.spent", this, AgentBudget::spentToday)
                .description("Agent model spend today including open reservations, RUB").baseUnit("rub").register(meters);
        Gauge.builder("astor.agent.budget.limit", this, budget -> budget.limit.doubleValue())
                .description("Agent daily model budget, RUB (0 — no limit)").baseUnit("rub").register(meters);
    }

    public boolean limited() {
        return limit.signum() > 0;
    }

    /** A reservation for one model call, or null when the day's budget has no room (or cannot be checked). */
    public synchronized Reservation reserve() {
        if (!limited()) {
            return UNLIMITED;
        }
        LocalDate day = today();
        try {
            ensureRow(day);
            int updated = jdbc.update("""
                    UPDATE agent_budget_days SET reserved_rub = reserved_rub + ?, updated_at = CURRENT_TIMESTAMP
                    WHERE scope = ? AND budget_day = ? AND spent_rub + reserved_rub + ? <= ?""",
                    reserve, SCOPE, Date.valueOf(day), reserve, limit);
            if (updated == 1) {
                return new Reservation(day, reserve);
            }
            if (!day.equals(warnedExhausted)) {
                warnedExhausted = day;
                log.warn("Agent daily model budget is spent: {} RUB; agent turns fall back until the next day", limit);
            }
            return null;
        } catch (RuntimeException e) {
            log.warn("Agent daily model budget cannot be checked ({}); the turn falls back", e.getClass().getSimpleName());
            return null;
        }
    }

    /** Turns the reservation into what the call cost; {@code response} null (an answer that says nothing) keeps all of it. */
    public void settle(Reservation reservation, ModelChatResponse response) {
        if (reservation == null || reservation.day() == null) {
            return;
        }
        BigDecimal cost = cost(response);
        charge(reservation, cost == null ? reservation.amount() : cost);
    }

    /** Hands the whole reservation back: the call was never sent, so it cost nothing. */
    public void release(Reservation reservation) {
        if (reservation == null || reservation.day() == null) {
            return;
        }
        charge(reservation, BigDecimal.ZERO);
    }

    /**
     * Settles a call that ended with {@code error} instead of an answer: {@link #failureCost} decides what it cost, the
     * whole reservation only when the error says nothing of the outcome.
     */
    public void settleFailure(Reservation reservation, Throwable error) {
        if (reservation == null || reservation.day() == null) {
            return;
        }
        charge(reservation, failureCost(error, reservation.amount()));
    }

    /**
     * What a failed call cost. An HTTP error answer is the provider's word: its {@code x-litellm-response-cost}, else
     * nothing. A call that timed out, could not connect or was refused before sending is nothing too: the gateway
     * reports no cost for it (the 16.10 load counted 517 ₽ against ~140 ₽ really charged). Any other error says nothing
     * of what reached the provider and keeps the {@code reserved} amount.
     */
    static BigDecimal failureCost(Throwable error, BigDecimal reserved) {
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 12; depth++, cause = cause.getCause()) {
            if (cause instanceof HttpStatusCodeException http) {
                BigDecimal charged = http.getResponseHeaders() == null ? null : charged(http.getResponseHeaders().getFirst(COST_HEADER));
                return charged == null ? BigDecimal.ZERO : charged;
            }
            if (cause instanceof TimeoutException || cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException
                    || cause instanceof ConnectException || cause instanceof UnknownHostException
                    || cause instanceof ResourceAccessException || NOT_SENT.equals(cause.getMessage())) {
                return BigDecimal.ZERO;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return reserved;
    }

    private static BigDecimal charged(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            BigDecimal cost = new BigDecimal(header.trim());
            return cost.signum() >= 0 ? cost : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private synchronized void charge(Reservation reservation, BigDecimal charged) {
        try {
            jdbc.update("""
                    UPDATE agent_budget_days
                    SET reserved_rub = GREATEST(reserved_rub - ?, 0), spent_rub = spent_rub + ?, updated_at = CURRENT_TIMESTAMP
                    WHERE scope = ? AND budget_day = ?""",
                    reservation.amount(), charged, SCOPE, Date.valueOf(reservation.day()));
            BigDecimal spent = spent(reservation.day());
            if (!reservation.day().equals(warnedNearLimit) && spent.compareTo(limit.multiply(new BigDecimal("0.8"))) >= 0) {
                warnedNearLimit = reservation.day();
                log.warn("Agent daily model budget at 80%: {} of {} RUB", spent.setScale(2, RoundingMode.HALF_UP), limit);
            }
        } catch (RuntimeException e) {
            // The reservation stays counted: the day is overestimated, never underestimated.
            log.warn("Agent model call was not settled ({}); its reservation stays counted", e.getClass().getSimpleName());
        }
    }

    /** Spent plus open reservations today; NaN when it cannot be read. */
    public double spentToday() {
        if (!limited()) {
            return 0;
        }
        try {
            return spent(today()).doubleValue();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private BigDecimal spent(LocalDate day) {
        return jdbc.query("SELECT spent_rub + reserved_rub FROM agent_budget_days WHERE scope = ? AND budget_day = ?",
                (rs, row) -> rs.getBigDecimal(1), SCOPE, Date.valueOf(day)).stream().findFirst().orElse(BigDecimal.ZERO);
    }

    private void ensureRow(LocalDate day) {
        try {
            jdbc.update("INSERT INTO agent_budget_days(scope, budget_day) VALUES (?, ?)", SCOPE, Date.valueOf(day));
        } catch (DuplicateKeyException exists) {
            // another call or an earlier start made the day's row
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }

    /** What the service charged, else the tariff for the reported tokens; null when the price is unknown. */
    private BigDecimal cost(ModelChatResponse response) {
        if (response == null) {
            return null;
        }
        Map<String, Object> metadata = response.metadata();
        if (metadata.get("costRub") instanceof Number charged && charged.doubleValue() >= 0) {
            return BigDecimal.valueOf(charged.doubleValue());
        }
        if (tariff != null && metadata.get("usage") instanceof Map<?, ?> usage) {
            return tariff.cost(response.model(), integer(usage.get("prompt_tokens")), integer(usage.get("completion_tokens")));
        }
        return null;
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static BigDecimal parse(String value, String variable) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new IllegalStateException(variable + " must be a number of rubles, e.g. 1000");
        }
    }
}
