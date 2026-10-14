package museon_online.astor_butler.domain.booking;

import museon_online.astor_butler.api.common.ApiException;
import museon_online.astor_butler.domain.booking.external.ExternalAvailabilityResult;
import museon_online.astor_butler.domain.booking.external.ExternalReservationProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * «Гость сел» on the real SQL (#151): the column of the migration, the one-shot update, and what the other clocks and
 * queries make of a SEATED booking — it keeps its table, never expires, is never auto-confirmed, may still be moved.
 */
class BookingSeatedJdbcTest {

    private static final Instant NOW = Instant.parse("2026-10-12T09:00:00Z");
    private static final Instant VISIT_START = NOW.minus(Duration.ofMinutes(10));
    private static final Instant VISIT_END = NOW.plus(Duration.ofMinutes(110));
    private static final long TABLE_4 = 1L;
    private static final long TABLE_5 = 2L;
    private static final long GUEST = 1001L;

    private final JdbcTemplate jdbc = TableReservationTestDatabase.create();
    private final TableReservationRepository repository = new TableReservationRepository(jdbc);
    private final TableReservationNotificationService notifications = mock(TableReservationNotificationService.class);
    private final ExternalReservationProvider externalProvider = mock(ExternalReservationProvider.class);
    private final TableReservationService reservations = new TableReservationService(repository, notifications, externalProvider);

    @BeforeEach
    void venue() {
        TableReservationTestDatabase.table(jdbc, TABLE_4, "4", "MAIN_HALL", 5);
        TableReservationTestDatabase.table(jdbc, TABLE_5, "5", "MAIN_HALL", 4);
        when(externalProvider.providerId()).thenReturn("SABY");
        when(externalProvider.checkAvailability(any()))
                .thenReturn(ExternalAvailabilityResult.unavailableBecauseUnconfigured("SABY", List.of()));
    }

    @Test
    void theMigrationAddsSeatedAtAndTheMapperReadsIt() {
        confirmed(51L, TABLE_4, null);
        assertThat(repository.findOrder(51L)).hasValueSatisfying(order -> assertThat(order.seatedAt()).isNull());

        TableReservationTestDatabase.seated(jdbc, 51L, NOW);

        assertThat(repository.findOrder(51L)).hasValueSatisfying(order -> {
            assertThat(order.status()).isEqualTo(TableReservationStatus.SEATED);
            assertThat(order.seatedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void theButtonSeatsAConfirmedBookingOnceAndTheTableStaysHeld() {
        confirmed(52L, TABLE_4, null);

        TableReservationOrder seated = reservations.seatByStaff(52L, "@dasha");

        assertThat(seated.status()).isEqualTo(TableReservationStatus.SEATED);
        assertThat(seated.seatedAt()).isNotNull();
        assertThat(TableReservationTestDatabase.holdStatus(jdbc, 52L)).isEqualTo("CONFIRMED");
        assertThat(repository.hasActiveConflict(TABLE_4, VISIT_START, VISIT_END)).isTrue();
        assertThat(repository.findAvailableTables("AERIS", VISIT_START, VISIT_END, 2))
                .extracting(VenueTable::tableCode).containsExactly("5");
        verify(notifications).traceSeated(seated, "@dasha");
        verify(notifications, never()).notifyHostessSeatedByVenue(any());

        // The second press: the same answer, no second stamp, no second trace.
        TableReservationOrder again = reservations.seatByStaff(52L, "@dasha");

        assertThat(again).isEqualTo(seated);
        assertThat(repository.seatIfConfirmed(52L)).isFalse();
        verify(notifications, times(1)).traceSeated(any(), anyString());
    }

    @Test
    void onlyAConfirmedBookingCanBeSeated() {
        awaiting(53L, TABLE_4, null);
        TableReservationTestDatabase.order(jdbc, 54L, GUEST, TABLE_5, TableReservationStatus.CANCELLED,
                VISIT_START, VISIT_END, null, NOW.minus(Duration.ofHours(1)));

        assertThatThrownBy(() -> reservations.seatByStaff(53L, "@dasha")).isInstanceOf(ApiException.class)
                .hasMessageContaining("Only confirmed reservations can be seated");
        assertThatThrownBy(() -> reservations.seatByStaff(54L, "@dasha")).isInstanceOf(ApiException.class);
        assertThat(repository.seatIfConfirmed(53L)).isFalse();
        assertThat(repository.findOrder(53L)).hasValueSatisfying(order ->
                assertThat(order.status()).isEqualTo(TableReservationStatus.AWAITING_MANAGER_CONFIRMATION));
        assertThat(TableReservationTestDatabase.holdStatus(jdbc, 53L)).isEqualTo("HELD");
        verifyNoInteractions(notifications);
    }

    @Test
    void prestoSeatsAConfirmedBookingOnceAndTheHostessHearsOnce() {
        confirmed(55L, TABLE_4, "saby-55");

        TableReservationOrder seated = reservations.seatFromVenue(55L);

        assertThat(seated.status()).isEqualTo(TableReservationStatus.SEATED);
        verify(notifications).notifyHostessSeatedByVenue(seated);
        verify(notifications).traceSeated(seated, "Presto");

        assertThat(reservations.seatFromVenue(55L)).isEqualTo(seated);
        verify(notifications, times(1)).notifyHostessSeatedByVenue(any());
        verify(notifications, times(1)).traceSeated(any(), anyString());

        // An awaiting booking is not seated from here: the sync confirms it first on its own read.
        awaiting(56L, TABLE_5, "saby-56");
        assertThat(reservations.seatFromVenue(56L).status()).isEqualTo(TableReservationStatus.AWAITING_MANAGER_CONFIRMATION);
    }

    @Test
    void aSeatedBookingNeverExpiresAndIsNeverConfirmedAgain() {
        confirmed(57L, TABLE_4, null);
        reservations.seatByStaff(57L, "@dasha");
        // Old enough for both clocks: the card went out an hour ago.
        jdbc.update("UPDATE table_reservation_orders SET updated_at = ? WHERE id = ?",
                java.sql.Timestamp.from(NOW.minus(Duration.ofHours(1))), 57L);

        assertThat(repository.findAwaitingManagerConfirmationUnansweredSince(NOW.minus(Duration.ofMinutes(7)), NOW, 100)).isEmpty();

        BookingHoldExpiryProperties expiry = new BookingHoldExpiryProperties();
        expiry.setEnabled(true);
        BookingHoldExpiryService holdExpiry = new BookingHoldExpiryService(expiry, repository, reservations);
        holdExpiry.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(holdExpiry.run()).isEqualTo(BookingHoldExpiryService.Report.NOTHING);

        BookingAutoConfirmProperties autoConfirm = new BookingAutoConfirmProperties();
        autoConfirm.setEnabled(true);
        BookingAutoConfirmPolicy policy = mock(BookingAutoConfirmPolicy.class);
        when(policy.timeoutMinutes()).thenReturn(7);
        BookingAutoConfirmService confirmTimer = new BookingAutoConfirmService(
                autoConfirm, policy, mock(BookingHostessStateStorage.class), repository, reservations, notifications);
        confirmTimer.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(confirmTimer.run()).isEqualTo(BookingAutoConfirmService.Report.NOTHING);

        assertThatThrownBy(() -> reservations.confirmAutomatically(57L, 7)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> reservations.expireHold(57L, 15)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> reservations.confirm(57L)).isInstanceOf(ApiException.class);
        assertThat(repository.findOrder(57L)).hasValueSatisfying(order ->
                assertThat(order.status()).isEqualTo(TableReservationStatus.SEATED));
        assertThat(TableReservationTestDatabase.holdStatus(jdbc, 57L)).isEqualTo("CONFIRMED");
        verify(notifications, never()).notifyGuestConfirmed(any());
        verify(notifications, never()).notifyGuestHoldExpired(any(), eq(15));
    }

    @Test
    void aSeatedBookingIsStillTheGuestsLiveBookingAndStillReadBackFromPresto() {
        confirmed(58L, TABLE_4, "saby-58");
        reservations.seatByStaff(58L, "@dasha");

        assertThat(repository.findActiveOrdersByChatId(GUEST)).extracting(TableReservationOrder::id).containsExactly(58L);
        assertThat(repository.findActiveOrdersByChatId(GUEST, "AERIS")).extracting(TableReservationOrder::id).containsExactly(58L);
        assertThat(repository.findActiveOrdersWithExternalId(10)).extracting(TableReservationOrder::id).containsExactly(58L);
        assertThat(reservations.findOverlappingReservation(GUEST, "AERIS", VISIT_START.plusSeconds(600), VISIT_END.plusSeconds(600)))
                .hasValueSatisfying(order -> assertThat(order.id()).isEqualTo(58L));
    }

    @Test
    void theHostessMayStillMoveASeatedGuestAndTheBookingStaysSeated() {
        confirmed(59L, TABLE_4, null);
        reservations.seatByStaff(59L, "@dasha");
        TableReservationOrder seated = repository.findOrder(59L).orElseThrow();

        TableReservationOrder moved = reservations.changeByStaff(59L,
                new TableReservationChangeCommand("AERIS", "5", null, null, null, null, null, null), "@dasha");

        assertThat(moved.status()).isEqualTo(TableReservationStatus.SEATED);
        assertThat(moved.seatedAt()).isEqualTo(seated.seatedAt());
        assertThat(moved.tableCode()).isEqualTo("5");
        assertThat(TableReservationTestDatabase.holdStatus(jdbc, 59L)).isEqualTo("CONFIRMED");
        assertThat(repository.hasActiveConflict(TABLE_5, VISIT_START, VISIT_END)).isTrue();
        assertThat(repository.hasActiveConflict(TABLE_4, VISIT_START, VISIT_END)).isFalse();
        verify(notifications, never()).notifyGuestMovedByStaff(any(), any());
    }

    @Test
    void aSeatedBookingCancelledInPrestoOrByTheGuestReleasesTheTable() {
        confirmed(60L, TABLE_4, "saby-60");
        reservations.seatByStaff(60L, "@dasha");

        TableReservationOrder cancelled = reservations.cancelFromVenue(60L);

        assertThat(cancelled.status()).isEqualTo(TableReservationStatus.CANCELLED);
        assertThat(TableReservationTestDatabase.holdStatus(jdbc, 60L)).isEqualTo("RELEASED");
        assertThat(repository.hasActiveConflict(TABLE_4, VISIT_START, VISIT_END)).isFalse();
        verify(notifications).notifyGuestRejected(eq(cancelled), any());

        confirmed(61L, TABLE_5, null);
        reservations.seatByStaff(61L, "@dasha");
        assertThat(reservations.cancelByGuest(61L).status()).isEqualTo(TableReservationStatus.CANCELLED);
        assertThat(TableReservationTestDatabase.holdStatus(jdbc, 61L)).isEqualTo("RELEASED");
    }

    @Test
    void aConciergeGuestWhoSatDownIsAnnouncedAndATelegramGuestIsNot() {
        confirmed(62L, TABLE_4, null);
        confirmed(63L, TABLE_5, null);
        // A Concierge booking has no Butler chat (the production column is nullable since the Concierge channel).
        jdbc.execute("ALTER TABLE table_reservation_orders ALTER COLUMN chat_id SET NULL");
        jdbc.update("UPDATE table_reservation_orders SET chat_id = NULL, telegram_user_id = NULL, source = 'CONCIERGE' WHERE id = 62");

        TableReservationOrder concierge = reservations.seatByStaff(62L, "@dasha");
        reservations.seatByStaff(63L, "@dasha");

        verify(notifications).notifyGuestSeated(concierge);
        verify(notifications, times(1)).notifyGuestSeated(any());
    }

    private void confirmed(long id, long tableId, String externalId) {
        TableReservationTestDatabase.order(jdbc, id, GUEST, tableId, TableReservationStatus.CONFIRMED,
                VISIT_START, VISIT_END, externalId, NOW.minus(Duration.ofHours(2)));
        TableReservationTestDatabase.hold(jdbc, id, tableId, "CONFIRMED", VISIT_START, VISIT_END);
    }

    private void awaiting(long id, long tableId, String externalId) {
        TableReservationTestDatabase.order(jdbc, id, GUEST, tableId, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION,
                VISIT_START, VISIT_END, externalId, NOW.minus(Duration.ofMinutes(5)));
        TableReservationTestDatabase.hold(jdbc, id, tableId, "HELD", VISIT_START, VISIT_END);
    }
}
