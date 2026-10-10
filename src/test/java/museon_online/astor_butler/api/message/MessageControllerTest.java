package museon_online.astor_butler.api.message;

import museon_online.astor_butler.domain.web.WebChannelPolicy;
import museon_online.astor_butler.domain.web.WebLeadNotificationService;
import museon_online.astor_butler.domain.web.WebChatRateLimiter;
import museon_online.astor_butler.domain.web.WebQuickReply;
import museon_online.astor_butler.domain.web.WebQuickReplyResolver;
import museon_online.astor_butler.domain.web.WebSessionMessageService;
import museon_online.astor_butler.domain.web.WebSessionResolution;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.MessageChannel;
import museon_online.astor_butler.service.message.MessageGatewayService;
import museon_online.astor_butler.service.message.OutgoingMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageControllerTest {

    private final MessageGatewayService gatewayService = mock(MessageGatewayService.class);
    private final WebSessionMessageService webSessionMessageService = mock(WebSessionMessageService.class);
    private final WebLeadNotificationService webLeadNotificationService = mock(WebLeadNotificationService.class);
    private final WebChatRateLimiter webChatRateLimiter = mock(WebChatRateLimiter.class);
    private final WebChannelPolicy webChannelPolicy = new WebChannelPolicy("astor-butler-commercial,astor-butler", "c3ag");
    private final WebQuickReplyResolver webQuickReplyResolver = new WebQuickReplyResolver("Бронь стола,Меню кухни");
    private final MessageController controller = new MessageController(
            gatewayService,
            webSessionMessageService,
            webLeadNotificationService,
            webChatRateLimiter,
            webChannelPolicy,
            webQuickReplyResolver
    );

    @Test
    void mapsTelegramExternalUserIdToTelegramUserIdForFsmSimulation() {
        MessageController.MessageRequest request = new MessageController.MessageRequest(
                "TELEGRAM",
                "900001001",
                900001001L,
                "Привет",
                null,
                "Анна",
                "weekend_anna",
                "test-correlation",
                Map.of()
        );

        when(gatewayService.handle(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> OutgoingMessage.of(
                        invocation.getArgument(0),
                        "ok",
                        "CONSENT_REQUIRED",
                        false,
                        true,
                        false,
                        false,
                        null,
                        List.of("REQUEST_CONTACT")
                ));
        when(webChatRateLimiter.check(any(), any(), any(), any()))
                .thenReturn(WebChatRateLimiter.Decision.allow());

        controller.process(request);

        ArgumentCaptor<IncomingMessage> captor = ArgumentCaptor.forClass(IncomingMessage.class);
        verify(gatewayService).handle(captor.capture());
        IncomingMessage incoming = captor.getValue();

        assertThat(incoming.channel()).isEqualTo(MessageChannel.TELEGRAM);
        assertThat(incoming.externalUserId()).isEqualTo("900001001");
        assertThat(incoming.telegramUserId()).isEqualTo(900001001L);
        assertThat(incoming.chatId()).isEqualTo(900001001L);
    }

    @Test
    void webMessageUsesFastPathAndProjectsTelegramOperatorNotification() {
        Map<String, Object> payload = Map.of(
                "site", "c3ag",
                "sessionId", "web-session-1",
                "page", "/film",
                "selectedVideo", Map.of("slug", "umekon-zavod", "title", "Сериал ЗАВОД")
        );
        WebSessionResolution session = new WebSessionResolution(
                UUID.randomUUID(),
                "web-session-1",
                "web:anon:web-session-1",
                900000123L
        );
        MessageController.MessageRequest request = new MessageController.MessageRequest(
                "WEB",
                "web:anon:web-session-1",
                null,
                "Хочу фильм о заводе",
                null,
                null,
                null,
                "web-correlation-1",
                payload
        );

        when(webSessionMessageService.resolve(eq("web:anon:web-session-1"), eq(null), any()))
                .thenReturn(session);
        when(webChatRateLimiter.check(any(), any(), any(), any()))
                .thenReturn(WebChatRateLimiter.Decision.allow());

        MessageController.MessageResponse response = controller.process(request).getBody();

        assertThat(response).isNotNull();
        assertThat(response.channel()).isEqualTo("WEB");
        assertThat(response.chatId()).isEqualTo(900000123L);
        assertThat(response.nextState()).isEqualTo("WEB_LEAD_RECEIVED");
        assertThat(response.actions()).contains("WEB_LEAD_CAPTURED", "ADMIN_ALERT");

        verify(webSessionMessageService).recordInbound(eq(session), eq("web-correlation-1"), eq("Хочу фильм о заводе"), any());
        verify(webSessionMessageService).recordOutbound(eq(session), eq("web-correlation-1"), any(OutgoingMessage.class));
        verify(webLeadNotificationService).project(eq(session), any(IncomingMessage.class), any(OutgoingMessage.class));
        verify(gatewayService, never()).handle(any());
        assertThat(response.sessionId()).isEqualTo("web-session-1");
        assertThat(response.quickReplies()).isEmpty();
    }

    @Test
    void astorSiteRunsGuestFsmWithStableWebIdentityAndQuickReplies() {
        WebSessionResolution session = new WebSessionResolution(UUID.randomUUID(), "web-abc-def", "web:anon:web-abc-def", 9000000000123L);
        MessageController.MessageRequest request = new MessageController.MessageRequest(
                "WEB",
                "staff:admin",   // identity chosen by the browser is ignored
                777L,            // chatId chosen by the browser is ignored
                "Хочу стол на завтра",
                null,
                null,
                null,
                "web-correlation-2",
                Map.of("sessionId", "web-abc-def", "site", "astor-butler-commercial", "contactPhone", "+7 900 000-00-00")
        );
        when(webChatRateLimiter.check(any(), any(), any(), any())).thenReturn(WebChatRateLimiter.Decision.allow());
        when(webSessionMessageService.resolve(eq(null), eq(null), any())).thenReturn(session);
        when(gatewayService.handle(any())).thenAnswer(invocation -> OutgoingMessage.of(
                invocation.getArgument(0),
                "Спасибо, контакт получил.",
                "READY_FOR_DIALOG",
                false,
                false,
                true,
                false,
                null,
                List.of("CONTACT_CAPTURED", "OPEN_MENU")
        ));

        MessageController.MessageResponse response = controller.process(request).getBody();

        ArgumentCaptor<IncomingMessage> captor = ArgumentCaptor.forClass(IncomingMessage.class);
        verify(gatewayService).handle(captor.capture());
        IncomingMessage incoming = captor.getValue();
        assertThat(incoming.channel()).isEqualTo(MessageChannel.WEB);
        assertThat(incoming.chatId()).isEqualTo(9000000000123L);
        assertThat(incoming.telegramUserId()).isEqualTo(9000000000123L);
        assertThat(incoming.externalUserId()).isEqualTo("web:anon:web-abc-def");
        assertThat(incoming.contactPhone()).isEqualTo("+7 900 000-00-00");
        assertThat(incoming.payload()).doesNotContainKey("contactPhone");

        assertThat(response).isNotNull();
        assertThat(response.sessionId()).isEqualTo("web-abc-def");
        assertThat(response.nextState()).isEqualTo("READY_FOR_DIALOG");
        assertThat(response.quickReplies()).extracting(WebQuickReply::text).containsExactly("Бронь стола", "Меню кухни");
        assertThat(incoming.payload()).containsKey("webClientKey");
        assertThat(String.valueOf(incoming.payload().get("webClientKey"))).startsWith("ip:");
        verify(webSessionMessageService).recordOutbound(eq(session), eq("web-correlation-2"), any(OutgoingMessage.class));
        verify(webLeadNotificationService).projectGuestFlow(eq(session), any(IncomingMessage.class), any(OutgoingMessage.class));
        verify(webLeadNotificationService, never()).project(any(), any(), any());
    }

    @Test
    void quickReplyActionStandsForTypedTextOnTheWeb() {
        WebSessionResolution session = new WebSessionResolution(UUID.randomUUID(), "web-abc-def", "web:anon:web-abc-def", 9000000000123L);
        MessageController.MessageRequest request = new MessageController.MessageRequest(
                "WEB", null, null, "", null, null, null, null,
                Map.of("sessionId", "web-abc-def", "site", "astor-butler", "action", "Бронь стола")
        );
        when(webChatRateLimiter.check(any(), any(), any(), any())).thenReturn(WebChatRateLimiter.Decision.allow());
        when(webSessionMessageService.resolve(eq(null), eq(null), any())).thenReturn(session);
        when(gatewayService.handle(any())).thenAnswer(invocation -> OutgoingMessage.of(
                invocation.getArgument(0), "На какой день?", "TABLE_BOOKING_COLLECT_DATE", false, false, false, false, null, List.of()
        ).withMetadata(Map.of("replyKeyboardRows", List.of(List.of("Сегодня", "Завтра"), List.of("Отмена")))));

        MessageController.MessageResponse response = controller.process(request).getBody();

        ArgumentCaptor<IncomingMessage> captor = ArgumentCaptor.forClass(IncomingMessage.class);
        verify(gatewayService).handle(captor.capture());
        assertThat(captor.getValue().text()).isEqualTo("Бронь стола");
        assertThat(response).isNotNull();
        assertThat(response.quickReplies()).extracting(WebQuickReply::value).containsExactly("Сегодня", "Завтра", "Отмена");
    }

    @Test
    void rateLimitedWebRequestAnswersWithRetryAfterAndSessionId() {
        MessageController.MessageRequest request = new MessageController.MessageRequest(
                "WEB", null, null, "Привет", null, null, null, null,
                Map.of("sessionId", "web-abc-def", "site", "astor-butler")
        );
        when(webChatRateLimiter.check(any(), any(), any(), any())).thenReturn(WebChatRateLimiter.Decision.deny("burst", 10));

        var response = controller.process(request);

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("10");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().sessionId()).isEqualTo("web-abc-def");
        assertThat(response.getBody().nextState()).isEqualTo("WEB_RATE_LIMITED");
        verify(gatewayService, never()).handle(any());
        verify(webSessionMessageService, never()).resolve(any(), any(), any());
    }
}
