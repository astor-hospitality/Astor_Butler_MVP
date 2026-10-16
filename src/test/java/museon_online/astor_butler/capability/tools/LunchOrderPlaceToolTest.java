package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityProperties;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.capability.GuestAgreement;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.booking.TableReservationService;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.journey.GuestJourneyProperties;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
import museon_online.astor_butler.domain.lunch.BusinessLunchCatalog;
import museon_online.astor_butler.domain.lunch.BusinessLunchOffer;
import museon_online.astor_butler.domain.lunch.BusinessLunchOrder;
import museon_online.astor_butler.domain.lunch.BusinessLunchService;
import museon_online.astor_butler.domain.lunch.ExternalLunchOrderProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LunchOrderPlaceToolTest {

    /** 13:00 in Yekaterinburg on Tuesday 20.10.2026. */
    private static final Instant LUNCH = Instant.parse("2026-10-20T08:00:00Z");
    /** «Now»: 10:00 in Yekaterinburg on Friday 16.10.2026. */
    private static final Clock FRIDAY_MORNING = Clock.fixed(Instant.parse("2026-10-16T05:00:00Z"), ZoneOffset.UTC);
    private static final BusinessLunchOffer OFFER = new BusinessLunchOffer("AERIS", "AERIS", true,
            List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), LocalTime.of(12, 0), LocalTime.of(16, 0), 90, null,
            List.of(new BusinessLunchOffer.Course("SOUP", "Суп", List.of(new BusinessLunchOffer.Dish("BORSCHT", "Борщ", 270, "300 г"))),
                    new BusinessLunchOffer.Course("MAIN", "Горячее", List.of(new BusinessLunchOffer.Dish("PIKE", "Щука", 450, "200 г")))),
            List.of());

    private BusinessLunchService lunch;
    private BusinessLunchCatalog catalog;
    private TableReservationRepository orders;
    private LunchOrderPlaceTool tool;
    private TableReservationOrder reservation;

    @BeforeEach
    void setUp() {
        lunch = mock(BusinessLunchService.class);
        orders = mock(TableReservationRepository.class);
        catalog = mock(BusinessLunchCatalog.class);
        when(catalog.find(anyString())).thenReturn(Optional.of(OFFER));
        reservation = CapabilityFixtures.order(41L, CapabilityFixtures.BUTLER_CHAT, TableReservationStatus.CONFIRMED, LUNCH);
        when(orders.findOrder(41L)).thenReturn(Optional.of(reservation));
        tool = toolAt(FRIDAY_MORNING.instant());
    }

    private LunchOrderPlaceTool toolAt(Instant now) {
        return new LunchOrderPlaceTool(lunch, catalog, new CapabilityProperties(),
                new ReservationAccess(orders, mock(GuestVisitRepository.class), mock(VisitTurnRepository.class)),
                new GuestJourneyProperties(), Clock.fixed(now, ZoneOffset.UTC));
    }

    private static Map<String, Object> args(Object confirmed, List<String> dishes) {
        Map<String, Object> args = new HashMap<>();
        args.put("reservationId", 41);
        args.put("dishCodes", dishes);
        if (confirmed != null) {
            args.put("confirmedByGuest", confirmed);
        }
        return args;
    }

    @Test
    void withoutGuestConfirmationNothingIsPlaced() {
        CapabilityResult missing = tool.invoke(CapabilityFixtures.butler("k1"), args(null, List.of("BORSCHT")));
        CapabilityResult refused = tool.invoke(CapabilityFixtures.butler("k1"), args(false, List.of("BORSCHT")));
        CapabilityResult saidInWords = tool.invoke(CapabilityFixtures.butler("k1"), args("true", List.of("BORSCHT")));

        assertThat(missing.error()).isEqualTo(CapabilityResult.NOT_ALLOWED);
        assertThat(refused.error()).isEqualTo(CapabilityResult.NOT_ALLOWED);
        assertThat(saidInWords.error()).isEqualTo(CapabilityResult.NOT_ALLOWED);
        verifyNoInteractions(lunch);
    }

    @Test
    void confirmedByGuestIsNotEnoughWithoutTheGuestsOwnYes() {
        for (GuestAgreement agreement : java.util.Arrays.asList(null, GuestAgreement.NONE, GuestAgreement.of("Борщ и щуку", List.of()))) {
            CapabilityResult concierge = tool.invoke(CapabilityFixtures.concierge("k1", agreement), args(true, List.of("BORSCHT")));
            CapabilityResult butler = tool.invoke(CapabilityFixtures.butler("k1", agreement), args(true, List.of("BORSCHT")));

            for (CapabilityResult result : List.of(concierge, butler)) {
                assertThat(result.error()).isEqualTo(CapabilityResult.NOT_ALLOWED);
                assertThat(result.facts()).containsEntry("reason", GuestAgreement.NEEDS_GUEST_YES);
            }
        }
        verifyNoInteractions(lunch);
    }

    @Test
    void unknownDishesAreABadArgumentNamingThem() {
        CapabilityResult result = tool.invoke(CapabilityFixtures.butler("k1"), args(true, List.of("BORSCHT", "CAVIAR", "PIZZA")));

        assertThat(result.error()).isEqualTo(CapabilityResult.BAD_ARGUMENT);
        assertThat(result.facts()).containsEntry("unknownDishCodes", List.of("CAVIAR", "PIZZA"));
        verifyNoInteractions(lunch);
    }

    @Test
    void placesTheConfirmedOrderIntoTheReservation() {
        BusinessLunchOrder order = mock(BusinessLunchOrder.class);
        when(lunch.placeFor(eq(reservation), any())).thenReturn(new BusinessLunchService.Placement(reservation, false, order,
                new ExternalLunchOrderProvider.Result(true, "saby", "ACCEPTED", "S-1", "ok")));

        CapabilityResult result = tool.invoke(CapabilityFixtures.butler("k1"), args(true, List.of("BORSCHT", "PIKE")));

        ArgumentCaptor<BusinessLunchService.Request> request = ArgumentCaptor.forClass(BusinessLunchService.Request.class);
        verify(lunch).placeFor(eq(reservation), request.capture());
        assertThat(request.getValue().dishCodes()).containsExactly("BORSCHT", "PIKE");
        assertThat(request.getValue().date()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(request.getValue().time()).isEqualTo(LocalTime.of(13, 0));
        assertThat(result.ok()).isTrue();
        assertThat(result.facts()).containsEntry("orderId", 41L).containsEntry("totalRub", 720)
                .containsEntry("sabyStatus", "ACCEPTED");
    }

    @Test
    void aReservationThatNoLongerHoldsATableGetsNoOrder() {
        for (TableReservationStatus status : List.of(TableReservationStatus.CANCELLED, TableReservationStatus.REJECTED,
                TableReservationStatus.EXPIRED)) {
            when(orders.findOrder(41L)).thenReturn(Optional.of(CapabilityFixtures.order(41L, CapabilityFixtures.BUTLER_CHAT, status, LUNCH)));

            CapabilityResult result = tool.invoke(CapabilityFixtures.butler("k1"), args(true, List.of("BORSCHT")));

            assertThat(result.error()).isEqualTo(CapabilityResult.NOT_ALLOWED);
            assertThat(result.facts()).containsEntry("reason", "not_active");
        }
        verifyNoInteractions(lunch);
    }

    @Test
    void notALunchTimeIsNotAllowed() {
        when(orders.findOrder(41L)).thenReturn(Optional.of(CapabilityFixtures.order(41L, CapabilityFixtures.BUTLER_CHAT,
                TableReservationStatus.CONFIRMED, CapabilityFixtures.START)));

        CapabilityResult result = tool.invoke(CapabilityFixtures.butler("k1"), args(true, List.of("BORSCHT")));

        assertThat(result.error()).isEqualTo(CapabilityResult.NOT_ALLOWED);
        assertThat(result.facts()).containsEntry("reason", "OUTSIDE_LUNCH_HOURS");
        // The refusal carries the lunch window lunch_menu gives, so the agent can name the hours.
        assertThat(result.facts()).containsEntry("served", false).containsEntry("from", "12:00").containsEntry("to", "16:00")
                .containsEntry("days", List.of("MONDAY", "TUESDAY")).containsEntry("nextServedDate", "2026-10-20");
        verifyNoInteractions(lunch);
    }

    /** A table held for a Saturday gets no lunch, and the refusal names the nearest lunch day. */
    @Test
    void aSaturdayTableGetsNoLunchAndTheNextLunchDayIsNamed() {
        when(orders.findOrder(41L)).thenReturn(Optional.of(CapabilityFixtures.order(41L, CapabilityFixtures.BUTLER_CHAT,
                TableReservationStatus.CONFIRMED, Instant.parse("2026-10-24T08:00:00Z"))));

        CapabilityResult result = tool.invoke(CapabilityFixtures.butler("k1"), args(true, List.of("BORSCHT")));

        assertThat(result.error()).isEqualTo(CapabilityResult.NOT_ALLOWED);
        assertThat(result.facts()).containsEntry("reason", "NOT_A_LUNCH_DAY").containsEntry("served", false)
                .containsEntry("nextServedDate", "2026-10-26").containsEntry("nextServedDay", "понедельник, 26 октября");
        verifyNoInteractions(lunch);
    }

    @Test
    void aLunchAlreadyInThePastIsNotAllowed() {
        // The 13:00 visit ended at 14:30; and a 15:00 visit is still on at 16:10, but the lunch hours ended at 16:00.
        Instant visitOver = Instant.parse("2026-10-20T09:40:00Z");
        Instant lunchHoursOver = Instant.parse("2026-10-20T11:10:00Z");
        TableReservationOrder atThree = CapabilityFixtures.order(42L, CapabilityFixtures.BUTLER_CHAT, TableReservationStatus.CONFIRMED,
                Instant.parse("2026-10-20T10:00:00Z"));
        when(orders.findOrder(42L)).thenReturn(Optional.of(atThree));
        Map<String, Object> intoAtThree = args(true, List.of("BORSCHT"));
        intoAtThree.put("reservationId", 42);

        CapabilityResult afterTheVisit = toolAt(visitOver).invoke(CapabilityFixtures.butler("k1"), args(true, List.of("BORSCHT")));
        CapabilityResult afterTheLunchHours = toolAt(lunchHoursOver).invoke(CapabilityFixtures.butler("k2"), intoAtThree);
        CapabilityResult aDayLater = toolAt(LUNCH.plus(Duration.ofDays(1)))
                .invoke(CapabilityFixtures.butler("k3"), args(true, List.of("BORSCHT")));

        for (CapabilityResult result : List.of(afterTheVisit, afterTheLunchHours, aDayLater)) {
            assertThat(result.error()).isEqualTo(CapabilityResult.NOT_ALLOWED);
            assertThat(result.facts()).containsEntry("reason", "already_passed");
        }
        verifyNoInteractions(lunch);
    }

    @Test
    void aSeatedGuestMayStillOrderDuringTheVisit() {
        when(lunch.placeFor(eq(reservation), any())).thenReturn(new BusinessLunchService.Placement(reservation, false,
                mock(BusinessLunchOrder.class), ExternalLunchOrderProvider.Result.manualEntry()));

        // 13:30, half an hour into the 13:00 visit.
        CapabilityResult result = toolAt(LUNCH.plus(Duration.ofMinutes(30)))
                .invoke(CapabilityFixtures.butler("k1"), args(true, List.of("BORSCHT")));

        assertThat(result.ok()).isTrue();
        verify(lunch).placeFor(eq(reservation), any());
    }

    /** A Concierge turn: the guest said «Да» to the lunch summary; the position is Tuesday 13:00 for two. */
    private static CapabilityContext conciergeYes(Map<String, Object> position) {
        return new CapabilityContext(CapabilityContext.CONCIERGE, null, "c-live", 7L, "k1", "Михаил", "+79120000060",
                GuestAgreement.of("Да", List.of()), position);
    }

    private static final Map<String, Object> TUESDAY_LUNCH = Map.of("date", "2026-10-20", "time", "13:00", "partySize", 2);

    private ReservationCreateTool create;
    private TableReservationService reservations;
    private ReservationAccess access;

    private LunchOrderPlaceTool booking() {
        create = mock(ReservationCreateTool.class);
        reservations = mock(TableReservationService.class);
        access = mock(ReservationAccess.class);
        when(reservations.findOverlappingSessionReservation(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(access.check(any(), anyMap())).thenReturn(new ReservationAccess.Checked(null, CapabilityResult.error(CapabilityResult.NOT_ALLOWED)));
        when(access.check(any(), eq(41L))).thenReturn(new ReservationAccess.Checked(reservation, null));
        when(lunch.placeFor(eq(reservation), any())).thenReturn(new BusinessLunchService.Placement(reservation, false,
                mock(BusinessLunchOrder.class), new ExternalLunchOrderProvider.Result(true, "saby", "ACCEPTED", "S-1", "ok")));
        return new LunchOrderPlaceTool(lunch, catalog, new CapabilityProperties(), access, new GuestJourneyProperties(),
                FRIDAY_MORNING).withBooking(create, reservations);
    }

    /**
     * Live test 16.10: the guest said «Да» to the lunch summary, but no table was booked and the model ordered into
     * reservations 0 and 1 that did not exist, with codes like «COLSLAW». The yes now books the table, then orders.
     */
    @Test
    @SuppressWarnings("unchecked")
    void withoutABookingTheYesToTheLunchBooksTheTableThenOrders() {
        LunchOrderPlaceTool tool = booking();
        when(create.invoke(any(), anyMap())).thenReturn(CapabilityResult.ok(Map.of("reservationId", 41L, "status", "AWAITING_HOSTESS")));
        Map<String, Object> invented = args(true, List.of("BORSCH", "щука"));
        invented.put("reservationId", 1);

        CapabilityResult result = tool.invoke(conciergeYes(TUESDAY_LUNCH), invented);

        ArgumentCaptor<CapabilityContext> yes = ArgumentCaptor.forClass(CapabilityContext.class);
        ArgumentCaptor<Map<String, Object>> slot = ArgumentCaptor.forClass(Map.class);
        verify(create).invoke(yes.capture(), slot.capture());
        assertThat(yes.getValue().agreement().covers(new GuestAgreement.Table(LocalDate.of(2026, 10, 20), LocalTime.of(13, 0), 2)))
                .isTrue();
        assertThat(slot.getValue()).containsEntry("date", "2026-10-20").containsEntry("time", "13:00").containsEntry("partySize", 2);
        ArgumentCaptor<BusinessLunchService.Request> request = ArgumentCaptor.forClass(BusinessLunchService.Request.class);
        verify(lunch).placeFor(eq(reservation), request.capture());
        assertThat(request.getValue().dishCodes()).containsExactly("BORSCHT", "PIKE");
        assertThat(result.ok()).isTrue();
        assertThat(result.facts()).containsEntry("reservationId", 41L).containsEntry("reservationCreated", true)
                .containsEntry("status", "AWAITING_HOSTESS").containsEntry("dateLabel", "вт 20.10").containsEntry("time", "13:00");
    }

    @Test
    void theArgumentsSlotWinsOverThePosition() {
        LunchOrderPlaceTool tool = booking();
        when(create.invoke(any(), anyMap())).thenReturn(CapabilityResult.ok(Map.of("reservationId", 41L)));
        Map<String, Object> given = args(true, List.of("BORSCHT"));
        given.remove("reservationId");
        given.put("date", "2026-10-20");
        given.put("time", "13:00");
        given.put("partySize", 3);

        assertThat(tool.invoke(conciergeYes(Map.of()), given).ok()).isTrue();
        verify(create).invoke(any(), eq(Map.of("date", "2026-10-20", "time", "13:00", "partySize", 3)));
    }

    @Test
    void theGuestsOwnBookingAtThatTimeIsUsedNotASecondOne() {
        LunchOrderPlaceTool tool = booking();
        when(reservations.findOverlappingSessionReservation(eq("c-live"), eq(7L), any(), any(), any())).thenReturn(Optional.of(reservation));

        CapabilityResult result = tool.invoke(conciergeYes(TUESDAY_LUNCH), args(true, List.of("BORSCHT")));

        assertThat(result.ok()).isTrue();
        assertThat(result.facts()).containsEntry("reservationCreated", false);
        verify(create, never()).invoke(any(), anyMap());
    }

    @Test
    void aSlotOutsideLunchIsRefusedBeforeAnythingIsBooked() {
        LunchOrderPlaceTool tool = booking();

        CapabilityResult saturday = tool.invoke(conciergeYes(Map.of("date", "2026-10-24", "time", "13:00", "partySize", 2)),
                args(true, List.of("BORSCHT")));
        CapabilityResult noSlot = tool.invoke(conciergeYes(Map.of()), args(true, List.of("BORSCHT")));

        assertThat(saturday.error()).isEqualTo(CapabilityResult.NOT_ALLOWED);
        assertThat(saturday.facts()).containsEntry("reason", "NOT_A_LUNCH_DAY").containsEntry("nextServedDate", "2026-10-26");
        assertThat(noSlot.error()).isEqualTo(CapabilityResult.BAD_ARGUMENT);
        assertThat(noSlot.facts()).containsEntry("reason", "needs_date_time");
        verify(create, never()).invoke(any(), anyMap());
        verifyNoInteractions(lunch);
    }

    @Test
    void aDishCodeThatFitsNothingReturnsTheMenu() {
        CapabilityResult result = tool.invoke(CapabilityFixtures.butler("k1"), args(true, List.of("PIZZA")));

        assertThat(result.error()).isEqualTo(CapabilityResult.BAD_ARGUMENT);
        assertThat(result.facts()).containsEntry("unknownDishCodes", List.of("PIZZA"))
                .containsEntry("menu", List.of(Map.of("code", "BORSCHT", "title", "Борщ"), Map.of("code", "PIKE", "title", "Щука")));
    }
}
