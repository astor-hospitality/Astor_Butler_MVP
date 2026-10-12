package museon_online.astor_butler.domain.booking;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class BookingMorningCheckPropertiesTest {

    @Test
    void readsTheSendTimeTheLeadAndTheZoneAndFallsBackOnNonsense() {
        var properties = new BookingMorningCheckProperties(true, "09:15", 45, "aeris", "Europe/Moscow");
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.sendTime()).isEqualTo(LocalTime.of(9, 15));
        assertThat(properties.minLead()).isEqualTo(Duration.ofMinutes(45));
        assertThat(properties.venueCode()).isEqualTo("AERIS");
        assertThat(properties.zone()).isEqualTo(ZoneId.of("Europe/Moscow"));

        var broken = new BookingMorningCheckProperties(false, "half past ten", -5, " ", "Mars/Olympus");
        assertThat(broken.isEnabled()).isFalse();
        assertThat(broken.sendTime()).isEqualTo(LocalTime.of(10, 30));
        assertThat(broken.minLead()).isZero();
        assertThat(broken.venueCode()).isEqualTo("AERIS");
        assertThat(broken.zone()).isEqualTo(ZoneId.of("Asia/Yekaterinburg"));
    }
}
