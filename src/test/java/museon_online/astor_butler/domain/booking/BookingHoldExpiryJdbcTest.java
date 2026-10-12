package museon_online.astor_butler.domain.booking;

import museon_online.astor_butler.api.common.ApiException;
import museon_online.astor_butler.domain.booking.external.ExternalReservationProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The whole path on the real SQL (#87): an unanswered request expires after the TTL, its table is free again for the
 * next request, nothing is sent twice, and the manager may still say yes or no to the expired card.
 */
class BookingHoldExpiryJdbcTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");
    private static final Instant VISIT_START = NOW.plus(Duration.ofHours(3));
    private static final Instant VISIT_END = NOW.plus(Duration.ofHours(5));
    private static final long TABLE_4 = 1L;
    private static final long TABLE_5 = 2L;
    private static final long GUEST = 1001L;

    private final JdbcTemplate jdbc = TableReservationTestDatabase.create();
    private final TableReservationRepository repository = new TableReservationRepository(jdbc);
    private final TableReservationNotificationService notifications = mock(TableReservationNotificationService.class);
    private final ExternalReservationProvider externalProvider = mock(ExternalReservationProvider.class);
    private final TableReservationService reservations = new TableReservationService(repository, notifications, externalProvider);
    private final BookingHoldExpiryProperties properties = new BookingHoldExpiryProperties();
    private final BookingHoldExpiryService service = new BookingHoldExpiryService(properties, repository, reservations);

    @BeforeEach
    void venueAndClock() {
        TableReservationTestDatabase.table(jdbc, TABLE_4, "4", "MAIN_HALL", 5);
        TableReservationTestDatabase.table(jdbc, TABLE_5, "5", "MAIN_HALL", 4);
        properties.setEnabled(true);
        properties.setTtlMinutes(15);
        service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void anUnansweredRequestExpiresAfterTheTtlAndFreesTheTableOnce() {
        awaiting(41L, TABLE_4, null, NOW.minus(Duration.ofMinutes(20)));
        // Today: the table is held and a new request for the same time gets 409.
        assertThat(repository.hasActiveConflict(TABLE_4, VISIT_START, VISIT_END)).isTrue();
        assertThat(repository.findAvailableTables("AERIS", VISIT_START, VISIT_END, 2))
                .extracting(VenueTable::tableCode).containsExactly("5");

        BookingHoldExpiryService.Report report = service.run();

        assertThat(report).isEqualTo(new BookingHoldExpiryService.Report(1, 1, 0, 0));
        assertThat(repository.findOrder(41L)).hasValueSatisfying(order ->
                assertThat(order.status()).isEqualTo(TableReservationStatus.EXPIRED));
        assertThat(TableReservationTestDatabase.holdStatus(jdbc, 41L)).isEqualTo("RELEASED");
        // The slot is free: the next request for table 4 at that time no longer gets 409.
        assertThat(repository.hasActiveConflict(TABLE_4, VISIT_START, VISIT_END)).isFalse();
        assertThat(repository.findAvailableTables("AERIS", VISIT_START, VISIT_END, 2))
                .extracting(VenueTable::tableCode).containsExactly("4", "5");
        verify(notifications).notifyHostessHoldExpired(any(), eq(15));
        verify(notifications).notifyManagerHoldExpired(any(), eq(15));
        verify(notifications).notifyGuestHoldExpired(any(), eq(15));

        // The second tick finds nothing: no second message to anyone.
        BookingHoldExpiryService.Report again = service.run();

        assertThat(again).isEqualTo(BookingHoldExpiryService.Report.NOTHING);
        verify(notifications, times(1)).notifyGuestHoldExpired(any(), anyInt());
        verify(notifications, times(1)).notifyManagerHoldExpired(any(), anyInt());
        verify(notifications, times(1)).notifyHostessHoldExpired(any(), anyInt());
    }

    @Test
    void aRequestTheHostessStillHasTimeForWaits() {
        awaiting(42L, TABLE_4, null, NOW.minus(Duration.ofMinutes(5)));

        BookingHoldExpiryService.Report report = service.run();

        assertThat(report).isEqualTo(BookingHoldExpiryService.Report.NOTHING);
        assertThat(repository.findOrder(42L)).hasValueSatisfying(order ->
                assertThat(order.status()).isEqualTo(TableReservationStatus.AWAITING_MANAGER_CONFIRMATION));
        assertThat(TableReservationTestDatabase.holdStatus(jdbc, 42L)).isEqualTo("HELD");
        verifyNoInteractions(notifications);
    }

    @Test
    void aConfirmedBookingNeverExpires() {
        TableReservationTestDatabase.order(jdbc, 43L, GUEST, TABLE_4, TableReservationStatus.CONFIRMED,
                VISIT_START, VISIT_END, null, NOW.minus(Duration.ofHours(2)));
        TableReservationTestDatabase.hold(jdbc, 43L, TABLE_4, "CONFIRMED", VISIT_START, VISIT_END);

        BookingHoldExpiryService.Report report = service.run();

        assertThat(report).isEqualTo(BookingHoldExpiryService.Report.NOTHING);
        assertThat(repository.findOrder(43L)).hasValueSatisfying(order ->
                assertThat(order.status()).isEqualTo(TableReservationStatus.CONFIRMED));
        assertThat(TableReservationTestDatabase.holdStatus(jdbc, 43L)).isEqualTo("CONFIRMED");
        assertThat(repository.hasActiveConflict(TABLE_4, VISIT_START, VISIT_END)).isTrue();
        verifyNoInteractions(notifications);
    }

    @Test
    void aRequestAlreadyInTheVenueSystemNeverExpiresHere() {
        awaiting(44L, TABLE_4, "saby-44", NOW.minus(Duration.ofHours(1)));

        BookingHoldExpiryService.Report report = service.run();

        assertThat(report).isEqualTo(new BookingHoldExpiryService.Report(1, 0, 1, 0));
        assertThat(repository.findOrder(44L)).hasValueSatisfying(order ->
                assertThat(order.status()).isEqualTo(TableReservationStatus.AWAITING_MANAGER_CONFIRMATION));
        assertThat(TableReservationTestDatabase.holdStatus(jdbc, 44L)).isEqualTo("HELD");
        verifyNoInteractions(notifications);
    }

    @Test
    void aRequestWhoseVisitIsOverIsNotTouched() {
        TableReservationTestDatabase.order(jdbc, 45L, GUEST, TABLE_4, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION,
                NOW.minus(Duration.ofHours(5)), NOW.minus(Duration.ofHours(3)), null, NOW.minus(Duration.ofHours(6)));
        TableReservationTestDatabase.hold(jdbc, 45L, TABLE_4, "HELD", NOW.minus(Duration.ofHours(5)), NOW.minus(Duration.ofHours(3)));

        assertThat(service.run()).isEqualTo(BookingHoldExpiryService.Report.NOTHING);
        verifyNoInteractions(notifications);
    }

    @Test
    void nothingHappensWhileTheFeatureIsOff() {
        awaiting(46L, TABLE_4, null, NOW.minus(Duration.ofHours(1)));
        properties.setEnabled(false);

        assertThat(service.run()).isEqualTo(BookingHoldExpiryService.Report.NOTHING);

        assertThat(repository.findOrder(46L)).hasValueSatisfying(order ->
                assertThat(order.status()).isEqualTo(TableReservationStatus.AWAITING_MANAGER_CONFIRMATION));
        assertThat(TableReservationTestDatabase.holdStatus(jdbc, 46L)).isEqualTo("HELD");
        assertThat(repository.hasActiveConflict(TABLE_4, VISIT_START, VISIT_END)).isTrue();
        verifyNoInteractions(notifications);
    }

    @Test
    void theManagerMayStillSayYesWhileTheTableIsFree() {
        awaiting(47L, TABLE_4, null, NOW.minus(Duration.ofMinutes(20)));
        service.run();
        TableReservationOrder expired = repository.findOrder(47L).orElseThrow();
        assertThat(reservations.tableStillFree(expired)).isTrue();

        TableReservationOrder confirmed = reservations.confirm(47L);

        assertThat(confirmed.status()).isEqualTo(TableReservationStatus.CONFIRMED);
        assertThat(TableReservationTestDatabase.holdStatus(jdbc, 47L)).isEqualTo("CONFIRMED");
        assertThat(TableReservationTestDatabase.holds(jdbc, 47L)).isEqualTo(2);
        assertThat(repository.hasActiveConflict(TABLE_4, VISIT_START, VISIT_END)).isTrue();
        verify(notifications).notifyGuestConfirmed(confirmed);
        verify(notifications).notifyHostessConfirmed(confirmed);
        // A second «Да» is the usual "already confirmed", not a third hold.
        assertThatThrownBy(() -> reservations.confirm(47L)).isInstanceOf(ApiException.class);
        assertThat(TableReservationTestDatabase.holds(jdbc, 47L)).isEqualTo(2);
    }

    @Test
    void theManagerYesIsRefusedOnceAnotherGuestTookTheTable() {
        awaiting(48L, TABLE_4, null, NOW.minus(Duration.ofMinutes(20)));
        service.run();
        // The slot was free, so another guest's request took table 4 for the same time.
        awaiting(49L, TABLE_4, null, NOW.minus(Duration.ofMinutes(1)));
        TableReservationOrder expired = repository.findOrder(48L).orElseThrow();

        assertThat(reservations.tableStillFree(expired)).isFalse();
        assertThatThrownBy(() -> reservations.confirm(48L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("active hold");
        assertThat(repository.findOrder(48L)).hasValueSatisfying(order ->
                assertThat(order.status()).isEqualTo(TableReservationStatus.EXPIRED));
        assertThat(TableReservationTestDatabase.holds(jdbc, 48L)).isEqualTo(1);
        verify(notifications, never()).notifyGuestConfirmed(any());
    }

    @Test
    void theManagerMayStillSayNoToTheExpiredRequest() {
        awaiting(50L, TABLE_4, null, NOW.minus(Duration.ofMinutes(20)));
        service.run();

        TableReservationOrder rejected = reservations.reject(50L);

        assertThat(rejected.status()).isEqualTo(TableReservationStatus.REJECTED);
        assertThat(repository.hasActiveConflict(TABLE_4, VISIT_START, VISIT_END)).isFalse();
        verify(notifications).notifyGuestRejected(eq(rejected), any());
        verify(externalProvider, never()).cancelReservation(any());
    }

    private void awaiting(long id, long tableId, String externalId, Instant cardSentAt) {
        TableReservationTestDatabase.order(jdbc, id, GUEST, tableId, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION,
                VISIT_START, VISIT_END, externalId, cardSentAt);
        TableReservationTestDatabase.hold(jdbc, id, tableId, "HELD", VISIT_START, VISIT_END);
    }
}
