package museon_online.astor_butler.domain.booking.external;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Which Butler tables the restaurant's own system offers for one time and party size.
 *
 * <p>Read from an {@link ExternalAvailabilityResult}: statuses {@code AVAILABLE} and
 * {@code NO_TABLES_AVAILABLE} are an answer, and {@code metadata.candidates[].tableName} names the
 * free tables that fit the party. Anything else (provider off, timeout, an incomplete list, or
 * table names that match no Butler table) is not an answer and blocks nothing, so the local
 * hostess flow keeps working.
 *
 * @param authoritative the provider answered and its table names line up with Butler tables
 * @param freeTableKeys {@link ExternalTableCodes#key} of every table the provider offers
 * @param reason        why the answer is or is not authoritative, for the log
 */
public record ExternalTableOccupancy(boolean authoritative, Set<String> freeTableKeys, String reason) {

    public static final String STATUS_AVAILABLE = "AVAILABLE";
    public static final String STATUS_NO_TABLES_AVAILABLE = "NO_TABLES_AVAILABLE";
    public static final String REASON_OK = "OK";
    public static final String REASON_INCOMPLETE_TABLE_LIST = "INCOMPLETE_TABLE_LIST";
    public static final String REASON_TABLE_NAMES_DO_NOT_MATCH = "TABLE_NAMES_DO_NOT_MATCH";

    static final String METADATA_CANDIDATES = "candidates";
    static final String METADATA_HAS_MORE = "hasMore";
    static final String CANDIDATE_TABLE_NAME = "tableName";

    public static ExternalTableOccupancy notAuthoritative(String reason) {
        return new ExternalTableOccupancy(false, Set.of(), reason);
    }

    /**
     * @param localTableCodes Butler table codes of the venue; asked for only when the provider
     *                        offered tables, to check that its table names mean Butler tables
     */
    public static ExternalTableOccupancy from(
            ExternalAvailabilityResult result,
            Supplier<? extends Collection<String>> localTableCodes
    ) {
        if (!result.providerConfigured()) {
            return notAuthoritative(result.status());
        }
        if (!STATUS_AVAILABLE.equals(result.status()) && !STATUS_NO_TABLES_AVAILABLE.equals(result.status())) {
            return notAuthoritative(result.status());
        }
        Map<String, Object> metadata = result.metadata() == null ? Map.of() : result.metadata();
        if (Boolean.TRUE.equals(metadata.get(METADATA_HAS_MORE))) {
            // A table missing from a partial list is not known to be busy.
            return notAuthoritative(REASON_INCOMPLETE_TABLE_LIST);
        }

        Set<String> free = new HashSet<>();
        if (metadata.get(METADATA_CANDIDATES) instanceof List<?> candidates) {
            for (Object candidate : candidates) {
                if (candidate instanceof Map<?, ?> table && table.get(CANDIDATE_TABLE_NAME) != null) {
                    String key = ExternalTableCodes.key(table.get(CANDIDATE_TABLE_NAME).toString());
                    if (!key.isEmpty()) {
                        free.add(key);
                    }
                }
            }
        }
        if (!free.isEmpty()) {
            boolean anyKnown = localTableCodes.get().stream().map(ExternalTableCodes::key).anyMatch(free::contains);
            if (!anyKnown) {
                return notAuthoritative(REASON_TABLE_NAMES_DO_NOT_MATCH);
            }
        }
        return new ExternalTableOccupancy(true, Set.copyOf(free), REASON_OK);
    }

    /** The restaurant's system answered and does not offer this table for the time and party size. */
    public boolean blocks(String tableCode) {
        return authoritative && !freeTableKeys.contains(ExternalTableCodes.key(tableCode));
    }
}
