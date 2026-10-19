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

    private void placesWith(ExternalLunchOrderProvider.Result external) {
        when(lunch.placeFor(eq(reservation), any())).thenReturn(new BusinessLunchService.Placement(reservation, false,
                mock(BusinessLunchOrder.class), external));
    }

    private static final ExternalLunchOrderProvider.Result SABY_OK =
            new ExternalLunchOrderProvider.Result(true, "saby", "SABY_LUNCH_ATTACHED", "S-1", "ok");

    /** Live test 16.10: «SALAD_HERRING_APPLE 2шт» ordered one portion, 2230 instead of 2510 ₽. */
    @Test
    void aCountWrittenWithTheDishOrdersThatManyPortions() {
        placesWith(SABY_OK);

        CapabilityResult result = tool.invoke(CapabilityFixtures.butler("k1"), args(true, List.of("BORSCHT 2шт", "PIKE")));

        ArgumentCaptor<BusinessLunchService.Request> request = ArgumentCaptor.forClass(BusinessLunchService.Request.class);
        verify(lunch).placeFor(eq(reservation), request.capture());
        assertThat(request.getValue().dishCodes()).containsExactly("BORSCHT", "BORSCHT", "PIKE");
        assertThat(result.facts()).containsEntry("totalRub", 990)
                .containsEntry("dishes", List.of(Map.of("title", "Борщ", "count", 2, "priceRub", 270),
                        Map.of("title", "Щука", "count", 1, "priceRub", 450)));
    }

    @Test
    void itemsWithCountsAreExpandedIntoPortionsAndTheTotalFollows() {
        placesWith(SABY_OK);
        Map<String, Object> given = args(true, List.of("PIKE"));
        given.put("items", List.of(Map.of("dish", "борщ", "count", 3)));

        CapabilityResult result = tool.invoke(CapabilityFixtures.butler("k1"), given);

        ArgumentCaptor<BusinessLunchService.Request> request = ArgumentCaptor.forClass(BusinessLunchService.Request.class);
        verify(lunch).placeFor(eq(reservation), request.capture());
        assertThat(request.getValue().dishCodes()).containsExactly("PIKE", "BORSCHT", "BORSCHT", "BORSCHT");
        assertThat(result.ok()).isTrue();
        assertThat(result.facts()).containsEntry("totalRub", 450 + 3 * 270);
    }

    @Test
    void onlyItemsAreEnoughWithoutDishCodes() {
        placesWith(SABY_OK);
        Map<String, Object> given = args(true, List.of());
        given.remove("dishCodes");
        given.put("items", List.of(Map.of("dish", "PIKE", "count", 2)));

        CapabilityResult result = tool.invoke(CapabilityFixtures.butler("k1"), given);

        assertThat(result.ok()).isTrue();
        assertThat(result.facts()).containsEntry("totalRub", 900);
    }

    @Test
    void aCountOutsideOneToTenIsABadArgument() {
        for (int count : List.of(0, 11, -2)) {
            Map<String, Object> given = args(true, List.of());
            given.put("items", List.of(Map.of("dish", "BORSCHT", "count", count)));

            CapabilityResult result = tool.invoke(CapabilityFixtures.butler("k1"), given);

            assertThat(result.error()).isEqualTo(CapabilityResult.BAD_ARGUMENT);
            assertThat(result.facts()).containsEntry("reason", LunchOrderPlaceTool.COUNT_OUT_OF_RANGE)
                    .containsEntry("maxCount", DishCodes.MAX_COUNT);
        }
        verifyNoInteractions(lunch);
    }

    /**
     * Live test 16.10: the guest with booking #68 today said «Да» to the lunch; the Concierge position was empty and the
     * model sent no reservationId, so the tool answered needs_date_time. The only booking is the one meant.
     */
    @Test
    void withoutIdOrSlotTheGuestsOnlyBookingIsUsed() {
        LunchOrderPlaceTool tool = booking();
        when(access.onlyUpcoming(any(), any(), any())).thenReturn(Optional.of(reservation));
        Map<String, Object> noId = args(true, List.of("BORSCHT"));
        noId.remove("reservationId");

        CapabilityResult result = tool.invoke(conciergeYes(Map.of()), noId);

        assertThat(result.ok()).isTrue();
        assertThat(result.facts()).containsEntry("reservationId", 41L).containsEntry("reservationCreated", false);
        verify(create, never()).invoke(any(), anyMap());
        verify(lunch).placeFor(eq(reservation), any());
    }

    /** The Butler's own chat, through the real access check: one booking not over yet, no booking path needed. */
    @Test
    void theButlerChatsOnlyBookingIsUsedWithoutTheBookingPath() {
        placesWith(SABY_OK);
        when(orders.findActiveOrdersByChatId(CapabilityFixtures.BUTLER_CHAT, "AERIS")).thenReturn(List.of(reservation));
        Map<String, Object> noId = args(true, List.of("BORSCHT"));
        noId.remove("reservationId");

        CapabilityResult result = tool.invoke(CapabilityFixtures.butler("k1"), noId);

        assertThat(result.ok()).isTrue();
        assertThat(result.facts()).containsEntry("reservationId", 41L);
    }

    @Test
    void twoBookingsOrABookingAlreadyOverAreNotGuessed() {
        TableReservationOrder over = CapabilityFixtures.order(40L, CapabilityFixtures.BUTLER_CHAT, TableReservationStatus.CONFIRMED,
                Instant.parse("2026-10-15T08:00:00Z"));
        TableReservationOrder other = CapabilityFixtures.order(42L, CapabilityFixtures.BUTLER_CHAT, TableReservationStatus.CONFIRMED,
                LUNCH.plus(Duration.ofDays(6)));
        ReservationAccess real = new ReservationAccess(orders, mock(GuestVisitRepository.class), mock(VisitTurnRepository.class));
        CapabilityContext ctx = CapabilityFixtures.butler("k1");

        when(orders.findActiveOrdersByChatId(CapabilityFixtures.BUTLER_CHAT, "AERIS")).thenReturn(List.of(reservation, other));
        assertThat(real.onlyUpcoming(ctx, "AERIS", FRIDAY_MORNING.instant())).isEmpty();
        when(orders.findActiveOrdersByChatId(CapabilityFixtures.BUTLER_CHAT, "AERIS")).thenReturn(List.of(over, reservation));
        assertThat(real.onlyUpcoming(ctx, "AERIS", FRIDAY_MORNING.instant())).contains(reservation);
    }

    /** Live test 16.10: «Хочу чизкейк с собой» about the cheesecake in the order made a second order. */
    @Test
    void packingADishAlreadyOrderedIsNotANewOrder() {
        placesWith(SABY_OK);
        assertThat(tool.invoke(CapabilityFixtures.butler("k1", GuestAgreement.of("Да", List.of())),
                args(true, List.of("BORSCHT", "PIKE"))).ok()).isTrue();
        GuestAgreement packIt = GuestAgreement.of("Да", List.of()).withTakeaway("Да", "Гость: упакуйте мой борщ");

        CapabilityResult result = tool.invoke(CapabilityFixtures.butler("k2", packIt), args(true, List.of("BORSCHT")));

        assertThat(result.error()).isEqualTo(CapabilityResult.NOT_ALLOWED);
        assertThat(result.facts()).containsEntry("reason", LunchOrderPlaceTool.ALREADY_ORDERED_TAKEAWAY)
                .containsEntry("ask", "Завернуть то, что в заказе, или добавить ещё одну порцию?");
        verify(lunch, org.mockito.Mockito.times(1)).placeFor(eq(reservation), any());
    }

    @Test
    void oneMorePortionToGoIsOrderedAfterTheGuestsYes() {
        placesWith(SABY_OK);
        tool.invoke(CapabilityFixtures.butler("k1", GuestAgreement.of("Да", List.of())), args(true, List.of("BORSCHT")));
        GuestAgreement more = GuestAgreement.of("Да, добавьте ещё одну", List.of())
                .withTakeaway("Да, добавьте ещё одну", "Гость: хочу борщ с собой");
        GuestAgreement newDish = GuestAgreement.of("Да", List.of()).withTakeaway("Да", "Гость: щуку с собой");

        assertThat(tool.invoke(CapabilityFixtures.butler("k2", more), args(true, List.of("BORSCHT"))).ok()).isTrue();
        assertThat(tool.invoke(CapabilityFixtures.butler("k3", newDish), args(true, List.of("PIKE"))).ok()).isTrue();
    }

    @Test
    void theBillsLinesCountAsAlreadyOrdered() {
        placesWith(SABY_OK);
        museon_online.astor_butler.domain.billing.GuestBillService bills = mock(museon_online.astor_butler.domain.billing.GuestBillService.class);
        museon_online.astor_butler.domain.billing.GuestBill bill = mock(museon_online.astor_butler.domain.billing.GuestBill.class);
        when(bill.id()).thenReturn(77L);
        when(bill.kind()).thenReturn(museon_online.astor_butler.domain.billing.GuestBillKind.BUSINESS_LUNCH);
        when(bills.findByReservation(41L)).thenReturn(Optional.of(bill));
        when(bills.linesOf(77L)).thenReturn(List.of(new museon_online.astor_butler.domain.billing.GuestBillLine(
                1L, 77L, 1, "PIKE", "Щука", 1, 45_000L, false)));
        tool.setBills(bills);
        GuestAgreement packIt = GuestAgreement.of("Да, заверните щуку", List.of());

        CapabilityResult result = tool.invoke(CapabilityFixtures.butler("k1", packIt), args(true, List.of("PIKE")));

        assertThat(result.facts()).containsEntry("reason", LunchOrderPlaceTool.ALREADY_ORDERED_TAKEAWAY);
        verifyNoInteractions(lunch);
    }

    /** Live test 16.10: Saby answered PROVIDER_RESULT_UNKNOWN and the guest read «оформлен». */
    @Test
    void aLunchSabyHasNotTakenSaysSoInItsFacts() {
        for (ExternalLunchOrderProvider.Result external : List.of(ExternalLunchOrderProvider.Result.manualEntry(),
                new ExternalLunchOrderProvider.Result(false, "saby", "PROVIDER_RESULT_UNKNOWN", "", "timeout"),
                ExternalLunchOrderProvider.Result.failed("saby", "HTTP_ERROR"))) {
            placesWith(external);

            CapabilityResult result = tool.invoke(CapabilityFixtures.butler("k1"), args(true, List.of("BORSCHT")));

            assertThat(result.ok()).isTrue();
            assertThat(result.facts()).containsEntry("kitchenConfirmed", false).containsEntry("manualEntry", true)
                    .containsEntry("tellGuest", "Заказ передал официанту, он внесёт его в кассу.");
        }
        placesWith(SABY_OK);
        CapabilityResult taken = tool.invoke(CapabilityFixtures.butler("k1"), args(true, List.of("BORSCHT")));
        assertThat(taken.facts()).containsEntry("kitchenConfirmed", true).containsEntry("manualEntry", false)
                .doesNotContainKey("tellGuest");
    }

    @Test
    void theDescriptionsTellTheModelHowToOrder() {
        String description = tool.description();
        assertThat(description).contains("так, как их назвал гость", "коды не спрашивай", "items",
                "manualEntry=true", "Заказ передал официанту, он внесёт его в кассу", "никогда не пиши «оформлен»",
                "Завернуть то, что в заказе, или добавить ещё одну порцию?", "table_request с WAITER",
                LunchOrderPlaceTool.ALREADY_ORDERED_TAKEAWAY);
        assertThat(tool.parameters()).extracting(map -> map.get("required")).isEqualTo(List.of("confirmedByGuest"));
    }
}
