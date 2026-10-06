package museon_online.astor_butler.integration.saby;

import museon_online.astor_butler.domain.booking.TableReservationCommand;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds the {@code retail/order/create} body for a Presto table booking.
 * Without a Saby table id the table is left to the Saby administrator ({@code woTable=true}); with one, the hall is
 * required by the Saby contract and {@code woTable} is false.
 */
final class SabyOrderPayload {

    static final String BUTLER_MARKER = "Astor Butler #";
    /** Saby's own limit is not documented; this keeps the hostess line readable and the marker always present. */
    static final int MAX_GUEST_COMMENT = 300;

    private SabyOrderPayload() {
    }

    static Map<String, Object> create(
            TableReservationCommand command,
            String idempotencyKey,
            SabyReservationProperties properties,
            String localStart
    ) {
        return create(command, idempotencyKey, properties, localStart, null);
    }

    /**
     * @param sabyTableId the table id from {@code retail/hall/list} when Butler chose the table; null leaves the choice
     *                    to the Saby administrator
     */
    static Map<String, Object> create(
            TableReservationCommand command,
            String idempotencyKey,
            SabyReservationProperties properties,
            String localStart,
            Long sabyTableId
    ) {
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("name", command.guestName().trim());
        customer.put("phone", phone(command.guestPhone())
                .orElseThrow(() -> new IllegalArgumentException("Guest phone is not a Russian number")));

        Map<String, Object> booking = new LinkedHashMap<>();
        booking.put("visitors", command.partySize());
        boolean hallKnown = !isBlank(properties.getHallId());
        if (sabyTableId != null && !hallKnown) {
            throw new IllegalArgumentException("Saby needs the hall id when Butler names the table (SABY_HALL_ID)");
        }
        if (hallKnown) {
            booking.put("hall", idValue(properties.getHallId()));
        }
        if (sabyTableId != null) {
            booking.put("table", sabyTableId);
            booking.put("woTable", false);
        } else {
            booking.put("woTable", true);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("product", "restaurant");
        body.put("pointId", idValue(properties.getPointId()));
        body.put("datetime", localStart);
        body.put("comment", comment(command, idempotencyKey));
        body.put("customer", customer);
        body.put("booking", booking);
        return body;
    }

    /**
     * The phone the way Saby examples show it: digits only, {@code 7} first, eleven in all. {@code +7 (912) 345-67-89}
     * and {@code 8 912 345 67 89} both become {@code 79123456789}; anything else is left to the hostess.
     */
    static Optional<String> phone(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() == 11 && (digits.startsWith("7") || digits.startsWith("8"))) {
            return Optional.of("7" + digits.substring(1));
        }
        if (digits.length() == 10 && digits.startsWith("9")) {
            return Optional.of("7" + digits);
        }
        return Optional.empty();
    }

    private static String comment(TableReservationCommand command, String idempotencyKey) {
        List<String> parts = new ArrayList<>();
        if (!isBlank(command.guestComment())) {
            parts.add(cut(command.guestComment().trim()));
        }
        if (!isBlank(command.preferredZone())) {
            parts.add("Зона: " + command.preferredZone().trim());
        }
        if (!isBlank(command.seatingPreference())) {
            parts.add("Пожелание: " + cut(command.seatingPreference().trim()));
        }
        if (!isBlank(command.tableCode())) {
            parts.add("Стол в Butler: " + command.tableCode().trim());
        }
        parts.add(BUTLER_MARKER + idempotencyKey);
        return String.join(" · ", parts);
    }

    private static String cut(String text) {
        return text.length() <= MAX_GUEST_COMMENT ? text : text.substring(0, MAX_GUEST_COMMENT - 1).stripTrailing() + "…";
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
