package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.capability.GuestAgreement;
import museon_online.astor_butler.domain.billing.BillActor;
import museon_online.astor_butler.domain.billing.GuestBillCancelService;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code bill_cancel}: only on the guest's own «отмените счёт»; a paid bill is refused with the till as the way. */
class BillCancelToolTest {

    private final GuestBillCancelService cancellations = mock(GuestBillCancelService.class);
    private BillCancelTool tool;

    @BeforeEach
    void setUp() {
        TableReservationRepository orders = mock(TableReservationRepository.class);
        when(orders.findOrder(41L)).thenReturn(Optional.of(CapabilityFixtures.order(41L, CapabilityFixtures.BUTLER_CHAT,
                TableReservationStatus.SEATED)));
        tool = new BillCancelTool(cancellations, new ReservationAccess(orders, mock(GuestVisitRepository.class), mock(VisitTurnRepository.class)));
    }

    @Test
    void theGuestsOwnRequestCancelsTheBill() {
        when(cancellations.cancelByReservation(eq(41L), eq(BillActor.GUEST), anyString()))
                .thenReturn(new GuestBillCancelService.Result(GuestBillCancelService.Outcome.CANCELLED, 9L));

        CapabilityResult result = tool.invoke(CapabilityFixtures.butler("k1", GuestAgreement.of("Отмените, пожалуйста, счёт", List.of())),
                Map.of("reservationId", 41));

        assertThat(result.ok()).isTrue();
        assertThat(result.facts()).containsEntry("billId", 9L).containsEntry("status", "CANCELLED").containsEntry("alreadyCancelled", false);
    }

    @Test
    void withoutTheGuestsRequestNothingIsCancelled() {
        for (String words : List.of("да", "нет", "счёт", "отменить счёт?", "не надо отменять счёт", "отмените бронь")) {
            CapabilityResult result = tool.invoke(CapabilityFixtures.butler("k1", GuestAgreement.of(words, List.of())), Map.of("reservationId", 41));
            assertThat(result.error()).as(words).isEqualTo(CapabilityResult.NOT_ALLOWED);
            assertThat(result.facts()).as(words).containsEntry("reason", GuestAgreement.NEEDS_GUEST_YES).containsEntry("action", "cancel_bill");
        }
        assertThat(tool.invoke(CapabilityFixtures.butler("k1", null), Map.of("reservationId", 41)).error()).isEqualTo(CapabilityResult.NOT_ALLOWED);
        verify(cancellations, never()).cancelByReservation(anyLong(), any(), any());
    }

    @Test
    void aPaidBillIsRefusedWithTheTillAsTheWay() {
        when(cancellations.cancelByReservation(eq(41L), eq(BillActor.GUEST), anyString()))
                .thenReturn(new GuestBillCancelService.Result(GuestBillCancelService.Outcome.PAID, 9L));

        CapabilityResult result = tool.invoke(CapabilityFixtures.butler("k1", GuestAgreement.of("отмените счет", List.of())),
                Map.of("reservationId", 41));

        assertThat(result.error()).isEqualTo(CapabilityResult.NOT_ALLOWED);
        assertThat(result.facts()).containsEntry("reason", "paid");
        assertThat((String) result.facts().get("guestLine")).contains("оплачен").contains("кассе");
    }

    @Test
    void noBillAndBillingOffAreSaidSo() {
        when(cancellations.cancelByReservation(eq(41L), eq(BillActor.GUEST), anyString())).thenReturn(
                new GuestBillCancelService.Result(GuestBillCancelService.Outcome.NO_BILL, null),
                new GuestBillCancelService.Result(GuestBillCancelService.Outcome.BILLING_OFF, null));
        GuestAgreement asked = GuestAgreement.of("отмените чек", List.of());

        assertThat(tool.invoke(CapabilityFixtures.butler("k1", asked), Map.of("reservationId", 41)).facts()).containsEntry("reason", "no_bill");
        assertThat(tool.invoke(CapabilityFixtures.butler("k1", asked), Map.of("reservationId", 41)).error()).isEqualTo(CapabilityResult.UNAVAILABLE);
    }
}
