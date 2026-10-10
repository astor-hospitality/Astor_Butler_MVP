package museon_online.astor_butler.api.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import museon_online.astor_butler.api.common.ApiException;
import museon_online.astor_butler.api.common.ErrorCode;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/messages")
@Tag(name = "FSM API", description = "FSM events, message gateway and safe state transitions")
public class MessageController {

    private final MessageGatewayService messageGatewayService;
    private final WebSessionMessageService webSessionMessageService;
    private final WebLeadNotificationService webLeadNotificationService;
    private final WebChatRateLimiter webChatRateLimiter;
    private final WebChannelPolicy webChannelPolicy;
    private final WebQuickReplyResolver webQuickReplyResolver;

    public MessageController(
            MessageGatewayService messageGatewayService,
            WebSessionMessageService webSessionMessageService,
            WebLeadNotificationService webLeadNotificationService,
            WebChatRateLimiter webChatRateLimiter,
            WebChannelPolicy webChannelPolicy,
            WebQuickReplyResolver webQuickReplyResolver
    ) {
        this.messageGatewayService = messageGatewayService;
        this.webSessionMessageService = webSessionMessageService;
        this.webLeadNotificationService = webLeadNotificationService;
        this.webChatRateLimiter = webChatRateLimiter;
        this.webChannelPolicy = webChannelPolicy;
        this.webQuickReplyResolver = webQuickReplyResolver;
    }

    @PostMapping
    @Operation(
            summary = "Process normalized UI message through FSM gateway",
            description = "Common entry point for future web chat, Telegram-like adapters and smoke checks. Telegram stays a UI transport; FSM remains the source of truth."
    )
    public ResponseEntity<MessageResponse> process(@RequestBody MessageRequest request, HttpServletRequest httpRequest) {
        return processInternal(request, httpRequest);
    }

    ResponseEntity<MessageResponse> process(MessageRequest request) {
        return processInternal(request, null);
    }

