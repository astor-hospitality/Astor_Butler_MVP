package museon_online.astor_butler.model;

import java.util.Map;

/** A function call the model asked for; {@code arguments} is never null. */
public record ModelToolCall(String name, Map<String, Object> arguments) {
    public ModelToolCall {
        if (arguments == null) {
            arguments = Map.of();
        }
    }
}
