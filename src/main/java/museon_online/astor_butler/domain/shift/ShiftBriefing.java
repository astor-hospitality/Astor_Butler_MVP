package museon_online.astor_butler.domain.shift;

import java.time.LocalDate;
import java.util.List;

/**
 * What the shift needs to know, once in the morning and once at the end of the day.
 *
 * Facts only, drawn from what Butler already holds: bookings, bills, the guests' word after the visit
 * and the settings that are off. No prediction, no score for anyone's work, and no guest phone
 * numbers — a briefing is read in a group chat, so it carries a name and a table and nothing more.
 */
public record ShiftBriefing(
        Kind kind,
        LocalDate date,
        String venueCode,
        List<Slot> slots,
        int guests,
        int awaitingConfirmation,
        int cancelled,
        List<String> lunchBookings,
        String lunchOffer,
        Bills bills,
        Reviews reviews,
        List<String> warnings
) {
    public enum Kind { MORNING, EVENING }

    /** One booking as the shift reads it: when, who, where, how many. */
    public record Slot(String time, String guestName, String table, String zone, int partySize,
                       String status, boolean businessLunch, String comment) { }

    public record Bills(int opened, int issued, int paid, int unpaid, long estimatedMinor, long venueMinor) { }

    public record Reviews(int answered, double averageStars, int thumbsUp, int thumbsDown) { }

    public boolean empty() {
        return slots.isEmpty() && bills.opened() == 0 && reviews.answered() == 0;
    }
}
