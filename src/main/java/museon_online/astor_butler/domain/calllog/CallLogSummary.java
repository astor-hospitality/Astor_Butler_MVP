package museon_online.astor_butler.domain.calllog;

import java.math.BigDecimal;

/** Totals of the call log over a period, for the accelerator report. */
public record CallLogSummary(
        long llmCalls,
        long toolCalls,
        long guestMessages,
        long targetActions,
        long distinctUsers,
        long tokensIn,
        long tokensOut,
        BigDecimal costRub
) {
}
