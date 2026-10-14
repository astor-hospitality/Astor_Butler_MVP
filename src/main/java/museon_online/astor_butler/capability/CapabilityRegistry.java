package museon_online.astor_butler.capability;

import museon_online.astor_butler.domain.ledger.LedgerWriter;
import museon_online.astor_butler.domain.ledger.PhoneMask;
import museon_online.astor_butler.domain.ledger.TurnChannel;
import museon_online.astor_butler.domain.ledger.TurnKind;
import museon_online.astor_butler.domain.ledger.TurnSide;
import museon_online.astor_butler.domain.ledger.VisitTurnDraft;
import museon_online.astor_butler.model.ModelTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The catalog of tools both agents call. An unknown name is {@code NOT_FOUND}, a failing tool {@code UNAVAILABLE}:
 * the caller always gets an answer. Every call is written to the visit ledger as a TOOL_CALL and a TOOL_RESULT, with
 * phone arguments masked. With {@code ASTOR_CAPABILITIES_ENABLED} off it lists nothing and touches nothing.
 *
 * <p>A commitment tool (a booking, an order) runs only with an idempotency key, at most once per key: the result,
 * a failure included, is saved and a repeated key gets it back without running the tool ({@code replayed = true} in
 * the ledger). The key is claimed before the tool runs, so two concurrent calls never both run it: the second is
 * refused with {@code reason = in_progress}. A missing key, or a key another tool used, is a {@code BAD_ARGUMENT};
 * without the store no commitment runs.
 */
@Component
public class CapabilityRegistry {

    private static final Logger log = LoggerFactory.getLogger(CapabilityRegistry.class);
    private static final Set<String> PHONE_KEYS = Set.of("guestPhone", "phone");

    private final Map<String, CapabilityTool> tools = new LinkedHashMap<>();
    private final LedgerWriter ledger;
    private final CapabilityProperties properties;
    private final IdempotencyStore idempotency;

    /** Without the idempotency store: read tools only, every commitment answers {@code UNAVAILABLE}. */
    public CapabilityRegistry(List<CapabilityTool> tools, LedgerWriter ledger, CapabilityProperties properties) {
        this(tools, ledger, properties, null);
    }

