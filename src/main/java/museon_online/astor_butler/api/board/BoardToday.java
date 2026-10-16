package museon_online.astor_butler.api.board;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/**
 * The shift manager's «Сегодня»: the day's bookings and lunch orders in time order and the day's counters, one venue or
 * all of them. Test guests never reach it (see {@link BoardQueries#todayRows}). Read-only: it only shows what the hostess
 * and the bots already decided.
 */
@Component
public class BoardToday {

    /** A request the hostess has not answered for this long is overdue on the board. */
    static final Duration OVERDUE = Duration.ofMinutes(10);

    public enum Status { WAITING, CONFIRMED, CANCELLED, NO_SHOW, BILL }

    public enum Channel { CONCIERGE, BUTLER }

    private static final Set<String> CANCELLED_RESERVATION = Set.of("REJECTED", "CANCELLED", "EXPIRED");
    private static final Set<String> CONFIRMED_RESERVATION = Set.of("CONFIRMED", "SEATED");
    private static final Set<String> CONFIRMED_STAGE = Set.of("CONFIRMED", "VISIT_DAY", "SEATED");
    private static final Set<String> BILL_STAGE = Set.of("BILL", "AFTER", "RETURNED");

    /**
     * @param guest        the first name only; «Гость» without one
     * @param lunchRub     the lunch bill (the venue's amount when it reported one, else Butler's estimate); null without lunch
     * @param waitingSince when the request reached the hostess, only while she has not answered
     * @param stars        the guest's stars for the visit, 1–5; null without a rating
     */
    public record Item(long visitId, long reservationId, String venue, String at, String guest, String table, Integer guests,
                       Status status, Long lunchRub, Channel channel, String waitingSince, Integer stars) {
    }

    /**
     * @param lunchRub  lunch bills of the day that were not cancelled
     * @param ratingAvg average stars of the day, one decimal; null without ratings
     */
    public record Totals(long requests, long confirmed, long waiting, long waitingOverdue, long lunchRub, Double ratingAvg,
                         long ratings) {
    }

    public record Day(String from, String to, String venue, List<String> venues, List<Item> items, Totals totals) {
    }

    private final BoardQueries queries;
    private final ZoneId zone;
    private final Clock clock;

    @Autowired
    public BoardToday(BoardQueries queries, @Value("${astor.guest-journey.zone-id:Asia/Yekaterinburg}") String zone) {
        this(queries, ZoneId.of(zone), Clock.systemUTC());
    }

    BoardToday(BoardQueries queries, ZoneId zone, Clock clock) {
        this.queries = queries;
        this.zone = zone;
        this.clock = clock;
    }

    /** The venue day that holds now: [midnight, next midnight) by the venue clock. */
    Instant[] today() {
        LocalDate day = LocalDate.now(clock.withZone(zone));
        return new Instant[]{day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant()};
    }

    /** @param venue a venue code, or null for every venue */
    public Day day(Instant from, Instant to, String venue) {
        Instant now = clock.instant();
        List<Item> items = queries.todayRows(from, to, venue).stream().map(BoardToday::item).toList();
        long confirmed = items.stream().filter(item -> item.status() == Status.CONFIRMED || item.status() == Status.BILL).count();
        List<Item> waiting = items.stream().filter(item -> item.status() == Status.WAITING).toList();
        long overdue = waiting.stream()
                .filter(item -> item.waitingSince() != null && !Instant.parse(item.waitingSince()).plus(OVERDUE).isAfter(now))
                .count();
        long lunch = items.stream()
                .filter(item -> item.lunchRub() != null && item.status() != Status.CANCELLED && item.status() != Status.NO_SHOW)
                .mapToLong(Item::lunchRub).sum();
        List<Integer> stars = items.stream().map(Item::stars).filter(value -> value != null).toList();
        Double rating = stars.isEmpty() ? null
                : Math.round(stars.stream().mapToInt(Integer::intValue).average().orElse(0) * 10) / 10.0;
        return new Day(from.toString(), to.toString(), venue, queries.venues(from.minus(BoardController.MAX_WINDOW)), items,
                new Totals(items.size(), confirmed, waiting.size(), overdue, lunch, rating, stars.size()));
    }

    static Item item(BoardQueries.TodayRow row) {
        Status status = status(row.reservationStatus(), row.stage());
        Instant at = row.startAt() != null ? row.startAt() : row.visitDay() != null ? row.visitDay() : row.openedAt();
        Long minor = row.lunchVenueMinor() != null ? row.lunchVenueMinor() : row.lunchEstimateMinor();
        Long lunchRub = row.lunch() ? (minor == null ? 0L : Math.round(minor / 100.0)) : null;
        Instant since = status == Status.WAITING ? (row.createdAt() != null ? row.createdAt() : row.openedAt()) : null;
        return new Item(row.visitId(), row.reservationId(), row.venue(), text(at), firstName(row.guestName()), row.table(),
                row.partySize(), status, lunchRub, channel(row.source()), text(since), row.stars());
    }

    /** The reservation decides a cancellation and a confirmation; the visit adds the no-show and the bill. */
    static Status status(String reservationStatus, String visitStage) {
        // Set.of(...).contains(null) throws: a missing reservation or stage reads as an empty one.
        String reservation = reservationStatus == null ? "" : reservationStatus;
        String stage = visitStage == null ? "" : visitStage;
        if ("NO_SHOW".equals(stage)) {
            return Status.NO_SHOW;
        }
        if ("CANCELLED".equals(stage) || CANCELLED_RESERVATION.contains(reservation)) {
            return Status.CANCELLED;
        }
        if (BILL_STAGE.contains(stage)) {
            return Status.BILL;
        }
        if (CONFIRMED_RESERVATION.contains(reservation) || CONFIRMED_STAGE.contains(stage)) {
            return Status.CONFIRMED;
        }
        return Status.WAITING;
    }

    static Channel channel(String source) {
        return "CONCIERGE".equals(source) ? Channel.CONCIERGE : Channel.BUTLER;
    }

    /** «Мария»: the first word of the name; nothing (or a phone-like name) → «Гость». */
    static String firstName(String name) {
        String masked = BoardController.maskName(name);
        int space = masked.indexOf(' ');
        return space < 0 ? masked : masked.substring(0, space);
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
