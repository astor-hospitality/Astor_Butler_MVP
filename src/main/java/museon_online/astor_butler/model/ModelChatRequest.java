package museon_online.astor_butler.model;

import java.util.List;
import java.util.Map;

/**
 * A chat completion with optional tools. An empty tool list makes it a plain completion.
 * {@code model}, when set, replaces the configured model name (the agent passes {@code astor.agent.model}).
 * {@code metadata} feeds the audit (channel, chatId, telegramUserId, correlationId, scenario, state).
 */
public record ModelChatRequest(
        List<ModelChatMessage> messages,
        List<ModelTool> tools,
        ModelProfile profile,
        String purpose,
        Map<String, Object> metadata,
        String model
) {
    public ModelChatRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        if (profile == null) {
            profile = ModelProfile.FRONTLINE;
        }
        if (metadata == null) {
            metadata = Map.of();
        }
        if (model != null && model.isBlank()) {
            model = null;
        }
    }

    public ModelChatRequest(List<ModelChatMessage> messages, List<ModelTool> tools, ModelProfile profile,
                            String purpose, Map<String, Object> metadata) {
        this(messages, tools, profile, purpose, metadata, null);
    }
}
