package museon_online.astor_butler.domain.calllog;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CallLogRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-10-15T09:00:00Z");

    private final JdbcTemplate jdbc = CallLogTestDatabase.create();
    private final CallLogRepository repository = new CallLogRepository(jdbc);

    @Test
    void appendsARowWithEveryColumn() {
        repository.append(llm(T0, "u1", 1200, 300, "0.1404"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM call_log", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT * FROM call_log"))
                .containsEntry("call_type", "llm")
                .containsEntry("name", "GigaChat-3-Pro")
                .containsEntry("tokens_in", 1200)
                .containsEntry("pseudo_user_id", "u1");
    }

    @Test
    void exportsAHeaderAndOneLinePerRowOfThePeriod() {
        repository.append(llm(T0, "u1", 1200, 300, "0.1404"));
        repository.append(llm(T0.plusSeconds(86_400 * 2), "u2", 1, 1, "0.0001"));

        String csv = repository.exportCsv(T0.minusSeconds(60), T0.plusSeconds(60));

        assertThat(csv.split("\n")).containsExactly(
                "ts,source,pseudo_user_id,correlation_id,call_type,name,result,error_code,tokens_in,tokens_out,cost_rub,latency_ms",
                "2026-10-15T09:00:00Z,telegram,u1,corr-1,llm,GigaChat-3-Pro,ok,,1200,300,0.1404,850");
    }

    @Test
    void quotesAndDefusesCsvCells() {
        assertThat(CallLogRepository.field("a,b")).isEqualTo("\"a,b\"");
        assertThat(CallLogRepository.field("=cmd")).isEqualTo("'=cmd");
        assertThat(CallLogRepository.field(null)).isEmpty();
    }

    @Test
    void summarizesThePeriod() {
        repository.append(llm(T0, "u1", 1200, 300, "0.1404"));
        repository.append(llm(T0.plusSeconds(1), "u1", 100, 50, "0.0161"));
        repository.append(new CallLogEntry(T0.plusSeconds(2), null, null, null, CallLogEntry.TYPE_TOOL, "saby:/order", "OK", null, null, null, null, 120));
        repository.append(new CallLogEntry(T0.plusSeconds(3), "telegram", "u2", null, CallLogEntry.TYPE_GUEST_MESSAGE, null, "received", null, null, null, null, null));
        repository.append(new CallLogEntry(T0.plusSeconds(4), "bot", "u2", null, CallLogEntry.TYPE_TARGET_ACTION, "reservation:create", "AWAITING_MANAGER_CONFIRMATION", null, null, null, null, null));
        repository.append(llm(T0.minusSeconds(3600), "u3", 999, 999, "1.0000"));

        CallLogSummary summary = repository.summary(T0, T0.plusSeconds(60));

        assertThat(summary).isEqualTo(new CallLogSummary(2, 1, 1, 1, 2, 1300, 350, new BigDecimal("0.1565")));
    }

    @Test
    void anEmptyPeriodSumsToZero() {
        assertThat(repository.summary(T0, T0.plusSeconds(1)))
                .isEqualTo(new CallLogSummary(0, 0, 0, 0, 0, 0, 0, new BigDecimal("0.0000")));
    }

    private static CallLogEntry llm(Instant ts, String user, int in, int out, String cost) {
        return new CallLogEntry(ts, "telegram", user, "corr-1", CallLogEntry.TYPE_LLM, "GigaChat-3-Pro", "ok", null,
                in, out, new BigDecimal(cost), 850);
    }
}
