package museon_online.astor_butler.domain.shift;

import museon_online.astor_butler.domain.billing.*;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.lunch.BusinessLunchCatalog;
import museon_online.astor_butler.domain.lunch.BusinessLunchOffer;
import museon_online.astor_butler.telegram.adapter.TelegramAdminNotifier;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ShiftBriefingServiceTest {
    private static final ZoneId VENUE = ZoneId.of("Asia/Yekaterinburg");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 7);
    private final TableReservationRepository reservations = mock(TableReservationRepository.class);
    private final GuestBillRepository bills = mock(GuestBillRepository.class);
    private final VisitReviewRepository reviews = mock(VisitReviewRepository.class);
    private final BusinessLunchCatalog lunches = new BusinessLunchCatalog(List.of(offer()));
    private final ShiftBriefingFormatter formatter = new ShiftBriefingFormatter();

    private BusinessLunchOffer offer() {
        return new BusinessLunchOffer("AERIS", "AERIS", true,
                List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                LocalTime.of(12, 0), LocalTime.of(16, 0), 90, "суп и основное",
                List.of(), List.of(new BusinessLunchOffer.LunchSet("SET1", "Сет", 690, List.of())));
    }

    private ShiftBriefingProperties properties(boolean enabled, boolean on) {
        return new ShiftBriefingProperties(enabled, "AERIS", "Asia/Yekaterinburg", on, on, on, on);
    }

    private ShiftBriefingService service(ShiftBriefingProperties properties) {
        return new ShiftBriefingService(reservations, bills, reviews, lunches, properties);
    }

    private TableReservationOrder order(int hour, int minute, int party, TableReservationStatus status,
                                        String table, String guest, String comment) {
        Instant start = ZonedDateTime.of(DAY, LocalTime.of(hour, minute), VENUE).toInstant();
        return new TableReservationOrder(1L, 10L, null, null, 5L, "5", table, "WINDOW", null, status, "TELEGRAM",
                start, start.plusSeconds(5400), party, guest, "+79990000000", comment, null, null, null, null,
                start, start);
    }

    private GuestBill bill(GuestBillStatus status, Long estimate, Long venue) {
        return new GuestBill(1L, 10L, null, "AERIS", GuestBillKind.BUSINESS_LUNCH, "TELEGRAM", 1L, null, null,
                status, BillPayState.NOT_REPORTED, estimate, venue, "RUB", "BUTLER", null, null, null,
                Instant.now(), Instant.now());
    }

    private VisitReview review(Integer stars, VisitReview.Thumb thumb) {
        return new VisitReview(1L, 1L, 10L, Instant.now(), stars, thumb, null, Instant.now(), Instant.now(), Instant.now());
    }

    @Test void theMorningPlanReadsTheDayByTheHourAndMarksTheLunchBookings() {
        when(reservations.findOrdersStartingBetween(eq("AERIS"), any(), any(), anyInt())).thenReturn(List.of(
                order(12, 30, 2, TableReservationStatus.CONFIRMED, "Стол 5", "Ирина", "у окна"),
                order(14, 0, 4, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION, "Стол 7", "Пётр", null),
                order(19, 0, 2, TableReservationStatus.CONFIRMED, "Стол 1", "Антон", null),
                order(20, 0, 6, TableReservationStatus.CANCELLED, "Стол 9", "Отменён", null),
                order(21, 0, 2, TableReservationStatus.AWAITING_GUEST_SELECTION, "Стол 2", "Выбирает", null)));
        when(bills.findOpenedBetween(eq("AERIS"), any(), any(), anyInt())).thenReturn(List.of());
        when(reviews.findCreatedBetween(any(), any(), anyInt())).thenReturn(List.of());

        var briefing = service(properties(true, true)).morning(DAY);

        assertThat(briefing.slots()).extracting(ShiftBriefing.Slot::time).containsExactly("12:30", "14:00", "19:00");
        assertThat(briefing.guests()).isEqualTo(8);
        assertThat(briefing.awaitingConfirmation()).isEqualTo(1);
        assertThat(briefing.cancelled()).isEqualTo(1);
        assertThat(briefing.slots()).extracting(ShiftBriefing.Slot::businessLunch).containsExactly(true, true, false);
        assertThat(briefing.lunchBookings()).hasSize(2);
        assertThat(briefing.lunchOffer()).contains("12:00").contains("690");
        assertThat(briefing.warnings()).isEmpty();
    }

    @Test void theEveningLooksBackAtBillsAndWhatGuestsSaid() {
        when(reservations.findOrdersStartingBetween(eq("AERIS"), any(), any(), anyInt())).thenReturn(List.of(
                order(13, 0, 2, TableReservationStatus.CONFIRMED, "Стол 5", "Ирина", null)));
        when(bills.findOpenedBetween(eq("AERIS"), any(), any(), anyInt())).thenReturn(List.of(
                bill(GuestBillStatus.PAID, 1380L * 100, 1420L * 100),
                bill(GuestBillStatus.ISSUED, 690L * 100, null)));
        when(reviews.findCreatedBetween(any(), any(), anyInt())).thenReturn(List.of(
                review(5, VisitReview.Thumb.UP), review(4, null), review(null, VisitReview.Thumb.DOWN)));

        var briefing = service(properties(true, true)).evening(DAY);

        assertThat(briefing.bills().opened()).isEqualTo(2);
        assertThat(briefing.bills().paid()).isEqualTo(1);
        assertThat(briefing.bills().unpaid()).isEqualTo(1);
        assertThat(briefing.bills().venueMinor()).isEqualTo(142000);
        assertThat(briefing.reviews().answered()).isEqualTo(2);
        assertThat(briefing.reviews().averageStars()).isEqualTo(4.5);
        assertThat(briefing.reviews().thumbsUp()).isEqualTo(1);
        assertThat(briefing.reviews().thumbsDown()).isEqualTo(1);
    }

    @Test void switchesThatAreOffAreSaidPlainly() {
        var warnings = properties(true, false).warnings();
        assertThat(warnings).anySatisfy(w -> assertThat(w).contains("Presto не подключён"));
        assertThat(warnings).anySatisfy(w -> assertThat(w).contains("счета выключены"));
        assertThat(warnings).anySatisfy(w -> assertThat(w).contains("оплата выключена"));
    }

    @Test void theMessageCarriesNoGuestPhoneAndEscapesWhatGuestsTyped() {
        when(reservations.findOrdersStartingBetween(eq("AERIS"), any(), any(), anyInt())).thenReturn(List.of(
                order(12, 30, 2, TableReservationStatus.CONFIRMED, "Стол 5", "<b>Ирина</b>", "аллергия & <script>")));
        when(bills.findOpenedBetween(eq("AERIS"), any(), any(), anyInt())).thenReturn(List.of());
        when(reviews.findCreatedBetween(any(), any(), anyInt())).thenReturn(List.of());

        String message = formatter.format(service(properties(true, true)).morning(DAY));

        assertThat(message).doesNotContain("+79990000000");
        assertThat(message).doesNotContain("<script>").contains("&lt;script&gt;").contains("&amp;");
        assertThat(message).contains("12:30").contains("Стол 5").contains("2 гостя").contains("ланч");
    }

    @Test void nothingIsSentWhileTheBriefingIsOffOrTheDayWasEmpty() {
        var notifier = mock(TelegramAdminNotifier.class);
        var off = new ShiftBriefingScheduler(service(properties(false, true)), formatter, notifier, properties(false, true));
        off.send(ShiftBriefing.Kind.MORNING, DAY);
        verifyNoInteractions(notifier, reservations);

        when(reservations.findOrdersStartingBetween(eq("AERIS"), any(), any(), anyInt())).thenReturn(List.of());
        when(bills.findOpenedBetween(eq("AERIS"), any(), any(), anyInt())).thenReturn(List.of());
        when(reviews.findCreatedBetween(any(), any(), anyInt())).thenReturn(List.of());
        var on = new ShiftBriefingScheduler(service(properties(true, true)), formatter, notifier, properties(true, true));
        on.send(ShiftBriefing.Kind.EVENING, DAY);
        verifyNoInteractions(notifier);

        // An empty morning still goes out: "no bookings today" is information for the shift.
        on.send(ShiftBriefing.Kind.MORNING, DAY);
        verify(notifier).sendAnalytics(argThat(text -> text.contains("Броней на сегодня нет")));
    }

    @Test void aFailureIsLoggedRatherThanThrownAtTheScheduler() {
        var notifier = mock(TelegramAdminNotifier.class);
        when(reservations.findOrdersStartingBetween(eq("AERIS"), any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("database is away"));
        var scheduler = new ShiftBriefingScheduler(service(properties(true, true)), formatter, notifier, properties(true, true));
        scheduler.send(ShiftBriefing.Kind.MORNING, DAY);
        verifyNoInteractions(notifier);
    }
}
