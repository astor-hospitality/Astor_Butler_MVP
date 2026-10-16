package museon_online.astor_butler.domain.booking;

import museon_online.astor_butler.domain.booking.external.ExternalReservationProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A Concierge booking has no chat: on the real SQL its guest is found by the visit of the session or by the ledger
 * turns that link the session to a visit, so a second table for the same time is never booked.
 */
class SessionReservationOverlapJdbcTest {

    private static final Instant SEVEN = Instant.parse("2026-10-20T14:00:00Z");
    private static final Duration VISIT = Duration.ofMinutes(90);

    private final JdbcTemplate jdbc = TableReservationTestDatabase.create();
    private final TableReservationRepository repository = new TableReservationRepository(jdbc);
    private final TableReservationService reservations = new TableReservationService(repository,
            mock(TableReservationNotificationService.class), mock(ExternalReservationProvider.class));

    @BeforeEach
    void schema() throws Exception {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/changelog/2026-10-14-guest-journey.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/changelog/2026-10-15-visit-turns.sql"));
        }
        TableReservationTestDatabase.table(jdbc, 1L, "4", "MAIN_HALL", 4);
    }

    @Test
    void theSessionsGuestIsFoundByItsLinkedVisitOrTheVisitItIsIn() {
        conciergeBooking(61L, 1L, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION, "c-1");
        conciergeBooking(62L, 2L, TableReservationStatus.CONFIRMED, null);
        conciergeBooking(63L, 3L, TableReservationStatus.CONFIRMED, "c-2");
        conciergeBooking(64L, 4L, TableReservationStatus.CANCELLED, "c-1");

        assertThat(repository.findActiveOrdersOfSession("c-1", null, "AERIS")).extracting(TableReservationOrder::id)
                .containsExactly(61L);
        assertThat(repository.findActiveOrdersOfSession("c-1", 2L, "AERIS")).extracting(TableReservationOrder::id)
                .containsExactlyInAnyOrder(61L, 62L);
        assertThat(repository.findActiveOrdersOfSession(null, 2L, "AERIS")).extracting(TableReservationOrder::id)
                .containsExactly(62L);
        assertThat(repository.findActiveOrdersOfSession("c-9", null, "AERIS")).isEmpty();
        assertThat(repository.findActiveOrdersOfSession(" ", null, "AERIS")).isEmpty();
    }

    @Test
    void onlyACrossingVisitIsInTheWay() {
        conciergeBooking(61L, 1L, TableReservationStatus.CONFIRMED, "c-1");

        assertThat(reservations.findOverlappingSessionReservation("c-1", null, "AERIS", SEVEN.plus(Duration.ofMinutes(60)),
                SEVEN.plus(Duration.ofMinutes(150)))).map(TableReservationOrder::id).contains(61L);
        // Touching is not crossing: the 19:00 visit ends at 20:30.
        assertThat(reservations.findOverlappingSessionReservation("c-1", null, "AERIS", SEVEN.plus(VISIT),
                SEVEN.plus(VISIT).plus(VISIT))).isEmpty();
        assertThat(reservations.findOverlappingSessionReservation("c-2", null, "AERIS", SEVEN, SEVEN.plus(VISIT))).isEmpty();
    }

    /** A Concierge booking as the tool leaves it: no chat, a visit, and the session's turns moved into the visit. */
    private void conciergeBooking(long orderId, long visitId, TableReservationStatus status, String session) {
        jdbc.update("""
                INSERT INTO table_reservation_orders (id, table_id, status, source, requested_start_at, requested_end_at,
                    party_size, guest_name)
                VALUES (?, 1, ?, 'CONCIERGE', ?, ?, 2, 'Анна')
                """, orderId, status.name(), Timestamp.from(SEVEN), Timestamp.from(SEVEN.plus(VISIT)));
        jdbc.update("INSERT INTO guest_visits (id, reservation_id, venue_code, stage) VALUES (?, ?, 'AERIS', 'REQUESTED')",
                visitId, orderId);
        if (session != null) {
            jdbc.update("INSERT INTO visit_turns (visit_id, session_id, side, channel, kind) "
                    + "VALUES (?, ?, 'CONCIERGE', 'CONCIERGE_TG', 'MESSAGE')", visitId, session);
        }
    }
}
