package museon_online.astor_butler.model;

import java.util.Map;

public record ModelVisionRequest(
        String prompt,
        String imageBase64,
        String mimeType,
        String model,
        String scenario,
        String state,
        String purpose,
        Map<String, Object> metadata,
        Integer maxTokens
) {
    public ModelVisionRequest {
        if (metadata == null) {
            metadata = Map.of();
        }
    }

    public ModelVisionRequest(String prompt, String imageBase64, String mimeType, String model, String scenario,
                              String state, String purpose, Map<String, Object> metadata) {
        this(prompt, imageBase64, mimeType, model, scenario, state, purpose, metadata, null);
    }

    /** A longer answer than the provider's default for photos (a whole schedule as JSON); null keeps the default. */
    public ModelVisionRequest withMaxTokens(Integer tokens) {
        return new ModelVisionRequest(prompt, imageBase64, mimeType, model, scenario, state, purpose, metadata, tokens);
    }

    public static ModelVisionRequest of(
            String prompt,
            String imageBase64,
            String mimeType,
            String model,
            String scenario,
            String state,
            String purpose
    ) {
        return new ModelVisionRequest(prompt, imageBase64, mimeType, model, scenario, state, purpose, Map.of());
    }
}
