package museon_online.astor_butler.model;

import java.util.Map;

/**
 * A function call the model asked for; {@code arguments} is never null. {@code id} is the provider's own call id
 * (OpenAI-style {@code tool_calls[].id}) when it gave one: the tool result is sent back under it. GigaChat has none.
 */
public record ModelToolCall(String name, Map<String, Object> arguments, String id) {
    public ModelToolCall {
        if (arguments == null) {
            arguments = Map.of();
        }
        if (id != null && id.isBlank()) {
            id = null;
        }
    }

    public ModelToolCall(String name, Map<String, Object> arguments) {
        this(name, arguments, null);
    }
}
