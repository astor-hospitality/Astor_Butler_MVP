package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.journey.GuestJourneyProperties;
import museon_online.astor_butler.domain.journey.GuestVisit;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.journey.JourneyStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VisitStatusToolTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T05:00:00Z"), ZoneOffset.UTC);
    private static final GuestVisit VISIT = new GuestVisit(7L, 42L, 501L, "+79120000060", "AERIS", JourneyStage.CONFIRMED,
            null, null, 0, Instant.parse("2026-10-14T14:00:00Z"), Instant.parse("2026-10-13T10:00:00Z"), null);

    private GuestVisitRepository visits;
    private TableReservationRepository orders;
    private VisitStatusTool tool;

    @BeforeEach
    void setUp() {
        visits = mock(GuestVisitRepository.class);
        orders = mock(TableReservationRepository.class);
        TableReservationOrder order = mock(TableReservationOrder.class);
        when(order.tableDisplayName()).thenReturn("Стол у окна");
        when(orders.findOrder(42L)).thenReturn(Optional.of(order));
        when(visits.findById(anyLong())).thenReturn(Optional.empty());
        when(visits.activeByChat(anyLong(), any())).thenReturn(Optional.empty());
        tool = new VisitStatusTool(visits, orders, new GuestJourneyProperties(), CLOCK);
    }

    @Test
    void visitIsFoundByIdFirst() {
        when(visits.findById(7L)).thenReturn(Optional.of(VISIT));

        CapabilityResult result = tool.invoke(context(501L, 7L), Map.of());

        assertThat(result.ok()).isTrue();
        assertThat(result.facts()).containsEntry("stage", "CONFIRMED").containsEntry("reservationId", 42L)
                .containsEntry("visitDay", "2026-10-14T19:00").containsEntry("table", "Стол у окна");
    }

    @Test
    void visitIsFoundByChatWithoutId() {
        when(visits.activeByChat(501L, CLOCK.instant())).thenReturn(Optional.of(VISIT));

        assertThat(tool.invoke(context(501L, null), Map.of()).facts()).containsEntry("stage", "CONFIRMED");
    }

    @Test
    void noVisitIsANullStage() {
        CapabilityResult result = tool.invoke(context(501L, null), Map.of());

        assertThat(result.ok()).isTrue();
        assertThat(result.facts()).containsEntry("stage", null);
    }

    @Test
    void anotherGuestsVisitIsNotAllowed() {
        when(visits.findById(7L)).thenReturn(Optional.of(VISIT));

        assertThat(tool.invoke(context(999L, 7L), Map.of()).error()).isEqualTo(CapabilityResult.NOT_ALLOWED);
    }

    private static CapabilityContext context(Long chatId, Long visitId) {
        return new CapabilityContext("CONCIERGE", chatId, null, visitId, null, null, null);
    }
}