    @Autowired
    public CapabilityRegistry(List<CapabilityTool> tools, LedgerWriter ledger, CapabilityProperties properties,
                              IdempotencyStore idempotency) {
        for (CapabilityTool tool : tools) {
            if (this.tools.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalStateException("Two capability tools are named " + tool.name());
            }
        }
        this.ledger = ledger;
        this.properties = properties;
        this.idempotency = idempotency;
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    /** The tools on offer; empty while the API is off. */
    public List<CapabilityTool> tools() {
        return enabled() ? List.copyOf(tools.values()) : List.of();
    }

    public List<ModelTool> asModelTools() {
        return tools().stream().map(tool -> new ModelTool(tool.name(), tool.description(), tool.parameters())).toList();
    }

    public CapabilityResult invoke(String name, CapabilityContext ctx, Map<String, Object> args) {
        if (!enabled()) {
            return CapabilityResult.error(CapabilityResult.UNAVAILABLE);
        }
        Map<String, Object> arguments = args == null ? Map.of() : args;
        CapabilityTool tool = name == null ? null : tools.get(name);
        Decision decision = tool != null && tool.commitment() ? decide(tool, ctx) : Decision.RUN;

        Map<String, Object> callPayload = new LinkedHashMap<>();
        callPayload.put("tool", name);
        callPayload.put("arguments", masked(arguments));
        if (decision.replayed()) {
            callPayload.put("replayed", true);
        }
        record(ctx, TurnKind.TOOL_CALL, name, callPayload);

        CapabilityResult result;
        if (decision.result() != null) {
            result = decision.result();
        } else if (decision.key() != null) {
            result = commit(tool, decision.key(), ctx, arguments);
        } else {
            result = run(tool, ctx, arguments);
        }

        Map<String, Object> resultPayload = new LinkedHashMap<>();
        resultPayload.put("tool", name);
        resultPayload.put("ok", result.ok());
        resultPayload.put("error", result.error());
        resultPayload.put("facts", masked(result.facts() == null ? Map.of() : result.facts()));
        if (decision.replayed()) {
            resultPayload.put("replayed", true);
        }
        record(ctx, TurnKind.TOOL_RESULT, name, resultPayload);
        return result;
    }

    /**
     * What to do with a call: answer at once ({@code result}; a replay when {@code replayed}), run a commitment whose
     * key this call has claimed ({@code key}), or just run the tool.
     */
    private record Decision(CapabilityResult result, boolean replayed, String key) {
        static final Decision RUN = new Decision(null, false, null);

        static Decision answer(CapabilityResult result) {
            return new Decision(result, false, null);
        }
    }

    /** Claims the key of a commitment before it runs; a key already claimed gives its result, never a second run. */
    private Decision decide(CapabilityTool tool, CapabilityContext ctx) {
        String key = ctx == null || ctx.idempotencyKey() == null || ctx.idempotencyKey().isBlank() ? null : ctx.idempotencyKey().trim();
        if (key == null || key.length() > IdempotencyStore.MAX_KEY) {
            return Decision.answer(CapabilityResult.error(CapabilityResult.BAD_ARGUMENT));
        }
        if (idempotency == null) {
            return Decision.answer(CapabilityResult.error(CapabilityResult.UNAVAILABLE));
        }
        try {
            Optional<IdempotencyStore.Call> known = idempotency.lookup(key);
            if (known.isEmpty()) {
                try {
                    idempotency.claim(key, tool.name());
                    return new Decision(null, false, key);
                } catch (DataIntegrityViolationException e) {
                    // Another call claimed the key between the lookup and the claim.
                    known = idempotency.lookup(key);
                }
            }
            if (known.isEmpty()) {
                return Decision.answer(CapabilityResult.error(CapabilityResult.UNAVAILABLE));
            }
            IdempotencyStore.Call call = known.get();
            if (!tool.name().equals(call.tool())) {
                return Decision.answer(withReason(CapabilityResult.BAD_ARGUMENT, "key_used_by_another_tool"));
            }
            if (call.inProgress()) {
                return Decision.answer(withReason(CapabilityResult.BAD_ARGUMENT, "in_progress"));
            }
            return new Decision(call.result(), true, null);
        } catch (RuntimeException e) {
            log.warn("Idempotency key not claimed: tool={}, reason={}", tool.name(), e.getClass().getSimpleName());
            return Decision.answer(CapabilityResult.error(CapabilityResult.UNAVAILABLE));
        }
    }

    /**
     * Runs a commitment whose key this call claimed and writes what came of it. The result handed back is the stored
     * one, so the first answer and every replay are the same.
     */
    private CapabilityResult commit(CapabilityTool tool, String key, CapabilityContext ctx, Map<String, Object> arguments) {
        CapabilityResult result = run(tool, ctx, arguments);
        try {
            idempotency.complete(key, result);
            return idempotency.find(key).orElse(result);
        } catch (RuntimeException e) {
            log.warn("Commitment result not saved: tool={}, reason={}", tool.name(), e.getClass().getSimpleName());
            return result;
        }
    }

    private static CapabilityResult withReason(String error, String reason) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("reason", reason);
        return new CapabilityResult(false, facts, error);
    }

    private CapabilityResult run(CapabilityTool tool, CapabilityContext ctx, Map<String, Object> arguments) {
        if (tool == null) {
            return CapabilityResult.error(CapabilityResult.NOT_FOUND);
        }
        try {
            CapabilityResult result = tool.invoke(ctx, arguments);
            return result == null ? CapabilityResult.error(CapabilityResult.UNAVAILABLE) : result;
        } catch (CapabilityArguments.BadArgument e) {
            return CapabilityResult.error(CapabilityResult.BAD_ARGUMENT);
        } catch (RuntimeException e) {
            log.warn("Capability tool failed: tool={}, reason={}", tool.name(), e.getClass().getSimpleName());
            return CapabilityResult.error(CapabilityResult.UNAVAILABLE);
        }
    }

    private void record(CapabilityContext ctx, TurnKind kind, String name, Map<String, Object> payload) {
        TurnSide side = ctx != null && ctx.butlerAgent() ? TurnSide.BUTLER : TurnSide.CONCIERGE;
        try {
            ledger.write(new VisitTurnDraft(ctx == null ? null : ctx.visitId(), ctx == null ? null : ctx.sessionId(),
                    side, TurnChannel.INTERNAL, kind, name, payload, null));
        } catch (RuntimeException e) {
            log.warn("Capability call not written to the ledger: tool={}, reason={}", name, e.getClass().getSimpleName());
        }
    }

    /** A copy with phone keys masked (dropped when the value is not a readable phone) and text phones masked. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> masked(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (PHONE_KEYS.contains(key)) {
                String masked = value == null ? null : PhoneMask.mask(value.toString());
                if (masked != null && !masked.equals(value.toString())) {
                    copy.put(key, masked);
                }
                return;
            }
            copy.put(key, maskedValue(value));
        });
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Object maskedValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return masked((Map<String, Object>) map);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(maskedValue(item)));
            return copy;
        }
        if (value instanceof String text) {
            return PhoneMask.mask(text);
        }
        return value;
    }
}
