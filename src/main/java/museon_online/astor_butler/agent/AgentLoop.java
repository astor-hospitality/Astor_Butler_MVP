package museon_online.astor_butler.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityRegistry;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.domain.calllog.CallLogContext;
import museon_online.astor_butler.model.ModelChatMessage;
import museon_online.astor_butler.model.ModelChatRequest;
import museon_online.astor_butler.model.ModelChatResponse;
import museon_online.astor_butler.model.ModelProfile;
import museon_online.astor_butler.model.ModelTool;
import museon_online.astor_butler.model.ModelToolCall;
import museon_online.astor_butler.model.ToolCallingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * One guest turn of an agent: the model answers or calls a tool, the tool's facts go back to it, and so on. At most
 * {@code maxToolCalls} (5) calls; after the last one the model is asked once more without tools. A model call that
 * fails in any way or outlasts {@code modelTimeoutMs} (20 s), or a turn that outlasts {@code turnTimeoutMs}, ends the
 * turn as a fallback: the caller takes its old path, the guest never sees a model error.
 *
 * <p>{@code ctx.idempotencyKey()} is the turn's key; each tool call gets {@code <turn key>:<call number>}, so a
 * retried turn repeats its commitments' keys and books nothing twice. A tool the model was not offered is a
 * {@code NOT_FOUND} fact, never run. A failed model call is never repeated, here or through another provider: the
 * turn falls back, and a tool that already ran keeps its key.
 *
 * <p>The model is the application's {@link ToolCallingModel} (GigaChat, or the OpenAI-compatible gateway), unless
 * {@code ASTOR_AGENT_PROVIDER} gives the agent its own ({@link AgentModelConfiguration#AGENT_MODEL}). With a
 * {@link MeterRegistry} every model call, turn and tool call is counted by provider and model:
 * {@code astor.agent.model.calls} (timer with p50/p95/p99), {@code astor.agent.model.tokens},
 * {@code astor.agent.turns} and {@code astor.agent.tool.calls}.
 */
@Component
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    private static final ExecutorService MODEL_CALLS = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("butler-agent-model-", 0).factory());

    private final CapabilityRegistry registry;
    private final AgentProperties properties;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private ToolCallingModel model;
    /** {@code ASTOR_AGENT_PROVIDER}'s own model; when set it answers instead of {@link #model}. */
    private ToolCallingModel agentModel;
    private String defaultProvider = "";
    private MeterRegistry meters;
    private AgentBudget budget;

    @Autowired
    public AgentLoop(CapabilityRegistry registry, AgentProperties properties) {
        this(null, registry, properties);
    }

    public AgentLoop(ToolCallingModel model, CapabilityRegistry registry, AgentProperties properties) {
        this.model = model;
        this.registry = registry;
        this.properties = properties;
    }

    /** The application's tool-calling model (GigaChat or openai-compatible); without one every turn is a fallback. */
    @Autowired(required = false)
    public void setModel(ToolCallingModel model) {
        this.model = model;
    }

    /** The agent's own model from {@code ASTOR_AGENT_PROVIDER}; the rest of the application keeps its provider. */
    @Autowired(required = false)
    public void setAgentModel(@Qualifier(AgentModelConfiguration.AGENT_MODEL) ToolCallingModel agentModel) {
        this.agentModel = agentModel;
    }

    /** {@code ASTOR_MODEL_PROVIDER}: the provider label of the metrics while the agent uses the application's model. */
    @Autowired(required = false)
    public void setDefaultProvider(@Value("${astor.model.provider:}") String provider) {
        this.defaultProvider = provider == null ? "" : provider.trim();
    }

    /** {@code ASTOR_AGENT_DAILY_BUDGET_RUB}: each model call reserves its price first; no room — the turn falls back. */
    @Autowired(required = false)
    public void setBudget(AgentBudget budget) {
        this.budget = budget;
    }

    @Autowired(required = false)
    public void setMeterRegistry(MeterRegistry meters) {
        this.meters = meters;
    }

    ToolCallingModel currentModel() {
        return agentModel != null ? agentModel : model;
    }

    /** The provider label of this turn's metrics. */
    private String providerLabel() {
        String provider = agentModel != null ? properties.getProvider() : defaultProvider;
        return provider == null || provider.isBlank() ? "unknown" : provider.trim();
    }

    public AgentOutcome run(List<ModelChatMessage> history, List<ModelTool> tools, CapabilityContext ctx) {
        return run(history, tools, ctx, true);
    }

    /**
     * @param auditContent false when the guest has not agreed to keep the words: the model audit then stores only
     *                     the roles and tool names of each call ({@code auditContent=false} in the request metadata)
     */
    public AgentOutcome run(List<ModelChatMessage> history, List<ModelTool> tools, CapabilityContext ctx, boolean auditContent) {
        return run(history, tools, ctx, auditContent, properties.getTurnTimeoutMs());
    }

    /** @param turnTimeoutMs the whole turn's budget, e.g. the shorter one of the Butler's own bot */
    public AgentOutcome run(List<ModelChatMessage> history, List<ModelTool> tools, CapabilityContext ctx, boolean auditContent,
                            long turnTimeoutMs) {
        if (currentModel() == null) {
            countTurn("fallback", "no_model");
            return AgentOutcome.fallback(List.of(), 0);
        }
        List<ModelTool> offered = tools == null ? List.of() : tools;
        Set<String> names = offered.stream().map(ModelTool::name).collect(Collectors.toSet());
        List<ModelChatMessage> messages = new ArrayList<>(history == null ? List.of() : history);
        List<AgentOutcome.ToolUse> uses = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, turnTimeoutMs));
        int calls = 0;
        while (true) {
            boolean toolsAllowed = calls < properties.getMaxToolCalls() && !offered.isEmpty();
            AgentBudget.Reservation reservation = budget == null ? null : budget.reserve();
            if (budget != null && reservation == null) {
                log.warn("Agent turn falls back: calls={}, reason=daily_budget", calls);
                countTurn("fallback", "daily_budget");
                return AgentOutcome.fallback(uses, calls);
            }
            ModelChatResponse response;
            try {
                response = ask(messages, offered, !toolsAllowed, ctx, deadline, auditContent);
            } catch (RuntimeException e) {
                // A timed-out or failed call may still be charged: it keeps its whole reservation.
                settle(reservation, null);
                log.warn("Agent turn falls back: calls={}, reason={}", calls, reason(e));
                countTurn("fallback", timedOut(e) ? "timeout" : "model_error");
                return AgentOutcome.fallback(uses, calls);
            }
            settle(reservation, response);
            if (toolsAllowed && response.toolCall().isPresent()) {
                calls++;
                ModelToolCall call = response.toolCall().get();
                CapabilityResult result = names.contains(call.name())
                        ? invoke(call, withKey(ctx, calls))
                        : CapabilityResult.error(CapabilityResult.NOT_FOUND);
                uses.add(new AgentOutcome.ToolUse(call.name(), result));
                countTool(names.contains(call.name()) ? call.name() : "unknown", result);
                messages.add(ModelChatMessage.assistantCall("", call));
                messages.add(ModelChatMessage.functionResult(call.name(), json(result)));
                continue;
            }
            String text = response.text() == null ? "" : response.text().trim();
            if (text.isEmpty()) {
                log.warn("Agent turn falls back: calls={}, reason=blank_answer", calls);
                countTurn("fallback", "blank_answer");
                return AgentOutcome.fallback(uses, calls);
            }
            countTurn("answered", "");
            return new AgentOutcome(text, uses.stream().map(AgentOutcome.ToolUse::result).toList(), false, calls, uses);
        }
    }

    /** One model call bounded by the per-call timeout and what is left of the turn. */
    /** @param answerOnly the last step: the tools are only declared (the history calls them), the model must answer */
    private ModelChatResponse ask(List<ModelChatMessage> messages, List<ModelTool> tools, boolean answerOnly, CapabilityContext ctx,
                                  long deadline, boolean auditContent) {
        long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (left <= 0) {
            throw new IllegalStateException("turn_timeout");
        }
        long timeout = Math.min(Math.max(1, properties.getModelTimeoutMs()), left);
        ModelChatRequest request = new ModelChatRequest(List.copyOf(messages), tools, ModelProfile.FRONTLINE, "butler_agent",
                metadata(ctx, auditContent), properties.getModel(), properties.getMaxTokens(),
                answerOnly && !tools.isEmpty());
        ToolCallingModel current = currentModel();
        long started = System.nanoTime();
        CompletableFuture<ModelChatResponse> answer = CompletableFuture.supplyAsync(
                        // The call log names the guest and the message of the calling thread.
                        CallLogContext.propagate(() -> current.chat(request)), MODEL_CALLS)
                .orTimeout(timeout, TimeUnit.MILLISECONDS);
        try {
            ModelChatResponse response = answer.join();
            if (response == null) {
                throw new IllegalStateException("no_answer");
            }
            countCall(response.toolCall().isPresent() ? "tool_call" : "answer", started, response);
            return response;
        } catch (RuntimeException e) {
            countCall(timedOut(e) ? "timeout" : "error", started, null);
            throw e;
        } finally {
            answer.cancel(true);
        }
    }

    private void settle(AgentBudget.Reservation reservation, ModelChatResponse response) {
        if (budget != null) {
            budget.settle(reservation, response);
        }
    }

    private static boolean timedOut(Throwable e) {
        Throwable cause = e;
        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            if (cause instanceof TimeoutException || "turn_timeout".equals(cause.getMessage())) {
                return true;
            }
        }
        return false;
    }

    /** One model call: latency by outcome, and the tokens the provider reported. Never fails the turn. */
    private void countCall(String outcome, long started, ModelChatResponse response) {
        if (meters == null) {
            return;
        }
        try {
            String provider = providerLabel();
            String modelName = properties.getModel() == null || properties.getModel().isBlank() ? "default" : properties.getModel().trim();
            Timer.builder("astor.agent.model.calls")
                    .description("Agent model calls by provider, model and outcome")
                    .tags("provider", provider, "model", modelName, "outcome", outcome)
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(meters)
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            if (response != null && response.metadata().get("usage") instanceof Map<?, ?> usage) {
                tokens(provider, modelName, "in", usage.get("prompt_tokens"));
                tokens(provider, modelName, "out", usage.get("completion_tokens"));
            }
        } catch (RuntimeException e) {
            log.debug("Agent metrics were not recorded: {}", e.getClass().getSimpleName());
        }
    }

    private void tokens(String provider, String modelName, String direction, Object value) {
        if (value instanceof Number number && number.longValue() > 0) {
            Counter.builder("astor.agent.model.tokens")
                    .description("Tokens the provider reported for agent model calls")
                    .tags("provider", provider, "model", modelName, "direction", direction)
                    .register(meters)
                    .increment(number.doubleValue());
        }
    }

    private void countTurn(String outcome, String reason) {
        if (meters == null) {
            return;
        }
        try {
            meters.counter("astor.agent.turns", "provider", providerLabel(), "outcome", outcome, "reason", reason).increment();
        } catch (RuntimeException e) {
            log.debug("Agent metrics were not recorded: {}", e.getClass().getSimpleName());
        }
    }

    private void countTool(String tool, CapabilityResult result) {
        if (meters == null) {
            return;
        }
        try {
            meters.counter("astor.agent.tool.calls", "provider", providerLabel(), "tool", tool,
                    "outcome", result != null && result.ok() ? "ok" : "error").increment();
        } catch (RuntimeException e) {
            log.debug("Agent metrics were not recorded: {}", e.getClass().getSimpleName());
        }
    }

    private CapabilityResult invoke(ModelToolCall call, CapabilityContext ctx) {
        try {
            CapabilityResult result = registry.invoke(call.name(), ctx, call.arguments());
            return result == null ? CapabilityResult.error(CapabilityResult.UNAVAILABLE) : result;
        } catch (RuntimeException e) {
            log.warn("Agent tool failed: tool={}, reason={}", call.name(), e.getClass().getSimpleName());
            return CapabilityResult.error(CapabilityResult.UNAVAILABLE);
        }
    }

    private static CapabilityContext withKey(CapabilityContext ctx, int call) {
        if (ctx == null) {
            return null;
        }
        String turn = ctx.idempotencyKey();
        String key = turn == null || turn.isBlank() ? null : turn + ":" + call;
        return new CapabilityContext(ctx.caller(), ctx.chatId(), ctx.sessionId(), ctx.visitId(), key, ctx.guestName(),
                ctx.guestPhone(), ctx.agreement());
    }

    private static Map<String, Object> metadata(CapabilityContext ctx, boolean auditContent) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scenario", "BUTLER_AGENT");
        if (!auditContent) {
            metadata.put("auditContent", false);
        }
        if (ctx != null) {
            metadata.put("channel", CapabilityContext.BUTLER_AGENT.equals(ctx.caller()) ? "TELEGRAM" : "CONCIERGE");
            if (ctx.chatId() != null) {
                metadata.put("chatId", ctx.chatId());
            }
            if (ctx.sessionId() != null) {
                metadata.put("correlationId", ctx.sessionId());
            }
        }
        return metadata;
    }

    private String json(CapabilityResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", result.ok());
        if (result.error() != null) {
            body.put("error", result.error());
        }
        // The model needs no phone: the guest's number travels in the context, never through the prompt.
        body.put("facts", result.facts() == null ? Map.of() : CapabilityRegistry.masked(result.facts()));
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            return result.ok() ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"" + result.error() + "\"}";
        }
    }

    private static String reason(Throwable e) {
        Throwable cause = e;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName();
    }
}
