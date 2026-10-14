package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.journey.JourneyStage;
import museon_online.astor_butler.domain.journey.talk.TableRequest;
import museon_online.astor_butler.domain.journey.talk.TableRequestRouter;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TableRequestToolTest {

    private TableRequestRouter router;
    private GuestVisitRepository visits;
    private TableRequestTool tool;
    private TableReservationOrder order;

    @BeforeEach
    void setUp() {
        router = mock(TableRequestRouter.class);
        visits = mock(GuestVisitRepository.class);
        TableReservationRepository orders = mock(TableReservationRepository.class);
        order = CapabilityFixtures.order(41L, CapabilityFixtures.BUTLER_CHAT, TableReservationStatus.SEATED);
        when(orders.findOrder(41L)).thenReturn(Optional.of(order));
        tool = new TableRequestTool(router, new ReservationAccess(orders, visits, mock(VisitTurnRepository.class)), visits);
    }

    @Test
    void onlyWhenSeated() {
        when(visits.findByReservation(41L)).thenReturn(Optional.of(CapabilityFixtures.visit(7L, 41L,
                CapabilityFixtures.BUTLER_CHAT, JourneyStage.CONFIRMED)));

        CapabilityResult result = tool.invoke(CapabilityFixtures.butler("k1"), Map.of("reservationId", 41, "request", "BREAD"));

        assertThat(result.error()).isEqualTo(CapabilityResult.NOT_ALLOWED);
        verify(router, never()).route(anyLong(), any(), anyString());
    }

    @Test
    void atTheTableTheWaiterIsCalled() {
        when(visits.findByReservation(41L)).thenReturn(Optional.of(CapabilityFixtures.visit(7L, 41L,
                CapabilityFixtures.BUTLER_CHAT, JourneyStage.SEATED)));
        when(router.route(41L, TableRequest.WATER, "воду")).thenReturn("Передал. Ольга скоро подойдёт.");
        when(router.waiterName(order)).thenReturn(Optional.of("Ольга"));

        CapabilityResult result = tool.invoke(CapabilityFixtures.butler("k1"), Map.of("reservationId", 41, "request", "WATER"));

        assertThat(result.ok()).isTrue();
        assertThat(result.facts()).containsEntry("routed", true).containsEntry("waiter", "Ольга");
    }

    @Test
    void anUnknownRequestIsABadArgument() {
        CapabilityResult result = tool.invoke(CapabilityFixtures.butler("k1"), Map.of("reservationId", 41, "request", "CAVIAR"));

        assertThat(result.error()).isEqualTo(CapabilityResult.BAD_ARGUMENT);
    }
}
