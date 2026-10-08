package museon_online.astor_butler.domain.semantic;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Fails startup with a message naming {@code ASTOR_SEMANTIC_EMBEDDINGS_PROVIDER} when the value is not one of the
 * adapters; otherwise every conditional {@link EmbeddingProvider} would back off and semantic search would silently stay
 * off. A blank value means {@code none}.
 */
@Component
public class SemanticEmbeddingsProviderCheck {

    static final List<String> PROVIDERS = List.of("none", "model-gateway", "yandex", "ollama", "spring-ai");

    private final String provider;

    public SemanticEmbeddingsProviderCheck(@Value("${astor.semantic-memory.embeddings.provider:none}") String provider) {
        String normalized = provider == null || provider.isBlank() ? "none" : provider.trim().toLowerCase(Locale.ROOT);
        if (!PROVIDERS.contains(normalized)) {
            throw new IllegalStateException("ASTOR_SEMANTIC_EMBEDDINGS_PROVIDER must be one of "
                    + String.join(", ", PROVIDERS) + ", got '" + provider + "'");
        }
        this.provider = normalized;
    }

    public String provider() {
        return provider;
    }
}
