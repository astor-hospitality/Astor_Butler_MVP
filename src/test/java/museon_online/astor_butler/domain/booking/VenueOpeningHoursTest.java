package museon_online.astor_butler.domain.booking;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/** The defaults are the hours on aeris.bar: Mon to Thu 12:00–02:00, Fri 12:00–04:00, Sat 14:00–04:00, Sun 14:00–02:00. */
class VenueOpeningHoursTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 9);
    private static final LocalDate SATURDAY = LocalDate.of(2026, 10, 10);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 11);

    private final VenueOpeningHours hours = new VenueOpeningHours();

    @Test
    void anEveningIsOpenAndTheMorningAfterIsNot() {
        assertThat(hours.isOpen(MONDAY, LocalTime.of(12, 0))).isTrue();
        assertThat(hours.isOpen(MONDAY, LocalTime.of(19, 30))).isTrue();
        assertThat(hours.isOpen(MONDAY, LocalTime.of(23, 59))).isTrue();
        assertThat(hours.isOpen(MONDAY, LocalTime.of(11, 59))).isFalse();
        assertThat(hours.isOpen(MONDAY, LocalTime.of(9, 0))).isFalse();
        // Three in the night from Monday to Tuesday: Monday closed at 02:00, Tuesday opens at noon.
        assertThat(hours.isOpen(MONDAY.plusDays(1), LocalTime.of(3, 0))).isFalse();
    }

    @Test
    void theHoursAfterMidnightBelongToTheEveningBefore() {
        assertThat(hours.isOpen(MONDAY.plusDays(1), LocalTime.of(1, 59))).isTrue();
        assertThat(hours.isOpen(MONDAY.plusDays(1), LocalTime.of(2, 0))).isFalse();
        // Friday runs until 04:00, so three on Saturday night is still open.
        assertThat(hours.isOpen(SATURDAY, LocalTime.of(3, 0))).isTrue();
        assertThat(hours.isOpen(SATURDAY, LocalTime.of(4, 0))).isFalse();
        // Sunday night ended at 02:00, so Monday 01:00 is open and Monday 03:00 is not.
        assertThat(hours.isOpen(MONDAY, LocalTime.of(1, 0))).isTrue();
        assertThat(hours.isOpen(MONDAY, LocalTime.of(3, 0))).isFalse();
    }

    @Test
    void knowsTheLateHoursOfAnEvening() {
        // Monday closes at 02:00 the next morning, Friday at 04:00.
        assertThat(hours.isLateHourOf(MONDAY, LocalTime.of(0, 30))).isTrue();
        assertThat(hours.isLateHourOf(MONDAY, LocalTime.of(1, 59))).isTrue();
        assertThat(hours.isLateHourOf(MONDAY, LocalTime.of(2, 0))).isFalse();
        assertThat(hours.isLateHourOf(MONDAY, LocalTime.of(13, 0))).isFalse();
        assertThat(hours.isLateHourOf(MONDAY, LocalTime.of(23, 0))).isFalse();
        assertThat(hours.isLateHourOf(FRIDAY, LocalTime.of(3, 0))).isTrue();
        assertThat(hours.isLateHourOf(null, LocalTime.of(0, 30))).isFalse();
        hours.setEnabled(false);
        assertThat(hours.isLateHourOf(MONDAY, LocalTime.of(0, 30))).isFalse();
    }

    @Test
    void theWeekendOpensLater() {
        assertThat(hours.isOpen(SATURDAY, LocalTime.of(13, 0))).isFalse();
        assertThat(hours.isOpen(SATURDAY, LocalTime.of(14, 0))).isTrue();
        assertThat(hours.isOpen(SUNDAY, LocalTime.of(12, 30))).isFalse();
        assertThat(hours.isOpen(FRIDAY, LocalTime.of(12, 30))).isTrue();
    }

    @Test
    void saysTheHoursOfADayInWords() {
        assertThat(hours.describe(MONDAY)).contains("с 12:00 до 02:00");
        assertThat(hours.describe(FRIDAY)).contains("с 12:00 до 04:00");
        assertThat(hours.describe(SATURDAY)).contains("с 14:00 до 04:00");
        assertThat(hours.describe(SUNDAY)).contains("с 14:00 до 02:00");
    }

    @Test
    void aDayCanBeClosedOrChangedInTheSettings() {
        hours.getDays().put(DayOfWeek.MONDAY, "closed");
        hours.getDays().put(DayOfWeek.SATURDAY, "10:00-23:00");

        assertThat(hours.isOpen(MONDAY, LocalTime.of(19, 0))).isFalse();
        assertThat(hours.describe(MONDAY)).isEmpty();
        // Sunday still runs into Monday night.
        assertThat(hours.isOpen(MONDAY, LocalTime.of(1, 0))).isTrue();
        assertThat(hours.isOpen(SATURDAY, LocalTime.of(10, 0))).isTrue();
        assertThat(hours.isOpen(SATURDAY, LocalTime.of(23, 0))).isFalse();
        // Friday night still runs into Saturday morning.
        assertThat(hours.isOpen(SATURDAY, LocalTime.of(3, 0))).isTrue();
    }

    @Test
    void hoursThatCannotBeReadOrAreSwitchedOffNeverRefuseAGuest() {
        hours.getDays().put(DayOfWeek.MONDAY, "с двенадцати");
        hours.getDays().remove(DayOfWeek.FRIDAY);

        assertThat(hours.isOpen(MONDAY, LocalTime.of(9, 0))).isTrue();
        assertThat(hours.isOpen(FRIDAY, LocalTime.of(9, 0))).isTrue();
        assertThat(hours.describe(FRIDAY)).isEmpty();
        assertThat(hours.isOpen(SATURDAY, LocalTime.of(13, 0))).isFalse();

        hours.setEnabled(false);
        assertThat(hours.isOpen(SATURDAY, LocalTime.of(13, 0))).isTrue();
        assertThat(hours.describe(SATURDAY)).isEmpty();
    }
}
