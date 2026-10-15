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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/**
 * The agent's daily model budget ({@code ASTOR_AGENT_DAILY_BUDGET_RUB}; blank or 0 — no limit) in
 * {@code agent_budget_days}, per venue day ({@code astor.guest-journey.zone-id}).
 *
 * <p>Every model call first {@link #reserve reserves} its worst-case price ({@code ASTOR_AGENT_CALL_RESERVE_RUB}, 1 ₽):
 * one conditional UPDATE, under a lock within this process, so parallel calls never pass the limit together. The answer
 * then {@link #settle settles} the reservation: what the service charged ({@code costRub} from
 * {@code x-litellm-response-cost}), else the call log tariff for the reported tokens, else — price unknown, a timeout or
 * any failure — the whole reservation. Counts survive restarts; a reservation left by a crash stays counted until the
 * day ends. If the table cannot be read or written, no reservation is given: the agent falls back (fails closed).
 * A reached limit means no model calls until the next venue day; turns fall back to the scenarios, never to another model.
 */
@Component
public class AgentBudget {

    static final String SCOPE = "butler-agent";
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

    /** Turns the reservation into what the call cost; {@code response} null (timeout, failure) keeps the whole reservation. */
    public synchronized void settle(Reservation reservation, ModelChatResponse response) {
        if (reservation == null || reservation.day() == null) {
            return;
        }
        BigDecimal cost = cost(response);
        BigDecimal charged = cost == null ? reservation.amount() : cost;
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
