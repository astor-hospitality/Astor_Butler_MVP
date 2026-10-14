package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.capability.CapabilityProperties;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
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

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LunchOrderPlaceToolTest {

    /** 13:00 in Yekaterinburg on Tuesday 20.10.2026. */
    private static final Instant LUNCH = Instant.parse("2026-10-20T08:00:00Z");
    private static final BusinessLunchOffer OFFER = new BusinessLunchOffer("AERIS", "AERIS", true,
            List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), LocalTime.of(12, 0), LocalTime.of(16, 0), 90, null,
            List.of(new BusinessLunchOffer.Course("SOUP", "Суп", List.of(new BusinessLunchOffer.Dish("BORSCHT", "Борщ", 270, "300 г"))),
                    new BusinessLunchOffer.Course("MAIN", "Горячее", List.of(new BusinessLunchOffer.Dish("PIKE", "Щука", 450, "200 г")))),
            List.of());

    private BusinessLunchService lunch;
    private TableReservationRepository orders;
    private LunchOrderPlaceTool tool;
    private TableReservationOrder reservation;

    @BeforeEach
    void setUp() {
        lunch = mock(BusinessLunchService.class);
        orders = mock(TableReservationRepository.class);
        BusinessLunchCatalog catalog = mock(BusinessLunchCatalog.class);
        when(catalog.find(anyString())).thenReturn(Optional.of(OFFER));
        reservation = CapabilityFixtures.order(41L, CapabilityFixtures.BUTLER_CHAT, TableReservationStatus.CONFIRMED, LUNCH);
        when(orders.findOrder(41L)).thenReturn(Optional.of(reservation));
        tool = new LunchOrderPlaceTool(lunch, catalog, new CapabilityProperties(),
                new ReservationAccess(orders, mock(GuestVisitRepository.class), mock(VisitTurnRepository.class)),
                new GuestJourneyProperties());
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
    void notALunchTimeIsNotAllowed() {
        when(orders.findOrder(41L)).thenReturn(Optional.of(CapabilityFixtures.order(41L, CapabilityFixtures.BUTLER_CHAT,
                TableReservationStatus.CONFIRMED, CapabilityFixtures.START)));

        CapabilityResult result = tool.invoke(CapabilityFixtures.butler("k1"), args(true, List.of("BORSCHT")));

        assertThat(result.error()).isEqualTo(CapabilityResult.NOT_ALLOWED);
        assertThat(result.facts()).containsEntry("reason", "OUTSIDE_LUNCH_HOURS");
        verifyNoInteractions(lunch);
    }
}
