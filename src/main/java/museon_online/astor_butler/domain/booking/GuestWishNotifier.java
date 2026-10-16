package museon_online.astor_butler.domain.booking;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.ledger.PhoneMask;
import museon_online.astor_butler.domain.lunch.BusinessLunchService;
import museon_online.astor_butler.telegram.utils.HostessTopicRouter;
import museon_online.astor_butler.telegram.utils.TelegramBot;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The guest's wishes for the visit («без лука», an allergy, the occasion, a high chair, a table by the window) as a short
 * note of their own in the wishes topic of the staff group ({@code telegram.booking.wishes-thread-id}, AERIS
 * «Пожелания»), so the kitchen and the floor read them without the whole card. The hostess card keeps its «Пожелание»
 * line as before. The booking, the day and time, the table, the party and the wish; never the guest's phone or name.
 * Posted when a booking comes with a wish and again only when a change brings a different one. Blank topic, blank
 * hostess chat or Telegram off: nothing is posted.
 */
@Service
@Slf4j
public class GuestWishNotifier {

    private static final ZoneId VENUE_ZONE = ZoneId.of("Asia/Yekaterinburg");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(VENUE_ZONE);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(VENUE_ZONE);
    /** «Пожелание: …» notes the table booking keeps among the guest's words (« | » between them), and the lunch's own. */
    private static final Pattern NOTE = Pattern.compile("Пожелание: (.+?)(?=\\s\\|\\s|\\.?\\s*В Saby внести вручную|$)", Pattern.DOTALL);
    /** «Подбери сам» and the like leave the table to the team: that is not a wish (as TableBookingStepRegistry reads it). */
    private static final List<String> LEAVES_THE_CHOICE = List.of("выбери сам", "подбери сам", "любой", "на твой выбор", "где удобно", "на ваше усмотрение");
    static final int REMEMBERED_BOOKINGS = 500;

    private final ObjectProvider<TelegramBot> telegramBotProvider;
    private final boolean enabled;
    private final String hostessChatId;
    private final Integer threadId;
    private final Map<Long, String> posted = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, String> eldest) {
            return size() > REMEMBERED_BOOKINGS;
        }
    });

    @Autowired
    public GuestWishNotifier(ObjectProvider<TelegramBot> telegramBotProvider,
                             @Value("${telegram.bot.enabled:false}") boolean telegramEnabled,
                             @Value("${telegram.booking.notifications-enabled:true}") boolean notificationsEnabled,
                             @Value("${telegram.booking.hostess-chat-id:}") String hostessChatId,
                             @Value("${telegram.booking.wishes-thread-id:}") String wishesThreadId) {
        this.telegramBotProvider = telegramBotProvider;
        this.hostessChatId = hostessChatId == null ? "" : hostessChatId.trim();
        this.threadId = HostessTopicRouter.threadId(wishesThreadId, "TELEGRAM_WISHES_THREAD_ID");
        this.enabled = telegramEnabled && notificationsEnabled && !this.hostessChatId.isEmpty() && threadId != null;
    }

    /**
     * Posts the note when the booking carries a wish it has not been posted with. Never throws.
     *
     * @return whether a note left Butler
     */
    public boolean onBooking(TableReservationOrder order) {
        if (!enabled || order == null || order.id() == null) {
            return false;
        }
        List<String> wishes = wishes(order);
        if (wishes.isEmpty()) {
            return false;
        }
        String wish = String.join("; ", wishes);
        String before = posted.get(order.id());
        if (wish.equals(before)) {
            return false;
        }
        try {
            TelegramBot bot = telegramBotProvider.getIfAvailable();
            if (bot == null) {
                return false;
            }
            bot.execute(SendMessage.builder()
                    .chatId(hostessChatId)
                    .messageThreadId(threadId)
                    .text(text(order, wish, before != null))
                    .parseMode("HTML")
                    .disableWebPagePreview(true)
                    .build());
            posted.put(order.id(), wish);
            return true;
        } catch (Exception e) {
            log.warn("Guest wish note was not posted: booking={}, reason={}", order.id(), e.getClass().getSimpleName());
            return false;
        }
    }

    /** What the guest asked for besides the table, in their words; empty when nothing. */
    static List<String> wishes(TableReservationOrder order) {
        List<String> wishes = new ArrayList<>();
        String seating = order.seatingPreference() == null ? "" : order.seatingPreference().trim();
        if (!seating.isEmpty() && !BusinessLunchService.SEATING_LABEL.equals(seating) && !leavesTheChoice(seating)) {
            wishes.add(seating);
        }
        String comment = order.guestComment() == null ? "" : order.guestComment();
        int lunchWish = comment.indexOf(BusinessLunchService.WISH_PREFIX);
        if (lunchWish >= 0) {
            int start = lunchWish + BusinessLunchService.WISH_PREFIX.length();
            int end = comment.indexOf(BusinessLunchService.WISH_SUFFIX, start);
            wishes.add((end < 0 ? comment.substring(start) : comment.substring(start, end)).trim());
        }
        Matcher note = NOTE.matcher(comment);
        while (note.find()) {
            wishes.add(note.group(1).trim().replaceAll("\\.$", "").trim());
        }
        return wishes.stream().filter(wish -> !wish.isBlank()).map(String::strip).filter(distinct()).toList();
    }

    private static java.util.function.Predicate<String> distinct() {
        List<String> seen = new ArrayList<>();
        return wish -> {
            String key = wish.toLowerCase(Locale.ROOT);
            if (seen.contains(key)) {
                return false;
            }
            seen.add(key);
            return true;
        };
    }

    private static boolean leavesTheChoice(String seating) {
        String normalized = seating.toLowerCase(Locale.ROOT).replace('ё', 'е');
        return normalized.split("\\s+").length <= 3 && LEAVES_THE_CHOICE.stream().anyMatch(normalized::contains);
    }

    static String text(TableReservationOrder order, String wish, boolean changed) {
        boolean lunch = BusinessLunchService.SEATING_LABEL.equals(order.seatingPreference())
                || order.guestComment() != null && order.guestComment().startsWith(BusinessLunchService.SEATING_LABEL);
        StringBuilder text = new StringBuilder("📝 <b>").append(changed ? "Пожелание изменилось" : "Пожелание гостя")
                .append("</b> — бронь #").append(order.id());
        if (lunch) {
            text.append(", бизнес-ланч");
        }
        text.append('\n');
        text.append("<b>Когда:</b> ").append(when(order)).append('\n');
        text.append("<b>Стол:</b> ").append(escape(TableReservationNotificationService.tableName(order))).append('\n');
        text.append("<b>Гостей:</b> ").append(order.partySize() == null ? "не указано" : order.partySize()).append('\n');
        text.append("<blockquote>").append(escape(PhoneMask.mask(wish))).append("</blockquote>");
        return text.toString();
    }

    private static String when(TableReservationOrder order) {
        if (order.requestedStartAt() == null) {
            return "не указано";
        }
        String start = DATE_TIME.format(order.requestedStartAt());
        return order.requestedEndAt() == null || Objects.equals(order.requestedEndAt(), order.requestedStartAt())
                ? start
                : start + "–" + TIME.format(order.requestedEndAt());
    }

    private static String escape(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
