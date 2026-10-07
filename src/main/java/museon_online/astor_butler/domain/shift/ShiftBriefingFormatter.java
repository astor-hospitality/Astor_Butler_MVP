package museon_online.astor_butler.domain.shift;

import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** The briefing as a chat message: short lines a hostess reads on a phone between guests. */
@Component
public class ShiftBriefingFormatter {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM, EEEE", Locale.of("ru"));

    public String format(ShiftBriefing briefing) {
        var text = new StringBuilder();
        boolean morning = briefing.kind() == ShiftBriefing.Kind.MORNING;
        text.append("<b>").append(morning ? "Смена начинается" : "Смена закрывается").append("</b> · ")
                .append(escape(briefing.date().format(DATE))).append('\n');

        if (morning) {
            if (briefing.slots().isEmpty()) {
                text.append("\nБроней на сегодня нет.\n");
            } else {
                text.append("\n<b>").append(briefing.slots().size()).append(" брони, ")
                        .append(briefing.guests()).append(" гостей</b>\n");
                for (ShiftBriefing.Slot slot : briefing.slots()) {
                    text.append(escape(slot.time())).append(" · ").append(escape(slot.table()))
                            .append(" · ").append(slot.partySize()).append(" гост").append(ending(slot.partySize()));
                    if (slot.guestName() != null && !slot.guestName().isBlank()) text.append(" · ").append(escape(slot.guestName()));
                    if (slot.businessLunch()) text.append(" · ланч");
                    if ("AWAITING_MANAGER_CONFIRMATION".equals(slot.status())) text.append(" · <i>ждёт подтверждения</i>");
                    if (slot.comment() != null && !slot.comment().isBlank()) text.append("\n    ").append(escape(slot.comment()));
                    text.append('\n');
                }
            }
            if (briefing.awaitingConfirmation() > 0) {
                text.append("\nПодтвердить до начала: ").append(briefing.awaitingConfirmation()).append('\n');
            }
            if (briefing.lunchOffer() != null) text.append("\nБизнес-ланч: ").append(escape(briefing.lunchOffer())).append('\n');
            if (!briefing.lunchBookings().isEmpty()) {
                text.append("На ланч: ").append(escape(String.join("; ", briefing.lunchBookings()))).append('\n');
            }
            if (briefing.bills().unpaid() > 0) {
                text.append("\nНезакрытые счета прошлого дня: ").append(briefing.bills().unpaid()).append('\n');
            }
        } else {
            text.append("\nБроней: ").append(briefing.slots().size()).append(", гостей: ").append(briefing.guests());
            if (briefing.cancelled() > 0) text.append(", отменено: ").append(briefing.cancelled());
            text.append('\n');
            var bills = briefing.bills();
            if (bills.opened() > 0) {
                text.append("Счета: ").append(bills.opened()).append(", оплачено ").append(bills.paid())
                        .append(", не оплачено ").append(bills.unpaid());
                if (bills.venueMinor() > 0) text.append(", ресторан провёл ").append(rub(bills.venueMinor())).append(" ₽");
                else if (bills.estimatedMinor() > 0) text.append(", предварительно ").append(rub(bills.estimatedMinor())).append(" ₽");
                text.append('\n');
            }
            var reviews = briefing.reviews();
            if (reviews.answered() > 0) {
                text.append(String.format(Locale.of("ru"), "Оценки: %d, в среднем %.1f", reviews.answered(), reviews.averageStars()));
                if (reviews.thumbsUp() + reviews.thumbsDown() > 0) {
                    text.append(" · 👍 ").append(reviews.thumbsUp()).append(" / 👎 ").append(reviews.thumbsDown());
                }
                text.append('\n');
            }
            if (briefing.awaitingConfirmation() > 0) {
                text.append("Осталось без подтверждения: ").append(briefing.awaitingConfirmation()).append('\n');
            }
        }

        if (!briefing.warnings().isEmpty()) {
            text.append("\n<i>").append(escape(String.join("; ", briefing.warnings()))).append("</i>\n");
        }
        text.append("\nСводка по данным Butler. Решения за сменой.");
        return text.toString();
    }

    private static String ending(int party) {
        int last = party % 10, tens = party % 100;
        if (tens >= 11 && tens <= 14) return "ей";
        if (last == 1) return "ь";
        if (last >= 2 && last <= 4) return "я";
        return "ей";
    }

    private static String rub(long minor) {
        return String.valueOf(Math.round(minor / 100.0));
    }

    static String escape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
