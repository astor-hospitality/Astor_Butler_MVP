package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.journey.talk.TableRequestRouter;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
import museon_online.astor_butler.domain.tip.TipOrder;
import museon_online.astor_butler.domain.tip.TipOrderCommand;
import museon_online.astor_butler.domain.tip.TipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TipCreateToolTest {

    private TipService tips;
    private TableRequestRouter router;
    private TipCreateTool tool;
    private TableReservationOrder order;

    @BeforeEach
    void setUp() {
        tips = mock(TipService.class);
        router = mock(TableRequestRouter.class);
        TableReservationRepository orders = mock(TableReservationRepository.class);
        order = CapabilityFixtures.order(41L, CapabilityFixtures.BUTLER_CHAT, TableReservationStatus.SEATED);
        when(orders.findOrder(41L)).thenReturn(Optional.of(order));
        tool = new TipCreateTool(tips, router, new ReservationAccess(orders, mock(GuestVisitRepository.class), mock(VisitTurnRepository.class)));
    }

    @Test
    void aDraftForTheWaiterOfTheTable() {
        when(router.waiterName(order)).thenReturn(Optional.of("Ольга"));
        TipOrder draft = mock(TipOrder.class);
        when(draft.id()).thenReturn(12L);
        when(tips.createDraftFor(eq(order), any())).thenReturn(draft);

        CapabilityResult result = tool.invoke(CapabilityFixtures.butler("k1"), Map.of("reservationId", 41, "amountRub", 500));

        ArgumentCaptor<TipOrderCommand> command = ArgumentCaptor.forClass(TipOrderCommand.class);
        verify(tips).createDraftFor(eq(order), command.capture());
        assertThat(command.getValue().amountMinor()).isEqualTo(50_000L);
        assertThat(command.getValue().staffDisplayName()).isEqualTo("Ольга");
        assertThat(command.getValue().chatId()).isEqualTo(CapabilityFixtures.BUTLER_CHAT);
        assertThat(result.facts()).containsEntry("draftId", 12L).containsEntry("waiter", "Ольга");
    }

    @Test
    void aConciergeBookingGetsADraftWithoutAChat() {
        TableReservationRepository orders = mock(TableReservationRepository.class);
        GuestVisitRepository visits = mock(GuestVisitRepository.class);
        VisitTurnRepository turns = mock(VisitTurnRepository.class);
        TableReservationOrder concierge = CapabilityFixtures.order(42L, null, TableReservationStatus.SEATED);
        when(orders.findOrder(42L)).thenReturn(Optional.of(concierge));
        when(visits.findByReservation(42L)).thenReturn(Optional.of(CapabilityFixtures.visit(8L, 42L, null,
                museon_online.astor_butler.domain.journey.JourneyStage.SEATED)));
        when(turns.existsLink(8L, CapabilityFixtures.SESSION)).thenReturn(true);
        when(router.waiterName(concierge)).thenReturn(Optional.of("Ольга"));
        TipOrder draft = mock(TipOrder.class);
        when(draft.id()).thenReturn(13L);
        when(tips.createDraftFor(eq(concierge), any())).thenReturn(draft);
        TipCreateTool conciergeTool = new TipCreateTool(tips, router, new ReservationAccess(orders, visits, turns));

        CapabilityResult result = conciergeTool.invoke(CapabilityFixtures.concierge("k1"), Map.of("reservationId", 42, "amountRub", 300));

        ArgumentCaptor<TipOrderCommand> command = ArgumentCaptor.forClass(TipOrderCommand.class);
        verify(tips).createDraftFor(eq(concierge), command.capture());
        assertThat(command.getValue().chatId()).isNull();
        assertThat(result.ok()).isTrue();
        assertThat(result.facts()).containsEntry("draftId", 13L).containsEntry("waiter", "Ольга");
    }

    @Test
    void noWaiterIsNotFoundWithAReason() {
        when(router.waiterName(order)).thenReturn(Optional.empty());

        CapabilityResult result = tool.invoke(CapabilityFixtures.butler("k1"), Map.of("reservationId", 41, "amountRub", 500));

        assertThat(result.error()).isEqualTo(CapabilityResult.NOT_FOUND);
        assertThat(result.facts()).containsEntry("reason", "no_waiter");
        verifyNoInteractions(tips);
    }

    @Test
    void theAmountIsAboveZeroAndAtMostTwentyThousand() {
        assertThat(tool.invoke(CapabilityFixtures.butler("k1"), Map.of("reservationId", 41, "amountRub", 0)).error())
                .isEqualTo(CapabilityResult.BAD_ARGUMENT);
        assertThat(tool.invoke(CapabilityFixtures.butler("k1"), Map.of("reservationId", 41, "amountRub", 20_001)).error())
                .isEqualTo(CapabilityResult.BAD_ARGUMENT);
        verifyNoInteractions(tips);
    }
}
