package museon_online.astor_butler.domain.booking;

import museon_online.astor_butler.api.common.ApiException;
import museon_online.astor_butler.api.common.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The clock behind the table hold (#87): which unanswered requests expire, which are left alone. */
class BookingHoldExpiryServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T15:00:00Z");

    private final BookingHoldExpiryProperties properties = new BookingHoldExpiryProperties();
    private final TableReservationRepository repository = mock(TableReservationRepository.class);
    private final TableReservationService reservations = mock(TableReservationService.class);
    private final BookingHoldExpiryService service = new BookingHoldExpiryService(properties, repository, reservations);

    @BeforeEach
    void featureOnFifteenMinutes() {
        properties.setEnabled(true);
        properties.setTtlMinutes(15);
        service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void doesNothingWhileTheFeatureIsOff() {
        properties.setEnabled(false);

        BookingHoldExpiryService.Report report = service.run();

        assertThat(report).isEqualTo(BookingHoldExpiryService.Report.NOTHING);
        verifyNoInteractions(repository, reservations);
    }

    @Test
    void looksForRequestsUnansweredForTtlMinutesWhoseVisitIsStillAhead() {
        when(repository.findAwaitingManagerConfirmationUnansweredSince(any(), any(), anyInt())).thenReturn(List.of());

        service.run();

        verify(repository).findAwaitingManagerConfirmationUnansweredSince(
                NOW.minus(Duration.ofMinutes(15)), NOW, BookingHoldExpiryService.BATCH);
    }

    @Test
    void theTtlIsNeverShorterThanAMinute() {
        properties.setTtlMinutes(0);
        when(repository.findAwaitingManagerConfirmationUnansweredSince(any(), any(), anyInt())).thenReturn(List.of());

        service.run();

        assertThat(properties.ttlMinutes()).isEqualTo(1);
        verify(repository).findAwaitingManagerConfirmationUnansweredSince(
                NOW.minus(Duration.ofMinutes(1)), NOW, BookingHoldExpiryService.BATCH);
    }

    @Test
    void expiresAnUnansweredRequestAfterTheTtl() {
        TableReservationOrder order = awaiting(7L, null);
        when(repository.findAwaitingManagerConfirmationUnansweredSince(any(), any(), anyInt())).thenReturn(List.of(order));

        BookingHoldExpiryService.Report report = service.run();

        verify(reservations).expireHold(7L, 15);
        assertThat(report).isEqualTo(new BookingHoldExpiryService.Report(1, 1, 0, 0));
    }

    @Test
    void aRequestAlreadyInTheVenueSystemIsLeftToTheVenueSystem() {
        TableReservationOrder order = awaiting(8L, "saby-1");
        when(repository.findAwaitingManagerConfirmationUnansweredSince(any(), any(), anyInt())).thenReturn(List.of(order));

        BookingHoldExpiryService.Report report = service.run();

        verify(reservations, never()).expireHold(any(), anyInt());
        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.expired()).isZero();
    }

    @Test
    void aRequestDecidedMeanwhileIsSkippedQuietly() {
        TableReservationOrder order = awaiting(9L, null);
        when(repository.findAwaitingManagerConfirmationUnansweredSince(any(), any(), anyInt())).thenReturn(List.of(order));
        when(reservations.expireHold(9L, 15)).thenThrow(
                new ApiException(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "Only awaiting manager confirmation reservations can expire"));

        BookingHoldExpiryService.Report report = service.run();

        assertThat(report.expired()).isZero();
        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.failed()).isZero();
    }

    @Test
    void oneBrokenOrderDoesNotStopTheSweep() {
        TableReservationOrder broken = awaiting(11L, null);
        TableReservationOrder fine = awaiting(12L, null);
        when(repository.findAwaitingManagerConfirmationUnansweredSince(any(), any(), anyInt())).thenReturn(List.of(broken, fine));
        when(reservations.expireHold(11L, 15)).thenThrow(new IllegalStateException("database is away"));

        BookingHoldExpiryService.Report report = service.run();

        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.expired()).isEqualTo(1);
        verify(reservations).expireHold(12L, 15);
    }

    private static TableReservationOrder awaiting(Long id, String externalId) {
        Instant start = NOW.plusSeconds(3600);
        return new TableReservationOrder(id, 1773317437L, 1773317437L, null, 4L, "4", "Стол 4", null, null,
                TableReservationStatus.AWAITING_MANAGER_CONFIRMATION, "TELEGRAM", start, start.plusSeconds(7200),
                2, "Иван", "+79990000000", null, 876857557L, null, "500", externalId,
                NOW.minusSeconds(1200), NOW.minusSeconds(1200));
    }
}
