package museon_online.astor_butler.fsm.reply;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.semantic.SemanticSearchResult;
import museon_online.astor_butler.model.ModelInteractionAuditRecord;
import museon_online.astor_butler.model.ModelInteractionAuditRepository;
import museon_online.astor_butler.model.ModelGateway;
import museon_online.astor_butler.model.ModelTextRequest;
import museon_online.astor_butler.model.ModelTextResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
public class ScenarioReplyComposer {

    private static final int MAX_REPLY_CHARS = 900;
    private static final int DEFAULT_LLM_TIMEOUT_MS = 6500;

    private final ModelGateway modelGateway;
    private final ModelInteractionAuditRepository auditRepository;
    private final boolean enabled;
    private final int maxRagChars;
    private final int llmTimeoutMs;

    @Autowired
    public ScenarioReplyComposer(
            ModelGateway modelGateway,
            ObjectProvider<ModelInteractionAuditRepository> auditRepository,
            @Value("${astor.scenario-replies.llm.enabled:false}") boolean enabled,
            @Value("${astor.scenario-replies.llm.max-rag-chars:900}") int maxRagChars,
            @Value("${astor.scenario-replies.llm.timeout-ms:6500}") int llmTimeoutMs
    ) {
        this.modelGateway = modelGateway;
        this.auditRepository = auditRepository.getIfAvailable();
        this.enabled = enabled;
        this.maxRagChars = Math.max(200, maxRagChars);
        this.llmTimeoutMs = Math.max(50, llmTimeoutMs);
    }

    public ScenarioReplyComposer(ModelGateway modelGateway, boolean enabled, int maxRagChars) {
        this(modelGateway, enabled, maxRagChars, DEFAULT_LLM_TIMEOUT_MS);
    }

    public ScenarioReplyComposer(ModelGateway modelGateway, boolean enabled, int maxRagChars, int llmTimeoutMs) {
        this.modelGateway = modelGateway;
        this.auditRepository = null;
        this.enabled = enabled;
        this.maxRagChars = Math.max(200, maxRagChars);
        this.llmTimeoutMs = Math.max(50, llmTimeoutMs);
    }

    public ScenarioReply compose(ScenarioReplyDraft draft) {
        String fallback = normalizeFallback(draft.fallbackText());
        if (!enabled || draft.ragContext().isEmpty()) {
            audit(draft, "", null, fallback, false, true, true, null);
            return ScenarioReply.fallback(fallback);
        }

        String prompt = promptFor(draft, fallback);
        try {
            ModelTextRequest request = ModelTextRequest.of(
                    prompt,
                    draft.scenario(),
                    draft.state(),
                    draft.purpose()
            );
            ModelTextResponse response = CompletableFuture
                    .supplyAsync(museon_online.astor_butler.domain.calllog.CallLogContext.propagate(() -> modelGateway.generateText(request)))
                    .orTimeout(llmTimeoutMs, TimeUnit.MILLISECONDS)
                    .join();
            String text = normalizeGenerated(response.text());
            if (text.isBlank()) {
                audit(draft, prompt, response, fallback, false, true, true, null);
                return ScenarioReply.fallback(fallback);
            }
            audit(draft, prompt, response, text, true, response.fallback(), true, null);
            return new ScenarioReply(
                    text,
                    true,
                    response.fallback(),
                    response.provider(),
                    response.model()
            );
        } catch (RuntimeException ex) {
            RuntimeException root = unwrapCompletionException(ex);
            audit(draft, prompt, null, fallback, false, true, false, root);
            log.warn(
                    "Scenario reply generation failed; using approved fallback. scenario={} state={} purpose={} reason={}",
                    draft.scenario(),
                    draft.state(),
                    draft.purpose(),
                    root.toString()
            );
            return ScenarioReply.fallback(fallback);
        }
    }

    private RuntimeException unwrapCompletionException(RuntimeException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof TimeoutException timeout) {
            return new RuntimeException("LLM timed out after " + llmTimeoutMs + " ms", timeout);
        }
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        return ex;
    }

    private String promptFor(ScenarioReplyDraft draft, String fallback) {
        return """
                Ты пишешь короткий ответ гостю ресторана AERIS на русском языке.

                Жесткие правила:
                - FSM уже решил действие. Не меняй следующий шаг сценария.
                - Не обещай бронь, оплату, наличие, скидки или цены.
                - Используй только факты из approved fallback и RAG context.
                - Если фактов недостаточно, верни смысл approved fallback.
                - Не используй Markdown. Не используй HTML. Не добавляй списки длиннее 4 пунктов.
                - Ответ должен быть теплым, спокойным и короче 900 символов.

                Scenario: %s
                State: %s
                Purpose: %s
                Guest text: %s

                Approved fallback:
                %s

                RAG context:
                %s

                Верни только текст ответа гостю.
                """.formatted(
                safe(draft.scenario()),
                safe(draft.state()),
                safe(draft.purpose()),
                safe(draft.guestText()),
                fallback,
                ragContext(draft.ragContext())
        );
    }

    private void audit(
            ScenarioReplyDraft draft,
            String prompt,
            ModelTextResponse response,
            String responseText,
            boolean generated,
            boolean fallbackUsed,
            boolean success,
            RuntimeException error
    ) {
        if (auditRepository == null || draft == null) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("ragContextSize", draft.ragContext().size());
        metadata.put("composerEnabled", enabled);
        if (response != null && response.metadata() != null && !response.metadata().isEmpty()) {
            metadata.put("modelResponse", response.metadata());
        }
        auditRepository.capture(new ModelInteractionAuditRecord(
                draft.venueCode(),
                draft.channel(),
                draft.chatId(),
                draft.telegramUserId(),
                draft.correlationId(),
                draft.scenario(),
                draft.state(),
                draft.purpose(),
                response == null ? "" : response.provider(),
                response == null ? "" : response.model(),
                response == null || response.metadata() == null ? "" : String.valueOf(response.metadata().getOrDefault("profile", "")),
                prompt,
                draft.guestText(),
                draft.fallbackText(),
                responseText,
                generated,
                fallbackUsed,
                success,
                error == null ? "" : error.getClass().getSimpleName(),
                error == null ? "" : error.getMessage(),
                response == null ? Duration.ZERO : response.latency(),
                metadata
        ));
    }

    private String ragContext(List<SemanticSearchResult> results) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            SemanticSearchResult result = results.get(i);
            builder.append(i + 1)
                    .append(". ")
                    .append(safe(result.title()))
                    .append(" / ")
                    .append(safe(result.sourceCode()))
                    .append(": ")
                    .append(safe(result.shortContent(360)))
                    .append("\n");
            if (builder.length() >= maxRagChars) {
                break;
            }
        }
        String value = builder.toString().trim();
        return value.length() > maxRagChars ? value.substring(0, maxRagChars) : value;
    }

    private String normalizeFallback(String value) {
        String fallback = value == null ? "" : value.trim();
        return fallback.isBlank() ? "Я на связи. Подскажу следующий шаг." : fallback;
    }

    private String normalizeGenerated(String value) {
        if (value == null) {
            return "";
        }
        String text = value
                .replace("```", "")
                .replaceAll("\\s+\\n", "\n")
                .trim();
        if (text.length() > MAX_REPLY_CHARS) {
            return "";
        }
        return text;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
