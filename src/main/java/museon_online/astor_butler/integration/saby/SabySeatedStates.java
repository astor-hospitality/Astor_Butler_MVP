package museon_online.astor_butler.integration.saby;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which Saby codes mean «the guest is at the table» (#151). The documentation names no such state — the green tick
 * in Presto Waiter is recorded on the first live test — so the codes come from the environment:
 * {@code ASTOR_SABY_SEATED_STATES=50/1002,60}, a comma list of {@code state} or {@code state/productState}. An entry
 * without a product state matches that {@code state} whatever the product state says; an entry with one matches only
 * the pair. Empty (the default) means the mapping is off and nothing is ever SEATED from Saby.
 */
public record SabySeatedStates(List<Code> codes) {

    public static final SabySeatedStates NONE = new SabySeatedStates(List.of());

    /** One configured code: {@code state} alone, or {@code state/productState}. */
    public record Code(int state, Integer productState) {

        boolean matches(Integer state, Integer productState) {
            if (state == null || state != this.state) {
                return false;
            }
            return this.productState == null || this.productState.equals(productState);
        }

        @Override
        public String toString() {
            return productState == null ? String.valueOf(state) : state + "/" + productState;
        }
    }

    public SabySeatedStates {
        codes = codes == null ? List.of() : List.copyOf(codes);
    }

    /** Entries that are not numbers are skipped, so one typo in the variable does not switch the whole adapter off. */
    public static SabySeatedStates parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        List<Code> codes = new ArrayList<>();
        for (String entry : raw.split(",")) {
            String trimmed = entry.trim().toLowerCase(Locale.ROOT);
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("/", 2);
            try {
                int state = Integer.parseInt(parts[0].trim());
                Integer productState = parts.length < 2 || parts[1].isBlank() ? null : Integer.valueOf(parts[1].trim());
                codes.add(new Code(state, productState));
            } catch (NumberFormatException e) {
                // Not a code: left out, the rest of the list still counts.
            }
        }
        return codes.isEmpty() ? NONE : new SabySeatedStates(codes);
    }

    public boolean empty() {
        return codes.isEmpty();
    }

    public boolean matches(Integer state, Integer productState) {
        for (Code code : codes) {
            if (code.matches(state, productState)) {
                return true;
            }
        }
        return false;
    }
}
