package museon_online.astor_butler.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.domain.calllog.CallLogWriter;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ModelInteractionAuditCallLogTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CallLogWriter callLog = mock(CallLogWriter.class);
    private final ModelInteractionAuditRepository audit = new ModelInteractionAuditRepository(jdbc, new ObjectMapper());

    @Test
    void everyAuditedModelCallGoesToTheCallLog() {
        audit.setCallLog(callLog);
        when(callLog.enabled()).thenReturn(true);
        ModelInteractionAuditRecord record = record();

        audit.capture(record);

        verify(callLog).llm(record);
    }

    @Test
    void offTheWriterIsNotCalled() {
        audit.setCallLog(callLog);

        audit.capture(record());

        verify(callLog).enabled();
        verifyNoMoreInteractions(callLog);
    }

    private static ModelInteractionAuditRecord record() {
        return new ModelInteractionAuditRecord("AERIS", "TELEGRAM", 7L, 42L, "corr", "s", "S", "p", "gigachat", "GigaChat-3-Pro",
                "", "prompt", "", "", "reply", true, false, true, "", "", Duration.ofMillis(10),
                Map.of("usage", Map.of("prompt_tokens", 1)));
    }
}
