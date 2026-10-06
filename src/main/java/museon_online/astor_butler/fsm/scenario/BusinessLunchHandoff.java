package museon_online.astor_butler.fsm.scenario;

import museon_online.astor_butler.service.message.IncomingMessage;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the Concierge passes along when it sends a guest for a business lunch. Two ways in, one meaning:
 * <ul>
 * <li>a Telegram link {@code https://t.me/<bot>?start=lunch_aeris_s2_p2_d20261007_t1300_r7f3a}, which reaches the bot as
 * the text {@code /start lunch_aeris_s2_p2_d20261007_t1300_r7f3a}: the venue first, then any of set number, party size,
 * day, time and the Concierge request id;</li>
 * <li>a gateway message with {@code payload.concierge = {scenario: "BUSINESS_LUNCH", venueCode, setCode, partySize, date, time, requestId}}.</li>
 * </ul>
 * Only the scenario marker is required. Anything that does not parse is left out and the dialogue asks for it.
 */
record BusinessLunchHandoff(
        String venueCode,
        String setRef,
        Integer partySize,
        LocalDate date,
        LocalTime time,
        String requestId
) {
    static final String SOURCE = "CONCIERGE";

    private static final Pattern START = Pattern.compile("^/start(?:@\\w+)?\\s+lunch(?:[_-]([A-Za-z0-9_-]*))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIELD = Pattern.compile("^([spdtr])([A-Za-z0-9-]+)$", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter LINK_DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter LINK_TIME = DateTimeFormatter.ofPattern("HHmm");

    static Optional<BusinessLunchHandoff> from(IncomingMessage incoming, String text) {
        Optional<BusinessLunchHandoff> fromPayload = fromPayload(incoming);
        return fromPayload.isPresent() ? fromPayload : fromStartLink(text);
    }

    static Optional<BusinessLunchHandoff> fromStartLink(String text) {
        Matcher matcher = START.matcher(text == null ? "" : text.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String venue = null;
        String set = null;
        Integer party = null;
        LocalDate day = null;
        LocalTime time = null;
        String request = null;
        String[] parts = matcher.group(1) == null ? new String[0] : matcher.group(1).split("_");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isBlank()) {
                continue;
            }
            if (i == 0) {
                venue = parts[i];
                continue;
            }
            Matcher field = FIELD.matcher(parts[i]);
            if (!field.matches()) {
                continue;
            }
            String value = field.group(2);
            switch (field.group(1).toLowerCase()) {
                case "s" -> set = value;
                case "p" -> party = number(value);
                case "d" -> day = parse(value, LINK_DAY, LocalDate::from);
                case "t" -> time = parse(value, LINK_TIME, LocalTime::from);
                default -> request = value;
            }
        }
        return Optional.of(new BusinessLunchHandoff(venue, set, party, day, time, request));
    }

    private static Optional<BusinessLunchHandoff> fromPayload(IncomingMessage incoming) {
        if (incoming == null || incoming.payload() == null || !(incoming.payload().get("concierge") instanceof Map<?, ?> concierge)) {
            return Optional.empty();
        }
        if (!"BUSINESS_LUNCH".equalsIgnoreCase(text(concierge.get("scenario")))) {
            return Optional.empty();
        }
        return Optional.of(new BusinessLunchHandoff(
                blankToNull(text(concierge.get("venueCode"))),
                blankToNull(text(concierge.get("setCode"))),
                number(text(concierge.get("partySize"))),
                parse(text(concierge.get("date")), DateTimeFormatter.ISO_LOCAL_DATE, LocalDate::from),
                parse(text(concierge.get("time")), DateTimeFormatter.ofPattern("H:mm"), LocalTime::from),
                blankToNull(text(concierge.get("requestId")))
        ));
    }

    private static Integer number(String value) {
        try {
            return value == null || value.isBlank() ? null : Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static <T> T parse(String value, DateTimeFormatter formatter, java.time.temporal.TemporalQuery<T> query) {
        try {
            return value == null || value.isBlank() ? null : formatter.parse(value.trim(), query);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
