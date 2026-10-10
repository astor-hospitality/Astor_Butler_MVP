package museon_online.astor_butler.max.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * One answer of {@code GET /updates}: the raw update objects (kept as JSON so an unknown update type or field never
 * breaks polling) and the marker to send with the next poll.
 */
public record MaxUpdatesPage(List<JsonNode> updates, Long marker) {
    public MaxUpdatesPage {
        updates = updates == null ? List.of() : List.copyOf(updates);
    }
}
