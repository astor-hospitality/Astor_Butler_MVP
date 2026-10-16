package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.capability.GuestAgreement;
import museon_online.astor_butler.domain.booking.TableReservationChangeCommand;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.booking.TableReservationService;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.journey.GuestJourneyProperties;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationChangeToolTest {

    private TableReservationService reservations;
    private ReservationChangeTool tool;

    @BeforeEach
    void setUp() {
        reservations = mock(TableReservationService.class);
        TableReservationRepository orders = mock(TableReservationRepository.class);
        when(orders.findOrder(41L)).thenReturn(Optional.of(CapabilityFixtures.order(41L, CapabilityFixtures.BUTLER_CHAT,
                TableReservationStatus.CONFIRMED)));
        when(reservations.changeByGuest(eq(41L), any())).thenReturn(CapabilityFixtures.order(41L, CapabilityFixtures.BUTLER_CHAT,
                TableReservationStatus.AWAITING_MANAGER_CONFIRMATION));
        tool = new ReservationChangeTool(reservations,
                new ReservationAccess(orders, mock(GuestVisitRepository.class), mock(VisitTurnRepository.class)),
                new GuestJourneyProperties());
    }

    @Test
    void withoutTheGuestsYesNothingChangesAndTheChangeIsNamed() {
        Map<String, GuestAgreement> agreements = new LinkedHashMap<>();
        agreements.put("no agreement (a REST call)", null);
        agreements.put("nothing said yet", GuestAgreement.NONE);
        agreements.put("a question", GuestAgreement.of("а на 20:30 можно?", List.of()));

        agreements.forEach((name, agreement) -> {
            CapabilityResult result = tool.invoke(CapabilityFixtures.butler("k1", agreement),
                    Map.of("reservationId", 41, "time", "20:30", "partySize", 3));

            assertThat(result.error()).as(name).isEqualTo(CapabilityResult.NOT_ALLOWED);
            assertThat(result.facts()).as(name).isEqualTo(Map.of("reason", GuestAgreement.NEEDS_GUEST_YES,
                    "reservationId", 41L, "action", "change", "time", "20:30", "partySize", 3));
        });
        verify(reservations, never()).changeByGuest(anyLong(), any());
    }

    @Test
    void movesTheTimeKeepingTheDayAndAwaitsTheHostess() {
        // CapabilityFixtures.butler carries the guest's «да».
        CapabilityResult result = tool.invoke(CapabilityFixtures.butler("k1"), Map.of("reservationId", 41, "time", "20:30"));

        ArgumentCaptor<TableReservationChangeCommand> command = ArgumentCaptor.forClass(TableReservationChangeCommand.class);
        verify(reservations).changeByGuest(eq(41L), command.capture());
        Instant start = Instant.parse("2026-10-20T15:30:00Z");
        assertThat(command.getValue().requestedStartAt()).isEqualTo(start);
        assertThat(command.getValue().requestedEndAt()).isEqualTo(start.plus(Duration.ofMinutes(90)));
        assertThat(command.getValue().partySize()).isNull();
        assertThat(result.facts()).containsEntry("reservationId", 41L).containsEntry("status", "AWAITING_HOSTESS");
    }
}
