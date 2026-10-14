package museon_online.astor_butler.domain.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.domain.calllog.CallLogWriter;
import museon_online.astor_butler.domain.identity.IdentityRecord;
import museon_online.astor_butler.domain.identity.IdentityService;
import museon_online.astor_butler.service.message.IncomingMessage;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TelegramIntakeCallLogTest {

    private final IdentityService identityService = mock(IdentityService.class);
    private final CallLogWriter callLog = mock(CallLogWriter.class);
    private final TelegramIntakeService intake = new TelegramIntakeService(mock(JdbcTemplate.class), new ObjectMapper(), identityService);

    @Test
    void aTelegramMessageIsAGuestMessageOfTheTelegramUser() {
        when(identityService.identifyTelegram(any())).thenReturn(new IdentityRecord(1L, 42L));
        intake.setCallLog(callLog);
        when(callLog.enabled()).thenReturn(true);

        intake.capture(message());

        verify(callLog).guestMessage("telegram", "42", "corr-1");
    }

    @Test
    void offTheWriterIsNotCalled() {
        when(identityService.identifyTelegram(any())).thenReturn(new IdentityRecord(1L, 42L));
        intake.setCallLog(callLog);

        intake.capture(message());

        verify(callLog).enabled();
        verifyNoMoreInteractions(callLog);
    }

    private static IncomingMessage message() {
        return IncomingMessage.telegram(7L, 42L, 1, 1, "текст гостя", null, null, null, null, null, false, "corr-1");
    }
}
