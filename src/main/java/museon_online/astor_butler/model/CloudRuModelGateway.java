package museon_online.astor_butler.model;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.stereotype.Service;

/**
 * Cloud.ru Evolution Foundation Models: Sber's hosted models behind the OpenAI HTTP API
 * ({@code https://foundation-models.api.cloud.ru/v1}, bearer API key from the Cloud.ru console).
 *
 * <p>Selected with {@code ASTOR_MODEL_PROVIDER=cloudru}. The wire format is the OpenAI-compatible one,
 * so this is {@link OpenAiCompatibleModelGateway} under its own name and {@code CLOUDRU_*} settings:
 * the two providers can be configured side by side and switched by one variable. Model names are
 * the catalogue's own ({@code GET /models}), for example {@code GigaChat/GigaChat-2-Max},
 * {@code Qwen/Qwen3-235B-A22B-Instruct-2507} or {@code openai/gpt-oss-120b}.
 */
@Service
@ConditionalOnProperty(prefix = "astor.model", name = "provider", havingValue = "cloudru", matchIfMissing = false)
public class CloudRuModelGateway extends OpenAiCompatibleModelGateway {

    static final String PROVIDER = "cloudru";
    static final String ENV_PREFIX = "CLOUDRU";
    static final String DEFAULT_BASE_URL = "https://foundation-models.api.cloud.ru/v1";

    public CloudRuModelGateway(
            RestTemplateBuilder restTemplateBuilder,
            @Value("${astor.model.cloudru.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl,
            @Value("${astor.model.cloudru.api-key:}") String apiKey,
            @Value("${astor.model.cloudru.model:}") String frontlineModel,
            @Value("${astor.model.cloudru.quality-model:}") String qualityModel,
            @Value("${astor.model.cloudru.vision-model:}") String visionModel,
            @Value("${astor.model.cloudru.embedding-model:}") String embeddingModel,
            @Value("${astor.model.cloudru.timeout-ms:15000}") int timeoutMs,
            @Value("${astor.model.cloudru.max-tokens:256}") int maxTokens,
            @Value("${astor.model.cloudru.temperature:0.1}") double temperature,
            @Value("${astor.model.cloudru.json-mode:true}") boolean jsonMode
    ) {
        super(PROVIDER, ENV_PREFIX, restTemplateBuilder,
                baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl,
                apiKey, frontlineModel, qualityModel, visionModel, embeddingModel,
                timeoutMs, maxTokens, temperature, jsonMode);
    }
}
