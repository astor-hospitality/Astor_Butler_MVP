package museon_online.astor_butler.domain.shift;

import java.time.LocalDate;
import java.util.List;

/**
 * What the shift manager gets the moment Butler is stopped (#134): every open status, so the shift can be run by hand
 * from this one message. Facts from what Butler already holds, no guest phone numbers — it is read in a group chat.
 *
 * @param awaitingHostess booking requests waiting for the hostess card («Да» / «Нет»), lunches included
 * @param confirmed       bookings the hostess (or the timer) confirmed and Presto does not hold
 * @param inPresto        bookings Presto holds (written there by Butler), whatever their local status
 * @param lunchesToday    today's business-lunch orders with time, table, party and composition
 * @param bills           today's bills by status; {@code null} when billing is off
 * @param staffTasks      open staff tasks; {@code null} when the table could not be read
 */
public record ButlerPauseSummary(
        LocalDate date,
        String venueCode,
        List<Booking> awaitingHostess,
        List<Booking> confirmed,
        List<Booking> inPresto,
        List<Lunch> lunchesToday,
        Bills bills,
        List<Task> staffTasks
) {
    /** One booking as the shift reads it: when, who, where, how many. */
    public record Booking(Long id, String when, String guestName, String table, int partySize, boolean lunch) { }

    public record Lunch(Long id, String time, String guestName, String table, int partySize, String status, String composition) { }

    /** {@code issued}: Presto holds the order, no link yet; {@code linkSent}: the guest has the payment page. */
    public record Bills(int estimated, int issued, int linkSent, int paid) {
        public int open() {
            return estimated + issued + linkSent;
        }
    }

    public record Task(String title, String tableCode, String status, String assignee) { }
}
