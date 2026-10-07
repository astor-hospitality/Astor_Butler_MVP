package museon_online.astor_butler.domain.booking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The hostess buttons under {@code astor.booking.confirmation-source}: who may say "confirmed" to the guest. */
class HostessReservationApprovalServiceTest {

    private final TableReservationService reservations = mock(TableReservationService.class);
    private final TableReservationNotificationService notifications = mock(TableReservationNotificationService.class);
    private final HostessReservationApprovalService service = new HostessReservationApprovalService(reservations, notifications);

    @BeforeEach
    void hostessChat() {
        service.setHostessChatId("500");
    }

    @Test
    void hostessConfirmsAsBeforeWhenSheIsTheSource() {
        TableReservationOrder awaiting = order(7L, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION, "saby-7");
        TableReservationOrder confirmed = order(7L, TableReservationStatus.CONFIRMED, "saby-7");
        when(reservations.getReservation(7L)).thenReturn(awaiting);
        when(reservations.confirm(7L)).thenReturn(confirmed);

        var result = service.handleCallback("table_booking:confirm:7", 500L);

        assertThat(result.handled()).isTrue();
        assertThat(result.answerText()).isEqualTo("Бронь #7 подтверждена");
        verify(reservations).confirm(7L);
        verify(notifications).notifyHostessAcknowledged(confirmed);
    }

    @Test
    void withTheVenueSystemAsSourceTheButtonOnlyPointsToPresto() {
        service.setConfirmationSource(BookingConfirmationSource.VENUE_SYSTEM);
        TableReservationOrder awaiting = order(8L, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION, "saby-8");
        when(reservations.getReservation(8L)).thenReturn(awaiting);

        var result = service.handleCallback("table_booking:confirm:8", 500L);

        assertThat(result.handled()).isTrue();
        assertThat(result.answerText()).contains("подтверждается в Presto");
        verify(reservations, never()).confirm(any());
        verify(notifications, never()).notifyHostessAcknowledged(any());
    }

    @Test
    void withTheVenueSystemAsSourceAnOrderPrestoDoesNotKnowIsStillConfirmedByTheHostess() {
        service.setConfirmationSource(BookingConfirmationSource.VENUE_SYSTEM);
        TableReservationOrder awaiting = order(9L, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION, null);
        TableReservationOrder confirmed = order(9L, TableReservationStatus.CONFIRMED, null);
        when(reservations.getReservation(9L)).thenReturn(awaiting);
        when(reservations.confirm(9L)).thenReturn(confirmed);

        var result = service.handleCallback("table_booking:confirm:9", 500L);

        assertThat(result.answerText()).isEqualTo("Бронь #9 подтверждена");
        verify(reservations).confirm(9L);
    }

    @Test
    void rejectStaysWithTheHostessUnderBothSources() {
        service.setConfirmationSource(BookingConfirmationSource.VENUE_SYSTEM);
        TableReservationOrder awaiting = order(10L, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION, "saby-10");
        TableReservationOrder rejected = order(10L, TableReservationStatus.REJECTED, "saby-10");
        when(reservations.getReservation(10L)).thenReturn(awaiting);
        when(reservations.reject(10L)).thenReturn(rejected);

        var result = service.handleCallback("table_booking:reject:10", 500L);

        assertThat(result.answerText()).isEqualTo("Бронь #10 отменена");
        verify(reservations).reject(10L);
        verify(notifications).notifyHostessRejected(rejected);
    }

    @Test
    void otherChatsAndOtherCallbacksAreNotHandled() {
        assertThat(service.handleCallback("table_booking:confirm:7", 501L).handled()).isFalse();
        assertThat(service.handleCallback("something:else", 500L).handled()).isFalse();
    }

    private static TableReservationOrder order(Long id, TableReservationStatus status, String externalId) {
        Instant start = Instant.parse("2026-10-07T14:00:00Z");
        return new TableReservationOrder(id, 1773317437L, 1773317437L, null, 4L, "4", "Стол 4", "WINDOW", null, status,
                "TELEGRAM", start, start.plusSeconds(7200), 2, "Иван", "+79990000000", null, 876857557L, null, "500",
                externalId, start, start);
    }
}
