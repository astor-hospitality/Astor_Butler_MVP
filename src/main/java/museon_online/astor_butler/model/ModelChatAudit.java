package museon_online.astor_butler.model;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code model_interaction_audit} row of one {@link ToolCallingModel#chat} call, the same for every provider. The
 * prompt keeps the roles and texts that were sent; tool arguments are never stored, only the called tool's name. A
 * request whose metadata says {@code auditContent=false} (a guest who has not consented) keeps only roles and tool
 * names: every text is {@value #HIDDEN}.
 */
@Slf4j
final class ModelChatAudit {

    /** What the audit keeps of a text the guest has not agreed to store. */
    static final String HIDDEN = "[скрыто]";

    private ModelChatAudit() {
    }

    static void record(ModelInteractionAuditRepository repository, String provider, ModelChatRequest request, String model,
                       ModelChatResponse response, Duration latency, RuntimeException error) {
        if (repository == null) {
            return;
        }
        try {
            Map<String, Object> context = request.metadata();
            boolean content = auditContent(context);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("capability", ModelCapability.TOOL_CALLING.name());
            metadata.put("tools", request.tools().stream().map(ModelTool::name).toList());
            String toolCall = response == null ? "" : response.toolCall().map(ModelToolCall::name).orElse("");
            metadata.put("toolCall", toolCall);
            if (response != null) {
                metadata.putAll(response.metadata());
            }
            repository.capture(new ModelInteractionAuditRecord(
                    "AERIS",
                    contextString(context, "channel"),
                    contextLong(context.get("chatId")),
                    contextLong(context.get("telegramUserId")),
                    contextString(context, "correlationId"),
                    contextString(context, "scenario"),
                    contextString(context, "state"),
                    request.purpose(),
                    provider,
                    model,
                    request.profile().name(),
                    prompt(request.messages(), content),
                    content ? contextString(context, "guestText") : "",
                    "",
                    response == null ? "" : (!toolCall.isEmpty() ? "function_call:" + toolCall : content ? response.text() : HIDDEN),
                    response != null,
                    false,
                    error == null,
                    error == null ? "" : error.getClass().getSimpleName(),
                    error == null ? "" : error.getMessage(),
                    latency,
                    metadata
            ));
        } catch (RuntimeException e) {
            log.warn("Chat audit was not prepared: {}", e.toString());
        }
    }

    /** Only an explicit {@code false} hides the texts: callers that never set it keep the full audit. */
    private static boolean auditContent(Map<String, Object> context) {
        Object value = context.get("auditContent");
        return !(Boolean.FALSE.equals(value) || "false".equalsIgnoreCase(String.valueOf(value)));
    }

    private static String prompt(List<ModelChatMessage> messages, boolean content) {
        StringBuilder prompt = new StringBuilder();
        for (ModelChatMessage message : messages) {
            prompt.append(message.role()).append(": ");
            if (message.functionCall() != null) {
                prompt.append("function_call:").append(message.functionCall().name());
            } else {
                if (message.name() != null && !message.name().isBlank()) {
                    prompt.append('[').append(message.name()).append("] ");
                }
                prompt.append(content ? (message.content() == null ? "" : message.content()) : HIDDEN);
            }
            prompt.append('\n');
        }
        return prompt.toString();
    }

    private static String contextString(Map<String, Object> context, String key) {
        Object value = context.get(key);
        return value == null ? "" : value.toString();
    }

    private static Long contextLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
