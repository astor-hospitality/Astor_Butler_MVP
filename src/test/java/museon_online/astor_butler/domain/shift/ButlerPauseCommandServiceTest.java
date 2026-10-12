package museon_online.astor_butler.domain.shift;

import museon_online.astor_butler.domain.booking.TableReservationNotificationService;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.telegram.adapter.TelegramSystemNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The shift manager's /stop, /start_butler and /status_butler: who may, what is said, what is written down. */
class ButlerPauseCommandServiceTest {

    private static final Instant AT = Instant.parse("2026-10-12T10:40:00Z");

    private final ButlerPauseService pause = mock(ButlerPauseService.class);
    private final ButlerPauseSummaryService summaries = mock(ButlerPauseSummaryService.class);
    private final TableReservationNotificationService notifications = mock(TableReservationNotificationService.class);
    private final TelegramSystemNotifier system = mock(TelegramSystemNotifier.class);
    private final ButlerPauseCommandService service =
            new ButlerPauseCommandService(pause, summaries, notifications, system, "500", "600", "700");

    @BeforeEach
    void featureOnAndRunning() {
        when(pause.enabled()).thenReturn(true);
        when(pause.state()).thenReturn(ButlerPauseState.running());
        when(summaries.summaryText()).thenReturn("SUMMARY");
    }

    @Test
    void stopFromTheManagerChatPausesPostsTheSummaryThereAndInTheStaffChatAndLeavesAnAuditLine() {
        when(pause.stop(anyString())).thenReturn(true);
        when(pause.state()).thenReturn(new ButlerPauseState(true, "Даша (@dasha)", AT));

        boolean handled = service.handle(staff(600L, 42L, "dasha", "/stop"));

        assertThat(handled).isTrue();
        verify(pause).stop("Даша (@dasha)");
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(notifications).sendStaffNotice(eq("600"), text.capture());
        assertThat(text.getValue()).contains("Butler остановлен").contains("Даша (@dasha)").contains("SUMMARY").contains("/start_butler");
        verify(notifications).sendStaffNotice(eq("500"), contains("SUMMARY"));
        verify(system).sendSystemNotice("Butler остановлен менеджером Даша (@dasha)");
    }

    @Test
    void stopFromTheStaffChatItselfIsNotEchoedTwice() {
        when(pause.stop(anyString())).thenReturn(true);

        service.handle(staff(500L, 42L, "dasha", "/стоп"));

        verify(notifications).sendStaffNotice(eq("500"), contains("Butler остановлен"));
        verify(notifications, never()).sendStaffNotice(eq("600"), anyString());
    }

    @Test
    void aSecondStopSaysItIsAlreadyStoppedRepeatsTheSummaryAndWritesNoSecondAuditLine() {
        when(pause.stop(anyString())).thenReturn(false);
        when(pause.state()).thenReturn(new ButlerPauseState(true, "Даша (@dasha)", AT));

        boolean handled = service.handle(staff(700L, 7L, "admin", "/stop@astor_butler_bot"));

        assertThat(handled).isTrue();
        verify(notifications).sendStaffNotice(eq("700"), contains("уже остановлен"));
        verify(notifications).sendStaffNotice(eq("700"), contains("SUMMARY"));
        verify(notifications, never()).sendStaffNotice(eq("500"), anyString());
        verifyNoInteractions(system);
    }

    @Test
    void startButlerResumesAndTellsTheStaffChat() {
        when(pause.resume(anyString())).thenReturn(true);

        assertThat(service.handle(staff(600L, 42L, "dasha", "/start_butler"))).isTrue();

        verify(pause).resume("Даша (@dasha)");
        verify(notifications).sendStaffNotice(eq("600"), contains("Butler снова работает"));
        verify(notifications).sendStaffNotice(eq("500"), contains("Butler снова работает"));
        verify(system).sendSystemNotice(contains("Butler снова работает"));
        verifyNoInteractions(summaries);
    }

    @Test
    void goIsStartButlerAndIsIdempotent() {
        when(pause.resume(anyString())).thenReturn(false);

        assertThat(service.handle(staff(500L, 42L, "dasha", "/go"))).isTrue();

        verify(notifications).sendStaffNotice(eq("500"), contains("и так работает"));
        verifyNoInteractions(system);
    }

    @Test
    void statusShowsPausedOrRunningWithTheSummaryAndChangesNothing() {
        when(pause.state()).thenReturn(new ButlerPauseState(true, "Даша (@dasha)", AT));
        assertThat(service.handle(staff(500L, 42L, "dasha", "/status_butler"))).isTrue();
        verify(notifications).sendStaffNotice(eq("500"), contains("Butler остановлен"));

        when(pause.state()).thenReturn(ButlerPauseState.running());
        assertThat(service.handle(staff(500L, 42L, "dasha", "/status_butler"))).isTrue();
        verify(notifications).sendStaffNotice(eq("500"), contains("Butler работает"));

        verify(pause, never()).stop(anyString());
        verify(pause, never()).resume(anyString());
        verifyNoInteractions(system);
    }

    @Test
    void aGuestOrAnUnknownGroupTypingStopIsNotTheShiftManager() {
        assertThat(service.handle(staff(123L, 42L, "guest", "/stop"))).isFalse();
        assertThat(service.handle(staff(-100999L, 42L, "someone", "/stop"))).isFalse();

        verify(pause, never()).stop(anyString());
        verifyNoInteractions(notifications, system, summaries);
    }

    @Test
    void withTheFeatureOffTheCommandsAreUnknown() {
        when(pause.enabled()).thenReturn(false);

        assertThat(service.handle(staff(600L, 42L, "dasha", "/stop"))).isFalse();
        assertThat(service.handle(staff(600L, 42L, "dasha", "/start_butler"))).isFalse();

        verify(pause, never()).stop(anyString());
        verifyNoInteractions(notifications, system, summaries);
    }

    @Test
    void theGuestsStartAndOtherTextsStayWithTheGuestFlow() {
        assertThat(service.handle(staff(500L, 42L, "dasha", "/start"))).isFalse();
        assertThat(service.handle(staff(500L, 42L, "dasha", "/manual on"))).isFalse();
        assertThat(service.handle(staff(500L, 42L, "dasha", "стоп"))).isFalse();
        assertThat(service.handle(null)).isFalse();

        verifyNoInteractions(notifications, system, summaries);
    }

    @Test
    void aFailedSummaryDoesNotBlockTheStop() {
        when(pause.stop(anyString())).thenReturn(true);
        when(summaries.summaryText()).thenThrow(new IllegalStateException("db down"));

        assertThat(service.handle(staff(600L, 42L, "dasha", "/stop"))).isTrue();

        verify(pause).stop(anyString());
        verify(notifications).sendStaffNotice(eq("600"), contains("Сводку открытых статусов собрать не удалось"));
        verify(system).sendSystemNotice(contains("остановлен менеджером"));
    }

    @Test
    void theNameFallsBackToUsernameThenToTheTelegramId() {
        when(pause.stop(anyString())).thenReturn(true);

        service.handle(IncomingMessage.telegram(600L, 42L, 1, 1, "/stop", null, null, null, "dasha", "ru", false, "c-1"));
        verify(pause).stop("@dasha");

        service.handle(IncomingMessage.telegram(600L, 43L, 1, 1, "/stop", null, " ", null, null, "ru", false, "c-2"));
        verify(pause).stop("id 43");
    }

    private static IncomingMessage staff(Long chatId, Long userId, String username, String text) {
        return IncomingMessage.telegram(chatId, userId, 1, 1, text, null, "Даша", null, username, "ru", false, "c-1");
    }
}
