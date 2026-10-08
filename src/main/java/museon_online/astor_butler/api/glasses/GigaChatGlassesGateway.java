package museon_online.astor_butler.api.glasses;

import museon_online.astor_butler.model.*;
import org.springframework.boot.restclient.RestTemplateBuilder;

import java.time.Duration;
import java.util.function.Function;

/**
 * The glasses on the GigaChat API called directly ({@code ASTOR_GLASSES_AI_PROVIDER=gigachat}). The wire work is
 * the bot's {@link GigaChatModelGateway}: NGW OAuth with the token cache, the Russian Trusted Root CA through
 * {@link GigaChatTrust}, photos uploaded to {@code /files} and attached by id. This class only holds it to the
 * glasses contract that {@link GlassesCompletionsGateway} keeps for the OpenAI-format providers: one text
 * model, one vision model, a complete answer ({@code finish_reason=stop}, not blank) or "Provider unavailable",
 * and no provider diagnostics, keys or tokens in what leaves it.
 *
 * <p>Settings, read from the environment by {@link #fromEnvironment}: {@code ASTOR_GLASSES_GIGACHAT_AUTH_KEY}
 * (else {@code GIGACHAT_AUTH_KEY}), {@code GIGACHAT_SCOPE}, {@code GIGACHAT_CA_CERT_PATH} (else
 * {@code SALUTE_CA_CERT_PATH}), {@code ASTOR_GLASSES_TEXT_MODEL} and {@code ASTOR_GLASSES_VISION_MODEL}
 * (both {@code GigaChat-2-Max} by default), optional {@code GIGACHAT_OAUTH_URL}, {@code GIGACHAT_API_URL},
 * {@code GIGACHAT_TIMEOUT_MS}. A Cloud.ru catalogue name such as {@code GigaChat/GigaChat-2-Max} left over in
 * the environment is reduced to the GigaChat API name.
 */
public final class GigaChatGlassesGateway implements ModelGateway {
    public static final String PROVIDER = "gigachat";
    public static final String DEFAULT_MODEL = "GigaChat-2-Max";
    static final String LABEL = "gigachat-glasses";
    /** The same bounds the OpenAI-format glasses gateway uses: a spoken text answer, a fuller photo answer. */
    static final int TEXT_MAX_TOKENS = 256;
    static final int VISION_MAX_TOKENS = 1024;
    static final double TEMPERATURE = 0.1;
    static final int DEFAULT_TIMEOUT_MS = 20000;

    private final ModelGateway delegate;

    GigaChatGlassesGateway(ModelGateway delegate) {
        this.delegate = delegate;
    }

    /** @param env an environment lookup that answers null for a variable that is not set */
    public static GigaChatGlassesGateway fromEnvironment(Function<String, String> env) {
        String authKey = first(env, "ASTOR_GLASSES_GIGACHAT_AUTH_KEY", "GIGACHAT_AUTH_KEY");
        String caCertPath = first(env, "GIGACHAT_CA_CERT_PATH", "SALUTE_CA_CERT_PATH");
        String text = modelName(first(env, "ASTOR_GLASSES_TEXT_MODEL"));
        String vision = modelName(first(env, "ASTOR_GLASSES_VISION_MODEL"));
        int timeoutMs = DEFAULT_TIMEOUT_MS;
        String timeout = first(env, "GIGACHAT_TIMEOUT_MS");
        if (!timeout.isEmpty()) {
            try {
                timeoutMs = Integer.parseInt(timeout);
            } catch (NumberFormatException e) {
                throw new IllegalStateException("GIGACHAT_TIMEOUT_MS must be a number of milliseconds");
            }
        }
        return new GigaChatGlassesGateway(new GigaChatModelGateway(new RestTemplateBuilder(),
                first(env, "GIGACHAT_OAUTH_URL"), first(env, "GIGACHAT_API_URL"), authKey, first(env, "GIGACHAT_SCOPE"),
                text, "", vision, "", caCertPath, timeoutMs, TEXT_MAX_TOKENS, VISION_MAX_TOKENS, TEMPERATURE));
    }

    @Override public ModelTextResponse generateText(ModelTextRequest request) {
        ModelTextResponse response;
        try {
            response = delegate.generateText(request);
        } catch (RuntimeException e) {
            throw unavailable();
        }
        if (response == null) throw unavailable();
        String text = complete(response.text(), response.fallback(), response.metadata().get("finishReason"));
        return ModelTextResponse.text(text, LABEL, response.model(), response.latency());
    }

    @Override public ModelVisionResponse analyzeImage(ModelVisionRequest request) {
        ModelVisionResponse response;
        try {
            response = delegate.analyzeImage(request);
        } catch (RuntimeException e) {
            throw unavailable();
        }
        if (response == null) throw unavailable();
        String text = complete(response.text(), response.fallback(), response.metadata().get("finishReason"));
        return ModelVisionResponse.vision(text, LABEL, response.model(), response.latency());
    }

    @Override public ModelEmbeddingResponse generateEmbedding(ModelEmbeddingRequest request) {
        throw new UnsupportedOperationException("Embeddings outside glasses scope");
    }

    /** A truncated ("length") or filtered ("blacklist") answer is not an answer the glasses may speak. */
    private static String complete(String text, boolean fallback, Object finishReason) {
        if (fallback || text == null || text.isBlank() || !"stop".equals(finishReason)) throw unavailable();
        return text.trim();
    }

    /** Blank means the default; the Cloud.ru catalogue prefix ({@code GigaChat/}) is not part of the API name. */
    static String modelName(String configured) {
        String name = configured == null ? "" : configured.strip();
        if (name.regionMatches(true, 0, "GigaChat/", 0, "GigaChat/".length())) name = name.substring("GigaChat/".length());
        return name.isEmpty() ? DEFAULT_MODEL : name;
    }

    private static String first(Function<String, String> env, String... names) {
        for (String name : names) {
            String value = env.apply(name);
            if (value != null && !value.isBlank()) return value.strip();
        }
        return "";
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Provider unavailable");
    }
}
