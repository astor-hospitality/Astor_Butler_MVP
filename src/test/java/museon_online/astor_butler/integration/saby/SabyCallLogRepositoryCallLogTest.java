package museon_online.astor_butler.integration.saby;

import museon_online.astor_butler.domain.calllog.CallLogWriter;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class SabyCallLogRepositoryCallLogTest {

    private final CallLogWriter callLog = mock(CallLogWriter.class);
    private final SabyCallLogRepository repository = new SabyCallLogRepository(mock(JdbcTemplate.class));

    @Test
    void aSabyCallIsAToolCallWithTheHttpStatusAsErrorCodeWhenItFailed() {
        repository.setCallLog(callLog);
        when(callLog.enabled()).thenReturn(true);

        repository.record("POST", "/order/{id}/create", 502, SabyCallLogRepository.OUTCOME_HTTP_ERROR, 340);
        repository.record("GET", "/order/{id}", 200, SabyCallLogRepository.OUTCOME_OK, 120);

        verify(callLog).tool("saby:/order/{id}/create", "HTTP_ERROR", "502", 340L);
        verify(callLog).tool("saby:/order/{id}", "OK", null, 120L);
    }

    @Test
    void offTheWriterIsNotCalled() {
        repository.setCallLog(callLog);

        repository.record("GET", "/order/{id}", 200, SabyCallLogRepository.OUTCOME_OK, 120);

        verify(callLog).enabled();
        verifyNoMoreInteractions(callLog);
    }
}
