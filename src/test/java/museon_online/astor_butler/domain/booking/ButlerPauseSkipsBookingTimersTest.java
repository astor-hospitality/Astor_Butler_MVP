package museon_online.astor_butler.domain.booking;

import museon_online.astor_butler.domain.booking.external.ExternalReservationProvider;
import museon_online.astor_butler.domain.booking.external.ExternalReservationStatus;
import museon_online.astor_butler.domain.shift.ButlerPauseService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * While the shift manager has stopped Butler (#134) the booking timers do nothing: no card confirms itself, no guest
 * is asked «Всё в силе?», nothing is read back from Presto. The same tick with Butler running still does its work.
 */
class ButlerPauseSkipsBookingTimersTest {

    private final ButlerPauseService pause = mock(ButlerPauseService.class);
    private final TableReservationRepository repository = mock(TableReservationRepository.class);

    @Test
    void autoConfirmTimerSkipsTheTickWhilePaused() {
        BookingAutoConfirmProperties properties = new BookingAutoConfirmProperties();
        properties.setEnabled(true);
        BookingAutoConfirmPolicy policy = mock(BookingAutoConfirmPolicy.class);
        when(policy.timeoutMinutes()).thenReturn(7);
        BookingAutoConfirmService service = new BookingAutoConfirmService(properties, policy,
                mock(BookingHostessStateStorage.class), repository, mock(TableReservationService.class),
                mock(TableReservationNotificationService.class));
        service.setButlerPause(pause);

        when(pause.paused()).thenReturn(true);
        assertThat(service.run()).isEqualTo(BookingAutoConfirmService.Report.NOTHING);
        verifyNoInteractions(repository);

        when(pause.paused()).thenReturn(false);
        when(repository.findAwaitingManagerConfirmationUnansweredSince(any(), any(), anyInt())).thenReturn(List.of());
        service.run();
        verify(repository).findAwaitingManagerConfirmationUnansweredSince(any(), any(), anyInt());
    }

    @Test
    void morningCheckSkipsTheTickWhilePaused() {
        BookingMorningCheckProperties properties = new BookingMorningCheckProperties(true, "10:30", 90, "AERIS", "Asia/Yekaterinburg");
        Clock afterSendTime = Clock.fixed(Instant.parse("2026-10-12T06:00:00Z"), ZoneOffset.UTC); // 11:00 in Yekaterinburg
        BookingMorningCheckRepository checks = mock(BookingMorningCheckRepository.class);
        BookingMorningCheckService service = new BookingMorningCheckService(properties, repository, checks,
                mock(BookingMorningCheckNotifier.class), afterSendTime);
        service.setButlerPause(pause);

        when(pause.paused()).thenReturn(true);
        assertThat(service.run()).isEqualTo(BookingMorningCheckService.Report.NOTHING);
        verifyNoInteractions(repository, checks);

        when(pause.paused()).thenReturn(false);
        when(repository.findOrdersStartingBetween(any(), any(), any(), anyInt())).thenReturn(List.of());
        when(checks.findByVisitDate(any())).thenReturn(List.of());
        service.run();
        verify(repository).findOrdersStartingBetween(any(), any(), any(), anyInt());
    }

    @Test
    void externalSyncSkipsTheTickWhilePaused() {
        ExternalReservationProvider provider = mock(ExternalReservationProvider.class);
        when(provider.status()).thenReturn(new ExternalReservationStatus("SABY", true, true, List.of(), "READ_WRITE"));
        ExternalBookingSync sync = new ExternalBookingSync(repository, mock(TableReservationService.class), provider,
                mock(museon_online.astor_butler.domain.billing.BillingSync.class));
        sync.setButlerPause(pause);

        when(pause.paused()).thenReturn(true);
        assertThat(sync.run()).isEqualTo(ExternalBookingSync.Report.NOTHING);
        verifyNoInteractions(repository);

        when(pause.paused()).thenReturn(false);
        when(repository.findActiveOrdersWithExternalId(anyInt())).thenReturn(List.of());
        sync.run();
        verify(repository).findActiveOrdersWithExternalId(anyInt());
    }

    @Test
    void withoutTheKillSwitchBeanTheTimersRunAsBefore() {
        BookingAutoConfirmProperties properties = new BookingAutoConfirmProperties();
        properties.setEnabled(true);
        BookingAutoConfirmPolicy policy = mock(BookingAutoConfirmPolicy.class);
        when(policy.timeoutMinutes()).thenReturn(7);
        BookingAutoConfirmService service = new BookingAutoConfirmService(properties, policy,
                mock(BookingHostessStateStorage.class), repository, mock(TableReservationService.class),
                mock(TableReservationNotificationService.class));
        when(repository.findAwaitingManagerConfirmationUnansweredSince(any(), any(), anyInt())).thenReturn(List.of());

        service.run();

        verify(repository).findAwaitingManagerConfirmationUnansweredSince(any(), any(), anyInt());
    }
}
