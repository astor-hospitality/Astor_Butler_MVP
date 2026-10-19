package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.domain.billing.BillRequestOutcome;
import museon_online.astor_butler.domain.billing.GuestBillRequestService;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BillRequestToolTest {

    private GuestBillRequestService bills;
    private BillRequestTool tool;

    @BeforeEach
    void setUp() {
        bills = mock(GuestBillRequestService.class);
        TableReservationRepository orders = mock(TableReservationRepository.class);
        when(orders.findOrder(41L)).thenReturn(Optional.of(CapabilityFixtures.order(41L, CapabilityFixtures.BUTLER_CHAT,
                TableReservationStatus.SEATED)));
        tool = new BillRequestTool(bills, new ReservationAccess(orders, mock(GuestVisitRepository.class), mock(VisitTurnRepository.class)));
    }

    @Test
    void theBillWithoutAPaymentLinkHasNoUrl() {
        when(bills.requestByReservation(eq(41L), anyString())).thenReturn(
                new BillRequestOutcome(BillRequestOutcome.Status.STAFF_NOTIFIED, 41L, 9L, 540L, null));

        CapabilityResult result = tool.invoke(CapabilityFixtures.butler("k1"), Map.of("reservationId", 41));

        assertThat(result.ok()).isTrue();
        assertThat(result.facts()).containsEntry("billId", 9L).containsEntry("totalRub", 540L).doesNotContainKey("paymentUrl")
                .containsEntry("amountKnown", true).containsEntry("paymentLinkSent", false);
    }

    /** 16.10: the guest heard Butler's estimate as the bill; without the venue's sum there is no number at all. */
    @Test
    void withoutTheVenuesSumTheGuestHearsTheWaiterIsBringingTheBill() {
        when(bills.requestByReservation(eq(41L), anyString())).thenReturn(
                new BillRequestOutcome(BillRequestOutcome.Status.STAFF_NOTIFIED, 41L, 9L, null, null));

        CapabilityResult result = tool.invoke(CapabilityFixtures.butler("k1"), Map.of("reservationId", 41));

        assertThat(result.facts()).doesNotContainKey("totalRub").containsEntry("amountKnown", false)
                .containsEntry("guestLine", "Официант несёт счёт.");
        assertThat(tool.description()).contains("guestLine").contains("10 минут").contains("не называй");
    }

    @Test
    void aLinkAlreadySentWithItsButtonIsSaidSo() {
        when(bills.requestByReservation(eq(41L), anyString())).thenReturn(
                new BillRequestOutcome(BillRequestOutcome.Status.STAFF_NOTIFIED, 41L, 9L, 2510L, "https://pay.example/9", true));

        CapabilityResult result = tool.invoke(CapabilityFixtures.butler("k1"), Map.of("reservationId", 41));

        assertThat(result.facts()).containsEntry("paymentLinkSent", true).containsEntry("totalRub", 2510L);
        assertThat((String) result.facts().get("guestLine")).contains("2510 ₽").contains("Оплатить");
    }

    @Test
    void thePaymentLinkIsPassedOnWhenThereIsOne() {
        when(bills.requestByReservation(eq(41L), anyString())).thenReturn(
                new BillRequestOutcome(BillRequestOutcome.Status.ALREADY_SENT, 41L, 9L, 540L, "https://pay.example/9"));

        CapabilityResult result = tool.invoke(CapabilityFixtures.butler("k1"), Map.of("reservationId", 41));

        assertThat(result.facts()).containsEntry("paymentUrl", "https://pay.example/9");
    }

    @Test
    void noVisitTodayIsNotAllowedAndASwitchedOffServiceUnavailable() {
        when(bills.requestByReservation(eq(41L), anyString())).thenReturn(
                new BillRequestOutcome(BillRequestOutcome.Status.NO_VISIT, 41L, null, null, null),
                new BillRequestOutcome(BillRequestOutcome.Status.OFF, 41L, null, null, null));

        assertThat(tool.invoke(CapabilityFixtures.butler("k1"), Map.of("reservationId", 41)).error()).isEqualTo(CapabilityResult.NOT_ALLOWED);
        assertThat(tool.invoke(CapabilityFixtures.butler("k1"), Map.of("reservationId", 41)).error()).isEqualTo(CapabilityResult.UNAVAILABLE);
    }
}
