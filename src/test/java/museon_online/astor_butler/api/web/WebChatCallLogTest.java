package museon_online.astor_butler.api.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.api.common.ApiException;
import museon_online.astor_butler.api.message.MessageController;
import museon_online.astor_butler.domain.calllog.CallLogWriter;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class WebChatCallLogTest {

    private final MessageController messageController = mock(MessageController.class);
    private final WebChatController controller = new WebChatController(messageController, new ObjectMapper());
    private final CallLogWriter callLog = mock(CallLogWriter.class);

    @Test
    void aWebMessageIsAGuestMessageOfItsSession() {
        controller.setCallLog(callLog);
        when(callLog.enabled()).thenReturn(true);
        when(messageController.process(any(), any())).thenReturn(ResponseEntity.ok().build());

        // The mocked gateway answers with no body, so the controller ends in 503 after the message was counted.
        assertThatThrownBy(() -> controller.process(request())).isInstanceOf(ApiException.class);

        verify(callLog).guestMessage("web", "web-k7s1-m2n3", null);
    }

    @Test
    void offTheWriterIsNotCalled() {
        controller.setCallLog(callLog);
        when(messageController.process(any(), any())).thenReturn(ResponseEntity.ok().build());

        assertThatThrownBy(() -> controller.process(request())).isInstanceOf(ApiException.class);

        verify(callLog).enabled();
        verifyNoMoreInteractions(callLog);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", WebChatController.PATH);
        request.setContentType("application/json");
        request.setContent("""
                {"channel":"WEB","text":"Привет","payload":{"sessionId":"web-k7s1-m2n3"}}
                """.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
