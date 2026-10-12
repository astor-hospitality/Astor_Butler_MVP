package museon_online.astor_butler.domain.lunch.scoreboard;

import museon_online.astor_butler.domain.billing.BillPayState;
import museon_online.astor_butler.domain.billing.GuestBill;
import museon_online.astor_butler.domain.billing.GuestBillKind;
import museon_online.astor_butler.domain.billing.GuestBillLine;
import museon_online.astor_butler.domain.billing.GuestBillRepository;
import museon_online.astor_butler.domain.billing.GuestBillStatus;
import museon_online.astor_butler.domain.billing.VisitReview;
import museon_online.astor_butler.domain.billing.VisitReviewRepository;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.lunch.BusinessLunchCatalog;
import museon_online.astor_butler.domain.lunch.BusinessLunchOffer;
import museon_online.astor_butler.domain.shift.ShiftBriefingProperties;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The join: lunch reservation of the day → its bill → the bill's lines and the bill's review. */
class LunchScoreboardServiceTest {

    private static final ZoneId VENUE = ZoneId.of("Asia/Yekaterinburg");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 12);

    private final TableReservationRepository reservations = mock(TableReservationRepository.class);
    private final GuestBillRepository bills = mock(GuestBillRepository.class);
    private final VisitReviewRepository reviews = mock(VisitReviewRepository.class);
    private final BusinessLunchCatalog catalog = new BusinessLunchCatalog(List.of(offer()));
    private final ShiftBriefingProperties venue = new ShiftBriefingProperties(true, "AERIS", "Asia/Yekaterinburg", false, false, false, true);

    private LunchScoreboardService service(boolean billingEnabled) {
        return new LunchScoreboardService(reservations, bills, reviews, catalog, venue, billingEnabled);
    }

    @Test
    void theDayIsReadInTheVenueZoneAndOnlyLiveLunchReservationsCount() {
        when(reservations.findOrdersStartingBetween(eq("AERIS"), any(), any(), anyInt())).thenReturn(List.of(
                lunch(1L, 2, TableReservationStatus.CONFIRMED, "Бизнес-ланч: 2 × «Сет 1». Борщ, Котлета."),
                lunch(2L, 3, TableReservationStatus.SEATED, "Бизнес-ланч: Борщ × 3. Пожелание гостя: без лука — согласовать с кухней."),
                lunch(3L, 4, TableReservationStatus.CANCELLED, "Бизнес-ланч: Борщ × 4."),
                lunch(4L, 1, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION, "Бизнес-ланч: Цезарь × 1. Пожелание гостя: соус отдельно — согласовать с кухней. В Saby внести вручную."),
                table(5L, 6, TableReservationStatus.CONFIRMED, "у окна, Пожелание гостя: торт — согласовать с кухней")));
        when(bills.findByReservation(eq(GuestBillKind.BUSINESS_LUNCH), anyLong())).thenReturn(Optional.empty());

        LunchScoreboard board = service(true).build(DAY);

        verify(reservations).findOrdersStartingBetween("AERIS",
                DAY.atStartOfDay(VENUE).toInstant(), DAY.plusDays(1).atStartOfDay(VENUE).toInstant(), LunchScoreboardService.LIMIT);
        assertThat(board.date()).isEqualTo(DAY);
        assertThat(board.lunches()).isEqualTo(3);
        assertThat(board.guests()).isEqualTo(6);
        assertThat(board.visits()).isZero();
        assertThat(board.wishes()).containsExactly("без лука", "соус отдельно");
        assertThat(board.billsAvailable()).isTrue();
        verify(bills, never()).findByReservation(eq(GuestBillKind.BUSINESS_LUNCH), eq(3L));
        verify(bills, never()).findByReservation(eq(GuestBillKind.BUSINESS_LUNCH), eq(5L));
    }

    @Test
    void dishesAreCountedFromTheBillLinesAndRatedThroughTheVisitsTheyWerePartOf() {
        when(reservations.findOrdersStartingBetween(eq("AERIS"), any(), any(), anyInt())).thenReturn(List.of(
                lunch(1L, 2, TableReservationStatus.SEATED, "Бизнес-ланч: 2 × «Сет 1»"),
                lunch(2L, 1, TableReservationStatus.SEATED, "Бизнес-ланч: Борщ × 1"),
                lunch(3L, 1, TableReservationStatus.SEATED, "Бизнес-ланч: Окрошка × 1"),
                lunch(4L, 1, TableReservationStatus.CONFIRMED, "Бизнес-ланч: Борщ × 1")));
        // A set order: the set line is priced, the dishes ride along as included; two guests, so two of each dish.
        bill(1L, 11L, GuestBillStatus.PAID, List.of(
                line(11L, 1, "SET1", "Сет 1", 2, false),
                line(11L, 2, "BORSCH", "Борщ", 2, true),
                line(11L, 3, "CUTLET", "Котлета", 2, true)));
        bill(2L, 12L, GuestBillStatus.PAID, List.of(line(12L, 1, "BORSCH", "Борщ", 1, false)));
        bill(3L, 13L, GuestBillStatus.ISSUED, List.of(line(13L, 1, "OKROSHKA", "Окрошка", 1, false)));
        bill(4L, 14L, GuestBillStatus.CANCELLED, List.of(line(14L, 1, "BORSCH", "Борщ", 1, false)));
        review(11L, 5, VisitReview.Thumb.UP);
        review(12L, 4, VisitReview.Thumb.UP);
        review(13L, 2, VisitReview.Thumb.DOWN);

        LunchScoreboard board = service(true).build(DAY);

        assertThat(board.lunches()).isEqualTo(4);
        assertThat(board.visits()).isEqualTo(3);
        assertThat(board.reviews()).isEqualTo(new LunchScoreboard.Reviews(3, 11.0 / 3, 1, 2, 1));
        assertThat(board.dishes()).containsExactly(
                new LunchScoreboard.Dish("BORSCH", "Борщ", 3, 2, 4.5),
                new LunchScoreboard.Dish("CUTLET", "Котлета", 2, 1, 5.0),
                new LunchScoreboard.Dish("OKROSHKA", "Окрошка", 1, 1, 2.0));
        assertThat(board.worst()).map(LunchScoreboard.Dish::code).contains("OKROSHKA");
        verify(bills, never()).findLines(14L);
    }

    @Test
    void aVisitWithoutStarsStillCountsItsDishesAndItsThumb() {
        when(reservations.findOrdersStartingBetween(eq("AERIS"), any(), any(), anyInt())).thenReturn(List.of(
                lunch(1L, 1, TableReservationStatus.SEATED, "Бизнес-ланч: Борщ × 1")));
        bill(1L, 11L, GuestBillStatus.ISSUED, List.of(line(11L, 1, "BORSCH", "Борщ", 1, false)));
        review(11L, null, VisitReview.Thumb.DOWN);

        LunchScoreboard board = service(true).build(DAY);

        assertThat(board.visits()).isEqualTo(1);
        assertThat(board.reviews()).isEqualTo(new LunchScoreboard.Reviews(0, 0, 0, 0, 1));
        assertThat(board.dishes()).containsExactly(new LunchScoreboard.Dish("BORSCH", "Борщ", 1, 0, 0));
    }

    @Test
    void aLineWithoutACodeIsADishByItsTitle() {
        when(reservations.findOrdersStartingBetween(eq("AERIS"), any(), any(), anyInt())).thenReturn(List.of(
                lunch(1L, 1, TableReservationStatus.SEATED, "Бизнес-ланч"),
                lunch(2L, 1, TableReservationStatus.SEATED, "Бизнес-ланч")));
        bill(1L, 11L, GuestBillStatus.ISSUED, List.of(line(11L, 1, null, "Суп дня", 1, false)));
        bill(2L, 12L, GuestBillStatus.ISSUED, List.of(line(12L, 1, null, "суп дня ", 2, false)));
        when(reviews.findByBill(anyLong())).thenReturn(Optional.empty());

        LunchScoreboard board = service(true).build(DAY);

        assertThat(board.dishes()).containsExactly(new LunchScoreboard.Dish(null, "Суп дня", 3, 0, 0));
    }

    @Test
    void withBillingOffNoBillIsLookedUpAndTheTableSaysSo() {
        when(reservations.findOrdersStartingBetween(eq("AERIS"), any(), any(), anyInt())).thenReturn(List.of(
                lunch(1L, 2, TableReservationStatus.CONFIRMED, "Бизнес-ланч: Борщ × 2")));

        LunchScoreboard board = service(false).build(DAY);

        assertThat(board.lunches()).isEqualTo(1);
        assertThat(board.billsAvailable()).isFalse();
        assertThat(board.dishes()).isEmpty();
        assertThat(board.reviews()).isEqualTo(LunchScoreboard.Reviews.NONE);
        verify(bills, never()).findByReservation(any(), anyLong());
    }

    @Test
    void theWishIsCutOutOfTheHostessComment() {
        assertThat(LunchScoreboardService.wishOf("Бизнес-ланч: Борщ × 1. Пожелание гостя: без лука — согласовать с кухней. В Saby внести вручную."))
                .contains("без лука");
        assertThat(LunchScoreboardService.wishOf("Пожелание гостя: лимонад кислее")).contains("лимонад кислее");
        assertThat(LunchScoreboardService.wishOf("Бизнес-ланч: Борщ × 1. Пожелание: побыстрее.")).isEmpty();
        assertThat(LunchScoreboardService.wishOf("Пожелание гостя:  — согласовать с кухней")).isEmpty();
        assertThat(LunchScoreboardService.wishOf(null)).isEmpty();
    }

    private void bill(long reservationId, long billId, GuestBillStatus status, List<GuestBillLine> lines) {
        GuestBill bill = new GuestBill(billId, 10L, null, "AERIS", GuestBillKind.BUSINESS_LUNCH, "TELEGRAM", reservationId,
                null, null, status, BillPayState.NOT_REPORTED, null, null, "RUB", "BUTLER", null, null, null,
                Instant.now(), Instant.now());
        when(bills.findByReservation(GuestBillKind.BUSINESS_LUNCH, reservationId)).thenReturn(Optional.of(bill));
        when(bills.findLines(billId)).thenReturn(lines);
    }

    private void review(long billId, Integer stars, VisitReview.Thumb thumb) {
        when(reviews.findByBill(billId)).thenReturn(Optional.of(new VisitReview(billId, billId, 10L, Instant.now(), stars, thumb,
                null, stars == null ? null : Instant.now(), Instant.now(), Instant.now())));
    }

    private static GuestBillLine line(long billId, int no, String code, String title, int quantity, boolean included) {
        return new GuestBillLine((long) no, billId, no, code, title, quantity, included ? null : 45_000L, included);
    }

    private static TableReservationOrder lunch(long id, int party, TableReservationStatus status, String comment) {
        return order(id, party, status, "Бизнес-ланч", comment);
    }

    private static TableReservationOrder table(long id, int party, TableReservationStatus status, String comment) {
        return order(id, party, status, "у окна", comment);
    }

    private static TableReservationOrder order(long id, int party, TableReservationStatus status, String seating, String comment) {
        Instant start = ZonedDateTime.of(DAY, LocalTime.of(13, 0), VENUE).toInstant();
        return new TableReservationOrder(id, 10L + id, null, null, 5L, "5", "Стол 5", "WINDOW", seating, status, "TELEGRAM",
                start, start.plusSeconds(5400), party, "Гость", "+79990000000", comment, null, null, null, null,
                start, start);
    }

    private static BusinessLunchOffer offer() {
        return new BusinessLunchOffer("AERIS", "AERIS", true,
                List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                LocalTime.of(12, 0), LocalTime.of(16, 0), 90, "",
                List.of(new BusinessLunchOffer.Course("SOUP", "Суп", List.of(new BusinessLunchOffer.Dish("BORSCH", "Борщ"))),
                        new BusinessLunchOffer.Course("MAIN", "Горячее", List.of(new BusinessLunchOffer.Dish("CUTLET", "Котлета")))),
                List.of(new BusinessLunchOffer.LunchSet("SET1", "Сет 1", 450, List.of(List.of("SOUP"), List.of("MAIN")))));
    }
}
