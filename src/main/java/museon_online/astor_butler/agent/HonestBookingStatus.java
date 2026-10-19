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
import java.util.regex.Pattern;

/**
 * The Butler never tells the guest a booking is confirmed while it waits for the hostess (live 15.10: the tool said
 * AWAITING_HOSTESS, the model wrote «Ваш стол у окна на 13:00 забронирован»). When the turn's last successful
 * reservation_create is not CONFIRMED and the reply claims confirmation, the reply is replaced by a fixed honest text
 * built from the tool's facts.
 *
 * <p>The same for a lunch the kitchen has not got (live test 16.10: Saby answered PROVIDER_RESULT_UNKNOWN and the guest
 * read «заказ оформлен»): when the turn's last placed lunch says {@code kitchenConfirmed=false}, a sentence that claims the
 * order is made or with the kitchen becomes «Заказ передал официанту, он внесёт его в кассу.», and that sentence is there
 * even when the model said nothing of it.
 */
final class HonestBookingStatus {

    private static final Logger log = LoggerFactory.getLogger(HonestBookingStatus.class);
    private static final List<String> CLAIMS = List.of("забронирован", "подтвержд", "бронь оформлена", "ждём вас", "ждем вас",
            "ожидаем вашего", "ожидаем вас");
    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd.MM");
    /** Says a request exists: made, passed on to the hostess or waiting for her; not under «не». Text is lower case, ё as е. */
    private static final Pattern CREATED = Pattern.compile("(?<!не )(забронирован|оформлен|бронь создана|заявка создана"
            + "|заявка передана|заявку передал|передал хостес|передала хостес|ждет подтверждения|ждем вас|ожидаем вас)");

    /** A lunch claimed as made or with the kitchen; not under «не». Text is lower case, ё as е. */
    private static final Pattern LUNCH_CLAIM = Pattern.compile("(?<!не )(оформлен|оформил|принят|приняли|заказ подтвержден"
            + "|(передан|передал|отправлен|отправил)\\S* (на кухню|в кухню|повар|в saby|в кассу)|кухня (уже )?(готовит|получила)"
            + "|готовим|уже готовят|пробит)");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?…])\\s+");

    private HonestBookingStatus() {
    }

    static String guard(String text, List<AgentOutcome.ToolUse> uses) {
        return lunch(booking(text, uses), uses);
    }

    private static String booking(String text, List<AgentOutcome.ToolUse> uses) {
        Map<String, Object> booking = lastBooking(uses);
        if (text == null || booking == null || "CONFIRMED".equalsIgnoreCase(value(booking.get("status")))
                || !claimsConfirmation(text)) {
            return text;
        }
        log.warn("Butler reply claimed an unconfirmed booking: status={}, replaced with the honest text", booking.get("status"));
        return honest(booking);
    }

    /** The last lunch placed this turn that the kitchen's system did not take ({@code kitchenConfirmed=false}); else null. */
    static Map<String, Object> unconfirmedLunch(List<AgentOutcome.ToolUse> uses) {
        if (uses == null) {
            return null;
        }
        for (int i = uses.size() - 1; i >= 0; i--) {
            AgentOutcome.ToolUse use = uses.get(i);
            if ("lunch_order_place".equals(use.tool()) && use.result() != null && use.result().ok()) {
                Map<String, Object> facts = use.result().facts();
                return facts != null && Boolean.FALSE.equals(facts.get("kitchenConfirmed")) ? facts : null;
            }
        }
        return null;
    }

    private static String lunch(String text, List<AgentOutcome.ToolUse> uses) {
        Map<String, Object> lunch = unconfirmedLunch(uses);
        if (text == null || lunch == null) {
            return text;
        }
        String handed = handed(lunch);
        List<String> kept = new ArrayList<>();
        boolean replaced = false;
        for (String sentence : SENTENCE_END.split(text.trim())) {
            String lower = sentence.toLowerCase(Locale.ROOT).replace('ё', 'е');
            if (LUNCH_CLAIM.matcher(lower).find() && (lower.contains("заказ") || lower.contains("ланч")
                    || lower.contains("блюд") || lower.contains("кухн"))) {
                if (!replaced) {
                    kept.add(handed);
                    replaced = true;
                }
            } else {
                kept.add(sentence);
            }
        }
        if (replaced) {
            log.warn("Butler reply claimed a lunch the kitchen has not got: sabyStatus={}, the claim was replaced",
                    lunch.get("sabyStatus"));
        } else if (!text.toLowerCase(Locale.ROOT).contains("официант")) {
            kept.add(handed);
        }
        return String.join(" ", kept);
    }

    private static String handed(Map<String, Object> lunch) {
        Object total = lunch.get("totalRub");
        return total instanceof Number sum
                ? "Заказ на " + sum + " ₽ передал официанту, он внесёт его в кассу."
                : "Заказ передал официанту, он внесёт его в кассу.";
    }

    /**
     * Whether the reply claims a booking that does not exist (prod run 16.10: «Бронь … в среду в 20:30 оформлена.», and
     * no row was created): the guest has no visit yet, nothing was booked or changed this turn, and the reply says a
     * request was made, passed on or is waiting for the hostess. A claim under «не» («ещё не оформлена») is no claim;
     * asking for the yes («подтверждаете?») is none either. The caller lets such a turn fall back.
     */
    static boolean unbacked(String text, List<AgentOutcome.ToolUse> uses, Long visitId) {
        if (text == null || visitId != null || committed(uses)) {
            return false;
        }
        if (!CREATED.matcher(text.toLowerCase(Locale.ROOT).replace('ё', 'е')).find()) {
            return false;
        }
        log.warn("Butler reply claimed a booking no tool made this turn; the turn falls back");
        return true;
    }

    private static boolean committed(List<AgentOutcome.ToolUse> uses) {
        return uses != null && uses.stream().anyMatch(use -> use.result() != null && use.result().ok()
                && ("reservation_create".equals(use.tool()) || "reservation_change".equals(use.tool())
                || "lunch_order_place".equals(use.tool())));
    }

    private static Map<String, Object> lastBooking(List<AgentOutcome.ToolUse> uses) {
        if (uses == null) {
            return null;
        }
        for (int i = uses.size() - 1; i >= 0; i--) {
            AgentOutcome.ToolUse use = uses.get(i);
            // A lunch order that booked its own table carries the same status (reservationCreated).
            boolean booked = "reservation_create".equals(use.tool()) || "lunch_order_place".equals(use.tool())
                    && use.result() != null && use.result().facts() != null
                    && Boolean.TRUE.equals(use.result().facts().get("reservationCreated"));
            if (booked && use.result() != null && use.result().ok()) {
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
