package museon_online.astor_butler.domain.lunch.scoreboard;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/** The send time is read as a venue-clock time; anything unreadable is 16:30. */
class LunchScoreboardPropertiesTest {

    @Test
    void sendAtIsParsedAsLocalTime() {
        assertThat(new LunchScoreboardProperties(true, "16:30").sendAt()).isEqualTo(LocalTime.of(16, 30));
        assertThat(new LunchScoreboardProperties(true, " 17:05 ").sendAt()).isEqualTo(LocalTime.of(17, 5));
        assertThat(new LunchScoreboardProperties(true, "16:30:15").sendAt()).isEqualTo(LocalTime.of(16, 30, 15));
    }

    @Test
    void anUnreadableSendAtFallsBackToHalfPastFour() {
        assertThat(new LunchScoreboardProperties(true, "half past four").sendAt()).isEqualTo(LocalTime.of(16, 30));
        assertThat(new LunchScoreboardProperties(true, "25:00").sendAt()).isEqualTo(LocalTime.of(16, 30));
        assertThat(new LunchScoreboardProperties(true, "").sendAt()).isEqualTo(LocalTime.of(16, 30));
        assertThat(new LunchScoreboardProperties(true, null).sendAt()).isEqualTo(LocalTime.of(16, 30));
    }

    @Test
    void offByDefaultIsAPlainBoolean() {
        assertThat(new LunchScoreboardProperties(false, "16:30").isEnabled()).isFalse();
        assertThat(new LunchScoreboardProperties(true, "16:30").isEnabled()).isTrue();
    }
}