    private ResponseEntity<MessageResponse> processInternal(MessageRequest request, HttpServletRequest httpRequest) {
        MessageChannel messageChannel = channel(request.channel());
        String correlationId = request.correlationId() == null ? UUID.randomUUID().toString() : request.correlationId();
        Map<String, Object> payload = request.payload() == null ? Map.of() : request.payload();
        WebSessionResolution webSession = null;
        Long chatId = request.chatId();
        String externalUserId = request.externalUserId();
        String text = request.text();
        String contactPhone = request.contactPhone();

        if (messageChannel == MessageChannel.WEB) {
            // The browser is anonymous: identity comes from payload.sessionId only, never from chatId/externalUserId.
            WebChannelPolicy.WebInbound inbound = webChannelPolicy.sanitize(externalUserId, text, contactPhone, payload);
            payload = inbound.payload();
            chatId = null;
            externalUserId = inbound.externalUserId();
            text = inbound.text();
            contactPhone = inbound.contactPhone();

            WebChatRateLimiter.Decision decision = webChatRateLimiter.check(
                    clientIp(httpRequest),
                    externalUserId,
                    null,
                    payload
            );
            if (!decision.allowed()) {
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", String.valueOf(decision.retryAfterSeconds()))
                        .body(rateLimitedResponse(inbound.sessionId(), correlationId, decision));
            }
            try {
                webSession = webSessionMessageService.resolve(externalUserId, null, payload);
                chatId = webSession.chatId();
                externalUserId = webSession.externalUserId();
                webSessionMessageService.recordInbound(webSession, correlationId, text, payload);
            } catch (IllegalArgumentException e) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        ErrorCode.BAD_REQUEST,
                        e.getMessage()
                );
            }
        }

        if (chatId == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCode.BAD_REQUEST,
                    "chatId is required for TELEGRAM and INTERNAL channels; WEB can be resolved from payload.sessionId"
            );
        }

        IncomingMessage incoming = messageChannel == MessageChannel.TELEGRAM
                ? IncomingMessage.telegram(
                        chatId,
                        telegramUserId(externalUserId, chatId),
                        null,
                        null,
                        request.text(),
                        request.contactPhone(),
                        request.firstName(),
                        null,
                        request.username(),
                        null,
                        false,
                        correlationId,
                        payload
                )
                : new IncomingMessage(
                        messageChannel,
                        externalUserId,
                        chatId,
                        // WEB: the stable per-session chatId doubles as the guest id the FSM scenarios key consents and bookings by.
                        messageChannel == MessageChannel.WEB ? chatId : null,
                        null,
                        null,
                        text,
                        contactPhone,
                        request.firstName(),
                        null,
                        request.username(),
                        null,
                        false,
                        correlationId,
                        Instant.now(),
                        payload
                );

        if (messageChannel == MessageChannel.WEB && webSession != null && !webChannelPolicy.routesToFsm(webChannelPolicy.site(payload))) {
            OutgoingMessage outgoing = webLeadReply(incoming);
            webSessionMessageService.recordOutbound(webSession, correlationId, outgoing);
            webLeadNotificationService.project(webSession, incoming, outgoing);
            return ResponseEntity.ok(MessageResponse.from(outgoing, webSession.sessionId(), List.of()));
        }

        OutgoingMessage outgoing = messageGatewayService.handle(incoming);
        if (webSession != null) {
            webSessionMessageService.recordOutbound(webSession, correlationId, outgoing);
            webLeadNotificationService.project(webSession, incoming, outgoing);
            return ResponseEntity.ok(MessageResponse.from(outgoing, webSession.sessionId(), webQuickReplyResolver.resolve(outgoing)));
        }
        return ResponseEntity.ok(MessageResponse.from(outgoing));
    }

    private String clientIp(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private MessageResponse rateLimitedResponse(String sessionId, String correlationId, WebChatRateLimiter.Decision decision) {
        String text = "Сейчас слишком много сообщений. Пожалуйста, попробуйте ещё раз через минуту — так я смогу не потерять ваш запрос.";
        return new MessageResponse(
                "WEB",
                null,
                null,
                text,
                "WEB_RATE_LIMITED",
                false,
                false,
                false,
                true,
                false,
                List.of("WEB_RATE_LIMITED"),
                Map.of(
                        "correlationId", correlationId,
                        "rateLimitReason", decision.reason(),
                        "retryAfterSeconds", decision.retryAfterSeconds()
                ),
                Instant.now(),
                sessionId,
                List.of()
        );
    }

    private MessageChannel channel(String channel) {
        if (channel == null || channel.isBlank()) {
            return MessageChannel.WEB;
        }
        try {
            return MessageChannel.valueOf(channel.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCode.BAD_REQUEST,
                    "Unsupported message channel",
                    Map.of("channel", channel)
            );
        }
    }

    private Long telegramUserId(String externalUserId, Long chatId) {
        if (externalUserId != null && !externalUserId.isBlank()) {
            try {
                return Long.parseLong(externalUserId.trim());
            } catch (NumberFormatException e) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        ErrorCode.BAD_REQUEST,
                        "Telegram externalUserId must be numeric",
                        Map.of("externalUserId", externalUserId)
                );
            }
        }
        return chatId != null && chatId > 0 ? chatId : null;
    }

    private OutgoingMessage webLeadReply(IncomingMessage incoming) {
        String text = """
                Я приняла запрос и передам его команде C3AG: посмотрим задачу по продакшену, видео и сайту, а менеджер вернется с человеческим ответом.

                Если удобно, следующим сообщением оставьте контакт, дедлайн и ссылку на текущие материалы.
                """.strip();
        return OutgoingMessage.of(
                incoming,
                text,
                "WEB_LEAD_RECEIVED",
                false,
                false,
                false,
                false,
                null,
                List.of("WEB_LEAD_CAPTURED", "WEB_FAST_REPLY", "ADMIN_ALERT")
        ).withMetadata(Map.of(
                "site", incoming.payload() == null ? "c3ag" : incoming.payload().getOrDefault("site", "c3ag"),
                "webFastPath", true
        ));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MessageRequest(
            String channel,
            String externalUserId,
            Long chatId,
            String text,
            String contactPhone,
            String firstName,
            String username,
            String correlationId,
            Map<String, Object> payload
    ) {
    }

    public record MessageResponse(
            String channel,
            String externalUserId,
            Long chatId,
            String text,
            String nextState,
            boolean html,
            boolean requestContact,
            boolean removeKeyboard,
            boolean fallback,
            boolean adminAlertRequired,
            List<String> actions,
            Map<String, Object> metadata,
            Instant createdAt,
            /** WEB only: the session the browser must keep sending; generated when the request had none. */
            String sessionId,
            /** WEB only: buttons to render under the reply (web equivalent of the Telegram keyboard). */
            List<WebQuickReply> quickReplies
    ) {
        static MessageResponse from(OutgoingMessage outgoing) {
            return from(outgoing, null, List.of());
        }

        static MessageResponse from(OutgoingMessage outgoing, String sessionId, List<WebQuickReply> quickReplies) {
            return new MessageResponse(
                    outgoing.channel().name(),
                    outgoing.externalUserId(),
                    outgoing.chatId(),
                    outgoing.text(),
                    outgoing.nextState(),
                    outgoing.html(),
                    outgoing.requestContact(),
                    outgoing.removeKeyboard(),
                    outgoing.fallback(),
                    outgoing.adminAlert() != null && outgoing.adminAlert().required(),
                    outgoing.actions(),
                    outgoing.metadata(),
                    outgoing.createdAt(),
                    sessionId,
                    quickReplies == null ? List.of() : quickReplies
            );
        }
    }
}
