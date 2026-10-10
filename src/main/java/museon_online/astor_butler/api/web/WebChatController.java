package museon_online.astor_butler.api.web;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import museon_online.astor_butler.api.common.ApiException;
import museon_online.astor_butler.api.common.ErrorCode;
import museon_online.astor_butler.api.message.MessageController;
import museon_online.astor_butler.domain.web.WebChannelPolicy;
import museon_online.astor_butler.domain.web.WebQuickReply;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Public transport of the Astor site chat widget. Only anonymous WEB messages are accepted: the channel is
 * forced, chatId/externalUserId are dropped, the payload is reduced to an allow-list and the body is bounded.
 * Everything else is the regular message gateway ({@link MessageController}); the reply is trimmed to what a browser needs.
 */
@RestController
@Tag(name = "Web chat", description = "Anonymous web chat transport for the Astor site widget")
public class WebChatController {

    public static final String PATH = "/api/astor/messages";
    static final int MAX_BODY_BYTES = 16 * 1024;
    static final Set<String> PAYLOAD_KEYS = Set.of(
            "sessionId", "site", "pageContext", "sentAt", "action", "contactPhone",
            "locale", "page", "referrer", "consent", "userAgentHash"
    );

    private final MessageController messageController;
    private final ObjectMapper objectMapper;

    public WebChatController(MessageController messageController, ObjectMapper objectMapper) {
        this.messageController = messageController;
        this.objectMapper = objectMapper;
    }

    @PostMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Web chat message from the Astor site widget",
            description = "Same contract as POST /api/messages for channel WEB, but the channel is forced, identity fields are ignored "
                    + "and the reply carries quick replies (web keyboard) and the session id. See docs/architecture/WEB_CHANNEL_ADAPTER.md."
    )
    public ResponseEntity<WebChatResponse> process(HttpServletRequest request) throws IOException {
        // Checked by hand rather than through "consumes": the global handler would turn the framework's 415 into a 500.
        String contentType = request.getContentType();
        if (contentType == null || !MediaType.parseMediaType(contentType).isCompatibleWith(MediaType.APPLICATION_JSON)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.BAD_REQUEST, "Web message must be application/json");
        }
        byte[] input = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (input.length > MAX_BODY_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCode.BAD_REQUEST, "Web message is too large");
        }
        JsonNode body;
        try {
            body = objectMapper.readTree(input);
        } catch (JacksonException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "Web message is not valid JSON");
        }
        if (body == null || !body.isObject()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "Web message must be a JSON object");
        }
        String channel = body.path("channel").asText("WEB");
        if (!"WEB".equalsIgnoreCase(channel.trim())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "Only the WEB channel is accepted here",
                    Map.of("channel", channel));
        }
        JsonNode textNode = body.path("text");
        if (!textNode.isMissingNode() && !textNode.isNull() && !textNode.isTextual()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "text must be a string");
        }
        String text = textNode.asText("");
        if (text.codePointCount(0, text.length()) > WebChannelPolicy.MAX_TEXT_CHARS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "text is longer than " + WebChannelPolicy.MAX_TEXT_CHARS + " characters");
        }
        Map<String, Object> payload = payload(body.path("payload"));
        if (text.isBlank() && !payload.containsKey("action") && !payload.containsKey("contactPhone")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "text, payload.action or payload.contactPhone is required");
        }

        MessageController.MessageRequest sanitized = new MessageController.MessageRequest(
                "WEB",
                null,
                null,
                text,
                null,
                null,
                null,
                null,
                payload
        );
        ResponseEntity<MessageController.MessageResponse> gateway = messageController.process(sanitized, request);
        MessageController.MessageResponse reply = gateway.getBody();
        if (reply == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE, "Message gateway returned no reply");
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.status(gateway.getStatusCode())
                .cacheControl(CacheControl.noStore());
        String retryAfter = gateway.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        if (retryAfter != null) {
            response.header(HttpHeaders.RETRY_AFTER, retryAfter);
        }
        return response.body(WebChatResponse.from(reply));
    }

    private Map<String, Object> payload(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "payload must be an object");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!PAYLOAD_KEYS.contains(field.getKey())) {
                continue;
            }
            JsonNode value = field.getValue();
            if (value.isTextual()) {
                payload.put(field.getKey(), value.asText());
            } else if (value.isObject() && "consent".equals(field.getKey())) {
                payload.put(field.getKey(), objectMapper.convertValue(value, Map.class));
            } else if (value.isValueNode() && !value.isNull()) {
                payload.put(field.getKey(), value.asText());
            }
        }
        return payload;
    }

    /** What the widget sees. Internal fields of the gateway reply (chatId, actions, metadata) stay inside. */
    public record WebChatResponse(
            String channel,
            String product,
            String sessionId,
            String text,
            boolean html,
            boolean requestContact,
            String nextState,
            boolean fallback,
            List<WebQuickReply> quickReplies,
            String correlationId,
            Instant createdAt
    ) {
        static WebChatResponse from(MessageController.MessageResponse reply) {
            Object correlationId = reply.metadata() == null ? null : reply.metadata().get("correlationId");
            return new WebChatResponse(
                    "WEB",
                    "butler",
                    reply.sessionId(),
                    reply.text(),
                    reply.html(),
                    reply.requestContact(),
                    reply.nextState(),
                    reply.fallback(),
                    reply.quickReplies() == null ? List.of() : reply.quickReplies(),
                    correlationId == null ? null : correlationId.toString(),
                    reply.createdAt()
            );
        }
    }
}
