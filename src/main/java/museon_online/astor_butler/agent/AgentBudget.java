package museon_online.astor_butler.agent;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import museon_online.astor_butler.domain.calllog.ModelTariff;
import museon_online.astor_butler.model.ModelChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/**
 * The agent's daily model budget ({@code ASTOR_AGENT_DAILY_BUDGET_RUB}; blank or 0 — no limit). The day is the venue's
 * ({@code astor.guest-journey.zone-id}). A call costs what the service charged ({@code costRub} from the
 * {@code x-litellm-response-cost} header of the OpenAI-compatible API) or, without it, the call log's tariff for the
 * reported tokens; a call priced by neither is not counted. Once the day's spend reaches the limit the agent makes no
 * more model calls until the next day: its turns fall back to the scenarios, never to another model. One WARN at 80%
 * and one at the limit per day. Kept in memory: a restart starts the day's count from zero.
 */
@Component
public class AgentBudget {

    private static final Logger log = LoggerFactory.getLogger(AgentBudget.class);

    private final double limitRub;
    private final ZoneId zone;
    private final Clock clock;
    private ModelTariff tariff;
    private LocalDate day;
    private double spentRub;
    private boolean warnedNearLimit;
    private boolean warnedExhausted;

    @Autowired
    public AgentBudget(@Value("${astor.agent.daily-budget-rub:}") String limitRub,
                       @Value("${astor.guest-journey.zone-id:Asia/Yekaterinburg}") String zone) {
        this(parse(limitRub), ZoneId.of(zone), Clock.systemUTC());
    }

    AgentBudget(double limitRub, ZoneId zone, Clock clock) {
        this.limitRub = Math.max(0, limitRub);
        this.zone = zone;
        this.clock = clock;
        if (this.limitRub > 0) {
            log.info("Agent daily model budget: {} RUB per venue day", this.limitRub);
        }
    }

    @Autowired(required = false)
    public void setTariff(ModelTariff tariff) {
        this.tariff = tariff;
    }

    @Autowired(required = false)
    public void setMeterRegistry(MeterRegistry meters) {
        Gauge.builder("astor.agent.budget.spent", this, AgentBudget::spentToday)
                .description("Agent model spend today, RUB").baseUnit("rub").register(meters);
        Gauge.builder("astor.agent.budget.limit", this, budget -> budget.limitRub)
                .description("Agent daily model budget, RUB (0 — no limit)").baseUnit("rub").register(meters);
    }

    public boolean limited() {
        return limitRub > 0;
    }

    /** Whether today's spend has reached the limit; never true without a limit. */
    public synchronized boolean exhausted() {
        rollOver();
        return limited() && spentRub >= limitRub;
    }

    public synchronized double spentToday() {
        rollOver();
        return spentRub;
    }

    /** Adds what this answer cost; an answer priced by neither the service nor the tariff adds nothing. */
    public void charge(ModelChatResponse response) {
        Double cost = cost(response);
        if (cost == null || cost <= 0) {
            return;
        }
        synchronized (this) {
            rollOver();
            spentRub += cost;
            if (!limited()) {
                return;
            }
            if (!warnedExhausted && spentRub >= limitRub) {
                warnedExhausted = true;
                log.warn("Agent daily model budget is spent: {} of {} RUB; agent turns fall back until the next day",
                        round(spentRub), limitRub);
            } else if (!warnedNearLimit && spentRub >= 0.8 * limitRub) {
                warnedNearLimit = true;
                log.warn("Agent daily model budget at 80%: {} of {} RUB", round(spentRub), limitRub);
            }
        }
    }

    private Double cost(ModelChatResponse response) {
        if (response == null) {
            return null;
        }
        Map<String, Object> metadata = response.metadata();
        if (metadata.get("costRub") instanceof Number charged) {
            return charged.doubleValue();
        }
        if (tariff != null && metadata.get("usage") instanceof Map<?, ?> usage) {
            BigDecimal estimate = tariff.cost(response.model(), integer(usage.get("prompt_tokens")), integer(usage.get("completion_tokens")));
            return estimate == null ? null : estimate.doubleValue();
        }
        return null;
    }

    private void rollOver() {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        if (!today.equals(day)) {
            day = today;
            spentRub = 0;
            warnedNearLimit = false;
            warnedExhausted = false;
        }
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static double parse(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(value.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("ASTOR_AGENT_DAILY_BUDGET_RUB must be a number of rubles, e.g. 100");
        }
    }
}
