package museon_online.astor_butler.agent;

import museon_online.astor_butler.capability.CapabilityResult;

import java.util.List;

/**
 * What one agent turn came to: the model's answer ({@code text}, null on {@code fallback}), the results of the tools
 * it called in order, and how many calls it made. {@code uses} pairs each result with its tool name.
 */
public record AgentOutcome(String text, List<CapabilityResult> results, boolean fallback, int toolCalls, List<ToolUse> uses) {

    public record ToolUse(String tool, CapabilityResult result) {
    }

    public AgentOutcome {
        results = results == null ? List.of() : List.copyOf(results);
        uses = uses == null ? List.of() : List.copyOf(uses);
        if (fallback) {
            text = null;
        }
    }

    public AgentOutcome(String text, List<CapabilityResult> results, boolean fallback, int toolCalls) {
        this(text, results, fallback, toolCalls, List.of());
    }

    public static AgentOutcome fallback(List<ToolUse> uses, int toolCalls) {
        List<ToolUse> made = uses == null ? List.of() : uses;
        return new AgentOutcome(null, made.stream().map(ToolUse::result).toList(), true, toolCalls, made);
    }
}
