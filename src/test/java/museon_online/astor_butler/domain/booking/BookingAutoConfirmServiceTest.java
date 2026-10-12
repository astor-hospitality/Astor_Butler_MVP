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

import static museon_online.astor_butler.domain.booking.BookingAutoConfirmPolicy.Decision;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The timer behind the hostess card: confirm by itself, or hand the card to the manager. */
class BookingAutoConfirmServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T15:00:00Z");

    private final BookingAutoConfirmProperties properties = new BookingAutoConfirmProperties();
    private final BookingAutoConfirmPolicy policy = mock(BookingAutoConfirmPolicy.class);
    private final BookingHostessStateStorage storage = mock(BookingHostessStateStorage.class);
    private final TableReservationRepository repository = mock(TableReservationRepository.class);
    private final TableReservationService reservations = mock(TableReservationService.class);
    private final TableReservationNotificationService notifications = mock(TableReservationNotificationService.class);
    private final BookingAutoConfirmService service = new BookingAutoConfirmService(
            properties, policy, storage, repository, reservations, notifications);

    @BeforeEach
    void featureOnSevenMinutes() {
        properties.setEnabled(true);
        properties.setTimeoutMinutes(7);
        service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        service.setHostessChatId("500");
        when(policy.enabled()).thenReturn(true);
        when(policy.timeoutMinutes()).thenReturn(7);
        when(storage.markEscalated(any())).thenReturn(true);
    }

    @Test
    void doesNothingWhileTheFeatureIsOff() {
        properties.setEnabled(false);
        when(policy.enabled()).thenReturn(false);

        BookingAutoConfirmService.Report report = service.run();

        assertThat(report).isEqualTo(BookingAutoConfirmService.Report.NOTHING);
        verifyNoInteractions(repository, reservations, notifications);
    }

    @Test
    void looksForCardsUnansweredForTimeoutMinutesWhoseVisitIsStillAhead() {
        when(repository.findAwaitingManagerConfirmationUnansweredSince(any(), any(), anyInt())).thenReturn(List.of());

        service.run();

        verify(repository).findAwaitingManagerConfirmationUnansweredSince(
                NOW.minus(Duration.ofMinutes(7)), NOW, BookingAutoConfirmService.BATCH);
    }

    @Test
    void confirmsAnUnansweredRequestAfterTheTimeout() {
        TableReservationOrder order = awaiting(7L, 2);
        when(repository.findAwaitingManagerConfirmationUnansweredSince(any(), any(), anyInt())).thenReturn(List.of(order));
        when(policy.decide(order)).thenReturn(Decision.AUTO);

        BookingAutoConfirmService.Report report = service.run();

        verify(reservations).confirmAutomatically(7L, 7);
        assertThat(report.confirmed()).isEqualTo(1);
        verify(notifications, never()).notifyManagerEscalation(any(), any(), anyInt());
    }

    @Test
    void aRequestTheHostessDecidedMeanwhileIsSkippedQuietly() {
        TableReservationOrder order = awaiting(7L, 2);
        when(repository.findAwaitingManagerConfirmationUnansweredSince(any(), any(), anyInt())).thenReturn(List.of(order));
        when(policy.decide(order)).thenReturn(Decision.AUTO);
        when(reservations.confirmAutomatically(7L, 7)).thenThrow(
                new ApiException(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "Only awaiting manager confirmation reservations can be confirmed"));

        BookingAutoConfirmService.Report report = service.run();

        assertThat(report.confirmed()).isZero();
        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.failed()).isZero();
    }

    @Test
    void aLargePartyGoesToTheManagerInsteadOfBeingConfirmed() {
        TableReservationOrder order = awaiting(8L, 8);
        when(repository.findAwaitingManagerConfirmationUnansweredSince(any(), any(), anyInt())).thenReturn(List.of(order));
        when(policy.decide(order)).thenReturn(Decision.LARGE_PARTY);

        BookingAutoConfirmService.Report report = service.run();

        verify(reservations, never()).confirmAutomatically(any(), anyInt());
        verify(storage).markEscalated(8L);
        verify(notifications).notifyManagerEscalation(order, Decision.LARGE_PARTY, 7);
        verify(notifications).notifyHostessEscalated(order, Decision.LARGE_PARTY, 7);
        verify(notifications).notifyGuestEscalatedToManager(order);
        assertThat(report.escalated()).isEqualTo(1);
    }

    @Test
    void inManualModeTheRequestGoesToTheManagerAfterTheTimeout() {
        TableReservationOrder order = awaiting(9L, 2);
        when(repository.findAwaitingManagerConfirmationUnansweredSince(any(), any(), anyInt())).thenReturn(List.of(order));
        when(policy.decide(order)).thenReturn(Decision.MANUAL_MODE);

        service.run();

        verify(reservations, never()).confirmAutomatically(any(), anyInt());
        verify(notifications).notifyManagerEscalation(order, Decision.MANUAL_MODE, 7);
        verify(notifications).notifyGuestEscalatedToManager(order);
    }

    @Test
    void aRequestAlreadyWithTheManagerIsNeitherForwardedTwiceNorConfirmedLater() {
        TableReservationOrder order = awaiting(9L, 2);
        when(repository.findAwaitingManagerConfirmationUnansweredSince(any(), any(), anyInt())).thenReturn(List.of(order));
        when(storage.escalated(9L)).thenReturn(true);
        // Manual mode was switched off meanwhile: the policy would now say AUTO, but the manager already has the card.
        when(policy.decide(order)).thenReturn(Decision.AUTO);

        BookingAutoConfirmService.Report report = service.run();

        verify(reservations, never()).confirmAutomatically(any(), anyInt());
        verify(notifications, never()).notifyManagerEscalation(any(), any(), anyInt());
        assertThat(report.skipped()).isEqualTo(1);
    }

    @Test
    void aRequestPrestoConfirmsIsLeftAlone() {
        TableReservationOrder order = awaiting(10L, 2);
        when(repository.findAwaitingManagerConfirmationUnansweredSince(any(), any(), anyInt())).thenReturn(List.of(order));
        when(policy.decide(order)).thenReturn(Decision.VENUE_SYSTEM);

        service.run();

        verify(reservations, never()).confirmAutomatically(any(), anyInt());
        verifyNoInteractions(notifications);
    }

    @Test
    void oneBrokenOrderDoesNotStopTheSweep() {
        TableReservationOrder broken = awaiting(11L, 2);
        TableReservationOrder fine = awaiting(12L, 2);
        when(repository.findAwaitingManagerConfirmationUnansweredSince(any(), any(), anyInt())).thenReturn(List.of(broken, fine));
        when(policy.decide(broken)).thenThrow(new IllegalStateException("redis down"));
        when(policy.decide(fine)).thenReturn(Decision.AUTO);

        BookingAutoConfirmService.Report report = service.run();

        assertThat(report.failed()).isEqualTo(1);
        verify(reservations).confirmAutomatically(12L, 7);
    }

    @Test
    void warnsAtStartupWhenEnabledWithoutAHostessChat() {
        service.setHostessChatId("");

        assertThat(service.startupWarning()).isPresent();
        assertThat(service.startupWarning().orElseThrow()).contains("TELEGRAM_HOSTESS_CHAT_ID");

        service.setHostessChatId("500");
        assertThat(service.startupWarning()).isEmpty();

        properties.setEnabled(false);
        service.setHostessChatId("");
        assertThat(service.startupWarning()).isEmpty();
    }

    private static TableReservationOrder awaiting(Long id, int partySize) {
        Instant start = NOW.plusSeconds(3600);
        return new TableReservationOrder(id, 1773317437L, 1773317437L, null, 4L, "4", "Стол 4", null, null,
                TableReservationStatus.AWAITING_MANAGER_CONFIRMATION, "TELEGRAM", start, start.plusSeconds(7200),
                partySize, "Иван", "+79990000000", null, 876857557L, null, "500", null,
                NOW.minusSeconds(600), NOW.minusSeconds(600));
    }
}
