package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.booking.TableReservationService;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.journey.JourneyStage;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationCancelToolTest {

    private TableReservationService reservations;
    private TableReservationRepository orders;
    private GuestVisitRepository visits;
    private VisitTurnRepository turns;
    private ReservationCancelTool tool;

    @BeforeEach
    void setUp() {
        reservations = mock(TableReservationService.class);
        orders = mock(TableReservationRepository.class);
        visits = mock(GuestVisitRepository.class);
        turns = mock(VisitTurnRepository.class);
        when(orders.findOrder(anyLong())).thenReturn(Optional.empty());
        when(visits.findByReservation(anyLong())).thenReturn(Optional.empty());
        tool = new ReservationCancelTool(reservations, new ReservationAccess(orders, visits, turns));
    }

    /** Run 16.10: «нет, это тест» to an order summary sent the agent looking for a booking to cancel. */
    @Test
    void theModelIsToldToCancelOnlyOnADirectRequest() {
        assertThat(tool.description()).contains("только когда гость прямо просит её отменить",
                "«Нет» на перечень заказа или на предложение стола — не просьба об отмене");
    }

    @Test
    void someoneElsesReservationIsNotAllowed() {
        when(orders.findOrder(41L)).thenReturn(Optional.of(CapabilityFixtures.order(41L, 999L, TableReservationStatus.CONFIRMED)));
        when(orders.findOrder(42L)).thenReturn(Optional.of(CapabilityFixtures.order(42L, null, TableReservationStatus.CONFIRMED)));
        when(visits.findByReservation(42L)).thenReturn(Optional.of(CapabilityFixtures.visit(8L, 42L, null, JourneyStage.CONFIRMED)));
        when(turns.existsLink(8L, CapabilityFixtures.SESSION)).thenReturn(false);

        CapabilityResult otherChat = tool.invoke(CapabilityFixtures.butler("k1"), Map.of("reservationId", 41));
        CapabilityResult otherSession = tool.invoke(CapabilityFixtures.concierge("k2"), Map.of("reservationId", 42));
        CapabilityResult conciergeOnATelegramBooking = tool.invoke(CapabilityFixtures.concierge("k3"), Map.of("reservationId", 41));

        assertThat(otherChat.error()).isEqualTo(CapabilityResult.NOT_ALLOWED);
        assertThat(otherSession.error()).isEqualTo(CapabilityResult.NOT_ALLOWED);
        assertThat(conciergeOnATelegramBooking.error()).isEqualTo(CapabilityResult.NOT_ALLOWED);
        verify(reservations, never()).cancelByGuest(any());
    }

    @Test
    void theGuestsOwnReservationIsCancelled() {
        when(orders.findOrder(42L)).thenReturn(Optional.of(CapabilityFixtures.order(42L, null, TableReservationStatus.CONFIRMED)));
        when(visits.findByReservation(42L)).thenReturn(Optional.of(CapabilityFixtures.visit(8L, 42L, null, JourneyStage.CONFIRMED)));
        when(turns.existsLink(8L, CapabilityFixtures.SESSION)).thenReturn(true);
        when(reservations.cancelByGuest(42L)).thenReturn(CapabilityFixtures.order(42L, null, TableReservationStatus.CANCELLED));

        CapabilityResult result = tool.invoke(CapabilityFixtures.concierge("k1"), Map.of("reservationId", 42));

        assertThat(result.ok()).isTrue();
        assertThat(result.facts()).containsEntry("reservationId", 42L).containsEntry("status", "CANCELLED");
    }

    @Test
    void anUnknownReservationIsNotFound() {
        CapabilityResult result = tool.invoke(CapabilityFixtures.butler("k1"), Map.of("reservationId", 404));

        assertThat(result.error()).isEqualTo(CapabilityResult.NOT_FOUND);
    }
}
