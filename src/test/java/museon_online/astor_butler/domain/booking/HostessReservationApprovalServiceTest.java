package museon_online.astor_butler.domain.booking;

import museon_online.astor_butler.service.message.IncomingMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The hostess buttons under {@code astor.booking.confirmation-source}: who may say "confirmed" to the guest. */
class HostessReservationApprovalServiceTest {

    private final TableReservationService reservations = mock(TableReservationService.class);
    private final TableReservationNotificationService notifications = mock(TableReservationNotificationService.class);
    private final BookingHostessStateStorage storage = mock(BookingHostessStateStorage.class);
    private final BookingAutoConfirmPolicy policy = mock(BookingAutoConfirmPolicy.class);
    private final HostessReservationApprovalService service =
            new HostessReservationApprovalService(reservations, notifications, storage, policy);

    @BeforeEach
    void staffChats() {
        service.setHostessChatId("500");
        service.setManagerChatId("600");
        service.setAdminChatId("700");
        when(policy.enabled()).thenReturn(true);
        when(policy.timeoutMinutes()).thenReturn(7);
        when(storage.manualMode()).thenReturn(HostessManualMode.auto());
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

    @Test
    void managerChatButtonsDecideACardThatWasForwardedThere() {
        TableReservationOrder awaiting = order(7L, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION, null);
        TableReservationOrder confirmed = order(7L, TableReservationStatus.CONFIRMED, null);
        when(reservations.getReservation(7L)).thenReturn(awaiting);
        when(reservations.confirm(7L)).thenReturn(confirmed);

        var result = service.handleCallback("table_booking:confirm:7", 600L);

        assertThat(result.handled()).isTrue();
        verify(reservations).confirm(7L);
        assertThat(service.handleCallback("table_booking:confirm:7", 700L).handled()).isFalse();
    }

    @Test
    void manualOnFromTheHostessChatIsPersistedWithReasonAndWho() {
        boolean handled = service.handle(staff(500L, 42L, "dasha", "/manual on банкет на 40"));

        assertThat(handled).isTrue();
        var saved = forClass(HostessManualMode.class);
        verify(storage).saveManualMode(saved.capture());
        assertThat(saved.getValue().manual()).isTrue();
        assertThat(saved.getValue().reason()).isEqualTo("банкет на 40");
        assertThat(saved.getValue().switchedBy()).isEqualTo("@dasha");
        assertThat(saved.getValue().switchedAt()).isNotNull();
        verify(notifications).sendStaffNotice(eq("500"), contains("Ручной режим включён"));
        verify(notifications).sendStaffNotice(eq("500"), contains("7 мин"));
    }

    @Test
    void manualOnWithoutAReasonMeansOverloaded() {
        service.handle(staff(500L, 42L, null, "/manual on"));

        var saved = forClass(HostessManualMode.class);
        verify(storage).saveManualMode(saved.capture());
        assertThat(saved.getValue().reason()).isEqualTo("перегружены");
        assertThat(saved.getValue().switchedBy()).isEqualTo("id 42");
    }

    @Test
    void manualOffResumesAutoConfirm() {
        boolean handled = service.handle(staff(500L, 42L, "dasha", "/manual off"));

        assertThat(handled).isTrue();
        var saved = forClass(HostessManualMode.class);
        verify(storage).saveManualMode(saved.capture());
        assertThat(saved.getValue().manual()).isFalse();
        assertThat(saved.getValue().switchedBy()).isEqualTo("@dasha");
        verify(notifications).sendStaffNotice(eq("500"), contains("Ручной режим выключен"));
    }

    @Test
    void russianAliasesSwitchTheModeToo() {
        service.handle(staff(500L, 42L, "dasha", "/ручной"));
        service.handle(staff(500L, 42L, "dasha", "/авто"));

        var saved = forClass(HostessManualMode.class);
        verify(storage, org.mockito.Mockito.times(2)).saveManualMode(saved.capture());
        assertThat(saved.getAllValues().get(0).manual()).isTrue();
        assertThat(saved.getAllValues().get(1).manual()).isFalse();
    }

    @Test
    void manualWithoutArgumentsReportsTheCurrentMode() {
        when(storage.manualMode()).thenReturn(
                new HostessManualMode(true, "перегружены", "@dasha", Instant.parse("2026-10-12T15:00:00Z")));

        boolean handled = service.handle(staff(500L, 42L, "dasha", "/manual"));

        assertThat(handled).isTrue();
        verify(storage, never()).saveManualMode(any());
        verify(notifications).sendStaffNotice(eq("500"), contains("перегружены"));
        verify(notifications).sendStaffNotice(eq("500"), contains("@dasha"));
    }

    @Test
    void manualCommandFromAGuestChatIsNotAStaffCommand() {
        boolean handled = service.handle(staff(123L, 42L, "guest", "/manual on"));

        assertThat(handled).isFalse();
        verify(storage, never()).saveManualMode(any());
        verify(notifications, never()).sendStaffNotice(any(), any());
    }

    @Test
    void manualCommandFromTheManagerAndAdminChatsIsAllowed() {
        assertThat(service.handle(staff(600L, 42L, "manager", "/manual on"))).isTrue();
        assertThat(service.handle(staff(700L, 42L, "admin", "/manual off"))).isTrue();

        verify(storage, org.mockito.Mockito.times(2)).saveManualMode(any());
        verify(notifications).sendStaffNotice(eq("600"), contains("Ручной режим включён"));
        verify(notifications).sendStaffNotice(eq("700"), contains("Ручной режим выключен"));
    }

    @Test
    void manualCommandSaysWhenAutoConfirmIsOffInTheConfig() {
        when(policy.enabled()).thenReturn(false);

        service.handle(staff(500L, 42L, "dasha", "/manual on"));

        verify(storage).saveManualMode(any());
        verify(notifications).sendStaffNotice(eq("500"), contains("ASTOR_BOOKING_AUTO_CONFIRM_ENABLED"));
    }

    @Test
    void otherHostessChatTextIsStillSwallowedAndGuestTextIsNot() {
        assertThat(service.handle(staff(500L, 42L, "dasha", "привет"))).isTrue();
        assertThat(service.handle(staff(123L, 42L, "guest", "привет"))).isFalse();
        verify(notifications, never()).sendStaffNotice(any(), any());
    }

    private static IncomingMessage staff(Long chatId, Long userId, String username, String text) {
        return IncomingMessage.telegram(chatId, userId, 1, 1, text, null, "Даша", null, username, "ru", false, "c-1");
    }

    private static TableReservationOrder order(Long id, TableReservationStatus status, String externalId) {
        Instant start = Instant.parse("2026-10-07T14:00:00Z");
        return new TableReservationOrder(id, 1773317437L, 1773317437L, null, 4L, "4", "Стол 4", "WINDOW", null, status,
                "TELEGRAM", start, start.plusSeconds(7200), 2, "Иван", "+79990000000", null, 876857557L, null, "500",
                externalId, start, start);
    }
}
