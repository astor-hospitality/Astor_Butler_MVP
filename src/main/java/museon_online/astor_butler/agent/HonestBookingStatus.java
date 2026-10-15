package museon_online.astor_butler.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Butler never tells the guest a booking is confirmed while it waits for the hostess (live 15.10: the tool said
 * AWAITING_HOSTESS, the model wrote «Ваш стол у окна на 13:00 забронирован»). When the turn's last successful
 * reservation_create is not CONFIRMED and the reply claims confirmation, the reply is replaced by a fixed honest text
 * built from the tool's facts.
 */
final class HonestBookingStatus {

    private static final Logger log = LoggerFactory.getLogger(HonestBookingStatus.class);
    private static final List<String> CLAIMS = List.of("забронирован", "подтвержд", "бронь оформлена", "ждём вас", "ждем вас",
            "ожидаем вашего", "ожидаем вас");
    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd.MM");

    private HonestBookingStatus() {
    }

    static String guard(String text, List<AgentOutcome.ToolUse> uses) {
        Map<String, Object> booking = lastBooking(uses);
        if (text == null || booking == null || "CONFIRMED".equalsIgnoreCase(value(booking.get("status")))
                || !claimsConfirmation(text)) {
            return text;
        }
        log.warn("Butler reply claimed an unconfirmed booking: status={}, replaced with the honest text", booking.get("status"));
        return honest(booking);
    }

    private static Map<String, Object> lastBooking(List<AgentOutcome.ToolUse> uses) {
        if (uses == null) {
            return null;
        }
        for (int i = uses.size() - 1; i >= 0; i--) {
            AgentOutcome.ToolUse use = uses.get(i);
            if ("reservation_create".equals(use.tool()) && use.result() != null && use.result().ok()) {
                return use.result().facts() == null ? Map.of() : use.result().facts();
            }
        }
        return null;
    }

    private static boolean claimsConfirmation(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return CLAIMS.stream().anyMatch(lower::contains);
    }

    private static String honest(Map<String, Object> facts) {
        String table = value(facts.get("table"));
        String date = date(value(facts.get("date")));
        String time = value(facts.get("time"));
        List<String> parts = new ArrayList<>();
        if (table != null) {
            parts.add(table);
        }
        String when = date != null && time != null ? date + " в " + time : date != null ? date : time;
        if (when != null) {
            parts.add(when);
        }
        String what = parts.isEmpty() ? "Заявку" : "Заявку на " + String.join(", ", parts);
        return what + " передал хостес. Как только она подтвердит — сразу сообщу.";
    }

    private static String date(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw).format(DAY_MONTH);
        } catch (DateTimeParseException e) {
            return raw;
        }
    }

    private static String value(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
