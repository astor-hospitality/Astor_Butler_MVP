package museon_online.astor_butler.domain.lunch;

import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.fsm.scenario.BookingTimeProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * The kitchen's own answer to a pre-order that reaches Presto hours before the guest (#164, chef interview 12.10, q6):
 * one line «ПРЕДЗАКАЗ: готовить ко времени 13:00, гостей 2» that opens the Saby booking comment, so it is the first thing
 * on the Presto ticket, and the same time as a bold line on the hostess card. The time is the lunch start the guest chose,
 * less {@code lead-minutes}, in the venue's own zone. Nothing here changes when the dishes are sent or what Presto does with
 * them — whether Presto prints them at once is checked on the first test. Off by default: with the flag off the comment and
 * the card are what they were, byte for byte.
 */
@Component
public class LunchPreorderMark {

    /** The Saby comment line starts with this; the kitchen reads it before anything else. */
    public static final String PREFIX = "ПРЕДЗАКАЗ: готовить ко времени ";
    public static final String GUESTS = ", гостей ";
    /** The hostess card line. */
    public static final String CARD_PREFIX = "⏱ Готовить ко времени ";
    /** The separator the Saby comment is assembled with ({@code SabyOrderPayload.comment}). */
    public static final String SEPARATOR = " · ";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(BookingTimeProvider.VENUE_ZONE);

    private final boolean enabled;
    private final int leadMinutes;

    public LunchPreorderMark(
            @Value("${astor.business-lunch.preorder-mark.enabled:false}") boolean enabled,
            @Value("${astor.business-lunch.preorder-mark.lead-minutes:0}") int leadMinutes) {
        this.enabled = enabled;
        this.leadMinutes = Math.max(0, leadMinutes);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** How many minutes before the lunch start the kitchen is asked to have the order ready; never negative. */
    public int leadMinutes() {
        return leadMinutes;
    }

    /** «13:00» — the lunch start less the lead, venue time. Empty while the mark is off or the start is unknown. */
    public Optional<String> readyAt(Instant startAt) {
        if (!enabled || startAt == null) {
            return Optional.empty();
        }
        return Optional.of(time(startAt));
    }

    /** The line for the Saby booking comment: «ПРЕДЗАКАЗ: готовить ко времени 13:00, гостей 2». */
    public Optional<String> sabyLine(Instant startAt, int guests) {
        return readyAt(startAt).map(time -> line(time, guests));
    }

    /** The line for the hostess card of a lunch: «⏱ Готовить ко времени 13:00». Empty for any other reservation. */
    public Optional<String> cardLine(TableReservationOrder order) {
        if (!isLunch(order)) {
            return Optional.empty();
        }
        return readyAt(order.requestedStartAt()).map(time -> CARD_PREFIX + time);
    }

    /**
     * A moved lunch: the line at the start of the comment follows the new time and party, so the kitchen never reads a stale
     * one. A comment without the line is returned as it is, whatever the flag says now — the line is only ever there because
     * the mark was on when the dishes were sent.
     */
    public String refresh(String comment, Instant startAt, Integer guests) {
        if (comment == null || !comment.startsWith(PREFIX) || startAt == null || guests == null) {
            return comment;
        }
        int end = comment.indexOf(SEPARATOR);
        return line(time(startAt), guests) + (end < 0 ? "" : comment.substring(end));
    }

    /** A lunch is known by its seating label, as everywhere else in Butler. */
    public static boolean isLunch(TableReservationOrder order) {
        return order != null && BusinessLunchService.SEATING_LABEL.equals(order.seatingPreference());
    }

    private String time(Instant startAt) {
        return TIME.format(startAt.minus(Duration.ofMinutes(leadMinutes)));
    }

    private static String line(String time, int guests) {
        return PREFIX + time + GUESTS + guests;
    }
}
