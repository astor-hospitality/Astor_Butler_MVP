package museon_online.astor_butler.domain.billing;

import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.shift.ButlerPauseService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** While Butler is paused (#134) no guest is sent a payment page: the venue's system is not asked for links. */
class ButlerPauseSkipsPaymentLinksTest {

    @Test
    void paymentLinkTimerSkipsTheTickWhilePaused() {
        BillingProperties properties = new BillingProperties();
        properties.setEnabled(true);
        ExternalPaymentProvider provider = mock(ExternalPaymentProvider.class);
        when(provider.enabled()).thenReturn(true);
        GuestBillService bills = mock(GuestBillService.class);
        GuestBillPaymentPrompter prompter = new GuestBillPaymentPrompter(properties, new ReviewProperties(), bills,
                mock(TableReservationRepository.class), List.of(provider), mock(GuestBillNotifier.class), mock(VisitReviewService.class));
        ButlerPauseService pause = mock(ButlerPauseService.class);
        prompter.setButlerPause(pause);

        when(pause.paused()).thenReturn(true);
        assertThat(prompter.run()).isEqualTo(GuestBillPaymentPrompter.Report.NOTHING);
        verifyNoInteractions(bills);

        when(pause.paused()).thenReturn(false);
        when(bills.awaitingPaymentLinkAfter(anyLong(), anyInt())).thenReturn(List.of());
        prompter.run();
        verify(bills).awaitingPaymentLinkAfter(anyLong(), anyInt());
    }
}
