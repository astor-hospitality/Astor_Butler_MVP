package museon_online.astor_butler.max.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.domain.messenger.MessengerChatBinding;
import museon_online.astor_butler.domain.messenger.MessengerChatBindingRepository;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.fsm.core.idempotency.IdempotencyService;
import museon_online.astor_butler.max.MaxBotSettings;
import museon_online.astor_butler.max.client.MaxApiException;
import museon_online.astor_butler.max.client.MaxBotApiClient;
import museon_online.astor_butler.max.client.MaxButton;
import museon_online.astor_butler.max.client.MaxOutgoingMessage;
import museon_online.astor_butler.service.message.AdminAlert;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.MessageChannel;
import museon_online.astor_butler.service.message.MessageGatewayService;
import museon_online.astor_butler.service.message.OutgoingMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** A MAX update walks the same gateway as a Telegram message and the answer goes back to the same MAX chat. */
class MaxRouterTest {

    private static final long MAX_CHAT = -100000000L;
    private static final long INTERNAL_CHAT = 8_000_000_000_003L;

    private final ObjectMapper json = new ObjectMapper();
    private final MaxBotApiClient client = mock(MaxBotApiClient.class);
    private final MessengerChatBindingRepository bindings = mock(MessengerChatBindingRepository.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final MessageGatewayService gateway = mock(MessageGatewayService.class);
    private final MaxAdminAlertRelay adminAlerts = mock(MaxAdminAlertRelay.class);
    private MaxRouter router;

    @BeforeEach
    void setUp() {
        router = router(false);
        when(idempotency.accept(anyString())).thenReturn(true);
        when(bindings.resolve(eq(MessageChannel.MAX), anyLong(), any(), anyString(), anyBoolean(), any()))
                .thenAnswer(call -> new MessengerChatBinding(MessageChannel.MAX, call.getArgument(1), call.getArgument(2),
                        call.getArgument(3), INTERNAL_CHAT, null));
    }

    private MaxRouter router(boolean groupChats) {
        MaxBotSettings settings = new MaxBotSettings(true, "t", URI.create("https://example.invalid"), "", null, 0,
                null, null, groupChats, true);
        return new MaxRouter(client, new MaxUpdateMapper(), new MaxReplyRenderer(), bindings, idempotency, gateway,
                adminAlerts, settings, Duration.ZERO);
    }

    @Test
    void dialogMessageGoesThroughTheGatewayAndTheAnswerBackToTheMaxChat() throws Exception {
        when(gateway.handle(any())).thenAnswer(call -> reply(call.getArgument(0), "Чем помочь?", BotState.READY_FOR_DIALOG));

        router.handle(message("mid.1", "dialog", "Привет", false));

        ArgumentCaptor<IncomingMessage> incoming = ArgumentCaptor.forClass(IncomingMessage.class);
        verify(gateway).handle(incoming.capture());
        assertThat(incoming.getValue().channel()).isEqualTo(MessageChannel.MAX);
        assertThat(incoming.getValue().chatId()).isEqualTo(INTERNAL_CHAT);
        assertThat(incoming.getValue().text()).isEqualTo("Привет");
        verify(idempotency).accept("max:mid.1");
        verify(bindings).resolve(eq(MessageChannel.MAX), eq(MAX_CHAT), eq(54321L), eq("dialog"), eq(false), any());

        ArgumentCaptor<MaxOutgoingMessage> sent = ArgumentCaptor.forClass(MaxOutgoingMessage.class);
        verify(client).sendMessage(eq(MAX_CHAT), sent.capture());
        assertThat(sent.getValue().text()).isEqualTo("Чем помочь?");
        assertThat(sent.getValue().keyboard().getFirst()).containsExactly(MaxButton.message("Меню кухни"), MaxButton.message("Бар"));
        verify(adminAlerts).forward(any());
    }

    @Test
    void aDuplicateUpdateIsNotAnsweredTwice() throws Exception {
        when(idempotency.accept("max:mid.2")).thenReturn(false);

        router.handle(message("mid.2", "dialog", "Привет", false));

        verifyNoInteractions(gateway, bindings);
        verify(client, never()).sendMessage(anyLong(), any());
    }

    @Test
    void groupChatsAndBotsAreIgnoredInPhaseOne() throws Exception {
        router.handle(message("mid.3", "chat", "Всем привет", false));
        router.handle(message("mid.4", "dialog", "я бот", true));

        verifyNoInteractions(gateway, bindings, idempotency);
    }

    @Test
    void groupChatsReachTheGatewayWithANegativeBindingOnlyWhenEnabled() throws Exception {
        when(gateway.handle(any())).thenAnswer(call -> reply(call.getArgument(0), "", BotState.UNKNOWN));

        router(true).handle(message("mid.5", "chat", "Всем привет", false));

        verify(bindings).resolve(eq(MessageChannel.MAX), eq(MAX_CHAT), eq(null), eq("chat"), eq(true), any());
        verify(gateway).handle(any());
        verify(client, never()).sendMessage(anyLong(), any());
    }

    @Test
    void sharedContactIsKeptOnTheBindingAndPassedAsContactPhone() throws Exception {
        when(gateway.handle(any())).thenAnswer(call -> reply(call.getArgument(0), "Спасибо, контакт получил.", BotState.READY_FOR_DIALOG));

        router.handle(json.readTree("""
                {"update_type":"message_created","timestamp":1,
                 "message":{"sender":{"user_id":54321,"first_name":"Иван"},"recipient":{"chat_id":-100000000,"chat_type":"dialog"},
                            "body":{"mid":"mid.6","seq":1,"attachments":[{"type":"contact","payload":{
                               "vcf_info":"BEGIN:VCARD\\r\\nTEL;TYPE=cell:79990000000\\r\\nEND:VCARD","hash":"h"}}]}}}
                """));

        verify(bindings).saveContactPhone(MessageChannel.MAX, MAX_CHAT, "79990000000");
        ArgumentCaptor<IncomingMessage> incoming = ArgumentCaptor.forClass(IncomingMessage.class);
        verify(gateway).handle(incoming.capture());
        assertThat(incoming.getValue().contactPhone()).isEqualTo("79990000000");
    }

    @Test
    void callbacksAreAcknowledgedAndOnlyOurTextPayloadReachesTheFsm() throws Exception {
        when(gateway.handle(any())).thenAnswer(call -> reply(call.getArgument(0), "Давайте подберём стол.", BotState.READY_FOR_DIALOG));

        router.handle(callback("cb-1", "text:Бронь стола"));
        router.handle(callback("cb-2", "utm_view_6"));

        verify(client).answerCallback("cb-1", null);
        verify(client).answerCallback("cb-2", null);
        ArgumentCaptor<IncomingMessage> incoming = ArgumentCaptor.forClass(IncomingMessage.class);
        verify(gateway, times(1)).handle(incoming.capture());
        assertThat(incoming.getValue().text()).isEqualTo("Бронь стола");
    }

    @Test
    void aSilentGatewayAnswerSendsNothingButAlertsStillGo() throws Exception {
        when(gateway.handle(any())).thenAnswer(call -> reply(call.getArgument(0), " ", BotState.READY_FOR_DIALOG));

        router.handle(message("mid.7", "dialog", "...", false));

        verify(client, never()).sendMessage(anyLong(), any());
        verify(adminAlerts).forward(any());
    }

    @Test
    void aFailedSendStopsTheRestOfALongAnswerWithoutThrowing() throws Exception {
        String longText = "а".repeat(3900) + "\n\n" + "б".repeat(3900);
        when(gateway.handle(any())).thenAnswer(call -> reply(call.getArgument(0), longText, BotState.AI_FALLBACK));
        doThrow(new MaxApiException(429, "too.many.requests", "slow down")).when(client).sendMessage(anyLong(), any());

        router.handle(message("mid.8", "dialog", "расскажи всё", false));

        verify(client, times(1)).sendMessage(anyLong(), any());
    }

    @Test
    void aGatewayFailureTellsTheGuestLikeTheTelegramBotDoes() throws Exception {
        when(gateway.handle(any())).thenThrow(new IllegalStateException("redis down"));

        router.handle(message("mid.9", "dialog", "Привет", false));

        ArgumentCaptor<MaxOutgoingMessage> sent = ArgumentCaptor.forClass(MaxOutgoingMessage.class);
        verify(client).sendMessage(eq(MAX_CHAT), sent.capture());
        assertThat(sent.getValue().text()).isEqualTo(MaxRouter.ERROR_TEXT);
        verify(adminAlerts, never()).forward(any());
    }

    private OutgoingMessage reply(IncomingMessage incoming, String text, BotState next) {
        return OutgoingMessage.of(incoming, text, next.name(), false, false, false, false, AdminAlert.none(), List.of());
    }

    private JsonNode message(String mid, String chatType, String text, boolean bot) throws Exception {
        return json.readTree("""
                {"update_type":"message_created","timestamp":1,"user_locale":"ru",
                 "message":{"sender":{"user_id":54321,"first_name":"Иван","is_bot":%s},
                            "recipient":{"chat_id":-100000000,"chat_type":"%s"},
                            "body":{"mid":"%s","seq":1,"text":"%s"}}}
                """.formatted(bot, chatType, mid, text));
    }

    private JsonNode callback(String id, String payload) throws Exception {
        return json.readTree("""
                {"update_type":"message_callback","timestamp":1,
                 "callback":{"timestamp":1,"callback_id":"%s","payload":"%s","user":{"user_id":54321,"first_name":"Иван"}},
                 "message":{"recipient":{"chat_id":-100000000,"chat_type":"dialog"},"body":{"mid":"mid.k","seq":0,"text":"..."}}}
                """.formatted(id, payload));
    }
}
