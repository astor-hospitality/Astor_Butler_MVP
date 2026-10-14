package museon_online.astor_butler.capability;

import java.util.Map;

/** What a tool found: facts on success, an error code otherwise. Errors are facts too, not HTTP failures. */
public record CapabilityResult(boolean ok, Map<String, Object> facts, String error) {

    public static final String BAD_ARGUMENT = "BAD_ARGUMENT";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String NOT_ALLOWED = "NOT_ALLOWED";
    public static final String UNAVAILABLE = "UNAVAILABLE";

    public static CapabilityResult ok(Map<String, Object> facts) {
        return new CapabilityResult(true, facts == null ? Map.of() : facts, null);
    }

    public static CapabilityResult error(String code) {
        return new CapabilityResult(false, Map.of(), code);
    }
}
