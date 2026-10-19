package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.capability.GuestAgreement;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.booking.TableReservationService;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.journey.JourneyStage;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    void theGuestsOwnReservationIsCancelledAtTheGuestsRequest() {
        sessionHolds42();
        when(reservations.cancelByGuest(42L)).thenReturn(CapabilityFixtures.order(42L, null, TableReservationStatus.CANCELLED));

        CapabilityResult result = tool.invoke(CapabilityFixtures.concierge("k1",
                GuestAgreement.of("Отмените бронь, пожалуйста", List.of())), Map.of("reservationId", 42));

        assertThat(result.ok()).isTrue();
        assertThat(result.facts()).containsEntry("reservationId", 42L).containsEntry("status", "CANCELLED");
    }

    @Test
    void theModelAloneCannotCancel() {
        sessionHolds42();
        Map<String, GuestAgreement> agreements = new LinkedHashMap<>();
        agreements.put("no agreement (a REST call)", null);
        agreements.put("nothing said yet", GuestAgreement.NONE);
        agreements.put("a yes to something else", CapabilityFixtures.AGREED);
        agreements.put("a question about cancelling", GuestAgreement.of("А можно отменить?", List.of()));
        agreements.put("a yes to cancelling another booking", GuestAgreement.of("да", List.of(), List.of(7L), List.of()));

        agreements.forEach((name, agreement) -> {
            CapabilityResult result = tool.invoke(CapabilityFixtures.concierge("k1", agreement), Map.of("reservationId", 42));

            assertThat(result.error()).as(name).isEqualTo(CapabilityResult.NOT_ALLOWED);
            assertThat(result.facts()).as(name).isEqualTo(Map.of("reason", GuestAgreement.NEEDS_GUEST_YES,
                    "reservationId", 42L, "action", "cancel"));
        });
        verify(reservations, never()).cancelByGuest(any());
    }

    @Test
    void aYesToTheCancellationTheVenueOfferedCancelsIt() {
        sessionHolds42();
        when(reservations.cancelByGuest(42L)).thenReturn(CapabilityFixtures.order(42L, null, TableReservationStatus.CANCELLED));

        CapabilityResult result = tool.invoke(CapabilityFixtures.concierge("k1", GuestAgreement.of("Да", List.of(), List.of(42L), List.of())),
                Map.of("reservationId", 42));

        assertThat(result.ok()).isTrue();
        verify(reservations).cancelByGuest(42L);
    }

    private void sessionHolds42() {
        when(orders.findOrder(42L)).thenReturn(Optional.of(CapabilityFixtures.order(42L, null, TableReservationStatus.CONFIRMED)));
        when(visits.findByReservation(42L)).thenReturn(Optional.of(CapabilityFixtures.visit(8L, 42L, null, JourneyStage.CONFIRMED)));
        when(turns.existsLink(8L, CapabilityFixtures.SESSION)).thenReturn(true);
    }

    /** «Мои заявки» → «Отменить»: a booking of the Concierge's form, never linked to the session `app-r<id>`. */
    @Test
    void theConciergeCancelsItsFormBookingForTheSameGuest() {
        when(orders.findOrder(70L)).thenReturn(Optional.of(CapabilityFixtures.formOrder(70L, 777001L, TableReservationStatus.CONFIRMED)));
        when(reservations.cancelByGuest(70L)).thenReturn(CapabilityFixtures.formOrder(70L, 777001L, TableReservationStatus.CANCELLED));
        GuestAgreement asked = GuestAgreement.of("Отмените, пожалуйста, мою бронь C-12 на пт 16.10, 13:00.", List.of());

        CapabilityResult result = tool.invoke(CapabilityFixtures.concierge("k1", asked, 777001L), Map.of("reservationId", 70));

        assertThat(result.ok()).isTrue();
        assertThat(result.facts()).containsEntry("reservationId", 70L).containsEntry("status", "CANCELLED");
    }

    @Test
    void theConciergeCannotCancelAFormBookingOfAnotherGuestOrWithoutTheGuestsId() {
        when(orders.findOrder(70L)).thenReturn(Optional.of(CapabilityFixtures.formOrder(70L, 777001L, TableReservationStatus.CONFIRMED)));
        GuestAgreement asked = GuestAgreement.of("Отмените, пожалуйста, мою бронь C-12 на пт 16.10, 13:00.", List.of());

        CapabilityResult other = tool.invoke(CapabilityFixtures.concierge("k1", asked, 777002L), Map.of("reservationId", 70));
        CapabilityResult unnamed = tool.invoke(CapabilityFixtures.concierge("k2", asked), Map.of("reservationId", 70));

        assertThat(other.error()).isEqualTo(CapabilityResult.NOT_ALLOWED);
        assertThat(unnamed.error()).isEqualTo(CapabilityResult.NOT_ALLOWED);
        verify(reservations, never()).cancelByGuest(any());
    }

    @Test
    void anUnknownReservationIsNotFound() {
        CapabilityResult result = tool.invoke(CapabilityFixtures.butler("k1"), Map.of("reservationId", 404));

        assertThat(result.error()).isEqualTo(CapabilityResult.NOT_FOUND);
    }

    /** Live test 16.10: «отмените бронь» came with reservationId 1 for the guest's booking 9; the guest heard «нельзя». */
    @Test
    void anInventedIdMeansTheSessionsOnlyBooking() {
        TableReservationOrder nine = CapabilityFixtures.order(9L, null, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION);
        when(orders.findActiveOrdersOfSession(eq(CapabilityFixtures.SESSION), any(), any())).thenReturn(List.of(nine));
        when(reservations.cancelByGuest(9L)).thenReturn(CapabilityFixtures.order(9L, null, TableReservationStatus.CANCELLED));

        CapabilityResult result = tool.invoke(CapabilityFixtures.concierge("k1", GuestAgreement.of("отмените бронь", List.of())),
                Map.of("reservationId", 1));

        assertThat(result.ok()).isTrue();
        assertThat(result.facts()).containsEntry("reservationId", 9L).containsEntry("status", "CANCELLED");
    }

    @Test
    void withTwoBookingsAnInventedIdStaysRefused() {
        when(orders.findActiveOrdersOfSession(eq(CapabilityFixtures.SESSION), any(), any())).thenReturn(List.of(
                CapabilityFixtures.order(9L, null, TableReservationStatus.CONFIRMED),
                CapabilityFixtures.order(10L, null, TableReservationStatus.CONFIRMED)));

        CapabilityResult result = tool.invoke(CapabilityFixtures.concierge("k1", GuestAgreement.of("отмените бронь", List.of())),
                Map.of("reservationId", 1));

        assertThat(result.error()).isEqualTo(CapabilityResult.NOT_FOUND);
        verify(reservations, never()).cancelByGuest(any());
    }
}
