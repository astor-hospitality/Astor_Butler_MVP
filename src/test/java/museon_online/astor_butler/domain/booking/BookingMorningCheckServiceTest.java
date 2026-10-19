package museon_online.astor_butler.domain.booking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Once the venue clock passes the send time, every Telegram guest with a live booking for today is asked once;
 * the three buttons reconfirm, hand over to the change dialogue, or hand over to the cancel dialogue.
 */
class BookingMorningCheckServiceTest {

    private static final ZoneId VENUE = ZoneId.of("Asia/Yekaterinburg");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 12);
    /** 10:30 at the venue (UTC+5). */
    private static final Instant SEND_AT = Instant.parse("2026-10-12T05:30:00Z");
    private static final long GUEST_A = 1001L;
    private static final long GUEST_B = 1002L;

    private final BookingMorningCheckProperties properties = new BookingMorningCheckProperties(true, "10:30", 90, "AERIS", "Asia/Yekaterinburg");
    private final TableReservationRepository reservations = mock(TableReservationRepository.class);
    private final BookingMorningCheckNotifier notifier = mock(BookingMorningCheckNotifier.class);
    private final MutableClock clock = new MutableClock(SEND_AT.minusSeconds(60));
    private final BookingMorningCheckRepository checks = new BookingMorningCheckRepository(BookingMorningCheckTestDatabase.create());
    private final BookingMorningCheckService service = new BookingMorningCheckService(properties, reservations, checks, notifier, clock);

    private final TableReservationOrder confirmedAtOne = order(41L, GUEST_A, 13, 0, TableReservationStatus.CONFIRMED, DAY.minusDays(1));
    private final TableReservationOrder awaitingAtTwo = order(42L, GUEST_B, 14, 0, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION, DAY.minusDays(1));

    @BeforeEach
    void todaysBookings() {
        when(notifier.ask(any(), any())).thenReturn(true);
        when(reservations.findOrdersStartingBetween(eq("AERIS"), any(), any(), anyInt())).thenReturn(List.of(
                confirmedAtOne,
                awaitingAtTwo,
                order(43L, 1003L, 11, 30, TableReservationStatus.CONFIRMED, DAY.minusDays(1)),   // starts within the lead time
                order(44L, 1004L, 19, 0, TableReservationStatus.CANCELLED, DAY.minusDays(1)),
                order(45L, 1005L, 20, 0, TableReservationStatus.AWAITING_GUEST_SELECTION, DAY.minusDays(1)),
                order(46L, 9_000_000_000_001L, 18, 0, TableReservationStatus.CONFIRMED, DAY.minusDays(1)), // web chat
                order(47L, 8_000_000_000_001L, 18, 0, TableReservationStatus.CONFIRMED, DAY.minusDays(1)), // MAX chat
                order(48L, 1008L, 18, 0, TableReservationStatus.CONFIRMED, SEND_AT.plusSeconds(15 * 60)),  // booked after 10:30
                order(49L, 1009L, 12, 0, TableReservationStatus.CONFIRMED, DAY.minusDays(1))     // exactly at the lead
        ));
    }

    @Test
    void asksTheRightGuestsOnceTheClockPassesTheSendTimeAndNeverTwice() {
        assertThat(service.run()).isEqualTo(BookingMorningCheckService.Report.NOTHING);
        verifyNoInteractions(notifier);

        clock.advance(Duration.ofSeconds(60));
        BookingMorningCheckService.Report report = service.run();

        assertThat(report.checked()).isEqualTo(9);
        assertThat(report.sent()).isEqualTo(3);
        assertThat(report.failed()).isZero();
        verify(notifier).ask(eq(confirmedAtOne), eq(LocalTime.of(13, 0)));
        verify(notifier).ask(eq(awaitingAtTwo), eq(LocalTime.of(14, 0)));
        verify(notifier, times(3)).ask(any(), any());
        assertThat(checks.findByVisitDate(DAY)).extracting(BookingMorningCheck::reservationId).containsExactly(41L, 42L, 49L);
        assertThat(checks.find(41L)).hasValueSatisfying(check -> {
            assertThat(check.chatId()).isEqualTo(GUEST_A);
            assertThat(check.sentAt()).isEqualTo(SEND_AT);
        });

        clock.advance(Duration.ofMinutes(5));
        BookingMorningCheckService.Report again = service.run();
        assertThat(again.sent()).isZero();
        verify(notifier, times(3)).ask(any(), any());
    }

    @Test
    void theDayIsReadInTheVenueTimeZone() {
        clock.advance(Duration.ofSeconds(60));
        service.run();

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(reservations).findOrdersStartingBetween(eq("AERIS"), from.capture(), to.capture(), eq(500));
        assertThat(from.getValue()).isEqualTo(Instant.parse("2026-10-11T19:00:00Z"));
        assertThat(to.getValue()).isEqualTo(Instant.parse("2026-10-12T19:00:00Z"));
    }

    @Test
    void aDeliveryThatFailedIsRecordedAndNotRepeated() {
        when(notifier.ask(eq(confirmedAtOne), any())).thenReturn(false);
        when(notifier.ask(eq(awaitingAtTwo), any())).thenThrow(new IllegalStateException("telegram is away"));
        clock.advance(Duration.ofSeconds(60));

        BookingMorningCheckService.Report report = service.run();

        assertThat(report.sent()).isEqualTo(1);
        assertThat(report.failed()).isEqualTo(2);
        assertThat(checks.find(41L)).hasValueSatisfying(check -> {
            assertThat(check.sent()).isFalse();
            assertThat(check.deliveryError()).isEqualTo("NOT_DELIVERED");
        });
        assertThat(checks.find(42L)).hasValueSatisfying(check -> assertThat(check.deliveryError()).isEqualTo("IllegalStateException"));

        service.run();
        verify(notifier, times(1)).ask(eq(confirmedAtOne), any());
        verify(notifier, times(1)).ask(eq(awaitingAtTwo), any());
    }

    @Test
    void nothingHappensWhileTheFeatureIsOff() {
        var off = new BookingMorningCheckService(
                new BookingMorningCheckProperties(false, "10:30", 90, "AERIS", "Asia/Yekaterinburg"), reservations, checks, notifier, clock);
        clock.advance(Duration.ofHours(3));

        assertThat(off.run()).isEqualTo(BookingMorningCheckService.Report.NOTHING);

        verifyNoInteractions(reservations, notifier);
    }

    @Test
    void keepStampsTheReconfirmationThanksTheGuestAndTellsTheHostessOnce() {
        asked();
        when(reservations.findOrder(41L)).thenReturn(Optional.of(confirmedAtOne));

        BookingMorningCheckService.CallbackResult result = service.handleCallback("morning_check:41:keep", GUEST_A);

        assertThat(result.handled()).isTrue();
        assertThat(result.guestText()).isNull();
        assertThat(result.answerText()).isEqualTo("Спасибо, ждём вас!");
        verify(notifier).thanks(confirmedAtOne, LocalTime.of(13, 0));
        verify(notifier).hostessReconfirmed(confirmedAtOne, LocalTime.of(13, 0));
        assertThat(checks.find(41L)).hasValueSatisfying(check -> {
            assertThat(check.answer()).isEqualTo("KEEP");
            assertThat(check.guestReconfirmedAt()).isEqualTo(clock.instant());
        });

        BookingMorningCheckService.CallbackResult again = service.handleCallback("morning_check:41:keep", GUEST_A);
        assertThat(again.handled()).isTrue();
        assertThat(again.answerText()).contains("уже отметили");
        verify(notifier, times(1)).hostessReconfirmed(any(), any());
        verify(notifier, times(1)).thanks(any(), any());
    }

    @Test
    void moveHandsOverToTheChangeDialogueWithTheWordsTheGuestCouldHaveTyped() {
        asked();
        when(reservations.findOrder(42L)).thenReturn(Optional.of(awaitingAtTwo));

        BookingMorningCheckService.CallbackResult result = service.handleCallback("morning_check:42:move", GUEST_B);

        assertThat(result.handled()).isTrue();
        assertThat(result.guestText()).isEqualTo("Перенести бронь");
        assertThat(checks.find(42L)).hasValueSatisfying(check -> {
            assertThat(check.answer()).isEqualTo("MOVE");
            assertThat(check.reconfirmed()).isFalse();
        });
        verify(notifier, never()).thanks(any(), any());
        verify(notifier, never()).hostessReconfirmed(any(), any());
    }

    @Test
    void cancelHandsOverToTheCancelDialogueAndCancelsNothingByItself() {
        asked();
        when(reservations.findOrder(41L)).thenReturn(Optional.of(confirmedAtOne));

        BookingMorningCheckService.CallbackResult result = service.handleCallback("morning_check:41:cancel", GUEST_A);

        assertThat(result.handled()).isTrue();
        assertThat(result.guestText()).isEqualTo("Отменить бронь");
        assertThat(checks.find(41L)).hasValueSatisfying(check -> assertThat(check.answer()).isEqualTo("CANCEL"));
        verify(reservations, never()).cancel(anyLong());
        verify(notifier, never()).hostessReconfirmed(any(), any());
    }

    @Test
    void onlyTheGuestWhoWasAskedCanAnswerAndOtherButtonsAreNotOurs() {
        asked();
        when(reservations.findOrder(41L)).thenReturn(Optional.of(confirmedAtOne));

        BookingMorningCheckService.CallbackResult stranger = service.handleCallback("morning_check:41:keep", 7777L);
        assertThat(stranger.handled()).isTrue();
        assertThat(stranger.guestText()).isNull();
        assertThat(stranger.answerText()).contains("не актуально");
        assertThat(checks.find(41L)).hasValueSatisfying(check -> assertThat(check.answer()).isNull());

        assertThat(service.handleCallback("morning_check:77:keep", GUEST_A).handled()).isTrue();
        assertThat(service.handleCallback("morning_check:77:keep", GUEST_A).guestText()).isNull();
        assertThat(service.handleCallback("visit_review:1:stars:5", GUEST_A)).isEqualTo(BookingMorningCheckService.CallbackResult.NOT_HANDLED);
        assertThat(service.handleCallback(null, GUEST_A)).isEqualTo(BookingMorningCheckService.CallbackResult.NOT_HANDLED);
        assertThat(service.handleCallback("morning_check:41:keep", null)).isEqualTo(BookingMorningCheckService.CallbackResult.NOT_HANDLED);
        verify(notifier, never()).thanks(any(), any());
    }

    @Test
    void aGuestWhoAlreadySatDownIsNotAskedAndAnOldButtonIsRefused() {
        TableReservationOrder seated = order(50L, 1010L, 13, 0, TableReservationStatus.SEATED, DAY.minusDays(1));
        when(reservations.findOrdersStartingBetween(eq("AERIS"), any(), any(), anyInt())).thenReturn(List.of(confirmedAtOne, seated));
        asked();

        verify(notifier).ask(eq(confirmedAtOne), any());
        verify(notifier, never()).ask(eq(seated), any());
        assertThat(checks.find(50L)).isEmpty();

        // The question went out while the booking was confirmed; the guest sat down before pressing «Всё в силе».
        when(reservations.findOrder(41L)).thenReturn(Optional.of(order(41L, GUEST_A, 13, 0, TableReservationStatus.SEATED, DAY.minusDays(1))));
        BookingMorningCheckService.CallbackResult result = service.handleCallback("morning_check:41:keep", GUEST_A);
        assertThat(result.handled()).isTrue();
        assertThat(result.answerText()).contains("уже не активна");
        verify(notifier, never()).hostessReconfirmed(any(), any());
    }

    @Test
    void anAnswerForABookingThatIsNoLongerActiveIsRefused() {
        asked();
        when(reservations.findOrder(41L)).thenReturn(Optional.of(order(41L, GUEST_A, 13, 0, TableReservationStatus.CANCELLED, DAY.minusDays(1))));

        BookingMorningCheckService.CallbackResult result = service.handleCallback("morning_check:41:move", GUEST_A);

        assertThat(result.handled()).isTrue();
        assertThat(result.guestText()).isNull();
        assertThat(result.answerText()).contains("уже не активна");
        assertThat(checks.find(41L)).hasValueSatisfying(check -> assertThat(check.answer()).isNull());
    }

    /** The morning question has gone out to today's guests. */
    @Test
    void aConciergeBookingIsAskedThroughTheConciergeOnce() {
        Instant start = ZonedDateTime.of(DAY, LocalTime.of(19, 0), VENUE).toInstant();
        Instant created = DAY.minusDays(1).atTime(12, 0).atZone(VENUE).toInstant();
        TableReservationOrder concierge = new TableReservationOrder(50L, null, null, null, 5L, "5", "Table 5", null, null,
                TableReservationStatus.CONFIRMED, "CONCIERGE", start, start.plusSeconds(7200), 2, "Мария", null, null, null, null,
                null, null, created, created);
        TableReservationOrder orphan = new TableReservationOrder(51L, null, null, null, 5L, "5", "Table 5", null, null,
                TableReservationStatus.CONFIRMED, "CONCIERGE", start, start.plusSeconds(7200), 2, "Олег", null, null, null, null,
                null, null, created, created);
        when(reservations.findOrdersStartingBetween(eq("AERIS"), any(), any(), anyInt())).thenReturn(List.of(concierge, orphan));
        museon_online.astor_butler.integration.concierge.ConciergeWebhook webhook =
                mock(museon_online.astor_butler.integration.concierge.ConciergeWebhook.class);
        when(webhook.reaches(50L)).thenReturn(true);
        when(webhook.announceNow("MORNING", concierge)).thenReturn(true);
        service.setConciergeWebhook(webhook);

        asked();
        clock.advance(Duration.ofMinutes(5));
        service.run();

        verify(webhook, times(1)).announceNow("MORNING", concierge);
        verify(webhook, never()).announceNow(eq("MORNING"), eq(orphan));
        verifyNoInteractions(notifier);
        assertThat(checks.find(50L)).hasValueSatisfying(check -> assertThat(check.sentAt()).isNotNull());
        assertThat(checks.find(51L)).isEmpty();
    }

    private void asked() {
        clock.advance(Duration.ofSeconds(60));
        service.run();
    }

    private static TableReservationOrder order(long id, long chatId, int hour, int minute, TableReservationStatus status, LocalDate createdOn) {
        return order(id, chatId, hour, minute, status, createdOn.atTime(12, 0).atZone(VENUE).toInstant());
    }

    private static TableReservationOrder order(long id, long chatId, int hour, int minute, TableReservationStatus status, Instant createdAt) {
        Instant start = ZonedDateTime.of(DAY, LocalTime.of(hour, minute), VENUE).toInstant();
        return new TableReservationOrder(id, chatId, null, null, 5L, "5", "Table 5", "WINDOW", null, status, "TELEGRAM",
                start, start.plusSeconds(7200), 2, "Ирина", "+79990000000", null, null, null, null, null, createdAt, createdAt);
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void journeyHearsTheMorningReconfirmation() {
        museon_online.astor_butler.domain.journey.JourneyEvents journey = org.mockito.Mockito.mock(museon_online.astor_butler.domain.journey.JourneyEvents.class);
        service.setJourneyEvents(journey);
        asked();
        when(reservations.findOrder(41L)).thenReturn(Optional.of(confirmedAtOne));

        service.handleCallback("morning_check:41:keep", GUEST_A);

        verify(journey).onReservation(confirmedAtOne, museon_online.astor_butler.domain.journey.VisitEventKind.MORNING_CONFIRMED, museon_online.astor_butler.domain.journey.JourneyEvents.GUEST);
    }
}
