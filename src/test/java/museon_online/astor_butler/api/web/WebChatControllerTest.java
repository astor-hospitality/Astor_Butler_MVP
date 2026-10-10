package museon_online.astor_butler.api.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.api.common.GlobalApiExceptionHandler;
import museon_online.astor_butler.api.message.MessageController;
import museon_online.astor_butler.domain.web.WebChannelPolicy;
import museon_online.astor_butler.domain.web.WebChatRateLimiter;
import museon_online.astor_butler.domain.web.WebLeadNotificationService;
import museon_online.astor_butler.domain.web.WebQuickReplyResolver;
import museon_online.astor_butler.domain.web.WebSessionMessageService;
import museon_online.astor_butler.domain.web.WebSessionResolution;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.MessageGatewayService;
import museon_online.astor_butler.service.message.OutgoingMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebChatControllerTest {

    private final MessageGatewayService gatewayService = mock(MessageGatewayService.class);
    private final WebSessionMessageService webSessionMessageService = mock(WebSessionMessageService.class);
    private final WebLeadNotificationService webLeadNotificationService = mock(WebLeadNotificationService.class);
    private final WebChatRateLimiter webChatRateLimiter = mock(WebChatRateLimiter.class);
    private final MessageController messageController = new MessageController(
            gatewayService,
            webSessionMessageService,
            webLeadNotificationService,
            webChatRateLimiter,
            new WebChannelPolicy("astor-butler-commercial", "c3ag"),
            new WebQuickReplyResolver("Бронь стола,Меню кухни")
    );
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new WebChatController(messageController, new ObjectMapper()))
            .setControllerAdvice(new GlobalApiExceptionHandler())
            .build();

    private void allowAndResolve(String sessionId) {
        when(webChatRateLimiter.check(any(), any(), any(), any())).thenReturn(WebChatRateLimiter.Decision.allow());
        when(webSessionMessageService.resolve(any(), any(), any()))
                .thenReturn(new WebSessionResolution(UUID.randomUUID(), sessionId, "web:anon:" + sessionId, 9000000000777L));
    }

    @Test
    void runsGuestFsmAndReturnsQuickRepliesAndSessionId() throws Exception {
        allowAndResolve("web-k7s1-m2n3");
        when(gatewayService.handle(any())).thenAnswer(invocation -> OutgoingMessage.of(
                invocation.getArgument(0),
                "Нажимая кнопку, вы соглашаетесь с политикой.",
                "CONSENT_REQUIRED",
                true,
                true,
                false,
                false,
                null,
                List.of("REQUEST_CONTACT")
        ));

        mvc.perform(post(WebChatController.PATH)
                        .contentType("application/json")
                        .header("X-Forwarded-For", "203.0.113.10, 10.0.0.1")
                        .content("""
                                {"channel":"WEB","text":"Привет","chatId":123,"externalUserId":"staff:1",
                                 "payload":{"sessionId":"web-k7s1-m2n3","site":"astor-butler-commercial","pageContext":"commercial_landing",
                                            "sentAt":"2026-10-10T10:00:00Z","tenant":"foreign"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.product").value("butler"))
                .andExpect(jsonPath("$.sessionId").value("web-k7s1-m2n3"))
                .andExpect(jsonPath("$.nextState").value("CONSENT_REQUIRED"))
                .andExpect(jsonPath("$.requestContact").value(true))
                .andExpect(jsonPath("$.html").value(true))
                .andExpect(jsonPath("$.quickReplies", hasSize(1)))
                .andExpect(jsonPath("$.quickReplies[0].kind").value("contact"))
                .andExpect(jsonPath("$.chatId").doesNotExist())
                .andExpect(jsonPath("$.metadata").doesNotExist());

        ArgumentCaptor<IncomingMessage> captor = ArgumentCaptor.forClass(IncomingMessage.class);
        verify(gatewayService).handle(captor.capture());
        assertThat(captor.getValue().chatId()).isEqualTo(9000000000777L);
        assertThat(captor.getValue().payload()).doesNotContainKey("tenant");
        verify(webChatRateLimiter).check(eq("203.0.113.10"), any(), any(), any());
    }

    @Test
    void contactStepSendsPhoneAsContactNotAsText() throws Exception {
        allowAndResolve("web-k7s1-m2n3");
        when(gatewayService.handle(any())).thenAnswer(invocation -> OutgoingMessage.of(
                invocation.getArgument(0), "Спасибо, контакт получил.", "READY_FOR_DIALOG", false, false, true, false, null, List.of()));

        mvc.perform(post(WebChatController.PATH).contentType("application/json").content("""
                        {"channel":"WEB","text":"","payload":{"sessionId":"web-k7s1-m2n3","site":"astor-butler-commercial","contactPhone":"+79000000000"}}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quickReplies[0].text").value("Бронь стола"));

        ArgumentCaptor<IncomingMessage> captor = ArgumentCaptor.forClass(IncomingMessage.class);
        verify(gatewayService).handle(captor.capture());
        assertThat(captor.getValue().contactPhone()).isEqualTo("+79000000000");
        assertThat(captor.getValue().text()).isEmpty();
    }

    @Test
    void rejectsPrivilegedChannelsMalformedBodiesAndOversizedPayloads() throws Exception {
        mvc.perform(post(WebChatController.PATH).contentType("application/json")
                        .content("{\"channel\":\"TELEGRAM\",\"text\":\"hi\",\"chatId\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(post(WebChatController.PATH).contentType("application/json").content("{"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(WebChatController.PATH).contentType("application/json").content("[1,2]"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(WebChatController.PATH).contentType("application/json").content("{\"text\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(WebChatController.PATH).contentType("application/json")
                        .content("{\"text\":\"" + "x".repeat(WebChatController.MAX_BODY_BYTES) + "\"}"))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(post(WebChatController.PATH).contentType("application/json")
                        .content("{\"text\":\"" + "x".repeat(WebChannelPolicy.MAX_TEXT_CHARS + 1) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(WebChatController.PATH).contentType("text/plain").content("hello"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post(WebChatController.PATH).header("Content-Type", "not a media type").content("{}"))
                .andExpect(status().isUnsupportedMediaType());
        verify(gatewayService, never()).handle(any());
        verify(webSessionMessageService, never()).resolve(any(), any(), any());
    }

    @Test
    void propagatesRateLimitWithRetryAfter() throws Exception {
        when(webChatRateLimiter.check(any(), any(), any(), any())).thenReturn(WebChatRateLimiter.Decision.deny("ip", 60));

        mvc.perform(post(WebChatController.PATH).contentType("application/json").content("""
                        {"text":"Привет","payload":{"sessionId":"web-k7s1-m2n3","site":"astor-butler-commercial"}}
                        """))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.nextState").value("WEB_RATE_LIMITED"))
                .andExpect(jsonPath("$.sessionId").value("web-k7s1-m2n3"));
        verify(gatewayService, never()).handle(any());
    }

    @Test
    void c3agSiteKeepsLeadFastPathWithoutQuickReplies() throws Exception {
        allowAndResolve("web-c3ag-1");

        mvc.perform(post(WebChatController.PATH).contentType("application/json").content("""
                        {"channel":"WEB","text":"Хочу фильм","payload":{"sessionId":"web-c3ag-1","site":"c3ag"}}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextState").value("WEB_LEAD_RECEIVED"))
                .andExpect(jsonPath("$.quickReplies", hasSize(0)));
        verify(gatewayService, never()).handle(any());
    }

    @Test
    void generatesSessionIdWhenBrowserSendsNoneOrAnInvalidOne() throws Exception {
        when(webChatRateLimiter.check(any(), any(), any(), any())).thenReturn(WebChatRateLimiter.Decision.allow());
        when(webSessionMessageService.resolve(any(), any(), any())).thenAnswer(invocation -> {
            Map<String, Object> payload = invocation.getArgument(2);
            String sessionId = String.valueOf(payload.get("sessionId"));
            return new WebSessionResolution(UUID.randomUUID(), sessionId, "web:anon:" + sessionId, 9000000000001L);
        });
        when(gatewayService.handle(any())).thenAnswer(invocation -> OutgoingMessage.of(
                invocation.getArgument(0), "ok", "CONSENT_REQUIRED", false, true, false, false, null, List.of()));

        mvc.perform(post(WebChatController.PATH).contentType("application/json").content("""
                        {"text":"Привет","payload":{"sessionId":"../etc/passwd","site":"astor-butler-commercial"}}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(org.hamcrest.Matchers.startsWith("web-")));
    }
}
