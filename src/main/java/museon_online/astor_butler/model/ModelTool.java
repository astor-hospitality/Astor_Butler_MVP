package museon_online.astor_butler.model;

import java.util.Map;

/** A function the model may call; {@code parameters} is a JSON Schema object. */
public record ModelTool(String name, String description, Map<String, Object> parameters) {
    public ModelTool {
        if (parameters == null) {
            parameters = Map.of("type", "object", "properties", Map.of());
        }
    }
}
