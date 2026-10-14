package museon_online.astor_butler.model;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/** The model's reply: either a tool call or plain {@code text}. */
public record ModelChatResponse(
        String text,
        Optional<ModelToolCall> toolCall,
        String provider,
        String model,
        Duration latency,
        Map<String, Object> metadata
) {
    public ModelChatResponse {
        if (text == null) {
            text = "";
        }
        if (toolCall == null) {
            toolCall = Optional.empty();
        }
        if (metadata == null) {
            metadata = Map.of();
        }
    }

    public ModelChatResponse(String text, Optional<ModelToolCall> toolCall, String provider, String model, Duration latency) {
        this(text, toolCall, provider, model, latency, Map.of());
    }
}
