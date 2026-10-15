package museon_online.astor_butler.agent;

import museon_online.astor_butler.domain.calllog.ModelTariff;
import museon_online.astor_butler.model.ModelChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class AgentBudgetTest {

    private static final ZoneId VENUE = ZoneId.of("Asia/Yekaterinburg");

    private static ModelChatResponse charged(double rub) {
        return new ModelChatResponse("ok", Optional.empty(), "openai-compatible", "gigachat-3-pro", Duration.ZERO,
                Map.of("costRub", rub));
    }

    private static final class MovableClock extends Clock {
        private Instant now;

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
    void withoutALimitItIsNeverExhausted() {
        AgentBudget budget = new AgentBudget("", "Asia/Yekaterinburg");
        budget.charge(charged(1_000_000));

        assertThat(budget.limited()).isFalse();
        assertThat(budget.exhausted()).isFalse();
        assertThat(budget.spentToday()).isEqualTo(1_000_000);
    }

    @Test
    void theServicesChargeAddsUpToTheLimitAndAWarningSaysSo(CapturedOutput output) {
        AgentBudget budget = new AgentBudget(100, VENUE, Clock.systemUTC());

        budget.charge(charged(79.99));
        assertThat(budget.exhausted()).isFalse();
        budget.charge(charged(0.02));
        assertThat(output.getAll()).contains("Agent daily model budget at 80%");
        budget.charge(charged(20));

        assertThat(budget.exhausted()).isTrue();
        assertThat(output.getAll()).contains("Agent daily model budget is spent");
    }

    @Test
    void theNextVenueDayStartsFromZero() {
        // 18:59 UTC is 23:59 in Yekaterinburg; ten minutes later it is the next venue day.
        MovableClock clock = new MovableClock(Instant.parse("2026-10-15T18:59:00Z"));
        AgentBudget budget = new AgentBudget(100, VENUE, clock);
        budget.charge(charged(150));
        assertThat(budget.exhausted()).isTrue();

        clock.now = Instant.parse("2026-10-15T19:09:00Z");

        assertThat(budget.exhausted()).isFalse();
        assertThat(budget.spentToday()).isZero();
    }

    @Test
    void withoutTheServicesChargeTheTariffPricesTheTokens() {
        AgentBudget budget = new AgentBudget(100, VENUE, Clock.systemUTC());
        budget.setTariff(new ModelTariff());
        ModelChatResponse gigachat = new ModelChatResponse("ok", Optional.empty(), "gigachat", "GigaChat-2-Pro", Duration.ZERO,
                Map.of("usage", Map.of("prompt_tokens", 100_000, "completion_tokens", 0)));
        ModelChatResponse unknown = new ModelChatResponse("ok", Optional.empty(), "openai-compatible", "no-tariff-model",
                Duration.ZERO, Map.of("usage", Map.of("prompt_tokens", 100_000, "completion_tokens", 0)));

        budget.charge(gigachat);
        budget.charge(unknown);

        // GigaChat-2-Pro: 500 RUB per 1M tokens; an unpriced model adds nothing rather than a made-up cost.
        assertThat(budget.spentToday()).isEqualTo(50.0);
    }

    @Test
    void aLimitThatIsNotANumberStopsTheStart() {
        assertThatThrownBy(() -> new AgentBudget("сто", "Asia/Yekaterinburg"))
                .hasMessageContaining("ASTOR_AGENT_DAILY_BUDGET_RUB");
    }
}
