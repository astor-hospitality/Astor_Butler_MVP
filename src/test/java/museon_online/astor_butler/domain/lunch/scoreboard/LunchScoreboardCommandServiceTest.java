package museon_online.astor_butler.domain.lunch.scoreboard;

import museon_online.astor_butler.domain.booking.TableReservationNotificationService;
import museon_online.astor_butler.domain.shift.ShiftBriefingProperties;
import museon_online.astor_butler.service.message.IncomingMessage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** /табло: the same table now, in the staff chats only, unknown everywhere while the flag is off. */
class LunchScoreboardCommandServiceTest {

    /** 2026-10-12 23:30 in Yekaterinburg: still the 12th there, already the 13th... nowhere, but 18:30 UTC. */
    private static final Instant NOW = Instant.parse("2026-10-12T18:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);

    private final LunchScoreboardService service = mock(LunchScoreboardService.class);
    private final LunchScoreboardFormatter formatter = mock(LunchScoreboardFormatter.class);
    private final TableReservationNotificationService notifications = mock(TableReservationNotificationService.class);
    private final ShiftBriefingProperties venue = new ShiftBriefingProperties(true, "AERIS", "Asia/Yekaterinburg", false, false, false, true);

    private LunchScoreboardCommandService service(boolean enabled) {
        LunchScoreboardCommandService commands = new LunchScoreboardCommandService(new LunchScoreboardProperties(enabled, "16:30"),
                service, formatter, notifications, venue, "500", "600", "700");
        commands.setClock(Clock.fixed(NOW, ZoneId.of("UTC")));
        return commands;
    }

    @Test
    void fromTheStaffChatTheTableOfTodayByTheVenueClockIsPostedThere() {
        LunchScoreboard board = LunchScoreboardFixtures.mixed();
        when(service.build(TODAY)).thenReturn(board);
        when(formatter.format(board)).thenReturn("TABLE");

        assertThat(service(true).handle(staff(500L, "/табло"))).isTrue();

        verify(notifications).sendStaffNotice("500", "TABLE");
    }

    @Test
    void theEnglishSpellingWithTheBotMentionWorksFromTheManagerAndAdminChats() {
        when(service.build(TODAY)).thenReturn(LunchScoreboardFixtures.oneReview());
        when(formatter.format(any())).thenReturn("TABLE");

        assertThat(service(true).handle(staff(600L, "/scoreboard@astor_butler_bot"))).isTrue();
        assertThat(service(true).handle(staff(700L, "/Табло"))).isTrue();

        verify(notifications).sendStaffNotice("600", "TABLE");
        verify(notifications).sendStaffNotice("700", "TABLE");
    }

    @Test
    void yesterdayIsOneDayBack() {
        when(service.build(TODAY.minusDays(1))).thenReturn(LunchScoreboardFixtures.oneReview());
        when(formatter.format(any())).thenReturn("YESTERDAY");

        assertThat(service(true).handle(staff(500L, "/табло вчера"))).isTrue();

        verify(service).build(TODAY.minusDays(1));
        verify(notifications).sendStaffNotice("500", "YESTERDAY");
    }

    @Test
    void aGuestChatDoesNotKnowTheCommand() {
        assertThat(service(true).handle(staff(42L, "/табло"))).isFalse();
        verifyNoInteractions(service, notifications);
    }

    @Test
    void withTheFlagOffTheCommandIsUnknownEvenInTheStaffChat() {
        assertThat(service(false).handle(staff(500L, "/табло"))).isFalse();
        verifyNoInteractions(service, notifications);
    }

    @Test
    void otherTextsAndCommandsAreLeftToTheRest() {
        assertThat(service(true).handle(staff(500L, "/stop"))).isFalse();
        assertThat(service(true).handle(staff(500L, "табло"))).isFalse();
        assertThat(service(true).handle(staff(500L, "/таблofficial"))).isFalse();
        assertThat(service(true).handle(null)).isFalse();
        verifyNoInteractions(service, notifications);
    }

    @Test
    void aFailedBuildAnswersWithAShortErrorInsteadOfSilence() {
        when(service.build(TODAY)).thenThrow(new IllegalStateException("database is away"));

        assertThat(service(true).handle(staff(500L, "/табло"))).isTrue();

        verify(notifications).sendStaffNotice(eq("500"), contains("Табло ланча собрать не удалось (IllegalStateException)"));
    }

    private static IncomingMessage staff(Long chatId, String text) {
        return IncomingMessage.telegram(chatId, 42L, 1, 1, text, null, "Даша", null, "dasha", "ru", false, "corr");
    }
}
