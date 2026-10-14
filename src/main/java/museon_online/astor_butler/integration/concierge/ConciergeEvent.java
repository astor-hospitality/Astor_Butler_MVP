package museon_online.astor_butler.integration.concierge;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the Butler tells the Concierge about a booking made in the Concierge's chat. {@code butlerText} is the Butler's own
 * short line for the guest; {@code facts} carry the values it was built from (time, table, total, link...).
 *
 * @param type CONFIRMED | REJECTED | EXPIRED | MOVED | MORNING | SEATED | BILL_READY | STAFF_COMING | CANCELLED | REVIEW;
 *             STAFF_COMING is part of the contract but not sent (the capability tool's result already carries the line)
 */
public record ConciergeEvent(String type, long reservationId, Long visitId, String sessionId, String butlerText,
                             Map<String, Object> facts) {

    public static final String CONFIRMED = "CONFIRMED";
    public static final String REJECTED = "REJECTED";
    public static final String EXPIRED = "EXPIRED";
    public static final String MOVED = "MOVED";
    public static final String MORNING = "MORNING";
    public static final String SEATED = "SEATED";
    public static final String BILL_READY = "BILL_READY";
    public static final String STAFF_COMING = "STAFF_COMING";
    public static final String CANCELLED = "CANCELLED";
    /** After the bill: one digit from 1 to 5, once per bill. */
    public static final String REVIEW = "REVIEW";

    public ConciergeEvent {
        facts = facts == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(facts));
    }

    /** The event with the Butler's line for its type built from the facts. */
    public static ConciergeEvent of(String type, long reservationId, Long visitId, String sessionId, Map<String, Object> facts) {
        return new ConciergeEvent(type, reservationId, visitId, sessionId, butlerText(type, facts == null ? Map.of() : facts), facts);
    }

    /** The Butler's line for the guest, the same few words whatever chat it lands in. */
    public static String butlerText(String type, Map<String, Object> facts) {
        String time = text(facts.get("time"), "назначенное время");
        String table = text(facts.get("table"), "стол");
        return switch (type) {
            case CONFIRMED -> "Стол подтверждён: " + table + " на " + time + ". Ждём вас.";
            case REJECTED -> "К сожалению, на это время мест нет. Предложить другое?";
            case EXPIRED -> "Команда не успела подтвердить бронь на " + time + ". Предложить другое время?";
            case MOVED -> "Бронь перенесена на " + time + ", стол " + table + ".";
            case MORNING -> "Доброе утро! Ждём вас сегодня в " + time + ". Всё в силе?";
            case SEATED -> "Приятного аппетита! Меню и счёт — здесь же.";
            case BILL_READY -> billText(facts);
            case STAFF_COMING -> facts.get("waiter") == null
                    ? "Передал команде, скоро подойдут."
                    : "Передал. " + facts.get("waiter") + " скоро подойдёт.";
            case REVIEW -> "Как вам сегодня? Оцените от 1 до 5 одной цифрой — передам команде.";
            case CANCELLED -> "Бронь на " + time + " отменена. Если захотите выбрать другое время — просто напишите.";
            default -> throw new IllegalArgumentException("Unknown Concierge event type: " + type);
        };
    }

    private static String billText(Map<String, Object> facts) {
        Object total = facts.get("totalRub");
        Object url = facts.get("paymentUrl");
        String head = total == null ? "Счёт готов" : "Счёт " + total + " ₽";
        return url == null ? head : head + " — оплатить: " + url;
    }

    private static String text(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }
}
