package museon_online.astor_butler.fsm.understanding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.model.ModelInteractionAuditRecord;
import museon_online.astor_butler.model.ModelInteractionAuditRepository;
import museon_online.astor_butler.model.ModelTextRequest;
import museon_online.astor_butler.model.ModelTextResponse;
import museon_online.astor_butler.model.ModelGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class LlmUnderstandingService {

    private final ModelGateway modelGateway;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private ModelInteractionAuditRepository auditRepository;

    @Value("${astor.understanding.llm.enabled:false}")
    private boolean enabled;

    @Value("${astor.understanding.llm.min-confidence:0.70}")
    private double minConfidence;

    @Value("${astor.understanding.llm.timeout-ms:6500}")
    private long timeoutMs;

    public LlmUnderstandingResult understand(
            String rawText,
            BotState currentState,
            String normalizedText,
            Map<String, SlotValue> localSlots
    ) {
        return understand(rawText, currentState, normalizedText, localSlots, Map.of());
    }

    public LlmUnderstandingResult understand(
            String rawText,
            BotState currentState,
            String normalizedText,
            Map<String, SlotValue> localSlots,
            Map<String, Object> context
    ) {
        if (!enabled || rawText == null || rawText.isBlank()) {
            return LlmUnderstandingResult.empty();
        }

        String state = currentState == null ? BotState.UNKNOWN.name() : currentState.canonical().name();
        String prompt = prompt(rawText, state, normalizedText, localSlots);
        try {
            ModelTextResponse response = generateWithTimeout(new ModelTextRequest(
                    prompt,
                    "LLM_UNDERSTANDING",
                    state,
                    "intent-slots-json",
                    museon_online.astor_butler.model.ModelProfile.FRONTLINE,
                    Map.of("rawText", rawText)
            ));
            audit(context, state, prompt, rawText, response, true, true, null);
            return parse(response);
        } catch (RuntimeException e) {
            audit(context, state, prompt, rawText, null, false, false, e);
            log.warn("LLM understanding skipped: state={}, reason={}", state, e.toString());
            return LlmUnderstandingResult.empty();
        }
    }

    public double minConfidence() {
        return minConfidence;
    }

    private ModelTextResponse generateWithTimeout(ModelTextRequest request) {
        if (timeoutMs <= 0) {
            return modelGateway.generateText(request);
        }
        try {
            return CompletableFuture
                    .supplyAsync(museon_online.astor_butler.domain.calllog.CallLogContext.propagate(() -> modelGateway.generateText(request)))
                    .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new RuntimeException("LLM understanding timed out or failed after " + timeoutMs + " ms", cause);
        }
    }

    private void audit(
            Map<String, Object> context,
            String state,
            String prompt,
            String rawText,
            ModelTextResponse response,
            boolean generated,
            boolean success,
            RuntimeException error
    ) {
        if (auditRepository == null) {
            return;
        }
        Map<String, Object> safeContext = context == null ? Map.of() : context;
        auditRepository.capture(new ModelInteractionAuditRecord(
                "AERIS",
                string(safeContext, "channel"),
                longValue(safeContext.get("chatId")),
                longValue(safeContext.get("telegramUserId")),
                string(safeContext, "correlationId"),
                "LLM_UNDERSTANDING",
                state,
                "intent-slots-json",
                response == null ? "" : response.provider(),
                response == null ? "" : response.model(),
                response == null || response.metadata() == null ? "" : String.valueOf(response.metadata().getOrDefault("profile", "")),
                prompt,
                rawText,
                "",
                response == null ? "" : response.text(),
                generated,
                response != null && response.fallback(),
                success,
                error == null ? "" : error.getClass().getSimpleName(),
                error == null ? "" : error.getMessage(),
                response == null ? Duration.ZERO : response.latency(),
                response == null || response.metadata() == null ? Map.of() : response.metadata()
        ));
    }

    private String string(Map<String, Object> context, String key) {
        Object value = context.get(key);
        return value == null ? "" : value.toString();
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private LlmUnderstandingResult parse(ModelTextResponse response) {
        String text = response == null ? "" : response.text();
        if (text == null || text.isBlank()) {
            return LlmUnderstandingResult.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(extractJson(text));
            InputIntent intent = parseIntent(root.path("intent").asText("UNKNOWN")).orElse(InputIntent.UNKNOWN);
            double confidence = clamp(root.path("confidence").asDouble(0.0));
            List<SlotValue> slots = slots(root.path("slots"));
            List<String> missingSlots = strings(root.path("missingSlots"));
            return new LlmUnderstandingResult(
                    intent,
                    confidence,
                    slots,
                    missingSlots,
                    root.path("replyDraft").asText(""),
                    response.provider(),
                    response.model()
            );
        } catch (Exception e) {
            log.warn("LLM understanding JSON was not accepted: reason={}, text={}", e.toString(), safeSnippet(text));
            return LlmUnderstandingResult.empty();
        }
    }

    private String prompt(
            String rawText,
            String state,
            String normalizedText,
            Map<String, SlotValue> localSlots
    ) {
        return """
                Верни только JSON. Ты извлекаешь intent и slots для FSM ресторана.
                Бизнес-действия не выполняй.
                Intents: TABLE_BOOKING, MENU_ASSETS, QUIET_GUIDE, SAFE_PLAY, EVENT_BOOKING, MANAGER_HELP, FEEDBACK, CHANGE_CANCEL, SMART_TIP, HIDDEN_HEART, ART_AUCTION, MERCH, MAIN_MENU, PROVIDE_DATE, PROVIDE_TIME, PROVIDE_PARTY_SIZE, PROVIDE_TABLE_SELECTION, PROVIDE_SEATING_PREFERENCE, AFFIRMATION, NEGATION, UNKNOWN.
                Slots: date, time, partySize, tableNumber, seatingPreference.
                Time format HH:mm. Party size as string number. Unknown meaning: intent UNKNOWN, confidence < 0.55.
                State: %s
                Normalized: %s
                Local slots: %s
                Guest text: %s
                JSON schema: {"intent":"TABLE_BOOKING","confidence":0.0,"slots":{"date":"","time":"","partySize":"","tableNumber":"","seatingPreference":""},"missingSlots":[],"replyDraft":""}
                """.formatted(
                state,
                normalizedText == null ? "" : normalizedText,
                localSlots == null ? Map.of() : localSlots,
                rawText
        );
    }

    private List<SlotValue> slots(JsonNode slotsNode) {
        if (slotsNode == null || !slotsNode.isObject()) {
            return List.of();
        }
        List<SlotValue> result = new ArrayList<>();
        slotsNode.fields().forEachRemaining(entry -> {
            String name = normalizeSlotName(entry.getKey());
            String value = entry.getValue() == null ? "" : entry.getValue().asText("");
            if (!name.isBlank() && !value.isBlank()) {
                result.add(new SlotValue(name, value, 0.88));
            }
        });
        return List.copyOf(result);
    }

    private List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode value : node) {
            String text = value.asText("");
            if (!text.isBlank()) {
                result.add(text);
            }
        }
        return List.copyOf(result);
    }

    private String extractJson(String text) {
        String value = text.trim();
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```(?:json)?", "").replaceFirst("```$", "").trim();
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return value.substring(start, end + 1);
        }
        return value;
    }

    private Optional<InputIntent> parseIntent(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(InputIntent.valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private String normalizeSlotName(String name) {
        return switch (name == null ? "" : name.trim()) {
            case "date", "time", "partySize", "tableNumber", "seatingPreference" -> name.trim();
            default -> "";
        };
    }

    private double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private String safeSnippet(String text) {
        if (text == null) {
            return "";
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() <= 240 ? compact : compact.substring(0, 240);
    }
}
