package museon_online.astor_butler.domain.calllog;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of the unified call log: an LLM call, a tool (Saby) call, a guest message or a target action.
 * Carries no text, phone or raw id: {@code pseudoUserId} is already pseudonymized by {@link CallLogWriter}.
 */
public record CallLogEntry(
        Instant ts,
        String source,
        String pseudoUserId,
        String correlationId,
        String callType,
        String name,
        String result,
        String errorCode,
        Integer tokensIn,
        Integer tokensOut,
        BigDecimal costRub,
        Integer latencyMs
) {
    public static final String TYPE_LLM = "llm";
    public static final String TYPE_TOOL = "tool";
    public static final String TYPE_GUEST_MESSAGE = "guest_message";
    public static final String TYPE_TARGET_ACTION = "target_action";
}
