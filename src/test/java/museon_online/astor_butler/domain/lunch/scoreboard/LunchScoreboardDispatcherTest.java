package museon_online.astor_butler.domain.lunch.scoreboard;

import museon_online.astor_butler.domain.booking.TableReservationNotificationService;
import museon_online.astor_butler.domain.lunch.BusinessLunchCatalog;
import museon_online.astor_butler.domain.lunch.BusinessLunchOffer;
import museon_online.astor_butler.domain.shift.ShiftBriefingProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Once a lunch day, after send-at by the venue clock, to the staff chat; the day is claimed before anything is built. */
class LunchScoreboardDispatcherTest {

    private static final ZoneId VENUE = ZoneId.of("Asia/Yekaterinburg");
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);
    private static final LocalDate SATURDAY = LocalDate.of(2026, 10, 17);

    private final LunchScoreboardService service = mock(LunchScoreboardService.class);
    private final LunchScoreboardFormatter formatter = mock(LunchScoreboardFormatter.class);
    private final LunchScoreboardSentMarker marker = mock(LunchScoreboardSentMarker.class);
    private final TableReservationNotificationService notifications = mock(TableReservationNotificationService.class);
    private final ShiftBriefingProperties venue = new ShiftBriefingProperties(true, "AERIS", "Asia/Yekaterinburg", false, false, false, true);

    private LunchScoreboardDispatcher dispatcher(boolean enabled, String staffChat, BusinessLunchCatalog catalog, LocalDate day, LocalTime venueTime) {
        LunchScoreboardDispatcher dispatcher = new LunchScoreboardDispatcher(new LunchScoreboardProperties(enabled, "16:30"),
                service, formatter, marker, notifications, catalog, venue, staffChat);
        dispatcher.setClock(Clock.fixed(ZonedDateTime.of(day, venueTime, VENUE).toInstant(), VENUE));
        return dispatcher;
    }

    private LunchScoreboardDispatcher dispatcher(LocalDate day, LocalTime venueTime) {
        return dispatcher(true, "500", new BusinessLunchCatalog(List.of()), day, venueTime);
    }

    @Test
    void offMeansNothingAtAll() {
        assertThat(dispatcher(false, "500", new BusinessLunchCatalog(List.of()), MONDAY, LocalTime.of(17, 0)).run())
                .isEqualTo(LunchScoreboardDispatcher.Outcome.OFF);
        verifyNoInteractions(service, marker, notifications);
    }

    @Test
    void beforeSendAtNothingIsClaimedOrSent() {
        assertThat(dispatcher(MONDAY, LocalTime.of(16, 29)).run()).isEqualTo(LunchScoreboardDispatcher.Outcome.NOT_YET);
        verifyNoInteractions(service, marker, notifications);
    }

    @Test
    void theVenueClockDecidesNotTheServerClock() {
        // 11:31 UTC is 16:31 in Yekaterinburg: past send-at even though a UTC clock says otherwise.
        LunchScoreboardDispatcher dispatcher = dispatcher(MONDAY, LocalTime.of(16, 31));
        dispatcher.setClock(Clock.fixed(Instant.parse("2026-10-12T11:31:00Z"), ZoneId.of("UTC")));
        when(marker.claim(MONDAY)).thenReturn(true);
        LunchScoreboard board = LunchScoreboardFixtures.oneReview();
        when(service.build(MONDAY)).thenReturn(board);
        when(formatter.format(board)).thenReturn("TABLE");

        assertThat(dispatcher.run()).isEqualTo(LunchScoreboardDispatcher.Outcome.SENT);

        verify(notifications).sendStaffNotice("500", "TABLE");
    }

    @Test
    void afterSendAtTheDayIsClaimedOnceAndSentToTheStaffChat() {
        LunchScoreboardDispatcher dispatcher = dispatcher(MONDAY, LocalTime.of(16, 30));
        when(marker.claim(MONDAY)).thenReturn(true, false);
        LunchScoreboard board = LunchScoreboardFixtures.mixed();
        when(service.build(MONDAY)).thenReturn(board);
        when(formatter.format(board)).thenReturn("TABLE");

        assertThat(dispatcher.run()).isEqualTo(LunchScoreboardDispatcher.Outcome.SENT);
        assertThat(dispatcher.run()).isEqualTo(LunchScoreboardDispatcher.Outcome.ALREADY_SENT);
        assertThat(dispatcher.run()).isEqualTo(LunchScoreboardDispatcher.Outcome.ALREADY_SENT);

        verify(service, org.mockito.Mockito.times(1)).build(MONDAY);
        verify(notifications, org.mockito.Mockito.times(1)).sendStaffNotice("500", "TABLE");
    }

    @Test
    void anEmptyDayIsStillSentOnce() {
        LunchScoreboardDispatcher dispatcher = dispatcher(MONDAY, LocalTime.of(18, 0));
        when(marker.claim(MONDAY)).thenReturn(true);
        when(service.build(MONDAY)).thenReturn(LunchScoreboardFixtures.emptyDay());
        when(formatter.format(any())).thenReturn("EMPTY");

        assertThat(dispatcher.run()).isEqualTo(LunchScoreboardDispatcher.Outcome.SENT);
        verify(notifications).sendStaffNotice("500", "EMPTY");
    }

    @Test
    void aWeekendIsNotALunchDayUnlessTheMenuFileSaysSo() {
        assertThat(dispatcher(SATURDAY, LocalTime.of(17, 0)).run()).isEqualTo(LunchScoreboardDispatcher.Outcome.NOT_A_LUNCH_DAY);
        verifyNoInteractions(marker, notifications);

        BusinessLunchCatalog sevenDays = new BusinessLunchCatalog(List.of(offer(List.of(DayOfWeek.values()))));
        when(marker.claim(SATURDAY)).thenReturn(true);
        when(service.build(SATURDAY)).thenReturn(LunchScoreboardFixtures.emptyDay());
        when(formatter.format(any())).thenReturn("EMPTY");
        assertThat(dispatcher(true, "500", sevenDays, SATURDAY, LocalTime.of(17, 0)).run()).isEqualTo(LunchScoreboardDispatcher.Outcome.SENT);

        BusinessLunchCatalog mondayOnly = new BusinessLunchCatalog(List.of(offer(List.of(DayOfWeek.MONDAY))));
        assertThat(dispatcher(true, "500", mondayOnly, MONDAY.plusDays(1), LocalTime.of(17, 0)).run())
                .isEqualTo(LunchScoreboardDispatcher.Outcome.NOT_A_LUNCH_DAY);
    }

    @Test
    void withoutAStaffChatNothingIsClaimedSoALaterConfigurationStillSendsThatDay() {
        assertThat(dispatcher(true, "", new BusinessLunchCatalog(List.of()), MONDAY, LocalTime.of(17, 0)).run())
                .isEqualTo(LunchScoreboardDispatcher.Outcome.NO_STAFF_CHAT);
        verifyNoInteractions(marker, service, notifications);
    }

    @Test
    void aFailedBuildIsNotRetriedAndNothingIsSent() {
        LunchScoreboardDispatcher dispatcher = dispatcher(MONDAY, LocalTime.of(16, 45));
        when(marker.claim(MONDAY)).thenReturn(true, false);
        when(service.build(MONDAY)).thenThrow(new IllegalStateException("database is away"));

        assertThat(dispatcher.run()).isEqualTo(LunchScoreboardDispatcher.Outcome.FAILED);
        assertThat(dispatcher.run()).isEqualTo(LunchScoreboardDispatcher.Outcome.ALREADY_SENT);
        verify(notifications, never()).sendStaffNotice(anyString(), anyString());
    }

    @Test
    void aRedisThatCannotAnswerMeansNoSend() {
        LunchScoreboardDispatcher dispatcher = dispatcher(MONDAY, LocalTime.of(16, 45));
        when(marker.claim(MONDAY)).thenReturn(false);

        assertThat(dispatcher.run()).isEqualTo(LunchScoreboardDispatcher.Outcome.ALREADY_SENT);
        verifyNoInteractions(service, notifications);
    }

    private static BusinessLunchOffer offer(List<DayOfWeek> days) {
        return new BusinessLunchOffer("AERIS", "AERIS", true, days, LocalTime.of(12, 0), LocalTime.of(16, 0), 90, "",
                List.of(new BusinessLunchOffer.Course("SOUP", "Суп", List.of(new BusinessLunchOffer.Dish("BORSCH", "Борщ")))),
                List.of());
    }
}
