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
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The catalog of tools both agents call. An unknown name is {@code NOT_FOUND}, a failing tool {@code UNAVAILABLE}:
 * the caller always gets an answer. Every call is written to the visit ledger as a TOOL_CALL and a TOOL_RESULT, with
 * phone arguments masked. With {@code ASTOR_CAPABILITIES_ENABLED} off it lists nothing and touches nothing.
 */
@Component
public class CapabilityRegistry {

    private static final Logger log = LoggerFactory.getLogger(CapabilityRegistry.class);
    private static final Set<String> PHONE_KEYS = Set.of("guestPhone", "phone");

    private final Map<String, CapabilityTool> tools = new LinkedHashMap<>();
    private final LedgerWriter ledger;
    private final CapabilityProperties properties;

    public CapabilityRegistry(List<CapabilityTool> tools, LedgerWriter ledger, CapabilityProperties properties) {
        for (CapabilityTool tool : tools) {
            if (this.tools.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalStateException("Two capability tools are named " + tool.name());
            }
        }
        this.ledger = ledger;
        this.properties = properties;
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
        Map<String, Object> callPayload = new LinkedHashMap<>();
        callPayload.put("tool", name);
        callPayload.put("arguments", masked(arguments));
        record(ctx, TurnKind.TOOL_CALL, name, callPayload);

        CapabilityResult result = run(name, ctx, arguments);

        Map<String, Object> resultPayload = new LinkedHashMap<>();
        resultPayload.put("tool", name);
        resultPayload.put("ok", result.ok());
        resultPayload.put("error", result.error());
        resultPayload.put("facts", masked(result.facts() == null ? Map.of() : result.facts()));
        record(ctx, TurnKind.TOOL_RESULT, name, resultPayload);
        return result;
    }

    private CapabilityResult run(String name, CapabilityContext ctx, Map<String, Object> arguments) {
        CapabilityTool tool = name == null ? null : tools.get(name);
        if (tool == null) {
            return CapabilityResult.error(CapabilityResult.NOT_FOUND);
        }
        try {
            CapabilityResult result = tool.invoke(ctx, arguments);
            return result == null ? CapabilityResult.error(CapabilityResult.UNAVAILABLE) : result;
        } catch (CapabilityArguments.BadArgument e) {
            return CapabilityResult.error(CapabilityResult.BAD_ARGUMENT);
        } catch (RuntimeException e) {
            log.warn("Capability tool failed: tool={}, reason={}", name, e.getClass().getSimpleName());
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
