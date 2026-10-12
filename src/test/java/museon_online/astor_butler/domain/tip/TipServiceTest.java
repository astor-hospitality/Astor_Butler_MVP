package museon_online.astor_butler.domain.tip;

import museon_online.astor_butler.api.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A guest who presses "Изменить размер чаевых" keeps the same thanks number: the draft's sum is rewritten in place. */
@ExtendWith(MockitoExtension.class)
class TipServiceTest {

    @Mock
    private TipRepository repository;

    @Test
    void rewritesTheSumOfADraftTheGuestHasNotConfirmedYet() {
        TipService service = new TipService(repository);
        when(repository.findOrder(55L)).thenReturn(Optional.of(order(TipOrderStatus.AWAITING_GUEST_CONFIRMATION, 24_000L)));
        when(repository.updateAmount(55L, 36_000L, "15% от 2400 ₽")).thenReturn(order(TipOrderStatus.AWAITING_GUEST_CONFIRMATION, 36_000L));

        TipOrder changed = service.changeDraftAmount(55L, 36_000L, "15% от 2400 ₽");

        assertThat(changed.id()).isEqualTo(55L);
        assertThat(changed.amountMinor()).isEqualTo(36_000L);
    }

    @Test
    void doesNotTouchADraftThatIsAlreadyConfirmedOrCancelled() {
        TipService service = new TipService(repository);
        when(repository.findOrder(55L)).thenReturn(Optional.of(order(TipOrderStatus.AWAITING_PAYMENT, 24_000L)));

        assertThatThrownBy(() -> service.changeDraftAmount(55L, 36_000L, "15%")).isInstanceOf(ApiException.class);
        verify(repository, never()).updateAmount(anyLong(), anyLong(), anyString());
    }

    @Test
    void aNewSumHasToBePositive() {
        TipService service = new TipService(repository);

        assertThatThrownBy(() -> service.changeDraftAmount(55L, 0L, "0")).isInstanceOf(ApiException.class);
        verify(repository, never()).findOrder(anyLong());
    }

    @Test
    void theStaffMarkAConfirmedTipPaidOnce() {
        TipService service = new TipService(repository);
        when(repository.findOrder(55L)).thenReturn(Optional.of(order(TipOrderStatus.AWAITING_PAYMENT, 24_000L)));
        when(repository.updateStatus(55L, TipOrderStatus.PAID)).thenReturn(order(TipOrderStatus.PAID, 24_000L));

        assertThat(service.markPaid(55L).status()).isEqualTo(TipOrderStatus.PAID);
    }

    @Test
    void aPaidTipStaysPaidAndAnUnconfirmedOneCannotBePaid() {
        TipService service = new TipService(repository);
        when(repository.findOrder(55L)).thenReturn(Optional.of(order(TipOrderStatus.PAID, 24_000L)));
        when(repository.findOrder(56L)).thenReturn(Optional.of(order(TipOrderStatus.AWAITING_GUEST_CONFIRMATION, 24_000L)));

        assertThat(service.markPaid(55L).status()).isEqualTo(TipOrderStatus.PAID);
        assertThatThrownBy(() -> service.markPaid(56L)).isInstanceOf(ApiException.class);
        verify(repository, never()).updateStatus(anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void theSbpLinkIsTheRecipientsAndIsKeptWithTheTip() {
        TipService service = new TipService(repository);
        when(repository.findStaff(1L)).thenReturn(Optional.of(staff(" https://qr.nspk.ru/team ")));
        when(repository.findStaff(2L)).thenReturn(Optional.of(staff(" ")));

        assertThat(service.sbpLinkOf(order(TipOrderStatus.AWAITING_PAYMENT, 24_000L))).contains("https://qr.nspk.ru/team");
        assertThat(service.sbpLinkOf(withStaff(2L))).isEmpty();
        assertThat(service.sbpLinkOf(withStaff(null))).isEmpty();

        service.attachSbpUrl(55L, "https://qr.nspk.ru/team");
        verify(repository).updateSbpUrl(55L, "https://qr.nspk.ru/team");
    }

    private static StaffProfile staff(String sbpLink) {
        return new StaffProfile(1L, "AERIS", "Команда AERIS", "VENUE_TEAM", null, null, sbpLink, true, "{}",
                Instant.parse("2026-10-06T10:00:00Z"), Instant.parse("2026-10-06T10:00:00Z"));
    }

    private static TipOrder withStaff(Long staffId) {
        return new TipOrder(55L, 1773317437L, 1773317437L, null, "AERIS", staffId, "Команда AERIS", TipOrderStatus.AWAITING_PAYMENT, "TELEGRAM",
                24_000L, "RUB", "Наталья", "10% от 2400 ₽", null, null,
                Instant.parse("2026-10-06T10:00:00Z"), Instant.parse("2026-10-06T10:00:00Z"));
    }

    private static TipOrder order(TipOrderStatus status, long amountMinor) {
        return new TipOrder(55L, 1773317437L, 1773317437L, null, "AERIS", 1L, "Команда AERIS", status, "TELEGRAM",
                amountMinor, "RUB", "Наталья", "10% от 2400 ₽", null, null,
                Instant.parse("2026-10-06T10:00:00Z"), Instant.parse("2026-10-06T10:00:00Z"));
    }
}
