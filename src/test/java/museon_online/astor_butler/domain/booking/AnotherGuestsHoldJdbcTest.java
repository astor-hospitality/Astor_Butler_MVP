package museon_online.astor_butler.domain.booking;

import museon_online.astor_butler.api.common.ApiException;
import museon_online.astor_butler.domain.booking.external.ExternalAvailabilityResult;
import museon_online.astor_butler.domain.booking.external.ExternalReservationProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Prod run 16.10: booking #8 (CONFIRMED) holds table 4 today at 13:00, yet the Butler put «стол 4, 13:00» to another
 * guest. On the real SQL, the table another guest's active order holds is neither offered by availability nor booked
 * when asked for by its code: the hold is what both read, whoever holds it.
 */
class AnotherGuestsHoldJdbcTest {

    /** 13:00 in Yekaterinburg. */
    private static final Instant ONE_PM = Instant.parse("2026-10-20T08:00:00Z");
    private static final Duration VISIT = Duration.ofMinutes(90);
    private static final long TABLE_4 = 1L;
    private static final long TABLE_5 = 2L;

    private final JdbcTemplate jdbc = TableReservationTestDatabase.create();
    private final TableReservationRepository repository = new TableReservationRepository(jdbc);
    private final ExternalReservationProvider externalProvider = mock(ExternalReservationProvider.class);
    private final TableReservationService reservations = new TableReservationService(repository,
            mock(TableReservationNotificationService.class), externalProvider);

    @BeforeEach
    void venue() {
        TableReservationTestDatabase.table(jdbc, TABLE_4, "4", "MAIN_HALL", 4);
        TableReservationTestDatabase.table(jdbc, TABLE_5, "5", "MAIN_HALL", 4);
        when(externalProvider.providerId()).thenReturn("SABY");
        when(externalProvider.checkAvailability(any()))
                .thenReturn(ExternalAvailabilityResult.unavailableBecauseUnconfigured("SABY", List.of()));
        // Booking #8 of another guest, confirmed by the hostess: its hold is CONFIRMED.
        TableReservationTestDatabase.order(jdbc, 8L, 1001L, TABLE_4, TableReservationStatus.CONFIRMED, ONE_PM,
                ONE_PM.plus(VISIT), null, ONE_PM.minus(Duration.ofDays(1)));
        TableReservationTestDatabase.hold(jdbc, 8L, TABLE_4, "CONFIRMED", ONE_PM, ONE_PM.plus(VISIT));
    }

    @Test
    void anotherGuestsTableIsNotOffered() {
        List<TableAvailability> free = reservations.availability("AERIS", ONE_PM, ONE_PM.plus(VISIT), 2);

        assertThat(free).filteredOn(TableAvailability::available).extracting(slot -> slot.table().tableCode())
                .containsExactly("5");
    }

    @Test
    void anotherGuestsTableIsNotBookedEvenByItsCode() {
        TableReservationCommand table4 = new TableReservationCommand(null, null, null, "AERIS", "4", null, null,
                ONE_PM, ONE_PM.plus(VISIT), 2, "Анна", null, null, null, null, TableReservationCommand.CHANNEL_CONCIERGE);

        assertThatThrownBy(() -> reservations.createReservation(table4))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(TableReservationTestDatabase.holds(jdbc, 8L)).isEqualTo(1);
    }
}
