package museon_online.astor_butler.integration.saby;

import museon_online.astor_butler.domain.booking.TableReservationCommand;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@code retail/order/create} body for a Presto table booking.
 * The table is left to the Saby administrator ({@code woTable=true}) until Butler tables are mapped to Saby ids.
 */
final class SabyOrderPayload {

    static final String BUTLER_MARKER = "Astor Butler #";

    private SabyOrderPayload() {
    }

    static Map<String, Object> create(
            TableReservationCommand command,
            String idempotencyKey,
            SabyReservationProperties properties,
            String localStart
    ) {
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("name", command.guestName().trim());
        customer.put("phone", command.guestPhone().trim());

        Map<String, Object> booking = new LinkedHashMap<>();
        booking.put("visitors", command.partySize());
        if (!isBlank(properties.getHallId())) {
            booking.put("hall", idValue(properties.getHallId()));
        }
        booking.put("woTable", true);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("product", "restaurant");
        body.put("pointId", idValue(properties.getPointId()));
        body.put("datetime", localStart);
        body.put("comment", comment(command, idempotencyKey));
        body.put("customer", customer);
        body.put("booking", booking);
        return body;
    }

    private static String comment(TableReservationCommand command, String idempotencyKey) {
        List<String> parts = new ArrayList<>();
        if (!isBlank(command.guestComment())) {
            parts.add(command.guestComment().trim());
        }
        if (!isBlank(command.preferredZone())) {
            parts.add("Зона: " + command.preferredZone().trim());
        }
        if (!isBlank(command.seatingPreference())) {
            parts.add("Пожелание: " + command.seatingPreference().trim());
        }
        if (!isBlank(command.tableCode())) {
            parts.add("Стол в Butler: " + command.tableCode().trim());
        }
        parts.add(BUTLER_MARKER + idempotencyKey);
        return String.join(" · ", parts);
    }

    /** Saby examples send ids as numbers; keep non-numeric ids as strings. */
    private static Object idValue(String value) {
        String trimmed = value.trim();
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException exception) {
            return trimmed;
        }
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
